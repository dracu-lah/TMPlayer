#!/usr/bin/env python3
"""Writes site/latest.json, the update feed every TMPlayer reads before it asks GitHub.

The release job runs it over the files it is about to publish, so the size and the SHA-256 of
each package come from the very bytes that go up. Run by hand, --from-release reads them off a
release that is already out instead (`gh release view vX.Y.Z --json assets` carries the digests).

    scripts/write-latest-json.py --version 1.20.0 --dist dist --out site/latest.json
    scripts/write-latest-json.py --version 1.19.1 --from-release assets.json --out site/latest.json

The schema is version 1 of the plan's C1. Clients ignore keys they do not know, so adding one is
safe; renaming or removing one is not, because installed copies read this file for years.
"""

import argparse
import datetime
import hashlib
import json
import os
import re
import sys

REPO = "dracu-lah/TMPlayer"

# Feed key to the file name CI gives it: the same names release.yml checks before publishing.
ASSETS = {
    "android-universal": "TMPlayer-{v}-universal.apk",
    "android-arm64-v8a": "TMPlayer-{v}-arm64-v8a.apk",
    "android-armeabi-v7a": "TMPlayer-{v}-armeabi-v7a.apk",
    "windows-x64-msi": "TMPlayer-{v}-windows-x64.msi",
    "windows-x64-portable": "TMPlayer-{v}-windows-x64-portable.zip",
    "linux-x64-appimage": "TMPlayer-{v}-x86_64.AppImage",
    "linux-x64-deb": "tmplayer_{v}_amd64.deb",
    "linux-x64-rpm": "tmplayer-{v}.x86_64.rpm",
    "linux-x64-flatpak": "TMPlayer-{v}.flatpak",
    "linux-x64-tarball": "TMPlayer-{v}-linux-x64.tar.gz",
}

DEFAULT_NOTES = "Fixes and improvements. The release page lists everything that changed."


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True)
    parser.add_argument("--dist", help="the folder holding the release's files")
    parser.add_argument("--from-release", help="the JSON `gh release view --json assets` printed")
    parser.add_argument("--published", help="ISO 8601 time; now when left out")
    parser.add_argument("--notes", default="", help="two or three plain sentences for the popup")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    version = args.version.removeprefix("v")
    match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", version)
    if not match:
        sys.exit(f"{version} is not MAJOR.MINOR.PATCH")
    major, minor, patch = (int(x) for x in match.groups())
    tag = f"v{version}"

    known = {}
    if args.from_release:
        with open(args.from_release) as f:
            for asset in json.load(f)["assets"]:
                digest = asset.get("digest") or ""
                if not digest.startswith("sha256:"):
                    sys.exit(f"{asset['name']} has no sha256 digest on the release")
                known[asset["name"]] = (asset["size"], digest.removeprefix("sha256:"))
    elif args.dist:
        for name in os.listdir(args.dist):
            path = os.path.join(args.dist, name)
            if os.path.isfile(path):
                known[name] = (os.path.getsize(path), None)
    else:
        sys.exit("Pass --dist or --from-release")

    assets = {}
    for key, pattern in ASSETS.items():
        name = pattern.format(v=version)
        if name not in known:
            sys.exit(f"{name} is missing, so the feed would point at nothing")
        size, digest = known[name]
        if digest is None:
            digest = sha256(os.path.join(args.dist, name))
        assets[key] = {
            "url": f"https://github.com/{REPO}/releases/download/{tag}/{name}",
            "sha256": digest,
            "size": size,
        }

    published = args.published or datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    feed = {
        "schema": 1,
        "version": version,
        "versionCode": major * 10000 + minor * 100 + patch,
        "published": published,
        "releaseUrl": f"https://github.com/{REPO}/releases/tag/{tag}",
        "notes": " ".join(args.notes.split()) or DEFAULT_NOTES,
        "assets": assets,
    }
    with open(args.out, "w") as f:
        json.dump(feed, f, indent=2)
        f.write("\n")
    print(json.dumps(feed, indent=2))


if __name__ == "__main__":
    main()
