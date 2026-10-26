#!/usr/bin/env bash
# Builds the Android FFmpeg extension from source: nextlib-media3ext 1.11.1-0.16.0 (by Anil
# Beesetti, GPL-3.0) at its release commit, with TMPlayer's patch, into
# build/media3ext/nextlib-media3ext-1.11.1-0.16.0-tm2.aar. app/build.gradle.kts uses that AAR when
# it is there, and the Maven Central one (without the decoders and the light mode below) when it
# is not. "tm2" is the patch's revision: tm1 had the decoders alone, tm2 adds the light mode, and
# the release notes link the corresponding source of the same revision.
#
# What the patch (scripts/media3ext/tmplayer-decoders.patch) changes, and nothing else:
#   - FFmpeg is configured with five more video decoders, all for formats Media3's extractors
#     already read but most devices cannot decode:
#       mpeg4      MPEG-4 Part 2 (XviD, DivX 4/5): ASP files with B-frames and quarter pel, which
#                  a phone's own MPEG-4 decoder gets wrong or refuses
#       msmpeg4v2  Microsoft MPEG-4 v2 (MP42 in AVI)
#       msmpeg4v3  Microsoft MPEG-4 v3, DivX 3 (DIV3, MP43 in AVI); FFmpeg names it "msmpeg4"
#       vc1        VC-1 (WVC1 in Matroska), the codec of many older Blu-ray remuxes, which almost
#                  no phone or stick decodes in hardware
#       mjpeg      Motion JPEG (MJPG in AVI), what older cameras record
#     All are LGPL parts of FFmpeg; the build stays --enable-version3, without --enable-gpl and
#     without anything non-free.
#   - FfmpegLibrary maps Media3's video/mp4v-es, video/divx, video/mp42, video/mp43, video/wvc1
#     and video/mjpeg to them, and FfmpegVideoDecoder hands MPEG-4 and VC-1 their codec private
#     data (the VOL header, VC-1's sequence header).
#   - setup.sh takes JOBS from the environment, so a small machine can build with JOBS=2.
#   - A light video mode (HX2c) for pictures the device cannot decode in software in real time,
#     chosen per format by the app through FfmpegLibrary.setLightVideoPolicy (TMPlayer passes
#     DecodeJudge's verdict; without a policy nothing changes): AV1 runs with dav1d's frame
#     threads (frame delay 2, `adb shell setprop debug.tmplayer.av1_frame_delay N` to try
#     another) instead of AV_CODEC_FLAG_LOW_DELAY, film grain is exported instead of applied,
#     and ffmpegRenderFrame hands the window YV12 (a copy, or a rounded 10 to 8 bit shift, in
#     the new media3ext/src/main/cpp/fflight.h) instead of swscale's RGBA conversion, falling
#     back to RGBA after the same fast 8-bit step where a window refuses YV12 and on emulators.
#     scripts/media3ext/bench times it on the host.
# Every other decoder, FFmpeg 9.0.1, dav1d 1.5.4, Mbed TLS 3.6.7 and the JNI glue are nextlib's own,
# downloaded from the addresses nextlib's setup.sh uses. F-Droid builds the same way: this script
# from the pinned, checksummed sources.
#
# Needs the Android SDK (sdk.dir in local.properties or ANDROID_HOME) with NDK 25.2.9519653 and
# CMake 3.22.1 (`sdkmanager "ndk;25.2.9519653" "cmake;3.22.1"`), and curl, patch (or git), make, meson,
# ninja, nasm and pkg-config on the PATH. Takes about an hour for the four ABIs with JOBS=2.
#
# Usage: JOBS=2 scripts/build-media3ext.sh      (nothing is rebuilt when the AAR is up to date)
# JOBS is make's and ninja's -j and, unless GRADLE_WORKERS says otherwise, Gradle's --max-workers.
set -euo pipefail

VERSION="1.11.1-0.16.0"
TM_REVISION="tm2"
COMMIT="2dc77cd2d9352bc308aa26782f9c77a320df510b"
SHA256="e80b3c90a3a72611f6ef62edc91255343da9eca0dbd7283049ebd751f88cc1b9"

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
patch_file="$root/scripts/media3ext/tmplayer-decoders.patch"
out="$root/build/media3ext"
aar="$out/nextlib-media3ext-$VERSION-$TM_REVISION.aar"
stamp="$out/stamp"
tree="$out/nextlib-$VERSION"

used="$(sed -n 's/^nextlib = "\(.*\)"$/\1/p' "$root/gradle/libs.versions.toml")"
if [ "$used" != "$VERSION" ]; then
  echo "nextlib is $used in libs.versions.toml, but this script builds $VERSION" >&2
  exit 1
fi

# The AAR is current when it was built from this script and this patch.
want="$(cat "$0" "$patch_file" | sha256sum | cut -d' ' -f1)"
if [ -f "$aar" ] && [ "$(cat "$stamp" 2>/dev/null)" = "$want" ]; then
  echo "up to date: $aar"
  exit 0
fi

if [ -z "${ANDROID_HOME:-}" ] && [ -f "$root/local.properties" ]; then
  ANDROID_HOME="$(sed -n 's/^sdk\.dir=//p' "$root/local.properties")"
fi
: "${ANDROID_HOME:?Set ANDROID_HOME or sdk.dir in local.properties}"
export ANDROID_HOME

mkdir -p "$out/downloads"
tarball="$out/downloads/nextlib-${COMMIT:0:8}.tar.gz"
if [ ! -f "$tarball" ] || ! echo "$SHA256  $tarball" | sha256sum --check --status; then
  curl --fail --silent --show-error --location --retry 3 -o "$tarball.part" \
    "https://github.com/anilbeesetti/nextlib/archive/$COMMIT.tar.gz"
  mv "$tarball.part" "$tarball"
fi
echo "$SHA256  $tarball" | sha256sum --check --status || { echo "SHA-256 mismatch for $tarball" >&2; exit 1; }

# FFmpeg's own sources and build directories are kept between runs (setup.sh skips a download it
# already has), so only a first build fetches them.
keep="$out/ffmpeg-cache"
if [ -d "$tree/ffmpeg/sources" ]; then
  rm -rf "$keep"
  mv "$tree/ffmpeg/sources" "$keep"
fi
rm -rf "$tree"
mkdir -p "$tree"
tar -xzf "$tarball" -C "$tree" --strip-components=1
[ -d "$keep" ] && mv "$keep" "$tree/ffmpeg/sources"
# patch(1) where there is one; git apply otherwise, kept from mistaking this repository for the
# tree being patched.
if command -v patch >/dev/null; then
  patch -d "$tree" -p1 --forward --silent < "$patch_file"
else
  (cd "$tree" && GIT_CEILING_DIRECTORIES="$out" git apply -p1 "$patch_file")
fi
grep -q 'mpeg4 msmpeg4v2 msmpeg4v3 vc1 mjpeg' "$tree/ffmpeg/setup.sh" || { echo "the patch did not apply" >&2; exit 1; }
[ -f "$tree/media3ext/src/main/cpp/fflight.h" ] || { echo "the patch did not apply" >&2; exit 1; }
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$tree/local.properties"

# nextlib's own Gradle build: ffmpegSetup runs ffmpeg/setup.sh, then CMake builds the JNI glue.
# Run with this repository's wrapper, so no second Gradle distribution is downloaded.
"$root/gradlew" -p "$tree" --no-daemon --max-workers="${GRADLE_WORKERS:-${JOBS:-2}}" :media3ext:assembleRelease

cp "$tree/media3ext/build/outputs/aar/media3ext-release.aar" "$aar"
echo "$want" > "$stamp"
echo "wrote $aar ($(du -h "$aar" | cut -f1))"
