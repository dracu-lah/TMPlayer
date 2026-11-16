# Site refresh after 1.27.0 to 1.28.2 (started 2026-10-29)

Most site pages were last edited 2026-10-20, before 1.28.1 and 1.28.2. Two read-only audits
(2026-10-29) listed what is wrong or missing; this file is the work list and the handoff.

## Decisions (user, 2026-10-29)

- Samsung and LG: say "beta", keep them out of the hero. Neither package is known to have run on
  a real TV (PLAN.md CP105 still says "Not started").
- Main route on both brands: open web.tmplayer.org in the TV's browser. The site's middleware
  answers an LG or Samsung browser with the TV build (checked 2026-10-29 with a Tizen user agent
  and `Accept: text/html`). The `.ipk` and `.wgt` are "advanced": LG needs Developer Mode and the
  `ares` CLI, renewed; the release `.wgt` is unsigned and a recent Samsung TV needs it signed with
  a certificate holding that TV's DUID (webapp/tv/README.md).
- TV build engines: webOS 5 and 6 (Chromium 68, 79), Tizen 2020 and later (5.5).

## Phases

1. Wrong text: Samsung/LG guide, `not_samsung_tizen_not_lg`, `vs.telegram.faq_download_a`,
   guides hub "Five guides", updater FAQ (`it_checks_github_and_asks`), formats (VC-1, MJPEG,
   decoder fallback, 3D, Dolby Vision, DTS-HD MA), subtitles (MSone, styling), llms.txt. TV files
   on download, install, security, signing.
2. Missing features: features page, platform pages (android, android-tv, fire-tv, desktop),
   FAQ entries (proxy, sync, t.me, search, notifications) and ld.ts, alternatives tables.
3. Privacy: Saved Messages sync, casting, proxies, web app, TV wrappers, change list entry.
4. Screenshots: promo build, one emulator; Home and TV shots, then `site-shot-variants.py`.
5. Translate every new or changed key into the 20 catalogs, build, i18n and link checks, push.

## Progress / handoff

- Phase 1 done (2026-10-29, commit b96f618, local only, not pushed). Samsung/LG guide: new band `#tmplayer-on-the-tv`, a fifth
  way and table row (`way_app_*`, `row_app_*`, `pick_app`), lede, install_p1, description. Download:
  `#tv-packages` card under the web app with the .ipk/.wgt folded (asset kinds `tv-webos`,
  `tv-tizen` in app.js, js.kind_* strings). Install: `#smart-tv` band (3 steps) and a picker tile;
  band tones after it flipped (shortcuts tonal, trouble plain). Fixed: android_tv
  not_samsung_tizen_not_lg, vs.telegram.faq_download_a, guides.lede (six guides) and
  samsung_lg_card, faq updater answer (tmplayer.org first, Android 12+), formats (VC-1, MJPEG,
  decoder fallback, Dolby Vision as HDR10, Fire TV DV+HDR10+), subtitles (MSone; SubDL left out on
  purpose: it needs the user's own key), security build list, signing page (effective 29 Oct),
  llms.txt, lastmod for 12 pages.
- JSON-LD keys renamed (old -> new) in `private/.ld-renames.json`: the 20 catalogs still hold the old
  four keys, which is all 80 i18n-check problems. Phase 5 renames and retranslates them.
- Translate step: every new or changed key is `git diff origin/main -- site/i18n/en.json` once the
  phases are in.
- Headless Chromium draws a page opened at an anchor as white when the window is tall; shoot
  without the anchor and crop.
- Phases 2 to 5 done together (2026-10-29, session bf4d07ad, parallel agents), pushed with phase 1.
  Features page: third group "Connecting and your devices" plus tiles for casting, lighter copies,
  player tools, subtitle style, new Home, search, t.me links. FAQ: proxies, search, t.me, resume
  sync, notifications (page and faq/ld.ts). Platform pages: Android, Android TV, Fire TV, desktop
  cards; alternatives: TMPlayer row (samsung_lg platform, last update from releases[0]), vs.common
  us_* strings. macOS no longer called a preview anywhere current. Privacy: resume sync, casting,
  proxies, web player and TV wrappers, SMS consent, change list 29 Oct. Shots retaken from the promo
  build: tv-home, tv-continue, tv-rail (annotation narrowed), phone-grid, phone-grid-top (+ light).
  137 keys translated into all 20 catalogs; the 4 old ld keys dropped. Build, i18n, links clean.
- Left: phone-chats(-light) still show Settings in the bottom bar (needs the phone AVD, touch);
  tv-player could show the More options panel; desktop-* shots not retaken (no promo capture path).
  ld.ts source English for the TMP and desktop FAQ answers is older than its en.json value (only the
  source text, live JSON-LD is right); fixing it renames keys in every catalog, so left alone.
  Translations were machine made by agents: native glance welcome on guide_smart_tv.pick_app.
