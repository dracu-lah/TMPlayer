#!/usr/bin/env python3
"""Checks every internal link in the built site (site/dist) after `pnpm -C site build`.

  - every internal href, src, srcset, poster and data-*-src resolves to a file in dist/, or to a
    redirect in site/vercel.json (such as /apk), or to Vercel's own /_vercel/ paths
  - a page under a language prefix (/es/, /zh-tw/, ...) links to the same language's copy of every
    localized page, never to the English one, as the site always has
  - every English page links its Markdown twin (<link rel="alternate" type="text/markdown">) and the
    twin is in dist/, and no translated page links one; an English page's first path segment is
    never two letters, which vercel.json's Accept: text/markdown redirect takes for a language

Usage: scripts/site-links.py [dist-dir]
"""

import json
import re
import sys
from concurrent.futures import ThreadPoolExecutor
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urljoin, urlsplit

ROOT = Path(__file__).resolve().parent.parent
PREFIXES = ["es", "pt", "fr", "de", "it", "pl", "cs", "ru", "uk", "tr", "id", "vi", "ar", "fa", "zh", "zh-tw",
            "ja", "ko", "hi", "bn", "ml"]
LOCALIZED = {"/", "/features/", "/download/", "/install/", "/android-tv/", "/desktop/", "/fire-tv/", "/faq/",
             "/formats/", "/translate/","/guides/telegram-videos-on-android-tv/", "/guides/telegram-videos-on-fire-tv/",
             "/guides/telegram-on-tv-without-casting/"}
ATTRS = {"href", "src", "poster", "data-theme-src", "data-dark-src", "srcset"}


class Links(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.out = []
        self.markdown = []
        self.lang = None

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "html":
            self.lang = a.get("lang")
        if tag == "link" and a.get("rel") == "alternate" and a.get("type") == "text/markdown":
            self.markdown.append(a.get("href") or "")
        if "data-lang-pick" in dict(attrs):
            return  # the language picker links every language's copy on purpose
        for k, v in attrs:
            if k not in ATTRS or not v:
                continue
            if k == "srcset":
                self.out += [(tag, k, p.split()[0]) for p in v.split(",") if p.strip()]
            elif tag == "link" and dict(attrs).get("rel") in ("canonical", "alternate", "preconnect", "dns-prefetch"):
                continue
            else:
                self.out.append((tag, k, v))


def main():
    dist = Path(sys.argv[1] if len(sys.argv) > 1 else ROOT / "site" / "dist").resolve()
    cfg = json.loads((ROOT / "site" / "vercel.json").read_text())
    redirects = [re.compile("^" + r["source"] + "$") for r in cfg.get("redirects", [])]
    pages = sorted(dist.rglob("*.html"))

    def exists(path):
        p = unquote(path)
        if p.startswith("/_vercel/") or any(r.match(p) for r in redirects):
            return True
        f = dist / p.lstrip("/")
        if p.endswith("/"):
            seg = p.rstrip("/").rsplit("/", 1)[-1]
            if "." in seg and not seg.startswith("."):
                return False  # Vercel answers a dotted path's slash form with a 308 to the slashless one
            return (f / "index.html").is_file()
        if f.is_file():
            return True
        # A directory without its slash only answers 200 when its last segment has a dot
        # (/changelog/1.26.0); anywhere else Vercel adds the slash with a 308, so the link is wrong.
        seg = p.rsplit("/", 1)[-1]
        return "." in seg and (f / "index.html").is_file()

    def check(page):
        rel = "/" + str(page.relative_to(dist))
        url = rel[: -len("index.html")] if rel.endswith("/index.html") else rel
        prefix = next((p for p in PREFIXES if url.startswith(f"/{p}/")), None)
        parser = Links()
        parser.feed(page.read_text(encoding="utf-8"))
        bad = []
        english = parser.lang == "en" and page.name == "index.html"
        if english and not parser.markdown:
            bad.append(f"{url}\tno markdown\t<link rel=alternate type=text/markdown> is missing")
        if not english and parser.markdown:
            bad.append(f"{url}\tmarkdown\tonly English pages have a Markdown twin")
        for value in parser.markdown:
            twin = urlsplit(urljoin("https://tmplayer.org" + url, value))
            if twin.netloc != "tmplayer.org" or not (dist / unquote(twin.path).lstrip("/")).is_file():
                bad.append(f"{url}\tbroken\t<link type=text/markdown href={value}>")
        if english and re.match(r"^/[a-z]{2}(?:-[a-z]+)?/", url):
            bad.append(f"{url}\tmarkdown\ttwo letter first segment: vercel.json takes it for a language")
        for tag, attr, value in parser.out:
            if re.match(r"^(?:[a-z][a-z0-9+.-]*:|//|#)", value):
                continue
            target = urlsplit(urljoin("https://tmplayer.org" + url, value)).path
            if not exists(target):
                bad.append(f"{url}\tbroken\t<{tag} {attr}={value}>")
            elif prefix and tag == "a" and attr == "href" and target in LOCALIZED:
                bad.append(f"{url}\tenglish link\t<a href={value}> (want /{prefix}{target})")
        return bad

    with ThreadPoolExecutor(16) as ex:
        rows = [r for rs in ex.map(check, pages) for r in rs]
    for r in rows:
        print(r)
    print(f"site-links: {len(pages)} pages, {len(rows)} problems", file=sys.stderr)
    return 1 if rows else 0


if __name__ == "__main__":
    sys.exit(main())
