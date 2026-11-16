# Onboarding and add-chats fixes (user go 2026-11-15, "do all and commit")

Source: findings ON-1 to ON-20 in
`/tmp/claude-1000/-home-dracu-projects-personal-TMPlayer/4937c6ee-fee4-4826-a45a-c85bf03134d0/scratchpad/review-onboarding.md`
(table at the end of that file, one row each; flow chart and step counts above it).

Rules for every task: commit locally section-wise (no push, no Co-Authored-By trailer), no em or en
dashes anywhere, new user text only in `core/src/commonMain/resources/i18n/en.json` (keys sorted, the
catalog test checks it), never run langsync, never delete existing keys (the other 21 catalogs are the
user's: reuse a dead key where it fits instead), no `!!` or `.first()` on outside data, every change
works on phone, TV (D-pad focus, tested with `performKeyInput`) and desktop, web where it applies.
Gradle: wrap every call as `flock /tmp/tmplayer-gradle.lock ./gradlew ... --max-workers=2` and run only
targeted tests, never the whole suite.

## Tasks

### W1 Picker (ON-1, ON-2, ON-3, ON-5, ON-12, ON-18, ON-19 reuse)
model: sonnet medium. depends: none.
files: `ui/src/shared/kotlin/com/tmplayer/ui/browse/AddChatsScreen.kt`, `AddChatsViewModel.kt`,
`ChatPicking.kt`, `ChatSuggestions.kt`, `app/src/main/java/com/tmplayer/ui/browse/AddChatsPage.kt`,
`desktop/src/**/ui/AddChats.kt`, `webapp/src/screens/AddChats.tsx` (copy only), en.json keys `picker.*`.
done when: ON-1 confirmed with a test or ruled out (say which); ON-2 copy and stale comments say
nothing is ticked; ON-3 an "Add these N chats" button in the Recommended band (explicit act, TV
reachable by D-pad, reuse `picker.add_more` if it fits); ON-5 Back in the required gate with ticks asks
before leaving; ON-12 reopening the picker later does not hold the Recommended band behind skeletons
and does not list added chats twice (an "Added" band or a mark); ON-18 ticks made before the stored
read lands are kept (union); targeted tests for each.

### W2 Setup and first Home (ON-8, ON-9, ON-10, ON-11, ON-16, ON-20)
model: sonnet medium. depends: none.
files: `ui/src/shared/kotlin/com/tmplayer/ui/onboarding/*` (Setup.kt, SetupScreen.kt, StepChrome.kt,
AppearanceStep.kt, CrashAskCard.kt), `LanguageSetting.kt`/`LanguageNotice`, `Settings.markTourDone`
(SettingsStore), the tour-offer and language-card lines of `MainActivity.kt` only (around 2431-2540).
done when: ON-8 Performance only when DeviceFit says the device is slow, one "Use recommended settings"
primary beside Next, crash consent asked after the first value (existing `CrashAskHost`); ON-9 Back on
the first page no longer skips the whole setup silently; ON-10 a fresh install writes
`languageAnnounced` when setup arms; ON-11 the tour offer is a quiet card under the hero, "Not now"
does not mean "never again" (or is labelled honestly); ON-16 the last page's writes finish before the
screen leaves (own scope or `NonCancellable`), with a test on a multi-write answer; ON-20 Skip all
reachable in one press on TV; focus tests for TV.

### W3 Gate, empty states, sign-in, web (ON-4, ON-6, ON-7, ON-13, ON-14, ON-15, ON-17)
model: sonnet medium. depends: W1, W2 merged (shares `MainActivity.kt`).
files: `ChatsGate.kt`, `ChatsGateHost.kt`, `Shell.kt` (desktop), gate lines of `MainActivity.kt`
(around 797 and 1786-1797), `HomePane.kt`, desktop `Home.kt`, `FirstRun.kt`, `BrowseScreen.kt`,
`LoginScreen.kt`, `webapp/src/screens/{Chats,Home,Settings}.tsx`, `webapp/src/App.tsx`,
`webapp/src/install.ts`.
done when: ON-4 an empty Recommended after the head start offers "Continue without choosing"; ON-6 the
8 s wait starts when the chat list is first needed, not at launch; ON-7 the wait shows
`picker.loading` and a way out after 4 s; ON-13 web "Add chats" button in the Home and Chats page bar;
ON-14 the persistent-storage prompt moves to first download or first Watch; ON-15 the empty Home
under "Picked" says nothing to show from the chosen chats, not "forward a video"; ON-17 "Proxy
settings" offered when Send code stays busy 8 s or fails.

## Decisions made by Claude
- W2: no `CrashAskHost` exists in the tree (only the per-crash `CrashQuestion` after a crash), so the
  crash consent stays as the setup's last switch row (reached with Next); "Use recommended settings"
  skips it and consent stays off, the per-crash prompt still asks after a crash. A post-first-value
  consent card is left to do (needs MainActivity/Shell lines outside W2).
- W2: "Use recommended settings" sits beside Next on the first page only (Skip all stays on later
  pages); on a device with recommendations it applies them (what Next on the Device step does).
- W2: Back on the first page is left to the system (phone leaves the app, TV/desktop: nothing handles it).
- W2: tour offer is a bottom card in the language card's place (not under the hero: HomePane is W3's).
  Not now = 7 days, then once more, then "No thanks" is final.

## Progress / handoff
(workers append: task, commit hash, checks, decisions)
- W1 done (worktree agent-a26bc23f29c31cf59): 53cf9de (ON-1), 876680a (ON-2, 3, 5, 12, 18, 19). Checks green: core CatalogTest, ChatsGateTest, ChatPickingTest, ChatSuggestionsTest; ui AddChatsReopenTest, ChatPickerScaleTest; app AddChatsTvKeysTest, AddChatsRenderTest; desktop AddChatsRenderTest. Build needs JAVA_HOME=/home/dracu/.jdks/jdk-21.0.12+8 and a copied local.properties in the worktree.
- W1 leftovers: web picker (`webapp/src/screens/AddChats.tsx`) has no Added band or Add these button (copy comment only, as scoped); webapp `picking.ts` Suggestion still documents pre-ticks; the new en.json keys (add_these, remove_these, leave_*) and the changed gate_body need the other 21 catalogs before release.
- W2 done (ON-8, 9, 10, 11, 16, 20): 321f0d9 (ON-16, test first failed, then fixed with a non-cancellable
  write before onDone), bb01b3b (ON-8/9/10/20), 79ede9f (ON-11). Checks: desktop SetupFirstRunTest (7),
  SetupRenderTest (19), ui SetupTest (30), core SetupFlagTest + CatalogTest, :app:compileDebugKotlin.
  Left: device check of TourOfferCard focus on TV (no compose test infra in app/), crash consent after
  first value, Setup choices page unchanged.

- W11 done (worktree agent-ad8e341768b946842, 3 local commits on main 5811757, nothing pushed): 9ce445e (link speed),
  c210b16 (SinkCaps into the plan), 43f6a6b (desktop battery and heat guard). Checks green: core LinkSpeedTest 18,
  PowerReadingsTest 15, SinkPlanTest 10, TranscodePlanTest, CastServerTest, CastFormatsTest, dlna.*, GarbageInputTest
  (SinkCaps.plan added), CatalogTest; desktop DesktopLinkSpeedTest, DesktopDeviceHealthTest 8, cast.*; app MergedCastSupportTest,
  :app:compileDebugKotlin.
  Decisions made by Claude: (1) NO active probe: neither Cast LOAD nor DLNA SetAVTransportURI lets a sender make a TV fetch a test
  body without loading it as a video (error flash, stops what plays). So the link = the cast server's own timing of the TV taking
  the bytes of a file sent as it is (write time only, 2 MiB windows, best of 5 min, LinkMeter) else the platform's link rate
  (Android 10+ Wi-Fi via NetworkCapabilities.transportInfo, no new permission; desktop Linux sysfs/iw, Windows Get-NetAdapter,
  macOS ifconfig media; Wi-Fi counts 50 %, cable 90 %) else unknown = old 1080p 8 Mb/s. LinkLadder holds the rung (down at once
  under 90 % of need, up with 25 % spare and 60 s on the rung); every transcode stream is re-targeted when it starts
  (TranscodePlanner.retarget), which also fixed the soundtrack switch dropping the link and the TV's wrapper. (2) receiverContainers
  passes CastSupport (merged delegates) and desktop Receiver.containers; SinkCaps.plan joins caps and containers. (3) Desktop guard:
  Linux sysfs, macOS pmset, Windows PowerShell battery only (no CPU temperature for a normal user, so no heat guard there, documented
  in DesktopDeviceHealth); plug state not told = unknown, never a stop; CPU heat is Severe at 95 C for 3 readings in a row. New key
  cast.transcode_too_hot_computer (en.json only, W12 translates).
  Not tested: any device. Device checks due: phone Wi-Fi linkSpeed value vs a real TV (is half of it a fair stream rate?), the burst
  meter on a real Chromecast and a Samsung/LG (do the first 2 MiB write at link speed or at the TV's buffer?), a real DLNA TV that lists
  only TS taking the MPEG-TS transcode from desktop ffmpeg, desktop Linux laptop on battery below 15 %, Windows PowerShell battery read
  (and its latency), macOS pmset text on Apple Silicon and Intel, iw output on a real Wi-Fi card, Ethernet speed on the desktop.
  Gaps left: the phone cannot write MPEG-TS (Media3 muxer is fMP4 only), so a TS-only DLNA TV gets fMP4 from the phone; macOS Wi-Fi and
  Android before 10 give no link rate (default applies until measured).

- In-chat search bug ("resident evil" found by the search across chats, not in the 'Mark S' chat's own box) done (worktree agent-a5de11d5c2ea685b5, reset to main e5b153e, 6 local commits, not pushed): 494d67c (core ChatSearch/ChatSearchRun + ChatRepository.historyPage + tests), 20e2947 (history round beside Telegram's search), e011e4e (MediaListViewModel, phone/TV MediaGridScreen, desktop MediaGrid, keys browse.search_scanned + browse.search_offline), 5839d0c (view model, SyntheticAccount and TV focus tests), 614dc1a (JS fix), 66fe7a2 (web app).
  Root cause: the app's in-chat search (ui BrowseViewModel.kt firstPage, lines 1416-1435 at e5b153e) asked Telegram's searchChatMessages alone, with the query whole, then its longest words, and ranked only what came back. Telegram matches the words of a file name as it parts them, and a release name with dots or underscores is one word to it, so Resident.Evil.Welcome.To.Raccoon.City.2021.1080p.mkv matched none of "resident evil", "resident", "evil". The search across chats has two answers the chat box lacked: release name spellings (GlobalSearchViewModel.kt:594, GlobalSearch.variants) and a local half over every video the app has seen (GlobalSearchViewModel.kt:655 absorb, GlobalSearch.localVideos over KnownMedia), which is how it found the video. Not the size filter (a search ignores it), not the grid folding (off under words), not the chat gate. Web app: worse, its chat search (webapp/src/tg/library.ts:209 arrange) only ranked the pages already loaded, never asking Telegram.
  Fix: local first answer (the chat's kept pages, the listing on screen, KnownMedia; every word a prefix in any order, case/accents/. _ - + folded), Telegram asked as typed, as words, with _ and ., then the 3 longest words, 50 a page, plus 500 newest messages of getChatHistory read in parallel and matched here; merged by message id under what is listed (no reordering); debounce 350 ms (existing SearchSettle) and a request number; a flood wait up to 30 s waited out once by that search, else the history; offline = TDLib's disk only (onlyLocal) with a note; Telegram empty or failing = history rounds of 500 with "Only the newest N messages ... were searched" and Keep looking (grid foot line, empty state button); TV: Search hands focus to the first result, Back clears the words first. Web: worker searchMessages with the query through the same spellings (WebRules.chatSearchSpellings), hits kept apart from the pages, Searching / Keep looking.
  Checks green: core ChatSearchTest 12 (failing first: the pre-fix algorithm finds nothing on a 5,000 video chat, the new one finds the dotted name past the loaded window), GarbageInputTest (ChatSearch.spellings/matches added), CatalogTest, FuzzyTest, GlobalSearchTest; ui ChatSearchModelTest 6 (bug at 1.6 s virtual, debounce, stale answer, Keep looking, 20,000 videos of SyntheticAccount at 0.8 s with 3 searchChatMessages + 5 getChatHistory), ChatGridKeptTest; app SearchNoteFocusTest 2 (performKeyInput); :desktop:compileKotlin, :app:compileDebugKotlin, :core:webRules, webapp tsc.
  Decisions made by Claude: history scan is 500 messages a round, by button only (not on scroll), to bound TDLib calls; searches are no longer kept in the page cache (the local half answers at once instead); Keep looking reuses common.keep_looking; Telegram's real tokenisation of dotted names was not verified (no device): the fake models whole words split by spaces.
  Not verified without the phone: that the real 'Mark S' video is found (depends on whether it is in the newest 500 messages, loaded, or Telegram matches a spelling; older ones need Keep looking presses), TDLib's getChatHistory first page often being one message (rounds count messages, 7 calls max), TV focus after the keyboard's Search on a real remote, offline note on a device, the web worker against real mtcute. Gaps: a deleted video can come back in a search's next page (the run's list is not told of deletions); a forum topic's history scan filters by topicId after reading the whole chat's messages.

## Play findings (PL-n, from UX-REVIEW-2026-11-15.md part 2; rows are in the same scratchpad file `review-play.md`, `grep '^| PL-'`)

### W4 Player, TV remote and episodes (PL-8, PL-9, PL-10, PL-11, PL-12, PL-15, PL-21, PL-22, PL-23, PL-24)
model: sonnet high. depends: none.
files: `app/.../player/PlayerActivity.kt` (grep, line ranges only, 10k lines), `PlayerControls.kt`,
`TrackPickerFragment.kt`, `player_controls.xml`, `core/.../EpisodeSteps.kt`, `FirstRunHint.kt`.
done when: PL-8 focus returns to the button that opened a sheet; PL-9 Down from the bare picture
shows the row with focus on the first button (Left/Right still seek); PL-10 CAPTIONS and
MEDIA_AUDIO_TRACK keys open the pickers; PL-11 a Next episode row button on TV when episodes exist;
PL-12 the autoplay switch in the Episodes panel is read by the next-up loop; PL-15 Next and autoplay
fall back to the other downloaded episodes when the Telegram search fails or is offline; PL-21 speed
shown on the button when not 1x, tap cycles the last two speeds, long press opens the sheet; PL-22
unsupported tracks listed dimmed with a reason; PL-23 `handingOver` cleared when `startActivity`
throws; PL-24 a `runCatchingCancellable` helper rethrows `CancellationException` at the five listed
sites. TV focus tests per change (`performKeyInput`), a unit test for each pure helper.

### W5 Search (PL-4, PL-5, PL-6, PL-7, PL-17)
model: sonnet medium. depends: none (files do not overlap W1 to W4).
files: `GlobalSearchViewModel.kt`, `PhoneBrowse.kt`, `PhoneSearch.kt`, `TvSearch.kt`, `TileReturn.kt`,
desktop `SearchPalette.kt`, `MediaGridScreen.kt` (moreFailed pattern only).
done when: PL-4 an Unreachable lookup opens the page and never removes the result; PL-5 a pressed
state or spinner and a bounded wait (not 30 s) on a result tap; PL-6 Resume or Play on a result with
progress (phone button, TV long OK); PL-7 TV result cards use `rememberTileReturn`; PL-17 a "Try
again" row when loading more fails.

### W6 Home, resume, offline (PL-3, PL-13, PL-14, PL-19)
model: sonnet medium. depends: W1, W2, W3 merged (shares `MainActivity.kt`).
files: `HomeRows.kt`, desktop `Home.kt`, `MainActivity.kt` (lines about 1283-1470), `ConnectionStatus.kt`.
done when: PL-3 desktop keeps in-progress cards without a thumbnail (generated cover); PL-13 offline
Home marks downloaded items and the Offline pill leads to Downloads; PL-14 resume of a complete
cached file or while offline skips the 6 s lookup; PL-19 Mark watched shows Undo for 8 s restoring the
resume point.

## Questions for the user (not built, decisions are theirs)
- PL-1: should phone and TV Home lead with the video you were watching (Resume as the hero)?
- PL-2: should a Continue tile with a resume point play at once (page on hold or Info)? This reverses
  the 2026-10-23 rule that Continue and Watched answer the same press.
- PL-18: Back on a paused video: close it instead of docking it to the mini bar?
- PL-16, PL-20: TV quick entries for Downloads and Watch later; Cast button on phone Home (needs a
  device check).
- W4 (worktree agent-aee4cd324deabd79d, 6 local commits, not pushed): 7f9de3c PL-23+PL-24 (`runCatchingCancellable` in core pureMain; playEpisode clears handingOver and shows `player.open_failed` when startActivity throws; helper used at the episode and pieces lookups and the two play-start reads in MainActivity), 3b27277 PL-12, 43aa58f PL-8+9+10+11 (focus memory, Down shows row on Subtitles, `TrackKeys`, `control_tv_next_episode` row button), d611abc PL-22 (new key `tracks.not_supported`), 3dc619a PL-21 (`SpeedSwap`, `SpeedLabelDrawable`, key `player.hold_speed_hint`), 793f447 PL-15 (`EpisodeNeighbours.amongKept` from Downloads). Checks: Robolectric TvRowFocusTest, SpeedButtonTest, PauseControlsRowTest, PlayerExtrasRenderTest, TrackKeysTest; core RunCatchingCancellableTest, SpeedSwapTest, FirstRunHintTest, EpisodeStepsTest, catalog tests; :app:compileDebugKotlin. Needs JAVA_HOME=/home/dracu/.jdks/jdk-21.0.12+8 (default JDK 25 fails). Decisions: Up on the bare picture still focuses the bar (PL-11's optional jump skipped); on TV the speed button OK opens the list (held OK is the other-app hand-over), tap-swap and long press are phone only; PL-15 uses Downloads only, not the watch cache; PL-22 has no unit test (Compose fragment), check on a device with an unsupported subtitle track.
- W5 (worktree agent-ab9c228ed177341d2, 5 local commits, not pushed): 489d52d PL-4 (`VideoSearchSource.lookup` returns MessageLookup, Unreachable/no answer opens and keeps the card, Gone removes; tests with fake lookups), 54d09f2 PL-5 (`openResult` in SearchOpen.kt: PlayPress Starting pill, double tap dropped, `CHECK_MS` = 2 s bound, skipped for on-device copies and offline search; phone, TV, desktop), 7ce6c56 PL-17 (`moreFailed` + `retryMore`, Try again row on phone, TV, desktop palette; scrolling no longer loops), 0b83d35 PL-6 (phone Resume button on a film result with progress via `SearchResultCard(trailing)`, TV long OK via `holdable` plays; both through `onPlayMedia`; shows excluded), 60f58e3 PL-7 (`rememberTileReturn`/`returnTile` on TV posters, `pageOpen = details.top != null`, `TvSearchReturnTest` verified to fail without the return). Checks: `:ui:jvmTest GlobalSearchViewModelTest` 16 pass, `TvSearchReturnTest` + `TvSearchRenderTest` pass, `:app:compileDebugKotlin` and `:desktop:compileKotlin` OK. Build needs JAVA_HOME=~/.jdks/jdk-21.0.12+8 and ANDROID_HOME=~/Android/Sdk in a worktree. Left: no device check; long OK has no on-screen hint; desktop shows no Starting pill (no pill host there).
- W3 done (worktree agent-a0286427b1ab37360, reset to main first, 6 local commits, not pushed): 2b9fa77 ON-6 (gate wait armed by signedIn && !setupPending, `waitedOutFlow`), ab38977 ON-7 (`ChatsGateWaiting`: picker.loading + after 4 s `picker.continue_without`; `ChatsGate.waive()` in-memory waiver for this visit, cleared on sign out, `decide(waived=)`), 1b8ecaf ON-4 (`onContinue` in AddChatsScreen: shown when list complete, no recommendation, nothing ticked; TV: Right from the list reaches it when Done is off), 497e0c2 ON-15 (`HomeEmptyCard`: Picked mode says nothing to show from chosen chats + Choose chats; forward tip hidden under Picked), 76aa463 ON-17 (`Submission.slow` after the 8 s ceiling shows Proxy settings in the phone pane and TV extras), b98e99c ON-14 (askKeepOnce moved to Watch.tsx on first video found). Checks green: core ChatsGateTest, CatalogTest; ui ChatsGateWaitTest; desktop ChatsGateWaitingTest, AddChatsRenderTest, HomeEmptyCardTest; app AddChatsTvKeysTest (+2 new), SubmissionTest. New keys: picker.continue_without, home.nothing_picked_title, home.nothing_picked_body (other 21 catalogs needed).
- W3 decisions: ON-13 not built: the user removed the Home button on purpose (54fa106, "stays on Chats") and Chats already has ChooseChatsButton. ON-4 waives for this visit (nothing written, as web setChatsGateWaived); MIN_CHATS counting of bots unchanged. ON-17 skipped the Failed pane (its footer already shows Proxy settings, no keyboard). ON-14: the web has no download, so first Watch only. `pnpm check` has 20 pre-existing TS7006 errors, none in touched files. Left: device check of the waiting pane and the new links; AddChatsTvKeysTest "Back from the band's button" failed once, passed on rerun (flaky).
- W6 done (worktree agent-af32b2f3d81cf6de5, reset to main first, 4 local commits, not pushed): 6a56f31 PL-14 (openStoredNow plays at once when Td.localFileAvailability is Complete or offline; a background lookupMedia refreshes the id), 4bd9dfa PL-19 (HistoryEdits.markWatched + HistoryUndo.marked, reuses takeResume/putBackResume; phone via HistoryRemoval Undo toast, desktop via runHistoryEdit and DetailPane; TV asks first with TvConfirm only when a place exists; keys browse.mark_watched_title and _message, reuses main.marked_watched), a84f123 PL-3 (HomeRows.withArt(..., gone) keeps Continue and Watch later cards, drops only GoneVideos; Recent, Chat and NewEpisodes still need art), 312aa9b PL-13 (LocalOfflineDownloads + OfflineDownloadsHost: one presentIds read per offline stretch; Downloaded badge on Continue and Watch later cards; Offline pill opens Downloads on touch, passive on TV). Checks: core HistoryEditsTest (9), DesktopHomeMixTest (8), CatalogTest; app OfflineHomeTest (4); :app:compileDebugKotlin, :desktop:compileKotlin. Decisions: Undo length is the shared action toast's, not forced to 8 s; no Downloaded row on top of Home (a HomeRow type change across 3 platforms); desktop has no offline pill or marks; TV pill stays passive by design (a TV Downloads entry is PL-16, a user question). Left: no device check; a stale Undo after the app closes is simply gone (in-memory closure).
- D-4 + D-5 done (worktree agent-a97a4a42bad490979): 8a7a7ba (D-4: LoadingChip gains starting and bufferFull, chip says "Getting the next part" while playing, speed line in Playback details, keys player.getting_next_part and player.details_line_speed), 44a6270 (D-5: WidgetAvailability.sync from App.onCreate, manifest enabled=true, both bools.xml removed). Checks green: core LoadingChipTest (9), app WidgetAvailabilityTest (5), :app:lintVitalRelease. Left: webapp chip (Chrome.tsx cached_chip) not changed; new keys need the 21 catalogs; no device check.
- D-1/D-2/D-3 done (worktree agent-a873d6fa281334e54, reset to main first, 3 local commits, not pushed): d92a1aa D-1 (TourOfferFrame puts the card in a row under the page; TvBrowseHint onBusy holds it back until the hint is gone; slim one line card on TV), 5852e2a D-2 (SetupButton tonal param, used by Use recommended settings only), 68857a3 D-3 (Pane topOnTv + scroll param, LoginForm starts at the top on TV and scrolls to the buttons on a phone when the keyboard is up; LoginForm now internal). Checks green: app TourOfferFrameTvTest (4) and PhoneTest (1), LoginFormKeyboardTvTest (3, fails with the centred column), desktop SetupRenderTest.useRecommendedStaysSecondaryUnderTheFocus (fails without tonal); :app:compileDebugKotlin, :desktop:compileKotlin. Left: SetupRenderTest.deviceStepFromTheKeyboard fails on main too (assertTrue(done) after Enter on Done), not touched; no device check (keyboard height assumed 55% from t2.png; phone scroll-to-buttons unverified on a real IME); desktop has no tour offer.
- W9 done (worktree agent-ac66fbe8c4976dfea, reset to main first, 4 local commits, not pushed): c51421a PL-1 (HomeFeatured.mix resumeFirst + HomeFeatured.leading, records played within 14 days with a ResumeRules.keeps point; shared ui mixedFeatured used by phone, TV and desktop (desktop keeps new first); TV start focus stays on the first Continue card when the lead page is a new title and Continue is the first row), 81a2cbe PL-2 (BrowseScreen.openResume plays at once when ResumeRules.canResume, else the page; Watched keeps the page; desktop ContinueCell Poster onOpen; hold, Info and right click still open the quick menu with Details; reverses the 2026-10-23 rule, noted in the code comment; the time left chip is the cue, no new text), 3cae21c PL-18 (MiniVerdict.Paused, MiniContext.pausedForMs, PAUSED_CLOSES_AFTER_MS = 4000; phone counts from onPlayWhenReadyChanged, desktop from the engine playing flag; casting, lock, PiP and the setting still win), 93c2d92 PL-16 (TvTopBar compact BarTab entries Downloads and Watch later after Chats, before Choose chats). Checks green: core DesktopHomeMixTest (+3), ResumeRulesTest (+1), MiniPlayerTest (+2), SameTitleTest; app TvTopBarQuickEntriesTest (4), ChooseChatsBarTest; :app:compileDebugKotlin, :desktop:compileKotlin. Decisions: no new en.json keys (reuse navDownloads, browseTabWatchLater, time left chip); Pause threshold 4 s; Continue tile with canResume false (at the very end or no position) opens the page; hold stays the quick menu (Details one step away). Left: no Compose test of HomePane start focus or of BrowseScreen.openResume (inline, heavy); TV D-pad traversal of the bar (Left, Right, Down, Back) is NOT machine tested: bar items refused focus in the Robolectric harness (even with the entry hook off), so only order, focus target, OK routes and icon-only state are tested; no device check; web (Home carousel resume lead) not changed (web Continue already plays at once).

## Round 3 (user go 2026-11-15: "do all things", recommendations as recommended)

### W9 Home, Continue, paused Back, TV quick entries (PL-1, PL-2, PL-18, PL-16)
model: sonnet high. files: HomePane.kt rememberFeatured, core HomeFeatured.kt, BrowseScreen.kt tile press,
core MiniPlayer.kt verdict, PlayerActivity.kt back handling, TvTopBar.kt, en.json.
decisions (user approved the recommendations): PL-1 phone and TV Home carousel leads with the video being
watched (resume slide first, desktop already mixes it); PL-2 a Continue tile with a resume point plays at
once, hold or Info opens the page, the Watched list keeps opening the page (this reverses the 2026-10-23 rule
that Continue and Watched answer the same press); PL-18 Back on a video paused for more than a few seconds
closes it and keeps the resume point instead of docking it to the mini bar; PL-16 TV top bar quick entries
for Downloads and Watch later.

### W10 TV software video (C3b)
model: sonnet high. files: webapp/src/player/*, webapp/libav, webapp/tv.
done when: non-H.264 pictures (MPEG-4 Part 2, WMV, MPEG-1/2, H.263, VC-1) play on the webOS and Tizen TV
bundle through the libav.js software decoder drawn on a canvas, capped to a resolution the device keeps up
with, with A/V sync to the audio clock, a clear message when it cannot keep up, and the 'needs the app' page
as the last stop.

### W11 Cast gaps (C4b)
model: sonnet medium. files: core cast (CastPlan, ReceiverCaps), app/.../cast, desktop cast.
done when: the link speed is measured (a short probe to the receiver or the local path) and sets the
transcode bitrate instead of the fixed 1080p 8 Mb/s; the DLNA renderer's own list of what it plays (SinkCaps)
is passed into the plan so the MPEG-TS route is reachable; the desktop gets a battery and heat guard like the
phone's (or a documented platform limit where the OS exposes neither).

### W12 Translations (after W9 and W11 merge) and the stick pass (after W9 and W11 merge)
- W10 done (worktree agent-ad9f730cc0d8ed0cc, reset to main first, 6 local commits b02687c..18dbcd7, not pushed): the canvas engine
  for TVs and any browser with no WebCodecs encoder. webapp/src/player: canvasclock.ts (pure: MasterClock on the audio context, chooseFrame
  drop/repeat, FrameRing bounded by 24 frames and 10 MB, DecodeGauge, SoundTimeline gapless placement), libavplanes.ts (YUV planes, float sound),
  canvasdraw.ts (WebGL 3 textures + BT.601/709 shader, else 2D), cueoverlay.ts (subtitles, shared with movi, no replaceChildren), libavcanvas.ts
  (CanvasEngine: libav.js worker decodes, Web Audio plays and is the clock, takes the `<video>` over like movi so the TV remote and focus are
  untouched), libav.ts readHeader shared. Player.fallback tries it after libav-to-MSE and movi, before the needs-the-app page (picture, no-mse and
  too-heavy reasons). Caps: canvasPixels 1024x576 on <=4 cores or <=2 GB, 720p phone, 1080p desktop; DecodeGauge says web.needs_app_too_heavy
  below ~20 fps of decode (24 frames, 1.5 s). No new strings. libavload now loads from BASE_URL (dist-tv/libav under /tv/).
  Checks: pnpm test:player 120 pass (17 new); tsc; e2e canvas.spec (23: AVI, WMV, FLV, RealMedia, VOB, MPEG-2 TS, MPEG-4 in MKV, Theora with
  VideoEncoder/Decoder removed, picture within 80 ms of the clock, pause, seeks, end, speed, 2D fallback, too heavy, needs-the-app last) plus
  player.spec and oldformats.spec (51 total green), and canvas+oldformats spec (33) again on the `vite build --mode tv` bundle (chrome68 target).
  Sizes gzip: main entry index unchanged (38,004 B tv), Player chunk +0.5 KB, new lazy libavcanvas chunk 26.9 KB raw / 9.6 KB gz.
  NOT run: Chromium 68 and 79 (snapshots r555668 and r706915 were downloaded to the scratchpad but running a downloaded binary was denied by
  the permission classifier, so no old-engine run; the TV bundle is only checked on current Chromium: no optional chaining, no replaceChildren,
  no inset in the lazy chunk), WebKit, any real TV. Left for the real webOS/Tizen TV: WebGL texture upload speed and context loss on the stick,
  AudioContext start without a gesture (falls back to a silent timer clock after 1.5 s), outputLatency on a real TV (picture offset to the
  speaker), memory on 1 GB, decode fps against the 20 fps verdict at 480p, /tv/libav loading from the hosted wrapper.
- W16 done (worktree agent-a3524b126de6f1894, reset to main first, 3 local commits, not pushed): 023e76e (cause: HomeFeatured.mix required every page, resume ones too, to be complete = online match with wide picture and overview, so a resume video with no art and no match was dropped; mix gets resumeComplete, mixedFeatured passes always true, new pages still gated; size/extra/14 day/finished/dedupe rules unchanged), 33756b6 (hero: time left in the facts line via SeriesWatch.leftSec on phone, TV, desktop, no new key; GeneratedCover playMark off inside EdgeBackdrop; continueStartsTheRemote), d57b40d (tests). Checks green: core DesktopHomeMixTest (+5), HomeFeaturedTest; app ResumeHeroNoArtTest (TV text, start focus on Resume, OK, Right/Left, Down/Up, phone upright and landscape), TvBannerFocusTest, FeaturedCarouselRenderTest; desktop HomeCarouselResumeNoArtTest (960, 411, 1280) and HomeCarouselRenderTest. Left: with online lookups off the carousel is still hidden (rememberFeatured returns empty); no device check; a long key sequence in one Robolectric test crashed the native JVM twice (JniConstants ShortBuffer), split into three tests; match swap-in is the existing EdgeBackdrop path, not separately tested.
