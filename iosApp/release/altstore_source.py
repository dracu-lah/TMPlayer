#!/usr/bin/env python3
"""TMPlayer's AltStore source for iPhone and iPad: the file AltStore and SideStore read to install the app
and offer its updates (altstore/source.json). The format is AltStore's:
https://faq.altstore.io/developers/make-a-source

  add     puts the version an IPA holds at the top of the source, with the permissions read from
          the IPA itself, which AltStore compares with the source's before it installs anything
  notes   prints a version's notes from iosApp/CHANGELOG.md: the release's text
  check   checks the source's fields, and its app identifier against iosApp/project.yml

The release workflow (.github/workflows/apple-release.yml) runs all three, and
docs/IOS.md, "Releasing", says how a release is made. Only Python's standard library is used.
"""

import argparse
import datetime
import json
import os
import plistlib
import re
import sys
import tempfile
import zipfile

CATEGORIES = {"developer", "entertainment", "games", "lifestyle", "other", "photo-video", "social", "utilities"}

# The app's icon in the repository, which the source points at.
ICON_PATH = "iosApp/TMPlayer/Assets.xcassets/AppIcon.appiconset/AppIcon.png"
SOURCE_PATH = "altstore/source.json"


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
    date = args.date or datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    entry = {
        "version": version,
        "buildVersion": build,
        "date": date,
        "localizedDescription": notes,
        "downloadURL": args.download_url,
        "size": os.path.getsize(args.ipa),
    }
    if isinstance(info.get("MinimumOSVersion"), str):
        entry["minOSVersion"] = info["MinimumOSVersion"]
    # Newest first, as AltStore reads it; the same version made again replaces the old entry.
    app["versions"] = [entry] + [v for v in app.get("versions", []) if v.get("version") != version]
    app["appPermissions"] = {"entitlements": sorted(set(args.entitlements or [])), "privacy": privacy_of(info)}
    # The latest version once more at the app's own level, where older AltStore and SideStore
    # releases read it.
    app.update(
        version=version,
        versionDate=date,
        versionDescription=notes,
        downloadURL=args.download_url,
        size=entry["size"],
    )
    if args.repo:
        raw = f"https://raw.githubusercontent.com/{args.repo}/{args.branch}"
        app["iconURL"] = f"{raw}/{ICON_PATH}"
        source["iconURL"] = app["iconURL"]
        source["website"] = f"https://github.com/{args.repo}"
        source["sourceURL"] = f"{raw}/{SOURCE_PATH}"
    if args.developer:
        app["developerName"] = args.developer
    source["identifier"] = f"{bundle}.source"
    source["featuredApps"] = [bundle]
    save_json(args.source, source)
    print(f"{bundle} {version} ({build}) is now the source's latest version")


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


def problems(source, published):
    """What is wrong with a source, as sentences; empty when nothing is."""
    found = []

    def need(where, data, key, kind):
        if not isinstance(data.get(key), kind) or (kind is str and not data[key]):
            found.append(f"{where} has no {key}")

    need("the source", source, "name", str)
    apps = source.get("apps")
    if not isinstance(apps, list) or not apps:
        return found + ["the source lists no app"]
    for app in apps:
        where = app.get("bundleIdentifier") or "an app"
        for key in ("name", "bundleIdentifier", "localizedDescription"):
            need(where, app, key, str)
        if app.get("category", "other") not in CATEGORIES:
            found.append(f"{where}: category {app.get('category')} is not one AltStore knows")
        permissions = app.get("appPermissions")
        if not isinstance(permissions, dict) or not isinstance(permissions.get("entitlements"), list) \
                or not isinstance(permissions.get("privacy"), dict):
            found.append(f"{where}: appPermissions needs an entitlements list and a privacy dictionary")
        versions = app.get("versions")
        if not isinstance(versions, list):
            found.append(f"{where} has no versions list")
            continue
        seen = set()
        for entry in versions:
            label = f"{where} {entry.get('version', '?')}"
            for key in ("version", "buildVersion", "date", "downloadURL"):
                need(label, entry, key, str)
            if not isinstance(entry.get("size"), int) or entry["size"] <= 0:
                found.append(f"{label} has no size in bytes")
            if entry.get("version") in seen:
                found.append(f"{label} is listed twice")
            seen.add(entry.get("version"))
        if published:
            need(where, app, "iconURL", str)
            need(where, app, "developerName", str)
            if not versions:
                found.append(f"{where} has no version yet")
    return found


def check(args):
    source = load_json(args.source)
    found = problems(source, args.published)
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

    adding = commands.add_parser("add", help="put an IPA's version at the top of the source")
    adding.add_argument("--source", default=SOURCE_PATH)
    adding.add_argument("--ipa", required=True)
    adding.add_argument("--download-url", required=True, help="where the release serves the IPA")
    adding.add_argument("--version", help="the version the tag names; the IPA must say the same")
    adding.add_argument("--build", help="the build number the tag names; the IPA must say the same")
    adding.add_argument("--notes-file", help="the version's notes (the notes command's output)")
    adding.add_argument("--date", help="ISO 8601; now, in UTC, when left out")
    adding.add_argument("--repo", help="owner/name on GitHub, for the icon's, the site's and the source's addresses")
    adding.add_argument("--branch", default="main", help="the branch the source and the icon are read from")
    adding.add_argument("--developer", help="the developer's name AltStore shows")
    adding.add_argument("--entitlements", nargs="*", help="a signed app's entitlements; an unsigned IPA has none")
    adding.set_defaults(run=add)

    noting = commands.add_parser("notes", help="print a version's notes from the changelog")
    noting.add_argument("--changelog", default="iosApp/CHANGELOG.md")
    noting.add_argument("--version", required=True)
    noting.add_argument("--build", default="1", help="the build number; 1 is the version's first build")
    noting.set_defaults(run=notes)

    checking = commands.add_parser("check", help="check the source")
    checking.add_argument("--source", default=SOURCE_PATH)
    checking.add_argument("--project", help="iosApp/project.yml, to compare the app identifier with")
    checking.add_argument("--published", action="store_true", help="also require what a published source needs")
    checking.set_defaults(run=check)

    args = parser.parse_args(argv)
    args.run(args)


if __name__ == "__main__":
    main()
