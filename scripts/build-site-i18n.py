#!/usr/bin/env python3
"""Localizes the site: English pages in, one copy per language out.

The English pages under site/ are the source. Every piece of text that a reader sees carries a
key in the markup:

  <p data-i18n="home.lede">A free, unofficial Telegram video player ...</p>
  <img alt="..." data-i18n-alt="home.tv_grid_alt">
  <meta name="description" content="..." data-i18n-content="home.description">

From those keys the script writes site/i18n/en.json, the catalog that is translated into
site/i18n/<tag>.json. For every language whose catalog is complete enough to publish, it then
renders site/<lang>/<page>/index.html with the translated text, `lang`, a canonical URL of its
own, `hreflang` alternates with `x-default`, `og:locale`, localized internal links, the language
picker in the header and footer, and the strings app.js needs. Nothing redirects: a reader whose
browser prefers another published language gets a quiet suggestion bar from app.js.

Inline markup inside a sentence becomes a placeholder, because a translator handed anything shaped
like <a>...</a> keeps it verbatim and leaves its text in English. So

  <p data-i18n="home.quiet">Android phones. <a href="/install/">How to install</a></p>

is catalogued as two strings, "home.quiet" = "Android phones. {a1}" and
"home.quiet__a1" = "How to install", and stitched back together when a page is rendered.
Icons, code and separators stay where they are and are never sent for translation.

A language is published only once its catalog covers PUBLISH_THRESHOLD of the keys. Until then
it is simply not generated, so a half-translated catalog never puts English pages under /es/.
Missing keys in a published language fall back to English one string at a time.

Usage:
  scripts/build-site-i18n.py           extract en.json and render every published language
  scripts/build-site-i18n.py --tag     add data-i18n keys to text that has none yet, then build
  scripts/build-site-i18n.py --check   change nothing; exit 1 if anything is untagged or stale
  scripts/build-site-i18n.py --status  print how complete each catalog is

Run it after editing any localized page, app.js strings or a catalog, and commit what it writes.
CI runs --check, so a page edited without a rebuild fails the style workflow. Translating is a
separate step: the catalogs are filled by hand (Claude translates, people who speak the language
correct), with the same keys and {placeholders} as en.json.
"""

import argparse
import hashlib
import html
import json
import re
import shutil
import sys
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SITE = ROOT / "site"
I18N = SITE / "i18n"
ORIGIN = "https://tmplayer.org"

# Page id and its directory under site/. Only these pages are localized; the changelog, the legal
# pages, security and signing stay in English and localized pages link to the English copies.
PAGES = {
    "home": "",
    "features": "features/",
    "download": "download/",
    "install": "install/",
    "android_tv": "android-tv/",
    "fire_tv": "fire-tv/",
    "faq": "faq/",
    "formats": "formats/",
    "translate": "translate/",
    "guide_android_tv": "guides/telegram-videos-on-android-tv/",
    "guide_fire_tv": "guides/telegram-videos-on-fire-tv/",
    "guide_no_casting": "guides/telegram-on-tv-without-casting/",
}

# The languages besides English, in the order the picker lists them. `tag` is the
# catalog file name, `path` the URL prefix, `hreflang` what search engines are told (the
# language alone where one region stands for all, so es-CL and pt-PT readers find these too), `og`
# the og:locale, `name` the language's name for itself, and `dir` the writing direction.
LANGS = [
    {"tag": "es-419", "path": "es", "hreflang": "es", "og": "es_LA", "name": "Español"},
    {"tag": "pt-BR", "path": "pt", "hreflang": "pt", "og": "pt_BR", "name": "Português"},
    {"tag": "fr", "path": "fr", "hreflang": "fr", "og": "fr_FR", "name": "Français"},
    {"tag": "de", "path": "de", "hreflang": "de", "og": "de_DE", "name": "Deutsch"},
    {"tag": "it", "path": "it", "hreflang": "it", "og": "it_IT", "name": "Italiano"},
    {"tag": "pl", "path": "pl", "hreflang": "pl", "og": "pl_PL", "name": "Polski"},
    {"tag": "ru", "path": "ru", "hreflang": "ru", "og": "ru_RU", "name": "Русский"},
    {"tag": "uk", "path": "uk", "hreflang": "uk", "og": "uk_UA", "name": "Українська"},
    {"tag": "tr", "path": "tr", "hreflang": "tr", "og": "tr_TR", "name": "Türkçe"},
    {"tag": "id", "path": "id", "hreflang": "id", "og": "id_ID", "name": "Bahasa Indonesia"},
    {"tag": "vi", "path": "vi", "hreflang": "vi", "og": "vi_VN", "name": "Tiếng Việt"},
    {"tag": "ar", "path": "ar", "hreflang": "ar", "og": "ar_AR", "name": "العربية", "dir": "rtl"},
    {"tag": "fa", "path": "fa", "hreflang": "fa", "og": "fa_IR", "name": "فارسی", "dir": "rtl"},
    {"tag": "zh-CN", "path": "zh", "hreflang": "zh-Hans", "og": "zh_CN", "name": "简体中文"},
    {"tag": "zh-TW", "path": "zh-tw", "hreflang": "zh-Hant", "og": "zh_TW", "name": "繁體中文"},
    {"tag": "ja", "path": "ja", "hreflang": "ja", "og": "ja_JP", "name": "日本語"},
    {"tag": "ko", "path": "ko", "hreflang": "ko", "og": "ko_KR", "name": "한국어"},
    {"tag": "hi", "path": "hi", "hreflang": "hi", "og": "hi_IN", "name": "हिन्दी"},
    {"tag": "bn", "path": "bn", "hreflang": "bn", "og": "bn_BD", "name": "বাংলা"},
    {"tag": "ml", "path": "ml", "hreflang": "ml", "og": "ml_IN", "name": "മലയാളം"},
]
ENGLISH = {"tag": "en", "path": "", "hreflang": "en", "og": "en_GB", "name": "English"}

PUBLISH_THRESHOLD = 0.95

# Text the build itself writes, so it has no element in the English pages to be read from.
BUILD_STRINGS = {
    "build.language": "Language",
    "build.languages": "Languages",
    "build.languages_count": "{count} languages",
    "build.suggest": "This page is also in {language}.",
    "build.suggest_go": "Read it in {language}",
    "build.suggest_dismiss": "Stay in this language",
}

VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source",
        "track", "wbr"}
# Elements that may sit inside a sentence. Anything else inside an element makes it a container.
INLINE = {"a", "abbr", "b", "bdi", "br", "cite", "code", "em", "i", "img", "kbd", "mark", "q",
          "s", "samp", "small", "span", "strong", "sub", "sup", "svg", "time", "u", "var", "wbr"}
# Kept exactly as written: never sent for translation.
VERBATIM = {"svg", "code", "kbd", "samp", "var", "br", "wbr", "img"}
SKIP = {"script", "style", "svg", "pre", "code", "kbd", "samp", "template", "noscript"}
OPTIONAL_END = {"p", "li", "dt", "dd", "option", "tr", "td", "th"}
ATTRS = ["alt", "aria-label", "title", "placeholder"]
META_TEXT = {("name", "description"), ("property", "og:title"), ("property", "og:description"),
             ("property", "og:image:alt"), ("name", "twitter:title"),
             ("name", "twitter:description"), ("name", "twitter:image:alt")}
# Words and handles that read the same in every language.
NO_TRANSLATE = {"TMPlayer", "Telegram", "GitHub", "nevil.dev", "Nevil", "dracu-lah", "Infoek.cz",
                "Android TV", "Android", "Windows", "Linux", "Fire TV", "Flatpak", "AppImage",
                "Telegram group", "Obtainium", "Downloader", "mpv"}
LD_TEXT_KEYS = {"name", "alternateName", "description", "text", "headline", "featureList",
                "about", "abstract", "acceptedAnswer"}
LD_URL_KEYS = {"url", "item", "@id", "mainEntityOfPage"}

REGION_RE = r"<!-- i18n:{0} -->.*?<!-- /i18n:{0} -->"
REGIONS = ["head", "picker", "footer", "langcount"]


class BuildError(Exception):
    pass


# ---------------------------------------------------------------------------- parsing

class Node:
    __slots__ = ("tag", "attrs", "start", "open_end", "close_start", "end", "children", "parent")

    def __init__(self, tag, attrs, start, open_end, parent):
        self.tag = tag
        self.attrs = dict(attrs)
        self.start = start
        self.open_end = open_end
        self.close_start = open_end
        self.end = open_end
        self.children = []
        self.parent = parent

    def get(self, name, default=None):
        value = self.attrs.get(name, default)
        return default if value is None else value

    def classes(self):
        return self.get("class", "").split()


class Tree(HTMLParser):
    """An element tree that remembers where every tag sits in the source, so a page can be
    edited in place without reformatting the parts nobody translated."""

    def __init__(self, src, name):
        super().__init__(convert_charrefs=False)
        self.src = src
        self.name = name
        self.lines = [0]
        for m in re.finditer("\n", src):
            self.lines.append(m.end())
        self.root = Node("#root", [], 0, 0, None)
        self.root.close_start = self.root.end = len(src)
        self.stack = [self.root]
        self.feed(src)
        self.close()
        while len(self.stack) > 1:
            node = self.stack.pop()
            if node.tag not in OPTIONAL_END:
                raise BuildError(f"{name}: <{node.tag}> at {self.where(node.start)} is never closed")
            node.close_start = node.end = len(src)

    def where(self, offset):
        line = self.src.count("\n", 0, offset) + 1
        return f"line {line}"

    def _pos(self):
        line, col = self.getpos()
        return self.lines[line - 1] + col

    def handle_starttag(self, tag, attrs):
        start = self._pos()
        text = self.get_starttag_text()
        parent = self.stack[-1]
        node = Node(tag, attrs, start, start + len(text), parent)
        parent.children.append(node)
        if tag in VOID or text.endswith("/>"):
            return
        self.stack.append(node)

    def handle_startendtag(self, tag, attrs):
        start = self._pos()
        text = self.get_starttag_text()
        parent = self.stack[-1]
        parent.children.append(Node(tag, attrs, start, start + len(text), parent))

    def handle_endtag(self, tag):
        start = self._pos()
        end = self.src.index(">", start) + 1
        if tag in VOID:
            return
        while True:
            if len(self.stack) == 1:
                raise BuildError(f"{self.name}: stray </{tag}> at {self.where(start)}")
            node = self.stack[-1]
            if node.tag == tag:
                self.stack.pop()
                node.close_start, node.end = start, end
                return
            if node.tag in OPTIONAL_END:
                self.stack.pop()
                node.close_start = node.end = start
                continue
            raise BuildError(f"{self.name}: </{tag}> at {self.where(start)} closes "
                             f"<{node.tag}> from {self.where(node.start)}")


def iter_nodes(node):
    for child in node.children:
        yield child
        yield from iter_nodes(child)


def segments(src, node):
    """The content of an element in order: ("text", raw) and ("node", child)."""
    out = []
    pos = node.open_end
    for child in node.children:
        if child.start > pos:
            out.append(("text", src[pos:child.start]))
        out.append(("node", child))
        pos = child.end
    if node.close_start > pos:
        out.append(("text", src[pos:node.close_start]))
    return out


COMMENT_RE = re.compile(r"<!--.*?-->", re.S)


def plain(raw):
    return html.unescape(COMMENT_RE.sub("", raw))


def direct_text(src, node):
    return "".join(plain(raw) for kind, raw in segments(src, node) if kind == "text")


def all_text(src, node):
    if node.tag in ("svg", "script", "style"):
        return ""
    out = []
    for kind, part in segments(src, node):
        out.append(plain(part) if kind == "text" else all_text(src, part))
    return "".join(out)


def has_words(text):
    return any(c.isalpha() for c in text)


def translatable_text(text):
    t = " ".join(text.split())
    if not has_words(t):
        return False
    if t in NO_TRANSLATE:
        return False
    if not any(c.islower() for c in t):
        return False  # GPL-3.0, APK, MKV: names, not words
    if re.fullmatch(r"[\w.+-]+@[\w.-]+|[\w-]+(\.[\w-]+)+(/\S*)?|v?\d[\w.+-]*", t):
        return False  # addresses, domains, versions
    return True


def opted_out(node):
    if node.get("translate") == "no":
        return True
    lang = node.get("lang")
    return lang is not None and not lang.startswith("en")


def inline_only(node):
    for child in node.children:
        if child.tag not in INLINE:
            return False
        if child.tag != "svg" and not inline_only(child):
            return False
    return True


def is_unit(src, node):
    if node.tag in SKIP or node.tag == "#root" or opted_out(node):
        return False
    return translatable_text(direct_text(src, node)) and inline_only(node)


# ---------------------------------------------------------------------------- templates

def is_verbatim(src, node):
    if node.tag in VERBATIM or opted_out(node) or not translatable_text(all_text(src, node)):
        return True
    # A link around nothing but code, <a><code>site/i18n/</code></a>, has no words of its own:
    # keying it would put an empty string in the catalog that no language can ever fill.
    if not node.children or direct_text(src, node).strip():
        return False
    return all(is_verbatim(src, child) for child in node.children)


def template(src, node):
    """The sentence inside an element, with each child element as a {placeholder}.

    Returns (prefix, tmpl, suffix, children): prefix and suffix are the icons or separators at
    either end, kept out of the string so a translator sees only words."""
    parts = []
    counts = {}
    children = {}
    for kind, part in segments(src, node):
        if kind == "text":
            parts.append(("text", plain(part)))
        else:
            counts[part.tag] = counts.get(part.tag, 0) + 1
            name = f"{part.tag}{counts[part.tag]}"
            children[name] = part
            parts.append(("node", name))

    def edge(seq):
        out = []
        while seq:
            kind, value = seq[0]
            if kind == "text" and not value.strip():
                out.append(seq.pop(0))
            elif kind == "node" and is_verbatim(src, children[value]):
                out.append(seq.pop(0))
            else:
                break
        return out

    seq = list(parts)
    head = edge(seq)
    seq.reverse()
    tail = edge(seq)
    seq.reverse()
    tail.reverse()
    # A trailing run that is only punctuation (the hung full stop) stays outside too.
    body = []
    for kind, value in seq:
        if kind == "text":
            if "{" in value or "}" in value:
                raise BuildError(f"braces in translatable text: {value.strip()[:60]}")
            body.append(value)
        else:
            body.append("{" + value + "}")
    raw = "".join(body)
    tmpl = " ".join(raw.split())
    # The space between the words and an element kept outside them ("Download <em>TMPlayer</em>")
    # belongs to neither, so it is put back on the outside rather than lost.
    if tail and raw[-1:].isspace():
        tail.insert(0, ("text", " "))
    if head and raw[:1].isspace():
        head.append(("text", " "))
    return head, tmpl, tail, children


def flatten_unit(src, node, key, out):
    """Every catalog string one unit owns: its own sentence and those of its child elements."""
    _, tmpl, _, children = template(src, node)
    out[key] = tmpl
    for name, child in children.items():
        if not is_verbatim(src, child):
            flatten_unit(src, child, f"{key}__{name}", out)
    return out


# ---------------------------------------------------------------------------- page model

def strip_regions(src):
    for name in REGIONS:
        src = re.sub(REGION_RE.format(name), f"<!-- i18n:{name} --><!-- /i18n:{name} -->", src,
                     flags=re.S)
    return src


def ensure_markers(src):
    """Puts the empty generated regions in a page that does not have them yet."""
    if "<!-- i18n:head -->" not in src:
        src = re.sub(r'(<link rel="canonical"[^>]*>\n)',
                     r"\1<!-- i18n:head --><!-- /i18n:head -->\n", src, count=1)
    if "<!-- i18n:picker -->" not in src:
        src = re.sub(r'(\n)([ \t]*)(<button class="theme-toggle")',
                     r"\1\2<!-- i18n:picker --><!-- /i18n:picker -->\n\2\3", src, count=1)
    if "<!-- i18n:footer -->" not in src:
        src = re.sub(r'(<nav class="footer-links footer-community".*?</nav>\n)',
                     r"\1    <!-- i18n:footer --><!-- /i18n:footer -->\n", src, count=1, flags=re.S)
    for name in ("head", "picker", "footer"):
        if f"<!-- i18n:{name} -->" not in src:
            raise BuildError(f"could not place the i18n:{name} region")
    return src


def in_shared_chrome(node):
    while node is not None:
        cls = node.classes()
        if node.tag in ("header", "footer") or "skip" in cls or "to-top" in cls:
            return True
        node = node.parent
    return False


def find_units(src, node, out):
    for child in node.children:
        if child.tag in SKIP or opted_out(child):
            continue
        if is_unit(src, child):
            out.append(child)
        else:
            if (translatable_text(direct_text(src, child)) and child.tag != "#root"):
                raise BuildError(f"text beside block elements in <{child.tag}> "
                                 f"near '{direct_text(src, child).strip()[:50]}': wrap it in a <span>")
            find_units(src, child, out)
    return out


def attr_targets(src, tree):
    """(node, attribute) pairs whose value is text a reader sees or hears."""
    out = []
    for node in iter_nodes(tree.root):
        if node.tag == "meta":
            for k, v in META_TEXT:
                if node.get(k) == v and translatable_text(node.get("content", "")):
                    out.append((node, "content"))
            continue
        if node.tag in ("script", "style", "link") or opted_out(node):
            continue
        anc = node.parent
        hidden_in_svg = False
        while anc is not None:
            if anc.tag == "svg":
                hidden_in_svg = True
            anc = anc.parent
        if hidden_in_svg:
            continue
        for attr in ATTRS:
            value = node.get(attr)
            if value and translatable_text(value):
                out.append((node, attr))
    return out


def slug(text, limit=5):
    words = re.findall(r"[a-z0-9]+", re.sub(r"\{\w+\}", " ", text).lower())
    return "_".join(words[:limit]) or "text"


def load_page(page_id):
    path = SITE / PAGES[page_id] / "index.html"
    src = path.read_text(encoding="utf-8")
    clean = ensure_markers(strip_regions(src))
    return path, src, clean


# ---------------------------------------------------------------------------- tagging

def tag_pages():
    """Gives every untagged piece of text a key. Identical text shares one key."""
    known = {}       # flattened unit strings (as a json string) -> key
    taken = {}       # key -> flattened strings
    attr_known = {}  # text -> key
    pages = {}
    for page_id in PAGES:
        path, src, clean = load_page(page_id)
        tree = Tree(clean, str(path))
        pages[page_id] = (path, clean, tree)
        for node in find_units(clean, tree.root, []):
            key = node.get("data-i18n")
            if key:
                flat = flatten_unit(clean, node, key, {})
                rel = json.dumps({k[len(key):]: v for k, v in flat.items()}, sort_keys=True)
                known.setdefault(rel, key)
                taken[key] = rel
        for node, attr in attr_targets(clean, tree):
            key = node.get(f"data-i18n-{attr}")
            if key:
                attr_known.setdefault(" ".join(node.get(attr).split()), key)
                taken[key] = taken.get(key, "attr")

    changed = []
    for page_id, (path, clean, tree) in pages.items():
        inserts = []
        for node in find_units(clean, tree.root, []):
            if node.get("data-i18n"):
                continue
            prefix = "common" if in_shared_chrome(node) else page_id
            flat = flatten_unit(clean, node, "@", {})
            rel = json.dumps({k[1:]: v for k, v in flat.items()}, sort_keys=True)
            # A title or a heading that a meta tag or an alt repeats word for word shares its key.
            key = known.get(rel) or (attr_known.get(flat["@"]) if len(flat) == 1 else None)
            if not key:
                base = f"{prefix}.{slug(flat['@'])}"
                key, n = base, 2
                while key in taken:
                    key, n = f"{base}_{n}", n + 1
                known[rel] = key
                taken[key] = rel
            inserts.append((node, f' data-i18n="{key}"'))
        for node, attr in attr_targets(clean, tree):
            if node.get(f"data-i18n-{attr}"):
                continue
            text = " ".join(node.get(attr).split())
            key = attr_known.get(text) or known.get(json.dumps({"": text}, sort_keys=True))
            if not key:
                prefix = "common" if in_shared_chrome(node) else page_id
                base = f"{prefix}.{slug(text)}"
                key, n = base, 2
                while key in taken:
                    key, n = f"{base}_{n}", n + 1
                attr_known[text] = key
                taken[key] = "attr"
            inserts.append((node, f' data-i18n-{attr}="{key}"'))
        if not inserts:
            continue
        out = clean
        for node, attr_text in sorted(inserts, key=lambda x: -x[0].start):
            close = node.open_end - (2 if clean[node.open_end - 2:node.open_end] == "/>" else 1)
            out = out[:close] + attr_text + out[close:]
        path.write_text(out, encoding="utf-8")
        changed.append(f"{path.relative_to(ROOT)}: {len(inserts)} keys")
    return changed


# ---------------------------------------------------------------------------- extraction

def ld_blocks(src):
    return list(re.finditer(r'(<script type="application/ld\+json">)(.*?)(</script>)', src, re.S))


def ld_key(text):
    return f"ld.{slug(text, 4)}_{hashlib.sha1(text.encode()).hexdigest()[:4]}"


def ld_strings(data, out, field=None):
    if isinstance(data, dict):
        for k, v in data.items():
            ld_strings(v, out, k)
    elif isinstance(data, list):
        for v in data:
            ld_strings(v, out, field)
    elif isinstance(data, str) and field in LD_TEXT_KEYS and translatable_text(data):
        out[ld_key(data)] = data
    return out


JS_RE = re.compile(r"""tmT\(\s*'([a-z0-9_]+)'\s*,\s*'((?:[^'\\]|\\.)*)'""")


def js_strings():
    src = (SITE / "app.js").read_text(encoding="utf-8")
    out = {}
    for key, text in JS_RE.findall(src):
        text = text.encode().decode("unicode_escape") if "\\" in text else text
        full = f"js.{key}"
        if full in out and out[full] != text:
            raise BuildError(f"app.js: tmT('{key}') has two English texts")
        out[full] = text
    return out


def extract(pages):
    """The English catalog, in page order. Fails on untagged text or on one key with two texts."""
    catalog = {}
    problems = []

    def put(key, text, where):
        if key in catalog and catalog[key] != text:
            problems.append(f"{where}: key {key} is used for two texts:\n  {catalog[key]!r}\n  {text!r}")
        catalog.setdefault(key, text)

    for page_id, (path, clean, tree) in pages.items():
        where = str(path.relative_to(ROOT))
        for node in find_units(clean, tree.root, []):
            key = node.get("data-i18n")
            if not key:
                problems.append(f"{where} {tree.where(node.start)}: untagged text "
                                f"'{all_text(clean, node).strip()[:50]}' (run with --tag)")
                continue
            for k, v in flatten_unit(clean, node, key, {}).items():
                put(k, v, where)
        for node, attr in attr_targets(clean, tree):
            key = node.get(f"data-i18n-{attr}")
            if not key:
                problems.append(f"{where} {tree.where(node.start)}: untagged {attr} "
                                f"'{node.get(attr)[:50]}' (run with --tag)")
                continue
            put(key, " ".join(node.get(attr).split()), where)
        for m in ld_blocks(clean):
            try:
                data = json.loads(m.group(2))
            except json.JSONDecodeError as e:
                problems.append(f"{where}: JSON-LD does not parse: {e}")
                continue
            for k, v in ld_strings(data, {}).items():
                put(k, v, where)
    for k, v in js_strings().items():
        put(k, v, "site/app.js")
    for k, v in BUILD_STRINGS.items():
        put(k, v, "build")
    if problems:
        raise BuildError("\n".join(problems))
    return catalog


def nest(flat):
    out = {}
    for key, value in flat.items():
        group, _, rest = key.partition(".")
        out.setdefault(group, {})[rest] = value
    return out


def unnest(data, prefix=""):
    flat = {}
    for k, v in data.items():
        key = f"{prefix}.{k}" if prefix else k
        if isinstance(v, dict):
            flat.update(unnest(v, key))
        elif isinstance(v, str):
            flat[key] = v
    return flat


def dump_json(data):
    return json.dumps(data, ensure_ascii=False, indent=2) + "\n"


# ---------------------------------------------------------------------------- rendering

PH_RE = re.compile(r"\{(\w+)\}")


class Renderer:
    def __init__(self, lang, catalog, english, published, page_id):
        self.lang = lang
        self.cat = catalog
        self.en = english
        self.published = published
        self.page_id = page_id
        self.fallbacks = 0

    def text(self, key):
        """The translation, or English when it is missing or lost a placeholder."""
        en = self.en.get(key)
        tr = self.cat.get(key)
        if tr and en is not None and sorted(PH_RE.findall(tr)) == sorted(PH_RE.findall(en)):
            return tr
        if key in self.en and self.lang is not ENGLISH:
            self.fallbacks += 1
        return en if en is not None else tr

    def start_tag(self, src, node):
        tag = src[node.start:node.open_end]
        for attr in ATTRS + ["content"]:
            key = node.get(f"data-i18n-{attr}")
            if not key:
                continue
            value = html.escape(self.text(key), quote=True)
            tag = re.sub(r'(\s' + re.escape(attr) + r'=")[^"]*(")',
                         lambda m: m.group(1) + value + m.group(2), tag, count=1)
        return tag

    def unit(self, src, node, key):
        head, tmpl_en, tail, children = template(src, node)
        tmpl = self.text(key) if key in self.en else tmpl_en

        def edge(seq):
            out = []
            for kind, value in seq:
                if kind == "text":
                    out.append(" " if value and not value.strip() else html.escape(value, quote=False))
                else:
                    out.append(self.whole(src, children[value], None))
            return "".join(out)

        def child(m):
            name = m.group(1)
            node_ = children.get(name)
            if node_ is None:
                return m.group(0)
            sub = None if is_verbatim(src, node_) else f"{key}__{name}"
            return self.whole(src, node_, sub)

        pieces = []
        pos = 0
        for m in PH_RE.finditer(tmpl):
            pieces.append(html.escape(tmpl[pos:m.start()], quote=False))
            pieces.append(child(m))
            pos = m.end()
        pieces.append(html.escape(tmpl[pos:], quote=False))
        return edge(head) + "".join(pieces) + edge(tail)

    def whole(self, src, node, key):
        open_tag = self.start_tag(src, node)
        if node.open_end == node.end:
            return open_tag
        inner = self.unit(src, node, key) if key else self.verbatim_inner(src, node)
        return open_tag + inner + src[node.close_start:node.end]

    def verbatim_inner(self, src, node):
        # A verbatim element can still carry an alt or aria-label of its own children.
        out = []
        pos = node.open_end
        for c in iter_nodes(node):
            if any(c.get(f"data-i18n-{a}") for a in ATTRS):
                out.append(src[pos:c.start])
                out.append(self.start_tag(src, c))
                pos = c.open_end
        out.append(src[pos:node.close_start])
        return "".join(out)


def page_url(lang, page_id):
    prefix = f"/{lang['path']}/" if lang["path"] else "/"
    return prefix + PAGES[page_id]


LOCALIZED_PATHS = {"/" + p for p in PAGES.values()}


def localize_links(out, lang, page_id):
    """Internal links to localized pages stay in the language; relative asset paths become
    absolute, because the page now lives one directory deeper."""
    base = "/" + PAGES[page_id]

    def fix(m):
        attr, value = m.group(1), m.group(2)
        if re.match(r"^(?:[a-z][a-z0-9+.-]*:|//|#|/|\?|$)", value):
            if attr == "href" and value.startswith("/") and not value.startswith("//"):
                path, sep, rest = re.match(r"([^#?]*)([#?]?)(.*)", value).groups()
                norm = path if path.endswith("/") else path + "/"
                if norm in LOCALIZED_PATHS and lang["path"]:
                    return f'{attr}="/{lang["path"]}{norm}{sep}{rest}"'
            return m.group(0)
        return f'{attr}="{base}{value}"'

    return re.sub(r'\b(href|src|srcset|poster|data-theme-src|data-dark-src)="([^"]*)"', fix, out)


GLOBE = ('<svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true" focusable="false">'
         '<path fill="currentColor" d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20Zm6.9 6h-2.9a15.7 15.7 '
         '0 0 0-1.4-3.6A8 8 0 0 1 18.9 8ZM12 4a13.8 13.8 0 0 1 1.9 4h-3.8A13.8 13.8 0 0 1 12 4ZM4.3 '
         '14a8.2 8.2 0 0 1 0-4h3.4a16.5 16.5 0 0 0 0 4H4.3Zm.8 2h2.9a15.7 15.7 0 0 0 1.4 3.6A8 8 0 '
         '0 1 5.1 16ZM8 8H5.1a8 8 0 0 1 4.3-3.6A15.7 15.7 0 0 0 8 8Zm4 12a13.8 13.8 0 0 1-1.9-4h3.8A13.8 '
         '13.8 0 0 1 12 20Zm2.3-6H9.7a14.7 14.7 0 0 1 0-4h4.6a14.7 14.7 0 0 1 0 4Zm.3 5.6a15.7 15.7 0 '
         '0 0 1.4-3.6h2.9a8 8 0 0 1-4.3 3.6Zm1.7-5.6a16.5 16.5 0 0 0 0-4h3.4a8.2 8.2 0 0 1 0 4h-3.4Z"/></svg>')


def regions(lang, page_id, langs, strings):
    """The generated parts of a page: alternates, the picker, the footer list and the home lines."""
    out = {name: "" for name in REGIONS}
    if len(langs) < 2:
        return out
    t = strings
    lines = []
    for other in langs:
        lines.append(f'<link rel="alternate" hreflang="{other["hreflang"]}" '
                     f'href="{ORIGIN}{page_url(other, page_id)}">')
    lines.append(f'<link rel="alternate" hreflang="x-default" href="{ORIGIN}{page_url(ENGLISH, page_id)}">')
    for other in langs:
        if other is not lang:
            lines.append(f'<meta property="og:locale:alternate" content="{other["og"]}">')
    info = {"current": lang["hreflang"], "langs": []}
    for other in langs:
        ts = other["strings"]
        info["langs"].append({
            "code": other["hreflang"], "tag": other["tag"], "name": other["name"],
            "dir": other.get("dir", "ltr"),
            "href": page_url(other, page_id),
            "suggest": ts["build.suggest"].replace("{language}", other["name"]),
            "go": ts["build.suggest_go"].replace("{language}", other["name"]),
            "dismiss": ts["build.suggest_dismiss"],
        })
    lines.append('<script type="application/json" id="tm-langs">'
                 + json.dumps(info, ensure_ascii=False).replace("</", "<\\/") + "</script>")
    out["head"] = "\n" + "\n".join(lines) + "\n"

    items = []
    for other in langs:
        current = ' aria-current="true"' if other is lang else ""
        items.append(f'<li><a href="{page_url(other, page_id)}" hreflang="{other["hreflang"]}" '
                     f'lang="{other["tag"]}" data-lang-pick="{other["hreflang"]}"{current}>'
                     f'{html.escape(other["name"])}</a></li>')
    label = html.escape(f'{t["build.language"]}: {lang["name"]}', quote=True)
    out["picker"] = (
        f'\n      <details class="lang-picker">'
        f'\n        <summary class="lang-picker-btn" aria-label="{label}" title="{label}">'
        f'{GLOBE}<span class="lang-picker-code" aria-hidden="true">{lang["hreflang"].upper()}</span></summary>'
        f'\n        <ul class="lang-picker-menu">\n          ' + "\n          ".join(items)
        + "\n        </ul>\n      </details>\n      ")

    # The footer list goes to each language's home page rather than to this page, so it stays
    # right when scripts/build-changelog.py lifts the footer into the English-only changelog.
    links = []
    for other in langs:
        current = ' aria-current="true"' if other is lang else ""
        links.append(f'<a href="{page_url(other, "home")}" hreflang="{other["hreflang"]}" '
                     f'lang="{other["tag"]}" data-lang-pick="{other["hreflang"]}"{current}>'
                     f'{html.escape(other["name"])}</a>')
    out["footer"] = (f'\n    <nav class="footer-links footer-langs" '
                     f'aria-label="{html.escape(t["build.languages"], quote=True)}">\n      '
                     + "\n      ".join(links) + "\n    </nav>\n    ")

    count = html.escape(t["build.languages_count"], quote=False).replace(
        "{count}", f"<b>{len(langs)}</b>")
    out["langcount"] = f"<span>{count}</span>"
    return out


def fill_regions(src, filled):
    for name, body in filled.items():
        src = re.sub(REGION_RE.format(name),
                     lambda m, n=name, b=body: f"<!-- i18n:{n} -->{b}<!-- /i18n:{n} -->",
                     src, flags=re.S)
    return src


def translate_ld(data, r, lang, field=None):
    if isinstance(data, dict):
        out = {}
        for k, v in data.items():
            if k == "inLanguage" and isinstance(v, str):
                out[k] = lang["tag"]
            elif k in LD_URL_KEYS and isinstance(v, str):
                out[k] = ld_url(v, lang)
            else:
                out[k] = translate_ld(v, r, lang, k)
        return out
    if isinstance(data, list):
        return [translate_ld(v, r, lang, field) for v in data]
    if isinstance(data, str) and field in LD_TEXT_KEYS and translatable_text(data):
        return r.text(ld_key(data))
    return data


def ld_url(value, lang):
    if not value.startswith(ORIGIN + "/"):
        return value
    path, sep, rest = re.match(r"([^#?]*)([#?]?)(.*)", value[len(ORIGIN):]).groups()
    if path in LOCALIZED_PATHS:
        return f"{ORIGIN}/{lang['path']}{path}{sep}{rest}"
    return value


def render(lang, page_id, clean, tree, english, langs):
    r = Renderer(lang, lang["catalog"], english, langs, page_id)
    edits = []
    units = find_units(clean, tree.root, [])
    covered = []
    for node in units:
        key = node.get("data-i18n")
        edits.append((node.open_end, node.close_start, r.unit(clean, node, key)))
        covered.append((node.start, node.end))

    def inside_unit(node):
        return any(a <= node.start and node.end <= b for a, b in covered)

    for node in iter_nodes(tree.root):
        if inside_unit(node):
            continue
        if any(node.get(f"data-i18n-{a}") for a in ATTRS + ["content"]):
            edits.append((node.start, node.open_end, r.start_tag(clean, node)))
    for m in ld_blocks(clean):
        data = translate_ld(json.loads(m.group(2)), r, lang)
        body = "\n" + json.dumps(data, ensure_ascii=False, indent=2).replace("</", "<\\/") + "\n"
        edits.append((m.start(2), m.end(2), body))

    edits.sort()
    for (a1, b1, _), (a2, b2, _) in zip(edits, edits[1:]):
        if a2 < b1:
            raise BuildError(f"{page_id}: overlapping edits at {a1} and {a2}")
    out = clean
    for a, b, text in reversed(edits):
        out = out[:a] + text + out[b:]

    dir_attr = f' dir="{lang["dir"]}"' if lang.get("dir") else ""
    out = out.replace('<html lang="en">', f'<html lang="{lang["tag"]}"{dir_attr}>', 1)
    url = ORIGIN + page_url(lang, page_id)
    out = re.sub(r'(<link rel="canonical" href=")[^"]*(")', lambda m: m.group(1) + url + m.group(2), out, count=1)
    out = re.sub(r'(<meta property="og:url" content=")[^"]*(")', lambda m: m.group(1) + url + m.group(2), out, count=1)
    out = re.sub(r'(<meta property="og:locale" content=")[^"]*(")',
                 lambda m: m.group(1) + lang["og"] + m.group(2), out, count=1)
    out = localize_links(out, lang, page_id)

    strings = {k[3:]: lang["catalog"][k] for k in english if k.startswith("js.") and lang["catalog"].get(k)}
    if strings:
        block = ('<script type="application/json" id="tm-strings">'
                 + json.dumps(strings, ensure_ascii=False).replace("</", "<\\/") + "</script>\n")
        out, found = re.subn(r'(<script src="/app\.js"></script>)', lambda m: block + m.group(1), out,
                             count=1)
        if not found:
            raise BuildError(f"{page_id}: no <script src=\"/app.js\"> to put the strings before")
    out = fill_regions(out, regions(lang, page_id, langs, lang["strings"]))
    return out, r.fallbacks


def generated_note(lang):
    return (f"<!-- Generated by scripts/build-site-i18n.py from the English page and "
            f"site/i18n/{lang['tag']}.json. Edit those, not this file. -->\n")


# ---------------------------------------------------------------------------- sitemap

def sitemap(src, langs):
    src = re.sub(r"\n[ \t]*<xhtml:link [^>]*/>", "", src)
    lang_paths = "|".join(re.escape(l["path"]) for l in LANGS)
    src = re.sub(r"\n  <url>\n    <loc>" + re.escape(ORIGIN) + rf"/(?:{lang_paths})/.*?</url>", "", src, flags=re.S)
    if len(langs) < 2:
        return src
    if "xmlns:xhtml" not in src:
        src = src.replace('<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"',
                          '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" '
                          'xmlns:xhtml="http://www.w3.org/1999/xhtml"', 1)
    by_path = {"/" + p: pid for pid, p in PAGES.items()}

    def alternates(page_id):
        lines = [f'    <xhtml:link rel="alternate" hreflang="{l["hreflang"]}" '
                 f'href="{ORIGIN}{page_url(l, page_id)}"/>' for l in langs]
        lines.append(f'    <xhtml:link rel="alternate" hreflang="x-default" '
                     f'href="{ORIGIN}{page_url(ENGLISH, page_id)}"/>')
        return "\n".join(lines)

    def block(m):
        whole, loc = m.group(0), m.group(1)
        page_id = by_path.get(loc[len(ORIGIN):])
        if not page_id:
            return whole
        alt = alternates(page_id)
        whole = whole.replace(f"<loc>{loc}</loc>", f"<loc>{loc}</loc>\n{alt}", 1)
        lastmod = re.search(r"<lastmod>.*?</lastmod>", whole)
        freq = re.search(r"<changefreq>.*?</changefreq>", whole)
        prio = re.search(r"<priority>.*?</priority>", whole)
        extra = []
        for l in langs:
            if l is ENGLISH:
                continue
            bits = [f"    <loc>{ORIGIN}{page_url(l, page_id)}</loc>", alt]
            bits += [f"    {x.group(0)}" for x in (lastmod, freq, prio) if x]
            extra.append("  <url>\n" + "\n".join(bits) + "\n  </url>")
        return whole + "\n" + "\n".join(extra)

    return re.sub(r"  <url>\n    <loc>([^<]*)</loc>.*?</url>", block, src, flags=re.S)


# ---------------------------------------------------------------------------- main

def load_catalog(lang):
    path = I18N / f"{lang['tag']}.json"
    if not path.exists():
        return {}
    return unnest(json.loads(path.read_text(encoding="utf-8")))


def coverage(catalog, english):
    keys = [k for k in english if not k.startswith("build.")]
    done = sum(1 for k in keys if catalog.get(k))
    return done / len(keys) if keys else 0.0


def build(check=False, status=False, tag=False):
    if tag:
        for line in tag_pages():
            print(f"tagged {line}")
    pages = {}
    for page_id in PAGES:
        path, src, clean = load_page(page_id)
        pages[page_id] = (path, src, clean, Tree(clean, str(path)))
    english = extract({k: (v[0], v[2], v[3]) for k, v in pages.items()})

    ENGLISH["catalog"] = english
    published = [ENGLISH]
    report = []
    for lang in LANGS:
        cat = load_catalog(lang)
        share = coverage(cat, english)
        report.append(f"{lang['tag']}: {share:.0%} of {len(english)} strings")
        if share >= PUBLISH_THRESHOLD:
            lang["catalog"] = cat
            published.append(lang)
    for lang in published:
        lang["strings"] = {k: (lang["catalog"].get(k) or v) for k, v in BUILD_STRINGS.items()}
    if status:
        print("\n".join(report))
        print("published: " + ", ".join(l["tag"] for l in published))
        return 0

    files = {I18N / "en.json": dump_json(nest(english))}
    for page_id, (path, src, clean, tree) in pages.items():
        files[path] = fill_regions(clean, regions(ENGLISH, page_id, published, ENGLISH["strings"]))
        for lang in published[1:]:
            out, fallbacks = render(lang, page_id, clean, tree, english, published)
            out = out.replace("<head>\n", "<head>\n" + generated_note(lang), 1)
            files[SITE / lang["path"] / PAGES[page_id] / "index.html"] = out
            if fallbacks:
                print(f"{lang['tag']} {page_id}: {fallbacks} strings fall back to English")
    sm = SITE / "sitemap.xml"
    files[sm] = sitemap(sm.read_text(encoding="utf-8"), published)

    # Language folders that are no longer published go away.
    stale_dirs = [SITE / l["path"] for l in LANGS if l not in published and (SITE / l["path"]).exists()]
    stale = []
    for path, text in files.items():
        if not path.exists() or path.read_text(encoding="utf-8") != text:
            stale.append(path)
    for lang in published[1:]:
        root = SITE / lang["path"]
        if root.exists():
            for f in root.rglob("*"):
                if f.is_file() and f not in files:
                    stale.append(f)

    if check:
        for p in stale:
            print(f"stale: {p.relative_to(ROOT)}")
        for d in stale_dirs:
            print(f"stale: {d.relative_to(ROOT)}/ (language not published)")
        if stale or stale_dirs:
            print("Run scripts/build-site-i18n.py and commit the result.", file=sys.stderr)
            return 1
        print(f"Site i18n is current: {len(english)} strings; published: "
              + ", ".join(l["tag"] for l in published))
        return 0

    for d in stale_dirs:
        shutil.rmtree(d)
    for path in stale:
        if path in files:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(files[path], encoding="utf-8")
            print(f"wrote {path.relative_to(ROOT)}")
        else:
            path.unlink()
            print(f"removed {path.relative_to(ROOT)}")
    print("published: " + ", ".join(l["tag"] for l in published))
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--check", action="store_true", help="exit 1 if anything is untagged or stale")
    group.add_argument("--status", action="store_true", help="print catalog coverage")
    group.add_argument("--tag", action="store_true", help="key untagged text, then build")
    args = parser.parse_args()
    try:
        return build(check=args.check, status=args.status, tag=args.tag)
    except BuildError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
