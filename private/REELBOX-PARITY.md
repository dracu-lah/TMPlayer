# Reelbox parity (2026-10-21)

Reviewed logged out with headless Chromium (`--dump-dom`) and the shipped bundles
(`/assets/index-CseVAwD4.js`, `/sw.js`, `/appearance.js`). The settings page needs a sign in, so the
rows below come from its English catalog, its stores (`player-prefs-v1`, `subtitle-style-v1`,
`subtitle-delays-v1`, `hidden-chats-v1`, `favourite-chats-v1`, `posters-and-overviews-v1`,
`appearance-v1`) and their defaults in the bundle. A logged in pass can confirm the screens; nothing
below depends on it. Ideas only: none of their code, text or branding is to be copied.

Areas: **Home** (Home/carousel/TMDB images), **Settings** (Settings/profile navigation),
**Haptics**, **Sort** (sorting and filtering), **TV** (TV trailer/resume/QR/detail sheet),
**Playback**.

## 1. Why reelbox starts and seeks fast

Reelbox is a web app: GramJS talks to Telegram, a service worker answers `/stream/<key>` with byte
ranges, and a `<video>` (or its built-in WebAssembly decoder) reads that URL.

1. **1 MB chunks straight from `upload.getFile`** (`precise: false`), the size Telegram serves
   fastest, with up to **3 chunks in flight at once** per video and **6 chunks of read-ahead**
   past whatever the player last asked for. A response to the browser is capped at 2 MB, so a
   range request never waits for more than two chunks.
2. **An in-memory LRU of 48 chunks** (48 MB) per video, and the last **3 video sources kept**
   alive, so leaving and reopening a video, or a short seek back, costs nothing.
3. **Warm-up before the press.** A card's hover (mouse, after 250 ms) or focus (keyboard or TV,
   after 250 ms) fetches chunks 0 and 1 **and the last chunk**; a pointer down fetches at once.
   Opening the player then fetches chunks 0 to 3 plus the last (0 to 1 plus the last when
   resuming). The last chunk is where an MKV's Cues and a non-faststart MP4's moov live, so the
   container's index is in memory before the demuxer asks for it: no jump to the end of the file
   on the critical path.
4. **Seek drops stale read-ahead.** Each read replaces the read-ahead queue with chunks after the
   new position; chunks already in flight finish into the cache, nothing queued for the old place
   is started.
5. **Poster first.** The player shows the video's thumbnail and title at once, `preload=metadata`,
   applies the resume time on `loadedmetadata`, and starts playing as soon as the browser has
   `HAVE_CURRENT_DATA` (readyState 2), with an autoplay retry. Its loader says "Taking longer than
   usual" when it is slow.
6. **The decoder is warmed while idle**: the WebAssembly decoder loads 1.5 s after the app starts
   (`requestIdleCallback`), and again on a card's hover when the file will need it.
7. **Scrub previews come from a side reader**: a hidden muted `<video>` on its own 8 chunk cache,
   so preview seeks never evict the playing video's chunks.

## 2. TMPlayer's start path today, and what to take

Measured from the code (no device pass yet):

- `TdByteWindow.requestDownloadFrom` (core `player/TdByteWindow.kt`) asks TDLib
  `downloadFile(priority 32, offset, limit 0, synchronous false)`. TDLib keeps one range per
  file: a new offset replaces the old request, so a seek already re-aims the download and stale
  ranges are cancelled by TDLib itself. Parallel part requests are TDLib's own.
- The first byte is requested only when Media3's loader thread opens `TdDataSource`
  (app `player/TdDataSource.kt`), which is after `PlayerActivity.openItem` has done the session
  wait, pieces lookup, `fileIdFits`, room check, watch cache claim, Low storage decision and about
  fifteen sequential settings reads, and after `buildPlayer`. None of that overlaps the network.
- **MKV**: Media3's Matroska extractor reads the head, then at the first Cluster jumps to the Cues
  (usually near the end) and back. Each jump is a TDLib re-aim (cancel, new parts, one round trip
  or more). The nudge logic in `awaitBytesAt` exists because that jump was seen to stall.
  **MP4 with moov at the end**: the same jump, to a moov that can be several MB.
- **Resume**: `resumeMs` is read in a separate coroutine (`ResumeSync.pullFor` can take a couple
  of seconds); when it lands after `prepare()`, the head is fetched from 0, then the seek re-aims.
- `DefaultLoadControl`: min 15 s, max 50 s, **2.5 s to start**, 5 s after a rebuffer, 20 MB
  target, 10 s back buffer. The 1.5 s PCM buffer is the AudioTrack size and does not hold the
  start back. Seeks are EXACT (Media3 default).
- Desktop: mpv over `TdStreamBytes` with the same `TdByteWindow`, so the same jumps.

To adopt (Playback, all platforms unless said):

| Idea | Status | Why / how |
| --- | --- | --- |
| Warm the head and the index before the player exists | Gap, adopt | In `openItem`, once the connection, room and Low storage checks pass, launch a warm-up: TDLib `downloadFile` of the first 1 MB (synchronous), read the head; MKV: SeekHead gives the Cues position; MP4: top level boxes give the moov offset when mdat comes first (`cast/MediaProbe` already parses both); fetch that range (synchronous, a few MB cap); then the playback request from 0 (or leave it to the DataSource). Overlaps the settings reads and `buildPlayer`. Skip for downloads, joined pieces, Low storage and Save mobile data streams. A shared flag stops the warm-up once `TdDataSource` opens, so it never moves the player's own range. |
| Warm on card focus or press | Gap, adopt, owner **Home** and **TV** for the hook | Core API (`StreamWarmup.warm(fileId)`, head plus index only) called after 250 ms of focus on a TV card and on press down on a phone. Not on metered data with Save mobile data on. |
| Warm the next episode | Gap, adopt | When the Next episode card comes up (`NEXT_UP_LEAD_MS`), warm its head and index. |
| Start threshold 2.5 s to about 1 s | Gap, adopt with a device check | `BUFFER_PLAYBACK_MS` in `PlayerActivity`; keep the rebuffer figure. Also drives the loader's percentage (`StreamStats.progress`). |
| Resume before prepare | Gap, adopt | Have `startPlayback` wait (bounded, about 300 ms) for the resume coroutine, or start it before the settings reads, so the first request is not from 0 for a resumed video. |
| In-memory chunk cache, 3 parallel requests | Not needed | TDLib already caches parts on disk and runs parallel part downloads. |
| Several ranges of one file at once | Not possible | TDLib has one download per file; the warm-up gets the same effect by running before the player. |
| Scrub previews off a separate reader | Has it | `TrickplayFrames` and `StoryboardThumbnails` read only what is downloaded. |
| Poster first, "Taking longer than usual" | Has it | Artwork in the loading sheet; stall sheets. A "slow" line before the stall sheet is a small gap. |
| Timing logs | Gap, adopt | Press to first frame (an extra stamped where the intent is built, else `onCreate`) and seek to ready, logged under `TdDataSource`/`PlayerStart` so the device pass can compare. |

## 3. Every setting, section by section

### Account

| Reelbox | TMPlayer | Gap / adopt | Area |
| --- | --- | --- | --- |
| Shows the Telegram account and "this browser" session | Settings, Account; Connected devices | None | Settings |
| Sign out, with what it deletes spelled out, and a pointer to Telegram's Devices | Sign out of Telegram, with a downloads tick | None | Settings |
| Profile header: "Your viewing" with Watching, Watched, Starred counts | Not as one summary | Gap, adopt: a profile header with the three counts linking to each list (phone, TV, desktop) | Settings |

### Appearance and language

| Reelbox | TMPlayer | Gap / adopt | Area |
| --- | --- | --- | --- |
| Theme System, Light, Dark | `theme_choice` | None | Settings |
| Pure black | `pure_black` | None | Settings |
| Accent: 9 presets plus monochrome | `accent` (presets, wallpaper colours on Android) | Check that monochrome exists; adopt if not | Settings |
| Custom accent colour, made readable on light and dark | No | Gap, adopt: a colour picker that derives light and dark variants with a contrast check (all) | Settings |
| Interface size Smaller, Default, Larger, Largest | `ui_scale` | None (check four steps) | Settings |
| Typeface choice (7 faces incl. system, a dyslexia friendly one, a serif) | No | Gap, adopt on desktop and phone; TV keep one face; bundled fonts cost APK size, so offer System plus two | Settings |
| High contrast (secondary text and edges brought forward) | No | Gap, adopt (all) | Settings |
| Reduce motion | `reduce_motion` | None | Settings |
| Language, automatic follows the system | `language` | None | Settings |
| Live preview card of the choices | No | Gap, adopt: a small poster card preview on the Appearance page | Settings |
| "Back to the defaults" with undo | No | Gap, adopt | Settings |
| Theme applied before first paint | Desktop and Android already read before drawing | None | Settings |

### Playback

| Reelbox | TMPlayer | Gap / adopt | Area |
| --- | --- | --- | --- |
| Player: built-in (every format) or browser (lighter on battery, fewer tracks); per file fallback with "Use the built-in decoder" on error | Desktop: Force software decoding; Android: FFmpeg extension renderers ON with decoder fallback | Partly. Adopt on Android a "Decoder" row (Automatic, Force software video) for broken frames, and on a decode error a "Try the other decoder" button | Playback |
| Languages remembered from the player, shown as "Sound: x, Subtitles: y" with Forget | Preferred audio and subtitle language, per series memory (`audio_<series>`, `text_<series>`); no view of what is remembered, no Forget | Gap, adopt: show what was learnt and a Forget that clears the per series choices (all) | Playback |
| Skip intro on or off (TheIntroDB, AniSkip, chapters); S key | `metadata.skip_times` toggle; desktop key | None (check a key for it on desktop) | Playback |
| Play the next episode, with "still watching" after three in a row | `autoplay_next`, still watching prompt | None | Playback |
| Volume and mute kept between videos, start muted | Android uses the system volume; desktop keeps volume in `DesktopPrefs` | Check desktop keeps mute too; nothing on Android | Playback |
| Player settings menu: Speed, Decoder | Speed per video, series, chat (`SpeedMemory`) | None | Playback |
| Miniplayer inside the app (i), picture in picture (p) | Android PiP; desktop `MiniPlayerWindow` | None | Playback |
| Keyboard: k j l m c f i p S, + and - subtitle size, Z and X subtitle timing | Desktop `PlayerKeys`: Z/X timing, +/- speed | Small gap: a key for subtitle size on desktop (not + and -, they are speed) | Playback |
| Loader "Starting", "Resuming from", "Taking longer than usual" | Starting, Resuming from, stall sheets after a timeout | Small gap: a quiet "taking longer" line before the stall sheet | Playback |
| Error sheet with "Copy details" (a diagnostics blob) | Report a problem on GitHub from Settings | Gap, adopt: Copy details on the player's error sheet (phone, desktop; TV shows a QR) | Playback |
| Fast start (section 1) | See section 2 | Adopt | Playback |

### Library

| Reelbox | TMPlayer | Gap / adopt | Area |
| --- | --- | --- | --- |
| Show posters and overviews, on or off | `metadata.toggle` | None | Home |
| Sources listed per provider with on, off or "needs a key" | About group lists TMDB, TVmaze, AniList; own TMDB key | None | Home |
| Saved details: kept for weeks, Clear | Clear posters and details (six months cap) | None | Home |
| Starred chats get a row each on Home; count and clear | Favourites, Clear favourites; Home rows | Check each starred chat has its own Home row; adopt if not | Home |
| Hidden chats: hide from a chat's menu or right click, left out of Chats, Home, Movies, Series and search with their videos; list with Show, Show all, Undo toast | No (only hiding the default groups) | Gap, adopt (all) | Sort |
| A chat's menu: star, open in new tab, copy link, hide | Star, copy Telegram link | Hide is the gap (above) | Sort |
| Library tabs Home, Movies, Series, search | Home, chats, series pages | None | Home |

### Subtitles

| Reelbox | TMPlayer | Gap / adopt | Area |
| --- | --- | --- | --- |
| Size (4 steps) | `subtitle_size` | None | Playback |
| Colour: white, yellow, cyan, green, pink | No | Gap, adopt (all) | Playback |
| Background box with "how dark" (35, 60, 80, 100 %) | Box on or off | Gap, adopt the darkness steps | Playback |
| Edge: outline, shadow, none | Outline only | Gap, adopt | Playback |
| Position: bottom, raised, high | `subtitle_position` | None | Playback |
| Typeface: default, serif, typewriter; weight regular or bold | No | Gap, adopt (Media3 `CaptionStyleCompat` takes a typeface; mpv `sub-font`, `sub-bold`) | Playback |
| Live preview, Back to the default look | Preview line; no reset | Small gap: reset | Playback |
| Timing per video, Earlier and Later, a hint which way | `SyncDelays` per message | None | Playback |
| Online subtitles from OpenSubtitles, optional account, daily quota shown | Online subtitles store, account | None (check the quota line) | Playback |
| Include machine translations | `include_machine` | None | Playback |
| SubDL key as a fallback | SubDL key | None | Playback |
| Downloaded subtitles: size kept, six months, Clear | Clear online subtitles | None | Playback |

### Storage

| Reelbox | TMPlayer | Gap / adopt | Area |
| --- | --- | --- | --- |
| Space used of the site's quota | Storage bar with free space | None | Settings |
| Keep data on this device (persistent storage) | Not applicable: app storage is not evicted | Not applicable | none |
| Watch history count, Clear | Clear Continue watching | None | Settings |
| Saved pictures, Clear | Clear pictures and previews | None | Settings |

### Support

| Reelbox | TMPlayer | Gap / adopt | Area |
| --- | --- | --- | --- |
| Buy a coffee page and a floating nudge that hides for a while | Support page, sponsor reminders | None | Settings |

## 4. Not to adopt

- Anything that hides Telegram's sponsored messages or restrictions, or fakes read state: reelbox
  does none of it either.
- Reelbox's "hidden by this site" list (chats left out for every user by the operator): a server
  side block list has no place in a client without a server.
- "Keep data on this device": a browser storage quota, nothing to do with an app.

## 5. Gap summary by area

- **Playback**: fast start (warm head and index before the player, next episode warm-up, 1 s start
  threshold, resume before prepare, timing logs); subtitle colour, edge, typeface, weight, box
  darkness, reset; remembered languages with Forget; Android decoder row and "try the other
  decoder"; Copy details on errors; a "taking longer" line; a desktop key for subtitle size.
- **Home**: warm-up hook on card focus or press (core API from Playback); check one Home row per
  starred chat.
- **TV**: warm-up hook on TV card focus and on the detail sheet's Play focus.
- **Settings**: profile header with Watching, Watched, Starred counts; custom accent; typefaces;
  high contrast; appearance preview; back to defaults; check monochrome accent.
- **Sort**: hidden chats (hide from the chat menu, left out of every list and search, a list in
  Settings with Show, Show all and Undo).
- **Haptics**: nothing from reelbox (a web app has none).
