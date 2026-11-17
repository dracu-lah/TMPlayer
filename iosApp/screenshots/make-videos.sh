#!/bin/bash
# Makes the video the README's player screenshot plays (iosApp/screenshots/ipad/player.webp):
# "The Quiet Orbit (2024).mp4", a title invented by upstream's demo library, whose picture is an
# Unsplash photo upstream already ships (app/src/promo/res/drawable-nodpi, credited in
# THIRD_PARTY_NOTICES.md). The picture under a slow camera move, with a silent sound track, so the
# real player shows and plays it like any video. No real film, show or poster.
#
#   iosApp/screenshots/make-videos.sh <output folder>
#
# The other screenshots need no video: they are the promo build's screens over the demo library
# (`-promo`, ActivityShell). For the player, copy the file into the app's Documents and launch it
# with `-playFile "The Quiet Orbit (2024).mp4" -mpvAudio no` (docs/IOS.md, Progress).
set -euo pipefail

if [ $# -ne 1 ]; then
  echo "usage: $0 <output folder>" >&2
  exit 2
fi
root=$(cd "$(dirname "$0")/../.." && pwd)
mkdir -p "$1"
out=$(cd "$1" && pwd)
frames=$((90 * 30))
ffmpeg -hide_banner -loglevel error -y \
  -loop 1 -i "$root/app/src/promo/res/drawable-nodpi/promo_quiet_orbit_backdrop.webp" \
  -f lavfi -i anullsrc=channel_layout=stereo:sample_rate=48000 \
  -filter_complex "[0:v]scale=3840:-2,zoompan=z='1+0.12*on/$frames':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=$frames:s=1920x1080:fps=30,format=yuv420p[v]" \
  -map "[v]" -map 1:a -t 90 \
  -c:v libx264 -preset medium -crf 24 -profile:v high -c:a aac -b:a 64k \
  -movflags +faststart "$out/The Quiet Orbit (2024).mp4"
echo "$out/The Quiet Orbit (2024).mp4"
