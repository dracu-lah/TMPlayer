#!/bin/sh
# Puts the exact sources of public/libav/ (TMPlayer's libav.js build of FFmpeg, LGPL-2.1-or-later)
# into the given folder, so every deploy serves them beside the files built from them
# (/libav/sources/). The CI build runs it into webapp/dist/libav/sources.
set -eu

out=${1:?usage: sources.sh <folder>}
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$out"

fetch() { # url, file, sha256
    curl -fsSL --retry 3 --retry-all-errors -o "$out/$2" "$1"
    echo "$3  $out/$2" | sha256sum -c --quiet -
}
fetch https://ffmpeg.org/releases/ffmpeg-9.0.tar.xz ffmpeg-9.0.tar.xz 7f607a00dd0d28a729d5a4811205812eef01cf6ef6155025febb6f36a9062d52
fetch https://github.com/Yahweasel/libav.js/archive/refs/tags/v6.10.9.0.tar.gz libav.js-6.10.9.0.tar.gz 3b640c356d74724a99230b077ef442d8c995a37c03e0729d6d2a9be0f3a41fd3
fetch https://github.com/Yahweasel/emfiberthreads/archive/refs/tags/v1.3.tar.gz emfiberthreads-1.3.tar.gz 0fe933d7742de3b3213608e0fb087a1ae0d060604d5835f7c9cdfcee0fc8cbcb
cp "$here/build.sh" "$here/fragments.json" "$out/"
cat > "$out/README.txt" <<'EOF'
The sources of TMPlayer's libav.js build (/libav/libav-6.10.9.0-tmplayer.*), FFmpeg 9.0 compiled to
WebAssembly by libav.js 6.10.9.0 with Emscripten 6.0.5, unchanged, configured with the fragments in
fragments.json (an AVI demuxer and the decoders for the pictures and sound AVI files carry).

  ffmpeg-9.0.tar.xz           FFmpeg, LGPL-2.1-or-later (no GPL or non-free parts are enabled)
  libav.js-6.10.9.0.tar.gz    libav.js, its build system and JavaScript wrapper (0BSD-style)
  emfiberthreads-1.3.tar.gz   emfiberthreads, linked into the build (0BSD)
  build.sh, fragments.json    how TMPlayer builds it: sh build.sh, with podman or docker

To use your own build, replace the three files in /libav/ with yours of the same names.
TMPlayer's own source: https://github.com/dracu-lah/TMPlayer (webapp/libav/).
EOF
echo "sources in $out"
