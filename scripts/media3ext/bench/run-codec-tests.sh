#!/usr/bin/env bash
# Host test of the decoders tm3 added to the media3ext AAR (C1), with no device or emulator: for
# each decoder a short clip is made by the host's own ffmpeg, decoded by codectest.cpp the way the
# AAR's JNI glue sets a decoder up (same FFmpeg 9.0.1 as the AAR, built by build-host-ffmpeg.sh
# with the patch's own decoder list, and the same ffcodecparams.h), and the md5 of what came out
# is compared with the md5 the host's ffmpeg gets from the same clip with the same decoder.
#
# Usage: scripts/media3ext/bench/run-codec-tests.sh <work dir> [decoder ...]
#   <work dir>  where build-host-ffmpeg.sh installed FFmpeg (<work dir>/prefix); the clips and the
#               test binary go there too.
# Needs the patched nextlib tree that scripts/build-media3ext.sh unpacks (for ffcodecparams.h), or
# FFLIGHT_DIR pointing at the directory that holds it, and an ffmpeg to make the clips.
#
# H.263+ (h263p) is not tested: containers label it H.263 and the h263 decoder reads it, so no clip
# carries the h263p codec id.
# A decoder the host ffmpeg has no encoder for (WMV 3, VP6, RealVideo 3 and 4, SVQ3, MJPEG-B, MP1,
# WMA Pro and Lossless, Cook, RealAudio 2.0, QCELP, SIPR, ATRAC3, Monkey's Audio and the rarer ADPCM
# and PCM kinds) is listed as untested at the end: the list is the patch's decoder list minus the
# decoders that ran, so a decoder added to the patch without a case here shows up in it.
set -uo pipefail

root=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
work=$(cd "${1:?work dir}" && pwd)
shift || true
only=" $* "
prefix=$work/prefix
version="$(sed -n 's/^nextlib = "\(.*\)"$/\1/p' "$root/gradle/libs.versions.toml")"
params=${FFLIGHT_DIR:-$root/build/media3ext/nextlib-$version/media3ext/src/main/cpp}
[ -f "$params/ffcodecparams.h" ] || { echo "no ffcodecparams.h in $params: run scripts/build-media3ext.sh or set FFLIGHT_DIR" >&2; exit 1; }

bin=$work/codectest
g++ -O1 -std=c++17 -I"$params" -I"$prefix/include" "$root/scripts/media3ext/bench/codectest.cpp" -o "$bin" \
  $(PKG_CONFIG_PATH="$prefix/lib/pkgconfig" pkg-config --static --libs libavformat libavcodec libswresample libavutil) -lpthread || exit 1

clips=$work/clips
mkdir -p "$clips"
tests_ran=""
passed=0
failed=0
skipped=""

# The 2 second sources. Video: a test card with a little noise so lossless codecs have work to do.
video_src=(-f lavfi -i "testsrc2=size=%SIZE%:rate=25,noise=alls=6:allf=t")
ffq() { ffmpeg -hide_banner -loglevel error -y "$@"; }

# The reference decode: FFmpeg 9.0.1's own program (build-host-ffmpeg.sh makes it), the same version
# as the AAR's; the host's ffmpeg otherwise, whose 7.x differs in the last bit for some decoders.
reference=$work/ref/bin/ffmpeg
[ -x "$reference" ] || { reference=ffmpeg; echo "note: no $work/ref/bin/ffmpeg, comparing with the host's ffmpeg"; }

has_encoder() { ffmpeg -hide_banner -encoders 2>/dev/null | awk '{print $2}' | grep -qx "$1"; }

wanted() { [ -z "${only// /}" ] || [[ "$only" == *" $1 "* ]]; }

# check <decoder> <clip>: the AAR's way against the host ffmpeg's, same decoder.
check() {
  local decoder=$1 clip=$2 kind=$3 ours theirs
  ours=$("$bin" "$clip" "$decoder" 2>&1 | tail -1)
  if [[ "$ours" != OK* ]]; then
    echo "FAIL $decoder: $ours"
    failed=$((failed + 1))
    return
  fi
  if [ "$kind" = video ]; then
    theirs=$("$reference" -hide_banner -loglevel error -c:v "$decoder" -i "$clip" -map 0:v:0 -an -sn -fps_mode passthrough -c:v rawvideo -f md5 - 2>/dev/null | sed -n 's/^MD5=//p')
  else
    theirs=$("$reference" -hide_banner -loglevel error -c:a "$decoder" -i "$clip" -map 0:a:0 -vn -sn -c:a pcm_s16le -f md5 - 2>/dev/null | sed -n 's/^MD5=//p')
  fi
  local md5=${ours##*md5=}
  local units=${ours##*units=}
  units=${units%% *}
  if [ "$md5" = "$theirs" ]; then
    echo "ok   $decoder ($units $([ "$kind" = video ] && echo frames || echo samples), md5 $md5)"
    passed=$((passed + 1))
  else
    echo "DIFF $decoder: ours $md5 host ffmpeg ${theirs:-none} ($ours)"
    failed=$((failed + 1))
  fi
  tests_ran="$tests_ran $decoder"
  # Damaged input must never take the app down: the same clip with its extra data and every packet
  # damaged, eight seeds, under a time limit (a hang is a failure too).
  local seed code
  for seed in 1 2 3 4 5 6 7 8; do
    timeout 120 "$bin" "$clip" "$decoder" 2 garbage "$seed" >/dev/null 2>&1
    code=$?
    if [ "$code" -ne 0 ]; then
      echo "CRASH $decoder: damaged input, seed $seed, exit code $code"
      failed=$((failed + 1))
      break
    fi
  done
}

# video <decoder> <host encoder> <extension> <size> [encoder options...]
video() {
  local decoder=$1 encoder=$2 ext=$3 size=$4
  shift 4
  wanted "$decoder" || return 0
  if ! has_encoder "$encoder"; then skipped="$skipped $decoder(no host encoder $encoder)"; return 0; fi
  local clip=$clips/$decoder.$ext
  local src=("${video_src[@]}")
  src[3]=${src[3]//%SIZE%/$size}
  if ! ffq "${src[@]}" -t 2 -c:v "$encoder" "$@" "$clip" 2>"$clips/$decoder.log"; then
    skipped="$skipped $decoder(host ffmpeg could not make the clip: $(tail -1 "$clips/$decoder.log"))"
    return 0
  fi
  check "$decoder" "$clip" video
}

# audio <decoder> <host encoder> <extension> <rate> <channels> [encoder options...]
audio() {
  local decoder=$1 encoder=$2 ext=$3 rate=$4 channels=$5
  shift 5
  wanted "$decoder" || return 0
  if ! has_encoder "$encoder"; then skipped="$skipped $decoder(no host encoder $encoder)"; return 0; fi
  local clip=$clips/$decoder.$ext
  if ! ffq -f lavfi -i "sine=frequency=440:sample_rate=$rate:duration=2" \
      -t 2 -ac "$channels" -ar "$rate" -c:a "$encoder" "$@" "$clip" 2>"$clips/$decoder.log"; then
    skipped="$skipped $decoder(host ffmpeg could not make the clip: $(tail -1 "$clips/$decoder.log"))"
    return 0
  fi
  check "$decoder" "$clip" audio
}

# Old video
video mpeg1video mpeg1video mpg 352x288 -b:v 800k -g 12
video wmv1 wmv1 avi 320x240
video wmv2 wmv2 avi 320x240
video h263 h263 3gp 352x288
video flv flv flv 320x240
video theora libtheora ogv 320x240 -q:v 6
video dvvideo dvvideo dv 720x576 -pix_fmt yuv420p
video svq1 svq1 mov 320x240
video cinepak cinepak avi 320x240
video rv10 rv10 rm 352x288
video rv20 rv20 rm 352x288
# Exotic video
video prores prores_ks mov 320x240 -profile:v 3
video ffv1 ffv1 mkv 320x240 -level 3 -g 5
video huffyuv huffyuv avi 320x240 -pix_fmt yuv422p
video ffvhuff ffvhuff avi 320x240
video utvideo utvideo avi 320x240
# Old audio
audio wmav1 wmav1 wma 44100 2
audio wmav2 wmav2 wma 44100 2
audio mp2 mp2 mp2 44100 2
audio adpcm_ima_wav adpcm_ima_wav wav 44100 2
audio adpcm_ms adpcm_ms wav 44100 2
audio adpcm_ima_qt adpcm_ima_qt mov 44100 2
audio adpcm_swf adpcm_swf flv 22050 1
audio adpcm_yamaha adpcm_yamaha wav 44100 2
audio g726 g726 wav 8000 1 -b:a 32k
audio g722 g722 wav 16000 1
audio adpcm_adx adpcm_adx adx 44100 2
audio gsm libgsm gsm 8000 1
audio gsm_ms libgsm_ms wav 8000 1
audio nellymoser nellymoser flv 22050 1
audio speex libspeex ogg 16000 1
audio wavpack wavpack wv 44100 2
audio tta tta tta 44100 2
audio real_144 real_144 rm 8000 1
# PCM, as a container carries it
audio pcm_s16be pcm_s16be aiff 44100 2
audio pcm_s24le pcm_s24le wav 44100 2
audio pcm_s32le pcm_s32le wav 44100 2
audio pcm_f32le pcm_f32le wav 44100 2
audio pcm_f64le pcm_f64le wav 44100 2
audio pcm_u8 pcm_u8 wav 44100 2
audio pcm_s8 pcm_s8 mov 44100 2
audio pcm_s24be pcm_s24be mov 44100 2
audio pcm_s32be pcm_s32be mov 44100 2
audio pcm_f32be pcm_f32be mov 44100 2
audio pcm_f64be pcm_f64be mov 44100 2

echo
echo "passed $passed, failed $failed"
[ -n "$skipped" ] && echo "skipped:$skipped"
list=$(awk '/^\+ENABLED_DECODERS="/{f=1} f{print} f && /"$/{exit}' "$root/scripts/media3ext/tmplayer-decoders.patch" \
  | sed 's/^+//; s/^ENABLED_DECODERS="//; s/"$//')
untested=""
for d in $list; do
  # The names the build is configured with, and the decoders' own where they differ.
  case $d in
    ra_144) n=real_144 ;; ra_288) n=real_288 ;; atrac3p) n=atrac3plus ;; adpcm_g722) n=g722 ;;
    adpcm_g726) n=g726 ;; adpcm_g726le) n=g726le ;; msmpeg4v3) n=msmpeg4 ;; *) n=$d ;;
  esac
  case " $tests_ran " in *" $n "*) ;; *) untested="$untested $d" ;; esac
done
echo "decoders in the patch with no passing host test (WMV 3, VP6, RealVideo 3 and 4 and the others without an encoder are covered by real files: run-sample-tests.sh, SAMPLES.md):$untested"
[ "$failed" -eq 0 ]
