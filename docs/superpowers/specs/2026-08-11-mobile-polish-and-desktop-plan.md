# TMPlayer polish plan: phone native, TV safe

Date: 2026-08-11, fully revised after a four-way codebase audit (playback path, secondary
screens, data and performance layer, design language against Telegram Android). This file is the
working plan and is written so work can resume from it alone after a context compaction.

Scope decisions, made by the user on 2026-08-11:

- **Desktop port and web version are DROPPED.** The app targets Android phone and Android TV
  only. (The earlier desktop research concluded a JVM port was feasible but a real project;
  a web version was rejected because browsers cannot play MKV/AC3/DTS. Kept here as one line
  of history, not as work.)
- **Nothing is implemented until the user explicitly says so.** This document is the only
  deliverable until then.
- The yardstick for the phone experience is the Telegram Android app and stock Material 3:
  the app should disappear into the native idiom, not decorate itself. TV keeps its own idiom.
- TV must not regress: every layout change is behind `FormFactor.isTv` / `isTouch()` unless
  explicitly marked "TV too".

Working tree note: three files carry kept, uncommitted changes that predate this plan. Build on
them, do not revert: `ui/components/Skeleton.kt` (adaptive skeletons), `ui/browse/MediaGridScreen.kt`
(touch art weight share), `ui/auth/LoginScreen.kt` (input filtering, verified coherent).

Anchors below are `file:line` at audit time (2026-08-11) and will drift as edits land.
All paths are under `app/src/main/java/com/tmplayer/` unless they contain `res/` or start
with another root.

---

## Phase A: Correctness fixes (broken behaviour today)

A1. **Next/previous episode is broken by launch mode.** `PlayerActivity` is
    `android:launchMode="singleTask"` (`AndroidManifest.xml:56`) with no `onNewIntent`;
    `playEpisode` does `startActivity` then `finish()` (`player/PlayerActivity.kt:549`), so the
    new intent routes to the live instance (extras never re-read) and `finish()` closes it.
    Also means relaunching from the launcher kills an active playback. Fix: drop `singleTask`
    (plain `standard` is fine for a player launched from the grid) or implement `onNewIntent`
    that re-reads extras and re-prepares. Verify next/previous on device afterwards.

A2. **Resuming a half-watched video deletes its own partial download.** `MainActivity.play()`
    calls `Td.clearMediaCache()` (`MainActivity.kt:228, 237`); `CachePolicy.decide` treats only
    `Complete` as cached (`data/CachePolicy.kt:42`), so a `Partial` file of the video being
    opened takes the FreeUp branch and is wiped, restarting from byte zero. Fix: pass the
    target file id into the policy and exempt it (Partial of the target counts as keep).

A3. **Downloads are never cancelled.** Player exit never calls `cancelDownloadFile`
    (`player/TdDataSource.kt:140-151`, `player/PlayerActivity.kt:831-841`): backing out of a
    12 GB file leaves TDLib downloading it to completion in the background. Thumbnails: the
    awaiting coroutine is cancelled on scroll-away but TDLib is never told to stop
    (`data/Thumbnails.kt:54-62`, `ui/components/Art.kt:42`). Fix: cancel on close in both paths.

A4. **Double keyboard padding on every phone sign-in pane.** `safeDrawingPadding()` already
    includes the IME inset, then `.imePadding()` adds it again (`ui/components/PaneLayout.kt:88-89`,
    `ui/auth/CountryPicker.kt:70-71`). Content floats a keyboard height too high. Fix: drop the
    redundant `imePadding()`.

A5. **Navigation state does not survive rotation or process death.** `var screen by remember`
    (`MainActivity.kt:175`); rotating inside a chat or Settings dumps the user to the chat list.
    Same for `signInError`, `roomPrompt`, and typed login values (`ui/auth/LoginScreen.kt:220,
    263, 397, 445`). Fix: `rememberSaveable` throughout; `Screen.Media` holds a non-parcelable
    `ChatSummary`, so save the chat id plus minimal fields (custom Saver) and rehydrate.

A6. **Login dead ends.** `QrPane` has no back handler, no header, no button
    (`ui/auth/LoginScreen.kt:653-726`): choosing QR then changing your mind means killing the
    app. `MethodPane` also lacks a `BackHandler` so Back exits with no two-press guard
    (`LoginScreen.kt:133-203`). Fix: back affordances on both panes.

A7. **Chat list refresh silently does nothing after a rename or avatar change.**
    `ChatSummary.equals` is identity-only, so `MutableStateFlow` conflates a refreshed list with
    unchanged id order (`ui/browse/BrowseViewModel.kt:97-104`, `data/ChatRepository.kt:21-30`).
    Fix together with E4 (real value equality / stability), not with another revision counter.

A8. **Dead pagination recovery.** `loadMore()` called from inside the coroutine held by
    `pageJob` is unconditionally a no-op due to its own `isActive` guard
    (`ui/browse/BrowseViewModel.kt:187, 333`). Fix the recovery path and cover it with a test.

A9. **Humanised TDLib errors are discarded at the player.** Flood-wait, file-reference-expiry
    and disk-full are wrapped into readable sentences (`player/TdDataSource.kt:272-282`,
    `data/Failures.kt:20-59`) but `friendlyError` never reads `error.cause?.message` and shows
    "Try a different copy of it" for everything (`player/PlayerActivity.kt:491-510`). Fix: map
    the cause chain first; pair with D9 (retry action).

A10. **Keep-screen-on is never released** while paused or on the error sheet
     (`player/PlayerActivity.kt:166`). Fix: clear the flag when not playing.

A11. **Td housekeeping.** `clientLoop` leaks one uncancelled child per client generation
     (`data/Td.kt:120-128`); dead `SettingsStore` field (`Td.kt:87, 92`); uncalled
     `clearMediaCacheInBackground` (`Td.kt:406`). Fix or delete.

---

## Phase B: Phone-native redesign (the Telegram yardstick)

Principles, applied to the touch branch only: one Material 3 theme with the default M3 type
scale; stock components over bespoke ones; actions live in toolbars and overflow menus, never
as labelled pills in content; no explanatory blurbs inside list screens; full-bleed rows, not
cards; ripple on every tappable; one small set of radii and paddings; density where content is
dense. Intentionality over decoration: the phone app should read as if Telegram shipped it.

B1. **One Material 3 theme for the whole touch tree.** Today only the drawer and app bar are
    M3; everything inside the pane is TV Material with the 10-foot type scale (root at
    `MainActivity.kt:88-91`, scale at `ui/theme/Theme.kt:60-73`, M3 island at
    `ui/browse/TouchNav.kt:76-93`). Move `TouchMaterialTheme` up to wrap the entire touch app
    (including Settings, auth, dialogs), give it M3 default typography, and purge
    `androidx.tv.material3` imports from every touch code path (full list in the audit:
    BrowseScreen, MediaGridScreen, SettingsScreen, TvSearchField, StateScaffold, TvMenu,
    TvConfirm, Art, ConnectionStatus, AuthFields, CountryPicker, UpdateDialog, IntroScreen,
    OverviewScreen). This single change fixes the "AI prototype" type feel: 16sp medium row
    titles over 14sp subtitles instead of 21sp semibold over 13sp.

B2. **Chat rows stop being cards.** Replace the 84dp, 16dp-radius, bordered, 14dp-gapped cards
    (`ui/browse/BrowseScreen.kt:1265-1332, 1098-1105`) with full-bleed 72dp rows on the window
    background: 54 to 56dp avatar, M3 `ListItem` metrics, no radius, no gap, ripple across the
    row. Grid tile mode stays but adopts the same type and radius discipline.

B3. **Search moves into the app bar.** Kill the always-visible search pill plus mic and Clear
    pills (`BrowseScreen.kt:997-1071`). Idiom: magnifier icon in the `TopAppBar` expanding into
    a full-width in-appbar text field, clear X inside the field, mic as the field's trailing
    action. `TouchBrowseShell` gains an `actions` slot (`ui/browse/TouchNav.kt:186-206`).
    The fuzzy filter from Phase C ranks as you type.

B4. **Toolbar actions replace pill rows.** Refresh, layout toggle, clear history, clear
    favourites become `IconButton`s in the app bar or `DropdownMenuItem`s in overflow; delete
    the touch rendering of `HeaderAction`/`PillButton`/`Pill` chip rows
    (`BrowseScreen.kt:842-924, 1030-1071`, `ui/browse/MediaGridScreen.kt:719-752`).
    **TV too (explicit user choice): refresh is icon-only everywhere**
    (`BrowseScreen.kt` RefreshAction `showLabel`, media grid `Pill("Refresh", ...)`).

B5. **Blurbs and counts leave the content area.** Per-tab taglines ("Everything, newest
    first") and the count line (`BrowseScreen.kt:95-110, 934-994`), "Videos from this chat"
    (`MediaGridScreen.kt:520-524`), "Changes save as you make them."
    (`ui/settings/SettingsScreen.kt:201-205`) all go on touch; a count may live in the toolbar
    subtitle, or nowhere. TV keeps its headings.

B6. **The media grid gets a real app bar.** Today: no back affordance, a 32sp title padded by
    the TV overscan constant so it sits under the status bar (`MediaGridScreen.kt:231-248,
    482-576, 502`). New touch layout: `Scaffold` + `TopAppBar` with back arrow (wire the same
    action as the existing BackHandler in `MainActivity.kt`), 40dp avatar, title, actions
    favourite / layout / refresh, search expanding in the app bar. Grid mode becomes dense
    Telegram-style: minimal gaps (2dp), square corners, duration overlaid in a corner, no text
    captions under tiles (`MediaGridScreen.kt:299-306, 796-836`); row mode keeps the file
    names, so nothing is lost. Bottom padding gains the navigation-bar inset; the floating
    "Loading more" chip drops `Tv.SafeV` on touch (`MediaGridScreen.kt:388-400`). The
    "On this TV" badge gets device-neutral wording (`MediaGridScreen.kt:913-926`).
    FLAGGED DECISION for the user: captionless grid tiles are the Telegram idiom but hide file
    names in grid mode; row mode retains them. Veto if names must stay on tiles.

B7. **Settings reads as a preference screen.** `TopAppBar` with back arrow (today there is no
    back affordance at all on phone, `MainActivity.kt:475-484`); un-card the rows to full-bleed
    (`SettingsScreen.kt:1095-1132, 186`); accent-coloured section headers
    (`SettingsScreen.kt:886-907`); replace the drawn pill toggle with a real M3 `Switch` that
    is itself the touch target and carries TalkBack state for free (`SettingsScreen.kt:1015-1092`);
    M3 `RangeSlider` for the size filter on touch, keeping the D-pad version on TV
    (`SettingsScreen.kt:687-751, 783-827`); busy feedback ("Deleting...", "Signing out...")
    surfaced where the user is looking, not below the last row (`SettingsScreen.kt:463-468`);
    `CountryPicker` gets a toolbar and back arrow (`ui/auth/CountryPicker.kt:66-79`).

B8. **Stock dialogs, sheets and snackbars.** On touch: `TvConfirm` becomes M3 `AlertDialog`;
    the long-press `TvMenu` becomes a `ModalBottomSheet` (today it has no visible dismiss on
    phone and tap-outside cannot fire because the scrim is part of the dialog content,
    `ui/components/TvConfirm.kt:74-77`, `ui/components/TvMenu.kt:87-93, 147-154`);
    `UpdateDialog` becomes `AlertDialog` + `LinearProgressIndicator`
    (`ui/update/UpdateDialog.kt:82-232`); platform `Toast` becomes `Snackbar` from the
    Scaffold host, with undo where an undo exists (`ui/components/Toast.kt:20-25`); the
    hand-animated spinner becomes `CircularProgressIndicator` on touch
    (`ui/components/StateScaffold.kt:113-158`). TV keeps its focus-driven versions.

B9. **Ripple on everything tappable.** Remove `indication = null` on the touch paths:
    `BrowseScreen.kt:811, 911, 1045`; `MediaGridScreen.kt:629-633, 709, 740, 818, 868`;
    `ui/components/TvSearchField.kt:112-116`. Several controls currently give zero press
    feedback on phone.

B10. **Consistency sweep.** One type role per UI role (M3 defaults after B1); collapse the
     nine live corner radii to the M3 shape set; 16dp screen side padding everywhere on phone;
     unify avatar sizes (list 54 to 56dp, drawer 44dp, app bar 40dp); merge the two competing
     empty states (`BrowseScreen.kt:1335-1373` vs `StateScaffold.kt:167-195`); one metric for
     chips where chips legitimately remain (TV).

B11. **Player chrome sized for the phone.** The touch player runs on `Theme.Leanback` with a
     fixed 520dp progress bar, 72dp side padding, 26sp type and TV overscan margins
     (`res/values/themes.xml:10-12`, `res/layout/activity_player.xml:24-178`). Fix: a phone
     theme and phone-sized loading sheet and chips, plus D1 insets work. Functional player
     gaps are Phase D.

B12. **Onboarding and copy speak phone.** The walkthrough shows TV screenshots and says
     "point the camera at the TV" and "the rail sorts them on the left" to phone users
     (`ui/onboarding/OverviewScreen.kt:174-195`; Settings advertises "Five screens" against
     three pages, `SettingsScreen.kt:417`). Fix: phone-specific pages or skip the walkthrough
     on touch. Sweep remaining TV wording on shared strings (`MainActivity.kt:207, 498`,
     `data/Failures.kt:30, 78`, `player/PlayerActivity.kt:239, 495`,
     `MediaGridScreen.kt:915`), following the `device` value pattern already used at
     `SettingsScreen.kt:149`.

B13. **System integration polish.** `enableEdgeToEdge()` so edge-to-edge is real on API 26-34,
     not only under targetSdk 35 enforcement (`res/values/themes.xml:4`);
     `android:enableOnBackInvokedCallback="true"` for predictive back (every BackHandler
     currently opts the app out of the system animation); `android:supportsRtl`; a
     `<monochrome>` layer for themed icons (`res/mipmap-anydpi-v26/ic_launcher.xml`).

B14. **Skeletons follow the new metrics.** `ui/components/Skeleton.kt:124-295` mirrors the
     current card design; update it in the same commits as B2 and B6 or loading and loaded
     states will visibly disagree.

B15. **Accessibility ride-alongs.** Free with B7/B8 (real Switch, real dialogs); still to do
     by hand: `Role.Button` on `ActionRow`/`PaneChooser` (`ui/auth/AuthFields.kt:115-144`),
     a description on the storage bar (`SettingsScreen.kt:935-955`), live-region or
     descriptions for the player chips and HUD (`res/layout/activity_player.xml:114-167`).

---

## Phase C: Forgiving search (kept from the original plan)

C1. **`data/Fuzzy.kt` + `FuzzyTest.kt`, pure and unit-tested.** `normalize` (lowercase, strip
    diacritics via NFD, non-alphanumerics to spaces). Token scoring: exact 4, prefix 3,
    substring 2, edit distance 1 for tokens of length >= 4 scores 2. A candidate matches when
    at least half the query tokens (rounded up) score, so a wrong keyword among right ones is
    forgiven. Whole-query substring bonus so exact matches rank first. `rank(items, query,
    key)` returns matches sorted by score, stable within score. Tests: typo ("freinds" finds
    "Friends"), extra wrong keyword forgiven, diacritics, case, blank query returns
    everything, ranking order.

C2. **Chat filter uses it.** `filterChats` in `ui/browse/BrowseScreen.kt` replaces plain
    `contains` with `Fuzzy.rank`. Both form factors: behaviour, not layout.

C3. **Forgiving video search.** In `MediaListViewModel`: full query to `searchChatMessages`
    first; if the first page is empty and the query has two or more tokens, retry single
    tokens (longest first, up to 3, length >= 3) until a page is non-empty; that token becomes
    the paging query (cursors unchanged) and every page is locally ranked and filtered with
    `Fuzzy` against the raw query. Empty-state message unchanged.

---

## Phase D: Player must-haves

D1. **Insets and immersive mode.** No insets or bar-hiding exists; on phone the status and
    navigation bars sit permanently over the video and the controller under the gesture bar
    (`player/PlayerActivity.kt:156-203`). Fix: hide system bars while playing
    (`WindowInsetsControllerCompat`, swipe to reveal), `layoutInDisplayCutoutMode=shortEdges`,
    inset the controller and overlays.

D2. **Subtitles on phone.** Text tracks are globally disabled and the picker is
    leanback-only, so a phone user cannot enable embedded subs at all
    (`PlayerActivity.kt:381, 293`, `player/TvPlaybackFragment.kt:41-42`). Fix: stop
    force-disabling text, `setShowSubtitleButton(true)`, expose audio and text selection on
    touch. Honour system `CaptioningManager` style instead of the hardcoded one
    (`PlayerActivity.kt:190-203`).

D3. **MediaSession and PiP.** Add `media3-session`, a `MediaSession` (notification and lock
    screen transport, headset and Assistant control) and phone PiP
    (`android:supportsPictureInPicture` + enter on user-leave while playing). Today leaving
    the app always stops playback (`PlayerActivity.kt:825-829`).

D4. **End of video.** `STATE_ENDED` clears resume and finishes instantly
    (`PlayerActivity.kt:422-425`). Fix: autoplay next episode with a short countdown (the next
    episode is already resolved by `findEpisodes`, `PlayerActivity.kt:519-538`), plus replay
    affordance; a setting to turn autoplay off.

D5. **Episode navigation on phone.** `episodes` is only consumed by the TV fragment; on touch
    the three searches run and the result is discarded (`PlayerActivity.kt:519, 287-316`).
    Wire prev/next into the touch controller. Depends on A1.

D6. **Resume or restart.** The saved position applies silently (`PlayerActivity.kt:222-229`);
    offer "start over" (controller affordance or one-time prompt). `PLAN.md:80` specified it.

D7. **Playback speed parity and persistence.** Phone has it in the Media3 gear; TV has
    nothing (`player/TvPlayerGlue.kt:90-93`). Add a TV speed action and persist a default.

D8. **Aspect and zoom control.** Cycle fit / zoom / fill (`RESIZE_MODE_*`) from the
    controller; pinch-to-zoom on touch (`player/PlayerGestures.kt` has no pinch).

D9. **Error sheet with actions.** "Press Back to pick something else" with nothing focusable
    (`PlayerActivity.kt:785-798`). Add Retry and, where relevant, "Download fully first";
    focusable on TV. Pairs with A9.

D10. **Orientation.** `sensorLandscape` forces landscape for vertical clips
     (`AndroidManifest.xml:57`). Follow the video's aspect (portrait videos allowed portrait)
     or add a rotation control.

D11. **Gestures vs controller.** Once the controller is visible its children consume touches
     and double-tap seek and drags stop working; the first tap also raises the controller and
     can swallow the second (`player/PlayerGestures.kt:93-104`). Fix the event routing.

D12. **Small correctness knobs.** `AudioAttributes` content type movie
     (`PlayerActivity.kt:399`); make `setConstantBitrateSeekingAlwaysEnabled` conditional
     (`PlayerActivity.kt:389`).

D13. **Metered-connection guard.** Nothing distinguishes Wi-Fi from mobile data anywhere; a
     multi-GB pull can start on cellular silently (`data/NetworkMonitor` has no metered
     concept). Add metered detection, a warn-once-per-session prompt before large downloads,
     and a Wi-Fi-only setting in Settings.

---

## Phase E: Performance, memory, data robustness (the 1 GB stick matters)

E1. **Get the data layer off the main thread.** No `withContext` exists anywhere in the
    repository or mapper; everything resumes on Main. Worst cases: 3 x 40 messages x 2
    filesystem syscalls per page in `MediaMapper.onDevice` inside a `MAX_EMPTY_PAGES = 8` loop
    (`data/ChatRepository.kt:168`, `data/MediaMapper.kt:154`, `ui/browse/BrowseViewModel.kt:347`);
    300 sequential `getChat` calls on the first-frame path (`ChatRepository.kt:75-88`);
    per-item `localFileAvailability` for every loaded item on every resume
    (`BrowseViewModel.kt:299-310`). Fix: dispatch repository and mapper work to IO/Default,
    batch or parallelise the `getChat` fan-out, diff instead of full-rescan on resume.

E2. **Stop paying for the chat list twice.** `syncChats` triple-loads then re-reads
    (`ChatRepository.kt:61-70`), and both `BackHandler`s call `chatsViewModel.load()` on every
    Back out of Media or Settings (`MainActivity.kt:438, 477`). Fix: one sync per trigger,
    no full sync on Back (refresh only when stale).

E3. **Cap the ViewModel graveyard.** `viewModel(key = "media-$chatId-...")` against the
    activity store leaks one full item list (with minithumbnail byte arrays) per visited chat
    per size-filter combination (`ui/browse/MediaGridScreen.kt:147-150`). Most likely OOM path
    on the stick. Fix: scope to the media screen or evict on leave.

E4. **Compose stability.** `MediaItem` and `ChatSummary` hold `ByteArray?` so nothing skips
    (`data/MediaMapper.kt:13-35`, `ChatRepository.kt:21-30`); id-only `equals` forced the
    `availabilityRevision` workaround and causes A7. Fix: stable wrappers or `@Immutable` with
    real equality; remove the workaround; hoist `qualityTags` regex and formatting out of
    recomposition (`MediaMapper.kt:28`, `MediaGridScreen.kt:912`); decode minithumbnails off
    the composition thread (`ui/components/Art.kt:38`, `data/Thumbnails.kt:29`).

E5. **Thumbnail pipeline.** Per-cell subscriptions to the global `fileUpdates` firehose
    (`Thumbnails.kt:64-68`), no in-flight dedup, no negative cache, fixed 12 MB LRU, no
    `onTrimMemory` anywhere (`App.kt`). Fix: one shared filtered stream, dedup, size the LRU
    from `ActivityManager.memoryClass`, implement `onTrimMemory` and trim.

E6. **Resume store discipline.** The 10-second heartbeat rewrites and fsyncs the whole
    DataStore file, re-running full-map scans in `watchProgress`/`continueWatching` for every
    collector, against an unbounded history (`PlayerActivity.kt:814-823`,
    `data/SettingsStore.kt:225-282`). Fix: cap history at 200 (LRU, as `PLAN.md:125`
    intended), write only on meaningful delta, avoid rescanning per write.

E7. **Throttle the download HUD.** `renderProgress` runs per TDLib `updateFile` (several per
    second at MB/s rates) on the main thread during decode start
    (`PlayerActivity.kt:613-621, 679-719`). Sample at ~500ms.

E8. **TdDataSource efficiency.** Keep the window warm from one long-lived `fileUpdates`
    subscription instead of a fresh subscription plus `getFile` per slow read
    (`player/TdDataSource.kt:214-236, 252-262`); revisit the `runBlocking`-per-read shape and
    the 120s outer / 60s inner timeout nesting (`TdDataSource.kt:300-318`). Consider a few
    seconds of back-buffer so short back-seeks stop re-issuing downloads
    (`PlayerActivity.kt:375`, `player/DownloadWindow.kt:49`), sized modestly for the stick.

E9. **A real cache story.** `clearMediaCache` is manual, all-or-nothing, and excludes photos
    and thumbnails, which grow forever; no `optimizeStorage`, no TTL, no ceiling
    (`data/Td.kt:392-404`). Fix: size-capped eviction via TDLib `optimizeStorage` on launch or
    idle, photos and thumbnails included; Settings storage card shows the breakdown; pairs
    with A3 (cancellation) so eviction is not fighting an unstoppable producer.

E10. **Retry with backoff, flood-wait aware.** `FLOOD_WAIT_n` is parsed into a sentence and
     the number is discarded (`data/Failures.kt:62-72`); `syncChats` cannot distinguish
     "no more chats" from a network failure (`ChatRepository.kt:65-67`);
     `awaitConnectedSession` waits forever (`Td.kt:352-358`). Fix: schedule retries at the
     server-given delay, rate-limit manual refresh during a flood wait, distinguish
     end-of-list from error, time-box the session wait, and give offline errors an
     offline-specific state with a Retry action (`BrowseViewModel.kt:196`).

E11. **Cold start and live updates.** First content frame waits on TDLib open plus the
     `getChat` fan-out; nothing subscribes to `updateChatTitle/Photo/Position`. Fix: persist a
     minimal chat snapshot (id, title, kind, photo id) for instant paint, subscribe to chat
     update events to keep the list live.

E12. **Track sanity on the stick.** Single-track files bypass viewport constraints so a 4K
     HEVC remux hits a 1080p-class decoder and fails with a generic message
     (`PlayerActivity.kt:378-384, 497-500`). Constrain or fail with a resolution-specific
     message.

E13. **Build hygiene.** Add a baseline profile plus a startup macrobenchmark; tighten
     `proguard-rules.pro:5` (`dev.g000sha256.tdl.** { *; }` pins thousands of DTOs; only the
     JNI-facing `org.drinkless.tdlib.**` needs keeping); bump `androidx.leanback` off 1.0.0
     (`gradle/libs.versions.toml:15`); fix deprecated `resourceConfigurations` spelling
     (`app/build.gradle.kts:43`).

E14. **Tests for the seams.** 169 pure-function tests exist; nothing covers
     `ChatRepository.mediaPage` merging, `MediaListViewModel.loadMore` (including A8),
     `ChatListViewModel` publish/conflation (A7), or `SettingsStore` flows. Add tests behind a
     client interface as those fixes land; Fuzzy (C1) ships tested from day one.

---

## Phase F: Backlog (valuable, not this pass)

- Android TV Watch Next channel fed from `continueWatching` (needs D3's session work).
- Quality variants: read TDLib alternative video representations for weak connections
  (`data/MediaMapper.kt:53-69`).
- External `.srt` from the same chat as `subtitleConfigurations` (deferred in `PLAN.md:26`).
- Localization: move strings to `strings.xml` with plurals (today: one string resource total).
- Theme choice (light / follow-system), `values-night`.
- Open source licences screen (attribution for the FFmpeg renderers is an obligation;
  `THIRD_PARTY_NOTICES.md` exists but is not surfaced in-app).
- Deep links (`t.me` / `tg://`), app shortcuts (resume last).
- Sleep timer; background audio mode.
- Per-item "keep offline" and a multi-item cache budget beyond E9.
- Report-a-problem link in Settings.

---

## Phase G: README and site refresh (after B and D land)

The redesign changes what the app looks like, so the public face must follow:

- **README** (`README.md`, images in `docs/screenshots/`): retake screenshots showing the new
  phone UI (chats, media grid, player) alongside the TV ones; update feature copy for
  anything user-visible that shipped (fuzzy search, subtitles on phone, PiP, autoplay next,
  metered guard). Per project rules every screenshot is WebP quality 88 via
  `magick shot.png -define webp:method=6 -define webp:sharp-yuv=true -quality 88 out.webp`,
  and the source PNG is never committed.
- **Site** (`site/`): refresh `site/screenshots/` the same way; any file name change must be
  mirrored in all three of `site/index.html`, `site/sitemap.xml` and `site/site.webmanifest`
  (the manifest carries `"type": "image/webp"` per entry). Update landing copy for the new
  phone experience. `site/og.png` and `site/icon-512.png` stay PNG, deliberately.
- Dash check both documents before committing.

---

## Execution order and TV safety

Recommended order: A (bugs) -> C (fuzzy search, small and independent) -> B (redesign)
-> D (player) -> E (performance) -> G (README and site) -> F (backlog, as asked for).
Within B, land B1 (theme) first since everything else
sits on it, then B2/B6/B14 together (rows, grid, skeletons), then the rest. Phases are
independent enough to pause between; build and test at each phase boundary.

TV safety rule: every layout change is behind `touch`/`isTv` except the two marked "TV too"
(icon-only refresh in B4; behaviour changes in C and E apply to both by design). After each
phase, spot-check TV: D-pad browse, chat open, playback, track picker, settings.

## Verification

- `./gradlew test` then `./gradlew assembleDebug` at each phase boundary.
- Dash check per project rule:
  `grep -rInP '\x{2014}|\x{2013}' --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle .`
- Phone manual QA: portrait and landscape rotation on chats, media grid, settings, login
  (keyboard up), player (gesture bar, cutout); process-death restore (developer option
  "Don't keep activities").
- TV manual QA: emulator or stick pass over browse, playback, next episode (A1), pickers.
- Performance sanity on the stick after E: cold start time, scroll a large chat, back-seek.

## Status checklist (update as work lands)

- [x] Audits complete (playback, screens, data/perf, design language)
- [x] Plan rewritten (desktop dropped, audits merged)
- [x] Phase A: correctness fixes (A1 to A11, all landed)
- [x] Phase C: Fuzzy.kt + tests, chat filter, video search fallback
- [x] Phase B: phone-native redesign
  - [x] B1 theme, B2 rows, B3 app-bar search, B4 toolbar actions (icon-only refresh on TV too),
        B5 blurbs out, B6 media-grid app bar and dense grid, B7 Settings, B8 dialogs and sheets,
        B9 ripple, B11 player chrome, B12 copy, B13 system integration, B14 skeletons
  - [x] B7 remainder: M3 `RangeSlider` for the size filter on touch, figures above the thumbs
  - [x] B8 remainder: `UpdateDialog` is Material's `AlertDialog` with a `LinearProgressIndicator`
        on touch; the TV keeps its focus-driven panel
  - [~] B15: storage-bar description, player HUD live regions and `Role.Button` on the country
        row all landed. B10's consistency sweep (corner radii, avatar sizes, merging the two
        empty states) has NOT been done: it is a broad visual change and there was no device to
        check it on.
  - [ ] B8, deliberately not done: `Toast` stays the platform toast rather than a Snackbar. It
        survives the screen that raised it going away, which is the case it exists for, and a
        Snackbar would need a host on screens that have no Scaffold.
- [x] Phase D: D1 to D13 all landed. D3 is a `MediaSession` plus phone PiP; D7 adds a television
      speed action and persists the choice; D8 cycles fit / crop / stretch and pinches on touch;
      D10 follows the video's own shape; D11 routes gestures from the activity so the transport
      row no longer swallows them; D13 is the metered guard and the Wi-Fi-only setting.
- [~] Phase E: E1 to E7 and E13 as before. Now also E10 (flood wait kept as a number, sync says
      whether it finished, the connection wait is time-boxed, Refresh holds off), E11 (cold-start
      snapshot, live title and photo updates), E12 (a resolution-specific decoder message),
      E14 (tests for the new seams), and the parts of E8 and E9 below.
      Outstanding: E8's long-lived `fileUpdates` subscription in `TdDataSource` (the back buffer
      and the timeout shape landed; keeping the window warm from one subscription did not, and it
      wants a device to measure on). E9's storage breakdown in the Settings card (the ceiling and
      the sweep that includes photos and thumbnails landed; the card still shows one figure).
      E11's chat reordering, which still waits for the next sync.
- [~] Phase G: README and site copy updated; screenshots retaken on real hardware for v1.3.0 and
      not since. The drawer changed shape in v1.4.0, so the phone shots are now out of date.
- [x] Verification: `./gradlew test`, `assembleDebug` and `assembleRelease` all green; dash check
      clean. Manual QA on a phone and on the stick has NOT been done: no device was attached.

## Shipped as v1.3.0 on 2026-08-11

Screenshots were retaken on real hardware, which the earlier pass could not do: a phone at
1080x2400 and a panel resized to 1920x1080 for the television layout, both from the promo fixture.
The fixture navigates now, so the site's hero clip is one recording rather than three cut together.
The launcher icon was redrawn inside the adaptive-icon safe circle, and the site was rebuilt around
CSS device frames with a four-step walkthrough.

## Shipped as v1.4.0 on 2026-08-12

The rest of Phase D, the Material remainder of Phase B, and the Phase E items listed above. Also,
outside the plan and asked for directly: the phone's navigation drawer was rebuilt. It opened with
a mark, an app name, an avatar, a name and a handle stacked over seven flat destinations; it now
has a single brand row, the three tabs about the viewer's own watching, a headed group for the
four ways of slicing the chat list, and the account demoted to a one-line footer carrying the
build number.

Nothing in this release has been run on hardware. Every item above was built and unit-tested only.
