#!/usr/bin/env bash
# Host test of the container reader tm4 adds to the media3ext AAR (C2), with no device or emulator:
# for each container a 6 second clip is made by the host's own ffmpeg, then demuxtest.cpp runs the
# AAR's own ffdemux.h over it (same FFmpeg 9.0.1, built by build-host-ffmpeg.sh): the probe picks a
# demuxer of the list, every packet comes out in range, the first packets of each stream decode
# with this build's decoders, and a seek to the middle reads a small part of the file and lands on
# or before the asked time. Each clip is then damaged with 12 seeds (cut short, flipped, zeroed,
# random after the head, random header fields, a size that lies), and a crash or a hang fails.
#
# Usage: scripts/media3ext/bench/run-demux-tests.sh <work dir> [container ...]
#   <work dir>  where build-host-ffmpeg.sh installed FFmpeg (<work dir>/prefix); clips go there too.
# Needs the patched nextlib tree that scripts/build-media3ext.sh unpacks (for ffdemux.h), or
# FFLIGHT_DIR pointing at the directory that holds it, and an ffmpeg to make the clips.
#
# A container the host ffmpeg cannot write (RealMedia with RealVideo 3/4 or Cook, NSV, Vivo, ivr,
# xwma, APE) is listed as untested at the end.
set -uo pipefail

root=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
work=$(cd "${1:?work dir}" && pwd)
shift || true
only=" $* "
prefix=$work/prefix
version="$(sed -n 's/^nextlib = "\(.*\)"$/\1/p' "$root/gradle/libs.versions.toml")"
headers=${FFLIGHT_DIR:-$root/build/media3ext/nextlib-$version/media3ext/src/main/cpp}
[ -f "$headers/ffdemux.h" ] || { echo "no ffdemux.h in $headers: run scripts/build-media3ext.sh or set FFLIGHT_DIR" >&2; exit 1; }

bin=$work/demuxtest
g++ -O1 -std=c++17 -I"$headers" -I"$prefix/include" "$root/scripts/media3ext/bench/demuxtest.cpp" -o "$bin" \
  $(PKG_CONFIG_PATH="$prefix/lib/pkgconfig" pkg-config --static --libs libavformat libavcodec libswresample libavutil) -lpthread || exit 1

clips=$work/demux-clips
mkdir -p "$clips"
passed=0
failed=0
skipped=""
ffq() { ffmpeg -hide_banner -loglevel error -y "$@"; }
wanted() { [ -z "${only// /}" ] || [[ "$only" == *" $1 "* ]]; }

# clip <name> <extension> <ffmpeg output options...>: 6 s of a test card and a tone, 2 s GOP.
clip() {
  local name=$1 ext=$2
  shift 2
  wanted "$name" || return 0
  local out=$clips/$name.$ext
  if ! ffq -f lavfi -i "testsrc2=size=320x240:rate=25" -f lavfi -i "sine=frequency=440:sample_rate=44100" \
      -t 6 "$@" "$out" 2>"$clips/$name.log"; then
    skipped="$skipped $name(host ffmpeg could not write it: $(tail -1 "$clips/$name.log"))"
    return 0
  fi
  run "$name" "$out"
}

# sound <name> <extension> <ffmpeg output options...>: 6 s of a tone alone.
sound() {
  local name=$1 ext=$2
  shift 2
  wanted "$name" || return 0
  local out=$clips/$name.$ext
  if ! ffq -f lavfi -i "sine=frequency=440:sample_rate=44100" -t 6 "$@" "$out" 2>"$clips/$name.log"; then
    skipped="$skipped $name(host ffmpeg could not write it: $(tail -1 "$clips/$name.log"))"
    return 0
  fi
  run "$name" "$out"
}

run() {
  local name=$1 out=$2 line seed code
  line=$(timeout 60 "$bin" "$out" 2>&1 | tail -1)
  if [[ "$line" == OK* ]]; then
    echo "ok   $name: ${line#OK }"
    passed=$((passed + 1))
  else
    echo "FAIL $name: $line"
    failed=$((failed + 1))
    return
  fi
  for seed in $(seq 1 12); do
    timeout 60 "$bin" "$out" garbage "$seed" >/dev/null 2>&1
    code=$?
    if [ "$code" -ne 0 ]; then
      echo "CRASH $name: damaged input, seed $seed, exit code $code"
      failed=$((failed + 1))
      return
    fi
  done
}

gop=(-g 50)
clip asf wmv -c:v wmv2 "${gop[@]}" -c:a wmav2 -b:a 64k
clip asf-msmpeg4 asf -c:v msmpeg4 "${gop[@]}" -c:a wmav1 -b:a 64k
sound wma wma -c:a wmav2 -b:a 64k
clip rm rm -c:v rv20 "${gop[@]}" -c:a real_144 -ar 8000 -ac 1
clip rm-rv10-ac3 rm -c:v rv10 "${gop[@]}" -c:a ac3 -b:a 128k
clip mpeg1-system mpg -f mpeg -c:v mpeg1video "${gop[@]}" -b:v 600k -c:a mp2 -b:a 128k
clip vob vob -f vob -c:v mpeg2video "${gop[@]}" -b:v 800k -c:a ac3 -b:a 128k
clip nut nut -c:v mpeg4 "${gop[@]}" -c:a pcm_s16le
clip mxf mxf -c:v mpeg2video "${gop[@]}" -b:v 2M -pix_fmt yuv422p -s 720x576 -r 25 -c:a pcm_s16le -ar 48000
clip wtv wtv -c:v mpeg2video "${gop[@]}" -c:a mp2
clip ogg-theora ogv -c:v libtheora "${gop[@]}" -q:v 5 -c:a libvorbis -q:a 3
wanted dv && {
  out=$clips/dv.dv
  if ffq -f lavfi -i "testsrc2=size=720x576:rate=25" -f lavfi -i "sine=frequency=440:sample_rate=48000" \
      -t 6 -c:v dvvideo -pix_fmt yuv420p -c:a pcm_s16le -ac 2 -f dv "$out" 2>"$clips/dv.log"; then
    run dv "$out"
  else
    skipped="$skipped dv(host ffmpeg could not write it: $(tail -1 "$clips/dv.log"))"
  fi
}
sound aiff aiff -c:a pcm_s16be
sound au au -c:a pcm_s16be
sound caf caf -c:a alac
sound voc voc -c:a pcm_u8
sound w64 w64 -c:a pcm_s16le
sound wv wv -c:a wavpack
sound tta tta -c:a tta

echo
echo "passed $passed, failed $failed"
[ -n "$skipped" ] && echo "skipped:$skipped"
echo "untested here (no host muxer or encoder), covered by real files in run-sample-tests.sh (SAMPLES.md): ivr, xwma, nsv, vivo, smjpeg, ape, RealMedia with RealVideo 3/4, Cook, SIPR or ATRAC3"
[ "$failed" -eq 0 ]
