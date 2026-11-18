#!/bin/bash
# Builds the iOS and iPadOS release IPA: the Release configuration, unsigned (AltStore, SideStore
# and Sideloadly sign it with the user's own Apple ID), stripped, with its dSYMs beside it.
#
#   iosApp/release/build-ipa.sh <version> <build> <output folder>
#
# The release workflow runs it, and so can a Mac with 8 GB: the Kotlin/Native Release link needs
# about 6 GB of heap for its whole-program analysis, so it runs alone, after the libraries are
# compiled and every Gradle and Kotlin daemon is stopped, in a compiler process of its own with
# the G1 collector, which can give one huge array nearly the whole heap (the parallel collector
# keeps it to the old generation, and the link ran out of memory at 5.5 GB). Measured 2026-11-13
# on an 8 GB Mac with other apps open: 18 minutes, the system swapping up to 9 GB.
# TM_KOTLIN_HEAP sets that heap (6000m when unset); a Mac with 16 GB can give more.
# TM_MIN_IOS (empty when unset) replaces project.yml's deployment target for a lower-floor trial.
set -euo pipefail

if [ $# -ne 3 ]; then
  echo "usage: $0 <version> <build> <output folder>" >&2
  exit 2
fi
version=$1
build=$2
out=$3
heap=${TM_KOTLIN_HEAP:-6000m}
# A test of a lower minimum iOS (docs/IOS.md, "Lowering the minimum iOS"): TM_MIN_IOS=17.0 builds
# with that deployment target instead of project.yml's. Such an IPA is a trial, never released.
min_ios=${TM_MIN_IOS:-}
if [ -n "$min_ios" ] && ! [[ "$min_ios" =~ ^[0-9]+(\.[0-9]+){0,2}$ ]]; then
  echo "TM_MIN_IOS must look like 17 or 17.0, not $min_ios" >&2
  exit 2
fi
root=$(cd "$(dirname "$0")/../.." && pwd)
work=${TMPDIR:-/tmp}/tmplayer-release
mkdir -p "$out" "$work"
out=$(cd "$out" && pwd)
cd "$root"

name="TMPlayer-ios-$version"
# A test version's build is 0.<n> (below the release's build 1): its file is 1.28.6-beta.<n>.
if [[ "$build" =~ ^0\.([1-9][0-9]*)$ ]]; then
  name="$name-beta.${BASH_REMATCH[1]}"
elif [ "$build" != 1 ]; then
  name="$name-$build"
fi

stop_daemons() {
  ./gradlew --stop > /dev/null 2>&1 || true
  pkill -f KotlinCompileDaemon || true
}

# The daemon settings for the link and for Xcode's own Gradle step. Xcode passes GRADLE_OPTS on to
# its script phase, so a link it found out of date would also run this way. The compiler process
# gets the JDK's default collector, G1.
lean="-Dorg.gradle.jvmargs=-Xmx1280m -Dfile.encoding=UTF-8"
lean="$lean -Dorg.gradle.project.kotlin.native.disableCompilerDaemon=true"
lean="$lean -Dorg.gradle.project.kotlin.native.jvmArgs=-Xmx$heap"
lean="$lean -Dorg.gradle.project.kotlin.compiler.execution.strategy=in-process"

echo "== The Kotlin libraries for iPhone"
./gradlew :ios:kit:compileKotlinIosArm64 --console=plain
stop_daemons

echo "== The Kotlin framework, Release ($heap of heap)"
GRADLE_OPTS="$lean" ./gradlew :ios:kit:linkReleaseFrameworkIosArm64 --console=plain
stop_daemons

echo "== The app, archived"
archive="$work/TMPlayer.xcarchive"
rm -rf "$archive"
# macOS empties files in TMPDIR that nobody opened for some days but keeps their folders, and
# Xcode then takes an MPVKit framework folder with nothing in it as downloaded (2026-11-16: "no
# Info.plist found" for every one). Such a cache is dropped, so Xcode fetches the packages again.
packages="$work/derived/SourcePackages"
if [ -d "$packages/artifacts" ]; then
  for framework in "$packages"/artifacts/*/*/*.xcframework; do
    if [ -d "$framework" ] && [ ! -f "$framework/Info.plist" ]; then
      echo "The package cache lost $(basename "$framework")'s files: fetching the packages again"
      rm -rf "$packages"
      break
    fi
  done
fi
GRADLE_OPTS="$lean" xcodebuild archive -project iosApp/TMPlayer.xcodeproj -scheme TMPlayer \
  -configuration Release -destination 'generic/platform=iOS' -archivePath "$archive" \
  -derivedDataPath "$work/derived" \
  MARKETING_VERSION="$version" CURRENT_PROJECT_VERSION="$build" \
  ${min_ios:+IPHONEOS_DEPLOYMENT_TARGET="$min_ios"} \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY=""
stop_daemons

app="$archive/Products/Applications/TMPlayer.app"
test -d "$app"
said=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$app/Info.plist")
said_build=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$app/Info.plist")
if [ "$said" != "$version" ] || [ "$said_build" != "$build" ]; then
  echo "The app says $said ($said_build), not $version ($build)" >&2
  exit 1
fi

echo "== The IPA"
rm -rf "$work/ipa"
mkdir -p "$work/ipa/Payload"
cp -R "$app" "$work/ipa/Payload/"
rm -f "$out/$name.ipa" "$out/$name-dSYMs.zip"
(cd "$work/ipa" && zip -qry -X "$out/$name.ipa" Payload)
(cd "$archive" && zip -qry -X "$out/$name-dSYMs.zip" dSYMs)
(cd "$out" && shasum -a 256 "$name.ipa" > SHA256SUMS.txt && cat SHA256SUMS.txt)
ls -l "$out/$name.ipa" "$out/$name-dSYMs.zip"
