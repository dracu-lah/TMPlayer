#!/usr/bin/env bash
# Builds nextlib-corresponding-source-1.11.1-0.16.0-tm2.tar.gz: the complete corresponding source
# of the FFmpeg extension that the Android build bundles through nextlib-media3ext 1.11.1-0.16.0
# (io.github.anilbeesetti, by Anil Beesetti, GPL-3.0), as scripts/build-media3ext.sh builds it:
# nextlib's tree at its tag, plus tmplayer/ holding TMPlayer's patch (five more FFmpeg video
# decoders and their mappings, and the light video mode for weak devices) and that build script.
# The "-tm2" is TM_REVISION in scripts/build-media3ext.sh, read from there, so the archive always
# names the patch revision it holds. Up to 1.27.x the app shipped nextlib's own AAR from Maven
# Central, whose source is the archive without the suffix (attached to v1.27.0); 1.28.x shipped
# tm1, the decoders alone, which this script at a commit before HX2c packs.
#
# The AAR carries, for each of arm64-v8a, armeabi-v7a, x86 and x86_64, libmedia3ext.so (nextlib's
# own JNI glue, built from media3ext/src/main/cpp) and FFmpeg's libavcodec, libavutil,
# libswresample and libswscale, with dav1d linked statically into libavcodec. nextlib builds them
# with ffmpeg/setup.sh at its release tag, which pins FFmpeg 9.0.1, dav1d 1.5.4 and Mbed TLS 3.6.7
# and downloads each from the address used below. This was checked against the shipped bytes, not
# assumed:
#   - Maven Central's nextlib-media3ext-1.11.1-0.16.0.aar (SHA-1 2abf2b15...41b7, the same as the
#     copy in the Gradle cache) was published on 2026-09-13 at 02:23 by nextlib's Publish workflow,
#     whose only run for branch release/1.11.1-0.16.0 built commit 2dc77cd2, the commit the tag
#     v1.11.1-0.16.0 points at. The root build.gradle.kts there sets the version to
#     "<androidxMedia3>-0.16.0" with androidxMedia3 = "1.11.1".
#   - Every FFmpeg library in all four ABIs reports "FFmpeg version 9.0.1", "LGPL version 3 or
#     later", and embeds the configure line of that setup.sh (--enable-version3, no --enable-gpl,
#     no --enable-nonfree, --enable-libdav1d, --enable-mbedtls, decoders up to vp8, vp9 and
#     libdav1d) as built on GitHub's runner with NDK 25.2.9519653.
#   - libavcodec carries the version string "1.5.4" of the dav1d it links. dav1d's tag 1.5.4 is
#     commit 54706fc6, the same dav1d mediamp builds for the desktop.
#   - The FFmpeg tarball has a good signature from the FFmpeg release signing key (FCF986EA...
#     D67658D8); the Mbed TLS tarball has the SHA-256 published with its release.
# Mbed TLS is built and linked into libavformat for its tls protocol, but nextlib ships no
# libavformat.so, so none of its code is in the AAR; its source is included anyway because
# setup.sh builds it.
#
# The archive keeps the layout of the one 1.21.0 attached: the nextlib tree at the tag (GitHub's
# archive of the pinned commit, which is the tree without .git) with upstream-sources/ holding
# the three upstream tarballs and their SHA256SUMS. Every file is checked against the SHA-256
# pinned here, and a mismatch stops the script. Nothing is uploaded: attaching the archive to a
# release is a separate, manual step.
#
# Usage: scripts/build-nextlib-source.sh [output dir]   (default: build/nextlib-source)
# Downloads are kept in <output dir>/downloads, so a second run fetches nothing.
set -euo pipefail

VERSION="1.11.1-0.16.0"
COMMIT="2dc77cd2d9352bc308aa26782f9c77a320df510b"
REVISION="$(sed -n 's/^TM_REVISION="\(.*\)"$/\1/p' "$(dirname -- "$0")/build-media3ext.sh")"
[ -n "$REVISION" ] || { echo "no TM_REVISION in scripts/build-media3ext.sh" >&2; exit 1; }
NAME="nextlib-corresponding-source-$VERSION-$REVISION"
TREE="nextlib-$VERSION"

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
out="${1:-$root/build/nextlib-source}"
mkdir -p "$out"
out=$(cd "$out" && pwd)
cache="$out/downloads"
stage="$out/$NAME"

# A nextlib bump means different natives, so this archive would be the wrong one.
used="$(sed -n 's/^nextlib = "\(.*\)"$/\1/p' "$root/gradle/libs.versions.toml")"
if [ "$used" != "$VERSION" ]; then
  echo "nextlib is $used in libs.versions.toml, but this script covers $VERSION" >&2
  exit 1
fi

github="https://github.com"

# SHA-256, file name, address. The first is the nextlib tree; the rest go in upstream-sources/,
# fetched from the same addresses ffmpeg/setup.sh uses.
manifest() {
  cat <<EOF
e80b3c90a3a72611f6ef62edc91255343da9eca0dbd7283049ebd751f88cc1b9 nextlib-${COMMIT:0:8}.tar.gz $github/anilbeesetti/nextlib/archive/$COMMIT.tar.gz
657dbf258cce6c0681714b6e40b8ca69e988c67ecfc2f7aab47574e6ebaceb38 ffmpeg-9.0.1.tar.gz https://ffmpeg.org/releases/ffmpeg-9.0.1.tar.gz
a1d5b63d2d38ec9bd03acf643caa51fa22edd1e89c5a109c4807717216bbec07 dav1d-1.5.4.tar.gz $github/videolan/dav1d/archive/refs/tags/1.5.4.tar.gz
a7e8bcbec0e6f761b4af24f25677626b35f762f68eef79c08677a363212d11f6 mbedtls-3.6.7.tar.bz2 $github/Mbed-TLS/mbedtls/releases/download/mbedtls-3.6.7/mbedtls-3.6.7.tar.bz2
EOF
}

mkdir -p "$cache"
while read -r sum file url; do
  [ -n "$sum" ] || continue
  if [ ! -f "$cache/$file" ] || ! echo "$sum  $cache/$file" | sha256sum --check --status; then
    echo "fetching $file"
    curl --fail --silent --show-error --location --retry 3 --max-time 1800 -o "$cache/$file.part" "$url"
    mv "$cache/$file.part" "$cache/$file"
  fi
  if ! echo "$sum  $cache/$file" | sha256sum --check --status; then
    echo "SHA-256 mismatch for $file from $url" >&2
    exit 1
  fi
done < <(manifest)

rm -rf "$stage"
mkdir -p "$stage"
tar -xzf "$cache/nextlib-${COMMIT:0:8}.tar.gz" -C "$stage"
mv "$stage/nextlib-$COMMIT" "$stage/$TREE"
test "$(sed -n 's/^FFMPEG_VERSION=//p' "$stage/$TREE/ffmpeg/setup.sh")" = "9.0.1"
test "$(sed -n 's/^DAV1D_VERSION=//p' "$stage/$TREE/ffmpeg/setup.sh")" = "1.5.4"
test "$(sed -n 's/^MBEDTLS_VERSION=//p' "$stage/$TREE/ffmpeg/setup.sh")" = "3.6.7"

# TMPlayer's patch and the script that applies it and builds the AAR, beside the untouched tree.
mkdir -p "$stage/$TREE/tmplayer"
cp "$root/scripts/media3ext/tmplayer-decoders.patch" "$root/scripts/build-media3ext.sh" "$stage/$TREE/tmplayer/"

upstream="$stage/$TREE/upstream-sources"
mkdir -p "$upstream"
manifest | tail -n +2 | while read -r sum file url; do
  ln -f "$cache/$file" "$upstream/$file" 2>/dev/null || cp "$cache/$file" "$upstream/$file"
done
manifest | tail -n +2 | awk '{ print $1 "  " $2 }' > "$upstream/SHA256SUMS"

cat > "$upstream/README.md" <<EOF
# Corresponding source for the FFmpeg extension in TMPlayer's Android build

TMPlayer's Android build decodes audio and some video through nextlib-media3ext $VERSION
(\`io.github.anilbeesetti\`, by Anil Beesetti, GPL-3.0). Its AAR carries libmedia3ext.so and
FFmpeg's libavcodec, libavutil, libswresample and libswscale for arm64-v8a, armeabi-v7a, x86 and
x86_64. The directory above this one is the nextlib source at its tag v$VERSION (commit
\`$COMMIT\`); \`ffmpeg/setup.sh\` there is the build script for the native libraries and
\`media3ext/src/main/cpp\` the JNI glue. This directory holds the upstream sources setup.sh builds
from, as it downloads them; \`SHA256SUMS\` lists them.

TMPlayer builds the tree with \`../tmplayer/tmplayer-decoders.patch\` applied, which adds FFmpeg's
mpeg4, msmpeg4v2, msmpeg4v3, vc1 and mjpeg video decoders to setup.sh and maps Media3's formats
to them in the Java glue, and adds a light video mode to the JNI glue (\`media3ext/src/main/cpp\`:
\`ffvideo.cpp\` and the new \`fflight.h\`) and the Java glue (\`FfmpegLibrary\`,
\`FfmpegVideoDecoder\`): dav1d's frame threads for AV1, film grain left out and YV12 frames to
the window, for the formats the app asks it for. \`../tmplayer/build-media3ext.sh\` is the script
that applies it and runs nextlib's own Gradle build (\`:media3ext:assembleRelease\`); this is
revision $REVISION of the patch.

| Component | Version | Licence | File |
|---|---|---|---|
| FFmpeg (libavcodec, libavutil, libswresample, libswscale) | 9.0.1 | LGPL-3.0-or-later (\`--enable-version3\`, no \`--enable-gpl\`, no non-free parts) | \`ffmpeg-9.0.1.tar.gz\` |
| dav1d (linked statically into libavcodec) | 1.5.4, commit \`54706fc6\` | BSD-2-Clause | \`dav1d-1.5.4.tar.gz\` |
| Mbed TLS (built for libavformat's tls protocol; nextlib ships no libavformat) | 3.6.7 | Apache-2.0 or GPL-2.0-or-later | \`mbedtls-3.6.7.tar.bz2\` |

TMPlayer's own source is at <https://github.com/dracu-lah/TMPlayer>, under GPL-3.0-or-later.
EOF

tar -C "$stage" --owner=0 --group=0 --numeric-owner --sort=name --mtime='2026-09-13 00:00Z' \
  -cf - "$TREE" | gzip -n > "$out/$NAME.tar.gz"
echo "wrote $out/$NAME.tar.gz ($(du -h "$out/$NAME.tar.gz" | cut -f1))"
sha256sum "$out/$NAME.tar.gz"
