#!/usr/bin/env python3
"""Checks the site's translation catalogs against the Astro pages.

site/i18n/en.json is the source of every string the site shows; site/i18n/<tag>.json holds the
translations with the same keys and {placeholders}. After `pnpm -C site build`, which writes the
keys the pages looked up to site/.astro/used-keys.json, this fails when:

  - a key a page uses is missing from en.json (the build itself also stops on that)
  - an en.json key is used by nothing: no page, no component, no tmT() call in app.js
  - a catalog has a key English lacks, or a translation's {placeholders} differ from English
  - app.js calls tmT('key', 'English') with a key en.json lacks or with different English text

--status prints how complete each catalog is and which languages are published (>= 95 per cent).

Usage: scripts/site-i18n-check.py [--status] [--no-build-keys]
"""

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SITE = ROOT / "site"
I18N = SITE / "i18n"
APP_JS = SITE / "src" / "scripts" / "app.js"
USED = SITE / ".astro" / "used-keys.json"
PUBLISH_THRESHOLD = 0.95
LANGS = ["es-419", "pt-BR", "fr", "de", "it", "pl", "cs", "ru", "uk", "tr", "id", "vi", "ar", "fa", "zh-CN",
         "zh-TW", "ja", "ko", "hi", "bn", "ml"]
# Strings the layout keeps for languages that still carry them, though no page shows them today.
UNUSED_OK = {"build.languages"}
# The support popup (SupportPopup.astro) is switched off by SHOW_SUPPORT_POPUP in Base.astro since
# 2026-11-03, so the build never looks these up. Its strings stay translated for when it comes
# back; delete these four lines then. (They failed the Style job on every push until 2026-11-08.)
UNUSED_OK |= {"home.bmc_pop_title", "home.bmc_pop_line", "home.bmc_pop_note", "home.bmc_pop_later"}
# Keys of the English-only pages that read their text from en.json (the one-app comparisons under
# /alternatives/). They do not count towards a language's share, as in site/src/i18n/t.ts.
ENGLISH_ONLY = ("vs.",)
PH = re.compile(r"\{(\w+)\}")
JS_RE = re.compile(r"""tmT\(\s*'([a-z0-9_]+)'\s*,\s*'((?:[^'\\]|\\.)*)'""")


def unnest(data, prefix=""):
    flat = {}
    for k, v in data.items():
        key = f"{prefix}.{k}" if prefix else k
        if isinstance(v, dict):
            flat.update(unnest(v, key))
        elif isinstance(v, str):
            flat[key] = v
    return flat


def load(tag):
    p = I18N / f"{tag}.json"
    return unnest(json.loads(p.read_text(encoding="utf-8"))) if p.exists() else {}


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--status", action="store_true")
    ap.add_argument("--no-build-keys", action="store_true", help="skip the used/unused check")
    a = ap.parse_args()
    en = load("en")
    problems = []

    if a.status:
        keys = [k for k in en if not k.startswith("build.") and not k.startswith(ENGLISH_ONLY)]
        published = ["en"]
        for tag in LANGS:
            cat = load(tag)
            share = sum(1 for k in keys if cat.get(k)) / len(keys)
            print(f"{tag}: {share:.0%} of {len(en)} strings")
            if share >= PUBLISH_THRESHOLD:
                published.append(tag)
        print("published: " + ", ".join(published))
        return 0

    js = {}
    for key, txt in JS_RE.findall(APP_JS.read_text(encoding="utf-8")):
        txt = txt.encode().decode("unicode_escape") if "\\" in txt else txt
        full = f"js.{key}"
        if full in js and js[full] != txt:
            problems.append(f"app.js: tmT('{key}') has two English texts")
        js[full] = txt
    for k, v in js.items():
        if k not in en:
            problems.append(f"app.js: tmT('{k[3:]}') has no {k} in en.json")
        elif en[k] != v:
            problems.append(f"app.js: tmT('{k[3:]}') says {v!r}, en.json says {en[k]!r}")

    if not a.no_build_keys:
        if not USED.exists():
            problems.append(f"{USED.relative_to(ROOT)} is missing: run `pnpm -C site build` first")
        else:
            used = set(json.loads(USED.read_text()))
            for k in sorted(used - set(en)):
                problems.append(f"page uses {k}, which en.json lacks")
            for k in sorted(set(en) - used - set(js) - UNUSED_OK):
                problems.append(f"en.json: {k} is used by nothing (remove it from en.json and every catalog)")

    for tag in LANGS:
        cat = load(tag)
        for k in sorted(set(cat) - set(en)):
            problems.append(f"{tag}.json: {k} is not in en.json")
        for k, v in cat.items():
            if k in en and v and sorted(PH.findall(v)) != sorted(PH.findall(en[k])):
                problems.append(f"{tag}.json: {k} has placeholders {sorted(PH.findall(v))}, "
                                f"English has {sorted(PH.findall(en[k]))} (English is shown instead)")

    for p in problems:
        print(p)
    if problems:
        print(f"site-i18n-check: {len(problems)} problems", file=sys.stderr)
        return 1
    print(f"site-i18n-check: {len(en)} strings, catalogs consistent", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
