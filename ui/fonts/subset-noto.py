#!/usr/bin/env python3
"""
Cuts the Noto fonts the desktop app bundles down to the characters it draws.

The desktop app draws its own text with Skia, so a language whose script the system has no font
for comes out as boxes. These subsets sit behind the system fonts as a last resort: Chinese
(Simplified and Traditional), Japanese, Korean, Devanagari, Bengali, Malayalam and Arabic (which
covers Persian too).

Run it again whenever a catalog in core/src/commonMain/resources/i18n brings characters outside
the margin below (a new language's name, a rare Han character):

    pip install fonttools brotli
    python3 ui/fonts/subset-noto.py <dir with the downloaded fonts>

The source fonts are the variable Noto Sans files from https://github.com/google/fonts (ofl/),
except Arabic, which is Noto Sans Arabic UI from the notofonts/arabic release NotoSansArabicUI-v2.011
(googlefonts/variable-ttf in the zip): the UI cut keeps its marks inside the line height the
other fonts use, where Noto Sans Arabic would make every Arabic row taller than the same row in
English. All of them are licensed under the SIL Open Font License 1.1; the notice the app ships is
fonts/OFL.txt beside the output. Only glyphs are dropped: the outlines, the hinting and the weight
axis stay as they are, so every weight the app asks for still draws real strokes.
The width axis of the Indic and Arabic fonts is pinned to normal, which the app always uses.

Margin, so a new string rarely needs a new run: the CJK fonts keep the common set of their script
(GB 2312 level 1, the Big5 frequent set, JIS X 0208 level 1 with all the kana, and the 2,350
KS X 1001 hangul syllables) on top of every character the catalogs use. The Indic and Arabic fonts
keep their whole Unicode blocks.
"""

import io
import json
import re
import sys
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

ROOT = Path(__file__).resolve().parents[2]
CATALOGS = ROOT / "core/src/commonMain/resources/i18n"
LANGUAGES = ROOT / "core/src/commonMain/kotlin/com/tmplayer/i18n/Languages.kt"
OUT = ROOT / "ui/src/jvmMain/resources/fonts"


def catalog_chars(*tags):
    chars = set()

    def walk(value):
        if isinstance(value, str):
            chars.update(value)
        elif isinstance(value, dict):
            for v in value.values():
                walk(v)
        elif isinstance(value, list):
            for v in value:
                walk(v)

    for tag in tags:
        walk(json.loads((CATALOGS / f"{tag}.json").read_text(encoding="utf-8")))
    return chars


def native_names():
    """Every language's own name, shown in the picker whatever the app language is."""
    text = LANGUAGES.read_text(encoding="utf-8")
    return set("".join(re.findall(r'"([^"]*)"', text)))


def decode_range(codec, lead, trail):
    chars = set()
    for a in lead:
        for b in trail:
            try:
                chars.update(bytes([a, b]).decode(codec))
            except UnicodeDecodeError:
                pass
    return chars


def block(*ranges):
    return {chr(c) for lo, hi in ranges for c in range(lo, hi + 1)}


# Punctuation and forms every CJK font keeps: CJK symbols, fullwidth forms, the middle dot.
CJK_COMMON = block((0x3000, 0x303F), (0xFF00, 0xFFEF), (0x2010, 0x2027), (0x00B7, 0x00B7))
KANA = block((0x3040, 0x30FF), (0x31F0, 0x31FF))
# Joiners and the dotted circle the shapers insert for a lone mark.
SHAPING = block((0x200C, 0x200F), (0x25CC, 0x25CC))

FONTS = [
    # (source, output, own catalogs, margin, pinned axes)
    ("NotoSansSC[wght].ttf", "NotoSansSC-Subset.ttf", ["zh-CN"],
     CJK_COMMON | decode_range("gb2312", range(0xB0, 0xD8), range(0xA1, 0xFF)), {}),
    ("NotoSansTC[wght].ttf", "NotoSansTC-Subset.ttf", ["zh-TW"],
     CJK_COMMON | decode_range("big5", range(0xA4, 0xC7), list(range(0x40, 0x7F)) + list(range(0xA1, 0xFF))), {}),
    ("NotoSansJP[wght].ttf", "NotoSansJP-Subset.ttf", ["ja"],
     CJK_COMMON | KANA | decode_range("euc_jp", range(0xB0, 0xD0), range(0xA1, 0xFF)), {}),
    ("NotoSansKR[wght].ttf", "NotoSansKR-Subset.ttf", ["ko"],
     CJK_COMMON | block((0x3130, 0x318F)) | decode_range("euc_kr", range(0xB0, 0xC9), range(0xA1, 0xFF)), {}),
    ("NotoSansDevanagari[wdth,wght].ttf", "NotoSansDevanagari-Subset.ttf", ["hi"],
     SHAPING | block((0x0900, 0x097F), (0xA8E0, 0xA8FF), (0x1CD0, 0x1CFF)), {"wdth": 100}),
    ("NotoSansBengali[wdth,wght].ttf", "NotoSansBengali-Subset.ttf", ["bn"],
     SHAPING | block((0x0980, 0x09FF), (0x0964, 0x0965)), {"wdth": 100}),
    ("NotoSansMalayalam[wdth,wght].ttf", "NotoSansMalayalam-Subset.ttf", ["ml"],
     SHAPING | block((0x0D00, 0x0D7F)), {"wdth": 100}),
    ("NotoSansArabicUI[wdth,wght].ttf", "NotoSansArabicUI-Subset.ttf", ["ar", "fa"],
     SHAPING | block((0x0600, 0x06FF), (0x0750, 0x077F), (0x08A0, 0x08FF), (0xFB50, 0xFDFF), (0xFE70, 0xFEFF)),
     {"wdth": 100}),
]


def main(source_dir):
    OUT.mkdir(parents=True, exist_ok=True)
    names = native_names()
    for source, output, tags, margin, pinned in FONTS:
        font = TTFont(Path(source_dir) / source)
        if pinned:
            font = instancer.instantiateVariableFont(font, pinned)
            # A round trip through bytes, so the subsetter reads tables that agree with each other.
            buffer = io.BytesIO()
            font.save(buffer)
            font = TTFont(io.BytesIO(buffer.getvalue()))
        cmap = font.getBestCmap()
        # The system's Latin font draws ASCII and the rest of Latin, so it is left out here.
        wanted = {ord(c) for c in catalog_chars(*tags) | names | margin if ord(c) > 0x2FF}
        unicodes = sorted(c for c in wanted if c in cmap)
        options = subset.Options()
        options.layout_features = ["*"]
        options.name_IDs = ["*"]
        options.name_languages = ["*"]
        options.notdef_outline = True
        options.hinting = True
        options.glyph_names = False
        sub = subset.Subsetter(options)
        sub.populate(unicodes=unicodes)
        sub.subset(font)
        font.save(OUT / output)
        print(f"{output}: {len(unicodes)} characters, {(OUT / output).stat().st_size / 1024:.0f} KiB")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: subset-noto.py <dir with the downloaded Noto variable fonts>")
    main(sys.argv[1])
