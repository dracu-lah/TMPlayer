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
magick -background none -density 512 "$root/site/logo.svg" \
    -resize 512x512 -strip "$root/site/icon-512.png"

# The social card is already 1200 by 630 in source units, so it only needs the text rendered at a
# higher density and scaled back down to keep the wordmark's edges clean.
magick -background none -density 288 "$root/site/og-source.svg" \
    -resize 1200x630 -background "#0e0e12" -alpha remove -alpha off \
    -strip "$root/site/og.png"

echo "wrote site/icon-512.png and site/og.png"
