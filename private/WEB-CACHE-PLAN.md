# Web app caching (plan, 2026-10-27)

Asked by the user on 2026-10-27: the web app is all network, so cache more to make it faster for
users, and check how, since the same build runs as the LG webOS IPK and Samsung Tizen WGT
(`webapp/tv/`). Add whatever is missing. Plan only: nothing is built until the user says start.
Runs before or beside `MOVI-PLAYER-PLAN.md` (phase 3 there also caches movi's WASM).

## 1. What is cached today

| What | Where | Bound | Notes |
| --- | --- | --- | --- |
| App shell (index, `/assets/*`) | Service Worker `public/sw.js`, cache `tmplayer-shell-<version>` | per version, old ones deleted | **phone and desktop build only**: the TV build (`--mode tv`) has no SW (`vite.config.ts`: `tv ? tvLegacy() : offlineShell()`) |
| HTTP cache | `public/_headers` | `/assets/*` immutable for a year | **`/tv/assets/*` and `/libav/*` are not listed**, so they get `max-age=0, must-revalidate`: a TV revalidates every file on every start |
| Settings, positions, watched, stars | IndexedDB (idb-keyval), localStorage for theme, layout, last position | small | fine |
| Telegram session and peers | mtcute IndexedDB storage `tmplayer` | mtcute's own | fine |
| Card thumbnails | Cache API `tmplayer-thumbs`, 4 fetches at a time | **no limit, no expiry** | grows forever on a large account |
| TMDB answers and posters | Cache API `tmplayer-posters`, 60 day hit / 7 day miss TTL; edge cache in `functions/tmdb` | **no size limit** | TTL checked on read only, nothing deleted |
| Video bytes | memory only (`TgFile`, 48 MB phone, 128 MB desktop), up to 3 warm files | memory | every reopen, resume or next session downloads the head, index and tail again |
| Home rows, chat list, chat grids, folders | **nothing**: rebuilt from Telegram every start | | the AGENTS.md scale rule wants first content from the disk within 3 s |
| Probe results, storyboards, chapters, subtitles of a file | **nothing** | | re-read every time a video opens |
| libav.js (and later movi) WASM | not in the SW shell (`NOT_IN_SHELL` leaves out the lazy decoders too) | | downloaded again when the HTTP cache drops it |

## 2. What to add, in order of payoff

### C1. Lists from the disk first (biggest win)

- Keep the last Home rows, Chats list (with folders and archive), and the first page of each opened
  chat grid in IndexedDB (a separate `tmplayer-lists` store), as plain `VideoItem` and chat records
  without file bytes.
- On start: draw from the disk at once (stale), then refresh from Telegram with the existing
  budgets and replace in place without the list jumping (same keys, same order where unchanged, the
  scroll position kept).
- Bounds: Home and Chats one snapshot each; chat grids for the 30 most recently opened chats, first
  200 items each; LRU by last opened. Measured against `SyntheticAccount` scale (3,000 chats,
  20,000 videos in one chat): the snapshot stays under 5 MB.
- Versioned by a schema number and the signed in account id; a different account or schema is
  dropped, never shown.

### C2. TV build: an offline shell and proper HTTP caching

- `_headers`: add `/tv/assets/*` and `/libav/*` (and movi's files) as immutable (their names carry a
  hash; libav's files get a hash or version in the path first). Quick win, no code risk.
- The hosted IPK and WGT open `https://web.tmplayer.org/`, a secure context, so the TV build can
  register a Service Worker too. Chromium 68 (webOS 5), 79 (webOS 6) and Tizen 2020 (69) support SW
  and the Cache API. Plan: build `sw.js` for the TV base (`/tv/`), scope `/tv/`, lowered to ES5 like
  the rest of the TV build; skip registration on plain http (the LAN `serve:tv`), which stays as it
  is now. Check on the user's TV that the wrapper's iframe or redirect keeps the SW (if the wrapper
  loads the page in a way that blocks SW, fall back to C2b).
- C2b, only if SW does not work in the wrappers: package the app shell inside the IPK and WGT
  (packaged app from file://, see WEBAPP-PLAN.md line 889 table), loading code updates from the site
  when online. Bigger change to the release flow, so asked before doing it.
- The "Cannot reach TMPlayer" shell in `tv/shell/index.html` opens the cached app when offline
  instead of stopping, once C2 works.

### C3. Video bytes on the disk (small, bounded)

- Keep each played file's head (first 1 to 2 MB: container header, moov or MKV cues) and tail
  (moov at the end, MKV cues) on disk, plus the 30 s around the saved position for Continue
  watching items. Reopening and resuming then start without the slow first round trips.
- Where: OPFS when there (faster, no response objects), else the Cache API; neither on the TV if
  the quota is under 200 MB.
- Bounds: 200 MB on desktop, 100 MB on phones, 50 MB on TVs, or 5% of `navigator.storage.estimate()`
  quota if smaller; LRU by last played; a file's pieces go together. A cached piece is checked
  against the file's size and Telegram's unique id before use, so a replaced file is not misread.
- Not a download feature: no whole videos on disk, nothing offline to watch (the web's "Downloads
  need the app" stays).

### C4. Per-file metadata

- Probe result (container, tracks, codecs, duration, base, route), storyboard map and sprite,
  chapters, a picked sound and subtitle track: one IndexedDB record per file key, 2,000 newest
  kept. The watch page then shows tracks, length and route before any byte arrives.

### C5. Bounds for what already exists

- `tmplayer-thumbs`: cap at 3,000 entries (about 30 to 60 MB), trim the oldest by a small index
  in IndexedDB (the Cache API has no order), run at most once a day, idle time only.
- `tmplayer-posters`: delete expired answers during the same trim, cap pictures at 1,500.
- Lazy decoders (AC-3, DTS, AAC encoder, libav, movi): cached by the SW on first use (runtime
  cache `tmplayer-decoders-<version>`), not precached, so the first load stays at its budget.

### C6. Storage health

- Ask `navigator.storage.persist()` once the user has signed in (today only when installed), so
  Safari and Chrome do not evict lists and the session under pressure.
- Settings, Storage: what each cache uses (lists, video pieces, thumbnails, posters, app), with
  Clear cache (keeps the sign in, settings, positions and watched) beside the existing figure.
- Sign out clears every new store too (`store.ts` sign out list).
- A failing or full storage never breaks anything: every cache read and write is wrapped, a
  `QuotaExceededError` trims that cache by half and carries on, private mode works from memory.

## 3. Platform notes

| Platform | Notes |
| --- | --- |
| iPhone PWA (Safari) | Quota is about 1 GB per origin when installed, data may be cleared after 7 days unused unless installed; C3 smaller (100 MB); OPFS from iOS 15.2, sync access handles from 16.4 (use the async API) |
| Android Chrome, desktop Chrome, Edge, Firefox | 60% of free disk; C3 at 200 MB; OPFS everywhere current |
| LG webOS 5 and 6, Tizen 2020+ | Chromium 68 to 79: SW and Cache API yes, OPFS no (use the Cache API), small flash and quota, so C3 at 50 MB or off when `estimate()` is missing; measure idle CPU after the trim job (AGENTS.md low-end rule) |
| Plain http LAN build | no SW, no Cache API in some engines: memory only, as now |

## 4. Edge cases

- Two tabs writing the same cache: writes go through the tab holding the Telegram Web Lock only.
- Stale lists: a chat left, a video deleted or a chat moved to another folder shows from the disk
  for a moment, then disappears on refresh; opening a deleted video says it is gone, not an error.
- A different account signed in on the same browser: account id on every record, old ones dropped.
- Clock changes and TTLs: TTLs use saved timestamps, a negative age counts as expired.
- App update with a new list schema: old snapshots dropped, not migrated.
- Restricted messages (`MediaMapper` rule): the disk snapshot is made from already filtered lists,
  and is refreshed before anything is played.
- Quota estimate missing (old TVs): assume small, keep C1 and C5 only.

## 5. Order and checks

1. C2 headers (one line each), C5 bounds, C6 persist and Clear cache. Check: TV start time over
   the LAN before and after (the user's TV), `pnpm e2e`.
2. C1 lists from the disk. Check: Home on `SyntheticAccount` scale shows within 3 s from a cold
   start with the network throttled to 3G; no jump when fresh data lands.
3. C4 metadata, then C3 video pieces. Check: reopening a 2 GB MKV and resuming at 1:00:00 time to
   first frame before and after, on desktop and phone width.
4. C2 TV Service Worker on the user's LG TV through the IPK, offline start, update to a new
   version.
5. Strings in `en.json`, the dash check, deploy to preview, then production.

## Progress / handoff

- 2026-10-27: plan written, nothing built. Next: step 1 (Sonnet, medium: headers, bounds and
  settings are mechanical). Start a new session with: "Do step 1 of `private/WEB-CACHE-PLAN.md`."
- 2026-10-27: step 1 done, commit 2949c85 on branch worktree-agent-a195f7b43d688dcd4 (local, not
  pushed, not deployed). Files: `webapp/public/_headers` (`/tv/assets/*` and `/libav/*` immutable:
  libav's files carry `6.10.9.0-tmplayer` in the name, so a new build is a new name), new
  `webapp/src/cacheBounds.ts` (index in its own IndexedDB `tmplayer-cache-index`, `touch`, daily
  idle `scheduleTrim`, `clearCaches`, `cacheSizes`, `clearIndex`), `store.ts` (thumbs touch, sign
  out clears the index), `screens/posters.ts` (touch), `install.ts` (`askKeepOnce`), `App.tsx`
  (ask + schedule trim once signed in), `screens/Settings.tsx` (breakdown + Clear cache),
  `strings.config.ts`, `en.json` (3 `web.storage_*` keys). Checks: catalog test passes, dash grep
  clean, `pnpm test:player` 11 pass. `pnpm check` only fails on the missing generated
  `src/rules/core/tmplayer-rules.mjs` (and the implicit-any errors that follow), same without my change.
  Next: step 2 (C1 lists from the disk). Gotchas: the trim waits 20 s after sign-in, then idle;
  entries kept before the index existed are adopted (size measured) by the first trim, so the
  breakdown shows 0 for them until then; "app and other" is usage minus thumbs and posters, so it
  includes IndexedDB and the mtcute session. Not done: QuotaExceededError "trim by half" (put
  failures are only swallowed), TV Settings storage block (src/tv) has no Clear cache, not tested
  in a browser, `pnpm e2e` and TV start timing not run.
