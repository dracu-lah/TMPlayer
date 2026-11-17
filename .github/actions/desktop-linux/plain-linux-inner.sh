#!/usr/bin/env bash
# Runs inside the Ubuntu 22.04 container started by plain-linux-check.sh. Not meant to be run by hand.
set -uo pipefail
export DEBIAN_FRONTEND=noninteractive

# The gate is worthless on a newer system, so say which one it ran on and refuse any other.
glibc="$(ldd --version | head -1 | grep -oE '[0-9]+\.[0-9]+$')"
echo "== container: Ubuntu $(. /etc/os-release && echo "$VERSION_ID"), glibc $glibc"
if [ "$glibc" != "2.35" ]; then
  echo "FAIL the container has glibc $glibc, not 2.35: the check would prove nothing" >&2
  exit 1
fi

# What the app loads from the system on a stock Ubuntu desktop (the PKGBUILD's list; the JDK, TDLib
# and libmpv are inside the package), then the tools glibc-floor.sh and the unpacking need.
apt-get update -qq >/dev/null
apt-get install -y -qq --no-install-recommends \
  libasound2 libdrm2 libegl1 libgl1 libpulse0 libvulkan1 libx11-6 libxext6 libxi6 libxrender1 libxtst6 \
  libfontconfig1 libssl3 zlib1g \
  binutils file unzip xz-utils >/dev/null || { echo "FAIL apt could not install the libraries" >&2; exit 1; }

status=0
bad() { echo "FAIL $*" >&2; status=1; }

# The output must say TDLib and libmpv loaded, on top of the exit code.
selftest() {
  local label="$1"; shift
  local log; log="$(mktemp)"
  echo "-- $label: --self-test"
  timeout 600 "$@" --self-test >"$log" 2>&1
  local rc=$?
  sed 's/^/   /' "$log"
  [ "$rc" -eq 0 ] || bad "$label: --self-test exited $rc"
  grep -q '^PASS tdlib' "$log" || bad "$label: TDLib did not load"
  grep -q '^PASS libmpv' "$log" || bad "$label: libmpv did not load"
  grep -q '^SELF-TEST PASSED' "$log" || bad "$label: the self-test did not pass"
}

floor() {
  local label="$1" app="$2"
  echo "-- $label: glibc floor"
  bash /check/glibc-floor.sh "$app" || bad "$label: a native needs more than glibc 2.35 or libstdc++ 3.4.30"
}

n=0
for pkg in "$@"; do
  n=$((n + 1))
  w="/work/$n"; mkdir -p "$w"
  name="$(basename "$pkg")"
  echo "== $name"
  case "$name" in
    *.AppImage)
      cp "$pkg" "$w/image.AppImage"; chmod +x "$w/image.AppImage"
      (cd "$w" && ./image.AppImage --appimage-extract >/dev/null) || { bad "$name: --appimage-extract failed"; continue; }
      floor "$name" "$w/squashfs-root"
      # The image itself, as a user starts it, without FUSE (a container has none).
      export APPIMAGE_EXTRACT_AND_RUN=1
      selftest "$name" "$w/image.AppImage"
      unset APPIMAGE_EXTRACT_AND_RUN
      ;;
    *.tar.xz)
      tar -C "$w" -xJf "$pkg" || { bad "$name: could not unpack"; continue; }
      app="$(find "$w" -mindepth 1 -maxdepth 1 -type d | head -1)"
      floor "$name" "$app"
      selftest "$name" "$app/bin/TMPlayer"
      ;;
    *)
      floor "$name" "$pkg"
      selftest "$name" "$pkg/bin/TMPlayer"
      ;;
  esac
done

if [ "$status" -eq 0 ]; then echo "PLAIN LINUX CHECK PASSED (glibc 2.35)"; else echo "PLAIN LINUX CHECK FAILED" >&2; fi
exit "$status"
