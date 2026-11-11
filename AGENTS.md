# Working on TMPlayer

The rules for anyone, person or coding agent, changing this repository. Agents read this file
directly (Codex, Cursor, Copilot, Windsurf) or import it (`CLAUDE.md`, `GEMINI.md`), so it is the
only copy: edit it here, never in those. The site has its own rules in
[site/AGENTS.md](site/AGENTS.md), loaded when working under `site/`.

Where the rest lives, read when the task touches it:

| Topic | File |
| --- | --- |
| Building, JDK, credentials, version pins, test devices | [docs/BUILDING.md](docs/BUILDING.md) |
| Cutting a release | [docs/RELEASING.md](docs/RELEASING.md) |
| How the code is laid out | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| Stores, package managers, Telegram's terms | [docs/distribution/README.md](docs/distribution/README.md) |
| The site: how to run it, add a page, a string or a language | [site/README.md](site/README.md) |
| Ground rules, style, translations | [CONTRIBUTING.md](CONTRIBUTING.md) |
| Telegram's terms, store rules, what to check on a sign in/manifest/updater change | [docs/agents/telegram-terms.md](docs/agents/telegram-terms.md) |
| Screenshots (WebP, quality 88, convert command) | [docs/agents/screenshots.md](docs/agents/screenshots.md) |
| Onboarding pictures and tour badges | [docs/agents/onboarding-pictures.md](docs/agents/onboarding-pictures.md) |
| Branches and worktrees tidy-up | [docs/agents/branches.md](docs/agents/branches.md) |

## Token budget and publishing

Locked by the user (2026-11-02); the global `~/.claude/CLAUDE.md` wins over anything looser here or
asked in a session, and `git push`, `gh release`, `gh workflow run` and `wrangler deploy` are denied
at user level. In short: short sessions with a Progress / handoff section in the plan file and a
clear point after each phase; no subagent unless the work is noisy (max two, cheapest model); grep
then line-range reads; short answers; the user runs all publishing, so commit locally and print the
exact commands with what each does and what to check.

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
  totals where the user picks several things.
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

## Writing

No em dashes or en dashes, anywhere: comments, KDoc, commit messages, UI strings, site copy. Write
the sentence with a colon, a comma or a full stop instead. Before committing:

```sh
grep -rInP '\x{2014}|\x{2013}' --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle --exclude-dir=node_modules .
```

Public copy (posts, store text, release notes, the site) describes TMPlayer as a player for the
videos already in your own Telegram chats: "video", not "film" or "movie", no real titles, never
"uploader", "movie channel" or "watch any movie", and posts link tmplayer.org/legal (2026-10-15, so
nothing reads like a piracy tool).
