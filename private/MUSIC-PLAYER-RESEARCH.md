# Music player for Telegram audio: research (2026-10-30)

Status: research only, nothing built. Asked for by the user on 2026-10-30 ("a lot of people have been
requesting a music player version of tmplayer"). The user likes the UX of PixelPlayer
(github.com/PixelPlayerHQ/PixelPlayer), studied in section 3. Written in the style of the other plans
in `private/`: decide, do not ask; every decision has its reason next to it.

## 0. The answer in ten lines

1. Yes, a separate app. A video app and a music app disagree on the queue, the mini-player, the library
   shape and background play being the main mode. Inside TMPlayer it would clutter both.
2. Separate package and store listing, same repository, same `:core` TDLib layer, same
   `TdDataSource`/`DownloadWindow`, same NextLib decoders. Working name: TMPlayer Music (name to settle).
3. Platforms: phone first, then desktop. TV is skipped (nobody browses a library with a remote). Web later.
4. UX target: PixelPlayer's three-tab shell, floating mini-player, wavy seek bar, synced lyrics page. Taken
   as inspiration, not copied (its licence is proprietary, and our rule is identity kept).
5. Source of tracks: the user's own chats, opt-in per chat. FLAC arrives as documents, so documents are
   scanned by mime type and extension, not only Telegram's audio filter.
6. Cache: on by default with a cap and least-recently-played eviction, can be switched off (stream only),
   "keep offline" pins, a Free up space screen with a per-chat breakdown.
7. Lyrics: embedded tags, then `.lrc` in the same chat, then LRCLIB through our own gateway (opt-in).
8. Playlists live locally, exported as JSON/M3U, never written into Telegram.
9. Not doing: YouTube Music scraping, public channel discovery, AI playlists, third-party artist image
   APIs by default, crossfade and DSP in v1.
10. Biggest risks: store and Telegram view of pirated music in chats (section 11), and low-end budget
    (section 9), because PixelPlayer itself asks for 6 GB RAM and Android 11.

## 1. Why a separate app

| Question | Inside TMPlayer | Separate app |
| --- | --- | --- |
| Navigation | Home, Movies, Downloads plus Music: five jobs in one bar | Each app has one job and 3 to 4 tabs |
| Player | One overlay built for video, D-pad and landscape | A queue, album art and a mini-player that lives on every screen |
| Background play | Exception today | The default mode |
| Store listing | "Video player" with a music mode | "Audio player for your own chats": its own keywords and screenshots |
| Takedown risk | A music complaint could hit the video app | Contained to one listing |
| Login | Shared | A second QR login (the cost) |

Alternative considered: one APK with two launcher icons (`activity-alias`). Shares the login and the
updater, but the listing, screenshots, permission set (notification, foreground service) and the Telegram
terms story all stay muddled, and the APK grows for people who want one half. Not chosen. Revisit only if
the second login turns out to hurt (see open question Q1).

## 2. What we can reuse (from `docs/ARCHITECTURE.md`)

- `:core` (pure Kotlin models, sign-in flow, `DownloadWindow`) and `jvmSharedMain` (`TdByteWindow`, TDLib
  wiring). Audio needs the same offset reads: seeking in a 200 MB FLAC is the same problem as seeking in
  an MKV.
- `TdDataSource` (Media3) and `TdMediaData` (desktop). Container hint in the URI (`tdfile://<id>?type=`)
  is reused with `audio/flac`, `audio/mpeg` and so on.
- NextLib software decoders already shipped (DTS, TrueHD, E-AC3): FFmpeg also covers ALAC, APE, WavPack,
  and anything the device lacks. Hardware or platform decoders stay first.
- `:ui` shared Compose screens and the design tokens; desktop libmpv via mediamp for the desktop build.
- `JobFailures`, `guardedLifecycleScope`, WindowRecovery, the i18n catalog test, `SyntheticAccount` for
  scale tests, the update-feed and release machinery (GitHub APK keeps the updater; store builds do not).
- Not reusable as is: the video overlay (`player_controls.xml`), Home rows, the watched store. The music
  app needs its own index (section 6) and queue.

New work: library index and tags reader, queue and media session, lyrics, cache policy, the screens.

## 3. Competitor study

### 3.1 Table

| App | Licence / status | What it does well | What to take | What to leave |
| --- | --- | --- | --- | --- |
| PixelPlayer | Proprietary (README: "Proprietary License", MIT only for code before 2026-05-12). 6.8k stars, Kotlin, Material 3 Expressive | The UX the user likes (3.2). Daily Mix, LRCLIB lyrics, tag editor, crossfade, Chromecast, widgets | Shell, mini-player, wavy seek bar, lyrics page, pill chips | Needs Android 11 and "6 GB RAM recommended". AI playlists, Deezer artist art, Hilt/Room stack |
| Gramophone | Open source, F-Droid | LRC, TTML, SRT lyrics, word-level karaoke, fully offline | Lyric formats and word sync | No streaming at all |
| Auxio | Open source | Very clean library, ReplayGain for MP3, FLAC, OGG, Opus, MP4 (uses a patched Media3) | Library structure, ReplayGain behaviour | The patched Media3 is a maintenance burden |
| Booming | Open source | Multi-timestamp LRC, auto lyric download, in-app lyric editor | Lyric editor idea (later) | |
| Namida | Open source, Flutter | The best looking UI, heavy customisation | Visual polish bar | Few audio tuning options, Flutter |
| Metrolist | Open source | Offline cache, synced lyrics, background play (YouTube Music client) | Cache UX | YouTube scraping (terms and store risk) |
| Poweramp | Paid | Gapless, crossfade, ReplayGain, 10-band EQ, Android Auto, Chromecast | The audiophile feature bar | Paid DSP engine |
| Musicolet | Free, no ads | Gapless, ReplayGain, tag editor, 10-band EQ, sleep timer (time or song count), Android Auto control of queues and folders | Sleep timer by song count; queue-first design | |
| Symfonium | Paid | Library sources abstraction, sleep timer, Android Auto, favourite button in the car | "Source" as a first-class idea: here a chat is a source | |
| PixelMusic | Proprietary, small | Only one found that streams from Telegram chats, channels and Saved Messages, with LRCLIB lyrics (its own claims, unverified) | Proof there is demand | Closed source, no stated cache or space model |

Search results for the paid apps were blog roundups of mixed quality: treat Poweramp, Musicolet and
Symfonium rows as unverified until checked on their own pages.

### 3.2 PixelPlayer UX, read from its four screenshots (2026-10-30)

- **Shell.** Three tabs only: Home, Search, Library. The bar is a floating rounded pill with a large
  indicator capsule behind the active icon and a label under each. Settings is a round icon button at the
  top right of Home and Library, not a tab.
- **Mini-player.** A floating rounded card above the tab bar, tinted from the album art (burgundy here),
  with a round cover, title and artist, a filled round play button and a smaller skip button. It floats
  over the list, so rows scroll under it.
- **Home.** Deliberately sparse: a huge "Your Mix" title, "Today's Mix for you" subtitle, one big round play
  button, and a scatter of cover art in mixed shapes (circles and a tilted pill). One idea per screen,
  almost no chrome.
- **Library.** Big title, a row of pill chips (Songs, Albums, Artist, then more off screen) with a small
  underline marker, then one rounded panel with a Shuffle pill and a sort button, then song rows as
  separate rounded cards: cover with rounded corners, title, artist, a round three-dot button.
- **Now Playing.** Full screen in the art's colour. Top row: round collapse button, "Now Playing" label,
  two small buttons (lyrics, queue). Square cover with soft rounded corners, centred title and artist, a
  **wavy progress line** with a round thumb and elapsed and total time, then three chunky transport
  buttons (previous, a larger rounded-square pause, next), then one capsule that holds shuffle, repeat and
  favourite.
- **Lyrics.** Own page: back button, a two-way pill switch Synced or Static, large left-aligned lines, the
  current line bold and bright, others dimmed. The play or pause is a floating rounded square and a dark
  floating seek capsule sits at the bottom, so the page needs no other controls.
- **Colour.** Everything is tinted from the cover (Material You dynamic colour plus album art colours).

### 3.3 What we take, what we change (rule: inspiration, not imitation; see memory "ui-inspiration-not-imitation")

Take: three tabs plus settings button, floating mini-player over content, one full-screen player with
cover, wavy progress, chunky buttons, a lyrics page that is only lyrics and a seek bar, art-tinted
colours, rounded list cards.

Change so it is TMPlayer's own: our colour and type tokens, our icon set, our round and pill language from
the video app, and a fourth idea PixelPlayer does not have: **Chats as a source** (Library has a Chats
chip, and a chat can be played as a whole). No scatter-of-covers Home and no "AI" wording; Home shows
Continue, Recently added, Recently played and Favourites, like the video app's Home but audio.

Budget notes (low-end rule in AGENTS.md): the wavy bar and the art-derived colour animate, so they pause
when the screen is off or the app is in the background, and stop when playback is paused. Measure idle CPU
after a track ends. Colour extraction runs once per cover, cached, off the main thread.

### 3.4 Cloning PixelPlayer: not allowed (checked 2026-10-30)

The user suggested cloning it and modifying it. Its LICENSE (Theo Vilardo, 2026) allows study and personal
non-commercial use only, and says: "Redistribution of this Software or any derivative work, in binary or
source form, is strictly prohibited without prior written permission", and names publishing on Google Play
or any commercial platform as prohibited. A TMPlayer Music fork shipped on GitHub, F-Droid or a store is a
derivative work. Only code contributed before 2026-05-12 is MIT (THIRD_PARTY_NOTICES.md), which means an
old commit could be forked legally, but it is a stale, mixed base that also pulls Hilt, Room, Retrofit and
Android 11+, none of which fit our KMP, Android 6 and TDLib stack. Decision: rebuild the UX ourselves on our
engine. Optional: ask the author for written permission, which is the only clean route to their code.

## 4. Screens and workflow

1. **Setup (first run).** QR sign-in, then "Which chats have your music?": a searchable list of chats with
   a count of audio files found so far, nothing selected by default, Select all available (confirm when
   the account is large). Skippable: Chats can be added later.
2. **Home.** Continue (the last queue, position kept), Recently added, Recently played, Favourites. A big
   Play button for "shuffle everything" is allowed but is a plain shuffle, not a recommendation.
3. **Search.** Titles, artists, albums, chats, lyrics text later. Results appear from the local index in
   under 100 ms; chats not yet indexed show a thin progress line, never a spinner over the whole page.
4. **Library.** Chips: Songs, Albums, Artists, Playlists, Chats, Folders (only where the platform has them,
   see 8). Shuffle pill and sort button on top, same as the reference. Sort: title, artist, date added,
   duration, size, most played. A Chats chip row lists each chosen chat with its cover, track count and
   size, and a Play all button.
5. **Mini-player.** On every screen. Swipe up opens Now Playing, swipe left or right skips, swipe down
   dismisses it only when paused.
6. **Now Playing.** Cover, title, artist (tap goes to the artist), wavy progress with time, previous,
   play, next, shuffle, repeat, favourite. Buttons at the top: lyrics, queue. Long press on the cover
   opens a sheet: sleep timer, speed, add to playlist, go to album, go to chat, keep offline, info (format,
   bit depth, sample rate, size, source chat).
7. **Queue.** Drag to reorder, swipe to remove, Play next and Add to queue everywhere, Save queue as a
   playlist.
8. **Lyrics page.** As in 3.2: Synced or Static switch, tap a line to seek, a Fix timing offset control, a
   "Lyrics are from" label naming the source. No lyrics: a quiet empty state with Search online (opt in).
9. **Settings.** Playback (gapless, ReplayGain, sleep timer defaults, resume long tracks), Library (chats
   scanned, tag source, hide short clips under 30 s, hide voice messages), Lyrics (online lookup on or
   off), Storage (cache switch, cap, Free up space), Appearance, About and legal, Send logs.

## 5. Features in phases

**v1**
- Formats: FLAC, MP3, AAC and M4A, OGG Vorbis, Opus, WAV, ALAC (FFmpeg path), AIFF if cheap.
- Gapless, queue (play next, add, reorder, remove), shuffle, repeat one and all.
- Background play with notification and lock-screen controls, Bluetooth and headset buttons, audio focus,
  pause on headset unplug (becoming noisy), resume after a call.
- Favourites, user playlists, smart lists (Recently added, Recently played, Most played).
- Resume position for tracks over about 20 minutes and a speed control (podcasts, mixes, audiobooks).
- Sleep timer by minutes, by end of track and by number of songs (Musicolet has the last one).
- Lyrics from tags and from `.lrc` in the chat; synced and plain views.
- Cache policy and Free up space (section 7).
- Search over the local index.

**v1.x**
- LRCLIB lookup through our gateway; lyric offset; word-level sync (enhanced LRC, TTML).
- ReplayGain (track and album) with a tested gapless story (section 6.4).
- Android Auto browse tree, system equaliser, home-screen widget, share a track as a card.
- Playlist import and export (M3U and JSON), listening statistics (local only).
- Tag editor for the files in TMPlayer's own cache is **not** planned: editing a file that lives in a chat
  does nothing for the chat copy, so it would mislead. A local override of title, artist and cover per
  track in the index is allowed instead.

**v2**
- Desktop (Windows, Linux, macOS) with MPRIS and SMTC media keys, drag-and-drop of local folders.
- Web: audio in the web app.
- Local files on the device as a second source (so the app is useful without Telegram). Decide later; it
  widens scope and the permission story (Android storage rules).

**Deliberately not done** (extends the "Set aside" list in `private/PLAN.md`): casting in v1, AI or
recommended playlists, Daily Mix, third-party artist-image APIs, crossfade, DSP chains, DSD and bit-perfect
USB output, YouTube Music, public channel search or any catalogue of music.

## 6. Telegram as the source: how it works

### 6.1 Finding the tracks

- `searchChatMessages` with `searchMessagesFilterAudio` returns audio messages, and
  `searchMessagesFilterDocument` returns documents (confirmed these filters exist in TDLib; the search
  results did not show any mime filter, so the mime check is ours).
- **Lossless is mostly sent as documents.** People post FLAC, WAV and APE as files so Telegram does not
  recompress or tag them as audio. Scan documents too and keep those whose `mime_type` starts with
  `audio/` or whose extension is in an allow list (flac, mp3, m4a, aac, ogg, oga, opus, wav, aiff, alac,
  ape, wv, wma only if decodable). A zip or an `.m3u` is ignored in v1.
- Voice notes (`voice_note`) are excluded by default, with a setting to include them. Round video notes
  never.
- A `.lrc` document with the same base name as an audio file in the same chat is that track's lyrics
  (section 8). So the document scan keeps `.lrc`, `.txt` and `.cue` names, indexed by message id, and does
  not show them in Songs.
- Telegram's own restriction reasons are honoured: `MediaMapper` already skips restricted messages and the
  music app uses the same mapper. `ignore_platform_restrictions` stays off.

### 6.2 Tags and cover art

- Telegram's `audio` message carries title, performer, duration and a small album-cover thumbnail, but
  documents carry none of it, and the fields are often empty or wrong even on audio messages.
- Read the real tags from the file head: ID3v2 (MP3), Vorbis comments and the PICTURE block (FLAC, OGG,
  Opus), `ilst` atoms (M4A). Fetch only the first 64 to 256 KB via the same offset download (an M4A with the
  `moov` at the end needs the tail too; the same head, tail, head rule as the seek matrix). Cache the
  parsed result in the index, never the whole file.
- Embedded art is decoded downscaled to the size shown and cached on disk (bounded, section 7). A 10 MB
  cover must never be decoded full size on a 1 GB device.
- Fallbacks, in order: tags, then Telegram's fields, then "Artist - Title" parsed from the file name, then
  the file name. Strip leading track numbers, `_` and common noise like "(Official Audio)". Never rewrite
  non-Latin text; decode legacy Windows-1251, Shift-JIS and similar ID3v1 and v2.3 encodings by detecting
  rather than assuming UTF-8.
- A parser of outside data needs its line in `GarbageInputTest`: the tag parsers get truncated, wrong-type,
  huge and random input (AGENTS.md "Parsers have garbage tests"). No `!!`, `.first()` or `[0]` on tag data.

### 6.3 The index (scale)

- A local database of tracks: file id, chat id, message id, title, artist, album, album artist, track and
  disc numbers, duration, size, mime, bit rate, sample rate where known, date added, tag state, cover key,
  plus plays and favourite. It must be a store the KMP modules can use (`:core` targets android, jvm and
  js); decide between the one already used in the repo and a KMP-friendly one when phase 1 starts.
- Sync is incremental per chat: remember the newest and oldest message id scanned, fetch pages newest
  first, stop at the stored id. A refresh reads a bounded number of chats, a few at a time, and honours
  FLOOD_WAIT inside the call that got it while the others continue; retries stop by themselves.
- First content shows from the disk index within 3 s, never after a whole scan. Only chats the user picked
  are scanned, which is what makes 3,000 chats with an archive affordable.
- Targets to prove with `SyntheticAccount`: 100,000 tracks in total, 20,000 in one chat, 3,000 chats
  with folders. Lists page from the database with a keyed window, never load all rows.
- Duplicates: same duration within 1 s, same normalised title and artist, and same size (or the same
  Telegram `remote_id`, which survives forwards) group into one track with several sources. Playing picks
  the source that is already on disk, then the smallest.
- Idle: no job keeps paging or polling when nobody touches the app. Measure `/proc/<pid>/stat` after
  touching Home, Library and Chats (AGENTS.md low-end rule).

### 6.4 Playing through TDLib

- A `tdfile://` source per track with the container hint. Read-ahead for audio is small: a few MB and the
  next track's head (see 6.5), because audio bit rates are low and the real risk is a stall on a slow
  connection, not memory.
- FLAC seeking needs the SEEKTABLE near the head. If it is missing, ExoPlayer falls back to an estimate,
  which downloads more; flag tracks without it in the matrix.
- MP3 gapless needs the LAME or Xing header at the head, so the head read in 6.2 doubles as that.
- Network cut mid-track: play what is buffered, show "waiting for network" in the mini-player, resume on
  its own (TDLib reconnects). A track whose file was deleted from the chat shows "Not available in the
  chat" and the queue skips it after one try, never loops.

### 6.5 Next-track prefetch and gapless

- Media3 handles gapless for FLAC (no padding) and for LAME MP3 (encoder delay and padding read from the
  header). The prefetch has to start the next track's file before the current one ends, but only the head
  and about 10 s, so the cache is not filled by a queue of 50.
- **ReplayGain conflicts with gapless.** The next track is pre-buffered before its gain is known, so its
  first seconds can be at the wrong volume. This is an open Media3 issue (androidx/media 418), worse for
  FLAC than for MP3 because FLAC buffers less. Auxio ships a patched Media3 for it. Plan: ship ReplayGain
  off by default, build it as an audio processor fed from the index (gain known before the track starts,
  since tags are read at index time), and test the first 3 seconds of each track in the device pass.
  Treat the patched-library route as a last resort.

## 7. Cache and space

Follows "Space and size", "Interruptions" and "Confirm what cannot be undone" in AGENTS.md.

- **Model.** Telegram's files live in TDLib's own folder. The music app puts a policy on top:
  - `Cache on` (default): played tracks stay on disk up to a cap. Caps: 500 MB, 1, 2, 5, 10 GB, No limit.
    Default 2 GB on phones, 1 GB on devices with under 2 GB RAM or little free space. Eviction is least
    recently played first and never touches pinned tracks or the current queue.
  - `Cache off`: stream only. The current track and the next track's head are kept and removed when
    playback moves on. Switching it on or off mid-playback never interrupts the track that is playing.
  - **Keep offline** on a track, album, playlist, chat or Favourites. Pinned items download in the
    background one at a time (a foreground service of the right type, a cancel button in its notification),
    pause on mobile data if the user chose Wi-Fi only, and resume after a restart.
- **Space checks.** Before a bulk Keep offline: show "needs X, Y free", the running total as the selection
  changes, and offer what fits ("Keep the first 214 tracks"). Reserve a floor (the larger of 300 MB and 2%
  of the disk) that the cache never uses.
- **Free up space screen.** Total used, then a breakdown: cache, kept offline, covers and lyrics. Under it
  a per-chat list sorted by size and a per-playlist list. Buttons: Clear cache (everything not pinned),
  Remove from one chat, Remove all kept offline. Every destructive button opens a dialog saying exactly
  what is removed and how much space it frees, and that the messages stay in Telegram; the "also remove
  TMPlayer's copy" style options are off by default.
- **Interruptions.** Disk full part way stops the queue and reports "saved 31, skipped 12 (no space), failed
  2"; no partial files left behind; a revoked folder permission (for an export folder) says so and offers
  to choose again; the app going to the background or a rotation does not restart a download; the same
  action pressed twice is ignored.
- **Bounded caches.** Cover art and lyrics have their own disk caps (for example 100 MB and 20 MB), and
  memory caches are size-limited LRUs.

## 8. Lyrics

- **Sources, in order:**
  1. Embedded tags: `USLT` and `SYLT` (ID3), `LYRICS` and `UNSYNCEDLYRICS` (Vorbis), `©lyr` (M4A).
  2. A `.lrc`, `.ttml` or `.srt` document in the same chat with the same base name (and, if the chat posts
     albums as a zip or a series, the nearest message in time from the same sender).
  3. LRCLIB lookup, opt in, see below.
- **Formats.** LRC with several timestamps per line, offset tag `[offset:]`, enhanced LRC word times `<mm:ss>`,
  TTML with word spans. Plain text shown as Static. Parsers are tolerant and covered by `GarbageInputTest`.
- **LRCLIB.** `GET /api/get` takes track name, artist name, album name and duration in seconds; the docs
  recommend giving all four; `GET /api/search` also exists, and results carry `plainLyrics` and
  `syncedLyrics`. The docs we found state no rate limit: confirm on lrclib.net before shipping. Send a
  clear User-Agent.
- **Privacy.** A lookup tells a third party which song the user plays. Same pattern as MSone subtitles:
  go through our own gateway (as `subs.tmplayer.org` does), send only title, artist, album and duration,
  never chat names or ids, keep nothing, off until the user turns it on, and say so in the privacy page.
  Add to `docs/distribution` and the site privacy page when built.
- **Copyright.** Lyrics are copyrighted. In 2012 a lyrics provider's complaint got the Apollo player pulled
  from Play. Keep the lookup user-triggered and opt in, cache per device only, do not host or redistribute,
  and credit the source on the lyrics page.
- **Display.** Synced page as in 3.2; a tap seeks; auto-scroll pauses while the user scrolls and returns
  after a few seconds; a Fix timing control (plus or minus in 100 ms steps, saved per track); right to left
  and Indian-script lines wrap correctly (this audience is large: test Malayalam, Hindi, Arabic).

## 9. Platform engineering notes

- **Playback.** Media3 `MediaSessionService` as a foreground service with `foregroundServiceType`
  `mediaPlayback`, declared in the manifest (Play wants a declaration per type). Notification permission on
  Android 13 and later: ask at the first play, work without it (the media session still shows on the lock
  screen on most builds; verify on the test phone). Android 6.0 is the floor, guard newer APIs via
  `LegacyApiCompat.kt`.
- **Android Auto.** `MediaLibraryService` with a small tree (Recent, Favourites, Playlists, Chats) and the
  rule that playback is not started on connect (Auto decides). v1.x.
- **Low end.** PixelPlayer asks for 6 GB RAM and Android 11. TMPlayer's floor is a 1 GB stick and Android 6.
  Budget: index queries paged, covers small, wavy bar animation off when paused or backgrounded, no
  per-frame state reads in lists (use the compose-performance checks and `compose-stability.conf`).
- **Desktop.** libmpv via mediamp plays everything we need; media keys through MPRIS (Linux) and SMTC
  (Windows, already worked on in 1.21.0); paths per OS for exports; keyboard (space, arrows, Ctrl+A to
  select all tracks).
- **Web.** Native `<audio>` plays MP3, AAC and Opus everywhere, FLAC on modern browsers including Safari;
  later, with the web layouts plan.
- **TV.** Skipped. A cast target later.

## 10. Edge cases checklist

Per AGENTS.md "Building a feature". Each needs a test or a device-pass line when built.

- Select all in Songs, in a chat, in a playlist; Ctrl+A on desktop; selection changes while work runs; empty
  selection; same action twice; play all of 20,000 (the queue is windowed, not 20,000 media items).
- Cancel at every step of Keep offline; disk full; permission revoked; file gone, restricted, still
  uploading or not allowed to be saved; a summary of what worked, what was skipped and why.
- Rotation, background, audio focus lost to another app, call, headset unplug, Bluetooth reconnect,
  notification permission denied, battery saver, the process killed and the queue restored.
- Tags: none, wrong encoding, 500-character titles, multi-disc, compilations ("Various Artists"), several
  artists in one field (configurable delimiters, as PixelPlayer does, default off to avoid splitting
  "AC/DC"), covers of 20 MB, non-square covers.
- A chat removed or left after indexing (its tracks become Not available, kept ones remain playable if on
  disk); an account with no audio at all (a helpful empty state, not a blank page); two accounts.
- A track posted in 3 chats (one entry, three sources); a forwarded track; an edited message that changes
  the file.
- Very long tracks: resume position, no gapless assumptions, sleep timer at the end of the track.
- Offline: play kept and cached tracks, show the rest dimmed, no error toast storms.
- Sleep timer with the screen off; timer set while the queue ends; "end of track" with repeat one.
- Idle CPU after the queue ends and after a long pause (measured, section 6.3).

## 11. Store, Telegram and wording risk

- **The honest risk.** Music posted in Telegram chats and channels is very often pirated. A music app that
  lists "everything in your chats" looks more like a piracy tool than a video player does, and music rights
  holders are more active than film ones. Past precedent in the search results: Grooveshark removed from
  Play (2011 and 2012), the Pirate Bay proxy apps removed (2014), and a 2026 developer thread where a music
  app was rejected for using artist names without proof of permission.
- **What lowers it.** Opt-in per chat (nothing scanned by default); no public channel search, no
  catalogue, no recommendations of what to listen to; no download-from-channel button that copies other
  people's posts; the library shows the user's own chats only; every store text says "audio files already
  in your own Telegram chats" and "unofficial, not affiliated with Telegram"; no artist or album names in
  listing text or screenshots (use public-domain or the user's own recordings, as the video screenshots use
  none); a visible legal page like tmplayer.org/legal with a takedown contact.
- **Telegram terms** (AGENTS.md): own `api_id`, no "Telegram" in the app title, no paper plane, sponsored
  messages still shown in channels, read state never faked, `MediaMapper` restrictions kept.
- **Stores.** Play: API 36 target requirement, foreground service type declaration, AAB, and a demo
  reviewers can use without a Telegram code (the video app's plan is set aside; same here). Amazon is Fire
  OS only. F-Droid is a good fit (no updater). GitHub keeps the updater. Do not rely on the store: plan for
  GitHub, F-Droid and Obtainium first, as the video app does.
- **Wording.** Public copy: "audio", "tracks", "music you already have in your chats". Not "free music",
  "download any song", "music channels". The i18n catalog gets its own `en.json`; other locales are the
  user's job (no LangSync run by me).
- **Developer verification** (AGENTS.md): the new package name and the same release key need registering
  alongside `com.tmplayer`.

## 12. Open questions (defaults chosen, change only if you disagree)

| # | Question | Default |
| --- | --- | --- |
| Q1 | Separate package or two icons in one APK | Separate package |
| Q2 | Name and package | "TMPlayer Music", `com.tmplayer.music`, name to check against trademarks |
| Q3 | Local device files as a source in v1 | No, v2 (Telegram first) |
| Q4 | LRCLIB on by default | Off, asked once on the first lyrics page |
| Q5 | Cache default | On, 2 GB (1 GB on weak devices) |
| Q6 | Voice notes in the library | Hidden, setting to include |
| Q7 | Dynamic colour from the cover | On, one extraction per cover, cached |
| Q8 | Daily Mix style rows | No; Recently played and Favourites only |
| Q9 | Where to host the site page | DECIDED (user, 2026-10-30): the home page is `music.tmplayer.org`. Built from `site/` so it shares the 21 languages and design rules (plan first, translate all before push); the web player lives in the web app as a Music mode, not on this host |

## 13. Build order (each phase ends with compile, unit tests, a commit, then a clean point to /clear)

Per the standing rule, the next session reads this section only. No phone testing unless the user lifts
"no test on the phone" (memory, 2026-10-28): compile, unit tests and render shots, phone checks listed as due.

| Phase | Work | Done when |
| --- | --- | --- |
| M0 | Decisions Q1 to Q9 confirmed; module skeleton (`:music` app and shared audio code in `:core`), package name, signing, CI | App launches to a signed-in empty shell |
| M1 | Chat picker, index, document and audio scan, tag reader with `GarbageInputTest` line, Songs and Chats lists, `SyntheticAccount` scale test | 100,000 tracks list in under 3 s from disk; idle CPU measured |
| M2 | Playback: media session service, queue, gapless, audio focus, notification, mini-player and Now Playing | Seek matrix for FLAC and MP3 passes (audio version of the video matrix) |
| M3 | Library tabs, playlists, favourites, smart lists, search | Edge cases in section 10 covered by tests |
| M4 | Cache policy, Keep offline, Free up space | Interruption and disk-full tests pass |
| M5 | Lyrics (tags, `.lrc`, page), then LRCLIB gateway and privacy text | Garbage and RTL tests pass |
| M6 | ReplayGain, sleep timer variants, widget, Android Auto | First 3 seconds test per track |
| M7 | Desktop build, then site page and store texts | Device pass on the real phone, then a pre-release (beta channel rule) |

### Progress / handoff

- 2026-10-30: research written (this file). Nothing built, nothing committed. Next: user answers or
  accepts the defaults in section 12, then M0.
- Gotchas found while researching: PixelPlayer is proprietary, so study its UX but copy no code or assets;
  FLAC arrives as documents, so a scan by Telegram's audio filter alone misses lossless; ReplayGain and
  gapless fight in Media3 (issue 418).

## 14. Additions agreed 2026-10-30

1. Saved Messages is pre-selected in the chat picker (people forward music to themselves).
2. New audio posted in a chosen chat shows in Recently added on its own (live update, no rescan).
3. A separate app means a second TDLib database and file cache: show it on the storage screen, and look
   at reusing the video app's login or files later.
4. "Open in the other app": audio shared from TMPlayer opens here, videos open back in TMPlayer.
5. Tracks without art get a generated placeholder (colour plus initials). No internet artwork by default.
6. Locale-aware sorting, accent-insensitive search, legacy tag encodings, non-Latin and right to left
   lyrics (an open PixelPlayer request asks for Georgian and Armenian latinisation and for artists named in
   several languages, so the need is real).
7. Accessibility: TalkBack labels, large text, a non-gesture way for every swipe.
8. A short first-run tour, same rules as the video app's.
9. An audio test matrix beside `PLAYBACK-TEST-MATRIX.md`: FLAC with and without a seek table, MP3 with and
   without a LAME header, M4A with the index at the end, hi-res 24-bit and 32-bit float, a 1 GB WAV, a
   3 hour mix, and a device pass.
10. Release plumbing: site page, README, changelog, update feed, privacy page, Play data-safety answers.
11. Not doing: analytics, accounts of our own, ads.

## 15. Edge cases mined from PixelPlayer's issue tracker (200 issues read, 2026-10-30)

Real failures other users hit in a mature player. Each is a test or a rule for us.

**Library and index**
- ALAC and M4A hidden because the tag duration was wrong (Samsung) or because "minimum song duration"
  filtered them. Rule: a missing or zero duration means unknown, never hidden; the short-clip filter uses
  the decoded duration, and only when known.
- New downloaded music not showing, "songs won't show up", sync errors, "telegram sync doesn't work".
  Rule: a visible scan status (counting, last synced, errors per chat), a manual refresh, and a retry.
- Albums with the same name split in two, and tracks of one album by different artists not grouped. Rule:
  group by album artist, album and year, with a compilation rule ("Various Artists") and a way to merge.
- Titles with diacritics broken (a, a, i) and tag details updating late. Rule: detect encodings; changed
  tags and changed covers invalidate the cache and never crash it.
- A deleted track still counted in Top albums, and cloud tracks still listed after disconnecting. Rule:
  leaving a chat, logging out or losing a file purges its rows and its stats.
- `.mka` covers missing, WMA unsupported. Rule: say which formats play; show "can't play this format"
  rather than a silent skip.

**Playback**
- "Source error" toasts, songs stuck, no audio when the next track starts by itself, silence after the
  auto transition, music starting unexpectedly, not resuming in the car. Rule: a transition test in the
  matrix (100 gapless tracks, FLAC to MP3 to M4A, different sample rates), never autoplay on connect.
- Hi-res files that will not play, 32-bit float with 16-bit files, large files crashing on some phones
  (Pixel 9a, Android 17). Rule: hi-res and 1 GB files are streamed, never loaded whole; resample or fall
  back to a safe path when the device cannot take a rate.
- ReplayGain turns off after a seek; playback too loud. Rule: gain lives on the track, not on the
  position; a test that seeks.
- Crossfade and the equaliser interfering. Rule: crossfade is later, and only after the EQ is stable.
- Progress bar frozen in mini mode, shuffle that misbehaves when switched back on, a queue truncated
  after a restart. Rule: the queue is saved whole, restored whole, and shuffle keeps the current track.
- Wanted: sleep timer, fade in and out on pause, multiple queues, balance, pause at zero volume. The sleep
  timer is already v1; fades are cheap and worth adding.

**Lyrics**
- Highlight 1 to 2 seconds late, out of sync, scroll glitches, special characters wrong. Rule: drive the
  highlight from the player clock with an audio latency allowance (Bluetooth adds more), plus the per-track
  offset control from section 8.
- Header lines in lyrics (`[ar:]`, credits), a big "no lyrics" dialog, leaving the lyrics page when the next
  track has none, no reload button, no way to clear or strip timestamps. Rules: strip headers on parse; a
  subtle "no lyrics" mark instead of a dialog; stay on the lyrics page; Reload, Clear, Remove timestamps;
  keep the screen awake option on that page.
- "Save lyrics into the file tags" cannot work for us (the file lives in a chat): lyrics are saved in our own
  index and are exported with the backup.

**App behaviour**
- Crashes when returning to the foreground (predictive back), the player not adapting on rotation,
  foldables, scroll position lost when leaving an album, animation stutter. Rule: rotation and foldable runs
  in every device pass; keep scroll state per list.
- A switch to turn off the media notification, lyrics in the notification, lyrics in Android Auto. Later.
- Someone reported a "possible fraudulent app" using their name on Play. Rule: register the name, keep
  one official link everywhere, and keep the legal page current.
- Common asks we should decide on later: Last.fm scrobbling, share-as-card, widgets, album year sorting,
  folder view, custom tab order, hide a song inside a playlist.

## 16. Decisions log

- 2026-10-30, user: home page at `music.tmplayer.org`. Hands-on items for the user, not done by Claude yet:
  add the DNS record and the domain in the site's Vercel project when the page exists, and approve before
  anything goes public. Until then the app name stays "TMPlayer Music" (trademark check pending).
- 2026-10-30, user: do not clone PixelPlayer; study its behaviour, write our own (section 3.4).
- 2026-10-30, user: phone first, then desktop, then web as a mode of the existing Solid web app.

## 17. TV app and casting (user asked 2026-10-30, reverses "TV skipped" and "no casting in v1")

Decision: yes to both, after the phone and desktop apps. Two separate features:

1. **TV app** (Android TV and Fire TV): the same app family, a lean-back layout: big cover, lyrics
   full screen, queue as a row, D-pad focus walked like the video TV bar, and a screen that does not burn in
   (slow drift, dim after idle). It signs in with the QR flow the video TV app already has and streams from
   Telegram itself. Idle CPU measured on the 1 GB stick (AGENTS.md low-end rule).
2. **Remote and hand-off between our own apps** (the Spotify Connect model), first:
   - "Play on TV": phone sends the queue, the current track and position; the TV app streams from Telegram
     on its own, so the phone can lock or leave. "Bring back to phone" reverses it.
   - "Control from phone": play, pause, seek, next, volume, queue edit, lyrics follow on both screens.
   - Discovery on the same Wi-Fi (NSD/mDNS), pairing with a short code or QR, a token on every command so
     another device on the network cannot drive the TV. Guest or isolated Wi-Fi: say so plainly.
   - Needs the same Telegram account on both; a different account on the TV shows a clear message.
3. **Google Cast and DLNA to speakers and other receivers**, later: the phone serves the bytes over a local
   HTTP server with a per-session token, which is where PixelPlayer's cast tests point (session security,
   codec detection for M4A). Needs Google's Cast receiver registration. Not v1 of the remote.

Edge cases: connection lost mid-track, two phones controlling one TV, TV asleep or off, TV out of space or
storage cache off, volume owned by the TV, a track the TV cannot decode (fall back to its software
decoders), queue changing while handing off, handing back with a different position, battery on the phone,
and the remote never revealing chat names to a stranger on the network.

## Sources

- PixelPlayer README and screenshots: github.com/PixelPlayerHQ/PixelPlayer (read 2026-10-30, licence
  and requirements from its README).
- Gramophone: f-droid.org/en/packages/org.akanework.gramophone/
- Booming: f-droid.org/en/packages/com.mardous.booming
- Local Player: f-droid.org/en/packages/com.cvc953.localplayer/
- Auxio: github.com/OxygenCobalt/Auxio
- Media3 gapless and ReplayGain issue: github.com/androidx/media/issues/418
- Media3 playback guide: developer.android.com/media/implement/playback-app
- Android Auto media playback: developer.android.com/training/cars/media/enable-playback
- LRCLIB API: lrclib.net/docs
- TDLib `SearchMessagesFilter`: core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1_search_messages_filter.html
- Namida: alternativeto.net/software/namida/?p=3 ; Metrolist: alternativeto.net/software/metrolist/
- Copyright precedents: androidpolice.com (Apollo, 2012), venturebeat.com (Grooveshark), secnews.gr
  (Pirate Bay apps).
- Poweramp, Musicolet, Symfonium rows: blog roundups (beebom.com, linuxlinks.com, apkmirror.com,
  support.symfonium.app); unverified.
