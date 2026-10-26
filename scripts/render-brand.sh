#!/bin/sh
# Regenerates the two brand PNGs from their SVG sources.
#
# These two files stay PNG on purpose while every screenshot in the repo is WebP: og.png is fetched
# by link unfurlers such as WhatsApp and LinkedIn, which cannot be relied on to decode WebP and
# would show no picture at all, and icon-512.png is the PWA icon, where installers expect PNG.
#
# Run it from anywhere: paths are resolved against the repository root.
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

# The PWA icon is the plain mark. A density of 512 over the 108 unit viewBox renders the vector at
# its final size rather than upscaling a small raster.
magick -background none -density 512 "$root/site/public/logo.svg" \
    -resize 512x512 -strip "$root/site/public/icon-512.png"

# The social card is drawn in HTML from the site's own stylesheet, so the type and the TV and phone
# frames match the home page hero. It is served over a local web server because Chromium will not
# load web fonts into a file:// page and quietly falls back to a system face. The PNG is forced to
# 8 bit RGB: ImageMagick wrote this card as 16 bit when it rasterised an SVG, and a 16 bit PNG is a
# file some link preview crawlers refuse, so the link arrives with no picture.
port=8719
tmp=$(mktemp -d)
python3 -m http.server "$port" --bind 127.0.0.1 --directory "$root" >/dev/null 2>&1 &
server=$!
trap 'kill "$server" 2>/dev/null; rm -rf "$tmp"' EXIT
sleep 1
card() {
    chromium-browser --headless=new --disable-gpu --no-sandbox --hide-scrollbars \
        --user-data-dir="$tmp/profile" \
        --force-device-scale-factor=1 --force-prefers-reduced-motion --window-size=1200,630 \
        --virtual-time-budget=6000 --screenshot="$tmp/og.png" \
        "http://127.0.0.1:$port/scripts/$1" 2>/dev/null
    magick "$tmp/og.png" -strip -depth 8 -define png:color-type=2 "$2"
}
card og-card.html "$root/site/public/og.png"
echo "wrote site/public/icon-512.png and site/public/og.png"
