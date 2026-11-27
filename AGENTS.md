# Working on TMPlayer

The rules for anyone, person or coding agent, changing this repository. Agents read this file
directly (Codex, Cursor, Copilot, Windsurf) or import it (`CLAUDE.md`, `GEMINI.md`), so it is the
only copy: edit it here, never in those. Folders have their own rules (see below).

Where the rest lives, read when the task touches it:

| Topic | File |
| --- | --- |
| Building, JDK, credentials, version pins, test devices | [docs/BUILDING.md](docs/BUILDING.md) |
| Cutting a release | [docs/RELEASING.md](docs/RELEASING.md) |
| What each CI workflow runs on and what publishes or deploys, reading a red run | [docs/CI.md](docs/CI.md) |
| iPhone and iPad: the port, Mac setup, tests, Linux checks, apple-v releases | [docs/IOS.md](docs/IOS.md) |
| How the code is laid out | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| Stores, package managers, Telegram's terms | [docs/distribution/README.md](docs/distribution/README.md) |
| The site: how to run it, add a page, a string or a language | [site/README.md](site/README.md) |
| Ground rules, style, translations | [CONTRIBUTING.md](CONTRIBUTING.md) |
| Telegram's terms, store rules, what to check on a sign in/manifest/updater change | [docs/agents/telegram-terms.md](docs/agents/telegram-terms.md) |
| Screenshots (WebP, quality 88, convert command) | [docs/agents/screenshots.md](docs/agents/screenshots.md) |
| Onboarding pictures and tour badges | [docs/agents/onboarding-pictures.md](docs/agents/onboarding-pictures.md) |
| Branches and worktrees tidy-up | [docs/agents/branches.md](docs/agents/branches.md) |
| Multi-account profiles: what is per account, the single live client, switching | [docs/agents/multi-account.md](docs/agents/multi-account.md) |
| Local checks before a push, what GitHub CI still runs, nightly | [docs/agents/CI-LOCAL-PLAN.md](docs/agents/CI-LOCAL-PLAN.md) |
| Round 3: clean-room local run, parallel and pruned tests, `main` stays green (plan) | [docs/agents/CI-FAST-PLAN.md](docs/agents/CI-FAST-PLAN.md) |
| What each remote key means on TV, per context (read before binding a key) | [docs/agents/tv-keys.md](docs/agents/tv-keys.md) |

## Token budget and publishing

Locked by the user (2026-11-02); the global `~/.claude/CLAUDE.md` wins over anything looser here or
asked in a session, and `git push`, `gh release`, `gh workflow run` and `wrangler deploy` are denied
at user level. In short: short sessions with a Progress / handoff section in the plan file and a
clear point after each phase; no subagent unless the work is noisy (max two, cheapest model); grep
then line-range reads; short answers; the user runs all publishing, so commit locally and print the
exact commands with what each does and what to check.

## Rules for building and fixing

Before a feature or a fix, read [docs/agents/engineering-rules.md](docs/agents/engineering-rules.md):
look first, shared parts first, TV is a platform, plan every platform and edge case, handle every
input from outside as untrusted, every async job has a handler, every parser has a garbage test.

## Where the code lives

Each folder with its own rules has an `AGENTS.md`: [core](core/AGENTS.md), [ui](ui/AGENTS.md),
[app](app/AGENTS.md), [desktop](desktop/AGENTS.md), [webapp](webapp/AGENTS.md),
[workers](workers/AGENTS.md), [site](site/AGENTS.md). Open the one for the folder you edit; skip the rest.

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
