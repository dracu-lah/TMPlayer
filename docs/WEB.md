# TMPlayer in the browser (CP92, web.tmplayer.org)

Frozen since 2026-10-22: no work or deploys; shared code is only kept compiling. When it stopped,
a signed in session ran against the real Telegram with the app's own api_id: QR sign in, the chat
list, a chat's video grid, and playback of real MP4 and MKV files from a channel, seeking to 90 %
of a 2 hour MKV with the page's JavaScript heap at 78 MB. "Where it stopped" lists what was done
and what was not.

Goal: as much of the app as a browser allows, for the videos already in the viewer's own Telegram
chats, at web.tmplayer.org, a static site with no server of ours in the path.

## Its replacement: the web player in `webapp/`

Decided 2026-10-24: a light SolidJS player in `webapp/` (branch `webapp-solid`, plan in
`private/WEBAPP-PLAN.md`) takes over web.tmplayer.org. Everything below this section is about the
frozen wasm build it replaces.

- **Build**: `cd webapp && pnpm install && pnpm build` (the app's rules from `:core` first, then tsc
  and Vite). A release build refuses to run without `TG_API_ID` and `TG_API_HASH` (environment or
  `local.properties`). `pnpm e2e` runs the end to end tests over a fake Telegram.
- **Licence notices**: the build writes `dist/licenses.txt` (`webapp/notices.config.ts`): every npm
  package in a built file, page and workers alike, with its licence text, then FFmpeg (LGPL 2.1 or
  later, inside Mediabunny's AC-3, DTS and AAC coders) and the Kotlin standard library, with the
  LGPL and Apache texts from `webapp/licenses/`. Settings links to it and the service worker keeps
  it for reading offline.
- **libav.js (AVI)**: what Mediabunny cannot open (AVI) is read by TMPlayer's own libav.js build of
  FFmpeg in a worker (`src/player/libav.ts`): an H.264 picture is copied (its showing order read
  from the stream, `src/player/h264.ts`), MPEG-4 Part 2, DivX 3 and H.263 are decoded and encoded
  again by the browser to H.264, the sound decoded and encoded as for MKV. The three built files are
  committed in `webapp/public/libav/`; rebuild them with `webapp/libav/build.sh` (podman, about 3
  minutes; `libav/fragments.json` lists what is in it). CI puts the exact sources in
  `dist/libav/sources/` (`webapp/libav/sources.sh`), so every deploy serves them for the LGPL.
- **CI**: `.github/workflows/webapp.yml` on every change under `webapp/` or `core/`: the rules, the
  type check, the build (not for pull requests, which get no secrets), and the end to end tests in
  Chromium and WebKit inside Playwright's image.
- **Deploy**: never by itself. Actions, Web player, Run workflow, with `deploy` set to `preview`
  (branch `solid-preview`, its own pages.dev address) or `production` (branch `main`, which is
  web.tmplayer.org), both to the Cloudflare Pages project `tmplayer-webapp` after the tests pass.
  GitHub runs a manual workflow only once the file is on `main`; before that, from this machine:
  `cd webapp && pnpm build && npx --yes wrangler@4.149.0 pages deploy dist
  --project-name=tmplayer-webapp --branch=solid-preview` (`--branch=main` is the live site).
  Headers are in `webapp/public/_headers` (`sw.js` never cached, `/assets/*` immutable); any
  other address gets `index.html`, since the build has no `404.html`.
- **Rollback**: Cloudflare keeps every deployment. The last wasm build is deployment
  `caec1989-b775-4378-9d0d-8ce2bc489cd1` (commit 1ea84e5, 2026-10-23,
  https://caec1989.tmplayer-webapp.pages.dev): Workers and Pages, tmplayer-webapp, Deployments, its
  menu, Rollback to this deployment. Visitors who opened the new player are signed out of the old
  one (below).
- **What the old app left**: its service worker answered `/stream/` in its own way, so the new
  worker takes over at once when the old caches are there (`webapp/public/sw.js`), then deletes
  the old caches, TDLib's database (`/tmplayer/dbfs`), `wasm-cache`, the OPFS `tdweb-files` and
  the files in tdweb's store inside the `tmplayer` database, which mtcute now uses. The old sign in
  stays listed in Telegram's active sessions until it expires or the viewer ends it there. Its
  `tm.*` settings in localStorage, a few KB, stay.

## What is built

| Piece | Where | State |
| --- | --- | --- |
| Gradle module, Kotlin/Wasm with Compose for Web | `web/build.gradle.kts` | dev and production tasks; Node from the machine, Binaryen from the settings repository |
| tdweb 1.8.64, pinned by SHA-256 | `fetchTdweb` | fetched from DIBgram/tdweb at commit `af27a3b`, checked file by file |
| Sparse storage for TDLib's files | `web/tdweb/sparse-fs.js`, `patchTdweb` | OPFS through FileSystemSyncAccessHandle in tdweb's worker, memory chunks with a ceiling where OPFS is missing; three exact text edits over the verified worker |
| The JSON bridge | `web/src/wasmJsMain/resources/td-bridge.js`, `web/.../td/WebTd.kt` | TDLib JSON in and out, windowed streaming reads, warm up, stop |
| `:core` pure source set with a wasmJs target | `core/src/pureMain/kotlin` | the catalogs, plural rules, number and date formatting (`PlatformText`: the JDK on the JVM, `Intl` in the browser), `Decimal`, the sign in flow (`AuthFlow`), a QR encoder checked against ZXing; the rest of `:core` moves in phase 2 |
| Sign in | `web/.../td/WebAuth.kt`, `ui/SignIn.kt` | QR (default), phone, code, email, email code, password, start over, another tab holding TDLib |
| Chat list, chat grid | `web/.../data/WebChats.kt`, `WebVideos.kt`, `ui/Shell.kt`, `ui/ChatGrid.kt` | from TDLib's updates; Telegram's video and document filters merged; restricted and self destructing messages left out |
| Player | `web/player/` | two engines (below), TMPlayer's controls, the desktop's keys, the phone's gestures, Media Session, wake lock |
| Service Worker | `web/src/wasmJsMain/resources/sw.js` | byte range streaming for the browser's own video, the offline shell |
| PWA | `manifest.webmanifest`, `index.html` | installable; iOS web app meta and safe areas; an install offer in the app |
| Local tests | `web/player/test/` | the player on generated files in headless Chromium, both engines, start and seek |

## iPhone first

The user's main reason for the web app is iPhone users (2026-10-21), so iOS Safari, installed to
the Home Screen, is the first target, ahead of desktop Chromium. Each decision below was checked
against WebKit's own sources on 2026-10-21.

| Decision | Why, with the source |
| --- | --- |
| **iOS 18.2 is the floor.** Kotlin/Wasm needs WebAssembly GC, which Safari has from 18.2 (exception handling from 15.2). | [WebKit features in Safari 18.2](https://webkit.org/blog/16301/webkit-features-in-safari-18-2/), [Kotlin/Wasm browser support](https://kotlinlang.org/docs/wasm-configuration.html). An older iPhone is told to update or use the app. |
| **Native `<video>` first on iOS.** MP4 and MOV with H.264 or HEVC, and AC-3 / E-AC-3, FLAC and ALAC sound, play in Safari's own player, which is the only way to full screen on an iPhone, AirPlay, the lock screen and the hardware decoders. | [Apple's HLS authoring spec](https://developer.apple.com/documentation/http-live-streaming/hls-authoring-specification-for-apple-devices) for the codecs; element full screen is iPad only, an iPhone has only `webkitEnterFullscreen` on a video ([New video policies for iOS](https://webkit.org/blog/6784/new-video-policies-for-ios/)). WebM with VP8/VP9 plays from iOS 17.4 (Safari 17.4 release notes). |
| **The Service Worker answers byte ranges exactly as WebKit asks.** WebKit opens with `Range: bytes=0-1` and needs a real 206 with `Content-Range`; a 200 fails. | [WebKit bug 229413](https://bugs.webkit.org/show_bug.cgi?id=229413), [bug 284443](https://bugs.webkit.org/show_bug.cgi?id=284443); the old Service Worker media bug is fixed ([184447](https://bugs.webkit.org/show_bug.cgi?id=184447)). Reliability inside an installed web app is not documented: it is on the device test plan, and ManagedMediaSource is the fallback if it fails there. |
| **ManagedMediaSource for what Safari cannot open (MKV and TS with H.264 or HEVC)**, repacked to fragmented MP4 in the page, so the picture stays in the native player. An iPhone has no classic MediaSource. MMS needs `disableRemotePlayback` or an AirPlay alternative. | [Safari 17.1](https://webkit.org/blog/14735/webkit-features-in-safari-17-1/) (MMS on iPhone), MDN compatibility data (MediaSource iPad only), Safari 17 release notes (the AirPlay condition). Stall on a `timestampOffset` change: [bug 262886](https://bugs.webkit.org/show_bug.cgi?id=262886). Not built yet (Open work); until then such files play in movi-player on the canvas, which works on iOS 18.2 and later. |
| **movi-player (FFmpeg in wasm) only as the last resort on iOS**: DTS, TrueHD and pictures with no native decoder. Its audio goes through FFmpeg and Web Audio; WebCodecs `AudioDecoder` is only in Safari 26. | Safari 26 release notes (AudioDecoder), Safari 16.4 (VideoDecoder), 17.4 (HEVC in WebCodecs). |
| **Small, strict memory ceilings on iOS.** There is no published per tab limit; WebKit kills a web content process past a soft limit under pressure, and wasm memory above a few hundred MB is unreliable on iOS. TDLib's downloads go to OPFS, never to memory; the memory fallback stops at 48 MiB on phones; the player's read cache is 12 MiB on phones; the download window ahead of the playhead is 24 MiB. | [bug 279637](https://bugs.webkit.org/show_bug.cgi?id=279637) (jetsam), [bug 269777](https://bugs.webkit.org/show_bug.cgi?id=269777) and [269937](https://bugs.webkit.org/show_bug.cgi?id=269937) (wasm memory). |
| **OPFS with synchronous handles in tdweb's worker.** In Safari since 15.2. The quota (Safari 17 and on) is up to 60 % of the disk for the browser's origin, the same for a Home Screen web app, which is also kept out of the 7 day deletion of script written data. `navigator.storage.persist()` is granted by heuristics that favour Home Screen apps; the app asks once installed. | Safari 15.2 release notes, [Updates to storage policy](https://webkit.org/blog/14403/updates-to-storage-policy/), [Full third party cookie blocking and more](https://webkit.org/blog/10218/full-third-party-cookie-blocking-and-more/). |
| **No SharedArrayBuffer path.** Threaded FFmpeg would need COOP and COEP; nothing in the app depends on them, and TDLib already runs in its own worker (unlike reelbox's mtcute, which shares the main thread with the decoder). | Safari 15.2 (SharedArrayBuffer with isolation). |
| **Picture in picture is hidden in an installed web app on iOS**, where `requestPictureInPicture()` fails with NotSupportedError; in Safari's tab it is offered. | [WebKit bug 303885](https://bugs.webkit.org/show_bug.cgi?id=303885), open on iOS 26.6. The button shows only where `webkitSupportsPresentationMode` says yes. |
| **Notifications through Web Push only once installed** (iOS 16.4 and on, asked for after a tap); Declarative Web Push from 18.4. | [Web Push for web apps on iOS](https://webkit.org/blog/13878/web-push-for-web-apps-on-ios-and-ipados/), [Meet Declarative Web Push](https://webkit.org/blog/16535/meet-declarative-web-push/). Not built yet: the app's notices for a new episode need a push service, which a static site has not got; the closest is a notice while a tab is open. |
| **The install path.** iOS has no install prompt: the app shows "Share, then Add to Home Screen". From iOS 26 every site added to the Home Screen opens as a web app. The manifest's `display: standalone`, the `apple-mobile-web-app-*` meta tags, an Apple touch icon and `viewport-fit=cover` with the canvas kept inside `env(safe-area-inset-*)`; `100dvh` (Safari 15.4). Orientation cannot be locked on iOS. | [Safari 26](https://webkit.org/blog/17333/webkit-features-in-safari-26-0/), Apple's archived web app guide for the meta tags, MDN for the rest. |
| **Lock screen and background.** Media Session for the title, artwork and buttons (Safari 15); audio keeps playing with the screen locked in a Home Screen app ([bug 232909](https://bugs.webkit.org/show_bug.cgi?id=232909), fixed); lock screen buttons have stopped answering in standalone mode before ([bug 261858](https://bugs.webkit.org/show_bug.cgi?id=261858)), so it is on the device test plan. Screen Wake Lock works in a Home Screen app from 18.4. `navigator.vibrate` does not exist on iOS: haptics are skipped quietly. | Safari release notes as linked. |

The device pass is `web/IOS-TEST-PLAN.md`.

## Two shells, one app

Phone users get the phone UI and desktop users the desktop UI (user, 2026-10-21), and tablets
"whatever is recommended". The web app therefore decides layout and input separately, at runtime
and again on every resize or rotation, without losing its place:

- **Layout, by Material's window size class on the window's width**: compact (under 600 dp) is
  the phone layout with the floating bottom bar; medium (600 to 840 dp) is the phone layout with
  the navigation rail and wider grids; expanded (840 dp and over) is the wide, desktop style
  layout. This is the Android app's own rule: its touch shell turns the bottom bar into a rail
  from 600 dp (`app/.../browse/TouchNav.kt`, `RAIL_FROM`), and it has no separate tablet layout
  beyond that, so the web app matches it and adds the wide layout at 840 dp.
- **Input, by the pointer, independent of layout**: on a coarse pointer (phones, iPad, Android
  tablets) every layout keeps touch behaviour: 44 pt targets, nothing reachable by hover alone,
  the phone's player gestures, bottom sheets where a finger needs room. A fine pointer gets hover
  states, popovers and the keyboard shortcuts; an iPad with a trackpad or keyboard gets both.
- Split View and Slide Over on an iPad change the window size, which is re-read like a rotation.

Built so far: the bottom bar and the rail by width, the player's touch and mouse behaviour by
pointer. The phone screens themselves (Home, chat pages, pickers and sheets) live in `app/` today;
the plan is to move them into `:ui` common code so Android and the web share them, which waits
for the coordinator's go ahead, as the desktop screens' move does.

## Playback as built

Two engines under one set of controls (`web/player/player.js`):

1. **The browser's own `<video>`** when `canPlayType` says yes to the container and the name's
   codec markers (HEVC, AV1, AC-3 / E-AC-3, DTS, TrueHD): MP4 and WebM nearly everywhere, and on
   Apple devices HEVC and AC-3 in MP4. Fed by the Service Worker's byte ranges (`stream/<fileId>`,
   2 MiB answers at most). A video that errors, or an MP4 whose sound Chromium cannot decode
   (no audio decoded after 4 s of a video over a minute long), moves to the second engine at the
   same moment.
2. **movi-player 0.4.1** (FFmpeg in wasm with WebCodecs, drawing on a canvas, sound through Web
   Audio) for everything else: MKV, AVI, TS, HEVC without a hardware decoder, E-AC-3, DTS and
   TrueHD. Fed straight from TDLib by `TgSource` (its SourceAdapter contract). When WebCodecs
   cannot decode the picture it reports `canTrySoftware`, and the player switches to FFmpeg's
   software decoders and carries on from the same moment. A picture too heavy to decode in time
   (software HEVC 10 bit at 1080p and up) gets an honest "This video needs the TMPlayer app" with
   "Play anyway", never a silent spinner.

The streaming read path (`td-bridge.js`) follows the apps' TdByteWindow: TDLib is pointed at the
byte the player wants with a window ahead of it (24 MiB on phones, 64 MiB elsewhere), reads wait
for TDLib's file updates, and the window moves as the reads do. A card held under the pointer or
the focus for 250 ms, and the next episode 30 s before the end, get their head (2 MiB) and their
last megabyte fetched ahead, which reelbox measured as the largest single win (a 2.6 GB MKV's
first frame 4.2 s after the click). Read-ahead stops when the video closes.

TDLib's files sit on OPFS in tdweb's worker (`sparse-fs.js`): each part is written at its offset,
nothing is held in memory, and what was downloaded survives a reload, so a video played before
opens from the disk. Tested on the real account: a 1 GB MKV opened from the head and the tail
read at 1032 MB, and a 2 hour MKV sought to 90 % with the JavaScript heap at 78 MB.

## Libraries and sizes

| Library | Licence | Loaded | Size on the wire (Brotli) |
| --- | --- | --- | --- |
| Compose Multiplatform 1.12 for Web, Skiko | Apache-2.0 | always | about 2.6 MB (prototype measure) |
| The app's own wasm | | always | about 0.9 MB (prototype measure; larger as screens arrive) |
| tdweb 1.8.64 (TDLib, OpenSSL 1.1.0l, localforage, zlib) | BSL-1.0, OpenSSL / SSLeay, Apache-2.0, zlib | always | about 3.3 MB |
| Noto Sans (Latin, Cyrillic, Greek) | OFL-1.1 | always | 0.7 MB uncompressed TTF |
| Noto CJK, Indic and Arabic subsets, Noto Color Emoji | OFL-1.1 | after the first paint | up to 14 MB, the UI language's first |
| movi-player 0.4.1 slim (`element.slim.js`) | Apache-2.0 | only when a video needs it | 0.91 MB |
| its `movi.wasm` (FFmpeg libavformat/libavcodec, dav1d) | LGPL-2.1+, BSD-2-Clause | with it | 1.71 MB |

movi-player's package says only Apache-2.0 and strips FFmpeg's licence line, so the LGPL notice and
the FFmpeg source offer are ours to ship: THIRD_PARTY_NOTICES "Web" section, and a CI build of
`movi.wasm` from its build script (Open work) so the exact source can be pointed to.

## Feature parity

Every feature reaches every platform (user, 2026-10-21): what the apps have the web app gets, and
what the web app gains goes to the apps. "Built" was done when the work was frozen; "Planned" is designed
and waiting (mostly for the `:core` and `:ui` moves); where a browser cannot, the closest equivalent.

| Feature | Android phone | TV | Desktop | Web, phone | Web, desktop |
| --- | --- | --- | --- | --- | --- |
| QR sign in | yes | yes | yes | Built | Built |
| Phone, code, email code, password | yes | yes | yes | Built | Built |
| Country picker, paste, resend timer, recovery, account reset | yes (sign in branch) | yes | yes | Planned (shared core) | Planned |
| Proxies | yes | yes | yes | MTProto proxy only if tdweb's WebSocket transport allows; SOCKS5/HTTP impossible from a page | same |
| Chat list | yes | yes | yes | Built | Built |
| Folders, archive, favourites, pinned | yes | yes | yes | Planned (data built) | Planned |
| Chat grid | yes | yes | yes | Built | Built |
| Sort, size filter, search in a chat | yes | yes | yes | Planned | Planned |
| Shows: seasons, episodes, copies, pieces | yes | yes | yes | Planned (with MediaItem in pure core) | Planned |
| Home: carousel, Continue watching, rows | yes | yes | yes | Planned | Planned |
| Search with @username and filters | yes | yes | yes | Planned | Planned |
| Online metadata (TMDB, TVmaze) | yes | yes | yes | Planned (fetch; both send CORS headers) | Planned |
| Watched, Watch later, favourites, history | yes | yes | yes | Watched and resume built locally; the rest Planned | same |
| Resume across devices (Saved Messages) | yes | yes | yes | Planned | Planned |
| Kids lock and PIN | yes | yes | yes | Planned | Planned |
| Themes, Black | yes | yes | yes | Built (system, dark, light, black) | Built |
| 21 languages | yes | yes | yes | Built (catalogs fetched, RTL) | Built |
| Player: speed, audio and subtitle tracks, subtitle delay | yes | yes | yes | Built | Built |
| Player: chapters, frame step, picture shape | yes | yes | yes | Built | Built |
| Player: keyboard shortcuts as the desktop | | | yes | | Built |
| Player: double tap seek, hold for 2x, swipe volume and brightness | yes | | | Built (brightness as a dimming layer; volume swipe not on iOS, which keeps volume to its buttons) | |
| Volume boost, night mode | yes | yes | yes | Built (compressor and gain; movi's levelling) | Built |
| Bookmarks, A-B repeat, sleep timer, skip intro | yes | yes | yes | Planned | Planned |
| Online subtitles | yes | yes | yes | Planned | Planned |
| Picture subtitles (PGS, VobSub), styled ASS | yes | yes | yes | Planned (JASSUB for ASS; PGS listed, labelled, never "no subtitles") | Planned |
| Picture in picture | yes | | yes (mini player) | Built where the browser allows; hidden in an installed iOS app (bug 303885) | Built |
| Casting | yes | | yes | AirPlay from Safari's own player; Remote Playback where offered | Remote Playback (Chrome) |
| Lock screen controls | yes | | MPRIS, SMTC | Built (Media Session) | Built |
| Notifications | yes | yes | yes | Built while a tab is open: new videos, new episodes, missing parts and the support ask, through the Notification API (from the Service Worker where the browser needs it); with the tab closed Web Push would need a push server | same |
| What's new, the language card, the tour offer, Send logs | yes | yes | yes | Built (the desktop's; logs to the browser's downloads) | same |
| Downloads for offline | yes | yes | yes | What was played stays on OPFS and plays again offline; no Downloads folder | same |
| Warm a video on hover or focus, and the next episode | partly | partly | partly | Built | Built |
| Installable | Play, APK | stores | packages | Built (Add to Home Screen) | Built (Chromium install) |

Built for the web that the apps lack, to take back to them: head and tail warm up on hover or
focus and for the next episode 30 s ahead (reelbox's largest measured win); stopping read-ahead
the moment a video closes; the honest "too heavy to decode here" notice with Play anyway.

Cannot work in a browser: offline downloads beyond the storage quota, the Downloads folder, a
storage location, TV and remote control layouts, DTS, TrueHD and most AC-3 sound, background
playback after the tab closes, MPRIS/SMTC (the Media Session API covers the basics), the system
tray, self update (the site is the update), the LAN cast server.

## Findings from the prototype (2026-10-19)

What ran:

- **A Kotlin/Wasm module with Compose Multiplatform for Web built**, against the repository's own
  Kotlin 2.4.21 and Compose Multiplatform 1.12.2 (wasmJs artifacts exist for ui, foundation,
  runtime and material3 1.12.0-alpha03). `:web:wasmJsBrowserDevelopmentRun` served it on
  localhost:8080 and `:web:wasmJsBrowserDistribution` wrote a static site. Run the two in separate
  Gradle invocations: together, Kotlin's development and production syncs share a directory and
  Gradle stops on an implicit dependency between them.
- **`:core` compiled for wasmJs** with a pure source set (below), and its common tests passed on
  Node (8 tests) and on the JVM, with the existing MediaNameTest (46), SplitPiecesTest,
  EpisodeCopies, VideoCopies and AuthReducer tests unchanged and green. The JVM regexes in
  MediaName (lookbehind, `\p{L}`, `\p{M}`) behaved the same on Wasm.
- **The Service Worker range streaming worked in Chromium 155**: a 60 second, 30 MB H.264/AAC MP4
  and a 23 MB VP9/Opus WebM, each served only through `stream/<id>` with 1 MiB 206 answers, played
  and seeked to 75%; the seek was answered by reads at 20.5 MB and 16 MB, not from what had
  played, so the element really asked for new ranges. The range logic also passed 10 Node tests
  (suffix ranges, 416, short reads answered as shorter ranges, a whole file reassembled chunk by
  chunk, a 200 streamed when there is no Range header).
- **tdweb loaded** from pinned, hash-checked files, and the bridge compiled; the sign in screen with
  a QR code was not yet screenshotted when the work stopped, and no real sign in was tried (it
  needs the user's phone).

What did not work, or needs care:

- **Gradle's repository rule.** `settings.gradle.kts` sets `FAIL_ON_PROJECT_REPOS`, and Kotlin's
  wasm tooling adds project repositories for Node.js, Yarn and Binaryen. Fixed by: Node from the
  machine (`WasmNodeJsEnvSpec.download = false`), npm instead of Yarn (`kotlin.js.yarn=false`), and
  Binaryen through an `exclusiveContent` ivy repository in settings with
  `BinaryenEnvSpec.downloadBaseUrl = null`. Kotlin then writes `kotlin-js-store/wasm/package-lock.json`,
  which is committed (`kotlinWasmUpgradePackageLock` refreshes it).
- **`js()` interop needs `kotlin.js.ExperimentalWasmJsInterop`** opted in (compiler option) on 2.4.
- **A custom `dependsOn` breaks the default hierarchy template** (a warning, and a Gradle 9.6
  deprecation for `by creating`). Use `applyDefaultHierarchyTemplate { common { group("jvmShared")
  { withJvm(); withCompilations { it.target.platformType == androidJvm } } } }` (opt in to
  `ExperimentalKotlinGradlePluginApi`); `withAndroidTarget()` does not match AGP's KMP library target.
- **The Compose compiler on the wasm target** is not needed for pure code; limit it with
  `composeCompiler.targetKotlinPlatforms = setOf(jvm, androidJvm)` in `:core`.
- **First load is heavy**: Brotli sizes of the production build were 2.6 MB (Skiko) + 0.9 MB (the
  app) + 3.3 MB (TDLib) + 0.1 MB of scripts, about 7 MB, and the TDLib wasm is 13.8 MB unpacked.
- **Headless Chromium on Fedora** plays H.264, VP9 and AV1, and says "" to HEVC and AC-3 and
  "maybe" to `video/x-matroska`, which it does not really play (see the codec matrix).

The prototype's whole diff (about 840 KB, most of it the npm lock file) and the two stream
screenshots were kept outside the repository, in the session's scratchpad, for reference.

## Architecture

```
 Compose for Web (Kotlin/Wasm)            page                       Service Worker
 screens, view models, :core pure  --->  td-bridge.js  ---> tdweb     sw.js + stream-range.js
          |   JSON strings                (TdClient)        worker      |  stream/<fileId>?size&mime
          |                                   ^              + wasm     |  Range -> 206
          v                                   |  readRange              v
     <video src="stream/42?...">  ----------  stream-host.js  <---  postMessage(MessageChannel)
```

### Telegram: tdweb

TDLib's own browser build (`example/web` in tdlib/td: emscripten, OpenSSL, the `tdweb` JS wrapper
with a worker and IndexedDB storage). Candidates checked on 2026-10-19:

| Build | TDLib | Date | Notes |
| --- | --- | --- | --- |
| `tdweb` (official npm) | 1.8.0 | 2022 | abandoned |
| `@wostafa/tdweb` | 1.8.10 | 2022-12 | abandoned |
| `@dibgram/tdweb` npm | 1.8.40 | 2024-11 | last npm publish |
| **DIBgram/tdweb repo, commit `af27a3b79f6dc7435d5ceeb31a998d531d845762`** | **1.8.64** | **2026-05-08** | built by the repo's public Actions workflow from tdlib/td with emsdk 3.1.1; not published to npm |
| `@aefen/tdweb` | 1.8.49 | 2025-05 | one anonymous publish |
| build it ourselves from tdlib/td `example/web` | 1.8.67 | any | about 1.5 h in CI |

The app uses TDLib 1.8.67 (read from tdl-coroutines 15.0.0's native library). Phase 1 takes
DIBgram's 1.8.64 files from the commit above, each checked by SHA-256 in Gradle:

| File | SHA-256 |
| --- | --- |
| `tdweb.js` | `747c02108e49b924c23bb556b40d203f11261c9e0bd419ba479ccd56e143d709` |
| `8a2ea5aea50674d967ce.worker.js` | `cc0789d76ddb74f647f0a6cc4c70420846f7792a117b251fb902a521cb7cae5e` |
| `1.8a2ea5aea50674d967ce.worker.js` | `eda4196bdad3d40fbe47a5a9cc75974f7204c497634cbfd1aff971ca0858c56e` |
| `b351db12a104e44cf97b09a3385b7077.wasm` | `33b823d03c4b7c59f82ca7527232d874ea3b28d52bebf738827e78cdfd6c7a98` |

The worker and the wasm are found relative to the page (webpack `publicPath` is ""), so they are
served from the site root. `tdweb.js` exposes `window.tdweb.default` (the `TdClient` class).

Licences: TDLib and tdweb's source are BSL-1.0 (the npm `package.json` says MIT, the code is
BSL-1.0); the bundle carries localforage (Apache-2.0), broadcast-channel, uuid and @babel/runtime
(MIT), zlib (zlib licence) and **OpenSSL 1.1.0l** (OpenSSL and SSLeay licences), the version TDLib's
`build-openssl.sh` still pins. OpenSSL 1.1.0 is long out of support; TLS to Telegram is MTProto's
own encryption on top, but this is the main reason to **build tdweb ourselves in phase 2** with a
current OpenSSL 3.x (Apache-2.0) and TDLib 1.8.67, in a workflow of our own with the outputs
hashed, rather than trust a third party's binary with the viewer's session. THIRD_PARTY_NOTICES.md
gets a "Web" section listing these when the web app first ships.

tdweb facts that shape the design (read from its source):

- `send(query)` returns a Promise; errors reject with `{ '@type': 'error', code, message }`.
- `readFilePart { file_id, offset, count }` is handled in the page: it checks
  `getFileDownloadedPrefixSize`, reads TDLib's partial download from the worker's in-memory file
  system, or slices the finished file's Blob from IndexedDB, and answers `{ data: Blob }`.
- **Files are kept in MEMFS while they download** and flushed to IndexedDB when complete. MEMFS
  files are dense arrays: a partial download at offset 1.5 GB allocates 1.5 GB of memory. This is
  the hard limit on streaming big files with stock tdweb (see Storage).
- Only one TdClient per `instanceName`; a second tab closes the first unless it is a background one.

### The bridge

Everything crosses the Wasm boundary as TDLib JSON text: `TmTd.send(json): Promise<string>` and
`TmTd.start(onUpdate: (string) -> Unit)` in `td-bridge.js`, parsed in Kotlin with
kotlinx-serialization-json (`JsonObject`, no generated DTOs). Video bytes never enter Wasm: the
Service Worker's reads are answered in JavaScript (`TmTd.readRange`). Small files (thumbnails,
chat photos) come to Kotlin as base64 and are decoded with Skia (`Image.makeFromEncoded`).

Typed calls for phase 1 (fields from td_api.tl 1.8.64; extra fields are ignored and missing ones
default, so it tolerates an older or newer TDLib): `setTdlibParameters`,
`requestQrCodeAuthentication`, `setAuthenticationPhoneNumber`, `checkAuthenticationCode`,
`checkAuthenticationPassword`, `setAuthenticationEmailAddress`, `checkAuthenticationEmailCode`,
`logOut`, `loadChats`, `getChats`, `getChat`, `searchChatMessages` (filters
`searchMessagesFilterVideo` and `searchMessagesFilterDocument`, merged, documents kept when the
name or type says video), `getFile`, `downloadFile` (offset, limit, synchronous),
`getFileDownloadedPrefixSize`, `readFilePart`.

Reads of one file are serialized: TDLib ends a waiting synchronous `downloadFile` when another one
with different bounds arrives for the same file.

### The core's web target

Proved in the prototype, to redo in phase 2: `:core` gets a `wasmJs { nodejs() }` target. Its
`commonMain` source set reads a new `src/pureMain/kotlin`; everything else becomes the
`jvmSharedMain` group (android plus jvm) that still reads `src/commonMain/kotlin`, so no file has
to move at once. Moved to pure in the prototype, with these changes:

| File | Change |
| --- | --- |
| `MediaName.kt` | `"%02d".format` to `padStart`; `lowercase(Locale.ROOT)` to `lowercase()` (already locale invariant); `Character.getType(...) == OTHER_SYMBOL` and code points through `expect fun isOtherSymbol(codePoint)` (JVM: `Character`; Wasm: `/\p{So}/u`); `Calendar` year through `expect fun currentYear()` |
| `SplitPieces.kt` | name half split into `SplitPieceNames` (pieceOf, isArchiveVolume, baseOf, keyOf, `SplitPiece`); `SplitPieces` keeps joining, reports and wording and delegates |
| `EpisodeCopies.kt`, `VideoCopies.kt` | `markers`, `closest<T>`, `languagesIn` and their tables moved to a pure `CopyMarkers`; both delegate |
| `MediaMapper.kt` | `VIDEO_EXTENSIONS` moved to a pure `VideoExtensions.ALL` |
| `AuthReducer.kt` | `AuthState`, `SignInMethod`, `CodeDelivery` moved to a pure `AuthState.kt` (they never touched TDLib) |

What blocks the rest of `src/commonMain` (159 files on 2026-10-19), and the change each kind needs:

- **TDLib DTOs** (16 files: Td, ChatRepository, MediaMapper, AuthReducer, ChatAudio, NewVideoAlerts,
  Proxies, PieceAlerts, ConnectHelp, Archives, ResumeSync, EpisodeAlerts,
  SponsoredMessageRepository, TdByteWindow, TdlResultExt, OnlineSubtitles). tdl-coroutines has no
  wasm target. Phase 2 puts a `TdGateway` interface in pure code, speaking the app's own models
  (MediaItem, chats, files, auth states), with two implementations: tdl-coroutines on the JVM and
  JSON over tdweb in the browser. MediaMapper's message reading becomes a mapper from a small
  common message model; AuthReducer reduces a TDLib-neutral auth state.
- **org.json** (13 files: Backup, MetadataCache, ResumeSyncDoc, MetaExtras, OpenSubtitlesApi,
  MetaModels, SkipTimes, SubtitleCache, SubdlApi, UpdateFeed, Translator, MetaApis,
  SubtitleModels): to kotlinx-serialization-json (`@Serializable` models or `JsonObject` reads),
  which also removes the Android/JVM org.json split in `:core`'s build.
- **java.io and java.nio** (31 files, among them the stores, DiskInfo, DownloadFiles, Failures,
  WatchCacheRules, MetadataStore, SubtitleText, MovieHash, CacheDirRename, the cast and remux code):
  the stores go behind a key value and blob storage interface (DataStore on the JVM, IndexedDB or
  OPFS in the browser); disk and cache code stays JVM only.
- **java.net / HttpURLConnection** (11 files: Http, OnlineMetadata, MetaApis, OpenSubtitlesApi,
  SubdlApi, Updates, UpdateFeed, Proxies, Feedback, CastServer, LanAddress): one `expect` HTTP
  client (fetch on the web, the current code on the JVM), or Ktor client if it earns its size.
  CORS decides what the browser can call directly: TMDB, TVmaze and OpenSubtitles send CORS
  headers; anything that does not needs a proxy we do not want, so it is left out on the web.
- **DataStore** (5 files: SettingsStore, BookmarkStore, WatchLaterStore, WatchedStore, Backup):
  behind the storage interface above; values stay the same keys, so Backup and resume sync read
  the same documents.
- **Locale, String.format, synchronized, @Volatile, threads** (about 35 and 25 files): mechanical;
  `kotlinx.atomicfu` or plain single threaded code on the web, `padStart` and the existing
  LocaleFormatter for numbers.
- **i18n**: `Translator` loads the catalogs with org.json from JVM resources; the web build ships
  the same `i18n/*.json` and loads them with fetch. Generated `Strings` and `L` move to pure.

About 75 files have no JVM import at all (the player rules such as AbLoop, Chapters, PieceMap,
Skip, SpeedMemory; HomeRows, WatchNext, SizeFilter and more), but many of them take MediaItem,
which lives beside TDLib types, so they move together once MediaItem is pure.

### Screens: reuse the desktop

`:ui` compiles `src/shared/kotlin` against Compose Multiplatform on the desktop. Phase 2 adds a
`wasmJs` target to `:ui` the same way (the shared directory compiled for it, with web actuals for
the few platform pieces: back handling, notices, the QR code, which on the web is drawn from
qrcode-generator 1.4.4, MIT, or a Kotlin encoder). The desktop's screens in
`desktop/src/main/kotlin/com/tmplayer/desktop/ui` (sign in, home, chats, grids, show pages,
search, settings) move into `:ui` behind small interfaces for what is desktop only (window drag,
title bar, mpv, file pickers, tray, MPRIS and SMTC). Phase 1's own screens are throwaway.

### Playback: a Service Worker serving byte ranges

`sw.js` answers one URL, `stream/<fileId>?size=<bytes>&mime=<type>`, and lets everything else go
to the network. `stream-range.js` (shared by the worker and the Node tests) turns the Range header
into an answer: `bytes=a-` and `bytes=a-b` give a 206 of at most 1 MiB, `bytes=-n` the last n
bytes, no header a 200 streamed in chunks, anything unsatisfiable a 416 with `Content-Range:
bytes */size`, and a read that comes back short a correct shorter 206 (the element asks for the
rest). Answers are `Cache-Control: no-store`; TDLib's storage is the cache.

The worker has no TDLib (it lives in the page), so each read is a message to the page that made
the request (`event.clientId`, else any open TMPlayer window) over a MessageChannel; the page
answers from `TmTd.readRange`: `getFileDownloadedPrefixSize`, a synchronous `downloadFile` with
offset and limit when the bytes are not there, then `readFilePart`, with the ArrayBuffer
transferred, not copied. The video element is the browser's own, over the Compose canvas.

Split pieces (`Name.mkv.001` and on): the worker maps one stream over the pieces in order (the
offset arithmetic SplitPieces already does for the apps), reading each piece's file in turn.

### Storage

| What | Where | Limit |
| --- | --- | --- |
| TDLib database (chats, messages, session) | IndexedDB via tdweb's IDBFS, synced every 5 s | small |
| Finished downloads | IndexedDB blobs (tdweb) | the browser's quota: up to about 60% of free disk in Chromium, 10% of disk in Firefox, about 1 GB before asking on Safari, and evictable unless `navigator.storage.persist()` is granted |
| Partial downloads while streaming | MEMFS in tdweb's worker, dense | RAM: a seek far into a large file allocates up to that offset |
| Settings, Watched, Watch later, bookmarks | IndexedDB through the storage interface | small |

What the site keeps today, and which part holds the sign in, is listed at the top of
`web/src/wasmJsMain/resources/storage-keeper.js`. TDLib's database (IndexedDB `/tmplayer/dbfs`)
holds the sign in and is never deleted by the app; videos stay in memory; tdweb's store of
finished small files (IndexedDB `tmplayer`), the runtime Cache Storage and the metadata answers
in localStorage are caches. The storage keeper checks `navigator.storage.estimate()` at start and
every minute, and past 70 % of the quota, or on any quota error (a localStorage write, a Cache
Storage write in sw.js, a refused save of TDLib's database or file store in tdweb's worker), it
frees those caches in that order until the site is under 50 %, logging one `[tm-storage]` line
per pass. A refused database save empties the file store inside the worker at once and saves
again. `navigator.storage.persist()` is asked on every signed in start and on install, and the
answer logged, since a site that is not persistent can be cleared whole under disk pressure.

The MEMFS limit means stock tdweb streams videos up to a few hundred MB well and fails on the
2 to 4 GB files common in channels. Phase 3 fixes it in our own tdweb build: TDLib's files
directory on OPFS (emscripten's WasmFS OPFS backend, or a custom sparse file backend that writes
each downloaded part with `FileSystemSyncAccessHandle` at its offset), and the files directory
trimmed the way the apps' WatchCacheRules trim theirs. Until then the web app says plainly that a
file over a set size needs the app.

## Codec matrix and the "needs the app" handoff

Decided before anything downloads, from Telegram's MIME type, the file name and its markers
(`CopyMarkers.markers`: x265, HEVC, AV1), asked of `video.canPlayType` and
`MediaSource.isTypeSupported`; a wrong guess is still caught when the element reports an error.

| File | Chrome / Edge | Firefox | Safari | Web app does |
| --- | --- | --- | --- | --- |
| MP4, H.264 + AAC | yes | yes | yes | plays (phase 1) |
| MP4, H.264 + AC-3 / E-AC-3 | video only | video only | yes (macOS, iOS) | phase 3: audio converted with WebCodecs where decoding exists, else needs the app |
| MP4, HEVC | where the OS has a decoder (Windows with the extension, macOS) | no | yes | plays where `canPlayType` says so, else needs the app |
| MP4 or WebM, AV1 | yes | yes | M3 and newer | plays |
| WebM, VP9 + Opus | yes | yes | yes (recent) | plays |
| MKV, H.264 + AAC | no (says "maybe") | no | no | phase 3: repacked to fragmented MP4 in the page and fed through Media Source (the cast code's `Matroska`, `MkvRemuxer` and `Fmp4` in `:core` are the starting point) |
| MKV, HEVC or with DTS / TrueHD | no | no | no | needs the app |
| AVI, WMV, FLV, TS | no | no | no | needs the app |
| ASS subtitles with styling | no | no | no | text kept as WebVTT, styling needs the app |

"Needs the app": the player layer says "This video needs the TMPlayer app", why in one line, and
"Get the TMPlayer app" linking to tmplayer.org/download/, which picks the right build for the
device. The grid marks such tiles "Needs the app" before they are opened.

## Where it stopped

The sections below still name the original plan's phases: 1, the proof; 2, `:core` and the
screens in shared code; 3, the player and codecs; 4, shipping (PWA, pages, deploy).

- Done: the `web/` module, tdweb pinned by hash, the JSON bridge, sign in, the chat list and a
  chat's grid, the player with both engines, sparse OPFS storage, the Service Worker, the PWA and
  offline shell, the `:core` pure source set with a wasmJs target.
- Partly: `:core` moving into the pure set (Home, shows, Search and the rest of the screens wait
  on it); `:ui`'s wasmJs target.
- Not started: tdweb and `movi.wasm` built by our own workflow, the MKV repack into
  ManagedMediaSource on iOS, the web app's privacy and legal pages, automated deploys.

## Deployment

For the player that replaces it, see the `webapp/` section at the top. The wasm build was
deployed by hand to Cloudflare Pages with the wrangler CLI, from the production build's
output `web/build/dist/wasmJs/productionExecutable`, on the domain web.tmplayer.org. `web.yml`
builds and tests the app on every change under `web/`, `core/` and `ui/` and deploys nothing.
The Telegram API id and hash come from `local.properties` or the environment at build time; they
are visible in the page's script, exactly as they are in the APK. No COOP/COEP headers are needed
(tdweb's build has no threads).

## Risks

- **Supply chain:** the TDLib wasm holds the viewer's Telegram session. Phase 1 trusts DIBgram's
  CI built files at a pinned commit and hash; phase 2 must replace them with our own build.
- **OpenSSL 1.1.0l** in every available tdweb build (TDLib's script pins it).
- **Large files:** stock tdweb keeps partial downloads in memory; without phase 3's OPFS work the
  web app cannot stream the files channels actually post.
- **Telegram's view of web clients:** a new api_id used from browsers may draw more flood waits and
  session checks; using the app's own id ties the web app's behaviour to the apps' standing.
- **One tab at a time:** tdweb closes older tabs' clients; a second tab must say so.
- **Safari:** Service Worker streaming to `<video>` works, but storage eviction after 7 days of no
  use (ITP) means signing in again; WebCodecs audio is partial.
- **Size:** about 7 MB compressed on first load (Skiko alone is 2.6 MB); a slow phone waits.

## Open work

- `:core` into the pure set (MediaItem, the shelf rules, the stores behind a key value interface,
  HTTP behind an interface, `TdGateway`), then the web screens on it: Home, shows, Search, Watch
  later, favourites, the kids lock, online metadata. A fork is on it.
- The `:ui` move (desktop screens, then the phone screens) once the coordinator says go.
- The sign in branch's additions (country picker, paste, resend timer, recovery, reset, proxy) on
  the web through the shared core.
- MKV and TS repacked to fragmented MP4 into ManagedMediaSource on iOS, so the picture stays in
  Safari's player; DTS and TrueHD sound converted where native video is kept.
- movi-player's first positioning bisects about half of the MKVs before frame 0 (reelbox's
  measure): find the call, or take the repack path for MKV with H.264.
- Recreate movi's wasm module after a number of videos or past a heap limit (it only grows).
- Styled ASS through JASSUB (MIT); PGS drawn or at least labelled.
- tdweb built by our own workflow (TDLib 1.8.67, OpenSSL 3), `movi.wasm` from source in CI.
- The privacy and legal pages, and an automated deploy.

## Running it

```sh
./gradlew :web:wasmJsBrowserDevelopmentRun      # http://localhost:8080/
./gradlew :web:wasmJsBrowserDistribution        # web/build/dist/wasmJs/productionExecutable
node --test web/tools/storage-keeper.test.mjs    # the storage keeper's tests on Node
node web/tools/serve.mjs <dist folder> --port 8091 &
node web/player/test/browser-test.mjs http://127.0.0.1:8091/player/test/player-test.html <files>
```

The last two are the player's test in headless Chromium, as `web.yml` runs it: copy
`web/player/test` into the dist folder and generate the media files first (the workflow shows the
`ffmpeg` lines). Node.js comes from the machine; the Telegram id and hash from local.properties or
`TG_API_ID` and `TG_API_HASH` in the environment, never committed.
