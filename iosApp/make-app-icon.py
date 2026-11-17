#!/usr/bin/env python3
"""Makes the iOS and iPadOS app icon from Android's launcher icon, so both apps wear the same one.

Reads upstream's adaptive icon as it is (app/src/main/res/drawable/ic_launcher_background.xml,
ic_launcher_foreground.xml and ic_launcher_monochrome.xml) and writes the asset catalog's AppIcon:
the coloured icon, and the tinted one iOS fills with the home screen's tint, which is what
Android's themed icon (the monochrome layer) is. Each shows the middle 72 of the layers' 108
units, the part an Android launcher shows inside its mask; iOS then rounds the corners itself.

Run it again when upstream changes its launcher icon, from the repository's root:

    python3 iosApp/make-app-icon.py

It needs ImageMagick (`brew install imagemagick`). Only what Android's vector format and the
launcher's files use today is understood (paths with a fill, a plain colour shape); anything else
stops the script with its name, rather than leave something out of the icon unnoticed.
"""

import json
import pathlib
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
DRAWABLE = ROOT / "app/src/main/res/drawable"
ICONSET = ROOT / "iosApp/TMPlayer/Assets.xcassets/AppIcon.appiconset"
ANDROID = "{http://schemas.android.com/apk/res/android}"
SIZE = 1024
# The adaptive icon's layers are 108 units across; launchers show the middle 72.
LAYER = 108
SHOWN = 72


def colour(value, where):
    """Android's #RGB, #ARGB, #RRGGBB or #AARRGGBB as an SVG colour and an opacity."""
    if not value or not value.startswith("#"):
        sys.exit(f"{where}: a colour this script does not read: {value!r}")
    digits = value[1:]
    if len(digits) in (3, 4):
        digits = "".join(c * 2 for c in digits)
    if len(digits) == 6:
        return "#" + digits, 1.0
    if len(digits) == 8:
        return "#" + digits[2:], int(digits[:2], 16) / 255
    sys.exit(f"{where}: a colour this script does not read: {value!r}")


def layer_paths(file):
    """The SVG path elements of one of Android's vector drawables, 108 units across."""
    root = ET.parse(file).getroot()
    if root.tag != "vector":
        sys.exit(f"{file.name}: expected a <vector>, found <{root.tag}>")
    for side in ("viewportWidth", "viewportHeight"):
        if float(root.get(ANDROID + side, "0")) != LAYER:
            found = root.get(ANDROID + side)
            sys.exit(f"{file.name}: expected a viewport of {LAYER}, found {found}")
    out = []
    for element in root:
        if element.tag is ET.Comment:
            continue
        if element.tag != "path":
            sys.exit(f"{file.name}: <{element.tag}> is not read by this script yet")
        known = {"pathData", "fillColor", "fillAlpha", "fillType"}
        unknown = [k[len(ANDROID):] for k in element.attrib if k[len(ANDROID):] not in known]
        if unknown:
            sys.exit(f"{file.name}: path attributes this script does not read: {unknown}")
        fill, alpha = colour(element.get(ANDROID + "fillColor"), file.name)
        alpha *= float(element.get(ANDROID + "fillAlpha", "1"))
        rule = "evenodd" if element.get(ANDROID + "fillType") == "evenOdd" else "nonzero"
        data = element.get(ANDROID + "pathData")
        out.append(
            f'<path d="{data}" fill="{fill}" fill-opacity="{alpha:.4f}" fill-rule="{rule}"/>'
        )
    return out


def background_colour(file):
    """The background layer's colour: a shape with a solid fill, as upstream's is."""
    root = ET.parse(file).getroot()
    solid = root.find("solid") if root.tag == "shape" else None
    if solid is None:
        sys.exit(f"{file.name}: expected a <shape> with a <solid> colour")
    fill, alpha = colour(solid.get(ANDROID + "color"), file.name)
    if alpha != 1.0:
        sys.exit(f"{file.name}: the background must be opaque for an iOS icon")
    return fill


def render(paths, background, target):
    """Draws the middle of the layers at twice the size and scales down, for smooth edges."""
    offset = (LAYER - SHOWN) / 2
    svg = (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{SIZE * 2}" height="{SIZE * 2}" '
        f'viewBox="{offset} {offset} {SHOWN} {SHOWN}">'
        f'<rect x="0" y="0" width="{LAYER}" height="{LAYER}" fill="{background}"/>'
        + "".join(paths)
        + "</svg>"
    )
    with tempfile.TemporaryDirectory() as folder:
        source = pathlib.Path(folder) / "icon.svg"
        source.write_text(svg)
        # App Store icons are opaque: no alpha channel at all. No dates or other metadata
        # either, so running the script again changes the files only when the icon changes.
        subprocess.run(
            ["magick", str(source), "-background", background, "-flatten", "-alpha", "off",
             "-resize", f"{SIZE}x{SIZE}", "-strip", "-define", "png:exclude-chunks=date,time",
             f"PNG24:{target}"],
            check=True,
        )


def main():
    background = background_colour(DRAWABLE / "ic_launcher_background.xml")
    ICONSET.mkdir(parents=True, exist_ok=True)
    coloured = layer_paths(DRAWABLE / "ic_launcher_foreground.xml")
    render(coloured, background, ICONSET / "AppIcon.png")
    # iOS tints a grey image: the monochrome layer in white on black, as Android tints its own.
    themed = layer_paths(DRAWABLE / "ic_launcher_monochrome.xml")
    render(themed, "#000000", ICONSET / "AppIcon-tinted.png")
    universal = {"idiom": "universal", "platform": "ios", "size": f"{SIZE}x{SIZE}"}
    contents = {
        "images": [
            {**universal, "filename": "AppIcon.png"},
            {
                **universal,
                "filename": "AppIcon-tinted.png",
                "appearances": [{"appearance": "luminosity", "value": "tinted"}],
            },
        ],
        "info": {"author": "xcode", "version": 1},
    }
    (ICONSET / "Contents.json").write_text(json.dumps(contents, indent=2) + "\n")
    catalog = ICONSET.parent / "Contents.json"
    catalog.write_text(json.dumps({"info": {"author": "xcode", "version": 1}}, indent=2) + "\n")
    print(f"Wrote {ICONSET.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
