#!/usr/bin/env bash
# Regenerates the desktop app icons from site/logo.svg, the same tile the site and the Android
# adaptive icon use. Needs ImageMagick 7 with librsvg and Python 3 with Pillow.
#
#   desktop/packaging/icons/generate.sh
#
# Outputs, all committed:
#   tmplayer.svg             the scalable icon (hicolor/scalable, AppImage)
#   hicolor/<n>x<n>.png      the Linux icon theme sizes
#   tmplayer.png             512 px, the Compose Linux iconFile and the AppImage icon
#   tmplayer.ico             Windows, 16 to 256 px
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
svg="$root/site/logo.svg"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# The SVG is 108 user units square at 96 dpi; this density makes librsvg rasterise it at exactly
# the target size, so each bitmap comes straight from the vector.
render() { # size out
  local density
  density="$(awk -v n="$1" 'BEGIN { printf "%.4f", 96 * n / 108 }')"
  magick -background none -density "$density" "$svg" -resize "${1}x${1}!" "PNG32:$2"
}

cp "$svg" "$here/tmplayer.svg"
mkdir -p "$here/hicolor"
for n in 16 24 32 48 64 128 256 512; do render "$n" "$here/hicolor/${n}x${n}.png"; done
cp "$here/hicolor/512x512.png" "$here/tmplayer.png"

for n in 16 20 24 32 40 48 64 128 256 1024; do render "$n" "$tmp/$n.png"; done
python3 - "$tmp" "$here" <<'EOF'
import sys
from PIL import Image
tmp, here = sys.argv[1], sys.argv[2]
def img(n): return Image.open(f"{tmp}/{n}.png").convert("RGBA")
ico_sizes = [16, 20, 24, 32, 40, 48, 64, 128, 256]
img(256).save(f"{here}/tmplayer.ico", format="ICO", sizes=[(n, n) for n in ico_sizes],
              append_images=[img(n) for n in ico_sizes if n != 256])
EOF
echo "icons written to $here"
