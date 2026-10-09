#!/usr/bin/env python3
"""Writes site/latest.json, the update feed every TMPlayer reads before it asks GitHub.

The release job runs it over the files it is about to publish, so the size and the SHA-256 of
each package come from the very bytes that go up. Run by hand, --from-release reads them off a
release that is already out instead (`gh release view vX.Y.Z --json assets` carries the digests).

    scripts/write-latest-json.py --version 1.20.0 --dist dist --out site/latest.json
    scripts/write-latest-json.py --version 1.19.1 --from-release assets.json --out site/latest.json

The schema is version 1 of the plan's C1. Clients ignore keys they do not know, so adding one is
safe. Renaming a key is not, because installed copies read this file for years. A key may be left
out when the release no longer carries that file: clients treat a missing key as "nothing to
update from" and point at the release page. That is how the per-ABI APKs, the Windows portable
zip, the deb, the rpm and the Flatpak left the feed after 1.21.0.
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
# Only what a release carries; a client finds nothing under any other key and says so.
ASSETS = {
    "android-universal": "TMPlayer-{v}-universal.apk",
    "windows-x64-msi": "TMPlayer-{v}-windows-x64.msi",
    "linux-x64-appimage": "TMPlayer-{v}-x86_64.AppImage",
    "linux-x64-tarball": "TMPlayer-{v}-linux-x64.tar.xz",
}

DEFAULT_NOTES = "Fixes and improvements. The release page lists everything that changed."


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def popup_notes(notes):
    """The tag's notes as one short paragraph, which is what every installed popup shows.

    Notes are written as a "- " list, one change per line. Installed copies put the notes on the
    same line as "You have x.y.z.", so a list would read as dashes run together; each item becomes
    a sentence instead. Prose without a list passes through with its whitespace folded.
    """
    sentences = []
    for line in notes.splitlines():
        line = " ".join(line.split())
        if line.startswith(("- ", "* ")):
            line = line[2:].strip()
            if line and line[-1] not in ".!?":
                line += "."
        if line:
            sentences.append(line)
    return " ".join(sentences)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True)
    parser.add_argument("--dist", help="the folder holding the release's files")
    parser.add_argument("--from-release", help="the JSON `gh release view --json assets` printed")
    parser.add_argument("--published", help="ISO 8601 time; now when left out")
    parser.add_argument("--notes", default="", help="the tag's notes: a list of \"- \" lines, or plain sentences")
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
        "notes": popup_notes(args.notes) or DEFAULT_NOTES,
        "assets": assets,
    }
    with open(args.out, "w") as f:
        json.dump(feed, f, indent=2)
        f.write("\n")
    print(json.dumps(feed, indent=2))


if __name__ == "__main__":
    main()
