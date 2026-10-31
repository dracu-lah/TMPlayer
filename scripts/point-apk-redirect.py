#!/usr/bin/env python3
"""Points tmplayer.org/apk at one release's universal APK.

The short address a TV's Downloader app is given redirects to the APK itself. A release carries
one APK, named with its version (TMPlayer-1.26.0-universal.apk), so GitHub's
releases/latest/download/<name> form cannot name it; the release job runs this after publishing,
in the same commit as the update feed, so the redirect moves with each release.

    scripts/point-apk-redirect.py 1.26.0
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
VERCEL = ROOT / "site" / "vercel.json"
# workers/download-mirror: caches this at Cloudflare's edge, since a TV's Downloader app is what
# this redirect is for and a slow route to GitHub's asset CDN hits it hardest.
MIRROR = "https://tmplayer-download-mirror.dracu.workers.dev"


def main():
    if len(sys.argv) != 2 or not re.fullmatch(r"\d+\.\d+\.\d+", sys.argv[1]):
        sys.exit("usage: point-apk-redirect.py X.Y.Z")
    version = sys.argv[1]
    url = f"{MIRROR}/download/v{version}/TMPlayer-{version}-universal.apk"
    config = json.loads(VERCEL.read_text())
    hits = 0
    for rule in config.get("redirects", []):
        if rule.get("source") in ("/apk", "/apk/"):
            rule["destination"] = url
            hits += 1
    if hits != 2:
        sys.exit(f"expected the /apk and /apk/ redirects in {VERCEL}, found {hits}")
    VERCEL.write_text(json.dumps(config, indent=2, ensure_ascii=False) + "\n")
    print(f"/apk -> {url}")


if __name__ == "__main__":
    main()
