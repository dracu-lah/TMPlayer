# UX interaction plan: search, inputs, lists, filters, keyboard, D-pad, gestures

Started 2026-11-16 (user: "you've done a lot of ux mistakes ... all types like dpad touch, gestures,
keyboard shortcuts everywhere"). The visual pass is `docs/ux-audit.md` (U19); this one is about
behaviour: what a control does when pressed, cleared, left, repeated, or driven without a touch.

## Rules learned (sources in the last section)

Search and inputs
1. A search box has a visible clear control; clearing keeps focus in the box (phone, desktop).
2. Escape clears first, then closes (desktop). Back clears first, then closes (phone, TV remote).
3. The query is per visit: leaving the screen clears it, except while a page opened from a result is
   up (Back returns to the results). Main Search keeps its 2 minute rule.
4. IME action matches the field: Search for a search box, Next between fields, Done at the end; the
   keyboard type matches the content (url, number, password); no autocorrect on codes and names.
5. No results says what to try (remove a filter, check spelling) and offers the one-tap way back.
6. Recent searches (if shown) have a per-item remove and a Clear all; none shown for private chats.
7. Ctrl+F (or `/`) focuses the search box on desktop; never rebind Ctrl+C, V, Z, F, A.

Lists, selection, filters
8. Ctrl+A selects all, Shift+click a range, Ctrl+click toggles, Esc clears the selection; a count shows.
9. A selection that changes while work runs is stable; empty selection disables the action.
10. A filter or sort is visible as chips or a label, one tap to clear, "Clear all" when two or more.
11. After Back, scroll position and the focused row come back; a removed row hands focus to a neighbour.
12. Empty, loading, error and huge states exist for every list (3,000 chats, 100,000 videos).

Remote, keyboard, gestures
13. D-pad: every control reachable, focus always visible, no trap, Back goes one level up, no exit
    confirmation, focus returns to the control that opened a dialog or sheet.
14. Keyboard: Tab order follows reading order, no keyboard trap, Esc closes the topmost transient
    layer only (dialog, menu, sheet), Enter activates, Space toggles, arrows move in lists.
15. Touch: targets 48 dp, a gesture always has a visible button twin, predictive back works,
    swipe and long-press actions are discoverable, every press answers (haptic or pressed state).

## Product bar (user, 2026-11-16)

"Inspect everything, beautiful looking, easy to use as a common man app." So every control is judged by
someone who has never used a media app: plain words (no jargon, no internal names such as "TDLib" or
"cache window"), one obvious next step per screen, defaults that work without opening Settings, big
targets, nothing destructive without a clear sentence, and the same look and motion everywhere.

## Redesign over workaround (user, 2026-11-16)

"Even if it is a completely new design and aspects for better usability, do that instead of
workarounding in the same stuff." So when a flaw is structural (wrong layout, too many steps, a
setting that should not exist, a screen that does the wrong job), do not patch it: redesign it.

- Order: shared parts first (tokens, shared components), then the screens, so nothing is built twice.
- Before building a redesign, write `design/redesign/<name>.md`: the problem Raju hits, the new flow,
  phone, TV (remote and focus), desktop (keyboard) and web in one table, edge cases (empty, huge,
  failing, interrupted), and a before shot. Then build, then an after shot. Small, clear fixes skip
  the note.
- Keep TMPlayer's identity (colour, type, shapes in the design system); take ideas from other apps,
  never copy their look. Every redesign ships on all platforms that have the feature (parity).
- Delete what the redesign replaces (old rows, dead strings, unused composables): no parallel old
  and new versions. A removed setting keeps a safe default; a stored value stays readable.
- Big ones (a new Home, a new player overlay, merged screens) stop at the design note for the
  user's OK; everything else goes ahead and is logged under "Decisions made by Claude".

## Feature admission gate (user, 2026-11-16: "add more missing features, but make sure it will not confuse users")

A feature goes in only if it passes all of these; a worker writes the answers in its design note.
1. Raju test: a first time user can use it without reading anything, or never has to know it exists.
2. It decides for the user: the app picks the right behaviour from facts (device, network, file); a new setting
   is added only when two sensible people would choose differently, and it ships with a default that is right
   for most (and a plain one line explanation). No setting for what the app can work out.
3. Appears when relevant, not before: cast only when a cast device is found, "Play a lighter copy" only on a weak
   line, skip pills only inside a stretch, remedies only after a failure. Nothing new on a main screen's first view
   without removing something (one in, one out per release).
4. One place, one name: grep first (AGENTS.md "look before adding"); extend the existing row or sheet, never a
   parallel one; the same word everywhere.
5. No silent change: anything that changes what existing users see is announced once in a dismissible card and
   can be undone; stored values keep working.
6. Plain words, no engine names (TDLib, codec, proxy, cache, MTProto) in the lead line; detail one tap away.
7. Parity: phone, TV remote (focus), desktop keyboard, web where it applies; machine tests per form factor.
8. Reversible and safe: nothing destructive or costly (data, space, money) without a sentence saying so.

Feature queue after the gate (sizes S/M/L; status from `private/COMPETITORS.md` and the plan):
- Invisible or automatic (do first): FQ1 say why a video is not starting after 10 s, with Try again, Download
  first, Back (S, P T D); FQ2 "Can't reach Telegram" banner after 20 s with the network up, Retry and Proxy
  button when proxies land (S); FQ3 the remedy ladder RL1 to RL15 (T7); FQ4 skip from chapters and more skip
  sources (T10); FQ5 season packs, ranges, caption link match, subtitle language (TS, T9); FQ6 anime episode data
  (T11); FQ7 Android sign in stall gets the desktop's answer (S).
- Contextual, one control: FQ8 cast to Chromecast and Google TV (CP73) as an icon that exists only when a device
  is found; FQ9 "Play a lighter copy" (CP80) offered only when the line is weak and a lighter copy exists; FQ10
  night mode for sound as one switch in the player's More panel (plain name "Quiet loud scenes"), Android audio
  processor first, desktop after a filter check (M); FQ11 a pure black theme as one more choice in Appearance (S).
- Opt in, outside the main flow: FQ12 progress and bookmark sync through your own Saved Messages (TS5, one
  question in the setup's end card, off until said yes); FQ13 Trakt or Simkl sync in Settings, Accounts
  (CP62 waits for keys); FQ14 audio files as a playlist (M, after the above); FQ15 troubleshooting guides on the
  site for "will not start", "buffering", "in pieces", "storage full" (S each; public copy: needs the user's OK).
- Needs the user's OK (conflicts or public copy): the TV pause screen clock (removed on purpose in 1.16.0), the
  store listing and a phone page on the site, live streams from channels (L, niche).
Each FQ becomes a worker task only after its design note answers the gate; none is built beyond the plan above
without it.

## Review dimensions for every control (the exhaustive pass)

Inventory: `node scripts/ux-inventory.mjs` writes `private/ux-inventory/<chunk>.md`, one checkbox per
interactive control (about 2,000 in 11 chunks, see INDEX.md). It is generated and git-ignored: read it
from the main tree at `/home/dracu/projects/personal/TMPlayer/private/ux-inventory/`. For each line, check:

- D1 Behaviour: what it does on press, repeat, cancel, empty, huge and failing data; rules 1 to 15 above.
- D2 Input modes: touch, D-pad (focus order, visible ring, no trap), keyboard and mouse (Tab, Enter,
  Space, Esc, shortcuts, hover, right-click where the platform expects it), gestures with a button twin.
- D3 Motion: every state change animates (press, focus, expand, appear, remove, navigate) using the
  shared Motion tokens and honouring Reduce motion; no animation that blocks input or runs while idle.
- D4 Look: spacing, type scale, radius, colour tokens, icon size and weight, alignment, truncation and
  wrapping at the largest font, dark and light, RTL; no one-off numbers where a token exists.
- D5 Words: plain, short, consistent term for the same thing everywhere, en.json only.
- D6 Speed: recomposition and frame cost (use the `compose-performance` skill: stability report,
  state reads in the frame phase, lazy keys), work on the main thread, idle CPU on the TV, startup cost.
- D7 Access: content descriptions, 48 dp targets, contrast, focus announcements, font scale 200%.
- D8 Common person: would a first time user know what this does without reading anything?

Skills for the pass (already installed): `impeccable` (polish, animation, hierarchy), `compose-performance`,
`visual-diff` (before and after screenshots, animation filmstrip), `web-design-guidelines` (web app and site).

## Waves (user wants speed; the locked rule caps it at 3 workers at once, each in its own worktree)

| Wave | Workers | Chunks |
| --- | --- | --- |
| 0 (running) | T1, T2, T3 | rule based pass above |
| 1 | R1, R2, R3 | desktop-ui-1, phone-browse-1, settings |
| 2 | R4, R5, R6 | desktop-ui-2, phone-browse-2, flows |
| 3 | R7, R8, R9 | desktop-ui-3 + components, player, web-1 + web-2 |

Each R worker writes `private/ux-review/<chunk>.md`: per control the verdict on D1 to D8, what was
fixed (commit), what is left and why. A wave starts when the previous one is merged, so two workers
never edit the same file. Every fix gets a machine check (render, focus, scale or unit test) first.

## Skills

No install needed: `impeccable` and `web-design-guidelines` (already available) cover review and
polish. An unvetted third-party skill is a supply-chain risk for no gain. Workers may call them.

## Tasks (3 workers, own worktree each, `files:` do not overlap)

Each worker: audit its surface against the rules above on phone, TV, desktop and web (read the
code, then fix), write a failing machine test first where a behaviour is wrong (focus/D-pad test,
Compose UI test, render test), fix, commit locally section-wise, list what is left in its
Progress section. No phone or emulator pass (user rule).

### T1 Search boxes and text inputs  (model: sonnet, depends: none)
files: `app/src/main/java/com/tmplayer/ui/settings/SettingsScreen.kt`, `SettingsSearchBox.kt`,
`desktop/src/main/kotlin/com/tmplayer/desktop/ui/Settings.kt`, the search page and every
`TextField`/`OutlinedTextField` outside the files owned by T2 and T3, plus their tests.
Done when:
- Settings search is cleared on leaving Settings (phone and desktop), with a Compose test per
  platform: type, open a result page, Back keeps the query, leave Settings, return, box empty.
- Every search/input box obeys rules 1 to 7 (list the boxes found, fix or say why not).
- IME actions and keyboard types reviewed for every field (sign in phone/code/password, proxy,
  TMDB key, rename, filters).
- Edge cases: rotation keeps the query while Settings is up; TV has no box (no change).

### T2 Lists, selection, filters, sort, empty states  (model: sonnet, depends: none)
files: `app/src/main/java/com/tmplayer/ui/browse/**` (except search), `ui/downloads/**`,
`desktop/.../ui/GridSelection.kt`, `Downloads.kt`, the chat and library list screens, their tests.
Done when: rules 8 to 12 hold on phone, TV and desktop, with a D-pad test for focus restore after
Back and after removing a row, a Ctrl+A / Shift+click / Esc desktop test, a filter-clear test, and
an empty-state check for each list.

### T3 Keyboard shortcuts, remote and gestures in the player and shell  (model: sonnet, depends: none)
files: `app/src/main/java/com/tmplayer/player/**`, `desktop/.../player/**`, `desktop/.../ui/Shell.kt`,
`desktop/.../Main.kt`, dialog and sheet components under `ui/src/shared/**`, `MainActivity.kt`
(only the back handling, minimal edits), their tests.
Done when: rules 13 to 15 hold; a table of every shortcut and remote key per platform is written
to `docs/shortcuts.md` (it exists only if missing: read first), conflicts with the universal
shortcuts fixed, Esc closes one layer at a time, focus returns to the opener after a dialog/sheet,
predictive back and the back gesture checked, targets under 48 dp listed or fixed.

### T4 Streaming storage: two tabs "Keep video" | "Save space"  (model: sonnet, depends: T1 merged)
User picked option A on 2026-11-16, without "the": row title "While streaming", tabs `Keep video` and
`Save space`, `Save space` selected on a fresh install (already committed, `d78d4df`). Same stored
boolean `low_storage_mode` (on = Save space), no migration. Reuse the existing segmented control
(`SettingsScreen.kt`, desktop `SettingsRows.kt`), do not add a new one.
files: `app/.../ui/settings/SettingsScreen.kt` (row at ~1703), desktop `SettingsRows.kt` and
`StorageSettings.kt`, `PlayerSheets.kt` and the `settingsLowStorage*` / `mainLowStorage*` /
`mainNoRoomLowStorage` / `playerNotEnoughRoomLowStorage` strings in `en.json` (the prompt text names
the mode: "Switch to Save space?"), `DownloadsScreen.kt` and desktop `Downloads.kt` call sites,
`LowStorageRenderTest.kt`, `docs/FEATURES.md`, `docs/settings-audit.md`.
Done when: phone, TV (D-pad Left/Right moves between tabs, focus ring visible) and desktop (arrows,
Tab) show the control with a detail line under the active tab ("Needs room for the whole video.
Seeking back is instant." / "Keeps only what you are watching now. Seeking back downloads it
again."); render tests per form factor; focus test; the setup's storage step and the "Recommended
for this device" card use the same words; `Notice.LowStorage` (the free space alert) keeps its name.
Edge: a download kept on purpose is never windowed, whatever the tab (say so in the detail line).

### T5 Chat picker: recommended list misses video channels, no counts  (model: sonnet, depends: T2 merged)
User, 2026-11-16: the recommended band in the chat picker (Add chats and the setup's chat step) leaves out
channels that hold a lot of videos; the video counts may be inaccurate, so show none, neither in
Recommended nor in the normal list. files: `ui/src/shared/.../ui/browse/AddChatsScreen.kt`,
`ChatSuggestions` and `ChatPicking` (core), their tests, `en.json` count strings, desktop and web picker
(`webapp/src/screens` SetupChats, AddChats). Done when: root cause of the misses found and written here
(suspects: the scan budget per refresh, a chat read cut off at a page, channels not yet opened, a minimum
count, folder or archive chats skipped, private-chat title rules), a `SyntheticAccount` test with a
channel of 20,000 videos that is recommended, counts removed from rows and from the band header on phone,
TV, desktop and web with dead strings deleted, ranking explained in one plain line to the user.
Edge cases: a chat with a few videos stays out of Recommended but findable; scale 3,000 chats within budget.

### T6 Mini player check  (model: haiku, depends: Raju phone pass)
User asked 2026-11-16 whether the in-app mini player (video keeps playing while browsing) is missing. Docs
say it exists on the phone (`docs/mini-player-phone.md`, `MiniDockBar`, `MiniPlayerBar`) and desktop
(Ctrl+P), never on TV (by design, `MiniPlayerState.allowedOn`). Verify on the phone with the Raju pass; if it
is not showing, find why.

### T7 Player engines and the automatic remedy ladder  (model: opus high, depends: research doc)
From `private/PLAYER-RELIABILITY-RESEARCH.md` (agent X1): detection signals and the one-switch-per-video
ladder; PR1, PR2, PR2b, PR3 in `private/PLAYER-RELIABILITY-PLAN.md`. Also: Low storage mode does not start
a film on phone and TV (HX79 evidence note): diagnose with logcat first, fix, add a test that a rolling
window starts.

### T8 Format badges: resolution, HDR, Dolby, audio  (model: sonnet high, depends: D1 or T5 done, a worker slot)
User, 2026-11-16: "HD, 4K, 1080p icons / logo images might be missing, add them if possible, also Dolby Atmos
and other stuff." Today only the details sheet shows a quality label (`VideoCopies.qualityLabel`,
`MediaDetailSheet.kt:526`); tiles, lists, the player and Downloads show none.
- Shared part first: one `FormatBadge` (ui/shared, design tokens, motion, TV focus is not needed, it is not
  a control) and a pure `FormatFacts` classifier in core with a table test: from width and height (cheap,
  Telegram gives them): SD, HD (720p), Full HD (1080p), 2K (1440p), 4K (2160p), 8K; from a probe: HDR10, HDR10+,
  HLG, Dolby Vision (profile in `FormatRoute.dolbyVisionProfile`), 10-bit, codec (HEVC, AV1), frame rate
  high (50 and up), audio: Atmos (E-AC-3 JOC or TrueHD Atmos), 7.1, 5.1, stereo, DTS, DTS-HD, DTS:X, AAC.
- Logos: Dolby, Dolby Vision, Dolby Atmos, DTS, HDR10+ and "4K Ultra HD" are licensed marks; their logo
  files may not be shown without a licence. So: our own badges in plain text pills ("4K", "1080p", "HDR",
  "Dolby Vision", "Atmos", "5.1"), a generic icon (a speaker, a sun) and no brand logo images. The
  user may supply a licence and logos later (no work until then).
- Where: poster tile (one resolution pill, top corner, only 720p and up, only when known), details page
  (full row: resolution, HDR kind, codec, audio, size), player info sheet and track picker (per track),
  Downloads rows. Phone, TV, desktop, web. No probing per tile: resolution from the message, the rest only
  from a file already on disk or after the first play, cached per video (bounded cache), never a TDLib
  call per visible tile (scale rule).
- Honest: a badge says what the file declares; "Atmos" only when the track is Atmos, never guessed from the
  name; unknown shows nothing (no empty pill). Raju check: the badge must not crowd the poster at 200% font.
- Tests: classifier table, garbage input (zero or negative size, absurd values), render per form factor.

### Raju review results (2026-11-16, merged `private/ux-review/raju-journey.md`, shots in `design/ux-review/raju/`)
Groups A to I (small fixes, own files, each with a machine test) are queued as workers W-A to W-I as slots
free up; G (player controls) needs T3's merged files (done); I needs T4 first. Group J are four big
redesigns that stop at their design notes for the user's OK:
- R1 show every video by default (replaces the 400 MB floor): blocker for Raju.
- R2 Downloads as a tab, one honest storage page (two twin switches become one 3 way control).
- R3 a one question setup. CHANGED by the user's "no counts" rule: the chat list shows NO video counts
  (the review proposed "120 videos"); order by evidence of videos, as T5 does. Merge R3 with T5's picker.
- R4 offline mode that tells the truth.
R5 (player labels, skip buttons) is a patch scale change and goes ahead as group G.
Open on the phone: History shows a 20 second test play from the review; remove it if unwanted.

### T9 Series: show every season and episode the data knows, not only the ones in the chats  (model: sonnet high, depends: a worker slot, T5 for shared Series files if any)
User, 2026-11-16: a show with 6 seasons where the chats hold 2 should still list all 6 from the metadata.
Today only `number_of_seasons` is read (`MetaApis.kt:250`) and the series page lists what the chats have.
- Shared part first: a pure `SeriesShelf` in core merging the metadata's seasons and episodes (TMDB, TVmaze,
  AniList, already fetched) with the videos found in the chats: each season and episode is Available (has a
  video, plays), Missing (known to the data, not in your chats), or Unaired (air date in the future).
  Table test with: 2 of 6 seasons present, a season with 5 of 10 episodes, specials (season 0), a two part
  finale, anime absolute numbering, a wrong match, no metadata (falls back to today), garbage and huge data.
- UI (phone, TV, desktop, web): Missing seasons are listed in order, quiet (lower contrast, a plain line
  "Not in your chats yet"), episodes inside a season show gaps by episode number with their data title; a tap
  on a missing item says what it is and offers "Find in other chats" (search by title, episode number) and
  never a Play button. Unaired shows the date. Season picker lists all seasons with a dot on those you have.
- Rules: no video counts as a promise (user: counts may be wrong), so the line says "6 seasons" only from the
  metadata and marks "in your chats" by presence, not by a number; a hide switch is not needed (it is the
  default honest view) but "Only show what I have" lives in the series sheet for people who want it.
- Offline or no metadata: unchanged, no empty skeletons. Scale: a 20-season show with 1,000 episodes lists
  lazily and cheaply; metadata comes from the existing cache, no new call per row.
- Edge: the chats' episodes numbered differently from the data (absolute vs per-season) keep today's matching
  and never show a present episode as missing twice; a mismatch shows nothing as missing for that season.

### T10 Skip times: more sources and a local fallback  (model: sonnet high, depends: a worker slot; files: core/.../online/MetaApis.kt, OnlineMetadata.kt, SkipTimes.kt, player skip pill, desktop and web skip code)
User, 2026-11-16: "intro-db, anime-skip.com and similar API features need to be added, check what is missing."
Found: TheIntroDB, IntroDB.app (by IMDb id) and AniSkip (by MAL id from AniList) are built (`MetaApis.kt:759` on,
`OnlineMetadata.skipTimes`, `SkipPill.kt`, cached in `MetaStores.skipTimes`). Missing, in value order:
1. Chapters inside the file (MKV and MP4 chapters titled Intro, Opening, Recap, Credits, Ending, Preview) as
   `SkipSegment`s: free, exact, offline, no API. Read through the engine's chapter list (Media3, libVLC, mpv);
   they win over any API when present. Pure title-to-kind mapper with a table test (many languages, garbage).
2. anime-skip.com (GraphQL, needs a client id header: register one for TMPlayer, read their terms first;
   timestamps types Intro, Recap, Credits, Preview, Title Card, Mixed Intro, Canon, Filler...) as the second
   source for anime after AniSkip; map its types to `SkipKind`; rate limit and a 404 and empty answers cached.
3. A second chance for anime ids: when AniList gives no `idMal`, map via a public id-mapping API or Jikan, so
   AniSkip can still be asked; a show recognised only by TMDB gets TheTVDB or IMDb ids for the two community DBs.
4. "Mark this as the intro" in the player (two taps: start, end) saved locally and applied to the other episodes
   of the same season and chat as a guess (with a pill "Skip intro (from your mark)"), phone, TV remote (one
   button path), desktop (key), no upload. Optional later: a contribute button that sends to a database
   (needs their key and terms, not now).
5. Per kind switch in Settings: Ask (pill, default) or Skip by itself (after a 3 s countdown with Cancel) or
   Off, per Recap, Intro, Credits, Preview; check what exists and extend one control, no parallel one.
6. Parity: the pill and the segments on phone, TV, desktop and web (desktop has 4 files and web 3 that touch
   segments today; `ui/shared` has none: move the model and the pill's logic to shared first).
7. Later, not now: local detection by comparing two episodes (audio fingerprint), expensive on weak devices.
Tests: provider answer fixtures for each API with garbage and huge answers (`GarbageInputTest`), a merge test
(chapters, then API, then user mark; overlapping and out of range segments dropped), a D-pad test that the
pill is focusable on TV and Back dismisses it. Privacy: lookups send only ids and episode numbers (no file
names, no chat names), Settings explains it in one plain line, and the switch off sends nothing.

### TS Telegram-Stremio gaps (user: "add all of them whatever necessary")  (doc: `private/TELEGRAM-STREMIO-GAPS.md`)
Result of the 2026-11-16 diff: of 50 upstream features TMPlayer has 30, takes 9, later 4, skips 7 (server and
multi-bot features dropped for Telegram's terms and the no-server promise). Takes, each a worker task TS1 to TS8
in that doc with done-when and tests: season packs on the show page, episode ranges in names (`~`, `to`, `,`,
en dash, `S01-S03`), a "Why no poster?" explainer (the local skip-channel idea), a preferred copy per chat
capped by what the device decodes, a backup before sign out plus bookmarks and viewer corrections in the Saved
Messages sync, an IMDb or TMDB link in a caption forcing the match, subtitle language from full names
(`Hindi.srt`). Split pieces and ZIP are already done on all four platforms. TS tasks run after T9 (they share
the series files) in waves of 3.

### T11 Anime episodes: titles, images and details (absolute numbering)  (model: sonnet high, depends: T9 and T10 (same online files), a worker slot)
User, 2026-11-16: opened a long anime (Naruto Shippuden, about 500 episodes, usually named by absolute number): the
episode pictures, titles and details were missing. Likely cause, to CONFIRM first with a failing test using
fixtures (a 500 episode show named `Show - 123.mkv`): `EpisodeNames.named` only accepts an episode when
`it.season == tag.season && it.number == tag.episode`, a show matched on AniList has no episode list at all
(AniList gives one entry per cour, no per-episode names or stills; no `streamingEpisodes` read in
`MetaApis.kt`), and TMDB's per-season lists do not line up with absolute numbers (no `episode_groups` read).
Fix as one redesign of anime episode data, not a patch:
1. A pure `AnimeEpisodes` resolver in core: absolute number to (season, episode) and back, built from the best
   available list: TMDB episode groups of type absolute order, else TMDB seasons counted in order, else Kitsu
   (episodes with canonical title, synopsis, thumbnail, air date, absolute number; found by MAL id through
   Kitsu mappings, no key), else Jikan (MAL episode titles and air dates), else AniList `streamingEpisodes`
   (title and thumbnail). One answer per episode with the source named; the first source with a title wins,
   images from the first that has one; each cached with a bound; one request per show not per row.
2. Wire it where episode rows, the player's episode labels and the next-up card read names and stills
   (`EpisodeNames`, `OnlineMetadata.season`, `SeriesShelf`, phone, TV, desktop, web).
3. Filename parsing for anime: `[Group] Show - 123 [1080p].mkv`, `Show 123v2`, `S2 - 05`, season cour splits
   (a file numbered 1 to 500 on a show with 20 seasons), batch and recap/OVA/special markers; ranges (TS2) share
   the parser. Garbage test.
4. UI: a missing still shows the show's own art with a dark overlay and the number, never an empty box; a missing
   title shows "Episode 123"; both fill in when the data arrives without a layout jump (reserve the space).
5. Show T9's missing-episode rows for anime too (a 500 episode list is lazy and cheap).
Tests: fixtures for Naruto Shippuden (500 episodes, 21 TMDB seasons, AniList one entry, Kitsu and Jikan
shapes) plus one cour-split show and one with a wrong match, scale (500 rows, no request per row), offline,
garbage. Privacy: only the show id and episode numbers are sent, none of the file or chat names.

### T12 Remove the phone's in-app mini player  (model: sonnet medium, depends: T1 merged (SettingsScreen.kt), a worker slot)
User, 2026-11-16: the in-app mini player confuses people: Back from the player leaves the video playing in a bar
while they are on the details page; people who press Back just want to leave. Decision: remove it (Raju 6.4
agrees). Back from the player now means "stop and go back": pause, save the position (resume point), release the
player, return to the page that opened it (detail, chat, Home). Keep: system Picture in Picture when the user
leaves with Home or the PiP button (their choice, not Back), background audio notification if it exists, the
desktop's always on top mini window (Ctrl+P, a different feature the user did not mention), and TV (never had it).
- Delete, not hide: `MiniDock.kt`, `MiniDockBar.kt`, `MiniPlayerBar.kt` (check the desktop does not use it: if it
  does, keep only what desktop needs), `MiniPlayerState.verdict` dock paths, the launch flags and `TouchNavHost`
  hooks, the Settings row or switch that controls it (`SettingsScreen.kt` mentions MiniPlayer), the strings in
  en.json, `docs/mini-player-phone.md` (replace with one paragraph in docs/FEATURES.md), render tests of the dock;
  `design/ux-audit/mini-player-phone.webp` stays as history only if referenced, else delete.
- Stored settings: an old "dock on Back" value is ignored and removed on first read, no crash.
- Edge cases: Back while buffering or on the failure sheet; Back right after a seek (position saved at the seek);
  a playing video when the app is killed (resume point already saved); casting (Back must not drop the cast
  session silently: it asks or stops it, say which in the plan); a download running for that video continues;
  the next-up card and episode panel closing first; predictive back animation; two quick Backs.
- Tests: Back stops playback and saves the position, the opener page is shown (Compose or Robolectric), PiP still
  works from Home, no leftover references (grep test or a compile), render tests per form factor.
Supersedes Raju group B's "mini bar X 48 dp" and 6.4.

### T7 detail: research is in, tasks RL1 to RL15 (doc: `private/PLAYER-RELIABILITY-RESEARCH.md` section 8)
Five classes found: sound without picture (Media3 raises no error when the video track is unsupported or never
outputs), a frozen picture after a seek, a wrong picture with no error (Allwinner H6: `enableDecoderFallback`
only retries on init failure), hardware decoder stutter (async MediaCodec off below API 31), software decode too
slow. Remedy ladder R0 to R4, at most 2 hops per video, a per device class memory. Order: group A pure core
(RL1 ladder state machine, RL2 memory, RL3 first frame and frozen output watches, RL4 known devices fields) in
parallel now-safe files; then RL5 decoder policy and RL6 the runner (hook lines only in `PlayerActivity.kt`, so
after T12 merges), then RL8 to RL12, RL13 16 KB gate any time, RL7 green-frame spike when the TX6 is reachable.
RL15 (device pass) is the user's. Not verified (tagged in the doc): vendor decoder internals, the current libVLC
Android options, PixelCopy on TV overlay planes.

### D1 Support cards: Buy Me a Coffee and UPI  (DONE, merged 2026-11-16)
Themed cards and dialogs with the official logos, regional order (IN: UPI first), icon popup through the
existing `SupportReminder` nag. Design note `design/redesign/support-cards.md`.

## RUN QUEUE (user, 2026-11-16: "do all"; hands-free, 3 workers at once, own worktree each)

"do all" approves: redesigns R1 to R4 (R3 without counts, merged with T5's picker), Raju groups A to I, T7 (RL1 to RL15
except the user's device pass RL15), T8, T10, T11, TS1 to TS8, FQ1 to FQ15 incl. the TV pause clock, store listing and phone
page copy in the repo (files only, nothing is published), live streams last. Hard stops that stay the user's: store
submissions, tags and releases, deploys, device passes (RL15, leftovers list), keys and secrets (Trakt and Simkl keys,
anime-skip client id: build everything behind a missing key, leave the key as a TODO for the user), NPCI logo.
Nothing is pushed until the user says (commits stay local); each merge into main runs CatalogTest and the compile of
app and desktop. A read-only Sonnet reviewer reads each big task's diff (R1 to R4, RL5 and RL6, FQ8, FQ12); the worker
fixes the findings once. A check failing twice marks the task blocked and the queue goes on.
Order (files that overlap run in different waves; "after" = depends):
1. Running: T4 (storage tabs), T9 (series all seasons, ranges), T12 (remove mini player).
2. Next: RL1+RL2+RL3 (core pure, new files); T10 skip times (after T9); FQ1+FQ2+FQ7 (not starting reason, unreachable
   banner, sign in stall; after T12).
3. Then: Raju A+D+H (chats and search phone, settings search synonyms, web); Raju B+R4 (banner, bottom bar, offline
   mode); Raju E+F (words only, connect help); RL5+RL6+RL8 (player wiring, after T12).
4. Then: R1 (every video by default); R3+T5 picker and one question setup; R2 (Downloads tab and storage page, after T4);
   T11 (anime episode data, after T9, T10); Raju C+G (detail and downloads, player controls and labels).
5. Then: T8 format badges; TS rest (season packs, why no poster, preferred copy, caption link, subtitle language,
   backup before sign out and sync); RL9, RL10, RL11, RL12, RL13, RL14 (docs); FQ10 night mode, FQ11 black theme,
   FQ9 lighter copy, FQ8 cast, FQ14 audio playlist, FQ15 troubleshooting guides, TV clock, store text, phone page, FQ13
   (blocked on keys), live streams.
6. Last: the inventory review waves (R1 to R9 on `private/ux-inventory/`, dimensions D1 to D8: animation, look, speed with
   the compose-performance skill) and Raju's second pass on the final build.
Progress of each item is logged below in "Progress / handoff" with its commit.

## Not in this pass
Store text, the site.

## Progress / handoff
- 2026-11-16: plan written. Fresh-install default for the streaming mode is committed separately
  (`armSetup`). Workers T1, T2, T3 start from that commit.
- 2026-11-16 T3 done (branch worktree-agent-a443a0c6839e96372, local, not pushed):
  - `19715dc` Esc closes a `TmAlertDialog` on every platform (`escapeCloses`), and on the desktop the layer
    over the page (Settings, detail pane, menu, palette) closes before full screen (`ShellState.transientLayerOpen`);
    `ShortcutsAuditTest` (dialog Esc and focus back on the opener, Esc order, Ctrl+C/V/X/Z/A untouched, Tab).
  - `055eb76` no exit confirmation at the top level in `MainActivity` (rule 13; the system back-to-home
    animation now plays; dead string `main.back_again` removed from en.json); 48 dp for the episode panel
    buttons (`EpisodesPanelTest` fails without it), the Free up space buttons and the subtitle swatches.
  - `e4cb9e1` `docs/shortcuts.md` (a table per platform, Esc order, targets under 48 dp), `929b5c1` PlayerKeysTest.
  - Checks: `:desktop:test` for ShortcutsAudit, BigScreen*, BrowseKeyboard, PlayerKeys, EpisodesPanel; `:app:compileDebugKotlin`.
    No app unit test for the MainActivity change (the whole Root composes); no device or emulator.
  - Left: `LoginScreen.kt` `ExitOnBack` still asks "press Back again" (T1's file); the player's back callback
    always claims Back (no predictive back animation from the player); brightness has no button twin;
    Series.kt phone rows are 40 dp (T2); desktop overlay buttons are 36 dp (mouse only).
  - Gotcha: `private/` is git-ignored and lives only in the main tree; Compose Desktop dialogs did not close on
    Esc in `runComposeUiTest` before `escapeCloses`; `press()` in desktop key tests needs a focused node (use `requestFocus` first).

- 2026-11-16 T2 (worktree agent-a8fbeec1c7c3f1840, local commits 57b52aa, f52246b, 6ca92f2):
  done: desktop chat grid Ctrl+A / Ctrl+Space / Enter+Space pick while selecting (ListSelectionKeysTest);
  desktop Downloads now on the shared GridSelection (Ctrl+A from nothing, Ctrl+click, Shift+click
  range, Ctrl+Space, Esc) and a deleted row hands keyboard focus to a neighbour (RowFocusHandoff,
  DownloadsSelectionKeysTest); rowAfterRemoving moved to ui/shared RowHandoff.kt (TV Downloads uses
  it); note design/redesign/list-selection.md. Audit: Android History/Downloads/TV focus restore,
  filter notes with Clear, empty states already existed and were left alone.
  left: focus handoff after removal in desktop History grid and chat list (RowFocusHandoff is ready
  to wear); Android phone/TV chat grid selection with a hardware keyboard (Ctrl+A); web grid
  selection; a Filters "Clear all" chip row when 2+ filters (FilterNote has one Clear today);
  scale test for 100,000 downloads selection.
  gotchas: Compose test mouse has no modifier keys: use performKeyInput keyDown, click, keyUp;
  a removed row loses focus BEFORE and the data change lands near it, hence the 1 s window match.

- 2026-11-16 T1 (worktree agent-ad837c2ce7e131651, local commits, not pushed):
  done: 6e11c96 Settings search per visit through one shared `rememberVisitQuery(keep)` (ui/shared
  components/VisitQuery.kt) used by phone SettingsScreen and desktop SettingsPage, tests
  SettingsSearchLeaveTest (leave and return empty, page from a result keeps words, rotation via
  StateRestorationTester) and SettingsSearchLeaveDesktopTest; 9e483fe Back empties the Settings box
  before leaving, plus a clear-control focus test; e19df74 the shared ClearButton in the Settings box,
  the country list and the topic find (three one-off crosses gone); bea77ef IME: proxy Next then Done and
  no autocorrect, subtitle account and keys, metadata keys; 731ddb0 sign in first screen no longer asks
  "press Back again" (rule 13, `ExitOnBack` deleted).
  left: AddChats search box (OutlinedTextField, phone and TV) has no clear control: a trailingIcon broke
  4 AddChatsTvKeysTest, reverted, needs its own look; desktop OnlineSubtitles/Metadata fields have no
  keyboardOptions; rules 5, 6, 7 (no results hint, recent searches, Ctrl+F) only checked for Settings;
  desktop test does not assert the kept query (box is not on an open page); string `login.press_back_again`
  stays in en.json (CatalogTest fails if only English drops a key: the locale files are the user's, so
  T3's removal of `main.back_again` from en.json will fail CatalogTest the same way, check it);
  ListsUi rename (T2) and PlayerBookmarkSheets (T3) not touched.
  gotchas: copy local.properties into a worktree; the shell tool refuses long compound git commands in a
  worktree, split them; `private/` lives only in the main tree.

## Decisions made by Claude
- T1: one shared `rememberVisitQuery` (not a patch per screen) so any later search box gets rule 3 by calling it; the phone keeps the query while a page is open (`keep = page != null`), the desktop while a category is open. No design note: small fix.
- Three workers, not more: the files are split by surface and `MainActivity.kt` is shared, so only
  T3 touches it and only for back handling.
- T3: Esc handling lives in the shared `TmAlertDialog` (one place, all platforms) instead of per dialog; full screen yields to a layer over the page on the desktop; the top level's exit confirmation is deleted rather than reworded. No design note: small, clear fixes.

## Sources
- Search patterns: https://carbondesignsystem.com/patterns/search-pattern ,
  https://developer.apple.com/design/human-interface-guidelines/search-fields.md ,
  https://design.visa.com/patterns/search
- Android TV navigation: https://developer.android.com/training/tv/start/navigation ,
  https://developer.android.com/design/ui/tv/guides/foundations/navigation-on-tv
- Desktop keyboard: https://developer.android.com/design/ui/desktop/guides/interaction/keyboard ,
  https://developer.gnome.org/hig/reference/keyboard.html ,
  https://w3.org/WAI/ARIA/apg/patterns/listbox
- IME actions: https://developer.android.com/training/keyboard-input/style
- Gaps (not found in the searches): official focus-restore guidance, inline validation and predictive
  back. Check m3.material.io and developer.android.com directly before relying on them.
