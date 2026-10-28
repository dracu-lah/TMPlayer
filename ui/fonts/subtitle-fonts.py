#!/usr/bin/env python3
"""
Cuts the Noto fonts the players fall back to when a subtitle's script has no font on the system.

A subtitle can be in any language OpenSubtitles, SubDL or MSone have, and the players draw it with
whatever the system has for that script. Android ships Noto for all of them, a browser has the
system's fonts, Windows and macOS cover every script below, but a bare Linux (the AppImage) or a
trimmed television may not, and then the line comes out as boxes. These files are the last resort:

- desktop/src/main/resources/subfonts/: the scripts the desktop UI's own subsets in
  ui/src/jvmMain/resources/fonts (Devanagari, Bengali, Malayalam, Arabic, CJK) do not cover. The
  player gives mpv's libass all of them through fontconfig (SubtitleFonts.kt).
- webapp/src/player/fonts/: every script below as WOFF2, behind @font-face rules with a
  unicode-range (subfonts.css), so a browser downloads one only for a line that needs it.
- app/src/main/assets/fonts/: Malayalam only, for MSone's subtitles on a device whose system has
  no Malayalam font (SubtitleScripts.kt). Android has shipped Noto for the other scripts since 5.0.

Each is the Regular instance of the variable Noto Sans (wght 400, wdth 100), cut to its script's
Unicode blocks plus the joiners and the dotted circle the shapers need. The players thicken it for
bold. The sources are the variable fonts in https://github.com/google/fonts (ofl/notosans<script>),
under the SIL Open Font License 1.1 (THIRD_PARTY_NOTICES.md). Run it again only to update them:

    pip install fonttools brotli
    python3 ui/fonts/subtitle-fonts.py <dir with NotoSans<Script>.ttf from google/fonts>
"""

import io
import sys
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

ROOT = Path(__file__).resolve().parents[2]
DESKTOP = ROOT / "desktop/src/main/resources/subfonts"
WEB = ROOT / "webapp/src/player/fonts"
ANDROID = ROOT / "app/src/main/assets/fonts"

# Joiners, the dotted circle a shaper puts under a lone mark, and the Indic dandas.
SHAPING = [(0x200C, 0x200D), (0x25CC, 0x25CC)]
DANDA = [(0x0964, 0x0965)]

# (script, its blocks, where it goes, its web file's short name)
SCRIPTS = [
    ("Malayalam", [(0x0D00, 0x0D7F)] + DANDA, {"web", "android"}, "ml"),
    ("Tamil", [(0x0B80, 0x0BFF), (0x11FC0, 0x11FFF)] + DANDA, {"desktop", "web"}, "ta"),
    ("Telugu", [(0x0C00, 0x0C7F)] + DANDA, {"desktop", "web"}, "te"),
    ("Kannada", [(0x0C80, 0x0CFF)] + DANDA, {"desktop", "web"}, "kn"),
    ("Sinhala", [(0x0D80, 0x0DFF), (0x111E0, 0x111FF)] + DANDA, {"desktop", "web"}, "si"),
    ("Devanagari", [(0x0900, 0x097F), (0xA8E0, 0xA8FF), (0x1CD0, 0x1CFF)], {"web"}, "hi"),
    ("Bengali", [(0x0980, 0x09FF)] + DANDA, {"web"}, "bn"),
    ("Gujarati", [(0x0A80, 0x0AFF)] + DANDA, {"desktop", "web"}, "gu"),
    ("Gurmukhi", [(0x0A00, 0x0A7F)] + DANDA, {"desktop", "web"}, "pa"),
    ("Oriya", [(0x0B00, 0x0B7F)] + DANDA, {"desktop", "web"}, "or"),
    ("Thai", [(0x0E00, 0x0E7F)], {"desktop", "web"}, "th"),
    ("Lao", [(0x0E80, 0x0EFF)], {"desktop", "web"}, "lo"),
    ("Myanmar", [(0x1000, 0x109F), (0xA9E0, 0xA9FF), (0xAA60, 0xAA7F)], {"desktop", "web"}, "my"),
    ("Khmer", [(0x1780, 0x17FF), (0x19E0, 0x19FF)], {"desktop", "web"}, "km"),
    ("Ethiopic", [(0x1200, 0x139F), (0x2D80, 0x2DDF), (0xAB00, 0xAB2F), (0x1E7E0, 0x1E7FF)], {"desktop", "web"}, "am"),
    ("Georgian", [(0x10A0, 0x10FF), (0x1C90, 0x1CBF), (0x2D00, 0x2D2F)], {"desktop", "web"}, "ka"),
    ("Armenian", [(0x0530, 0x058F), (0xFB13, 0xFB17)], {"desktop", "web"}, "hy"),
    ("Hebrew", [(0x0590, 0x05FF), (0xFB1D, 0xFB4F)], {"desktop", "web"}, "he"),
    ("Arabic", [(0x0600, 0x06FF), (0x0750, 0x077F), (0x08A0, 0x08FF), (0xFB50, 0xFDFF), (0xFE70, 0xFEFF)], {"web"}, "ar"),
]


def regular(path):
    """The variable font pinned to Regular, round tripped so the subsetter reads matching tables."""
    font = TTFont(path)
    if "fvar" in font:
        axes = {a.axisTag: (400 if a.axisTag == "wght" else 100 if a.axisTag == "wdth" else a.defaultValue)
                for a in font["fvar"].axes}
        font = instancer.instantiateVariableFont(font, axes)
    buffer = io.BytesIO()
    font.save(buffer)
    return TTFont(io.BytesIO(buffer.getvalue()))


def cut(path, ranges, flavor=None):
    font = regular(path)
    cmap = font.getBestCmap()
    unicodes = sorted(c for lo, hi in ranges + SHAPING for c in range(lo, hi + 1) if c in cmap)
    options = subset.Options()
    options.layout_features = ["*"]
    options.name_IDs = ["*"]
    options.name_languages = ["*"]
    options.notdef_outline = True
    options.hinting = True
    options.glyph_names = False
    options.flavor = flavor
    sub = subset.Subsetter(options)
    sub.populate(unicodes=unicodes)
    sub.subset(font)
    buffer = io.BytesIO()
    font.flavor = flavor
    font.save(buffer)
    return buffer.getvalue()


def unicode_range(ranges):
    """
    The CSS unicode-range: the script's own blocks only, so a dash never loads a font. The dandas
    are Devanagari's, shared by the other Indic scripts: kept in each file, claimed by none.
    """
    return ", ".join(f"U+{lo:X}" if lo == hi else f"U+{lo:X}-{hi:X}" for lo, hi in ranges if (lo, hi) not in DANDA)


def main(source_dir):
    for out in (DESKTOP, WEB, ANDROID):
        out.mkdir(parents=True, exist_ok=True)
    css = [
        "/* Written by ui/fonts/subtitle-fonts.py. A subtitle line in a script the system has no font",
        " * for falls back to these (player.css lists the family after the system's). unicode-range",
        " * means a browser fetches a file only for a line that uses its script, and local() takes an",
        " * installed copy first. Noto Sans, SIL Open Font License 1.1. */",
    ]
    for script, ranges, where, short in SCRIPTS:
        source = Path(source_dir) / f"NotoSans{script}.ttf"
        if "desktop" in where:
            data = cut(source, ranges)
            (DESKTOP / f"NotoSans{script}-Regular.ttf").write_bytes(data)
            print(f"desktop NotoSans{script}-Regular.ttf: {len(data) / 1024:.0f} KiB")
        if "android" in where:
            data = cut(source, ranges)
            (ANDROID / f"NotoSans{script}-Regular.ttf").write_bytes(data)
            print(f"android NotoSans{script}-Regular.ttf: {len(data) / 1024:.0f} KiB")
        if "web" in where:
            data = cut(source, ranges, flavor="woff2")
            (WEB / f"subfont-{short}.woff2").write_bytes(data)
            print(f"web subfont-{short}.woff2: {len(data) / 1024:.0f} KiB")
            css += [
                "@font-face {",
                "  font-family: 'TM Subtitle';",
                f"  src: local('Noto Sans {script}'), local('NotoSans{script}-Regular'), url('./fonts/subfont-{short}.woff2') format('woff2');",
                f"  unicode-range: {unicode_range(ranges)};",
                "  font-display: swap;",
                "}",
            ]
    (WEB.parent / "subfonts.css").write_text("\n".join(css) + "\n", encoding="utf-8")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: subtitle-fonts.py <dir with the downloaded Noto Sans variable fonts>")
    main(sys.argv[1])
