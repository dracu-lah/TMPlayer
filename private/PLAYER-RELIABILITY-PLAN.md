# Player reliability plan (user, 2026-11-15)

Why: users on weak or odd chipsets (a Tanix TX6, Allwinner H6, "Automatic did not work") get a frozen,
blocky or stuttering picture in the built-in player, while VLC or MX Player play the same video fine.
We cannot yet say why on other devices either (stutter cause unknown), titles from real chats do not
parse, and Sentry is opt-in so we see few bugs. This plan fixes the first, finds the second, and makes
the third and fourth visible. Research only so far: no code is changed.

Rules this plan follows: AGENTS.md (edge cases, TV is a platform, machine checks first, no em or en
dashes, "video" in public copy), the global LOCKED rules (user runs releases and publishing), and the
user's rule that time and tokens are not a cost, only what would be missing is.

## Progress / handoff (updated 2026-11-15, session 860e0983, read this first)
All of this is on local `main` (about 83 commits since `ae4dd0a`, NOT pushed, no tags). Section-wise commits, no
Co-Authored-By trailer. Run builds with `JAVA_HOME=/home/dracu/.jdks/jdk-21.0.12+8` (the shell default is
JDK 25 and fails), `--max-workers=3`, and a `timeout` on test runs.

Done (commit): L1 `83b1aff`, L2 `8a191b5`, L3 `2723888`, L4 core `8e9836c` + app `22d70b6`, L5 bundle
`a0653a3` + upload `587047c` (Worker NOT deployed, button hidden until `LOG_UPLOAD_URL` is set), L6 `c29b279`;
PR1 + PR3 core `b7a9a84`; PR2 `11b0440` (libVLC off by default, build with `-PtmVlc=true`, never run on a device),
PR2 spike `9ac77dd`, PR4 `1222b1f` + subtitles `6ddb764`, PR5 `8b4984a` + follow-ups `9ce7c69`, PR6 `dd88a1a`,
PR7 `6af0218`, PR8 + U13 `291fc24` + `463c0ff`; S1 `1e3c30d`, S2 `f544d6d` + wiring `831395d`, S3 `270aa8d`;
N1 `1d7c7a7` + `3cf01fb` + `69d9ef8`, N2 + N3 core `72c2512`, N3 wiring `b188dac`; U1 `7772ac2`, U2 `da48b74`,
U3 `441a9c6` (TV), U4 (already present, verified), U5 `cf9e77e`, U6 `0036436`, U9 `c2fd384`, U10 `732e4da`,
U11 `37e503e` (Repeat one not done), U12 `70f107c` + `08766b0` + `792febb`, U14 `d8ac0b9`, U15 `0350d88`,
U16 `992804d`, U18 `2706c61` + `c3fca3d` + `9896acc`. Two flaky tests fixed (`f61d66e`, `27068fd`) and two
slow scrubbers fixed (`b239c39` title report, `496ed90` log scrubber: 89 s and minutes down to milliseconds).

Also done after the first list (all on local main, none pushed): U7 resume on any device (`29bd391` core,
transport, settings rows, privacy section; pinned document in Saved Messages edited in place), U8 mini player
(desktop `a31689b`; phone: design `docs/mini-player-phone.md`, the player opens in its own task and docks as an
audio-only bar through the existing PlaybackService handover, setting "Keep playing in a mini player" ON by
default: turn it off first if phone playback or Back acts strangely), Repeat one for audio (`ccd07cb`), the archive
reason in the player (`eca5eb3`), U20 settings audit (`docs/settings-audit.md`: one page order, SettingsMap,
search box on phone and desktop, link test, "Suggest VLC" removed), U19 UX audit and U17 first large-screen pass
(`docs/ux-audit.md`, shots in `design/ux-audit/`: icon fixes, Cancel focused on Send to developer, 48 px web touch
areas, Settings centred at 720 dp on tablets, web build fixed), test fixes (`02f4696` Sentry-dependent TV settings
test, SettingsMap row for the mini player).

Not done and why: web app (Play random, filters, rename, sync, Sort menu items); U5 on VLC; search twin grouping;
desktop sign-out trace cleanup; "settings in use" in the log bundle; tablet Chats, Downloads and Search layouts
(no two-pane); widget text at 200 percent font; "Channels . N subscribers" plural label (needs a string in all 22
catalogs); the Sort menu closes after every pick; unused-key cleanup (283 candidate keys in `docs/ux-audit.md`,
all 22 catalogs at once: yours); candidates to remove from the audits (update check next to the update
notification, subtitle and details cache clears, the unreachable Trickplay row, the stats overlay).

Known-flaky only under load (pass alone): `DlnaFlowTest`, `AddChatsTvKeysTest` (2 cases). Run each Gradle
invocation to its own log file: parallel runs from several worktrees share ports.

For the USER (nothing here was done by Claude): deploy the log inbox Worker (`workers/log-inbox/`, wrangler),
set `LOG_UPLOAD_URL`, decide Sentry opt-in vs opt-out (`docs/distribution/crash-consent.md`), check the public
site FAQ title "or the VLC engine" before deploying the site (libVLC is not in released builds), Play and
F-Droid review of libVLC (`docs/distribution/libvlc-spike.md`), the other 21 translations, every release step,
and the device tests below.

Device tests still to do (nothing was run on a device): TV idle CPU and memory with the trace sampler; the
seek matrix with the loopback stream; VLC and MX handover (intent extras are marked unverified); the chooser
focus on TV; tunneled playback; the stats overlay; the Tanix TX6 itself; libVLC (a build with `-PtmVlc=true`).
- Order that was used: pure core first, then player wiring, then settings and logs, then search and filters.

Remedy ladder wiring (2026-11-16, research RL4, RL5, RL6, RL8, RL9; local commits, not pushed):
- RL4 `13223e0`, RL5 `dc931f6`, RL6 + RL8 `ce86660` (merged in `e42f689`). Review fixes `674503a`: the tick stops
  once the path played 60 s clean (a seek watches 15 s again, background audio posts nothing, the picture coming
  back restarts it); a re-open without a session ends in the sheet instead of a lost hop; the memory is written
  through one conflated writer (`LatestWriter`).
- RL9 `22f745d` (HX79): the index start learned from the head (`StreamWarmup` via `StreamIndex.locate`, or the
  stream's own first far read via `TdByteWindow.learnIndex`) makes a read anywhere in it an index read, held beside
  the window (`LowStorageWindow.counted`, up to `MAX_INDEX_BYTES` 32 MB). `LowStorageIndexTest` (12 MB moov after a
  head played before) failed first, passes now. Not done in RL9: `setContinueLoadingCheckIntervalBytes` (needs the
  1 GB stick measurement first) and the head-to-tail window restart count. HX79 itself is still a hypothesis: confirm
  on the film with the "Low storage: deleting file" logcat line.
- Not done: A2 passthrough off; the real-ExoPlayer fake adapter test (7.7, needs media3-test-utils); idle CPU on the
  stick; a real frozen decoder (RK356x, Amlogic, TX6). New strings: `player.remedy_*` (6, en.json only).

## Findings (sources in chat 2026-11-15, all anecdotal, not benchmarks)
- Android uses Media3 ExoPlayer 1.11.1, video on the hardware decoder, NextLib FFmpeg for audio only
  (`docs/ARCHITECTURE.md:19`). Desktop already uses mpv (mediamp). Web uses the browser.
- Many Plex and Jellyfin users report ExoPlayer choppy where external MX or libVLC is fine; others say
  libVLC causes the most problems. No controlled comparison exists.
- Existing: `ExternalPlayer.kt` hands over a FILE only (fully downloaded). `CastServer` (core `cast/`) is a
  token + Range HTTP server over the TDLib window, built for Cast and DLNA. `StutterReport` already
  carries codec, decoder, hardware, dropped, frames, underruns, prediction. Sentry is opt-in with a
  scrubber and per-device daily limits (`CrashLimits`). `LogExport` saves 2000 scrubbed logcat lines.
  Rename (`rename_*` strings) exists. `private/name-survey/` holds real title shapes.
- Last player commit: four stutter reports had a hardware decoder, no drops, no underruns. The cause
  there is NOT the decoder being slow, so measure before swapping engines.

- 2026-11-16 (user, first hand, PR0 data point): a feature film streamed from a chat on a connected device
  stuttered; the user is downloading the same file to compare with VLC. Open: which device (phone or TV),
  streamed or downloaded copy, the file's container, codec, profile, resolution, fps and bitrate (`ffprobe`),
  whether the stutter is steady or only after a seek, and what VLC does on the same device. Collect with the
  PR0 steps (logcat, `dumpsys media.codec`, frame drops) before changing the engine selector.

- 2026-11-16 (user): the same film does not play at all with Low storage mode on, on the phone AND the TV (so it is the
  shared mode logic, not one device's decoder; nothing starts, no stutter). Not diagnosed. The window is clamped (`LowStorageWindow.windowBytes`, never 0), so look first at
  `startLowStorageIfOn` / `LowStorageStreams.allow` in `PlayerActivity` (a hold that is never granted, a
  `downloadFile` limit that TDLib reads wrong, the rolling delete racing the first reads), then logcat with the
  mode on and off on the same file. Also: libVLC (PR2 `11b0440`) is in the code but off unless built with
  `-PtmVlc=true`, so the debug APK installed on 2026-11-16 has no VLC engine to fall back to.

- 2026-11-16 (T4 worker, HX79 hypothesis for the Low storage mode no-start, unconfirmed): the mode's window is 200 MB plus 16 MB slack
  and only the last 8 MB counts as an index read (`INDEX_TAIL_BYTES`). A film whose moov or Cues is larger than
  that has its tail read treated as a seek; that plan deletes the head, the next head read deletes the tail, so it
  keeps deleting and nothing starts. Confirm with the film's index size (`ffprobe -v trace`, moov size) and the
  logcat line "Low storage: deleting file". RL9 (MP4 index in memory) must include a test for an index bigger than 8 MB.

## Decisions made by Claude (overrule in the PR)
- Keep ExoPlayer the default; add libVLC as a SECOND in-app engine, not a replacement (a full swap loses
  tunneling, HDR and Dolby Vision output, audio passthrough, frame-rate matching and the stutter
  diagnostics).
- External player option first (small, helps at once), libVLC engine second.
- Sentry: keep CONSENT as the basis, but ask once at first run with a clear card (see S1). Not a silent
  default-on. Reason: the privacy page and CP01 name consent as the basis, F-Droid treats default
  tracking as an anti-feature, Play Data safety must match, and a silent switch would need a legal
  basis change the user should confirm. The user may overrule to opt-out (S1 lists what changes).
- Log upload (L5) is per send, shown before it leaves, never automatic.
- Title reports (N1) are per title, shown before they leave, off by default.

## Decisions for the user (a default is used until answered)
- Sentry default: first-run ask (default) or opt-out default-on. Not legal advice; check the privacy
  page change with whoever you trust for GDPR.
- Log upload endpoint: a Cloudflare Worker + private R2 (default) or only share/save files.
- libVLC in the F-Droid flavor: leave it out (default) or build from source.

---

## Tasks

### PR0 Evidence first: why did Automatic fail, what do these devices report
model: opus high   depends: none   files: `app/.../player/PlayerActivity.kt`, `PlayerErrorSheet.kt`,
`core/.../data/StutterReport.kt`, `scripts/` (new `devices` note)
- Ask the Tanix TX6 reporter (and the others who use VLC) for Send logs, chipset, codec, whether the
  video was streamed or downloaded, and the same file played from a full download.
- Find why "Automatic" never fell back to the app's software video: a decoder that starts and then draws
  green, blocky or frozen frames throws nothing, so ExoPlayer cannot fall back. Check
  `setEnableDecoderFallback`, the NextLib video renderer path, and whether a frozen-frame watcher exists.
- Fix candidates, cheapest first: a frozen or garbage frame watcher that offers (not forces) the other
  decoder; a known-bad chipset list (PR1); then the VLC engine (PR2).
- Done when: for the Tanix class of device we know whether the picture is a decoder fault, a software
  decoder too slow, or the stream; written in this file under Findings.
- Edge cases: a device with no log (ask for the L5 report once it ships); a video that stutters only when
  streamed (then it is I/O, not the decoder).

### PR1 Engine selector and known-bad device list
model: opus high   depends: PR0   files: new `core/.../player/EngineSelector.kt` and test,
`core/.../data/KnownDevices.kt`, `GarbageInputTest.kt`
- Pure class: inputs (user choice, device facts, failure history for this device, video facts, 3D flag),
  output (engine, reason). A decision-table test, including: never switch back and forth, one automatic
  switch per video, user choice wins, 3D stays on the device decoder, unknown stored value means Automatic.
- Known-bad list (chipset, model, Android versions, what to prefer) shipped in the app and refreshed from
  the update feed. The feed is untrusted: a bad entry is skipped, the rest kept, no `!!`, a garbage test.
- Done when: the table test passes and the list parser survives garbage.
- Edge cases: a device on the list that plays fine (user can pick ExoPlayer); a list entry too broad;
  a stale list when offline (use the shipped one).

### PR2 libVLC as a second in-app engine (phone and TV)
model: opus high (design), sonnet high (build)   depends: PR1, PR3 spikes   files: `app/.../player/`
(new `Engine` interface + `ExoEngine`, `VlcEngine`), `PlayerActivity.kt`, `player_controls.xml`,
`gradle/libs.versions.toml`, `docs/ARCHITECTURE.md`
- Spikes first, answered in this file: libVLC minimum API against the Android 6 floor, 16 KB page size,
  HDR and Dolby Vision behaviour, custom input callbacks in the Java binding (else the loopback server),
  APK size per ABI, LGPL relinking notice.
- One engine interface behind the existing overlay: prepare, play, pause, seek, tracks (audio, subtitle),
  speed, aspect, volume, PiP surface, media session, stats. The overlay, subtitles, progress, next-up card,
  skip pill and TV focus stay ours.
- Only one engine loaded at a time; release fully before switching (1 GB stick). Lazy load: never at app
  start. Switching keeps position, track languages, speed, volume.
- Missing native library (wrong ABI, 32-bit TV, UnsatisfiedLinkError): caught, option hidden, ExoPlayer used.
- ExoPlayer-only features stay: tunneling, HDR output, passthrough, frame-rate matching, async MediaCodec.
- Tests: one engine contract test run against both adapters; D-pad focus tests per engine
  (`performKeyInput`, `assertIsFocused()`); preview screenshots per form factor; seek matrix (ARCHITECTURE
  "Seek matrix") on the 1 GB stick; idle CPU on the TV via `/proc/<pid>/stat`.
- Edge cases: rotation and recreation (surface re-attach), PiP, background audio, audio focus, Bluetooth
  delay, subtitles in non-Latin and right to left scripts, embedded and external subtitles, 3D, HDR toggle,
  a video that ExoPlayer plays and VLC does not (offer to switch back), both fail (status sheet with Try
  again, Send logs, Open in another app).
- Caveats: bigger APK for everyone (variant or on-demand only if store rules allow); unverified claims
  above must be checked first.

### PR2b More in-app engines behind the same `Engine` interface (user, 2026-11-16)
model: opus high (spikes, design), sonnet high (build)   depends: PR2 (interface), PR1   files: `app/.../player/`
(new `MpvEngine`, `ExoSoftwareEngine`), `gradle/libs.versions.toml`, `docs/ARCHITECTURE.md`, settings rows
User: "add more internal FOSS players so users can test what works for them and adapt." Candidates, in the
order to build them (each is a spike first, answered here, then a build only if it passes):
1. Media3 with the FFmpeg software video decoder (extension, same engine and UI, no second player): the cheap
   win for video the hardware decoder rejects. Today FFmpeg (NextLib) is audio only (ARCHITECTURE.md:19).
2. libVLC (PR2, built, off, never run on a device): turn it on, test on phone and TV.
3. libmpv on Android (desktop already uses mpv through mediamp, so the Kotlin side exists): spike a binding
   (mediamp's Android backend or mpv-android's libmpv), HDR, subtitles (libass), seek speed, APK size per ABI.
Not taken: IJKPlayer (ffplay based, little maintenance, no security updates), GStreamer (large, poor fit).
- Spike answers for each: licence and what it means for Play, F-Droid and the sideload APK (LGPL relinking,
  no GPL build), APK size per ABI, minimum API and 16 KB pages, 1 GB TV stick memory, start time, seek time.
- Product: Settings "Player engine": Automatic (default) or a named engine; in the player's More sheet
  "Try another player" switches the same video at the same position (one engine loaded at a time), remembers
  the choice for this video and, after two successes, offers it for the device (the known-bad list, PR1).
  Plain words for Raju: "Not playing well? Try another player." Engine names only in Settings.
- Size: native engines are optional modules where the store allows (Play feature module), else one APK with
  a lazy load; the preview and debug builds include all (`-PtmVlc=true` today).
- Edge: a missing native library (wrong ABI, 32-bit TV) hides that engine, never crashes; low storage mode
  works through the loopback server (PR3) for every engine, with a test that a rolling window starts.

### PR3 Loopback stream server with a read arbiter
model: opus high   depends: none   files: `core/.../cast/CastServer.kt` (reuse), new
`core/.../player/LoopbackStream.kt`, `app/.../TdDataSource.kt` (path to confirm), tests
- A second CastServer instance bound to 127.0.0.1 only, random token per session, closed when playback
  ends. The Cast server keeps its LAN-only filter, never widened.
- One arbiter in front of TDLib: VLC and MX open several connections (index at the end, then the body) but
  TDLib keeps ONE active download per file and the last request wins. Serialise or coalesce reads so two
  readers do not thrash the window.
- Fully downloaded file: serve the file directly, no TDLib window.
- Tests: concurrent readers, every Range form (open ended, suffix, past the end 416, HEAD), files over 4 GB
  (Long everywhere), idle close and reopen after a long pause, port in use, token reuse refused.
- Edge cases: network cut (VLC may give up sooner than ExoPlayer), FLOOD_WAIT waited out by the call that got
  it, file deleted from the chat mid-play, disk full, cleartext to localhost on Android 9 and later.

### PR4 "Open in another app" as a default player (VLC, MX Player)
model: sonnet medium   depends: PR3   files: `app/.../player/ExternalPlayer.kt`, settings screens,
`en.json`, tests
- Setting "Default player" (user, 2026-11-15): TMPlayer (default), Ask each time, or one installed app. The
  list is every installed app that takes `ACTION_VIEW` for `video/*` (asked with `queryIntentActivities`,
  with a `<queries>` entry for it, never `QUERY_ALL_PACKAGES`), each with its icon and name, VLC and MX Player
  first (`org.videolan.vlc`, `com.mxtech.videoplayer.ad`, `.pro`), then the rest (Just Player, mpv-android,
  Kodi, a TV box's own player). Explicit intents only, to the component the viewer picked.
- First use: when the viewer opens "Open in another app" (or the first play with a non-default choice), our
  own chooser sheet lists those apps plus "TMPlayer", with two buttons, Always and Just once, like Android's
  chooser but ours, so we can remember the pick and stream to it (PR3). Settings > Playback > Default player
  shows the pick, with Change and Reset to Ask. Phone and TV (D-pad focus, a TV list that scrolls); the
  desktop offers its installed players the same way (VLC, mpv, system default) through the OS.
- A way back is always shown: with an external default, the video menu and the TV hold-OK keep "Play in
  TMPlayer", and the first time the default is used a one-line hint says where it is. Never lock the viewer
  into an app.
- Pass what the app can take: title, the saved resume position, the subtitle file, per app (VLC and MX read
  `position`, `title` and subtitle extras; names unverified, check on a real install).
- Edge cases: the external app closes within about 2 s without playing (offer the built-in player), the
  default app is uninstalled or updated to a version without video support (ask again), two default choices
  after a restore from backup (re-validate against what is installed), a Cast session running, kids lock
  (the lock stays in force: only TMPlayer's own player until the viewer's lock allows it).
- A video that is not fully downloaded streams through PR3 instead of the "all here" refusal
  (`open_elsewhere_partial`, `handoff_nothing`).
- A foreground service while the stream is open (as `DlnaCastService` does), or Android kills TMPlayer on a
  low-memory TV and the stream dies.
- The result position comes back only when the app returns one; treat it as untrusted.
- Edge cases: the app is uninstalled later (fall back to built-in, say so once), subtitles only by each app's
  own intent extras, no autoplay of the next episode (say so), TV focus returns to the row, a Cast session
  running at the same time.
- Desktop: "open in external player" through the OS is optional (mpv is built in); web: not applicable.

### PR5 Settings and in-player toggles (every one reachable by the D-pad)
model: sonnet medium   depends: PR1   files: settings, `PlayerActivity.kt`, `en.json`
- Settings > Playback: Player engine (Automatic, ExoPlayer, VLC), Open videos in another app (PR4), Switch
  engine on decoder error (on, or only suggest), Suggest VLC when playback cannot keep up (on), Prefer
  software video (exists).
- Advanced (collapsed): Tunneled playback (Auto, On, Off, TV), Frame-rate matching (per device), Audio
  passthrough (per device), Async MediaCodec mode (off), Playback stats overlay (off).
- In the player menu: "Try the other engine" for this video (next to "Try the other decoder"); on a failure
  card, "Always use VLC on this device".
- New strings in `en.json`, keys sorted; the other 21 catalogs are the user's (no langsync).
- Edge cases: a stored value from a newer build (unknown, use the default), backups and restore, a toggle
  that does not apply to this device (hide, not grey).

### PR6 Native crash isolation
model: sonnet high   depends: PR2   files: `app/.../data/CrashExits.kt`, `CrashReports.kt`, startup safe mode
- libVLC crashes natively with no Java exception. If the app dies while the VLC engine was playing, record
  it; Automatic then stops choosing VLC on that device. Startup safe mode counts these crashes too.
- Edge cases: a crash loop from a bad video (not from the engine), a tombstone with no stack, the user who
  forced VLC (their choice stays, but a card offers ExoPlayer after two crashes).

### PR7 Distribution and legal checks
model: opus high (reading rules), haiku (notices)   depends: PR2 spikes   files: `THIRD_PARTY_NOTICES.md`,
`docs/distribution/`, `docs/agents/telegram-terms.md`, F-Droid and Play notes
- F-Droid may refuse a prebuilt libVLC binary: leave it out of that flavor or build from source.
- Play: 16 KB native alignment, the Data safety form after S1, and the policy on downloaded code.
- LGPL: notices, relinking, source offer. Telegram's terms check for the loopback handoff.
- Done when: each store and package manager row has a written yes, no or action here.

### PR8 Parity, docs and site
model: haiku low   depends: PR2   files: `docs/ARCHITECTURE.md`, `docs/BUILDING.md`, `site/`
- Platform table in ARCHITECTURE: phone, TV (both), desktop (mpv), web (browser), Tizen (not applicable).
- Site FAQ "Video stutters? Try the VLC engine" and the store text; "video", never "movie" in public copy.

### PR9 Rollout
model: sonnet low   depends: PR2 to PR8   files: `docs/RELEASING.md`
- A `-beta.N` first (pre-release before every stable). Release cadence guard before any release; the user
  runs tags and releases. Reply to the reporters and close their issues once on main ("next release").

---

## Seeing the stutter (logs)

### L1 Playback session recorder
model: opus high   depends: none   files: new `core/.../player/PlaybackTrace.kt` and test, hooks in
`PlayerActivity.kt`, `TdDataSource.kt`, `StutterReport.kt`
- Every 5 s (2 s in a beta build) and on events, one line: dropped frames since last, underruns, buffer
  ahead (ms), bitrate, decoder name and hardware flag, TdDataSource read wait (ms blocked waiting for TDLib
  bytes, reopen and seek count), TDLib download speed, process CPU share, memory and trim-memory level,
  thermal status (API 29+), battery saver, display refresh rate and mode, surface type, audio path
  (passthrough, offload, Bluetooth latency), engine.
- The read wait splits "the network or Telegram was slow" from "the decoder was slow": the question that has
  no answer today.
- Kept in a bounded ring on disk (last 5 sessions, size capped), plain JSON lines, a garbage test for the
  reader.
- Edge cases: a session killed mid-way (file stays valid), a low-end device (cost of the sampler measured on
  the 1 GB stick, stops with the player), sign-out and clear data remove it, a huge session (ring caps).

### L2 End-of-playback verdict
model: sonnet high   depends: L1   files: `StutterReport.kt`, `PlayerErrorSheet.kt`
- Summarise each session: decoder bound, network bound (read waits, underruns), render or sync, thermal,
  memory. Feeds the existing `prediction` and the could-not-keep-up card. A table test with fixed traces.

### L3 "Report stutter" in the player
model: sonnet medium   depends: L1   files: player menu, `en.json`
- A menu item and a TV key hold: marks the moment and keeps the last 60 s of trace, so a report lines up with
  what the viewer felt. Edge cases: pressed twice, during a seek, on a cast session (not applicable).

### L4 Detail levels and a persistent log file
model: sonnet medium   depends: none   files: `core/.../platform/Logger.kt`, `LogExport.kt`, settings
- Logger has two levels today and logcat holds only minutes. Add a bounded rotating app log file (about 2 MB
  x 2) that survives until sign-out. Beta and preview builds log more and sample L1 at the faster rate;
  stable keeps the summary only. Settings > Advanced "Keep detailed logs" (on in beta, off in stable).
- The file is local, holds names (the export scrubs them), and is covered by "Delete your data".
- Edge cases: full disk (stop writing, never crash), concurrent writers, a rotated file mid-export.

### L5 A better Send logs
model: opus high   depends: L1, L4   files: `LogActions.kt`, `LogExport.kt`, `desktop/.../SendLogs.kt`, new
`workers/log-inbox/` (Worker + private R2), `site/privacy/index.html`
- One bundle: scrubbed app log, the last playback sessions, the earlier run's crash, device facts, the
  decoders the device lists for this video's codec (`MediaCodecList`: names, hardware or software, max size
  and rate), engine and settings in use.
- Phone: share sheet (as today). TV: Download folder (as today) PLUS "Send to developer": uploads the bundle
  and shows a short code (TMP-xxxx) to say in the group or an issue. The send screen shows what is included,
  size, and asks first. Offline: saves the file and says where.
- The Worker: size cap, content type check, rate limit per address, 30 days retention, no logging of the
  address, private bucket. The user deploys it (wrangler is a user-only action).
- Privacy page lists exactly this. Garbage test for the bundle reader.
- Edge cases: upload cut off, double tap, a very old report, a bundle over the cap (drop the oldest
  sessions first), a revoked storage permission, a report with a video name in it (the scrubber test uses
  the known-names list).

### L6 "Playback test" (decoder or network?)
model: sonnet high   depends: L1   files: Settings > Help, `en.json`
- Plays the first 30 s of a video from disk, muted, with the whole window downloaded first so the network is
  out of the picture, and reports dropped frames, decoder and a verdict. If it is clean, the stutter is
  streaming; if not, it is the decoder or render path. The result goes into Send logs.
- Edge cases: the video is not downloaded (offer to fetch the first 30 MB, show space needed and free),
  cancel, a TV with no keyboard, running while another video plays (refused).

---

## Seeing bugs (Sentry)

### S1 Ask once at first run (default), or opt-out
model: opus high   depends: none   files: `CrashReports.kt`, onboarding, `site/privacy/index.html`,
`site/terms` (path to confirm), `en.json`, Play Data safety notes
- Default plan: a one-time card after onboarding, not blocking, "Help fix crashes? Sends only the error and
  device model, never titles or chats", Yes focused on TV, a Settings toggle, and the existing per-crash ask
  for those who said no. Consent stays the basis (CP01).
- If the user picks opt-out default-on instead: the privacy page basis moves to legitimate interest with an
  easy opt-out, the first-run notice says so, Play Data safety changes, and F-Droid gets it OFF by default.
- Sentry quota: the free plan takes 5,000 errors a month for every device; `CrashLimits` already caps per
  device per day. Re-check the numbers once more users opt in, and prefer the daily cap to a bigger plan.
- Edge cases: a returning user who earlier said no (do not ask again), a fork or CI build with no DSN
  (nothing starts), a child or shared TV (a clear card), a language with a long text (focus and wrap).

### S2 Better crash context without private data
model: sonnet high   depends: S1   files: `CrashReports.kt`, `CrashReportScrubber.kt` and tests
- Add the fields that explain player bugs and nothing else: engine, decoder name, chipset, codec and size
  class, the L2 verdict. All of them stay in the allowed-fields test (CP01), no titles, no chat names.
- Edge cases: an SDK update that adds a field (the test fails closed), ANR and tombstones (existing rules).

### S3 What else could spot bugs (alternatives)
model: haiku low   depends: none
- Android vitals in the Play Console (ANR and crash rates by device, free, no SDK).
- The L5 upload is the cheapest way to see a single user's trace; Sentry is for crashes.
- A weekly list of top stutter reports by chipset from the opted-in `StutterReport` events.

---

## Titles that look wrong

### N1 A way for users to send a bad title
model: sonnet medium   depends: none   files: the Rename sheet (`rename_*` strings), new
`core/.../data/TitleReport.kt`, `workers/log-inbox/` (same Worker), `site/privacy/index.html`, `en.json`
- After Rename, or from the video menu "Wrong title?": a sheet showing exactly what is sent (the original
  file name, the title they chose) with a checkbox, off by default, and a send button. Only that pair; the
  chat name goes only if they tick it. Offline: queue and send later, or drop.
- Edge cases: a name with a phone number or a @username (the scrubber runs first, the user sees the result),
  the same title reported twice (dedupe by hash), a rename that is a pure typo fix, TV (a short code to read
  instead of typing).

### N2 Corpus and a tool that turns reports into tests
model: sonnet high   depends: N1   files: `core/src/jvmTest/resources/names/corpus.tsv` (new),
`MediaNameTest.kt`, `scripts/names/ingest.mjs` (new), `private/name-survey/` (seed)
- A golden file: raw name, expected title, year, season, episode, part, extra. One test runs every row. The
  script reads the inbox, folds the names into shapes (letters to a or A, digits to 9, separators kept), ranks
  shapes by how many reports share them, runs the parser and lists failures. Fixing a shape means adding rows
  and changing the parser until green.
- Seed the corpus from `private/name-survey/` (the MSB and Cinema Paradiso shapes: `@CC` prefixes, `DVDWO`,
  mathematical letters, truncated names, YouTube ids, URL encoding, aliases, spelling twins).
- Edge cases: a name that is truly ambiguous (mark it, do not guess), scripts other than Latin (digits too,
  see the earlier `toInt` crash), huge or empty names (the parser's garbage test already feeds them).

### N3 Fold spelling twins
model: sonnet high   depends: N2   files: `MediaName.kt`, tests
- Names like Aniyathipraavu x4, Kathanayakan/Kadhanayakan, Aarya 2/Arya 2 should group as one title. Check
  what the parser already folds, then add a fold key with tests from the survey rows. Do not merge two
  different titles (a sequel number stays).

### N4 Optional: anonymous shape counter
model: sonnet medium   depends: S1, N2   files: `StutterReport` neighbour, privacy page
- Count only shapes (never letters) of names the parser could not read, behind its own toggle, off by
  default. Skip it if the cost to trust is not worth it; N1 gives the same input with consent per title.

---

## Missing UX that users usually want (review 2026-11-15)

Checked against `en.json`, `PLAN.md` and `private/COMPETITORS.md` before adding. Already in the app, so NOT
rebuilt: A-B repeat, frame step and save frame, screen lock, sleep timer, night mode, chapters, skip intro,
swipe seek, brightness and volume swipes, pure black theme, mobile data warning and "Save mobile data",
notifications for new videos in starred chats, Load subtitle file and OpenSubtitles search, grouping of
duplicate copies (with "Not the same video"), size limits and their "hidden" note, mark as watched, bookmarks,
voice search, Telegram link copy, "Open in Telegram". Already in plan: CP73 Cast, CP74 @username, CP75 split
pieces, CP76 notifications, CP77 proxy, CP80 lighter copies, CP84 accounts, CP93 to CP98 next up, paused card,
viewers, shortcuts and accessibility. Not rebuilt here. Each task below says "check first" where a quick grep
could still find it.

### U1 Say why a video is not starting
model: sonnet medium   depends: L1 (optional)   files: the loader status in the player, `PlayerErrorSheet.kt`
- After about 10 s with no bytes, say that Telegram has not started sending it (not just "Starting"), with
  Try again, Download first, Play a lighter copy (CP80), Open in another app (PR4) and Back. Phone, TV, desktop.
- Edge cases: a slow but moving line (show speed, do not blame Telegram), FLOOD_WAIT (say how long), a
  protected chat, a file removed, the app in the background, the same card twice.

### U2 "Can't reach Telegram" banner
model: sonnet medium   depends: CP77 for the Proxy button   files: the connection banner, `en.json`
- When the network is up but Telegram stays unreachable for about 20 s, show a banner with Retry and Proxy
  (today the banner only appears after the device goes offline). Phone, TV, desktop.
- Edge cases: captive portal, a VPN, a flapping line (do not flash), the TV remote focus on Retry.

### U3 Hold to play faster
model: sonnet medium   depends: none   files: the player gesture layer, `en.json`   (check first: no "hold" speed string)
- Press and hold on the picture plays at 2x until release, the way YouTube and MX do. TV: hold the fast
  forward key. Desktop: hold a key. A haptic tick on phone. A setting to switch it off.
- Edge cases: conflict with double-tap seek, swipe seek and screen lock, PiP, the per-series speed restoring
  on release, subtitles and audio pitch at 2x, a cast session (not applicable).

### U4 Pause when headphones unplug or Bluetooth drops
model: sonnet low   depends: none   files: `PlayerActivity.kt`   (check first: `setHandleAudioBecomingNoisy`)
- Pause on the "becoming noisy" signal and on an incoming call, and optionally resume when the audio comes
  back. A setting. Phone and TV (Bluetooth headphones on a TV).
- Edge cases: background audio on, a Bluetooth speaker that reconnects, a cast session, audio focus lost to a
  call and returned.

### U5 Subtitles found for you
model: sonnet high   depends: CP49 (preferred languages), CP31   files: `core/.../online/OnlineSubtitles.kt`, player
- When the video has no subtitle in the viewer's preferred language, offer (setting, off by default) to fetch
  the best OpenSubtitles match by file hash, then name, cache it, and say where it came from. Phone, TV,
  desktop.
- Edge cases: wrong timing (offer the delay control), no key or rate limit, no network or metered line (ask),
  hearing-impaired and forced variants, a language the service lacks, a wrong match (Not this one, try the
  next), cache size bounded, kids lock.

### U6 Filter and sort a chat by what a video is
model: sonnet high   depends: none   files: `BrowseViewModel.kt`, `SizeFilter`, `MediaGridScreen.kt`, desktop
`MediaGrid.kt`, `private/SORT-FILTER-PLAN.md`   (check first: the sort options that exist today)
- Chips and sort: duration (short, long), resolution (4K, 1080p, 720p), HDR, codec, has subtitles, unwatched
  only, date range; sort by size, duration, date added, last played. Remembered per chat. A line "N videos
  hidden by your filter" with Show them, as the size limits already do.
- Scale: filtering a 20,000-video chat must come from cached facts, paged, never loading a whole chat; the
  first content shows within 3 s.
- Edge cases: a video with no known duration or resolution (shown, marked "unknown"), filters that hide
  everything (the empty state offers Clear filters), D-pad reach on the TV, desktop keyboard.

### U7 Resume on any device
model: opus high   depends: CP53 backups   files: `Backups`, new sync note, `site/privacy/index.html`
- Opt-in, through the viewer's own Telegram: one pinned file in Saved Messages with progress and lists, merged
  by last write per video key. No server of ours. Phone, TV, desktop.
- Edge cases: two devices writing at once (last write wins per video, never lose a watched mark), a huge list
  (cap and compress), Saved Messages unavailable, sign out (stop syncing, say so), another account, offline
  (queue), the privacy page and Play Data safety updated.

### U8 Mini player while browsing
model: sonnet high   depends: PR2 engine interface   files: phone and desktop browse shells, player service
- Leaving the player keeps the video playing in a docked bar; tap expands, swipe dismisses. Not PiP. Phone and
  desktop; TV does not need it.
- Edge cases: audio focus, rotation, a low-end device (one decoder only), Android 6, the kids lock, the engine
  switch (PR2), the queue and next-up, memory on a 1 GB phone.

### U9 Copy link at this time
model: sonnet low   depends: CP79   files: the video menu, `TelegramLinks`
- "Copy link at this time" adds `?t=` to the Telegram link; TMPlayer already opens that time from a link.
- Edge cases: a private chat (`t.me/c/...`), a topic link, a protected or restricted video (hide the item),
  a video with no link (archive piece).

### U10 Archives and live streams
model: sonnet high   depends: CP75   files: `SplitPieces.kt` and the listing
- Today every archive is dropped silently (`SplitPieces.kt:313`). List them greyed with "Compressed, cannot be
  played"; stream uncompressed (STORE) ZIPs. Live streams and video chats from channels are a later, larger step.
- Edge cases: ZIP64, a password, a split archive, a very large archive (count, never open).

### U11 Audio files as a playlist
model: sonnet medium   depends: none   files: `ChatAudio.kt`, background audio
- Lectures and talks posted as audio play as a queue, with background audio and the course progress.
- Edge cases: shuffle and repeat, a chat with thousands of files (paged), screen off on a TV, headphone keys.

### U12 Optional: home screen widget
model: sonnet medium   depends: none   files: new Glance widget (phone only)
- "Continue watching" widget with the last video and a poster. Not on TV.
- Edge cases: nothing to show, app lock or kids lock (hide titles), battery cost of updates, a removed video.

### U13 Store text, site page and troubleshooting
model: haiku low   depends: PR2, PR4   files: `fastlane/metadata/android/en-US/`, `site/`
- Mention phones in the short description, add a phone page, and guides for a video that will not start or
  keeps buffering (including "try another player"), Telegram blocked on this network, and phone storage full.
  Use "video", never "movie".

### U14 Search by topic name (requested by a user, 2026-11-15)
model: opus high   depends: none   files: `ChatRepository.kt` (`forumTopics`), `BrowseViewModel.kt`,
`TopicTabs.kt`, search screens on phone, TV, desktop palette and the web app, `en.json`
- Topic tabs and per-topic search exist (CP45); nothing searches topic NAMES. Add a "Topics" group to global
  search (rows read "Chat > Topic", a tap opens the chat on that topic through `TopicMemory`) and a searchable
  chip row once a group has more than about 20 topics. `getForumTopics` already takes a query.
- Scale: only forum chats, a few at a time, only after the viewer types (debounce, cancel), FLOOD_WAIT waited
  out by the call that got it, no background crawl; budget stated and tested with `SyntheticAccount`.
- Edge cases: closed, pinned and untitled topics, a topic with no videos (dimmed, "No videos"), case, diacritics
  and emoji in names, the same topic name in many chats, a hidden or restricted chat (the kids lock stays),
  a failed search (Try again, Send logs), results only from loaded chats on a very large account.

### U15 Streaming comes first, downloads yield
model: opus high   depends: L1   files: `OfflineDownloads.kt`, downloads service, `TdDataSource`, settings
- Second-pass finding (2026-11-15): only `heldByPlayer` keeps a file from deletion; nothing slows background
  downloads while a video streams, so downloads and the stream share one line and TDLib's queue. Add: while a
  video is playing from the network, other downloads pause (or take a small share), and resume when playback
  ends or pauses for long. Setting "Pause downloads while playing" (on). The L1 trace records the share so the
  effect is visible in a report.
- Edge cases: the downloading file is the one playing (never pause it), a download near done (let it finish),
  several players (cast and phone), a pause that outlives the player after a crash (resume on next start),
  Wi-Fi only rules, low storage, TV remote status line says why downloads are waiting.

### U16 Play something at random
model: sonnet low   depends: none   files: chat and downloads menus
- "Play random" in a chat, a topic, Downloads and a list (clips, lectures, shorts), phone, TV, desktop.
- Edge cases: an empty list, watched videos (setting to skip them), a very large chat (pick from the loaded
  page, never load all), the same video twice in a row.

### U17 Large screens: tablets, foldables, split screen, Chromebooks
model: sonnet high   depends: none   files: `ui/src/shared`, screens, previews   (check first: no
WindowSizeClass use found in `ui` or `app`)
- A device pass with preview screenshot tests per size class: two-pane browse and detail, the player in split
  screen and freeform windows, folding and unfolding without losing position, keyboard and mouse focus order.
- Edge cases: rotation, a window resized mid-play (surface and subtitle layout), PiP and multi-window together.

### U18 Dead ends and empty states (from `private/COMPETITORS.md`, "Dead ends found in the code")
model: sonnet medium   depends: U1, U2   files: `BrowseViewModel.kt:1010`, `BrowseScreen.kt:2968`,
`DownloadsScreen.kt:891`, `MainActivity.kt:1115`, desktop `SignIn.kt`
- Give every dead end a next step, in the style the app already uses (`SlowAnswerNotice`, "Show them", "Keep
  looking"): no match in a chat, topic or the whole list offers Search all chats; every empty tab (Favourites,
  Watch later, Unread, Archived, Saved) offers a button to Chats; "No chats yet" gets Refresh (a new sign in on
  a large account can meet it while TDLib is loading); an empty Downloads tab offers Browse and Download a
  season; Android sign in that stalls shows the desktop's reasons and Retry; "No playable videos" says what
  the chat does hold (archives, photos).
- Edge cases: TV focus lands on the new button first, a long translated label, the button hidden by the kids lock.

### U19 UX and UI audit: symmetry, flow, and what to remove
model: opus high   depends: U17 (screens), can start alone   files: screens across `ui/`, `app/`, `desktop/`,
`webapp/`, `private/shots/`
- Walk every feature the way a first-time viewer would reach it (from Home, from a chat, from search, with the
  TV remote) and record: misaligned or unequal spacing and padding between sibling elements, controls that
  differ between phone, TV, desktop and web for the same job, inconsistent labels and icons, focus order that
  jumps, touch targets under 48 dp, text that clips at 200 percent scale or in a long translation, and flows
  that need more taps than the job is worth.
- Tools: preview screenshot tests per form factor and the `visual-diff` skill (three widths); local headless
  Chromium for the web app and site; no phone pass unless the user asks.
- REMOVE what is redundant or dead, on these rules only: a setting or string with no code reference, a screen
  or entry that duplicates another (one row where two do the same, as "Recently added" did), a control that
  does nothing on its platform, dead feature flags. A live feature a viewer may use is NOT removed here: it is
  listed under "Candidates to remove" with why, for the user to decide.
- Output: fixes committed section by section, a report in this file under Findings with before and after
  screenshots for each visual fix, and the candidate list.
- Edge cases: a removal that breaks a backup file (keep reading old keys, drop writing them), a deep link or
  shortcut that pointed to the removed screen, a translation key still used by another catalog.

### U20 Settings: remove repetition, group by job
model: opus high   depends: PR5 (its new rows), S1, can start alone   files:
`app/.../ui/settings/` (SettingsScreen.kt, SettingsGroups.kt, DecoderSettings.kt, TrackLanguageSettings.kt,
LearntLanguagesSettings.kt, OnlineSubtitlesSettings.kt, HomeSettingsSheets.kt), desktop `SettingsPages.kt`,
web settings, `en.json`
- Findings so far: settings are spread over a dozen files (about 6,900 lines on Android alone), and the same job
  shows up in several places (subtitle language in track languages, learnt languages and online subtitles;
  storage in Low storage, Save mobile data, download location and the downloads screen; decoder in its own
  page plus the in-player menu). Audit every row on every platform and list: duplicates (same job twice),
  rows that only differ by platform, rows nobody can reach, rows whose detail text repeats the title, and
  rows that belong with another group.
- Fix: one home per job. Merge or link duplicates (a row that exists twice becomes one row plus a link),
  group the page by what the viewer is trying to do (Playback, Subtitles and audio, Downloads and storage,
  Appearance, Privacy and lock, About and help), put the new PR5 rows (Player engine, Default player,
  advanced group) where they belong, and give each group a one-line summary. A search box in Settings for the
  phone and desktop once it has more than about 25 rows. Keep reading every old stored key (backups, restore),
  stop writing the dropped ones.
- Rules: a removed row's string is deleted from `en.json` only when no catalog or code uses it; the other 21
  catalogs are the user's, so list the keys left over for them, never run langsync.
- Edge cases: a setting that only applies on one platform is hidden, not greyed, on the others; TV: every
  row and dialog reachable with the D-pad, focus returns to the row after a dialog; a row deep-linked from a
  notification, error card or shortcut keeps working (update the link target); "What is in Settings"
  help text (`settings_title` and its lines) matches the new groups.
- Done when: a table of old row to new place is committed in this file under Findings, preview screenshots
  per form factor of every changed page, D-pad focus tests on the changed pages, no dead string.

### Flow standard (applies to every task above)
A task is not done until its feature passes these, with a test or a screenshot where a machine can check:
1. Reachable in at most two steps from where the viewer already is, on the phone, with the TV remote, on the
   desktop with the keyboard, and on the web.
2. The first use explains itself in one line, never a wall of text, and can be skipped.
3. Every choice has a safe default, and the viewer can always go back or undo (a way back from a default
   external player, an undo for a removal, "Reset to default" on a setting).
4. Every wait says what it waits for, and every failure says what happened and offers Try again and Send logs.
5. Sibling controls line up: the same padding, size, icon style and label tone as the screen beside them,
   the same order on every platform, touch targets at least 48 dp, focus visible and in reading order on the TV.
6. Text survives a long translation and 200 percent scale without clipping; nothing depends on colour alone.
7. No new row where an existing one can be extended ("Look before adding").

### Gap loop (self review, bounded)
- At the end of every wave, re-run the gap scan (grep `en.json`, `PLAN.md`, `COMPETITORS.md` for what users
  of video players usually ask for) and add anything new here as a U task. Stop when a full pass adds
  nothing, or after three passes, so it cannot run forever.
- Pass 1 (2026-11-15): U1 to U14. Pass 2 (2026-11-15): U15 to U19. Frame-rate matching already exists
  (`FrameRateMatch.kt`), so PR5 only exposes its toggle. Equalizer, audio output picker, external audio files
  and voice assistants were looked at and left out as low value for now.

### Not added on purpose
- Playing local files from the phone's storage: TMPlayer is a player for videos already in your Telegram
  chats; say so if you want it reconsidered.
- Posting or forwarding to chats: the app never joins or posts for you (`open_in_telegram_detail`).
- A clock on the TV pause screen: reverses a choice from 1.16.0; CP95 already asks first.

---

## Checks before calling a task done (machine first)
- Pure classes get table tests (selector, verdict, trace reader, title corpus); parsers of outside data get
  a line in `GarbageInputTest`.
- Both engines pass the same contract test and the D-pad focus test; screenshots per form factor.
- Idle CPU on the Mi TV stick after leaving the player; the sampler's own cost measured there.
- Dash check before every commit:
  `grep -rInP '\x{2014}|\x{2013}' --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle --exclude-dir=node_modules .`

## Left for the user
- A real Tanix TX6 (or its owner) to confirm VLC fixes it and the selector picks it.
- The slowest TV for the stream test and the sampler cost.
- Deploying the log Worker; Play and F-Droid review of the new native code; legal check of the privacy and
  terms changes; translations in the other 21 catalogs; every release step.
