#!/usr/bin/env python3
"""TMPlayer's AltStore source for iPhone and iPad: the file AltStore and SideStore read to install the app
and offer its updates (altstore/source.json). The format is AltStore's:
https://faq.altstore.io/developers/make-a-source

  add          puts the version an IPA holds in the source, newest first, with its size, SHA-256,
               minimum iOS and the permissions read from the IPA itself, which AltStore compares with
               the source's before it installs anything; older versions stay, the same version and
               build made again replaces its entry
  notes        prints a version's notes from iosApp/CHANGELOG.md: the release's text
  screenshots  lists the pictures in iosApp/screenshots/iphone and /ipad as the app's screenshots
  news         adds (or replaces) a news post: what AltStore and SideStore show above the apps
  links        prints the source's address and the altstore:// and sidestore:// links that add it
  check        checks the source's fields and URLs, its versions' order, and its app identifier
               against iosApp/project.yml; --published also needs what a published source needs
               (nothing is fetched: addresses are checked for their shape, and the files they name
               in this repository for their existence)

The format is AltStore's source v2, which SideStore reads as it is. The release workflow
(.github/workflows/apple-release.yml) runs these, and docs/IOS.md, "Releasing", says how a release
is made. Only Python's standard library is used.
"""

import argparse
import datetime
import hashlib
import json
import os
import plistlib
import re
import struct
import sys
import tempfile
import urllib.parse
import zipfile

CATEGORIES = {"developer", "entertainment", "games", "lifestyle", "other", "photo-video", "social", "utilities"}

# The app's icon in the repository, which the source points at.
ICON_PATH = "iosApp/TMPlayer/Assets.xcassets/AppIcon.appiconset/AppIcon.png"
SOURCE_PATH = "altstore/source.json"
# The App Store style pictures: iosApp/screenshots/iphone/*.png and iosApp/screenshots/ipad/*.png,
# listed in name order, which is the order AltStore shows them in.
SCREENSHOTS_PATH = "iosApp/screenshots"
SCREENSHOT_DEVICES = ("iphone", "ipad")
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RAW_FILE = re.compile(r"^https://raw\.githubusercontent\.com/[^/]+/[^/]+/[^/]+/(.+)$")
HEX_COLOUR = re.compile(r"^#?[0-9A-Fa-f]{6}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")


def fail(message):
    raise SystemExit(f"altstore_source: {message}")


def read_text(path):
    """A text file's content, closed again at once; a file that cannot be read stops the script."""
    try:
        with open(path, encoding="utf-8") as file:
            return file.read()
    except (OSError, UnicodeDecodeError) as error:
        fail(f"cannot read {path}: {error}")


def load_json(path):
    try:
        with open(path, encoding="utf-8") as file:
            return json.load(file)
    except (OSError, ValueError) as error:
        fail(f"cannot read {path}: {error}")


def save_json(path, data):
    """Written whole or not at all: a temporary file beside it, then renamed over it."""
    folder = os.path.dirname(os.path.abspath(path))
    handle, temporary = tempfile.mkstemp(dir=folder, prefix=".source-", suffix=".json")
    try:
        with os.fdopen(handle, "w", encoding="utf-8") as file:
            json.dump(data, file, indent=2, ensure_ascii=False)
            file.write("\n")
        os.replace(temporary, path)
    except BaseException:
        if os.path.exists(temporary):
            os.unlink(temporary)
        raise


def read_ipa(path):
    """The app's Info.plist inside the IPA, and whether the app is signed."""
    try:
        with zipfile.ZipFile(path) as ipa:
            names = ipa.namelist()
            plists = [n for n in names if re.fullmatch(r"Payload/[^/]+\.app/Info\.plist", n)]
            if len(plists) != 1:
                fail(f"{path} holds {len(plists)} apps, not one (Payload/<name>.app/Info.plist)")
            info = plistlib.loads(ipa.read(plists[0]))
            app = plists[0][: -len("Info.plist")]
            signed = any(n.startswith(app + "_CodeSignature/") for n in names)
    except (OSError, zipfile.BadZipFile, plistlib.InvalidFileException) as error:
        fail(f"cannot read {path}: {error}")
    for key in ("CFBundleIdentifier", "CFBundleShortVersionString", "CFBundleVersion"):
        if not isinstance(info.get(key), str) or not info[key]:
            fail(f"{path}: Info.plist has no {key}")
    return info, signed


def privacy_of(info):
    """Every usage description the app declares, which AltStore wants listed as they are."""
    return {k: v for k, v in sorted(info.items()) if k.endswith("UsageDescription") and isinstance(v, str)}


def the_app(source, bundle):
    apps = [a for a in source.get("apps", []) if a.get("bundleIdentifier") == bundle]
    if len(apps) != 1:
        listed = ", ".join(a.get("bundleIdentifier", "?") for a in source.get("apps", [])) or "none"
        fail(
            f"the IPA is {bundle}, the source lists {listed}: the source's bundleIdentifier and "
            "iosApp/project.yml's PRODUCT_BUNDLE_IDENTIFIER must be the same"
        )
    return apps[0]


def now():
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def sha256_of(path):
    digest = hashlib.sha256()
    try:
        with open(path, "rb") as file:
            for block in iter(lambda: file.read(1 << 20), b""):
                digest.update(block)
    except OSError as error:
        fail(f"cannot read {path}: {error}")
    return digest.hexdigest()


def version_key(entry):
    """Newest sorts last: the version's numbers, then the build's."""
    numbers = tuple(int(n) for n in re.findall(r"\d+", str(entry.get("version", ""))))
    builds = re.findall(r"\d+", str(entry.get("buildVersion", "")))
    return numbers, int(builds[0]) if builds else 0


def newest_first(versions):
    return sorted(versions, key=version_key, reverse=True)


def image_size(path):
    """(width, height) of a PNG or JPEG, read from its header; None for anything else."""
    try:
        with open(path, "rb") as file:
            head = file.read(26)
            if head[:8] == b"\x89PNG\r\n\x1a\n" and head[12:16] == b"IHDR":
                return struct.unpack(">II", head[16:24])
            if head[:2] == b"\xff\xd8":
                file.seek(2)
                while True:
                    marker = file.read(4)
                    if len(marker) < 4 or marker[0] != 0xFF:
                        return None
                    length = struct.unpack(">H", marker[2:4])[0]
                    if marker[1] in (0xC0, 0xC1, 0xC2):
                        frame = file.read(5)
                        height, width = struct.unpack(">HH", frame[1:5])
                        return width, height
                    file.seek(length - 2, os.SEEK_CUR)
    except (OSError, struct.error):
        return None
    return None


def raw_base(repo, branch):
    return f"https://raw.githubusercontent.com/{repo}/{branch}"


def screenshots_of(root, repo, branch, folder=SCREENSHOTS_PATH):
    """{"iphone": [...], "ipad": [...]} from the pictures in <folder>/iphone and /ipad, each with
    the raw address and the size AltStore wants. A picture whose size cannot be read stops the
    script: AltStore v2 lays the pictures out by it."""
    found = {}
    for device in SCREENSHOT_DEVICES:
        directory = os.path.join(root, folder, device)
        names = sorted(n for n in (os.listdir(directory) if os.path.isdir(directory) else [])
                       if n.lower().endswith((".png", ".jpg", ".jpeg")))
        pictures = []
        for name in names:
            size = image_size(os.path.join(directory, name))
            if size is None:
                fail(f"{folder}/{device}/{name} is not a PNG or JPEG whose size can be read")
            url = f"{raw_base(repo, branch)}/{folder}/{device}/{urllib.parse.quote(name)}"
            pictures.append({"url": url, "width": size[0], "height": size[1]})
        found[device] = pictures
    return found


def put_screenshots(app, root, repo, branch):
    """Sets the app's screenshots from the folder; a folder with no pictures leaves them as they are."""
    found = screenshots_of(root, repo, branch)
    if any(found.values()):
        app["screenshots"] = found
        return True
    return False


def slug(text):
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-") or "post"


def put_news(source, entry, keep):
    """The post in, the same identifier replaced, newest first by date, the oldest dropped past `keep`."""
    others = [n for n in source.get("news", []) if n.get("identifier") != entry["identifier"]]
    source["news"] = sorted(others + [entry], key=lambda n: str(n.get("date", "")), reverse=True)[:keep]


def add(args):
    source = load_json(args.source)
    info, signed = read_ipa(args.ipa)
    bundle = info["CFBundleIdentifier"]
    version = info["CFBundleShortVersionString"]
    build = info["CFBundleVersion"]
    if args.version and args.version != version:
        fail(f"the tag says {args.version} and the IPA says {version}")
    if args.build and args.build != build:
        fail(f"the tag says build {args.build} and the IPA says build {build}")
    if signed and not args.entitlements:
        print(
            "altstore_source: the app is signed; list its entitlements with --entitlements, or "
            "AltStore may refuse it",
            file=sys.stderr,
        )
    app = the_app(source, bundle)
    notes = read_text(args.notes_file).strip() if args.notes_file else ""
    digest = sha256_of(args.ipa)
    earlier = next((v for v in app.get("versions", [])
                    if v.get("version") == version and v.get("buildVersion") == build), None)
    # Run again for the same IPA, the entry keeps its date, so the file does not change.
    if args.date:
        date = args.date
    elif earlier and earlier.get("sha256") == digest and earlier.get("date"):
        date = earlier["date"]
    else:
        date = now()
    entry = {
        "version": version,
        "buildVersion": build,
        "date": date,
        "localizedDescription": notes,
        "downloadURL": args.download_url,
        "size": os.path.getsize(args.ipa),
        "sha256": digest,
    }
    if isinstance(info.get("MinimumOSVersion"), str):
        entry["minOSVersion"] = info["MinimumOSVersion"]
    # Newest first, as AltStore reads it; older versions stay, so a device still on one is offered
    # the update and a pinned install can name it. The same version and build made again replaces
    # its entry (an iOS-only fix, the same version with another build, is an entry of its own).
    others = [v for v in app.get("versions", [])
              if not (v.get("version") == version and v.get("buildVersion") == build)]
    app["versions"] = newest_first(others + [entry])
    app["appPermissions"] = {"entitlements": sorted(set(args.entitlements or [])), "privacy": privacy_of(info)}
    # The latest version once more at the app's own level, where older AltStore and SideStore
    # releases read it.
    top = app["versions"][0]
    app.update(
        version=top["version"],
        versionDate=top["date"],
        versionDescription=top.get("localizedDescription", ""),
        downloadURL=top["downloadURL"],
        size=top["size"],
    )
    if args.repo:
        raw = raw_base(args.repo, args.branch)
        app["iconURL"] = f"{raw}/{ICON_PATH}"
        source["iconURL"] = app["iconURL"]
        source["website"] = source.get("website") or f"https://github.com/{args.repo}"
        source["sourceURL"] = f"{raw}/{SOURCE_PATH}"
        put_screenshots(app, args.root, args.repo, args.branch)
    if args.developer:
        app["developerName"] = args.developer
    source["identifier"] = f"{bundle}.source"
    source["featuredApps"] = [bundle]
    if args.news:
        post = {
            "identifier": f"release-{slug(version)}-{slug(build)}",
            "title": f"{app.get('name', 'TMPlayer')} {version}" + (f" (build {build})" if build != "1" else ""),
            "caption": notes.splitlines()[0].lstrip("-* ").strip() if notes else "A new version is out.",
            "date": date,
            "tintColor": app.get("tintColor") or source.get("tintColor") or "#2AABEE",
            "notify": True,
            "appID": bundle,
        }
        if args.release_url:
            post["url"] = args.release_url
        put_news(source, post, args.news_keep)
    save_json(args.source, source)
    print(f"{bundle} {version} ({build}) is now the source's latest version")


def screenshots(args):
    source = load_json(args.source)
    apps = source.get("apps", [])
    if len(apps) != 1:
        fail(f"{args.source} must list exactly one app")
    if not put_screenshots(apps[0], args.root, args.repo, args.branch):
        fail(f"no pictures in {SCREENSHOTS_PATH}/iphone or /ipad, so nothing to list")
    save_json(args.source, source)
    counts = ", ".join(f"{len(apps[0]['screenshots'][d])} for the {d}" for d in SCREENSHOT_DEVICES)
    print(f"screenshots: {counts}")


def news(args):
    source = load_json(args.source)
    date = args.date or now()
    entry = {
        "identifier": args.identifier or f"{slug(args.title)}-{date[:10]}",
        "title": args.title,
        "caption": args.caption or "",
        "date": date,
        "tintColor": args.tint_color or source.get("tintColor") or "#2AABEE",
        "notify": bool(args.notify),
    }
    if args.url:
        entry["url"] = args.url
    if args.image_url:
        entry["imageURL"] = args.image_url
    if args.app:
        entry["appID"] = args.app
    put_news(source, entry, args.keep)
    found = [p for p in problems(source, False, args.root) if "news" in p]
    if found:
        fail("; ".join(found))
    save_json(args.source, source)
    print(f"news: {entry['identifier']} is in the source")


def links(args):
    url = f"{raw_base(args.repo, args.branch)}/{SOURCE_PATH}"
    quoted = urllib.parse.quote(url, safe=":/")
    print(f"source:    {url}")
    print(f"altstore:  altstore://source?url={quoted}")
    print(f"sidestore: sidestore://source?url={quoted}")


def notes(args):
    text = read_text(args.changelog)
    text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
    build = args.build or "1"
    if not re.fullmatch(r"[1-9][0-9]*", build):
        fail(f"the build {build} is not a whole number from 1")
    # A version's first build is "## 1.28.5", a later one, an iOS-only fix, "## 1.28.5 build 2".
    label = args.version if build == "1" else f"{args.version} build {build}"
    after = r"(?! build)" if build == "1" else r" build " + build
    heading = re.compile(r"^## \[?" + re.escape(args.version) + r"\]?" + after + r"(?=[\s(]|$)", re.M)
    found = heading.search(text)
    if not found:
        fail(f"{args.changelog} has no '## {label}' section: write the release's notes there first")
    rest = text[found.end():]
    body = rest[rest.find("\n") + 1:] if "\n" in rest else ""
    following = re.search(r"^## ", body, re.M)
    body = (body[: following.start()] if following else body).strip()
    if not body:
        fail(f"the '## {label}' section of {args.changelog} is empty")
    print(body)


def project_bundle(path):
    """The app target's PRODUCT_BUNDLE_IDENTIFIER in XcodeGen's project.yml."""
    lines = read_text(path).splitlines()
    try:
        start = lines.index("targets:")
        target = next(i for i in range(start, len(lines)) if re.fullmatch(r"  TMPlayer:\s*", lines[i]))
    except (ValueError, StopIteration):
        fail(f"{path} has no TMPlayer target")
    for line in lines[target + 1:]:
        if re.match(r"  \S", line):
            break
        found = re.match(r"\s+PRODUCT_BUNDLE_IDENTIFIER:\s*(\S+)", line)
        if found:
            return found.group(1)
    fail(f"{path}: the TMPlayer target has no PRODUCT_BUNDLE_IDENTIFIER")


def url_problem(where, key, value, local_root=None):
    """What is wrong with one address's shape, or None. Nothing is fetched. With a root, an address
    that names a file of this repository on raw.githubusercontent.com must name one that exists."""
    if not isinstance(value, str) or not value:
        return f"{where} has no {key}"
    parts = urllib.parse.urlsplit(value)
    if parts.scheme != "https" or not parts.netloc or re.search(r"\s", value):
        return f"{where}: {key} {value!r} is not an https address"
    if local_root:
        raw = RAW_FILE.match(value)
        if raw and not os.path.isfile(os.path.join(local_root, urllib.parse.unquote(raw.group(1)))):
            return f"{where}: {key} names {urllib.parse.unquote(raw.group(1))}, which is not in the repository"
    return None


def problems(source, published, root=None):
    """What is wrong with a source, as sentences; empty when nothing is. With `published`, also what
    a source AltStore and SideStore install from needs; `root` (a checkout) lets that check that the
    files its raw addresses name exist."""
    found = []

    def need(where, data, key, kind):
        if not isinstance(data.get(key), kind) or (kind is str and not data[key]):
            found.append(f"{where} has no {key}")

    def address(where, data, key, required=False):
        if key in data or required:
            bad = url_problem(where, key, data.get(key), root if published else None)
            if bad:
                found.append(bad)

    def colour(where, data):
        if "tintColor" in data and not (isinstance(data["tintColor"], str) and HEX_COLOUR.match(data["tintColor"])):
            found.append(f"{where}: tintColor {data['tintColor']!r} is not a six digit hex colour")

    need("the source", source, "name", str)
    colour("the source", source)
    for key in ("iconURL", "headerURL", "website", "sourceURL"):
        address("the source", source, key)
    listed = [a.get("bundleIdentifier") for a in source.get("apps", []) if isinstance(a, dict)]
    posts = source.get("news", [])
    if not isinstance(posts, list):
        found.append("the source's news is not a list")
        posts = []
    seen_posts = set()
    for post in posts:
        label = f"news post {post.get('identifier', '?')}" if isinstance(post, dict) else "a news post"
        if not isinstance(post, dict):
            found.append(f"{label} is not an object")
            continue
        for key in ("identifier", "title", "date"):
            need(label, post, key, str)
        if post.get("identifier") in seen_posts:
            found.append(f"{label} is listed twice")
        seen_posts.add(post.get("identifier"))
        for key in ("url", "imageURL"):
            address(label, post, key)
        colour(label, post)
        if "appID" in post and post["appID"] not in listed:
            found.append(f"{label} names the app {post['appID']}, which the source does not list")
        if "notify" in post and not isinstance(post["notify"], bool):
            found.append(f"{label}: notify must be true or false")
    apps = source.get("apps")
    if not isinstance(apps, list) or not apps:
        return found + ["the source lists no app"]
    for app in apps:
        where = app.get("bundleIdentifier") or "an app"
        for key in ("name", "bundleIdentifier", "localizedDescription"):
            need(where, app, key, str)
        if app.get("category", "other") not in CATEGORIES:
            found.append(f"{where}: category {app.get('category')} is not one AltStore knows")
        colour(where, app)
        address(where, app, "iconURL")
        permissions = app.get("appPermissions")
        if not isinstance(permissions, dict) or not isinstance(permissions.get("entitlements"), list) \
                or not isinstance(permissions.get("privacy"), dict):
            found.append(f"{where}: appPermissions needs an entitlements list and a privacy dictionary")
        shots = app.get("screenshots", {})
        if isinstance(shots, dict):
            for device, pictures in shots.items():
                if device not in SCREENSHOT_DEVICES or not isinstance(pictures, list):
                    found.append(f"{where}: screenshots has '{device}', which is not an iphone or ipad list")
                    continue
                for number, picture in enumerate(pictures, 1):
                    if not isinstance(picture, dict):
                        found.append(f"{where}: {device} screenshot {number} is not an object")
                        continue
                    address(f"{where}: {device} screenshot {number}", picture, "url", True)
                    for key in ("width", "height"):
                        if key in picture and not (isinstance(picture[key], int) and picture[key] > 0):
                            found.append(f"{where}: {device} screenshot {number} has a bad {key}")
        elif not isinstance(shots, list):
            found.append(f"{where}: screenshots must be an object with iphone and ipad lists")
        versions = app.get("versions")
        if not isinstance(versions, list):
            found.append(f"{where} has no versions list")
            continue
        seen = set()
        for entry in versions:
            label = f"{where} {entry.get('version', '?')}"
            for key in ("version", "buildVersion", "date", "downloadURL"):
                need(label, entry, key, str)
            address(label, entry, "downloadURL")
            if not isinstance(entry.get("size"), int) or entry["size"] <= 0:
                found.append(f"{label} has no size in bytes")
            if "sha256" in entry and not (isinstance(entry["sha256"], str) and SHA256.match(entry["sha256"])):
                found.append(f"{label}: sha256 is not 64 lower case hex digits")
            if "minOSVersion" in entry and not (isinstance(entry["minOSVersion"], str)
                                                 and re.fullmatch(r"[0-9]+(\.[0-9]+){0,2}", entry["minOSVersion"])):
                found.append(f"{label}: minOSVersion {entry['minOSVersion']!r} is not a version like 26.4")
            key = (entry.get("version"), entry.get("buildVersion"))
            if key in seen:
                found.append(f"{label} build {entry.get('buildVersion')} is listed twice")
            seen.add(key)
        if versions and [version_key(v) for v in versions] != sorted((version_key(v) for v in versions), reverse=True):
            found.append(f"{where}: versions must be listed newest first")
        if published:
            need(where, app, "iconURL", str)
            need(where, app, "developerName", str)
            if not versions:
                found.append(f"{where} has no version yet")
            for entry in versions:
                label = f"{where} {entry.get('version', '?')}"
                download = entry.get("downloadURL") or ""
                if not (download.endswith(".ipa") and "/releases/download/" in download):
                    found.append(f"{label}: downloadURL must be a release's .ipa (.../releases/download/<tag>/<name>.ipa)")
                if not isinstance(entry.get("sha256"), str):
                    found.append(f"{label} has no sha256")
            if versions:
                top = max(versions, key=version_key)
                if app.get("version") != top.get("version") or app.get("downloadURL") != top.get("downloadURL"):
                    found.append(f"{where}: the app's own version and downloadURL are not the newest version's")
    return found


def check(args):
    source = load_json(args.source)
    found = problems(source, args.published, args.root)
    if args.project:
        bundle = project_bundle(args.project)
        listed = [a.get("bundleIdentifier") for a in source.get("apps", [])]
        if bundle not in listed:
            found.append(f"{args.project} builds {bundle}, the source lists {', '.join(map(str, listed))}")
    if found:
        fail("; ".join(found))
    print(f"{args.source} is in order")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    commands = parser.add_subparsers(dest="command", required=True)

    def common(sub, repo_required=False):
        sub.add_argument("--source", default=SOURCE_PATH)
        sub.add_argument("--root", default=ROOT, help="the checkout the pictures and files are read from")
        sub.add_argument("--repo", required=repo_required, help="owner/name on GitHub, for the raw addresses")
        sub.add_argument("--branch", default="main", help="the branch the source, the icon and the pictures are read from")

    adding = commands.add_parser("add", help="put an IPA's version in the source, newest first")
    common(adding)
    adding.add_argument("--ipa", required=True)
    adding.add_argument("--download-url", required=True, help="where the release serves the IPA")
    adding.add_argument("--version", help="the version the tag names; the IPA must say the same")
    adding.add_argument("--build", help="the build number the tag names; the IPA must say the same")
    adding.add_argument("--notes-file", help="the version's notes (the notes command's output)")
    adding.add_argument("--date", help="ISO 8601; the entry's own date again for the same IPA, else now in UTC")
    adding.add_argument("--developer", help="the developer's name AltStore shows")
    adding.add_argument("--entitlements", nargs="*", help="a signed app's entitlements; an unsigned IPA has none")
    adding.add_argument("--news", action="store_true", help="also post the release to the source's news")
    adding.add_argument("--release-url", help="the news post's link (the release page)")
    adding.add_argument("--news-keep", type=int, default=20, help="how many news posts the source keeps")
    adding.set_defaults(run=add)

    noting = commands.add_parser("notes", help="print a version's notes from the changelog")
    noting.add_argument("--changelog", default="iosApp/CHANGELOG.md")
    noting.add_argument("--version", required=True)
    noting.add_argument("--build", default="1", help="the build number; 1 is the version's first build")
    noting.set_defaults(run=notes)

    shooting = commands.add_parser("screenshots", help="list iosApp/screenshots/iphone and /ipad in the source")
    common(shooting, repo_required=True)
    shooting.set_defaults(run=screenshots)

    posting = commands.add_parser("news", help="add a news post (the same identifier replaces it)")
    common(posting)
    posting.add_argument("--title", required=True)
    posting.add_argument("--caption", help="one or two sentences under the title")
    posting.add_argument("--identifier", help="the post's own name; the title and the day when left out")
    posting.add_argument("--date", help="ISO 8601; now, in UTC, when left out")
    posting.add_argument("--url", help="where the post opens")
    posting.add_argument("--image-url", help="a picture for the post")
    posting.add_argument("--tint-color", help="a hex colour, the source's when left out")
    posting.add_argument("--app", help="the bundle identifier of the app the post is about")
    posting.add_argument("--notify", action="store_true", help="ask AltStore to notify about the post")
    posting.add_argument("--keep", type=int, default=20, help="how many posts the source keeps")
    posting.set_defaults(run=news)

    linking = commands.add_parser("links", help="print the source's address and the links that add it")
    linking.add_argument("--repo", required=True, help="owner/name on GitHub")
    linking.add_argument("--branch", default="main")
    linking.set_defaults(run=links)

    checking = commands.add_parser("check", help="check the source")
    common(checking)
    checking.add_argument("--project", help="iosApp/project.yml, to compare the app identifier with")
    checking.add_argument("--published", action="store_true", help="also require what a published source needs")
    checking.set_defaults(run=check)

    args = parser.parse_args(argv)
    args.run(args)


if __name__ == "__main__":
    main()
