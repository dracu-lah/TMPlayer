#!/usr/bin/env bash
# Host test of the decoders and containers that run-codec-tests.sh and run-demux-tests.sh cannot
# cover because the host ffmpeg has no encoder or muxer for them (WMV 3, VC-1, SVQ3, VP6, RealVideo
# 3 and 4, MJPEG-B, WMA Pro and Lossless, MP1, Cook, RealAudio 2.0, QCELP, SIPR, ATRAC3 and 3+, APE;
# IVR, xWMA, NSV, Vivo, SMJPEG): real files from FFmpeg's public sample suite
# (https://samples.ffmpeg.org/), listed in samples.tsv, the results in SAMPLES.md.
#
# Usage: scripts/media3ext/bench/run-sample-tests.sh <work dir> <samples dir> [sample ...]
#   <work dir>     where build-host-ffmpeg.sh installed FFmpeg (<work dir>/prefix and <work dir>/ref)
#   <samples dir>  where the files are kept: each in a directory of its own, downloaded on the first
#                  run (curl) and never committed. Nothing in them is executed: they are read by
#                  codectest, demuxtest and the reference ffmpeg only, and their paths are arguments.
# Needs the patched nextlib tree that scripts/build-media3ext.sh unpacks (ffcodecparams.h, ffdemux.h),
# or FFLIGHT_DIR pointing at the directory that holds them.
#
# Per sample: codectest decodes each listed decoder the way the AAR's JNI glue sets it up and its md5
# is compared with the reference ffmpeg 9.0.1's own decode of the same file (bit for bit), then 4
# damaged copies of its packets must not crash it; for a container FfmpegExtractor reads, demuxtest
# opens it over the AAR's own ffdemux.h with small reads, decodes the first packets of each stream,
# seeks to the middle and back to the start, and survives 12 damaged copies. The demux line is
# printed as it is, not judged: several samples are odd files whose seeks FFmpeg itself cannot do
# (see SAMPLES.md), and the exit code follows the decoders and the damaged copies only.
set -uo pipefail

root=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
bench=$root/scripts/media3ext/bench
work=$(cd "${1:?work dir}" && pwd)
samples=$(mkdir -p "${2:?samples dir}" && cd "$2" && pwd)
shift 2 || true
only=" $* "
prefix=$work/prefix
reference=$work/ref/bin/ffmpeg
[ -x "$reference" ] || { echo "no $reference: run build-host-ffmpeg.sh" >&2; exit 1; }
version="$(sed -n 's/^nextlib = "\(.*\)"$/\1/p' "$root/gradle/libs.versions.toml")"
headers=${FFLIGHT_DIR:-$root/build/media3ext/nextlib-$version/media3ext/src/main/cpp}
[ -f "$headers/ffdemux.h" ] || { echo "no ffdemux.h in $headers: run scripts/build-media3ext.sh or set FFLIGHT_DIR" >&2; exit 1; }

libs=$(PKG_CONFIG_PATH="$prefix/lib/pkgconfig" pkg-config --static --libs libavformat libavcodec libswresample libavutil)
for t in codectest demuxtest; do
  g++ -O1 -std=c++17 -I"$headers" -I"$prefix/include" "$bench/$t.cpp" -o "$work/$t" $libs -lpthread || exit 1
done

passed=0
failed=0
while IFS=$'\t' read -r name path prepare decoders demux; do
  case $name in ''|'#'*) continue ;; esac
  [[ "$only" == *" $name "* || -z "${only// /}" ]] || continue
  dir=$samples/$name
  file=$dir/$(basename "$path")
  if [ ! -f "$file" ]; then
    mkdir -p "$dir"
    encoded=$(printf '%s' "$path" | sed 's/ /%20/g; s/+/%2B/g; s/@/%40/g; s/\[/%5B/g; s/\]/%5D/g')
    curl -sS -f -m 300 -o "$file" "https://samples.ffmpeg.org/$encoded" || { echo "FAIL $name: download"; failed=$((failed + 1)); continue; }
    if [ "$prepare" = gunzip ]; then
      gzip -dc "$file" > "$file.raw" && mv "$file.raw" "$file" || { echo "FAIL $name: gunzip"; failed=$((failed + 1)); continue; }
    fi
  fi
  echo "== $name ($(stat -c %s "$file") bytes)"
  IFS=',' read -ra list <<< "$decoders"
  for entry in "${list[@]}"; do
    [ "$entry" = - ] && continue
    decoder=${entry%%:*}
    kind=${entry#*:}
    expect_missing=0
    case $kind in *'!') expect_missing=1; kind=${kind%!} ;; esac
    any=""
    case $kind in V) kind=v; any=1 ;; A) kind=a; any=1 ;; esac
    ours=$(env ${any:+TM_ANYSTREAM=1} timeout 300 "$work/codectest" "$file" "$decoder" 2>&1 | tail -1)
    if [ "$expect_missing" = 1 ]; then
      if [[ "$ours" == *"no such decoder"* ]]; then echo "ok   $decoder: not in this build, as expected"; passed=$((passed + 1)); else echo "FAIL $decoder: expected no decoder: $ours"; failed=$((failed + 1)); fi
      continue
    fi
    if [[ "$ours" != OK* ]]; then echo "FAIL $decoder: $ours"; failed=$((failed + 1)); continue; fi
    if [ "$kind" = v ]; then
      theirs=$(timeout 300 "$reference" -hide_banner -loglevel error -c:v "$decoder" -i "$file" -map 0:v:0 -an -sn -fps_mode passthrough -c:v rawvideo -f md5 - 2>/dev/null | sed -n 's/^MD5=//p')
    else
      theirs=$(timeout 300 "$reference" -hide_banner -loglevel error -c:a "$decoder" -i "$file" -map 0:a:0 -vn -sn -c:a pcm_s16le -f md5 - 2>/dev/null | sed -n 's/^MD5=//p')
    fi
    md5=${ours##*md5=}
    if [ "$md5" = "$theirs" ]; then
      echo "ok   $decoder: ${ours#OK $decoder } (matches the reference ffmpeg)"; passed=$((passed + 1))
    else
      echo "DIFF $decoder: ours $md5, reference ${theirs:-none}"; failed=$((failed + 1)); continue
    fi
    for seed in 1 2 3 4; do
      env ${any:+TM_ANYSTREAM=1} timeout 120 "$work/codectest" "$file" "$decoder" 2 garbage "$seed" >/dev/null 2>&1
      code=$?
      if [ "$code" -ne 0 ]; then echo "CRASH $decoder: damaged packets, seed $seed, exit code $code"; failed=$((failed + 1)); break; fi
    done
  done
  if [ "$demux" = yes ]; then
    echo "demux: $(timeout 120 "$work/demuxtest" "$file" 2>&1 | tail -1 | cut -c1-260)"
    echo "demux: $(timeout 120 "$work/demuxtest" "$file" info 2>&1 | grep '^packets=')"
    for seed in $(seq 1 12); do
      timeout 60 "$work/demuxtest" "$file" garbage "$seed" >/dev/null 2>&1
      code=$?
      if [ "$code" -ne 0 ]; then echo "CRASH demux: damaged file, seed $seed, exit code $code"; failed=$((failed + 1)); break; fi
    done
  fi
done < "$bench/samples.tsv"

echo
echo "passed $passed, failed $failed"
[ "$failed" -eq 0 ]
