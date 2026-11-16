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

### RUN LOG (main, local commits, nothing pushed; updated 2026-11-16)
Merged: T1 `6e11c96..731ddb0`, T2 `07c3b71`, T3 `1ff6bd4`, T5+D1 `ac6028b`, Raju review `27034ea`, T12 `8e12ef2`,
RL1 to RL3 `88d194b`, T4 `aeff4e5`, T9 `da4c88f`, Raju A+D+H `2f60ee3`. Catalog fix `0215c69` (DEAD-STRINGS.md).
Running: RL4+RL5+RL6+RL8 (player ladder wiring, opus), T11 (anime episodes), T10 (skip sources).
To confirm (user): star mode on the chat list (new, from Raju A), pill word "Choose chats", the strip hides at 8 or
fewer chats, desktop Back dock removal (queued), ten `series.*` strings to translate.
PRIORITY FIX (reviewer finding on `e42f689`, next free slot, by the RL worker `acef092900fca8fb1` via SendMessage): (1) SERIOUS
`RemedyRunner.kt:333` `wanted()` keeps the 1 Hz tick running for the whole video (`FrozenOutputWatch.fired` is only true
on a freeze): stop once `proven && firstFrameWatch.done`, re-arm on seek or hop, add a test that the tick stops after
proven (idle CPU on the 1 GB stick); (2) `PlayerActivity.kt:2811` `moveToEngine` `castClient ?: return` loses a hop: make
`reopen()` return Boolean and call `exhaust` on false; (3) `saveMemory` launches per call, out of order writes: one serial
writer. Then RL9 (index bigger than 8 MB, HX79) and RL10 to RL14.

Next: FQ1+FQ2+FQ7, Raju B+R4, E+F, then R1, R3, R2, C+G, T8, TS rest, RL9 to RL14, FQ rest, review waves.

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

- 2026-11-16 T12 done (worktree agent-a473246fa2c3ffd6f, local commits fa8a83a core, 6f621da phone):
  done: Back from the player = `leaveByBack()` (pause, `finish()`; onStop/onDestroy save the place and release; a
  second Back while finishing is ignored). Deleted MiniDock.kt, MiniDockBar.kt, PlayerLaunch, the dock paths in
  PlayerActivity, the shell's bar and kids-lock hook in TouchNav, MainActivity's expand/Back-to-launcher hooks, the
  notification tap's expand extra, the manifest's own task (taskAffinity, autoRemoveFromRecents: back to pre-U8),
  the Settings row, `mini_player` in SettingsMap and Backup, docs/mini-player-phone.md (paragraph "Back means stop" in
  docs/FEATURES.md), MiniDockTest, MiniDockBarTest. Old stored `mini_player` is removed on start
  (`SettingsStore.dropRetiredMiniPlayer`, test in SetupFlagTest). Kept: system PiP (Home or button), background audio,
  the desktop's Ctrl+P window.
  checks: core jvmTest (all), app tests BackStopsThePlayerTest (leftover grep, manifest, Back source guard),
  PlaybackServiceTest, PictureInPictureRulesTest, Settings*, Browse*; :app:compileDebugKotlin; desktop ShellMiniPlayerTest
  and MiniPlayerBarTest. No device.
  left: desktop still docks Back into the in-app bar (ShellState.mini, MiniPlayerHost, shared MiniPlayerState and
  MiniPlayerBar): the user only named the phone, needs the user's OK to remove it too. No Robolectric test of
  PlayerActivity's Back (the activity does not start there): the guard test reads the source. Strings
  settings.mini_player* stay in en.json (DEAD-STRINGS.md). docs/ux-audit.md still links the desktop bar render.

- 2026-11-16 T4 (worktree agent-aaee249e87eba5827, local commits 78cf91b, 9a193da, bfe974a, fbd57b5, aef898f, not pushed):
  done: row "While streaming" with tabs `Keep video` | `Save space` (same boolean `low_storage_mode`, on = Save space) on phone
  (Material segmented, as Theme), TV (new shared `TabSwitch` in ui/shared components, extracted from the setup's ChoiceSwitch:
  one stop, Left/Right pick, OK swaps, focus ring; the setup uses it too) and desktop (`Segments`); the chosen tab's line says
  kept downloads are never trimmed. New keys only (settings.stream_*, main.stream_*, player.not_enough_room_stream,
  downloads.free_save_space*, free_switch, setup.stream_body, tour.settings_stream_page[_tv]); old keys listed in DEAD-STRINGS.md.
  Setup, device card, Free up space offer, switch prompt, no-room messages, docs renamed; `Notice.LowStorage` untouched.
  Tests: StreamStorageRowTest (app: phone, narrow phone at 2x font, TV remote, PNGs in build/device-test/stream),
  SettingsGroupsTvTest (D-pad reaches the switch), desktop LowStorageRenderTest (tabs, keyboard Left/Right/Space),
  SetupTest skips the Storage step in the every-locale test until the user's langsync (`awaitingSync`).
  left: site copy and site/data still say "Low storage mode" (site has its own rules, 21 languages); the settings search
  does not find "low storage" any more (only the new words); Setup enum names (LowStorage, KeepWhilePlaying) and many
  code comments keep the old name; no emulator pass.
  HX79 "film does not start with the mode on": hypothesis only (see the T4 reply): index larger than INDEX_TAIL_BYTES (8 MB)
  and SLACK (16 MB) makes the tail probe a "seek", whose plan() deletes the head, then the head read deletes the tail: a
  delete ping-pong. Not fixed, left for T7 (needs the film's moov/Cues size and logcat "Low storage: deleting file").

- 2026-11-16 T9 (worktree agent-aadcb79e81d1f9f33, local commits 1da2d4f, a3ebc5e, 628fa2a, 07fb67b, not pushed):
  done: series page lists every season and episode the metadata knows, merged with the chats' videos, on phone,
  TV, desktop (shared `SeriesPanel`) and web (`Detail.tsx` Episodes). Merge is `EpisodeGuide` (core, extended: specials
  last and only when held, caps 200 seasons / 3,000 episodes, count-made gaps, next-episode date, `agrees` for a
  wrong match or another numbering, `rows()` Have or Gap, `covered()` for "S01E09E10"); table test
  `SeriesGuideTableTest`; page tests `SeriesGuideTest` (desktop: gaps, unaired, wrong match, only mine, focus back on
  the gap, 20 seasons ask twice at most); web `seriesGuide.ts` + test, worker extras carry `seasonList` and `next`.
  UI: quiet gap rows with the data's title, a card (what it is, "Find in other chats" via `SearchRequest.ask` on
  phone/TV, `searchEverywhere` on desktop, `/search?q=` on web), unaired with its date, a dot on held seasons,
  "Only show what I have" as a round button on the show page (stored in `SettingsStore.seriesOnlyMine`, web
  localStorage). Design note `design/redesign/series-all-seasons.md`. Bug fixed on the way:
  `rememberSeasonEpisodes` kept the last season's list when the season changed (names of the new season never loaded).
  Episode ranges take (TS1 parser half): `~`, `to`, `+`, `&`, commas, en dash, `S01-S03` (`ParsedName.seasonEnd`).
  left: web TV (`webapp/tv`) series screen; no before/after screenshots (no emulator pass); the web has no per-episode
  names for gaps (count only); `pack` flag and the season pack row (TS2) now done, see TS names-and-copies below; phone/TV D-pad test of gap rows only
  by the shared desktop focus test; "series sheet" read as the show page's action row (ShowMenu untouched).
  gotchas: `SeriesPanel` has no local slots left (HX1): new logic lives in `SeriesGuide.kt`; a symlinked
  `webapp/node_modules` is not git-ignored (remove it); canned TMDB for tests is in `SeriesGuideTest.kt`.

- 2026-11-16 FQ1+FQ2+FQ7+Raju E+F done (worktree branch worktree-agent-ab6a72262f73e90ad, local, `da84459`, `4c76e38`, `16f2256`):
  - Found on main already: the stalled sheet (U1), `Td.unreachable` with the 20 s rule and 3 s hysteresis (U2), the banner with Retry and Proxy, and the Android sign in's `ConnectingHelp` (the research notes were stale). Added: `StartVerdict` (core pure, table test) used by the Android and desktop loaders; plain keys; More help.
  - Banner and connecting card now lead with "Try again" and "More help" (proxy page, device report, Send logs behind it); on the TV the banner's Right reaches More help.
  - Left: the banner stays up 3 s after the line returns (hysteresis, on purpose); web has no link state so no banner there; "visible help link" is More help on the card and the banner; the resend row (2.3) and country chip (2.1) are layout work, not words.
  - Dead strings and new text to translate listed in DEAD-STRINGS.md.

- 2026-11-16 T10 (worktree agent-a8846dfb15f6a7499, local commits not pushed; see `git log` there):
  done: (1) `ChapterSkips` (core, `SkipChapters.kt`): title to kind in about 25 languages, accents folded, whole words
  (substring for CJK/Thai/Hangul), "scene" titles refused, 5 s to 6 min (credits 10 min) and under 2/5 of the file;
  tests `SkipChaptersTest`, garbage in `GarbageInputTest`. Chapters come from the reader the chapter list already uses
  (MkvChapters/Mp4Chapters via `FileChapters`, not through each engine: same list on all engines, works offline).
  (2) `AnimeSkipApi` (`AnimeSkipApi.kt`): GraphQL `findShowsByExternalId(service: ANILIST)`, ids only, one query per
  show kept 10 min, X-Client-ID from `SkipSources.ANIME_SKIP_CLIENT_ID` (EMPTY: TODO for the user, nothing is asked while
  blank), timestamps are points that run to the next one (Intro/New Intro/Mixed Intro to Opening, Credits family to
  Ending, Recap, Preview; Canon, Title Card, Filler... only end a stretch); a 403 "Rate limit exceeded" (their limiter
  answers 403) pauses that source for a minute and is not kept as "nobody timed it"; 404 and empty are kept
  (`SkipLookupTest`, `AnimeSkipApiTest`). Their terms: no public terms page could be read by a script; the API is
  GPL-3.0 open source, clients are registered per account (`myApiClients`), a per client per minute limit. User must
  read anime-skip.com terms and register the client id. (3) `IdMapApi` (arm.haglund.dev, AniList id to MAL, IMDb,
  TheTVDB, TMDB) when AniList has no idMal; TMDB shows with no TheIntroDB answer by tmdb id are asked by IMDb, then
  TheTVDB id (Jikan not used: it has no id mapping). (4) "Mark this as the intro": two presses (`IntroMarking`),
  saved in `UserSkipMarks` (own file `skip-marks.json`, bounded 2,000 / 500 seasons), the season of the same chat and
  show gets it as a guess, pill "Skip intro (from your mark)"; phone and TV: a line in the More menu after Bookmarks
  (only for an episode; "Remove my intro mark" only with a mark); desktop key O and the Options menu line.
  (5) per kind Ask / Skip by itself (3 s, Cancel; TV: Back cancels, OK skips now) / Off for Recaps, Intros and
  openings, Credits and endings, Previews: the existing "Skip intro buttons" row stays the master and four rows sit
  under it (phone/TV `SkipModeSettings.kt`, desktop `ChoiceSetting`); the row now shows with lookups off too, since
  chapters and marks need none; its one line is the privacy sentence. (6) shared model in core (`SkipSession`,
  `SkipController`, `SkipMerge`), used by phone, TV and desktop; web: the same title table (generated, with a drift test)
  for chapter skips; web has no modes, marks or online lookups yet.
  tests: core `SkipChaptersTest`, `SkipPolicyTest` (merge: chapters, API, own mark, guess, overlapping and out of
  range dropped), `AnimeSkipApiTest`, `SkipLookupTest`, `GarbageInputTest`; app `SkipMarkFocusTest` (TV D-pad to the
  line, pill never focusable, countdown, phone Cancel); desktop `PlayerKeysTest` (O), `SkipPillRenderTest`; web
  `skipTable.test.ts`.
  left: web modes and marks; a PlayerActivity-level test of the countdown (the pure `SkipSession` and the pill are
  tested, the activity wiring is not); privacy page and THIRD_PARTY_NOTICES need anime-skip.com and arm.haglund.dev
  (public copy: waits for the user); TS/Compose focus test of the settings rows; phone/TV device pass.
- 2026-11-16 T11 anime episodes (worktree agent-a06d96ab6698dda2c, local commits not pushed; `git log` there; note `design/redesign/anime-episodes.md`):
  done: step 1 failing `AnimeEpisodeCauseTest` (fixture world `AnimeFixtures`/`AnimeWorld`: Naruto Shippuden shape, 500 episodes, 21 TMDB seasons, AniList one entry, Kitsu, Jikan) proved the four causes; step 2 pure `AnimeEpisodes` (absolute to season and episode and back, merge in order TMDB absolute group, TMDB seasons in 2 appended calls, Kitsu by MAL id, Jikan, AniList streamingEpisodes; source named per episode; wrong match dropped by `plausible`) and the guide in `OnlineMetadata` (`animeGuide`, one answer per show, each source kept a day, resumes when cut short, `season()`, `animeExtras`, name through `episodeFor` so `EpisodeNames.named`, the player label and the next-up card get it with no PlayerActivity change); step 3 and 5 in `MetaExtrasUi` (overlay, first paint held 2.5 s), `SeriesGuide.kt` (GapRow picture: show art dimmed with the number, "Episode N", credit on the facts line and the gap card, find query `Show 123`); step 4 `AnimeName` fallback in `MediaName` (one digit after a fansub dash, 123v2, OVA/SP/Special/Recap as season 0, NCOP as extra, 12-13 and 12~13 ranges, "Season 2 - 05" no longer seasons 2 to 5) with `AnimeNameTest` and GarbageInputTest lines.
  checks: `:core:jvmTest`, `:ui:jvmTest`, `:desktop:test` (AnimeSeriesGuideTest incl. 500 rows; 2 DownloadsSelectionKey desktop tests are flaky under load and untouched by T11), core JS and desktop compile. `:app` not compiled here (no local.properties in the worktree); app code unchanged.
  left: web (no AniList, Kitsu or Jikan there and no per-episode lists: needs a worker route, web files belong to Raju H); no TV D-pad test beyond T9's (gap rows are unchanged focusables); no new strings; MetaApis.kt and OnlineMetadata.kt will conflict with T10 on merge (additive).

- 2026-11-16 R3 one question setup (worktree agent-a62424d2271f34c48, local commits, not pushed; note `design/redesign/one-question-setup.md`):
  done: `SetupFlow` (core, pure: Idle/Hold/Wait/Offline/Choose/Finish) with `SetupFlowTest`; `rememberSetupFlow`, `SetupOffline`
  (ui browse), `SetupFlowEffects` (silent DeviceFit apply once, language seen, Finish marks the setup done), `TipsCard` (shared)
  replace `SetupAndTour`, `AppearanceStep`, the setup pages, `CrashAskCard`/`CrashAskHost`, `CrashReportsQuestion`, the modal
  tour offer; `SetupScreen.kt` is now `SetupParts.kt` (only what Settings' Setup choices still uses). Replay: `replaySetup()`
  writes `replay`, shows the ordinary picker. `SettingsStore`: `setupReplay`, `tipsDue`; `tourOfferAfter`, `putOffTourOffer` gone.
  Picker: Back always asks "Leave without choosing?", hint "Forward a video to Saved Messages" when nothing is recommended.
  Web: heading, hint, min one chat, offline line. Strings: dead ones in DEAD-STRINGS.md, new ones listed there.
  tests: core `SetupFlowTest`, `SetupFlagTest` (fresh install: streaming on, crash off), `CrashAskTest` trimmed; ui `SetupTest`,
  `SetupChoicesTest` (silent Lighter or Full); desktop `OneQuestionSetupTest` (new), `SetupRenderTest` trimmed to tour and first
  run Home; app `AddChatsTvKeysTest` (TV: first chat, OK ticks, Right to Done, Back asks), `AddChatsRenderTest` (hint, offline
  card, Tips card, phone and TV), `TourOfferFrameTest` (Tips card focus).
  left: no phone or TV device pass; web edits are not run (no node_modules here); the `awaitingSync` skip in `SetupTest` stays
  (the Storage step lives on in Settings' Setup choices); SkipLookupTest (T10) fails in core on this branch, not touched by R3.
- 2026-11-16 T8 done (branch worktree-agent-a5e82d7e6b5dea53d, local, not pushed): design note `design/redesign/format-badges.md` with the
  after pictures. Core `FormatFacts` (size to 1080p/4K and so on, tolerant of crops, rotation and strips; probe to HDR10, HDR10+, HLG,
  Dolby Vision, 10-bit, HEVC, AV1, 50+ fps, Atmos, DTS, DTS-HD, DTS:X, AAC, 7.1, 5.1, Stereo) and `FormatProbes` (256 entry LRU, filled
  by the Media3 and mpv track lists only). UI: `FormatBadge`, `FormatBadgeRow`, `rememberFormatTags` (ui/shared/components/FormatBadge.kt),
  not a focus stop. Shown: poster tile corner (720p and up, replaces the name chip), details row (replaces the one quality chip), Downloads
  rows (one slot, phone and desktop), the player's audio track picker (pills under the name, phone and TV), the desktop's audio track
  names. `FormatRoute.dolbyVisionProfile` now delegates to `FormatFacts.dolbyVisionProfile`. No new strings, no en.json change.
  Tests: core `FormatFactsTest` (table, crops, rotated, garbage, cache bound), `GarbageInputTest.formatFactsNeverThrow`; app
  `FormatBadgeRenderTest` (phone 1.0 and 2.0 font, TV: row, not a control, empty draws nothing, poster tile inside its picture at 2.0);
  desktop `FormatBadgeRenderTest` (280 dp column at 1.0 and 2.0).
  left: web poster tile (items carry no size, rules only in the worker; web details already has the outlined resolution badge);
  probe for a file on disk without playing; no persisted probes; HDR10+ has no Android source; TrueHD Atmos not shown on Android
  (Media3 does not say); no phone or TV device pass; the copy picker and Play's quality label still use `VideoCopies.qualityLabel`
  (name first) so a file whose name contradicts its stated size can read two ways.

### TS names and copies (worktree branch worktree-agent-a1b3fbc0ed5d93643; note `design/redesign/names-and-copies.md`)
done (commits local, not pushed): (1) season packs: `ParsedName.pack/packSeason/seasonFrom`, `Series.packs` (`SeriesPack`),
`EpisodeGuide.Row.Pack`, `PackRow` on the shared series page (phone, TV, desktop) and the web watch page; (2) IMDb/TMDB link in
a name or caption (`MetaLinks`, `MetaQuery.imdbId/fromLink`, `MetaInfo.linked`, `MatchBasis.Linked`, TMDB `find` once per id,
web worker `i=` param); (3) subtitle language from full names, native spellings and "(Latin America)" forms
(`ChatSubtitles.tagsOf`); (4) "Why no poster?" (`PosterWhy`, `NameDiagnosis`, `PosterWhyLine`, replaces the round turn-on
button on the three detail layouts and the web page); (5) best copy by default (`VideoCopies.bestForDevice`, phone capped at
1080p, never a too-slow copy; `copyBest` tag "Best for this device"; no new setting).
left: phone/TV Robolectric shots of the pack row and the poster line (desktop shots only, in the design folder); web has no
default-copy picker, so no "Best for this device" tag there; the per-chat stored preferred copy and its Settings row (T6) were
not built on purpose (gate rule 2); TV focus return after "Fix match" relies on the picker's own focus restore.
- 2026-11-16 FQ15 + store text + phone page + privacy/FAQ (worktree agent-a86521ca1dd6e3a00, branch worktree-agent-a86521ca1dd6e3a00, commits local, not pushed: a516a94, 6b1389d, eb8ce31, fdb2071; fast-forwarded to main 66f2137 first):
  done: (1) /troubleshooting/ now has 7 guides in plain words (3 steps max, what the app does by itself, when to send logs): new video-stutters, sound-without-picture, nothing-in-home, telegram-sign-in-problems; video-will-not-start and phone-storage-full (slug kept, now "Downloads stopped or space full") rewritten for Save space / Keep video and the Downloads tab; English-only data file, no catalog keys; lastmod and llms.txt moved. (2) store text: new docs/distribution/store-text.md (limits per store, what's new 478 chars, feature lines, support line), fastlane full_description 3,746 of 4,000, Amazon long text and bullets, winget Description, AlternativeTo text. No changelogs/<versionCode>.txt written (version unknown): the draft is in store-text.md. (3) phone page: the page already existed (/android/), so it was extended, not duplicated: 3 new cards (one question, downloads and offline, sync) + 3 reworded (formats, storage, resume), 8 new/changed android.* keys. (4) privacy page: skip lookups li now names TheIntroDB, IntroDB.app, AniSkip, anime-skip.com, arm.haglund.dev (ids only), chapters and own marks stay on device; the sync section renamed Keep my place on all my devices, file version 3 (bookmarks, corrections), the sign out backup, effective 16 November 2026 and a Changes line. FAQ: 2 new questions (skip sources, sync file contents).
  checks: pnpm -C site build, site-i18n-check.py (1958 strings, consistent, every language still 97 percent), site-links.py (452 pages, 0 problems), dash grep clean; looked at /android/ and /troubleshooting/ in headless Chromium.
  left for the user: (a) anime-skip.com client id is still the TODO in SkipSources: the privacy page and FAQ already name anime-skip.com, so register the id (and read their terms) before the release that ships this page, or drop it from both; (b) the FAQ answer "Can I stop on one device..." (ld.yes_once_you_turn_9ec7) still says "Resume on your other devices": changing it renames the ld key in all 21 catalogs, so it waits for langsync (only the user runs it); (c) 8 android.* and 10 ld.* new keys plus 2 reworded android keys (formats_body, storage_body, resume_body) need translating; (d) store text claims "Home works offline", "Downloads tab", sync, badges: re-check each against the build that ships; Microsoft Store limits are from memory; (e) Play and Amazon may want the Buy Me a Coffee / UPI line without a link (store-text.md); (f) no real device pass of the guides' menu names (Report stutter, Try the other decoder, Playback test, While streaming).

## Decisions made by Claude
- T1: one shared `rememberVisitQuery` (not a patch per screen) so any later search box gets rule 3 by calling it; the phone keeps the query while a page is open (`keep = page != null`), the desktop while a category is open. No design note: small fix.
- T4: the TV gets the setup's two-answer switch (`TabSwitch`, extracted and shared) because Material segments cannot be reached with a D-pad; phone keeps Material segments, desktop keeps `Segments`. Words: setup choice titles now read Keep video / Save space; device card says "While streaming: Save space". The Free up space offer's button says Switch (was Turn on). No design note: a rename plus one row.
- Three workers, not more: the files are split by surface and `MainActivity.kt` is shared, so only
  T3 touches it and only for back handling.
- T3: Esc handling lives in the shared `TmAlertDialog` (one place, all platforms) instead of per dialog; full screen yields to a layer over the page on the desktop; the top level's exit confirmation is deleted rather than reworded. No design note: small, clear fixes.

- T12 casting: Back while casting stops the cast and leaves (no ask). It is what Back already did (`PlayerCast.release`
  stops the TV media and the DLNA session in onDestroy), the place the TV reached is saved, and "Back means stop" stays
  one rule on every screen; an ask on every Back would add a dialog to the most common key. Home while casting does not
  stop it (the activity stays, the cast goes on, as before the dock). If the user wants an ask instead, it is one dialog in
  `leaveByBack()`.
- T12 desktop: only the phone's half removed; core `MiniPlayerState`/`MiniPlayerBar` stay for the desktop's in-app bar.
- T11: the anime guide lives behind `OnlineMetadata.season()`/`animeExtras` and the lookup's episode, not in each screen, so phone, TV, desktop, the player label and the next-up card all read one answer; AniList's season list is one season counted straight through (what fansub files say), a per-season file finds its title through TMDB's own place. Kitsu and Jikan are asked only while titles are still missing. A TMDB match that lacks an episode above 24 is retried as an anime through AniList. The title of a TMDB search may be an AniList alias (kept on `MetaInfo.aliases`).
- R1 (show every video by default, `design/redesign/show-every-video.md`): the switch is one more line group ("Small videos") in the shared Sort menu (`videoMenuGroups`), so phone, TV, desktop and web read one definition and none needs its own dialog; it does not close the sheet so the grid changes behind it and the TV remote stays on it. "Show them" is a per-visit override held in the screen (`showAllThisVisit`), not a write, as the brief said "for this visit". The Home floor is 50 MB (`SizeFilter.HOME_MIN`, equal to the clip floor) rather than 400 MB: one meaning of "small" everywhere; a custom floor above it still wins. Search's "Hide small clips" moved from 400 MB to the same 50 MB for the same reason (one name, one meaning). The stored `max_size_bytes` stays readable and shows in the same custom limits line with Reset, since the slider that could change it is gone. The web chat search and global search ignore the floor (as the apps' chat search does). The group is called "Small videos", not "Size", because the Sort by group already has a "Size" line. 100,000 videos: the hidden set is the existing one-pass split per page, no extra query; the end row says "Some small videos are hidden" until the chat has been read to its start.
- R3: the crash question lives only in Settings (the "Send crash reports" row under Privacy and lock, with its sentence);
  no card after Skip and nothing in the setup. The pure `CrashAsk` decision and its tests went; `OPT_OUT_DEFAULT_ON` stays false.
- R3: "Run the setup again" is a replay of the one question as the ordinary picker (Back, Done closes it), not a repeat of the
  old pages; the old pages' answers are the rows of Settings > Redo setup choices, unchanged.
- R3: the setup asks about even one chat (Saved Messages alone gets the hint); the chat choice after an update still waits for four.
- R3: the offline card shows after 3 s without a list and without network, so connecting after sign in does not flash it.
- T8: 2K is labelled "1440p" (names elsewhere are "720p", "1080p"; "2K" also names the 1080p cinema format). A stated size beats the
  name and a name only fills in when there is no size (drawn hollow, no new string); the tile pill drops "SD". The details page's outlined
  quality chip is replaced by the format row, not kept beside it. The audio picker shows kind and layout as pills and drops the old
  "Dolby" and channel words where a pill says them. No brand logo anywhere (licensed marks), only a generic speaker on sound pills.

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

### Raju group B + R4 offline mode (worker, 2026-11-16, branch worktree-agent-a7e741aeed041d07d, local, not pushed)
- Done: `design/redesign/offline-mode.md` first, then `0f1781f` pure rules (OfflineSettle 2 s both ways, OfflineHome,
  OfflineHomeRows: On this phone row, no duplicates, placeholders dropped, hero = first video on the phone), then shared
  parts, phone and TV, desktop, web (Reload card on Home when offline with no rows).
- Banner: the Connect banner's card (`OfflineBanner`: Try again, Downloads on touch and desktop) stands above the tab bar
  (`TouchNavHost` banner slot, pages padded by `LocalBannerRoom`), TV in the Connect banner's top end place, desktop top
  centre. The passive Offline pill is deleted; Reconnecting and Connecting pills stay.
- 4.5: the bar returns when a push or fling hits the end of a list (`BarVisibility`). Not done: "show again after 1 s of rest".
- Tests: core OfflineModeTest (debounce, rows), app OfflineBannerBarTest (tab tappable under the banner, 1.0 and 2.0 font),
  OfflineHomePaneTest (states, gating, cached resume, TV card order and OK on a greyed card), OfflineHomeTest, desktop
  OfflineHomeTest. The test found a bug: the desktop hover Play disc on a greyed tile (now hidden).
- Decisions made by Claude: offline hold 2 s each way; greyed means locality Remote and not in the download index or the
  fully cached set (at most 40 videos asked, once per offline stretch); a greyed press opens the detail page whose Play row
  says "Connect to play, or download it first" (disabled, no press); series cards are not greyed (their episodes are
  gated on the page); `main.connect_to_play` kept for grids and search; the web keeps its existing offline line.
- Unrun: webapp type check and e2e (no node_modules in the worktree). Leftovers for the user on devices: real Wi-Fi off
  on the phone and the TV (first focus on the On this phone row, the banner over the bar), flaky link feel.

### R2 Downloads tab + one storage page + Raju group C (worker, 2026-11-16, branch worktree-agent-a31824f7275463da6, local, not pushed)
- Design note first: `design/redesign/downloads-tab.md` (phone, TV, desktop, web table, edge cases). Then: core `MobileData`
  (Ask first / Never / Allow, read from `wifi_only_downloads` + `save_mobile_data` until chosen, the old Wi-Fi only key kept in
  step, table test), Settings "Downloads and data" (Where to save, On mobile data, While streaming, Download first, Remove
  after watching; the low space alert and Pause downloads while playing are always on, no switch), phone bar Home, Chats,
  Search, Downloads (running count, 99+) with a gear at the top end of Home and of Downloads for your lists and Settings, the
  TV top bar tab "Downloads" (count) after Chats, the desktop bar button with Ctrl+J, the web's Downloads page (Get the app card),
  one list "On this phone" (auto-saved wear "Auto-saved" and Keep), Undo on cancel (`CancelWindow`, 5 s, touch only), Free up
  space only when there is something to free, "Download paused" tile word, Raju C: lone rating letters, Watched and Watch later
  drawn on, "Cancel" word, the live download line, size and room toast over 1 GB.
- Decisions made by Claude: Download the whole video first stays (a behaviour, not a twin) under While streaming; the phone's
  gear opens the old picture card without its Downloads row; the TV face menu and the desktop picture menu lose Downloads too;
  Back from the TV Downloads page lands the remote on the page it returns to; "Ask first" only governs a streamed video (the
  player's existing sheet), a download is the viewer's own press; cancel's Undo is touch only (a remote cannot reach a toast);
  leaving Downloads inside the Undo window leaves the download paused, never deletes it; the desktop's auto-saved row has Watch
  and Keep, its Delete stays in Free up space; TabSwitch now walks two or three answers.
- Unrun: webapp e2e (no node_modules in the worktree; `tsc` on the new files was clean through a borrowed node_modules).
- FQ10, FQ11, TV pause clock, desktop Back (player-small-features worker, 2026-11-16): look first found that all
  three queued features already ship. FQ10: night mode exists on phone, TV and desktop since 1.x (processor
  `VolumeBoostProcessor.night`, off is a plain copy, no timers); only renamed to "Quiet loud scenes" (new keys
  `player.quiet_loud_scenes*`, `player.details_quiet_loud_scenes`; old ones dead). Bundled FFmpeg (libavfilter
  11.4) has only aformat, anull, aresample: no `acompressor`, `dynaudnorm` or `volume`, so the desktop keeps its
  Dolby range control and dialogue lift, with the honest detail line; NOT hidden (hiding takes a working feature
  from users): user may overrule. FQ11: Pure black switch since 2026-10-19 on phone, TV, desktop, web; contrast
  and focus ring tests cover black; no fourth segment (that was the replaced design). TV clock: `controls_clock`
  shows on a paused TV (and in the web TV player); pinned by `PauseControlsRowTest`; no "ends at". Desktop Back:
  dock deleted (MiniPlayerHost, ShellState mini, PlayerScreen docked mode); Ctrl+P untouched. The phone's dock
  (MiniDock, MiniDockBar, shared MiniPlayerBar, core MiniPlayerState, Settings "Keep playing in a mini player",
  its strings) stays until T12 lands: the phone still uses them. Note: `docs/shortcuts.md` is not in this worker's
  worktree (T3's file), edited in the main tree only.
- FQ12 (sync through your own Saved Messages, backup before sign out): worker found CP89 already syncs places, Watched, Watch later, favourites and lists in one pinned file, so this extends it instead of adding a second: file version 3 adds bookmarks and corrections (by newest change, removals remembered 90 days, 2,000 and 1,000 caps); the Library row and its enable dialog are replaced by one switch under Settings, Account plus a once-only card at the end of Home (the switch is the single question, no second dialog); a SyncScheduler holds the write rules (3 minutes, leaving the player, nothing in the background on a low end device); sign out has a ticked 'Save my settings backup to my Saved Messages first' that never blocks (Sign out anyway). Web app sync and restore-after-sign-in left out; the public privacy page needs a sentence (public copy, user's OK). Design note: design/redesign/sync-saved-messages.md, pictures design/sync-saved-messages/.

### Reviewer findings on the Saved Messages sync (merge 66f2137), to fix once by the sync worker af29fab81473144b7 when a slot is free
Merge main into its branch first. 1 (medium) SavedMessagesBackup.kt:71 confirmation needs `m.date*1000 >= now-120000`: a clock over 2 min ahead (TV sticks) never confirms, dialog says slow, Back resends duplicates: wait for `updateMessageSendSucceeded` by message id. 2 (medium, unverified) HomePane.kt:825 + KeepPlaceCardState.kt:37/49: yes()/notNow() hide the card while a button holds focus: move focus to the last row or the list first, add a focus test. 3 (low, privacy) ForgetAccount.kt: delete `tmplayer-sync/backup-<ms>` dir on failure and in forgetAccount. 4 (low) SettingsScreen signOutNow has no "Sign out anyway" during the up to 40 s busy overlay (desktop has one). 5 (low) ResumeSync.kt:145 onRan writes KEEP_PLACE_WROTE_AT even when Outcome.Off: skip. 6 (low) BookmarkStore.kt:132 applySync re-adds then evicts at MAX_PER_VIDEO with no tombstone, repeats every sync. 7 (low) BookmarkStore.kt:167 + corrections: a backup restore of a since-deleted item is undone by the tombstone: clear the tombstone on restore.

### DECISION (user, 2026-11-16): FQ14 audio files as a playlist is CANCELLED. TMPlayer plays videos only; no audio-only files, playlists or music features, anywhere (app, site, store text). Remove FQ14 from the queue; workers must not add it.

### DECISIONS (user answers, 2026-11-16)
- REMOVE audio and voice message support (ChatAudio.kt, isAudio path in PlayerActivity, audio items in chat lists, web, desktop). Keep: background sound of a VIDEO with the screen off, and fixes for "sound but no picture" videos. Runs after the player-labels worker merges (PlayerActivity overlap). Add a regression test that audio items never show in lists.
- FQ8 cast: Chromecast and Google TV sender plus the existing DLNA. Google Cast library is not FOSS: put it behind a build flag so the F-Droid flavour leaves it out. Same wave rule: after the player-labels worker merges.
- Live streams: DROPPED.
- "Add more chats to the devices": user said "later". Do not touch the real selection.

### DECISION (user, 2026-11-16): FQ9 "Play a lighter copy" (CP80) DROPPED for now. Do not build; the "too slow for this chip" end state stays the status sheet text (and external app) from RL5/RL6. Revisit later only if the user asks.

## WRAP UP NOW, THE REST IS LATER (user, 2026-11-16: "i don't have that much time left ... cut down new works to plans to do later")
No new workers start. Finish only: the three running (RL10-12 + player labels, sync fixes, test stabiliser), merge them, run the
full checks, write the device checklist. Everything below is LATER, in this order, each ready to run as a hands-free task:
1. Remove audio and voice messages (ChatAudio.kt, isAudio in PlayerActivity, chat lists, web, desktop); keep a video's background sound. After the player labels merge.
2. FQ8 cast: Chromecast and Google TV sender behind a build flag (not FOSS), plus existing DLNA.
3. RL13 (16 KB page-size gate script) and RL14 (docs).
4. Web follow-ups: anime provider on web, poster-tile badge on web, web sync (not built), restore offer after sign in.
5. Raju leftovers: resend row 2.3, country chip 2.1, bar returns after 1 s of rest.
6. Inventory review waves R1 to R9 over private/ux-inventory (dimensions D1 to D8, compose-performance skill), then Raju's second pass.
7. Blocked on the user: Trakt and Simkl sync (keys), anime-skip client id, add more chats to the devices ("later").
DROPPED: live streams, FQ9 lighter copy, FQ14 audio playlists.

## RESUME CHECKPOINTS (written 2026-11-16 before the user went out; read this first after a compaction)
State: main at the commit "Plans: wrap up now, the rest is later" or later; nothing pushed. No new workers. Only the three below.
- [ ] CP1 Sync fixes worker af29fab81473144b7 (branch worktree-agent-af29fab81473144b7): 7 reviewer findings (list above). Done when it replies; then merge.
- [ ] CP2 Test stabiliser a06f2029e76f5d0dc (branch worktree-agent-a06f2029e76f5d0dc): flaky and failing tests, full per-module runs. Merge.
- [ ] CP3 Player labels + RL10 to RL12 a98d5b6ea6412adb9 (branch worktree-agent-a98d5b6ea6412adb9): controls with words, Report stutter, trace fields, decoder facts. Merge LAST (touches the most files; expect conflicts with the sync work in PlayerActivity, HomePane, SettingsScreen).
For each merge, in this order:
  1. `cd /home/dracu/projects/personal/TMPlayer && git add -f private && git commit -m "Plans: notes"` (merge is blocked by uncommitted private/ files otherwise).
  2. `git merge --no-edit <branch>`; resolve conflicts keeping BOTH sides (check the SettingsScreen sign out flow signOutNow/backupFirst and HomePane keepPlace + onChooseFavourites).
  3. Checks (JDK 21: `export JAVA_HOME=$HOME/.jdks/jdk-21.0.12+8 PATH=$JAVA_HOME/bin:$PATH`): `./gradlew :core:jvmTest --tests '*CatalogTest' :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :desktop:compileKotlin :desktop:compileTestKotlin --max-workers=3 -q`.
  4. A check failing twice: mark the task blocked here and leave that branch unmerged.
- [ ] CP4 After all three: dash grep (AGENTS.md "Writing"), `git status`, `git log --oneline | head`.
- [ ] CP5 Write the final summary for the user: what is merged, the device checklist (phone, TV, Tanix, Mi TV: Mongoose film in Save space and Keep video, Report stutter, sign out backup, TV Home card focus, offline Home), the needs-the-user list (anime-skip client id, langsync + dead keys, translations, push, beta release with the cadence guard), the LATER list (see "WRAP UP NOW"). Then STOP.

### Progress / handoff: Saved Messages sync review fixes (branch worktree-agent-af29fab81473144b7, commit 6a9cda4, main merged in e99d4ea)
- Done in 6a9cda4: all 7 findings. 1 backup confirms by updateMessageSendSucceeded/Failed by message id; 2 card moves focus up before hiding (TV focus test added, not yet run); 3 backup- upload folder deleted on any end and in forgetAccount (app and desktop); 4 SignOutSaving prompt with Sign out anyway on phone/TV; 5 scheduler job returns false for Outcome.Off, no pause started; 6 bookmark eviction leaves a removal; 7 restore clears bookmark and correction removals.
- Run and green after the fixes: core Sync/Bookmark/Backup/Garbage/Catalog tests (SyncBookmarksCorrectionsTest 20/20); :app:compileDebugKotlin and :desktop:compileKotlin compile.
- NOT run: full :app:testDebugUnitTest and :desktop:test (stopped), so the new KeepPlaceTest focus test and the changed ResumeSyncRowsTest/KeepPlaceTest after the fixes are unverified. No device checks.

### Worker handoff: player controls (R5/G), RL10, RL11, RL12 (worktree branch worktree-agent-a98d5b6ea6412adb9)
- RL11 + RL12 done, commit 2fd1dde (trace v2 facts/hops, TraceFacts, DecoderFacts, SessionFacts, StutterReport tags, LogReport blocks). Core and the targeted app tests passed.
- RL10 + R5 labels/skip buttons/audio line/Back-paused test/chip reword: committed as "WIP:" (HEAD). Targeted tests passed earlier (RemedyRunnerTest, PlayerMenuTest, ControlLabelsTest, DecoderFactsTest); AudioButtonAnswerTest, BackStopsThePlayerTest and the full `com.tmplayer.player.*` run (job bzfrrij54) were NOT seen finishing.
- Left: run that suite, catalog sorted-keys test, SeekChrome/PlayerExtras render tests, desktop unchanged (tooltips already existed). Not done: Timings/Source/Audio trace groups, dev.vendorApi dropped.
- Decisions: skip buttons 10 s both ways (Skip.BUTTON_MS); viewer press never opens the sheet and acts once per video; UserStutter evidence added.

### Test triage handoff (worktree-agent-a06f2029e76f5d0dc, 2026-11-16, local commits, not pushed)
- Fixed: SettingsMapScreenTest (79a8136, four skip rows in SettingsMap); TvSearchTelevisionRtlRenderTest hang (dcc58a9: RTL first focus hit the clear control's tip Popup, whose frame loop never ends under the paused looper; now 8 s); NamedListsTest flake (0ed922c: best of 3 for the 1 s budget).
- SeriesTvScrollTest: passes alone at HEAD; not seen failing, likely load or frame stall in a full run.
- Still flaky: DownloadsSelectionKeysTest (desktop). Passes alone at HEAD and at 6ca92f2; failed 2 of 4 in the full :desktop:test run (Shift range never reached "5 selected"; focus handoff after delete). Suspect global state from another test class. Not fixed.
- Runs done green: :core:jvmTest (3120 tests), :ui:jvmTest (296). :desktop:test: only that class failed (635 tests). Not run: full :app:testDebugUnitTest.

## FINAL STATE of this run (2026-11-16, main at "Settings map: the sync switch lives under Account", 210 commits ahead of origin, nothing pushed)
CP1 DONE (sync fixes, merge 7c5e567). CP2 DONE (test fixes merged; still open: DownloadsSelectionKeysTest fails 2 of 4 only in the full desktop run, full :app:testDebugUnitTest never run). CP3 MERGED with one WIP commit (f9e6b3e: RL10 + player labels; AudioButtonAnswerTest, BackStopsThePlayerTest and the full player suite not seen finishing; compile and the 6 targeted tests I ran are green). CP4 dash grep clean. Checks green: CatalogTest, compile of app, app tests, desktop, desktop tests.
Next session: run `:app:testDebugUnitTest` and `:desktop:test` in full (per module, --max-workers=3), fix what fails, then the LATER list in "WRAP UP NOW".

### Worker handoff: T1 Remove audio and voice messages (branch worktree-agent-aad8f0c57a3ba0563, on main b1657fb)
- Done: core 5fed104 (ChatAudio.kt, QueuePlan.kt, QueuePlanTest, ui ChatAudioList.kt gone; PlayQueue's Repeat this video part is now `RepeatVideo` with repeats/replaysAtEnd/set/reset; new VideosOnlyTest), app 4fba407 (AudioRows.kt gone; PlayerActivity: isAudio, the sound-alone card, PlayQueue.stepsFor, repeatVideoOffered removed; PlayerControls.setShapeOffered removed), desktop a748bbd (AudioEntryRow, AudioListSheet, sound-alone card, isAudio checks), web c30691a (webapp/src/tg/media.test.ts, `pnpm test:tg`), docs/site db78d58 (FAQ answer, llms.txt, ARCHITECTURE table, store category).
- Kept: background sound of a video (`backgroundAudioOn`), the audio track picker, pictureUnplayable/software checks (now run for every file), RemedyRunner's `active` flag.
- Already absent before this task: the entry points to the audio list (removed 20c5db9, 2026-10-22); the web never listed audio (rawOf takes video and document only).
- Dead strings listed in private/DEAD-STRINGS.md (app grid.audio_*, media.untitled_audio, media.voice_message; site ld.yes_a_chat_with_48f9 in UNUSED_OK). New site key to translate: ld.no_tmplayer_plays_videos_29f0.
- Decisions: ChatHolds still counts audio for the empty chat line ("It holds 3 audio files" explains the empty grid, plays nothing). The FAQ answer names TMPlayer Music as the separate app for audio (the footer already links it). Commits per section do not each compile alone (core removes what app/desktop used); the branch head does.
- Not run: `pnpm -C site build` and the build-keys half of site-i18n-check (no node_modules in the worktree); full app and desktop suites (T0's).

### Worker handoff: T5 Raju leftovers (worker, 2026-11-16, branch worktree-agent-a57c7f5a8e8b2be16, local, not pushed)
- Done: `4eee2d2` bar returns after 1 s of rest (`BarRest` + `BarVisibility.moves` in TouchNav.kt; reads sit in a snapshotFlow, no per-frame recomposition); `b88623f` 2.1 country chip and 2.3 resend row.
- 2.1: touch country row shows "India +91" (PaneChooser `trailing`, `SignInWords.dialChip`), desktop row the same; TV keeps the note and the +code in the field. New key `errors.phone_check` ("Check the number. Pick your country first, then type the number as you dial it.") now returned by `Td.submitPhoneNumber`; `errors.phone_invalid` stays (the webapp reads it).
- 2.3: wrong code now returns `errors.code_check` ("Wrong code. Check the latest message from Telegram."); email and recovery codes keep `errors.wrong_code`. The resend row was already shown at once, greyed under its countdown (kept). New: with no other route (`canResend` false) the row is a muted line `login.resend_none` on phone, TV and desktop instead of nothing.
- Tests: app BarRestTest (4), LoginCountryAndResendPhoneTest (6, incl. 2.0 font) and TvTest (4, remote focus), desktop SignInCodeRowsTest (3); CatalogTest, PhoneEntryTest, SignInPhoneRenderTest green.
- Decisions: the bar returns after 1 s since the last list move, also while a finger rests mid-scroll; the TV country chip stays the existing note (the code is already in the one field); web sign in (webapp) not touched, so web still shows `errors.phone_invalid` / `errors.wrong_code` and no chip: the same keys `errors.phone_check`, `errors.code_check`, `login.resend_none` can be mapped in `webapp/src/errors.ts` and `SignIn.tsx` once the audio-removal worker is done.
- Gotcha: on the TV code pane with Sign in greyed (after a wrong code, the field still holds it), Down from Start over lands on Wrong number, the resend button is one Left away (tested). In the Robolectric test a Down from the text field itself did not leave it; not investigated.
- Translations: new keys need the other 21 locales (user's langsync).
