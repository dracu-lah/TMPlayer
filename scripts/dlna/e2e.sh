#!/usr/bin/env bash
# The real-renderer check for TMPlayer's DLNA client, in one command:
#
#   scripts/dlna/e2e.sh                  # every renderer this machine can run
#   scripts/dlna/e2e.sh gmrender rygel   # some of them: gmrender, rygel, host-rygel
#   scripts/dlna/e2e.sh --build          # build the container images first if they are missing
#
# It starts software MediaRenderers on the host network (so SSDP multicast reaches them), makes a
# 120 s H.264 MP4 and MKV with ffmpeg (silent sound, so nothing is heard), and runs
# DlnaRealRendererTest (core/src/jvmTest), which drives TMPlayer's own client against each one:
# discovery, description, GetProtocolInfo, SetAVTransportURI with our DIDL, Play, the position
# moving, Pause, Play, Seek, the TV changing state by itself (Pause, Play, Stop from another control
# point), Stop, an MKV, and the two failures a TV shows most (nothing to fetch, a 404).
#
# Renderers:
#   gmrender    gmrender-resurrect in a container (Ubuntu's gmediarender, libupnp + GStreamer)
#   rygel       Rygel 0.42 Playbin in a container (Ubuntu, GUPnP + GStreamer)
#   host-rygel  this machine's own rygel, when installed (Fedora's is newer), in a private D-Bus
#               session with its config, cache and UUID in a scratch folder
# A renderer whose image or binary is missing is skipped, and said so; no podman skips both
# container ones. Everything started is removed on exit, even on Ctrl+C.
#
# The record lands in core/build/dlna-e2e/<time>/: summary.txt (pass, fail or skip per step and
# renderer), and per renderer steps.txt, soap.log (every SOAP request and answer, in full),
# http.log (every request the renderer made to the cast server) and description.xml.
#
# Settings (environment): DLNA_IFACE=<IPv4> picks the interface (default: the one with the default
# route), JAVA_HOME (default ~/.jdks/jdk-21.0.12+8), GRADLE_ARGS (default --max-workers=2 --offline).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
build=0
wanted=()
for a in "$@"; do
  case "$a" in
    --build) build=1 ;;
    gmrender|rygel|host-rygel) wanted+=("$a") ;;
    -h|--help) sed -n '2,32p' "$0"; exit 0 ;;
    *) echo "unknown argument: $a" >&2; exit 2 ;;
  esac
done
[ ${#wanted[@]} -eq 0 ] && wanted=(gmrender rygel host-rygel)

ip4="${DLNA_IFACE:-$(ip -4 route get 1.1.1.1 2>/dev/null | sed -n 's/.* src \([0-9.]*\).*/\1/p')}"
dev="$(ip -4 -o addr show | awk -v a="$ip4" 'index($4, a"/")==1 {print $2; exit}')"
if [ -z "$ip4" ] || [ -z "$dev" ]; then
  echo "SKIP: no IPv4 interface with a default route (set DLNA_IFACE)" >&2
  exit 0
fi
stamp="$(date +%Y%m%d-%H%M%S)"
out="$repo/core/build/dlna-e2e/$stamp"
work="$(mktemp -d -t tmplayer-dlna-e2e.XXXXXX)"
mkdir -p "$out"
echo "Interface $dev ($ip4); record in $out"

started_containers=()
host_pid=""
cleanup() {
  for c in "${started_containers[@]}"; do podman rm -f "$c" >/dev/null 2>&1 || true; done
  if [ -n "$host_pid" ]; then
    # dbus-run-session does not take its child down with it: the rygel with this run's config goes by name.
    pkill -f "^rygel -c $work/host-rygel/rygel.conf" 2>/dev/null || true
    kill "$host_pid" 2>/dev/null || true; wait "$host_pid" 2>/dev/null || true
  fi
  rm -rf "$work"
}
trap cleanup EXIT INT TERM

# Test videos: 120 s of a test pattern, 320x240 H.264, silent AAC (long enough that a TV stopped
# by itself mid video is not read as the end of it).
if ! command -v ffmpeg >/dev/null; then echo "SKIP: ffmpeg is not installed" >&2; exit 0; fi
for ext in mp4 mkv; do
  extra=(); [ "$ext" = mp4 ] && extra=(-movflags +faststart)
  ffmpeg -loglevel error -y -f lavfi -i testsrc=size=320x240:rate=25 -f lavfi -i anullsrc=r=44100:cl=stereo \
    -t 120 -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -shortest "${extra[@]}" "$work/check.$ext"
done

pulse=()
if [ -S "${XDG_RUNTIME_DIR:-/nonexistent}/pulse/native" ]; then
  pulse=(-v "$XDG_RUNTIME_DIR/pulse/native:/pulse" -e PULSE_SERVER=unix:/pulse)
fi
have_podman=0; command -v podman >/dev/null && have_podman=1

image_ready() { # name containerfile
  podman image exists "$1" && return 0
  [ "$build" = 1 ] || return 1
  podman build -t "$1" -f "$here/$2" "$here" >"$out/build-$1.log" 2>&1
}

names=()
for r in "${wanted[@]}"; do
  case "$r" in
    gmrender)
      if [ "$have_podman" = 0 ]; then echo "SKIP gmrender: podman is not installed"; continue; fi
      if ! image_ready tmplayer-gmrender Containerfile.gmrender; then echo "SKIP gmrender: no image (run with --build)"; continue; fi
      podman rm -f tmp-e2e-gmr >/dev/null 2>&1 || true
      podman run -d --name tmp-e2e-gmr --network host --security-opt label=disable "${pulse[@]}" tmplayer-gmrender \
        gmediarender -I "$dev" -p 49494 -f "TMP E2E GMR" -u 0e2e0000-0000-4000-8000-000000000001 --logfile=stdout \
        --gstout-initial-volume-db=-60 --gstout-audiosink="$([ ${#pulse[@]} -gt 0 ] && echo pulsesink || echo fakesink)" \
        --gstout-videosink=fakesink >/dev/null
      started_containers+=(tmp-e2e-gmr); names+=("TMP E2E GMR") ;;
    rygel)
      if [ "$have_podman" = 0 ]; then echo "SKIP rygel: podman is not installed"; continue; fi
      if ! image_ready tmplayer-rygel Containerfile.rygel; then echo "SKIP rygel: no image (run with --build)"; continue; fi
      sed -e "s/^interface=.*/interface=$dev/" -e "s/^title=.*/title=TMP E2E Rygel/" "$here/rygel.conf" >"$work/rygel-container.conf"
      podman rm -f tmp-e2e-rygel >/dev/null 2>&1 || true
      podman run -d --name tmp-e2e-rygel --network host --security-opt label=disable "${pulse[@]}" \
        -v "$work/rygel-container.conf:/root/.config/rygel.conf:ro" tmplayer-rygel >/dev/null
      started_containers+=(tmp-e2e-rygel); names+=("TMP E2E Rygel") ;;
    host-rygel)
      if ! command -v rygel >/dev/null || ! command -v dbus-run-session >/dev/null; then echo "SKIP host-rygel: rygel is not installed"; continue; fi
      mkdir -p "$work/host-rygel"
      sed -e "s/^interface=.*/interface=$dev/" -e "s/^title=.*/title=TMP E2E Host Rygel/" "$here/rygel.conf" >"$work/host-rygel/rygel.conf"
      # Its own D-Bus session and XDG folders, so the user's Rygel (if any) and settings are not touched.
      # No display: GStreamer's video goes to its fake video sink, which keeps real time.
      env -u DISPLAY -u WAYLAND_DISPLAY GST_PLUGIN_FEATURE_RANK=fakevideosink:MAX XDG_CONFIG_HOME="$work/host-rygel" XDG_CACHE_HOME="$work/host-rygel" XDG_DATA_HOME="$work/host-rygel" \
        dbus-run-session -- rygel -c "$work/host-rygel/rygel.conf" -n "$dev" >"$out/host-rygel.log" 2>&1 &
      host_pid=$!
      names+=("TMP E2E Host Rygel") ;;
  esac
done
if [ ${#names[@]} -eq 0 ]; then echo "SKIP: no renderer could be started"; exit 0; fi
sleep 4 # the renderers announce themselves

joined="$(IFS=,; echo "${names[*]}")"
echo "Renderers: $joined"
status=0
(
  cd "$repo"
  JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/jdk-21.0.12+8}" \
  DLNA_E2E_IFACE="$ip4" DLNA_E2E_NAMES="$joined" DLNA_E2E_MP4="$work/check.mp4" DLNA_E2E_MKV="$work/check.mkv" DLNA_E2E_OUT="$out" \
    ./gradlew :core:jvmTest --tests 'com.tmplayer.cast.dlna.DlnaRealRendererTest' --rerun ${GRADLE_ARGS:---max-workers=2 --offline} -q
) || status=$?
for c in "${started_containers[@]}"; do podman logs "$c" >"$out/$c.log" 2>&1 || true; done
echo
cat "$out/summary.txt" 2>/dev/null || echo "no summary written (the test did not run)"
echo "Record: $out"
exit $status
