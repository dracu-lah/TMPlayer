# Web app rewrite plan (web.tmplayer.org), 2026-10-22

Goal (user, 2026-10-22): the fastest and most usable Telegram media player in a browser; "I just
want the player to be super fast". Decided with the user the same day:

- Rewrite the web client. It no longer draws the apps' Compose screens (the 2026-10-21 rule is
  lifted for the web only); it keeps their look, strings and behaviour.
- **SolidJS + TypeScript, built with Vite**, installed as a PWA. Not Astro (a content site tool;
  tmplayer.org stays on it) and not htmx (needs a server holding every user's Telegram session).
- TDLib (tdweb) is replaced by an MTProto library in JavaScript running in a worker.
- Nothing is built until the user says go. The current web app stays live until the new one
  reaches parity, then is hot swapped on the same domain (no beta domain).

Research done 2026-10-22 (two passes: the Telegram layer, playback and UI). Picks: **mtcute** for
Telegram, **mediabunny** for MKV to fragmented MP4, native playback first everywhere. Sources at the end.

## 1. Why the current web app is slow (measured 2026-10-22)

Headless Chromium on the desktop over a fast line; a phone will be several times slower.

| What | Now | Target (mid-range phone and iPhone on 4G) |
| --- | --- | --- |
| Bytes before first paint | about 7 MB brotli (Compose 2.3 MB, skiko 2.6 MB, fonts, JS) | under 200 KB |
| Cold visit, UI / QR | 3.3 s / 8.5 s | under 1 s / under 2 s |
| After sign in | TDLib wasm, 3.3 MB brotli (13.7 MB raw), compiled in a worker, database on OPFS | no wasm before the first list |
| Signed-in reload to Home | about 6 s blank then spinner | Home from cache under 1.5 s |
| Search "jailer" | skeletons then "Telegram is slow to answer" | first results under 1 s |
| First frame | stalls; MKV, HEVC and Dolby go to software FFmpeg on a canvas | under 2 s (MP4 or remuxable MKV), seek under 1.5 s |
| Idle | n/a | CPU near zero, no reads when paused or closed |
| Lighthouse mobile (live site) | FCP 3.1 s, speed index 15.5 s, script boot 15.1 s, main thread 32.9 s, 12.2 MB moved | Telegram Web A for scale: 521 KB, FCP 2.5 s, interactive 3.7 s, boot 1.0 s |

Causes: about 34 MB of wasm must load and compile before anything is useful; TDLib boots its own
database and syncs before the first query; Compose draws everything on one canvas so no HTML
appears early; most real files miss the hardware decoder.

## 2. Architecture

```
 page (Solid UI, main thread)          worker: tg (MTProto)                 service worker
 ─────────────────────────────         ────────────────────────────        ──────────────────────
 screens, router, stores  ── RPC ──►   client, session, DCs, auth           app shell precache
 player controls                       dialogs, history, search             /stream/<key> ranges
 player engines:                       file downloader (chunks, parallel,   ── asks the tg worker
   native <video>  ◄── /stream ───────  priority, cancel, cache)  ◄──────       for bytes
   MSE remux (MKV → fMP4)              updates (new messages)
   movi (last resort, lazy)            IndexedDB: session, cache
```

- **One tab owns the Telegram connection** (Web Locks API); other tabs talk to it through a
  BroadcastChannel and take over if it closes. Replaces today's "Use it here" takeover.
- **Data flow:** every list is read from IndexedDB first and drawn at once, then refreshed from
  Telegram and patched in place (stale while revalidate). No spinner for anything seen before.
- **Code split:** the first load is the shell, sign in or Home, and the Telegram worker. The
  player engines, Settings, show pages and movi load on demand, and are prefetched while idle.

### 2.1 Telegram layer: mtcute

**Pick: `@mtcute/web` 0.32.6 (MIT), pinned** (pre 1.0, so its API still moves). Measured bundles
(esbuild, minified): mtcute 237 KB brotli plus a 59 KB crypto wasm (AES-IGE about 70 MB/s, 2.5x
the JS ciphers); GramJS 169 KB brotli but more once its Node polyfills are added, pure JS crypto,
slower upkeep, and Web A's better fork is not published; tdweb today 3.3 MB brotli plus 13.7 MB of
wasm to compile. reelbox, the closest app to ours, runs on mtcute.

- **Where it runs:** `TelegramWorker` (a SharedWorker where the browser has one, Safari 16+, else
  a dedicated Worker), so crypto and parsing never touch the main thread. Session in IndexedDB
  (`IdbStorage`), so a returning visit opens no new auth key. Wasm loaded with
  `instantiateStreaming` and served as `application/wasm`.
- **One owner per browser:** the SharedWorker is the one connection for every tab; where it is a
  dedicated Worker, a Web Lock picks the owning tab and the others talk to it over a
  BroadcastChannel and take over when it closes.
- **Own api_id and hash** from secrets at build time (never Web K's public one).
- **Sign in:** QR via `exportLoginToken` (renewed before expiry, about 1 s to show once
  connected, estimated), phone with the country picker, code (app, SMS, email), 2FA password with
  hint, account reset, logout. The sign in rules stay those of the core's AuthFlow (2.2).
- **Connections:** separate download pools per DC (mtcute's main, download and downloadSmall
  pools). Start at **3 download connections and about 9 MiB in flight per DC**, as Web A and Web K
  do for free accounts; measure 2, 4 and 6 on day 1 and stay near the official clients, since more
  invites flood waits. Inactivity timeout 300 s.
- **Media DC warm-up** (reelbox): 1.5 s after a list draws, one 4 KB `getFile` to each media DC
  seen in it, skipped with Save-Data; this pays the auth export and handshake before Play.
- **Errors:** mtcute's flood waiter sleeps through waits up to 10 s and remembers them per method,
  so a short wait never surfaces as "Telegram is slow"; a long one says how long. File reference
  expired or invalid: refetch the message, retry the part. `FLOOD_PREMIUM_WAIT`: Telegram's
  Premium note at most once an hour (`upload_premium_speedup_notify_period`). CDN redirects are not
  asked for (`cdn_supported` unset): browsers cannot reach CDN DCs and no web client handles them.
- **Updates:** new messages in followed chats patch the caches (new episodes, new videos). When
  the tab is hidden and nothing plays, the connection idles and resumes on focus.
- **Telegram's terms kept:** sponsored messages shown in channels; read state never faked or
  hidden; nothing done on the user's behalf without them knowing; restricted messages skipped as
  `MediaMapper` does.

**Lists and search** (what reelbox and Telegram's own Media tab do):

- Chat list: `getDialogs` / `iterDialogs`, first 40 drawn at once, the rest in the background.
- A chat's videos: `messages.search` with the Video filter and, in parallel, the Document filter
  kept to `video/*` and `.mkv` (files posted as documents), 100 per page; counts from
  `messages.getSearchCounters` without fetching messages.
- Across chats: `messages.searchGlobal` with the same two filters in parallel, paged by
  `next_rate`, `offset_peer`, `offset_id`; empty query to browse (limit 100), limit 60 while typing.
  Debounce 350 ms as the apps; a new query cancels the old one (AbortSignal).
- Telegram's own extras when a post has them: `alternative_videos` (H.264, HEVC or AV1 copies,
  with an HLS playlist) and storyboards (2.4, 2.5).

### 2.2 Shared rules from the Kotlin core

The apps' naming and grouping rules (MediaName, MediaFacts, SeriesShelf, SeriesFill, HomeRows,
HomeFeatured, SameTitle, SameVideo, VideoCopies, SplitPieces, SizeFilter, VideoSort, GlobalSearch,
Fuzzy, ResumeSyncDoc, WatchedBook, TelegramLinks, StoryboardMaps, Trickplay...) live in
`core/src/pureMain`. Two ways, decided by a day 1 spike:

1. **Compile them to a small JS module** (a Kotlin/JS target with `@JsExport` facades, IR and
   dead code elimination). Kept if it is under about 150 KB brotli and loads off the first paint
   path. One source of truth: a rule fixed for the apps is fixed for the web.
2. **Otherwise port them to TypeScript**, with the Kotlin tests' cases exported as JSON fixtures
   that both test suites run, so the two never drift.

Resume across devices uses the same Saved Messages document (ResumeSyncDoc) as the apps.

### 2.3 Streaming

After Web A (`src/serviceWorker/progressive.ts`), Web K (`src/lib/serviceWorker/stream.ts`) and
reelbox's reader:

- **Chunks:** 512 KiB for files up to 75 MB, 1 MiB above (Web K: mobile Safari will not start a
  large video fed in 512 KB parts). Offsets and limits follow Telegram's rules: divisible by 4 KB,
  1 MiB divisible by the limit, never across a 1 MiB boundary.
- **Queue:** a byte budget per DC (about 9 MiB in flight), priority order: the part the player is
  waiting on, then read-ahead, then warm-ups. A seek or close cancels everything behind it.
  Identical in-flight requests are merged. Stalled parts time out and reconnect (Web A: 60 s, 5
  retries; ours shorter for the waiting part).
- **Read-ahead:** 30 s of the file's bitrate, clamped to 6 to 16 MiB (reelbox); stops when paused
  long, closed or hidden.
- **Head and tail first:** on open, the first chunks and the **last** chunk together (the MP4
  `moov` or the MKV Cues often sit at the end; MKV Cues are found through the SeekHead).
- **Memory and cache:** an in-memory LRU (48 MiB phone, 128 MiB desktop) with head and tail
  pinned; the first 2 MiB of recently played videos kept in the Cache API (Web A's
  `tt-media-progressive`), so a reopened video starts at once; nothing written that can fill the
  quota and cost the session.
- **Service Worker** answers `/stream/<key>?size=&mime=` with a real 206 and a correct
  `Content-Range`, at most 2 MiB per answer; **Safari's `bytes=0-1` probe is answered locally from
  the size in the URL** without touching Telegram (WebKit bugs 184447, 229413); a request without
  a Range header gets a streamed 200. Bytes come from the tg worker over a MessageChannel with a
  timeout; when iOS has killed the Service Worker in the background, it fails cleanly and the
  player reopens the stream. Split pieces are joined into one virtual file.
- **Warm-up on intent:** a card held under the pointer or focus for 250 ms, or touched, fetches
  the first 4 chunks and the last one (two for files over 256 MiB); the next episode is warmed 30 s
  before the end. Warm-ups stop when the user moves on or the player closes.
- **Lighter copies:** Telegram's 360p and 720p copies offered or picked on a slow line or Save
  mobile data, as the apps do.

### 2.4 Playback engines

One pipeline everywhere: **read the file's header, then play natively or remux, and let the
hardware decoder do the work.** Software decoding is the last resort. No other web player does
the remux in the browser (Jellyfin, Plex and Stremio do it on a server), so this is our edge.

Decided per file from the container and the codecs read from its header (not its name), checked
with `MediaSource.isTypeSupported`, `canPlayType` and MediaCapabilities, then **confirmed by the
first frame and audible sound arriving within a timeout**, since browsers sometimes say yes and
play silence (WebKit bug 198583). On a miss it moves to the next engine at the same position
(the idea from avbridge, MIT, worth reading).

1. **Telegram's HLS copy** when the post is a video with `alternative_videos`: native HLS on
   Safari (iPhone's fastest path, H.264, nothing to remux), hls.js 1.7.x (Apache-2.0, lazy) on
   Chrome and Firefox. Only when the copy is not much lighter than the upload is the upload used.
2. **Native `<video>`** over `/stream`: MP4 and WebM the browser decodes (on iPhone H.264, HEVC
   and AC-3/E-AC-3 in MP4). AirPlay, PiP and the lock screen come free.
3. **Remux to fragmented MP4** in a worker with **mediabunny 1.62 (MPL-2.0, about 148 KB brotli,
   tree-shaken)**, fed to **ManagedMediaSource** (iPhone, Safari; needs `disableRemotePlayback` or
   an AirPlay alternative; listen to `startstreaming` / `endstreaming`) or MediaSource. Video bytes
   untouched; HEVC written as `hvc1` (Safari refuses `hev1`). Seeks jump to the cluster the Cues
   name. A file without Cues builds an index on its first pass, cached. Forward buffer 30 to 60 s.
   - Sound the browser cannot play (AC-3 and E-AC-3 on Chrome and Firefox, DTS and TrueHD
     everywhere): decoded with `@mediabunny/ac3` / `@mediabunny/dts` (lazy) and encoded with
     WebCodecs `AudioEncoder` to AAC (Opus where AAC is not offered), in 1 to 2 s fragments aligned
     to the video's; downmixed to stereo on phones. TrueHD and rare cases through libav.js 6.10
     (LGPL-2.1) with libavjs-webcodecs-bridge, lazy.
4. **movi-player** (FFmpeg wasm on a canvas) only for picture codecs this device cannot decode:
   HEVC on Chrome for Linux without VA-API, 10 bit HDR on Firefox, AVI, old codecs. Loaded only
   then. Too heavy to decode in time: the honest "needs the app" with Play anyway.

Codec facts to plan around (2026):

| | iPhone Safari | Android Chrome | Desktop Chrome / Edge | Firefox | macOS Safari |
| --- | --- | --- | --- | --- | --- |
| MKV | remux, ManagedMediaSource | remux, MSE | remux, MSE | remux, MSE | remux, ManagedMediaSource |
| HEVC | hardware, 8 and 10 bit | Main yes, Main10 by chip | Windows D3D11 and macOS yes; Linux only with VA-API | 134+ Windows (OS extension), 136+ macOS, 137+ Android; 10 bit HDR poor | yes, Dolby Vision 5 and 8 |
| AC-3 / E-AC-3 | native | convert | Chrome convert; Edge native on Windows (not on clean 24H2) | convert | native |
| DTS / TrueHD | convert | convert | convert | convert | convert |

Each engine reports one state shape (position, duration, buffered, playing, waiting, tracks,
errors), so the controls do not care which runs. One `<video>` element and one MediaSource are
reused. The engine used and why is logged and shown in the playback details panel.

**iPhone memory:** iOS kills a tab that grows past roughly 300 to 500 MB rather than failing, so
on the iPhone the picture is always decoded by the native element (outside the wasm heap), the
remux worker keeps a small window, and nothing else heavy shares the tab.

### 2.5 Player features

- Controls: play, pause, seek bar with buffered range, time and remaining, skip back and forward,
  speed, volume and mute, fullscreen, picture shape, audio track, subtitle track and delay,
  chapters, next episode with the countdown, skip intro, bookmarks, A-B repeat, sleep timer,
  frame step, playback details.
- **Scrub thumbnails** (the request that started this): Telegram's storyboard tiles when the
  video has one; otherwise frames from a second, private reader over the parts already loaded
  (a hidden video element where the engine is native or MSE; skipped on iPhone if it costs the
  main video). Cached by time, cancelled when the pointer moves on, never fetching on its own.
- Input: the desktop's keyboard shortcuts; touch: double tap seek, hold for 2x, swipe for
  volume (not on iOS) and brightness (a dimming layer); TV-like remotes via arrow keys.
- Subtitles: SRT converted to WebVTT on a native `<track>` (free, and it shows in iOS's own
  fullscreen); ASS through JASSUB 2.5 (libass in wasm, WebGL in a worker, Safari 17+), loaded only
  when an ASS track is picked, with in-page fullscreen on iPhone since it cannot draw over iOS's
  own; plain lines where JASSUB cannot run; PGS listed and labelled; online subtitles as the apps.
- Media Session (lock screen and hardware keys), PiP (hidden in an installed iOS app, WebKit bug
  303885), AirPlay from the native element, Remote Playback where offered.
- Resume: place saved every 10 s, on pause and on close; resume rewind as the apps do;
  Continue watching and Watched updated; synced through Saved Messages.

### 2.6 Screens (same look as the apps, phone and desktop layouts)

- Shell: bottom bar under 600 px wide (Home, Chats, Search, You), rail above; light, dark, black;
  RTL; safe areas for the notch and the Home indicator.
- Sign in: QR, phone, code, password, errors, the privacy line.
- Home: carousel (films and shows only), Continue watching, Movies, Shows; posters and
  backdrops from the online metadata (TMDB, TVmaze, AniList, through fetch, cached).
- Chats: folders, archive, pinned, favourites, hidden chats; chat grid with sort, size filter,
  search in chat; chat info sheet.
- Show page: seasons, episodes, copies, missing parts, next episode. Detail page for a film.
- Search: across chats with @username, filters (films, shows, size), recent searches, results
  as they arrive, debounced as the apps (350 ms).
- You and Settings: the pages a browser can do (Playback, Subtitles, Library, Storage, Online
  metadata, App lock and kids lock, Appearance, Language, About, Send logs).
- Offers: install (Home Screen steps on iPhone, Chromium's prompt, the native app link), the
  coffee card with **Buy Me a Coffee only** (no GitHub Sponsors), the tour.

### 2.7 Strings, fonts and assets

- Strings from `core/src/commonMain/resources/i18n/*.json`, the same keys; only the active
  language is loaded (about 15 to 40 KB per catalog brotli). Web-only strings are new keys in
  `en.json`; other locales follow the LangSync rule (the user runs it).
- Fonts: the system UI font first; Noto subsets as woff2 only for scripts the system lacks,
  loaded when a catalog or a title needs them (today's NotoSans.ttf alone is 346 KB at start). No
  5 MB emoji font (the system's emoji are used).
- Icons as an inline SVG sprite from the apps' icon set.

## 3. Performance budget (enforced in CI)

| Item | Budget (brotli) |
| --- | --- |
| HTML + CSS + first JS chunk (shell, router, Solid) | 60 KB |
| Telegram worker (mtcute + crypto wasm) | 300 KB, loaded in parallel with the shell |
| Home screen chunk | 40 KB |
| Player chunk (controls + native engine) | 40 KB, prefetched while idle |
| Remux worker (mediabunny) | 150 KB, prefetched while idle |
| Sound conversion (AC-3 / DTS) | about 300 KB gzip each, only when a file needs it |
| hls.js, JASSUB, libav.js | only when needed |
| movi | about 3.2 MB, only when needed |

A size check in CI fails the build when a chunk passes its budget. Lighthouse on the shell: 95+.

## 4. Test plan

- Unit tests: the chunk downloader, range answers, the remuxer (generated files: H.264/HEVC,
  AAC/AC-3/E-AC-3/DTS, SRT/ASS, with and without Cues), engine choice, the shared rules' fixtures.
- Browser tests in headless Chromium: generated files through every engine; cold load, warm load
  and seek timings recorded by a script into this file.
- Real account (the signed-in test profile): Jailer and the other films the user tried, a 3 to
  4 GB MKV seek to 95 %, a split video, a series with seasons.
- Devices: the POCO phone (Chrome, installed), an iPhone (Safari and Home Screen, the user's),
  desktop Chrome, Firefox and Safari on a Mac if available.
- Idle check: CPU and network quiet after pausing or closing, on the phone.

## 5. Edge cases

- Sign in: QR expiry, 2FA, wrong code limits, session revoked elsewhere, two tabs, logout.
- Network: offline (cached screens stay usable, play what is cached), Wi-Fi to mobile, FLOOD_WAIT,
  DC migration, expired file reference, Telegram down.
- Files: deleted or edited message, split pieces, 4 GB files, no audio, broken or truncated
  files, unsupported codecs (the honest "needs the app" with the right download).
- Browser: storage cleared or full (session kept, cache dropped first), iOS killing a background
  tab (resume where it was), private mode, no MSE, no WebCodecs, no Service Worker (direct reads).
- Player: seek before the index, rapid seeks, closing while loading (all reads cancelled), next
  episode, rotation, keyboard focus after closing, speed above 2x on native.
- Low-end: nothing polls or reads ahead when idle; refreshes stop when hidden.

## 6. Where it lives and how it ships

- New folder `webapp/` (pnpm, Vite, Solid, TypeScript), built and tested in CI next to the site.
  The Kotlin `web/` stays until the switch, then is removed with its Gradle module.
- No separate beta domain (user, 2026-10-22): the new app is tested locally (`pnpm -C webapp
  preview` on the desktop, the quick tunnel from IOS-TEST-PLAN.md for the phones), then hot swapped:
  the same Cloudflare Pages project `tmplayer-webapp` gets the new build in one deploy, after the
  user's go. The previous deploy stays one click away in Pages for a rollback.
- Sentry, analytics and the legal and privacy pages carried over.
- Docs: `docs/WEB.md` rewritten for the new design; `site/public/llms.txt` updated if its claims
  change.

## 7. Timeline (building it together)

Two agents at a time at most, as agreed; each step ends with something you can try.

| When | I build | You check |
| --- | --- | --- |
| Day 1 | Measuring script and today's numbers; four spikes: mtcute sign in and download speed with 2, 4 and 6 connections against tdweb; the core rules as JS (size); mediabunny remux of a real MKV (Jailer) into MSE in Chrome; ManagedMediaSource in an installed Home Screen app on the iPhone (unverified anywhere) | The iPhone spike on your phone through the tunnel |
| Days 2 to 3 | Scaffold (Vite, Solid, PWA shell, strings, themes), Telegram worker, sign in by QR, phone and password | Sign in on the POCO and your iPhone |
| Days 4 to 5 | Chunk downloader, Service Worker streaming, native engine, basic controls, resume | **Checkpoint 1:** an MP4 plays, seeks and resumes on both phones |
| Days 6 to 8 | MSE remux for MKV, sound conversion, movi fallback, subtitles, scrub thumbnails | **Checkpoint 2:** Jailer and the films that failed play smoothly |
| Days 9 to 11 | Chats, folders, chat grid, caches, Search, show pages, detail | Browse and search on the local build |
| Days 12 to 14 | Home, online metadata, Watched, Watch later, favourites, cross-device resume, Settings, offers, PiP, Media Session, keys and gestures | **Checkpoint 3:** the full app on the local build |
| Days 15 to 17 | Edge cases, 21 languages, budgets in CI, Lighthouse, device pass, docs | **Checkpoint 4:** your go to hot swap web.tmplayer.org |

About three and a half weeks of working days. Checkpoints 1 and 2 come first because the player
is the point; if a spike shows a better route, the plan is updated here before building on it.

## 8. Open questions for the user

1. Settled: no beta domain, local testing then a hot swap.
2. Keep the current web app live during the rewrite? (Planned: yes, until the swap.)
3. An iPhone for checkpoints 1 and 2: yours, through the quick tunnel as in IOS-TEST-PLAN.md?

## Sources

Telegram layer:
- https://github.com/mtcute/mtcute, npm `@mtcute/web` and `@mtcute/core` (download-iterable, network-manager, flood-waiter, media-throttle); https://mtcute.dev/guide/intro/faq; https://github.com/mtcute/benchmarks
- https://github.com/Ajaxy/telegram-tt (src/serviceWorker/progressive.ts, src/lib/gramjs/client/downloadFile.ts, src/util/dcBandwithManager.ts, src/global/cache.ts, src/hooks/useStreaming.ts)
- https://github.com/morethanwords/tweb (src/lib/serviceWorker/stream.ts, src/lib/appManagers/apiFileManager.ts, src/lib/mtproto/dcConfigurator.ts)
- https://core.telegram.org/api/files, https://core.telegram.org/api/config, https://core.telegram.org/cdn, https://core.telegram.org/method/messages.searchGlobal, https://core.telegram.org/api/search
- https://reelbox-10k.pages.dev/ (its sw.js and bundle)

Playback and startup:
- https://mediabunny.dev/guide/supported-formats-and-codecs, https://mediabunny.dev/guide/reading-media-files
- https://cdn.jsdelivr.net/npm/avbridge@2.13.0/README.md, https://github.com/ThaUnknown/jassub
- https://webkit.org/blog/14735/ (ManagedMediaSource), https://caniuse.com/hevc, https://github.com/StaZhu/enable-chromium-hevc-hardware-decoding
- https://issues.chromium.org/issues/40263241 (AC-3 in Chrome), https://www.mozilla.org/en-US/firefox/136.0beta/releasenotes/, https://www.mozilla.org/firefox/android/137.0/releasenotes
- WebKit bugs 184447, 229413 (range probes), 198583 (silent audio), 221530, 269777 (iOS memory)
- https://v8.dev/blog/wasm-code-caching, https://kotlinlang.org/docs/wasm-configuration.html
- https://forum.jellyfin.org/t-ios-hevc-files-always-get-transcoded-in-jellyfin-10-11, https://github.com/Stremio/stremio-video

