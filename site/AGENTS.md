# Working on tmplayer.org

The rules for changing the site, on top of the repository's [AGENTS.md](../AGENTS.md). How to run
it, add a page, a string or a language, and how to measure it, is in [README.md](README.md); this
file holds what must stay true. Every rule exists so a new page or a release reaches search
engines the same way the existing ones do.

After any change: `pnpm -C site build`, `python3 scripts/site-i18n-check.py` and
`python3 scripts/site-links.py` (CI runs all three).

## SEO

- **URLs end in a slash.** `vercel.json` sets `trailingSlash` and `cleanUrls`, and Astro builds
  with `trailingSlash: 'always'`. Canonicals, `og:url`, sitemap `<loc>`s and internal links all use
  the slash form; a canonical that points at a redirect is ignored by Google. The one exception is
  a URL whose last segment has a dot: Vercel answers `/changelog/1.26.0/` with a 308 to
  `/changelog/1.26.0`, so release pages are named without the slash everywhere (`releaseUrl()` in
  `src/lib/releases.ts`).
- **Every page's head** comes from `src/layouts/Base.astro`. A page passes it a unique `title`
  (under about 60 characters, no "X - TMPlayer" where X already says TMPlayer) and a `description`
  of roughly 120 to 160 characters, plus its page id or path, and puts JSON-LD with at least a
  `BreadcrumbList` in the `head` slot. `src/pages/support/index.astro` is the smallest complete
  example. The 404 page alone is `noindex` with no canonical (`notFound`).
- **One `<h1>` per page**; every `<img>` has `alt`, `width`, `height`, and below the fold
  `loading="lazy" decoding="async"`. Page images go through `Shot`, never a bare `<img>` (see
  caching below).
- **Strings.** Text a reader sees comes from `i18n/en.json` by key, and the other catalogs are
  translated with the same keys and placeholders before a push. English-only pages (legal,
  privacy, security, signing, support, changelog) keep literal English. JSON-LD text is keyed by a
  hash of its English (`src/i18n/ld.ts`), so changing that English renames its `ld.*` key in every
  catalog.
- **Sitemap.** `src/pages/sitemap.xml.ts` writes it from `src/data/lastmod.json`: a new indexable
  page gets an entry, and a changed page's `lastmod` moves to the day of the change (never today by
  default). IndexNow pings the pages whose `lastmod` moved, so move it with the content.
- **Releases.** The release job runs `scripts/release-data.py`, which writes
  `src/data/releases.json` (the changelog, the RSS feed, one page per release, the sitemap's
  release entries). Never hand-edit it; rerun the script. Release notes are the tag message, so a
  good tag message is the SEO text for that release. `public/latest.json` is the installed apps'
  update feed: only the release job writes it.
- **Caching** lives in `vercel.json`. Fonts and `/_astro/` are immutable. Screenshots keep their
  names when retaken, so `Shot.astro` writes each as `<file>?v=<content hash>`
  (`src/lib/asset-version.ts`), cached for a year; the plain URL keeps the one day cache. A new
  top-level asset type gets its own entry.
- **Files for crawlers.** `public/llms.txt` is a hand-written summary for AI assistants: update it
  when a page is added, renamed or its claims change. The home page's `Organization` JSON-LD
  carries the official profiles (`sameAs`). `public/.well-known/security.txt` expires on the date
  in it: renew it before then. `public/<key>.txt` is the IndexNow key: never delete it. The
  Markdown twins and `/llms-full.txt` are generated (`integrations/markdown-twins.mjs`): never
  write one by hand. `robots.txt` names the AI crawlers only to allow them, training ones included
  (user, 2026-10-15).
- **The download page** keeps its `preconnect` hints to the download mirror (below), GitHub's API
  and github.com.

## Screenshots on the site

`site/public/screenshots/` is referenced from the pages under `src/pages/`, the sitemap's image
entries in `src/data/lastmod.json` and `public/site.webmanifest` (with `"type": "image/webp"`).
Renaming one means changing all three (`grep -rn` the old name under `site/src`). After adding or
retaking one, run `python3 scripts/site-shot-variants.py <name>` for the phone sized copies
(`--check` lists missing ones). Only demo-content screenshots, never a real account; light and
dark shots stay paired.

Cards made for posting elsewhere (Telegram, LinkedIn, X) live in `site/public/social/` and stay
PNG, as `og.png` does, because the networks' crawlers cannot be relied on to decode WebP. Each is
drawn from its own page in `scripts/social-card-*.html` with headless Chromium at 1200x630
(`--window-size=1200,630 --screenshot=... --allow-file-access-from-files`), so it can be redrawn
when the figures move.

A shot that points at one thing is cropped and zoomed, and its arrows, circles and underlines are
drawn by `scripts/annotate-shot.py` from a spec in `scripts/annotations/` (user, 2026-10-14). No
words go into an image: labels stay HTML with catalog keys. The `/features/` tour keeps its
numbered HTML markers matched by a numbered list.

## Sponsoring

- Every content page ends with the "Support TMPlayer" offer, through the shared `SponsorOffer`
  component (it carries the `home.support_*`, `home.sponsor_on_github` and
  `home.buy_me_a_coffee` keys): in the page's star band (`StarCard`) where it has one, otherwise as
  a `support-slim` card in the last band. The home page sets `StarLCard` after its bento and
  `GiveLCard` after its setup steps (user, 2026-10-20).
- The line about ads (`home.support_no_ads`) sits behind an info button beside the heading, a
  native `<details>`, so the words stay in the HTML. The release cards and the support page keep
  it in the open (user, 2026-10-18).
- Buy Me a Coffee is the primary way to chip in (user, 2026-10-18): wherever it stands beside
  GitHub Sponsors it is the larger button in their own brand (`CoffeeButton`: `#FFDD00`, their
  near-black text and their official cup from `public/brand/`, never redrawn or recoloured), and
  GitHub Sponsors is the quieter link under it. Links live in `src/lib/support.ts`; the page is
  `buymeacoffee.com/nevil.dev` (`/nevil` is somebody else).
- Every page but support and the 404 has the floating `CoffeeCard` (a pill on a
  phone, a card wider up, bottom of the start edge), shown shortly after load and then always on
  screen, above the phone's download bar (user, 2026-10-20). The download and install pages carry
  it as well since 2026-11-01 (user: the card was missing where most readers go). No scan code on it. The support page
  dropped its own QR codes too (user, 2026-11-01, "remove the scan option": two buttons already
  say where to go); `GiveBand`'s star card is the only place they still show. Its x hides it for
  that page view only, nothing stored.
- Every Buy Me a Coffee link (`data-bmc`) opens their widget in `CoffeeDialog`, a native
  `<dialog>` whose iframe app.js loads ahead of the click on the visitor's first scroll, touch, key
  or pointing at a support link (never for a runner that does not touch the page, nor with
  Save-Data), and stays a real link for a middle click or without scripting (user, 2026-10-20).
  `vercel.json`'s Permissions-Policy lets that frame use `payment`.
- It stays an offer: no popup that opens by itself, no nag, nothing locked.

## Home page pill and stats

The "New in X" pill above the headline links the latest release page; the stats row shows
downloads, platforms and languages (user, 2026-10-20). `src/lib/stats.ts` works them out at build
time, so they are final in the first paint; app.js only counts them up in view. Downloads are the
app files downloaded from GitHub and from the download mirror (below) across releases, so they
are "downloads", never users or installs. The build logs `[tm-stats]` with its source (GitHub,
else `releases.json`) and the mirror's own count.

## Download mirror

Every binary download (`app.js`, `scripts/write-latest-json.py`'s `latest.json`, the `/apk`
redirect `scripts/point-apk-redirect.py` writes) goes through `workers/download-mirror`, a
Cloudflare Worker that caches TMPlayer's own GitHub release assets at the edge: a slow route to
GitHub's asset CDN used to make every download slow, this doesn't. A file's first request still
reaches github.com, so `download_count` there still sees it; every request after is served from
cache, which GitHub never sees, so the worker counts those itself and answers them at `/stats`,
which `stats.ts` adds to GitHub's count. It only ever proxies TMPlayer's own release assets (its
path has to match the worker's own pattern): never make it a general-purpose GitHub proxy.
Deploys manually, same as the web player: Actions, "Download mirror", Run workflow.

## Performance

Measure with Lighthouse as [README.md](README.md#performance) says. What must stay true:

- No layout shift: anything above the fold is drawn at build time, never filled in by app.js once
  GitHub answers; the font weights used there are preloaded (`Base.astro`: 400, 500, 600).
- app.js never reads layout (`getBoundingClientRect`, `innerHeight`, `scrollY`) while the page is
  built: use an IntersectionObserver, or read after the first paint.
- A screenshot's srcset just covers the drawn width times the screen's ratio; widths in
  `src/data/shot-widths.json`, `sizes` in `src/components/shot-sizes.ts`.
- The stylesheet stays one shared, render blocking, immutable file, and the Obtainium badge PNG
  stays as it is (both decided 2026-10-18).
