# tmplayer.org development plan

Written 2026-10-21 against `7497a81` (the site redesign in 21 languages). Planning only: nothing in
`site/` was changed to write it.

This is the one list of what is left to do on the website. It builds on, and does not repeat:

- `private/SEO-PLAN.md` (CP72): the search, AI answers and calls to action plan, Phases 1 to 4
  shipped 2026-10-15 to 2026-10-19, Phase 5 (measure) due 2026-11-12, plus the outreach tables
  and the competitor research. SEO items below point at it instead of copying it.
- `docs/ASTRO-MIGRATION.md` (CP71, done): how the Astro site was built and proven identical.
- `private/PLAN.md`: R8 Site (design reference), CP26 (localization, done), CP72, CP90, U20
  (Search Console), U15/U17/U19 (listings), U22 and CP24 (translation review).
- `docs/WEB.md` (CP92): web.tmplayer.org, the app in the browser. A separate product, not this
  plan; only its hand-off to the site is listed here (SH05).
- CLAUDE.md "Site SEO", "Site performance" and "Sponsoring": the settled rules every item here
  keeps. The design rules of 2026-10-20 (borders not shadows, no hover motion, one-time short
  entrances, full colour landscape star and coffee cards apart, plan before building, translate
  all 20 catalogs before pushing) are in the memory note `site-design-preferences`.

## How to use this file

- Pick the first open item of the lowest phase whose "Needs" are met, or the one the user names.
- Each item says the files, the check that closes it, what it needs, whether it can run beside
  others, and whether the user has to do something by hand. "Lane" letters mark items that touch
  different files and can run as parallel agents; the standing limit is two agents at a time,
  compile and check only, one test pass at the end (memory: limit-agents-machine-load).
- Done means: `pnpm -C site build`, `python3 scripts/site-i18n-check.py` and
  `python3 scripts/site-links.py` green, the dash grep empty, every new string in all 20
  catalogs (Claude translates them directly; no langsync), committed and pushed to `main`.
  Tick the box here with the commit hash.
- Site pushes are not releases, so the release cadence guard does not apply to them, but a push
  during the 7 days after a release should not change what the release notes or the update
  feed point at.

## Current state (2026-10-21)

- **Stack:** Astro, static, on Vercel through its git integration (GoDaddy DNS). 419 pages built
  in about 7 s: 39 English pages, 20 localized copies of the 19 translatable pages, 10 release
  pages, the 404. 38 Markdown twins and `llms-full.txt` from `site/integrations/markdown-twins.mjs`.
  Sitemap 418 URLs. IndexNow ran green on every push of 2026-10-20.
- **Pages:** home, features, download (device tabs), install, Android TV, Android (phone),
  desktop, Fire TV, formats, FAQ (five groups), translate, guides hub and six guides,
  alternatives hub and three comparison pages (English only `vs.*` keys), changelog with feed and
  one page per release, and the English-only legal, privacy, security, signing and support pages.
- **Strings:** 1822 keys in `site/i18n/en.json`; every other catalog has 1582, the difference
  being the 240 English-only `vs.*` keys, by design. Machine translated, no native review yet.
  12 to 43 strings per catalog are still identical to English (some are brand names).
- **Assets:** stylesheet 120 KB, 23 KB compressed (it was about 14 KB at the 2026-10-18 audit,
  before the redesign); `app.js` 34 KB, 10 KB compressed; screenshots 8.8 MB on disk across all
  copies. The home page stats read 2117 downloads from GitHub at this build.
- **Checks today:** build, i18n check (1822 strings, consistent), link check (419 pages, 0
  problems), `site-shot-variants.py --check` and `tour-badges.py --check` all clean. No em or
  en dash in `site/src`, `site/i18n` or `llms.txt`.
- **Search (U20, 2026-10-20):** Google 338 clicks and 3.2K impressions in 28 days, 90 pages
  indexed, 161 discovered and not indexed. Bing set up, AI Performance report available.
- **Outreach:** batch 1, 30 correction or tip emails, sent 2026-10-19 and 2026-10-20
  (`private/outreach-sent.json`; SEO-PLAN.md still says "nothing sent yet"). Batch 2 drafted in
  `private/outreach-batch2.md`, nothing sent.

## Done (for context, do not redo)

- [x] Static site, localization in 21 languages, hreflang, sitemap alternates, picker, suggestion
  bar (CP26: ac034f6, ef9a156, 93c8a38).
- [x] Move to Astro, proven identical on all 267 pages before cutover (CP71, c089b1b, 2026-10-15).
- [x] SEO-PLAN Phases 1 to 4: titles and entity, FAQ answers, desktop page, guides hub, Updated
  dates, header Download button, closing download cards, phone bar, after-download panel, robots
  for AI crawlers, Markdown twins, llms-full.txt, IndexNow on every site push (CP72, through
  16fdad5, 2026-10-15).
- [x] Lighthouse pass: `?v=` hashed screenshots cached a year, phone copies, minified app.js,
  no forced reflow, download page layout shift 0.002 (c2f327a, 2026-10-18).
- [x] Video, not film, in the site copy, alt text, FAQ and llms.txt (000684f); plainer wording
  (b0f5470).
- [x] Buy Me a Coffee first, floating card, offer behind an info button (b6b8af5, 4c4678a);
  its page in a native dialog loaded ahead of the click, Permissions-Policy `payment` (cb2511b).
- [x] Phone page `/android/` (CP90, 830aa68); Android TV and Fire TV pages as the player, guides
  as the how-to (e718974).
- [x] Guides: Samsung or LG TV, Chromecast casting (8f43b12), external player (ec67a1f);
  `/alternatives/` hub (6358e52) and one page per app (84b0d32); `sameAs` with the Telegram
  channel and group (d0e4939); release pages link the blog post (1accd47).
- [x] Screenshots retaken for 1.27.0 and the TV top bar (4d3e7ba, 43fb9b8).
- [x] Home "New in" pill and stats row worked out at build time (cb2511b); Downloader code 3206503
  beside tmplayer.org/apk (6b604cf).
- [x] The redesign: new home, Download tabbed by device, Features, Install, FAQ, one header and
  footer, StarBand and GiveBand on every content page, in all 21 languages (7497a81, 2026-10-20).

## Phase A: after the redesign (bugs, regressions, rule drift)

The redesign changed 181 files in one commit and was checked by eye in rounds. This phase proves
it holds everywhere before more is built on it.

- [ ] **SA01 Visual pass across sizes, themes and scripts.** Headless Chromium screenshots of
  home, download (each tab), install, features, FAQ, a guide, an alternatives page, a release page
  and support, at 320x640, 390x844, 844x390, 1024x768 and 1440x900, light and dark, in en, de
  (long words), ml (tall script), ar and fa (right to left). Files: `site/src/styles/styles.css`,
  `site/src/components/*`. Check: no horizontal scroll (`scrollWidth <= innerWidth` read after
  load), nothing clipped or overlapping, the coffee pill clear of the phone download bar, the
  star and coffee cards apart, no orphan chip in a row; fixes committed with before and after
  shots in the scratchpad. Needs: nothing. Lane A. User: no.
- [ ] **SA02 Lighthouse after the redesign.** The stylesheet grew from about 14 KB to 23 KB
  compressed and the home hero is new (TV and three phones that fan out). Run the CLAUDE.md loop
  on a `pnpm -C site preview` build (home, features, download; mobile and desktop; median of
  three, one run at a time; check `curl -s https://api.github.com/rate_limit` first). Files:
  whatever the failing audits name. Check: every page 100 or a written reason; LCP under 2.0 s
  mobile; CLS under 0.01 on `/download/` with the tab chosen before first paint; the CLAUDE.md
  table updated with a 2026-10 "after the redesign" column. Needs: SA01 fixes merged (so the
  numbers are of the final layout). Lane B. User: no.
- [ ] **SA03 Screenshot `sizes` re-measured for the new layouts.** `shot-sizes.ts` and
  `shot-widths.json` were measured from the old pages; the bento, the device fan and the new
  crops draw at different widths now. Measure each drawn width at 412 px and 1.75x (Lighthouse's
  phone) and at 1440, adjust `sizes`, add only copies that are missing
  (`scripts/site-shot-variants.py <name>`, originals untouched, no re-encoding). Check: no
  "Improve image delivery" item that a correct copy would fix; `--check` clean. Needs: SA01.
  Lane B (same files as SA02, run with it, not beside it). User: no.
- [ ] **SA04 Keyboard, screen reader and reduced motion.** Walk the new interactive parts with the
  keyboard and Orca in Chromium: download tabs (arrow keys, Home and End, `aria-selected`,
  focus visible), the install device picker, the copy chips (a polite "Copied" announcement), the
  coffee dialog (focus moves in, Esc closes, focus returns to the link), the ads info `details`,
  the FAQ groups, the language picker, the ToTop button. With
  `prefers-reduced-motion: reduce` the phone fan, the count-up and the cross-fade do not run.
  Files: `site/src/scripts/app.js`, `styles.css`, the components named. Check: each item works
  and is noted here; Lighthouse accessibility stays 100. Needs: nothing. Lane A. User: no.
- [ ] **SA05 Scripting off.** With JavaScript disabled every download card shows (the tabs become
  a jump list), the stats are final, the coffee links open buymeacoffee.com, the FAQ reads in
  full. Check: a `--disable-javascript` screenshot of home, download and FAQ. Needs: nothing.
  Lane A. User: no.
- [ ] **SA06 Titles and descriptions in range again.** English: home title 63 characters,
  features 61 (rule: under about 60); `/android/` description 180 (rule: 120 to 160). Localized:
  75 titles over 60 characters and 21 descriptions over 170 (CJK descriptions under 100 are
  fine, they count characters, not letters). Shorten in `site/i18n/en.json` and the matching
  catalog lines; keep "unofficial" and the long name in the home title. Add the length check to
  `scripts/site-links.py` as a warning for English pages (error over 65 and 170). Check: the
  audit script finds no English page out of range. Needs: nothing. Lane C. User: no.
- [ ] **SA07 llms.txt complete.** `/signing/` and `/translate/` are in `lastmod.json` but not in
  `site/public/llms.txt`. Add them, and add to `site-links.py` a check that every `lastmod.json`
  path appears in `llms.txt`. Check: the new check passes and fails on a removed line. Needs:
  nothing. Lane C. User: no.
- [ ] **SA08 Banned words in release notes.** `site/src/data/releases.json` carries "uploader"
  (1.27.0 notes) and "films" (1.20.0 notes) from the tag messages, and the changelog, the feed,
  the release pages and their Markdown twins publish them. `releases.json` is never hand-edited
  and old tags are not rewritten, so `scripts/release-data.py` gets a small wording map applied
  to the notes it writes ("the uploader's" becomes "the poster's", "films and shows" becomes "videos and shows"), and `site-links.py` fails on the
  TMPlayer wording list (CLAUDE.md global rules) in any built English page. Check: rerun the
  script, the grep of `site/dist` finds none, the release job still produces byte-identical
  output for an unchanged release. Needs: nothing. Lane D. User: no.
- [ ] **SA09 CLAUDE.md drift.** The Sponsoring section names `StarCard`; the components are
  `StarBand` (with `GiveBand` closing the page) and, on the home page, `StarLCard` and
  `GiveLCard`. Guides close with `MoreHelp`, which carries the GiveBand. Update the text so the
  next agent reaches for the right component. Check: every component named in the Site sections
  exists under `site/src/components/`. Needs: nothing. Lane C. User: no.

## Phase B: content and pages

- [ ] **SB01 Release day refresh, as a checklist.** At each release: the home pill and stats, the
  download page version and the What's new lines move by themselves; by hand, the screenshots of
  anything the release changed (WebP q88, phone copies, alt text and captions in all 20
  catalogs), the FAQ and guide claims the release changes, `release-posts.json` when a blog post
  exists, `lastmod.json` for each page whose words changed. Put the list in `site/README.md`
  under "Checks". Check: the list exists and the next release follows it. Needs: nothing (first
  use at the next release, no sooner than 2026-10-26). Lane D. User: no.
- [ ] **SB02 Fact recheck of the comparison pages.** `/alternatives/` and its three pages were
  sourced on 2026-10-19 (`src/data/alternatives.json`, `vs.json`, sources in SEO-PLAN.md).
  Recheck each claim monthly, first on 2026-11-19: store pages still live or gone, versions,
  prices, platforms. Facts only, no digs. Check: each row has a "checked" date in the data and
  `lastmod.json` moves only when a fact changed. Needs: nothing. Lane E. User: no.
- [ ] **SB03 Samsung and LG guide recheck** (Google Cast on Tizen and webOS is rolling out).
  Due 2027-01-15. File: the guide page and its strings. Check: every brand line has a source
  dated within three months. Needs: nothing. Lane E. User: no.
- [ ] **SB04 Proxy answer.** 1.27 added proxy settings (CP77) for places where Telegram is
  blocked. Add a FAQ answer ("Telegram is blocked here: can TMPlayer use a proxy?", MTProto,
  SOCKS5 and HTTP, before sign in, a t.me/proxy link or QR) and one line on the Android TV page.
  A full guide only if Search Console shows the queries at SC01. Wording stays factual, no
  promise of getting around anything. Files: `faq` components, `en.json` plus 20 catalogs,
  FAQPage JSON-LD (same order and words), `llms.txt`. Check: build, i18n check, the FAQ twin
  carries it. Needs: confirm the shipped behaviour from the 1.27.0 tag notes. Lane C. User: no.
- [ ] **SB05 macOS card wording after the real Mac test.** CP70 is waiting for the user's
  friends to test playback, fullscreen and first launch. When they report, the download card and
  `/desktop/` drop "preview" or say what does not work. Needs: the test (user). Lane D. User:
  yes, the report.
- [ ] **SB06 Testimonials** (R8, deferred): only with written permission, with the person's
  chosen name, never invented, never a rating in JSON-LD. Default: not done until someone offers
  one in the Telegram group or Discussions. User: yes, the permission.
- [ ] **SB07 Burmese.** Myanmar had the best CTR of any big country in the 2026-10-14 export
  with no Burmese page. Default: add `my` once a native reader is found in the group (machine
  Burmese is weaker than the other 20); the site side is one entry in `LANGS` plus a catalog.
  User: yes, the go-ahead (adding a language is the user's call, SEO-PLAN.md).

## Phase C: SEO and crawlers (continues private/SEO-PLAN.md)

- [ ] **SC01 Measure Phase 5, on 2026-11-12.** The user exports Search Console (same filters as
  2026-10-19: last 3 months, web; plus per-page queries for `/`, `/download/`, `/install/`,
  `/android-tv/`) and Bing's Search Performance and AI Performance. Claude compares with the
  2026-10-14 and 2026-10-19 exports in SEO-PLAN.md, writes "Export of 2026-11-12" there, and
  turns what moved and what did not into new items here. Check: the section exists and each
  query from SEO-PLAN's "What the data says" has a before and after. Needs: the exports. User:
  yes, the exports.
- [ ] **SC02 Request indexing.** U20 asks for the guides, `/alternatives/` pages, `/download/`,
  `/faq/` and `/` on 2026-10-21 (quota about ten a day), then the localized home pages. User:
  yes (Search Console login).
- [ ] **SC03 Outreach follow-ups.** Batch 1 went out 2026-10-19 and 2026-10-20; one follow-up
  each on 2026-11-02 or 2026-11-03 (two weeks, the outreach rule), only to those who did not answer.
  Record answers and links won in SEO-PLAN.md (and fix its "nothing sent yet" line now). Batch 2
  (`private/outreach-batch2.md`) goes when the user says. Check: `outreach-sent.json` has the
  follow-up dates. User: yes, the go-ahead to send.
- [ ] **SC04 Structured data check in CI.** A script (inside `site-links.py` or beside it) parses
  every JSON-LD block of the built pages and checks the types used here: `BreadcrumbList` on
  every page but home and 404, `FAQPage` matching the visible questions, `HowTo` steps,
  `SoftwareApplication` with `softwareVersion` equal to `releases.json`'s latest, `BlogPosting`
  dates on release pages, `Organization` `sameAs` URLs answering. Then one manual pass through
  Google's Rich Results Test on home, FAQ, a guide and a release page (Search appearance is still
  empty). Check: the script runs in CI and catches a broken block. Needs: nothing. Lane D. User:
  no.
- [ ] **SC05 Content-Security-Policy**, open since the 2026-10-15 audit. Start with
  `Content-Security-Policy-Report-Only` in `site/vercel.json`: script hashes for the inline theme
  and tab scripts, `/_vercel/insights/`, `api.github.com`, `frame-src` for Buy Me a Coffee's
  widget, fonts and images from self. A week of no reports in the browser console on every page
  type, then enforce. Check: the header is live, no console violation on home, download (each
  tab), a guide, support, the coffee dialog. Needs: SA01 (no layout work in flight). Lane F.
  User: no.
- [ ] **SC06 Localized pages discovered but not indexed.** The language links are in the header
  picker only. Add a plain language list in the footer (R8 asked for it) so every page links
  every language in crawlable HTML, and link the localized guides from the English guides hub's
  language line. Check: links check green, Search Console's localized "indexed" count grows at
  SC01. Needs: nothing. Lane A (footer files). User: no.
- [ ] **SC07 Home and download `lastmod` on release days.** The home pill, stats and the download
  version change at every release, but their `lastmod` stays at the last hand edit, so IndexNow
  is not told. Default: the release job sets `/`, `/download/` and `/changelog/` to the release
  day in `lastmod.json` in the same commit as `releases.json`. Files: `.github/workflows/release.yml`
  or `scripts/release-data.py`. Check: a dry run on the 1.27.1 data produces the right diff.
  Needs: nothing. Lane D. User: no.
- [ ] **SC08 security.txt renewal.** `Expires: 2027-10-13`; renew in September 2027. Check: a
  reminder line in `private/PLAN.md` near the end of 2027. User: no.

## Phase D: languages and translation review

- [ ] **SD01 Strings still in English.** List every catalog value identical to English (12 to 43
  per catalog, ml, fa and bn the most), keep brand names, product names and code, translate the
  rest, and add the brand allowlist to `site-i18n-check.py` so it can warn on new ones. Check:
  the warning list is empty. Needs: nothing. Lane C. User: no.
- [ ] **SD02 Native review** (U22, CP24): Hindi and Malayalam by the user, Spanish by the issue #2
  author, then anyone from the Telegram group for the rest; `/translate/` already invites it.
  Check: each reviewed catalog is marked in `site/README.md`. User: yes.
- [ ] **SD03 Comparison pages stay English.** The 240 `vs.*` keys are English only by design.
  Default: keep it so until SC01 shows localized searches for the other apps; then translate the
  TMP page first. User: no.

## Phase E: performance

- [ ] **SE01 Live Lighthouse after deploy.** After SA02 and SA03 are live, run the same loop
  against https://tmplayer.org (cache headers only count there). Check: scores recorded in the
  CLAUDE.md table. Needs: SA02, SA03 deployed. Lane B. User: no.
- [ ] **SE02 Stylesheet weight.** If SA02 shows render blocking time grew with the 23 KB
  stylesheet, remove rules no page uses any more after the redesign (old bands, old cards) rather
  than splitting or inlining it (the shared immutable file stays, CLAUDE.md). Check: compressed
  size drops and no page changes in the SA01 shots. Needs: SA01, SA02. Lane B. User: no.

## Phase F: sponsoring

- [ ] **SF01 Coffee dialog in other browsers.** Firefox and WebKit (Playwright's builds are fine
  for this; Chromium stays the default) for the native dialog, the iframe loading ahead of the
  click, Esc and backdrop close, and the fallback if Buy Me a Coffee ever refuses framing (the
  link opens their page instead of an empty frame). Check: works in all three, or a fix. Needs:
  nothing. Lane F. User: no.
- [ ] **SF02 Offer placement audit.** Every content page ends with the offer, the home page
  keeps StarLCard after the bento and GiveLCard after the setup steps, the floating card is on
  every page but download, install, support and 404 (true at this build). Add this to
  `site-links.py` so a new page cannot ship without it. Check: the check fails on a page with
  the offer removed. Needs: SA09. Lane D. User: no.

## Phase G: analytics

- [ ] **SG01 Confirm Vercel Web Analytics is on** for the project (the script path 404s
  harmlessly when it is off). User: yes (Vercel dashboard), one look.
- [ ] **SG02 Click counts without custom events.** Vercel's custom events are a paid feature; the
  budget is $0. Default: no events. Read referrers and top pages in Vercel monthly, GitHub
  download counts (`scripts/download-counts.sh`) and Search Console, and write the month's
  numbers into SEO-PLAN.md at each SC01-style review. Nothing about visitors is added, so the
  privacy page stays as it is. User: no.

## Phase H: release and changelog automation

- [ ] **SH01** Wording map in `release-data.py`: see SA08.
- [ ] **SH02** Release day lastmod: see SC07.
- [ ] **SH03 Tag message as SEO text.** `docs/RELEASING.md` gets the rule that a tag message is
  the release page's text: one plain first line that says what changed for the viewer, video not
  film, no titles, no "uploader". Check: the line is in the doc. Lane D. User: no.
- [ ] **SH04 Feed and release page check.** `site-links.py` checks that the RSS feed's newest item,
  the newest release page, `latest.json` and `releases.json`'s first entry name the same version.
  Check: fails on a mismatch made by hand in a scratch copy. Lane D. User: no.
- [ ] **SH05 web.tmplayer.org hand-off** (CP92, later): when the browser app exists, the site
  gets a "Watch in the browser" line on the download page and the home download box, a
  `/web/` page or section, the privacy page changes WEB.md lists, and `llms.txt`. Not before
  CP92's phase 1 works. User: yes, the go-ahead for CP92 itself.

## Parallel lanes

Lanes touch different files, so two agents can take one lane each (never more than two at once):

- Lane A: `styles.css` and layout components (SA01, SA04, SA05, SC06).
- Lane B: performance and images (SA02, SA03, SE01, SE02), after Lane A's fixes.
- Lane C: strings and `llms.txt` (SA06, SA07, SA09, SB04, SD01).
- Lane D: scripts and CI checks (SA08, SB01, SC04, SC07, SF02, SH03, SH04).
- Lane E: research rechecks (SB02, SB03), any time.
- Lane F: headers and the dialog (SC05, SF01), after Lane A.

Recommended order: SA01 with SA08 (A and D), then SA06 with SA07 and SA09 (C) beside SA04
(A), then SA02 and SA03 (B), then the Lane D checks, then SC05 and SF01.

## Only the user

- SC01: the Search Console and Bing exports on 2026-11-12.
- SC02: Request indexing in Search Console, about ten a day.
- SC03: the go-ahead for follow-ups (from 2026-11-02) and for batch 2.
- SD02: the Hindi and Malayalam review, and asking the Spanish reviewer.
- SG01: one look at Vercel's analytics switch.
- SB05: the Mac testers' report.
- SB07: whether Burmese is added.
- SB06 and SH05: only when someone offers a testimonial, or CP92 starts.
