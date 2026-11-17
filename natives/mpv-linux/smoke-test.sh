#!/usr/bin/env bash
# Proves the rebuilt libraries from scripts/build-mpv-linux-2-35.sh work in the real runtime:
#
# 1. Makes a runtime folder: every library of mediamp-mpv-runtime-linux-x64-0.5.2.jar with the
#    rebuilt ones dropped in, and runs check-floor.sh on it.
# 2. Compares each rebuilt library with the shipped one: SONAME, RUNPATH, NEEDED and the exported
#    symbols (name and version). Any difference is printed; only a lost export or a changed
#    SONAME/RUNPATH fails, since a dropped libstdc++.so.6/libgcc_s.so.1 NEEDED is the point.
# 3. Inside a plain ubuntu:22.04 (only the libraries every Ubuntu desktop has: X11, GL, EGL,
#    ALSA, PulseAudio, fontconfig, Vulkan loader, libdrm, fonts), loads libmpv and libmediampv by
#    path, decodes 30 frames of an H.264/AAC MP4 and an MKV, the MP4 with a subtitle rendered
#    through libass (vf=sub), and must exit 0.
# 4. The same on this host, and when the host can load the shipped runtime (glibc 2.38 or newer)
#    a diff of libmpv's version, configuration, option, property and decoder lists between the
#    shipped and the rebuilt runtime, which must be empty.
#
# Usage: natives/mpv-linux/smoke-test.sh [rebuilt dir]   (default ~/.cache/tmplayer-natives/mpv)
set -euo pipefail

here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
rebuilt="${1:-$HOME/.cache/tmplayer-natives/mpv}"
cache="${TMPLAYER_MPV_DOWNLOADS:-$HOME/.cache/tmplayer-natives/mpv-downloads}"
jar="$cache/mediamp-mpv-runtime-linux-x64-0.5.2.jar"
base="docker.io/library/ubuntu:22.04@sha256:45e9ddc6895604674618a409ca9f73d301da8fdb75fe330e8e9a27b3b219e4ad"
image="tmplayer-mpv-linux-2-35"
mp4="$root/webapp/e2e/media/h264-aac.mp4"
mkv="$root/webapp/e2e/media/h264-aac.mkv"
rebuilt_libs="libmpv.so libmpv.so.2 libmpv.so.2.6.0 libass.so.9 libcrypto.so.3 libglib-2.0.so.0
  libharfbuzz.so.0 libplacebo.so.338 libva.so.2 libmediampv.so"

[ -f "$jar" ] || { echo "no $jar: run scripts/build-mpv-linux-2-35.sh first" >&2; exit 1; }
for f in $rebuilt_libs; do
  [ -f "$rebuilt/$f" ] || { echo "no $rebuilt/$f: run scripts/build-mpv-linux-2-35.sh first" >&2; exit 1; }
done

work=$(mktemp -d "${TMPDIR:-/tmp}/mpv-smoke.XXXXXX")
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/shipped" "$work/runtime"
unzip -q "$jar" -d "$work/shipped"
cp -a "$work/shipped/." "$work/runtime/"
rm -rf "$work/runtime/META-INF"
for f in $rebuilt_libs; do cp "$rebuilt/$f" "$work/runtime/$f"; done

echo "== 1. floor of the swapped runtime"
"$here/check-floor.sh" "$work/runtime"

echo "== 2. rebuilt against shipped"
fail=0
dyn() { readelf -d "$1" | sed -n "s/.*($2).*\[\(.*\)\]/\1/p" | sort; }
exports() { nm -D --defined-only --with-symbol-versions "$1" | awk '{print $NF}' | sort -u; }
for f in libmpv.so.2.6.0 libass.so.9 libcrypto.so.3 libglib-2.0.so.0 libharfbuzz.so.0 \
         libplacebo.so.338 libva.so.2 libmediampv.so; do
  old="$work/shipped/$f" new="$work/runtime/$f"
  for tag in SONAME RUNPATH; do
    if [ "$(dyn "$old" $tag)" != "$(dyn "$new" $tag)" ]; then
      echo "FAIL $f: $tag $(dyn "$old" $tag) became $(dyn "$new" $tag)"; fail=1
    fi
  done
  needed=$(diff <(dyn "$old" NEEDED) <(dyn "$new" NEEDED) | grep '^[<>]' | tr '\n' ' ' || true)
  lost=$(comm -23 <(exports "$old") <(exports "$new"))
  added=$(comm -13 <(exports "$old") <(exports "$new"))
  printf '%-20s exports %5d (shipped %5d)  lost %3d  added %3d  NEEDED diff: %s\n' "$f" \
    "$(exports "$new" | wc -l)" "$(exports "$old" | wc -l)" \
    "$(grep -c . <<<"$lost" || true)" "$(grep -c . <<<"$added" || true)" "${needed:-none}"
  if [ -n "$lost" ]; then
    # Instances of libstdc++ templates (std::vector, std::from_chars, ...) that GCC 13 emitted and
    # GCC 11 inlines are not API; libmediampv's whole C++ side is internal (its API is the Java_*
    # JNI functions). Any other lost export fails.
    if [ "$f" = libmediampv.so ]; then api='^_Z'; else api='^_Z+N?K?(St|Sa|Ss|NSt|NKSt|TVSt|TISt|TSSt)'; fi
    [ "$f" = libmediampv.so ] || grep -E "$api" <<<"$lost" | sed 's/^/  lost (not API): /' || true
    if grep -q -v -E "$api" <<<"$lost"; then
      grep -v -E "$api" <<<"$lost" | sed 's/^/  lost: /'
      fail=1
    fi
  fi
  [ -z "$added" ] || [ "$f" = libmediampv.so ] || sed 's/^/  added: /' <<<"$added"
done

echo "== compiling the smoke program on Ubuntu 22.04"
cp "$here/smoke.c" "$work/smoke.c"
podman image exists "$image" || podman build -q -t "$image" -f "$here/Containerfile" "$here" >/dev/null
podman run --rm --security-opt label=disable -v "$work:/w" "$image" \
  gcc -O2 -Wall -o /w/smoke /w/smoke.c -ldl
cp "$mp4" "$work/video.mp4"
cp "$mkv" "$work/video.mkv"
# Latin, Hebrew (right to left, FriBidi) and CJK (fallback font through fontconfig).
printf '1\n00:00:00,000 --> 00:00:05,000\nSmoke test \xd7\xa9\xd7\x9c\xd7\x95\xd7\x9d \xe4\xbd\xa0\xe5\xa5\xbd\n' > "$work/sub.srt"

echo "== 3. plain Ubuntu 22.04"
podman run --rm --security-opt label=disable -v "$work:/w:ro" "$base" bash -euo pipefail -c '
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq >/dev/null
  apt-get install -y -qq --no-install-recommends libasound2 libdrm2 libegl1 libgl1 \
    libfontconfig1 fonts-dejavu-core libpulse0 libvulkan1 libx11-6 libx11-xcb1 libxcb1 >/dev/null
  echo "$(getconf GNU_LIBC_VERSION), $(dpkg-query -W -f "libstdc++6 \${Version}" libstdc++6)"
  /w/smoke play /w/runtime /w/video.mp4 /w/sub.srt
  /w/smoke play /w/runtime /w/video.mkv
  /w/smoke info /w/runtime > /dev/null
  echo "PASS: Ubuntu 22.04"
'

echo "== 4. this host"
"$work/smoke" play "$work/runtime" "$work/video.mp4" "$work/sub.srt"
"$work/smoke" info "$work/runtime" > "$work/info-rebuilt.txt"
host_glibc=$(getconf GNU_LIBC_VERSION | cut -d' ' -f2)
if [ "$(printf '%s\n2.38\n' "$host_glibc" | sort -V | head -1)" = "2.38" ]; then
  "$work/smoke" info "$work/shipped" > "$work/info-shipped.txt"
  if diff "$work/info-shipped.txt" "$work/info-rebuilt.txt"; then
    echo "libmpv info: identical ($(wc -l < "$work/info-rebuilt.txt") lines)"
  else
    echo "FAIL: libmpv info differs from the shipped runtime"; fail=1
  fi
else
  echo "host glibc $host_glibc cannot load the shipped runtime, info diff skipped"
fi

[ "$fail" -eq 0 ] || { echo "FAIL: see above" >&2; exit 1; }
echo "PASS: all checks"
