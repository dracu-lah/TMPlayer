#!/usr/bin/env python3
"""
Writes the app's translated name line into the Linux package metadata, from the app catalogs.

    scripts/i18n-metadata.py          # rewrite the files
    scripts/i18n-metadata.py --check  # exit 1 if they are out of date (for CI)

Reads `meta.summary` and `meta.generic_name` from core/src/commonMain/resources/i18n/<tag>.json
and writes, for every language that translates them:

- `Comment[xx]=` and `GenericName[xx]=` in desktop/packaging/linux/io.github.dracu_lah.TMPlayer.desktop
- `<summary xml:lang="xx">` in desktop/packaging/linux/io.github.dracu_lah.TMPlayer.metainfo.xml

The English lines stay as they are written in those files, and must match en.json. The name,
TMPlayer, is the same in every language, so there is no Name[xx]. Run it after a catalog changes.
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
CATALOGS = ROOT / "core/src/commonMain/resources/i18n"
DESKTOP = ROOT / "desktop/packaging/linux/io.github.dracu_lah.TMPlayer.desktop"
METAINFO = ROOT / "desktop/packaging/linux/io.github.dracu_lah.TMPlayer.metainfo.xml"


def locale(tag: str) -> str:
    """BCP 47 to the freedesktop form: pt-BR to pt_BR, es-419 to es (no region code there)."""
    language, _, region = tag.partition("-")
    return f"{language}_{region}" if len(region) == 2 else language


def meta(tag: str) -> dict:
    data = json.loads((CATALOGS / f"{tag}.json").read_text(encoding="utf-8"))
    return data.get("meta", {})


def xml_escape(text: str) -> str:
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def build():
    english = meta("en")
    translated = {}
    for path in sorted(CATALOGS.glob("*.json")):
        tag = path.stem
        if tag == "en":
            continue
        own = meta(tag)
        lines = {k: v for k, v in own.items() if k in ("summary", "generic_name") and v.strip()}
        if lines:
            translated[locale(tag)] = lines

    desktop = DESKTOP.read_text(encoding="utf-8")
    kept = [l for l in desktop.splitlines() if not re.match(r"^(Comment|GenericName)\[", l)]
    for key, field in (("summary", "Comment"), ("generic_name", "GenericName")):
        line = f"{field}={english[key]}"
        if line not in kept:
            sys.exit(f"{DESKTOP.name}: {field} does not match meta.{key} in en.json")
        at = kept.index(line) + 1
        extra = [f"{field}[{loc}]={lines[key]}" for loc, lines in sorted(translated.items()) if key in lines]
        kept[at:at] = extra
    new_desktop = "\n".join(kept) + "\n"

    metainfo = METAINFO.read_text(encoding="utf-8")
    metainfo = re.sub(r'\n *<summary xml:lang="[^"]+">[^<]*</summary>', "", metainfo)
    summary = f"<summary>{xml_escape(english['summary'])}</summary>"
    if summary not in metainfo:
        sys.exit(f"{METAINFO.name}: <summary> does not match meta.summary in en.json")
    extra = "".join(
        f'\n  <summary xml:lang="{loc}">{xml_escape(lines["summary"])}</summary>'
        for loc, lines in sorted(translated.items()) if "summary" in lines
    )
    new_metainfo = metainfo.replace(summary, summary + extra, 1)
    return new_desktop, new_metainfo, len(translated)


def main():
    new_desktop, new_metainfo, count = build()
    stale = new_desktop != DESKTOP.read_text(encoding="utf-8") or new_metainfo != METAINFO.read_text(encoding="utf-8")
    if "--check" in sys.argv:
        if stale:
            sys.exit("Linux metadata is out of date: run scripts/i18n-metadata.py")
        print(f"Linux metadata up to date ({count} translated languages)")
        return
    DESKTOP.write_text(new_desktop, encoding="utf-8")
    METAINFO.write_text(new_metainfo, encoding="utf-8")
    print(f"wrote {count} translated languages")


if __name__ == "__main__":
    main()
