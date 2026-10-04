#!/usr/bin/env bash
# Builds the Linux release packages from the app image that `:desktop:createDistributable` makes.
#
#   ./gradlew :desktop:createDistributable -PdesktopVersion=1.18.0
#   desktop/packaging/linux/package.sh 1.18.0 [tar] [appimage]
#
# With no format named it builds both into desktop/build/packages (OUT overrides):
#
#   TMPlayer-<v>-linux-x64.tar.xz   by hand on any distribution; what the AUR PKGBUILD builds from
#   TMPlayer-<v>-x86_64.AppImage    any distribution, nothing installed
#
# The natives are stripped once in a staging copy (the app image itself is left alone) and both
# formats are compressed hard (xz for the tarball, zstd at its top level with 1 MB blocks for the
# AppImage, whose runtime reads no other compressor), which is what makes the jars' stored entries
# pay off.
#
# The AppImage comes from appimagetool; set APPIMAGETOOL to its path if it is not on PATH. The
# deb, rpm and Flatpak that earlier releases carried are no longer built.
#
# Every native the app loads (TDLib inside the tdl-coroutines jar, libmpv and FFmpeg inside the
# mediamp runtime jar) was linked against glibc 2.38 and GCC 13's libstdc++, which is the floor
# for both: Ubuntu 24.04, Debian 13, Fedora 39, Mint 22, openSUSE Tumbleweed or Leap 16, RHEL 10,
# Arch, or newer.
set -euo pipefail

version="${1:?usage: package.sh <version> [tar] [appimage]}"
shift
formats=("$@")
[ ${#formats[@]} -eq 0 ] && formats=(tar appimage)
for f in "${formats[@]}"; do
  case "$f" in tar|appimage) ;; *) echo "unknown format: $f (tar or appimage)" >&2; exit 1 ;; esac
done

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
app="${APP_IMAGE:-$root/desktop/build/compose/binaries/main/app/TMPlayer}"
out="${OUT:-$root/desktop/build/packages}"
work="${WORK:-$root/desktop/build/packaging-work}"
id="io.github.dracu_lah.TMPlayer"
icons="$root/desktop/packaging/icons"

[ -x "$app/bin/TMPlayer" ] || { echo "no app image at $app; run :desktop:createDistributable first" >&2; exit 1; }
release_date="$(git -C "$root" log -1 --format=%cs 2>/dev/null || date -u +%F)"

rm -rf "$work"
mkdir -p "$work" "$out"

# The freedesktop integration both formats share: launcher, icons, AppStream metadata, licence.
share="$work/share"
install -Dm644 "$here/$id.desktop" "$share/applications/$id.desktop"
for png in "$icons"/hicolor/*.png; do
  size="$(basename "$png" .png)"
  install -Dm644 "$png" "$share/icons/hicolor/$size/apps/$id.png"
done
install -Dm644 "$icons/tmplayer.svg" "$share/icons/hicolor/scalable/apps/$id.svg"
mkdir -p "$share/metainfo"
sed -e "s/@VERSION@/$version/" -e "s/@DATE@/$release_date/" \
  "$here/$id.metainfo.xml" > "$share/metainfo/$id.metainfo.xml"
install -Dm644 "$root/LICENSE" "$share/doc/tmplayer/copyright"
# Checked here rather than by appimagetool, which only knows the older .appdata.xml name.
if command -v desktop-file-validate >/dev/null; then desktop-file-validate "$share/applications/$id.desktop"; fi
if command -v appstreamcli >/dev/null; then appstreamcli validate --no-net "$share/metainfo/$id.metainfo.xml"; fi

# One staging copy of the app image, with the unstripped ELF natives stripped, shared by both formats.
stage="$work/app"
cp -a "$app" "$stage"
if command -v strip >/dev/null; then
  is_elf() { [ "$(head -c4 "$1" 2>/dev/null | od -An -c | tr -d ' ')" = '177ELF' ]; }
  while IFS= read -r -d '' f; do
    if is_elf "$f"; then strip --strip-unneeded "$f" 2>/dev/null || true; fi
  done < <(find "$stage/lib/app" "$stage/lib/runtime" -type f \( -name '*.so' -o -name '*.so.*' -o -path '*/runtime/bin/*' \) -print0 2>/dev/null)
fi

has() { local f; for f in "${formats[@]}"; do [ "$f" = "$1" ] && return 0; done; return 1; }

if has tar; then
  top="TMPlayer-$version"
  mkdir -p "$work/tar/$top"
  cp -a "$stage/." "$work/tar/$top/"
  cp -a "$share" "$work/tar/$top/share"
  tar -C "$work/tar" --owner=0 --group=0 -cf - "$top" | xz -9 -T0 > "$out/TMPlayer-$version-linux-x64.tar.xz"
  echo "built $out/TMPlayer-$version-linux-x64.tar.xz"
fi

if has appimage; then
  tool="${APPIMAGETOOL:-$(command -v appimagetool || true)}"
  [ -n "$tool" ] || { echo "appimagetool not found; set APPIMAGETOOL" >&2; exit 1; }
  appdir="$work/TMPlayer.AppDir"
  mkdir -p "$appdir/usr/lib"
  cp -a "$stage" "$appdir/usr/lib/tmplayer"
  cp -a "$share" "$appdir/usr/share"
  install -m755 "$here/AppRun" "$appdir/AppRun"
  cp "$share/applications/$id.desktop" "$appdir/$id.desktop"
  cp "$icons/hicolor/256x256.png" "$appdir/$id.png"
  ln -s "$id.png" "$appdir/.DirIcon"
  # appimagetool runs as an AppImage itself; extracting it first works without FUSE (CI, containers).
  ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "$tool" --no-appstream --comp zstd \
    --mksquashfs-opt -Xcompression-level --mksquashfs-opt 22 --mksquashfs-opt -b --mksquashfs-opt 1M \
    "$appdir" "$out/TMPlayer-$version-x86_64.AppImage"
  echo "built $out/TMPlayer-$version-x86_64.AppImage"
fi

ls -la "$out"
