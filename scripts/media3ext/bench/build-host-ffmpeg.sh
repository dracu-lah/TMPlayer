#!/usr/bin/env bash
# Builds, for the host (Linux x86_64), the same FFmpeg 9.0.1 and dav1d 1.5.4 that nextlib's
# ffmpeg/setup.sh builds for Android, with the same decoders and options as far as a host build
# allows (static libraries, no Mbed TLS, no network protocols), so bench.cpp can time the AV1
# decode and the frame conversion of the media3ext AAR without a device or an emulator (HX2c).
#
# Usage: scripts/media3ext/bench/build-host-ffmpeg.sh <work dir>
# Takes the upstream tarballs from build/nextlib-source/downloads (scripts/build-nextlib-source.sh
# fetches and checks them) and installs into <work dir>/prefix. JOBS (default 4) sets make's -j.
set -euo pipefail

root=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
work=${1:?work dir}
mkdir -p "$work"
work=$(cd "$work" && pwd)
downloads=${DOWNLOADS:-$root/build/nextlib-source/downloads}
jobs=${JOBS:-4}
prefix=$work/prefix

echo "657dbf258cce6c0681714b6e40b8ca69e988c67ecfc2f7aab47574e6ebaceb38  $downloads/ffmpeg-9.0.1.tar.gz" | sha256sum --check --status
echo "a1d5b63d2d38ec9bd03acf643caa51fa22edd1e89c5a109c4807717216bbec07  $downloads/dav1d-1.5.4.tar.gz" | sha256sum --check --status

mkdir -p "$work/src"
[ -d "$work/src/ffmpeg-9.0.1" ] || tar -xzf "$downloads/ffmpeg-9.0.1.tar.gz" -C "$work/src"
[ -d "$work/src/dav1d-1.5.4" ] || tar -xzf "$downloads/dav1d-1.5.4.tar.gz" -C "$work/src"

if [ ! -f "$prefix/lib/libdav1d.a" ]; then
  rm -rf "$work/dav1d-build"
  meson setup "$work/dav1d-build" "$work/src/dav1d-1.5.4" --prefix="$prefix" --libdir=lib \
    --buildtype=release --default-library=static -Db_staticpic=true \
    -Denable_tools=false -Denable_tests=false
  ninja -C "$work/dav1d-build" -j"$jobs"
  ninja -C "$work/dav1d-build" install
fi

# The decoder list of the patched setup.sh.
decoders="vorbis opus flac alac pcm_mulaw pcm_alaw mp3 amrnb amrwb aac ac3 eac3 dca mlp truehd h264 hevc mpeg2video mpegvideo mpeg4 msmpeg4v2 msmpeg4v3 vc1 mjpeg vp8 vp9 libdav1d"
opts=""
for d in $decoders; do opts="$opts --enable-decoder=$d"; done

if [ ! -f "$prefix/lib/libavcodec.a" ]; then
  cd "$work/src/ffmpeg-9.0.1"
  # shellcheck disable=SC2086
  PKG_CONFIG_PATH="$prefix/lib/pkgconfig" ./configure \
    --prefix="$prefix" --extra-cflags="-O3 -fPIC" \
    --pkg-config-flags=--static \
    --enable-static --disable-shared --disable-doc --disable-programs --disable-everything \
    --disable-vulkan --disable-avdevice --disable-avfilter --disable-symver \
    --enable-parsers --enable-demuxers --enable-protocol=file --enable-swresample \
    --enable-avformat --enable-libdav1d --enable-version3 --disable-debug \
    $opts
  make -j"$jobs"
  make install
fi
echo "host FFmpeg in $prefix"
