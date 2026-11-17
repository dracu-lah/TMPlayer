#!/usr/bin/env bash
# Proves that a built Linux package runs on a plain Ubuntu 22.04 (glibc 2.35, libstdc++ 3.4.30),
# the oldest system the downloads promise (issue #30). Starts a container of that release with
# only the libraries a stock desktop has, then, for each package given:
#
#   1. unpacks it (an AppImage with --appimage-extract, no FUSE; a tarball with tar),
#   2. runs desktop/packaging/linux/glibc-floor.sh over it, which fails when any native needs a
#      newer GLIBC or GLIBCXX than the floor,
#   3. runs its --self-test inside the container, which fails when TDLib or libmpv does not load.
#
# Both checks run for every package even when the first fails, so one run shows everything wrong.
#
#   .github/actions/desktop-linux/plain-linux-check.sh dist/TMPlayer-1.2.3-x86_64.AppImage \
#       dist/TMPlayer-1.2.3-linux-x64.tar.xz
#
# A package is an .AppImage, a .tar.xz, or an unpacked app image directory (bin/TMPlayer).
# CONTAINER_ENGINE: docker (default, what the runners have) or podman. IMAGE: the base image.
set -euo pipefail

[ $# -ge 1 ] || { echo "usage: plain-linux-check.sh <AppImage|tar.xz|app dir>..." >&2; exit 2; }
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
engine="${CONTAINER_ENGINE:-docker}"
# Ubuntu 22.04 by digest, the same image the libmpv build's smoke test uses.
image="${IMAGE:-docker.io/library/ubuntu:22.04@sha256:45e9ddc6895604674618a409ca9f73d301da8fdb75fe330e8e9a27b3b219e4ad}"
command -v "$engine" >/dev/null || { echo "$engine is not installed (set CONTAINER_ENGINE)" >&2; exit 2; }

mounts=()
args=()
n=0
for p in "$@"; do
  [ -e "$p" ] || { echo "no such package: $p" >&2; exit 2; }
  n=$((n + 1))
  abs="$(cd "$(dirname "$p")" && pwd)/$(basename "$p")"
  mounts+=(-v "$abs:/in/$n/$(basename "$p"):ro")
  args+=("/in/$n/$(basename "$p")")
done

# label=disable: SELinux hosts (Fedora, podman) would otherwise deny the read-only mounts.
exec "$engine" run --rm --security-opt label=disable \
  -v "$here/plain-linux-inner.sh:/check/inner.sh:ro" \
  -v "$repo/desktop/packaging/linux/glibc-floor.sh:/check/glibc-floor.sh:ro" \
  "${mounts[@]}" \
  "$image" bash /check/inner.sh "${args[@]}"
