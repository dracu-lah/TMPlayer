# TMPlayer master plan

One file for everything still to do. It merges the 2.0 roadmap (`docs/superpowers/specs/2026-10-05-roadmap.md`,
removed in the commit that added this file), the listing plan artifact
(https://claude.ai/artifact/7vQ2jWqry627JP3GU6AMfQ) and the untracked `DISTRIBUTION_PLANS.md` notes. Written
2026-10-06 against `124523d` (1.22.1).

## How to use this file

**You (the user):** tick a box in the Status board when something is done, or tell Claude "CP07 done" or
"U05 done". After every checkpoint, run `/clear` and start the next session with:

> continue the plan

**Claude, at the start of a fresh session:**
1. Read only this "How to use" section, the Status board and the Standing rules (`sed -n '1,/^## Claude checkpoints/p' docs/PLAN.md`).
2. Pick the first unticked CP whose "Needs" are met, or the one the user named.
3. Read only that CP's section (`grep -n '^### CP' docs/PLAN.md`, then read that range) and the Design reference
   section it points to. Do not read the rest.
4. Do the work. At the STOP line: build and test as the CP says, commit, tick the box here with the commit hash,
   push, and report in at most five lines. Then wait. Do not start the next CP in the same session.
5. If a CP turns out bigger than one session, split it (CP07a, CP07b) in this file and stop at the split.

## Progress / handoff (session f7d1e1d6-da4f-4926-9044-e56e81f05ad0, 2026-10-31, read this first)

A fresh session resumes from this section alone. Nothing below HX31's core is built yet. Rules that bind every item:
HX27 to HX41 below are the plan; AGENTS.md rules apply (no dashes, outside data untrusted, en.json only, no langsync, no
releases without the guard). Max two agents at a time, compile and unit tests only while they work, one device pass at the
end (phone, TV and desktop emulators; the real POCO M2 Pro `d3f7da23` or POCO F5 `387acac9` over adb only for the final check,
`install -r` of a local RELEASE build keeps the login, never the debug build). Boundary (HX41): nothing fetches content from
bots or unknown sources, no plugin API for video catalogues.

State on disk right now:
- DONE 2026-10-31: HX31 core rules 1 to 4, commit `1fc406a` (local, not pushed), SupportReminder KDoc fix `3499dbb`. All
  `:core:jvmTest` and `HomeScaleTest` green. Regression diff over `all-grid-titles.txt`: 357 of 2,868 changed, all intended
  (330 `@CC Name` lose the handle, 20 underscore handles such as `@CC_ALL_*`, `@cinema_company_*`, `@Mallu_Movies_*`,
  `@MM_New_*` that read as EMPTY before now give their title, 2 glued years, 4 short language tails, 1 invisible prefix).
  Deviations from the design below: rule 4 keeps U+200C/200D (ZWNJ, ZWJ) after a letter or mark of a script past Latin,
  since Malayalam chillu and Persian spell words with them (guard row in the corpus); the language tail strip trims back
  without `clean`, so `Name (2k23) Mal` keeps its closing bracket (guard row). Corpus test:
  `JAVA_HOME=$HOME/.jdks/jdk-21.0.12+8 ./gradlew :core:jvmTest --tests 'com.tmplayer.data.NameEdgeCasesTest' --max-workers=2 --offline -i | grep -E 'expected|got |BUILD'`.
  The survey dump test was temporary and is deleted; recreate it the same way for the HX31 second pass.
- DONE 2026-10-31: HX32 reviewed and cherry-picked onto main as `16b0c04` (local, not pushed); worktree and branch removed. Archive
  paged to a 4000 ceiling (first frame still 100), failed archive or folder reads and dropped chats reported (`ChatSync.partial`,
  `FolderMembership.failed`), "Some chats could not load." foot and folder Loading / could not be read pages with Try again,
  `ChatListLoadTest` (9), `HomeScaleTest` now asserts all 1,800 chats (sync budget 5 min of virtual time, flood waits included).
  Open: three new en.json keys (`browse.folder_loading`, `browse.folder_not_loaded`, `chats.some_not_loaded`) need the other 20
  catalogs before a release; on the device pass, check whether TDLib caps `getChats` at 1000 per call (would cut the 4000 archive
  ceiling and the main list's load more short) and that a chat getChat keeps failing on does not leave the partial foot up for ever.
  Main is up to date with origin plus these three commits, nothing pushed.
- DONE 2026-10-31: HX39 `d1dde51` (local, not pushed). `showCoverTitle` on `MediaArt`/`PosterArt`/`MediaPreview`; Android grid, desktop grid, search results, episode rows pass false. Not changed: the poster at `Series.kt` ~1007 still draws its cover title (caption beside it unconfirmed). Device pass: check a poster-less card on phone, TV and desktop shows the name once.
- DONE 2026-10-31: HX33 `b4b3dfc` (cause: a DataStore whose scope ended completes empty; `SafeDataStore.data` now `onEmpty` emits empty prefs; Shell open-last-chat and Thumbnails wait use `firstOrNull`; the R8 mapping was NOT checked, caller inferred). HX36 `fa9f905` (`MpvNatives.create` catches `LinkageError`, remembers it for the session, `NoEngine` stand-in, player status sheet shows the message; new en.json keys `player.engine_blocked`, `player.engine_missing` need the other 20 catalogs before a release; message ends with plain URL tmplayer.org/install, INSTALL.md got a Smart App Control paragraph, the site's Windows page did not; real Windows block path untested, needs the win11 VM). HX34 `ed432dd` docs only: the throw is in the wrapper's own receive loop (`TdlEngine`), not catchable in our code, swallowing it in the default handler would freeze the app. New task: bump tdl-coroutines 15.0.0 to 17.0.0 (TDLib 1.8.67 to 1.8.69, 17.0.0 fixes "gift without a background", the `photo` key unconfirmed, API breaks, needs a phone and TV pass, offline cache has only 15.0.0); report the two missing keys upstream. After the merge `:desktop:compileTestKotlin` and `:app:compileDebugKotlin` pass. Main is 9 commits ahead of origin (1fc406a, 3499dbb, 16b0c04, d1dde51, b4b3dfc, ed432dd, fa9f905, 58b65fb, e08fddf), nothing pushed. Whole pass after HX35 and HX37: `:core:jvmTest` 1,902 tests 0 failures, `HomeScaleTest` 5 of 5, `:app:compileDebugKotlin`, `:desktop:compileTestKotlin` and `:ui:compileKotlinJvm` green, the dash grep clean.
- DONE 2026-10-31: HX37 and HX11 `58b65fb` (worktree agent, reviewed, fast-forwarded onto main, worktree and branch removed). New
  `FrameRates` and `FrameRateEstimator` in `core/src/pureMain/.../player/`; `PlaybackWatch` rewritten: a 2 s settle after the start, a
  seek or any `interrupted()` (buffering, pause, a non-seek discontinuity, a decoder or format change), the pace measured against the
  speed the viewer asked for (0.75x no longer reads as 75 per cent), late frames count only with more than 2 per cent dropped and at
  least 10, and one `PlaybackWatch.symptomOf` is the single definition of a stutter, used by the watch's answer and by the new
  `StutterReport.isReal` gate (checked before the daily limit, so a false alarm cannot use up the day's one report). A Matroska with no
  default duration gets its rate measured from 48 rendered frame times (median band, snapped to a standard rate within 1.5 per cent, 3
  attempts then unknown, hook released after one answer); unknown stays 0 everywhere (`DecodeJudge` assumes 30, the display request is
  cleared, the frame step falls back, the details row says nothing), and the bit rate falls back to size over duration. New tags
  `symptom` and `fps_source`. No new en.json keys. `FrameRatesTest` (15) and `StutterGateTest` (16) added, `DecodeJudgeTest` split.
  Open: the display switch now happens about 2 s into a video when the rate had to be measured (a mid-play mode switch, may blank
  briefly) and whole-millisecond Matroska times cannot tell 23.976 from 24. Device pass: Mi TV over adb Wi-Fi with a 720p Matroska
  without a default duration (look for the `Frame rate: container said none, measured ...` log line), "Match content frame rate" set to
  Always, 1080p 10 bit HEVC, idle CPU with the frame hook attached, and 0.75x and 1.5x give no "Stuttering?" offer. Not shipped on
  purpose: the software decoder fallback for 4 core armv7 TVs. `DecodeJudge` already calls 1080p 10 bit HEVC software
  `SOFTWARE_TOO_SLOW` there, and the Changhong reports were `OMX.MS.HEVC.Decoder`, which it counts as hardware, so it cannot predict
  the problem; whether software or steering to a lighter copy is better needs a device pass.
- DONE 2026-10-31: HX35 `e08fddf` (main session). `letGoOfPlayer()` in `PlayerActivity`: the player is stopped and released FIRST and
  the view hands its surface back after, because a released player has already taken its callbacks off the surface view and marks a
  message to the playback thread as handled instead of sending it. The old order paid Media3's detach cap (2 s) once per video renderer
  (there is more than one, the FFmpeg renderers sit beside the hardware ones) and then the release cap again, which is what the two
  Changhong ANRs caught. `setReleaseTimeoutMs(500)` spelt out on the builder so a later Media3 default cannot raise it. Never twice:
  the field is cleared first and `isReleased` is checked; PiP and background audio never reach it. `resumeHeldDownloads()` (the only
  work `finish()` did beyond `super.finish()`) now runs on `App.backgroundScope`: it started a foreground service per held download,
  which is the Binder call the second ANR caught Back waiting on. No unit test: the change is teardown ordering against the framework,
  with no pure logic to assert. Device pass due: a TV emulator and the Mi TV, leave a 1080p 10 bit HEVC Matroska mid-play with Back and
  with the next-episode handover, and check the next video still gets a decoder.
- Survey data kept in `private/name-survey/` (gitignored): `all-grid-titles.txt` (2,868 distinct titles from MSB, DiccoBot and its
  twin), the per-chat raw lists, `shapes-so-far.md`. Use `all-grid-titles.txt` as a REGRESSION set: print `tileTitle` of every line
  before and after the HX31 change (a temporary jvmTest that writes a file) and diff; only the intended shapes may change.
- User to do: set the sort of "Add-on Database: MSB" back to Newest (an agent left it on Oldest); say whether Zeyrox Movies Official is
  links-only (10bitclub is); confirm the defaults (X = 20 videos, cap 30; chat photos only in the picker; pin, mute, archive and mark
  read removed from the chat menu; existing users see everything until they answer HX28).

- DONE 2026-10-31: HX27 phase 1, the core of the added chats, commit `883fb34` (local, not pushed). What is on disk now:
  `AddedChats` + `AddedChoice`/`AddedChat`/`AddedMode` (`core/src/pureMain/.../AddedChats.kt`, the `HiddenChats` codec,
  keys `added_chats` and `added_chats_mode`), `AddedChatsSource` on `SettingsStore` (`setAddedChats`, `keepAllChats`,
  `addChat`, `removeChat`) and on `SettingsBook`, both in `Backups.SETTINGS`; `ChatGate.browsing` (kids and added met in
  the middle) plus `awaitBrowsing()`, with `ChatGate.allowed` left meaning the kids lock ALONE. Switched to `browsing`:
  `BrowseViewModel.publish` and its two snapshot waits, `HomeViewModel.rememberHomeChatList`, `ChatRepository.recentMedia`
  and `searchAllChats`, `GlobalSearchViewModel` (its live filter and `start`), `TvHomeChannels`, the favourites on phone,
  TV and desktop, `VideoCopies`, `NameReport`, and the background checks in `BackgroundChecks` and `DesktopChecks`. Left
  on `allowed` ON PURPOSE (do not "fix" these): `WatchedStore`, `WatchLaterStore`, `WatchedBook`, `continueWatching`,
  `DownloadResults`, both Downloads screens, `LinkFlow`, `TelegramLinkActions`, `MainActivity`'s deep links,
  `ChatRepository.chatInfo`/`summary`, `MoreVideos` and `MoreVideosUi` (a channel's discussion group is a chat of its own
  that the viewer has not added, so gating comments would break them), and `PhoneBrowse`, `TvSearch` and `SearchPalette`,
  where `allowed != null` means "the kids lock is on, hide the recent searches" and would otherwise hide the viewer's own
  searches the moment they added a chat. New: `ChatVideoCount` (commonMain, the TDLib counter) with its pure shapes in
  `ChatCounts.kt` (`ChatCount`, `CountAnswer`, `ChatCountCalls`), `ChatSuggestions` (pure: 20, 30, 5 and `shape`),
  `HomeLayout.overAdded`, `ChatRepository.searchAddedChats` + `AllChatsCursor.chatsSearched` +
  `AllChatsSearch.CHATS_PER_PAGE` (8) and `ChatRepository.CHATS_AT_ONCE` (3), `StateAction.AddChats` and
  `AddChatsRequest` (the `SearchRequest` pattern: the shells open the picker), en.json `browse.add_chats` and
  `browse.no_chats_added` (translations due). Deviations from the design above: named `AddedChats`, not `ChosenChats`
  (`HomeLayout.chosenChats`, `RecentSource.Chosen` and `home_recent_chats` already mean Home's narrower "Chats for
  Recently added"); and sign out WIPES the choice rather than keeping it (the HX27 edge list said keep, but
  `clearEverything`'s own rule is that nothing of the previous account is left behind, and chat ids are the account's,
  so the picker asks again after a sign out). Green: `:core:jvmTest` 1,933 tests, `:ui:jvmTest` 180 (HomeScaleTest among
  them), `:core:compileKotlinJs`, `:ui:compileKotlinJvm`, `:app:compileDebugKotlin`, `:desktop:compileKotlin`, dash grep
  clean. NOT built yet, in this order: phase 2 (the picker screen, two agents), phase 3 (HX30 b and c, and the web app),
  phase 4 (HX30 a, the colour art). Loose ends phase 2 must close: `StateAction.AddChats` has no host wired yet (the
  button appears on the Chats page's new empty state and does nothing until a shell takes `AddChatsRequest`), and Home
  with an empty added set falls to the old "none chosen" line instead of an Add chats card (edge (d)).

- DONE 2026-10-31: HX27 phase 2, the picker screen and its scale tests, commits `327ea46` (the rules and the
  view model), `f0d61ae` (the scale harness and two fixes) and `64514f0` (the screen). On disk now:
  `ChatPicking` (pure: the bands Saved, Recommended, Starred, Chats, Archived, Gone; a search or a filter
  answers about every chat with no bands in the way; Select all and Clear act on what is visible and leave a
  tick made under another filter alone; `needsConfirm` over 50; `total`; `headStart`; `counted`),
  `AddChatsViewModel` (reads the stored choice, counts the head of the list once, counts a row as it appears,
  takes the recommendation ONCE, knows whether anything changed, writes the choice with each chat's name,
  stops every count on close), `ChatListViewModel.everything` (the ungated list the picker needs),
  `AddChatsScreen` in `:ui` with `AddChatsFed` and `newAddChatsViewModel`, the phone and television host
  `app/.../browse/AddChatsPage.kt` (a `Screen.AddChats` of its own, reached from Settings, the Chats page's
  empty state and `AddChatsRequest`), the desktop overlay `desktop/.../ui/AddChats.kt` with Ctrl+A and Cmd+A,
  27 new en.json keys in a `picker` group (ENGLISH ONLY, the other 20 catalogues are the user's to sync before
  a release), 5 render tests and 10 `ChatPickerScaleTest` tests plus a `HomeScaleTest` case and a
  `ScaleCpuTest` budget. Verified independently after the agents finished: `:core:jvmTest` 1,946,
  `:ui:jvmTest` 191, `:app:testDebugUnitTest` and `:desktop:test` green (AddChats render 3 of 3 and 2 of 2),
  all four compile targets and the JS target green, dash grep clean, `webapp` tsc clean.
  Two defects the scale tests found, both FIXED not asserted around: the Recommended band waited for the whole
  head start, so one chat flood waiting for 20 s held it back (now taken from whatever answered after 3 s,
  measured 3.05 s, and it still never re-sorts); and a timed out count was asked again behind a call TDLib was
  still holding, which cost 5 calls in flight against a budget of 3 and 492 requests where a lossless run makes
  489 (a timeout is now final with Retry on the row, timeout 20 s, measured 489 and 3 in flight).
  Measured, for the device pass to confirm: `ChatPicking.rows` over 3,000 chats costs 1.0 ms as listed and
  7.2 ms searched on this machine, so about ten times that on a television stick (it runs in composition, per
  keystroke; the budget test guards it).
  URGENT beside this (user, 2026-10-31): HX44, refresh on the chat list is slow and nearly pointless, since
  TDLib's updates already keep the list live and only a brand new chat is missed. Its step 1 (read the one
  unknown chat an update names) stands alone and can go before or beside phase 3; its step 3 (re-read only the
  added chats, leave the 4000 deep archive walk to the picker) wants the gate that phase 1 already landed.
  STILL OPEN from phase 2, carry these into phase 3: the "downloads and history stay" confirm when unticking a
  chat is not built (the screen has no downloads or history query); Home with nothing added still falls to the
  old "none chosen" line instead of an Add chats card (edge d); `tvReachRoot` and `tvReachable` are not used on
  the picker and first focus lands on the search field rather than the first row, which wants a look on a
  television beside HX42; one view model per opening stays in the activity store until the activity ends (a
  `reset()` on the view model would let the host drop it); process death loses unsaved ticks (rotation does
  not); `AddChatsState.open` has no shared host, the desktop shell opens it; the picker applies the kids lock
  itself because `everything` is deliberately ungated.

- DONE 2026-10-31: HX27 phase 3, two of its three parts. HX30 (b), the Telegram writes removed, commit `a05a5c7`
  (rebased from the agent's own `bb706e9` onto main, since its worktree had started before phase 2 landed and
  had to be rebased by hand through two small conflicts in en.json and BrowseViewModel.kt, both resolved by
  keeping phase 2's additions and dropping only what HX30(b) itself made dead). Deleted: `ChatRepository.
  setPinned/setMuted/setArchived/markRead`, the four `BrowseViewModel` actions plus their now-unused `arrange()`/
  `act()` helpers, the Android `TvMenu` and desktop `ChatRowMenu` entries and P/M/A/R shortcuts for the same four,
  `TelegramLinkActions.joinInvite`/`joinPublic` and the `JoinOutcome` type, and the in-app Join buttons in
  `ChatInfoSheet`/`ChatInfoPanel`. The row menu is now Open, Star, Hide only (no Details/Mark-watched existed on
  it before). `LinkFlow.openInTelegram()` replaces `join()`: no TDLib call, just hands the chat's t.me URL to a
  new `onOpenExternal` callback (`Intent.ACTION_VIEW` on Android, browser launch on desktop); the not-a-member
  sheet now reads "You're not in this chat." with an Open in Telegram button. `isPinned`/`isMuted`/`isArchived`
  display (badges, the Archived chip, sort order) is untouched, only the ability to SET them from the app is
  gone. ~41 now-dead i18n keys pruned from en.json and all 20 other catalogs (unused keys only, no translation
  work needed). Untouched on purpose, awaiting the user: `ResumeSync.kt sendMessage` (cross-device resume) and
  `AccountSessions.kt terminateSession` (ending another sign-in from Settings > Devices).
  The web app's picker and gate, commit `b0c3088` (worktree agent, reviewed, fast-forwarded onto main). Built on
  top of the already-written, previously inert `webapp/src/store.ts` half: `webapp/src/screens/AddChats.tsx`
  (search, select all/clear, star toggle, per-row video count via a batched worker call, a "No longer available"
  band with Remove, Done writing through `store.ts`'s existing `setAddedChats`); `library.ts` gained
  `addedSetOf()` and the gate is now threaded through `across`, `searchAll`, `recentPage`/`loadRecent` (a chat
  dropped from the choice since a page was read drops its videos too without losing what else was kept);
  `videoCounts(tg, chatIds)` reads each chat's rough count via `tg.searchMessages({..., limit: 1}).total` summed
  over the video and document filters, batched 3 at a time, 10 minute cache, failures never cached (-1, not
  cached, as `ChatVideoCount` does in the Kotlin apps). Home, Search, Recent and Chats now all send/read the
  gate; a new "Add chats" entry sits in Chats' header and Settings > Library (phone/desktop) plus a TV panel.
  New route `/added-chats`. Deliberately simplified versus the Kotlin picker: picker search is a plain client-
  side substring match, not `rankChats`'s fuzzy rank, since the whole list is already in memory. `keepAllChats()`
  is not wired into any web UI control, matching the Kotlin app where it is also unwired pending HX28.
  Verified independently after both merges: `:core:jvmTest`, `:ui:jvmTest`, `:app:compileDebugKotlin`,
  `:desktop:compileTestKotlin` all green on main at `7e0067c`; webapp `tsc --noEmit` clean (verified by the
  agent before merge). Dash grep clean. Both worktrees and their branches removed after merging.
  DONE 2026-10-31: the third part of phase 3, HX30 (c), commit `3206b5b` (worktree agent, reviewed, fast-forwarded
  onto main). Found mostly already built by phase 1/2 (the Starred band in `ChatPicking.kt`, the picker already
  fed real starred data, `KidsLock.favourites` already gating by `ChatGate.browsing`): the one real gap was a
  way to star or unstar a chat from inside the picker itself without leaving it. Added `AddChatsViewModel.
  toggleStar` (calls the existing `SettingsStore.toggleFavorite`, no second favourites store) and wired it to the
  picker row's long press (Menu key on TV/keyboard) when the row's count is known (when unknown, long press
  still means count-again, as before); a `CustomAccessibilityAction` added for screen readers, reusing existing
  strings, no new i18n. New regression test in `AppLockTest.kt` locking in the existing behaviour. The agent
  flagged a genuine ambiguity rather than silently resolving it: whether a starred-but-unadded chat should still
  show in Favourites (what the item's wording implied) or stay hidden until re-added (what phase 1 actually
  shipped, with its own code comment defending the choice). ASKED, user decided 2026-10-31: keep the shipped
  behaviour (`ChatGate.browsing` stays as is at all three call sites); no code change needed, matches how every
  other list is gated.
  Phase 3 is now fully done. Verified on main at `3206b5b`: `:core:jvmTest`, `:ui:jvmTest` green.

- DONE 2026-10-31: HX27 phase 4, HX30 (a) the colour art, commit `c2984d5` (main session). Nothing was removed: the
  apps already draw a real chat photo everywhere, so this only replaced the grey disc behind a MISSING one. New pure
  `NameColour` (`core/src/pureMain/.../data/NameColour.kt`): the eight tuned pairs moved out of `CoverArt.Pairs` (which
  now reads them from core, same values), and the pair a name wears comes from FNV-1a over its UTF-16 code units, low
  byte then high, folded once, over the name trimmed, blank runs collapsed and lower cased. Never `String.hashCode`:
  the web twin `webapp/src/ui/colour.ts` has to give the same answer, and both tests assert the same 17 name table
  (`NameColourTest`, `colour.test.ts`, plus a spread check that 4,000 names fill all eight). A blank is the four
  characters spelt out in both files, not `Char.isWhitespace` or `\s`, which disagree over a no-break space.
  New `NameArtTile` + `NameArt` in `:ui` (`components/NameArt.kt`): the pair as a diagonal gradient with the covers'
  own top-start light (built in `drawWithCache`, once per size, not per frame), the letter a slot so the television
  keeps tv-material's `Text`, white on it (`Tone.onArt`, and `CoverArtTest` already asserts 4.5:1 on every stop).
  Wired at the three Kotlin sites that draw a chat or an account with no picture: `app/.../components/Art.kt`
  (`MediaPreview`, so phone and TV chat rows, cards, info sheet, account avatars), `desktop/.../ui/Common.kt`
  (`ChatAvatar`, so the desktop list, chrome, info panel, TV top bar), and `ui/.../browse/SearchResultsUi.kt`
  (`ChatAvatar`, so the picker's rows, search results and the avatar stacks). On the web the same colour goes on
  `Initial` (`ui/PageBar.tsx`, inline background so `.initial.saved` keeps the bookmark on the accent), Settings'
  account header now uses `Initial` rather than its own span, and the phone nav and TV bar pictures take `nameArt`.
  A name with no letter at all (emoji only, blank, null) keeps the colour and draws no letter. Left alone on purpose:
  the TMDB cast face in `Detail.tsx` (not a chat, keeps the accent circle) and Saved Messages in the Kotlin apps,
  which has no bookmark art there and so just wears its letter as before.
  Verified: `:core:jvmTest` 1,953, `:ui:jvmTest` 191, `:desktop:test` 464 (the new `NameArtRenderTest` 2 of 2) and
  `:app:testDebugUnitTest` all green, `:app:compileDebugKotlin`, `:ui:compileKotlinJvm`, `:desktop:compileTestKotlin`
  and `:core:compileKotlinJs` green, `webapp` tsc clean, `node --test src/ui/colour.test.ts` 5 of 5 (a new
  `pnpm test:ui` script runs `src/ui/*.test.ts`, which nothing ran before), dash grep clean. No new en.json keys.
  Device pass due for phase 4: a chat with no photo on the phone, the TV and the desktop (one letter, readable, not a
  grey disc), the picker scrolled fast on the Mi TV for the gradient's cost, and the web app's own avatars.
  Phase 4 closes HX27/HX30; the i18n keys still owed from earlier phases (27 `picker` keys, `browse.folder_loading`,
  `browse.folder_not_loaded`, `chats.some_not_loaded`, `player.engine_blocked`, `player.engine_missing`) remain the
  user's to sync before a release.

HX31 core design (decided after reading `MediaName.kt`; implement in this order, each with its red corpus rows):
1. `HANDLE` becomes `@[A-Za-z0-9_]{2,}` (`@CC`, `@MM` have two letters). `handleOf(match, before, after)` gets context from
   `stripDecoration`: `before` = text before the match, `after` = text after it (the extension is already stripped by every caller).
   Keep the existing run-on branch unchanged (a year or tag inside the match: drop the first two words, keep the rest: this is what
   `@HarbourMob_LinkZz_Night_Lantern_2022_...` and `@HEVC_Mob_...` rely on). New: a one-word handle goes whole. A multi-word match
   with no year or tag inside: if it is NOT at the start of the name (`before` only separators and TRIM chars) it is a trailing
   signature (`... @cinema_company`), drop it whole; if at the start and `after` is non-blank and does not begin with a year or tag, it is
   a signature before a name (`@TG_Clips4u -Night Lantern`), drop it whole (old behaviour); if at the start and `after` is blank (the
   name itself is in the underscores: `@CC_Silver_Harbour_Night`, `_@CC_Baahubali_2_The_Conclusion`) or begins with a year or tag
   (`@WMR_Samrajyam.1990.Malayalam...`), the handle is the first word, plus the second only when the match has more than two words and
   the second is in a small `CHANNEL_WORDS` set (all, mal, tam, tel, hin, eng, new, movies, movie, hd, hevc, mob, files, hub, club,
   official, world, company, cinema, tv, tg, dub, dubbed), and the rest, joined by spaces, is the name. The analysis agent's idea
   (drop the second word whenever two title words remain) is WRONG: it would eat "Malappuram" of `@CC_Malappuram_Haji_Mahanaya_Joji`.
   Never leave nothing: if no word remains return " ".
2. Glued year and quality: in `spaced`, after collapsing, `(?<!\d)((?:19|20)\d{2})((?:480|576|720|1080|1440|2160)[pi])\b` becomes
   "$1 $2" (use the lambda form of `replace`, it is multiplatform), only when `containsMatchIn` first (hot path).
3. Short language tail: `\s+#?(?:mal|tam|tel|hin|eng)` directly before a year or at the end, removed from the title (up to three
   times), never when the text before it is blank, an article, or ends in a joining word (`Tales of Tam`); also add `SHORT_LANGUAGE.
   containsMatchIn(tidy)` to the `release` gate in `tileTitle` so the card title drops it. Do NOT add them to `TAGS` (`firstTagIndex`
   would cut real titles and `handleOf`'s run-on test would trip).
4. Invisible characters: remove U+200B to 200F, 202A to 202E, 2060 to 2064, FEFF, 00AD and the variation selectors FE00 to FE0F at the
   top of `stripDecoration` (category Cf, not whitespace). Add `~` to `TRIM_CHARS`.
5. Then, in a second pass with their own rows: math letters folded to ASCII, truncated 32 character tails, `A K A` aliases, the
   year-only and junk titles, `%26` decoding, YouTube ids, per chat prefix learning (cinema company, MM, Movie Hub), spelling twin
   folding (HX31 text above has the guards). Set these apart from the core, they are riskier.
Run the regression diff over `private/name-survey/all-grid-titles.txt` before and after, and the whole `:core:jvmTest` plus
`HomeScaleTest` at the end.

Assignments for the next sessions (model and effort, one agent or session per row, max two at once):
| Order | Item | Who | Model, effort | Notes |
| --- | --- | --- | --- | --- |
| 1 | HX31 core (rules 1 to 4) | main session | Opus, high | DONE `1fc406a` (2026-10-31) |
| 1 | HX32 missing and archived chats | worktree agent | Sonnet, high | DONE `16b0c04` (2026-10-31), merged, worktree removed |
| 2 | HX39 title twice on a poster-less card | worktree agent | Sonnet, medium | DONE `d1dde51` (2026-10-31), fast-forwarded onto main, worktree removed. No new render test (BrowseRenderTest has no assertions); look at the poster-less card on the device pass |
| 2 | HX33, HX34, HX36 crash hardening | worktree agent | Sonnet, high | DONE (2026-10-31) HX33 `b4b3dfc`, HX34 docs only `ed432dd`, HX36 `fa9f905`, cherry-picked onto main, worktree removed. HX34 needs its own task: bump tdl-coroutines 15.0.0 to 17.0.0 (see docs/BUILDING.md) |
| 3 | HX35 player ANR on the Changhong TV | main session | Opus, high | DONE `e08fddf` (2026-10-31). Media3 forbids releasing off the main thread (`verifyApplicationThread` throws and `setThrowsWhenUsingWrongThread` is not public in 1.11.1), so the fix is the teardown ORDER plus bounded caps, not a thread |
| 3 | HX37 and HX11 stutter report | worktree agent | Sonnet, high | DONE `58b65fb` (2026-10-31), reviewed, fast-forwarded, worktree removed |
| 4 | HX27 + HX30 picker and chats list | main session, then Sonnet for the platform screens | Opus, high (design), Sonnet, medium (screens) | plan agreed and written below; phase 1 (the core) DONE `883fb34` (2026-10-31). Phases 2 to 4 still to build |
| 5 | HX29 folders into the picker, HX28 prompt, HX41 search, HX38 recommended copy | one agent each | Sonnet, high | after HX27 exists |
| 6 | HX40 DLNA | main session plan check, then agents | Opus, high | after the chat work, real renderers to test |
| 7 | HX31 second pass | worktree agent | Sonnet, high | the risky rules with their guards |

HX27 + HX30 build plan (agreed with the user 2026-10-31, after reading the code; the item text below still holds,
this is how it is built). Two surveys corrected the item's own assumptions: `ChatAudio.count` has NO TTL cache and no
FLOOD_WAIT handling (the counter is new code, not a pattern to copy), and `recentMedia`/`searchAllChats` are single
GLOBAL TDLib searches over `ChatListMain` that can only be post-filtered, so "added chats only" cannot be done by
filtering alone (with 12 added chats of 3,000 the newest 40 globally hold none).

1. **The gate.** `KidsLock`/`ChatGate` (`core/src/pureMain/.../KidsLock.kt`) is already an allow-list of chat ids where
   null means everything, threaded through 43 call sites, so this is one new gate intersected at a chokepoint, not 43
   new filters. New pure `AddedChats.kt`: `AddedChat(id, title)` kept as one TEXT setting (`id\ttitle` lines, the
   `HiddenChats` codec, so a gone chat's remembered title draws its row), plus a mode key with three states:
   unanswered (= everything, no library shrinks on update), all (explicit "Keep all"), picked (honoured, empty allowed).
   `ChatGate.allowed` keeps today's meaning (kids lock only, UNCHANGED); new `ChatGate.browsing` = kids and added
   intersected. Only the content sites move to `browsing`. Named `AddedChats`, not `ChosenChats`: `HomeLayout.chosenChats`,
   `RecentSource.Chosen` and `home_recent_chats` already exist and mean the narrower "Chats for Recently added".
   - Reads `browsing`: Chats list (`BrowseViewModel.publish`), Home rows, carousel, Recently added, search (local,
     Telegram and chat ranking), More videos, the copies list, TV launcher rows, the Favourites tab, the new-video,
     new-episode and piece alerts, TMDB lookups (they follow the rows).
   - Keeps `allowed` (kids only): Continue watching, Watched, Watch later, History, Downloads, a t.me link the viewer
     opened, a notification they tapped, `chatInfo` and `summary` of one chat. That is edge cases (a), (b), (g), (h).
2. **The global-search problem.** Recently added switches to the existing, tested `RecentSource.Chosen` path (per-chat
   reads over the added set, cap 50) when the gate is honoured: no new fetch path. Search gets a new
   `ChatRepository.searchChats(ids, query, ...)` over the existing private per-chat `search`, 3 chats at a time, reusing
   `GlobalSearchViewModel`'s flood rules (120 s cap, 2 retries), paging by chat on load more. HX41 owns the copy.
3. **Counts, all new.** `ChatVideoCount` in core behind a `ChatCountCalls` seam (so `SyntheticAccount` can fake it):
   `getChatMessageCount(chatId, null, SearchMessagesFilterVideo(), local=true)`, then the server when it answers -1; the
   raw `TdlResult` (never `valueOrNull`) so `Failures.floodWaitSeconds` sees a FLOOD_WAIT and that call alone waits it out
   under 60 s; 3 tries, 15 s timeout, `Semaphore(3)`, a 10 min TTL cache holding successes only. Exact to 999 then "1k+";
   a failure shows "videos" with no number and Retry on long press; the row says "about" (documents are not counted).
   Only on-screen rows ask (a `LaunchedEffect(chatId)` per row, about 12 at a time over 3,000 chats), plus a bounded head
   start of 60 chats in chat order so Recommended can appear. Nothing pages itself; leaving the picker stops every count.
   `ChatSuggestions` (pure, tested): MIN_VIDEOS 20, MAX_PRETICKED 30, archived included, most videos first, pre-ticked;
   if no chat reaches 20, the top five by count shown but NOT pre-ticked with "No chat has 20 or more videos yet."; Saved
   Messages sits first on its own and pre-ticks when its count is above zero. The Recommended set is snapshotted once
   (head start settled, or 4 s) and never re-sorts, so no row moves under a finger.
4. **The picker.** One shared screen `ui/src/shared/.../browse/AddChatsScreen.kt` on the `DevicesScreen`/`ProxyScreen`
   convention (`AddChatsState.open` + a host; phone, TV and desktop all draw it): accent-folded search, filter chips
   (All, Archived, Channels, Groups, People; folders arrive with HX29), the HX32 partial-load foot with Retry and Send
   logs, then Saved Messages, Recommended, All chats, Archived chats; a row is cover + title + a fixed-height count
   placeholder bar + checkbox + star. Select all and Clear act on what is visible only; above 50 a confirm ("Add 120
   chats? Scanning many chats takes longer."); the footer total always says the whole choice; Done with an empty choice
   is allowed. Phone: full screen, shared `BackHandler`, ticks, query and tab in `rememberSaveable` (rotation and
   backgrounding), two taps on Done run once, Back keeps the old choice and asks "Discard changes?" only when something
   changed. TV: `tvReachRoot`, `FocusOnOpen`, `TvSearchField`, focus held on the row after a toggle, Done reachable from
   the bottom, idle CPU measured after leaving. Desktop: a modal page like Downloads and Settings, Ctrl+A and Cmd+A over
   the visible list (the `HistorySelection.kt` `onPreviewKeyEvent` pattern), Esc through the discard confirm. Web: a
   SEPARATE Solid screen (`webapp/` is TypeScript and mtcute, not Compose, so this lands twice): `screens/AddChats.tsx`,
   `added:<id>` and mode keys in `store.ts` beside the existing `star:<id>`, a worker `videoCounts(ids)` using a raw
   `messages.search` with limit 1 to read `count`, 3 at a time, and the gate applied in `tg/library.ts` at `chatList`,
   `knownChats`, `rankChats`, `across`, `searchAll` and `recentPage`; strings through `webapp/strings.config.ts`.
   Entry points now: Settings, Library, "Add more chats" (beside Hidden chats), an empty-Home card, and the Chats page's
   own "No chats added yet" empty with an Add chats button. The one-time prompt and the setup step stay HX28.
5. **HX30.** (a) USER DECIDED 2026-10-31: the coloured initials are the STAND-IN only, "in places wherever avatar image
   is not present use that colored ones", so a real chat photo is still drawn wherever a chat is drawn (picker, Chats,
   Home, TV, web) and the colour tile fills in only when there is none. Reuses the existing `NameInitial` rule (it already
   has a TS twin at `webapp/src/ui/initial.ts`) plus a new TMPlayer palette and an FNV-1a hash so Kotlin and the web
   agree (never `String.hashCode`, which the web cannot reproduce). (b) WIDENED by the user 2026-10-31: "also remove
   features that might change things on telegram directly from here", so every write to Telegram that is a browsing
   action goes, not just the four. Delete `setPinned`, `setMuted`, `setArchived`, `markRead` from `ChatRepository`
   (`toggleChatIsPinned`, `setChatNotificationSettings`, `addChatToList`, `viewMessages` at :404 to :465), the four
   `BrowseViewModel` actions, the Android `TvMenu` items, the desktop `ChatRowMenu` items and their P/M/A/R row keys,
   their strings and tests; the menu keeps Open, Details, Mark watched, Star, Hide; `isPinned` leaves the sort in
   `BrowseSections.filterChats`. ALSO delete joining from a t.me link (`TelegramLinkActions.kt:186,195`
   `joinChatByInviteLink` and `joinChat`): a link to a chat the viewer is not in now says so and offers Telegram,
   which also matches HX41's boundary. The unread badge and the Unread filter chip STAY as read-only display (Claude's
   reading, told to the user 2026-10-31: nothing in the app sends read receipts implicitly, so after `markRead` goes the
   app never writes read state at all, and Telegram's terms ask only that it is not faked or hidden). STAYS because
   Telegram's API terms require it: the sponsored message calls (`SponsoredMessageRepository` `viewMessages`,
   `clickChatSponsoredMessage`, `reportChatSponsoredMessage`). STAYS because it is the sign in, not browsing: the auth
   writes (phone number, QR, password recovery, `Td.deleteAccount` in the forgotten-password dead end). ASKED, awaiting
   the user (2026-10-31, neither touched yet): `ResumeSync.kt:171` `sendMessage` writes the watch position into the
   viewer's own Saved Messages, which is the whole mechanism of cross-device resume, and `AccountSessions.kt:150,166`
   `terminateSession` ends another Telegram sign in from Settings, Devices. (c) A
   Starred section at the top of the added chats, the star on the row and in the picker, and
   `KidsLock.favourites(stored, browsing)` so a starred but unadded chat leaves the Favourites tab while its star stays.
6. **Edge cases that get code.** A gone, left or banned chat keeps its row with "No longer available" and Remove, drawn
   from the remembered title, and ONLY once the chat list is complete, so HX32's partial load never mislabels a chat as
   gone. Unticking a chat with downloads or history asks first and says both stay, and nothing in Telegram is touched.
   Archived and muted chats sit in their own section, pre-ticked only if they pass 20. Sign out clears the set with the
   rest of the prefs (`clearEverything`); a restored older backup reads as unanswered, so everything shows.
7. **Tests, compile and unit only while agents work.** `AddedChatsTest` (codec, the three modes, the gate intersection),
   `ChatSuggestionsTest`, `ChatVideoCountTest` (-1 then the server, the flood wait, the timeout, 3 tries, the TTL, only
   successes cached), a `GarbageInputTest` line for the codec, `BooksMatchStoresTest` and `BackupCoverageTest` entries,
   and a search test with one added and one unadded chat holding the same title. Scale: `SyntheticAccount` gets a
   `ChatCountCalls` through its virtual-time `server(...)` with a new "count" flood bucket (some chats answering -1
   locally first, some failing, one flooding); new `ChatPickerScaleTest` over 3,000 chats, 1,500 archived, 400 video
   chats asserts first rows under 3 s from the disk snapshot, never more than 3 counts in flight, a flood wait taken by
   its own call while the rest go on, no 429 storm, Recommended matching `videoCount` ground truth at the 20 and 30 rule,
   the snapshot never re-ordering, and that counting STOPS after leaving the picker (the idle-CPU rule as an assertion).
   `HomeScaleTest` gets a case: 40 added chats of 3,000, first row under 3 s, inside the search budget, and no video from
   an unadded chat.
8. **Order.** Phase 1 (main session, Opus): the gate, store, book, backup, the switched sites, Recently added through
   `RecentSource.Chosen`, the per-chat search path, the counter, `ChatSuggestions`, the core unit tests. Phase 2 (two
   agents, Sonnet medium): the shared picker screen with phone, TV and desktop hosting, strings and render tests | the
   `SyntheticAccount` and scale tests. Phase 3 (two agents, Sonnet medium): HX30 (b) and (c) | the web app's picker and
   gate. Phase 4 (main session): HX30 (a) the colour art on every platform and its web twin, the whole test pass, the
   dash grep, the new en.json keys listed for the user, the handoff. Device pass listed as due at the end (emulators and
   the desktop AppImage, no phone run, see no-phone-testing).

## URGENT: do these first (user, 2026-10-25)

Crashes and bugs come before every other item in this file and before any feature or web player session
(user, 2026-10-26: "make sure crashes and bugs are fixed in first priority"). Work the table top to bottom:
crashes first, then bugs, then HX5 and HX6, then everything else (the web player sessions in
`private/WEBAPP-PLAN.md` included). A new crash or bug report (GitHub issue, Sentry, a log from the user)
goes into this table at its place by severity and is taken before any feature work that has not started.

- [ ] HX79 (user, 2026-11-15, plan written, nothing built) Player stutter and decoder failures on weak devices
  (Tanix TX6, Allwinner H6: "Automatic" never falls back, VLC and MX Player play the same video fine), the stutter
  cause is unknown, wrong titles from real chats, Sentry is opt-in. Full plan with tasks, edge cases and checks:
  `private/PLAYER-RELIABILITY-PLAN.md` (PR0 to PR9 engine and external player, L1 to L6 logs and "Report stutter",
  S1 to S3 Sentry and privacy page, N1 to N4 title reports and corpus, U1 to U14 missing UX: why a video is not
  starting, hold for 2x, auto subtitles, filters, resume on any device, mini player, topic name search; PR4 is the
  default external player with its chooser). Take PR0 (evidence from the reporters) first,
  after any open crash row below.
- [ ] HX69 (reported 2026-11-02, Android phone, vivo V2050 on Android 13, app 1.28.3, CRASH, URGENT, first in the
  table, not researched yet, no work yet) Crash when downloading videos and series, the reporter thinks for files
  of 500 MB and more ("fix the crashes when downloading files"). No stack trace or log yet. Ask the reporter (or
  check Sentry for vivo V2050 / 1.28.3) for Send logs and one example size. Look first at the download path
  (`DownloadManager`/downloads service, foreground service start on Android 13 and its `foregroundServiceType`,
  notification permission, progress notification updates), and at anything that holds a whole file or its
  progress in memory (low-RAM vivo with aggressive background killing, an OOM on 500 MB+). Likely suspects to
  check, none verified: a foreground service started from the background or killed by the vendor battery
  manager, a progress callback flooding the main thread or notification manager, free space not checked before
  the write, the destination folder (SAF) revoked mid-download, and a partial file left behind. Reproduce with
  a 500 MB to 2 GB video on an Android 13 emulator with low RAM, then on the phone only if asked (memory:
  no phone testing unless asked). Fix with: space check before start, bounded memory, a handler on every
  download job (`JobFailures`), partial file cleanup, resume after a kill, and a "Try again and Send logs" card
  instead of a crash. Tests: a download of a large fake file under a small heap, cancel and kill mid-way.
  Reply to the reporter and close the issue once fixed on main ("next release").
  - **Research 2026-11-02 (session 400aa3cd), no cause yet.** No GitHub issue for it. `sentry-cli` gets 403
    (token is org:ci); Sentry org is `nevil-i0`, try `nevil-i0.sentry.io/issues/?query=device:V2050` in the
    browser. Read and found already handled: FGS start first in `onStartCommand` (dataSync type), space
    check before the fetch, bounded connect wait, ticker 1 s, `DownloadFiles.moveIntoDownloads` copies in
    64 MB `transferTo` chunks to a `.part` file and deletes it on failure, service scope has
    `JobFailures.handler`. Open lead: `DownloadFinisher.finish` catches only `IOException` from the move.
    No Android 13 AVD exists (only api36 phone, signed in). Next: get the stack trace or logs first.
  - **PARKED 2026-11-02:** the user searched Sentry, no event for V2050, so it never reached Sentry (native
    crash, vendor kill or reports off). Blocked on the reporter's Send logs; take the next item meanwhile.
- [x] HX77 (done 2026-11-03, a38c494, not deployed: Maybe later visible and dimmed for the same 5 s, then it, Esc
  and a click outside close; `home.bmc_close` kept, the coffee and UPI dialogs use it) The support popup
  (`site/src/components/SupportPopup.astro`, opened by app.js) reads too pushy. Remove the close icon (the
  `support-pop-close` button and its filling ring); Esc still closes it; "Maybe later" (`support-pop-later`) is shown
  from the start instead of arriving with the x. Keep the same timing (opens a few seconds after load, or after a
  download click on the download and install pages; once per tab session). Check when building: whether Esc and a
  click outside stay locked for the first seconds as now, or close at once (user said "same timing", read as the
  popup's own timing; ask if unclear); remove the ring's CSS and app.js code with it; `home.bmc_close` may then be
  unused (drop from en.json only if nothing else uses it; other catalogs are the user's); update the component's
  header comment and `site/AGENTS.md` if it describes the x. Tests: `pnpm -C site build`, `site-i18n-check.py`,
  `site-links.py`, headless Chromium shot at 390 and 1440 wide (Tab order: first focus on a button, not a hidden one).
- [ ] HX72 (reported 2026-11-02 from Sentry, event 2026-11-01 17:01 UTC, issue 157823285, tmplayer-android, app
  1.28.3, NATIVE CRASH, URGENT, cause researched 2026-11-02 (see Progress / handoff below), no fix yet, placed with the crashes) SIGSEGV
  SEGV_MAPERR in `__memcpy_aarch64_simd`, called from `java.nio.DirectByteBuffer.put` on a HandlerThread, on an
  OPPO CPH2727, Android 16, arm64. Obfuscated frames (R8 names) from the top: `cf7.c` -> `cf7.e` -> `cv6.v` ->
  `z00.readSource` -> `bl1.run` -> `xe2.i` / `xe2.handleMessage`. `readSource` plus the "ExoPlayer:Simpl"
  thread in the dump points at the media3 loading path (the data source feeding the player), so the likely
  fault is a copy into or from a direct ByteBuffer whose memory is gone or too small: a buffer released
  (use after free) while a load was still running, a seek or release racing a read, or a byte count from
  TDLib or the cache larger than the destination. Other threads are idle (TDLib receiver/sender, OkHttp), so
  no second thread is visible in the trace. Do first: get the R8 mapping for 1.28.3 (build 1280399) from the
  release artifacts and de-obfuscate `cf7`, `cv6`, `z00`, `bl1`, `xe2` (Sentry should have it if the mapping
  was uploaded; if the frames stay obfuscated, fix the upload, since every release crash is unreadable
  without it). Then read our own data source / cache code that does `ByteBuffer.put` (grep `put(` near
  TDLib file reads in the player module) and check bounds, buffer lifetime and release ordering. Fix with
  length checks (`OrNull` style, skip not crash), a release that waits for loads, and a test that seeks and
  releases mid-read. Not tied to a codec. Reply to any reporter with "next release" once fixed on main.
  - **Progress / handoff (2026-11-02, cause researched, no code changed).**
    - **De-obfuscated** with the 1.28.3 mapping (workflow artifact `mapping-1.28.3` of run 41038181736,
      `gh run download 41038181736 -n mapping-1.28.3`): `xe2` = `ExoPlayerImplInternal` (playback thread),
      `bl1.run` = `MediaCodecRenderer.lambda$feedInputBuffer$0`, `z00.readSource` = `BaseRenderer.readSource`,
      `cv6.v` = `ProgressiveMediaPeriod$SampleStreamImpl.readData` -> `SampleQueue.read`, `cf7.e` =
      `SampleDataQueue.readSampleData`, `cf7.c` = `SampleDataQueue.readData(node, pos, ByteBuffer, len)`
      (media3 1.11.1, source line 411 to 415): `target.put(allocation.data, offset, toCopy)`.
    - **Every frame is media3, none ours, and not our data source.** The plan's first guess (our TDLib
      read path, `readSource` as a loader) is wrong: `readSource` is the renderer pulling a sample out of
      the sample queue into the decoder, on the playback thread. The copy source is a heap `byte[]` from
      the `DefaultAllocator`; the target is `buffer.data` of a `MediaCodecRenderer`, which is the
      **MediaCodec input buffer** (a direct ByteBuffer over memory the codec owns).
    - **Why it is not a size bug.** `ByteBuffer.put(byte[], off, len)` bounds checks both sides in Java;
      too small a target throws `BufferOverflowException`, and media3 checks capacity first
      (`InsufficientCapacityException`). A SIGSEGV SEGV_MAPERR inside that memcpy means the Java buffer
      still looked valid while **the codec's native memory under it was already unmapped**. So no length
      check of ours can catch it.
    - **Who can unmap it while the playback thread is copying.** Not our code: release, flush, track
      switches (`DecoderSwitch`, `BackgroundPlayback.withoutVideo`) and `exo.release()` all run on that
      same playback thread, in order. What runs beside it: the codec itself. Likeliest, in order:
      (1) the system took the codec back (ResourceManagerService reclaim, or the vendor codec2 / media
      service process died) and freed its buffers from a binder thread; (2) a Qualcomm codec2 / ColorOS 16
      bug on this phone. CPH2727 is the OPPO A5, Snapdragon 6s 4G Gen 1, Geekbench 313/768: a budget phone
      with aggressive ColorOS background limits, which fits (1).
    - **Our wrappers ruled out.** `Hdr10PlusMaskingCodecAdapterFactory` only applies on Amazon MT8696
      models (`HevcHdr10PlusMasker.affectedDevice`), so not here. `LevelLenientVideoRenderer` only widens
      `supportsFormat` (a stream above the decoder's claimed level is still tried); it cannot shrink the
      input buffer, but a decoder pushed past its level is a weak (2) variant: worth knowing if the video
      was above 1080p level on this phone. The "ExoPlayer:Simpl" thread is `SimpleDecoder`, an FFmpeg
      decoder (likely the audio track) alive beside a MediaCodec video codec; it is not in the crash path.
    - **Mapping upload is fine.** `uploadSentryProguardMappingsRelease` ran in that release
      (`UPLOADED 92c99169-97df-35b5-9f15-36cc7c8d3fd5`). The frames stay obfuscated because this is a
      native crash event (tombstone), where Sentry does not apply the ProGuard mapping to the Java frames.
      Not fixable in our build; de-obfuscate such events by hand with the artifact (grep `-> cf7:`).
    - **Not known yet.** Event count, other devices, breadcrumbs (background? seek? which track? video
      resolution) and whether the app was in the background. The local tokens are an org upload token
      (`Invalid org token` on the events API) and `~/.sentryclirc` has none, so the event was not read.
      Needs a user auth token with `event:read`, or the user pastes the breadcrumbs and the "Contexts"
      app state.
    - **Next (fix phase, after the facts above).** A native crash inside the framework's buffer cannot be
      caught in Kotlin, so the options are avoidance only: (a) report upstream to androidx/media with the
      de-obfuscated trace; (b) if it repeats on OPPO/Qualcomm budget models, a `DeviceQuirks` entry that
      turns off async queueing (`forceDisableMediaCodecAsynchronousQueueing()`) or prefers the FFmpeg video
      decoder there; (c) if breadcrumbs show it in the background, release the video codec sooner when
      hidden (already `withoutVideo`; check it runs before ColorOS freezes the app). One event on one
      phone does not justify a quirk yet: watch the count first.
    - **Done 2026-11-02 (option a, drafted only).** Issue text for androidx/media is in `private/HX72-media3-issue-draft.md`; not filed (the user files it). Option (b) not done: one event on one phone does not justify a quirk, watch the Sentry count first.
    - **Option (c) checked 2026-11-02 (code read only, nothing changed).** `onStop` drops the video track
      (`hideVideoUnseen`, `PlayerActivity.kt:3946`) only on the background audio path (`startBackgroundAudio`).
      On the plain pause path (`player?.pause()` in `onStop`) the MediaCodec video decoder stays allocated
      while the app is hidden, so a ColorOS freeze or reclaim can hit a live codec, and media3 still feeds
      input buffers while paused and ready. If breadcrumbs show the crash after going to the background,
      the fix is to call `hideVideoUnseen` on the pause path too (cost: the picture takes a moment to come
      back, "picture back" in logcat). Still blocked on the event facts.
    - **Event facts 2026-11-02.** Sentry org `nevil-i0`, project id 4511899422556240. The event has NO breadcrumbs by
      design (`CrashReports.kt:111` sets `maxBreadcrumbs = 0`, privacy rule), and its Contexts only show OPPO
      CPH2727, Android 16, so foreground or background is unknown. Decision: wait and watch the event count; if
      it repeats, add privacy-safe tags (`app_state`, `video_codec_active`, no names) in a separate task.
    - **Sources.** The mapping was read from a scratch download, not kept in the repo. Upstream search found no media3
      report of this exact trace; Samsung forum has a similar codec-side `__memcpy_aarch64_simd` crash
      after an OS update (forum.developer.samsung.com/t/35878).
- [ ] HX70 (reported 2026-11-02 from Sentry, event 2026-11-01 22:23 UTC, issue 156926561, tmplayer-android, app
  1.28.3, ANR, URGENT, noted only, not researched yet, no work yet) `ApplicationNotResponding: ANR`, culprit
  `__epoll_pwait`, device "yes" by SEI Robotics (a TV box or stick), Android 12, mechanism `AppExitInfo`
  (reported by the system after the fact, not by our watchdog). The main thread stack is the idle Looper
  (`MessageQueue.next` -> `nativePollOnce`), with nothing of ours in any thread, so the stack alone does not
  say what blocked. An idle main thread at dump time usually means the ANR was a Service/broadcast/input
  timeout whose cause is gone by the time the trace is taken. Check first: a foreground service whose
  `startForeground` is not called within 5 s of `startForegroundService` (downloads, playback, the update
  checker), a `BroadcastReceiver.onReceive` doing work, main-thread disk or TDLib calls at cold start on a slow
  1 GB TV stick (see the idle CPU note in AGENTS.md), and the `AppExitInfo` description text in Sentry (it names
  the ANR reason: "Input dispatching timed out", "executing service", "Context.startForegroundService() did
  not then call Service.startForeground()"). Fetch the full event for that reason before changing code. Likely
  related to the slow-TV work (HX44 and the Mi TV speed notes). Fix once the reason is known, with a test or
  a StrictMode check on the TV emulator. Raw trace not kept: it holds only system frames, the facts are above.
- [ ] HX73 (reported 2026-11-02 from Sentry, event 2026-11-01 12:05 UTC, issue 157778294, tmplayer-android, app
  1.28.3, ANR, URGENT, noted only, not researched yet, no work yet) ANR on the Xiaomi Mi TV (MiTV-AFKR0,
  Android 11, 4 cores, 32-bit), mechanism AppExitInfo. This one has OUR frames, unlike HX70. Main thread: busy
  composing, `Palette.kt:4` (`d`) called from `TvTopBar.kt` `BarTab` (line 312) in `TvTopBar` (line 209), in
  `BrowseScreen.kt:1099`, from `MainActivity.kt:1820`, inside a Choreographer frame (the culprit
  `Object.getClass` is only where the sample landed). Meanwhile a `DefaultDispatcher` worker is running
  `HomeViewModel.kt:1068` -> `HomeRows.build` (HomeRows.kt:283) -> `row` -> `arranged` ->
  `SeriesShelf.arrange` (SeriesShelf.kt:243) -> `SeriesShelf.plan` (line 735), and the ART JIT thread is
  waiting in `JitCodeCache::Reserve`, so the CPU and the JIT cache are both busy. Reading: Home row building
  (`SeriesShelf.plan/arrange`) hogs the slow TV's cores while the main thread recomposes the top bar, and the
  frame misses the ANR limit. Same device and same hour as the stutter reports in HX71 (12:15 and 12:29 UTC),
  so one root cause is likely: background work starving playback and UI on this TV. Do first: read
  `SeriesShelf.plan` (loop or search cost on a large library, `nextInt` shows it was inside an int range
  loop), `HomeRows.build` (how often it reruns: each update re-arranging everything) and whether `Palette.d`
  (called per tab on every recomposition) allocates or does work that should be `remember`ed. Fix with: run
  and cap `plan/arrange` (coalesce reruns, lower priority, budgeted per refresh), keep the main thread
  composition cheap (remember palette lookups, no work per frame), and measure idle and Home CPU on the TV
  (`/proc/<pid>/stat`, memory: mi-tv-speed-testing) before and after. Test: `HomeScaleTest` with the
  synthetic large account, timing `arrange`. Also check HX70 (ANR on another TV box) against this.
  Second report, same TV, one minute earlier (Sentry issue 157778110, 2026-11-01 12:04:39 UTC, "Background
  ANR", culprit `ReadonlyStateFlow.getValue`): main thread again in a Choreographer frame composing Home:
  `HomePane.kt:246` -> `rememberFeatured` (`HomePane.kt:1380`) -> `collectAsState` -> `StateFlow.getValue`,
  from `BrowseScreen.kt:664`. No other app thread is busy and the ART JIT is compiling, so here the main
  thread itself is the slow part (cold start on the 4-core TV, code not yet compiled, plus a heavy first
  Home composition). Look at `rememberFeatured` for work done on the main thread inside composition (mapping
  or sorting a big list, building the featured carousel on every recompose) and move it to a flow
  `.map`/`stateIn` on a background dispatcher so the composable only reads a ready value. Two ANRs in a minute
  after launch also say: check cold start on this TV (baseline profile present and installed for the
  release, so the JIT does not compile Home composition at launch).
  - **Progress / handoff (2026-11-02, worker part fixed in 2cd6871, not pushed).** Found: `Palette`/`Tone`
    and `StateFlow.getValue` are trivial, only where the samples landed: the main thread was starved, not busy
    with our work. Two causes fixed: (1) `HomeRows.build` has no suspension point, so when `produceState`
    restarted on a new answer the cancelled build ran on beside the new one on `Dispatchers.Default`, and a cold
    start's builds could take all 4 cores; builds now run on one `home-rows` thread at Java priority 4 (nice 10),
    queued, a cancelled one never starts (`HomeViewModel.kt`, `homeBuild`). (2) A chat's new answer replaced
    every `MediaItem`, so one new post re-read every name (`MediaName.titleEnd` is the hotspot, about 0.1 ms a
    name on desktop); `MediaItem.carryReads` keeps held instances or their reads, in `fetchChat` and
    `fetchRecent` (`ScaleCpuTest`: 2,000 refreshed videos 100 ms read again, 17 ms carried). Mi TV AESP0, cold
    start, first 40 s, NOT profile-compiled (as a sideloaded install runs for days): before 64 s CPU, main
    13.3 s, start 8.7 s, frame p99 1.7 s; after 49 and 40 s, main 9.9 and 8.9 s, start 4.1 and 3.9 s, p99 1.05
    and 1.1 s. Compiled (`cmd package compile -m speed-profile`): no change, about 19 s CPU, main 2.85 s, start
    1.3 to 1.5 s, so compiled Home is fine either way. Open, the bigger lever: an uncompiled install costs 3x
    the CPU of a compiled one. The baseline profile is in the APK, but a sideload is only compiled by the idle
    dexopt job, which a TV that is never idle rarely runs. Research next: the in-app updater writing a `.dm`
    (dex metadata with the profile) into its PackageInstaller session so Android 9+ compiles at install, and
    whether `ProfileInstaller` writes the profile at all on these TVs (`adb shell dumpsys package dexopt`).
    Also open: `MediaName.titleEnd` runs `TAGS` over the name once per soft tag (cost per first read).
    HX70 checked against this: not confirmable, its stack has no frame of ours and the Sentry token in
    `~/.sentryclirc` is an org upload token that cannot read events; needs the AppExitInfo reason from the
    Sentry UI. HX71: the Mi TV's real 720p hardware stutter (fourth report) fits the same starvation, since Home
    builds could run at normal priority behind the player; re-check after this ships. Its false positive (third
    report) is the stutter gate HX37 already fixed in 58b65fb (1.28.4-beta.1).
- [~] HX74 (fix committed 2026-11-02, not yet confirmed on the Mi TV: our code never calls setPlaybackState, Media3's session does on every player event, and Telegram streams flip is-loading per chunk; `StateChangeOnlyPlayer` now drops loading-only events before the session, test `StateChangeOnlyPlayerTest`; baseline 2026-11-02 on Mi TV AESP0 with 1.28.1 (no fix), Don resumed, atrace binder_driver 30 s: main thread made 9 transactions to system_server (0.3/s), no storm while the stream was steady; the storm needs chunk loading, so repeat with the fix build on a cold stream; MEASURED 2026-11-02 with the fix build 1.28.1-hx74 on the Mi TV, cold resume of Don, atrace binder_driver (needs -b 1024, bigger buffers OOM on the 1 GB TV): main thread to system_server 245 per 30 s, but 225 of them in seconds 2 to 9 (activity and player start, loading included), then 1 every 3 s (0.33/s, same as the 0.3/s baseline); three seeks in 20 s made 34 calls (peak 1.7/s). No per-chunk storm. Left to do: confirm with a Sentry 1.28.4+ ANR count, and attribute the start burst (node 5005 code 1, bursts of about 20, not setPlaybackState proven)) (reported 2026-11-02 from Sentry, event 2026-11-01 04:53 UTC, issue 157715772, tmplayer-android, app
  1.28.3, ANR, URGENT, noted only, not researched yet, no work yet) ANR on a Xiaomi Mi TV (MiTV-AXFR2, a second
  Mi TV model, Android 11, 32-bit arm), mechanism AppExitInfo. Main thread is inside
  `android.media.session.MediaSession.setPlaybackState` -> `BinderProxy.transact` (a synchronous call into the
  system server), and was waiting in `art::GoToRunnable` when it came back from the native call (the thread was
  asked to suspend, for example by a GC checkpoint, while the ART JIT compiler was also running). No frame of
  ours is shown, but `setPlaybackState` is ours to call: the player updates the media session from the main
  thread, probably on every position tick. Binder calls on a loaded TV can take hundreds of ms, and a call
  per tick piles up. Do first: grep `setPlaybackState` and `PlaybackStateCompat` in the player and TV code;
  see how often it is called (it should be only on state changes: play, pause, seek, speed, and a rare
  position refresh, not each progress update) and which thread calls it. Fix with: skip the call when the
  state is unchanged, throttle position updates, and keep the media session work off the main thread or
  coalesced into one update per frame. Measure on the TV (memory: mi-tv-speed-testing) with the player
  running and count binder transactions. Related to HX73 (same TV family, main thread starved) and HX70.
- [x] HX75 (reported 2026-11-02, site and apps, UX, URGENT; done 2026-11-02: site touch click follows the link and opens UpiDialog, Android phone UpiPay always opens UpiDialog, web app Chip opens a QR dialog; TV and desktop app already show QR and ID inline; device-checked 2026-11-03: site verified in Chromium headless over CDP (desktop click opens dialog without navigating; touch click follows the upi:// link and opens the dialog with "Open a UPI app" visible); Android verified on the Poco F5 (debug build off 04dbcfa, forced the support reminder with `--ei support_rung 1`): tapping Pay by UPI correctly reports "No UPI app answered" and opens the QR/ID dialog, Copy shows "UPI ID copied" and lands in the clipboard. TV and desktop app not yet device-checked) Every "Pay by UPI" button should
  also open the GPay modal (the UPI QR, the UPI id with Copy), not only try to open a payment app. If the app that
  opens is wrong, or none opens, the viewer can still copy the id and pay in their own app. Places found by grep:
  site `UpiButton.astro` and `UpiDialog.astro`, used by `SupportPopup`, `CoffeeCard`, `SponsorOffer`,
  `support/index.astro`, `Base.astro` (`support.ts`, `app.js`); Android `ui/settings/Support.kt` (`UpiPay`,
  which already shows `UpiPlate` and `UpiIdRow` only in its no-app case). Check desktop and the web app too.
  Edge cases: no UPI app installed, a desktop browser (the QR is the only way), TV (QR to scan with a phone, Copy
  is useless there), the copy confirmation, keyboard and D-pad focus inside the modal, closing it with Back/Esc.
- [ ] HX78 (reported 2026-11-05 by the user, generic Android TV boxes such as T95, BUG, built in ee0f019 + 8964eea, test APK out, waiting on the tester's tm-form log) The
  box opens in the phone layout: `FormFactor` only checks leanback and the TV uiMode, which uncertified AOSP
  boxes lack. Plan in `private/TV-BOX-DETECT-PLAN.md` (heuristic classifier, Interface setting, D-pad nudge,
  forced TV resources, T95-like AVD).
- [ ] HX76 (reported 2026-11-03 by the user while testing on the Mi TV, Android TV, UX, URGENT, noted only, no work yet) The
  "Choose chats to watch" picker (setup step and Add chats) is not D-pad friendly. Seen on the Mi TV with the real
  account (14 ticked, about 3,000 chats): from Search, Down walked every row one by one (about 100 presses of Down to
  reach Done at the foot, with a Recommended group then All chats under it), Done only sits at the end of the list,
  the focus lands in the Search field first (opens the keyboard on OK), and Select all is far to the right of the chip
  row. Fix so the remote reaches everything in a few presses, whatever fits best: (a) keep Done and the running count in
  a footer that is one Down from the filter chips or reachable with a fixed key (Menu or the Play/Pause key could jump
  to Done), (b) focus goes first to the first row, not into the text field (Search is a chip that opens the field on OK),
  (c) Left/Right in a row jumps a whole section (Recommended, All chats) or Channel Up/Down pages by a screenful, (d) a
  fast-scroll or "jump to letter" for the long All chats list, (e) Back from the list goes to the chips, a second Back
  confirms leaving, (f) after Done, focus lands on Home's Play. Edge cases to cover: 3,000 and 100 chats, zero chats,
  a search with no match, ticking from the remote while the list loads in, focus kept when the list reorders (ticked rows
  move to Recommended), RTL. Tests: `performKeyInput` DPAD down counts from the top to Done on `SyntheticAccount` (3,000
  chats, at most 5 presses), focus is a row after the field closes, preview screenshot at TV size. Look first at the
  existing picker (`ChatPickerScreen`, `ChatPickerScaleTest`) and the TV panel hosting; extend them, do not add a second
  picker. Related: HX55, HX29.
  **Progress / handoff (2026-11-03, f0815ca):** done in `ui/.../browse/AddChatsScreen.kt` (the picker is
  `AddChatsScreen`, not `ChatPickerScreen`): first focus on the first row (the chosen filter chip until a row lands,
  never moved after a key press); Right (end side, RTL aware) or Play/Pause to Done (to Select all while the gate has
  nothing ticked); Left to the band's first row, then the band before, then the chips; Back on TV from a row or the
  foot to the chips, second Back leaves; Page/Channel Up/Down and Down/Up held past 6 repeats move a screenful (a
  running jump swallows repeats); the TV keyboard's Search and a desktop Enter go to the first row; TV Select all moved
  into the foot beside Done; foot hint `picker.tv_keys` (en.json only, translate before release). Tests:
  `app/src/test/.../AddChatsTvKeysTest.kt` (Robolectric TV, 3,000 chats, real D-pad events; needs
  `setInTouchMode(false)` or nothing can take focus), frame `build/device-test/review/add-chats-tv-remote.png`.
  **Not done:** (d) jump to letter (Mi remote has no letter or channel keys; held Down plus Search cover it), (f) focus
  on Home's Play after Done (Home's first focus is the first row, Play only when the carousel is alone; a gate-only
  rule needs a one-shot flag in `HomeFocusMemory` and featured timing). **Next:** check on the Mi TV with the real
  account (presses to Done, held Down CPU and smoothness, ticks while the list loads, focus when Recommended lands).
  **Mi TV check (2026-11-03, f0815ca built as 1.28.1 code 1280600, armeabi-v7a release):** opened from Settings,
  Library, Chosen chats (6 chosen, not the 14 / 3,000 chats of the report). First focus on the first row; Right goes
  to Done, Left to Select all; held Down moved a screenful per repeat; Back from a row went to the chips (list back at
  top), second Back left to Library with the chosen set unchanged. Held Down x3: 21 frames, 52% janky, 90th 73 ms,
  95th 97 ms, about 1.6 s CPU over 5 s; idle 25 ticks in 30 s (0.8% of a core). **Not checked:** ticking while the
  list loads, Recommended landing late, a 3,000-chat account, Search keyboard path, Page keys (no such keys on the Mi remote).
  **Cost per jump (2026-11-03, fa41a9d):** (1) a row counts only once
  the list is still (`racing` set by each jump and cleared 400 ms after the last, or `isScrollInProgress`), so the
  rows a held Down runs past start no Telegram count whose answer recomposed all 3,000 rows mid jump; test
  `a held Down does not count the rows it runs past` (31 calls in 30 repeats before, 0 now). (2) Rows have a
  `contentType`, so a jump reuses the rows it drops. (3) On TV the list uses `EdgeStep` (moved from HomePane to
  `ui/components/EdgeStep.kt`, Home uses it too): steps at the edges instead of the TV pivot's slide per press.
  **Mi TV A/B, same script** (`sendevent` held Down on /dev/input/event2, 3 x 1.5 s, warm, gfxinfo + /proc):
  base 71 to 85% janky, fix 55 to 79%, main thread 1.54 s vs 1.57 s per run: no measurable change, because this
  TV account's All list is about 15 rows, all inside the 60-chat head start, and a hold reaches the end in under
  a second and then repeats Down on Select all in the foot. The 52% figure measured the same thing. **Next:** measure
  on a long list (an account or scope with 300+ rows on the TV, or a debug fake list), and find why All holds about
  15 rows on an account reported at about 3,000 (listComplete early, or LoadMoreAhead not firing at the end?).
  Per thread during a hold: main 1.5 s, GC 0.35 s, RenderThread 0.35 s, TDLib about 0 (allocation is worth a look).
  **Three pane TV picker (planned 2026-11-03, not started).** Why: the remote's shortcuts (Left back a band, Right to
  a foot that sits below, held Down paging) are invisible, so a viewer who never finds them scrolls 3,000 rows. Rule:
  every key lands on something on screen, and the default list is short enough that most viewers press Done without
  scrolling. TV only (`FormFactor` tv); phone and desktop keep today's layout.
  - **Layout** (960 x 540 dp): left rail about 200 dp | chat list | right "Your choice" panel about 240 dp.
    - Rail: All, Channels, Groups, People, Archived, Search. Reuse `ChatPicking.Scope` (the chips become the rail,
      no second notion of scope); All keeps its bands (Saved, Recommended, Starred, Chats). The selected entry is
      marked; an empty scope stays, dimmed, so the rail never shifts.
    - Panel: "N chats, M videos" (the foot's totals, from `ChatPicking`), Done, Select all / Clear for the rows of
      the current scope ("Select all Channels (240)", with the existing word before a very large one), the error
      and Try again from `State.error`.
    - List: as now (bands, counts, ticks, stars, `EdgeStep`, count settle from fa41a9d).
  - **Short list.** In All and each scope, chats counted at 0 videos fold into one row at the end of their band,
    "1,800 chats with no videos"; pressed, it opens in place (the remote stays on it, now the first such chat).
    Uncounted chats stay in the main list. Ticked or starred chats never fold. The fold rule goes in `ChatPicking`
    (pure, unit tested), not the screen.
  - **Count budget for the fold** (AGENTS.md scale rules): after the head start, count the rest in the background,
    at most 3 at a time, `localOnly` first if it answers from the database (check what `ChatVideoCount` does with
    it), stopping on `stop()`; FLOOD_WAIT waited out by the call that got it while others go on; the 10 minute
    `ChatVideoCount` cache keeps a reopen free. Idle CPU on the Mi TV after it finishes back to about 1%.
  - **Focus map** (replaces the f0815ca keys; held Down paging and Search landing on a row stay):
    - Start: on Done when the Recommended band ticked something; on the first row when nothing is ticked, while
      loading, or when there is no recommendation. A Recommended band that lands late (up to 3 s) never moves a
      remote that has already moved.
    - Rail: Up / Down between entries, Right or Select into the list (first row of the scope, or the row last
      focused in it), Back leaves (in the required setup, as Back from the chips does now).
    - List: Up / Down rows, held Down speeds up, Select ticks, Left to the rail on the current entry, Right to Done.
    - Panel: Up / Down between Done and Select all, Left back to the row last focused, Back to the rail.
    - No chats at all: start on the rail, the list says why (empty state as today).
  - **Tests, machine first** (rewrite `AddChatsTvKeysTest`, extend the others):
    - Every line of the focus map, including start on Done, start on a row with nothing ticked, late Recommended
      not stealing focus, Back chain in the required setup, empty scope on the rail.
    - Fold: `ChatPicking` unit tests (ticked and starred never fold, uncounted never fold, count of the fold row);
      a key test that pressing the fold opens it with the remote on its first chat.
    - `ChatPickerScaleTest` on 3,000 chats: background counting stays at 3 in flight, stops on leave, a FLOOD_WAIT
      on one chat does not hold the others; the list's first content within 3 s.
    - TV preview screenshot (w960dp television) of the three panes, plus the phone and desktop shots unchanged.
  - **Mi TV check after:** a long list (Phase 0 below), held Down jank and CPU against the fa41a9d numbers, idle CPU
    after the background count, Recommended landing late, the Search keyboard path, Back chain. List what is left
    for the user.
  - **Phase 0, first:** find why All holds about 15 rows on an account reported at about 3,000 (`listComplete` set
    early, or `LoadMoreAhead` not firing at the end), since the fold and the jank numbers both depend on a long list.
  - **Costs and limits:** names in the middle pane get about 480 dp and long ones cut; the fold needs every chat
    counted once (a Telegram call each, budgeted above); undoes the Left band jump and the foot from f0815ca on TV.
  - **Phase 0 result (2026-11-03, no code change):** no cap in the code. Main list pages 300 at a time
    (`CHAT_LIMIT`, `mayHoldMore`), archive to 4,000, `ChatPicking.rows` drops nothing under All, the screen trims
    nothing, `KidsLock.chats` passes all when unlocked, and any failed or short sync sets `partial` (Try again shows,
    and did not on the TV). On the Mi TV (1.28.1 code 1280603) All ends with no "loading more" foot and Archived
    holds 3 chats (Kerala Developers, Kochi FOSS Meetups, Poco Off-Topic). So TDLib on the TV reports about 15 + 3
    chats: the TV's account is small, or TDLib's local list is. **Open:** the user to confirm the account's real chat
    count in Telegram; if it is about 3,000, the next step is a debug build logging `sync.chats.size`, `loaded` per
    `loadChats` and `getChats(Main)` size on the TV. Either way a long list for the jank A/B needs a debug fake list
    or another account.
  - **Progress / handoff (2026-11-03, built, not yet on the Mi TV):** `ChatPicking.fold/foldedIn/toCount` + `Item`
    (`ChatPickingFoldTest`), `AddChatsViewModel.countRest` (3 workers, `hold` while the remote jumps, `countsRest`
    flag for tests), TV three panes in `AddChatsScreen.kt` (rail `TV_RAIL` + Search entry, list with `FoldRow`,
    panel with Done, scope Select all, error), focus map as planned (start on Done when Recommended ticked, rail
    Right/OK into the last row of the scope, row Left to rail, Right to Done, panel Left to last row, Back chain).
    Strings: `picker.fold`, `select_scope`, `clear_scope`, `all_chats`, `results`, `your_choice`, `tv_keys` reworded
    (en.json only, translate before release). Tests: `AddChatsTvKeysTest` rewritten (10), scale test
    `the background count stays at three...` (465 chats in 45 s virtual, 3 in flight), shot
    `build/device-test/review/add-chats-tv-three-panes.png`. Choices: fold not applied while a search is typed; a
    focused row never folds away; Select all on TV acts on the rows shown (folded chats excluded); start on Done also
    for a viewer who already chose and has ticks. **Left:** Mi TV check (long list, held Down jank/CPU, idle CPU after
    the count, late Recommended, Search keyboard, Back chain), RTL look, `tv_keys` hint wording. Key test flaked once
    (empty focus) on the first run after a cold compile; passed on 3 reruns.
  - **Mi TV check, three panes (2026-11-03, HEAD 358a149 built as 1.28.1 code 1280700, armeabi-v7a release):** account
    has 74 chats (All 74, Channels 31, Groups 17, People 26, Archived 26), 6 chosen. Opened from Settings, Library,
    Chosen chats. Start on Done (Recommended present); Left to the list; held Down (35 repeats) from the top reaches the
    fold row "5 chats with no videos"; Back from a row to the rail on All with the list back at top; Down on the rail
    moves the highlight without changing the list; Right on Groups opens Groups (first row, "Select all Groups (14)",
    fold row "2 chats with no videos"); Back, Back leaves to Library, chosen set unchanged (6). Held Down x2, same
    script as fa41a9d: janky 38 to 47% (was 55 to 79%), 90th 57 to 61 ms (was 73), main thread 0.9 to 1.0 s per hold
    (was 1.54 s), RenderThread 0.25 s, TDLib about 0. Idle after the count: 19 to 20 ticks in 30 s (0.7% of a core).
    **Not checked:** a list of 300+ rows (this account has 74), Recommended landing late, the Search keyboard path (a
    Gboard session once froze this TV's input, memory mi-tv-speed-testing), RTL, tick-while-loading.
  - **Long list on the Mi TV (2026-11-03, debug build 1.28.1 code 1280701, armeabi-v7a, signed with the release key so
    the login stayed):** debug-only fake source: `--ez fakechats true` on `MainActivity` (debug only) makes
    `AddChatsPage` run over `FakePicker` (`app/src/debug`), which wraps `SyntheticAccount` (moved to
    `ui/src/synthetic/kotlin`, shared by the jvmTest and the app's debug source set; reached by name from
    `DebugFakeChats` in main, so a release has no class and no flag). 3,000 main plus 1,500 archived chats (All 4,500,
    Channels 1,799, Groups 2,100, People 601, Archived 1,500; the picker has no folders), real time, the model's
    latencies and flood waits. Same script as fa41a9d (`hold.sh`, `sendevent` held Down on event2), warm.
    - **While the background count runs** (3 in flight, about 10 chats a second): idle CPU 2,200 to 2,900 ticks per
      20 s, which is 1.1 to 1.4 cores, for about 270 s until the count ended; held Down 36 frames 100% janky, 90th
      250 ms (on the panel), then on a list row 23 frames 100% janky, 90th 850 ms, 995 ticks over 5.3 s; GC thread
      (HeapTaskDaemon) 2.6 to 2.8 s per run, RenderThread 0.2 s, TDLib about 0.
    - **After the count** (4,500 chats, fold rows live): held Down 70 and 82 frames, 76 to 79% janky, 90th 150 to
      200 ms, about 570 to 610 ticks per run. Idle 17, 9, 4 ticks per 20 s (0.2% of a core; the 74-chat release
      number was 0.7%).
    - **Same debug build on the 74-chat account** (fake off, All 74): 49 frames, 90% janky, 90th 200 ms, 605 ticks,
      so a long list holds no worse than a short one once counted. The numbers are about twice the release ones of
      the 74-chat check above (38 to 47% janky, 90th 57 to 61 ms) because this is a debug build (no R8, no baseline
      profile, Compose debug), so they do not compare with the release rows; a release build with the hook is not
      possible by design. The second run of the 74-chat baseline hit 0 frames: the remote's input node
      (`/dev/input/event2`) went away mid session (it is a Bluetooth device and sleeps), so only one clean baseline run.
    - **Finding, the background count is the cost, not the list.** About 1.1 to 1.4 cores for 4.5 minutes on a TV
      with 4 weak cores, and a held Down in that window is unusable (90th 250 to 850 ms). Cause, from the code: each
      answer does `counted = counted + (id to answer)` (AddChatsViewModel `_state.update`, copies a 4,500 entry map),
      and the screen then re-runs `ChatPicking.fold` over all rows, `visible`, `bands` groupBy and `placeOf` per answer,
      about 10 times a second: quadratic, and the GC thread shows it. **Next (Sonnet, medium):** apply the answers in
      batches (collect for about 500 ms, one `update` per batch), keep `counted` cheap to extend (or fold only the
      rows whose answer changed), hold the batch while the remote moves (`hold` exists), and lower the background
      workers from 3 to 1 on TV. Re-measure with the same script; target idle under 10% of a core during the count
      and held Down 90th under 100 ms (debug) with the count running. `ChatPickerScaleTest` already has a 3 in
      flight assertion to extend with a "state updates per second" cap.
    - **Count cost fix (2026-11-03, c54ce29):** `AddChatsViewModel.countRest(chats, atOnce)`: background answers go
      into `pending` and one `flush()` applies them every 500 ms (`BATCH_MS`), not while `hold` is on (the remote is
      jumping), and once more at the end and on `stop()`; the TV passes `BACKGROUND_ON_TV = 1` worker (phone and
      desktop keep 3, though they do not call it today). Row-driven `count(id)` still updates at once. `stateUpdates`
      counts updates for tests. Tests (`ChatPickerScaleTest`, passing): state updates cap `COUNT_AHEAD + elapsed/500 ms + 5`
      over 400+ background answers; new `the television counts one chat at a time in the background`; `app` AddChats
      tests pass (JDK 21 needed: `JAVA_HOME=~/.jdks/jdk-21.0.12+8`). Cost: 1 worker is about a third of the speed, so
      4,500 chats take roughly 15 to 25 min to fold instead of 4.5, and the fold rows fill in slowly (rows counted by
      being on screen still answer first). **Mi TV re-measure NOT done:** debug build 1.28.1 code 1280702 (release
      key) is installed on the TV, but the remote's `/dev/input/event2` is gone (Bluetooth asleep), so no `sendevent`
      held Down. **Next:** wake the remote, `--ez fakechats true`, Settings, Library, Chosen chats, run `hold.sh`
      while the count runs; targets: idle under 10% of a core during the count, held Down 90th under 100 ms (debug).
    - **Re-measure of c54ce29 on the Mi TV (2026-11-03, debug 1280702, remote's event2 gone, so `sendevent` on event0
      `aml_keypad` KEY_DOWN 108, which works):** with the focus on Done, idle was 176 to 288 ticks per 20 s (9 to 14%
      of a core, was 110 to 145%), but that was before the background count started. With the focus on the list the
      count runs: idle 1,270 to 1,420 ticks per 20 s (65% of a core), main thread 48% and GC 16%, held Down 91 to 92%
      janky, 90th 300 to 400 ms. So the 500 ms batch still re-folds 4,500 rows twice a second: too often on the TV.
      **Fix (not yet measured on the TV):** `countRest(chats, atOnce, batchMs)`, the TV passes `BATCH_ON_TV_MS` = 5 s
      (about 17 answers per batch at 1 worker); the TV scale test caps state updates at `COUNT_AHEAD + elapsed / 5 s + 5`
      (passes). Debug build code 1280703 (release key) is installed. **Blocked:** on the TV `com.tmplayer.test` (the
      preview build, PlayerActivity "Baththa" at 0:14, subtitles sheet open) came to the front after my launch, so
      I stopped driving it. **Next:** user confirms the TV is free; open `com.tmplayer` with `--ez fakechats true`, one
      key and one screenshot at a time (a blind key run opened a video once), then focus the list, measure idle 3 x 20 s
      and per-thread, then `m.sh` held Down. Targets: idle under 10% of a core while counting, held Down 90th under 100 ms.
    - **Re-measure of the 5 s batch on the Mi TV (2026-11-03, debug 1280703, release key, `--ez fakechats true`,
      4,500 chats, user confirmed the TV free, `sendevent` held Down on event0, focus on a list row, count running):**
      idle 79, 22, 95 ticks per 20 s and 83, 18 per 10 s, which is 1 to 5% of a core (was 65 to 140%): **idle target
      met**. Held Down: 30 frames 90% janky, 90th 500 ms, 95th 700 ms, 714 ticks; second run 37 frames 86% janky, 90th
      300 ms, 557 ticks (was 91 to 92% janky, 90th 300 to 400 ms): **held Down target (90th under 100 ms, debug) NOT
      met**, and no better than the counted list (76 to 79%, 90th 150 to 200 ms). So with the batch at 5 s the count
      no longer costs idle CPU, and the remaining jank is the held Down itself on a 4,500 row list in a debug build.
      Seen: the Recommended band landing late moved the first focus from the first row to Done (the designed start),
      so a Down sent right after open did nothing (1 frame); rows far down show "Could not count. Press Menu to try
      again" for Chat 130 (a fake flood or error answer, not looked at). **Next:** profile the held Down on a long
      list (per thread, `compose-performance` skill: recomposition of rows per jump, `EdgeStep`, the fold run per
      scroll step), and compare a release-like build only if one with the hook is allowed (it is not by design).
    - **Same session, user asks:** rail icons added (`railIcon`: Chats, Channel, Group, Person, Archive, Search; rail 210
      dp); all Select all removed from the picker on every platform for now (button, Ctrl+A, the large selection
      confirm, TV panel entry): `AddChatsViewModel.selectAll/adding/clear` and `ChatPicking.needsConfirm/allTicked`
      are left unused for when it returns; strings `select_scope`, `clear_scope`, `browse.select_all` for the picker
      stay in `en.json`. `AddChatsTvKeysTest` updated (Down on Done stays on Done), passes.
    - **Not checked:** the installed debug APK still has the icons but also the old Select all (the final code is
      built only for tests), Recommended landing late, the Search keyboard (not touched, as asked), RTL, tick while
      loading. The TV now runs a debug build signed with the release key at code 1280701: the next release or a release
      build with a higher code goes over it with `install -r`.
  - **Held Down profile (2026-11-03, debug 1280703 on the Mi TV, fake 4,500 chats, all counted, focus on a list
    row, `sendevent` held Down on event0):** clean hold 60 frames, 83% janky, 90th 200 ms; CPU per thread over the
    hold: main 3.11 s, HeapTaskDaemon 0.56 s, RenderThread 0.35 s, so the main thread is the limit. ART sampling
    (`am profile start --sampling 1000 com.tmplayer /data/local/tmp/hold.trace`, file must be under /data/local/tmp;
    parsed by `private/tools/dmtrace.py <trace> main <top> [grep] [callers-of depth]`, `DMT_CALLEES=<method>` for
    callees; profiler run 96% janky, so shares not ms count). Of 4.9 s on main:
    - **Composing a fresh screenful per jump, about 1.0 s** (`scrollToItem` 1.36 s incl. measure, `subcompose`
      `composeInitial` 1.0 s). Per row (`PickRow` 773 ms over about 40 rows): Material3 `Checkbox` 226 ms (about 6 ms a
      row, its color and check animations), `RowAvatar` 187 ms (`MediaArt`, `FadeOver` 137 ms, `NameArtTile` 68 ms),
      `CountLine` 125 ms (`Messages.format`).
    - **Draw, about 0.65 s** (`ThreadedRenderer.draw`): this TV gets Compose's View-backed `GraphicsViewLayer` (205
      records, 561 ms), each record throwing and catching a `ClassCastException` inside `View.draw` (112 throws,
      about 230 ms with stack fill). The layer per row is `FadeOver`'s `graphicsLayer { alpha }`, kept on TV although
      `still` (Motion.television) never fades.
    - **Screen body, about 0.33 s:** `AddChatsScreen` recomposed 8 times in one hold (about 40 ms each, one with a
      280 ms GC stall); `racing` is read in the body (`LaunchedEffect(racing)`), so each settle flip recomposes the
      whole screen. `ChatPicking.fold` over 4,500 rows ran 14 times (150 ms plus `Item.Chat.equals` 25 ms): the `items`
      derivedStateOf reads `onRow`, so every focus move refolds (via `checkDerivedStateChanged`).
    - Not a cost: TDLib (fake), counting (all counted), input dispatch (75 ms), `requestFocus` (78 ms).
    **Next (Sonnet, medium), in order of expected gain:** (1) `FadeOver`: when `still`, draw `content(target)` with no
    `graphicsLayer`, `Animatable` or effect (helps every TV screen: Home cards too); (2) `PickRow` on TV: a drawn tick
    box instead of Material3 `Checkbox`; (3) fold keyed on a narrow derived "focused row that would fold" instead of
    `onRow`; (4) `racing` into `model.hold` via `snapshotFlow` in `LaunchedEffect(Unit)` so the body does not read it;
    then the same hold script and per-thread script (`threads0.sh`, `holdup0.sh` use event0) against 83% / 200 ms /
    main 3.11 s. Even then each jump composes about 7 new rows in one frame, so the debug 90th under 100 ms may stay out
    of reach; if so, consider a smaller held step (half a screen) and a release-like build measure.
    **Progress / handoff (2026-11-03, uncommitted):** all 4 fixes applied: `FadeOver.kt` skips the
    `graphicsLayer`/alpha read when `still`; `PickTick` (new, `AddChatsScreen.kt`) draws a plain box+check
    instead of Material3 `Checkbox` on TV; `ChatPicking.folds` made public and `AddChatsScreen`'s `items`
    derivedStateOf now keys on a narrow `onRowWouldFold` boolean instead of raw `onRow`; the `racing`
    `LaunchedEffect` is keyed on `Unit` with a `snapshotFlow { racing }.collect { model.hold(it) }` body so
    the screen does not recompose on every settle flip. `:ui`/`:core`/`:app:compileDebugKotlin` all compile
    (`JAVA_HOME=~/.jdks/jdk-21.0.12+8`, system JDK is 25 and lacks a compiler); `AddChatsTvKeysTest` and
    `AddChatsRenderTest` pass. Wrote `private/tools/threads0.sh` (per-thread CPU from
    `/proc/<pid>/task/*/stat` before/after `hold0.sh`, same directory; two Android-shell traps hit and
    fixed: `$14`/`$15`/`$12`/`$13` need `${}` braces past `$9`, and a thread's own `(comm)` field inside
    `/proc/.../stat` can hold spaces so fields must be split after the last `)`, not by fixed position).
    **Mi TV re-measure:** installed as a throwaway side-by-side package (`applicationIdSuffix = ".hx76"`,
    reverted after, not committed) since the Mi TV's `com.tmplayer` is release-signed and conflicts with a
    debug build; signed in with the user's real account for this test package (2FA entered live). Fake
    4,500-chat picker reached via `adb shell am start -n com.tmplayer.hx76/com.tmplayer.MainActivity --ez
    fakechats true`, three Down presses into the list. First run (counting still churning, not "all
    counted" like the baseline) ran worse than baseline (main 5.45 s, Heap 1.47 s): not a regression, just
    an unsettled background count competing for the main thread. A later run, same session, closer to
    settled: **75.95% janky (was 83%), 90th 150 ms (was 200 ms), main thread 3.14 s (was 3.11 s, about
    flat), HeapTaskDaemon 0.70 s (was 0.56 s), RenderThread 0.49 s (was 0.35 s).** Jank and the 90th moved
    in the right direction; main thread did not move much, which is plausible since fixes (1)(3)(4) cut
    recomposition and layer cost, not the `scrollToItem`/measure/subcompose cost called out as the single
    biggest line in the ART profile (composing a fresh screenful per jump, about 1.0 s): that is still
    unaddressed. **Caveat:** this account's 4,500 chats never fully reach "all counted" in a short session
    (some are fixture-designed to fail forever, the rest batch at 1 in flight / 5 s, so a full settle is
    hours not minutes); the numbers above are the best same-session comparison reached, not a clean
    apples-to-apples against the original "all counted" baseline. **Not done:** an ART sampling profile
    pass of this build to see whether `scrollToItem`/`subcompose` is now the dominant cost (expected, per
    the "next" paragraph above) and whether the 90th can still get under 100 ms; a longer or release-like
    build measure once that is known. **Next:** either let the fake account fully settle overnight and
    re-measure cleanly, or move to the ART profile pass to find the next cut (composing a fresh screenful
    per jump) rather than re-chasing this measurement's noise floor.
  - **ART pass on the 4 fixes, and 2 more cuts (2026-11-03, committed 25f0a89, local, not pushed).** Profile of the
    4-fix build (`hold2.trace`, same method as above): `ChatPicking.fold` still ran 12 times per hold (once per jump,
    11 ms each, one 312 ms GC spike from `Long` boxing in `folds`), because a `derivedStateOf` tracks what an inner
    derived state reads, so the narrow `onRowWouldFold` still keyed `items` on `onRow`. Draw 0.79 s with 149
    `GraphicsViewLayer.record` (77 `ClassCastException`s): each row still had about 4 clips (row, tick box, avatar,
    `groupedCard`), each a View-backed layer on this API 28 TV. Fixes: `items` reads `onRow` through
    `Snapshot.withoutReadObservation` (no `onRowWouldFold`, `ChatPicking.folds` private again; a row kept for the
    focus stays unfolded until the next count or tick refolds); `PickRow` clips only off TV (`background(color,
    round)`), `PickTick` and the count placeholder take a shaped background with no clip, `groupedCard` clips only
    its rounded first and last rows (all platforms: middle rows are square). Tests: `AddChatsTvKeysTest` (10),
    `AddChatsRenderTest`, `ChatPickerScaleTest` pass. **Mi TV** (hx76 debug 1280200, fake 4,500, warm, 3 runs of
    `private/tools/m76.sh`, pushed to /data/local/tmp, after `holdup0.sh`): janky 82, 74, 62%, 90th 150, 113, 113 ms, hold 517,
    431, 493 ticks (was about 76%, 150 ms, 570 to 610). Warm profile (`hold4.trace`): fold 0 per hold; left on
    main per hold: `scrollToItem` 0.99 s over 8 jumps (about 11 ms per new row composed, `PickRow` 629 ms over 90,
    `RowAvatar` 119, `CountLine` 80), draw 0.78 s with 142 layer records still (avatar `clip(CircleShape)` in
    `ChatAvatar`/`MediaArt`, `groupedCard` ends, the list's own layer). The fresh-process run (`hold3`) was 2x worse
    (JIT cold), so only warm runs compare. **Next (Sonnet, medium):** (1) `ChatAvatar` circle with no layer: letter
    tile as `background(color, CircleShape)`, a photo drawn as `drawCircle(ShaderBrush(ImageShader(bitmap)))` or a
    `drawWithCache` `clipPath` (check anti-aliasing on the Mi TV), helps Home cards too; (2) a smaller held step
    (half a screen) so each jump composes about 4 rows, not 7; then the same 3 x `m76.sh` and one warm profile.
    The debug 90th under 100 ms may need (2); a release-like number stays unmeasurable on the TV by design.
    Gotcha: the hx76 build needs `debug { applicationIdSuffix = ".hx76" }` in `app/build.gradle.kts` for the build
    only (reverted, never committed) and `-PtmVersionCode` above the installed one (1280200 now).
  - **ART pass 2 (2026-11-03, local, not pushed):** `ChatAvatar` circle on TV is a `drawWithCache` + `clipPath`
    (`circleCrop` in `SearchResultsUi.kt`: no layer, Skia anti-aliases it, the letter and photo circles look clean on
    the Mi TV; phone and desktop keep `clip`), and a held Down/Up jumps half a screen (`page / 2`, at least 2; Page and
    Channel keys keep a screenful; `AddChatsTvKeysTest` bound 80 -> 60). **Mi TV** (hx76 debug 1280201, fake 4,500,
    focus on the first list row, 3 x `m76.sh`): run 1 cold 100% janky (JIT, ignore); warm runs janky 89, 61%, 90th
    109, 65 ms, hold 434, 451 ticks (was 74, 62%, 113, 113 ms, 431, 493): 90th better, janky and ticks about the same,
    so no big win yet. Warm profile (`hold5.trace`): `GraphicsViewLayer.record` 58 per hold (was 142), draw 0.86 s
    (was 0.78, noise), `scrollToItem` 1.24 s over 7 jumps (was 0.99 over 8), so composing the rows is still the
    limit. Gotcha: the focus must be on a list row (Right from the rail), or the runs draw 0 frames. Costs: a held
    arrow now covers half the distance per jump. **Next:** cut the per-new-row compose cost (`PickRow` about 11 ms
    each: look at what one row composes), or keep the held jump at a full page if the user prefers speed over frames.
  - **Per-row compose pass (2026-11-03, committed 9e96cfb, local, not pushed):** from `hold5.trace`, a new row's
    cost was the avatar (`FadeOver` Animatable + effect + 2 boxes, a `Text` letter), `PickTick` (Box + border + an
    `Icon` with its own vector painter), and `pickerCountAbout` (ICU plural + locale number per row). Fixes: `FadeOver`
    on TV is one `Box` (no fade composed, helps Home too); `PickTick` is one `Spacer` drawing the fill, ring and
    TmIcons.Check's stroke; `DrawnLetter` (NameArt.kt) paints the avatar letter from one shared `TextMeasurer`
    (`LetterLayouts`, 64 cached, TV only, phone/desktop keep `Text`); `CountWords` formats each count once per screen.
    **Mi TV** (hx76 debug 1280202, fake 4,500, 4 x `m76.sh`): run 1 cold; warm janky 91, 78, 60%, 90th 101, 69,
    53 ms, hold 489, 463, 393 ticks (was 89, 61%, 109, 65 ms, 434, 451): a small gain, noisy (background count still
    running, idle 110 ticks/10 s). Warm profile `hold6.trace` (scratchpad a4775b26): `scrollToItem` 1.02 s over 7
    jumps (was 1.23), PickTick 20 ms (was 62), text measure 150 (was 177), applyChanges 270 (was 320). What is left
    in `PickRow`: 35 of 81 samples are the 2 rows recomposing on each focus move (`focused` changes ink and fill,
    so both Texts and CountLine recompose), 13 are semantics built (`invalidateSemantics` 96 ms: check whether an
    accessibility service is on on the Mi TV, `settings get secure enabled_accessibility_services`). Tests:
    `:ui:jvmTest` (33 classes), `:app` Tv/Render/Home tests (26 classes) pass; JDK 21 needed
    (`JAVA_HOME=~/.jdks/jdk-21.0.12+8`, the default 25 fails `:app`). Gotcha: `AddChatsTvKeysTest` fails 3 tests
    (initial focus) when run beside `AddChatsRenderTest` on base too, passes alone: an existing order dependence.
    **Next (Opus, high, or stop here):** focus colour changes without recomposition (fill and ring in draw from a
    `State` read, text colour via `BasicText(color = ColorProducer)`), so a focus move redraws 2 rows instead of
    recomposing them; then re-measure after the fake account fully counts.
  - **Focus in draw pass (2026-11-03, local, not pushed):** `PickRow` keeps `focused` as a `State` and reads it only
    in draw: fill and ring via the new shared `Modifier.focusDrawn(state, ring, shape, fill)` in `Palette.kt` (moved
    from `MediaGridScreen`'s private `focusRingDrawn`, which now uses it; `focusRingColor()` beside `focusRing`), the
    title and `CountLine` are `BasicText(color = ColorProducer)`, the star is `RowStar` (vector painter tinted in
    draw). All platforms, since the look is the same. Tests: `:ui:jvmTest`, `:app` AddChats/Home/MediaGrid/Tv,
    `:desktop` AddChatsRenderTest pass; `AddChatsTvKeysTest` saves `add-chats-tv-row-focused.png` and
    `-focus-moved.png` (build/device-test/review). Gotcha: an extra Down press added to "the remote starts on the
    first row" test made 2 or 3 later tests lose initial focus (destroying each activity in `tearDown` made 9 fail),
    so the pictures sit in "a row goes right to Done" instead; the key tests' focus setup is order fragile.
    **Next (Sonnet, medium):** the hx76 debug build (versionCode above 1280202) and 4 x `m76.sh` on the Mi TV with
    focus on a list row, plus one warm profile: expect `PickRow` recompositions per focus move at 0 and fewer
    samples than 81; check `settings get secure enabled_accessibility_services` for the semantics cost.
  - **Focus in draw pass, Mi TV (2026-11-03):** hx76 debug 1280203, fake 4,500, focus on first list row (Left from
    the sidebar; Up x3 between runs), no accessibility service. 4 x `m76.sh`: janky 93, 79, 47, 49%, 90th 250, 93, 65,
    53 ms, hold 546, 528, 346, 391 ticks (runs 1 to 2 cold JIT; warm 3 to 4 vs the last warm 61%, 65 ms, 451 ticks:
    janky and 90th better, ticks about the same, idle ticks/10s noisy 9 to 86 from the background count). Profile
    (hold6.trace, main 7.1 s): `PickRow` 42 samples (was 81), 174 ms inclusive, main mostly idle in `nativePollOnce`
    3.7 s; biggest cost now `forceRemeasure`/scroll measure about 1.0 s. Gotcha: the first 4 runs scored 2 frames
    because focus had drifted to Done; check a screenshot first.
  - **Jump-ahead prefetch (2026-11-03, committed 092aa1e, local, not pushed):** hold6 split the 135 ms per `scrollToItem` into about
    80 ms composing new rows (PickRow content 25, `applyChanges` 33) and 40 ms measuring them (text layout 30). A
    cache window never refills after a snap (only on first layout or a count change, checked in bytecode), so
    `rememberJumpAheadListState(enabled = tv)` in `AheadState.kt`: a `LazyListPrefetchStrategy` holding the screenful
    past the visible items in the last direction plus 2 behind (window logic in `JumpAheadWindow`, 7 tests in
    `JumpAheadPrefetchTest`; Robolectric cannot run the Android prefetch scheduler, fake clock). A refold keeps the
    handles (no screenful recomposed per count). Prefetched rows run effects, so the row count also waits for the
    row to be in `visibleItemsInfo` (`AddChatsTvKeysTest` asserts no row below the stop is counted). **Mi TV** (hx76
    debug 1280204, fake 4,500, focus on Saved Messages via Left from Done, Up x3 between runs): janky 61, 57, 46, 41%,
    90th 150, 69, 53, 40 ms, frames 71, 114, 154, 179, hold ticks 587, 481, 588, 572 (last warm: 47, 49%, 65, 53 ms,
    346, 391 ticks). Profile `hold7.trace` (scratchpad a1e5d5ef): `scrollToItem` 762 ms over 7 jumps (109 each, was
    135), prefetch ran 39 rows (420 ms in idle), but 29 rows still composed fresh inside jumps: back to back hold
    frames leave the idle scheduler only part of each gap. Costs: more CPU per hold (rows composed ahead that a jump
    skips); at rest 0 extra. **Next (Opus, high):** an A/B on one session (same build with `enabled = false`) to
    separate the gain from run noise; then either pace the held jump to the prefetch (the next snap waits until its
    rows are held, `markAsUrgent` on them) or accept, since a release build composes 2 to 3x faster than debug.
  - **Jump-ahead A/B, Mi TV (2026-11-03, measured only, no code change):** one process, hx76 debug 1280205 with a
    temporary toggle (`debug.tm.jumpahead` system property read in `JumpAheadPrefetch`, 0 = platform prefetch;
    reverted, never committed), 2 warm-ups, then 12 runs ABBA, focus on Saved Messages, back to the top between runs by
    a held Up (`holdup.sh`, 50 repeats of key 103). Medians ON vs OFF: janky 22.4 vs 28.6%, 90th 30 vs 35 ms, 95th 43
    vs 69 ms, frames 190 vs 130, hold ticks 428 vs 290. Both reach the same row in a hold (Chat 67, 4 screenshots), so
    no jump is swallowed either way. Histogram (4 more runs): frames over 50 ms 5, 1 ON vs 10, 10 OFF, so the jump hitch
    drops from 51 to 100 ms into 34 to 50 ms; ON draws about 60 extra frames under 17 ms per hold, so its janky % is
    flattered (absolute janky frames 43 vs 37) and the hold costs about 47% more CPU (none at rest, idle noisy from the
    background count). **Decision: keep the prefetch, do not pace the jump:** pacing would remove 1 to 5 frames over
    50 ms per hold in debug at the cost of a slower held Down and a timing path; release composes 2 to 3x faster.
    **Next (Opus, high, optional):** find what draws the ~60 extra cheap frames when rows are prefetched (likely a
    prefetched row's effect or `onFocusChanged`/`rowFocus` registration writing state the screen reads, which costs a
    frame each); removing it should take most of the +47% hold CPU. Otherwise HX76 is done for the chat picker.
  - **Extra frames found (2026-11-03, trace reading only, no code change):** not a row effect or focus write: the
    draw-only frames run no recomposition, and `rowFocus`/`lazyIndex` are plain HashMaps. Cause, from `hold7.trace`
    stacks plus Compose 1.12.1 sources (scratchpad d36b9478, `src-ui`, `src-fd`, scripts `perframe.py`, `inval.py`,
    `cause.py`): `precompose` of a row (new or from the reuse pool) attaches or updates its modifier nodes
    (`BackgroundElement`, `TextStringSimpleElement`, `drawWithCache` setBlock, autoInvalidate) and each calls
    `invalidateDraw`; `NodeCoordinator.invalidateLayer` has no `isPlaced` check, so it climbs from the unplaced row
    to the list's layer, `GraphicsLayerOwnerLayer.invalidate` calls `View.invalidate`, `scheduleTraversals`. That
    frame re-records the whole list layer (about 11 ms in debug), runs `measureAndLayout` (LazyList measure 3.5 ms)
    and syncs (6 ms) with nothing changed on screen. It then keeps itself going: `AndroidPrefetchScheduler` gives an
    unlimited budget only when nothing drew for 2 frames (`isFrameIdle`), so each frame it caused leaves the next
    row a frame's leftover, the row's compose and measure split across frames, each step invalidating again: about
    1 to 2 frames per prefetched row. Trace: 18 draw-only frames after a prefetch step in 4 s ON vs 2 in 7 s OFF.
    Fix options: (a) a TV prefetch scheduler (`LazyListPrefetchStrategy.prefetchScheduler`, deprecated but still
    read in 1.12.1 `LazyLayout.kt:123`) that runs the window's requests in one idle batch with a time cap (about
    40 ms), so one frame per batch instead of per step; cost: a key during the batch waits up to the cap, and the
    API may be dropped later (fall back to the platform one). (b) a layer per TV row so the climb stops there: the
    frames stay but cost about a quarter; undoes part of the layer cut (142 to 58 records per held Down). (c) accept:
    release frames are 2 to 3x cheaper. **Next (Opus, high):** (a) behind a flag, then the ABBA A/B as before.
  - **Fix (a) A/B, Mi TV (2026-11-03, measured, not kept):** `IdleBatchScheduler` (the strategy's
    `prefetchScheduler`: requests queued, run after the frame by `view.post`, the first row whole and the rest
    while a 40 ms cap lasts, the next batch a choreographer frame later; `IdleBatch` queue with 5 jvm tests).
    hx76 debug 1280206, one process, runtime toggle `debug.tm.batch` (off = a reflected `AndroidPrefetchScheduler`),
    2 warm-ups, 12 runs ABBA, focus on Saved Messages, `holdup.sh` between runs. Medians batched vs platform: hold
    ticks 331 vs 497 (-33%), frames 115 vs 185 (the extra cheap frames are gone), janky frames 38 vs 54, but 90th
    59 vs 32 ms, 95th 69 vs 53 ms; histogram (2 runs each) frames over 50 ms 17, 19 vs 9, 9. Both land on Chat 68.
    Why: gfxinfo counts a frame from its intended vsync, so a key frame that waits behind a 40 ms batch shows
    late; the platform runs a step only if it fits before the next frame, so its extra frames are cheap and
    never delay one. **Decision: not kept** (a held Down looks worse; the CPU saved is only during a hold, none at
    rest). Patch kept in `private/hx76-idle-batch.patch` (applies on 092aa1e; includes the AB-TEMP toggle and the
    `.hx76` suffix, strip those). A cap under a row (one row per gap) would still block 10 to 15 ms in debug, so
    not tried. **HX76 is done for the chat picker** unless (b) is wanted for CPU: a layer per TV row.
  - **Old progress line:** planned only, Phase 0 done above. Next: build from this plan (Sonnet, medium):
    `ChatPicking` fold and its tests, background count budget in `AddChatsViewModel`, the TV layout in
    `AddChatsScreen.kt`, the key tests, screenshots, one build, then the Mi TV check.
- [ ] HX71 (reported 2026-11-02 from Sentry, event 2026-11-01 04:04 UTC, issue 157712257, tmplayer-android, app
  1.28.3, PLAYBACK, URGENT, noted only, not researched yet, no work yet) "Video could not keep up" (logger
  `tmplayer.stutter`, prediction SOFTWARE_TOO_SLOW) on a vivo iQOO Z7 (V2270A, Android 15, arm64, 8 cores). The
  video is HEVC Main10 (`hvc1.2.4.L153.90`, 10-bit), 3840x1604, 60 fps, 5132 kbps, MP4, and it was decoded by
  the software FFmpeg decoder (`ffmpegLavc63.1.101-hevc`): 596 of 678 frames dropped, no audio underruns,
  speed 99%. The question is why a phone with a hardware HEVC decoder fell back to software. Check first: does
  the device's MediaCodec HEVC decoder list Main10 at level 5.1 (L153 = 5.1) for 3840x1604@60 (a profile/level
  or size/fps cap can make the codec refuse), did the renderer pick FFmpeg because the hardware codec failed
  once (a fallback that then sticks for the session), and is the FFmpeg preference order wrong for 10-bit HEVC.
  Prefer hardware, fall back to software only when the hardware decoder truly rejects the stream, and for a
  software-too-slow case show the existing stutter hint with a cheaper option (hardware retry, lower
  resolution if the source offers it). Look in the decoder selection and the stutter predictor under
  `playback` (grep `SOFTWARE_TOO_SLOW`, `ffmpeg`). Ties to "playback reliability first". Test with a
  3840x1604 10-bit HEVC 60 fps sample on the emulator (software path) and check the decoder choice logic with
  a unit test of fake codec capability lists. Reply with "next release" once fixed.
  Second report, same pattern (Sentry issue 157830664, 2026-11-01 17:53 UTC): realme C55 (RMX3710, Android 15,
  8 cores), HEVC Main10 (`hvc1.2.4.L150.90`, level 5.0), 3840x1616, MKV, software FFmpeg decoder again, 50 of
  253 frames dropped, speed 99%. Two different vendors (vivo, realme), both 4K-wide 10-bit HEVC on Android 15
  falling to software, so suspect our decoder choice or a common 4K 10-bit HEVC cap, not one device. Compare the
  two events' codec lists (Send logs) to see if the hardware decoder was ever offered.
  Third report, a likely FALSE POSITIVE (Sentry issue 157782531, 2026-11-01 12:29 UTC): Xiaomi Mi TV
  (MiTV-AFKR0, Android 11, armeabi-v7a, 4 cores), HEVC Main10 1920x1080 MKV on the HARDWARE decoder
  (`OMX.amlogic.hevc.decoder.awesome2`), 0 of 274 frames dropped, 0 underruns, speed 100%, prediction
  HARDWARE. The stutter warning fired although nothing stuttered, so the trigger in the stutter detector is
  wrong or too sensitive (check what it fires on besides dropped frames and speed: a short stall at start, a
  seek, a buffering pause counted as a stutter). Fix the detector so it needs real drops or speed under 100%,
  and add a unit test with these numbers (0 dropped, speed 100, hardware) that must not report. Noisy events
  also hide the real software-too-slow ones above in Sentry.
  Fourth report, a REAL stutter on hardware (Sentry issue 157780179, 2026-11-01 12:15 UTC, same Mi TV and same
  Sentry trace id as the third, so one session, 14 minutes apart): H.264 High (`avc1.64001F`) 1280x536 8-bit
  MKV on `OMX.amlogic.avc.decoder.awesome2`, 50 of 316 frames dropped (16%), speed 90%, 0 underruns,
  prediction HARDWARE. A hardware H.264 decode of a 720p-class video should never drop that many, so the cause
  is not the decoder: look at the slow 4-core armv7 TV under load (the app's own background work such as
  paging, warm-ups or thumbnails, see the idle CPU rule in AGENTS.md), the render path (surface size, the UI
  drawing over the video, tunneling or frame-rate matching), and the source (cache or TDLib reads blocking the
  playback thread, though underruns are 0). Reproduce on the Mi TV (memory: mi-tv-speed-testing) with a
  720p H.264 MKV and watch CPU with `/proc/<pid>/stat` while playing.
- [x] HX32 (2026-10-31, 16b0c04, fixed) Chats are missing from the list: archived ones, and "still some"
  after the user unarchived them on the phone (POCO F5, 1.28.3, account with many movie channels). Found in the
  code, nothing changed yet. (1) `ChatRepository.ARCHIVE_LIMIT = 100` (line ~634): only the first 100 archived
  chats are ever read (`ChatListLoad.sync` loads one 100 page, `cached` calls `getChats(Archive, 100)`), and
  `CHAT_LIMIT = 300` caps the main list, with more only after a "load more" the user may never reach. A heavy
  user has hundreds of movie channels, so the rest never appear. (2) `cached()` calls `getChats(Archive)`
  inside `runCatching`, so one failure (a flood wait, the app starting offline) makes the WHOLE archive
  vanish silently, with no retry and no message. (3) `calls.summary()` returns null when `getChat` fails and
  `filterNotNull()` drops that chat without a trace. (4) `browseSections` hides archived chats everywhere
  except the Archived chip (BrowseSections.kt ~357), so Home rows, search, the carousel and "Recently added"
  may skip videos from archived channels: check each, a viewer who archives a movie channel still wants its
  videos on Home. (5) Folder chips: "Movies" showed "Nothing in this folder yet" on the phone, from
  `membership()` swallowing a failure with `runCatching { ... }.orEmpty()` (an empty list is
  indistinguishable from not loaded), and the empty text says "edited in Telegram" even when the read failed.
  (6) The Chats list shows no video count, so a chat with 100+ videos looks like any other. Plan: raise or
  remove both limits and page the archive like the main list until `AllLoaded` (bounded by memory on the 96 MB
  stick: rows are small `ChatSummary`, `getChat` chunks of 32), retry a failed archive read once after the
  flood wait and show a visible "Some chats could not load, Try again" row instead of nothing, log each
  dropped chat, keep archived chats' videos on Home (a switch, default on), show "not loaded yet" not "empty"
  for folders. Verify against `SyntheticAccount` (1,500 archived, 3,000 main) and with a unit test per cause.
- [x] HX33 (2026-10-31, b4b3dfc, fixed) Sentry 157382777, fatal, Windows 11, 1.28.2. `NoSuchElementException: Expected at least one element`
  from `kotlinx.coroutines.flow.first` on an empty flow, uncaught. AGENTS.md bans `.first()` on outside data.
  The trace has no app frames (R8), so find it: `grep -rn "\.first()" desktop core ui` on flows that can
  complete empty (settings, session, update feed), use `firstOrNull()` with a default, and put a garbage test
  in. Check the desktop mapping file for the release to name the caller before guessing.
- [x] HX34 (2026-10-31, ed432dd, fixed: tdl-coroutines upgraded to 17.0.0) Sentry 157222181 Android 17 Samsung SM-S948U1 1.28.2, and the desktop twin, fatal. `NoSuchElementException:
  Key photo is missing in the map` and `Key background is missing in the map`, thrown inside the TDLib Kotlin
  wrapper (`dev.g000sha256.tdl`, an R8 frame in a TDLib update handler) while building an update from TDLib's
  JSON. TDLib's native library sent an object without a field the wrapper requires (a newer TDLib than the
  wrapper, or a field Telegram now omits). It is outside our code, so the app dies on a Telegram answer: the
  exact failure HX10 forbids. Plan: find the wrapper and native TDLib versions (`Td.kt`, the gradle pins in
  docs/BUILDING.md), see whether a newer wrapper release fixes it, else wrap the update dispatch in a
  `CoroutineExceptionHandler` / `JobFailures` so one bad update is skipped and logged, never fatal; report
  upstream with the two keys.
- [x] HX35 (2026-10-31, e08fddf, not pushed; Sentry 157503393 and an unnumbered ANR, 2026-10-30, 1.28.3, Changhong "AI PONT" Android 11 TV)
  Two ANRs in the player on the same TV: `PlayerActivity.onDestroy` blocks the main thread in
  `ExoPlayer.release` (`ConditionVariable.blockUninterruptible`, PlayerActivity.kt ~8390), and `finish()` from
  `dispatchKeyEvent` (~5302/4965) waits on a Binder call. The same TV also reports the HEVC stutter with
  `OMX.MS.HEVC.Decoder` (dropped 50 frames), so the decoder is likely wedged and release waits on it. Plan:
  release the player off the main thread with a timeout (a thread that may be abandoned, the activity finishes
  at once), stop the decoder first, make Back never wait on release, and measure on a TV emulator and the
  Mi TV; do not release twice; keep the PiP and sleep paths in mind.
- [x] HX36 (2026-10-31, fa9f905, fixed) Sentry, Windows, `UnsatisfiedLinkError: An Application Control policy has blocked this file`, mpv.
  Windows Smart App Control or WDAC blocks the unsigned mpv DLL (`MpvPlaybackEngine.<init>`, PlayerScreen.kt
  ~239). It is an `Error`, not an `Exception`, so a plain `catch (e: Exception)` misses it and the window dies.
  Plan: catch `UnsatisfiedLinkError` (and `LinkageError`) around engine creation, fall back to the engine that
  needs no native file if one exists (check the desktop engines), else the player's status sheet says "Windows
  blocked the video engine" with the policy's name and a link to the page that explains how to allow it or
  use the portable build; remember the failure so the next play does not retry into a loop; add to docs/BUILDING
  or the site's Windows page.
- [x] HX37 (2026-10-31, 58b65fb, not pushed; Sentry, 6 "Video could not keep up" warnings 2026-10-30 and 31, 1.28.2 and 1.28.3) With HX11. Five
  are HEVC Matroska on `OMX.MS.HEVC.Decoder` (Changhong AI PONT and SWTV TVs, Android 11, armv7, 4 cores, 8 and 10
  bit, 76 to 100 percent speed, 0 to 50 dropped frames, 0 underruns) and two on MediaTek `c2.mtk.hevc.decoder`
  phones (Android 16 and 17, 0 dropped frames, one at speed 36). Every one has `fps 0.0` and `bitrate 0`,
  as HX11: the Matroska frame rate is not read. With 0 dropped frames and 0 underruns the warning may be a
  false positive (startup, a seek, or a network or download stall measured as slow decoding): check how
  `tmplayer.stutter` computes `speed_percent` and gate the report on a real symptom. For the 4-core TVs, test a
  1080p 10 bit HEVC MKV on the Mi TV and try the software decoder fallback as `DecodeJudge` already models.
- [x] HX48 (Sentry 157637484, fatal, MiTV-MOEU0, 1.26.0, fixed 2026-10-31) `NullPointerException: Can't toast on
  a thread that has not called Looper.prepare()` from `Toast.makeText`/`show`, all frames outside the app.
  `PlayerActivity.downloadFirst()` (the only raw `android.widget.Toast.makeText` call left in the app; every
  other file importing `Toast` only passes its `LENGTH_*` constant into the Compose toast wrapper in
  `ui/src/androidMain/.../Toast.kt`) is only reachable today from `statusOpenWith`'s click listener, which is
  already on the main thread, so the exact 1.26.0 call path that crashed is not reproducible as the code
  stands now (an older refactor likely already moved this). Hardened anyway since a raw `Toast.show()` off
  thread is a standing footgun: wrapped it in `runOnUiThread { ... }`. No test added (nothing to assert against
  without a thread-switch harness); compiles clean.
- [x] HX49 (Sentry 157626500, fatal, Haier MatrixTV DE, Android 14, 1.28.3, guarded 2026-10-31, not pushed)
  `IllegalArgumentException: offset(2) is out of bounds [0, 1)` inside Compose foundation's own
  `CoreTextField`/`MultiParagraph` (`notifyFocusedRect` -> `getBoundingBox` -> `requireIndexInRange`), every
  frame outside the app. Researched first (cheapest route): no confirmed fix in our pinned composeBom
  `2026.09.00` / composeMultiplatform `1.12.2`, and no upstream issue matches the exact shape closely enough to
  cite a fixed version (closest: Google issue tracker 119393094 and 298046462, the same "stale text-layout
  offset after a shrink" family, both still open) — not a known-fixed bug to upgrade out of.
  Found the trigger instead: `HeldText.sync` (`ui/.../components/HeldText.kt`) already names the exact shape
  ("a recent search tapped, then Clear") as "a text from elsewhere," distinct from the field's own typing. On
  `TvSearchField` (the one TV field that takes a Clear this way) that external shrink can land while the field
  still holds the IME's focus, racing the IME's own async focus-rect query, which is built for the old, longer
  text. Fixed: `HeldText` now exposes `externalChange` (true only for a text-from-elsewhere sync, not the
  field's own edits); `TvSearchField` watches it and, while focused, clears and immediately reclaims focus
  (`reclaimingFocus` guards its own `onFocusChanged` from reading that blip as the viewer leaving and closing
  editing), so the IME tears down and rebuilds its session against the fresh text instead of querying a stale
  one. Other TextField call sites (phone/desktop search, the Add chats picker, settings fields) were not
  touched: HeldText's addition is backward compatible, and this crash's shape (a remote's Clear on a field
  still IME-focused) is TV-specific. `HeldTextTest` covers `externalChange` telling typing apart from a text
  from elsewhere; `:core:jvmTest`/`:ui:jvmTest` and `:app:compileDebugKotlin` clean. Not verified against the
  real Haier TV (not owned): if HX49 recurs after this ships, the race may reach the field a different way
  (a recent search row chosen while this field, rather than that one, holds focus) and needs a second look.
- [x] HX50 (Sentry 157592092, warning, AFTS SS, Amazon Fire TV Stick, Android 9, armv7, 1.28.3, reviewed
  2026-10-31, no change) "Video could not keep up": avc1920x1080 8-bit on `OMX.MTK.VIDEO.DECODER.AVC`,
  speed_percent 99 (not slow), dropped_frames 50 of 312 frames (~16%, over HX37's "a tenth of frames dropped"
  bar), underruns 0, fps 0.0 and bitrate 0 (the Matroska-frame-rate gap HX37/HX11 already named, here on AVC
  not HEVC). Checked: `PlaybackWatch.symptomOf` (`core/.../player/PlaybackWatch.kt`) ORs the speed, drop-share
  and underrun checks, so a report does not need all three; HX50's figures trip the drop-share branch alone.
  That branch IS measured over more than an instant (summed across the whole 12 second `WINDOW_MS`, after
  `interrupted()` already drops a seek, pause or buffering stall from the count), but that aggregation is
  cumulative from the first counted sample rather than windowed, so one bad second amid an otherwise flawless
  watch (a GC pause or a brief decoder hiccup while still playing, not a seek) can alone push the share past
  the 10 per cent bar, same as a genuinely struggling device: added
  `one bad second amid an otherwise clean window still trips the drop-fraction gate` to `StutterGateTest` to
  pin this down. A narrower fix (a floor requiring the drops spread across more than one sample, or more than
  one second) would need its own tuning and its own false-negative risk against a Fire Stick that really is
  behind, so left as a documented gap rather than changed blind; ask the user which way to tune it if HX50-
  shaped reports keep coming in. HX50's own report itself is plausibly a real, if mild and borderline, stutter
  on weak hardware (Fire TV Stick, Android 9) rather than a false alarm either way.
- [x] HX51 (reported 2026-11-01, desktop AppImage 1.28.4-dev, BUG, URGENT, fixed f14afd7) Sign out spun forever.
  The confirm dialog closed itself, then launched the sign out on its own `rememberCoroutineScope`, which the
  close cancelled: each step's `runCatching` swallowed the cancel (nothing cleared), the window went to
  Connecting, and the cancelled `logOut` never reached TDLib (log: no `LoggingOut`, then the 12 s stall
  replaced the client and it came back Ready). Now on `Background.scope`; `Td.logOut` sends under
  `NonCancellable`; the cache clear before it waits at most 5 s. Phone uses the screen's scope, not affected.
  Due: the user signs out once on the AppImage to confirm.
- [x] HX52 (reported 2026-11-01, desktop on sway, BUG, URGENT, fixed d50ca6b) Back up / Restore "reloaded the
  whole app": the portal file dialog had no parent, so sway tiled it beside TMPlayer, halving the window and
  re-laying every page (the log's run of backdrop redraws), then again on close. Every FileKit picker (and
  Swing's fallback) now names the app window as parent. Due: user check on sway that the dialog floats; if
  the portal ignores the parent there, a sway `for_window [app_id="xdg-desktop-portal-gtk"] floating enable`
  rule is the fallback.
- [x] HX53 (reported 2026-11-01, desktop, BUG, URGENT, fixed a0c4f69 884f801) The Downloads card looked
  broken, and Free up space in Settings jumped to the Downloads panel. The list kept its old 920 dp full page
  cap inside the 1040 dp panel (a wide empty strip on the right) and the sort chips were inset twice; now even
  margins, chips under the headings (`ChoiceChips(inset)`). Settings' Free up set `downloadsOpenFreeUp` and
  went to Downloads at once; the review now runs over Settings (shared `FreeUpReviewHost`, `runFreeUp`) and
  ends in a toast. Left as is: the folder scan in Settings still switches to Downloads when it finds files.
- [ ] HX54 (reported 2026-11-02, desktop, BUG, URGENT, not researched yet) Scrolling a carousel hard on desktop
  shows "Telegram is slow to answer" just below it. Question to answer: is the carousel not caching (fast
  scrolling asks TDLib for pages or thumbnails again instead of reading the disk/memory cache, and trips the
  slow-answer notice)? Issue only, research later: reproduce with a fast wheel/drag scroll, check the log for the
  calls behind the notice, and whether the notice fires on a slow call that is only prefetch.
- [ ] HX55 (reported 2026-11-02, desktop and TV, BUG/UX, URGENT, PARTLY DONE 2026-11-03 in code, not device-checked: TV overview 3 lines, score marks 24 dp on TV and 22 dp on desktop with 2 lines, Details pill grey, start fade 0.72 to 0.6; TV carousel 232 to 328 dp and the title logo cap 72 to 56 dp (2026-11-03, device-checked on the Mi TV with the preview build: grey Details pill and 3 overview lines shown, at 280 dp the overview was ellipsized at 2 lines because the column had no room; 312 was still short with a score row); detail-page picture height 0.62 to 0.74 of the window on TV and desktop, hero max 680 to 800 dp, not device-checked, TV case added to FeaturedCarouselRenderTest 2026-11-03; low-res fallback DONE 2026-11-03 (EdgeBackdrop: a source 480 px or wider but under 2/3 of the frame width is drawn at 1.5x its width at the end, fully faded at its start; tiny thumbnails keep the 2x cap; sizing is `edgeSize`, tests `EdgeSizeTest` and `EdgeBackdropRenderTest`, not device-checked); w1920 size check DONE (TMDB's API has no 1920 size: w1280 then original; `MetaImageSizeTest`; Android TV decodes 1280 so 1080p is exactly at the cap, a 4K-pixel frame would show it at 1920 wide, not checked on a device); still open: device check on TV and desktop, real w780-only titles) The Home carousel image does
  not look like the TMDB backdrop it should be (reference: media.themoviedb.org/t/p/w1920_and_h800_multi_faces/
  2C6yyoCStz7jcwjINdJTF5zAbi4.jpg, the wide 1920x800 "multi faces" crop). Desktop: the edges of the image are
  too black (the scrims/gradients cover too much) so the picture is not properly visible, and the rating icons
  are too small. TV: the same two problems, plus low resolution: when the poster/backdrop source is low quality
  the old style (a smaller image placed on the side) looked clearer, because a small image is shown nearer its
  native size, while the new full-width one is stretched and looks soft. Issue only, research later: check which
  TMDB size is requested (w1920_and_h800_multi_faces vs smaller), the scrim and vignette alpha on desktop and
  TV, rating icon sizes per form factor (scale tests), and whether to fall back to the side layout when the
  source image is below the width needed. Design system tokens first, TV focus unchanged.
  Added 2026-11-02 (user, Android TV carousel, same task): (1) the description is cut off; (2) the ratings do
  not show properly (not just small: check what is missing or clipped); (3) the Details button should be grey
  or lighter (a secondary, quieter style next to Play) so it draws less attention, still with a clear focus
  mark; (4) the black gradient should cover only as much of the left side as the title, description and
  buttons need to read, not more; (5) the image does not show in full: consider a taller carousel on Android
  TV. Check against a long description, no rating, every rating source, a portrait-only or low-res picture, light
  and dark theme, reduced motion, and the TV preview screenshot tests; keep D-pad focus order Play, Details.
  Check and fix the same five on the desktop (window sizes from small to 4K, scale tests), and the height of the
  picture on the video's detail page on TV and desktop (the same "not shown in full" problem) where it needs it.
- [ ] HX56 (reported 2026-11-02, Android TV first, then phone, desktop and web, UX, URGENT, not researched yet)
  "Add more chats" in the onboarding, and other places on Android TV, have bad UX. Not pinned down which steps
  yet. Review the same screens on every other device too (phone, desktop, web), since the same flow is shared.
  Issue only, work on it later. When taken: walk the onboarding and every "add more chats" entry point (also
  the Home row and Settings) on the TV with the D-pad first: where focus lands on entry, whether every control
  is reachable and visible when focused, Back behaviour, the empty state, a huge chat list (3,000 chats),
  then the same walk on phone, desktop and web. Machine checks first (focus tests with `performKeyInput`,
  preview screenshots per form factor), devices after. Look for the existing add-chats list/row and extend it,
  do not build a parallel one (see "Recently added" lesson in AGENTS.md).
- [ ] HX57 (asked 2026-11-02, all platforms, feature, URGENT, phone and TV planned 2026-11-03, desktop and web not
  yet) Storage location: there is no
  option to change the path where TMPlayer keeps its files (downloads, caches, saved videos). Add a setting to
  pick and change it. Issue only, plan later. When taken, plan per platform first (phone, TV, desktop, web where
  it applies) with the edge cases from AGENTS.md: free space check (needed vs free), moving existing files to the
  new place with progress, cancel and a full disk part way with no partial files left, a revoked folder
  permission or a missing drive, the same action run twice, a confirm dialog before removing the old copy (off by
  default), Android storage rules (Android 6.0 floor, scoped storage and SAF, `LegacyApiCompat.kt`), desktop paths
  on Windows, Linux and macOS, and the TV remote and focus on the picker. Look first for the existing folder
  setting (Settings storage, Downloads folder, backups) and extend it.
  - **Progress / handoff (2026-11-03, phone and TV planned, no code yet).** Extends HX26 (7bad029), no parallel
    setting. Decisions (user, 2026-11-03): the phone gets "Other folder" through the system folder picker (SAF);
    TDLib's cache and the watch cache stay on internal storage (Settings says so). Desktop (`StorageSettings.kt`)
    and web are separate HX57 parts, not planned here. "Saved videos" is already covered by Save to folder and
    Save to gallery (`FolderExport.kt`), not touched.
  - **Audit, already handled by HX26:** free space before a move (needed plus 1 GB spare, `planLocationChange` in
    `ui/settings/DownloadLocationSettings.kt`); progress on the row and in a foreground notification with Cancel
    (`DownloadRelocationService`); cancel stops after the current file and removes its `.part`
    (`DownloadFiles.moveIntoDownloads`); a pending move survives a kill and resumes at launch
    (`DownloadRelocation.resumePending`); the same action twice (`running` guard); a confirm with "also move"
    off by default; Android 6 floor (`getExternalFilesDirs`, `StorageManager` guarded at N); a missing card
    sends new downloads to internal and the row warns; the root is left out of backups. Protected chats cannot
    be downloaded at all (`OfflineDownloads.start`), so a public folder raises no Telegram terms issue.
  - **Audit, gaps:**
    1. The row is hidden with only internal storage (`DownloadLocationSettings.kt`, `if (volumes.size <= 1)
       return`): most phones have no setting at all. This is HX57 on the phone.
    2. Only app-specific folders exist, which Android erases on uninstall (the card's too) and which the gallery
       and file managers cannot see on Android 11+. The pick note does not say so.
    3. Internal storage has no free-space line in the picker (`detail` is null when `root` is null).
    4. A full disk part way: every later file is still tried (fills, fails, removes its `.part`), slow, and the
       toast only says how many failed. Stop at the first no-space error and say how much more is needed.
    5. A pending move whose card is gone retries silently at every launch; nothing tells the viewer. Show
       "waiting for {card}" on the row with a way to forget the move.
    6. A card pulled mid move fails file by file; the toast does not say the card went away.
    7. `!!` on `localPath` in `DownloadRelocation.finish`, `sizeOf` in `DownloadLocationSettings.kt` and
       `DownloadsScreen.kt:456`: replace (AGENTS error rule).
    8. The volume list is read once (`remember`): a card put in while Settings is open never shows. Re-read on
       resume and on `ACTION_MEDIA_MOUNTED`/`UNMOUNTED`.
    9. No test for the row, the sheet or the confirm: no focus/D-pad test, no screenshot per form factor.
  - **Phase 1, HX26 gaps (Sonnet, medium).** Always show the row (internal alone is still a choice, and Phase 2
    adds "Other folder"); fix gaps 3 to 8; pick note adds "Kept in TMPlayer's own folder, removed if you
    uninstall" for internal and card volumes and "Caches stay on internal storage". Strings in `en.json` only.
    Tests: `DownloadRelocationTest` gains no-space-stops-the-move, card-gone-pending-shows, forget-the-move;
    a Compose test for the row (shown with one volume, free space on every line); a TV focus test (first focus
    on the current volume, Down through the list, Back returns focus to the row, confirm opens on Cancel).
  - **Phase 2, "Other folder" through SAF (Sonnet, high; it moves the viewer's files).**
    - Storage: a picked tree is kept as a `content://` string in the same `downloadRoot` setting (the prefix
      tells a tree from a path), parsed with `runCatching`, its grant taken with `takePersistableUriPermission`
      and checked against `persistedUriPermissions` at launch and before each use. Only the local provider
      (`com.android.externalstorage.documents`) is accepted: a cloud folder cannot seek, so it is refused with a
      reason. A garbage line in `GarbageInputTest` for the stored value.
    - Writing: a finished download is copied into the tree through FolderExport's `SafFolder`
      (`FolderExport.kt:343`, `folderFor`) and `FolderCopyIo`, size checked, then TDLib's copy deleted; a failed
      copy is discarded (`SafFolder.discard`), so no partial file. The record's `localPath` holds the document
      Uri.
    - Reading: `LocalDownloads.fileFor` returns `File?` today; change it to a small sealed `LocalVideo` (a file
      on disk, or a document Uri) with `uri()`, `length()`, `exists()`. Call sites: `MainActivity.kt` 406, 848,
      1165, 1191, 1370; `DownloadService.kt:436`; `PlayerActivity.kt` 1923, 5027 (joined pieces); plus
      `LocalDownloads.kt`, `StorageSplit.kt`, `RoomOnDisk.kt`, `DownloadsScreen.kt` 456, 602. Media3 plays
      `content://` as is. Check before coding: `PlayerCast` serving a download to a TV, subtitle and track
      reading, storyboard and thumbnails, `FrameSaver`; anything that needs a path gets one from a
      `ParcelFileDescriptor` or falls back to Telegram.
    - Moving: `DownloadRelocation.finish` writes to a target that is a folder on disk or a `SafFolder`, with
      the same pending note, resume, cancel and stop-on-full. Free space through `FolderExport.freeBytes(tree)`
      where the tree maps to a path; unknown otherwise, said plainly (as `save_to_folder_free_unknown` does),
      and the move stops cleanly if the folder fills.
    - Revoked grant or deleted folder: new downloads go to internal, the row says "TMPlayer can no longer reach
      {folder}. Choose it again", those downloads show as missing in Downloads and play from Telegram; no crash
      and no retry loop.
    - Android limits: `ACTION_OPEN_DOCUMENT_TREE` from API 21, so it works down to the API 23 floor;
      `EXTRA_INITIAL_URI` (API 26) goes through `LegacyApiCompat.kt`; on Android 11+ the picker refuses the
      Download root and `Android/data`, so the note says to pick or create a subfolder (Download/TMPlayer).
      Files in a picked folder survive uninstall; after a reinstall they are not listed (records are not backed
      up), which the note says.
    - TV: "Other folder" shows only where the intent resolves (many TV boxes have no picker), so the TV keeps the
      volume list; after the picker returns, focus goes back to the row.
    - Tests: Robolectric for a tree pick, a revoked grant, a cloud tree refused, a move into and out of a tree
      with cancel and a full folder; `LocalVideo` unit tests; the Phase 1 focus test with the extra entry.
  - **Phase 3, devices.** Emulators API 23 and API 35 with an SD card image (`-sdcard`, `-gpu host -no-window`,
    workspace 10): pick, move, cancel, eject mid move, revoke the grant in system settings. Mi TV: focus and idle
    CPU after Settings. No phone pass unless asked. Leftovers listed for the user.
  - **Phase 1 DONE (2026-11-03, commit see git log "HX57 phase 1").** Row always shown; free space on every
    line incl. internal; pick note says own folder, erased on uninstall, caches stay internal; move stops at
    the first full disk (`Stop.NoSpace`, says how much more) or pulled card (`Stop.VolumeGone`); pending move with
    the card gone waits quietly, row says "Waiting for {card}", extra "Forget the move" row (`waitingFor`,
    `forgetPending`); a card put back resumes the move (`LaunchedEffect` on the volume tick); volumes re-read on
    resume and on media mount/eject broadcasts; `!!` gone in `DownloadRelocation`, `sizeOf`, `DownloadsScreen`.
    Tests: `DownloadRelocationTest` (+3: volume gone stops at first, no-space words and message, waiting and
    forget), `DownloadLocationRenderTest` (phone and TV frames, one volume). Build needs
    `JAVA_HOME=~/.jdks/jdk-21.0.12+8` (system JDK is 25).
  - **Phase 1 leftovers (TV focus test partly done in Phase 2, see its leftovers):** the TV focus test (first focus on current volume, Down through list, Back to row,
    confirm opens on Cancel) is NOT written: `app` has no compose ui-test, only the Robolectric key-dispatch
    pattern of `TvReachTest`; do it in Phase 2 with the extra entry. No test of the no-space path with a real
    ENOSPC (only the message detection). Gap 6 for a card pulled from the source side is covered by
    `!src.exists()`, untested.
  - **Phase 2 DONE (2026-11-03, commit 02a72cc).** "Other folder" through SAF on touch
    devices (hidden on TV, whose picker is a stub; `<queries>` for `OPEN_DOCUMENT_TREE` added to the manifest so
    Android 11+ resolves it). Storage: `StoredLocation` (core) tells blank / volume path / local tree / unusable
    by prefix; the tree string lives in `downloadRoot` and a record's `localPath` holds the document Uri string.
    `DownloadTree` (grant take/check/release, `usable`, `put` into a tree through `FolderExport.folderFor` +
    `FolderCopyIo.pipe`, size read back or discarded, `pull` out through `.part`, `releaseUnused`).
    `LocalVideo` (OnDisk | InTree: `uri()`, `length()`, `exists()`, `name()`, `open()`, `readAt()`,
    `handoutUri()`); `LocalDownloads.fileFor/presentFile` became `videoFor/presentVideo`. Player plays the
    document Uri (cast, joined pieces, chapters, subtitle hash through `readAt`, scrub thumbnails through
    `MediaMetadataRetriever` with the Uri), share and external player hand the document over.
    `DownloadRelocation` has `Destination` (Internal | Volume | Tree) and moves any source to any destination
    with stop-on-full / lost grant, pending note, resume, cancel with no partial; `DownloadFinisher` copies a
    finished download straight into the tree (`MovedToTree`), falls back to internal while the grant is gone.
    Settings: "Other folder" line, the picked folder as a chosen line, "TMPlayer can no longer reach {folder}.
    Choose it again", cloud folder refused with a reason, free space unknown said plainly, grant given back when
    the pick is dismissed. Tests: `StoredLocationTest`, `GarbageInputTest.storedLocationNeverThrows`,
    `DownloadTreeTest` (8), `DownloadRelocationTreeTest` (8, with `FakeTreeProvider`), `DownloadLocationPhoneTest`
    (2), `DownloadLocationFocusTest` (TV). Added `androidx.compose.ui:ui-test-junit4` (+ manifest, debug) to
    `app` tests: now compose rule tests work under Robolectric. Core 2359 and app 572 tests green.
  - **Phase 2 leftovers (not done, on purpose or for lack of a way):**
    1. Save to folder / Save to gallery skip videos kept in an Other folder and count them as "not finished"
       (`FolderExport.plan` wants a `File`; `FolderCopyIo.Source` is File-based and shared with the desktop).
       Fix next: let `Source` take a stream supplier. Misleading message until then.
    2. The TV list's own focus (first focus on the volume in use, Down, Back to the row, confirm opens on
       Cancel) is NOT machine tested: the list is a dialog window Robolectric does not focus (the test only
       checks the row takes the remote and OK opens the list without Other folder). Device check on the Mi TV.
    3. Phone/TV screenshot of the open list and the Other folder confirm are not made.
    4. Downloads shows a revoked folder's videos as "File missing"; only Settings names the folder.
    5. Room check before a download covers internal storage only for a tree (TDLib downloads there first); the
       copy at the end checks the folder's room when it can be asked and fails with the reason, the file stays
       whole in the cache for Try again.
    6. `presentIds` / `indexedBytes` ask the provider once per download (one query each): fine for hundreds,
       not measured at 10,000 on a slow card. Legacy downloads (no path) still migrate to internal.
    7. Real ENOSPC on a real tree is only simulated with `/dev/full`; not run on a device.
  - **Phase 3 PARTLY DONE (2026-11-03, emulator `tmplayer_phone_api36` only, user chose no new AVDs).**
    Passed: SD card listed with free space (SDCARD 510 MB); Other folder through the system picker (created
    Download/TMPlayer, grant held, confirm dialog, row then reads just "TMPlayer"); a season queued from Saved
    Messages: E1 (821.9 MB) landed whole in the folder (861,793,242 bytes, size matches), Downloads lists it,
    cancel of queued episodes works; tree to internal move with "also move" ticked showed "Moving downloads,
    299 MB of 822 MB", finished in about 15 s, folder left empty, no partial; SD too small is refused before
    the move. Findings: (a) refusal says "The downloads alone need 1.0 GB" while the dialog said 822 MB:
    `StorageRelocationPlan.validate` adds `SPARE_BYTES` to the figure, so "alone" is wrong, reword or show the
    downloads size and the margin apart; (b) the row after a pick shows only the folder name ("TMPlayer"), not
    that it is a picked folder or where; (c) no Cancel control found during a move (tapping the row mid move
    opens the location list); (d) no size or free-space prompt when queueing a 3 GB season (internal room check
    only, by design, see leftover 5). NOT done: play / cast from the tree, move to SD and back, eject mid move,
    folder deleted while chosen (the way to lose a grant: adb cannot revoke a SAF grant, so `rm -r` the folder),
    uninstall and reinstall, API 23 and API 35, Mi TV focus and idle CPU, leftover 1.
    State left: emulator may still run (`adb -s emulator-5554 emu kill`), SD image is a 3 GB file made with
    `mksdcard` in the session scratchpad (a `-sdcard` file must exist first), app is signed in again (QR scan and
    password by the user this session: the AVD had lost its login), storage is Internal with E1 inside.
    Gotchas: coordinates differ per screen so screenshot before each tap (a blind chain opened a Loki page); in
    zsh use `>|` for screencap files and never `pkill -f <script>` from the same shell.
  - **Phase 3 code part DONE (2026-11-03, commit "HX57 phase 3 code").** Finding (a): refusal now says "The downloads
    and 1 GB to spare need {needed}" (`drive_too_small`, `default_drive_too_small`, shared with desktop; test
    updated). Finding (b): the row after a pick reads "Other folder: {folder}. Downloads stay there when TMPlayer
    is uninstalled" (`android_folder_chosen`). Leftover 1: `FolderCopyIo.Source` now takes length and open
    functions (File constructor kept for desktop and tests), `FolderExport.plan` reads through `LocalVideo`, so
    Save to folder / gallery copy videos kept in an Other folder (+1 test, stream source and a gone one).
    Not done: findings (c) no Cancel control during a move and (d) no size prompt for a 3 GB season (by design).
    Device items below were NOT run this session (no device pass, only the emulator was left running).
  - **Phase 3 devices, partial (2026-11-03, session 2).** Build needs `JAVA_HOME=/home/dracu/.jdks/jdk-21.0.12+8`
    (system JDK 25 fails: "does not provide JAVA_COMPILER"). A first install in this session was a STALE APK
    (build failed, old file installed): always check the build exit before `adb install`. Rebuilt, installed on
    emulator-5554 (API 36 phone, still signed in, SD 510 MB free, internal 3.1 GB free). Verified: Storage
    location dialog shows the new uninstall wording and lists Internal / SDCARD / Other folder. NOT run: every
    item in the list below (pick folder, play, cast, move, cancel, eject, grant loss, reinstall, API 23/35, Mi TV).
    The screen needs a screenshot per tap (1080x2400, screenshots shown at 0.83 scale).
  - **Phase 3 devices, session 3 (2026-11-03, emulator-5554 API 36 only; Mi TV skipped, user: dad is using it).**
    Mi TV note: its `com.tmplayer` is release-signed with versionCode 1280703, so a debug install fails
    (VERSION_DOWNGRADE, then UPDATE_INCOMPATIBLE); use `com.tmplayer.test` or uninstall (loses login) next time.
    Passed on the new build: Other folder pick (Download/TMPlayer, allow dialog, "also move the 1 download" ticked),
    row reads "Other folder: TMPlayer. Downloads stay there when TMPlayer is uninstalled."; the 822 MB file moved
    in about 20 s, Downloads lists it, Play from the folder starts (media session PLAYING, no crash); `rm -r` of the
    folder then restart: Settings says "TMPlayer can no longer reach Download/TMPlayer. Choose it again. New
    downloads are going to internal storage until then.", Downloads shows "File missing" (leftover 4 still true).
    NOT run: cast from the folder, move to SD and back and eject mid move (SD has 510 MB, file is 822 MB, so
    refused; needs a bigger card image or a smaller video), uninstall and reinstall (wipes the emulator login),
    API 23 and API 35 phone (no such AVDs, user chose no new ones; TV AVDs hide Other folder), Mi TV focus and
    idle CPU. State: storage setting still points at the deleted folder, emulator running, helper script
    `ui.sh` in the session scratchpad (uiautomator dump + tap by text; tap ALLOW by coordinates, text match hits the title).
  - **Phase 3 Mi TV (2026-11-03, session 3, MiTV-AESP0).** Installed the preview build (1.28.1) as
    `com.tmplayer.test` after uninstalling the newer 1.28.5-test (user agreed; login lost, user scanned QR and gave the
    password). Passed: Settings, Storage and downloads, Storage location: first focus on the volume in use
    (Internal), Down reaches Close, Back returns to the Storage location row; no Other folder entry on the TV; list
    shows Internal only (no card on the TV). Idle CPU 30 s after sitting on Settings: 76 ticks (about 2.5% of one
    core at 100 Hz). Not run: a TV with a card, Home/lists idle CPU. TV test app is now 1.28.1 and signed in again.
  - **Phase 3 devices, session 4 (2026-11-03, emulator-5554 only).** No new test run. Checked state: emulator and
    Mi TV (10.27.27.34) both connected, app on Storage and downloads still says the Download/TMPlayer folder is
    unreachable, `/sdcard/Download` empty, SD image `0000-0000` still about 510 MB free. Blocker for move, cancel and
    eject: no small downloaded video on the emulator (the only one is 822 MB, bigger than the card). Needs either a
    small video downloaded from a chat on the emulator or a bigger SD image. Cast from the folder needs a real cast
    device; API 23 and API 35 phone AVDs do not exist (user chose no new ones).
  - **Phase 3 CLOSED (2026-11-03, user).** Run on devices: Other folder pick, move in, play from the folder, lost
    grant message and "File missing", Mi TV focus and idle CPU. NOT run, left open: cast from the folder (needs a
    cast device), move to card and back, cancel and eject mid move (the emulator card shows 512 MB whatever the
    image or `sdcard.size` says, tried restart, repartition and config; needs a small video or another card tool),
    uninstall and reinstall, API 23 and API 35 phones, a TV with a card. Findings (c) no Cancel during a move and (d)
    no size prompt for a 3 GB season stay open by design. Emulator left running on emulator-5554.
  - **Next (superseded by Phase 3 CLOSED):** devices (Sonnet, medium). Emulators API 23 and API 35 with an SD card image (`-sdcard`,
    `-gpu host -no-window`, workspace 10): pick Other folder (Download/TMPlayer), download a small video into
    it, play it, cast it, move to the card and back, cancel mid move, eject mid move, revoke the grant in
    system settings, uninstall and reinstall (files stay, not listed). Mi TV: focus on the list and idle CPU
    after Settings. No phone pass unless asked. Leftover 1 is done. Prompt for a fresh session: "Run HX57 phase 3
    (devices) from private/PLAN.md (HX57 Progress / handoff), then update the handoff." Model `/model sonnet`,
    `/effort medium`.
- [ ] HX58 (asked 2026-11-02, all platforms, test tooling, URGENT, not researched yet) Scale test on every device
  with a "promo" app: a build that runs on a huge fake account (far more chats, folders, archive, videos, series,
  downloads, watch history and cache than the owner has), because real users have much more than the dev account.
  Issue only, no work yet. When taken, look first at `SyntheticAccount` (`ui/src/jvmTest`, see `HomeScaleTest`)
  and the preview build type (`com.tmplayer.test`), and extend them instead of a parallel fake. Plan per platform
  first (phone, TV on the slowest Mi TV, desktop on Windows/Linux/macOS, web) with: sizes to test (3,000 chats with
  folders and an archive, 100,000 videos, 20,000 in one chat), first content within 3 s, idle CPU on the TV after
  Home/lists, bounded memory and disk caches, FLOOD_WAIT waited out, scroll and focus (D-pad) on huge lists,
  Android 6 low-RAM devices, and screenshots per form factor. Decide then whether it is a build flavor with a
  fake TDLib engine (no real login, safe to demo and to share) or a debug-only switch. Also useful for promo
  screenshots, so keep the fake titles free of real film names (TMPlayer wording rule).
- [ ] HX59 (asked 2026-11-02, all platforms, UX safety, URGENT, review only, no fixes yet) Some destructive actions
  have no confirmation prompt (Clear history is one the user named). Audit every destructive action on phone, TV,
  desktop and web, and add the missing prompts. Quick first pass (2026-11-02, grep only, not verified on screen):
  Android has dialogs for Clear history, Clear Watch later and Clear favourites (`BrowseScreen.kt` ~1203, 1220;
  Settings `Prompt.ClearHistory`). Desktop `Watched.kt` ~104/255 and `MediaGrid.kt` ~655 `clearAll` run straight
  to an Undo toast with no dialog, so Clear history and Clear Watch later on desktop are the likely gap; web
  (`webapp`, Settings `web.storage_clear_cache`, "Clear watched", bookmark delete in the player) not read yet.
  Also check: Clear recent searches (`MediaGridScreen.kt` ~335, one tap, low risk), Forget the last chat, Forget all
  learnt languages, Clear posters and details, Clear online subtitles, Clear recently played videos, Clear
  pictures and previews, Hide/Remove chat from TMPlayer (`MainActivity.kt` ~1958, `Chats.kt` ~266), stopping or
  removing a paused/queued/running download, Delete bookmark, Remove PIN (asks the PIN, probably enough), reset
  of size limits and other "Reset" buttons, and sign out. Rule to decide when taken: a dialog for anything that
  loses data the user cannot get back in one tap; a pointer or touch screen may use Undo instead only for single,
  cheap items, never for "clear all". The dialog says what goes, what stays (nothing leaves Telegram), and for the
  TV remote the focus starts on Cancel. Reuse the existing confirm dialog component (`BrowseScreen` dialogs,
  the `confirm.*` strings in `en.json`) and add one Compose desktop and one web equivalent, not a new one per
  screen. Tests: a focus/D-pad test per dialog, and a test that every `clear*`/`remove*` call site is behind a
  prompt where possible. New text goes in `en.json` only.
- [ ] HX60 (reported 2026-11-02, desktop, BUG/UX, URGENT, not researched yet) The TMPlayer logo looks too big on
  the desktop app, bigger than it should be. Issue only, no work yet. Not yet known which spot (title bar, sidebar,
  sign in, About, loader); grep the logo use in `desktop/src/main/kotlin/com/tmplayer/desktop/ui/` (`Shell.kt`,
  `SignIn.kt`, `About.kt`) and `Main.kt`, ask for a screenshot if unclear, and size it from a shared token, not a
  per-screen number. Check at 100%, 150% and 200% scale and a small window, then the phone and web equivalents
  for the same mismatch.
- [ ] HX62 (asked 2026-11-02, all platforms, product/UX, URGENT, not researched yet, no work yet) Search must stay
  inside the user's own chats and their videos, like the rest of TMPlayer, and never reach out to find new chats,
  channels or people the way Telegram's search does. Starting points found by grep (not verified on screen):
  `findByUsername` in `ChatRepository.kt` (~475) calls `searchPublicChat`, which looks a public @username up on
  Telegram's servers and so finds chats that are not in the list; `searchAllChats` (~315) is one account-wide
  `searchMessages` that covers every chat on the account, including chats the user did NOT pick in "Add more
  chats" (check that results are filtered to the chosen chats, and to Hidden/removed ones too, since the
  `hide_chat_detail` string promises they leave search); web `webapp/src/tg/library.ts` `searchGlobal` (~431, 584)
  has the same question. Also read `GlobalSearchViewModel.kt` and the "Search chats" scope on phone, TV, desktop
  and web. Rule when taken: a result is a chat the user already has in TMPlayer or a video in one of those chats;
  no username lookup, no public chat or contact search, no "search Telegram" fallback; the empty state points to
  "Add more chats" instead (cf. the existing `search_videos_none` text). Check the kids lock still applies, that
  a large account (`SyntheticAccount`, `HomeScaleTest`) keeps the first results within 3 s, and that old recent
  searches (e.g. an @username) do not break. Only `en.json` text changes. Related: HX29.
- [ ] HX63 (reported 2026-11-02, desktop on a laptop, BUG/UX, URGENT, not researched yet, no work yet) Laptop
  touchpad gestures do not work in the desktop app, such as a swipe to go back; "and others" is not listed yet, so
  ask which ones (two finger sideways swipe for Back/Forward, pinch, three finger swipes, edge swipe) and on which
  OS and compositor (Linux X11 or Wayland on sway, Windows, macOS). Found by grep (not verified): on Linux X11,
  `os/X11SideScroll.kt` (installed from `Main.kt` ~222) deliberately turns a touchpad's sideways swipe into a
  sideways scroll, because the JDK maps it to Back and a burst of swipes went back through every screen; so
  swipe-to-go-back is off on purpose there and only the mouse's side buttons go Back (`ShellState.kt` ~657
  handles `isBackKey`). On Wayland (sway) the toolkit may deliver nothing at all, and macOS/Windows are not
  read yet. When taken: decide per OS what a gesture does (e.g. a sideways swipe goes Back only on a page that
  does not scroll sideways, once per gesture with a lock-out so a burst cannot walk back through every screen;
  pinch zoom in the player is PZ1), keep the Home carousel and rows scrolling sideways (`HomeCarousel.kt` ~134),
  and add a machine test for the gesture-to-action mapping plus a hands-on list for Linux, Windows and macOS
  (Windows touchpad in the win11 VM). Nothing may break the mouse Back/Forward buttons.
- [ ] HX64 (asked 2026-11-02, all platforms, feature, URGENT, not researched yet, no work yet) Report a problem and
  Request a feature inside the app, not only by mail or a GitHub issue. Today (found by grep, not verified on
  screen) "Report a problem" (`feedback.*` in `en.json`, `Feedback.kt` `ISSUES`, `LanguageAndFeedback.kt`, TV QR
  code) only opens a prefilled GitHub bug report or a `mailto:` to hello@tmplayer.org, so a user without a GitHub
  account, or on a TV or desktop with no mail program, drops out; and there is no "Request a feature" at all.
  Look first at `Feedback.kt`, the Send logs flow (logs already have names, numbers and file names taken out),
  `JobFailures` / "Try again and Send logs" cards, and the crash-report switch in Settings, and extend them
  rather than add a parallel one. Decide when taken (ask the user once, in a batch): where the report goes (a
  small endpoint, e.g. a Cloudflare Worker that files a GitHub issue or sends mail, with no secrets in the app and
  rate limits against spam) or a hosted form, whether a user can attach a screenshot and the redacted logs (opt in
  per report, shown before sending), an optional reply contact (never required), and a status shown after sending
  (sent, queued offline, failed with Try again). Edge cases: offline (keep the draft and send later), a huge
  message, a double tap on Send, the app killed mid send, the kids lock, TV remote and focus (a text field on a TV
  is slow, so offer the phone QR route as a second way), desktop and web equivalents, and privacy copy that says
  exactly what is sent. Keep the GitHub and mail routes as fallbacks. Per platform plan first (phone, TV, desktop,
  web), text in `en.json` only, and a garbage-input test for any new parser. Publishing the endpoint
  (`wrangler deploy`) is the user's to run. Related: HX62, HX63.
- [ ] HX65 (asked 2026-11-02, desktop, UX, URGENT, not started, no work yet) Remove the left and right arrow
  buttons on the desktop Home carousel. Found by grep (not verified on screen): `HomeCarousel.kt` ~359-373 draws
  two `ArrowButton`s (`TmIcons.ArrowBack` / `ArrowForward`, labels `homeRowScrollBack` / `homeRowScrollOn`) shown on
  hover, focus or touch (`ArrowSlot`, `TOUCH_ARROWS_MS` ~195). Before removing, check what is left to turn the
  pages so desktop is not worse: arrow keys, the touchpad two finger swipe, Shift plus wheel, a tilting wheel
  (KDoc ~134), a drag, and the page dots/indicator; a mouse-only user with no horizontal wheel needs one of them
  (dots clickable, or click and drag), and touch screens on Windows laptops lose their only visible control.
  Keep the other rows' scroll arrows unless told otherwise (ask once: only the hero carousel, or every row on
  desktop?). Dead code goes with it (`ArrowSlot`, `ArrowButton`, touch timer, unused strings only in `en.json`),
  and the TV and phone carousels are not touched. Tests: update `BrowseRenderTest` shots and add a check that the
  pages still turn by key and by dots. Related: HX54, HX55, HX63.
- [ ] HX66 (asked 2026-11-02, desktop, UX, URGENT, not researched yet, no work yet) The "Recently added" row on
  the desktop Home shows too few videos, when there could be many more different ones (other chats, other kinds,
  other days). Not yet found which part is too small: the number of cards in the row, how far the row reaches
  back, or the variety (one chat or one series filling the row). Strings: `row_recent_all` / `home_recent`
  ("Recently added") and `home_chats_note` in `en.json`, settings in `HomeSettings.kt` (`layout.recentlyAdded`);
  grep the row's builder in `ui/src/shared/.../browse` and the desktop Home before guessing. Ask the user once for
  a screenshot and what "more variations" means (more cards, mixed chats, mixed kinds, a See all page?).
  Look first for what already covers it: the Chats page and "Recently added" in other places so nothing is built
  twice (the 2026-10 merge of "Recently added" and "add more chats"). When taken, plan per platform (phone, TV,
  desktop, web) and decide the rules: a larger page or an open-ended row that pages in as you scroll, spread
  across chats so one chat cannot fill it, dedupe (the same video in two chats, series episodes grouped), and a
  See all link. Keep the scale rules: first content within 3 s, TDLib calls budgeted, no endless paging on idle
  (a row that kept paging held a 1 GB TV stick at 150% CPU), checked on `SyntheticAccount` (`HomeScaleTest`)
  with a large account, and a focus/D-pad test on TV. Related: HX55, HX29.
- [ ] HX67 (asked 2026-11-02, tooling, URGENT, TODO: fix langsync so it is used from the next release on, not
  researched yet, no work yet) langsync failed all 2,680 translations on 2026-11-01 (rate limit, no shared limiter
  across parallel locales, no Retry-After handling, no circuit breaker, exit 0 on total failure in 0.10.0).
  The user thinks the translate API's limits and terms have changed since, so START by finding out what the API
  allows today (current limits, headers, pricing, model) before changing any code. Then: (1) check the installed
  version against 0.11.0 (fixes the error misclassification and exit code); (2) run a tiny test (one locale, a
  few keys) and read the real error and headers; (3) fix in `/home/dracu/projects/personal/langsync-cli`: one
  shared limiter across locales, honour Retry-After, a circuit breaker that stops after N straight failures,
  a non-zero exit when anything failed, and the heavy test spec in `docs/rate-limit-failure.md`; (4) dry run on
  this repo's 20 catalogs with the config in `private/langsync.json`, and make sure the `.langsync-state.json` it
  writes lands outside `core/src/commonMain/resources/i18n/` (it breaks three `CatalogTest` tests there); (5) after
  a run: key sets, ICU args, plural cases, no em or en dashes, script matches language, spot check ml, bn, ar, fa,
  ja. Why: it saves tokens on every release compared with translating by hand; small batches (a few strings)
  stay with Claude directly. Only the user asks for a langsync run (global rule), so this item ends with a
  command for the user to run. Related: memory `langsync-fails-at-scale`.
- [ ] HX68 (asked 2026-11-02, site/SEO, URGENT, plan only, no work yet) Search Console follow-up from the export of
  2026-11-02 (28 days, 2026-10-03 to 10-30; 2,470 clicks, 13,238 impressions; copy kept outside the repo, re-export
  before starting). Read `docs/SEO-PLAN.md` first and extend it, do not start a parallel plan. Facts: most clicks are people
  typing the name ("tmplayer", "tm player", about 1,560 clicks, position 1); few find us by what the app does. 2026-10-30 alone
  had 1,590 clicks (a one day event), so judge from the other days (about 60 to 80 clicks a day). India 64% of clicks;
  87% mobile. Work, in order:
  1. Titles and descriptions of pages that rank 1 to 2 but get almost no clicks: `/android/` (814 impressions, CTR
     1.2%), `/install/` (472, 1.3%), `/desktop/` (1,101, 2.3%), `/guides/external-player-for-telegram/` (376, 2.1%),
     `es/guides/telegram-videos-on-fire-tv/` (238, 2.1%). Say what the page gives in plain words; keep "Telegram Media
     Player" in the title (CP72), TMPlayer wording rule, no dashes.
  2. `web.tmplayer.org/` still shows in Google (1,436 impressions, CTR 1.5%, position 2.4) and competes with
     `tmplayer.org/web/`. Decide redirect vs canonical once the Solid web app is live (see HX6 rollback note: the old
     deployment stays until then). Not before.
  3. Italy (911 impressions, CTR 2.7%) and Brazil (777, 2.2%): check the `it` and `pt` titles and descriptions.
  4. Non-brand queries to grow: "telegram video player" (326 impressions, CTR 39%), "telegram player" (384, CTR 9.9%,
     position 4.1), "telegram media player", "telegram player for pc/windows", "telegram android tv" (59, CTR 3.4%,
     position 7.3). Add or sharpen content for these; sourced alternatives content, honest (see Outrank TMP honestly).
  5. Re-export Search Console about 2026-11-12 (CP72 measure date) so the 10-30 spike leaves the window, compare
     CTR on the pages in step 1, and record the result here.
  Only `site/` changes; site strings in `site/i18n/*.json` follow the site README, no langsync. Run the site build and the
  dash grep before committing; the user runs any deploy.
- [~] PZ1 (asked 2026-11-01, feature; phone and desktop done 7ea17d4 3036732 54b2036 bc7621a, web open) Free pinch zoom in the player, VLC style. Phone: 100 to
  400 per cent with the fingers around the focal point, a "Zoom n%" chip, pan when zoomed (clamped), snap to fit
  under ~105%, Reset zoom in More options; resets on a new video and a shape change; PiP shows the whole picture;
  none in 3D/VR or lock; subtitles never zoomed. Desktop: Ctrl+wheel (and touchpad pinch where delivered) via
  mpv video-zoom/pan, drag to pan, keys. TV: skipped (no touch, shapes on the remote, arrows are seek and focus).
  Web: open (`Chrome.tsx` pinch steps `pinchShape`): CSS scale/translate on the video from the shared PictureZoom,
  subtitles outside the transform, same resets, skip in iOS native full screen. Android 6 keeps the old shape
  steps (SurfaceView ignores view transforms before API 24, `videoFollowsTransforms()`); quick scale (double tap
  and drag) is off so double-tap seek runs stay clean; desktop keys Alt+= / Alt+- / Alt+0, touchpad pinch only on
  Windows; PGS/VobSub subtitles still zoom with the picture on desktop. Due: phone pass (hardware and FFmpeg light
  mode, pan clamp, rotate, PiP, TalkBack, subtitles), Linux Ctrl+wheel, Windows touchpad in the win11 VM.
- [x] HX42 (GitHub #16, reported 2026-10-28, STILL BROKEN on 1.28.2 as of 2026-10-30, TV, BUG, fixed 2026-10-31,
  not pushed) "Can't see where my current selection is" on Home's banner, Mi TV box. The
  reporter was asked for a video on 2026-10-31; it is not needed, the cause is in the code and it is three
  things, all found 2026-10-31:
  (1) `HeroPill` (`ui/src/shared/.../browse/HeroPill.kt:45`) says where the focus is ONLY by growing
  `Motion.FocusScale`, which is `1.05f` (`ui/src/shared/.../theme/Motion.kt:40`): under 3 px on a 56 dp pill,
  seen from a sofa. Its KDoc says why: "no accent fill and no ring (user, 2026-10-22), the scale alone says
  where the focus is."
  (2) Worse, that scale is gated on `!still` where `still` is `rememberReducedMotion()`, so with Reduce motion
  on, or a television with animations turned off, `scale` stays `1f` focused or not: NO focus mark at all. That
  is the report exactly.
  (3) `Modifier.focusRing` (`ui/src/shared/.../theme/Palette.kt:128`) draws only when `!LocalDarkTheme.current`,
  so the app's own ring helper is a no-op on a dark themed television. `TvHomeCarousel.kt:87` imports it and
  never uses it.
  The second half of the report, "Left or Right needs two presses to change the title", is the SAME cause, not
  a second bug: Left is handled on Play and Right on Details (`TvHomeCarousel.kt:222-241`), so from Play the
  first Right only moves the focus to Details and the second turns the page. With the focus invisible, press one
  reads as nothing happening.
  Decision asked and answered 2026-10-31: ring/fill, dark-theme aware, reversing the 2026-10-22 "scale alone"
  call (user picked this over a reduced-motion-only fallback or leaving it).
  Fixed, following the pattern `DetailHero`'s own Play pill already shipped (fill swaps to `Tone.focusFill` on
  focus, plus `Modifier.focusRing`), rather than inventing a new one: (1) `HeroPill` (used by both the
  first-run card's button and the TV carousel's Play) now animates its fill to `Tone.focusFill` and its ink to
  `Tone.onFocusFill` on focus (was a fixed `Tone.hero`/`Tone.onHero` with only the scale changing), and adds
  `.focusRing(focused, CircleShape)`. (2) `TvDetailsPill` (`TvHomeCarousel.kt`, private, so fixed separately)
  gets the same fill/ink swap and ring. (3) `Modifier.focusRing` (`Palette.kt`) no longer skips the dark theme:
  it draws `Tone.onFocusFill` there (Material's own contrast-guaranteed ink for `Tone.focusFill`, which is what
  every `focusRing` caller's fill becomes when lit, so a ring in the same hue as the block it sits on, which
  was the reason it used to skip dark theme, no longer applies) instead of `Tone.accent`; light theme keeps
  `Tone.accent`, unchanged. This also fixes `TopicTabs`' own dark-theme blindness the same bug described ("lit"
  from being chosen alone looked identical to being chosen AND focused, since the ring that tells them apart
  never drew in the dark).
  The scale still runs for everyone it already worked for; it is additive, not replaced, so no reduced-motion
  or dark-theme viewer is worse off either way. Checked every `focusRing` caller (`TopicTabs`, `MediaDetail`,
  `FreeUpSpace`, `DetailHero`, `SetupChoices`, `ShowMenu`, `PinPad`, `Copies`, `AppearanceStep`, `Series`): all
  pair the ring with a `Tone.focusFill`-class fill already, so the new dark ring colour is right for every one
  of them, not just HeroPill's.
  Verified live on a TV emulator (`tmplayer_promo_tv`, real D-pad `adb input keyevent`, not Robolectric): the
  login screen's own focusFill+ring pill lit up correctly moving the remote onto it, confirming the mechanism
  this fix reuses. Did not verify the Home carousel's own Play/Details pills live (needs a signed-in account,
  which the promo TV AVD was not); a Robolectric render test hit an unrelated Compose touch-mode quirk
  (`VideoTile` needed its own `holdOrPress` modifier instead of plain `.clickable()` for exactly this reason,
  a real TV has no touch mode to race) rather than proving anything about the fix, so it was discarded rather
  than kept failing. `:ui:compileKotlinJvm`, `:app:compileDebugKotlin`, `:desktop:compileKotlin` and
  `:ui:jvmTest`'s theme tests clean. Worth a real-TV screenshot next time one is signed in.
- [ ] HX43 (GitHub Discussion #10, asked 2026-10-18, feature, after HX27) Tags for videos, across chats. The
  reporter filters tutorial videos out of busy chats and wants to label videos into categories, by hand if
  need be, so they can be found again to rewatch. The reply on the discussion asked whether this means
  bookmarks: it does NOT. `Bookmarks` (`core/src/pureMain/.../Bookmarks.kt`) is a moment INSIDE one video; this
  is a label on a whole video, shared across chats. Plan: a local store keyed by video in the shape of
  `WatchLaterStore` (a record per video plus a `*Book` twin so the web app reads the same keys, in the backup
  list and `BackupCoverageTest`), a pure rules object for the tag text (trimmed, folded for matching, a length
  cap, a cap per video, no duplicates), tag chips in the detail sheet to add and remove, a Tags row or filter
  where the viewer browses, and `#tag` understood by search. Stays inside the HX41 boundary: local only, nothing
  fetched, no catalogue. Edge cases: a tag on a video in a chat later removed from the added chats (HX27) or
  deleted in Telegram (the tag stays, the video is not listed); renaming and deleting a tag everywhere at once;
  the same tag typed with different case or accents; 500 tagged videos; the television's remote entering text
  (reuse `TvSearchField`'s keyboard path); per platform, phone, TV, desktop and the web app.
- [x] HX44 (2026-10-31, 21b42a6, fixed) Refresh on the chat list was slow and almost pointless; the gap is
  closed and the button is gone. The user asked "why reloading on on phone others take a lot of time? if
  it's not needed for tdlib and auto appears chats and stuff then maybe remve that button?".
  Re-read fresh against the main tip this landed on (HX27, HX30 and HX32 had all shifted the file since the
  plan above was written): `watchChatChanges` (`BrowseViewModel.kt`) already kept the list live from TDLib's
  own updates (title, photo, position with pin and archive move, unread badge, mute), and `reorder` already
  dropped a chat TDLib no longer lists. The one gap was fetching a chat that had only just appeared, left on
  purpose per `reorder`'s own KDoc ("chasing it here would put a `getChat` fan-out behind an update that
  fires on every incoming message"). Fixed: a position update naming an id this screen has never shown is
  now queued and read with one `getChat`, on the same settle debounce `reorder` already waits on, capped at
  `NEW_CHAT_FETCH_CAP` (20) per settle window so a sign-in's burst of new chats costs a handful of round
  trips rather than a fan-out; an id that leaves the list again before its turn is dropped from the queue
  rather than fetched (no flicker), and whatever a burst could not fit in the cap is caught by a later burst
  or the next full sync.
  With that gap closed, the explicit Refresh is gone from everywhere it stood: the television's profile menu
  (its item is now Home-only, which handles the real Home refresh, and Chats/Favourites/History get none),
  the desktop's toolbar button (F5 and Ctrl+R still work, unlabelled, exactly as Home's already do), and the
  chat sync Home's own refresh used to piggyback is removed too. HX32's "Some chats could not load. Try
  again" foot and the first sync's own retry are untouched, since both are real failures rather than habit.
  Decision on the phone's pull to refresh: kept, as a cheap reread for the viewer who pulls out of habit;
  unlike a persistent button it costs nothing idle and many viewers expect the gesture to work regardless.
  Step 3 (a refresh re-reading only the added chats under HX27's Picked mode, leaving the 4000 deep archive
  walk to the picker) is NOT done: the Add chats picker reads `ChatListViewModel.everything` for its full
  account view rather than doing its own fetch, so narrowing the regular sync to the added ids would starve
  the picker of every chat the viewer has not already added. Left as a TODO at `everything`'s declaration:
  giving the picker its own `ChatRepository`-backed sync (its own loading state, used only while it is open)
  is what would let the regular sync drop the deep archive walk under Picked mode. Bigger than this task's
  one-sync-call swap, and worth doing carefully rather than risking the already-shipped, scale-tested
  HX27/HX30 picker.
  Compiled core, ui, the Android app, desktop and the webapp (tsc) clean; `:core:jvmTest` and `:ui:jvmTest`
  (`ChatListLoadTest`, `AddedChatsTest`, `ChatPickingTest`, `ChatSuggestionsTest`, `AppLockTest`,
  `HomeScaleTest`, `ChatPickerScaleTest`, `ChatListImmediateStartTest`, `ChatListFirstVisitTest`,
  `ChatChipsTest`) green; `:app:testDebugUnitTest` (`AddChatsRenderTest`, `TouchChromeRenderTest`) and
  `:desktop:test` (`BrowseKeyboardTest`) green.
- [x] HX45 (reported 2026-10-31, chats, BUG, URGENT, fixed and verified 2026-10-31 against the real "MarkS"
  chat on the POCO F5). Root cause, found by pulling the real file names/captions via a temporary debug
  build + `Log.i` + `adb logcat` (the real app had to be uninstalled and the user re-logged in via QR to
  install a debuggable build; reinstalled as release afterward is not needed since the final fix shipped in
  a normal build):
  (1) "Prince of Mollywood" (3 copies, identical file name, `S01 EP(29-32) COMBINED`): `MediaName`'s episode
  parser had no pattern for a parenthesised combined-episode range ("EP(29-32)"), only "S01E29-E32" and
  "S01E29-32" glued forms (`PAIRED_EPISODE`) and a single-number "EP04" form (`EPISODE_ONLY`). So `episode`
  parsed as null while `season` parsed as 1 (from "S01" alone), making `isSeries = true` but `isEpisode =
  false`: `SeriesShelf.candidateKey()` then had no key to give it at all (`filmKeyOf` refuses series items,
  the loose episode-key path requires a non-null episode), so the three copies could never be recognized as
  versions of one file. Fixed: added `MediaName.EPISODE_RANGE` and used it in `findEpisode()`, so "EP(29-32)"
  now reads as `episode = 29, lastEpisode = 32`; the three copies now fold into one "3 versions" card. Test:
  `MediaNameTest` `a combined-episode release written as EP(29-32) opens on the first and knows its last`.
  (2) "Doraemon New Nobita And The Castle Of The Undersea Devil" (2 copies, 1080p/720p, identical file name
  truncated by Telegram to "...Devil (20.mkv" with no closing year and no quality tag left in the visible
  name): `SeriesShelf.filmKeyOf()` refused to key ANY film with no year and no `nameMarkers` (1080p/x265/etc
  in the name), even when the title was an exact, specific, multi-word match for another copy of itself, so
  `candidateKey()` returned null and the pair could never be compared by `SameVideo` at all. Fixed: the guard
  now also accepts a multi-word, non-generic title as "specific enough" to key (`' ' in key &&
  !item.genericFileName`), leaving the original single-word guard ("Lecture", "Video") and `genericFileName`
  camera-roll guard (`VID_20241013_120000.mp4`) in place; `SameVideo`'s own evidence (duration/size/name
  match) still decides whether two same-keyed items actually fold, so this only lets real duplicates into the
  comparison, it doesn't force a merge. The pair now folds into one "2 versions" card. Tests: `SameVideoTest`
  `a film re-posted with no year or quality marker still folds by its specific title` and
  `a single generic word still never folds two unrelated clips` (guard still holds). Full `:core:jvmTest` and
  `:ui:jvmTest` pass (1956+ tests).
  NOT fixed, and not safely fixable by a name heuristic: the real "MarkS" chat also has a 3-copy
  "@MovieeManHD Doraemon Nobita and the Castle of the Undersea" set (now correctly evaluated by `SameVideo`
  but genuinely different durations, so correctly stay 3 separate cards, confirmed live via logcat across
  several runs), a "Doraemon Nobita and Castle of UnderSea Devil (2026) Tamil" with different wording, and an
  "@TamizhCinemaz - Doraemon Nobita and the Castle of t" truncated at a different point: three more spellings
  of the same film that the grouping genuinely cannot tell apart from an unrelated title without risking a
  false merge elsewhere, so the "6 Doraemon tiles" the screenshot showed are now 5 (one real fold applied),
  not 1. Flag to the user if they want a riskier fuzzy-title merge for this case.
  The "shows marked Missing episode that is really under a duplicate tile" side effect (HX45's second
  paragraph) was not reproduced in this session's real data (the two confirmed cases are a combined-episode
  release and standalone films, not an actively-numbered show with missing episodes): still open, re-check
  next time a concrete example turns up.
- [x] HX46 (reported 2026-10-31, cards, BUG, URGENT, fixed 2026-10-31). Two card bugs seen together on
  duplicate-looking entries:
  (1) Title drawn twice, fixed: `Series.kt:1007`'s `MediaArt(...)` call now passes `showCoverTitle = false`,
  matching every other caller.
  (2) Half-blurred landscape cards. User chose "crop instead of Fit" over softening the blur/dim. Fixed in
  both `MediaArt.kt` (`Picture`/`DrawnPicture`, the 16:9 grid tiles) and `PosterArt.kt` (`WidePicture`, the
  2:3 poster cards): both now always crop a landscape thumbnail to fill the frame, same as every other shape.
  Removed the now-dead `letterboxes()` rule, `LETTERBOX_LOSS`/`LETTERBOX_DIM`/`LETTERBOX_BLUR` constants,
  `drawFitted()`, `Thumbnails.backdrop`'s soft-field compositing in `PosterArt.kt`, and the `MediaArtRuleTest`
  that only tested the removed rule. `:ui:compileKotlinJvm`/`:ui:compileTestKotlinJvm` both clean.
- [x] HX47 (asked 2026-10-31, cards, follow-up of HX46/the dark-gradient question, fixed and verified on the
  POCO F5). Two polish items the user asked about after HX45/HX46:
  (1) Fallback gradient read as plain black on an OLED/"Pure black" screen. `NameColour.Pairs`
  (`core/.../NameColour.kt`) is the shared 8-pair palette `CoverArt.Pairs` and the chat-avatar colour both
  read from; the dark stop of each pair was near-black (contrast vs white 15-17.5:1, far past the documented
  6.9:1 floor). Lifted every dark stop 65% of the way toward its light stop (computed, not eyeballed: worst
  case after the lift is still 9.34:1, well clear of 6.9:1; light stops untouched since one, olive, was
  already at the 6.9:1 floor). Kept in sync across all three copies the codebase carries (the file's own doc
  comment says to): `core/src/pureMain/kotlin/com/tmplayer/data/NameColour.kt`, `webapp/src/ui/colour.ts`
  (chat avatars) and `webapp/src/screens/cover.ts` (a separate, undocumented third copy found while fixing
  this, same 8 pairs, now also updated). `NameColourTest`'s pinned first-pair value updated to match.
  (2) Tried, then reverted on the user's own second look: blurring the low-res inline preview in the grid
  tiles (`MediaArt.kt`)/poster cards (`PosterArt.kt`) while the sharp thumbnail loads. User decided raw,
  unblurred is better there after seeing it ("for video cards maybe show that low res one instead of
  blurring"); both files are back to the exact HX46 state (confirmed via `git diff HEAD`), no `MiniPreview`/
  `PosterPreview` wrapper, no blur.
  (3) The real bug the user actually meant: the DETAIL PAGE's backdrop (`ArtBackdrop.kt`, used by
  `MediaDetail.kt`/`DetailHero.kt`/`WideDetail.kt`/`Copies.kt`) and the HOME CAROUSEL's
  (`EdgeBackdrop.kt`, used by `app/.../HomeCarousel.kt`, `TvHomeCarousel.kt`, the desktop `HomeCarousel.kt`)
  both drew a small SHARP inset of a too-small Telegram thumbnail over a separately blurred/soft field, which
  read as "a tiny light picture sitting oddly on a blurred backdrop," confirmed on the real
  "@MovieeManHD Doraemon Nobita..." item's own detail page (screenshot, grayscale 32px field + sharp small
  logo in the corner) and reasoned through for the carousel (narrow/portrait frames there had no cap or blur
  at all, stretching a low-res thumbnail across the whole banner sharp). Fixed both: `ArtBackdrop`'s
  `step.field != null` branch no longer draws the `SmallPicture` inset at all (removed `SmallPicture` and its
  three now-dead constants), just the field full size, now always blurred (`STAND_IN_BLUR`) rather than only
  when it was TMDB's tiny stand-in. `EdgeBackdrop` keeps its existing small/capped/vignette sizing (a
  different, already-careful design, left alone) but now blurs whenever the shown picture isn't the real
  sharp one (`lowRes = standIn || picture.width < SHARP_MIN_WIDTH`), not only the TMDB-tiny-stand-in case.
  `BackdropArt.blurred` is gone (nothing read it after both call sites moved to their own "is this low-res"
  check). `:core:jvmTest`/`:ui:jvmTest` and the webapp's `node --test src/ui/colour.test.ts` all pass;
  verified visually on the POCO F5 that the detail page's backdrop is now one soft field, no small sharp
  inset. The carousel fix is code-reviewed and traced but not screenshotted: the Home carousel's Featured
  picks on this account all had real posters during this session, so no low-res carousel card came up to
  photograph; the EdgeBackdrop code path is the same one the detail page used before the fix, so it is
  expected to look right next time an unmatched title is featured.
- [ ] HX24 (reported 2026-10-29, metadata, NEXT) Wrong poster and details for a same-name title. The
  Malayalam "Bigg Boss" in the chat "Deonnn" (S08E50 to E53) shows TMDB's Tamil "Bigg Boss" (2017,
  Vijay Sethupathi poster, Tamil episode names) on the POCO phone, 1.28.2. Cause: TMDB has several
  shows named "Bigg Boss" (Tamil, Malayalam, Telugu, Kannada); all pass `MetaMatch.accepts`, and
  `MetaPick.score` only adds +2 when the file name or caption names a language
  (`MetaQuery.languages`), so with no language word TMDB's first answer wins. Today a match can only
  be forced by `{tmdb-<id>}` in the file name or caption. Ask the user for the raw file names first:
  if they do say "Malayalam" or "Mal", the hint path has its own bug. Fix, in order:
  (1) language hints from the chat's title, description and nearby captions, scored against TMDB's
  `original_language` (a hint, never a reason to accept);
  (3) ambiguity: two or more same-name candidates in different original languages that nothing
  (year, language, runtime) separates means unsure: show the file's own name and a "Pick the right
  one" choice instead of a guess;
  (4) "Wrong match? Search again" on the details page: TMDB's top results with year, language and
  poster, saved per chat and series so every episode follows, "Use the file's name", undo, kept in
  Backup, cached wrong match cleared. Check whether the existing `round_wrong_match`,
  `round_use_match` and `matched_as` strings back a flow to reuse;
  then (2) the audio track's language tag ("mal", "tam") read at first play to re-match, and (5) drop
  a show match when TMDB has no such season or episode.
  Shared core code, so phone, TV, desktop and web; the picker needs each platform's layout. Edge
  cases: dual-audio files, chats mixing languages, a fix for one series or the whole chat, undoing a
  manual pick, old wrong matches already cached, TMDB offline. Tests in `MetaPickTest`: two
  same-name shows in different languages, a chat hint picks the right one, no hint means no match,
  the year still wins where it separates; `GarbageInputTest` for any new parser. Opus, high effort.
- [ ] HX25 (asked 2026-10-29, subtitles, NEXT after HX24) An online subtitle, once downloaded, comes
  back by itself: the user should never search and download the same subtitle again. What exists
  (checked 2026-10-29): the files are already kept on the device for up to six months
  (`SubtitleCache`, `cacheDir/online-subtitles` on Android and desktop, keyed by provider and file
  id, a second download is free), and the web app keeps 200 files or 20 MB (`webapp/src/player/msone.ts`).
  What seems missing (verify first): nothing remembers WHICH online subtitle was picked for a
  video, so reopening it means opening the picker and choosing again. Do: remember the pick per
  video (file id plus the series and episode, so a re-post or another copy finds it too) and load
  it from the cache on open, before the preferred-language auto pick; show it as the selected
  track; "Off" is remembered too. Also check: Android may clear `cacheDir` under storage pressure,
  and Free up space or Low storage mode may purge it, so a picked subtitle (small) moves to
  `filesDir` or is excluded from those purges; Backup carries the picks, not the files; a video
  whose cached file is gone downloads it again quietly once (quota and key rules unchanged), and
  says so if it cannot. Phone, TV, desktop and web. Cloudflare (answered 2026-10-29): videos never
  pass through Cloudflare (TDLib fetches them from Telegram). Only TMDB lookups for the web app
  (`webapp/tmdb`, Cloudflare's cache) and the MSone search gateway (subs.tmplayer.org, search
  metadata only; files come from MSone, OpenSubtitles and SubDL directly) do. No server-side
  subtitle cache is needed for this: the device cache is the one that saves the user's downloads.
  Download counts (user, 2026-10-29: "their download count won't be increased unnecessarily"):
  the device cache already means a second download of the same file spends nothing from the
  OpenSubtitles daily quota (`download()` returns the cached file before any call). Check that
  every path goes through it: auto pick, the episode list's next episode, a re-post or another copy
  of the same video, the web app, and a cache emptied by the system. Do NOT add a shared
  Cloudflare cache of subtitle FILES: one user's download served to others is redistribution,
  OpenSubtitles' API terms count a download per user through their API, and MSone asked that the
  apps fetch each file from MSone themselves because that is what it counts
  (`MsoneApi.kt`). A shared cache of SEARCH answers at subs.tmplayer.org (metadata only, a short
  TTL) is fine and saves rate limit; check the gateway repo for whether it already does. Tests: a
  second open of the same video makes no provider call (fake transport counting calls), the
  cached file survives a restart, a deleted cache file re-downloads once.
- [x] HX26 (2026-10-31, downloads, built: phone and TV, desktop unchanged, web and a phone folder
  picker deferred) Choose the download location on every platform. Phone and TV share one Storage
  row (`DownloadLocationSettings.kt`), a plain volume list rather than a document picker: internal
  storage, or an SD card or USB drive Android already has mounted (`AndroidStorage.kt`, no
  permission needed, works down to the API 23 floor), since many TV boxes have no folder picker
  and the phone's own choice reduces to the same list. `DownloadRelocation.kt` and
  `DownloadRelocationService.kt` do the move: free space checked first, a confirm off by default
  with move or leave in place, cancel, a crash-safe pending move resumed at the next launch, the
  watch cache and TDLib's own folders never touched, Downloads still lists correctly after a move,
  and the chosen folder is left out of backups (`BackupCoverageTest`), since a path to this
  device's disk means nothing on another. Tests: `DownloadRelocationTest` (app module,
  Robolectric); `StorageRelocationPlanTest`, `BackupCoverageTest` and the i18n catalog test (core)
  still pass. Desktop already had `StorageSettings.kt` before this task, unchanged. DEFERRED, as
  separate follow-ups: a true system folder picker on the phone for an arbitrary shared folder
  (TMPlayer's downloads are plain files by path; writing through a SAF tree needs its own
  DocumentFile path, too large a change to land solidly in this pass), and the web half entirely
  (the File System Access API, or saying plainly that downloads stay in the browser), since web has
  no downloads settings surface yet to put either in. Reply to the requesting users when it ships.

- [x] HX27 (2026-10-31, built across phases 1-4 above, commits 883fb34..c2984d5) "Add chats" picker, modelled on
  TMPlayerMusic (`app/.../ui/onboarding/ChatPicker.kt`, `core/.../data/FirstRun.kt`, `ChatDirectory.kt`).
  FINDING: TMPlayer has no chosen-chats set. It shows every chat of the account's main list plus the
  archive; the only per-chat local state is Hidden chats (opt-out) and Favorites (opt-in, `favorite_chats`).
  So this is a new stored set (a `ChosenChats` store in `SettingsStore` and `SettingsBook`, in the backup list,
  `BackupCoverageTest`) that Chats, Home, search and the carousel read. Plan first, per platform:
  phone and TV (full screen picker, remote focus, the Back key), desktop (Ctrl+A, window), web (same shared
  screens). What the picker does: lists chats with a running total ("N chats, about M videos"), a video
  count under each title (placeholder bar until it arrives, so rows do not jump), search (accent folded),
  Select all and Clear on the filtered list, a confirm above 50 chats ("scanning many takes longer"), an
  empty choice allowed, Saved Messages first, archived chats in their own section, secret chats left out.
  RECOMMENDED: a chat, channel or group with at least X videos goes in a "Recommended" section at the top and
  is ticked by default. Proposal: X = 20 videos (a chat with fewer is probably chatter), at most 30 pre-ticked,
  most videos first; X and the cap are constants in one pure object (`ChatSuggestions`, with a test) so the user
  can tune them after seeing real numbers. Count with `getChatMessageCount(chatId, null,
  SearchMessagesFilterVideo, true)` (local only), then `false` (server) when it returns -1, 3 at a time,
  3 tries, a 15 s timeout, FLOOD_WAIT under 60 s waited out per call, a TTL cache (the pattern is in
  `ChatAudio.count` and `countedTopics`; no per-chat video helper exists yet). Documents sent as video files
  are not in that count; say so in the row copy like Music does. First content within 3 s: show the chat
  list and Saved Messages at once, counts fill in, the Recommended section appears as counts arrive and never
  re-orders a row the viewer is touching. Scale: test against `SyntheticAccount` (3,000 chats, 400 video
  chats, 20,000 in one chat), idle CPU on the TV after leaving it. Edge cases: no chat reaches X (show the
  top five by count instead), a chat left in Telegram after being chosen, unticking a chat only hides it
  here (never touches Telegram, nothing deleted from Downloads without a confirm), the same action twice,
  rotation, the app going to the background, sign out and back in, a revoked chat.
- [ ] HX28 (asked 2026-10-31, chats, after HX27) Existing users are asked once. They have no chosen set, so
  until they answer, the app keeps showing everything exactly as today (no one's library shrinks on update).
  One card or sheet, once per install, built on the `TourState.Never` precedent ("updated from a version
  without it, offer once", `MainActivity.kt` ~2269, desktop `Shell.kt` ~492) plus a `*_ANNOUNCED` key like
  `LanguageNotice` (pure rule + test, key in `SettingsStore` and `SettingsBook`, `rememberChatsNotice` on
  phone, TV and desktop; the web app too). The picker opens with the Recommended chats ticked AND the chats
  they already starred ticked, and a plain "Keep all my chats" button; "Not now" asks again after a
  fixed gap, then stops. Never in the player, during setup, sign in or with another window up. New installs
  get the picker as a setup step (after sign in), not the card. Settings keeps a permanent "Add more chats" row.
- [ ] HX29 (asked 2026-10-31, chats, after HX27) Telegram folders move inside "Add more chats" as a filter.
  Today folders are chips on the Chats page (`chatPageChips`, `BrowseSection.Folder`, `BrowseScreen.kt` ~574,
  desktop `Chats.kt` ~184) from `Td.folders` and `ChatRepository.folderMembership()` (loaded lazily, HX8).
  Plan: the picker gets a folder filter row (All, each folder, Archived, Channels, Groups, People) reading
  the same two sources; the Chats page drops its Folder chips (it then shows only the chosen chats; keep
  All/Unread/Channels/Groups/People filters if they still make sense). "Select all in this folder" ticks the
  visible folder only. Handle a stored folder selection and the old `takeOnlyFolders` migration, folders that
  vanish, a chat in several folders, 3,000 chats with folders (membership must not load before the picker is
  opened). Update `BrowseSections` tests and the render tests that assert the chips (`BrowseRenderTest`,
  `SelectedAndEmptyShotsTest`).
- [x] HX30 (2026-10-31, built across HX27 phases 3-4, commits a05a5c7/3206b5b/c2984d5) Chats list after the picker, from the Music review.
  (a) Colour: chats without a photo get a solid circle or tile with initials, the colour from a name hash over
  a fixed palette (Music: 8 colours, `Artwork.kt`); keep TMPlayer's own identity, not a copy (see
  ui-inspiration-not-imitation). Chats WITH a photo show that photo ONLY in the picker (user: "for the
  chats with images use that ones only for chats selection"); elsewhere every chat uses the coloured
  initials or the title art. Confirm this reading with the user before building. (b) Read-only toward
  Telegram: REMOVE pin, mute, archive and mark read from the chat menu (`ChatRepository.setPinned`,
  `setMuted`, `setArchived`, `markRead`, called from `BrowseViewModel.act`, `MainActivity` ~1855, desktop
  `Chats.kt` ~267) and their tests/strings. Keep what the user chose to do on purpose: resume sync into
  Saved Messages, joining by link, the sponsored message calls Telegram's terms need. Real Telegram pins and
  unread flags stay untouched; do not read them either for ordering. Old stored state that relied on the removed
  actions needs a migration check. (c) Favourites (the app's own star, local only) sort to the top of the
  chosen chats, a "Starred" section or pinned order inside the app, with the star toggle on the row and in the
  picker. Edge cases: 40 starred, a starred chat no longer chosen, the TV focus after a toggle.
- [x] HX31 (2026-10-31, 1fc406a, fixed) Prefixes like `[CC]`, `@CC`, `@CC_ALL` are not cut from names
  on the user's many movie channels. Discovery: `MediaName` (`core/src/pureMain/.../MediaName.kt`) already
  strips a leading `[Group]` (`GROUP_TAG`, not a year), `@handle` (`HANDLE`, `handleOf` for run-on names), site
  domains and emoji; no rule names `CC`/`CC_ALL` and no chat-TITLE cleaner exists (a new pure object beside
  `Usernames`, `ChatInfo.kt`). So first find which are failing and where (file names, captions, chat titles).
  Method: run the app's own `MediaName.parse` over the real names of the signed-in account (read the chats from
  the debug app or a desktop build and dump title, file name and parsed title to a scratch file; no phone
  testing, see no-phone-testing), group the leftovers by shape (prefix, suffix, bracket, handle, separator,
  language word, quality word), and list every shape before changing a rule. Fix them all as general rules
  (not a list of channel names), add each shape to `NameCorpus` with INVENTED titles (the corpus rule), and a
  `GarbageInputTest` line if a new parser appears. Check the same names also reach `tileTitle`,
  `looseEpisode`, grouping (`SameTitleTest`) and the TMDB query (HX24). Re-run `HomeScaleTest` after.
  Run as two parallel agents max: one surveys real names, one writes the rules once shapes are known.

- [ ] HX38 (asked 2026-10-31, cards, with HX27) A card with many versions can show the wrong quality
 Checked on the POCO F5 (1.28.3), chat Cinema_paradasio, 2026-10-31: the grid card for "The Paradise (2026)" says
  1080p and "14 versions", its detail page opens a 720p 2.2 GB Hindi and Malayalam copy and says "11 versions", and the
  picker lists five 1080p copies (3.4 GB Tamil E-AC3 first) above that selected 720p one. So the badge, the count and
  the copy Play opens are three different answers, and the pre-selected copy is not the recommended one. (The count
  differs since the card folds posts the detail page does not, "posted twice" copies among them: use one fold.)
  Also seen there: the grid title "~SAW X" keeps its leading `~` (a HX31 prefix shape: `~`, and likely `-`, `.`, `_`,
  `*`, `#`), and the filter chip reads "22 hidden" (what is hidden and why must be visible, see HX32)., and the
  version Play opens should be the recommended one. Found in the code, nothing changed yet. (1) The card badge
  comes from `GlobalSearch.qualityOf` (GlobalSearch.kt ~503): the file NAME's tag wins ("720p" in the name),
  and the stated width and height are only a fallback. The details panel (`MediaFacts.read`) does the opposite,
  the stated size first, so a file named 720p that is really 1920 wide shows "720p" on the card and "1080p" in
  the panel: the wrong-badge case the user saw. (2) Folded copies are ordered by `GlobalSearch.copyOrder`
  (on this device, then that same name-first quality, then newest), and the lead copy is the badge and the
  Play target, so a mislabelled copy can lead and a better one hide behind it. (3) `SameVideo` and `SameTitle`
  fold on length, year and name, so a trailer, a dub or a cam copy can sit among the real versions. Plan:
  one `QualityRead` object (pure, tested, `NameCorpus`-style cases) that decides a copy's quality the same way
  everywhere: the stated size when Telegram gave it, else the name, else the file size per minute; a mismatch
  between name and size is kept as a flag, the size wins. A "recommended copy" order replaces `copyOrder`'s
  quality step: on this device, then the best the DEVICE plays well (the HEVC 10 bit judge from `DecodeJudge`
  for a stick that struggles, see HX11 and HX37), then the viewer's language (a Tamil copy before its Hindi dub when
  the title says Tamil, `MediaName.languages`), then quality, then the size the free space and mobile data setting
  allow, then newest. The card shows the recommended copy's real badge, plus "N versions" when there are more, and
  the copy picker marks "Recommended" and shows each copy's quality, codec, size and language so the badge always
  matches what Play opens. The picker already exists (`SameVideo`'s copy picker), so no new screen: only the order,
  the label and the chip. Edge cases: a copy with no stated size and no tag (no badge rather than a guess); a
  4K copy on a 1 GB stick (recommended is the 1080p one, the 4K stays selectable); a viewer's own choice or last
  played copy wins over the recommendation (remember it per video); a download wins; a copy that is a cam or a
  sample is never recommended; two copies equal on everything keep the newer; a stale cache of the old badge is
  rebuilt (bump the cache key, see `HomeCache`); the TV card and the web card use the same object; no layout shift
  (the badge width is fixed, "SD" to "4K" already fits).

  Real shapes found on the phone, 2026-10-31 (POCO F5, 1.28.3, Cinema_paradasio, about 400 grid titles, no `[CC]`,
  `@CC` or `@CC_ALL` in this chat, so those are in the other channels: scan 10bitclub Official Channel and Zeyrox
  Movies Official next, then every chat the user adds). Each is a duplicate card or a dirty title today:
  (1) a channel tag word in front, "MM Premalu", "MM Neru", "MM Salaar", "MM Salaar Tam", "MM Kaathal The Core",
  "MM Aquaman and the Lost Kingdom", "Movie Hub Narivetta": the same film sits twice, with and without the tag;
  (2) year glued to quality, "Kanguva 20241080p", "Kanguva 2024720p": no year or quality read, so these split from
  the 22 version "Kanguva" card; (3) a language word as a suffix, "Salaar Mal", "Salaar Tam", "Hi Nanna Mal"
  (and `#Hin` in "Bramayugam #Hin", a hashtag suffix); (4) an alias in the title, "Godzilla Minus One A K A Gojira
  -1 0", "Badland Hunters A K A Hwang-ya", "Bramayugam"; (5) a leading `~`, "~SAW X"; (6) case or spelling twins that
  should fold, "VidaaMuyarchi" and "Vidaamuyarchi", "Guruvayoorambala Nadayil" and "Guruvayoor Ambalanadayil",
  "Vishwanath and Sons" and "Vishwanath & Sons", "Barroz" and "Barroz - Guardian of Treasures"; (7) spaced
  initialisms, "G D N", "A R M", "J S K", "L I K" (should read "GDN", "ARM", "JSK", "LIK"); (8) loose words after
  a subtitle, "Pushpa 2 Malayalam The Rule Reloaded Version", "Salaar Cease Fire Part 1"; (9) bare numbers and
  scores as titles, "4", "8.6". The "N hidden" chip counts 22 to 146 as the list scrolls: find out what it hides
  (HX32). Tests: each shape becomes `NameCorpus` rows with invented titles, and a fold test per twin pair.

- [x] HX39 (2026-10-31, d1dde51, fixed) A video or film card with no poster shows its title twice,
  once inside the art and once under the card. Cause: `GeneratedCover` (ui/.../components/GeneratedCover.kt) writes the
  title on the cover by default (`showTitle = true`), and `MediaArt` (MediaArt.kt ~130) and `PosterArt` (PosterArt.kt
  ~118) call it that way for the tile's art, while `VideoTile` (MediaGridScreen.kt ~2144) also writes the name below. The
  places that already pass `showTitle = false` are the series cards (`SeriesArt.kt`), `ContinueCard`, the chat avatars
  and the desktop grid; the Android grid's `MediaCard` (MediaGridScreen.kt ~2113), `PosterArt` at ~2541, search results
  (`SearchResultsUi.kt` 64 and 191), the copy picker and `MetaExtrasUi.kt` ~697 still draw the title inside. Fix: a
  `showCoverTitle` parameter through `MediaArt` and `PosterArt`, false whenever the tile writes its own caption (every
  card with a caption below), true only where the art stands alone (a full-bleed player loader, a widget). Keep the
  big faint number and the chat initial on the cover, they are not the title. Edge cases: a card with no caption
  (Home's carousel, which names itself), the TV's focused card, a title that is only the cover (no name at all,
  then the cover shows the chat initial and nothing else), a very long name, RTL, the 1x and 2x render tests
  (`BrowseRenderTest`, shot refresh) and the desktop and web draw of the same cover. Commit apart from the rest.

  HX31 root causes and rules (agent analysis of `MediaName.kt` against those shapes, 2026-10-31; nothing built yet).
  Why they fail: (1) `HANDLE` (`@[A-Za-z0-9_]{3,}`, MN ~688) swallows a whole underscore name, `handleOf` then drops
  two words by assumption, so `@CC_Padheyam_1994` leaves bare `1994` and `@CC_Malappuram_Haji_Mahanaya_Joji.mp4` leaves
  nothing, so `tileTitle` returns null and the raw name shows; (2) `@CC` and `@MM` are two characters, under the `{3,}`
  floor, so the `@` stays in the title; (3) a channel name as a prefix ("cinema company", "Movie Hub", "MM") is plain
  words with no rule, and a tag at index 0 is never cut (`firstTagIndex`); (4) `DVDWO` is in no tag list; (5) math
  letters (U+1D400 block) are letters, not symbols, so `isOtherSymbol` keeps them and the fold key never matches the
  plain spelling; (6) a zero width character (category Cf) is not whitespace, so `trimStart` and `clean` keep it; (7)
  `BARE_YEAR` needs a non digit after the year, so `20241080p` has no year or tag; (8) names cut at 32 characters
  ("Malayalam Full M", "Ful", "Ma") fail `titleEnd` since the tail is not a whole tag; descriptors ("Superhit
  Malayalam Movie X") and genre tails are not tags; (9) `mal`, `tam`, `hin` exist only in `LANGUAGE_CODES`, `#` and
  `~` are not in `TRIM_CHARS`; (10) no rule for a YouTube id, `%26`, a spelled out digit tail, or recasing; (11) nothing
  handles `A K A` (the dots became spaces first); (12) `keyOf` only lowercases, so spelling twins never share a key,
  and a name with no year or tag gets no key at all (SS ~546).
  Rules to build, each general and with its guard: handle = the first underscore word, a second only when two title
  words remain, never leave a result without a letter, `@\w{2,}` only when it stands alone before a space or `_`;
  a leading run of tags (`dvd*`, `bd*`, `hd*`, `web*`, `cam*`, `DVDWO`) of two or more tokens, or one `dvd*` longer
  than three letters, with a letter left over (so a film called `HD`, `DVD`, `CC`, `Hindi Medium` is safe); a descriptor
  run ("superhit", "blockbuster" + language + "movie", "full") of two or more tokens; split a glued year and quality
  with a fixed height list (`(19|20)dd` + `480|576|720|1080|1440|2160` + `p|i`); drop Cf characters (200B to 200F,
  2060, FEFF, 00AD, 202A to 202E); fold math and fullwidth letters to ASCII by hand (no NFKC in common Kotlin); cut a
  truncated tail that is a proper prefix of a tag phrase; add `mal|tam|tel|hin|eng` as trailing soft tags, `#` consumed
  with them, never at index 0 so a film named "Mal" stays; trim a leading `~ * = + ^` run; decode `%26` style escapes
  only when no space follows; cut an 11 character mixed case YouTube id with its tail; split `A K A` and keep the alias
  as a second key; join runs of three or more single letters for the key ("G D N" is "GDN"); recase an all lower case
  name for display; a name of tag words only returns null so the caption is used.
  Per chat learning for the channel-name prefix ("cinema company X", "MM X", "Movie Hub X", "AsH X"): a one or two word
  leading n-gram that starts at least 15 percent of 20 or more cleaned titles in one chat (or three when it is within
  one edit of the chat's own title), with varied remainders, is a tag for that chat, used for the display title and the
  fold key only, never stored, never leaving an empty title; a small chat learns nothing; the risk is a franchise or actor
  chat that starts every title alike, so a minimum sample, the next-token variety test and an "undo" in the title
  correction sheet.
  Spelling twins: a second tier key (lower case, no spaces, collapsed repeats, `h` after a consonant dropped, `w` as `v`,
  `g/k`, `d/t`, `b/p` paired, digits kept) that only folds when the first key does not, the years are equal or both
  absent and `SameVideo` agrees (lengths within 180 s, or the same size or fileId); "Salam" inside "Lal Salam" never
  folds by name; undated names fold only by length or size, never by name.
  Corpus rows (invented): `@CC_Silver_Harbour_Night.mp4`, `@CC_Silver_Harbour_1982_Malayalam_TVRip.mkv`, `@CC Amber
  Gate.mp4`, `Amber Gate 20241080p.mkv`, `Amber Gate 2024720p WEB-DL.mkv`, a math letter title, a zero width start,
  `Copper Tide Malayalam Full M.mp4`, `Moon Ledger #Hin.mkv`, `DVDWO Reed Lantern.avi`, `Tin Ferry A K A Tin Boat.mp4`,
  `Tom %26 Wendy (2015).mkv`; guards that must keep passing: `CC.mkv`, `Mal (2011) 720p.mkv`, `HD.mp4`, `2012.mkv`,
  `Hindi Medium Lite (2017).mkv`.
  HX31 phone survey result (2026-10-31, POCO F5, 1.28.3; titles saved in the session scratch folder). Chats with movies:
  Cinema_paradasio (about 1,045 titles), Add-on Database: MSB (1,348), Add-on Database: DiccoBot (1,094), Database
  @DiccoBot (758+, stopped early). 10bitclub Official Channel and Zeyrox Movies Official show nothing (see "Hidden"
  below). Not opened: MollywoodSearchBot, HomeLander, the archived MollywoodChannel. The user's report is confirmed:
  the 1965 to 1978 films of MSB (333 titles, almost all `@CC Title`, for example `@CC Chemmeen` 1965, `@CC Kodiyettam`
  1978) show the `@CC ` prefix in the card. Raw file names seen on the details page (the "File name" line, verbatim):
  `@CC.Chemmeen 1965 Malayalam @cinema_company.mp4`, `@CC Aaradi Manninte Janmi 1972 WEBRip @cinema_company.mp4`,
  `CC.Bhargavi.Nilayam.1964.Malayalam.VCDRip.@Cinema_Company.mkv`, `_@CC_Baahubali_2_The_Conclusion.mkv` (grid title is
  the raw name), `@CC_Njandukalude_Naattil_Oridavela.mkv`, `Rajamma @ yahoo DVDRip @Cinema_Company.mkv`,
  `Cinema_commpanny_Mariyam_Mukku_2014.mp4`, `wWw_CineMaSite_Co_Nirnayakam_2015.mkv`,
  `Romeo.%26.Juliets.2013.1080p.SS.WEB-DL.DD%2B5.1.x264-JR.mkv`, `@WMR_Samrajyam.1990.Malayalam.720p.WebHD.x264.mp4`
  (grid title "1992 Malayalam WebHD": a different year and no title, a real bug, the year came from the tag run).
  So the rules to add, beyond the list above: the TRAILING channel handle (`... @cinema_company.mp4`, `@Cinema_Company.mkv`)
  must go too; `CC.` as a dotted prefix; `_@CC_` with the leading underscore; `@CC_ALL_` and `@CC_MAL_` (the second token
  is a tag, not a title word); `@WMR_`, `@Mallu_Movies_`, `@MM_New_`, `@Cinematic_World_`; site prefixes (`wWw
  CineMaSite Co`, `MoviesNow`, `1TamilBlasters`, `Cinema commpanny` misspelt); brackets cut at 32 characters
  ("Choodan [Dookudu] 20 13", "Kavacham [Adhurs", "Chakravyooham (Brindavanam", "Twenty One Grams (2022", "PINNEYUM (2"):
  close or drop an open bracket at the end; junk titles "$€f (2k23) Mal", "@π√ (2k23) Mal", "kt (2k23) Mal",
  "ᴊᴏᴊɪ" (small caps), "𝙼𝚁 Udal", "മാ Org", and bare "Malayalam", "DVDrip", "SD", "Manglish", "Ep 04 ... Soul Stories 2024
  1080p MMAX WEB", "CINEMA MAX3,O,Kudukku,2025,Malayalam,Movie,HDRip" (commas as separators); `2k23` as a year.
  Duplicates that did not fold: "Premam" x2, "Vallavan" x4 (all with the @piro_files logo as poster: that logo must never
  be accepted as a poster, check it is not what TMDB or the thumbnail cache returns for year-less names), "Ayalathe
  Adheham" x2, "Highness Abdullah" and "His Highness Abdullah", "1971 Beyond Boarders" and "1971 Beyond Borders",
  "Rajamma @ yahoo" and "Rajamma at Yahoo". Check each against the title correction sheet too.
  Hidden chip (HX32, answered): it is the size filter. It opens a "Smallest video shown" sheet, default 400 MB, "25
  videos in this chat are smaller and hidden. This applies to every chat". It does NOT hide duplicates or junk. But
  the counts are huge on a movie archive (DiccoBot says 6,269 hidden, 10bitclub shows nothing at all: "No videos here
  of 400 MB or more, 2 videos hidden by the size limits"), and the chat count was 25 then 121 after a sort change
  (the count looks like it changes with the sort: find out, it should not). Many old films are 400 to 900 MB, so a
  400 MB default hides real films; check what a film of 300 MB loses. Wording: "N hidden" on a chip next to Star and
  Search reads as hidden chats, not hidden small videos; rename ("N under 400 MB") and say the same in the picker.
  Also: sorting by Year shows only year-less titles (42), and a search for `_@CC` does not filter by raw name.
  Correction (user, 2026-10-31): 10bitclub Official Channel only posts links to external sites, no Telegram videos, so
  its empty grid is right and nothing is lost there; drop it from the HX31 survey. Zeyrox Movies Official showed "No
  playable videos" and still needs a look. For the picker (HX27): a chat with zero videos, or only links, shows "No
  videos here" under its title, is never in Recommended and never pre-ticked, may still be ticked by hand; it must not
  count towards the "N videos" total; and the size-limit text ("2 videos hidden by the size limits") must not be
  shown as if the chat had videos worth adding.
- [ ] HX40 (asked 2026-10-31, casting, feature, PLANNED 2026-11-03, no code yet, after HX27 to HX32) DLNA / UPnP casting.
  Today only Google Cast exists. Most smart TVs (Samsung, LG, Sony, Philips) take a DLNA push and have no Cast, and DLNA
  needs no Google Play Services, so the F-Droid build gets casting for the first time. Echogram lists it (SEO-PLAN.md).
  - **Decisions (user, 2026-11-03).** (1) Push only: TMPlayer is the control point and serves just the chosen video and
    its subtitle; no ContentDirectory, no library a TV can browse. (2) TVs first: Samsung, LG and Sony quirks are fixed
    and tested before release, VLC, Kodi and `gmrender-resurrect` are the test renderers; boxes and NAS players work
    when they follow the spec, their quirks come later. (3) LAN guard: the session token plus the renderer's own IP, no
    PIN (a renderer cannot type one). (4) The TV app gets no casting and runs no discovery; TMPlayer TV as a DLNA
    renderer is a separate item, only if asked.
  - **What is reused (no parallel stack).** core `cast/`: `CastServer` (token path, Range, the `allowed` address
    filter), `CastSource` and `RecoveringCastSource`, `CastFormats` with `ReceiverCaps` and `CastPlan` routes,
    `MkvRemuxer`, `CastRecovery`, `CastStateMapper` with `ReceiverMedia` and `CastPlace`, `LanAddress.forReceiver`,
    `WebVtt`. Phone: the `CastSupport` interface (`app/src/main/.../cast/CastSupport.kt`), which `PlayerCast.kt`,
    `CastRemoteScreen` and `CastPickerSheet` already drive whatever the build. Desktop: `DesktopCast`, `CastDiscovery`,
    `CastPlaybackEngine`, `SwitchingEngine`. Shared UI: `ui/shared/.../CastRemote.kt` (`CastRemoteState`,
    `CastRemoteActions`).
  - **Design.**
    1. New package `core/src/commonMain/kotlin/com/tmplayer/cast/dlna/`, pure Kotlin on `java.net` like `CastServer`:
       `Ssdp` (M-SEARCH for `urn:schemas-upnp-org:device:MediaRenderer:1` to 239.255.255.250:1900, reply and NOTIFY
       alive/byebye parser), `DeviceDescription` (UDN, friendlyName, manufacturer, modelName, icon, the AVTransport,
       RenderingControl and ConnectionManager control URLs resolved against URLBase or LOCATION), `Soap` (envelope
       builder, answer and UPnP fault parser), `Didl` (DIDL-Lite builder, every field XML escaped, `protocolInfo` with
       `DLNA.ORG_PN` when known, `DLNA.ORG_OP=01` for a ranged file, `00` for a stream, `DLNA.ORG_FLAGS`), `SinkCaps`
       (the `GetProtocolInfo` Sink list into a `ReceiverCaps` plus the containers it takes; an empty list gives MP4,
       H.264, AAC, 1080p), `DlnaRenderer` (SetAVTransportURI, Play, Pause, Stop, Seek `REL_TIME`,
       GetPositionInfo and GetTransportInfo polled every 1 s playing and 5 s paused, only during a session,
       GetCurrentTransportActions for what it allows, SetVolume and SetMute when RenderingControl is there; it emits
       `ReceiverMedia` so `CastStateMapper` works unchanged) and `DlnaDiscovery`.
       XML: `javax.xml.parsers` DOM, on Android and the JVM alike, so no new dependency; DOCTYPE and external entities
       off (XXE), body capped before parsing (description 256 KB, SOAP 64 KB). Own client rather than a library: the
       subset is SSDP and about ten SOAP actions; Cling is unmaintained and jUPnP brings a servlet container and an
       Android service of its own.
    2. `CastServer`: answer `HEAD`; on media answers add `contentFeatures.dlna.org` (when the request has
       `getcontentFeatures.dlna.org: 1`), `transferMode.dlna.org: Streaming` (Interactive for subtitles), and
       `CaptionInfo.sec` pointing at the subtitle; serve an SRT next to the WebVTT (`CastResource.Text`, `text/srt`);
       for a DLNA session `allowed` is the renderer's address alone; close after 30 min paused with no request.
    3. Routes: the same `CastPlan` with `Hls` dropped for DLNA (few TVs take HLS over DLNA). Direct with ranges first.
       Remux and ConvertAudio as a progressive fMP4 stream go as a Maybe; a seek on a stream is a new
       SetAVTransportURI with the start in the query. If real TVs refuse an fMP4 stream with no length, an MPEG-TS
       writer is phase 4, measured first.
    4. Phone: `DlnaCastSupport : CastSupport` in `app/src/main` (both flavors) and `MergedCastSupport(google, dlna)`:
       one device list, Google Cast first, ids prefixed `gc:` and `dlna:`, session and remote from whichever is
       connected. `Cast.support()` returns it on a phone; the TV keeps `NoCastSupport`; the nocast flavor merges DLNA
       alone, so the F-Droid build shows the Cast button. `CastDeviceInfo` gets a `kind` for the small "DLNA" label,
       and the support exposes the connected receiver's `ReceiverCaps`. Keep-alive: first check how the Google Cast
       session keeps `CastServer` alive with the screen off; DLNA uses the same, else `PlaybackService` (mediaPlayback,
       already declared) hosts the session with a MediaSession forwarding to `DlnaRenderer` (notification and lock
       screen controls), a `WifiLock` and a partial wake lock held only while a DLNA cast plays or is paused.
       Permissions: `CHANGE_WIFI_MULTICAST_STATE` (install time) and a `MulticastLock` held only while the picker is
       open. targetSdk is 35; when it reaches 37, Android's local network protection needs a runtime permission:
       add that to the version pins in BUILDING.md. Android 6.0: every API used is older.
    5. Desktop: `DesktopCast.State.Connected` holds a `Receiver` interface (id, name, model, address, caps, media,
       volume, lost, load, play, pause, seek, stopMedia, setVolume, setMuted, setActiveTracks, setRate) implemented by
       `CastClient` and a `DlnaRenderer` adapter; `CastPlaybackEngine` takes a `Receiver`, and `ReceiverCaps.forModel`
       moves behind `receiver.caps`. The picker starts and stops `CastDiscovery` and `DlnaDiscovery` together.
    6. Discovery, both apps: one multicast socket per interface that `LanAddress` accepts (no VPN, tunnel, bridge,
       container adapter), M-SEARCH three times at the start then every 5 s, NOTIFY listened for on 1900 with
       SO_REUSEADDR (where the OS service holds it, as Windows' SSDP Discovery may, replies to M-SEARCH alone),
       stopped 10 s after the last new device and when the picker closes; descriptions fetched 4 at a time, 3 s
       timeout, cached for the session, one row per UDN.
  - **Per platform.**

    | Platform | DLNA | What it does, and the limits |
    | --- | --- | --- |
    | Phone (both builds) | yes | One Cast list; Wi-Fi or Ethernet only (on mobile data the sheet says to join the TV's Wi-Fi); a hotspot the phone hosts works by subnet when multicast passes; volume keys move the TV only when the user turned that on and RenderingControl is there |
    | TV | no (decided) | No button, no discovery, no CPU on 1 GB sticks; the parity table says so |
    | Desktop (Windows, Linux, macOS) | yes | Same list as Cast; Windows: the inbound HTTP rule Cast already needs, M-SEARCH replies pass the default multicast response rule; Linux: firewalld or ufw may drop SSDP, said in the help card; macOS 15+: the Local Network prompt, add `NSLocalNetworkUsageDescription` to the app's Info.plist (none today, which may also be why Cast is not found on some Macs: check) |
    | Web | no | A browser cannot send SSDP multicast or run a LAN server; the Cast button stays hidden and the help line says "use the phone or desktop app to cast to a TV" |

  - **Edge cases, handled.**
    - Discovery: nothing found gives a help card (same Wi-Fi, guest network or AP isolation, router dropping
      multicast, VPN on, the TV's network standby or DLNA switch off, the desktop firewall) with Try again, never an
      empty list; a device on two interfaces or IPv4 and IPv6 is one row by UDN; a renderer with no AVTransport is
      skipped; a LOCATION outside the private range or the interface's subnet is never fetched (no SSRF); a big office
      LAN: search the MediaRenderer type only, at most 64 rows; a TV that has both Cast and DLNA (Sony, Philips, some
      LG) is one row by IP and uses Cast.
    - Load: a refused SetAVTransportURI (UPnP 714 bad MIME, 716 not found) tries the next route, then says plainly
      "this TV cannot play this video" with the reason, never a silent black screen; a renderer busy in another app
      answering 701 gets Stop, then the load again; the judge runs before any push (HEVC 10 bit, AC-3, DTS, Matroska).
    - Seek: allowed only when GetCurrentTransportActions lists Seek or `DLNA.ORG_OP` says time seek; otherwise the
      bar is off with "This TV does not take seeking", not lying.
    - Position: a missing or `NOT_IMPLEMENTED` duration uses the probed one; a position that jumps back while the TV
      buffers is ignored; STOPPED within the last 2% (or NO_MEDIA_PRESENT after playing to the end) counts as
      finished and marks it watched; stopped mid way keeps the place and offers Play here.
    - Lost: byebye or three failed polls go through `CastRecovery` into the Lost state with Reconnect and Play here,
      as Cast does; the local place is kept.
    - Subtitles: `CaptionInfo.sec` header plus a `res` of `text/srt` in the DIDL (Samsung, LG); offered as "may not
      show on this TV" when the description says nothing; no burning in. Audio track change: reload at the place.
    - Next episode: follows on the TV as with Cast, by a new SetAVTransportURI (no SetNextAVTransportURI in pass 1).
    - Interruptions: screen off and background (keep-alive above), a call, rotation (state lives in the support, not
      the screen), Wi-Fi switch or new IP (rebind and reload at the place when the renderer still answers, else stop
      and say so), battery saver killing the service (the TV loses the stream: the next open shows the place kept),
      process death (port gone, no orphan), a second cast (asks to replace the first), a file still downloading
      (`CastSource` reads as for Cast), a long pause the TV times out on (Play reloads at the place on a 716), a
      protected video (refused, the existing rule). No disk writes, so no space check.
    - Security: bind to the LAN address only, the token path, the renderer's IP alone, only this session's video and
      subtitle, port closed on Stop, app exit, Lost and the idle timeout; DIDL escaping (the `%26` bug class); XXE off.
    - Low end and scale: discovery and polling stop by themselves; measure idle CPU on the phone after closing the
      picker and during and after a 30 min cast (`/proc/<pid>/stat`).
    - Errors (HX10): every parser (SSDP, description XML, SOAP, Sink list, DIDL round trip) gets its lines in
      `GarbageInputTest`; every job through `JobFailures`; a SOAP or XML failure is a message with Try again and Send
      logs, never a crash.
  - **Not done, and why.** Web (browser limits); TV (decided); a browsable media server (decided); AirPlay (out of
    scope); DTCP-IP protected renderers; casting across subnets or a VPN (multicast does not cross; an "add by
    address" entry only if asked); gapless next episode; volume on a TV without RenderingControl; MPEG-TS remux until
    phase 3 shows it is needed.
  - **Terms and stores.** The user's own video to their own TV on their LAN, one session, no public URL. The new
    permission is a manifest change: check `docs/agents/telegram-terms.md`. F-Droid gains it with no new dependency.
    Public copy says "cast to a TV", "DLNA" only in the small print (a trademark, used as the protocol's name).
  - **Strings (en.json only, translated before release):** the DLNA label, the help card lines, Wi-Fi only, the
    cannot-play reasons, no seeking, subtitles may not show, the desktop firewall and macOS Local Network lines.
  - **Phases (each a commit and a clean `/clear` point).**
    0. Fixtures: real SSDP replies, descriptions, Sink lists and SOAP answers from VLC, Kodi, `gmrender-resurrect`
       (podman) and the user's TV into `core/src/jvmTest/resources/dlna/`, names scrubbed. Sonnet, medium.
    1. Core: the `dlna` package, parsers, DIDL, `SinkCaps`, `GarbageInputTest` lines, `CastServer` HEAD, headers and
       SRT, and a `FakeRenderer` in jvmTest running the whole flow on loopback (discover, describe, load, play, seek,
       poll, byebye, lost, another address answered 403). Sonnet, high.
    2. Desktop: `Receiver`, `CastPlaybackEngine` on it, `DlnaDiscovery` in the picker, a DLNA row in the
       `CastRemoteRenderTest` style test; VLC and gmrender on this machine, the Windows VM for the firewall. Sonnet, high.
    3. Phone: `DlnaCastSupport`, `MergedCastSupport`, the nocast flavor, label, keep-alive, locks, Wi-Fi check; unit
       tests for the merge and ids, a picker preview screenshot test, a test that discovery ends within 10 s of
       closing. Phone device runs only when asked (memory). Sonnet, high.
    4. Real TVs: Samsung, LG, Sony quirks; MPEG-TS writer only if fMP4 streams are refused. Opus, high (unknown causes).
    5. Docs: ARCHITECTURE.md cast section, BUILDING.md permission pin; translations are the user's.
    Left for the user by hand: the TVs themselves (Wi-Fi and Ethernet), the Windows firewall prompt, the macOS Local
    Network prompt, battery after a 30 min cast.
  - **Progress / handoff (2026-11-03, phases 0 and 1 DONE, committed locally, not pushed).**
    - Phase 0 `c90e298`: `scripts/dlna/capture.py` (read only by default, `--play URL`, `--notify N`, scrubs names,
      UUIDs, MACs, addresses; the script to hand to someone with a Samsung, LG or Sony TV for phase 4),
      `Containerfile.gmrender`, `Containerfile.rygel`, fixtures in `core/src/jvmTest/resources/dlna/{gmrender,rygel}`
      with a README. Kodi's UPnP renderer never opened in the Ubuntu container (3 tries), VLC 3 is a control point
      only: both noted in the README. Rygel is the second renderer (GUPnP, unlike gmrender's libupnp).
    - Phase 1 `84aa6e0`: `core/src/commonMain/kotlin/com/tmplayer/cast/dlna/` = `Xml` (DOCTYPE refused, caps),
      `Ssdp`, `DeviceDescription` (same-host control URLs only), `Soap` + `SoapClient`, `Didl` + `DlnaFeatures`,
      `SinkCaps` + `CastPlan.forDlna()`, `DlnaPlayback` (UpnpTime, TransportActions, place and stop-reason logic),
      `DlnaRenderer` (load, play, pause, stop, seek, volume, polling, lost), `DlnaDiscovery`, `DlnaCast` (server +
      renderer session, idle close, stream seek by reload). `CastServer`: `publish(name, resource, DlnaServing)`,
      DLNA headers when asked, 403 to strangers, `idleMs()`, injectable clock. `WebVtt.writeSrt`. Tests: fixtures,
      parsers, `FakeRenderer` flow on loopback (20), `CastServerDlnaTest`, `GarbageInputTest.dlnaParsersNeverThrow`,
      opt-in `DlnaLiveTest` (env `DLNA_LIVE_IFACE`, `DLNA_LIVE_MP4`; passed against gmrender and Rygel over real
      multicast, with `--rerun` so Gradle does not skip it). `:core:jvmTest` and `:core:compileAndroidMain` green.
    - Real-renderer quirks found and handled: Rygel says STOPPED while paused (read as paused after our Pause),
      Rygel answers position 0 and duration 0 straight after a Seek (the seek target is held until the TV gets
      there or 8 s pass), gmrender answers 200 with no error to a bad seek target or InstanceID, times come as
      `0:00:29` or `0:00:00.962`, header case differs (`LOCATION`, `Location`), Rygel lists the file types
      it takes far down its Sink list (the first entries are one AVC baseline profile).
    - Not done in 1, on purpose: nothing outside core (no phone, desktop, strings), no MPEG-TS, no
      SetNextAVTransportURI, no LAN-subnet check in `DlnaCast` beyond the renderer-address filter.
    - **Phase 2 (desktop) DONE 2026-11-03, committed locally, not pushed.** `Receiver` (desktop/cast/Receiver.kt) with
      `CastReceiver` (wraps `CastClient`, builds the LOAD JSON from a neutral `ReceiverLoad`) and `DlnaReceiver` (wraps
      `DlnaRenderer`, polls from the first load, 30 min idle limit, server open to the TV's address alone, `adapt` drops
      HLS). `CastPlaybackEngine` takes a `Receiver` (`receiver.caps` replaces `ReceiverCaps.forModel`; seek refused with a
      note when the TV's actions leave Seek out; paused 30 min closes the port and Play restarts it at the place).
      `CastFinder` = Cast + DLNA discovery started and stopped together, one row per IP (Cast wins), DLNA ids `dlna:<udn>`.
      `DesktopCast.State` holds `CastTarget` and `Receiver`. Picker: "DLNA" label, help text, Try again. Core:
      `CastStreams(serving=)` + `dlnaServing()` put the DLNA headers on what the engine publishes. macOS: Info.plist
      `NSLocalNetworkUsageDescription` + `NSBonjourServices` in `desktop/build.gradle.kts` (unverified on a Mac). Strings
      in en.json: `cast.cannot_seek`, `dlna_label`, `idle_closed`, `refused_busy/open/type`, `none_found` reworded.
      Tests: `DlnaReceiverTest` (9, scripted SOAP + merged list), `CastRemoteRenderTest` panel for a DLNA TV, opt-in
      `DlnaEngineLiveTest` (env `DLNA_LIVE_IFACE`, `DLNA_LIVE_MP4`; passed on gmrender: discover, play, seek, pause, stop).
    - Not done in 2: subtitles to a DLNA TV from the desktop (the engine's `CAST_SUBTITLES` is off for Cast too), SSDP
      byebye while the picker is closed (three failed polls catch it), the Windows VM firewall pass, a Mac, a real TV.
    - **Phase 3 (phone) DONE 2026-11-03, committed locally, not pushed.** `app/.../cast/`: `CastSupport` gained `CastKind`,
      `CastDeviceInfo.kind/address`, `Connected.kind`, `RemoteStatus.canSeek`, `CastLoadRequest.seekable`, and defaults for
      `caps`, `serverAccess` (`ServerAccess`: allowed = TV's address alone, DLNA headers, 30 min idle), `adapt` (drops HLS),
      `refusal`, `offLan`. `DlnaCastSupport` (SSDP list while a picker is open, `DlnaRenderer` per session, a load keeps
      the process alive, Lost on three failed polls), `DlnaPlatform`/`AndroidDlnaPlatform` (multicast lock only while the
      picker is open, LAN check, service start), `DlnaCastService` (foreground, mediaPlayback, MediaSession + Play/Pause and
      Stop notification, Wi-Fi lock, partial wake lock capped at 4 h; only while a video is on the TV),
      `MergedCastSupport(google, dlna)` (ids `gc:` / `dlna:`, Cast wins one row per address, the last connected side is the
      session; picking the other side connects the new TV first and then ends the old one). `Cast.support` returns the merge on a
      phone, so the F-Droid build now has the Cast button (DLNA alone). `PlayerCast`: caps from the support, plan adapted,
      server open to the TV only and with DLNA headers (`CastStreams(converterFactory =, serving =)`: phase 2 had made a
      trailing lambda bind to `serving`, so the app did not compile until now), server made again when the address or kind
      changes or after the idle close, seek refused with `cast.cannot_seek` when the TV leaves Seek out, 30 min paused closes
      the port and Play loads again at the place, another TV picked mid cast moves the cast there, `release()` ends a DLNA
      session with the activity. Manifest: `CHANGE_WIFI_MULTICAST_STATE` + the service (mediaPlayback is declared already).
      en.json: `cast.none_found_phone`, `cast.wifi_only`. Tests: `MergedCastSupportTest` (12), `DlnaCastSupportTest` (14, incl.
      real discovery on loopback gone within 10 s of closing), `CastPickerRenderTest` (3 screenshots in
      `build/device-test/review/cast-picker-*.png`). `:app:testDebugUnitTest` 367 green, F-Droid variant compiles.
      Build needs `JAVA_HOME=~/.jdks/jdk-21.0.12+8` (the box default is 25).
    - Not done in 3: a real phone run (memory: only when asked), idle CPU on the phone after the picker closes and over a
      30 min cast, battery, the bar greyed out for a no-seek TV (only the note on a press, as the desktop), subtitles to a
      DLNA TV (CAST_SUBTITLES is off for Cast too), a Wi-Fi switch rebinding without a Reconnect (the polls fail, Lost,
      Reconnect makes the server again on the new address), asking before one cast replaces another (it switches, as a
      second Chromecast did), the volume keys on a TV with no RenderingControl are swallowed (no setting to turn them off),
      Android 17's local network permission (targetSdk is 35; add to the BUILDING.md pins in phase 5).
    - **Next: phase 4 needs real TVs** (Samsung, LG, Sony; Opus, high): hand someone `scripts/dlna/capture.py` and ask for the
      folder back; measure whether fMP4 streams are refused before writing an MPEG-TS remux. Phase 5 (docs: ARCHITECTURE.md
      cast section, BUILDING.md permission pin) can be done now with Sonnet, low: prompt "Do HX40 phase 5 from
      private/PLAN.md (docs only; phases 0 to 3 are done, see Progress). Read AGENTS.md first."
    - **Phase 5 (docs) DONE 2026-11-03, commit fd0c120:** ARCHITECTURE.md Casting section, BUILDING.md Android 17 permission pin. Only phase 4 (real TVs) is left.
  - (Not HX40: the paragraph below is the HX31 name probe from 2026-10-31, left where it was.)
  Reproduced in a JVM probe (2026-10-31, `MediaName.parse` and `tileTitle` over the raw names from the phone, probe file
  removed): every shape above fails exactly as seen on screen. Handle-only underscore names (`@CC_Malappuram_Haji_...`,
  `_@CC_Baahubali_...`, `@cinema_company_The_Great_Father`, `@Mallu_Movies_...`, `@MM_New_...`) give an EMPTY title and
  `tileTitle` null, so the card falls back to the raw file name; `@CC.Chemmeen 1965 ...` keeps `@CC Chemmeen`; `@WMR_Samrajyam.
  1990.Malayalam.720p.WebHD...` gives title `1990` and no year (the title word was eaten as a second handle word, then the
  year became the title); `Kanguva 20241080p`, `Salaar Mal`, `Bramayugam #Hin`, `~SAW X`, `DVDWO ...`, `MM Premalu` are
  unchanged. Use these raw names as the first red `NameCorpus` rows (with invented titles in the same shapes) before
  changing any rule. Run: `JAVA_HOME=~/.jdks/jdk-21.0.12+8 ./gradlew :core:jvmTest --tests '...' --max-workers=2 --offline`
  (about 25 s warm).
- [ ] HX41 (asked 2026-10-31, search, with HX27) Search any film or show, list the copies the viewer has, say so when
  there are none. The Search screen finds any title through TMDB (poster, year, cast, the same match rules as HX24: language
  and year scored) and, under it, lists the copies found in the ADDED chats only (HX27, the search rule above), best copy
  first (HX38). No copy: "Not in your chats" with three honest actions: Where to watch (TMDB's legal streaming and rental
  availability for the viewer's region, a plain metadata call, no links to unknown sources), Search my other chats (a
  one-off search over chats not added, never adds them silently), and Tell me when it appears (a watch on that TMDB id,
  reusing the new-episode alert machinery, chosen chats only). Edge cases: lookups off or offline (the copies list still
  works from names alone), a TMDB id with several matching copies of different languages, a film with a common name, a
  viewer with no chat added, a region with no providers (say so), a very large account (the search budget and FLOOD_WAIT
  rules), the TV's voice search and keyboard, the web app. BOUNDARY, written into the plan on purpose (user and Claude,
  2026-10-31): no feature fetches, requests or searches for content from a bot, channel or source the viewer has not
  added themselves; no plugin or source API for video catalogues; Recommended in the picker (HX27) uses only the viewer's
  own chats and their video counts and never suggests a chat the viewer is not already in; no "discover channels" screen.
  Reasons: AGENTS.md (a client Telegram and the stores have no reason to remove, no piracy tool wording), Telegram's API
  terms, store listings, developer verification. Plugin points that stay allowed: subtitles (MSone, OpenSubtitles, SubDL)
  and metadata or posters, which carry no video catalogue.
**Edge cases and the chosen solution for HX27 to HX31 (user, 2026-10-31: all edge cases planned before building):**
- A chat with 100+ videos: the count is exact up to 999, then "1k+" (never hidden, never a spinner forever; a count that
  fails shows "videos" with no number and a retry on long press). Video files sent as documents are counted by the scan
  later, so the row copy says "about".
- The count is wrong or lags (TDLib local figure is -1 or stale): ask the server once, show the better of the two, and
  let a later scan correct it; never block Continue on counts.
- Recommended threshold X: one constant, 20 videos, cap 30; if no chat reaches X, offer the top five by count; a chat
  that crosses X after the picker was shown does not move a row under the finger.
- Archived and muted chats: shown in the picker, in their own section, never pre-ticked unless they pass X (a movie
  channel the viewer archived to hide it from Telegram is still a movie channel); the Archived filter exists in the
  picker so nothing is unreachable (see HX32).
- Chosen chat deleted, left or banned: the row stays with "No longer available" and a Remove button; Home skips it
  without an error; counts of Downloads from it stay.
- The viewer unticks a chat that has downloads or history: it only leaves the lists, with a confirm that says
  downloads and history stay; nothing in Telegram is touched.
- Existing users (HX28): until they answer, `ChosenChats` is "everything" so an update never empties Home; "Keep all"
  stores that explicit state; a restored backup from an older version is treated the same; a second device asks on
  its own; sign out and in keeps the answer (device setting, like the support counters).
- Select all with a search or folder filter ticks only what is visible; Clear unticks only what is visible; the footer
  total always says how many are chosen overall.
- Large accounts: first rows from the disk snapshot in under 3 s, counts 3 at a time with FLOOD_WAIT waited per call,
  list virtualised, nothing pages itself on an idle TV (measure idle CPU after leaving the picker).
- Cancel and interruption: leaving the picker with Back keeps the old choice (a "Discard changes?" only if something
  changed); rotation and backgrounding keep the ticks in a saved state; two taps on Done run once.
- Removing pin, mute, archive and mark read (HX30): chats the viewer had starred or pinned keep their star; Telegram's
  own pin and unread state are neither read for ordering nor written; tests that call those actions are deleted, the
  chat menu keeps Play, Details, Mark watched, Star, Hide.
- Names (HX31): a rule must not eat a real title that starts with letters like `CC` or `HD` (a title is only stripped
  when the tag is a known group tag shape, bracketed, a handle, or followed by a separator); a title that is entirely
  a prefix keeps its text; Unicode lookalike brackets, full width `@`, zero width characters and emoji around the tag
  are handled; the same cleaned title groups the same series across channels (`SameTitleTest`); a chat title cleaned
  for display never changes the stored id or the name used in TMDB lookups unless it improves the match.

**Priority change (user, 2026-10-31): adding chats is the first priority, and Home shows content from the chosen chats only.**
Order is now HX32 (missing chats, the picker needs the full list), then HX27 with HX30, HX29, HX28, and HX31 alongside as
a parallel agent. "Chosen only" covers the Home carousel, Recently added, every starred or chat row, search, Chats, the
Favorites tab, folders, the TV launcher rows and the web app. Edge cases: (a) Continue watching and History keep videos
the viewer already started, even from a chat they later unticked (hiding them would lose progress), shown without a
chat link; (b) Downloads always lists every downloaded file, a chat is not needed to play it; (c) an existing user who
has not answered the HX28 prompt has `ChosenChats = everything`, so Home does not empty on update; (d) a new install
that skips the picker ("Add chats later") sees an empty Home with a card that opens the picker, never a blank screen;
(e) a chosen chat with no videos yet shows nothing and is not an error; (f) unticking removes its rows at once, no
restart; (g) TMDB lookups and the metadata cache for unchosen chats stop (saves requests and battery), their cached
posters stay on disk until the normal cache limit evicts them; (h) the background checks (new episodes, new videos,
notifications) read only chosen chats, so no alert comes from a chat the viewer never added.

**Search (user, 2026-10-31): search only inside the added chats.** The app's search (Search tab, the Chats search box,
desktop and web search, the TV's voice search) is rewritten to read `ChosenChats` only: video results, chat results and
title matches all come from chosen chats, never a Telegram-wide search. Edge cases: no chat chosen (a card that opens the
picker, not "no results"); a result in a chat unticked while the results are on screen (drops out at once); an existing
user before they answer HX28 (everything, as today); the empty-results text names the cause ("Searched in your 12
chats", with an "Add more chats" button, so a missing movie is never mistaken for a broken search); a search that also
matches a downloaded or History item still lists it under "On this device"; the cost drops too, since fewer chats are
queried (the per-call TDLib budget and FLOOD_WAIT rules of AGENTS.md still apply). Tests: a fake account with one
chosen and one unchosen chat holding the same title, search must return only the chosen one.

**Order and agents for HX27 to HX31 (user, 2026-10-31: run parallel agents for testing, plan first):**
HX31 first (smallest, fixes what the user sees now), then HX27 and HX30 together (the picker and its list),
then HX29, then HX28 last (needs the picker to exist). Max two agents at a time, compile only while they
work, one final device pass on the emulators (phone portrait and landscape, TV, desktop AppImage). Open
questions for the user, with the default I will use if no answer: X = 20 and cap 30; chat photos only in
the picker; existing users keep everything until they answer.

- [x] HX11 (2026-10-31, 58b65fb, not pushed; reported 2026-10-28, Sentry 156433678, the HX2b warning) Mi TV Stick (MiTV-AESP0, Android 9, armeabi-v7a,
  4 cores), 1.28.2-dev: an H.264 High 3.1 1280x536 Matroska on the hardware decoder OMX.amlogic.avc.decoder.awesome
  dropped 50 of 295 frames at 80 per cent speed with 1 audio underrun, although DecodeJudge predicted HARDWARE.
  The tags say fps 0.0 and bitrate 0: the frame rate was not read from the Matroska, so check what that does to the
  frame rate switch, the renderer's frame release timing and DecodeJudge's verdict, and measure on the Mi TV (adb
  Wi-Fi) with a 720p Matroska without a default duration. Not a decode limit: 720p AVC is easy for that chip.
  (Sentry 155851031, the 1.28.1 desktop VerifyError of 2026-10-27, is HX1, fixed in 1.28.2-beta.1.)
- [x] HX12 (2026-10-28, f0fc262, pushed; reported same day, desktop AppImage and Windows, beta.3) A saved login Telegram had ended sat on
  "Reaching Telegram", then the slow help (Retry, Send logs, Use a proxy); only Retry (20 s more) reached the QR.
  Log: `Ready -> LoggingOut`, then nothing: TDLib kept trying to tell Telegram over a line that would not come up.
  Fix: `Td.endingSession` keeps the slow help away while logging out, the loader says "Telegram ended this sign in.
  Starting again", and `watchLogOut` reopens the connections after 4 s and replaces the client after 6 s more
  (shared `replaceClient`, as Retry). Phone and TV share it (ConnectingHelp). Due: a real ended session on desktop.
- [x] HX13 (2026-10-28, f0fc262, pushed; reported same day, desktop) A video sat on the stall sheet with "Telegram limits downloads for
  accounts without Premium" until Try again, then opened in 8 s. The speed limit notice of the video before was
  still held (5 min) and nothing at all was arriving: a connection had quietly stopped. Fix: the Premium line only on
  the slow "arriving at X" line, never on the stall sheet (desktop, phone, TV); and `OpeningStall.NUDGE_MS` (6 s
  with no bytes) reopens Telegram's connections once per wait by itself (`Td.nudgeForStream`), opening and mid play.
  Web: its own stream, not checked. Due: phone and TV pass.
- [x] HX14 (2026-10-28, 9abe7cd, pushed; reported same day, desktop) Window buttons missing while the loader shows: `WindowControlsFallback`
  hid whenever a page header was composed, and Home's header is composed under the loader. Now `WindowChrome.covers`
  (`CoverWindowControls`) brings the buttons back over every full window cover: the loader, the setup, the tour,
  the proxy page, Devices and the PIN pad in Settings, a show's page and the detail pane. Render test
  `WindowControlsRenderTest.coverOverHeader`.
- [ ] HX15 (asked 2026-10-28, design) Title logo, ratings and the opening loader. Plan first, use the
  design-taste-frontend skill for the loader and cards, then build per platform:
  - Title logo: detail pages (`MediaDetailScreen.kt:454`, `WideDetail.kt:217`, show page `Series.kt:957`, which
    passes waitMs=0 and so shows the name and swaps to the logo) and the carousels (`HomeCarousel` phone, TV,
    desktop; `TitleArt` in `Meta.kt:449`, 1.5 s blank wait). Instead: a logo shaped skeleton (`SkeletonBox`) in the
    logo's slot while the metadata or the logo is on its way; the logo when decoded; the text name only when there
    is no logo (metadata says none, the picture fails, or a ceiling passes). No skeleton at all when online
    metadata is off (no TMDB key) or the video has no match: the name at once. Same box size either way, no jump.
  - Ratings: the score row already carries each site's mark (`MetaScoresRow`, `ScoreLogos.kt`); give the detail
    page's score line a skeleton too, and the legacy "★ 7.8" its site mark. Cards (Home rows, grids, carousels,
    TV rails): one score (IMDb, else TMDB) with its mark, from metadata already cached only, never a TMDB call
    per card (scale rule: 100,000 videos). Nothing shown when none is cached, no placeholder on cards.
  - Opening loader (`ui/.../player/PlayerLoader.kt`, desktop `PlayerLoaderSheet.kt`, Android
    `PlayerLoaderHost.kt`, web `webapp/src/player/loader.tsx`): title logo when already cached (never waited for,
    the loader is short), the score row with marks, year, genres, runtime; review alignment and spacing of
    poster, title, line, overview, progress and status on phone portrait and landscape, TV and desktop widths.
  - Web: title logos and the card score where the web has the metadata (parity rule).
  - Edge cases: offline; no key; logo fails to decode; very wide or tall logos (bounded box); a dark logo on a
    dark backdrop; light theme; RTL; reduced motion (still shimmer); TV 1 GB: logos decoded at slot size and
    the shimmer stops off screen; Low storage mode; long titles; a series episode (show logo, episode line).
  - Progress / handoff (2026-10-28, session 1): committed 77a7343 (local, not pushed). core jvmTest, desktop test, web e2e and worker tests pass.
    Done: core `MetaScore.cardPick`, `CardScore`, public `MetaExtras.withProviderScore` (MetaScores.kt);
    `OnlineMetadata.peekCardScore`/`cachedCardScore` (memory, then the disk's extras, never the network, own
    4,096 entry LRU so grids never evict the detail pages' extras) and `storedExtras` (no network, for the
    loader). UI: `TitleArt` holds the name's place with a logo shaped `LogoSkeleton` (2.5 s ceiling, was 1.5 s
    blank; title pages pass `skeletonHeight`, no more waitMs=0), `LogoImage`, `TitleLogo` +
    `rememberCachedTitleLogo` + `TitleLogoImage` for the loader. `MetaScoresRow` shows the legacy rating as
    the provider's score with its mark (`provider` param, default TMDB). `CardScore` pill (bottom end):
    phone/TV `MediaGridScreen` card (not list thumbs), `SeriesArt` (not compact, the episodes pill has the
    foot), desktop `MediaGrid` Poster art. Loader: `LoaderContent.meta/extras/logo`, facts line year ·
    2 genres · length (2 lines on an upright phone), score row (3), poster and words share a foot, progress
    under both. Web: worker appends TMDB `images` to extras (`logo()` in tmdb/extras.ts, v2 edge key),
    `keptExtras`/`keptRating`/`keptLogo`/`loadLogo` in posters.ts (an episode's extras also kept under the
    show's key for its card), `ui/TitleLogo.tsx` on Detail and Hero (light theme inks white logos),
    card TMDB pill in `CardArt`, loader facts/score/kept logo and the apps' block layout, touchpad
    sideways swipe on the carousel (wheel deltaX, non-passive, one page per gesture, was going Back).
    Tests: desktop `TitleLogoShotsTest` (4 states x carousel/page x themes, card score),
    `PlayerLoaderRenderTest` (+logo, legacy, genres), web `tmdb/extras.test.ts`, `e2e/titlelogo.spec.ts`
    (fake flag `big` for the carousel). Web has no OMDb: its card and pages show TMDB's score only.
    Not done / due: TV web (src/tv) hero logo; the web Hero facts line (year, genres) as the apps';
    phone and TV device pass (no phone testing for now); FeaturedHero carousels already show scores.
  - Done when: render shots of every state (loading, logo, no logo, no key) on phone, TV and desktop, web at
    phone and desktop widths; device checks listed as due (no phone testing for now).
- [ ] HX16 (asked 2026-10-28, player) More options and picture shape. (1) "More options" in the player opens
  a small window somewhere after a second click; the user expects a side panel that opens at once
  (check desktop, phone, TV, web). (2) Picture shape: add 16:9, 4:3 and the other usual ratios (21:9, 2.35:1,
  1:1, Fill, Stretch, Original). (3) VR: a user says shapes do not work there (equirectangular 360/180,
  side-by-side); (4) 3D videos (SBS, top-bottom): picture shape changes did nothing. Test on the Android TV,
  which has 3D videos (user, 2026-10-28). Plan per platform and edge cases first.
  - What is there today (read 2026-10-28):
    - Phone: ⋮ opens an `android.widget.PopupMenu` anchored to the button (`PlayerActivity.showOverflow`,
      entries `PlayerMenu.phoneEntries`); Picture shape, Speed, Sleep, 3D then open a second sheet
      (`PlayerSheets`): the "second click" and the "small window somewhere".
    - TV: `PlayerTvMenu` hosts the shared `TvMenu` in a dialog window; shape and speed are row buttons only.
    - Desktop: `PlayerMenu` in `PlayerOverlay.kt:817` is a `TmDropdownMenu` anchored to ⋮ (or the right
      click), paged in place (Main, Options, Shape, Stereo, Sleep...). On sway a dropdown is its own popup.
    - Web: `Chrome.tsx` `Menu` anchored popup for 'more', a `Picker` per page.
    - Shapes: `VideoScale` (core pureMain) Fit / Crop / Stretch, saved by name. Android: PlayerView
      `resizeMode` + codec SCALE_TO_FIT; pinch steps the three. Desktop mpv: keepaspect + panscan.
      Web: `object-fit` contain / cover / fill.
    - 3D (`Stereo3d`, core): Android draws it with a Media3 `GlEffect` (`Stereo3dEffect`); the effects
      pipeline is built at prepare for any file whose name says SBS/TAB, even with 3D off
      (`prepareStereo`). Desktop: an mpv user shader plus `video-aspect-override` = one eye's (or the VR
      pair's) aspect. Web: no 3D. No 360/180 (equirectangular) view anywhere: such a video plays flat.
  - Why shapes fail on 3D and VR (to confirm on the TV, first thing in session 1):
    - Android, leading cause: once `setVideoEffects` is on, Media3's final GL stage draws each frame into
      the SurfaceView's surface fitted with its aspect kept. PlayerView's resize only resizes that surface,
      so Stretch comes back letterboxed (looks like Fit) and Fill changes little. This hits every
      SBS/TAB-named file, 3D on or off. Check: same file renamed without the tag (no pipeline) vs tagged,
      `dumpsys SurfaceFlinger` layer size per shape, screencaps.
    - VR view: the output is the two eyes side by side (`outputSize`, capped at MAX_VR_WIDTH); Fill crops
      the outer half of each eye and Stretch the pair, neither of which a headset wants.
    - Desktop: 3D owns `video-aspect-override`, so a fixed ratio and 3D would fight; turning 3D off writes
      "no" and would wipe a chosen ratio.
    - 360/180: there is no spherical view, so "shapes don't work in VR" there means the flat frame; a real
      360 view (Media3 `SphericalGLSurfaceView`, mono and st3d stereo) is a separate feature, suggested as
      a follow-up, not this pass.
  - Plan (1) More options as a side panel, opens at once:
    - Phone landscape, TV, desktop, web wide: a panel docked to the end edge, full height, 360 dp
      (TV 400 dp, desktop/web 340 px), sliding in 150 ms (reduced motion: fade). Phone portrait and web
      phone: the existing `PhoneSheet` bottom sheet at full width (a side panel does not fit 360 dp).
    - One tap opens it; its sub-pages (Picture shape, Speed, Sleep timer, 3D, Subtitle style, Bookmarks,
      Chapters) slide inside the same panel with a Back line: no second window. Each line shows its value.
    - Built from state already in memory: no TDLib, disk or network call on open (bookmark count, chapters,
      watched state are held already). Video keeps playing; controls held while it is up.
    - Closes on Back / Escape / tap outside / the ⋮ again; TV focus goes to the first line on open and back
      to ⋮ on close; desktop right click opens the same panel; Ctrl/keys behind it do nothing while open.
    - Edge cases: rotation while open (phone moves between panel and sheet, page kept); PiP entered while
      open (closed first); cast connected (3D and shape lines hidden as today); a chat that forbids saving
      (Save lines hidden); RTL (panel on the left); TV 1 GB: panel composed on open, not kept alive.
  - Plan (2) more shapes:
    - `VideoScale` gains Ratio16x9, Ratio4x3, Ratio21x9, Ratio235, Square, after Fit (label "Original"),
      Crop (label "Fill"), Stretch. Saved names stay, so nobody's saved choice moves. A fixed ratio draws
      the picture at that shape, fitted inside the screen (as VLC and mpv's aspect override).
    - Remembered: Original / Fill / Stretch across videos as today; a fixed ratio for this player (an
      autoplayed next episode keeps it), a new player starts from the last of the three.
    - Row button keeps cycling, now through all eight; pinch keeps stepping the three; the panel's page
      picks any directly; desktop W and web W cycle as today.
    - Android: plain PlayerView path sets `AspectRatioFrameLayout.setAspectRatio` after PlayerView's own
      size update (re-applied on video size change, rotation, PiP). Pipeline path: see (3).
      Desktop: `video-aspect-override` "16:9" etc. with keepaspect on, panscan 0. Web: the video box sized
      to the ratio inside the stage, `object-fit: fill`.
    - Edge cases: size unknown before the first frame (apply when known); anamorphic SAR (Original uses the
      display aspect, ratios ignore SAR); PiP params clamped to Android's 1:2.39 to 2.39:1; subtitles follow
      the frame (`fitSubtitlesTo`); Save frame and seek preview use the source frame; audio only and cast:
      no shape line; i18n keys for the five ratio names and "Original"/"Fill" in en.json.
  - Plan (3)(4) 3D and VR:
    - Android, pipeline on: the shape moves into the pipeline. A `Presentation` effect after
      `Stereo3dEffect` sized to the screen (Original: fit, Fill: `LAYOUT_SCALE_TO_FIT_WITH_CROP`, Stretch:
      `LAYOUT_STRETCH_TO_FIT`, a ratio: `createForAspectRatio` then fit), the PlayerView kept at fit so the
      final stage has nothing left to letterbox. Re-sent with `setVideoEffects` on shape, 3D or size change.
    - VR view: the shape applies to each eye in its half (Original fits each eye, Fill/ratios per eye,
      Stretch fills each half). If per eye in the shader is too risky for this pass: shapes greyed in VR
      with a line saying each eye fills its half, and per eye left as a follow-up.
    - Desktop: one place computes `video-aspect-override` from (3D, shape): a ratio wins, else 3D's eye
      aspect, else "no". Web: no 3D, so only (2).
    - Edge cases: 3D blocked (HDR, FFmpeg decoder): shape still works on the plain view; turning 3D on/off
      mid-video keeps the shape; a TAB file's half height eye; full-size SBS (3840x1080); 3D on a 1 GB TV
      stick (one Presentation pass, measure frame drops with `dumpsys gfxinfo` and the player's dropped count).
  - Progress / handoff (2026-10-28): run as parallel agents. Base 73a4567 (VideoScale eight shapes). S1 ec6f353
    merged: cause from Media3 1.11.1 bytecode (setVideoEffects puts every SBS/TAB-named file through the GL graph,
    whose FinalShaderProgramWrapper adds Presentation SCALE_TO_FIT at the surface size, undoing Stretch/Fill);
    fixed shapes on the plain view via AspectRatioListener; pipeline path = view full screen + Presentation after
    Stereo3dEffect (folded into the last pass); VR per eye in the shader; ratio icons. S2 92f5823 merged:
    PlayerMorePanel.kt replaces the PopupMenu and PlayerTvMenu (side panel 360/400 dp, PhoneSheet upright, pages
    via LocalChoicePageHost). Merged main passes app player tests (161) and core tests. Merged release build
    versionCode 1280204 on the phone and the Mi TV. S3 (desktop, web) still running.
    Due: TV 3D matrix in com.tmplayer (shot.sh in the session scratchpad), phone rotation with the panel open, the
    upright sheet, bookmark note keyboard, PiP while open, RTL, TV focus and Menu key, 4:3 on the phone.
  - Sessions: S1 Android: TV diagnosis (user starts a SBS and a TAB video on the Mi TV), `VideoScale`
    ratios + core tests, PlayerView and pipeline paths, VR per eye, TV pass. S2 the panel on phone and TV
    (replaces PopupMenu and the TvMenu dialog), render shots. S3 desktop (panel, mpv aspect owner) and web
    (panel, ratios), render shots both themes, web e2e. Phone checks listed as due (no phone testing).
- [x] HX18 (2026-10-28, d09ed24 497e726 merged, not deployed; reported 2026-10-28, web TV app org.tmplayer.tvweb on the Mi TV) "Looks misaligned". Seen on 1920x1080
  screencaps: sign in column off centre (left 416 px, right 464 px); Home hero: Play (104 px tall) and
  "Go to <chat>" (96 px) differ in height and their middles, carousel dots (y 657) not on the buttons' middle
  (y 642); hero backdrop blank; first Recently added cards show only a colour (no poster yet). Check against
  the apps' TV Home and fix after HX16 merges.
- [x] HX19 (2026-10-28, f2a5a7d, local) Web TV: "Something went wrong" card came up while a 3D video loaded and the
  remote could not reach Reload or Close (the player kept focus and keys). Now the card takes the remote's keys.
  The failure itself did not come back on a reload (caught nothing in 40 s over WebView DevTools: adb forward
  tcp:9333 localabstract:webview_devtools_remote_<pid>, Python websocket client with suppress_origin). Due: catch it.
- [x] HX20 (2026-10-28, 7dd3b55 merged; asked 2026-10-28) Shows lack features the single videos have (Watch later and others): audit the show
  page, season and episode level against the video page on phone, TV, desktop, web, and add what is missing.
- [x] HX21 (2026-10-28, 95cd783 777dc0a merged; asked 2026-10-28) Kept data pointing at files or messages that are gone (deleted from the chat, a
  removed download, a cleared cache, a moved storage folder): Watch later, Continue watching, Watched, bookmarks,
  downloads, posters and caches must handle it on every platform; audit and fix.
- [x] HX22 (2026-10-28, 92eb67a 081b1a0 84b2dcf merged) TV remote reaches every banner, card and toast with buttons
  (TvReach.kt, web TV update notice and Undo), OK on the shape button keeps focus. Device checks due.
- [x] HX23 (2026-10-28, ece0b8b 2953b02 merged) A session Telegram ended while the app ran stayed "Ready", every
  request refused, and the banner guessed "blocked, use a proxy" (Mi TV). Now SessionCheck/LineProbe notice it and go
  to sign in; proxy suggested only with evidence. Ended session clears data like Sign out (asked the user whether
  watched and Watch later should survive). Device repro due: terminate the TV session from Telegram's Devices.
- TV 3D pass 2026-10-28 (169fff9, Mi TV, Toy Story 3D Half-SBS): new remote keys KEYCODE_TV_ZOOM_MODE (shape) and
  KEYCODE_3D_MODE (off, one eye, VR, red-cyan) drove all 32 cases by adb: 3D off, one eye, VR (per eye in each half)
  and red-cyan, each with Original, Fill, Stretch, 16:9, 4:3, 21:9, 2.35:1, 1:1: all PASS, shape kept across 3D
  changes, VR subtitles per eye. Shots in the session scratchpad tv/m/. HX23 changed: an ended session keeps
  watched, Watch later, bookmarks and settings (user), only cache and snapshots go, no setup again.
  Still due: frame drop numbers with 3D on, top-bottom file, phone build 169fff9 (phone was unplugged).
- Device pass 2026-10-28: phone OK (panel one tap, shape page, 4:3 box). TV: Toy Story 3D (Half-SBS) plays; the 3D
  matrix could not be driven over adb Wi-Fi (row hides between keys); offered the user a hands-on pass or an adb
  test hook in the preview build.
- [ ] HX17 (asked 2026-10-28, web) The apps' UX and keyboard shortcuts the web lacks: list the desktop's
  and phone's shortcuts and gestures, compare with the web (player and browse), add the missing ones.
  Touchpad swipe on the Home carousel is done (HX15 session 1).
Per AGENTS.md, each one gets its per platform plan and edge cases written down before code, and is tested
on the real phone and the emulators (TV, desktop) before it is called done. The release cadence guard
applies to the hotfix: 1.28.1 shipped 2026-10-24, so say so to the user and get their confirmation before
tagging 1.28.2.

Status 2026-10-26:
- [x] HX3 (2026-10-26, d5683c4, merged a8b1a43, pushed) Seek preview and time chip fixed. Desktop: the hover
  picture was cut to a thin strip by the 28 dp bar, and the chip sat on the times and ran off both ends; now one
  card (picture over its time chip), inside the bar's ends and clear of the times, a whole chip alone when there
  is no storyboard or frames or the scrub setting is off (`SeekPreview` in `StoryboardPreview.kt`; a map that
  needs the length is retried once mpv knows it). Phone and TV: the card is 8 dp off the screen edges and clear of
  the times line (phone) and the title (TV, it covered the title). Tests: `SeekPreviewShotsTest` (desktop, both
  edges, narrow and wide, light and dark, frame, storyboard, time only), `SeekPreviewCardTest` (Robolectric, phone
  both ways, TV); checked on the promo phone (both orientations) and TV emulators. Not seen: a real Telegram
  storyboard map (the signed-in phone AVD `tmplayer_phone_api36` is logged out, TDLib WaitPhoneNumber: ask the
  user to scan its QR before the next pass that needs it), the real desktop app with mpv and a live mouse, a real
  D-pad drag.
- [x] HX1 (2026-10-26, f296d6d, ca11dd2, merged 3f64b7d, pushed) ProGuard, not kotlinc: its LivenessAnalyzer
  tracks only a method's first 64 locals (proguard-core MAX_VARIABLES_SIZE), SeriesPanel used about 98, and the
  long `remember(art.chatId, art.messageId)` keys in slots 68 to 71 were written as a long at the
  `meta?.backdropUrl` branch after slot 69 was reused, so the release build failed verification on whatever first
  touched SeriesKt (a Home shelf, a chat grid, the detail page's ResumeLine). ProGuard 7.10.0 does not fix it.
  Longs moved out of SeriesPanel (`rememberSeriesArt`, `rememberSeasonButtons`, `SeasonButtons`). Guard:
  `ClassLinkCheck` links every com.tmplayer class (4478, about 2 s) in `:desktop:verifyReleaseClasses` (before
  `createReleaseDistributable` and every `packageRelease*`), in the packaged `--self-test` (`classes` step, so CI
  and release.yml check each installer on its runtime) and in `ClassLinkCheckTest`; it fails on the 1.28.1 code.
  Release image checked on Linux in a nested headless sway, signed in: Home shelves, a show page, chat grid wheel
  scroll, the wide detail page with a resume line (old code crashed on Home with the Sentry trace). Still open for
  1.28.2: the CI self-test's `PASS classes` on Windows, Linux and macOS (run 40340563618), the win11 VM check
  once no emulator runs (Home shelf, grid scroll, detail page), a release note line, the user's go (cadence
  guard: 1.28.1 shipped 2026-10-24). Then comment on GitHub #15 and #12. The Mi TV carries HX2's test build (versionCode 12802, name 1.28.1-hx2, release key): install the official 1.28.2 APK over it with `adb install -r` once released.
- [x] HX2 (2026-10-26, a58fd1e 42e32a5 33e1e5b, merged 0912a87) Cause, measured on the Mi TV with 1.28.1: the
  WEB-DL copy of "The Paradise" is 1080p AV1 10-bit, no AV1 silicon (4x A53 1.416 GHz, 32-bit), so dav1d in software:
  175% of a core, 0.36x real time, 43 underruns in 69 s, 85% janky frames; ExoPlayer:Playback at 82% is NextLib's
  single-threaded 10-bit to RGBA `sws_scale` (`ffvideo.cpp`). The 720p H.264 copy on OMX.amlogic.avc: 36%, 1.0x, 0.
  E-AC-3 was hardware in both. ANR: the first ICU LocaleData load on the main thread waiting on a GC while TDLib
  was busy, and `PlatformText` built a NumberFormat per call. Fix: locale warm-up off the main thread at start and
  on language change, number formats cached per thread and locale; `DecodeJudge` (core: Media3 MediaCodecInfo
  hardware answer, one software budget table by codec, bit depth and pixel rate scaled by cores, clock and
  bitness) with `DecodeMemory` per device and kind (settings, not in backups); `VideoCopies.pick` skips copies
  too heavy here unless picked by hand, Play says which copy and why with Change; copies lists say "Smooth on this
  TV" or "May stutter on this TV"; a too heavy start moves to a smooth posted copy or asks (lighter copy, Play
  anyway, do not ask again for this kind); `PlaybackWatch` reads the first 12 s of playing (ticks only while
  playing, stops after its answer), offers "Stuttering? Switch" once (focused on TV) and records the result;
  casting skips it all. Promo `PromoDecodeBenchActivity` and `--es feedback stutter|passed|ask`. Tests:
  `DecodeJudgeTest` (16), `:core:jvmTest`, `:ui:jvmTest`, `:app:testDebugUnitTest`; TV emulator matrix in
  `private/PLAYBACK-TEST-MATRIX.md` section 12; warm-up done 1.24 s after process start, off main. References
  (Kodi, VLC, mpv-android, Just Player, Media3) in the agent report: none of them predicts or warns. Left as
  follow-ups: HX2b, an opt-in "could not keep up" Sentry event with a privacy page line; HX2c, a cheaper dav1d
  in the media3ext AAR (NextLib sets AV_CODEC_FLAG_LOW_DELAY for AV1, which turns off dav1d frame threads; the
  RGBA conversion could be libyuv, threaded sws or a YV12 window; film grain export), a native rebuild, none of
  it makes 1080p AV1 10-bit real time on an A53. Test APK: `~/Downloads/TMPlayer-1.28.1-hx2-12802-armeabi-v7a.apk`
  (release key, 12802), never installed on the Mi TV, which still runs 1.28.1 untouched; promo build and clips
  removed. The video's resume moved from 53:53 to about 1:11:12 during testing.
- [x] HX4 (2026-10-26, e08e475, pushed) The gap was the Android TV launcher's Play next row ("Continue
  watching" on Google TV), written by `WatchNextPublisher`: every entry wore one fixed image, the app's mark
  (`watch_next_poster`), because nothing handed the launcher a readable copy of the video's picture. Now each entry
  carries the online wide picture or else Telegram's thumbnail (downloaded first if needed), copied into
  `TvArtProvider`'s folder as a `content://` URI; the mark only when neither exists, and `fillMissingArt` repairs
  those 30 s after Home opens (never under a playing video, at most 6 per visit, stops by itself). Shared picture
  code in `TvArt.kt` with per publisher prefixes so one's prune no longer deletes the other's; channel cards whose
  copy is gone are rewritten. Home's own Continue row was fine. Kept as decided 2026-10-23: a channel card with no
  online wide picture is left off (ask the user whether to fall back to the Telegram thumbnail). Tests:
  `WatchNextArtTest` (10, Robolectric, the provider read as another caller, late thumbnail, evicted copy, removed
  entry, path refusal), `TvChannelsTest`, `WatchNextTest`.
- [x] HX2b (2026-10-26, 73ae133, pushed a84e604) Opt-in "Video could not keep up" Sentry warning when
  PlaybackWatch says CANNOT_KEEP_UP (not while casting): only format tags (codec, profile, depth, size, fps,
  bitrate, container), decoder and hardware or software, DecodeJudge's prediction, speed, dropped and total frames,
  underruns, device make, model, Android, cores, ABI, app version; text filtered to safe characters, run through
  `CrashReportScrubber`, fingerprint by kind and model, one per kind per device per day (`crash/stutter.json`), its own
  budget; nothing built or kept with crash reporting off. Privacy page paragraph and change entry (English only
  page, no locale catalog). `StutterReportTest` (8); site builds. Device check: one event per heavy kind per day
  with reporting on, none with it off, decoder name filled, no stray SDK tags.
- [x] HX6 (2026-10-26, 13b30c1 ef84f66, pushed f3a16a8) Removed the Kotlin/Wasm web app: `web/`, the wasmJs targets
  and source sets in `:core` and `:ui`, `kotlin-js-store/wasm/`, `docs/WEB.md`, `.github/workflows/web.yml`, the
  Binaryen repository and Wasm Node blocks, `kotlinx-atomicfu` and `kotlinx-browser`, the web section of
  THIRD_PARTY_NOTICES and the tdweb clause of LICENSE-OPENSSL-EXCEPTION, plus the unused `TdGateway` and
  `JvmTdGateway` (about 19,000 lines, 6.7 MB). Kept: the `:core` js target and `:core:webRules` for webapp/. Left:
  `LocalVideoBehindPage` in the desktop Shell (always false now, its `seeThrough` branches are dead), the R8 and APK
  size comparison, the untracked root strays. Checks: core and ui JVM tests, desktop and app compiles, webRules,
  webapp build, site build. Cloudflare's old deployments stay for rollback.
- [x] RL1 (2026-10-26, fc6d9d3 + f6f2a03, pushed) Pre-releases: `vX.Y.Z-beta.N` tags (N 1 to 98) publish as GitHub
  pre-releases, never latest, with a test version line; for a beta the workflow skips `latest.json`, the site commit
  (feed, releases.json, /apk, PKGBUILD), AUR, IndexNow, the Telegram post and winget. versionCode
  major*1000000 + minor*10000 + patch*100 + stage (beta N = N, final 99; 1.28.2-beta.1 = 1280201, 1.28.2 = 1280299);
  MSI ProductVersion and CFBundleVersion x.y.(patch*100 + stage), CFBundleShortVersionString x.y.z; a beta AppImage
  uses the `latest-all` channel. One rule in `core/.../ReleaseVersion.kt`, copied in `scripts/release-version.py`
  and both build files, one shared case table (`release-versions.txt`) checked by the Kotlin test and `--check` in
  the workflow. "Get test versions" in Settings, About on phone, TV and desktop (off, a warning on turning it on,
  stays on its device, not in backups): reads GitHub's release list too, the newer wins, a tie goes to stable, an
  installed beta is always offered its final. F-Droid tag pattern written in docs/RELEASING.md (no recipe in the
  repo). Betas do not count for the cadence guard. Tests: ReleaseVersionTest, UpdateFeedTest (29),
  UpdateSchedulerTest, SelfUpdateTest, VersionCodeTest, aapt2 badging of a beta APK. Cut: `git tag -a
  v1.28.2-beta.1 -F notes.txt && git push origin v1.28.2-beta.1`, approve the release environment; later the same
  for v1.28.2 (store changelog file `1280299.txt`). Known one-off: a 1.28.1 Windows install updating itself to 1.28.2
  looks for ProductVersion 1.28.2, finds 1.28.299, runs msiexec again (about 15 s) and leaves a failed marker that
  1.28.2 clears on start; confirm on the VM. winget's manifest will need `DisplayVersion: 1.28.299`.
- [x] HX5 (2026-10-26, f13ab8c, pushed) The GitHub APK installs its update through a PackageInstaller session
  (`SelfInstaller.kt`, rule `SilentUpdateRule` in `SelfInstallRules.kt`, credited to Morphe's `allowsSilentUpdate`):
  no dialog on Android 12+ when TMPlayer updates itself, the APK's target SDK qualifies (29 on 12, 30 on 13, 31 on
  14, 33 on 15, then platform minus 2), on 14+ the update owner is empty or com.tmplayer, and install permission is
  granted; `UPDATE_PACKAGES_WITHOUT_USER_ACTION` declared (both install permissions removed in the fdroid manifest).
  A refusal is remembered per Android release (`update_silent_refused`) and falls back to the question; a killed
  session to the old installer screen; Android 6 to 11 keep the system installer. Each status explained (declined,
  space with needed and free, different key with the release page, incompatible). The APK must match package and
  signer (SHA-256, rotation accepted) with a higher versionCode; free space 2x the APK plus 32 MB checked first;
  sessions abandoned on every exit, the APK deleted. After a session install `MY_PACKAGE_REPLACED` posts "TMPlayer
  updated to X, tap to open" (background activity starts are blocked from Android 10), "Updated to" once on open;
  the player saves its place before the install ends the process. "Install updates by itself" (Android 12+, GitHub
  build, off): app in the background 3 minutes, nothing playing, casting, downloading or saving, unmetered and
  charging or 30% battery, silent only, once per version then 12 h, follows Get test versions. The preview build
  now refuses the real package's APK. Tests: UpdateInstallTest (14), SelfInstallRulesTest (16), AfterUpdateTest (4).
- [x] HX2c (2026-10-26, 85c5290 3f3d4ef, pushed 73c41e0) Light video mode in the media3ext patch (AAR tm2, in
  `build/media3ext/` locally, all four ABIs) for pictures `DecodeJudge.wantsLightDecode` says are too slow in
  software (never a hardware decoder, phones that keep up unchanged; set through
  `FfmpegLibrary.setLightVideoPolicy` by reflection): dav1d frame threads (`max_frame_delay` 2, try others with
  `setprop debug.tmplayer.av1_frame_delay 3`) instead of LOW_DELAY, film grain skipped, a YV12 window with a NEON
  10 to 8 bit copy instead of sws RGBA (RGBA after the 8-bit step where a window refuses YV12 and on emulators);
  `logcat -s ffmpeg_jni` says "light mode". Host bench (`scripts/media3ext/bench`, 4 cores): decode 68.8 to 174.5
  fps, with conversion 40.3 to 164.1, conversion 10.75 to 1.76 ms a frame, a 48-frame seek 714 to 274 ms, 32 MB more
  memory; the last 2 frames of a file are dropped (as FFmpeg's own frame threads do). Next release attaches
  `nextlib-corresponding-source-1.11.1-0.16.0-tm2.tar.gz` (`scripts/build-nextlib-source.sh`), then set `TAG` in
  release.yml. Found: v1.28.1's notes link `...-tm1.tar.gz`, which was never attached (a GPL source gap on a
  shipped release); being built from the v1.28.1 tag for upload on the user's go.
- [x] HX9 (2026-10-26, c53ee43, pushed) "Picture in picture when leaving the app" (`pip_on_leave`, default On,
  travels in backups next to background_audio), in Settings, Player after Background audio, phone only (hidden on
  TV and without the PiP feature). Off: no `onUserLeaveHint` entry on Android 8 to 11, `setAutoEnterEnabled(false)` on
  12+, applied live with a video open; leaving then pauses, or keeps sound with Background audio, and the place is
  saved (onStop, unchanged); the manual PiP buttons ignore the setting. Rules in `PictureInPictureRules.kt`, tested
  by `PictureInPictureRulesTest`. Desktop and web enter PiP only by hand, so nothing added there; note for web 13:
  gate any `autopictureinpicture` or Media Session `enterpictureinpicture` behind a web setting with the same words.
- [x] HX8 (2026-10-26, ef16443, merged) The reporter's log shows Home's first row in about 4 s; the hours were the
  HX1 crash reopening onto Home. The synthetic 3,000-chat account (`SyntheticAccount`, `HomeScaleTest`) found two real
  scale faults: the chat list waited for folder membership (TDLib reads the whole account for a folder: 200 s and 49
  getDialogs cold, now 1.6 s, folders after), and Home had no TDLib budget (4,042 calls, 2,420 flood waits and 2,340
  429s an hour, now 442 calls and none). `HomeFetches`: 3 at a time, the first 8 rows up front and the rest when
  shown, each row queued once, retries at 5, 20, 60 s then stop, a flood wait holds only its row, post-triggered
  refetch at most every 2 min. A 4 s watchdog shows what is there with "Telegram is slow to answer" and Retry
  (desktop). Home no longer waits for the chat list (HomeCache or its own starred and found chats, at most 64; the
  desktop waits at most 3 s for starred chats). Retry continues the chat list load. `CrashLoop` (desktop): two runs
  that crashed within 2 min open the third on Chats. Search flood retries capped at 2; web Home waits for the first
  300 chats; the phone refreshes Home in place on reconnect. Trade-offs: followed chats' new posts reach Home up to
  2 min later; phone and TV Home work waits while Home is hidden (TV channels catch up when Home shows); tile chat
  names blank while the list loads on phone and TV. AGENTS.md has a Scale rule. HX8b left: a slow line on phone and
  TV, chat names while the list loads, incremental grouping for a 20,000 video chat (11 to 15 s CPU to scroll to the
  end on desktop), the web reading every starred chat, real flood limits unknown.
- [x] HX10 (2026-10-27, 693ba1f fb83ac9 9d383af, merged) Crash hardening, kept to what was needed (user: skip
  what already handles errors well). Safety nets: `JobFailures` (a CoroutineExceptionHandler that logs, reports
  through the opt-in crash reporting and lets the scope go on) on Background, Td, CastRecovery, OnlineMetadata,
  TdByteWindow, Toast, every view model, DownloadService, FolderExport, BackgroundChecks; `guardedLifecycleScope` for
  Android activities (any of 50 `lifecycleScope.launch` used to kill the process); `onPlayerError` wrapped; desktop
  `WindowRecovery` (LocalWindowExceptionHandlerFactory) rebuilds the window with an error card (Try again, Settings,
  Save logs, Report on GitHub) instead of Compose's dialog and quit, never restarting by itself, giving up only
  after 4 failures a minute, a failure in the card or OOM; web `src/crash.ts` Reload card and a worker restart (3 in
  5 min). Fixes with evidence: corrupt or unwritable DataStore (`SafeDataStore`: a truncated preferences file
  crashed every Android launch, a full disk threw from about 150 setting writes), cache writes on a full disk
  (MetadataCache, SubtitleCache), StoryboardMaps and WebVtt number overflows, URI errors in UrlConnectionTransport,
  mpv errors -2, -13 to -18 in words (`MpvErrors.kt`, GitHub #14: -17 says the format is not recognised, may not be
  a video, download first), mpv errors after the start reach the error sheet. Checked and fine: UpdateFeed,
  Backups.decode, KeyValueCodec, HomeCache, ChatGridDisk, CrashRecord, MetaApis, fromJson models, TelegramLinks,
  MediaName, MediaMapper, no GlobalScope, all 33 init blocks, ExoPlayer errors. Left: desktop engineFactory in
  remember without a try (the card shows), ScoreItem NaN (no live path), other mpv codes raw, odd data smoke tests
  dropped after the trim. Tests: GarbageInputTest (about 40 parsers and 6 providers, a corpus plus 300 random
  inputs), SafeDataStoreTest, JobFailuresTest, WindowRecoveryTest, MpvErrorsTest. AGENTS.md "Error handling".
- Queue finished 2026-10-27 about 01:40: every task built, merged and pushed (main 7fb3c7d, CI and the Web player workflow green on it: Windows, macOS, Linux installers self-tested, Chromium and WebKit e2e). Found by CI and fixed in 7fb3c7d: a failed TMDB lookup (no key, TMDB down) remounted the web video page over and over through the error boundary. Last full checks on
  the merged code: `:core:jvmTest`, `:ui:jvmTest`, `:desktop:test`, `:desktop:verifyReleaseClasses`,
  `:app:testDebugUnitTest`, `:core:jsNodeTest`, webapp tsc, build, `test:player`, `test:tmdb`, Playwright 245 of 245
  over chromium, phone-390, phone-412 and desktop-1280. The Kotlin/JS lock `kotlin-js-store/package-lock.json` (the
  js target's, removed by HX6 by mistake) is back (04f8e21). Known CI flake: `PlayerMouseTest` "the position is
  written ... every ten seconds and on pause" timed out once on the Windows runner (a8e9c73), green on later runs.
  The tm1 source archive was uploaded to v1.28.1 on 2026-10-27 (sha256 3c47e585, the notes link answers 200). Next for the user: the Device pass list below, then `v1.28.2-beta.1` (RL1 commands), then stable 1.28.2. Open
  questions for the user: a Sentry read token (event:read, project:read) for the full crash
  list; whether channel cards with no online wide picture should fall back to the Telegram thumbnail (HX4). Later:
  HX7 (waits on #14's reporter), HX8b, 13b, web 14 (smart TV files).
- More agents (user, 2026-10-26 21:00: "kill if any unnecessary process are running and utilize more agents if
  required"): four idle Gradle and Kotlin daemons (about 13 GB) stopped; four agents at once while memory allows
  (RL1, HX6, web 19, web 23, HX2c, HX5, HX8; then web 21 and 19b, all done or running by 22:50), memory checked before each start.
- Queue (user, 2026-10-26 late: "do all things first then we can test out for issues"): every task is built and
  committed with code, builds and automated tests only (unit, Robolectric, desktop render, Playwright in
  headless Chromium); no emulator, Mi TV, phone or VM until the whole queue is done, then one device pass by
  the user with a combined checklist. Two agents at a time, memory checked before each start. Order: HX2 and web
  20 (both done and pushed 2026-10-26, 0912a87 and 239f56e), HX4 (Sonnet high, done e08e475), RL1 (Opus high, running) then HX5 (Opus high, after RL1: both touch the updater), HX2b (Sonnet medium), HX6
  (Sonnet medium), web 19 (Opus high), 23 (Opus high), 21 (Sonnet medium), 22 (Sonnet medium, device parts
  deferred), 13 (Sonnet medium); 14 (the .ipk and .wgt) put back to later by the user 2026-10-26, after hearing why it is the riskiest (old TV engines, mtcute on Chromium 68 and 79, secure context without a deploy, no real TV), then HX2c (Opus high, native media3ext rebuild).
  HX7 waits on the reporter's log. Each agent ends with a "needs a device check" list; the coordinator gathers
  them under "Device pass" below.
- HX7 waits on the reporter's log.
- GitHub (checked 2026-10-28): #15, #12 (HX1), #13 (desktop Layout: TV) and #5 (MSone) closed as shipped in
  1.28.2. Open: #14 mpv_error=-17 still waits on a log (HX7, asked again); #16 TV Home banner focus not
  visible below Start and Left/Right needing two presses (Mi TV box, 1.28.1), asked to retest on 1.28.2.
- GitHub (checked again 2026-10-31, 3 open issues, 1 discussion, no open pull requests): #16 is now HX42, the
  reporter confirmed on 2026-10-30 that 1.28.2 did not fix it and the cause is found in the code (no video
  needed, one decision from the user first). #17 "STORAGE LOCATION" (SD card) is already HX26's scope, so it
  needs a reply saying so, not an item of its own. #14 is HX7 and still waits on the reporter's log after two
  asks (the 26th and the 28th): nothing to build, do not ask a third time. Discussion #10 "Hashtag" is now
  HX43 (tags are not bookmarks, which the reply on the discussion guessed at and should correct).

Progress / handoff, session 3 of 2026-10-27 (main local at da823f0, NOT pushed):
- Done: size filter default 400 MB with quick picks (642f931); phone/TV/desktop "n hidden" button opening the smallest size picker, one-off toast gone (de916c5); Settings > Library one Clear history row with Undo on phone and desktop, TV confirms first (18cf6b3); web: n hidden chip + picker, 400 MB default, Clear history row with 8 s Undo (c066a52, 261da95, merge da823f0). core + ui jvm tests pass, desktop and app compile, webapp tsc clean.
- Not done: device pass of these on phone and TV; web Home carousel rules and History per-video selection not ported (web Hero does not use HomeFeatured); new en.json keys untranslated (grid.size_hidden, size_hidden_label, size_quick_note, size_default) plus the earlier ones; unused strings list now also has settings.clear_history*, settings.clear_watched*, settings.history_cleared, settings.watched_cleared, settings.continue_clear*, browse.hidden_by_size_notice, watched.clear_*.
- Release: user said go; v1.28.2-beta.1 published 2026-10-27 from da823f0 (tag only, main NOT pushed so the site does not deploy early), attestation verified.
- After beta.1, merged on main (34f6aae, not pushed): translations of all 20 locales + 46 unused keys removed (7495c87); web posters priority queue, light w300 first, sized card posters, detail waits for film/show, cover gradients, title under cards (ef422e1, 43ae6a0); web carousel rules + History removal (01ba320, 7db8793, c7e30db); seek preview on every platform with skeleton and fallback, storyboard download no longer cancelled by each move (Android 28f6191, desktop+web 34f6aae). Mi TV: bar scrub shows the card (fallback face, no storyboard on that MKV); arrow jumps over the bare picture show no card by design (asked the user); fallback had no thumbnail when opened from the detail page's Resume. Phone, desktop and web previews untested on devices.
- Next: tag v1.28.2-beta.2 (blocked by the permission classifier, needs the user's OK), phone pass of the preview, push main on release day.

Progress / handoff, session 2 of 2026-10-27 (session c651e285, main local at 8ecb8e5, NOT pushed):
- Merged and committed: TV Chats in two columns with Back restoring focus (7a869f4, merge f3b562f); web Solid createRoot warning fixed (three JSX props read from the virtualizer) plus AVI e2e cases (4c403af, merge 862a792); desktop Settings/Downloads panel has its own close (X quit the app) and no crash on a scrim click (duplicate saveable key "Home"; 8eefac1); carousel lone episode stands for its show (58dd5cf, test in DesktopHomeMixTest); online poster over a soft copy when no wide picture, desktop wide detail page shows the picture whole (863df19); desktop Layout Desktop/TV, Auto removed (ec19c5c); desktop poster hover instant, warm-up still 300 ms (d6e8637); backdrop field grown to 256 px so the Mi TV shows no blocks (8ecb8e5).
- Device pass done on 1.28.2-dev (versionCode 1280200, release key, login kept; phone arm64 and Mi TV v7a with TV Chats): phone Home first rows by 3 s, start 443 ms, player first frame 1.0 s, PiP On enters PiP, Off pauses with no PiP (setting put back On), seek drag chip and fine scrub hint fine (no storyboard frame on that file); TV start 4.2 s, Continue by 8 s, Home full about 20 s, Chats two columns focus fine, The Paradise 720p on OMX avc hardware, first frame 14.4 s at 548 KB/s, idle CPU 1.5 % after playback (re-measure; was 0.2 %). HX4 not testable: the user's launcher shows no channel rows (needs Customize channels by the user). Moved the user's place in The Paradise from 1:41:32 to about 1:46:34 by mistake; "Back to 1:43:36, Up to go back" then Up did not jump back (check whether Up is consumed by focus).
- Agents running at handoff (worktrees, merge when they report): History and Continue watching removal (long press, checkboxes, one click Clear history with Undo, "Remove from history" wording everywhere, confusing wording sweep); Home customization (row switches, Recently added from chosen chats); quick UX fixes from the review (avatar letter, raw caption line, TV skeleton shape and heading flip, player line shows chat, Loading chip, "Video" 1 min film in carousel).
- Written: `private/UX-REVIEW-2026-10-27.md` (per platform tables, Do next 15).
- Later the same session: merged History removal (acb5b55), Home customization (acf2f5b), quick UX fixes (ad6957b, one HomeRows conflict resolved by hand, merge 4cc1f25); Undo line 8 s on Android and Watch later wording (5246be8). Final 1.28.2-dev (1280200) on phone and TV: long press Remove from history and Undo work (an Undo tapped after the old 5 s lost Pulimada's place on the phone, at about 1:24:28), TV hold menu has Remove from history, carousel shows "Series / Play S01E02". Never build local APKs with code 1280201: that is beta.1's.
- Merged at the end: carousel rules (films and shows only, 400 MB floor, only pages with a wide picture and an overview, logo first, hidden when none, fill waits at most 1.5 s; merge 171bef4) and one layout per page (poster grid in a chat, chat rows on TV Chats, rows in History; the switch, its settings and code removed on every platform; merged after). Full core and ui jvm tests pass, web tsc clean. Final 1.28.2-dev (1280200) installed on phone and Mi TV: carousel shows only complete pages with logos, chat page is the poster grid with Star/Search/Sort, TV Chats two columns with "Y" for "(YEDHU)", no TMPlayer crash (the hourly FATAL in the TV's logcat is Xiaomi's com.mitv.download.service). Seen once: the phone showed "Can't reach Telegram right now" for the first seconds after the install, then connected.
- Next session: the 400 MB size filter default and the "n hidden" chip with a quick size modal (needs the user's go); drop the unused strings in the next translation pass (`browse.as_rows`, `as_tiles`, `show_as_rows`, `show_as_tiles`, `rows_short`, `tiles_short`, `browse.forget_resume*`, `confirm.forget_resume_*`, `browse.clear_continue*`, `browse.clear_watched*`, `browse.mark_unwatched_detail`, `continue.remove`, `watched.clear`, `main.forgot_resume`, `main.history_cleared`); Settings > Library still has the old separate clear rows; translate all new en.json keys into the 20 catalogs; then the release cadence check before v1.28.2-beta.1. Nothing pushed yet.
- Waiting on the user: size filter default 400 MB with an "n hidden" chip and quick modal (was 400 MB until 1.21, lowered to 50 MB for missing episodes; proposal sent, no answer yet). Not done: HX10 destructive checks (corrupt datastore, full disk) need the user's OK on their devices; HX5 and RL1 need a beta release; HX2c needs the AV1 copy; web items need a real iPhone. Release: cadence check before v1.28.2-beta.1 (1.28.1 on 2026-10-23).

Progress / handoff, device pass session 2026-10-27 (main c25b29b, pushed):
- Done and pushed: desktop Chats in columns on a wide window (237dbe2), carousel picture static (ab0f19d), desktop detail page full window with Back (3546a8a), History Clear on Continue and Watch later and no zoom buttons (8124d0d), Free up space not dimmed and result as a toast (a5733bf), web fast seek wedge fixed in `FeedEngine` (bounded wait on the last run, 120 ms settle, stall retry; 735667d), web sign in rework (round loader, Scanned tick, life bar, refresh pill; d28237c, not yet looked at on screen), Settings and Downloads as a desktop modal (ab94349, merged, not looked at on screen), desktop TV mode bar over the page like Android TV (25a64d6), web player Play/Previous/Next off centre fixed (c25b29b: `button.tonal` padding leaked into `.pc-btn.tonal`).
- Open from the user this session: TV Chats could use two columns like web and desktop (not changed, needs a TV check); review which Android TV dialogs could be popovers like the desktop's; a detailed per platform UX plan written as a Netflix-style user (no code); emulator and promo build pass looking for broken UI; check the web libav (AVI only) software path with a real AVI; Solid dev warning "computations created outside a createRoot" not traced; then v1.28.2-beta.1 (cadence check first: 1.28.1 shipped 2026-10-23, so tell the user before tagging).
- Gotchas: the local AppImage in ~/Applications is 1.28.2 built before most of this session (rebuild with JDK 21 at ~/.jdks/jdk-21.0.12+8, JDK 25 fails jlink); the web player only opens one Telegram tab at a time; a Chromium with --remote-debugging-port=9222 and the user's login was used to test the web player.

Device pass (for the user at the end; the coordinator adds each finished task's list here):
- HX1: win11 VM with the CI MSI (Home with a show shelf, chat grid wheel scroll, a wide detail page with a resume
  line). CI self-test passed 2026-10-26: `PASS classes: 4480 com.tmplayer classes linked` on Windows (MSI, upgrade
  included), macOS and Linux (run 40340563618).
- HX4 on the TV (signed in, Play next on): a video played over 3 minutes then Home, one never shown on Home and
  one with its thumbnail cached, shows its own picture in the launcher's Continue watching; with online lookups
  on, the wide picture; an entry left quickly gets its picture 30 s after TMPlayer Home opens; no stretching (16:9
  declared for portrait thumbnails); channel cards keep their pictures; Mi TV idle CPU after Home.
- RL1: Windows MSI stable 1.28.1 to beta to beta to final by hand (Installed apps shows 1.28.201 and 1.28.299;
  the CI Windows self-test confirms ProductVersion), the one-off double msiexec from 1.28.1; macOS About shows
  1.28.2 (1.28.201); AppImageUpdate on the `latest-all` channel; phone and TV: the switch, its warning and TV
  focus, a beta APK over 1.28.1, beta.1 to beta.2 to final when opted in, nothing offered when off; the same on
  the desktop.
- HX5: an Android 12+ phone updates with no dialog, the "Updated, tap to open" notification, "Updated to" once;
  Android 14+ update owner path (a build installed by another store asks); the Mi TV (Android 9) keeps the dialog,
  focus after the restart; the POCO on HyperOS (Morphe says it aborts silent sessions: the dialog fallback and the
  next update asking at once); "Install updates by itself" after 3 minutes in the background on Wi-Fi; a debug
  build offered a release APK says it is signed with a different key.
- HX2c on the Mi TV with The Paradise AV1 copy: the "light mode" logcat line, speed, underruns, CPU per thread,
  colours right in the YV12 window (no green or garbled picture), seeking, memory on the 1 GB stick, optionally
  frame delay 3; a phone with AV1 hardware still uses it (no light mode line); a rotated and an odd-sized video on
  the TV (the RGBA path).
- HX8: the reporter on 1.28.2 with a cold start log (data folder cleared): first chat list and Home's first row;
  the crash guard (`-Dtmplayer.crashAfterWindow=true` twice within 2 min, the third start on Chats); the desktop
  slow line with the network pulled at start; phone and TV: Home draws while the list loads, rows past the first 8
  load when scrolled to, TV channels still fill, reconnect does not blank Home, TV idle CPU with Home open, folder
  chips fill after a cold start.
- HX10: desktop, force a screen exception (the card, Try again and Settings work, no flicker loop, window
  controls while a video played); a -17 video on Windows shows the new message (GitHub #14); network pulled mid
  play reaches the error sheet; Android, a corrupt `datastore/tmplayer.preferences_pb` starts with defaults, a full
  disk does not crash on a setting change; a bad storyboard map gives no frames and no crash; web, the error card
  on a forced error, a killed worker restarts, no stray card offline, on abort or autoplay refusal.
- HX9: an Android 12+ phone, an Android 8 to 11 phone and the POCO: Home while playing with the setting Off
  (pauses, or sound with Background audio, place kept) and On (PiP), flipped Off with a video open, the manual PiP
  button still working when Off.
- Web 19b, 21, 13 (local web build): iPhone Settings pages and Back, switches, chat bar order, landscape cards,
  sign in layout, Share on chat info; a real account with TMDB (detail page, trailer link, copies picker; needs
  `TMDB_API_KEY` where the build runs); TV browser: real remote codes, Magic Remote, autoplay blocked, the QR, idle
  CPU, overscan; pinch and fine scrub on a real phone and iOS; Picture in picture on iPhone Safari and desktop
  Chrome; sleep timer on a locked phone; next episode autoplay in Safari; chapters on real Telegram files.
- Web 23 (desktop TV mode): Windows full screen in and out by Back and F11, an MCE remote's Back and media keys;
  a real remote or Flirc on Linux (X11 and XWayland, XF86Back as 166), idle CPU with the pointer hidden, Plasma
  Bigscreen starting `TMPlayer --tv`; macOS full screen, `open -a TMPlayer --args --tv`, Ctrl+Cmd+F.
- Web 19 (local web build): iPhone full screen (iOS's own controls), the fixed watch page with safe areas both
  ways, double tap, hold and drag gestures, the iOS edge swipe, brightness drag, sheets with the notch; Android
  Chrome rotate and gestures against pull to refresh; desktop hover preview with a real storyboard, menus at small
  heights.
- Web 20: a TV's own keyboard with the read only field, real remote key codes (461, 10009, colour keys) and an LG
  Magic Remote pointer with keys, idle CPU of the TV layout.
- HX3: a real Telegram storyboard on the signed-in phone AVD (scan its QR first, it is logged out), the desktop
  app's live mouse hover over the seek bar, a real D-pad scrub on the TV.
- HX2 on the Mi TV with the fixed build: the Play press on "The Paradise" (copy choice and the line naming it), the
  "Smooth / May stutter" lines in the copies lists, the "may stutter" question and the "Stuttering? Switch" offer
  with TV focus and Back, the locale warm-up at cold start (and an Android 11 TV if one is around), the matrix run
  twice (normal, then `--ez software true` to calibrate the budget table); the same on the phone. Note: versionCode
  12802 builds block the real 1.28.2 if installed, so after RL1's new scheme use its numbers instead.

| # | Task | What is known | Done when |
| --- | --- | --- | --- |
| HX1 | **1.28.1 crashes on the desktop (client report; 1.27.1 works).** GitHub #15 (Linux AppImage and AUR, crash after sign in and no start until the data folder was cleared: Home opens from its saved rows) and Sentry 155851218 (the detail page's `ResumeLine`) and 155857767 (Home `ShelfCell`) are the same crash; #12 (1.27.0 `failedChats` NPE, fixed in 1.27.1 by 90a334e) may now be hitting it too. Comment on #15 and #12 when 1.28.2 ships. Log: `private/logs/tmplayer-logs-20261024-1254.txt` (Windows 11, 1.28.1) | `java.lang.VerifyError: Inconsistent stackmap frames at branch target 5371` in `SeriesKt.SeriesPanel` (locals[68] is `top`, the stack map says `long`), thrown while a chat grid composes a `SeriesPoster` (`SeriesViews.kt:173`) during mouse wheel scrolling; logged as `E/Crash: uncaught on AWT-EventQueue-0`, then the app restarts. `ui/.../browse/Series.kt` grew by about 500 lines since 1.27.1 and `SeriesPanel` (line 713) is one very large composable, so this looks like a Kotlin 2.4.21 JVM codegen limit on a huge composable, not a logic bug. Android runs on ART and is not hit the same way, so the JVM desktop builds are the ones at risk (Windows, Linux, macOS) | Split `SeriesPanel` into smaller composables (or find the construct that breaks the stack map); a desktop test or CI step that loads every class of the desktop jar with `-Xverify:all` so a VerifyError can never ship again; the desktop build scrolled through a chat of shows on Windows (win11 VM) and the AppImage; hotfix 1.28.2 prepared (tag only on the user's go) |
| HX3 | **Seek preview thumbnails are broken** | The time chip above the player's control bar looks broken, and hovering the seek bar shows no preview picture. Hover means the desktop first; check the phone's and the TV's chip too | Hover over the seek bar on the desktop shows the preview card with its time, drawn correctly at both edges of the bar; the chip is whole at every width and in light and dark; the TV and the phone checked in both orientations |
| HX2 | **Web-DL movies lag on Android TVs** | User repro (2026-10-26): on the Mi TV, the video "Paradise" in the "Cinema Paradiso" chat has several copies; its WEB-DL copy was "super laggy" and the user switched to another copy to watch. Measured 2026-10-26 while it played: the WEB-DL copy is AV1 decoded in software by dav1d (four dav1d-worker threads about a third of a core each, ExoPlayer:Playback over a full core), the Mi TV has no AV1 hardware decoder. Fix direction: prefer a copy the device decodes in hardware (MediaCodecList capabilities), warn and offer the lighter copy when only a software-only copy exists, never block; references from Kodi, VLC, mpv-android, Just Player, Media3; a codec by size by audio matrix run on the Mi TV and the TV emulator, results in the playback matrix. UX agreed with the user (2026-10-26): Play picks the smooth copy by default with a 6 s flash and Change; the copy picker marks each copy "Smooth on this TV" or "May stutter on this TV" and keeps a hand pick; only a heavy copy gives one short dialog before play (lighter copy, Play anyway, do not ask again for this kind), never on a device with the hardware; a watchdog in the first 10 to 15 s offers a smoother copy once in a non-blocking bar; results remembered per device and kind; opt-in "could not keep up" reports with codec, size and model only. Sentry 156073936 (2026-10-25, 1.28.1, Skyworth FlipkartTV, Android 11): an ANR with the main thread inside a Choreographer frame callback, stuck in the system's first ICU locale data load (`initLocaleDataNative`, waiting on a lock in `Integer.valueOf`) while TDL-Receiver-Thread ran; the TDLib thread's frames came through as bare method names with no classes, so they could not be retraced even though the 1.28.1 mapping was uploaded. Suspects: a first `String.format`, `NumberFormat` or date format in composition on a slow TV (warm it off the main thread at start), GC pressure. No web-dl log yet; the lag is on the TV sticks (1 GB class). Suspects to measure, not guess: the codec and bitrate of web-dl files (HEVC 10 bit, high bitrate), the decoder the player picks, the download window and read-ahead, idle work on the main thread | A web-dl test file reproduced on the TV emulator and the Mi TV over adb Wi-Fi with CPU measured (`/proc/<pid>/stat`) before and after; the cause written here; plays without dropped frames or stalls on the slowest TV; no regression on the other files in `PLAYBACK-TEST-MATRIX.md` |
| HX8 | **Home takes hours to load on the desktop for an account with many chats (user report, Windows, 2026-10-26)** | Reporter log (2026-10-26 22:20, 1.28.1, `private/logs/tmplayer-logs-20261026-2220-new.txt`): Home's first rows came in 4.4 s and 3.8 s, then the HX1 VerifyError crashed the app as a show was drawn and it restarted into the loading screen, so the "hours" was most likely a crash and reload loop on 1.28.1, fixed by HX1; the scale budgets stay wanted. The screen it sat on: "Loading your chats…" (`browse.loading_chats`) with "Telegram is slow to answer" and Retry (`common.slow_answer`), so Home was gated on the chat list itself (9f0ed2f, 6cd78fb: Home waits for the chat list). A user with a lot of chats, many of them video chats, waited hours for Home on Windows. No log yet. Suspects to measure, not guess: Home fills itself across every chat with videos (119757d: starred chats first, then the chats the search across chats finds most videos in, each chat its own rows), so the TDLib calls may grow with the number of chats and pages; sequential calls, FLOOD_WAIT answers waited out one after another, retries of failed rows (`failedChats`, `retryFailed` on reconnect), Home waiting for the whole chat list, the disk cache of rows growing without a bound, grouping or matching work that grows faster than the number of videos. User: if it is stuck, after a few seconds show what is there and refresh, and check the same kind of performance problem everywhere; "scalability should not be an after thought" | Home shows its first rows within a few seconds and finishes within a fixed budget on an account with thousands of chats and a hundred thousand videos, measured with a synthetic large account (a fake Td in tests) and written down: time to first row, total, TDLib calls, memory; a stall shows what is there and refreshes by itself; FLOOD_WAIT handled without blocking the rest; the same budgets checked for Search across chats, the Chats list, a big chat, Downloads, History, the TV channels and the web player; budgets written as a rule in AGENTS.md so new features keep them |
| HX9 | **Picture in picture cannot be turned off (user's friend, Android, 2026-10-26)** | Leaving the app while a video plays puts it in the corner: `onUserLeaveHint` on Android 8 to 11, `setAutoEnterEnabled` on 12+ (`PlayerActivity.kt` around line 5600), phone only, with no setting. The user wants an option on every device that does it by itself | A "Picture in picture when leaving the app" switch (default On, today's behaviour): Off means no automatic PiP on any Android version (pause, or sound only with Background audio), the manual PiP buttons still work, hidden on TV; the desktop and web gated the same way where they enter by themselves; tests; device check on Android 12+ and 8 to 11 |
| HX10 | **Crash hardening everywhere (user, 2026-10-26: "a lot of major crashes are happening, make sure everything is properly error handled and stuffs are not breaking")** | Known: the 1.28.1 desktop VerifyError and its follow-on "layout state is not idle" at start (HX1), the TV ANR (HX2), the 1.27.0 failedChats NPE (fixed 1.27.1), GitHub #14 mpv_error=-17, the crash and reload loop (HX8 guard). The local sentry-cli token is CI only (org:ci, 403 on reading issues), so the full Sentry list needs a read token from the user | Safety nets per platform (desktop window exception handler and error card with Try again and Send logs, Android CoroutineExceptionHandler for app scopes and ViewModels, the web player's onerror card and worker restart); a ranked static audit with fixes (`!!`, lateinit, empty lists, outside text parsing, JSON without fallback, effects and collectors without handlers, init order, TDLib and mpv and ExoPlayer errors, file I/O); garbage input tests for every parser and smoke tests over every screen with odd data; an error handling rule in AGENTS.md |
| HX4 | **Continue watching shows no image in the channels section** | Not yet pinned down which surface: the TV launcher channel (Play Next / the TMPlayer channel) or the chats rows on Home. Find it at repro before changing code | The picture shows for every Continue watching entry where it fails now, on the surface where it was reported, with a video whose thumbnail is not yet downloaded and one whose is; the other surfaces checked for the same gap |
| HX7 | **Desktop: "mpv playback failed (mpv_error=-17)" on Windows** | GitHub #14 (1.28.1, Windows 11, MSI, es-419, a video in a chat named "Mundial (TVGRAM)"). -17 is `MPV_ERROR_UNKNOWN_FORMAT`: mpv could not tell the format, so the bytes it got were not a video it knows (a stream or document that is not a video, a file cut short, or a read that returned nothing). No log yet; asked for it on the issue | The cause found from the log; the player says plainly when a file is not a playable video instead of a raw mpv error; a fix if it is a read or format problem, checked with a similar file on the desktop |
| HX5 | **In-app update without the Android installer prompt, as Morphe (ReVanced alternative) does it** | Researched 2026-10-26 in MorpheApp/morphe-manager (GPL-3.0 like TMPlayer, so its code may be reused with credit): `domain/installer/SessionInstaller.kt` commits a `PackageInstaller` session with `setRequireUserAction(USER_ACTION_NOT_REQUIRED)` on Android 12+ when `allowsSilentUpdate` says so (installed target SDK, `getInstallSourceInfo().installingPackageName`, on 14+ `updateOwnerPackageName`, or the app updating itself), and if the platform still asks (`STATUS_PENDING_USER_ACTION` or a refusal) it remembers `silentUpdatesRefused` and commits again with the dialog; `canUpdateSilently()` is asked before any install the user did not start. Android rules: 12+ only, the app holds REQUEST_INSTALL_PACKAGES (the GitHub APK does, store builds have no updater), same signing key, a recent target SDK, and on 14+ no other store owning updates; "Install unknown apps" is still granted once. Plan for TMPlayer: the GitHub APK on Android 12+ updates without the dialog when the user taps Update (and optionally by itself while idle, never during playback, since the install kills the process), with a notice after the restart; Android 6 to 11 and any refusal keep the dialog; phone and TV alike. Research first: read how Morphe's updater installs, then pick the route that works for TMPlayer (a `PackageInstaller` session, whether Android 12+ `USER_ACTION_NOT_REQUIRED` applies to updates of an app TMPlayer installed itself, or a helper such as Shizuku). Android 6.0 stays the floor (`LegacyApiCompat.kt`). Store builds (Play, Amazon, F-Droid) keep no updater and no `REQUEST_INSTALL_PACKAGES`; only the GitHub APK updates itself | The plan for each Android version written here before code; an update from one GitHub release to the next installs with the least prompting the platform allows, and falls back to the normal prompt where it does not; cancelled, failed and low-storage updates leave the old app intact; tested phone and TV |
| RL1 | **Pre-releases before every release (user, 2026-10-26: "complete all of these work and test on my own, and create a pre release first")** | Today `release.yml` and `docs/RELEASING.md` know only stable tags, and versionCode is major*10000 + minor*100 + patch, so a beta cannot sit between 1.28.1 (12801) and 1.28.2 (12802). Morphe (morphe-manager): semantic-release on two branches, `main` stable and `dev` with `"prerelease": true`, so every dev push makes `vX.Y.Z-dev.N` marked Pre-release; `app-release.json` (the stable feed) is written only for stable; the app has a "use pre-releases" setting with a warning dialog, and `VersionUtils` ranks dev.9 < dev.10 < the stable of the same base. For TMPlayer (main stays the only branch, AGENTS.md): tags `vX.Y.Z-beta.N` cut from main by the same workflow, published with `--prerelease`; a new versionCode scheme that keeps rising and leaves room (for example major*1000000 + minor*10000 + patch*100 + stage, beta N = N, final = 99, so 1.28.2 = 1280299, all above today's codes); the stable feed (`latest.json`, the GitHub latest fallback) never names a pre-release; an opt-in "Get test versions" setting with a warning reads a beta feed; winget, AUR, F-Droid, the site and the Telegram channel post skip pre-releases; pre-releases do not count for the release cadence guard (no stable user is prompted) | A `v1.28.2-beta.1` from main publishes as a GitHub Pre-release with every installer, the user installs and tests it on their devices, stable users see no update, an opted-in install updates beta to beta and beta to the final, the final 1.28.2 follows from the same commit or a fix; `docs/RELEASING.md` describes it |
| HX6 | **Remove redundant files and code** | Starts with the Kotlin/Wasm web app: the `:web` module (`web/`), the `wasmJs` targets in `:core` and `:ui`, `kotlin-js-store/`, `docs/WEB.md`, `docs/WEB`-only CI jobs and Gradle repositories (Node, Yarn, Binaryen), tdweb and its notices in `THIRD_PARTY_NOTICES.md`, and every mention in `AGENTS.md`, `docs/ARCHITECTURE.md` and the site. Then a sweep for the rest: unused Kotlin code and resources, unused dependencies, stale scripts and docs, and the strays in the repo root (`issues.md`, `log.txt`, the Search Console exports, and the log file from HX1 once HX1 is closed). Careful: `webapp/` reads the app's rules from the `:core` `js()` target, so that target stays; only `wasmJs` goes. Do not delete the old web build before the Solid web app is live on web.tmplayer.org (WEBAPP-PLAN session 11 keeps the old deployment for rollback). Check `LICENSE-OPENSSL-EXCEPTION.md` and the notices before removing any licence text, since the Android and desktop builds still ship TDLib and OpenSSL | The list of what goes is written here and approved before anything is deleted; one commit per area so each can be reverted alone; `./gradlew` builds and tests pass for phone, TV and desktop, `webapp/` still builds, the site builds; the AGENTS.md dash grep is clean; nothing deleted that a script, CI job or doc still points to (grep for each name first); R8 and the APK size compared before and after |

## Next release

1.27.0, prepared 2026-10-18 at the user's request (1.26.0 shipped 2026-10-14, so this is inside the 7 day
cadence guard; v1.26.1 was tagged but never released, and its Windows update fix ships here). Notes are a "- "
list in the annotated tag (`docs/RELEASING.md`); the store changelog is
`fastlane/metadata/android/en-US/changelogs/12700.txt`. What's new moved to 1.27.0 (`WhatsNew.VERSION` had stayed
at 1.23.0 through 1.24.0 to 1.26.0): five new `whatsnew.highlight_*` keys in English only, the five old ones
removed from every catalog, so the sheet reads English in the other 20 languages until they are translated.

In it: search across all chats (TV page, desktop Ctrl+K), copies of one video folded into one card with a copy
and quality picker, the floating bar and the Chats page on phone and desktop, the Home carousel on every device,
player chapters, picture shape on the bar, finer speed, Save a frame, background audio and the desktop player's
catch up, per chat sort, rename and match fixes, opt-in new video notifications, Send logs, VP8, VP9 and AV1
through nextlib 0.16.0, macOS sign in without Homebrew, the Windows install folder fix from 1.26.1, and the
Downloads, History, Settings and setup rework.

Before tagging: nextlib (1.11.1-0.16.0) and mediamp (0.5.2) both moved since 1.26.0, so the publish job's
source link checks in `release.yml` fail until new corresponding source archives are attached and `LINKED`,
`NEXTLIB_SOURCE`, the notes link, `COVERED`, `TAG` and `MAC_TAG` point at them. The usual checks still apply:
- Windows MSI check, last, on a CI build of the final main: signed in Home, dark theme, uninstall.
  Steps: `podman start win11`, `gh run download <run> -n tmplayer-desktop-windows`, MSI to
  `/home/dracu/vms/win11/shared/`, jars from a keyed local build into `shared/jars/`, then in the VM
  `powershell -executionpolicy bypass -file \\host.lan\data\test.ps1`. The VNC driver scripts lived in a
  session scratchpad; recreate them if needed (vncdotool).

Not seen on real hardware: real first sign in, a low-end device, Low storage mode on a phone or stick,
course progress (no numbered course on the test account), the New episodes row. The emulator AVD
`tmplayer_phone_api36` no longer boots (graphics error on cold boot, 2026-10-14): recreate it, then sign in
again (the user scans the QR). `tmplayer_tv_api36` and `tmplayer_promo_tv` work; screenshots use one
emulator and the promo app only. Agents cannot start emulators inside their sandbox; the main session
starts them headless (`-no-window`).

Open after 1.26.0: the desktop neither exits nor answers SIGTERM when startup fails (found 2026-10-14
with the ChatListViewModel crash fixed in 1ac3cc4; that crash no longer triggers it). The plain "Khalifa"
(2026) file matches no metadata in any language.

State at the end of 2026-10-21 (user out of time, testing later): main has the merged Home revamp,
Settings, haptics, playback, sign in, sorting, subtitles and decoders, two Sentry fixes, the desktop
detail pane fix, carousel films and shows only, and Home as carousel, Continue, Movies, Shows only.
Pushed up to 011b64d; later commits are local. Local test builds (APK 1.28.0-dev versionCode 12790,
AppImage 1.27.9) are in the session scratchpad, not installed yet: install on the Mi TV
(10.27.27.34:5555, has 1.27.1), Linux (~/Applications/TMPlayer.AppImage) and the phone when the user
tests. Web beta: the web agent's branch `worktree-agent-a3db7b7ec854ecc21`; on finishing, merge, add
`_headers` and the manual Cloudflare Pages deploy job (project `tmplayer-webapp`, secrets set,
CNAME live), then wait for the user's go.

Web beta live 2026-10-21 at https://web.tmplayer.org (wrangler pages deploy from the session
scratchpad build of the web branch, merged into main e1b177b, pushed fd38f36). Known before sharing:
playback stalls on the spinner after the first video (unfound cause), MKV/movi, Search typing, sign in,
resume and sign out unchecked; the web build predates main's Home changes (carousel films and shows
only, Home rows), so rebuild from main before the next deploy. After the beta list: agent report in
the session, docs/WEB.md and CP92 not updated yet.

Postponed by the user (2026-10-21, "will do that later"): the updater check. macOS has no self update
(`SelfUpdate.detect()` gives Manual, the popup opens the release page) and replacing the app by hand
from the DMG left users with the old copy gone and the new one missing, likely while TMPlayer was still
running. Agreed small fix when it is picked up: a macOS line in the popup (quit, open the DMG, drag to
Applications, Replace) and the same steps in INSTALL.md, nothing on the site; then a read only audit of
the MSI, AppImage and Android APK updaters, fixing only real bugs.
Kept for then, unmerged: branch `worktree-agent-a6065ef4c67e81e5e` commit 99bff30 (Android update popup
reads the "install unknown apps" answer again on return, so Update appears after allowing it; compiles,
core Update tests pass, not device tested). Merge or drop it when the updater work resumes.

Kept on purpose, no merge date (user, 2026-10-23): branch `webapp-solid`, commit d4670dc, the SolidJS
web player parked while the web app is frozen. Its `webapp/` working copy (with node_modules and dist)
was moved to the trash; `git checkout webapp-solid` and `pnpm install` bring it back.

Held until the site update after the web beta (user, 2026-10-21): new guides on branch
`worktree-agent-a780870766b17e694`, WIP commit b56617a, English only and unchecked: subtitles for
Telegram videos, Telegram MKV not playing, Telegram video no sound, Telegram videos on PC, plus links from
desktop, formats, the external player guide and the guides hub. Before merging: check no web app mention,
build, i18n check, links check, then translate (Vietnamese and Arabic drafts were in the session scratchpad).

On hold: F-Droid needs a decision on the Telegram API key, and nothing about F-Droid goes in the app, site or
README until then. `/signing` says the SignPath application is pending; update it when it is approved.

## Status board

Tick `[x]` and add the commit or date. `U` items are yours, `CP` items are Claude's. `[-]` is set aside.

**Order (user, 2026-10-06):** every code checkpoint first (CP24, CP26, CP17, CP13, CP18, then CP11 and CP25), releases held until that is done, then the outside tasks.

**Phase A. Compliance before any more listings (no release needed to commit; ships with the next release)**
- [x] CP01 Crash reports and privacy page match each other (c6b3e22)
- [x] CP02 Wording: "unofficial" everywhere, site titles, release notes text, legal Terms (ae52f5a)
- [x] CP03 Third-party notices complete, a real desktop corresponding source script, notices inside every package (97a0f3f)
- [x] CP04 Respect Telegram "restrict saving content" and self-destructing media (572287c, not yet tested on a protected channel)
- [x] CP05 About screen on phone, TV and desktop (49b4c6f)
- [x] U01 Two Sentry settings (2026-10-06: IP storage off, default scrubbers on; DSN host `.ingest.de.sentry.io`, EU, privacy page updated)
- [ ] U02 Player logo's source (label settled 2026-10-06: GPL-3.0-or-later everywhere, already true in README, winget, AUR, metainfo and the MSI; libraries keep their own licences)
- [x] U03 Budget decision: $0 only (2026-10-06). Phase G dropped, no Certum
- [x] U03a No macOS (2026-10-06): not built, tested, packaged or promised anywhere; the CI job, the DMG target and the icns are gone
- [ ] U04 (optional) Commit email `hello@tmplayer.org`
- [ ] U05 (optional) DMARC record

**Phase B. The "Player" release (earliest 2026-10-13, cadence guard)**
- [x] CP06 Subtitle and audio delay, subtitle style (905d009, desktop verified; phone and TV not yet tested on a device)
- [x] CP07a Volume boost, sleep timer and "still watching?" on Android, TV next-up card (cf88a64, TV card and menu checked on the emulator; phone and real audio not yet tested on a device)
- [x] CP07b Volume boost, sleep timer and "still watching?" on desktop (83b0789, menu and card checked in the dev player; the bundled FFmpeg has no dynaudnorm, so the boost is gain only for now)
- [x] CP08 Resume rules, desktop player extras (b47cddc, rules and saved tracks, speed and delays unit tested on both stores; help sheet, A-B loop, chapters and a real screenshot checked in the dev player; resume restore on Android not yet tested on a device)
- [x] CP09 Empty and error states with actions, recent searches (7b4656e, every state checked on the phone and TV emulator fixtures and the desktop render test; real slow Telegram and search recording not yet tested on a device)
- [x] CP10 Remove after watching, free space, TV focus and labels (6d4d36f, toggle, free space, focus scale and 48/27 margins checked on the phone and TV emulator fixtures and the desktop render test; real deletion after a watched download not yet tested on a device)
- [x] CP10a Sidebar: version beside the logo, compact accordion groups (phone, TV, desktop) (b5d6468, draft, awaiting user review; TV rail folded and open, D-pad walk, phone drawer and desktop light and dark checked on the emulator fixtures and the render test. The TV rail has no icons only mode, so none was added)
- [x] CP10b One theme on every platform: Android TV modal borders, surfaces and the rest match phone and desktop (f7fd2bf, draft, awaiting user review; shared Floating and Focus tokens, every dialog, sheet and menu with the same hairline, fill and 60 per cent dim; audit and before and after shots of the eight screens on the TV and phone emulators and the desktop render test, light and dark)
- [x] CP10c Support the project: sponsor button and QR code on every platform, a gentle reminder, site section (9c53d0e, entry, QR codes and forced card checked on the phone and TV emulator fixtures and the desktop render test, every code decoded with zbarimg; site section at 390 and 1440 in headless Chromium; the real 14 day and 10 play trigger not yet seen on a device)
- [x] CP10d TV player: drop the on-screen play, back 5 s and forward 10 s buttons the remote already covers (ab2f4b9, row, paused cue, jump HUD, menu and D-pad walk checked on the TV emulator fixture; real playback keys not yet tested on a device)
- [ ] CP11 Release prep and device verification
- [ ] CP12 Release "Player" (only when the user says so) and post-release checks
- [ ] U06 Check the 1.22.1 post on @tmplayerapp went out

**Phase C. Distribution (each submission only on the user's go-ahead)**
- [x] CP13 winget manifests for the new release (c1c994f, 1.24.0 MSI downloaded, SHA-256 matches the release digest and latest.json, ProductCode read from its Property table, all three files validate against the 1.12.0 schemas; nothing submitted, next is U13)
- [-] CP14 Windows signing in release.yml (set aside 2026-10-06: installers stay unsigned)
- [ ] CP15 Android verification token in the APK (needs U08)
- [ ] CP16 winget auto-update job (needs the first winget PR merged and U14)
- [x] CP17 F-Droid MR: get the pipeline green (2026-10-09, fork pipeline 2930107122 on 1bc08d44 green in all 9 jobs incl. build and check apk, recipe on 1.24.0; but the MR was closed by linsui on 2026-10-06 for not following the template, so reopening it or opening a new one is the user's call, see CP17)
- [x] CP18 Obtainium badge on README and download page (93c8a38, official badge trimmed losslessly to 564x168, shown at 161x48 under Android phone in all 21 languages with the translated alt, and in the README; download page checked at 390x844, 844x390 and 1440 in headless Chromium, English and Arabic)
- [ ] U07 Android developer account and identity check
- [ ] U08 Register `com.tmplayer` and the signing key, send Claude the token
- [x] U09 Pick the Windows signing route: stay unsigned (2026-10-06)
- [-] U10 SignPath Foundation: send them references (set aside with CP14)
- [-] U11 Certum: buy and validate (dropped, U03 is $0)
- [-] U12 Signing secrets in the GitHub `release` environment (set aside with CP14)
- [ ] U13 wingetcreate on a Windows PC, submit, sign the CLA
- [ ] U14 `WINGET_CREATE_GITHUB_TOKEN` secret
- [ ] U15 Awesome lists: 5 merged, 9 open (5 new on 2026-10-20), FMHY via Discord; see U15
- [ ] U16 Flathub PR #10513: answer or let it go
- [x] U17 AlternativeTo (submitted 2026-10-20 as an alternative to Telegram, Telegram X and Unigram; normal queue, $9 priority review not bought; TMP and TvGramPlayer are not listed there)
- [ ] U18 Uptodown (after CP05)
- [ ] U19 XDA thread (written by you)
- [ ] U20 Search Console and Bing (both set up since 2026-10-05, sitemap read 2026-10-18, IndexNow live. 2026-10-20: Google 338 clicks / 3.2K impressions in 28 days, 90 indexed, 161 discovered not indexed; indexing requested for /alternatives/tmp-telegram-media-player/, quota then exhausted: request the guides, /alternatives/ pages, /download/, /faq/ and / on 2026-10-21)
- [ ] U21 Downloader code and Amazon developer account
- [x] U33 (2026-10-20) Product Hunt: scheduled for Tue 2026-10-20 12:01 AM PT (12:31 PM IST), page producthunt.com/products/tmplayer; tags TV, Video Streaming, Open Source; Free; 5 gallery images from scratchpad post-image/. On the day: answer comments from the Launch Day dashboard, share the link (no asking for upvotes, no upvote groups)
- [x] U34 (2026-10-20) launch-day posts: SaaSHub listing submitted free (saashub.com/manage/tmplayer, competitors Telegram, Telegram Desktop, Telegram X, Unigram; queue 1 to 12 weeks); Uneed submitted (uneed.best/tool/tmplayer) but the free waiting line is full for 5 months, so it is not scheduled (paid only); Show HN link post https://news.ycombinator.com/item?id=50161458 (no text, by design); dev.to article https://dev.to/draculah/building-a-telegram-video-player-for-android-tv-phones-and-desktop-with-kotlin-tdlib-and-compose-2hi3 (AI-assisted label, footer says AI-drafted). Not posted: Peerlist (profile filled to the rules, then Peerlist suspended the profile; see the email they sent), Fazier (free tier needs a Fazier backlink badge on tmplayer.org: user decision), Indie Hackers (posting only for established members or IH+), Lemmy (lemmy.world not signed in; !android@lemmy.world bans self-promotion, target !opensource@lemmy.ml id 31 and !androidtv@lemmy.world id 32590 once signed in). Second round, 2026-10-20: Launching Next submitted free (launchingnext.com/thanks/?i=164012, queue about 4 months, $99 fast-track declined); TinyLaunch submitted, waiting for approval (tinylaunch.com/dashboard/startups/29002, maker profile nevilkrishnak, free Standard launch, no add-ons; goes live 2026-11-23 midnight PT if approved); Microlaunch skipped (only a paid Pro launch offered); StartupBase draft complete (startupbase.io/submissions/tmplayer, tags Open Source and Privacy, 5 screenshots, Free) but its free launch needs a StartupBase badge on tmplayer.org (user decision, like Fazier); Lemmy posted: https://lemmy.world/post/53362674 (!opensource@lemmy.ml) and https://lemmy.world/post/53362676 (!androidtv@lemmy.world); AFTVnews Downloader code 3206503 (aftv.news/3206503 -> tmplayer.org/apk, made by the user); SourceForge project imported from GitHub: https://sourceforge.net/projects/tmplayer-app/ (slug tmplayer is an old unrelated project; Downloads mirror and Source Code on, Issues and Wiki off so GitHub stays the tracker; summary, description with tmplayer.org and /legal, 6 features, support URL tmplayer.org/support/, icon; categories Multimedia > Video > Media Players, GPLv3, Android/Windows/Linux/Mac, Production/Stable, End Users, Kotlin, English, Graphical Android; v1.27.0 and v1.27.1 files already mirrored)
- [ ] U32 (2026-10-20) AUR account: registration was paused on 2026-10-20 (a wave of automated sign-ups; watch aur-general or the Arch news). When it reopens, register with the sign-up mail, paste `~/.ssh/aur.pub` (already made) under My Account, tell Claude the username; Claude does the first push of `tmplayer-bin` and sets the `AUR_SSH_PRIVATE_KEY` repo secret. Until then release.yml bumps the PKGBUILD and skips the publish (39ce41d, `docs/distribution/aur.md`)

**Phase D. The "Languages" release**
- [x] CP19 P1 i18n infrastructure (f7ba39d, catalog, generated `L`, Translator, ICU subset, CLDR plurals for all 17 languages, formatter, resolution, `LocalStrings` and en-XA covered by the :core tests; TV promo home unchanged on the emulator. API: add a key to en.json, read `LocalStrings.current.key` in Compose and `L.key` elsewhere)
- [x] CP20 P2 shared onboarding and the desktop promo fixture (728ccef, tour pages checked on the phone and TV emulator fixtures, the desktop render test and en-XA; gating unit tested. Site wiring for the desktop shots is done but left uncommitted, see the report; the real first run on a device not yet seen)
- [x] CP21 P3 extract phone and TV text (7d0b42a, 783 new keys over :app and :ui, formatter switch, notifications, track picker and accessibility labels; English compared with the phone and TV emulator fixtures, en-XA shows only :core text, brand names and demo data; 1 leftover by the literal scan, the "TMPlayer" brand)
- [x] CP22 P4 extract desktop and core text (d05ff55, 611 new keys over :desktop and :core, including the :core labels phone and TV showed in English; English compared and en-XA checked on browse, Settings, the player menu, the "?" sheet, the update popup and the sign out dialog in the desktop render test; `--self-test` reads every catalog; 0 leftovers by `scripts/i18n-leftovers.py`, protocol strings, brand names and TDLib error text are allowlisted with reasons; no tray exists on the desktop, so none was extracted)
- [x] CP23 P5 language setting, what's new, feedback, localized metadata (94d8d4c, language resolution, the "Now in" card rule, lastSeenVersion and the issue and mailto URLs unit tested against a test-only `de` catalog; Settings row, picker, a live switch to en-XA and to Arabic, the card, What's new, Report a problem with its QR decoded, and right to left including the player overlay checked on the phone and TV emulator fixtures and the desktop render test; desktop scripts render from system fonts, so the package grew 0 bytes and no font notice was needed; `WhatsNew.VERSION` and the `whatsnew.highlight_*` keys move with each release; the PWA manifest goes to CP26 with the site; the star prompt was left out; the real first run of a new version and Android's own language page not yet seen on a device)
- [ ] CP24 P6 translations (go-ahead given 2026-10-06, 16 languages). All 16 translated by Claude directly, then Persian (fa), Traditional Chinese (zh-TW), Polish (pl) and Bengali (bn) added the same day, 21 languages in all (app catalog 1696 strings, site 940) on 2026-10-06 after the three-language cut; machine quality, no native review yet. Still open: review pass (Telegram and Fire OS menu names, Spanish "tú"), review request on issue #2. Overflow check in de, ru and ml done 2026-10-09 (b5ec6d4, 7ea985c): TV emulator, phone emulator portrait and landscape, desktop render test; What's new buttons wrap, TV rail widens up to 240 dp, desktop side bar steps down to 12 sp, shorter de, ru and ml catalog lines; left open: 34 keys (support reminders, support page, player help and menu labels, shortcut groups) are in en.json only and show English in all 20 locales, the promo sign out prompt is hardcoded English, ml "Saved" in the landscape phone drawer, the real stick (CP44). Desktop fonts: bundle subset Noto fonts (user, 2026-10-09); done in 6cd8944 (SC, TC, JP, KR, Devanagari, Bengali, Malayalam, Arabic UI subsets behind the system fonts, CJK order follows the language, about 9.4 MB installed and 6 MB compressed per desktop package; render test checked in all nine scripts with a Latin-only fontconfig, no boxes, rows and RTL as with system fonts; AppImage and Windows MSI not yet tested). New strings from CP40 to CP43: Claude translates them directly into all 20 locales, no langsync (user, 2026-10-09)
- [ ] CP25 P7 verification, then release "Languages" on request
- [x] CP26 P14b site localization and the remaining site pages (ac034f6 and ef9a156 had already built it: data-i18n, 20 languages under site/<lang>/, hreflang with x-default, og:locale, sitemap alternates, header picker, suggestion bar without redirect, the CI --check, /translate/ and the three guides; 93c8a38 closes the last gap, two code-only links that left empty strings falling back to English. The "Now in" line and the macOS promise stay out on purpose (259ff14, 0d1bdf6). /, /es/, /download/, /ar/download/ and /fire-tv/ checked at 390x844, 844x390 and 1440 in headless Chromium)
- [ ] U22 Review hi and ml; ask the issue #2 author to review es

**Phase E. The "Browse" release**
- [x] CP27 P9 series view (5fb2c07, MediaName and grouping unit tested on real name shapes; series tiles, season tabs, episode list, phone sheet, TV page with a D-pad walk and the Series and All files switch checked on the phone and TV emulator fixtures, desktop dropdown on the render test, light and dark; real chats and caption-only uploads not yet seen on a device)
- [x] CP28 P10 Home rows (1c9a085, row building unit tested for order, the limit of 10, dedupe and empty favourites; Home checked on the phone and TV emulator fixtures with a D-pad walk across and down the rows and on the desktop render test, light and dark; on a 1 GB TV emulator profile a D-pad scroll of a non-debuggable promo build had 5.1 per cent janky frames, p90 30 ms, against 6.0 per cent for the chat grid; real Telegram rows and Recently added not yet seen on a device)
- [x] CP29 P10 detail panel and search across all chats (c777290, action list, file name facts, scope and result splitting unit tested, the search view model with a fake source; panel opened by long press on the phone, OK hold and the info key on the TV, right click and the menu key on the desktop, in the grid, Home rows and search results, light and dark; TV D-pad walk reaches the facts, stays in the pane, Back returns focus to the tile; all-chats search over the promo fixture's fake searchMessages on phone, TV and desktop. Series tiles open the series view rather than a panel. Real searchMessages results and a protected channel not yet seen on a device)
- [x] CP30 P11 Fire TV, trickplay, Watch Next, then release "Browse" on request (a7ed256, release not done, on request. Fire OS detection, remote quirks, voice fallback, the trickplay range and memory rules and the Watch Next entry builder unit tested, Fire OS by faked manufacturer, model and feature flags since it cannot be emulated; trickplay while scrubbing in and past the downloaded range, both new settings and the Play next row on the Android TV launcher, opened from there into the player, checked on the TV emulator with the promo fixture; on tmplayer_tv_1gb trickplay is off and Settings says why; the Fire TV keyboard hint and the missing Watch Next switch checked with Fire OS faked in the promo build. Android only, desktop trickplay not built. Amazon pack in docs/distribution/amazon.md. Real Fire TV remote, Alexa mic and real partial-file thumbnails not yet tested on a device)
- [ ] U23 Amazon Appstore listing submission (after CP30)

**Phase F. The "Online" release**
- [x] U24 Rotate the OpenSubtitles key (2026-10-06: new key in the `OPENSUBTITLES_API_KEY` repo secret and local.properties; TMDB_API_KEY was already a repo secret since 2026-08-07)
- [x] CP31 P12 OpenSubtitles (a0c195b, hash and name search, download, quota from 429, 406 and remaining=0, revoked key, expired token, rate limits, cache hits, TTL and purge, SubDL fallback unit tested over a fake HTTP layer; one live search by name with the real key found 50 results, no sign in and no download; Settings section, sign in prompt, "Search online" in the picker, results and every disabled state checked on the phone and TV emulator fixtures and the desktop render test, a canned download loaded on the TV fixture. A real sign in, a real download and the hash of a real stream not yet tested on a device)
- [x] CP32 P13 TMDB, TVmaze, AniList, then release "Online" on request (3cab226, release not done, on request. Matching (film by title and year, series with season and episode, anime via AniList, `{tmdb-123}`), the strict no match, a 429 pause, a refused key, cache hits, TTL, purge and the English fallback unit tested over a fake HTTP layer; live calls found Big Buck Bunny on TMDB with the real key (overview fell back to English from ml), Breaking Bad S01E02 on TVmaze and Frieren on AniList, and a bad key was refused; detail panel with poster and overview, series page with its poster, Home with posters, the Settings section with the key override and its states, and About with the TMDB logo and notice checked on the phone and TV emulator fixtures and the desktop render test. Off by default, turned on from Settings or the detail panel. Real chats, real poster loads on a 1 GB stick and a viewer's own key not yet tested on a device)
- [x] U25 MSone reply (issue #5): yes on 2026-10-05, on two conditions (translators credited, every download counted; series zips must not inflate the count)
- [ ] CP33 MSone subtitles in the apps (2026-10-27). Waits on the API repo `~/projects/personal/msone-unofficial-api` reaching A4 (live at subs.tmplayer.org). B1 fonts can go first. See "CP33 MSone" below

**Phase G. Google Play (dropped 2026-10-06, U03 is $0; kept for reference)**
- [ ] U30 (2026-10-19) Decide whether Phase G comes back: Play is $25 once (U07 pays it anyway for developer verification), then 12 testers for 14 days. Facts and risks in `docs/distribution/play.md`; the store rules are in CLAUDE.md under "Telegram's terms and the stores"
- [-] U26 Line up 15 testers (start early, it gates production)
- [-] CP34 Store flavor without the updater, targetSdk 36, AAB in the release
- [-] CP35 Reviewer demo mode, TV ad links as QR, TV banner with the name, Report and sensitive media
- [-] CP36 Play listing images (outside the repo)
- [-] U27 Create the app in Play Console and fill in the forms
- [-] U28 Closed test for 14 days
- [-] U29 Production, then opt in to Android TV

**Phase H. Player declutter and support asks (planned 2026-10-06; its design file was removed once Phase H shipped, see git history)**
- [x] CP37 Android player declutter (693edbc, phone and TV; phone row is subtitles, audio, speed, rotate, with Lock, PiP and Picture shape in the overflow, Start over is an offer on the resume, TV row is subtitles, audio, speed, More, with Picture shape in More; checked on the phone and TV emulators with the promo build, D-pad walk with no dead focus, unit tests green. Still pending for a real device: double tap and pinch gestures, the first-run hint, the resume offer over a real resume, and the other locales still carry the unused back_seconds, forward_seconds and playback_speed_now keys (kept in en.json because CatalogTest fails otherwise; drop them when the locales are synced). Left as is: the two subtitle Look renderers, since touch and D-pad each use one. CP40 still owes the TV More episode lines)
- [x] CP38 Desktop player declutter: bar, main menu, shortcut sheet (e07f7cb; the NextUpCard and Countdown sheet were already exclusive, no cut; Picture shape moved into Playback options; unused en.json strings kept until langsync prunes the locales)
- [x] CP39 Support asks v2: positive moments, three asks at most, thank-you state, tmplayer.org/support (45d6037, ladder, counters, triggers and the site page done; ladder unit tested, phone rungs 1 to 3 on the emulator, desktop render test, site at 390 and 1440; TV and the phone thank-you state not shot on an emulator, none was free; the site page is English only, like legal)
- [x] CP40 Episodes button and modal in the player (phone, TV, desktop), after CP37 and CP38 (c961089 and 4ac8650; Episodes at the right end of the phone and TV row and the desktop bar, only for a series; one shared list with the previous and next step, autoplay switch (the existing setting), next-up order by episode number or upload order and the learned Skip intro (Set intro end here, Clear) kept per series, season tabs and the series view's rows with the playing episode tinted, scrolled to and focused; a long press, a held OK or a right click marks a row; Skip intro pill on all three (tap, OK over the bare picture, click or Enter); the TV More menu lost its episode lines. Checked on the phone emulator portrait and landscape in English and Arabic (button, list, season switch, long press tick, Back), on the Mi TV stick and the TV emulator (row walk to Episodes, focus on the playing episode, D-pad walk, OK hold tick, season switch by focus, Left to the choices, Back back to the button, Arabic), desktop render and UI tests; build and tests green. Also translated the 9 desktop player and remote strings missing from every locale and let desktop menu lines wrap. Pending for CP44: a real resume and autoplay across a real Telegram series with upload order, Skip intro over real playback, the hold on the POCO; 25 support and About keys are still missing from every locale)
- [x] CP43 Sidebar with fewer entries: fold the eight chat sections into two entries with filter chips (before CP41) (200e2fa; six fixed entries: Home, History (Continue and Watched tabs), Favourites, Downloads, then Chats (chips All, Unread, Channels, Groups, People, Archived and a Recent or A to Z toggle, remembered in SettingsStore) and Saved, under a group now headed Telegram; checked on the POCO in portrait and landscape and in Arabic, on the Mi TV stick with a D-pad walk in English and Arabic, and the desktop render test; unit tests green. Kept short: Saved stays "Saved" on the entry, since "Saved Messages" is cut off in the 180 dp TV rail; the heading says Saved Messages. Not done: the site's tv-chats screenshots still show the old v1.8 rail, refresh them with the next capture)
- [x] CP41 Setting to hide Telegram's default chat groups everywhere, with warning prompts, plus a sweep for settings that lack one (a280a35; "Only my folders" in Settings, Library: Chats and Saved leave the drawer, rail and side bar, Folders unfold, a hidden section lands on Home, the prompt counts and unstars favourites in no folder or archived, first-run Home and the chat tip point at folders; search still reaches every chat on purpose. Prompt sweep 74868f0. Unit tested (DefaultGroupsTest); checked on the phone emulator in portrait and landscape, English and Arabic, the TV emulator with a D-pad walk in English and Arabic, and the desktop render test. Strings in all 21 catalogs. Pending for CP44: a real account with folders on the POCO and the Mi TV stick)
- [x] CP42 Minimal onboarding (replaced by CP67, 2026-10-14): six tour pages down to two, one first-sign-in card, deferred hints (7ddddb5 and a280a35: two page tour, first-run Home and chat list tip, phone gesture hint, TV browse hint; the first sign in card is armed by a sign in screen, asks "Show everything" or "Only my folders" once the chat list is in and only with folders, skipped for good without, once per install, and the TV browse hint now waits for it. Card checked through the promo fixture on the phone emulator, portrait and landscape, English and Arabic, on the TV emulator with Show everything focused and Back answering, and in the desktop render test. Pending for CP44: a real first sign in on each platform, real phone gestures and the TV hint over a real first chat list)
- [x] CP44 Device pass for CP37 to CP43: install on the phone and the Mi TV stick, hunt wasted space and anything broken (2026-10-14 pre-1.26.0 pass, fixes 619bb75, 7564485, 75cbf0f, 22a7449, c211d5a, 17e7c1e. Checked on the phone and TV emulators with the debug and promo builds, then on the Mi TV stick with release builds 1.26.0-test9 to test11 and the real account: Home with Recently added after Continue watching and the side fades, the rail always open and Left into it landing on the screen shown, no screensaver or carousel left in settings, strings or backups, the update popup naming the TV, Settings walked page by page, preferred audio language picker, Rewind when resuming stepper, a show page with season download, episode thumbnails and season tabs, the PIN set, asked for Settings and removed, the kids lock limiting Home and the home screen row to the allowed chat, and CP65's home screen row: cards play, a show's card opens its page, the switch off and on keeps the row. Found and fixed: This phone wording on TV, the rail landing beside the current screen, the home screen row lost for good after the switch went off and on, the show's name scrolled off the top of a show page, Up into the season tabs switching season, no focus after the PIN pad. Still open: a show opened from a home screen card has nothing focused until the first key; the onboarding's How it works picture still shows the old v1.0 sidebar; the Favourites badge counts chats the kids lock hides; Open a video's page first says A tap on TV. Not seen on a real device: anything on the POCO (it was in use), real playback and codecs, autoplay and Skip intro across a real series, speed memory, skip silence, A-B repeat, frame step, bookmarks, backup and restore, log export and UI scale, real phone gestures and holds, a real first sign in, folders on a real account)
  - Phone emulator pass 2026-10-14 (debug build, real account, portrait and landscape, English and Arabic): checked a81937f, c92f774, 4c27169, e27ec84, 7714371, 9dd3ddc, d0b859a and 772fad9; found and fixed the show page's back button over Resume once scrolled (5a7aad5), episode rows saying Episode 3 instead of TMDB's names (02acc75), and Arabic tiles with no poster or the file's own thumbnail because the title only matched in English (ac9cf10). Low storage mode's phone UI (CP66) checked the same day: the switch, the play and download questions, Not now, Switch and play, the question inside the player on Next, Arabic; desktop test 2198919. Not checkable: course progress (no numbered course on the account), the New episodes row (no new uploads), real hardware

**Phase I. Telegram groups, watching habits and ideas from other players (planned 2026-10-13, from a look at Nuvio, Stremio, Kodi, Jellyfin, mpv frontends and Animetrix-style apps)**

Batch 1 is the offline features, batch 2 the larger ones, sync last because it needs U30. Every CP covers phone, TV and desktop.
- [x] CP45 Forum topics as tabs, captions in titles and search, lecture next-up, paid media, edited and scheduled messages (7cb7d6d and 032259d; topic tabs checked on the POCO in STRIVER TUF)
- [x] CP46 A show is one tile, copies of a film or episode one tile with versions, anime numbering kept (3e765ce; checked on the POCO in Cinema_paradasio)
- [x] CP47 Skip intro, recap and credits from TheIntroDB, IntroDB.app and AniSkip, next-up counted from the credits (eb8661b; unit tested, privacy page updated; the skip pill is not yet seen on a device, the promo app has online metadata off)
- [x] CP48 Speed remembered per series, per chat and per forum topic, presets 0.75 to 2 (see commit; `SpeedMemory` in core: a show under the track choice's series key, a lecture under its course, else the chat or chat plus topic; opens at the file's own, the series', the chat's or topic's, then 1x, and 1x forgets it; the old app-wide last speed is gone. Pickers already offered 0.5 to 2. Unit tested; not yet seen on a device)
- [x] CP49 Preferred audio and subtitle language, subtitle mode (batch 1; Settings rows on phone, TV and desktop, order saved tracks, series, Settings, file defaults in TrackRules, ISO 639 codes normalised, unit tested in TrackPreferencesTest; not yet seen on a device, strings in en.json only)
- [x] CP50 Watch later: single videos without starring the chat, Home row, History tab (batch 1)
- [x] CP51 Download a season, or only its unwatched episodes, from the series page (batch 1; a Download button beside Play on phone, TV and desktop, opening "Download season" and "Download unwatched" on a line under it once the season is partly watched; one copy per episode as the list would play it, episodes kept or queued in any copy skipped, restricted chats left out, then the grid's own multi-download for room and wording. Unit tested (SeasonDownloadTest); not yet seen on a device)
- [x] CP52 Course progress: "12 of 40 watched" on lecture series and topics (batch 1; a chat or forum topic that is mostly one numbered run of lessons gets a line over its listing on phone, TV and desktop with the course name, "12 of 40 watched", a thin bar and Continue to the first unwatched lesson; a show's page gets the bar over its episodes and Play reads "Continue S01E05" once some are watched; a saved position past 90 % now counts as watched for both. Counted over the videos loaded so far; topic tabs carry no count, since that would mean listing every topic. Unit tested (CourseProgressTest); not yet seen on a device)
- [x] CP53 Backup and restore settings, lists and progress to a file (batch 2; format and merge in core `Backups`, unit tested in BackupTest; settings, CP49 track preferences, favourites, watched, watch later, resume points, `speed_` and track memory, topics, recent searches; not yet seen on a device)
- [x] CP54 Updates feed: new episodes of shows being watched, badges on tiles (unit tested in core and ui; not yet seen on a device)
- [x] CP55 Timestamped bookmarks with notes (batch 2)
- [x] CP56 Android chapters, A-B loop and frame step (desktop has them since CP08) (batch 2) (A-B repeat in the phone overflow and the TV More menu, marks on the bar and "A-B" by the time; frame steps there while paused, menu only on TV since the arrows seek; `AbLoop` moved to core. Chapters skipped: Media3 1.10.1's Matroska and MP4 extractors expose none, only MP3's ID3 chapter frames. Not yet tried on a device) Chapters on Android left out: Media3 1.10.1 exposes no MKV or MP4 chapter marks.
- [x] CP57 TV screensaver, PIN lock for settings and a kids lock (batch 2) (PIN as a salted PBKDF2 hash with a stored back-off, asked for Settings and optionally on open, on phone, TV and desktop; kids lock "Allowed chats only" filtered once through ChatGate and KidsLock in core for the chat list, Home, search, Continue watching, Watched and Watch later; TV screensaver Off, 2, 5 or 10 min with clock and drifting Continue watching art. Unit tested in AppLockTest. Not yet checked on a device)
- [x] CP58 Skip silence, rewind a few seconds on resume (batch 2) (skip silence under the speeds on phone and TV, kept as a setting; Android only, mpv's silenceremove drifts sound from picture. "Rewind when resuming" Off/3/5/10 s, default 3 s, on phone, TV and desktop via `ResumeRewind`, unit tested. Not yet tried on a device)
- [x] CP59 UI scale, log export for bug reports (batch 2)
- [x] CP60 Episode thumbnails in the series list (batch 2)
- [ ] CP61 (later, user 2026-10-13: no new outside services for now) Absolute anime episode numbers to season and episode via ani.zip or the Fribb list (batch 2)
- [ ] U30 Register AniList and Simkl API clients, give Claude the client ids
- [ ] CP62 AniList and Simkl progress sync (later, user 2026-10-13: anything needing keys or accounts waits; needs U30)
- [ ] CP63 UI refresh in the style of Nuvio: home hero, poster rows, detail and series pages, player chrome, on phone, TV and desktop (spec: docs/design/2026-10-13-nuvio-style-ui.md)
- [x] CP64 Desktop Home that feels like a desktop app: wide hero with text on the left, row arrows and sideways wheel scrolling, hover lift with a play button, See all pages, cards that scale with the window, keyboard moves (user, 2026-10-14)
- [x] CP65 Android TV home screen channels: Recently added as the default channel, Watch later and New episodes to add from the launcher, real posters and thumbnails, cleared on sign out and by the kids lock, a Settings switch on TV (15aeada, 80a3344)
- [x] CP66 Low storage mode: a stream keeps a rolling window of about 200 MB on disk (less on a nearly full disk), so a film larger than the free space plays; off by default, a switch under Playback on phone, TV and desktop, carried in backups; never applied to a download or a video being downloaded; the room check measures the window when it is on, and every refusal of a video or a download that would stream in the mode asks "Switch and play" or "Not now" (eb169ad, 333d8f5, c246c06, 722d167, 66244fc, 538b7d0). Verified: TDLib keeps the holes sparse (desktop TDLib on btrfs, a restart at 509 MB took 40 MB of disk for a 549 MB file); a real TDLib harness through TdByteWindow read 150 MB in 4 rolls with a 40 MB window, 32 MB peak on disk against 150 MB without the mode, SHA-256 equal to a plain read including a seek back; on the TV emulator a 2.7 GB film on 1.1 GB free held 200 MB, rolled at 205 MB and played on, a seek back past the window deleted and refetched, the play prompt (focus on Switch and play, Not now giving the old refusal), the download prompt, and the switch off and on in Settings. Found on the emulator and fixed: a limit of 0 let TDLib fetch the whole film after the end, and a seek inside the window deleted it. Not checked: the prompt inside the player (next episode, autoplay), the phone dialog and the desktop UI on screen, a real device
- [x] CP67 Setup after sign in instead of the tour before it (user, 2026-10-14): the sign in screen comes first, then up to three steps, one choice each with a cropped, hand-marked picture of both answers (scripts/annotate-shot.py, specs setup-android-* and setup-desktop-*), Skip, Skip all and Back: Your library (Show everything or Only my folders, only with folders; the old first sign in card), Posters and details (the MetadataStore switch, on by default), Storage (Low storage mode, key low_storage_mode, drawn rather than shot). One `setup` flag armed by a sign in screen: an install signed in before it never sees it, a fresh sign in (also after signing out) does, Settings' Run the setup again re-arms it; the two page tour, `overview_seen`, `first_sign_in_card`, the v1.0 sidebar shots and their strings are gone, and Android now shows Telegram's unofficial line under the sign in screens. Strings in all 21 languages. Unit tested (SetupTest, SetupFlagTest), desktop render and keyboard tests (SetupRenderTest: every step light and dark, Arabic, en-XA, a narrow window, arrows, Enter, Back, Skip, Skip all). On the TV emulator with the promo build (`--es screen setup --es step library|posters|storage`): phone portrait and landscape, light, dark and Arabic; TV with the first focus on the recommended answer, Right and OK picking, Back to the step before on the answer set. Not seen: a real first sign in on any device
- [x] CP68 Low-end devices (user, 2026-10-14): `DeviceFitRules` in core judges a `DeviceProbe` (Android: isLowRamDevice, total memory under 2 GiB, or 1.5 GiB on TV, API under 26 or 32-bit only = short of memory; free space under 3 GiB = short of storage; the desktop reads free disk only) and recommends only existing or new Settings rows still off: Low storage mode on (storage), Thumbnails while seeking off (memory, only where they show at all), and two new rows under Appearance, Lighter pictures (picture cache at 1/16 of the heap instead of 1/8, posters, stills, thumbnails and the backdrop decoded at 3/5 width, no blur behind letterboxed art) and Reduce motion (Motion.standard tweens land at once, the skeleton shimmer stands still), both carried in backups and kept across sign out. Not changed, on purpose: player buffers (already 20 MB target, 50 s ceiling, short back buffer for the 1 GB stick; the 1.5 s PCM floor stays), Home prefetch (rows load art only when shown), posters and details (kept on, the list says they cost data and memory). Offered in three places, each listing exactly what changes: the setup's Device step in Storage's place (Apply focused, Not now), a Recommended for this device card at the top of Settings until applied or closed (Apply all focused on TV), and a once-per-install hint (a bar on the phone, a dialog with Apply focused on TV) when the player drops frames or the system sends onTrimMemory RUNNING_LOW. Strings in all 21 languages. Unit tested (DeviceFitTest, DeviceFitUiTest, SetupTest), desktop render and keyboard tests (the Device step light, dark, Arabic, narrow, Enter applies; the card on Settings). On the TV emulator with the promo build and `--ez lowend true` (`--es screen setup --es step device`, `--es screen settings`, `--es overlay devicehint`): TV step with Apply focused, the Settings card with Apply all focused and gone once applied, the rows on and turned back one by one, the hint dialog; phone-shaped step, card, hint bar and Arabic. A debug build takes the same `--ez lowend true` (and `--ez devicehint true`); the desktop `-Dtmplayer.lowend=true`. Not seen: a real low-end device, a real stutter or memory warning raising the hint
- [ ] CP69 Full setup, Save mobile data and a quick tour after sign in, in all 21 languages, for 1.26.0 (user, 2026-10-14; built and pushed through f140fb7, checked on the TV emulator in TV and phone shapes, light, dark and Arabic, and on the desktop render tests; test14 built, mapping upload to Sentry verified; shipped in 1.26.0 on 2026-10-14 (device test skipped by the user); open: Save mobile data over real mobile data, native review of the new strings)
- [ ] CP70 macOS DMG preview, Apple Silicon, macOS 26 and newer, GitHub releases only, in 1.26.0 (user, 2026-10-15; built in CI on macos-26, signed ad hoc, TDLib 1.8.67 and mpv 0.41 load from the app and from the DMG, 110 MB; Cmd shortcuts; site card hidden until a release carries the DMG; INSTALL.md, notices, release notes row. Shipped in 1.26.0 on 2026-10-14: the macOS mediamp source attached, MAC_TAG=v1.26.0 set (77322bf), site-after-1.26.0 merged (238f0d9) and deleted, the Mac card live on /download/. Open: a real Mac test by the user's friends (playback, fullscreen, first launch), notarization only if wanted ($99 a year).
- [x] CP71 (live 2026-10-15, merge c089b1b; production matched the old site on all 267 pages, sitemap, feed and headers; the old `/styles.css` and `/app.js` copies and the comparison scripts removed the same day) Move tmplayer.org to Astro: layout and components, `<T>` keys over the same `site/i18n/*.json`, no generated HTML in git, every URL, SEO, OG, sitemap, feed, locale and header proven identical by a local side by side review before cutover (plan: docs/ASTRO-MIGRATION.md, AM00 to AM14; cutover off release days)
- [x] CP72 (live 2026-10-15, through 16fdad5; measure again 2026-11-12) Search, AI answers and calls to action (user, 2026-10-15): say the long name "Telegram Media Player" in titles and JSON-LD, FAQ answers shaped as the questions people search, a desktop page, a header download button and a phone bar instead of pop-ups, Markdown twins, llms-full.txt and IndexNow on every site change (plan: docs/SEO-PLAN.md, from the Search Console export of 2026-10-14)
- [ ] CP73 Cast to a Chromecast or Google TV from the phone and the desktop, the way VLC does it (user, 2026-10-18; after 1.27.0). A Cast button on the player finds receivers on the Wi-Fi; TMPlayer serves the video from Telegram over a small local HTTP server with ranges (Low storage mode and split pieces keep working); direct play when the receiver decodes the codecs, the sound alone converted to AAC (and the container remuxed) when only the audio or container is the problem, a full hardware encode only when the video itself cannot play, with a note saying so; plain subtitles as WebVTT, styled ones as text. The phone or desktop keeps the controls (play, pause, seek, skip, tracks, volume, stop, notification and lock screen), and the place is kept for Continue watching. Android uses Google's Cast framework, in the regular APK only, never in an F-Droid build (as the update check); the desktop speaks the Cast protocol itself. Later, if asked: Play on TV between TMPlayer apps. Built 2026-10-19, awaiting a test on a real Chromecast and Google TV: core `com.tmplayer.cast` has the server (`CastServer`: bound to the LAN address on the receiver's subnet, a random path token per session, 206 ranges, CORS, keep-alive, private addresses only, the oldest of more than three reads of one file closed), the probe (`MediaProbe`: Matroska head and Tracks, MP4 moov at either end), the decision table (`CastFormats`: MP4 and WebM direct, Matroska direct as WebM when it is VP8/VP9/AV1 with Opus/Vorbis, else rewrapped, direct Matroska tried on the Google TV models; HEVC, VP9, AV1 and 10 bit by the model name; AC-3 and E-AC-3 passed through, DTS, TrueHD, PCM and the rest converted where the device can, or the picture alone with a note), the rewrap (`MkvRemuxer`: Matroska to fragmented MP4 as it is sent, H.264, HEVC, AV1 with AAC, MP3, AC-3, E-AC-3, Opus, FLAC, fragments on keyframes, a seek is a new stream from the cue before it; checked with ffprobe and ffmpeg on real files), WebVTT from SRT, ASS and SSA, and `CastStreams`. Android: `CastSupport` over Google's Cast framework and MediaRouter in src/cast, a stand-in in src/nocast for `-PtmFdroid=true` (no Google dependency, the options provider taken out of the manifest); a Cast button on the player's top bar and the detail page (phone and tablet), the picker sheet, the cast screen in place of the picture (Casting to, state, timebar, skips, episode steps, audio and subtitle pickers, TV volume and the volume keys, Stop casting, Reconnect or Play here when lost), the framework's notification and lock screen controls, the place saved for Continue watching, the next episode loading on the TV, the sound converted with NextLib's FFmpeg decoders and MediaCodec AAC. Desktop: mDNS discovery and the Cast protocol written here (TLS on 8009, CastMessage protobuf, connection, heartbeat, receiver and media namespaces), a Cast button and device popover on the player bar, a casting panel, and the TV behind the same `PlaybackEngine`, so keys, bar, menus and OS media controls drive it. First device test (2026-10-19, POCO to a Mi TV Stick): an HEVC and Dolby Digital Matroska rewrapped as one endless stream stalled at about 70 s (the receiver took it for 45 s long), the phone kept saying Playing and counting while the TV was paused, the TV volume read as zero, and the cast screen was too tall in landscape. Fixed the same day: Matroska now goes as HLS first (`MkvHls`, `CastRoute.Hls`): a VOD master playlist, segments cut at the cues (about 6 s, from the header and Cues alone, `HlsSegments`), the picture in fMP4 (CMAF) segments made on request from the cluster of each cue with decode times from the presentation times (trun version 1, signed offsets), every soundtrack the receiver takes as its own EXT-X-MEDIA rendition (one variant per audio codec, DTS, TrueHD and the like converted per segment on the phone from half a second before the segment), every text subtitle track inside the file and every loaded subtitle file as segmented WebVTT renditions, served chunked from a folder resource (`CastResource.Tree`); LOAD says application/x-mpegurl with fMP4 segment formats. The receiver then knows the length, seeks itself, and switches soundtracks and subtitles through activeTrackIds (Android `setActiveMediaTracks`, desktop EDIT_TRACKS_INFO), its own track ids found by `HlsTrackMatch`; a file without cues, or a receiver that refuses the playlist, falls back to the old rewrap. The sender follows the receiver (`CastStateMapper`: PLAYING, PAUSED, BUFFERING, IDLE told apart by FINISHED, CANCELLED, INTERRUPTED and ERROR, the position run on from the status only while playing, a press shown at once), with Try again and Play here on an error or a stop on the TV. Volume comes from the MediaRouter route (fixed volume on a CEC TV said so), the desktop no longer takes the stream volume for the TV's. The phone's cast screen is the shared `CastRemote` (ui), compact in landscape with everything on screen, a column upright and on a tablet, light and dark, with speed when the receiver supports it; the desktop panel is one compact card. Checked with ffmpeg and ffprobe over HTTP on real files (H.264 and AAC; HEVC with two AC-3 tracks and SubRip: length, every frame, segments decoded alone at their times, each soundtrack, the subtitles); renders in desktop/build/cast-shots. To try on the stick: the same HEVC and DD Matroska past 70 s, a seek to the middle, both soundtracks, the subtitles inside it, pause on the phone and on the TV remote, the volume slider. Not done: converting the picture itself (HEVC on a first generation Chromecast is refused with a note), sound conversion on the desktop (a DTS-only Matroska goes without sound there), switching the soundtrack of an MP4 sent as it is, and keeping a cast going after the phone's player is closed. Second device test (2026-10-19, POCO F5 to the Mi TV Stick, a 1.2 GB H.264 Matroska with E-AC-3 5.1 and AAC 2.0 Malayalam and English subtitles): HLS played past 2 minutes, seeked to 1:22:29 in about 2.6 s, the compact screen fit, the TV volume followed and Stop casting ended the session. Fixed after it: (1) the first tap from the detail page dropped at once, because MediaRouter ignores selecting a route still selected from an earlier session; the stale route is let go and selected afresh, discovery is held until the session is up, and a stuck connection fails after 30 s (`CastBuild`). (2) Turning on the subtitles failed the video: the VTT segments wrote `X-TIMESTAMP-MAP=MPEGTS=0,LOCAL=...` with equals signs, and Shaka Player (the Default Media Receiver's HLS player since May 2026) throws a critical INVALID_TEXT_HEADER on a map it cannot read; now RFC 8216's `MPEGTS:0,LOCAL:00:00:00.000`, proven by `HlsShakaTest` (Shaka in headless Chromium against the cast server). Subtitles stay off while casting (`CAST_SUBTITLES = false`: no Subtitles button, no subtitle renditions or side tracks) until a retest on the stick. (3, 4) Try again and the fallback started at 0:00, and Stop casting resumed at the place before casting: a failed or reloading receiver reports 0, which became the place. `CastPlace` keeps the place from a playing, paused or buffering TV only, holds the start asked for after a LOAD or a seek until the TV gets there, and the phone saves it for Continue watching through reloads and pauses (the desktop too). (5) The TV's list had only the AAC track while the picker showed the E-AC-3 one on: Shaka drops a variant whose codec the TV's MSE refuses. Dolby now goes twice where the phone converts (as it is and as AAC, `a2` and `a2c`), the picker lists only what the receiver kept, one line per track, the one the TV plays marked (`HlsTrackMatch.audioOffers`, `settle`), and a codec the TV turned away is converted in the rewrap fallback too. (6) The detail page's Cast sat over Back (a tooltip box took the align); now at the top right, Back and Cast 48 dp with 24 dp icons, Cast filled while connected, before Close in one row on a tablet. To retest on the stick: the first tap connects; the AAC copy plays and the picker shows it; Try again and a stop keep 1:23:30; Stop casting resumes there; the detail page's buttons; then subtitles with `CAST_SUBTITLES` on.
- [ ] CP74 @username search and a chat info sheet (user, 2026-10-18): Search finds a person, group or channel by @username, also outside the loaded list; tapping a chat's avatar and name opens a bottom sheet on a phone (side panel on TV and desktop) with the avatar, name, copyable @username, bio or description, member count, and a share button with the t.me link for public groups and channels.
- [ ] CP75 Videos split into raw byte pieces (`.part001`, `.mkv.001`) found as one video and played as one continuous stream, not as copies (user, 2026-10-18, a real chat with a 4K video in seven 3.9 GB pieces).
- [x] CP76 (built 2026-10-19, next release; device pass still to do) Notifications that help (user, 2026-10-18). Every one opt-in or quiet by default, rare, with its own switch in Settings and its own Android channel, none on a TV (its launcher rows do that job), the desktop through the system's own notifications. (a) The support ask gets action buttons: Buy me a coffee, Star on GitHub, Not now; a star is asked for on its own rung, before money, and once given (or Don't ask again) never again. (b) Downloads: one notification when a download or a whole season finishes or fails, with Play and Retry. (c) New episode of a show you are watching (narrower than every new video in starred chats): "Episode 5 of X is in Y", with Play. (d) Low storage: when the cache or downloads pass the limit or the device runs short, with Clear the cache. (e) A missing piece of a split video arriving. (f) Update ready, on Android and the desktop, when the app is not open (the popup already covers an open app). **Built:** one `Notice` per kind in `:core` with its own switch (`SettingsStore.noticeOn`), all off but Finished downloads (Sponsor reminders too, which was on), a Notifications page in Settings on the phone and the desktop (none on a TV; the new videos and sponsor switches moved there), turning one on is where Android 13 asks for the permission. The rules, unit tested in `NoticeRulesTest`: (a) `SupportReminder.NudgeRung`, a star on its own first ("Enjoying TMPlayer?"), then "Don't forget to sponsor" on the same 30 day cadence; buttons Buy me a coffee, Star on GitHub (gone once pressed, here or on the window) and Not now (`NoticeActions` on Android, `NoticeButton` on Linux D-Bus and Windows toasts). (b) `DownloadResults` groups a season and the pieces of a cut video into one notification once the last settles, with Play (the earliest episode, the first piece) and Try again (the failed ones, `DownloadService.EXTRA_FILE_IDS`); its own `download_results` channel; the desktop keeps Open for a lone video and groups the rest. (c) `EpisodeAlerts`: the furthest episode watched per season from Continue watching and the watched list, the chats it was watched in plus the starred ones, said once per episode in whichever chat, with Play. (d) `StorageAlerts`: under 1 GB or 5 per cent free, or recently played over `CacheShelf.ceiling`, once per low spell and at most every three days, Free up space opening Downloads with its sheet. (e) `PieceAlerts`: the player remembers a cut video with a gap, the chat check looks again and says which part arrived, or that every part is there. (f) `UpdateAlerts`: once per version, never a skipped one, only with the app closed (Android, a twelve hour job) or in the background (the desktop, from the update check it already runs). Every one names nothing from a chat the kids lock hides or behind the app lock, and Play goes through the home screen launch, which checks the kids lock again.
- [ ] CP77 Proxy settings (MTProto, SOCKS5, HTTP), reachable before sign in, a t.me/proxy or tg://proxy link or QR adds one, ping and switch (addProxy, getProxies, enableProxy, pingProxy). For places where Telegram is blocked; a TV has no other way through.
- [ ] CP78 Sign in gaps: email code (AuthorizationStateWaitEmailCode now ends in an unsupported step) and Forgot password for two-step verification.
- [x] CP79 (built 2026-10-19, next release; 567cfaf, a072a1c, c2041d9; device pass still to do) Telegram links: open a pasted t.me message link (t.me/chan/123, t.me/c/id/msg, topic links) and play that video (getMessageLinkInfo); preview and join an invite link (checkChatInviteLink, joinChatByInviteLink, with a confirm); join a public channel found by username; Android opens t.me links when the user picks TMPlayer. **Built:** `TelegramLinks` reads t.me, telegram.me and telegram.dog posts (`/<name>/<id>`, `/c/<internal>/<id>`, a topic between, `/s/` previews), `?t=` in seconds, `1m30s` or a clock, `?comment=` and `?thread=`, invites (`+hash`, `joinchat/`) and the tg:// forms (resolve, privatepost, join); Telegram's own paths (proxy, addstickers, joinchat) are never taken for a username any more. `TelegramLinkActions` + `LinkRules`: a post's video plays through each platform's own play path from the link's time (BookmarkJump), a post without one opens its chat and says so, a private chat not joined or an unknown post is refused in words; an invite shows title, picture, kind, member count and description, a paid one is left to Telegram, an already joined one opens; Join (or Ask to join) only on a press, INVITE_REQUEST_SENT, already a member, expired, too many chats, full and banned all worded. One flow for every way in (`LinkFlow`, `LinkRequests`): Search on the phone (a line over the results), the TV (a pill beside the field) and the desktop palette (first row) offer Open this post, Preview and join, or Join <chat> for a public group or channel found by @username that the account is not in; the chat info sheet and the desktop info panel have Join with a spinner. Android: VIEW filters for https t.me hosts and tg://resolve, privatepost and join with autoVerify off, so TMPlayer is a choice in the chooser, never the default. The kids lock refuses links to chats it hides and every join. Unit tested (TelegramLinksTest, LinkAndKeepRulesTest), desktop render shots of the cards (LinkAndKeepShotsTest). Not seen on a device: every link kind against real Telegram, the chooser on a phone, the TV pill.
- [x] CP80 (built 2026-10-19, 5399f79; device pass still to do) Telegram's own video qualities (MessageVideo.alternativeVideos: its 360p and 720p copies of a big upload) for a slow line and Save mobile data; the uploader's cover and start time (MessageVideo.cover, startTimestamp); seek thumbnails from Telegram's storyboards, which also give the desktop and 1 GB sticks scrub previews. **Built:** `MediaItem.qualities` (`TelegramQualities`: lighter than the upload only, one per height, H.264 first), `playingQuality` with the upload's id kept, so the place, Watched, bookmarks and a download always speak of the upload; `QualityWish` hands a choice to the player as `BookmarkJump` does. The detail page has a quality line under the copies (As uploaded, Telegram's 720p, ...), the player's copies list (phone and TV sheet, desktop menu) lists Telegram's copies under the upload; the player picks a lighter one itself under Save mobile data on mobile data (best up to 720p) and when `LineSpeed` (measured on this run while bytes move) is under the upload's rate with 30 per cent headroom, says so in a flash, and the "not arriving" sheet offers Play Telegram's 480p (Android `status_lighter`, desktop LoaderSheet and PlaybackStall). The cover is the card's thumbnail (largest size up to 800 px). `PostStart`: a link's ?t= wins, then the saved place, then the poster's start time (a t.me ?t= arrives from CP79's link opener as a BookmarkJump, which wins over both; Android also takes `EXTRA_LINK_START_SEC`). Storyboards: `StoryboardMaps` reads WebVTT, number lines and key=value maps and falls back on the grid (Telegram does not document the map format, so the real one is still to be seen), `JpegSize`; Android `StoryboardThumbnails` (region decoder, alone on a 1 GB stick, behind TrickplayFrames elsewhere), the desktop timebar hover (`StoryboardTip`), both under the scrub pictures setting. Unit tested (TelegramQualitiesTest, PostStartTest, StoryboardMapsTest), desktop render of the quality line. Not done: Telegram's HLS playlists (the MP4 copy is played), switching quality without reopening the player, a lighter copy chosen mid-play by itself (the stall sheet offers it), the desktop's Save mobile data (it has none). Not seen: a real post with alternative videos, a cover or a storyboard map.
- [x] CP81 (built 2026-10-19, next release; 567cfaf, a072a1c, c2041d9; device pass still to do) More places videos live: comments under a channel post (getMessageThread, videos in the thread), round video notes and videos in link previews, a chat's pinned post. **Built:** a channel post with comments gets Videos in the comments on its page (phone, TV, desktop): the thread read with getMessageThreadHistory a hundred at a time, 500 comments per Look further back, its videos played like any other, and the discussion group must be allowed by the kids lock too. Round video messages (SearchMessagesFilterVideoNote) are counted and listed from a Round videos entry over a chat's videos, drawn in circles, and a chat of nothing else is never "No playable videos" alone; MediaMapper maps video notes and a text post's link preview video (LinkPreviewTypeVideo or VideoNote with a file) so they play from a link, a pinned post and the comments. The pinned post (getChatPinnedMessage) shows over the videos when it is a video (plays) or links to a post (opened through the CP79 flow). Live updates still add only what the grid lists. Open: a chat's link previews are not searched for videos (TDLib has no filter for preview videos; they play when met through a link, a pin or a comment).
- [x] CP82 (built 2026-10-19, next release; 567cfaf, a072a1c, c2041d9; device pass still to do) **Forwarding removed 2026-10-21** (user: it did not look good; branch worktree-agent-a09673dc9ad393dd8, commit fa23118): Forward to Saved Messages and Forward to a chat are gone on every platform, the gallery and folder copy stay. Keep a video: forward it to Saved Messages or another chat without downloading (forwardMessages, only where it can be forwarded), and copy a download to the phone's gallery (MediaStore, only where it can be saved). **Built:** Forward to Saved Messages (one press) and Forward to a chat (a picker: Saved Messages first, then people and groups, never channels, only what the kids lock allows, a find field on a phone and the desktop) on every video's page and hold menu, phone, TV and desktop; every piece of a cut video and every part of a film in parts go together. `KeepVideo` asks getMessageProperties at the press; a chat that restricts saving keeps the lines, which say why and refuse. Save to the phone's gallery copies a finished download to Movies/TMPlayer through MediaStore (Android 10 and later, phones only), and the desktop's Copy to a folder copies it where the viewer picks, never over a file already there (`DesktopExport`); both only where saving is allowed, asked again at the copy. Unit tested (LinkAndKeepRulesTest, GalleryExportTest, DesktopExportTest). Not seen: a real forward, the gallery copy on a phone.
- [x] CP83 (built 2026-10-19, 1e02199 and 5399f79; device pass still to do) Account safety: active sessions, end another device's session (getActiveSessions, terminateSession); say why a download is slow for an account without Premium (speed limit updates), information only. **Built:** a Devices page (`DevicesScreen`, shared) from a row in Settings on the phone and TV (category list, under App lock) and the desktop (App lock page): this device first and marked, the others newest first with app, system, place, address, signed in date, unofficial app, unconfirmed or password pending flags; End session and End all other sessions, each confirmed in place with Cancel focused (`AccountSessions`, `terminateSession`, `terminateAllOtherSessions`). Inside Settings, so behind its PIN. `Td.premium` from the is_premium option and `updateSpeedLimitNotification` (`SpeedLimit`, five minutes) add one line to the player's slow note and the stall sheet on Android and the desktop. Unit tested (AccountSessionsTest), render shots on phone, TV and desktop. Not done: the speed note in Downloads; the session TTL setting.
- [ ] CP84 Several accounts (one TDLib database each); large, after the rest.
- [x] CP85 Archives in a chat (COMPETITORS item 8; 9c704df): ZIP, RAR, 7z, tarballs and their volumes met among a chat's documents are listed under its videos as files that cannot be played, each with the reason and a press that says it again; a chat of archives alone lists them under "No playable videos". Open: streaming a stored (uncompressed) ZIP's single video, which needs its local header read through TDLib and a byte window into the file (the split pieces' joined source is the place for it); not started.
- [x] CP86 Black theme (COMPETITORS item 18; 9c704df): Theme is System, Light, Dark, Black on phone, TV and desktop; Black is the dark scheme on true black with the surfaces stepped up from it (`pureBlack()` in Palette.kt), on the wallpaper's colours too. Render shots of the empty states in black (SelectedAndEmptyShotsTest). Not yet seen on a device. 2026-10-19 (495edea): Black became a Pure black switch under Theme, the usual Android way (System, Light, Dark plus "Pure black", applied whenever the app is dark, shown under System and Dark), a stored or backed up Black read as Dark with the switch on. Same day (41da8cb): accent colours as swatches under it, Telegram blue by default, Red, Orange, Amber, Emerald, Teal, Violet, Pink and Monochrome, AA checked in light, dark and pure black; the wallpaper's colours win on Android. Both for 1.27.0.
- [x] CP87 Night mode for sound (COMPETITORS item 14; 9c704df): a line beside Volume boost in every player menu, remembered. Android squeezes the range in the boost's processor (+6 dB, -15 dBFS at 6:1: a full scale peak lands near -11 dBFS, dialogue at -30 comes up 6 dB; unit tested). The desktop's bundled FFmpeg has only aresample, anull and a few others, so no `acompressor` or `dynaudnorm`: Night mode asks for `acompressor` (works the day the build carries it), turns on a Dolby track's own range control (`ad-lavc-ac3drc`) and lifts the centre channel on a surround fold; Playback details says which. Open: a mediamp FFmpeg with `--enable-filter=acompressor,dynaudnorm` (then Volume boost levels too).
- [x] CP88 Audio as a playlist (COMPETITORS item 13; db5f225): a chat's audio files and voice messages counted and listed from an Audio entry over its videos on phone, TV and desktop; Play all from the oldest, or one file, each going on to the next one posted (PlayQueue feeds the players' next episode); sound alone shows its name and chat where the picture would be, and plays on with the screen off on a phone whatever the background audio setting. Open: audio posted as plain files (an .mp3 sent as a document) is not listed; the album cover is not drawn in the player; no TV background playback; the queue is the list as loaded, not a saved playlist.
- [x] CP89 Resume on your other devices, opt in (COMPETITORS item 11; e44b7f8, db5f225): Continue watching places kept in one message in the account's own Saved Messages, found by `#TMPlayerSync`, edited in place and never sent twice; newest place wins per video, a removal is remembered for a month; read at start and briefly before a video opens, written once the player's saves settle; a switch in Settings (Library, Lists) on every device with the whole explanation. Unit tested (ResumeSyncDocTest). Not yet tried across two real devices.
- [x] CP90 Store listing and site for phones (COMPETITORS items 20, 21; 830aa68): short description names Android TV, phone and PC, the full one about 2000 characters within the wording rules; home title "for TV, phone and PC"; a new localized `/android/` page (Base head, BreadcrumbList and app JSON-LD, lastmod.json with the phone shots, llms.txt, footer and download page links, StarCard, CoffeeCard). Other languages show the new strings in English until translated (catalogs at 97 per cent).
- [x] CP91 UX sweep, every screen (user, 2026-10-18; 9c704df, 6cd78fb): Search all chats on every "nothing matches", Refresh on "No chats yet", Go to Chats on empty tabs, History and Downloads, a failed or slow next page with Try again, mid-play stalls with the opening's sheet, search back online and slow, Home rows that come back with the line, chat info and online subtitles that say when they fail, Free up space on the desktop player and on failed downloads, TV focus after removals and deletes, dp offers, subtitles inside the picture on the desktop. Open from the sweep: delete failures are still toasts without Try again; the desktop storage move failure is a toast; the TV Home can stay unfocused for up to 30 s while a starred chat's row is loading; online subtitles have no "load a file" on the desktop panel.
- [ ] CP92 web.tmplayer.org (planned for later, not started; the full plan, with what a 2026-10-19 prototype proved, is [WEB.md](WEB.md)): TMPlayer in the browser with as much of the app as a browser allows (user, 2026-10-19: "maximum features from this project"). Telegram through TDLib's WebAssembly build (tdweb, a maintained community build) behind a small bridge; the shared core given a web target (its java.io and org.json uses moved behind platform layers, kotlinx.serialization); the desktop's Compose UI on Compose for Web (Wasm). Playback: a Service Worker serves /stream/<file> with byte ranges out of TDLib to the browser's own video element, so seeking and Low storage windows work; split pieces joined the same way. Parity target: QR and phone sign in with email code and proxies, Home with the carousel, Continue watching and rows, Chats with folders, chat grids, series and show pages with seasons, episodes and skip intro, copies and qualities, Search with @username and filters, chat info sheet, online metadata, subtitles (text tracks as WebVTT; online subtitles), audio and subtitle choice, speed, chapters, bookmarks, A-B repeat, frame step, keyboard shortcuts as on the desktop, picture in picture, Watched, Watch later, favourites, history, sort, rename and fix matches, resume across devices through Saved Messages, the kids lock and PIN, themes including black, 21 languages, tooltips, notifications through the browser when allowed, and casting through the browser's Cast support. Plays what the browser decodes (MP4 and WebM with H.264, VP9, AV1, AAC, Opus; HEVC where the browser has it), repacks MKV with H.264 and AAC in the browser, converts AC3 or E-AC3 sound with WebCodecs where it can, and for the rest (DTS, styled ASS, files past the browser's storage, offline downloads, TV) says plainly that the video needs the app, with the right download button for the device. Phases: (1) proof: tdweb bridge, QR sign in, chat list, a grid, an MP4 played through the Service Worker; (2) the core's web target and the desktop screens ported; (3) the player's full feature set and the codec handoff; (4) PWA install, the site's SEO, legal and privacy pages for the web app, analytics as the site has.
- [ ] CP93 Next up and watched marks: Next up cards in Continue watching, mark a season watched or unwatched, skip automatically, countdown length (phone, TV, desktop; details in [OTT-REVIEW.md](OTT-REVIEW.md), from the 2026-10-19 review of other video apps)
- [ ] CP94 Download the next episode, paired with Remove after watching, Wi-Fi only (phone, desktop; TV off by default; after CP93)
- [ ] CP95 Player: paused card (ask first, reverses part of 1.16.0) and desktop scrub frames (phone, TV, desktop; frames need CP80 or the local fallback)
- [ ] CP96 Viewers on one account: own Continue watching, lists, speeds, kids lock per viewer (TV first; L; after CP93)
- [ ] CP97 Desktop OS integration: macOS Now Playing and menu bar, Windows thumbnail buttons and Jump List, Linux desktop actions
- [ ] CP98 Android shortcuts and accessibility: launcher shortcuts, subtitles following the system caption style, high contrast focus on TV, TalkBack pass
- [x] CP99 TV top bar in place of the side rail, Netflix 2025 style (2026-10-19, tried on the stick and taken for 1.27.0): the account's picture first (its menu holds Favourites, Downloads, History, Watch later, Refresh and Update), then Search, Home, Chats and Settings, the app's mark at the end; the current tab marked by its filled icon; Up from the top of any page to the bar, Down back where the remote was (Home's Play with nothing scrolled), Back to the bar and from the bar to Home; pages full width under one start inset and clipped at the bar, no page headings; Home's focused tile held at the row's start. Commits d40d8fd, 46f5cb8, daacc9b, 0a88643, 2486a43, 15c2e68, b077f48, ec01483, 5b8113f, 55abaf9 and the one ticking this. Separate: 3109db3, the minimal Home hero (no segments, no facts line) and See all as an accent link.
- [ ] CP100 Shorter first run, colours in it (user, 2026-10-19; planned, not started). Today a first run is up to about 12 screens: Appearance (1), the preference steps (Posters, Storage or Device, Performance, Mobile data on a phone: up to 4) and the quick tour (5 to 7 pages, `Tour.pages`). Grouped into about 5:
  - [ ] Appearance, one screen: Theme, the Pure black switch and the accent swatches (the colours from the Appearance work, Telegram blue first and selected) and interface size, with the screen behind it repainting live as they change.
  - [ ] Preferences, one screen of rows with the recommended answer already set: online posters, Low storage mode, Save mobile data (phone only). Each is a switch with its one line of why, instead of a page per question with two cards.
  - [ ] Performance and the Device step only when the device check finds it short of memory or storage (CP68), folded into the same screen as one "Recommended for this device" row that is on, not a page of its own.
  - [ ] The tour cut to three pages by pairing what belongs together: Videos with Home, Player with Controls, Downloads with Settings (TV: TvHome with Settings), each still one marked picture with its numbered lines; offered as "Show me around" with Skip on the preferences screen's button rather than started by default.
  - [ ] One step count and bar across the whole run, Back on every screen, Skip all kept, D-pad order on TV top to bottom then the buttons; the onboarding pictures and their specs (scripts/annotations, `scripts/tour-badges.py`) retaken for the merged pages.
  - [ ] Render shots of every new screen on phone portrait and landscape, TV and desktop, light and dark, and a timed run on the stick against today's flow.
- [ ] CP101 Robolectric: a setting changed in the middle of a render test (DataStore written from the test, collected by the screen) sometimes never reaches the screen when other render tests ran first in the same process; AppearanceRenderTest dropped its two checks on it (the switch folding under Light, the wallpaper line) on 2026-10-19 and keeps the shots, the behaviour checked on the phone instead. Find why (the shared SettingsFile, the paused looper and DataStore's IO collector) and restore them. Also FeaturedCarouselRenderTest's first case sat for 58 minutes in one full local run and a CI run took 56 minutes.
- [~] U31 Sentry on the free plan (2026-10-19: organization token made, `org:ci` covers ProGuard uploads, repository secret and local.properties set; still open: the email alert, spike protection, and a `tmplayer-desktop` project with `gh secret set SENTRY_DSN_DESKTOP`, until then the desktop sends to the Android project)
- [x] CP102 Crash reports that arrive and can be read (user, 2026-10-19; built the same day, ships with the next release; open items in the parts):
  - [x] CP102a Mapping upload from CI and the 1.27.x backfill (20a58d5; 1.27.0 and 1.27.1 mappings uploaded by hand 2026-10-19; the first CI upload is seen at the next release; keeping mappings past 90 days is the user's call)
  - [x] CP102b A crash from an older build is still sent, decoded with that build's mapping (86024bc; decoding across builds seen only after the next release)
  - [x] CP102c The crash prompt shown before Home builds, so a start-up crash loop is still reported (523ca73, 13eb792 also asks on the sign in screens; checked on the TV emulator: asked after two start-up crashes, Send went)
  - [x] CP102d App not responding and native crashes (TDLib, mpv, FFmpeg) (9cd0d73; TV emulator: native crash and ANR asked, sent, a declined one not asked again; switch on not tried)
  - [x] CP102e Stay inside the free plan's 5,000 errors a month (cffc887, unit tested)
  - [x] CP102f The crash report question in the first run (10e4712, its own setup step until CP100 folds it in; render tested, not seen on a device)
  - [x] CP102g Crash reports on the desktop (6aa2fc9, f8cc844, 68a351c privacy and security pages; unit and render tested, not run on Windows, Linux or macOS yet: do it in the pre-release VM pass)
  - [ ] CP102h (ask the user first) Chosen errors the app survives

## Standing rules

- **Stop at every CP.** Commit, tick, push, report, wait for `/clear`.
- **Never without the user saying so in that session:** cut a release or tag, submit to a
  store, registry or list, upload to an existing GitHub release, or post anywhere.
- **Releases:** run the cadence guard first (`gh release list --limit 10`). 1.20.0, 1.21.0, 1.22.0 and 1.22.1
  went out on 2026-10-04 and 2026-10-05, so nothing before 2026-10-13. Write the release notes in the annotated
  tag message: the update popup and the Telegram post both read it.
- **Push freely** to main.
- **No em or en dashes** anywhere (CLAUDE.md has the grep). Screenshots are WebP q88; `site/og.png` and
  `site/icon-512.png` stay PNG.
- **Commits:** no Claude co-author trailer.
- **Strings:** until CP19 lands, new UI text is plain English literals; CP21 and CP22 extract them. From CP19 on,
  every new string goes into `en.json`.
- **Bulk edits** use grep, sed, perl and jq (Design reference R9), not hand edits.
- **Every CP's verification baseline:** `./gradlew build`, the `:core` jvmTest suite, and the dash grep.
  TV tests use the TV emulator, desktop tests `./gradlew :desktop:runPlayerDev --args="--file clip.mkv"`, site
  screenshots use headless Chromium.
- **Screenshots and device tests (user, 2026-10-06):** emulators first. Phone UI screenshots and checks run on the
  phone emulator (`tmplayer_phone_api36`) with the promo flavor, TV on `tmplayer_tv_api36` with the promo flavor
  (fake data, no login). Use the POCO (adb `d3f7da23`) only for what an emulator cannot show: real Telegram data
  and login state, hardware decoding and codecs, audio output (volume boost, Dolby), real storage and battery.
  On the POCO install only the release-signed APK with `adb install -r` (keeps the Telegram login) or the promo
  build (`com.tmplayer.promo`, installs alongside); never the plain debug build, which would wipe the login.
  Each CP's "Done when" names which screenshots it needs; keep them outside the repo unless they replace a
  README or site screenshot (then WebP q88 per CLAUDE.md).

## Claude checkpoints

### CP01 Crash reports and privacy page match each other
**Why:** the privacy page, store text and in-app toggle say a crash report is only a stack trace, app version,
Android version and device model, with no identifier. Commit `2d73eeb` only nulled `device.id` and
`device.name` and redacted some paths. The SDK still sends timezone, battery, memory, storage, screen and
connection details, and the OS and app contexts carry more.
**Do:**
- `app/src/main/java/com/tmplayer/data/CrashReports.kt` `setBeforeSend`: keep only the exception and stack
  trace, `release`, OS name and version, and device model and manufacturer. Drop or null every other device
  field (timezone, locale, battery, charging, memory, storage, screen, orientation, connection type, boot
  time, simulator flag), app context extras (start time, permissions, in-foreground), `event.serverName`,
  `event.tags` that are not ours, `event.contexts` other than os, device and app, and the per-install
  `installationId` wherever it lands. Remove the stray trailing whitespace lines.
- Redact file paths in exception messages and in stack frame `vars`, and anything that looks like a Telegram
  file name (any token ending in a video extension).
- A unit test that builds a full `SentryEvent`, runs the callback and asserts the allowed field list exactly.
- `site/privacy/index.html` (2d73eeb touched it; check every point and finish what is missing):
  - covers phones, Android TV, Windows and Linux: where the session, cache and downloads live, that
    uninstalling leaves them, that titles show in the system media controls (MediaSession, MPRIS, SMTC);
  - a "Delete your data" section: sign-out keeps downloads unless the box is ticked, the desktop folders,
    and telegram.org/deactivate for the Telegram account;
  - GDPR basics: who runs it, consent as the basis for crash reports, retention, processors (Sentry, Vercel,
    GitHub), how to withdraw consent, the right to complain, a link to Telegram's privacy policy;
  - sponsored message views and clicks are reported to Telegram; the update check reveals the version and IP;
  - the effective date and a short change list;
  - the EU storage claim matches U01's answer (if U01 is not done, leave a TODO comment and say so).
**Done when:** the test passes; the privacy page lists exactly what the callback keeps; headless Chromium shot
of `/privacy/` at 390 and 1280 wide.
**STOP**

### CP02 Wording: "unofficial" everywhere, site titles, release notes text, legal Terms
**Why:** Telegram API terms 2.2 want the intro to say the app uses the Telegram API. Only the Android
`IntroScreen.kt` says it today; desktop has nothing.
**Do:**
- Desktop sign-in or first screen: the same line as Android, "TMPlayer is an unofficial app that uses the
  Telegram API. It is not made by Telegram." Use one shared constant or the same wording on both.
- Site `<title>`s and `og:title`: "Unofficial Telegram video player" instead of "Telegram video player" on
  every page (`site/*.html`, `site/*/index.html`).
- `.github/workflows/release.yml` line ~372 says "Installed copies update themselves". Make it "Installed copies
  offer each update", the same as SECURITY.md. Same text in `scripts/post-release-telegram.py`.
- The FAQ: "nothing passes through anything of mine" becomes "no media or account data passes through".
- The analytics note: "a first-party proxy to Vercel Web Analytics, processed by Vercel".
- `/legal/`: the sponsored messages caveat, and a short Terms section with the GPL no-warranty clause and a
  link to Telegram's terms (check what 2d73eeb already added).
- grep for "No ads" and "no ads" across site, README and fastlane; every one carries the sponsored messages
  caveat.
**Done when:** grep shows no bare "No ads" and no "update themselves"; Chromium shots of `/`, `/legal/`, `/faq/`.
**STOP**

### CP03 Third-party notices, desktop corresponding source, notices inside packages
**Why:** this is the one real licence blocker. The desktop builds bundle mediamp's mpv runtime (libmpv, GPL on
Linux; FFmpeg 8, LGPL; libplacebo, libass, dav1d; on Windows glib, fribidi, libiconv, libintl and the GCC
runtime) without source. Commit `2d73eeb` added a placeholder: `scripts/build_mediamp_source_archive.sh` only
echoes, its versions are wrong (mpv 0.38, FFmpeg 7.0), `THIRD_PARTY_NOTICES.md` credits the wrong author
("Khang Le"; mediamp is `org.openani.mediamp`, Open Ani) and links
`releases/download/v1.21.0/mediamp-corresponding-source-0.5.0.tar.gz`, which does not exist.
**Needs:** U02 for the licence label (default GPL-3.0-or-later if the user has not answered) and the logo source.
**Do:**
- Find mediamp 0.5.0's runtime build scripts (the open-ani/mediamp repo at the 0.5.0 tag) and read the exact
  library versions they build for Windows and Linux.
- Rewrite the script (rename to `scripts/build-mediamp-source.sh`, kebab case like the others): download the
  mediamp tag tarball plus the exact source tarball of every bundled library, verify SHA-256 sums pinned in the
  script, pack `mediamp-corresponding-source-0.5.0.tar.gz` with a README listing each library, version,
  licence and upstream URL.
- release.yml: a guard like the nextlib one that fails the release if `mediamp` in `libs.versions.toml` moves
  away from the version the archive covers, and a "mediamp source" link in the notes next to the nextlib one.
  Uploading the archive itself needs the user's go-ahead (CP12).
- `THIRD_PARTY_NOTICES.md`: fix the mediamp entry; a desktop section with every library above; Sentry (MIT),
  OpenSSL 1.1.1w inside Android TDLib with its acknowledgement, Compose Multiplatform, Skiko, JNA, dbus-java,
  FileKit, the bundled Temurin 21 runtime (GPL-2.0 with Classpath Exception); "Solar Icons by 480 Design,
  CC BY 4.0, modified"; the player logo per U02.
- One licence label everywhere (README, winget, AUR, metainfo, MSI): GPL-3.0-or-later unless U02 says otherwise.
- Add a GPLv3 section 7 additional permission for OpenSSL to our own code (a short `LICENSE` addendum and a
  README line), since the FSF treats OpenSSL 1.x as GPL-incompatible.
- Ship `LICENSE` and `THIRD_PARTY_NOTICES.md` inside the MSI, AppImage, tarball and AUR package
  (`desktop/build.gradle.kts` app resources plus `desktop/packaging/`), and show them from About (CP05).
**Done when:** the script runs end to end in the scratchpad and produces the archive (do not upload);
`./gradlew :desktop:packageDistributionForCurrentOS` (or the Linux tarball task) contains both files.
**STOP**

### CP04 Respect "restrict saving content" and self-destructing media
**Why:** Telegram API terms 1.4, and `/legal/` already promises the app "does not bypass Telegram
permissions". Today nothing checks it, and desktop writes downloads to the visible Downloads folder.
**Do:**
- In `:core`, read TDLib `message.can_be_saved` and `chat.has_protected_content`, and treat messages with a
  `self_destruct_type` as not playable.
- Protected: streaming still works (it is what the official clients do), but Download, "Keep", share and
  "open in another player" are hidden on phone, TV and desktop, and an existing download of such a file is not
  moved to the downloads folder (it stays in the cache and is evicted as cache).
- Skip self-destructing media in the grid, with the hidden count in the existing "N hidden" line.
- Tests in `:core` for the filter.
**Done when:** a protected test channel shows no Download action on phone and desktop. If no protected channel
is available, say so and test with a forced flag.
**Result (572287c):** built and unit tested; no phone was attached and no protected channel tried, so check one
in CP11. Known gaps: rows rebuilt from stored records (Continue watching, Watched, Downloads) do not carry the
flag, so their actions ask `Td.maySave` when pressed, and the move into Downloads checks it again. Files already
in Downloads keep Show in folder and Open with. `./gradlew build` fails on lint errors that were already there
(UnsafeOptInUsageError in PlayerControls and App, NewApi in themes.xml); run it with `-x lint` until fixed.
**STOP**

### CP05 About screen on phone, TV and desktop
**Why:** GPL-3 section 5(d) wants appropriate legal notices in the UI; Uptodown's terms want contact details in
the app; the TV has no browser, so the Privacy and Lawful use links do nothing there today.
**Do:**
- Settings, then About, built once in `:ui` shared code where possible: version and build, "GPL-3.0-or-later,
  no warranty", links to source, `THIRD_PARTY_NOTICES`, the corresponding source (nextlib and mediamp),
  Privacy, Lawful use, hello@tmplayer.org, the Telegram channel and group, Discussions, Sponsors and Buy Me a
  Coffee ("voluntary, unlocks nothing").
- On TV every link is a QR code (reuse the QR component in `:ui`), including the existing Privacy and Lawful
  use rows.
- The notices file is also viewable offline inside the app (bundled asset).
**Done when:** screenshots of About on phone, TV emulator and desktop.
**STOP**

### CP06 Subtitle and audio delay, subtitle style
**Design:** R4. **Do:**
- Delay in ±0.1 s steps in the track menu on every platform, remembered per file in `ResumeRecord`.
  Android: a `SubtitleView` timing shim for text, an `AudioProcessor` offset for audio. Desktop: mpv
  `sub-delay` and `audio-delay`.
- Subtitle style: size S, M, L, XL; background box on or off; position; a live preview in Settings and the
  player menu. Replaces the fixed `0.065` in `PlayerActivity.kt` and `sub-font-size` 46 in
  `MpvPlaybackEngine.kt`. Stored in `SettingsStore` so all platforms share the key names.
- TV menus are D-pad reachable (`PlayerTvMenu.kt`).
**Done when:** a clip with an offset subtitle can be brought into sync on phone, TV emulator and desktop.
**STOP**

### CP07 Volume boost, sleep timer and "still watching?", TV next-up card
**Design:** R4. **Do:**
- Volume boost or night mode: a gain plus compressor `AudioProcessor` on Android (next to the PCM buffer
  floor work), mpv `volume-max=150` and `af=dynaudnorm` on desktop. A toggle in the player menu.
- Sleep timer in the player menu, not on screen (keeps the 1.16.0 decision). "Still watching?" after N
  autoplayed episodes (default 3) or 2 h with no input: a full-screen card that pauses playback and stops
  downloads.
- Port `buildNextUpCard` to the TV branch with D-pad focus on "Play now".

Split in two (2026-10-06): CP07a is everything above on Android (phone and TV) plus the shared rules
in `:core` (`StillWatching`, `SleepTimer`, the `volume_boost` setting). CP07b is the desktop half:
`volume-max=150` and `af=dynaudnorm` behind the same Volume boost toggle, the sleep timer in the
player menu, and "Still watching?" from the same `StillWatching` rules, pausing mpv and the queue.
**STOP**

### CP08 Resume rules, desktop player extras
**Design:** R4. **Do:**
- Read `ResumeRecord.kt` first and only fill gaps: no resume point in the first 3 min or the last 8 %;
  watched at 90 %; save tracks, speed and delays with the position every 10 s as well as on pause and quit.
- Desktop: frame step (`.` and `,`), screenshot (`s`, saved to Pictures/TMPlayer), A-B repeat, chapter
  navigation, and the `?` help sheet updated. No desktop frame-rate matching.
**STOP**

### CP09 Empty and error states with actions, recent searches
**Design:** R5. **Do:**
- Through `StateScaffold` and `UiState`: "N videos hidden by the size limits [Show them]", "Telegram is slow to
  answer [Retry]", and a first-load tip.
- Recent searches: the last 5 as chips, on every platform, stored in `SettingsStore`, with a clear action.
**STOP**

### CP10 Remove after watching, free space, TV focus and labels
**Design:** R5. **Do:**
- Downloads: a "Remove after watching" toggle (deletes a download when it is marked watched), and "x GB free"
  on the download action.
- TV: 1.05 focus scale on tiles, check the 48/27 dp overscan margins, content descriptions for the actionable
  icons among the 54 null-labelled ones (decorative ones stay null).
**STOP**

### CP10a Sidebar: version beside the logo, compact accordion groups
**Why:** the user asked on 2026-10-06 for the app version next to the icon in the sidebar, and for a more
compact sidebar where related entries fold into groups like an accordion.
**Where:** TV rail `NavRail`, `RailHeading`, `RailItem` in `app/.../ui/browse/BrowseScreen.kt`; phone drawer
`DrawerBrand`, `DrawerDestinations`, `DrawerSeparator`, `DrawerFooter` in `app/.../ui/browse/TouchNav.kt`;
desktop `Sidebar` and `Rail` in `desktop/.../ui/Shell.kt`; the tab list `BrowseTab` in
`ui/src/shared/kotlin/com/tmplayer/ui/browse/BrowseSections.kt`.
**Do:**
- Version: the brand row shows the logo, "TMPlayer" and the version (`BuildConfig.VERSION_NAME` on Android,
  the desktop app version) in a smaller muted style. When an update is available, the existing amber "Update"
  entry stays where it is; the version text does not turn into a button.
- Groups (proposal, show the user a screenshot before polishing): **Watch** (Continue, Watched, Favourites,
  Downloads), **Chats** (Recent, Unread, Saved, Channels, Groups, People, All chats, Archived), **Folders** (the
  account's Telegram folders, only when there are any), then Settings and Update pinned at the bottom.
  Define the grouping once in `:ui` shared code, next to `BrowseTab`, so all three platforms use the same model.
- Accordion: each group heading folds and unfolds with a chevron; the group holding the current destination
  is always open; open or closed state is remembered in `SettingsStore`; Watch starts open, the rest closed.
- Tighter rows: smaller vertical padding and a smaller icon to text gap, keeping TV targets at least 48 dp
  high and text at least 14 sp (v1 design §4, `docs/design/2026-08-07-v1-design.md`).
- TV: a group heading is focusable; OK toggles it; D-pad down from an open heading enters the group; the
  collapsed rail (icons only) shows group icons and expands on focus as today. No focus traps.
- Phone drawer: the same groups; the account footer stays.
- Desktop: the same groups; the collapsed icon rail keeps working.
**Done when:** screenshots of the rail on the TV emulator (collapsed and expanded), the phone drawer, and the
desktop sidebar in light and dark, with one group closed; D-pad walk on TV reaches every entry.
**STOP**

### CP10b One theme on every platform (Android TV looks different)
**Why:** the user asked on 2026-10-06: the Android TV build does not look like phone and desktop. Modals and
sheets lack the borders the other platforms have, and surfaces, corners and accents drift.
**Where:** `app/.../ui/theme/Theme.kt`, the TV components in `app/.../ui/components/` (`TvMenu.kt`,
`TvConfirm.kt`, `TmButton.kt`, `TvSearchField.kt`, `StateScaffold.kt`), the desktop theme in `desktop/`, and
shared code in `ui/src/shared`.
**Do:**
- First an audit: screenshots of the same screens (browse grid, a modal, a menu, a confirm dialog, Settings,
  player menu, sign-in) on the TV emulator, the POCO and desktop, light and dark, side by side. List every
  difference: colours, surface tones, border width and colour, corner radius, elevation, typography, focus
  and pressed states. Show the user the list before changing anything.
- Pull the shared values (colours, border, radius, spacing) into one token set in `:ui` shared code, so the
  phone `MaterialTheme`, the TV `androidx.tv.material3` theme and desktop all read from it.
- Modals, dialogs, menus and sheets on TV get the same border and radius as phone and desktop. The TV focus
  ring stays (it is how the D-pad shows where you are), but it is drawn from the same tokens.
**Done when:** the audit screens again on all three, light and dark, with no unexplained differences.
**STOP**

### CP10c Support the project: sponsor button, QR code and a gentle reminder
**Why:** the user asked on 2026-10-06: the apps have no way to sponsor. GitHub Sponsors
(`github.com/sponsors/dracu-lah`) and Buy Me a Coffee (`buymeacoffee.com/nevil.dev`) are only in the README
and `.github/FUNDING.yml`.
**Do:**
- A "Support TMPlayer" entry in Settings and in the About screen from CP05 on phone, TV and desktop. Phone and
  desktop open the links. TV shows a QR code for each instead of opening a browser (reuse
  `ui/src/shared/kotlin/com/tmplayer/ui/auth/QrCode.kt`). The QR is useful on phone and desktop too, as a
  "scan on another device" option.
- A friendly reminder, shown rarely: a small dismissible card (not a blocking dialog, never during playback)
  after real use, for example 14 days since install and 10 plays, then not again for 60 days. "Not now" and
  "Don't ask again" both work. Counters and the opt-out live in `SettingsStore`.
- The site: a support section on the home page and the download page with the same two links.
- Keep CP35 in mind: the future `play` flavor drops donation links, so put the entry and the reminder behind a
  single switch that flavor can turn off.
**Done when:** screenshots of the entry and the QR on TV, phone and desktop, and the reminder forced on with a
debug flag.
**STOP**

### CP10d TV player: drop the redundant centre buttons
**Why:** the user asked on 2026-10-06 whether the TV player's play, back 5 s and forward 10 s buttons are
needed, since the remote already does all three, and said to remove them if they are not.
**Where:** `app/src/main/res/layout/player_controls.xml` (`controls_center`: `center_previous`,
`center_rewind`, `center_play_pause`, `center_forward`, `center_next`; and the bottom row `controls_buttons`)
and `PlayerControls.kt`. Desktop's layout for comparison: `desktop/.../player/PlayerScreen.kt`.
**Do:**
- TV has no touch gestures, but the D-pad already does the job: left and right on the bare picture jump
  back 5 s and forward 10 s, OK opens the row, OK on the focused timebar plays and pauses, and the remote's
  play key works too. So on TV, hide the big centre cluster. Keep it on the phone, where it is the tap target.
- The user then said (2026-10-06) that on TV the remote plus the feedback icons are enough: no transport
  buttons at all. So on TV also drop play and pause, back 5 s and forward 10 s from the bottom row. What stays:
  the timebar and the non transport buttons (tracks, subtitles, speed and the rest of the menu).
- Make sure every remote action shows its icon: the existing side HUD for the 5 s and 10 s jumps, and a brief
  centre play or pause glyph when OK or the play key toggles playback. Paused keeps showing a pause cue.
- Previous and next episode: the remote's media keys, plus entries in the player menu if they are not there.
- Check D-pad focus: OK still opens the row on the timebar, Down reaches the buttons, no dead focus spots.
  Update INSTALL.md's remote table if it lists the removed buttons.
**Done when:** TV emulator screenshots of the row while playing and paused, the play and pause cue, the jump
HUD, and a D-pad walk across the row.
**STOP**

### CP11 Release prep and device verification
**Do:**
- Run the whole Phase A and B set on the POCO, the TV emulator and desktop. Fix what turns up.
- `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, the draft tag message (highlights in plain
  English), and the version bump, all committed but not tagged.
- If CP14 is done, check the signing step's dry path.
- Report the proposed version number and wait.
**STOP**

### CP12 Release "Player" and post-release checks
**Needs:** the user saying "release" in that session, and the cadence guard passing (2026-10-13 or later).
**Do:** tag with the annotated message, push, watch the run. Then, each only with the user's OK: upload
`mediamp-corresponding-source-0.5.0.tar.gz` (built by `scripts/build-mediamp-source.sh`) to that release, then set `TAG`
in release.yml's mediamp step to that tag so later releases link there; check the Telegram post, `site/latest.json` and the
changelog commit; `gh attestation verify` one file; bump the AUR PKGBUILD (from this release on its
`package()` also installs the notices, which older tarballs lack).
**STOP**

### CP13 winget manifests for the new release
**Do:** in `docs/distribution/winget/`: `ManifestVersion` and the three schema headers to 1.12.0; add
`PrivacyUrl: https://tmplayer.org/privacy/`; point the URL, `InstallerSha256` and `ProductCode` at the new
release's MSI (download it and hash it; the 1.22.0 hash in `DISTRIBUTION_PLANS.md` is stale); fix `winget.md`,
which says `wingetcreate new` takes `--submit`. Then U13.
**STOP**

### CP14 Windows signing in release.yml
**Needs:** U09 and either U10 (SignPath accepted) or U11 (Certum issued), plus U12.
**Do:** in the Windows job: build the app image, sign `TMPlayer.exe` with an RFC 3161 timestamp, build the MSI
from that image, sign the MSI, `signtool verify /pa` both. The release stops if the secrets are missing, rather
than shipping unsigned. Signing happens before the attestation and the feed SHA-256. SignPath route: their
GitHub Action plus the manual approval in SignPath. Certum route: SimplySign with the TOTP seed from a secret.
Update `/security/` and `/signing/`, which say the installer is unsigned. The DLLs inside JARs stay unsigned.
**STOP**

### CP15 Android verification token in the APK
**Needs:** U08's token file. **Do:** put it in `app/src/main/assets/` exactly as given, build a signed release
APK through CI (a `workflow_dispatch` build or the next release), tell the user which file to upload.
**STOP**

### CP16 winget auto-update job
**Needs:** the first winget PR merged and U14. **Do:** a job after `publish` on `windows-latest` that runs
`wingetcreate update dracu-lah.TMPlayer --version <v> --urls <msi url> --submit`, reading the token from the
`WINGET_CREATE_GITHUB_TOKEN` env var (never on the command line), `continue-on-error: true`, skipped when the
secret is unset.
**STOP**

### CP17 F-Droid MR: get the pipeline green
**State:** https://gitlab.com/fdroid/fdroiddata/-/merge_requests/51285, opened 2026-10-05, no reviewer
comments yet. Pipeline 2913691203 (commit 88842c2d) failed in 5 jobs: `fdroid build` (Gradle BUILD FAILED),
`checkupdates` ("Couldn't find any version information": the recipe needs an `UpdateCheckData` or a parsable
versionName, since ours is computed in `build.gradle.kts`), `schema validation`, `fdroid lint` and
`fdroid rewritemeta` (the recipe's format). Read the jobs with
`glab api projects/dracu-lah%2Ffdroiddata/jobs/<id>/trace`. Recipe on branch `add-com-tmplayer` of `dracu-lah/fdroiddata`, `metadata/com.tmplayer.yml`. Known
problems: `tdl-coroutines 15.0.0` needs compileSdk 37 (`platforms;android-37.0`); installing it under `sudo:`
leaves root-only files, so it moved to `prebuild: sdkmanager "platforms;android-37.0"`; with `subdir: app`
Gradle does not read the root `gradle.properties`, so `android.suppressUnsupportedCompileSdk=37.0` goes in
`gradleprops`.
**Do:** read the failed job log with `glab`, fix the recipe, push to the MR branch (pushing to our own fork is
fine, commenting on the MR needs the user's OK). Expect reviewer questions on prebuilt native libraries
(TDLib from tdl-coroutines, nextlib FFmpeg) and Sentry (Tracking anti-feature even when opt-in). Report those
honestly rather than hiding them. If the prebuilt TDLib is a hard no for F-Droid, say so and recommend closing.
**Result (2026-10-09):** fork pipeline 2930107122 (commit 1bc08d44 on `add-com-tmplayer`) is green in all nine
jobs, `fdroid build` and `check apk` included. What fixed it:
- The real build error: AGP installed build-tools 35 in the middle of the build, and an SDK install mid-run
  leaves that run unable to resolve `android-37` (reproduced locally with F-Droid's own `sdkmanager` from PyPI).
  `prebuild: sdkmanager "platforms;android-37.0" "build-tools;35.0.0"` puts both in place first. No sudo, no
  copies, no hand-written `package.xml`, no `gradleprops` (Gradle does read the root `gradle.properties`).
- `output: build/outputs/apk/release/app-universal-release-unsigned.apk`, since the release build makes
  three APKs (two ABIs plus universal) with one versionCode.
- `UpdateCheckData: app/build.gradle.kts|Code.*\?:\s*(\d+)|.|Name.*\?:\s*"([\d.]+)"` with
  `UpdateCheckMode: Tags ^v[0-9.]+$` and `AutoUpdateMode: Version` (the old `Version v%v` fails the schema).
- Category `Video` does not exist; now `Multimedia` and `Online Media Player`.
- Recipe moved to 1.24.0, `commit` is the full hash `4341b1a7...` as the template asks.

**Still open, for the user:**
- The MR itself is **closed**: linsui closed it on 2026-10-06, "Merge Request template is not followed". The
  pipeline above ran on the fork branch, not on the MR. Reopening it (or a new MR) needs the "App inclusion"
  template filled in as the description: what the app does with screenshots, why it is needed, and the
  checklist ticked. Nothing was posted or reopened.
- Reviewer decisions the green pipeline does not settle: the APK ships prebuilt `libtdjsonjava.so` (TDLib from the
  tdl-coroutines AAR) and prebuilt FFmpeg (`libavcodec`, `libavutil`, `libswresample`, `libswscale`,
  `libmedia3ext` from nextlib). The scanner does not look inside Maven AARs, but F-Droid's inclusion policy
  wants native code built from source, so expect a request to build TDLib and FFmpeg from source (srclibs) or a
  refusal. Sentry is opt-in, which may still draw a `Tracking` anti-feature. Reproducible builds are not set up
  (F-Droid would sign with its own key), and the template asks for a reason if not.
- **The F-Droid build switch (2026-10-09):** `-PtmFdroid=true` turns off the GitHub update check and its UI
  (`BuildConfig.UPDATE_CHECK`, read into `Updates.enabled`) and removes `REQUEST_INSTALL_PACKAGES` from the
  release manifest (overlay `app/src/fdroid/AndroidManifest.xml`). Once the first release that contains it
  ships, the recipe's build entry for that version must pass it through `gradleprops`:
  `gradleprops: [tmFdroid=true]`. Recipes for 1.24.0 and earlier must not, since those tags do not have it.
  Since CP73 the switch also leaves Google's Cast framework out (no dependency, `app/src/nocast` in place of
  `app/src/cast`, the options provider taken out of the manifest, no Cast button). The scanner still reads
  `com.google.android.gms` in `gradle/libs.versions.toml`, so the recipe deletes that line in `prebuild`
  (`sed -i '/play-services-cast/d' gradle/libs.versions.toml`); `app/build.gradle.kts` looks the library
  up by name, so the script still compiles without it.
- Telegram sign in needs `TG_API_ID` and `TG_API_HASH`, read from `local.properties` only (no environment
  fallback, unlike the Sentry, OpenSubtitles and TMDB keys). F-Droid has no secrets, so a build without them
  compiles but every sign in stops at "no API credentials". The recipe would have to write them into
  `local.properties` in `prebuild`, which makes them public in fdroiddata. The other keys degrade quietly:
  no DSN means no crash reporting switch, no OpenSubtitles key hides online subtitles, no TMDB key falls back to
  TVmaze and AniList.
**STOP**

### CP18 Obtainium badge
**Do:** download the official badge from Obtainium's repo, trim its transparent edge losslessly, host it under
`site/`, add it to the README and the download page at 161×48.
**STOP**

### CP19 P1 i18n infrastructure
**Design:** R1. **Do:** catalog `core/src/commonMain/resources/i18n/en.json`; Gradle codegen to `object L`;
`Translator` with per-key English fallback, the ICU subset, CLDR plurals for en, es, pt, hi, ml, the locale
formatter, track-language names; resolution order; `LocalStrings` in `ui/src/shared`; a debug-only `en-XA`
pseudo-locale. Tests: resolution, plurals, placeholders, fallback, catalog consistency (keys, placeholders,
ICU validity, no dashes).
**Done when:** build green, tests pass, nothing visible changes.
**STOP**

### CP20 P2 shared onboarding and the desktop promo fixture
**Design:** R2. **Do:** move the pager and page model from `app/.../ui/onboarding/OverviewScreen.kt` and
`app/.../ui/auth/IntroScreen.kt` into `ui/src/shared/kotlin/.../onboarding/`; pages Language, What TMPlayer is,
Sign in, Chats, Videos; wording per form factor; `OnboardingImage` actuals; gating via `overviewSeen`; desktop
`Shell.kt` shows the tour before `SignInScreen`; Settings "Show the walkthrough again"; a desktop promo fixture
that captures light and dark shots of sign-in, chats, grid and player. Those shots also become the missing
desktop screenshots in the README and on the site (WebP q88).
**Done when:** tour screenshots on phone, TV and desktop.
**STOP**

### CP21 P3 extract phone and TV text
**Design:** R9. Over `:app` and `:ui`: notifications and channels, `TrackPickerFragment`, accessibility labels,
the formatter switch. **Done when:** English unchanged, `en-XA` shows no leftovers. If it runs long, split by
module and stop at the split.
**STOP**

### CP22 P4 extract desktop and core text
`:desktop` (tray, Windows notifications and SMTC, update popup, keyboard help) and `:core` user-facing errors, plus the :core labels the phone and TV still show in English (ThemeChoice, PlaybackSpeed, SleepTimer, SyncDelays, SubtitleStyle, TouchPrefs, DiskInfo.freeLabel, WatchedWhen, ChatKind, UpdateWords);
`--self-test` loads every catalog.
**STOP**

### CP23 P5 language setting, what's new, feedback, localized metadata
**Design:** R1, R3. **Do:** the Settings language row everywhere, Android `locales_config.xml` and
`LocaleManager`, the auto-switch and the one-time "Now in Español" card; what's new after an update
(`lastSeenVersion`, translatable highlights, Settings "What's new" and "Changelog"); "Report a problem"
(prefilled GitHub issue URL, QR on TV, mailto fallback); localize the About from CP05; Linux `.desktop`
`Name[xx]`, AppStream, PWA manifest; a pseudo-locale overflow pass; Noto Devanagari and Malayalam on desktop
(with a notices entry if fonts are bundled). Optional: the one-time "Star TMPlayer on GitHub" prompt after
10 plays.
**STOP**

### CP24 P6 translations
**Go-ahead given 2026-10-06:** the user said to translate into the most common languages with langsync now and
review while using the app. **Do:** empty `{}` files for es-419, pt-BR, fr, de, it, ru, uk, tr, id, vi, ar, zh-CN, ja, ko, hi, ml
(check how langsync maps `zh-CN` and `es-419` before the run); `langsync --dry-run`, then `langsync`; a review pass (Spanish "tú", leftovers); CONTRIBUTING "Add a translation"
and an issue template; a review request on issue #2. Every language stays visible in the picker, reviewed or not (user, 2026-10-06).
**STOP**

### CP25 P7 verification and the "Languages" release
Device matrix in en, es and en-XA: onboarding, Settings language change without restart, `es-CL` system locale
auto-switch and card, player menus with the D-pad, desktop with `LANG=es_CL.UTF-8`. Fix what turns up. Release
only on request, after the cadence guard.
**STOP**

### CP26 P14b site localization and the remaining site pages
**Design:** R8. `data-i18n` on the pages and `app.js`; `scripts/build-site-i18n.py` renders `site/<lang>/` with
`lang`, `hreflang` and `x-default`, `og:locale`, sitemap entries, a header language picker, a suggestion bar, no
auto-redirect; CI fails on stale output. Also `/translate/`, the three SEO guides, "Now in Español, Português,
हिन्दी, മലയാളം" on the home page. `langsync -c langsync.site.json` for the same 16 languages (go-ahead given 2026-10-06).
**Done when:** Chromium shots of `/`, `/es/`, `/download/`, `/fire-tv/` at phone and desktop widths; hreflang,
sitemap and JSON-LD validate.
**STOP**

### CP27 P9 series view
**Design:** R5. Extend `MediaName.kt` with `1x02`, `Ep 02`, the anime `- 02` form and the caption as a second
source, with unit tests from real names; group by series key into one tile; season tabs and an episode list
with progress and watched ticks; phone bottom sheet, desktop dropdown; "Series" and "All files" toggle.
**STOP**

### CP28 P10 Home rows
**Design:** R5. A new first destination on every device: Continue, one row per favourite chat, "Recently added
across chats"; lazy rows of 10; rail and drawer keep their tabs. Smooth on a 1 GB emulator profile.
**STOP**

### CP29 P10 detail panel and search across all chats
**Design:** R5. Detail panel on OK-hold, the info key, right-click or long press (side pane on TV and desktop,
sheet on phone). Search: a "Videos in all chats" scope through TDLib `searchMessages` with a video filter,
results split into Chats and Videos.
**STOP**

### CP30 P11 Fire TV, trickplay, Watch Next, then the "Browse" release
**Design:** R4, R6. Fire OS detection for remote quirks and the voice-search fallback; trickplay from the
already-downloaded range only, behind a setting, off on 1 GB devices; optional Watch Next behind a setting.
Amazon listing pack (text, screenshots, content rating) in `docs/distribution/`. Release on request.
**STOP**

### CP31 P12 OpenSubtitles
**Design:** R7. **Needs:** U24.
**STOP**

### CP32 P13 TMDB, TVmaze, AniList, then the "Online" release
**Design:** R7. Includes the detail panel poster and the attribution in About. Release on request.
**STOP**

### CP34 Store flavor, targetSdk 36, AAB
**Needs:** U03 = pay, and the user confirming Play. Flavors `github` (as today) and `play` (no install
permission, no update check, no GitHub release links, no donation links). targetSdk 36: Android 16 stops sending
Back to apps that target it, so the player's Back handling moves to the predictive back API and gets tested.
release.yml also builds an `.aab`. Version codes stay shared with GitHub builds.
2026-10-19: Play's floor is API 36 since 2026-08-31 (extension to 2026-11-01 on request). Build the `play`
flavor on the F-Droid switch (`UPDATE_CHECK` off, the manifest overlay without `REQUEST_INSTALL_PACKAGES`); an
`amazon` variant is the same without Cast (no Google services on Fire TV), the file for U23. Never set TDLib's
`ignore_platform_restrictions` in either. Play also needs the dataSync and mediaPlayback foreground service
declarations (U27).
**STOP**

### CP35 Reviewer demo mode and Play policy items
A demo mode in the `play` build with a few openly licensed clips (Big Buck Bunny) so a reviewer can use it
without a Telegram code; sponsored links on TV as a QR code (TV ads must not link out); a TV banner showing the
name "TMPlayer"; a "Report" action for chats; sensitive media hidden by default.
**STOP**

### CP36 Play listing images
Outside the repo (Play takes PNG): icon 512×512 full square; feature graphic 1024×500; 2 to 8 phone shots
cropped to 1080×2160; TV shots 1920×1080; TV banner 1280×720 with the name. Only own or openly licensed video
on screen; no "movies" or "series" wording, no "S02E04" example.
**STOP**

### CP37 Android player declutter
**Design:** sections 2 to 4 of `docs/design/2026-10-06-player-declutter-and-support-asks-plan.md`, in git history (decisions D1, D2
default to the recommendations unless you say otherwise). Phone first, then TV.
**Do:** remove phone centre seek buttons, row Lock, PiP and Scale (Scale and Lock and PiP into the overflow),
overflow Speed and Start over; route `gesture_hud` through `PlayerFeedback`; one renderer for the subtitle Look
controls; stale XML comment and `controls_clock` default; TV row down to subtitles, audio, speed, More, with
Scale and PiP in More and no row episode buttons; drop dead TV Lock and Rotate code; first-run hints for double
tap and TV remote keys; update `INSTALL.md` and `docs/FEATURES.md`.
**Done when:** phone and TV emulator (promo flavor) screenshots of the new rows and menus, a D-pad walk with no dead
focus, tests green; real gestures and pinch listed as pending for CP11.
**STOP**

### CP38 Desktop player declutter
**Design:** section 5 of the same document (D3, D4). Runs in parallel with CP37 (disjoint files).
**Do:** remove 10 s buttons, wall clock and the Shape button; the speed pill opens the list; main menu reduced to
Always on top, Playback options, Subtitle style and timing, Telegram items, Help; group and trim the shortcut
sheet; one next-episode prompt instead of card plus countdown sheet.
**Done when:** desktop render test screenshots of the bar, main menu and each new page.
**STOP**

### CP39 Support asks v2
**Design:** section 6 of the same document (D5, D6). Replaces the flat 14 day, 10 play rule of CP10c.
**Do:** completed-watch and watch-time counters in `SettingsStore`; the three-rung ladder in `SupportReminder`
with "Later" and "I already support"; trigger on player close after a finished watch or a finished download,
never in the player; thank-you About state; `tmplayer.org/support/?utm_source=` page and QR targets; debug flag per
rung; one closing support line in the Telegram release post; tests.
**Done when:** unit tests for the ladder, screenshots of each rung and the thank-you state on phone, TV and
desktop, and the site page at 390 and 1440 wide in headless Chromium.
**STOP**

### CP40 Episodes button and modal in the player
**Why:** the user asked for an Episodes button in the empty bottom right of the controls row, opening an episodes
modal with everything about switching episodes (2026-10-06; the original request was never written down, so the
exact feature list is still open, see below).
**Where:** Android `PlayerControls.kt`, `player_controls.xml`, `PlayerActivity.kt` (`findEpisodes`, `playEpisode`,
`stepEpisode`); desktop `PlayerOverlay.kt`. Reuse `SeasonsSection`, `EpisodeRow` and the `SeriesSheet` code from
browse (`ui/src/shared/.../browse/`) so the modal matches the series view.
**Do:**
- An "Episodes" button at the right end of the row (phone, TV) and of the bar (desktop), visible only when the
  file belongs to a series.
- The modal: season tabs, the current episode highlighted and scrolled into view, per row the title, watched
  tick or progress and length. Previous and next at the top. Tapping a row plays it through the same loader,
  resume and watch cache path as the next-episode button.
- TV: D-pad focus starts on the current episode, Back closes the modal first, no dead focus. Desktop: keyboard
  navigable, Esc closes.
- Once it exists, remove the TV More-menu previous and next lines and the TV row episode buttons (CP37 leaves
  them until then). Phone centre previous and next stay.
- Extras (user, 2026-10-09: all four): an autoplay next toggle in the modal (the existing autoplay setting,
  shown here too); mark watched or unwatched from a row (long press on phone, OK hold on TV, right click on
  desktop); a next-up order choice, episode number or upload order in the chat, remembered per series; and Skip
  intro. Telegram files carry no intro markers, so Skip intro is learned: a "Set intro end here" action in the
  modal stores the position per series, and later episodes of that series show a "Skip intro" pill until that
  point passes (Enter or OK on TV, tap on phone, click on desktop). Clearing it lives in the same place.
- Removed 2026-10-09: Skip intro, because marking it per show by hand added work rather than saving it.
- Removed 2026-10-09: the next-up order choice, another per show chore; series are found by episode number,
  so that order is always the right one.
**Done when:** emulator screenshots of the button and modal on phone and TV (current episode highlighted, a
season switch, a watched tick), the desktop render test, a D-pad walk, and tests green.
**STOP**

### CP41 Hide Telegram's default chat groups, and prompts where they are missing
**Order:** after CP43, so it hides the shorter Chats group (see CP43).
**Why:** the user asked on 2026-10-06 for a Settings option that hides the default Telegram grouping from the
sidebar and from other places, removes favourites that this affects, shows proper alert prompts for features
like it, and adds the same prompts where they are missing.
**Main goal (user, 2026-10-06):** the sidebar shows only the custom groups the viewer made (their Telegram
folders), and none of the app's built-in ones. Everything below serves that: nothing built-in is left over,
chats outside every folder are not listed anywhere except search, and the Watch group (the viewer's own
watching, not a Telegram grouping) stays. Open for the user: should Watch also be optional? Default: no.
**What "default grouping" means (confirmed 2026-10-06):** the user's friend wants the app's fixed groupings
disabled completely, keeping only the custom ones. Default: the Chats group of the sidebar built by
`browseSections` in `ui/src/shared/.../browse/BrowseSections.kt`. Since CP43 that group (`NavGroup.Chats`,
headed "Telegram" on screen) holds two entries, Chats (`BrowseTab.Chats`, with the `ChatFilter` chips All,
Unread, Channels, Groups, People, Archived and the `ChatSort` toggle over it) and Saved (`BrowseTab.Saved`,
headed Saved Messages). Custom: the account's own Telegram folders (the Folders group), which stay. Watch (Home,
History with its Continue and Watched tabs, Favourites, Downloads) stays too. Both default entries go together,
with no per-entry choice. With no folders on the account the sidebar would hold Watch only, so the toggle's
prompt says to make folders in Telegram first, and Home and the empty states point there instead of at Chats.
**Do:**
- A Settings toggle "Hide Telegram's default groups" (name to settle), off by default, stored in `SettingsStore`.
  When on, `browseSections` and `navGroups` leave the Chats group out on phone drawer, TV rail and desktop side
  bar, and every other surface that lists those sections stops offering them (the chip rows in
  `BrowseScreen` and the desktop `ChatsPage`, the Home empty states and hints, search scope, and the
  desktop dropdown). With the group gone, chats are reached through Folders, Favourites, search and Home.
- Favourites: chats are favourited by id (`favorite_chats`), so switching the option on must not leave a
  favourite the viewer can no longer reach. Work out which favourited chats would become unreachable (in no
  Telegram folder), list the count in the prompt and, on confirm, remove them from favourites in one write.
  Switching it off does not bring them back, and the prompt says so.
- Prompts: a confirm dialog before the switch takes effect, in the app's existing dialogs (`TmAlertDialog` on
  touch, `TvConfirm` with focus on Cancel on TV, the desktop equivalent), with a plain explanation, the
  favourites count, how to undo it, and a destructive Confirm label. Turning it off needs no prompt.
- Sweep for missing prompts: audit Settings and the app for actions that remove data or are hard to undo and
  have no confirm, for example sign out, clear cache or downloads, clear all favourites, mark all watched or
  reset the watched list, change storage location, turn off crash reports, remove a downloaded file, language
  change that restarts. Write the list into this section, add a prompt to each one that lacks it, reuse one
  shared confirm component per platform.
- Strings go into `en.json` only; leave other locales and never run langsync.
**Done when:** unit tests for the section filtering and the unreachable favourites calculation, emulator
screenshots on phone and TV of the toggle, the prompt with a favourites count and the shortened sidebar,
the desktop render test, and the sweep list with each added prompt shown once.
**Prompt sweep (2026-10-09, done ahead of the toggle):** one confirm per platform, each with focus on Cancel
where focus exists: `TvConfirm` (`app/.../ui/components/TvConfirm.kt`, Material's `TmAlertDialog` on touch, its
own panel on TV, `extra` slot for a tick box) and the desktop `ConfirmDialog` (`desktop/.../ui/Common.kt`, now
with the same `extra` slot and a `cancelLabel`). The toggle reuses these: the favourites count goes in `detail`
or `message`, Confirm is destructive by default. New strings sit in the `confirm.*` group, in all 21 catalogs.
Phone and TV share one list because they share the screens.
- Phone and TV, already had a prompt: sign out of Telegram (with the delete downloads tick box); clear cache;
  clear pictures and previews; clear everything except downloads; clear Continue watching, clear the watched
  list and clear favourites (from Settings and from the browse headers and overflow); delete one download,
  delete selected, delete all; delete a cached video; Remove from Downloads in the detail sheet; sign out of
  OpenSubtitles.
- Phone and TV, added: Remove from Continue watching on one video (its saved place cannot be got back); Clear
  online subtitles (a subtitle fetched again counts against the OpenSubtitles daily quota); Clear posters and
  details; turning on Remove after watching (off needs none). Both caches skip the prompt when they are empty.
- Phone and TV, left without a prompt on purpose: turning off crash reports (nothing is deleted, one press
  turns it back on); language change (switches live, no restart, picked back the same way); Forget the last
  chat (only the remembered chat id, refilled by opening a chat); clear recent searches (a few typed words);
  cancel or remove a running, paused or failed download (it starts again from the same menu); mark watched
  and unwatched, add and remove one favourite, unarchive (each undone by the same entry); Android has no
  storage location setting.
- TV, CP44 finding 6: a held Down on the Settings list now stops on About, so the remote no longer lands on
  "Sign out of Telegram" by reaching the end of the list; one fresh press still reaches it, and its prompt
  opens with Cancel focused.
- Desktop, already had a prompt: clear cache; clear everything except downloads; clear Continue watching;
  clear favourites; clear the watched list (page and Settings); delete selected and delete all on Downloads
  (and the Delete button on one row); change storage location (with the move plan).
  Sign out of Telegram had its own dialog without Cancel focus: moved onto `ConfirmDialog`.
- Desktop, added: sign out of OpenSubtitles, as phone and TV ask; Remove from Downloads in the detail pane
  and in a tile's right click menu; delete a file found in the downloads folder that is not on TMPlayer's
  list; delete a cached video on the Downloads page; Remove from Continue watching on one card; Clear online
  subtitles; Clear posters and details; turning on Remove after watching.
- Desktop, left on purpose: language (live), Forget the last chat, recent searches, cancel a running
  download, the watched marks; there is no crash report switch on the desktop.
- Checked: phone emulator, promo build, portrait and landscape, English and Arabic (right to left): the
  Remove after watching prompt from the real Downloads row, and the Remove from Continue watching and Clear
  online subtitles prompts through the fixture's new `--es overlay forget|online-cache` (a phone fixture can
  now turn sideways with `--es orient land`). Desktop: the sign out and Remove after watching prompts in the
  render test, light and dark. `:core:jvmTest`, `:ui:jvmTest` and `:desktop:test` green.
- Pending for CP44: every TV side of this sweep. The Mi TV stick answered "no route to host" and both TV
  emulators crashed on start (exit 139) on this host, so the TV prompts (same `TvConfirm`, Cancel focused) and
  the held Down stopping on About are not yet seen on a screen. Also the new prompts on the POCO.
**The toggle (2026-10-09):** named "Only my folders" (Settings, Library on phone and TV; the Library group
on desktop), `SettingsStore.hideDefaultGroups`, off by default. The rules live once in
`ui/src/shared/.../browse/DefaultGroups.kt`, beside `browseSections(folders, hideDefaultGroups)`:
- Hidden: Chats and Saved go together from the phone drawer, the TV rail and the desktop side bar, so the
  "Telegram" heading goes too; the Folders group is unfolded (`NavGroupState.unfold`), being the way in.
  The desktop's narrow rail names its Chats entry Folders (folder icon) and drops it when there are no
  folders; the narrow window's section chips list only the folders. A saved or requested section that
  names Chats or Saved lands on Home (`DefaultGroups.reachable`); the desktop Chats page moves to the
  first folder.
- Kept on purpose: the search. "Videos in all chats" and a chat name search still reach any chat, which
  is the one place a chat outside every folder still shows, as the main goal says. The scope chips name
  no section, so nothing there changed. Favourites keeps listing whatever is starred.
- Unreachable favourites: starred chats in no folder, and archived ones (a folder's list leaves them out);
  a starred chat the list does not hold is not counted. The prompt counts them and the confirm unstars
  them in the same DataStore write as the switch (`setHideDefaultGroups(true, unstar)`). Off brings
  nothing back, and the prompt says so.
- Prompt: `TvConfirm` on phone and TV (Cancel focused), `ConfirmDialog` on desktop; title "Show only your
  folders?", what leaves the sidebar, the favourites count when there are any, the no folders line
  (make folders in Telegram first) when there are none, how to undo it, destructive "Hide them". Off asks
  nothing and says "Chats and Saved Messages are back".
- Wording when hidden: first-run Home sends the viewer to the first folder ("Open Films") instead of Saved
  Messages, or with no folders says to make one or turn the option off, with no button; the chat list's
  first-run tip says forward to a chat in a folder; the clear favourites prompts say the chats stay in
  the folders. The other empty states name no hidden section and are unchanged.
- The chip over Chats (filter and order) is left in `SettingsStore` while hidden, so turning the option
  off comes back to the same chip.
- Tests: `DefaultGroupsTest` (sections and groups when hidden, fallback to Home, unreachable favourites,
  prompt words, first-run target, the one write, the kept chip, the first sign in card's rule and once
  per install). Strings in a new `groups.*` group, in all 21 catalogs.
**STOP**

### CP42 Minimal onboarding
**Why:** the user asked on 2026-10-06 to check what the onboarding lacks and whether pages can merge so a new user
sees fewer steps. Audit of the shared tour (`ui/src/shared/.../onboarding/Onboarding.kt`, `OnboardingTour.kt`,
`PostersSwitch.kt`, the phone and TV `OnboardingScreen.kt`, desktop `Shell.kt`).
**Today:** six tour pages on every platform (Language, About, Sign in, Chats, Videos, Posters), then the sign in
screens (phone number, code, optional 2FA; QR first on TV and desktop), then the shell. Skip is hidden until
after About (the Telegram API terms line lives there). Three of the six pages (Sign in, Chats, Videos) describe
screens the user is about to see. The Posters page is a notice rather than a choice, because
`PostersDefaultOn` turns online lookups on at tour start, so a skipped tour enables them without the user ever
seeing the explanation.
**Do:**
- Tour of two pages instead of six. Page 1 "Welcome": the three honest lines (unofficial, no server of ours,
  nothing collected), the Telegram API terms line, a compact language row ("English, change", system language
  preselected) instead of a whole page, and a visible "Look up posters and descriptions online" switch with
  one line on what is sent. This replaces the hidden default: the switch is on screen, so choosing is real.
  Page 2 "How it works": one illustrated page saying videos come from your own Telegram chats and channels, to
  forward a video to Saved Messages to get a first one in, folders and favourites in one line, how the sign in
  works (phone number or QR), then Start. The standalone Sign in, Chats and Videos pages go.
- Move the "Help translate" block off page 1 into Settings and About; the language card ("Now in X") stays as
  the second language prompt.
- One contextual card after the first sign in, not a tour page, because it needs the account: when the account
  has Telegram folders, ask "Show everything" or "Only my folders" (the CP41 setting), with the CP41 prompt
  rules. No folders: skip it. Depends on CP41.
  Built 2026-10-09 (`FirstSignIn` in :ui, `FirstSignInCard` on Android and desktop): a sign in screen arms
  it (`armFirstSignInCard`), so somebody signed in before it existed is never asked; it waits for the
  shell and the chat list, asks when the account has folders, and is skipped for good after a 3 s grace
  when there are none. "Show everything" (focused on TV, also Back) changes nothing; "Only my folders"
  opens the CP41 prompt with its favourites count. Asked once per install: the answer survives sign out.
- First-run empty state on the chat list and Home: say how to get a first video (forward to Saved Messages),
  with the existing strings made visible instead of buried in an empty state.
- Hints deferred to first use, shown once and dismissible: double tap to jump and the swipe gestures on the
  first phone playback, the OK, hold-for-favourite and remote keys on TV (shares work with CP37).
- Kept as they are, on purpose: notification permission on the first download, crash reports off by default and
  only in Settings, storage location only in Settings, the support card and the update, language and what's new
  cards held until the shell. No storage permission exists to ask for.
- Tour state: bump the "overview seen" flag so existing users are not shown the new tour, and keep the
  Telegram terms acknowledgement working for a fresh install that taps Start.
- Strings go into `en.json` only; leave other locales and never run langsync. The shorter tour makes the old
  keys unused, so remove them from `en.json` and note that translations of the new keys wait for the user.
**Done when:** emulator screenshots on phone and TV of both tour pages, the first sign in card (with and without
folders) and the first-run empty state, the desktop render test of the tour, a check that a skipped or finished
tour leaves posters exactly as the switch says, unit tests for the tour state, and the old `Onboarding` page
count updated in docs and the site's first-run description if it lists steps.
**STOP**

### CP43 Sidebar with fewer entries
**Why:** the user asked on 2026-10-06 whether sidebar sections can be grouped instead of hidden, so there are
fewer menu entries. CP10a already made the groups fold like an accordion; this goes further and removes
entries rather than only tucking them away. Run it before CP41, which then hides a smaller group.
**Today:** `browseSections` lists Home, Continue, Watched, Favourites, Recent, Unread, Saved, Channels, Groups,
People, All, Archived, then the folders, plus Downloads. Six of the eight Chats entries are only filters of the
same chat list (`filterChats` in `BrowseSections.kt`: Recent is a sort, Unread, Channels, Groups and People
filter by `unreadCount` or `ChatKind`).
**Do:**
- Chats group becomes two entries: "Chats" (one list) and "Saved messages" (kept apart because it is the
  video library most people use). Inside "Chats" a row of filter chips: All, Unread, Channels, Groups, People,
  with a sort toggle for Recent, and Archived as a last chip. The chosen chip is remembered in `SettingsStore`.
- Watch group: Continue and Watched become one "History" entry with two tabs (Continue, Watched), since both are
  lists of videos you already played. Home, Favourites and Downloads stay. Net: eleven fixed entries plus
  folders become six.
- Keep the shared model: define the entries and chips once in `:ui` shared code next to `BrowseTab`, so phone
  drawer, TV rail and desktop side bar stay identical. `BrowseSection`, saved screen state, deep links, empty
  states and hints that name a removed tab (All, Channels, Recent) are updated to point at the chip.
- TV: the chip row is D-pad reachable (Up from the list header, Left and Right between chips, OK selects),
  no dead focus, and it keeps the 48 dp target and 14 sp text floor.
- Folders group unchanged. CP41 then hides "Chats" and "Saved messages" (the default grouping) and leaves
  History, Watch and the folders; update its text if the entries differ when it starts.
- CP10a's accordion state and the drawer, rail and desktop layouts stay; remove unused headings only if a
  group ends up with one entry.
- Strings go into `en.json` only; leave other locales and never run langsync.
**Done when:** unit tests for the chip filters and the saved selection, screenshots of the shorter sidebar and
the chip row on phone and TV, the desktop render test, a D-pad walk, and the old tab names gone from docs.
**STOP**

### CP44 Device pass: layout, wasted space, nothing broken
**Why:** the user asked on 2026-10-06 to commit the changes, install them on the phone and on Android TV, and look
for extra spaces that can be used and anything that looks broken. Repeat after each batch of CP37 to CP43 lands;
it also takes the "pending for CP11" device checks those CPs list.
**Rules (from the standing rules and memory):** done from the main session, not a subagent (subagents are
refused installs on real devices). Release-signed APKs only, `adb install -r`, never the debug build on the
phone (same package, other key, wipes the Telegram login). The stick is armeabi-v7a on Android 9 and needs the
release-signed universal APK; it is the `MiTV-AESP0` at `10.27.27.33:5555` over adb. The phone is the POCO and is
shared with the user: ask or check it is connected, set media volume to 0 before any playback test and confirm it
with `cmd media_session volume --stream 3 --get`. Emulators first (`tmplayer_phone_api36`, `tmplayer_tv_api36`,
promo flavor); the devices are for real data, codecs, audio and storage. Verify the installed versionCode with
aapt2 (see the stale versionCode note) before trusting a test.
**Do:**
- Build the release APK(s) from the merged main, install on the phone and on the stick, keep the login.
- Walk every changed screen at 1:1 with screenshots: player controls row and overflow, TV row and More menu,
  support card rungs, sidebar and chips, hide-default-groups setting and its prompt, onboarding, episodes
  modal. Phone in portrait and landscape; TV on the stick's 1080p.
- Look for: unused gaps and padding, controls that are too small or crowded, text clipped or overlapping,
  content that does not use the freed room (for example a shorter sidebar leaving a dead strip, a shorter
  player row leaving an empty band), focus that gets lost on the D-pad, anything that looks broken, then fix
  what is found or list it.
- Record what was verified and what was not in the plan board line.
**First pass, 2026-10-06 (release build 1.23.0-test28, versionCode 12330, merged CP37, CP38, CP39; installed with
`adb install -r`, login kept; phone POCO M2 Pro `d3f7da23`, 1080x2400; stick Mi TV `10.27.27.33`, 1080p):**
- Checked and fine: phone Home, the detail page, the player in portrait and landscape (centre cluster is
  previous, play/pause, next; bottom row subtitles, audio, speed, rotate), the phone overflow, TV Home with the
  folded rail and the version beside the logo, TV detail page, the TV row (subtitles, audio, speed, More plus the
  clock), the TV More menu, TV Settings, About and help, and the TV Support dialog (QR decodes to
  `tmplayer.org/support/?utm_source=settings&utm_medium=app`).
- Findings, open:
  1. Phone landscape, controls up: the subtitle line runs through the timebar and the time labels. This is the
     existing `liftSubtitles` tradeoff (it never climbs into the centre buttons), so little room is left
     sideways. Options: hide the subtitles while the row is up in a short window, or give them a scrim. Not changed.
  2. Phone portrait: the previous and next captions (S01E03, S01E05) sit on the same line as the subtitles and
     collide with them when the controls are up. Same fix as 1.
  3. Phone landscape overflow: 11 entries scroll and cut off at "Load a subtitle file". Merge or drop entries
     (Copy link, Save, Mark watched, Open in another app) or split it into two groups.
  4. Empty right half of the controls row on phone and TV. This is the slot CP40 fills with Episodes.
  5. Home in landscape and on TV: a Continue row with one item fills a third of the width and leaves the rest
     empty. Fine with more items, but worth a wider card or a second row (hint plus Favourites) at this size.
  6. The "Sign out of Telegram" row on TV Settings is the default focus on arrival at the bottom of the list and
     has no visible confirm on a single OK; CP41's prompt sweep covers it.
**Fixes after the first pass (2026-10-06, user: fix all, defaults taken):** 1 and 2: the subtitle cue stands down
(fades) while the controls are up when it cannot climb clear of the row or would reach the centre buttons and
their episode captions; 3: the phone overflow drops Copy link and Picture shape (the pinch is the shortcut), 9
entries left; 4: a lone Continue card is 1.5 times wider, and the empty controls slot stays for CP40's Episodes
button; 5: already covered, Sign out opens a confirm with Cancel focused first, so only the landing focus remains,
which is ordinary D-pad scrolling. Not yet seen on a device.
- Not tested: real gestures and pinch, the resume offer pill and the first-run hint over a real resume (the
  first resume here showed the loader line "Resuming from 13:52", not the offer; check whether the offer shows
  once playback is ready), the TV rungs of the support card (they need the forced debug flag), phone
  thank-you state, Picture shape sheet, and the desktop pass. Media volume on the phone was set to 0 for the
  test and put back to 14.
**Done when:** screenshots of each changed screen on both devices were viewed, findings fixed or listed, and
the pending device checks of CP37 to CP43 are ticked or carried forward by name.
**STOP**

### Phase I notes (CP45 to CP62)
**Why:** on 2026-10-13 the user joined a forum supergroup whose topics all showed mixed together, then asked
for other Telegram edge cases, captions done in a readable way (study videos, not only films and series),
Cinema_paradasio's repeated uploads grouped, skip intro, and finally features taken from Nuvio and other
open-source players: "do all the ones."
**How every CP in this phase runs:** one feature per worktree agent is fine (they are file-disjoint enough),
merged in the main tree. English strings go into `core/src/commonMain/resources/i18n/en.json` only; after the
batch the main session translates them into all 20 locales directly (no langsync) and checks `CatalogTest`
(CLDR plurals: es-419, fr, it and pt-BR need `many`). Build and test with
`JAVA_HOME=~/.jdks/jdk-21.0.12+8 ./gradlew -q :core:jvmTest :ui:jvmTest :desktop:test :app:assemblePromo`,
install the promo APK on the POCO with `adb -s d3f7da23 install -r` (keeps the login), look at it, then commit
and push. No release unless asked.
**Test chats:** STRIVER TUF (forum topics, restricts saving), Cinema_paradasio (repeated films, Love Under
Construction and Kerala Crime Files).

### CP48 Speed per series, chat and topic
Today speed lives per file in `ResumeRecord`. Remember the speed the viewer picks for the series (same key as
`SettingsStore.trackChoice`), else for the chat, or chat plus topic when `MediaItem.topicId != 0`. Opening a
video: the file's own speed, then the series, then the chat or topic, then 1.0. Choosing 1.0 clears it. Make
sure the pickers offer 0.75, 1, 1.25, 1.5, 1.75 and 2 without redesigning them. Unit test the precedence.

### CP49 Preferred audio and subtitle language
Settings rows on every platform (TV through `ChoiceSheet`): preferred audio language (Automatic or a language),
preferred subtitle language (Automatic, Off or a language), and subtitles mode: off unless chosen, always when
available, or only when the audio is not in the viewer's language. Order: the file's saved tracks, the series
memory, these defaults, the file's own defaults. Android via Media3 `TrackSelectionParameters`, desktop via
mpv `alang`, `slang` or the existing track code. Normalise ISO 639-2 and 639-1 codes; names from
`java.util.Locale` in the UI language. Unit test the order and the normalisation.

### CP50 Watch later
A `WatchLaterStore` modelled on `WatchedStore`, keyed `chatId:messageId`, holding what a tile needs (title, file
name, size, duration, chat title, file id, date added). Add and remove in the hold menu and detail actions,
a Home row after Continue watching (hidden when empty) and a History tab; tiles open like Continue watching
does (`ChatRepository.mediaItem`). Finishing a video removes it. Sign out does what `WatchedStore` does.

### CP51 Download a season
"Download season" and "Download unwatched" beside Play on the series page (`Series.kt`, `SeriesScreens.kt`,
desktop `SeriesViews.kt`). Each episode uses the copy the list would play (`EpisodeCopies.closest`), skipping
ones already saved or queued and chats that restrict saving. Check free space first with the existing
messages (`some_fit`, `none_fit`) and reuse the multi-download feedback. Unit test the episode and copy choice.

### CP52 Course progress
A lecture series (`LessonSteps`) or a forum topic shows "12 of 40 watched" and a thin bar on its tile and at the
top of its list, from `WatchedStore` and resume records. Continue opens the first unfinished lesson.

### CP53 to CP61 (batch 2)
- **CP53 Backup and restore:** export settings, favourites, watched, watch later, bookmarks, resume points and
  per-series memory to one JSON file; import merges. No Telegram session or keys in it.
- **CP54 Updates feed:** for shows in progress, new episodes found in their chats since the last visit, a Home
  row and a dot on the show tile.
- **CP55 Bookmarks:** a position plus an optional note, listed in the player and on the detail page.
- **CP56 Android player extras:** chapters from the container, A-B loop and frame step, matching desktop.
- **CP57 TV locks:** a screensaver after idle on browse screens, a PIN for settings and sign out, a kids lock
  that limits browsing to chosen chats.
- **CP58 Skip silence and resume rewind:** Media3 silence skipping (desktop via an mpv filter if cheap), and
  resume a few seconds before the saved point (setting, default 3 s).
- **CP59 UI scale and log export:** a scale setting for phone, TV and desktop, and a "Save logs" action next to
  bug reports, scrubbed of chat names.
- **CP60 Episode thumbnails:** TDLib video thumbnails in the series episode list, lazy, no extra downloads.
- **CP61 (optional) Anime mapping:** ani.zip or the Fribb anime list to turn absolute numbers (One Piece 1080)
  into season and episode for metadata and skip times. Privacy page if a new service is called.

### CP63 UI refresh in the style of Nuvio
The user asked on 2026-10-13 for every screen to look as good as Nuvio's. A research agent writes the spec
(`docs/design/2026-10-13-nuvio-style-ui.md`) with work packages split by file; they run after batch 1 merges
and before batch 2, since batch 2 builds on the same screens. Patterns only, none of Nuvio's name or assets.

### CP62 AniList and Simkl sync
Needs U30. OAuth on each platform (TV through a QR or device code), push watched episodes and progress, pull
the lists for a Home row. Privacy page, sitemap lastmod and `llms.txt` updated.

### CP102 Crash reports that arrive and can be read
**Why:** the user, 2026-10-19: "is it optimal way to get more reports? am i missing out anything?" The
privacy design stays as it is (off on a fresh install, the manifest's `io.sentry.auto-init` off, the
beforeSend gate, `CrashReportScrubber`, no breadcrumbs, sessions or tracing). What is wrong is that few
reports arrive and the ones that do cannot be read. The account is Sentry's free Developer plan: one user,
5,000 errors a month, email alerts only, about 30 days of retention (check sentry.io/pricing; the privacy
page's "at most 90 days" stays true).

**Found 2026-10-19:**
- No release has uploaded its R8 mapping. `SENTRY_AUTH_TOKEN` is not a repository secret, and the 1.27.0
  run (39192922014) shows it empty, so `autoUploadProguardMapping` was false. The Nord 2 crash in Sentry
  (issue 154596076) reads `iw2 in f`, `SourceFile`, every frame "Not in app". The last is a symptom, not a
  setting: sentry-android-core adds the package name as an in-app include itself
  (`AndroidOptionsInitializer`, 8.61.0), and `iw2` does not match `com.tmplayer` until it is decoded.
- The mappings are kept only as workflow artifacts (`mapping-<version>`, 90 days, the most a public repo
  allows). 1.27.0's and 1.27.1's expire on 2027-01-17; after that a build's crashes can never be read.
- `CrashReports.review()` deletes a pending crash from another build. People who crash update next (1.27.1
  was the fix for 1.27.0's crash), so those reports are lost.
- The pending prompt is drawn inside MainActivity's shell. A crash before it is drawn (1.27.0's Home crash)
  repeats on every start, overwrites `crash/pending.json`, and is never asked about.
- With the switch off only Java and Kotlin crashes are written down. App not responding (ANR) and native
  crashes are never recorded; with it on, native crashes are still missed, since only core is included.
- The desktop has no crash reporting at all (`StartupFailure` only shows a launch error). No handled error is
  ever captured (no `captureException` anywhere).

**CP102a Mapping upload from CI and the backfill** (needs U31)
- With `SENTRY_AUTH_TOKEN` set, check on a test tag or a local `assembleRelease` that
  `uploadSentryProguardMappingsRelease` uploads (sentry-cli prints the UUID) and that Sentry's Project
  Settings, ProGuard lists it. If an organization token is refused for debug files, use the personal token
  from U31.
- release.yml: a `::notice::` when `SENTRY_AUTH_TOKEN` is unset, like the DSN's, so a release without a
  readable mapping says so.
- Backfill 1.27.0 and 1.27.1: `gh run download <run> -n mapping-<version>`, the UUID from the released APK
  (`unzip -p TMPlayer-<version>-universal.apk assets/sentry-debug-meta.properties`, key
  `io.sentry.ProguardUuids`), then `sentry-cli upload-proguard --uuid <uuid> mapping-<version>.txt`
  (`--org nevil-i0 --project <id>`). Then open issue 154596076 and check it reads as source.
- Keep every mapping past 90 days: ask the user where (an asset on a draft release that is never
  published, which only collaborators see, or a private repo). Uploading to a release needs their go-ahead.
- **Done when:** a release build's crash (a debug-only crash button in the promo or a test tag) shows class
  names and line numbers in Sentry.

**CP102b A crash from an older build is still sent**
- `CrashRecord` also keeps the build's ProGuard UUID (read from `assets/sentry-debug-meta.properties` at
  `watch()`), and `toEvent()` sets `release`, `dist` (the version code) and a `DebugMeta` with that UUID as
  a `proguard` image. Sentry keeps an image already on the event: `DebugMeta.isMatchingDebugImage` in
  8.61.0 treats an existing ProGuard image as taking precedence over the option's, so the running build's
  UUID is not added beside it.
- `review()` stops deleting other builds' crashes; it deletes one older than 30 days instead (the free
  plan's retention). A record without a UUID (written by 1.27.1 or older) is still deleted.
- The privacy page's "A crash from an older version of the app is deleted without asking" changes to match.
- **Done when:** a unit test writes a record as build A, reads it as build B and checks release, dist and
  the single proguard image; a promo crash in one build is sent from the next and reads as source.

**CP102c The crash prompt before Home builds**
- `CrashRecord` gains the time since the process started. A crash in the first ~15 s of a run, twice in a
  row, is a start-up loop.
- MainActivity checks `CrashReports.pending` before building the shell. With a pending start-up crash it
  shows only the crash question (phone and TV, focus on Send), then builds the shell after the answer. The
  ordinary prompt keeps its place for other crashes.
- With the switch on, a start-up crash is sent by `review()` before the shell builds and flushed
  (`Sentry.flush`), so the next start's crash cannot cut it off.
- **Done when:** a promo flag that throws in Home's first composition gives the question on the second
  start, on the phone and the TV emulator, and Send reaches Sentry.

**CP102d App not responding and native crashes**
- With the switch on: `options.isTombstoneEnabled = true` (in core since 8.32, Android 12 and newer, from
  `ApplicationExitInfo.REASON_CRASH_NATIVE`, no NDK module needed). ANR v2 is on by default. Leave
  `attachAnrThreadDump` and `attachRawTombstone` off (attachments count against the plan, and a raw dump
  is more than the privacy page lists). TDLib's and FFmpeg's prebuilt libraries have no symbols, so frames
  show the library and offset; that is enough to tell which one.
- With the switch off (Android 11 and newer): at start, read `getHistoricalProcessExitReasons` for an ANR
  or native crash newer than the last one asked about, and offer it in the same prompt ("TMPlayer stopped
  responding" or "TMPlayer closed unexpectedly"). Send brings the SDK up for that report, as
  `sendPending` does, and the ANR and tombstone integrations send the latest exit. Don't send stores its
  timestamp, and beforeSend drops a `HistoricalAppExitInfo` or tombstone event at or before it, so turning
  the switch on later never sends what was declined.
- The scrubber covers the new events: registers already go, thread names stay, the abort message is
  redacted, debug images are kept (they are what decodes a native frame). The privacy page lists ANRs and
  native crashes.
- **Done when:** on the TV emulator (Android 12 and newer) a debug-only ANR (the main thread asleep for 10 s
  while a key is pressed) and a debug-only native crash arrive in Sentry with the switch on, and are offered,
  sent and declined correctly with it off.

**CP102e Stay inside the free plan**
- In beforeSend, after the scrubber: the same crash (exception type plus the top in-app frame) at most once
  a day per device, and no more than 10 events a day per device, counted in a small private file. A crash
  loop on many phones must not use the month's 5,000 in a day.
- Sentry's own spike protection (U31) is the second line.
- **Done when:** a unit test sends the same event twice and a burst of twelve, and checks what is dropped.

**CP102f The question in the first run** (with CP100)
- A "Send crash reports" row in CP100's Preferences screen, off, with one line saying what a report holds and
  a link to the privacy page. Not preselected, no nudge (consent). Settings and the per-crash prompt stay.
- Strings in all 21 languages; the privacy page says where it is asked.
- **Done when:** render shots of the row (phone, TV, desktop when CP102g lands), and turning it on starts the
  SDK.

**CP102g Crash reports on the desktop**
- `io.sentry:sentry` (plain JVM, no Compose integration) on Windows, Linux and macOS, the same opt-in switch
  in Settings, the same pending file and prompt, the same scrubber (moved where both apps can use it; it only
  needs `io.sentry.protocol`). A separate Sentry project, `tmplayer-desktop`, sharing the plan's quota.
- The desktop is shrunk but not obfuscated (`obfuscate.set(false)`), so no mapping is needed.
- `StartupFailure` keeps showing the launch error; the report is sent first when the switch is on.
- The privacy page drops "It exists on Android phones and Android TV only".
- **Done when:** a dev-only crash on the AppImage and in the win11 VM arrives with the switch on, and is
  offered with it off.

**CP102h (ask the user first) Chosen errors the app survives**
- A short list only, each as a `warning`, only with the switch on, through the scrubber and CP102e's limits:
  a player error that ends playback (the codec and container names, never the file), a failed cast LOAD, a
  TDLib error that ends sign in. The privacy page would have to say so, which is why it waits for a yes.

**STOP** after each part: build, unit tests, the dash grep, commit, tick, push. The device checks for
CP102b to CP102d can wait for one pass at the end. Nothing here needs a release of its own; it goes out with
the next one (not before 2026-10-26).

## Your tasks

### CP69 Full setup, Save mobile data and a quick tour after sign in (for 1.26.0)
**Why:** the user, 2026-10-14: the setup should walk a new user through what they need, step by step, every
step with marked screenshots; the performance work never reaches a capable device; Low storage mode also helps
people on mobile data, which deserves its own rule; every string in all 21 languages. Wanted in 1.26.0.
**Today:** CP67 setup, at most three choices: Library (only with folders), Posters, Storage (Low storage mode,
drawn picture, the user likes it as it is). The performance rows (Lighter pictures, Reduce motion, Thumbnails
while seeking) only appear as the Device step, on a device `DeviceFitRules` calls short. Nothing shows how to get
a first video, the player, the controls, downloads, favourites, the TV home screen row or where Settings keep
the rest. Streaming asks TDLib for the whole rest of the file (`limit = 0`), so on mobile data a film stopped
after 20 minutes can have cost most of its size.

**1. Save mobile data (new feature, Android)**
- Setting `save_mobile_data`, default off, carried in backups, kept across sign out like the other device
  settings. Row "Save mobile data" on Settings, Storage next to "Downloads on Wi-Fi only", phone only (the TV
  is treated as unmetered, as Wi-Fi only already is; the desktop has no metered state).
- While it is on and `NetworkMonitor.metered` is true, a stream (not a download) asks TDLib for a bounded window
  ahead of the playhead (about 100 MB, enough for minutes of 1080p) and extends it as playback nears its end,
  through the same request path Low storage mode uses, but nothing behind the playhead is deleted, so seeking
  back and watching again cost nothing extra. Switching to Wi-Fi mid-video lifts the bound at the next request.
  Low storage mode on top keeps its own rolling and deleting rules.
- Unit tests for the plan (bounded only while metered and on, extended near the end, never deletes, a download
  is never bounded); a device check on the phone over mobile data.

**2. Setup, part 1: choices** (one setting per step, two answers, pictures, recommended answer first and focused
on a remote; Back, Skip, Skip all as now; the progress line counts both parts)
1. Your library: unchanged (only with folders).
2. Posters and details: unchanged.
3. Storage: unchanged, drawn picture included.
4. Performance (new, every device): "Full quality" or "Lighter" (turns on Lighter pictures and Reduce motion
   and turns off Thumbnails while seeking; the card lists them). Pictures: marked shots of Home with full art
   and the same Home lighter. On a device short of memory "Lighter" comes first and is recommended. On a
   device the Device step (CP68) applies to, that step replaces Storage and Performance as today, so nothing is
   asked twice.
5. Mobile data (new, phone only): "Save mobile data" (recommended: Save mobile data on, downloads wait for
   Wi-Fi) or "No limits" (both off). Drawn picture in the Storage step's style: a timebar with a short
   stretch fetched ahead against one filled to the end.
- Not asked, on purpose: preferred languages (a list, not a two-answer question; the tour points to it),
  autoplay and Skip intro (on by default), PIN and kids lock (asking for a lock at first run feels like a
  demand), storage location, theme (follows the system), crash reports (asked after a crash now).

**3. Setup, part 2: quick tour** (after the choices; no answers; each page one marked screenshot with numbered
badges drawn over it by the app, and the same numbers in front of short lines beside it on TV and desktop, under
it on a phone; Next, Back, Skip all; the last page says Start)
1. Your videos: (1) Saved Messages, forward any video there; (2) chats and channels, every video in them is
   here; (3) Search. Shot: the chat list.
2. Home: (1) Continue watching; (2) Recently added in starred chats; (3) star a chat (hold OK on TV, long
   press on a phone, right click on the desktop). Shot: Home.
3. The player: (1) the timebar's lighter part is downloaded, seek anywhere; (2) subtitles, audio, speed;
   (3) Skip intro and Next episode when they apply. Shot: the player.
4. Controls, one per platform. Phone: double tap a side to jump, swipe right side for volume and left for
   brightness, tap to show or hide controls (shot: the player, sides marked). TV: OK plays and hold OK for
   options, Left opens the side menu, Back goes back and Left and Right jump in the player (shot: a focused
   card). Desktop: Space and the arrows, F and ?, right click (shot: the player).
5. Downloads: (1) download a video or a whole season; (2) the Downloads screen and its space; (3) Remove after
   watching. Shot: Downloads.
6. TV only, Home screen row: (1) TMPlayer's row of recently added videos; (2) add Watch later or New episodes
   from the home screen's channel settings. Shot: the launcher row (the site's tv-home-screen capture).
7. Settings: (1) Playback, incl. preferred audio and subtitle language; (2) Storage, incl. Low storage mode
   and Save mobile data; (3) Lock: app PIN and kids lock; (4) Appearance: theme, Lighter pictures. Shot:
   Settings' page list.

**4. Who sees what**
- A fresh sign in: part 1 then part 2. "Run the setup again" in Settings: both.
- Existing users updating to 1.26.0: never the choices; a one-time "Take the quick tour" offer (a card in the
  shell, Take the tour or Not now), held back like the other cards (update, language, what's new), then never
  again; flag `tour_offered`. Settings, Help gets "Quick tour" to open part 2 any time.

**5. Pictures**
- Demo content only (promo build on one emulator, `tmplayer_promo_tv`, phone shape by `wm size` as in CP67;
  the desktop through `PromoShots`), cropped and marked by `scripts/annotate-shot.py`, WebP quality 88, no
  words in any image. Raw captures stay out of git (build/shots/).
- Android: `app/src/main/res/drawable-nodpi/tour_<page>.webp`, `_dark`, `_tv`; `setup_performance_full` and
  `setup_performance_lighter` with `_dark` and `_tv`. Desktop: `ui/src/jvmMain/resources/onboarding/
  tour-<page>-light|dark.webp`, `performance-full|lighter-light|dark.webp`. Pages: videos, home, player,
  controls, downloads, tvhome (TV only), settings.
- Badges: every tour spec has one `circle` mark per numbered line, in line order; a generator reads the specs
  and writes the badge positions (fractions of the image) into `TourBadges.kt`, so a retaken shot moves its
  numbers with one command.

**6. Localization**
- Every new string in `en.json` and translated into all 20 other catalogs (user asked, 2026-10-14), including
  the crash prompt's six `main.crash_*` keys from c48a447. `CatalogTest` green; a key-diff check that no
  catalog misses a key; Arabic and Persian checked right to left on screen; no text inside pictures.
- Site: the privacy page is English only by design; any localized page whose text changes (none expected) is
  rebuilt with `scripts/build-site-i18n.py` and `--check`.

**7. Tests and checks**
- Unit: SetupTest (step lists per form and device class, Mobile data only on a phone, Performance order flip,
  Device step replacing both), TourTest (pages per form, TV row only on TV, badges count equal to lines),
  tour-offer flag tests, Save mobile data planner tests.
- Desktop render and keyboard tests for each new step and tour page: light, dark, Arabic, en-XA, narrow.
- Promo screens on the TV emulator, TV and phone shapes, light and dark, English and Arabic.
- Release build on the phone and the Mi TV: the setup through "Run the setup again", the tour, the existing
  user offer after an update over test13, Save mobile data on the phone's mobile data.
**Order of work:** (a) Save mobile data and (b) the screenshots run in parallel in their own worktrees while
(c) the setup and tour code is written here; then badges, translations, tests, device checks, PLAN status.
**As built (2026-10-14), where it differs from the above:**
- No order flip on Performance: "Full quality" stays first everywhere, as the device short of memory gets the
  Device step instead (Performance is left out whenever that step recommends more than Low storage mode).
- The progress line counts both parts ("Quick tour · Step 7 of 10"); Skip all ends both; Back on the tour's
  first page returns to the setup's last step.
- The offer to existing users is a confirm in the shell ("Take a quick tour?", Take the tour or Not now) held
  behind the other notices; the flag is `tour` (Never, Pending, Done) in `SettingsStore`. "Quick tour" sits in
  Settings, Help, on Android and the desktop.
- Pictures: the desktop Settings page is four stacked windows onto the one long page (1140x1518); Downloads'
  marks are the first downloaded row, the Downloaded tab and the storage panel (the desktop has no space line,
  so its third mark is the cache line); the TV player marks Episodes for line 3 (no next-episode button on the
  TV transport); the phone Home marks the menu button for line 3 (no stars on a phone's Home). The app draws
  each badge just outside its mark's top left corner, so no number covers what it points at.
- Promo routes: `--es screen tour --es page <name>`, `--ez lighter`, `--ez poster_thumbs`, `--ez downloads_demo`.
**STOP**

### U01 Two Sentry settings
1. sentry.io, the TMPlayer project, **Settings → Security & Privacy**.
2. Turn on **Prevent Storing of IP Addresses**, and **Data Scrubber** with **Use Default Scrubbers**.
3. **Client Keys (DSN):** does the host end in `.de.sentry.io`? Tell Claude yes or no (the privacy page
   says "stored in the EU").

### U02 Licence label and logo source
- One label: the README says GPL-3.0, winget, AUR, metainfo and the MSI say GPL-3.0-or-later. Default if you
  say nothing: GPL-3.0-or-later.
- Find the exact SVG Repo page the player logo came from and its licence. Only the source can say whether it
  may be relabelled GPL.

### U03 Budget decision
`DISTRIBUTION_PLANS.md` says strict $0 (no Play fee, no paid certificate). The listing plan recommended Certum
(about EUR 49 a year) and Google Play ($25 once). Pick one:
- **$0:** Windows signing only if SignPath accepts; no Google Play (Phase G dropped). Android developer
  verification (U07) may still need the $25 account; check whether Google's free hobbyist option applies.
- **Pay:** Certum now, SignPath as a free bonus, Google Play later.

### U04 (optional) Commit email
235 commits carry nevilnicks4321@gmail.com. Past ones stay. For future ones:
`git config user.email "hello@tmplayer.org"` (or your GitHub noreply address).

### U05 (optional) DMARC
DNS TXT record `_dmarc`, value `v=DMARC1; p=none; rua=mailto:hello@tmplayer.org`.

### U06 Check the 1.22.1 Telegram post
The 1.22.1 run finished green on 2026-10-05; it was the first real run of the announcement step. Look at
t.me/tmplayerapp. If nothing was posted, tell Claude (the step is `continue-on-error`, so a failure would not
show red).

### U07 Android developer account
Enforced since 2026-09-30 in Brazil, Indonesia, Singapore and Thailand, worldwide in 2027, for GitHub APKs too.
1. play.google.com/console/signup with the Google account that should own TMPlayer.
2. **Personal** account (an organization needs a D-U-N-S number).
3. The one-time $25 fee (see U03), legal name, address, phone, government ID. Your address is not shown
   because the app is free; your name, country and developer email are.
4. Wait a few days for the identity check.

### U08 Register com.tmplayer and the key
1. In the console, **Android developer verification**, register an app distributed outside Play.
2. Package: `com.tmplayer`
3. Certificate SHA-256 (`CN=TMPlayer, OU=dev, O=dracu-lah`):
   `00:FB:F6:E8:BC:F6:08:00:60:E2:65:3B:BD:44:00:46:55:E6:5D:25:DD:46:E6:D3:D2:BC:C8:81:C2:84:26:52`
   (check: `apksigner verify --print-certs TMPlayer-1.22.1-universal.apk`)
4. Copy the token file the console gives you and send it to Claude (CP15). Upload the signed APK it builds.

### U09 Pick the Windows signing route
Azure Artifact Signing is out (individuals in US and Canada only).
- **SignPath Foundation:** free, publisher shows "SignPath Foundation", manual approval per release. Already
  applied (policy page at tmplayer.org/signing). Small projects are often declined.
- **Certum Open Source cloud:** about EUR 49 a year, your real name, needs ID and a utility bill.
- **SSL.com IV with eSigner:** about $309 a year, official GitHub Action.
A certificate puts your name on the installer; the SmartScreen warning still shows until reputation builds.

### U10 SignPath: send them references
On 2026-10-05 Phillip Deng (oss-support@signpath.org) wrote that they could not find enough public references
and asked for articles, forum posts or community mentions before a final decision. Reply from the same inbox
with the evidence list (draft given in the 2026-10-06 session): the two infoek.cz articles, the merged
awesome-kotlin and awesome-compose-multiplatform entries, release download counts (about 480 across 1.16.0 to
1.22.0 on 2026-10-06), 19 stars and 2 forks, issues #2, #3 and #5 opened by three different users, the
Telegram channel. Small projects are often declined even so; then decide on U11 or stay unsigned.
If accepted: create the project in SignPath, tell Claude (CP14).

### U11 Certum (only if paying)
1. shop.certum.eu, "Open Source Code Signing in the Cloud", buy.
2. Activate: your name exactly as on your ID, email, project URL `https://github.com/dracu-lah/TMPlayer`.
3. Identity check, plus a utility bill in your name (ask support before paying if you have none).
4. Install **SimplySign**, register it. **Save the TOTP seed when it shows the QR**; CI needs it and it cannot
   be recovered.
5. Tell Claude when the certificate is issued.

### U12 Signing secrets
Repo **Settings → Environments → release → Environment secrets**. Claude gives the exact names in CP14.
Recommended: add yourself as a **Required reviewer** on that environment.

### U13 winget submission (after CP13)
1. Windows Terminal (not PowerShell ISE): `winget install wingetcreate`, reopen, `wingetcreate token --store`.
   (Token route: a classic token with only `public_repo`; fine-grained tokens do not work.)
2. Copy the three files to `manifests\d\dracu-lah\TMPlayer\<version>`.
3. Optional test: `winget settings --enable LocalManifestFiles` (admin), then
   `winget validate --manifest <folder>` and `winget install --manifest <folder>`.
4. `wingetcreate submit --prtitle "New package: dracu-lah.TMPlayer version <version>" <folder>`
5. Reply to the CLA bot with `@microsoft-github-policy-service agree`. Answer `Needs-Author-Feedback` within
   5 days. "Telegram" may trigger a trademark review (Policy-Test-2.2); the "unofficial" wording is the
   defence. Never replace a release file after submitting.

### U14 winget token secret
After the first winget PR merges: a classic token with `public_repo` as repo secret
`WINGET_CREATE_GITHUB_TOKEN`. Then CP16.

### U15 Awesome lists
Status 2026-10-20 (one nudge comment on each open PR that day; do not comment again before 2026-10-27).
Merged: Heapy/awesome-kotlin#1184, mahozad/awesome-compose-multiplatform#5, pcqpcq/open-source-android-apps#496
(2026-10-06), kalanakt/awesome-telegram#101 (2026-10-10), ebertti/awesome-telegram#331 (2026-10-13).
Open from before: Generator/Awesome-Android-TV-FOSS-Apps#57 (filed as a PR, entry reworded), stax76/awesome-mpv#56
(upstream dormant since 2026-02), krzemienski/awesome-video#139 (their awesome.video submission form is the preferred
route: the user can submit there), offa/android-foss#785 (new rule from #788: 6 months of active development, so
TMPlayer qualifies early February 2027; leave open or reopen then. If spyware comes up: Sentry is off by default and
only starts when the user turns it on, then sends only the stack trace, versions and device model).
Opened 2026-10-20: serhii-londar/open-source-mac-os-apps#1708, iCHAIT/awesome-macOS#1316,
cuongtk8/awesome-linux-apps#77, jetpack-compose/jetpack-compose-awesome#103, mcxiaoke/awesome-kotlin#149.
Closed unmerged: fmhy/edit#6585 (FMHY adds links through its own testing process; follow up on their Discord, a PR
will not do it).
Skipped on purpose: 0PandaDEV/awesome-windows (bans AI-built projects), Awesome-Linux-Software (archived),
kmp-awesome (libraries only), linsa-io/macos-apps (personal list; possible later), the inactive KMP, Compose-apps
and foss-apps lists.

### U16 Flathub PR #10513
The bot closed it because the checklist used `[✓]` instead of `- [x]`. The body was fixed and a reopen was
requested on 2026-10-05; it is still closed, and a reviewer (ostfriese4) replied that the linked video
`demo-phone.mp4` "is not taken on Linux". Reopening needs a real screen recording of the Flatpak build running
on Linux. Flathub needs offline from-source builds of the app, TDLib, mpv
and FFmpeg, which the listing plan judged too heavy. Either wait for a maintainer, or let it go (AppImage,
tarball and AUR cover Linux). Do not open a new PR; the bot says to comment instead.

### U17 AlternativeTo
1. Sign up at alternativeto.net (ideally hello@tmplayer.org), verify the email.
2. User icon, **Suggest new application**. Name `TMPlayer`. Tagline:
   `Watch the videos in your Telegram chats on your TV, phone or computer, while they download.`
3. Website https://tmplayer.org; License Free, Open Source ticked; Platforms Windows, Linux, Android, Android
   Tablet, Android TV, Fire TV (not "AppImage"); Category Video & Movies; Features media player, video player,
   Telegram, streaming, subtitle support, remote control. **Not** "ad-free" and not "no registration".
   Icon `site/icon-512.png`; screenshots `tv-grid.webp`, `tv-chats.webp`, `phone-grid.webp` from
   `site/screenshots/`.
4. Description (no links allowed):
   > TMPlayer is a free and open source video player for the videos in your own Telegram chats and channels. It runs on Android TV, Fire TV, Android phones, Windows and Linux, and starts playing within seconds while the file is still downloading; seeking re-aims the download instead of waiting. Sign in on a TV by scanning a QR code with your phone. Pick a chat and only its playable videos appear, as a poster grid with what you were watching first. It plays MKV, MP4, AVI and TS with embedded subtitles and multiple audio tracks, recognises episode names and moves on to the next episode, and remembers where you stopped. No ads of its own (Telegram's sponsored messages appear in public channels, as Telegram requires), no account except your Telegram one, no server in between.
   >
   > TMPlayer is an unofficial app, not made by or affiliated with Telegram. It uses the Telegram API through TDLib and needs your own Telegram account.
5. Alternatives: Telegram, Kodi, VLC Media Player, optionally Unigram and Telegram X. The $5 priority review
   buys speed, not approval.

### U18 Uptodown (after CP05)
1. en.uptodown.com/developers-console, organization "TMPlayer", hello@tmplayer.org. No ID check.
2. **Apps → Add new app**, Android, the universal APK from the GitHub release.
3. Icon `site/icon-512.png`, name TMPlayer, category Video, website, PEGI 3, author your name.
4. Short description (70 chars): `Play your Telegram videos on TV and phone while they download`
5. Full description: `fastlane/metadata/android/en-US/full_description.txt` plus
   `Source code (GPL-3.0): https://github.com/dracu-lah/TMPlayer`.
6. Final Release, Min SDK 26, changelog from fastlane. Licensing: Free; **Ads: Yes** (sponsored messages);
   licence GPL 3.0, License Text URL `https://github.com/dracu-lah/TMPlayer/blob/main/LICENSE`, Source Code
   URL the repo.

### U19 XDA thread
XDA bans AI-written posts (rule 15): write it yourself; `docs/distribution/telegram-apps.md` is only a fact list.
Register at xdaforums.com, put Sponsors and Buy Me a Coffee in your signature (the only place allowed), read the
rules, search "TMPlayer" first, post once in **Android Apps and Games**. Title:
`[APP][8.0+][Android TV, phone, Windows, Linux] TMPlayer: stream your Telegram videos while they download (free, open source)`.
Include the "unofficial, not affiliated with Telegram" line, live download links, source, the Telegram channel.
For each release, edit the first post.

### U20 Search Console and Bing
The google-site-verification TXT is already on the domain: confirm the property, submit
`https://tmplayer.org/sitemap.xml`, then in Bing Webmaster Tools use "Import from Google Search Console".

### U21 Downloader code and Amazon developer account
Register a Downloader short code for `https://tmplayer.org/download/` at aftvnews.com; tell Claude the code
(it replaces the DOWNLOADER-CODE marker on `/fire-tv/`). Create the Amazon developer account for U23.

### U22 Translation reviews (CP24)
Review the languages you read as you use the app; ask the issue #2 author to review es-419.

### U23 Amazon Appstore listing (after CP30)
Submit using the pack Claude prepares in `docs/distribution/`.

### U24 Rotate the OpenSubtitles key
It was pasted in chat on 2026-10-05. Rotate it on opensubtitles.com and update the `OPENSUBTITLES_API_KEY`
secret before CP31.

### U25 MSone
Permission email sent 2026-10-05. MSone agreed (see CP33). The API side is a separate repo,
`~/projects/personal/msone-unofficial-api` (its PLAN.md, phases A0 to A5). Draft reply asking for the
Search API contract: that repo's `private/email-to-msone.md` there (gitignored; the owner said not to send it for now).

### CP33 MSone subtitles in the apps
**Needs:** the API repo at A4 (`/msone/search` live at `https://subs.tmplayer.org`), except B1.
Hosting facts: Cloudflare free (100k req/day shared with the TMDB proxy), GoDaddy CNAME `subs`.
Wording rule: "video", no real titles in public copy. Edge cases per AGENTS.md "Building a feature".
- [ ] **B1 Fonts (independent, can go first).** Android phone/TV: bundle a Noto Sans Malayalam subset
  (OFL, `THIRD_PARTY_NOTICES.md:198`), use a `Typeface` from it in `app/.../player/SubtitleLook.kt` when
  cues contain U+0D00-0D7F (`FallbackFonts.kt` is a no-op, TV sticks may show boxes). Desktop mpv: ship
  the font, set `sub-fonts-dir` and `sub-font` in `MpvPlaybackEngine.kt` (~:695-717); Compose's subset
  does not reach mpv. Web: `@font-face` Noto Sans Malayalam woff2, `unicode-range: U+0D00-0D7F,U+200C-200D`,
  in `webapp/public/fonts/`, `font-display: swap`, precached by the service worker, applied to
  `.movi-cue` and `video::cue` (`player.css:35-38`); give JASSUB the font when ASS lands. Keep ZWJ and
  ZWNJ in the subsets (check `ui/fonts/subset-noto.py`). Test lines: chillus (ൻ ർ ൽ ൾ ൺ), conjuncts
  (ക്ക, ന്ത, ശ്ശ, സ്റ്റ), `്യ/്വ + ര`, ZWNJ, a long two-line cue, bold and italic. Strip BOM.
- [ ] **B2 Core provider.** `Msone` in `SubtitleProvider` (`SubtitleModels.kt:7`) and the `when`
  branches in `OnlineSubtitles.kt` (`search` :148, `download` :285, `blockedFor` :299); `MsoneApi.kt`
  modelled on `SubdlApi.kt` against `https://subs.tmplayer.org` (shape in the API repo's `API.md`);
  pass the IMDb/TMDB id into `SubtitleTarget` (new optional field, from `OnlineMetadata`); `OrNull`
  parsing and a line in `GarbageInputTest`.
- [ ] **B3 Pack once per device.** Download a series pack once, extract every episode into
  `SubtitleCache` keyed `msone:<release>:<SxxEyy>`, serve later episodes from it (one MSone count per
  user per series). Skip if MSone offers a per-episode endpoint. "Subtitle pack saved" note. Free-space
  check, 4 MB per file cap, no partial files on cancel or full disk, dedupe in-flight.
- [ ] **B4 UI and credit.** Picker rows in `TrackPickerFragment.kt` (online page :328), desktop
  `OnlineSubtitlesPanel.kt`, a new web online-subtitles sheet feeding `readSubtitle()` (`subtitles.ts`).
  Each hit: "Subtitles by MSone", translator (link), release page, year. Settings toggle (default on
  when Malayalam is a learnt language), TV remote focus on the credit row and list, "pick a file"
  fallback offline or blocked. en.json only (no LangSync). Legal page line.
  **If MSone asks us to stop** (state `paused` or `stopped` from the gateway `/health`, or a static
  `status.json` on tmplayer.org if the apps call MSone directly): check at most every few hours,
  cache the last state, keep `stopped` if the check fails. Show an inline row in the picker where the
  results would be ("MSone subtitles are paused at their request. You can still pick a subtitle
  file." with the file picker right under it, focusable on TV), the Settings toggle off and disabled
  with the same line, and a toast only when the user taps MSone search just as it changes. Texts in
  en.json, the message from the status may be shown as is. `stopped` hides the setting and deletes
  cached MSone packs on next launch; `paused` keeps them for offline use. Tests: each state in the
  picker and Settings, a failed check keeping `stopped`, pack purge.
- [ ] **B5 Verify.** Phone, TV emulator (also one with no system Malayalam font), AppImage, web in local
  Chromium; play episode 1 then 2 and confirm no second pack download; idle CPU on the TV after the
  picker closes; screenshots of Malayalam text on all four surfaces.
- [ ] **B6 Housekeeping.** THIRD_PARTY_NOTICES, comment on issue #5 (close when on main).
**Progress / handoff (2026-10-28):** the gateway is live with `/health` only (state `off` until
MSone's key arrives). Built against the draft `API.md`, behind the off state:
- Core: `MsoneApi.kt` (health, search, fetch; `parseHealth`/`parseHits` tolerant, https-only links,
  hits capped at 100, text at 300 chars; `downloadUrl` fetched directly only on
  malayalamsubtitles.org, else `/msone/download/:id`), `SubtitleProvider.Msone`, MSone fields on
  `SubtitleHit` (pack, translator, translatorUrl, pageUrl, link) and `SubtitleTarget.imdb`,
  `SubtitleNotice.MsoneOff/MsoneBusy/MsoneTooShort`. `OnlineSubtitles.msoneStatus(force)`: checked
  at most every 6 h, Check again at most once a minute, a failed check keeps the last state
  (`stopped` survives, never answered = `Unknown`), `stopped` deletes MSone files
  (`SubtitleCache.purgeFiles`), `paused` keeps and still lists them. `searchMsone` (IMDb id first,
  else title, year, season, episode; empty answers not cached), `download` (pack: zip up to 20 MB,
  the video's episode kept as its own file `id:SxxEyy`, so B3's pack-once is not done yet: each
  episode fetches the pack again). Gateway errors 410/503 update the state, 429 busy, 400 too short.
  Tests: `MsoneTest` (16), `GarbageInputTest` lines.
- **Hidden entry point:** typing `msone` as the SubDL key (Settings, Online subtitles) toggles
  `OnlineAccount.msoneShown` with a toast; the key itself is kept. Web: `?msone=1` / `?msone=0`.
  Only then: "Malayalam subtitles from MSone" in the subtitle picker (Android phone/TV
  `TrackPickerFragment`, desktop player menu) and an MSone row in Settings (state line, gateway
  message, press = Check again).
- Picker: the state line where results would be, the gateway's message under it, Check again and
  Pick a subtitle file; hits with year, Season pack, Translated by, Subtitles by MSone; desktop
  also links Release page and the translator. Strings in en.json `msone.*` (not translated yet).
- Verified: core tests, `:app:compileDebugKotlin`, desktop render shots of every state
  (`OnlineSubtitlesRenderTest.msonePanelInEachState`). **Not verified on Android devices** (user,
  2026-10-28: "no test on the phone"): TV remote focus on the state row's buttons, the phone picker
  in both orientations.
- Not done: B1 fonts, B3 pack once per device, Android translator/release links, IMDb id passed
  from `OnlineMetadata` into `SubtitleTarget.imdb` (field exists, callers pass null), Settings
  toggle default by learnt language, legal page line, translations of `msone.*`.
- Web (73ad63c): `webapp/src/player/msone.ts` (client, zip reader on DecompressionStream, kept
  subtitles in idb `tmplayer-msone`, 200 files or 20 MB, paused lists them, stopped deletes),
  `msone-sheet.tsx`, `msoneFlag.ts` (`?msone=1`), Settings row; `msone.test.ts` (node --test, 23).
  A pack whose numbered entries lack this episode gives nothing, on web and apps (a27a5bd).
- From the gateway's code (5c07979): its onError answers 503 `unavailable`, so that is offline, not
  `off`; only /health, 410 and 503 `paused` set the state. Message cap 500. The gateway's CORS
  allows only https://web.tmplayer.org, so localhost web shows "Couldn't check".
- Web deployed to production (2026-10-28). Tag `v1.28.2-beta.3` (notes: 5 lines, desktop keys,
  History bar, popup crash, LG/Samsung packages, MSone groundwork) NOT pushed: the auto mode
  classifier refused it; the user pushes it. A local release-signed 1.28.2-beta.3 (1280203) is on
  the POCO phone and the Mi TV. The TV's web test wrapper `org.tmplayer.tvweb` is rebuilt from
  `private/tvweb/build.sh 'https://web.tmplayer.org/?tv=1\&msone=1'` (no Gradle, debug key).
- Not checked on devices yet: the TV picker's MSone row and remote focus, MSone on the TV web.
**2026-10-28 (late): MSone gave their addon (addon.malayalamsubtitles.org), live end to end.**
Gateway (private repo now) reads it, API.md version 1, client keys required (X-Api-Key), 403 for
unlisted browser origins, 60/min per IP, `tmdb=` turned into IMDb via TMDB. Apps (8a740e1): build
key `MSONE_GATEWAY_KEY` (local.properties / CI secret; web uses secret `MSONE_GATEWAY_KEY_WEB`),
search IMDb id (OnlineMetadata.idsFor, from extras) then TMDB id + title, then title; MSone shown by
default in a build with the key (`msone` as SubDL key hides it); packs and zip readers removed (one
srt per episode, so B3 is moot); expired link = `msone.gone`, search cache dropped. B1 fonts merged
(1118299, desktop fontconfig set for 15 scripts, Android Malayalam, web 19 scripts by unicode-range).
Owner must set: gateway secret TMDB_API_KEY, TMPlayer secrets MSONE_GATEWAY_KEY and
MSONE_GATEWAY_KEY_WEB, local.properties MSONE_GATEWAY_KEY (keys in the gateway repo's
private/client-keys.env). Then redeploy web and cut the beta (cadence guard: 3 betas this week).
Device checks due: TV picker focus on MSone rows, a TV without a Malayalam font, AppImage on a bare
Linux, win11 still on system fonts. `msone.gone` and `msone.*` need translating before release.
**STOP**

### U26 to U29 Google Play (only if paying)
- **U26** Ask the Telegram group for 15 or more people with Android phones or TVs who will stay opted in for 14
  days; collect their Play Gmail addresses.
- **U27** Play Console: app TMPlayer, App, Free. **App signing: upload the existing release key with PEPK before
  any testing release** (cannot change later; keeps GitHub and Play builds interchangeable). Privacy policy
  `https://tmplayer.org/privacy`. App access: the demo mode. Ads: Yes. Content rating honestly (expect Teen or
  Mature). Target audience 18+. Data safety: phone number and user IDs collected, sent to Telegram, required;
  crash logs optional, Sentry as processor; encrypted in transit; deletion by sign-out and Telegram account
  deletion. Foreground service `dataSync` with a short screen video of a download. No donation links.
- **U28** Closed testing track, upload the AAB, add testers, they stay 14 days in a row, then apply for
  production (about 7 days review).
- **U29** Production with a staged rollout (20 %), then **Advanced settings → Form factors → Android TV**, TV
  screenshots and banner, a separate TV review.

### U31 Sentry on the free plan
1. **A token for the mapping upload.** sentry.io, **Settings → Developer Settings → Organization Tokens →
   Create New Token**. On this page there are no scopes to pick: an organization token always gets
   `org:ci` (release creation and uploads), which is why the earlier advice to give it release rights could
   not be followed. If that page is missing or the upload is refused, use **User Settings → Personal Tokens**
   with Project: Read & Write, Release: Admin and Organization: Read instead.
2. `gh secret set SENTRY_AUTH_TOKEN` and paste it (nothing is echoed). For the backfill on this machine,
   also `export SENTRY_AUTH_TOKEN=...` in the shell, or give it to Claude in that session.
3. **Alerts → Create Alert → Issues:** email when a new issue is created and when one regresses (the free
   plan has email, not Slack).
4. **Settings → Subscription (or Spike Protection):** check spike protection is on for the project.
5. Tell Claude: done, and which token kind worked.

## Design reference

### R1 Localization
- Catalog `core/src/commonMain/resources/i18n/<tag>.json`, `en.json` is the source of truth; keys namespaced by
  screen; ICU MessageFormat (`{count, plural, one {# video} other {# videos}}`).
- Translations are written by Claude straight into the catalogs (langsync was removed 2026-10-06: Google's free
  endpoint rate-limited it to nothing, and it cannot write Arabic's six plural forms). Product names stay in
  Latin script, `{placeholders}` and ICU structure are kept, and `:core:jvmTest` checks both.
- Codegen: a Gradle task in `:core` turns `en.json` into `object L` with typed accessors (`L.onboardingSkip`,
  `L.videosHidden(count)`), so a missing key fails the build.
- `Translator`: classpath catalogs, per-key English fallback; ICU subset (`{arg}`, `plural`, `select`) with CLDR
  plural rules for en, es, pt, hi, ml, no ICU4J; a locale-aware formatter for sizes, durations, dates and
  numbers; ISO 639 track names in the UI language (`Locale("de").getDisplayLanguage(active)`).
- Resolution on every device: saved `LANGUAGE` in `SettingsStore` (empty = system), else the system list
  matched by language then region (`es-CL` to `es-419`, `pt-PT` to `pt-BR`), else English. Desktop reads
  `SettingsStore` via `DesktopPlatform.kt`.
- Compose: `LocalStrings` in `ui/src/shared` fed by a `StateFlow`, so a change recomposes without restart. Text
  outside Compose (notifications and channel names, `TrackPickerFragment`, tray, Windows notifications, SMTC)
  reads `Translator`.
- Android: `res/xml/locales_config.xml` plus `android:localeConfig`; `LocaleManager.setApplicationLocales` on
  API 33+; `values-<tag>/strings.xml` for `app_name`; `PhoneCountries.kt` via `Locale.getDisplayCountry`.
- Track defaults: last pick for this series, then user preference, then UI language.
- Decisions: auto-switch to a supported system language plus a one-time "Now in Español, change in Settings"
  card; contributions by GitHub PR on the JSON with CI `:core:jvmTest`, no Weblate; screenshots English only;
  first wave es-419 (issue author reviews), pt-BR, hi, ml (user reviews); unreviewed languages hidden.
- **Changed 2026-10-06 (user):** ship the most common languages at once and review in use: es-419, pt-BR, fr, de, it, ru, uk, tr, id, vi, ar, zh-CN, ja, ko, hi, ml.
  Machine translations are visible in the picker (no hiding). This widens the earlier choices: CLDR plural
  rules for all 16 (ru, uk and ar have the extra categories; ja, ko, zh, vi and id have one form); right to
  left layout for `ar` (Compose mirrors on its own; check the XML player overlay, icons that point a
  direction, and the timebar); desktop fonts for CJK, Arabic, Devanagari and Malayalam (Noto, with a notices
  entry; subset or load from the system if the package grows too much, and say how much it grew).

### R2 Shared onboarding
Pages: Language (system preselected, each language named in itself, "Help translate"); What TMPlayer is
(unofficial, no server, no data collected); Sign in; Chats; Videos. Wording by form factor (touch, remote, mouse
and keyboard). `OnboardingImage` actuals in `ui/src/androidMain` (existing drawables) and `ui/src/jvmMain` (WebP).
Gating `SettingsStore.overviewSeen`, `markOverviewSeen`, `replayOverview` everywhere. Desktop promo fixture
mirrors `app/src/promo/`.

### R3 App shell
What's new: `lastSeenVersion`, a dismissible sheet on the first run of a new version with bundled, translatable
highlights. Feedback: a prefilled GitHub issue URL (version, device, OS, language, no personal data), mailto
fallback, QR on TV. About: see CP05. Star prompt: once, after 10 completed plays, never repeats (optional).

### R4 Player
Delays ±0.1 s per file; subtitle style S/M/L/XL, box, position, live preview; volume boost via Android gain and
compressor `AudioProcessor`, mpv `volume-max=150` and `af=dynaudnorm`; sleep timer in the menu; "still watching"
after N autoplays or 2 h idle, pauses and stops downloads; TV next-up card with D-pad focus on "Play now";
resume rules (Kodi model): none in the first 3 min or last 8 %, watched at 90 %, save tracks, speed and delay
every 10 s and on pause and quit; desktop frame step, screenshot, A-B repeat, chapters, `?` sheet. Trickplay:
thumbnails from the downloaded range only (Android `MediaMetadataRetriever.getScaledFrameAtTime(OPTION_CLOSEST_SYNC)`,
desktop a second mpv instance or an mpv screenshot at a position), off on 1 GB devices, behind a setting.

### R5 Browse
Series view from `MediaName.kt` SxxEyy plus new forms, one tile per series ("Show · 2 seasons · 18 eps"),
season tabs, episode list with progress and ticks, "Series" and "All files" toggle. Home: Continue (thumbnails
and progress), a row per favourite chat, "Recently added across chats", lazy rows of 10, rail and drawer keep
their tabs. Detail panel: cleaned title, filename, caption as synopsis, resolution, codecs, tracks, size,
Resume `34:10` or Start over, Download, Mark watched, TMDB poster and overview when enabled. Search: last 5
recent searches as chips, a "Videos in all chats" scope via TDLib `searchMessages` with a video filter, results
split into Chats and Videos. Empty states via `StateScaffold` and `UiState`. Downloads: "Remove after
watching", "x GB free". TV: 1.05 focus scale, 48/27 dp overscan check, labels for actionable icons. Watch Next:
`androidx.tvprovider` `WatchNextProgram` CONTINUE and NEXT, works on stock Android TV launchers, retired on
Google TV in 2027, behind a setting.

### R6 Fire TV
Detect `amazon.hardware.fire_tv` for remote key quirks and the voice-search fallback; test on a Fire TV emulator
or device; Amazon listing reuses the existing screenshots.

### R7 Online metadata (zero cost at any scale)
- **OpenSubtitles.com REST v1:** one free app key; the terms forbid making users enter their own key; downloads
  count against each user's own free account (10 to 20 a day, 5 per IP anonymously); 5 req/s per IP, login
  1 req/s; the hash match (size plus 64 KB head and tail) is the reliable route; exclude `ai_translated` and
  `machine_translated` by default; es and pt deep, hi thin, ml very thin.
- **TMDB:** free for non-commercial use (donations stay voluntary and unlock nothing); about 40 req/s per IP;
  cache at most 6 months, purge on termination; attribution required; es and pt-BR good, hi partial, ml sparse.
- **TVmaze:** no key, at least 20 calls per 10 s, CC BY-SA with attribution. **AniList:** no key, about 30/min.
  **SubDL:** free key, 2,000 req/day, has the Subscene archive, better for hi and ml.
- **Subtitles design:** the app key injected by CI from `OPENSUBTITLES_API_KEY` (builds without it hide the
  feature); the user signs in with their own OpenSubtitles account under Settings → Online subtitles, token
  stored like other credentials; search by hash (head and tail via TDLib byte ranges, works mid-download), then
  TMDB/IMDb id, then parsed title and episode; machine and AI subtitles off by default (a labelled toggle);
  language from the subtitle preference then the UI language; "N of M downloads left today" from `remaining`
  and `reset_time`; optional SubDL fallback with a user-entered SubDL key.
- **Metadata design:** TMDB (CI key from `TMDB_API_KEY`, user can override in Settings), TVmaze for episodes,
  AniList for anime; off by default, on in Settings or from the detail panel; matching on the cleaned title plus
  year or SxxEyy, `{tmdb-123}` in a filename forces a match; UI language with English fallback; About shows the
  TMDB notice and logo (less prominent than our branding) and the TVmaze link.
- **Shared:** an on-device cache keyed by file id or hash, TTL at most 6 months, a purge-all action;
  single-flight per item, debounce, backoff on 429; posters at the right size into the image disk cache.
- **Never stop working midway (user requirement):** no core feature depends on a provider; cached results stay;
  fallbacks TMDB → TVmaze/AniList → filename and OpenSubtitles → SubDL → local `.srt`; on 401/403 the provider is
  disabled with a clear message ("Online subtitles unavailable right now", "Add your own TMDB key"); keys rotate
  through updates; the app and site call these "online extras". Skipped providers: OMDb, Trakt, Fanart.tv,
  SubSource, Wyzie.
- **MSone (issue #5):** about 3,400 releases, IMDb id on every page, direct `.srt` via `?wpdmdl=`, WP REST API
  blocked, no reuse licence. Nothing is built until they agree; never a scraper. If they agree: a provider
  searching by title and year or IMDb id, episode pick inside season packs, a "via MSone" credit.
- **Verification:** cache hits, a forced 429 for quota exhaustion, key-revoked behaviour.

### R8 Site
The website's development plan, with what is done and every open item by phase, is `private/SITE-PLAN.md` (2026-10-21).
Done already: OS-detected download, `/changelog/` and `feed.xml` via `scripts/build-changelog.py` (rerun after
footer edits), `/fire-tv/`, `/formats/`, `/security/`, `/signing/`, JSON-LD, community and donate footers.
Still to do (CP26): localization as in CP26; `/translate/`; SEO guides "Watch Telegram videos on Android TV",
"...on Fire TV", "Telegram on TV without casting"; a home numbers strip if not complete (stars, downloads from
`scripts/download-counts.sh`, languages, "0 ads of our own, 0 trackers", "free forever"); testimonials only with
permission; the language switcher in the footer.

### R9 Scripted bulk extraction
1. Find: `grep -rnoP` per module for `Text("`, `text =`, `label =`, `title =`, `contentDescription =`, toasts and
   notifications, into a scratchpad TSV.
2. Name: a script proposes keys; review once per module.
3. Catalog: `jq` merges into `en.json` (sorted). Only `en.json` is written by hand or script.
4. Replace: `perl -pi -e` or `sed -i` on exact call shapes, plus imports.
5. Check: build, `git diff --stat`, re-grep; leftovers go to an allowlist or a manual list (interpolations,
   plurals, concatenations, Views, words needing two translations).
6. Site: the same, `perl` adds `data-i18n`, `jq` writes `site/i18n/en.json`.

### R10 Critical files
- core: `core/build.gradle.kts`, `core/src/commonMain/kotlin/com/tmplayer/data/{SettingsStore,MediaName,ResumeRecord,ChatRepository,SizeFilter}.kt`
- ui: `ui/src/shared/kotlin/com/tmplayer/ui/{browse/BrowseSections.kt,browse/BrowseViewModel.kt,components/Skeleton.kt,components/UiState.kt}`
- app: `app/src/main/java/com/tmplayer/{MainActivity.kt,ui/onboarding/OverviewScreen.kt,ui/auth/IntroScreen.kt,ui/settings/*,ui/browse/TouchNav.kt}`,
  `player/{PlayerActivity.kt,PlayerTvMenu.kt,PlayerGestures.kt,FrameRateMatch.kt,TrackPickerFragment.kt,ExternalSubtitle.kt}`,
  `data/{DownloadService.kt,PhoneCountries.kt,CrashReports.kt}`
- desktop: `desktop/src/main/kotlin/com/tmplayer/desktop/{ui/Shell.kt,ui/ShellState.kt,ui/Settings.kt,ui/MediaGrid.kt,ui/UpdatePopup.kt,player/MpvPlaybackEngine.kt,player/PlayerKeys.kt,player/PlayerScreen.kt,SelfTest.kt}`, `desktop/packaging/`
- site: `site/*.html`, `site/app.js`, `site/sitemap.xml`, `site/site.webmanifest`
- CI: `.github/workflows/release.yml`, `ci.yml`

## Done before this plan (for context, do not redo)

- Tier 1 (2026-10-05): plan and langsync configs (`1c4635e`); community and donate links (`ffa496b`); Telegram
  release announcements in release.yml (`a4960ef`, first real run was 1.22.1); English site pages (`ffa496b`);
  distribution drafts, fastlane text, Obtainium link (`760b7ad`); build provenance attestations (`1e55b5f`);
  repo hygiene (`f6a08fb`).
- Partial compliance pass `2d73eeb`: Android intro "unofficial" line, `dependenciesInfo` off, Gradle wrapper
  checksum, some wording, a first crash scrub, privacy page edits, `/signing/`. CP01 to CP03 finish it.
- Accounts: Telegram channel t.me/tmplayerapp and group t.me/tmplayer_chat, release bot and secrets, Discussions,
  GitHub Sponsors (tiers live), Buy Me a Coffee, hello@tmplayer.org (ImprovMX on GoDaddy DNS), `TMDB_API_KEY`
  and `OPENSUBTITLES_API_KEY` secrets, repo social preview, launch tweet.
- Releases: 1.22.1 (2026-10-05) fixed the TV player crash. The 50 MB and no-upper-limit size defaults are in it.
- Submissions made on 2026-10-05: see U15, U16, CP17, U10.

## Set aside

- **IzzyOnDroid:** their policy rejects vibe-coded apps and the form asks the AI assistance level; an honest
  answer means a decline. Obtainium covers the same users. A draft exists in `docs/distribution/izzyondroid.md`
  if the policy changes.
- **telegram.org/apps:** no submission path; only @PressBot, which is for journalists.
- **Casting, profiles, kids mode, preview autoplay, recommendation rows, hero carousels, quality choice,
  audio-fingerprint skip intro, theme engines:** deliberately not copied from competitors.

- [ ] CP103 3D videos and anaglyph (user, 2026-10-22: "a lot of users are requesting it", everywhere; agreed to do after the 2026-10-26 release, about a week). Reuse open source maths: FFmpeg's stereo3d (mpv) Dubois matrices for red/cyan, Kodi's name tags (3D with SBS, HSBS, TAB, HTAB, OU, HOU). Layout from the file where it says (Media3 Format.stereoMode, Matroska StereoMode 0x53B8 in core cast/Matroska.kt, mpv video-params/stereo-in), else the name, else a manual picker (Off, Side by side, Top and bottom), since Telegram's re-encoded copies lose the metadata. Outputs: 2D one eye (default for 3D), red/cyan anaglyph, as is for a 3D TV. Android: a Media3 GlEffect shader through setVideoEffects (check the Mi TV stick's Mali-450 at 1080p30, the FFmpeg decoder path, the HEVC freeze in androidx/media #1630); desktop: a GLSL user shader through mpv (keeps hardware decoding), video-crop for one eye; web: WebGL over the video element, iPhone's native full screen skips it (inline and standalone only). Optional first step (1 to 2 days): "Show in 2D" crop everywhere plus desktop anaglyph. Later: green/magenta, amber/blue, interlaced and checkerboard, per-video memory, subtitles per eye; MVC and MV-HEVC skipped.

- [ ] CP104 Web player in `webapp/` on branch `webapp-solid` replaces web.tmplayer.org (user, 2026-10-24; main merged in that day). Very light, iPhone first, then old webOS TVs; the app's look kept minimal; English first; FOSS libraries for every piece; the app's grouping rules through a Kotlin/JS target of `:core`. Full plan, flow and edge cases: `private/WEBAPP-PLAN.md`; built in sessions (its section 0, "Do web player session N"), each one the go for itself. Not started.
- [ ] CP104a Uncommitted with it: `web/.../ui/WebOffers.kt` drops the GitHub Sponsors link from the web app's wide coffee card (Buy Me a Coffee only, bottom padding 16 dp). Small and done; compile `:web` and commit on its own when work resumes. Also untracked in the repo root since 2026-10-19: `issues.md` (a Sentry NPE, issue 154596076), `log.txt` (a desktop Compose stack trace), two Search Console zips and `.wrangler/`; triage, then delete or move to `private/`.
- [ ] CP105 TV build of the web player: webOS 5+ (LG) and Tizen 2020+ (Samsung), from the Solid app in `webapp/` only (user, 2026-10-25; many users ask for webOS and Tizen). One codebase and site with a TV mode; legacy bundle; thin hosted `.ipk` and `.wgt` wrappers that open web.tmplayer.org, shared as files (no stores). Keep mtcute; teleproto only if mtcute fails on Chromium 68 or 79 (GramJS is archived). Playback ladder: TV decoder, container repack, wasm sound, SD software video, then the message. First step is a spike in headless Chromium 79 and 68. Details: `private/WEBAPP-PLAN.md` section 6 (Decisions 2026-10-25) and session 14. Not started.

- [ ] CP106 `ChatAvatar`'s thumbnail cache has no type tag: `Thumbnails.kt`'s global LRU is keyed only by the raw TDLib file id (`"file:$fileId"`), and `ChatSummary.photoFileId` is persisted to disk by `ChatSnapshot` and reloaded before TDLib reconfirms it. TDLib local file ids are only valid within one session; a stale persisted id can collide with a since-reassigned id for an unrelated file. Seen live 2026-11-01 on the Mi TV: two recent-search chat avatars ("Music in DSD, W...", "Malayalam old...") briefly rendered unrelated gallery/settings screenshots instead of a photo or the initials fallback; a reinstall cleared it. Not release-blocking (self-resolved, narrow trigger), but the architecture gap is real: tag the cache key with a kind (chat photo vs video/file thumbnail) or revalidate `photoFileId` against TDLib before trusting a persisted value.

- [ ] CP107 Fold duplicate copies by TMDB id, not just by name (user, 2026-11-01, follow-up to HX45). Seen live in the real "MarkS" chat on the phone: 5 separate Doraemon tiles that are the same film under different channel titles ("MovieeManHD" placeholder x2 different sizes, the proper poster, "Tamizh Cinema" branded, and "Doraemon New..." truncated to a different leading word) — HX45's name-heuristic fold cannot tell these apart from genuinely different titles without risking a false merge elsewhere, and the user chose the TMDB-id approach over a riskier fuzzy-text match. Scoped 2026-11-01: `SeriesShelf.candidateKey()`/`filmKeyOf()` and `SameVideo` (`core/src/pureMain/kotlin/com/tmplayer/data/SeriesShelf.kt`, `SameVideo.kt`) are pure, synchronous `:core` functions shared by Android, desktop and the web app (`core/src/jsMain/kotlin/com/tmplayer/web/WebRules.kt:291`), with callers that have no coroutine scope (sorting, filtering, tests) — they cannot be made to await `OnlineMetadata.lookup()` (suspend, network, rate-limited, may be off or offline). A TMDB id is also not resolved ahead of grouping: `MediaItem.metaQuery.tmdbId` is only ever set from an explicit `{tmdb-123}` filename tag (`MetaModels.kt:81-96`), the real resolved id only exists after an async `lookup()` call made later by the UI layer (e.g. `HomeViewModel.kt:1148` `PrefetchHomeArt`), by which point `SeriesShelf.arrange()` has already run. Shape decided: keep `SeriesShelf`/`SameVideo` as the unchanged, synchronous name-based baseline every non-UI caller still gets; add a second, UI-layer fold pass (`HomeViewModel`/`MediaGridScreen.kt`/`Series.kt`) that takes the already-arranged tiles plus a `(MediaItem) -> MetaInfo?` read from `OnlineMetadata.cachedMatch`/`peek` (synchronous, cache-only, no network) and merges tiles whose resolved id+provider match, re-triggering as lookups land for tiles already shown ungrouped (the same pattern `PrefetchHomeArt` already uses to react to metadata arriving). Touches all three platforms since they share the grouping code, though the new fold pass is wired per-platform. Tests: `core/src/jvmTest/kotlin/com/tmplayer/data/SeriesShelfTest.kt`, `SameVideoTest.kt`; a new case for id-based folding likely lands wherever the UI-layer pass does, alongside `core/src/jvmTest/kotlin/com/tmplayer/online/` (`OnlineMetadataTest.kt`, `MetaPickTest.kt`). Not started.

- [ ] CP108 Multiple account logins, like Telegram's own account switcher (user, 2026-11-01). Sign in more than one Telegram account and switch between them from inside TMPlayer, rather than one account per install. Needs a plan before any code: how many TDLib clients run at once (Telegram's own apps keep every signed-in account's client alive together, each its own local database; TMPlayer today assumes exactly one), where each account's on-disk state lives (`SettingsStore`, caches, the chosen-chats/Add-chats preference once CP-era features like it exist, downloads, resume positions: per-account or shared is a real design call, not a detail), the switcher UI itself on phone, TV and desktop (TV's remote-driven account list, desktop's own window chrome), what switching does to an open player or an in-progress download, and notifications/background sync for an account that is not the active one. Not started; not scoped yet.

Progress HX67 (2026-11-02, langsync-cli, uncommitted): real test showed Google answers 429 via a redirect to
google.com/sorry (captcha block on this IP), no Retry-After. Code done: `ratelimit.py` (shared token bucket, backoff,
Retry-After, breaker), no per-item fallback on rate limit, exit 3 on abort, `tests/test_ratelimit.py`,
`tests/test_rate_limit_scale.py`. Next: user waits for the block to clear, runs the dry run, then a `-l de` run.

## Progress 2026-11-13: videos kept in a ZIP (split or whole), debug device pass
- Done (local commits, not pushed): ZipArchive parser 0942daa, listing of zip pieces dc15f68, JoinedDataSource window 04cc403, player probe 598af13, TMDB regional tags 739e601, zoomed brightness/volume strips 294541b.
- Checked on the Poco F5 (debug build) and the Mi TV stick (fresh debug install, signed in): Optimus Prime chat lists Avengers (3 parts, 9 GB) and Colony (18 parts, 69 GB); Avengers plays from the zip with far seeks on both devices, 0 dropped frames. Colony not yet played; zipped video whose entry is compressed not yet seen on a device.
- Open: desktop player (PlayerMedia.kt, JoinedStreamBytes.kt) still lists zips but cannot play them; web app (WebRules.kt) unchanged; the TV chat picker says "No videos counted" for a zip-only chat; #16 (TV Home focus hidden) still reproduces, no fix yet; pinch zoom-out bug not reproduced (adb cannot pinch); the plan checkbox for CP75 is stale (shipped 1.27.0).

## Progress 2026-11-14: desktop and web for ZIP videos
- Desktop cf14670: WindowedBytes, readRange, zipWindow in PlayerMedia; ZipWindowBytesTest (3) passes. Not run against a real Telegram.
- Web 53cb16d, 3c7a97a, 14bf790, 09d318e, 51572e0: listing in WebRules, stream/zip.ts (port of ZipArchive.kt), stream/joined.ts (JoinedFile, mountZip, max 3 pieces open), Player wiring and error cards, `pnpm test:stream` (31), e2e/zip.spec.ts (7, 5x rerun). Not verified: real Telegram, WebKit, TV bridge, idle CPU on a low-end device. Real multi-disk .z01 zips are not read (not listed).
- Open bug: Colony (18 pieces, 69.9 GB 4K HEVC) on the Poco F5 failed 3 of 3: StuckPlayerException twice, "hasn't started" once. Zip probe and window were correct (75,072,728,928 bytes, Cues read). Avengers (3 pieces, 1080p) fine on phone and TV. Next: test a second high bitrate file, zipped and plain, to split zip from bitrate/buffer (MID_BUFFER_BYTES 64 MB on this phone).

## Progress 2026-11-14 (2): not-done items review
- Pinch zoom-out FIXED (46c702b, 639f4c6): ScaleGestureDetector would not begin under about 37 mm between fingers and ended at 27 mm; PinchTracker reads the span itself; zoomed picture always zooms by pinch. 24 Robolectric cases (8 failed before). Not felt on a real phone yet.
- TV chat picker count FIXED (99a0b65): ChatVideoCount adds one bounded document search for "zip" when a chat has under 20 videos; ZippedVideos.count (one per piece set, backups no). Verified on the Mi TV stick: Optimus Prime chosen reads "about 2 videos" (was 0). Web videoCounts unchanged.
- Issue #16 TV Home focus (fc5c847): unfocused Play calm, remote starts on Play, no page jump, Up returns to the button left, pager dots + "n / N". Verified on the stick (one focus indicator). Issue #16 stays open until release and the reporter confirms; Right from Play still reaches Details first. Desktop carousel has the same bright Play pill (not changed).
- Colony bug: worker A (opus) still investigating on the phone.

## Progress 2026-11-14 (3): Colony fixed on the phone
- Cause (worker A, confirmed by me on the phone): the selected Hindi AC3 track's first packet is 141 MB into the file; DefaultLoadControl filled its 64 MB byte budget with picture only (buffered 0 ms) and stopped loading, so Media3 threw StuckPlayerException after 4 s. Also the 10 s back buffer at 80 Mbps exceeds the budget. Not the zip, JoinedDataSource or TDLib.
- Fix d702ad4 + 759ad33 (cherry-picked): AheadFirstLoadControl (loads past the target while under 4 s is buffered ahead, up to 3/5 of the heap, 2x target on low-RAM; playback starts on time buffered), honest message player.error_memory at the ceiling, bytes read count as the opening arriving. 9 Robolectric tests.
- Verified by me on the Poco F5 (main build): Colony 4 of 4 starts with a far seek, 0 stuck errors; Avengers and a 1080p control play, Watch kept_up 0 dropped.
- Open: TV hero Play for Colony opens a lone piece (.zip.002) and says "Part 1 of 18 is not in this chat" (worker D). Colony will likely show error_memory on the 1 GB stick (40 MB ceiling). Steady state on the phone about 150 MB of a 256 MB heap; off-cache line about 3.5 MB/s against 10.2 MB/s video so it rebuffers, no message for that yet.

## Progress 2026-11-14 (4): TV hero and piece completion
- Cause (worker D, confirmed from the stick's home-cache.json): Home reads only a window of a chat's newest messages; Colony pieces 002..018 were joined without 001, the intent carried 17 pieces, and findPieces skipped the chat search because the list was non-empty ("Part 1 of 18 is not in this chat").
- Fix 41e13fb + 5b9811a (cherry-picked): SplitPieces.lookUp completes an incomplete set (known pieces, openSets, missingFrom); Android findPieces and desktop joinedPieces use it; HomeViewModel completes cut sets for chat rows and Recently added (3 sets per read, 10 min memory) so hero, Continue and rows play the joined item.
- Full suites on merged main: core 2148, ui 214, desktop 511, app 452 tests, 0 failures; :core:jsNodeTest passes.
- Verified by me: TV hero Play for Colony opens the joined .zip.001 and shows "This video is 2160p, which this device's decoder can't manage. A 1080p copy will play." (correct: the Mi stick cannot decode 4K HEVC 10-bit). Phone: Colony plays again on merged main with a far seek. Not re-verified by me on the TV: Avengers after this merge (worker D did).
- Open: no message yet for a line slower than the stream (rebuffering); nothing pushed (about 20 local commits on main).
