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
catalog test fails otherwise) and is translated into the other 20 catalogs before a release.

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
videos already in your own Telegram chats: "video", not "film" or "movie", no real titles.

## Telegram's terms and the stores

TMPlayer stays a client Telegram and the stores have no reason to remove. Check these whenever a
change touches sign in, what is shown from a chat, the manifest, the updater or any store text
(details and sources in `docs/distribution/telegram-apps.md` and `docs/distribution/play.md`):

- **Telegram API terms.** The app's own `api_id` from secrets, never a sample one. No "Telegram"
  in any app or store title and no paper plane logo; "unofficial, not affiliated with Telegram" in
  every description. Telegram's sponsored messages stay shown in channels. Nothing is done on the
  user's behalf without them knowing, and read state is never faked or hidden.
- **Telegram's own restrictions stay on.** Never set TDLib's `ignore_platform_restrictions`, and
  keep `MediaMapper` skipping messages with a restriction reason.
- **A store build has no updater.** Google Play and Amazon get a file without the in-app updater
  and without `REQUEST_INSTALL_PACKAGES`, as the F-Droid build already is; only the GitHub APK
  updates itself.
- **Google Play** wants API 36 as the target for new apps and updates since 2026-08-31 (the app
  targets 35), a declaration for each foreground service type, an AAB, and a demo reviewers can
  use without a Telegram code. It is set aside while the budget is $0 (working plan, Phase G).
- **Amazon** is Fire OS only: the Appstore left other phones on 2025-08-20, and Vega OS sticks run
  neither the APK nor sideloaded apps.
- **Developer verification.** Since 2026-09-30 certified devices in Brazil, Indonesia, Singapore
  and Thailand install only apps from registered developers, everywhere in 2027. Registering
  `com.tmplayer` and the release key (U07, U08, CP15) is what keeps the GitHub APK installable.

## Screenshots

Every screenshot in the repository is a WebP, and stays one. Convert before committing:

```sh
magick shot.png -define webp:method=6 -define webp:sharp-yuv=true -quality 88 docs/screenshots/name.webp
```

Quality 88 is settled (about 39 to 44 dB PSNR on this app's screens, so the TV's small text stays
sharp, at 60 to 90 per cent less than the PNG). Do not commit the PNG beside it and do not lower
the quality. `docs/screenshots/` is the README's; the site's are in `site/public/screenshots/`
(see [site/AGENTS.md](site/AGENTS.md) for their variants and references). Two images stay PNG on
purpose: `site/public/og.png` (link preview crawlers cannot be relied on to decode WebP) and
`site/public/icon-512.png` (the PWA icon).

## Onboarding pictures

The setup's answers and the quick tour's pages (CP67, CP69) show cropped, marked screenshots of the promo
build: one spec each in `scripts/annotations/` (`setup-android-*`, `setup-desktop-*`,
`tour-android-<page>[-dark|-tv]`, `tour-desktop-<page>-light|dark`), drawn by
`scripts/annotate-shot.py` into `app/src/main/res/drawable-nodpi/` and
`ui/src/jvmMain/resources/onboarding/`. A tour spec marks each thing its page talks about with one
`circle`, in the order of the page's numbered lines; the app draws the numbers over the picture,
so no number or word goes into an image. After retaking or re-marking a tour shot, run
`scripts/tour-badges.py` (`--check` says whether it is due).

## Branches and worktrees

`main` is the only long lived branch. Work done in a worktree or on a side branch (an agent's
`worktree-agent-*` branch, a `diag/*` test build, a branch held back until a release) is tidied
away as soon as it is merged or no longer needed:

- After merging: `git worktree remove <path>`, `git branch -d <branch>`, and if it was pushed,
  `gh api -X DELETE repos/dracu-lah/TMPlayer/git/refs/heads/<branch>`. Then `git fetch --prune`.
- A branch kept on purpose (such as `site-after-<version>`, merged on release day) is named in
  `private/PLAN.md` (the working notes, kept off GitHub) with the day it goes, and deleted the
  same way once merged.
- Before deleting a branch that was never merged, say so, list its commit hashes in the report,
  and ask first.
- Check with `git worktree list`, `git branch` and
  `gh api repos/dracu-lah/TMPlayer/branches --jq '.[].name'` that only `main` (and any branch
  kept on purpose) is left.
