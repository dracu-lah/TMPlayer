#!/usr/bin/env bash
# Builds the Linux release packages from the app image that `:desktop:createDistributable` makes.
#
#   ./gradlew :desktop:createDistributable -PdesktopVersion=1.18.0
#   desktop/packaging/linux/package.sh 1.18.0 [tar] [deb] [rpm] [appimage]
#
# With no format named it builds all four into desktop/build/packages (OUT overrides):
#
#   TMPlayer-<v>-linux-x64.tar.gz   any distribution; also what the Flatpak and the AUR build from
#   tmplayer_<v>_amd64.deb          Debian, Ubuntu, Mint, Pop!_OS
#   tmplayer-<v>.x86_64.rpm         Fedora, openSUSE, RHEL
#   TMPlayer-<v>-x86_64.AppImage    any distribution, nothing installed
#
# The deb and rpm come from nfpm and the AppImage from appimagetool; set NFPM and APPIMAGETOOL to
# their paths if they are not on PATH. Neither needs root, dpkg or rpmbuild. The Flatpak has its
# own script next to its manifest (../flatpak/build.sh), since it needs flatpak-builder.
#
# Every native the app loads (TDLib inside the tdl-coroutines jar, libmpv and FFmpeg inside the
# mediamp runtime jar) was linked against glibc 2.38 and GCC 13's libstdc++, which is the floor
# for every format here except the Flatpak, whose runtime brings its own: Ubuntu 24.04, Debian 13,
# Fedora 39, Mint 22, openSUSE Tumbleweed or Leap 16, RHEL 10, Arch, or newer.
set -euo pipefail

version="${1:?usage: package.sh <version> [tar] [deb] [rpm] [appimage]}"
shift
formats=("$@")
[ ${#formats[@]} -eq 0 ] && formats=(tar deb rpm appimage)

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

# The freedesktop integration every format shares: launcher, icons, AppStream metadata, licence.
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

has() { local f; for f in "${formats[@]}"; do [ "$f" = "$1" ] && return 0; done; return 1; }

if has tar; then
  top="TMPlayer-$version"
  mkdir -p "$work/tar/$top"
  cp -a "$app/." "$work/tar/$top/"
  cp -a "$share" "$work/tar/$top/share"
  tar -C "$work/tar" --owner=0 --group=0 -czf "$out/TMPlayer-$version-linux-x64.tar.gz" "$top"
  echo "built $out/TMPlayer-$version-linux-x64.tar.gz"
fi

if has deb || has rpm; then
  nfpm="${NFPM:-$(command -v nfpm || true)}"
  [ -n "$nfpm" ] || { echo "nfpm not found; set NFPM" >&2; exit 1; }
  stage="$work/stage"
  mkdir -p "$stage/opt"
  cp -a "$app" "$stage/opt/tmplayer"
  cp -a "$share" "$stage/share"
  export TM_VERSION="$version"
  if has deb; then
    (cd "$work" && "$nfpm" package --config "$here/nfpm.yaml" --packager deb --target "$out/tmplayer_${version}_amd64.deb")
  fi
  if has rpm; then
    (cd "$work" && "$nfpm" package --config "$here/nfpm.yaml" --packager rpm --target "$out/tmplayer-$version.x86_64.rpm")
  fi
fi

if has appimage; then
  tool="${APPIMAGETOOL:-$(command -v appimagetool || true)}"
  [ -n "$tool" ] || { echo "appimagetool not found; set APPIMAGETOOL" >&2; exit 1; }
  appdir="$work/TMPlayer.AppDir"
  mkdir -p "$appdir/usr/lib"
  cp -a "$app" "$appdir/usr/lib/tmplayer"
  cp -a "$share" "$appdir/usr/share"
  install -m755 "$here/AppRun" "$appdir/AppRun"
  cp "$share/applications/$id.desktop" "$appdir/$id.desktop"
  cp "$icons/hicolor/256x256.png" "$appdir/$id.png"
  ln -s "$id.png" "$appdir/.DirIcon"
  # appimagetool runs as an AppImage itself; extracting it first works without FUSE (CI, containers).
  ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "$tool" --no-appstream "$appdir" "$out/TMPlayer-$version-x86_64.AppImage"
  echo "built $out/TMPlayer-$version-x86_64.AppImage"
fi

ls -la "$out"
