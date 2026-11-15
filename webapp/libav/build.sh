#!/bin/sh
# Builds TMPlayer's two libav.js variants (FFmpeg in WebAssembly) for what the browser cannot play:
#
#   tmplayer        (fragments.json) the player engine: AVI, FLV, ASF/WMV, RealMedia, MPEG-PS, and
#                   MP4/3GP, Matroska, MPEG-TS and Ogg whose picture or sound mediabunny cannot hand
#                   to the browser; SD pictures decoded (MPEG-4 Part 2, MPEG-1/2, WMV, VC-1, H.263,
#                   RealVideo, Theora, VP6 and older), the sound of every C1 family decoded. H.264 and
#                   HEVC are only parsed (copied, never decoded here).
#   tmplayer-sound  (fragments-sound.json) the sound decoders of the codecs mediabunny names (AAC,
#                   MPEG audio, Opus, Vorbis, FLAC) for a track this browser cannot decode (no
#                   WebCodecs on the TVs, Vorbis or Opus in old Safari, MPEG Layer II anywhere).
#
# Each is built as a classic script, which the player loads in every browser (TVs before Chromium 80
# have no module workers), its JavaScript lowered to Chromium 68 (lower.mjs), and as an ES module
# for node's tests (src/player/libavbuild.test.ts). Its wasm is lowered once more to the first
# WebAssembly (`.mvp.wasm`, for Chromium 68 to 74: no bulk memory, sign extension or saturating
# float to int).
#
# Output: webapp/public/libav/ (committed; served at /libav/) and webapp/libav/libav.types.d.ts.
# The exact sources are published with each deploy for the LGPL (sources.sh).
#
# Needs podman (or docker as PODMAN=docker) and the webapp's node_modules (binaryen, vite). About 15 minutes.
set -eu

LIBAVJS_TAG=v6.10.9.0
V=6.10.9.0
EMSDK=docker.io/emscripten/emsdk:6.0.5
here=$(cd "$(dirname "$0")" && pwd)
out="$here/../public/libav"
work=${WORK:-"$here/build"}
podman=${PODMAN:-podman}
wasmopt="$here/../node_modules/binaryen/bin/wasm-opt"

mkdir -p "$work" "$out"
if [ ! -d "$work/libav.js" ]; then
    git clone -q --depth 1 --branch "$LIBAVJS_TAG" https://github.com/Yahweasel/libav.js.git "$work/libav.js"
fi
dist="$work/libav.js/dist"

build() { # variant, fragments file
    fragments=$(tr -d '\n' < "$here/$2")
    "$podman" run --rm -v "$work/libav.js:/src:Z" -w /src "$EMSDK" sh -c "
        set -e
        (cd configs && node mkconfig.js $1 '$fragments')
        [ -d node_modules ] || npm ci --no-audit --no-fund --silent
        make -j\$(nproc) build/ffmpeg-9.0.tar.xz build/emfiberthreads-1.3.tar.gz
        make -j\$(nproc) dist/libav-$V-$1.mjs dist/libav-$V-$1.wasm.mjs dist/libav.types.d.ts
        cp dist/libav-$V-$1.wasm.wasm dist/libav-$V-$1.es6.wasm
        make -j\$(nproc) dist/libav-$V-$1.js dist/libav-$V-$1.wasm.js
    "
    # The ES module and the classic build must share one wasm (only the JS around it differs).
    cmp "$dist/libav-$V-$1.es6.wasm" "$dist/libav-$V-$1.wasm.wasm"
    cp "$dist/libav-$V-$1.mjs" "$dist/libav-$V-$1.wasm.mjs" "$dist/libav-$V-$1.js" "$dist/libav-$V-$1.wasm.js" \
        "$dist/libav-$V-$1.wasm.wasm" "$out/"
    # The classic scripts the player loads, in syntax Chromium 68 parses (lower.mjs).
    node "$here/lower.mjs" "$out/libav-$V-$1.js" "$out/libav-$V-$1.wasm.js"
    # The lowering runs last, so no optimisation brings the newer instructions back.
    node "$wasmopt" "$out/libav-$V-$1.wasm.wasm" --enable-bulk-memory --enable-bulk-memory-opt \
        --enable-nontrapping-float-to-int --enable-sign-ext --enable-mutable-globals -Oz \
        --llvm-nontrapping-fptoint-lowering --signext-lowering --llvm-memory-copy-fill-lowering \
        -o "$out/libav-$V-$1.mvp.wasm"
    # Fails unless the result is the first wasm alone.
    node "$wasmopt" "$out/libav-$V-$1.mvp.wasm" --mvp-features -o /dev/null
}

build tmplayer fragments.json
build tmplayer-sound fragments-sound.json
cp "$dist/libav.types.d.ts" "$here/libav.types.d.ts"
echo "built: $(ls "$out")"
