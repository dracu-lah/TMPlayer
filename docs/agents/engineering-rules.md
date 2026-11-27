# Engineering rules: method, features, error handling

Moved out of the root AGENTS.md (2026-11-27) so the root file stays short. Read this before building or
changing a feature. The rules are unchanged.

## Working method

Think before building (user, 2026-11-02), so one feature is not built twice or only for one platform:

- **Look first.** Grep for what already covers the feature and extend it. "Recently added" built
  beside "add more chats" had to be merged later.
- **Shared parts first.** A UI feature starts from the design system (tokens, shared components) and
  an existing library or tool where one saves work, not a hand-rolled copy.
- **TV is a platform, not a port.** The Android TV remote and focus are specified and tested with the
  feature; phone and desktop working does not mean TV works.
- **Machine checks before manual ones.** Focus/D-pad tests (`performKeyInput` with `Key.DirectionDown`
  and `assertIsFocused()`), preview screenshot tests per form factor, scale tests, then devices.

## Building a feature

Plan first, then build (user, 2026-10-22). Before writing code for a feature, list what it does on
each platform (phone, TV, desktop, web) and its edge cases. Then implement every edge case, or,
where one is too risky to handle in this pass, say so and suggest what to do instead. Never ship
the happy path alone. The kinds of cases that keep coming up:

- **Space and size.** Check free space before anything that writes (a download, saving files to a
  folder), say plainly how much is needed and how much is free, and offer what fits. Show running
  totals where the user picks several things. A browser cannot know the Downloads disk space
  (`storage.estimate()` is the site's quota, not the disk), so the web shows the size and relies on the
  failure summary (user decision 2026-11-25).
- **Several at once.** Select all (and Ctrl+A on the desktop), a selection that changes while the
  work runs, an empty selection, the same action run twice.
- **Interruptions.** Cancel, a full disk part way, a revoked folder permission, the app going to
  the background or the screen rotating, a file that is gone, still downloading or not allowed to
  be saved. No partial files left behind, and a summary of what worked, what was skipped and why.
- **Confirm what cannot be undone.** A dialog before a destructive or large step that says what
  will happen (removing TMPlayer's copy after saving it elsewhere, for example), off by default.
- **Each platform's limits.** The TV's remote and focus after the action, Android storage rules
  (Android 6.0 is the floor: guard newer APIs, see `LegacyApiCompat.kt`), the desktop's paths on
  Windows, Linux and macOS, a browser without the API needed.
- **Low-end devices.** Anything that runs by itself (paging ahead, refreshes, warm-ups) must stop
  on its own. A row that kept paging while nobody touched it held a 1 GB TV stick at 150% CPU and
  slowed every video start (2026-10-22), so measure idle CPU on the TV (`/proc/<pid>/stat`) after
  touching Home, lists or background work.
- **Scale.** Every list or feed is checked against a large account: 3,000 chats with folders and
  an archive, 100,000 videos, 20,000 in one chat. Its first content shows within 3 s (from the
  disk or the first answers), never after a whole chat list; its TDLib calls have a budget (chats
  read per refresh, a few at a time, pages limited to what is shown); its memory and disk caches
  are bounded. A FLOOD_WAIT is waited out by the call that got it while the rest go on, and
  retries stop by themselves. Reuse `SyntheticAccount` (`ui/src/jvmTest`, see `HomeScaleTest`):
  Home on a large account took hours on Windows (HX8, 2026-10-26).

Then test on the real devices (phone, TV) with small videos before calling it done.

New user-visible text goes into `core/src/commonMain/resources/i18n/en.json` (keys sorted; the
catalog test fails otherwise) and is translated into the other 21 catalogs before a release.

## Error handling

Nothing the app reads from outside is trusted, and nothing that fails takes the app down (user,
2026-10-26, HX10):

- **Outside data.** Telegram's answers, files on disk (settings, caches, backups, subtitles,
  storyboard maps) and the network (TMDB, the update feed) can be missing, cut short, the wrong
  type or absurdly large. No `!!`, `.first()`, `[0]`, `toInt()`, `require` or `error()` on them:
  use the `OrNull` forms, skip the bad entry and keep the rest.
- **Every async job has a handler.** App-wide scopes and view models go through `JobFailures`
  (core), activities through `guardedLifecycleScope`. A screen's `LaunchedEffect` or
  `scope.launch` that calls TDLib, the disk or the network catches its own failure and shows it.
- **A failure shows a way out.** A screen that fails says what happened with Try again and Send
  logs (the desktop window's `WindowRecovery` card is the last stop, the player ends in its status
  sheet, the web page in its Reload card), and never restarts itself into a loop.
- **Parsers have garbage tests.** A new parser of outside data gets its line in
  `GarbageInputTest` (core), which feeds every parser truncated, wrong-typed, huge and random input.
