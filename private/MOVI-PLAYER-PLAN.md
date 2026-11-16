# Web player on movi-player (plan, 2026-10-27)

Asked by the user on 2026-10-27: use [movi-player](https://github.com/mrujjwalg/movi-player)
(Apache-2.0, v0.4.1, FFmpeg WASM demux plus WebCodecs decode, MKV, HEVC, AV1, multi audio, PGS and
ASS subtitles) as web.tmplayer.org's player, in TMPlayer's look, fix the seek bar's thumbnail, test on
the user's own account with the files that do not play today (HEVC first), and keep every player
feature the web app has now. Plan only: nothing is built until the user says start.

## 1. Where we start

- `webapp/src/player/`: `Player.tsx` (700 lines) runs an engine under `Chrome.tsx` (2,042 lines, the
  app's own controls on Video.js 10 `media-container`, phone, desktop and TV variants).
- Engines (`engines.ts`, `libav.ts`): `native` (Service Worker `/stream/`), `remux` (mediabunny to
  fMP4 into ManagedMediaSource or MediaSource, AC-3, E-AC-3, DTS sound converted), `libav` (AVI only).
  `route()` throws `Unplayable` and the page says "needs the app".
- Session 15 survey (964 videos): 697 native, 76 remux, **173 HEVC need the app** (MKV 87,
  QuickTime 62, MP4 9), 5 AVI (now libav), 4 small H.264 MP4 refused, 2 other. Fedora Chromium has no
  HEVC decoder, so it is the worst case.
- Seek preview: Telegram storyboard first (`storyboard.ts`), else `FrameGrabber` (`seekpreview.ts`), a
  hidden second `<video>`, only for native files, not on iPhone or TV. Remux and libav files get no
  picture: that is the "fix seek thumbnail" item.

## 2. Decision: movi as an engine under TMPlayer's own controls

Recommended, and the default unless phase 0 disproves it:

- **Keep `Chrome.tsx` as the UI.** It already is TMPlayer's player (phone, desktop, TV, four themes,
  every sheet). Restyling movi's shadow DOM chrome into ours would mean rebuilding all of that inside
  `::part()` rules and losing the TV and phone variants. "Its UI like TMPlayer's" is met by our UI
  driving movi's playback. movi's own controls stay off.
- **movi is the engine for what the browser cannot play itself**: HEVC in any container, MKV and TS
  (replacing `remux`), AVI and the rest (replacing `libav`), the refused H.264 MP4s. **Native stays**
  for MP4 the browser plays (697 of 964): it is the lightest, uses the hardware `<video>` path, and is
  the only path where Picture in Picture, AirPlay, iPhone fullscreen, lock screen and battery stay as
  they are. Phase 0 measures movi on native files too; if it is as good, the user can choose
  movi-for-all, with native kept as the fallback.
- **Fallback chain**: native, then movi, then the current remux (browsers without WebCodecs: iOS
  before 18 per movi's table, old TVs), then "needs the app". `remux` and `libav` are removed only in
  the last phase, after the survey shows movi covers them.
- **Bytes from Telegram**: a movi `SourceAdapter` (`getSize`, `read(offset, length)`, `seek`,
  `getPosition`, `close`, `getKey`, `getFatalError`) over `TgFile` from `stream/reader.ts`, so
  read-ahead, warm-up, the 48/128 MB cache, FLOOD_WAIT handling and split pieces are the same code.

## 3. Phase 0: spike and measure (no app code changed)

A scratch page in `webapp/` (not routed, not shipped) that plays one real file through movi.

1. Which build: `movi-player/player` (no UI, renders to a canvas) or `movi-player/element/slim`
   with controls off (exposes a `<video>`-like API). Pick the one whose `currentTime`, `duration`,
   `paused`, `buffered`, `playbackRate`, `volume`, events and audio/subtitle track lists can stand
   behind `Chrome.tsx` and Video.js's `media-container` with the least glue.
2. Size: slim JS plus `movi.wasm` (about 750 KB brotli plus WASM) loaded only when a file routes to
   movi, never in the first load (47.6 KB gzip budget). Served from `public/`, cached by the SW.
3. COOP/COEP: movi plays without them; check whether the fast path is worth adding the headers on
   Cloudflare Pages (they break cross-origin TMDB posters unless `credentialless`).
4. HEVC on Fedora Chromium (no hardware HEVC): does movi decode in WASM, how many fps at 1080p and
   720p, CPU. Then Chrome with hardware HEVC, Firefox, Safari 18 (WebKit Playwright), and the user's
   iPhone if they can.
5. Licence: what FFmpeg configuration `movi.wasm` is built with (LGPL or GPL), add it to
   `notices.config.ts` and the third-party notices; publish the source as for libav.js.

Exit: a short table here (browser, file, fps, CPU, first frame, seek time) and the build choice.

## 4. Phase 1: the movi engine

- `player/movi.ts`: `MoviEngine implements Engine` (`name: 'movi'`), the `TgFile` SourceAdapter,
  start position, `setAudio`, `destroy` that frees the WASM, decoders and canvas.
- A media facade if the chosen build is canvas based: the properties and events `Chrome.tsx`,
  `keep.ts` (resume), `cues.ts`, chapters and the stall timers read, so none of them branch on the
  engine.
- `route()`: native when the browser plays it, else movi when WebCodecs and WASM are there, else
  remux, else `Unplayable`. `MoviPlayer.assessPlayback()` decides up front whether the device plays it
  smoothly; if not, say so ("This device may stutter on this video") and offer Telegram's lighter
  copy (CP80 qualities) or the app, rather than a slideshow.

## 5. Phase 2: everything the player has, kept

Each row is checked on the movi engine, desktop and phone width, light and dark, and the TV layout:

| Feature | How it stays |
| --- | --- |
| Play, pause, seek bar, FineScrub drag, ±10 s, double tap, gestures | Chrome.tsx over the facade |
| Resume, rewind on resume, Watched, Continue | `keep.ts`, `store.ts` unchanged |
| Next episode loaded ahead, episodes and "More from this chat" | watch page, the next file warmed through `TgFile` |
| Sound tracks, AC-3, E-AC-3, DTS | movi decodes them (FFmpeg); the sheet lists movi's tracks |
| Subtitles inside the file (SRT, ASS, PGS), a file from the device, delay, size and colours | movi's tracks into our cue renderer (`cues.ts`) so the look settings keep working; PGS as images |
| Chapters | movi's or `chapters.ts`, one list |
| Speed, volume and boost, keyboard shortcuts | facade |
| Picture in Picture, AirPlay, fullscreen | native files as now; on movi: Document PiP or canvas `captureStream()` into a `<video>` for PiP where it works, fullscreen of the container; AirPlay said unavailable for that file |
| Media Session (lock screen, keys) | set from the facade |
| Stalled, "nothing arriving", Reload, errors with Try again and Send logs | movi's `getFatalError` and stall events into the existing status card |
| Other tabs paused, one Telegram lock | unchanged |
| TV remote, focus, idle CPU | measured on the TV layout after phase 2 |

## 6. Phase 3: seek bar thumbnails

1. Telegram storyboard when the post has one (as now).
2. Otherwise movi's thumbnail pipeline (keyframe mode, not `precise`, on phones and the TV) for
   **every** file, native ones included, replacing `FrameGrabber`'s hidden second `<video>`, so
   remux, HEVC, MKV and AVI files get pictures too, and the iPhone gets them if memory allows.
3. Rules from `seekpreview.ts` kept: `frameSlot` (1/120 of the video, 1 to 10 s), one decode at a
   time with the latest asked for next, throttle, JPEG blob cache with a cap, skeleton then the
   picture, `GIVE_UP_MS` fallback to the time alone. Reads share `TgFile`'s cache and are lower
   priority than playback reads, and stop when the bar is let go.
4. Fix whatever makes today's card wrong (picture stale after a seek, wrong frame, missing on
   resume from the detail page) and check it in the phase 4 pass.

## 7. Phase 4: real account test pass

The user signs in on the local build (`pnpm dev`). Then:

1. Re-run `survey()` with the movi route: every file that said "needs the app" in session 15 (HEVC
   MKV with E-AC-3, AAC, AC-3; HEVC QuickTime with apac and PCM; HEVC MP4; AVI; the 4 H.264 MP4s).
2. Play a sample of each row for 2 minutes, seek to the middle and the end, switch sound and
   subtitles, scrub for thumbnails, resume. Fedora Chromium first (hardest), then WebKit, then the
   user's iPhone and Chrome on Android if they can.
3. Regression: 10 native MP4s and 10 former remux MKVs play no worse (first frame, seek time).
4. Big files: a 4 GB+ MKV, a 20,000 video chat, memory in DevTools after 30 minutes and after 20
   seeks (bounded caches, no leaked decoders).

Results go into a table here, failing files with the reason.

## 8. Phase 5: clean up and ship

- Remove `libav.ts`, `libav/` and the remux path only if phase 4 shows movi covers them on every
  browser that has WebCodecs; keep remux for those that do not.
- Strings into `en.json` (sorted), notices, `WEBAPP-PLAN.md` section 3 updated, e2e tests
  (`pnpm e2e`) and `player.test.ts` extended, dash check, deploy to preview, then production
  (`gh workflow run webapp.yml -f deploy=production`, allowed without asking).

## 9. Edge cases

- **Browser without WebCodecs or WASM** (iOS 17 and before, old webOS, Firefox before 130): native or
  remux as now, else "needs the app". Never load movi's WASM there.
- **Too slow to decode** (4K HEVC in software on a phone or TV stick): `assessPlayback` before
  play, offer a lighter Telegram copy or the app; never play a slideshow silently.
- **Memory on iPhone**: decoded frames and the WASM heap capped; thumbnails off if Safari kills the
  tab (measure first).
- **Seek while reading, many seeks in a row, seek past what is downloaded**: one read in flight per
  position, older ones cancelled, as `FeedEngine` does now.
- **File gone, FLOOD_WAIT, network lost mid play, tab in the background**: errors come back through
  the SourceAdapter, the status card shows Try again; background tabs pause decoding.
- **Same video opened twice, another tab playing**: unchanged lock rules.
- **Switching sound or subtitle track on movi**: position kept, no restart from zero.
- **Next episode**: the old engine fully destroyed (WASM worker, decoders) before the next starts.
- **HDR**: tone mapped by movi on SDR screens; check colours are not washed out.

## 10. Open questions for the user (answer before phase 1, defaults in brackets)

1. movi only for files the browser cannot play, native kept for plain MP4 [yes, recommended], or
   movi for every file?
2. Use movi's own controls restyled, or TMPlayer's controls driving movi [TMPlayer's controls].

## 11. Smaller alternative weighed (2026-10-27)

The "needs the app: HEVC" page is not a detection bug: `probe` already sends HEVC through the remux
path when `MediaSource.isTypeSupported('video/mp4; codecs="hvc1..."')` says yes (Chrome on Windows,
macOS and Android, Safari, Edge). It shows only where the browser has no HEVC decoder at all (Linux
Chromium and Firefox, Chrome on Linux without VAAPI, older Android). Only a software decoder fixes
that.

- **@hevcjs/core** (MIT, 236 KB WASM, v1.6.0, active): decodes HEVC in a worker and re-encodes to
  H.264 with WebCodecs, fMP4 in and fMP4 out (`SegmentTranscoder`). It slots into the existing
  remux path between mediabunny's output and MediaSource, so native, PiP, AirPlay, the controls and
  everything else stay. 1080p transcode about 2.5x real time single threaded on Apple Silicon, so
  roughly real time on a mid laptop, not enough for 4K. **Unknown: Main 10 (10 bit) support**, and
  most HEVC uploads are 10 bit; phase 0 must test it first.
- **movi-player**: FFmpeg covers every codec, bit depth and container, but about 2.4 MB and a
  canvas player beside our `<video>`.

**Decided (user, 2026-10-27): movi-player.** The user saw another Telegram web client built on movi
play these HEVC videos in Zen on this computer, where hevc.js is unproven (10 bit, Firefox encoder).
hevc.js is dropped. The earlier order was: try hevc.js first (half a day spike on a 10 bit and an 8 bit HEVC MKV in
Fedora Chromium, and in Zen, the Firefox-based browser where the user saw the error, which needs Firefox's
WebCodecs H.264 encoder); if 10 bit fails or it cannot keep up, use movi for HEVC only.

## Progress / handoff

- 2026-10-27, built (uncommitted until the test pass ends):
  - **Order**: native, then the repack; HEVC the browser cannot decode goes through **hevc.js in
    workers** (`player/hevc.ts`, `player/hevc-worker.ts`): the repack's fMP4 is cut into whole boxes,
    each fragment (it starts at a key frame) converted to H.264 with the sound as AAC (mediabunny's
    wasm AAC encoder where the browser has none), two workers side by side on 8+ core desktops.
    hevc.js numbers fragments by count, not by their tfdt, so `placeTimes` rewrites the output tfdts
    from the input's. Then **movi** (`player/movi.ts`) for what neither plays (other containers,
    a failed repack, no H.264 encoder), drawing on a canvas over the `<video>`, whose properties and
    events it takes over so Chrome.tsx, resume, the stall watch and keys work unchanged; subtitles
    from a file drawn by movi.ts in the same look; picture in picture left out. libav.js still last for AVI.
  - movi seeks land on mediabunny's key frame when movi decodes in software (seeks 15 s to 2 s).
  - A notice (`web.slow_decode`) once per video when the picture is made more slowly than it plays.
  - Reader waits out FLOOD_WAIT_n (up to 60 s, 5 times); the repack only falls to movi on media errors.
  - Measured, Fedora Chromium (no HEVC), i7-1265U: Aasha 10 bit 1080p HEVC: movi 3.3 fps (useless),
    hevc.js one worker 0.88x, two workers 1.5x real time, page smooth (no long tasks). 8 bit HEVC MKV
    about 3x. 4K or 60 fps HEVC is too heavy here (3.5 to 7 fps): the notice says so.
  - Zen (Flatpak) lacked `org.freedesktop.Platform.ffmpeg-full`, so it had no H.264 or HEVC at all;
    Fedora Firefox 157 plays HEVC natively. Told the user the install command.
  - Testing gotcha: Playwright's connectOverCDP leaves new documents and workers paused when a script
    times out; use the raw CDP scripts (scratchpad cdp/raw.mjs) and one app tab only (Web Lock).
- 2026-10-27, committed 32fe21e and pushed; production deploy run 40555915332. Also on main:
  2949c85 (cache step 1) and 92461bd (desktop History selection bar above the pill).
  Production build tested signed in (raw CDP, headless Chromium with the user's profile in the
  scratchpad): Aasha 25 fps real time, seeks 7 to 14 s; 8 bit HEVC MKV 24 fps; H.264 MKVs seek in
  about 1 s; movi forced (?engine=movi) plays with picture and file subtitles. e2e: every Chromium
  project green except layout.spec "?tv=1 and ?tv=0" (from 92ae26a, not this work). WebKit cannot
  launch on Fedora (Playwright's build wants Ubuntu libjpeg.so.8, libjxl.so.0.8, libbacktrace):
  run it in mcr.microsoft.com/playwright:v1.64.1-noble with podman.
- Later: faster first frame after a seek on converted HEVC (stream the first fragment), memory
  (two decoders about 1.4 GB), iPhone check of movi (Safari 18 WebCodecs).
