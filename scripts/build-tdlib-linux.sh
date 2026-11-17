#!/usr/bin/env bash
# Builds TDLib's JNI library, libtdjsonjava.so, for the desktop Linux build so that it runs on
# glibc 2.35 (Ubuntu 22.04, Pop!_OS 22.04, Mint 21; issue #30).
#
# The library that comes inside tdl-coroutines-jvm-15.0.0.jar (linux/x64/libtdjsonjava.so) was
# built on Ubuntu 24.04 with GCC 13: it asks for glibc 2.38 through one symbol (__isoc23_strtol,
# the C23 alias the 2.38 headers pick) and for GLIBCXX_3.4.32 / CXXABI_1.3.9 from the system's
# libstdc++. Ubuntu 22.04 has glibc 2.35 and GLIBCXX up to 3.4.30, so sign in never starts there.
#
# This script builds the same library from the same source on an Ubuntu 22.04 base, with the C++
# runtime linked in statically:
#   - Source: tdl-coroutines 15.0.0 pins its `td` submodule to g000sha256/TDLib at the commit below
#     (TDLib 1.8.67). The jar's library carries the same commit hash and version string, so the
#     TL schema the Kotlin API was generated from is exactly the one built here. The archive is
#     fetched from GitHub's permanent address for that commit and checked against its SHA-256.
#   - Steps: the ones tdl-coroutines' "Build JVM Linux x64" job runs (see
#     natives/tdlib-linux/build-in-container.sh for the two deliberate differences).
#   - Toolchain: natives/tdlib-linux/Containerfile, a digest-pinned ubuntu:22.04 with packages from
#     a dated Ubuntu snapshot.
#   - Dependencies: like the jar's library it links OpenSSL 3 (libssl.so.3, libcrypto.so.3) and zlib
#     (libz.so.1) dynamically, so the system's security updates still apply.
#
# The result goes outside the repository, to ~/.cache/tmplayer-natives/tdlib/libtdjsonjava.so
# (override with TMPLAYER_NATIVES), then check-floor.sh and the smoke test run on it. The download
# and the container images are kept, so a second run only rebuilds.
#
# Usage: scripts/build-tdlib-linux.sh [--no-smoke]
#   JOBS=n   parallel compile jobs, default 4, at most 6 (the machine is shared; each TDLib job can
#            take 2 to 3 GB of memory)
# Needs podman (rootless is fine). Took 14 minutes on 6 jobs (12 threads, 2026-11-18).
set -euo pipefail

smoke=true
if [ "${1:-}" = "--no-smoke" ]; then
  smoke=false
fi

TDL_VERSION="15.0.0"
TD_COMMIT="bc9c263e2bfee06aaab41e82db51a103376030bc"
TD_VERSION="1.8.67"
TD_URL="https://github.com/g000sha256/TDLib/archive/$TD_COMMIT.tar.gz"
TD_SHA256="aab8cc1897b7781b131888b28860a2cf414ed6c3976881fef0372e48ae975d84"

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
here="$root/natives/tdlib-linux"
natives="${TMPLAYER_NATIVES:-$HOME/.cache/tmplayer-natives}"
out="$natives/tdlib"
downloads="$out/downloads"
jobs="${JOBS:-4}"

case "$jobs" in
  '' | *[!0-9]*) echo "JOBS must be a number, got '$jobs'" >&2; exit 1 ;;
esac
if [ "$jobs" -lt 1 ] || [ "$jobs" -gt 6 ]; then
  echo "JOBS must be 1 to 6, got $jobs" >&2
  exit 1
fi

# A tdl-coroutines bump means another TDLib commit, so this library would answer a different
# schema than the Kotlin API expects and the first request would fail.
used="$(sed -n 's/^tdl = "\(.*\)"$/\1/p' "$root/gradle/libs.versions.toml")"
if [ "$used" != "$TDL_VERSION" ]; then
  echo "tdl-coroutines is $used in libs.versions.toml, but this script builds TDLib for $TDL_VERSION." >&2
  echo "Read the td submodule commit of the new tag on github.com/g000sha256/tdl-coroutines and update the pins." >&2
  exit 1
fi

command -v podman > /dev/null || { echo "podman is needed (docker is not supported here)" >&2; exit 1; }

mkdir -p "$downloads"
tarball="$downloads/td-$TD_COMMIT.tar.gz"
if [ ! -f "$tarball" ]; then
  curl -fL --retry 3 -o "$tarball.part" "$TD_URL"
  mv "$tarball.part" "$tarball"
fi
actual="$(sha256sum "$tarball" | cut -d' ' -f1)"
if [ "$actual" != "$TD_SHA256" ]; then
  echo "SHA-256 mismatch for $tarball" >&2
  echo "  expected $TD_SHA256" >&2
  echo "  got      $actual" >&2
  exit 1
fi

image="localhost/tmplayer-tdlib-linux-build"
podman build --target build -t "$image" -f "$here/Containerfile" "$here"

start=$(date +%s)
rm -f "$out/libtdjsonjava.so"
podman run --rm \
  --security-opt label=disable \
  -v "$downloads:/in:ro" \
  -v "$out:/out" \
  "$image" \
  build-in-container.sh "/in/td-$TD_COMMIT.tar.gz" "$TD_COMMIT" "$jobs" /out
end=$(date +%s)

lib="$out/libtdjsonjava.so"
[ -s "$lib" ] || { echo "the build left no $lib" >&2; exit 1; }
echo "Built $lib in $(( (end - start) / 60 )) min"
sha256sum "$lib"

"$here/check-floor.sh" "$lib"

if $smoke; then
  "$here/smoke-test.sh" "$lib" "$TD_VERSION" "$TD_COMMIT"
fi
