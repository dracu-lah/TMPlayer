#!/usr/bin/env bash
# Builds TMPlayer-<v>.flatpak, a single file bundle, from the Linux release tarball.
#
#   desktop/packaging/linux/package.sh 1.18.0 tar
#   desktop/packaging/flatpak/build.sh 1.18.0
#
# Needs flatpak and flatpak-builder, and the flathub remote for the user (the runtime and SDK are
# installed from it on first use). The bundle names Flathub as its runtime source, so
# `flatpak install --user TMPlayer-<v>.flatpak` fetches org.freedesktop.Platform when missing.
set -euo pipefail

version="${1:?usage: build.sh <version> [tarball]}"
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
out="${OUT:-$root/desktop/build/packages}"
tarball="${2:-$out/TMPlayer-$version-linux-x64.tar.gz}"
work="${WORK:-$root/desktop/build/flatpak-work}"
id="io.github.dracu_lah.TMPlayer"

[ -f "$tarball" ] || { echo "no tarball at $tarball; run package.sh $version tar first" >&2; exit 1; }
rm -rf "$work"
mkdir -p "$work" "$out"
cp "$here/$id.yml" "$work/"
cp "$tarball" "$work/TMPlayer-linux-x64.tar.gz"

flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
# rofiles-fuse needs FUSE, which containers and CI runners may not offer; it only guards caching.
flatpak-builder --user --install-deps-from=flathub --disable-rofiles-fuse --force-clean \
  --repo="$work/repo" "$work/build" "$work/$id.yml"
flatpak build-bundle --runtime-repo=https://dl.flathub.org/repo/flathub.flatpakrepo \
  "$work/repo" "$out/TMPlayer-$version.flatpak" "$id"
ls -la "$out/TMPlayer-$version.flatpak"
