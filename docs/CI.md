# CI and deploys: what runs when, and what publishes

For humans first, agents second. Last checked 2026-11-27. If you change a workflow's trigger or what
it publishes, change this file in the same commit.

## Naming rule

`<surface>-<verb>.yml`. `check` means it tests and builds and publishes nothing. `release` or
`deploy` means it publishes. Files that only support others are named after what they do.

## At a glance

| Workflow file | Runs when | What it does | Publishes or deploys | Run by hand |
| --- | --- | --- | --- | --- |
| `android-check.yml` | pull request touching the build, manual, nightly | Unit tests of core, ui, app; debug APKs | Nothing (14 day artifacts) | `gh workflow run android-check.yml` |
| `desktop-check.yml` | pull request touching the build, manual, nightly | Packages the desktop app on Linux and Windows and self-tests it (macOS: manual and nightly only) | Nothing (14 day artifacts) | `gh workflow run desktop-check.yml` |
| `webapp.yml` | pull request touching `webapp/**` or `core/**`, manual | Build, then e2e in chromium, webkit, webkit-iphone | **Cloudflare Pages, only on a manual run with `deploy` = preview or production** | `gh workflow run webapp.yml -f deploy=none` |
| `apple-ci.yml` | pull request touching iOS paths, manual, nightly | Kotlin compile for iOS (Linux); Xcode build and Swift tests only when the variable `APPLE_MAC_CI` is `true` | Nothing | `gh workflow run apple-ci.yml` |
| `release.yml` | **a tag `v*`** | Builds Android, desktop (3 OS), TV; then the publish job | **GitHub release, update feed and changelog commit to main, AUR, IndexNow, Telegram post, winget** | Push a tag (user only, see docs/RELEASING.md) |
| `apple-release.yml` | **a tag `apple-v*`**, manual (dry run by default) | Builds the IPA | **GitHub release, AltStore `source.json` (push or PR), Telegram post** | `gh workflow run apple-release.yml` (dry run) |
| `style.yml` | push to main, pull request, manual | No em or en dashes; site checks. Seconds | Nothing | `gh workflow run style.yml` |
| `download-mirror.yml` | pull request touching `workers/download-mirror/**` (dry run), manual | Validates the Worker; manual run deploys it | **Cloudflare Worker, manual only** | `gh workflow run download-mirror.yml` |
| `announce.yml` | manual | Posts a message to the Telegram channel | **Telegram** | `gh workflow run announce.yml -f text=...` |
| `test-build.yml` | manual | macOS DMG with secrets (release environment gate) | Artifact only | `gh workflow run test-build.yml -f version=...` |
| `nightly.yml` | cron 02:17 UTC | If `main` moved since the last green Android check, dispatches android-check, desktop-check, webapp, apple-ci | Nothing (it never deploys) | `gh workflow run nightly.yml` |
| `site-rebuild.yml` | **cron 05:17 UTC every day**, manual | POSTs the Vercel deploy hook | **tmplayer.org rebuild** (ends green if the secret is missing) | `gh workflow run site-rebuild.yml` |
| `indexnow.yml` | **push to main touching `site/**`** | Waits up to 20 min for Vercel's commit status, then pings Bing | **IndexNow ping** | none |

## Things that happen without anyone clicking

These are easy to miss because they are not in a file called "deploy":

- **Vercel's own git integration** deploys tmplayer.org on every push to `main`. It lives in the
  Vercel dashboard, not in this repo. `site-rebuild.yml` also rebuilds the site every day.
- **`indexnow.yml`** pings search engines after a push to main that touches `site/**`.
- **`release.yml`'s publish job** commits the update feed and changelog to `main` and posts to
  Telegram, AUR and winget the moment a `v*` tag is pushed.
- **`nightly.yml`** starts four other workflows when `main` has new commits. It publishes nothing.
- **The pre-push hook** (`.githooks/pre-push`, via `core.hooksPath`) runs `scripts/ci-local.sh` on
  the maintainer's machine before any push. Web e2e inside it takes 30 to 35 minutes.
  `SKIP_LOCAL_CI=1 git push` skips it; use that only for changes that cannot affect a build, such as
  a workflow-only edit.

Nothing deploys the web player on a push: `webapp.yml` deploys only when started by hand with
`deploy=preview` or `deploy=production`.

## Deploying the web player

1. Push `main` (the pre-push gate must pass or be skipped on purpose). Do not chain the deploy
   after the push in one command: a failed push must stop the deploy.
2. `gh workflow run webapp.yml -f deploy=production` (or `preview`).
3. The run is Build, e2e x3, then Deploy to Cloudflare Pages. The Deploy job checks out only
   `webapp/functions`, `webapp/tmdb` and `webapp/src` (sparse), because wrangler bundles the poster
   proxy from them. A file the proxy imports from anywhere else needs adding there.
4. After it: open https://web.tmplayer.org and the `/tmdb/...` proxy answers JSON, not the page.

## Reading a red run

```sh
gh run list --limit 20
gh run view <id> --json jobs --jq '.jobs[]|select(.conclusion=="failure")|.name'
gh run view <id> --log-failed | sed 's/\x1b\[[0-9;]*m//g' | grep -E 'FAILED|Error|✘|exit code'
```

Kotlin lines starting `w:` are warnings, not the cause.

## Known causes of red, and what was done (2026-11)

| Symptom | Cause | State |
| --- | --- | --- |
| Web deploy: `Could not resolve ../src/plain.ts` | Sparse checkout lacked `webapp/src` | Fixed in the workflow |
| Web e2e: 300+ failures, `ERR_CONNECTION_REFUSED` | The Vite dev server ran out of memory after half an hour | Server gets `--max-old-space-size=4096` and a start timeout |
| Web e2e `home-defer.spec.ts` counts over the limit | Timing tests that need an idle machine | Tagged `@slow`, run with `pnpm e2e:full` |
| Android unit tests: a different test fails each run | Compose in Robolectric asserted right after an async write | Four tests now wait for the real end state; if it returns, add retries and shard (see below) |
| Apple release: "not permitted to create pull requests" | A repo setting | **User only**: Settings, Actions, General, Workflow permissions, "Allow GitHub Actions to create and approve pull requests", or set the repository variable `APPLE_SOURCE_PUSH=true` |
| iOS archive failed on `SIZE_MAX` | A Kotlin name that is a C macro | Fixed (renamed) |

Still open: the Android job runs 50 to 60 minutes because `forkEvery = 1` in `app/build.gradle.kts`
starts a JVM per test class. Sharding it across jobs, or `forkEvery = 25` after the screenshot tests
were removed, is the lever. `release.yml` skips its tests only when it finds a green
`android-check.yml` run for the tagged commit on `main`; nothing runs that on a push, so the nightly
run is the only one that can match.

## Web tests

- `pnpm -C webapp check` types, `pnpm -C webapp test:unit` runs all node tests (about 1,000, in
  seconds), `pnpm -C webapp e2e` runs Playwright without the `@slow` tests, `pnpm -C webapp
  e2e:full` runs everything.
- Tag a new e2e test `@slow` in its title when it counts real time (rest timers, flings, idle CPU).
- Specs write screenshots into `design/` only when a shots variable is set; keep it that way so a
  normal run leaves the tracked files alone.
