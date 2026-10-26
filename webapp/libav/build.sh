#!/bin/sh
# Builds TMPlayer's libav.js variant (FFmpeg in WebAssembly) for the web player's files that
# mediabunny cannot open: AVI, with MPEG-4 Part 2 (DivX, Xvid), MS MPEG-4 and H.263 pictures decoded
# and the usual AVI sound decoded. H.264 and HEVC are only parsed (copied, never decoded here).
#
# Output: webapp/public/libav/ (the three files the page loads, committed; served at /libav/) and
# webapp/libav/libav.types.d.ts. The exact sources are published with each deploy for the LGPL
# (sources.sh).
#
# Needs podman (or docker as PODMAN=docker). Takes about 10 minutes.
set -eu

LIBAVJS_TAG=v6.10.9.0
EMSDK=docker.io/emscripten/emsdk:6.0.5
VARIANT=tmplayer
here=$(cd "$(dirname "$0")" && pwd)
out="$here/../public/libav"
work=${WORK:-"$here/build"}
podman=${PODMAN:-podman}

mkdir -p "$work" "$out"
if [ ! -d "$work/libav.js" ]; then
    git clone -q --depth 1 --branch "$LIBAVJS_TAG" https://github.com/Yahweasel/libav.js.git "$work/libav.js"
fi

fragments=$(tr -d '\n' < "$here/fragments.json")

"$podman" run --rm -v "$work/libav.js:/src:Z" -w /src "$EMSDK" sh -c "
    set -e
    (cd configs && node mkconfig.js $VARIANT '$fragments')
    npm ci --no-audit --no-fund --silent
    make -j\$(nproc) build/ffmpeg-9.0.tar.xz build/emfiberthreads-1.3.tar.gz
    make -j\$(nproc) dist/libav-6.10.9.0-$VARIANT.mjs dist/libav-6.10.9.0-$VARIANT.wasm.mjs dist/libav.types.d.ts
"

dist="$work/libav.js/dist"
cp "$dist/libav-6.10.9.0-$VARIANT.mjs" "$dist/libav-6.10.9.0-$VARIANT.wasm.mjs" "$dist/libav-6.10.9.0-$VARIANT.wasm.wasm" "$out/"
cp "$dist/libav.types.d.ts" "$here/libav.types.d.ts"
echo "built: $(ls "$out")"
