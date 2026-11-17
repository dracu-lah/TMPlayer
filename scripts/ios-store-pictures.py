#!/usr/bin/env python3
"""The iPhone and iPad pictures of the AltStore / SideStore source (docs/IOS.md, "Pictures").

These are NOT simulator captures. TMPlayer's screens are shared Compose, so this draws the real
ones from the Android promo build (`app/src/promo`, demo library only: invented videos, the build's
own art, no account) on an emulator whose display is set to an iPhone's or an iPad's size, with the
system bars hidden so the content alone is in the picture. A real simulator capture should replace
them when a Mac is available (docs/IOS.md).

    ./gradlew :app:assemblePromo -PpromoShots
    emulator -avd tmplayer_promo -port 5558 -no-window -no-audio -gpu host -no-snapshot-load   # background
    python3 -I scripts/ios-store-pictures.py --serial emulator-5558 [--device iphone|ipad] [--theme dark|light]

The promo build is its own package (com.tmplayer.promo), so no real install or login is touched. The
display size and density are put back at the end. PNG as the emulator writes it, never recompressed:
AltStore reads PNG and JPEG only, and iosApp/screenshots is the one place PNG stays
(docs/agents/screenshots.md). Standard library only.
"""
from __future__ import annotations

import argparse
import struct
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "com.tmplayer.promo"
CAPTURE = f"{PACKAGE}/com.tmplayer.PromoCaptureActivity"
PLAYER = f"{PACKAGE}/com.tmplayer.PromoPlayerActivity"

# Pixels and density. iPhone 6.9 inch (17 Pro Max, 16 Pro Max): 1320x2868 at 3x, 440 points wide;
# Android density 480 is 3x, so the layout is 440 dp wide as on the phone. iPad 13 inch (Pro M4/M5,
# Air M2/M3): 2064x2752 at 2x, 1032 points wide; Android density 320 is 2x.
DEVICES = {
    "iphone": {"size": (1320, 2868), "density": 480},
    "ipad": {"size": (2064, 2752), "density": 320},
}

# (file, activity, extras). The first shot is what AltStore shows first.
SHOTS = [
    ("01-home", CAPTURE, ["--es", "screen", "home", "--ez", "meta", "true", "--ez", "poster_thumbs", "true"]),
    ("02-chat", CAPTURE, ["--es", "screen", "media", "--ez", "meta", "true", "--ez", "poster_thumbs", "true"]),
    ("03-video", CAPTURE, ["--es", "screen", "media", "--es", "variant", "resume", "--ez", "meta", "true"]),
    ("04-player", PLAYER, ["--ez", "playing", "true"]),
    ("05-downloads", CAPTURE, ["--es", "screen", "downloads", "--ez", "downloads_demo", "true"]),
    ("06-settings", CAPTURE, ["--es", "screen", "settings"]),
]


def adb(serial: str, *args: str, binary: bool = False, check: bool = True):
    done = subprocess.run(["adb", "-s", serial, *args], capture_output=True, check=check)
    return done.stdout if binary else done.stdout.decode("utf-8", "replace")


def png_size(data: bytes) -> tuple[int, int]:
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit("the capture is not a PNG")
    return struct.unpack(">II", data[16:24])


def settle(serial: str, seconds: float) -> None:
    """Waits for the first frame and for the bars to hide (the activity hides them once its window has focus)."""
    time.sleep(seconds)


def shoot(serial: str, name: str, activity: str, extras: list[str], theme: str, out: Path, size: tuple[int, int]) -> None:
    adb(serial, "shell", "am", "force-stop", PACKAGE)
    adb(serial, "shell", "am", "start", "-W", "-n", activity, "--ez", "nobars", "true", "--es", "theme", theme, *extras)
    settle(serial, 9.0)
    data = adb(serial, "exec-out", "screencap", "-p", binary=True)
    got = png_size(data)
    if got != size:
        raise SystemExit(f"{name}: the capture is {got[0]}x{got[1]}, wanted {size[0]}x{size[1]}")
    path = out / f"{name}.png"
    path.write_bytes(data)
    print(f"{path}  {got[0]}x{got[1]}  {len(data) // 1024} KB")


def main() -> int:
    parser = argparse.ArgumentParser(description="iPhone and iPad pictures for the AltStore source")
    parser.add_argument("--serial", default="emulator-5558")
    parser.add_argument("--device", choices=[*DEVICES, "all"], default="all")
    parser.add_argument("--theme", choices=["dark", "light"], default="dark")
    parser.add_argument("--out", default=str(ROOT / "iosApp" / "screenshots"), help="folder holding iphone/ and ipad/")
    parser.add_argument("--apk", help="install this promo APK first (adb install -r)")
    args = parser.parse_args()

    if args.apk:
        adb(args.serial, "install", "-r", "-g", args.apk)
    if PACKAGE not in adb(args.serial, "shell", "pm", "list", "packages", PACKAGE):
        raise SystemExit(f"{PACKAGE} is not installed on {args.serial}: build it with ./gradlew :app:assemblePromo -PpromoShots and pass --apk")
    names = list(DEVICES) if args.device == "all" else [args.device]
    try:
        for device in names:
            spec = DEVICES[device]
            width, height = spec["size"]
            adb(args.serial, "shell", "wm", "size", f"{width}x{height}")
            adb(args.serial, "shell", "wm", "density", str(spec["density"]))
            settle(args.serial, 2.0)
            folder = Path(args.out) / device
            folder.mkdir(parents=True, exist_ok=True)
            for name, activity, extras in SHOTS:
                shoot(args.serial, name, activity, extras, args.theme, folder, (width, height))
    finally:
        adb(args.serial, "shell", "am", "force-stop", PACKAGE, check=False)
        adb(args.serial, "shell", "wm", "size", "reset", check=False)
        adb(args.serial, "shell", "wm", "density", "reset", check=False)
    return 0


if __name__ == "__main__":
    sys.exit(main())
