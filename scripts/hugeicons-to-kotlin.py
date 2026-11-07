#!/usr/bin/env python3
"""Turns the Hugeicons the app uses into Compose vectors and Android vector drawables.

    npm pack @hugeicons/core-free-icons --ignore-scripts      # in an empty directory of its own
    tar xzf hugeicons-core-free-icons-*.tgz
    scripts/hugeicons-to-kotlin.py path/to/package

writes

- ui/src/shared/kotlin/com/tmplayer/ui/icons/TmIcons.kt, every glyph the Compose screens draw on
  phone, TV and desktop, as lazily built ImageVectors;
- the player's and the notifications' ic_*.xml under app/src/main/res/drawable, which the View
  based player controls and the Notification API need as resources;
- the mapping table in docs/design/icons.md.

The icons come from the free set, @hugeicons/core-free-icons (MIT, Stroke Rounded style). The
package ships each icon as a JavaScript module holding an array of [tag, attributes] pairs. This
script reads those files as text and never runs them: npm's install scripts stay off too, which is
why the package is fetched with `npm pack` rather than installed.

Only the icons listed below are written, so the app carries the few dozen it draws rather than the
six thousand in the set. To add one, find it on hugeicons.com (or by file name in dist/esm), add a
line to ICONS or DRAWABLES and run the script again. Nothing it writes is edited by hand.

Every stroke is 1.5 units on the 24 unit grid with round caps and joins, as the set is drawn (1.75
in the Android drawables, see DRAWABLE_STROKE), and
black (white in the drawables): Compose's Icon and Android's tint repaint the whole glyph in the colour the call
site asks for, so the black never shows. A "solid" icon is the same outline with its inside
filled, for the few places where a filled glyph is the state (a favourite that is set) or has to
hold its weight at size (the player's big centre play and pause).
"""

from __future__ import annotations

import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KOTLIN = ROOT / "ui/src/shared/kotlin/com/tmplayer/ui/icons/TmIcons.kt"
DRAWABLES_DIR = ROOT / "app/src/main/res/drawable"
TABLE = ROOT / "docs/design/icons.md"

STROKE = 1.5
# The drawables are the player's controls over moving video, read from a sofa on a TV, and the
# notification glyphs the status bar shrinks: a quarter unit heavier keeps them from thinning out.
DRAWABLE_STROKE = 1.75


@dataclass
class Icon:
    name: str  # the TmIcons property
    source: str  # the Hugeicons name, without the "Icon" suffix the package adds
    doc: str  # what it stands for in the app
    replaces: str = ""  # what it took over from, for the table
    mirror: bool = False  # flips in right to left layouts
    solid: bool = False  # outline filled in


# Grouped by where they are mostly seen. The order is the order of the generated file.
ICONS: list[tuple[str, list[Icon]]] = [
    ("Navigation and chrome", [
        Icon("ArrowBack", "ArrowLeft02", "Back.", "Material ArrowBack", mirror=True),
        Icon("ArrowForward", "ArrowRight02", "Forward, onward.", "Material ArrowForward", mirror=True),
        Icon("ChevronLeft", "ArrowLeft01", "A step to the previous item.", "Material KeyboardArrowLeft", mirror=True),
        Icon("ChevronRight", "ArrowRight01", "A row that opens a page.", "Material KeyboardArrowRight", mirror=True),
        Icon("ChevronDown", "ArrowDown01", "A list that opens downwards.", "Material KeyboardArrowDown, ArrowDropDown"),
        Icon("ChevronUp", "ArrowUp01", "A list that folds back up.", "Material KeyboardArrowUp"),
        Icon("Close", "Cancel01", "Close, dismiss, cancel.", "Material Close, PlayerIcons.Close"),
        Icon("Menu", "Menu01", "The navigation drawer.", "Material Menu"),
        Icon("MoreVert", "MoreVertical", "More actions.", "Material MoreVert, PlayerIcons.MoreVert"),
        Icon("Home", "Home03", "Home.", "Material Home"),
        Icon("Search", "Search01", "Search.", "Material Search"),
        Icon("SearchRemove", "SearchRemove", "A search that found nothing.", "TmIcons.Search"),
        Icon("FilterHorizontal", "FilterHorizontal", "The search's filters.", ""),
        Icon("ArrowUpLeft", "ArrowUpLeft01", "Put a suggestion into the search field to go on typing.", "", mirror=True),
        Icon("Settings", "Settings01", "Settings.", "Material Settings"),
        Icon("Refresh", "Refresh", "Refresh, try again, start over.", "Material Refresh"),
        Icon("History", "History", "Recent searches and watch history.", "TmIcons.History"),
        Icon("Clock", "Clock01", "Recent.", "TmIcons.Clock"),
        Icon("Download", "Download04", "Downloads, what is held on this device.", "TmIcons.Download"),
        Icon("Folder", "Folder01", "A Telegram chat folder.", "TmIcons.Folder"),
        Icon("FolderOpen", "FolderOpen", "A chat folder with nothing in it yet.", ""),
        Icon("Grid", "GridView", "The grid arrangement.", "TmIcons.Grid"),
        Icon("List", "LeftToRightListBullet", "The list arrangement.", "Material List", mirror=True),
        Icon("Sort", "SortingAZ02", "The chat list sorted by name.", "TmIcons.Sort"),
        Icon("OpenExternal", "LinkSquare02", "Open in another app.", "Material ExitToApp"),
        Icon("SignOut", "Logout05", "Sign out.", "Material ExitToApp", mirror=True),
    ]),
    ("Desktop window buttons", [
        Icon("WindowMinimize", "MinusSign", "Minimise the window.", "WindowGlyphs.Minimize"),
        Icon("WindowMaximize", "Stop", "Maximise the window.", "WindowGlyphs.Maximize"),
        Icon("WindowRestore", "Copy", "Restore a maximised window: one square behind another.", "WindowGlyphs.Restore"),
        Icon("WindowClose", "Cancel01", "Close the window.", "WindowGlyphs.Close"),
    ]),
    ("Actions and states", [
        Icon("Add", "Add01", "Add.", "Material Add"),
        Icon("Remove", "MinusSign", "Take one away, on a stepper.", "TmIcons.Remove"),
        Icon("Check", "Tick02", "Done, chosen.", "Material Check, PlayerIcons.Check"),
        Icon("CheckCircle", "CheckmarkCircle02", "Selected, downloaded, all clear.", "Material CheckCircle"),
        Icon("Checklist", "CheckList", "Pick several at once.", "TmIcons.Checklist"),
        Icon("Delete", "Delete02", "Delete, remove.", "Material Delete"),
        Icon("Edit", "PencilEdit02", "Rename, edit.", "Material Edit, PlayerIcons.Edit"),
        Icon("Info", "InformationCircle", "Details, about.", "Material Info"),
        Icon("Help", "HelpCircle", "The walkthrough, how this works.", "TmIcons.Help"),
        Icon("Warning", "Alert02", "Something needs attention.", "Material Warning"),
        Icon("Lock", "SquareLock02", "Locked, private, the PIN.", "Material Lock"),
        Icon("LockOpen", "SquareUnlock02", "Unlock the locked player, as the phone's pill draws it."),
        Icon("Star", "Star", "A favourite that is set.", "Material Star", solid=True),
        Icon("StarOutline", "Star", "Favourites, a favourite that is not set.", "TmIcons.StarOutline"),
        Icon("Favorite", "Favourite", "Support the project.", "Material Favorite"),
        Icon("Bookmark", "Bookmark02", "Saved Messages, bookmarks.", "TmIcons.Bookmark"),
        Icon("Pin", "Pin", "A pinned chat.", "TmIcons.Pin"),
        Icon("Share", "Share08", "Share.", "TmIcons.Share"),
        Icon("Send", "Sent", "Send.", "Material Send", mirror=True),
        Icon("Archive", "Archive03", "Archived chats.", "TmIcons.Archive"),
        Icon("Bell", "Notification01", "Notifications on.", "TmIcons.Bell"),
        Icon("BellOff", "NotificationOff01", "Notifications off.", "TmIcons.BellOff"),
        Icon("Unread", "MessageNotification01", "Unread chats.", "TmIcons.Dot"),
        Icon("Backspace", "Eraser01", "Delete the last digit on the PIN pad.", "TmIcons.Backspace", mirror=True),
        Icon("VisibilityOff", "ViewOffSlash", "Controls out of sight.", "TmIcons.VisibilityOff"),
    ]),
    ("People and chats", [
        Icon("Chats", "Message01", "The Chats section, every chat.", "TmIcons.List"),
        Icon("ChatsMany", "MessageMultiple01", "No chats to show.", ""),
        Icon("ChatsDone", "MessageDone01", "Nothing unread: up to date.", ""),
        Icon("Account", "UserCircle", "An account.", "Material AccountCircle"),
        Icon("Person", "User", "A person.", "Material Person"),
        Icon("Group", "UserGroup", "A group.", "TmIcons.Group"),
        Icon("PersonAdd", "UserAdd01", "Choose chats: a person with a plus.", ""),
        Icon("Channel", "Megaphone02", "A broadcast channel.", "TmIcons.Channel"),
        Icon("Phone", "SmartPhone01", "A phone.", "Material Phone"),
        Icon("Email", "Mail01", "Email.", "Material Email"),
    ]),
    ("Settings", [
        Icon("Language", "Globe02", "The language setting.", "TmIcons.Language"),
        Icon("TextSize", "TextFont", "The interface size.", "TmIcons.TextSize"),
        Icon("Theme", "Moon02", "The theme.", "TmIcons.Theme"),
        Icon("Palette", "PaintBoard", "Wallpaper colours.", "TmIcons.Palette"),
        Icon("Image", "Image01", "Posters, thumbnails.", "TmIcons.Image"),
        Icon("Frames", "Album02", "Preview frames over the scrub bar.", "TmIcons.Frames"),
        Icon("Logs", "File01", "Save logs.", "TmIcons.Logs"),
        Icon("Bug", "Bug01", "Report a problem.", "TmIcons.Bug"),
        Icon("Gavel", "Legal01", "Lawful use.", "TmIcons.Gavel"),
        Icon("Vibration", "Vibrate", "Haptics.", "TmIcons.Vibration"),
        Icon("Wifi", "Wifi01", "Downloads over Wi-Fi only.", "TmIcons.Wifi"),
        Icon("WifiOff", "WifiOff02", "Offline.", "TmIcons.WifiOff"),
        Icon("Mic", "Mic01", "Voice search.", "TmIcons.Mic"),
        Icon("Remote", "RemoteControl", "The remote's keys.", "TmIcons.Remote"),
    ]),
    ("Player", [
        Icon("Play", "Play", "Play.", "Material PlayArrow, PlayerIcons.Play"),
        Icon("Pause", "Pause", "Pause.", "TmIcons.Pause, PlayerIcons.Pause"),
        Icon("PlaySolid", "Play", "The player's big centre play, and play badges on artwork.", "PlayerIcons.Play", solid=True),
        Icon("PauseSolid", "Pause", "The player's big centre pause.", "PlayerIcons.Pause", solid=True),
        Icon("PlayCircle", "PlayCircle", "Continue watching with nothing started yet.", ""),
        Icon("VideoOff", "VideoOff", "A chat or a topic with no videos to play.", ""),
        Icon("SkipNext", "Next", "Next episode.", "PlayerIcons.SkipNext"),
        Icon("SkipPrevious", "Previous", "Previous episode.", "PlayerIcons.SkipPrevious"),
        Icon("Repeat", "Repeat", "A-B repeat.", "TmIcons.Repeat"),
        Icon("Episodes", "ListVideo", "The season's episodes.", "TmIcons.Episodes"),
        Icon("Subtitles", "Subtitle", "Subtitles.", "PlayerIcons.Subtitles"),
        Icon("Audio", "Headphones", "Audio track.", "PlayerIcons.Audio"),
        Icon("VolumeUp", "VolumeHigh", "Volume.", "PlayerIcons.VolumeUp"),
        Icon("VolumeDown", "VolumeLow", "Volume down.", "PlayerIcons.VolumeDown"),
        Icon("VolumeOff", "VolumeMute02", "Muted.", "PlayerIcons.VolumeOff"),
        Icon("Fullscreen", "ArrowExpand", "Full screen.", "PlayerIcons.Fullscreen"),
        Icon("FullscreenExit", "ArrowShrink", "Leave full screen.", "PlayerIcons.FullscreenExit"),
        Icon("PictureInPicture", "PictureInPictureOn", "Picture in picture.", "PlayerIcons.PictureInPicture"),
        Icon("AspectRatio", "AspectRatio", "Fit, fill, zoom.", "PlayerIcons.AspectRatio"),
        Icon("ShapeFit", "FitToScreen", "The picture shape button on Fit: the video's own shape inside the screen."),
        Icon("ShapeCrop", "Crop", "The picture shape button on Crop: filled, the edges cut."),
        Icon("ShapeStretch", "MaximizeScreen", "The picture shape button on Stretch: pulled out to the screen's edges."),
        Icon("Trailer", "Clapperboard", "A title's trailer.", "TrailerIcon"),
        Icon("Cast", "Cast", "Cast to a Chromecast or Google TV."),
        Icon("Tv", "Tv01", "A TV in the cast picker."),
        Icon("Rewind", "Backward01", "Skip back, on the cast screen."),
        Icon("FastForward", "Forward01", "Skip forward, on the cast screen."),
    ]),
]


@dataclass
class Twin:
    outline: str  # the TmIcons property it is the selected state of
    source: str  # the same Hugeicon the outline is drawn from
    cut: dict[int, str]  # shape index to how it is knocked out: "s" its stroke, "f" its inside, "sf" both
    body: list[int] | None = None  # the shapes filled in; every shape not cut when None
    mirror: bool = False
    cut_width: float = STROKE  # a cut's stroke, a little wider where it has to clear the body's own
    close_cut: bool = False  # close an open cut path, so its stroke runs along the closing edge too


# The selected states the bars and rails fill in (SelectableIcon). Body shapes are filled and keep
# their stroke, so the silhouette matches the outline exactly; cut shapes are drawn into a second
# vector that is knocked out of the fill, so the detail reads in whatever is behind the icon.
TWINS: list[Twin] = [
    # The door, open at the foot: closed and cut a little wider, so no sliver of the house's own
    # bottom stroke is left across the doorway.
    Twin("Home", "Home03", cut={1: "sf"}, cut_width=2.25, close_cut=True),
    Twin("Chats", "Message01", cut={0: "s"}),  # the two lines of text
    Twin("Settings", "Settings01", cut={1: "f"}),  # the hub
    Twin("Search", "Search01", cut={}),
    Twin("StarOutline", "Star", cut={}),
    Twin("Bookmark", "Bookmark02", cut={}),
    Twin("Folder", "Folder01", cut={}),
    Twin("Clock", "Clock01", cut={1: "s"}),  # the hands
    Twin("Play", "Play", cut={}),
    Twin("Person", "User", cut={}),
    Twin("Archive", "Archive03", cut={2: "s"}),  # the arrow
    Twin("Channel", "Megaphone02", cut={}),
]


@dataclass
class Drawable:
    file: str  # without .xml
    source: str
    doc: str
    size: int = 24  # dp, as the layout expects it
    tint: bool = False  # android:tint white, for the ones a notification or an ImageView draws untinted
    mirror: bool = False


DRAWABLES: list[Drawable] = [
    Drawable("ic_arrow_back", "ArrowLeft02", "The player's back button.", mirror=True),
    Drawable("ic_more_vert", "MoreVertical", "The player's overflow."),
    Drawable("ic_player_next", "Next", "Next episode on the player row."),
    Drawable("ic_player_previous", "Previous", "Previous episode on the player row."),
    Drawable("ic_player_forward", "Forward02", "The seek step setting."),
    Drawable("ic_episodes", "ListVideo", "The player's episodes button."),
    Drawable("ic_subtitles", "Subtitle", "Subtitles, on the player row and in settings.", size=36, tint=True),
    Drawable("ic_audio_language", "Headphones", "Audio track, on the player row and in settings.", size=36, tint=True),
    Drawable("ic_speed", "DashboardSpeed02", "Playback speed.", size=36, tint=True),
    Drawable("ic_aspect", "AspectRatio", "Fit, fill, zoom.", size=36, tint=True),
    Drawable("ic_shape_fit", "FitToScreen", "The row's picture shape button on Fit.", size=36, tint=True),
    Drawable("ic_shape_crop", "Crop", "The row's picture shape button on Crop.", size=36, tint=True),
    Drawable("ic_shape_stretch", "MaximizeScreen", "The row's picture shape button on Stretch.", size=36, tint=True),
    Drawable("ic_rotate_auto", "ScreenRotation", "Rotation follows the phone.", size=36, tint=True),
    Drawable("ic_rotate_landscape", "SmartPhoneLandscape", "Rotation held in landscape.", size=36, tint=True),
    Drawable("ic_rotate_portrait", "SmartPhone01", "Rotation held in portrait.", size=36, tint=True),
    Drawable("ic_pip", "PictureInPictureOn", "Picture in picture."),
    Drawable("ic_cast", "Cast", "The player's Cast button."),
    Drawable("ic_lock", "SquareLock02", "Lock the player's screen, on its menu."),
    Drawable("ic_lock_open", "SquareUnlock02", "Unlock the locked player."),
    Drawable("ic_brightness", "Sun03", "The brightness swipe."),
    Drawable("ic_volume", "VolumeHigh", "The volume swipe."),
    Drawable("ic_volume_off", "VolumeMute02", "The volume swipe at zero."),
    Drawable("ic_warning", "Alert02", "A playback error."),
    Drawable("ic_stat_download", "Download04", "The download notification's status bar icon.", tint=True),
    Drawable("ic_notify_pause", "Pause", "The download notification's Pause action.", tint=True),
    Drawable("ic_notify_resume", "Play", "The download notification's Resume action.", tint=True),
    Drawable("ic_notify_cancel", "Cancel01", "The download notification's Cancel action.", tint=True),
    Drawable("ic_stat_new_videos", "ListVideo", "The new videos notification's status bar icon.", tint=True),
    Drawable("ic_stat_support", "Favourite", "The sponsor reminder's status bar icon.", tint=True),
]


# ---------------------------------------------------------------------------------------------
# Reading the package

ELEMENT = re.compile(r'\[\s*"(\w+)"\s*,\s*\{(.*?)\}\s*\]', re.S)
ATTR = re.compile(r'(\w+)\s*:\s*"([^"]*)"')


@dataclass
class Shape:
    d: str
    stroke: bool
    fill: bool
    even_odd: bool
    alpha: float
    width: float = STROKE


def fmt(v: float) -> str:
    s = f"{v:.4f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def to_path(tag: str, a: dict[str, str]) -> str:
    if tag == "path":
        return a["d"]
    if tag in ("circle", "ellipse"):
        cx, cy = float(a["cx"]), float(a["cy"])
        rx = float(a.get("rx", a.get("r", "0")))
        ry = float(a.get("ry", a.get("r", "0")))
        return (f"M{fmt(cx - rx)} {fmt(cy)}a{fmt(rx)} {fmt(ry)} 0 1 0 {fmt(2 * rx)} 0"
                f"a{fmt(rx)} {fmt(ry)} 0 1 0 {fmt(-2 * rx)} 0Z")
    if tag == "rect":
        x, y = float(a.get("x", "0")), float(a.get("y", "0"))
        w, h = float(a["width"]), float(a["height"])
        rx = float(a.get("rx", a.get("ry", "0")))
        ry = float(a.get("ry", a.get("rx", "0")))
        if rx == 0:
            return f"M{fmt(x)} {fmt(y)}h{fmt(w)}v{fmt(h)}h{fmt(-w)}Z"
        return (f"M{fmt(x + rx)} {fmt(y)}h{fmt(w - 2 * rx)}a{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(rx)} {fmt(ry)}"
                f"v{fmt(h - 2 * ry)}a{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(-rx)} {fmt(ry)}h{fmt(-(w - 2 * rx))}"
                f"a{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(-rx)} {fmt(-ry)}v{fmt(-(h - 2 * ry))}"
                f"a{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(rx)} {fmt(-ry)}Z")
    raise SystemExit(f"unsupported element <{tag}>")


def load(package: Path, name: str, solid: bool = False) -> list[Shape]:
    file = package / "dist" / "esm" / f"{name}Icon.js"
    if not file.is_file():
        raise SystemExit(f"{name}Icon is not in the package")
    shapes = []
    for tag, body in ELEMENT.findall(file.read_text(encoding="utf-8")):
        a = dict(ATTR.findall(body))
        if "transform" in a:
            raise SystemExit(f"{name}: transforms are not handled; pick another icon or extend the script")
        stroke = a.get("stroke", "none") != "none"
        fill = a.get("fill", "none") != "none" or solid
        shapes.append(Shape(
            d=" ".join(to_path(tag, a).split()),
            stroke=stroke,
            fill=fill,
            even_odd=a.get("fillRule") == "evenodd",
            alpha=float(a.get("opacity", "1")),
        ))
    if not shapes:
        raise SystemExit(f"{name}Icon has no shapes the script can read")
    return shapes


# ---------------------------------------------------------------------------------------------
# Writing

def kotlin(package: Path, version: str) -> str:
    out = [
        f"// Generated by scripts/hugeicons-to-kotlin.py from @hugeicons/core-free-icons {version}.",
        "// Hugeicons, MIT licence, copyright (c) 2025 Hugeicons; see THIRD_PARTY_NOTICES.md.",
        "// Do not edit by hand: change the script's list and run it again.",
        "",
        "package com.tmplayer.ui.icons",
        "",
        "import androidx.compose.ui.graphics.Color",
        "import androidx.compose.ui.graphics.PathFillType",
        "import androidx.compose.ui.graphics.SolidColor",
        "import androidx.compose.ui.graphics.StrokeCap",
        "import androidx.compose.ui.graphics.StrokeJoin",
        "import androidx.compose.ui.graphics.vector.ImageVector",
        "import androidx.compose.ui.graphics.vector.PathParser",
        "import androidx.compose.ui.unit.dp",
        "",
        "/**",
        " * A selected glyph made from an outline: [body] is the outline's shapes filled in, and [cut], when",
        " * there is one, is the inner detail to knock out of it, so a door or a speech bubble's lines show",
        " * through in whatever is behind the icon. `SelectableIcon` draws the two; see [TmIcons.selectedOf].",
        " */",
        "class FilledIcon(val body: ImageVector, val cut: ImageVector?)",
        "",
        "/**",
        " * Every icon the app draws in Compose, on phone, TV and desktop: Hugeicons' free Stroke Rounded",
        " * set, 1.5 unit strokes on a 24 unit grid with round caps and joins.",
        " *",
        " * Drawn in black, which never shows: `Icon` paints a vector through its tint, so the colour the",
        " * call site asks for is the one that lands. The list, and which Hugeicon each one is, lives in",
        " * scripts/hugeicons-to-kotlin.py and docs/design/icons.md.",
        " */",
        "object TmIcons {",
    ]
    for area, icons in ICONS:
        out.append("")
        out.append(f"    // {area}")
        for icon in icons:
            shapes = load(package, icon.source, icon.solid)
            out.append("")
            out.append(f"    /** {icon.doc} Hugeicons {icon.source}{', filled' if icon.solid else ''}. */")
            mirror = ", autoMirror = true" if icon.mirror else ""
            if len(shapes) == 1:
                out.append(f'    val {icon.name}: ImageVector by lazy {{ icon("{icon.name}", {part(shapes[0])}{mirror}) }}')
            else:
                out.append(f"    val {icon.name}: ImageVector by lazy {{")
                out.append(f'        icon(')
                out.append(f'            "{icon.name}",')
                for s in shapes:
                    out.append(f"            {part(s)},")
                if icon.mirror:
                    out.append("            autoMirror = true,")
                out.append("        )")
                out.append("    }")
    out.append("")
    out.append("    // Selected twins: the free set has no filled style, so these are its outlines filled in")
    by_name = {i.name: i for _, icons in ICONS for i in icons}
    for tw in TWINS:
        if tw.outline not in by_name:
            raise SystemExit(f"twin of {tw.outline}, which is not in ICONS")
        if by_name[tw.outline].source != tw.source:
            raise SystemExit(f"twin of {tw.outline} is drawn from {tw.source}, the outline from {by_name[tw.outline].source}")
        shapes = load(package, tw.source)
        body_ix = tw.body if tw.body is not None else [i for i in range(len(shapes)) if i not in tw.cut]
        body = [Shape(shapes[i].d, True, True, False, shapes[i].alpha) for i in body_ix]
        cut = [
            Shape(shapes[i].d + ("Z" if tw.close_cut else ""), "s" in how, "f" in how, False, shapes[i].alpha, tw.cut_width)
            for i, how in tw.cut.items()
        ]
        mirror = "            autoMirror = true,\n" if tw.mirror or by_name[tw.outline].mirror else ""
        out.append("")
        out.append(f"    /** [{tw.outline}], selected: Hugeicons {tw.source} filled in{', its detail cut out' if cut else ''}. */")
        out.append(f"    val {tw.outline}Selected: FilledIcon by lazy {{")
        out.append("        FilledIcon(")
        out.append(f'            body = icon("{tw.outline}Selected", {", ".join(part(s) for s in body)}{", autoMirror = true" if mirror else ""}),')
        if cut:
            out.append(f'            cut = icon("{tw.outline}SelectedCut", {", ".join(part(s) for s in cut)}{", autoMirror = true" if mirror else ""}),')
        else:
            out.append("            cut = null,")
        out.append("        )")
        out.append("    }")
    out.append("")
    out.append("    /** The filled twin of [outline], for its selected state, or null where it has none. */")
    out.append("    fun selectedOf(outline: ImageVector): FilledIcon? = when (outline.name) {")
    for tw in TWINS:
        out.append(f'        "{tw.outline}" -> {tw.outline}Selected')
    out.append("        else -> null")
    out.append("    }")
    out += [
        "",
        "    /** One shape of a glyph: its SVG path data and whether it is stroked, filled or both. */",
        "    private class Part(val d: String, val stroke: Boolean, val fill: Boolean, val evenOdd: Boolean, val alpha: Float, val width: Float)",
        "",
        f"    private fun s(d: String, alpha: Float = 1f, width: Float = {fmt(STROKE)}f) = Part(d, stroke = true, fill = false, evenOdd = false, alpha = alpha, width = width)",
        f"    private fun f(d: String, evenOdd: Boolean = false, alpha: Float = 1f) = Part(d, stroke = false, fill = true, evenOdd = evenOdd, alpha = alpha, width = {fmt(STROKE)}f)",
        f"    private fun sf(d: String, alpha: Float = 1f, width: Float = {fmt(STROKE)}f) = Part(d, stroke = true, fill = true, evenOdd = false, alpha = alpha, width = width)",
        "",
        "    private val Ink = SolidColor(Color.Black)",
        "",
        "    private fun icon(name: String, vararg parts: Part, autoMirror: Boolean = false): ImageVector {",
        "        val builder = ImageVector.Builder(",
        "            name = name,",
        "            defaultWidth = 24.dp,",
        "            defaultHeight = 24.dp,",
        "            viewportWidth = 24f,",
        "            viewportHeight = 24f,",
        "            autoMirror = autoMirror,",
        "        )",
        "        for (part in parts) {",
        "            builder.addPath(",
        "                pathData = PathParser().parsePathString(part.d).toNodes(),",
        "                pathFillType = if (part.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,",
        "                fill = if (part.fill) Ink else null,",
        "                fillAlpha = part.alpha,",
        "                stroke = if (part.stroke) Ink else null,",
        "                strokeAlpha = part.alpha,",
        "                strokeLineWidth = part.width,",
        "                strokeLineCap = StrokeCap.Round,",
        "                strokeLineJoin = StrokeJoin.Round,",
        "                // The round join is not always honoured, and a sharp corner (the Archive lid's ends)",
        "                // then grows a miter spike past the glyph; a limit of 1 bevels it instead.",
        "                strokeLineMiter = 1f,",
        "            )",
        "        }",
        "        return builder.build()",
        "    }",
        "}",
        "",
    ]
    return "\n".join(out)


def part(s: Shape) -> str:
    alpha = f", alpha = {fmt(s.alpha)}f" if s.alpha != 1 else ""
    if s.stroke and s.width != STROKE:
        alpha += f", width = {fmt(s.width)}f"
    if s.stroke and s.fill:
        return f'sf("{s.d}"{alpha})'
    if s.fill:
        eo = ", evenOdd = true" if s.even_odd else ""
        return f'f("{s.d}"{eo}{alpha})'
    return f's("{s.d}"{alpha})'


def drawable(package: Path, version: str, d: Drawable) -> str:
    out = [
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
        f"<!-- {d.doc} Hugeicons {d.source} (@hugeicons/core-free-icons {version}, MIT).",
        "     Generated by scripts/hugeicons-to-kotlin.py; do not edit by hand. -->",
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        f'    android:width="{d.size}dp"',
        f'    android:height="{d.size}dp"',
        '    android:viewportWidth="24"',
        '    android:viewportHeight="24"' + ("" if d.tint or d.mirror else ">"),
    ]
    if d.mirror:
        out.append('    android:autoMirrored="true"' + ("" if d.tint else ">"))
    if d.tint:
        out.append('    android:tint="#FFFFFFFF">')
    for s in load(package, d.source):
        out.append("    <path")
        out.append(f'        android:pathData="{s.d}"')
        if s.fill:
            out.append('        android:fillColor="#FFFFFFFF"')
            if s.even_odd:
                out.append('        android:fillType="evenOdd"')
            if s.alpha != 1:
                out.append(f'        android:fillAlpha="{fmt(s.alpha)}"')
        if s.stroke:
            out.append('        android:strokeColor="#FFFFFFFF"')
            out.append(f'        android:strokeWidth="{fmt(DRAWABLE_STROKE)}"')
            out.append('        android:strokeLineCap="round"')
            out.append('        android:strokeLineJoin="round"')
            if s.alpha != 1:
                out.append(f'        android:strokeAlpha="{fmt(s.alpha)}"')
        out[-1] += " />"
    out.append("</vector>")
    return "\n".join(out) + "\n"


def table(version: str) -> str:
    out = [
        "# Icons",
        "",
        "Generated by `scripts/hugeicons-to-kotlin.py`; edit the script, not this page.",
        "",
        f"Every icon in the app is a [Hugeicons](https://hugeicons.com/) glyph from the free set,",
        f"`@hugeicons/core-free-icons` {version} (MIT, Stroke Rounded style). The app logo, the launcher",
        "icon, brand marks and flags are not icons in this sense and are not part of it.",
        "",
        "`scripts/hugeicons-to-kotlin.py` writes `TmIcons` (Compose, shared by phone, TV and desktop),",
        "the Android vector drawables the View based player controls and the notifications use, and",
        "this page. To change or add an icon, edit the lists in the script and run it again against the",
        "npm package; the script's docstring says how to fetch it without running anything from it.",
        "",
        f"Strokes are {fmt(STROKE)} units on the 24 unit grid with round caps and joins ({fmt(DRAWABLE_STROKE)} in the",
        "Android drawables, which are the player's controls over video and the notification glyphs).",
        "Icons marked as mirrored flip in right to left languages.",
        "",
        "The free set is stroke only. Where a filled glyph is the state (a favourite that is set) or has",
        "to hold its weight at size (the player's big centre play and pause, play badges on artwork), the",
        "set's own outline is filled in (`Star`, `PlaySolid`, `PauseSolid`).",
        "",
        "The bars' and rails' selected places fill in too, with a short rising drop (`SelectableIcon`).",
        "Their filled twins are made by the script from the same paths: the outline's shapes filled, and",
        "the inner detail (a door, a speech bubble's lines, the gear's hub) cut out of the fill so it",
        "shows through in the background. They are listed under Selected twins below.",
        "",
        "## Compose: `com.tmplayer.ui.icons.TmIcons`",
        "",
        "| TmIcons | Hugeicons | Mirrored | Replaces | Stands for |",
        "| --- | --- | --- | --- | --- |",
    ]
    for _, icons in ICONS:
        for i in icons:
            src = f"{i.source}{' (filled)' if i.solid else ''}"
            out.append(f"| `{i.name}` | {src} | {'yes' if i.mirror else ''} | {i.replaces} | {i.doc} |")
    out += [
        "",
        "## Selected twins: `TmIcons.selectedOf`",
        "",
        "| Outline | Hugeicons | Cut out of the fill |",
        "| --- | --- | --- |",
    ]
    for tw in TWINS:
        out.append(f"| `{tw.outline}` | {tw.source} | {', '.join(f'shape {i + 1} ({ {"s": "stroke", "f": "inside", "sf": "inside and stroke"}[h]})' for i, h in tw.cut.items()) or 'nothing'} |")
    out += [
        "",
        "## Android drawables: `app/src/main/res/drawable`",
        "",
        "| Drawable | Hugeicons | Mirrored | Size | Used for |",
        "| --- | --- | --- | --- | --- |",
    ]
    for d in DRAWABLES:
        out.append(f"| `{d.file}` | {d.source} | {'yes' if d.mirror else ''} | {d.size} dp | {d.doc} |")
    out += [
        "",
        "## Not from the set",
        "",
        "- The phone player's big centre play and pause (`PlayPauseIcon.kt`) is drawn in code, because",
        "  its two bars fold into the triangle as an animation; a filled glyph reads at that size.",
        "",
    ]
    return "\n".join(out)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    package = Path(sys.argv[1])
    meta = json.loads((package / "package.json").read_text(encoding="utf-8"))
    if meta.get("name") != "@hugeicons/core-free-icons":
        raise SystemExit("that is not @hugeicons/core-free-icons")
    if meta.get("license") != "MIT":
        raise SystemExit(f"the package's licence is {meta.get('license')!r}, not MIT: check it before using it")
    version = meta["version"]
    names = [i.name for _, icons in ICONS for i in icons]
    dupes = {n for n in names if names.count(n) > 1}
    if dupes:
        raise SystemExit(f"named twice: {sorted(dupes)}")
    KOTLIN.parent.mkdir(parents=True, exist_ok=True)
    KOTLIN.write_text(kotlin(package, version), encoding="utf-8")
    for d in DRAWABLES:
        (DRAWABLES_DIR / f"{d.file}.xml").write_text(drawable(package, version, d), encoding="utf-8")
    TABLE.write_text(table(version), encoding="utf-8")
    print(f"{len(names)} Compose icons, {len(DRAWABLES)} drawables, from {version}")


if __name__ == "__main__":
    main()
