#!/usr/bin/env bash
# Checks that an APK is signed by TMPlayer's release key and is the version it claims to be, so an
# APK built outside the release workflow (scripts/release-local.sh) can never reach a release with
# another key, a debug key or the wrong versionCode. Installed copies refuse an update signed by any
# other key, so a wrong signer would strand every user who installs it.
#
#   scripts/verify-apk-signer.sh <apk> [<versionName> <versionCode>]
#
# What is checked
#   1. `apksigner verify` passes (every signature scheme the APK carries is valid).
#   2. Exactly one signer, whose certificate SHA-256 is the one in .github/release-signing-cert.sha256.
#      That file holds the public certificate's digest (the same one `apksigner verify --print-certs`
#      prints for any released APK), not anything secret. A key rotation changes it on purpose.
#   3. With a versionName and versionCode: the package is com.tmplayer at exactly that version
#      (`aapt2 dump badging`).
#
# apksigner and aapt2 come from the newest Android build-tools under ANDROID_HOME, ANDROID_SDK_ROOT,
# sdk.dir in local.properties or ~/Android/Sdk (APKSIGNER and AAPT2 override).
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
apk="${1:-}"
want_name="${2:-}"
want_code="${3:-}"
[ -n "$apk" ] || { echo "usage: $0 <apk> [<versionName> <versionCode>]" >&2; exit 2; }
[ -s "$apk" ] || { echo "$apk is missing or empty" >&2; exit 1; }

pin_file="$root/.github/release-signing-cert.sha256"
want_cert="$(grep -v '^#' "$pin_file" 2>/dev/null | tr -d ' \n' | tr 'A-F' 'a-f')"
case "$want_cert" in
  [0-9a-f]*) [ "${#want_cert}" -eq 64 ] || { echo "$pin_file does not hold one SHA-256 digest" >&2; exit 1; } ;;
  *) echo "$pin_file does not hold one SHA-256 digest" >&2; exit 1 ;;
esac

# find_tool <name>: the newest build-tools copy of an Android SDK tool, or nothing.
find_tool() {
  local name="$1" sdk
  local -a sdks=("${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}")
  [ -f "$root/local.properties" ] && sdks+=("$(sed -n 's/^sdk\.dir=//p' "$root/local.properties")")
  sdks+=("$HOME/Android/Sdk")
  for sdk in "${sdks[@]}"; do
    [ -n "$sdk" ] && [ -d "$sdk/build-tools" ] || continue
    find "$sdk/build-tools" -maxdepth 2 -name "$name" -type f 2>/dev/null | sort -rV | sed -n 1p | grep . && return 0
  done
  command -v "$name" 2>/dev/null || true
}

apksigner="${APKSIGNER:-$(find_tool apksigner)}"
[ -n "$apksigner" ] || { echo "apksigner not found: install Android build-tools or set APKSIGNER" >&2; exit 1; }

# The JVM's native access warnings go to stderr; only stdout is read.
certs="$("$apksigner" verify --print-certs "$apk" 2>/dev/null)" || {
  echo "apksigner verify failed for $apk: it is not validly signed" >&2
  exit 1
}
signers="$(grep -c '^Signer #[0-9]* certificate SHA-256 digest:' <<<"$certs" || true)"
if [ "$signers" != "1" ]; then
  echo "$apk has $signers signers, expected exactly one" >&2
  exit 1
fi
got_cert="$(sed -n 's/^Signer #1 certificate SHA-256 digest: *//p' <<<"$certs" | tr -d ' :' | tr 'A-F' 'a-f')"
if [ "$got_cert" != "$want_cert" ]; then
  echo "$apk is signed by certificate $got_cert, not the release key $want_cert ($pin_file)" >&2
  exit 1
fi
echo "Signed by the release key ($got_cert)"

if [ -n "$want_name" ]; then
  aapt2="${AAPT2:-$(find_tool aapt2)}"
  [ -n "$aapt2" ] || { echo "aapt2 not found: install Android build-tools or set AAPT2" >&2; exit 1; }
  package="$("$aapt2" dump badging "$apk" 2>/dev/null | sed -n '1{/^package:/p}')"
  name="$(sed -n "s/.* name='\([^']*\)'.*/\1/p" <<<"$package")"
  code="$(sed -n "s/.* versionCode='\([^']*\)'.*/\1/p" <<<"$package")"
  version="$(sed -n "s/.* versionName='\([^']*\)'.*/\1/p" <<<"$package")"
  if [ "$name" != "com.tmplayer" ] || [ "$version" != "$want_name" ] || [ "$code" != "$want_code" ]; then
    echo "$apk is $name $version ($code), expected com.tmplayer $want_name ($want_code)" >&2
    exit 1
  fi
  echo "Package com.tmplayer $version ($code)"
fi
