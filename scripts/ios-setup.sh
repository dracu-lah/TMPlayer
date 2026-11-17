#!/bin/sh
# Checks what building the iOS and iPadOS app needs on this Mac and says what is missing
# (docs/IOS.md, "Setting up a Mac"). It changes nothing: it only looks and prints.
#
#   scripts/ios-setup.sh
#
# On Linux or Windows it says that a Mac is needed and what can be checked here instead.
# Exits 0 when everything is in place, 1 when something is missing, 2 when this is not a Mac.

set -u

root=$(cd "$(dirname "$0")/.." && pwd)
missing=0

ok() { printf '  ok       %s\n' "$1"; }
miss() { printf '  MISSING  %s\n           %s\n' "$1" "$2"; missing=1; }
note() { printf '  note     %s\n' "$1"; }

if [ "$(uname -s)" != "Darwin" ]; then
  cat <<'EOF'
The iOS and iPadOS app builds only on a Mac with Xcode (docs/IOS.md).

On this machine you can still check the parts that need no Xcode:
  python3 -I -m unittest discover -s iosApp/release      the release scripts and the identifiers
  ./gradlew -Ptmplayer.ios=true :core:appleSourcesForIos :ui:appleSourcesForIos \
      :ios:androidScreensForIos :ios:androidPlayerForIos  the iOS copies of the shared sources
EOF
  exit 2
fi

echo "TMPlayer for iPhone and iPad: checking this Mac"

# Apple silicon: the Kotlin framework is built for arm64 devices and arm64 simulators only.
if [ "$(uname -m)" = "arm64" ]; then
  ok "Apple silicon ($(uname -m))"
else
  miss "Apple silicon" "The Kotlin framework targets iosArm64 and iosSimulatorArm64; an Intel Mac cannot run its simulator build."
fi

# Xcode with the iOS 26.4 SDK or later (iosApp/project.yml's deployment target).
if xcodebuild -version >/dev/null 2>&1; then
  xcode=$(xcodebuild -version 2>/dev/null | awk 'NR == 1 { print $2 }')
  sdk=$(xcodebuild -showsdks 2>/dev/null | awk '/-sdk iphoneos/ { sub(/.*iphoneos/, ""); print; exit }')
  ok "Xcode $xcode (iOS SDK ${sdk:-unknown})"
  major=$(printf '%s' "${sdk:-0}" | cut -d. -f1)
  minor=$(printf '%s' "${sdk:-0}" | cut -d. -f2)
  if [ "${major:-0}" -lt 26 ] || { [ "${major:-0}" -eq 26 ] && [ "${minor:-0}" -lt 4 ]; }; then
    miss "iOS SDK 26.4 or later" "Install a newer Xcode from the App Store or developer.apple.com."
  fi
else
  miss "Xcode" "Install Xcode from the App Store, open it once, then run: sudo xcode-select -s /Applications/Xcode.app"
fi

# An iOS simulator runtime, for the simulator build and the tests.
if xcrun simctl list runtimes 2>/dev/null | grep -q 'iOS'; then
  ok "an iOS simulator runtime"
else
  miss "an iOS simulator runtime" "Xcode, Settings, Components: add the iOS platform (or: xcodebuild -downloadPlatform iOS)."
fi

# JDK 21 for Gradle (docs/BUILDING.md).
jdk=""
if [ -n "${JAVA_HOME:-}" ] && "$JAVA_HOME/bin/java" -version 2>&1 | grep -q 'version "21'; then
  jdk=$JAVA_HOME
elif /usr/libexec/java_home -F -v 21 >/dev/null 2>&1; then
  jdk=$(/usr/libexec/java_home -F -v 21)
elif [ -d /opt/homebrew/opt/openjdk@21 ]; then
  jdk=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi
if [ -n "$jdk" ]; then
  ok "JDK 21 ($jdk)"
else
  miss "JDK 21" "brew install openjdk@21 (Xcode's build phase finds it there), or any JDK 21 with JAVA_HOME set."
fi

# XcodeGen, only to change iosApp/project.yml: the generated project is committed.
if command -v xcodegen >/dev/null 2>&1; then
  ok "XcodeGen $(xcodegen --version 2>/dev/null | awk '{ print $NF }')"
else
  note "XcodeGen not found: only needed after changing iosApp/project.yml (brew install xcodegen)"
fi

# Telegram's api id and hash in local.properties, as for Android (docs/BUILDING.md).
props="$root/local.properties"
if [ -f "$props" ] && grep -Eq '^TG_API_ID=[0-9]{1,10}[[:space:]]*$' "$props" && grep -Eq '^TG_API_HASH=[0-9a-f]{32}[[:space:]]*$' "$props"; then
  ok "TG_API_ID and TG_API_HASH in local.properties"
else
  miss "TG_API_ID and TG_API_HASH in local.properties" "Get your own at https://my.telegram.org (API development tools). The app builds without them, but cannot sign in."
fi

# Disk: Kotlin/Native's toolchain, the Gradle caches and Xcode's DerivedData take room.
free_gb=$(df -g "$root" 2>/dev/null | awk 'NR == 2 { print $4 }')
if [ -n "${free_gb:-}" ] && [ "$free_gb" -lt 20 ]; then
  miss "20 GB free" "Only ${free_gb} GB free on this disk: Kotlin/Native, Gradle and DerivedData need about 20 GB."
elif [ -n "${free_gb:-}" ]; then
  ok "${free_gb} GB free"
fi

# Memory: the Release link of the Kotlin framework wants about 6 GB of heap.
mem_gb=$(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1073741824 ))
if [ "$mem_gb" -lt 8 ]; then
  miss "8 GB of memory" "This Mac reports ${mem_gb} GB; the Release link (iosApp/release/build-ipa.sh) needs about 6 GB of heap."
else
  ok "${mem_gb} GB of memory"
fi

echo
if [ "$missing" -eq 0 ]; then
  cat <<'EOF'
Everything is in place. Next (docs/IOS.md):
  open iosApp/TMPlayer.xcodeproj        then run the TMPlayer scheme on an iPhone or iPad simulator
  ./gradlew :core:iosSimulatorArm64Test :ui:iosSimulatorArm64Test :ios:iosSimulatorArm64Test
EOF
  exit 0
fi
echo "Something is missing: see above, then run scripts/ios-setup.sh again."
exit 1
