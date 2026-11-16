# EchoGram: every ask and bug against TMPlayer (2026-11-15)

Inputs: `ECHOGRAM-FINDINGS.md` (Announcements read in full; Bug Report from Nov 14 back to about Oct 3;
Feature Request from Nov 14 back to Sep 16 plus two Oct 2025 posts), `ECHOGRAM-COMPARE.md`,
`COMPETITORS.md`, `OTT-REVIEW.md`, `PLAN.md`, `docs/FEATURES.md`, `docs/agents/telegram-terms.md`, and
grep over `core`, `ui`, `app`, `desktop`, `webapp` and `en.json` at f850cc6. Read only: nothing in the
code was changed. Statuses come from the code, not from plan checkboxes (DLNA and Cast, for example,
are built although HX40 and CP73 are still unticked in PLAN.md).

Ids: EG-F1 to EG-F49 are the findings' F1 to F49; EG-F50 is the TV request seen in the Bug Report topic;
EG-F51 to EG-F64 are things EchoGram shipped (version timeline) that no read screen asked for, kept so
nothing they offer is missed. EG-B1 to EG-B21 are the findings' B1 to B21.

Status words: **has** (where), **partial** (what is missing), **missing**, **decline** (why), **n/a**
(nothing to do in an app: process, or a duplicate), **unverified** (the findings cut the text).
Value H/M/L, cost S (a day or less), M (a few days), L (a week or more). Platforms: P phone, T TV,
D desktop, W web.

## Summary

- 64 feature rows (49 asked, 1 TV ask from the bug topic, 14 shipped by EchoGram), 21 bug rows.
- Features: has 33, partial 16, missing 5, decline 9 (EG-F54 counted here: GIFs play, photos declined),
  unverified 1.
- Bugs: has 12, partial 3, missing 1, n/a 4 (process, duplicates, no live streams), unverified 1.
- TMPlayer already covers most of what EchoGram's users ask for, and for free: EchoGram put seeking,
  resume and auto play next behind Premium (developer, Reddit 2025-03-15) and dropped the lifetime plan.
- Real gaps (the backlog below): albums whose title or cover sits on a photo (B11, B16, the loudest
  complaint class), subtitles posted in the chat (F48), plain videos with no "next" and no repeat one
  (F30, F52), Home row order (F7, F23, F27), search scope (HX62, F1, F18, F49), the codec in a playback
  error (F37), DLNA discovery on old TVs (B9, B14), TV focus contrast (F22), named lists across chats
  (F55, HX43).

## 1. Every request and bug

### Feature requests

| Id | Ask (one line) | Askers | EchoGram's answer | TMPlayer status | Proposed action | Platforms | Value | Cost |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| EG-F1 | Collections search only the user's own selected channels | 3 | none seen | partial: Add chats picker (HX27) chooses chats, but `ChatRepository.searchAllChats` is one account-wide `searchMessages`; HX62 (open) restricts results to added chats | T5 (HX62) | P T D W | H | M |
| EG-F2 | Search can leave out archived chats | 1 | none | has: `search.filter_include_archived`, `GlobalSearch` `includeArchived` | none | P T D W | - | - |
| EG-F3 | Skip intro on TV (introdb, anime-skip) | 1 (+1 objection) | todo, low priority | has: CP47 TheIntroDB, IntroDB.app, AniSkip, learnt intro end per series (`player.skip_intro`, `Skip.kt`) | none | P T D | - | - |
| EG-F4 | Phone player like Netflix/YouTube: easy pause and subtitles; subtitles break on zoom | 1 | "hopefully soon" | has: CP37 phone row (subtitles, audio, speed, rotate), big centre play; `PlayerActivity.renderZoom` keeps subtitles out of the zoomed frame | none | P | - | - |
| EG-F5 | Films apart from series; search returns copies not in my language | 1 | none | partial: Home rows Movies and Shows (`home.row_movies`, `home.row_shows`), copies folded (CP46); the best copy ignores the viewer's language (HX38 open) | T5: rank copies by preferred audio language | P T D W | M | S |
| EG-F6 | Order of steps when adding to a collection (search, player, add) | 1 | none | decline: no collection add flow; HX41's search-first flow is the same idea | none | - | L | - |
| EG-F7 | A whole folder as a collection; custom Home order instead of A to Z | 1 | none (1.1.0 shipped carousel order) | partial: folders are filters (CP43); Home rows can only be switched on or off (`settings.home_rows`) | T4 | P T D W | M | M |
| EG-F8 | Search results as a grid with "279 of 309" counter | 1 | none | partial: TV search is a grid (`TvSearch.kt` LazyVerticalGrid), phone a list; no total shown | T5: a count line | P T D W | L | S |
| EG-F9 | Player UI like TiviMate/SmartTube; metadata is clutter vs wanted | 2 | none | has: Playback details behind I / More (`player.details_*`), CP37/CP38 declutter | none | P T D | - | - |
| EG-F10 | App lock | 1 | later (shipped 1.0.5.6) | has: CP57 PIN, `lock.on_open`, kids lock | none | P T D | - | - |
| EG-F11 | Trakt watch sync and scrobble | 1 | Trakt removed Jul 31 (keys pulled) | decline for now: user 2026-10-13 "no new outside services"; CP62 AniList and Simkl wait on keys (U30) | decision D4 | P T D | M | M |
| EG-F12 | Search by file name | 1 | "Telegram API cannot" | has: `GlobalSearch` underscore variants plus local `Fuzzy` over file names; `findEpisodes` relies on Telegram matching file names as words | check on a real chat that Telegram matches document file names (EchoGram's developer says it does not) | P T D W | - | - |
| EG-F13 | Card shape per channel (16:9 or 2:3) | 1 | none | decline: one layout per page by design (FEATURES.md "nothing to set"); 2:3 posters where a title matched, 16:9 thumbnails otherwise | decision D7 | - | L | S |
| EG-F14 | Saved Messages style grid, show as list | 1 | none | has: every chat is a grid, Saved Messages is a tab, sort and filter (CP29) | none | P T D W | - | - |
| EG-F15 | (Developer's statement: the app is agnostic to files) | - | - | has: FEATURES.md "hosts nothing, indexes nothing"; TMDB lookups by name only, on the privacy page | none | - | - | - |
| EG-F16 | Posters instead of black title boxes | 3 | "not possible", own plans first | has: CP32 TMDB/TVmaze/AniList, uploader cover (`MediaMapper.kt:62`), `poster.choose`, HX39 fixed the double title; album photo gap is EG-B16 | T1 | P T D W | - | - |
| EG-F17 | A survey to rank features | 1 | none | decline: GitHub Discussions and in-app feedback (`Feedback.kt`) already exist | none | - | L | - |
| EG-F18 | Search only the chosen channels, not news channels | 3+ | none | partial: same as EG-F1 | T5 (HX62) | P T D W | H | M |
| EG-F19 | Separate Continue watching for films and series | 1 | none | partial: Movies and Shows rows exist; Continue watching is one row | T4: optional, only if row order lands cheaply | P T D W | L | S |
| EG-F20 | Online subtitle search, AI live translation | 2 | "can't commit" | has: OpenSubtitles and SubDL (CP31), MSone; AI translation decline (cloud service or a large on-device model; no server) | decision D8 | P T D W | - | - |
| EG-F21 | Posters and cast online, Tamil Discover and subtitles, variants grouped | 1 | (Discover shipped) | partial: posters, cast, grouping (CP32, CP46) have it; no Tamil catalog | decision D6 | P T D W | L | M |
| EG-F22 | Stronger focus highlight on TV | 1 | none | partial: CP98 "high contrast focus on TV" planned, not built | T8 | T | M | S |
| EG-F23 | Several Home rows, move whole rows up and down | 2-3 | shipped in 1.1.0 | partial: starred chat rows exist (`settings.home_starred_rows`); no order | T4 | P T D W | M | M |
| EG-F24 | Picture in picture on phone | 1-2 | none | has: HX9 `settings.picture_in_picture_leave`, PiP button | none | P | - | - |
| EG-F25 | Sort a channel oldest first | 1 | none | has: `grid.sort_oldest`, `VideoSort.kt` | none | P T D W | - | - |
| EG-F26 | Export and import settings and collections | 1 | cloud sync (Premium) | has: CP53 backup and restore (`backup.*`) | none | P T D | - | - |
| EG-F27 | Arrange the Library tiles (folders) on TV | 1 | (folder order) | partial: folders follow Telegram's order; Home rows have no order | T4 (Home rows only; folder order stays Telegram's) | T P | L | - |
| EG-F28 | Choose the app language | 1 | planned | has: `settings.language`, 22 catalogs | none | P T D W | - | - |
| EG-F29 | Search chips for Collections and Discover | 1 | collections chip planned | has: type and chat type filters (`search.hidden_by_type`); no collections exist | none | - | - | - |
| EG-F30 | Auto play the next older video in the list | 1 (repeat) | none | partial: episodes, lessons (CP45) and audio (`QueuePlan`) step; a plain video "is a film and asks nothing" (`PlayerActivity.findEpisodes`) | T3 | P T D W | M | M |
| EG-F31 | MKV default and forced tracks honoured; track title beside language | 2 | "can't reproduce" | has: `TrackPreferences.kt` rules (forced through even with Off), `TrackPickerFragment.describe`/`tags` show "language (title)", Forced, SDH | none | P T D | - | - |
| EG-F32 | File names in search (dup of F12) | 2-3 | "explained many times" | has: as EG-F12 | none | - | - | - |
| EG-F33 | A user's list of subtitle tasks | 1 | "future versions" | unverified: the tasks were not recorded | second reading pass | - | - | - |
| EG-F34 | Cover via a bot; maximum streaming quality | 1 | streams max quality | has: original file streamed, Telegram's lighter copies optional (CP80); a bot is declined (TMPlayer never posts) | none | - | - | - |
| EG-F35 | Locking the phone pauses instead of leaving the video | 1 (3 reactions) | none | has: `PlayerActivity.onStop` pauses (or background audio) and keeps the player; Back later resumes | device check on the phone | P | - | - |
| EG-F36 | Subtitle box only as wide as the text; raised position | 1 | "I'll check" | has: `SubtitleLook.captionStyle` uses the per-line background, window transparent; `subtitles.position` Raised and High | none | P T D | - | - |
| EG-F37 | Say the format and codec in the playback error | 1 | "I'll check" | partial: `player.error_format` names nothing; UHD and Dolby Vision 5 are named; codecs only in Playback details | T6 | P T D W | M | S |
| EG-F38 | ExoPlayer vs LibVLC memory | 3 (talk) | - | has: `EngineSelector` (ExoPlayer default, VLC fallback, `EngineCrashes`), desktop mpv | none | P T | - | - |
| EG-F39 | HDR and Dolby Vision switch the TV's mode | 1 | community: use ExoPlayer | has: Media3 HDR output, `DolbyVisionBaseLayer.kt`, `settings.frame_rate_matching` | none | T | - | - |
| EG-F40 | Build a poster page from a FilmAffinity link | 1 | "own plans first" | decline: TMDB matching plus Choose the right poster does it without building pages; FilmAffinity has no open API | none | - | L | - |
| EG-F41 | Online subtitle search like VLC (dup of F20) | 1 | see F20 | has | none | - | - | - |
| EG-F42 | Favourites / watch later | - | exists (long press) | has: CP50 Watch later, starred chats | none | P T D W | - | - |
| EG-F43 | Favourites per Telegram folder | 2 | - | decline: low value; folders already filter chats and stars are global | none | - | L | - |
| EG-F44 | Favourites row on Home | 1 | shipped as collections | has: `home.row_watch_later`, starred chat rows | none | P T D W | - | - |
| EG-F45 | Cover-changer bot workaround | 4-5 | none | decline: TMPlayer never posts or edits messages; covers come from the uploader, TMDB and (T1) the album photo | none | - | - | - |
| EG-F46 | 2:3 portrait cards | 3+ | shipped 1.0.7.0 | has: posters 2:3 (`SearchResultsUi.kt:226`, `Series.kt:1012`) | none | P T D W | - | - |
| EG-F47 | Covers for videos in private channels | 1 | community: bot | has: TMDB by name works for any chat | none | - | - | - |
| EG-F48 | Add an .srt from the Telegram chat while watching | 1 | none | missing: `ExternalSubtitle.kt` is a local file picker only, and a TV has none (`tracks.no_file_picker`) | T2 | P T D W | H | M |
| EG-F49 | Chat avatar beside each result; only chosen chats | 1 | none | partial: avatar has (`GlobalSearch` chat hit); scope is HX62 | T5 | P T D W | M | - |
| EG-F50 | TV: icons instead of a search bar and chips; TV keyboard bugs | 1 | heart | has: CP99 top bar, voice search; HX56 (open) reviews the TV flows | none beyond HX56 | T | - | - |
| EG-F51 | Live streams from channels (EchoGram 1.0.7.0, Premium) | - | shipped | missing: no group call code (`groupCall` only in `SyntheticAccount`) | decision D1, then spike T10 | P T D | M | L |
| EG-F52 | Loop one video, "Remember loop" (1.0.2, 1.0.5.8) | - | shipped | missing: A-B loop only (`AbLoop.kt`); `QueuePlan.repeatOne` is audio only | T3 | P T D W | M | S |
| EG-F53 | Cloud sync of settings, collections, channels (1.1.0, Premium, their server) | - | shipped | partial: CP89 syncs places, watched, Watch later, favourites through Saved Messages; settings never travel by design (`SyncDocument.kt:39`) | decision D3 | P T D | M | M |
| EG-F54 | Images and GIF playback (rc.3) | - | shipped | GIFs has (`MediaMapper.kt:46` MessageAnimation); photos decline: "plays videos, and that is all it does" (FEATURES.md) | decision D2 | - | L | M |
| EG-F55 | Collections: own lists of shows across channels, smart search, per-season refresh (1.1.0) | - | shipped | partial: series pages per chat (CP27), Watch later, bookmarks; HX43 (tags across chats) and HX41 (copies across chats) planned | T9 | P T D W | M | L |
| EG-F56 | Discover: TMDB trending lists, search your channels for a title (rc.7) | - | shipped, praised | partial: HX41 planned (a title, then the copies in your own chats); trending lists decline: HX41 boundary, reads like a catalogue | decision D5 | P T D W | M | L |
| EG-F57 | Cast and DLNA, "Connect to device" on Home | - | shipped | has: `DlnaCastService.kt`, `CastPicker.kt`, transcode; discovery gap is EG-B9 | T7 | P D | - | - |
| EG-F58 | Kurdish language (1.0.2) | - | shipped | missing: no `ku` or `ckb` catalog | decision D6 | P T D W | L | M |
| EG-F59 | Demo account for store review (1.0.5.7) | - | shipped | decline now: no Play listing at $0 (U30); `telegram-terms.md` notes Play wants one | revisit with U30 | - | L | M |
| EG-F60 | Automatic storage cleanup every 7 days | - | shipped | has: cache limit (`storage.cache_limit_detail`), one-video watch cache, Clear pictures | none | P T D | - | - |
| EG-F61 | Datacenter and bitrate in video details | - | shipped | missing (L): `LineProbe.kt` knows the DC, Playback details does not show it | T6 (one line) | P T D | L | S |
| EG-F62 | Gestures: double tap seek, swipe volume and brightness, hide them | - | shipped (Premium) | has: FEATURES.md phone player, `TouchPrefs.kt` | none | P | - | - |
| EG-F63 | QR to contact and community on TV | - | shipped | has: `about.show_qr`, `about.link_qr_what` | none | T | - | - |
| EG-F64 | Send diagnostic logs from Settings | - | shipped | has: `LogUploader.kt`, `LogExport.kt` | none | P T D | - | - |

### Bugs

| Id | Bug (one line) | Reports | EchoGram's answer | TMPlayer status | Proposed action | Platforms | Value | Cost |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| EG-B1 | Trailers spin forever on Fire TV Cube | 1 | change trailer source | has: `TrailerOpener` lists `com.amazon.firetv.youtube`; `TrailerPlayer` gives up after `START_TIMEOUT_MS` with a QR | Fire TV check when one is at hand | T | - | - |
| EG-B2 | Collections gone after "delete cache" | 1 | cloud sync | has: lists live in settings stores, Clear pictures says lists are untouched (`settings.clear_pictures_untouched`) | none | P T D | - | - |
| EG-B3 | Scan misses MP4/MKV that are in the channel | 2 | send logs | has: video documents included, `MediaText.looksLikeVideo` extension fallback, Keep looking | none | P T D W | - | - |
| EG-B4 | After phone then TV, the TV does not auto advance | 1 | none | has by design: the next episode is found at play time from the chat (`EpisodeNeighbours`), not from local history | include in CP89's two-device pass | P T D | M | S |
| EG-B5 | "Unexpected message type" opening a channel | 1 | send logs | has: `MediaMapper` maps known contents and skips the rest, link preview videos read (`linkPreviewVideo`), `GarbageInputTest` | none | P T D W | - | - |
| EG-B6 | tvOS: Room "unable to open database", spinner forever | 1 | fixed tvOS 1.1.4 | has (no Room): `SafeDataStore.kt`, `CrashLoop.kt`, `errors.tdlib_unavailable`; risk moves to CP105 web TV builds | first-run storage test in CP105 | W (TV) | M | S |
| EG-B7 | Send logs says "cannot send" | 1 | TestFlight | has: `LogUploader` returns NoConnection and the caller keeps the file | none | P T D | - | - |
| EG-B8 | tvOS app closes a second after opening | 3 | Room fix | has: as EG-B6 | none | - | - | - |
| EG-B9 | Chromecast listed, DLNA list empty | 1 | none | partial: DLNA with a multicast lock (`DlnaPlatform.kt:37`) and help text (`cast.none_found`); no add-by-address, no repeated search | T7 | P D | M | M |
| EG-B10 | Refresh per season in a collection | 1 | exists (long press) | n/a: no collections; every listing has Refresh | none | - | - | - |
| EG-B11 | Album whose title is on the cover photo is not found in search | 3 | "Telegram search, send a channel" | partial: `MediaText.shareAlbumCaptions` shares a caption only among an album's videos; a photo's caption, and a search hit on that photo, are dropped | T1 | P T D W | H | M |
| EG-B12 | A Telegram link cannot be opened in the app | 1 | none | has: CP79, manifest filters for t.me and tg:// | none | P T D | - | - |
| EG-B13 | Saved Messages with thousands of videos shows a few | 1 | none | has: Saved tab, paging, 20,000-in-one-chat scale tests (`SyntheticAccount`) | check a real Saved Messages with topics by source chat | P T D W | M | S |
| EG-B14 | Old LG TV not found over DLNA | 1 | none | partial: as EG-B9 | T7 | P D | M | - |
| EG-B15 | VLC engine crashes on every video on a Fold 6 | 1 | community: force stereo | has: `EngineCrashes` (Automatic leaves VLC after a native crash), ExoPlayer default, `AudioDownmix.kt` | none | P T | - | - |
| EG-B16 | Album of cover image plus video: the video has no poster | 2 | not read | missing: the album's photo is never the video's picture; only the video's own cover is (`MediaMapper.kt:62`) | T1 | P T D W | H | M |
| EG-B17 | Internal build bug posted in the wrong topic | 1 | "right topic" | n/a: process | none | - | - | - |
| EG-B18 | Audio cover art gone (blue note tiles) | 1 | "report properly" | has: `ChatAudio.kt` reads `albumCoverThumbnail`; same class open as CP106 (thumbnail cache keyed by file id only) | do CP106 | P T D | M | S |
| EG-B19 | Kurdish not found | 1 | unclear | n/a: duplicate of EG-F58 | - | - | - | - |
| EG-B20 | Live stream stutters on mobile data | 1 | community: use Wi-Fi | n/a: no live streams yet, goes with EG-F51 | with T10 | - | - | - |
| EG-B21 | Home channels carousel odd after clearing cache | 1 | asked channel count | unverified: the text is cut | second reading pass | - | - | - |

(Counted as: has 12 = B1 to B8, B12, B13, B15, B18; partial 3 = B9, B11, B14; missing 1 = B16; n/a 4 =
B10, B17, B19, B20; unverified 1 = B21. B4, B6 and B13 are has with a check still to run.)

## 2. Backlog (partial and missing only)

Order is by value for the job (playing your own chats' videos) over cost. Every task: en.json keys
sorted, the other 21 catalogs left to the user (no langsync), `GarbageInputTest` for any new parser,
`JobFailures` or a caught failure with Try again for any new TDLib call, no dashes in text.

**Sequencing (files overlap):**
- T1 then T5: both touch `GlobalSearch.kt`, `AllChatsSearch.kt`, `ChatRepository.kt`.
- T2 then T3 then T6: all touch `PlayerActivity.kt`, the player menus and the desktop player.
- T4 then T9: both touch `HomeLayout.kt`, `HomeRows.kt`, the Home settings page and `Backup.kt`.
- T3, T4, T8 and T9 each add a setting to `SettingsStore.kt`/`SettingsModels.kt`/`SettingsBook.kt`: run
  them one after another or rebase the later one; `BackupCoverageTest` must list each new key.
- T7 and T8 are independent of all others. First wave that can run at once: T1, T2, T4 (plus T7).

### T1 Albums read as one post (EG-B11, EG-B16, EG-F16 gap) - sonnet high - H, M
- What: an album's photo caption becomes the caption of its videos; the album's photo is the video's
  picture when the video has no cover of its own (uploader cover still wins, then the album photo, then
  TMDB per the existing order, decide and document it); a search hit on a photo that belongs to an album
  resolves to that album's videos.
- Files: `core/src/commonMain/.../data/MediaMapper.kt`, `core/src/pureMain/.../data/MediaText.kt`,
  `MediaItem.kt` (an `albumPhotoFileId` or similar), `ChatRepository.kt` (album siblings around a message:
  `getChatHistory` from the hit with a small window, filtered by `mediaAlbumId`), `AllChatsSearch.kt`,
  `GlobalSearch.kt`, `HomeCache.kt` (persist the new field), `core/src/jsMain/.../web/WebRules.kt` and
  `webapp/src/tg/media.ts` for the web.
- Done when: a chat posting "photo with caption + video" albums shows the caption as the title and the
  photo as the card on P, T, D, W, and a channel search for that caption finds the video.
- Tests: unit (album of photo + 1 video, photo + 3 episodes, captions on two members, album id 0, photo
  only, video first), `GarbageInputTest` (album of 10 photos and no video, sizes missing, a 4096 char
  caption), scale (`HomeScaleTest`/`SyntheticAccount` 20,000-video chat: no extra TDLib call per item in
  listings; sibling reads only for search hits, at most N per page, FLOOD_WAIT waited by that call alone).
- Edge cases: protected chats (picture shown, nothing saved), a photo later deleted, an album split
  across two pages of history, an album with videos of two different shows, Home cache from an older
  version without the field.

### T2 Subtitles posted in the chat (EG-F48) - sonnet high - H, M
- What: Subtitles in the track picker gets "In this chat": `.srt`, `.ass`, `.ssa`, `.vtt`, `.sub` documents
  from the same album, a reply to the video, a few neighbouring messages, or a name match (series and
  episode code, or the video's base name), best match first; downloaded through TDLib and fed to the
  existing `ExternalSubtitle` path; remembered per video like an online pick (HX25 shape). A zip of
  subtitles is listed as "Compressed" (reuse `ZipArchive.kt` for a STORE zip, otherwise say it).
- Files: new `core/src/pureMain/.../player/ChatSubtitles.kt` (matching), new
  `core/src/commonMain/.../data/ChatSubtitlesTd.kt` (one `searchChatMessages` with the document filter and
  the show name, inside a budget), `app/.../player/TrackPickerFragment.kt`, `PlayerActivity.kt` (load
  hook only), `desktop/.../player` track menu (mpv `sub-add`), `webapp/src/player` (track menu).
- Done when: a chat with `Show.S01E02.mkv` and `Show.S01E02.srt` offers that file first on P, T, D, W, and
  on a TV it is reachable with the D-pad.
- Tests: matching unit tests on real name shapes (language tags `en`, `.eng.forced`, two languages),
  `GarbageInputTest` (binary bytes named .srt, a 50 MB "srt", an empty file, wrong encoding), TV focus
  test (`performKeyInput` DirectionDown to the entry, `assertIsFocused`), a one-call budget per open
  checked with a fake source.
- Edge cases: offline (entry hidden, local picker still there), a VobSub `.sub` (existing refusal), a
  chat with 2,000 subtitle files (only the top 10 matches listed), casting (existing
  `cast.subtitles_inside` path takes the file), kids lock (only allowed chats).

### T3 Play the chat in order, and Repeat this video (EG-F30, EG-F52) - sonnet medium - M, M
- What: a setting "After a video that is not an episode": Stop (today) or Play the next in the chat's
  order, following the grid's current sort (oldest first plays forward in time, which is the lecture
  case); the existing next-up countdown and Still watching apply. A Repeat this video switch in the
  player menu (phone overflow, TV More, desktop menu, web), off when the next video starts unless "Keep
  repeat on" is set (EchoGram's Remember loop), exclusive with A-B loop.
- Files: `core/src/pureMain/.../data/QueuePlan.kt`, `ChatAudio.kt` (`PlayQueue` for videos too),
  `SettingsStore.kt`/`SettingsModels.kt`/`SettingsBook.kt`, `app/.../player/PlayerActivity.kt`, the phone and
  TV player menus, `desktop/.../player`, `webapp/src/player`.
- Done when: in a chat of plain videos sorted oldest first, the next one starts after the countdown on
  P, T, D, W; Repeat restarts the same video.
- Tests: unit (sort change mid-play, the last loaded video when more pages exist: load the next page,
  end of chat: stop), repeat vs A-B vs sleep timer "end of this video" vs Still watching, TV focus test
  for the Repeat entry, idle CPU unchanged on the TV after a repeat.
- Edge cases: protected chats, a deleted next message, a next video above the size limit (skip it as
  the grid does), offline, Low storage mode (the cache trims between plays).

### T4 Home row order (EG-F7, EG-F23, EG-F27, optional EG-F19) - sonnet medium - M, M
- What: Settings, Home, Rows on Home gets an order: Move up and Move down on every row (Continue
  watching, New episodes, Watch later, Recently added, Movies, Shows, each starred chat row), drag on
  phone and desktop, a move mode on TV (OK picks up, Up and Down move, OK drops, Back cancels).
  Optional: split Continue watching into shows and single videos.
- Files: `core/src/pureMain/.../data/HomeLayout.kt`, `HomeRows.kt`, `SettingsStore.kt`/`SettingsModels.kt`,
  `Backup.kt` (order travels in the backup), `app/.../ui/settings` Home rows page, `app/.../ui/browse/HomePane.kt`,
  `desktop/.../ui/SettingsPages.kt` and desktop Home, `webapp/src/screens` Home and Settings.
- Done when: the chosen order shows on Home on P, T, D, W, survives a restart and a backup restore.
- Tests: unit (unknown row ids from a newer version ignored, a new row appended at its default place,
  an unstarred chat drops out), TV focus test for move mode, preview screenshots per form factor.
- Edge cases: every row off (existing `home.off_title`), 40 starred chats (the list scrolls, Home's
  first row still within 3 s), the kids lock hiding a starred row.

### T5 Search inside your own chats (HX62 with EG-F1, EG-F18, EG-F49, EG-F5, EG-F8) - sonnet high - H, M
- What: HX62 as written in PLAN.md (results only from added or chosen chats, hidden chats out, no
  @username lookup, empty state points to Add more chats), plus "Search my other chats" as a one-off
  button, a result count ("23 videos in 6 chats"), copies ranked by the preferred audio language tag in
  the name (HX38 overlap: take HX38's ranking if it lands first).
- Files: `core/src/pureMain/.../data/GlobalSearch.kt`, `AllChatsSearch.kt`, `ChatRepository.kt`
  (`searchAllChats`, `findByUsername`), `ui/src/shared/.../browse/SearchResultsUi.kt` and the search view
  model, `app/.../ui/browse/PhoneSearch.kt`, `TvSearch.kt`, desktop search, `webapp/src/tg/library.ts`.
- Done when: a search on an account with 3,000 chats and 40 added lists only the added chats' videos,
  first results within 3 s, on P, T, D, W.
- Tests: scale (`SyntheticAccount`, `HomeScaleTest` shape), FLOOD_WAIT, kids lock, old recent searches
  holding an @username (garbage), TV focus on the new button.
- Edge cases: no chat added yet (search every chat once and say so), a user who never used the picker
  (HX28: existing users), offline (on-device matches only).

### T6 The playback error says what the file is (EG-F37, EG-F61) - sonnet medium - M, S
- What: under "This device can't play this video's format" one line from the failed format, for
  example "HEVC Main 10, 3840 x 2160, E-AC-3 5.1", plus Try the other decoder (exists) and Copy details;
  the same on the desktop from mpv's track list; Playback details gains the datacenter line.
- Files: new `core/src/pureMain/.../player/FormatLine.kt` (pure), `app/.../player/PlayerActivity.kt`
  (`byErrorCode` area), `desktop/.../player/MpvErrors.kt`, `webapp/src/player` error card.
- Done when: an unplayable sample shows the codec line on P, T, D, W.
- Tests: unit with real and garbage MIME and codec strings (empty, 2 KB, unknown), screenshot of the
  sheet per form factor.
- Edge cases: an error before any format is known (no line), DV profile 5 and UHD keep their messages.

### T7 DLNA finds more TVs (EG-B9, EG-B14, EG-F57) - sonnet high - M, M
- What: repeat the M-SEARCH three times (ssdp:all and MediaRenderer:1), listen for NOTIFY alive, keep
  the list live while the picker is open; "Add a TV by its address" (IP or description URL), remembered;
  separate counts for Cast and DLNA in the picker. Verify (not assumed) whether Android 17's local network
  protection needs a permission once targetSdk rises, before CP15/U30 raise it.
- Files: `core/src/commonMain/.../cast/dlna/*`, `app/.../cast/DlnaPlatform.kt`, `app/.../cast/CastPicker.kt`,
  the desktop cast picker.
- Done when: gmrender-resurrect, VLC and Kodi renderers are found within 5 s on phone and desktop, and an
  address typed by hand casts.
- Tests: SSDP and description parsers in `GarbageInputTest` (huge, cut, wrong type), `DlnaFlowTest` with a
  slow renderer, a renderer that answers only NOTIFY.
- Edge cases: guest Wi-Fi and VPN (existing help text), a TV that changes IP, two network interfaces on
  the desktop, the LAN guard (token plus renderer IP) unchanged.

### T8 TV focus you can see from the sofa (EG-F22, part of CP98) - sonnet medium - M, S
- What: a thicker accent focus ring and a brighter scale on every TV focusable, with a High contrast
  focus switch under Appearance (TV only).
- Files: `ui/src/shared/.../components` focus modifiers and tokens, `SettingsStore.kt`, the TV settings page.
- Done when: every TV screen's focused element passes a contrast check in both themes and Black.
- Tests: preview screenshots per TV screen in light, dark and Black; D-pad focus tests unchanged.
- Edge cases: posters with light art under the ring, the 1 GB stick (no new animation cost).

### T9 Named lists across chats (EG-F55, HX43) - opus high (data model and sync) - M, L
- What: HX43's tags as named lists: add a video or a whole show to a list from the hold menu, a list
  page, an optional Home row per list (after T4's order), in the backup and in the CP89 sync file (a
  format version step, `too_new` handling kept).
- Files: new `core/src/pureMain/.../data/Lists.kt` and store, `SyncDocument.kt`, `Backup.kt`, `HomeRows.kt`,
  `ui` detail actions (`DetailActions.kt`), app, desktop, web.
- Done when: a list made on the phone shows on the TV through sync, and a video that left its chat
  leaves the list with the existing gone-video notice.
- Tests: merge rules (newest wins, removals remembered), `GarbageInputTest` for the new sync fields,
  scale (a list of 5,000 entries), TV focus in the hold menu.
- Edge cases: a show across two chats, the kids lock, a list name of 500 characters, sync file size cap.

### T10 Live streams spike (EG-F51, EG-B20) - opus high - only after decision D1
- What: one day to find whether TDLib's video chat stream segments for a channel's RTMP live stream
  can be played through Media3 without joining visibly; write the answer into PLAN.md, build nothing.

## 3. Decisions for the user

- **D1 Live streams.** EchoGram sells them in Premium. Watching a channel's live stream through TDLib
  likely means joining its video chat, which shows you as a viewer; it is also an L-sized player
  engine job. Do it, or leave it to Telegram's own app?
- **D2 Photos.** EchoGram shows images; TMPlayer plays videos only (GIFs already play). Keep it so?
- **D3 Settings across devices.** CP89 syncs places, watched marks, Watch later and stars, never
  settings. Add an opt-in "settings too" to the same Saved Messages file, or keep the backup file?
- **D4 Trakt, AniList, Simkl.** Outside services with keys; EchoGram lost Trakt overnight. Still "no new
  outside services"?
- **D5 Discover.** HX41 (a title, then the copies in your own chats) is planned; trending or popular
  lists that show titles you do not have read like a catalogue. Confirm HX41's boundary stands.
- **D6 New languages.** Kurdish (asked of EchoGram) and Tamil (asked with Discover). The catalogs are
  yours: add them to the next translation pass or not?
- **D7 Card shape per chat (EG-F13).** Against "one layout per page"; keep declining?
- **D8 AI subtitle translation.** Needs a cloud model (a server, a cost) or a large on-device model.
  Decline?
- **D9 Repeat default (T3).** Should Repeat this video stay on for the next video by default? Suggested: off.

## 4. Not verified (schedule a second reading pass)

- General Discussion topic: not read at all.
- Internal Testers topic: skipped on purpose; it is where internal build regressions are reported.
- Feature Request from about Oct 2025 to Sep 15, 2026 (roughly 800 of 1,100 messages) and its oldest 20
  screens: requests there may repeat or add to the 49.
- Bug Report before about Oct 3, 2026 (roughly 1,100 of 1,391 messages): the Aug 2026 freeze and crash
  hunt (1.0.5.1 to 1.0.5.4), the rc.4 cleanup bug and the ExoPlayer crash a minute before the end were
  reported there.
- EG-F33 (the subtitle task list was not recorded), EG-B16 (the developer's answer was cut), EG-B21
  (the text was cut), EG-F9 (date unclear).
- Two code claims to check on devices, not from the findings: that Telegram's search matches document
  file names as whole words (TMPlayer's `findEpisodes` relies on it; EchoGram's developer says no), and
  Android 17's local network rule for DLNA (T7).

## 5. What EchoGram's timeline says

- **Playback stability ate the year.** Of 24 version posts, about 12 are engine fixes: rc.4 to rc.5
  (LibVLC stopping, "cleanup mechanism" killing playback), rc.6.1 (ExoPlayer crash a minute before the
  end), rc.final (engine rewrite), 1.0.2 (96% less CPU), 1.0.4 to 1.0.5.4 (a freeze on play the
  developer could not reproduce for a week). Audio codecs needed FFmpeg twice (1.0.5.6, 1.0.5.8).
- **Storage cleanup** shipped five times (rc.4 internal, rc.4, rc.final, 1.0.1, 1.0.5.8).
- **Then looks and lists**: Discover (rc.7, Trakt then TMDB only), new navigation (1.0.1), covers and
  2:3 cards (1.0.5.8, 1.0.7.0), carousels and their order (1.0.1, 1.1.0), collections and cloud sync
  (1.1.0). Users' loudest asks were covers (about 8 users) and search scope (5+), not new engines.
- **Money moved often**: Premium fixes in rc.4, rc.4.2, rc.final; lifetime removed in Aug 2026; sync
  justified by server cost. TMPlayer's free, no-server line is its sharpest difference.
- **Ports cost them**: tvOS launched with a crash at start (Room database), iOS not approved after two
  months.

## 6. Their bug classes against TMPlayer

| Class | EchoGram | TMPlayer exposure and guard | Check |
| --- | --- | --- | --- |
| Local database fails to open on a new platform | tvOS Room crash at launch (B6, B8) | no Room; `SafeDataStore`, `CrashLoop`, `errors.tdlib_unavailable`; web TV builds (CP105) are the new platform | first-run storage failure test on webOS and Tizen |
| Cache cleanup kills the video playing | rc.4 "cleanup mechanism" | watch cache claimed in the player (memory note), Low storage window | keep the test that trims during playback |
| Crash near the end of a video | rc.6.1 | next-up card, credits skip and next-episode prefetch all run there | a near-end test on the TV fixture with autoplay on and off |
| Device-specific freeze or crash | 1.0.5.x hunt, Fold 6 VLC (B15) | `KnownDevices`, `EngineCrashes`, Sentry; HX69 (vivo crash) and HX79 (stutter) open | finish HX69 and HX79 first |
| Audio codecs missing | FFmpeg twice | NextLib/media3ext with DTS, TrueHD, E-AC3, now G.723.1 and Siren (tm4) | none |
| Default and forced tracks look random | F31 | `TrackRules`, learnt languages with Forget | none |
| Album and caption shapes | B11, B16 | partial, T1 | T1 tests |
| Thumbnail mix-ups | B18 cover art gone | CP106: `Thumbnails.kt` LRU keyed by raw file id | do CP106 |
| Third-party API pulled | Trakt keys removed, Trakt outage | TMDB, TVmaze, AniList, OpenSubtitles, SubDL, intro sources each disable with a message on 401/403 | none |
| Casting discovery | B9, B14, Android 17 fixes | DLNA and Cast built; discovery gaps in T7 | T7 |
| Telegram flood limits and search limits | search pagination fixes (1.0.2) | FLOOD_WAIT waited by the call that got it, budgets, scale tests | T5 scale test |
| Logs that cannot be sent | B7 | the file is kept and can be saved | none |
| Cross-device state | B4 | CP89 not yet tried on two real devices | CP89 two-device pass |

## Progress

- T1 Albums read as one post (worker, worktree branch worktree-agent-a7860d84df5ee3650, 4 commits 5263bbe, 27e9537, 6914620, b7fdb1f; not pushed).
  - Done: `AlbumPosts` (pureMain): `pick`, `fold`, `enrich`; `MediaItem.albumPhotoFileId` and `hasCover`, persisted in HomeCache and ChatGridDisk (keys `aph`, `cov`, older caches read none); mapper reads the photo (`MediaMapper.albumPhotoOf`) and folds it in `screen`; chat search (`mediaPage` with a query: photo filter search plus one `getChatHistory` window per album, 6 albums a page, 3 at once, FLOOD_WAIT waited by that read once up to 30 s), search across chats and added chats (same), history scan keeps photos across pages (album split over two pages); web: `WebRules.screenAlbums` + `RawPhoto`, worker `searchChat`/`across` ask photos and read album windows, card picture from the photo message.
  - Ladder decided: picture = uploader cover, then album photo, then Telegram's frame (all `thumbnailFileId`); the online poster (TMDB etc.) still draws above all three where posters are on, as for every video. Caption = the video's own (name parsing keeps priority), else the earliest captioned member of the album (photo or video). An album whose videos name two different shows is left alone.
  - Tests: AlbumPostsTest (23), AlbumSearchTest, GarbageInputTest.albumPostsNeverThrow, WebRulesTest (3 new, jsNodeTest), AlbumScaleTest (ui, SyntheticAccount 20,000 videos); tsc clean.
  - Left / decisions: a plain listing (no query) never reads albums (the rule: no TDLib call per item), so a Home or chat grid card gets the album photo and caption only once a search hit, a history scan or a cached item has carried it; a lazy per-card resolve on open (Details) is the follow-up if the user wants browse covered too. The web's uploader cover is not read (mtcute), so the web prefers the album photo over Telegram's frame. Not run on a device (no adb, per the brief). Edge not handled: TDLib answering a history window with fewer messages than asked (an album at the edge of what TDLib holds) is left as the hit showed it, no second read.
- T2 Subtitles posted in the chat (worker, worktree branch worktree-agent-abba45b08f1d0585b, 4 commits 893412e, 649db1b, 19fca4d, 3566b77; not pushed).
  - Done: core `ChatSubtitles` (pureMain, matching: album, reply either way, neighbours by 6 message steps or 3 minutes, then name by episode code, base name or title and year; `.en`, `.eng.forced`, `.pt-BR`, SDH tags; a different episode or film is left out of neighbours and names, only the same album or a reply keeps a different film's name; top 10; a zip over 8 MB, a file over 4 MB or an empty one is not listed). `ChatSubtitleFinder` (commonMain): ONE `searchChatMessages` per video (document filter, from the video, offset -50, limit 100: both sides, empty query), kept 5 min, shared by every picker, kids lock asks nothing, a failed or slow (8 s) answer hides the entry and is not kept. `ChatSubtitleText`: decode through `SubtitleText.decode` (the file's own language tag picks the code page), binary and cue-less text refused, VobSub `.sub` refused with `tracks.file_vobsub`, zip read through `ZipArchive.entries` (bounded: 600 entries, 40 MB) then `SubtitleText.fromArchive` (stored or deflated). `ChatSubtitleLoad.load`: fetch (TDLib `downloadFile`, 20 s) then read, every failure a sentence. Phone and TV: `TrackPickerFragment` Chat page ("In this chat", after the tracks and before Search online), `ChatSubtitleLoader`, `SubtitleFiles.attachChat` (MicroDVD to SubRip, cast through `onCastFile`, remembered per video through `SubtitleMemory` unless the chat or message withholds saving). Desktop: menu page `MenuPage.ChatSubtitles`, mpv `sub-add`. Web: worker `chatSubtitles` (one `searchMessages` with addOffset -50, plus a read of the video's own message), ranking by `WebRules.chatSubtitles` (the same Kotlin), an `In this chat` Picker, the file read through `chunk`, a zip entry by `fromZip` (stored; deflate where `DecompressionStream` exists).
  - New en.json keys: tracks.in_chat, tracks.in_chat_compressed, tracks.in_chat_detail, tracks.in_chat_gone (other 21 catalogs left to the user; web PAGE_STRINGS updated).
  - Tests: ChatSubtitlesTest (14, also on Kotlin/JS), ChatSubtitlesTdTest (the one search with 2,000 files and concurrent opens, the offset window, kids lock, a failed and a hanging search, 50 MB srt, binary, wrong code page, UTF-16, MicroDVD and VobSub, stored and deflated zip), GarbageInputTest lines (names, text and bytes as a file), ChatSubtitlesFocusTest (the real picker on a television: Down walks the tracks to In this chat, OK, Down, OK hands that file to the host; a gone file; no entry without files), desktop ChatSubtitlesMenuTest, WebRulesTest (1 new), webapp chatsubs.test.ts (5, with garbage); tsc clean. Full :core:jvmTest: only BackupCoverageTest fails (settings key tour_offer_after, from other work on main, not this task).
  - Decisions: the one call is an empty-query window around the video, not a query by show name (the plan said the show name): the first three tiers of the order (album, reply, neighbours) are all in that window, and a query would lose an album member named "english.srt". Cost: a subtitle posted far from its video under a name that does not say so is not found. A zip is listed with "Compressed" under its name and opened when chosen.
  - Left: not run on a device (no adb, per the brief). Desktop and web do not remember the pick per video (the online pick has no memory there either; HX25 is not built). Android remembers the file only for the handover to VLC or MX Player (HX25 would load it again on the next open). The web TV remote is not machine tested (the pickers share `Choice`).

- T7 DLNA finds more TVs (worker, worktree branch worktree-agent-a9b8ca06262c7b136, commits e18af11, 360a6a2, c23b014, 3993565, 09f1fa3 and a docs commit; not pushed).
  - Done: `DlnaDiscovery` asks for MediaRenderer:1 and ssdp:all 3 times at the start (`live` mode stays on while the picker is open: NOTIFY alive and byebye, a slow search every 30 s, rows expire by max-age, a TV that changes IP is one row by its UDN, non-renderers fetched once); `ManualAddress` (validator and resolver: IPv4 or description link, private and link local only, no names, no IPv6, no https, no redirects; unicast M-SEARCH then guessed paths); `ManualTvStore` (file, max 32, rechecked on load, not in a backup); shared `AddTvDialog` (ui/shared) on the phone's picker (`CastPicker.kt`, `DlnaCastSupport.manualTvs`) and the desktop popover (`CastFinder.add/remove`, `CastControls.kt`); Cast and DLNA counts apart; existing none_found help strings reused; `LocalNetworkPermission` behind a target check (no change at target 35).
  - Android 17 verified (developer.android.com/privacy-and-security/local-network-permission): ACCESS_LOCAL_NETWORK runtime permission (NEARBY_DEVICES group) needed only when targetSdk >= 37 on Android 17+, covers SSDP/TCP/UDP to the LAN; at target <= 36 it is implicit and must not be declared; NEARBY_WIFI_DEVICES was the deprecated Android 16 opt-in. Written into docs/BUILDING.md. Before CP15/U30 raise the target: add the manifest line.
  - Tests: ManualAddressTest, ManualTvStoreTest, DlnaFlowTest (+9: slow TV, NOTIFY only, ssdp:all only, max-age expiry and renew, IP change, broken description, public LOCATION, saved TV, 50 TVs), GarbageInputTest lines, AddTvDialogTest (desktop, 7), DlnaCastSupportTest (+3), LocalNetworkPermissionTest, DlnaReceiverTest (+1). New en.json keys: cast.add_address_hint, add_broken, add_confirm, add_entry, add_error_invalid, add_error_name, add_error_public, add_error_silent, add_remove, add_saved, add_working, counts, local_network_denied (other 21 catalogs left to the user).
  - Decisions: IPv6 (link local too) is refused with "use the IPv4 address" (the cast server and LanAddress are IPv4); a manual TV never expires, a saved one that is off just is not listed; the search interfaces are read at start (a Wi-Fi change while the picker is open needs reopening).
  - Left: raise-the-target manifest line (CP15/U30); real gmrender-resurrect, VLC and Kodi found within 5 s is a device pass (no device used here); TV (Android TV) has no casting so nothing there.

- T4 Home row order (worker, worktree branch worktree-agent-a2e49ef74ee7172e1, 4 commits 574d817, d9ac4c5, 3cd2b98 and the scale case; not pushed).
  - Done: core `HomeOrder` (keys continue, recent, watch-later, new-episodes, chat-<id>; parse with caps, arrange: unknown ids ignored, a new row at its default place, an unstarred chat drops out; move) applied by `HomeRows.build` through `HomeLayout.order`; setting `home_order` (SettingsStore.setHomeOrder), listed in `Backups.SETTINGS` (BackupCoverageTest green), NOT in the sync file (D3 open). Shared `HomeOrderEditor`/`HomeOrderSection` (ui): Move up and Move down per row, drag handle (touch and mouse), TV move mode (OK picks up, Up and Down move, OK drops, Back cancels, focus loss cancels, nothing written until the drop), Default order button; Settings, Home on phone, TV (same screen, remote = not touch) and desktop; SettingsMap row `home_order`. Web: Settings > Home list (buttons, mouse drag, reset), `homeOrder` setting checked by cleanHome, worker WebRules passes it to HomeLayout.order, ui/homeOrder.ts mirrors HomeOrder.kt.
  - New en.json keys (settings): home_order, home_order_down, home_order_handle, home_order_hint, home_order_hint_buttons, home_order_hint_tv, home_order_hint_web, home_order_moving, home_order_reset, home_order_up. Other 21 catalogs left to the user.
  - Tests: HomeOrderTest (12), GarbageInputTest (HomeOrder.parse, HomeLayout.of order), WebRulesTest case, node homeOrder.test.ts and homeSettings.test.ts, HomeOrderEditorTest (desktop: buttons, touch and mouse drag, reset, 40 chats at phone, desktop and TV widths, PNGs when TMPLAYER_SHOTS is set), HomeOrderMoveModeTest (app, Robolectric TV, D-pad by performKeyInput), HomeLayoutScaleTest (40 starred chats reversed: first row at 1.0 s, 27 chat searches); SettingsMapScreenTest, SettingsMapDesktopTest, SettingsMapTest, SettingsLinksSourceTest, CatalogTest, tsc clean.
  - Decisions: the list shows Continue watching, Recently added and the starred chats only: Watch later and New episodes are never built on Home (it fills itself, HomeRows) and the Movies and Shows rows were removed 2026-10-23, so there is nothing to move; HomeOrder still orders them if they return. The carousel stays on top, not a row. A row switched off stays in the list marked Off and keeps its place. EG-F19 (split Continue watching) skipped: a new row kind, key and switch on four platforms, not cheap. Web TV uses the Move buttons (no move mode); the web list names chats from the rows Home last drew (kept in the store), so a chat appears after Home loaded once.
  - Left: not run on a device or emulator (brief); drag past the screen edge does not auto scroll (buttons cover it); the 21 other catalogs.

- T3 Play the chat in order, and Repeat this video (worker, branch worktree-agent-ad2f44d6df943a7b5, commits c345171 core, 1773b06 phone+TV, 2b08486 desktop, 50c5c5b web, 394cbdf settings map):
  - Done: core `ChatOrder` (pureMain: Way from the sort, Oldest runs forward in time, every other sort newest first; `lookUp` pages a chat from the video playing, up to 6 pages, stops at the chat's end or offline; `pick` skips the video itself, a copy of the same file, a split piece and whatever the size limits refuse); `ChatRepository.videoWindow` (the three searches of mediaPage around an anchor, offset for the newer side); `PlayQueue.repeatsVideo/setRepeatVideo` and `atEnd(item, keepRepeat, sleepAtTheEnd)` (repeat lapses with the video unless Keep repeat on; a sleep timer for the end wins); switches `play_chat_order` and `keep_repeat` (SettingsStore, SettingsBook, Backups.SETTINGS, SettingsMap rows). Phone and TV: `findChatOrder` in PlayerActivity feeds `_episodes` so next-up card, countdown and Still watching apply as for an episode; Repeat this video line (phone overflow, TV More, switch, stays open), exclusive with A-B in both directions. Desktop: PlayerMedia.chatOrder, Options page switch, same rules. Web: the watch page takes the next card of the grid in its sort (chatOrderAround, a page more when last), Repeat/Keep repeat/chat order switches on the Playback options page.
  - New en.json keys: player.repeat_video, player.repeat_video_on; settings.keep_repeat(+_off/_on), settings.play_chat_order(+_off/_on). Web picks added to strings.config.ts.
  - Tests: core ChatOrderTest (14: per sort, paging, end of chat, offline, size limit, copies, deleted, repeat vs sleep vs keep), ChatOrderSettingsTest; app PlayerMenuTest, RepeatVideoFocusTest (TV remote Down/OK/Down), Settings map tests; webapp player.test.ts (repeat lapse rule).
  - Decisions (D9 etc): Keep repeat on defaults OFF; setting is a switch ("After a video that is not an episode": off Stop, on Play next); a replay is not an autoplay so it never counts towards the three-in-a-row Still watching (the two-hour idle ask still applies); a repeat does not mark the video watched again (as audio Repeat one); sorts other than Oldest and Newest (Name, Size, Year...) use newest first because the player has no grid list, the web follows the grid's tile order exactly; no polling added (the end of the video asks), so idle CPU is unchanged by construction, not measured.
  - Left: no device, emulator or browser run; idle CPU on the TV not measured; web tsc could not run fully (no generated rules module in the worktree), only my files checked; 21 other catalogs; PromoPlayerActivity untouched.
- T5 Search inside your own chats (worker, worktree branch worktree-agent-add88c69bf3e8bfcf, 3 commits 293f18a core, efbb5d4 view model + phone/TV/desktop, e9673f2 web; not pushed).
  - Done: search lists only the added chats (HX27's `searchAddedChats` was already there; this adds the rest of HX62). No @username lookup in search on phone, TV, desktop (`rememberUsernameChat`, `withUsernameChat` and the Join offer removed; `UsernameSearch.kt` is now `LinkOffer.kt`, a pasted post or invite link is still offered; `ChatRepository.findByUsername` stays for opened t.me links only, LinkFlow). Old recent searches with an @name run as plain words (GarbageInputTest lines, VM test). One-off "Search my other chats" (`GlobalSearchViewModel.searchOtherChats`, `VideoSearchSource.everyChatPage`, `searchAllChats(everyChat = true)` reads the kids lock alone): same words over the whole account, for those words only, added chats' cards stay. Count line "23 videos in 6 chats" (`GlobalSearch.tally`, `searchCountLine`) on all four platforms. Empty state: only the added chats are searched, Search my other chats, Add more chats. No chat added yet (or never used the picker, HX28): every chat is searched and a note says so with Add more chats. Copies ranked by preferred audio language: `GlobalSearch.copyOrder(copies, language)` (on this device, name carries the language, name names none, other languages, then picture and date) through `SearchIdentity.preferring`; HX38's ranking did not exist yet, so this is the language step it can reuse; fed from `trackPreferences.audioLanguage` in BrowseScreen and the desktop Shell. FLOOD_WAIT: each added chat's two searches wait it out in the call that got it (once, up to 30 s). Web: each added chat searched on its own six at a time (was one account wide search post-filtered), other chats button, count line, empty state, `src/ui/searchTally.ts`.
  - New en.json keys (search section): count, every_chat_note, none_added, other_chats, other_chats_detail (other_chats_detail is not shown anywhere yet). Reused picker.add_more. Web PAGE_STRINGS updated.
  - Tests: GlobalSearchTest (3 new), GarbageInputTest (2 lines), OwnChatsSearchTest (VM, 8), OwnChatsSearchScaleTest (SyntheticAccount 3,000 chats, 40 added: first results under 3 s, results only from added chats, no account wide call, six calls in flight; the one-off makes one account wide call), TvSearchScopeTest (Robolectric: the pill reached with Down and Up, OK on it, Add more chats, no other chats pill without a choice), searchTally.test.ts (node). Phone, TV, desktop compile; app, ui and core search tests green.
  - Decisions: the count line is not itself focusable on TV (text), it sits in the one row with the pill that leads the posters; the other chats results are merged with the added chats' ones, not shown apart; web copy ranking by language is not done (no audio language setting on the web); web reads at most 60 added chats per search.
  - Left: no device run (no adb); the other 21 catalogs; `search.other_chats_detail` unused; `link.join_named` strings unused now; the web `tsc` cannot run clean in a worktree without the generated rules module, only the touched files were checked; web FLOOD_WAIT relies on mtcute's own wait; HX38's wider recommended-copy order (decode ability, size) still open.
- T6 The playback error says what the file is (worker, branch worktree-agent-a2feb501f13f5b325, commits 1aa3d03 core, 5fcd738 phone+TV, 63611fe desktop, 29e4857 desktop datacenter, b45b14d web; not pushed).
  - Done: core `FormatLine` (pureMain: `line(Facts, extras)`, `copyBlock`; HEVC/H.264/AV1/VP9/Dolby Vision profile and level from the codecs string, size, rate, HDR10/HLG, sound with channels, container; total on empty, 2 KB, unknown, null parts) and `FileDatacenter`. Phone and TV: `FailedFormat` (failing renderer's format, then the listed tracks; FFmpeg reader and software decoder said) feeds a new `status_format` line under the sentence (codec or container failures only, codes 3000 to 6999; no line before any format is known). Copy details is now a native sheet button (`status_copy`) on phone AND TV (was a phone-only Compose pill), carrying the format line, the device's MediaCodec decoder names per MIME type, size and length, error chain, datacenter, and NO title, chat or file name (the old `File:` line carried the file name). Try the other decoder and the DV profile 5, UHD and DRM sentences are untouched. Desktop: `MpvFormats` from mpv's track list (plus `decoder-list`, `hwdec-current`, gamma for HDR) into the same line and Copy block, `Phase.Failed.format/details`; Copy details no longer carries the title. Web: `formatline.ts` (TS mirror of FormatLine, same test cases), `Probe.facts`, the line and a Copy details button on the error card.
  - Datacenter (EG-F61): TDLib exposes NO datacenter field. `File.remote.id` is the Bot API file id, whose second int32 (after the unpacked type word) is the dc_id; `FileDatacenter.of` reads only that, believes 1..5 and returns null otherwise (no guess). Phone and TV Playback details line "Telegram datacenter: DC n" (`Td.remoteDatacenter`, read once after opening, a local call), desktop Details row "Datacenter", web details line from the mtcute document's dcId (`VideoItem.dc`). Checked against the id shape "BQACAgIAAxkBAAI" (dc 2) and an encoder round trip in the test, not against a live id.
  - New en.json keys: player.error_via_ffmpeg, player.error_via_software, player.details_datacenter, player.details_label_datacenter (other 21 catalogs left to the user). Web PAGE_STRINGS gains player.copy_details, details_copied, details_datacenter.
  - Tests: FormatLineTest, FileDatacenterTest, GarbageInputTest.formatLineNeverThrows (core); FailedFormatTest, PlayerExtrasRenderTest sheet shots phone, phone sideways, TV (`build/device-test/review/extras-format-*.png`) and a TV focus walk Try the other decoder, Copy details, Go back, OK (the sheet is Android views, not Compose, and Robolectric has no ViewRootImpl key handling, so the D-pad step is `focusSearch`, not performKeyInput); desktop MpvFormatsTest (fake mpv props, garbage, throwing props, clipboard text has no title, sheet shot `desktop/build/seek-shots/error-format.png`); web formatline.test.ts (7) and the 133 player tests pass; web tsc shows only the known missing generated rules module errors.
  - Decisions: web leaves the colour transfer (HDR tag) out so the opening reads nothing more; desktop has no "Try the other decoder" (none existed there); the clipboard text is English whatever the UI language.
  - Left: no device or browser run (no adb); no web screenshot test; desktop's software note shows whenever hwdec-current is "no" at a failure; Copy details on a TV puts text on a clipboard nobody may paste from (kept per the brief); the FFmpeg container reader's ids read as upper case (ASF) in the line.
- T8 TV focus you can see from the sofa (worker, worktree branch worktree-agent-ab8324168df380c05, commits 8dbc460, ab26303, 8200ff6; not pushed). (Appended here because the plan edit was refused.)
  - Done: ONE definition in `ui/.../theme/FocusStyle.kt`: `FocusStyle` (ring, edge, scale, high contrast outer and inner bands), `LocalFocusStyle`, `focusScale`, `focusRing` (moved from Palette.kt, optional colour), `focusEdge`, `focusDrawn` (now @Composable, same draw-phase only code), `focusRingOnTv`, `focusRingOnHighContrast`, `FocusInk` and `FocusContrast` (pure WCAG functions). Phone and desktop keep 2 dp ring, 3 dp edge, 1.05 (the default style, same pixels). `TMPlayerTheme` provides `FocusStyle.tv(highContrastFocus)` on a television: ring 3 dp, edge 4 dp, scale 1.07 (7.3 dp a side on a 208 dp tile, inside Tv.FocusClearance 8 dp); with High contrast focus ring 4, edge 5, a 2 dp outer line (white on dark, black on light) and a 1 dp keyline of the opposite ink inside, so the ring shows on pale AND black art. Call sites moved to the style: tiles (ring and grow), search tiles, top bar tab and profile, chat rows and Recent tiles (focusEdge instead of a transparent border drawn on every item), list rows, Series and meta rows, ChooseChatsButton, Downloads rows. Fill-only TV elements (top bar tab, TvMenu row, episode rows) got the ring in daylight (their fill is a 1.24:1 step on the page); the Play and Details pills keep the user's 2026-11-01 calm fill swap and get the ring only with High contrast focus on.
  - Setting: `high_contrast_focus` (SettingsStore, SettingsBook, Backups.SETTINGS so BackupCoverageTest is green, SettingsMap row on the TV Appearance page, SettingsScreen row under Reduce motion, TV only, off by default). Not in the "Back to the defaults" snapshot (AppearanceSnapshot).
  - New en.json keys (settings): high_contrast_focus, high_contrast_focus_detail. Other 21 catalogs left to the user.
  - Tests: FocusContrastTest (ui, 6: ring, outer line, ring on a fill, white and black art, style numbers, every accent in light, dark, black), TvFocusRenderTest (app, Robolectric TV: the focus read back from pixels of a pale tile, a dark tile and a picker line, 3 themes x switch off and on, 3:1 against the page and, switch on, against the pale art; frames in build/device-test/review/focus-tv-*), TvFocusSettingsRenderTest (frames of the Appearance page, dark and Pure black). Existing D-pad focus tests, TileFocusRecomposeTest (a focus move composes no caption or art), SettingsMapScreenTest, ui jvmTest and desktop focus tests green. Perf: no animation and no layer added, ring and bands drawn in the same drawWithCache pass as before, scale is the existing graphicsLayer target; TileFocusRecomposeTest is the only TV perf test and passes; no stick CPU measured (no device).
  - Left: the light theme frames of the Appearance page could not be produced in the harness (the stored Light choice does not reach that page in Robolectric; the tiles and picker frames cover light). The player transport row and the Home banner were not rendered or pixel read separately (same focusRing and fill). tv-material Buttons and Cards keep their own default focus scale and border. A real TV pass (distance, glare, the 1 GB stick) is the user's.
- T9 Named lists across chats, design (worker, worktree branch worktree-agent-ac52739b2dc79b3bd, written before code; the plan edit was refused from the worktree, so this goes here for the main session to paste).
  - Model: `NamedLists` (core pureMain `Lists.kt`): `ListInfo(id, name, at)` and entries that are `WatchLaterRecord`s (a video, or a whole show by chat and series key, the record Watch later keeps) tagged with their list id; `addedAt` is the entry's write time. Ids: 10 random lowercase letters and digits, so `list-<id>` is a `HomeOrder` key and two lists of one name never meet. Name: control characters out, spaces folded, trimmed, cut to 60; an empty name is refused.
  - Store: `ListsStore` in the watched file beside Watch later (keys `nm_<list>`, `n_<list>_<entry>`, tombstones `xn_<list>`, `xn_<list>_<entry>`), not SettingsStore: the settings file is rewritten on every resume save during playback and a 5,000 entry list would ride along each time (WatchedStore's own rule for growing lists). Sign out clears it with the watched file; Clear watched leaves it.
  - Merge (sync and restore): newest write wins per list name and per entry; a removal beats an entry of the same age or older; a deleted list drops its entries older than the delete, and an add made after the delete on another device brings the list back with only that newer entry. Tombstones: at most 2,000, 90 days (SyncDocuments.REMOVAL_DAYS), pruned on every write and merge. The same video added twice keeps its first time (no duplicate, no move). Caps: 100 lists, 5,000 entries per list, 20,000 in all; an add past a cap is refused with a line, never evicts silently.
  - Sync: `lists` field in the CP89 file. Version step: a file holding lists or list tombstones is written as version 2, one without as version 1, so devices on older builds keep syncing until somebody makes a list; an older build then reads 2 as `too_new` and neither reads nor writes over it (nothing lost). Size: entries travel stripped (title cut to 60, no chat or file names); past 1 MB the cut drops list entries last, oldest added first, counted in the status's "left out" number.
  - Kids lock: entries of a locked chat are kept but never listed (pages, Home rows, counts in the picker). Gone video: GoneVideos.forget takes the message out of every list too. Web: lists in the page's IndexedDB with the same records and tombstone rules (the web app has no CP89 sync yet), rules shared through WebRules.
- T9 Named lists across chats, result (same branch, 7 commits da7acc9 core, 5543d01 sync and backup, eaa5e3b phone and TV, dfc1098 desktop, dc41a4b JS build fix, 342c9b1 web, 10b43d8 Settings order rows; not pushed).
  - Done: core Lists.kt (NamedLists, NamedListRules, ListPages.cards, NamedListKeys), ListsStore (WatchedStore.lists), ListsJson; SyncDocument lists field with the version 1/2 step, cut order (list lines last, oldest first, SyncDropped.listEntries); Backup lists (version 2 when present, restore adds back); GoneVideos leaves lists ("your lists" lines); HomeRow.Listed, HomeOrder list-<id> keys and editor rows (app, TV, desktop, web Settings, Home); DetailAction.AddToList; shared ListPickerHost/ListPickerMenu/ListNameDialog/ListDeleteDialog/ListPageScaffold; app ListPage (window, History header entry), MediaDetailSheet and show menu/page; desktop right click, detail pane, ListsPage over Home, History buttons; web ui/lists.ts, store, worker listTiles, /lists page, card menu, detail and show page, History tab, Home rows, Settings order rows.
  - New en.json keys: backup.restore_lists, continue.gone_left_history_and_your_lists, continue.gone_left_your_lists, detail.round_list, lists.* (add_to, add_to_detail, added, already, create, delete, delete_message, delete_title, deleted, empty, full, gone, heading, name, name_empty, name_hint, new, none, on_home, open, remove, removed, rename, save, too_many). Other 21 catalogs left to the user.
  - Tests: NamedListsTest (21: names, caps, merge algebra, removals and expiry, version step, stripped lines, 5,000 lines fit the file and the cut, phone to TV through the file, web and phone at once, backup restore, page grouping, kids lock, Home row order, store at 5,000 under 1 s), GarbageInputTest.namedListsNeverThrow, BackupCoverageTest watched file case, DetailActionsTest case, ListsTvFocusTest (app, Robolectric TV), ListsPageTest (desktop), web node lists.test.ts plus goneRules and homeOrder cases; full core jvmTest, jsNodeTest and ui jvmTest green; tsc clean.
  - Decisions: lists live in the watched file, not SettingsStore (see design); the Home switch travels with the list (sync), the row order stays per device; a show of the same name in two chats is two cards (each opens its chat's episodes); film copies a list holds separately fold as the grid folds them; Remove from list on a card takes every line it stands for; the web app's lists stay in the browser (it has no Saved Messages sync yet), so "web writing while the phone does" is covered by the shared merge rules and two tabs, not by a real sync.
  - Left: no device or emulator run (brief); the name field's focus on a TV is checked on the desktop only (a focused field's blinking cursor never lets Robolectric idle); the web TV has no card menu, so Add to a list there is on the video's page; a list's See all on a TV Home row is the row's last tile; the 21 other catalogs.
