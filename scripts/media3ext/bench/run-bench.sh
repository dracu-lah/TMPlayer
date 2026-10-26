#!/usr/bin/env bash
# Times the media3ext AAR's AV1 path on the host, before and after HX2c's light mode, with no
# device or emulator: the same FFmpeg and dav1d (build-host-ffmpeg.sh), the same decoder setup and
# the same frame step (fflight.h from the patched nextlib tree), on four cores (taskset).
#
# Usage: scripts/media3ext/bench/run-bench.sh <work dir> [cpus]
#   <work dir>  where build-host-ffmpeg.sh installed FFmpeg (<work dir>/prefix); the clip and the
#               bench binary go there too.
#   cpus        the four CPUs to pin to (default 4-7: four efficiency cores on a 12th gen Intel).
# Needs the patched nextlib tree that scripts/build-media3ext.sh unpacks (for fflight.h), or
# FFLIGHT_DIR pointing at a directory holding fflight.h, and an ffmpeg with libsvtav1 to make
# the clip: 10 s of 1080p 24 fps AV1 10-bit with film grain, a keyframe every 2 s.
#
# Read the figures as relative ones. A desktop core is several times an A53's, and x86 has SIMD
# for swscale's 10-bit paths where 32-bit ARM has none, so the conversion gap on a TV stick is
# wider than here; "SIMD off" shows the C paths ARM falls back to.
set -euo pipefail

root=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
work=$(cd "${1:?work dir}" && pwd)
cpus=${2:-4-7}
prefix=$work/prefix
version="$(sed -n 's/^nextlib = "\(.*\)"$/\1/p' "$root/gradle/libs.versions.toml")"
fflight=${FFLIGHT_DIR:-$root/build/media3ext/nextlib-$version/media3ext/src/main/cpp}
[ -f "$fflight/fflight.h" ] || { echo "no fflight.h in $fflight: run scripts/build-media3ext.sh or set FFLIGHT_DIR" >&2; exit 1; }

clip=$work/av1-1080p10-grain.mkv
if [ ! -f "$clip" ]; then
  ffmpeg -hide_banner -loglevel error -y -f lavfi \
    -i "testsrc2=size=1920x1080:rate=24,noise=alls=10:allf=t+u,format=yuv420p10le" -t 10 \
    -c:v libsvtav1 -preset 8 -crf 32 -g 48 -svtav1-params "film-grain=8:lp=2" \
    -color_primaries bt709 -color_trc bt709 -colorspace bt709 "$clip"
fi

bin=$work/bench
g++ ${CXXOPT:--O2} -std=c++17 -I"$fflight" -I"$prefix/include" "$root/scripts/media3ext/bench/bench.cpp" -o "$bin" \
  $(PKG_CONFIG_PATH="$prefix/lib/pkgconfig" pkg-config --static --libs libavformat libavcodec libswscale libavutil) -lpthread

run() { taskset -c "$cpus" "$bin" "$@"; }

if [ "${CASES:-all}" = convert ]; then
  run convert "$clip" 48
  exit 0
fi

echo "== decode only, 4 threads on CPUs $cpus"
run decode "$clip" 4 lowdelay grain          # before: what every AV1 decoder got
run decode "$clip" 4 lowdelay nograin
run decode "$clip" 4 delay2 grain
run decode "$clip" 4 delay2 nograin          # light mode
run decode "$clip" 4 delay3 nograin
run decode "$clip" 4 delay4 nograin
run decode "$clip" 4 auto nograin

echo "== a seek to the end of a keyframe's group (47 frames), 4 threads"
run seek "$clip" 4 lowdelay grain            # before
run seek "$clip" 4 delay2 nograin            # light mode

echo "== decode and the render's conversion on the same four CPUs"
run decode "$clip" 4 lowdelay grain rgba     # before
run decode "$clip" 4 delay2 nograin yv12     # light mode, a window that takes YV12
run decode "$clip" 4 delay2 nograin light-rgba  # light mode, a window that does not

echo "== the conversion alone, one thread"
run convert "$clip" 48
run convert "$clip" 48 c
