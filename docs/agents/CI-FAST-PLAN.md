# CI round 3: fast local checks, a green GitHub

Written 2026-11-26. Status: plan, nothing built yet. Builds on [CI-LOCAL-PLAN.md](CI-LOCAL-PLAN.md)
(P1 gate, P2 no CI on push, P3 Linux containers, all built); this round fixes why the gate and GitHub
still disagree and why both are slow.

## Start here (after /clear)

1. Read this file, then `docs/agents/CI-LOCAL-PLAN.md` (what is already built: `scripts/ci-local.sh`,
   `.githooks/pre-push`, `scripts/linux-local.sh`, the nightly workflow).
2. State of the repo that matters: see "Progress / handoff" at the bottom (the 1.29.0 release is
   blocked on red CI; its fixes are merged locally but not pushed past `1f7022f`).
3. Machine: 12 cores, 30 GB RAM (about 14 GB busy in a normal day). `org.gradle.jvmargs=-Xmx4g`,
   `org.gradle.parallel=true`, `org.gradle.caching=true` are set; no Gradle worker cap, no
   `maxParallelForks`, no coverage or mutation tool in the build.
4. Run it as a plan run: one worker per task in its own git worktree, at most 3 at once and only
   for tasks whose `files:` do not overlap, tasks in the order of "Order and cost". Per the standing
   rules a worker commits locally, updates the Progress section and replies in under 150 words. A
   check failing twice blocks the task; go on with the others.
5. Before anything touches the test suite, F0 (the audit) writes its table first: nothing is deleted
   before the table is reviewed.
6. Never `git push` a task's commits to GitHub before F1 exists, except what the 1.29.0 release needs.

## Research summary (WebSearch, 2026-11-26)

What comparable projects found, and where this plan uses it:

- **AI written tests pile up and repeat themselves.** Coverage based de-duplication on one 54 test
  AI generated suite kept 15 tests with the same coverage and cut CI from about 10 min to 3 (a
  vendor adjacent write up, one anecdote: [DEV Community](https://dev.to/kirankotari/how-i-reduced-my-test-suite-by-72-using-testiq-3ff2)).
  Coverage alone can delete a test that checks a different edge case, so mutation kills are the
  arbiter: a test whose killed mutants are a subset of another's is redundant
  ([high coverage zero signal](https://origo.prose.sh/high-coverage-zero-signal)). A paper on AI
  test generation (MIST-RL, [arXiv 2603.01409](https://arxiv.org/pdf/2603.01409)) found about the top
  quarter of generated tests delivered over 85 percent of the mutation score, the rest repeat
  assertions. Atlassian found a generic "write tests to kill mutants" prompt made redundant tests,
  and that re-running mutation testing and checking the score really rose fixed it
  ([Atlassian](https://www.atlassian.com/blog/developer/automating-mutation-coverage-with-ai)). A
  Medium post names the hidden cost of AI tests (trivial, shallow, "value exists" tests) and asks for
  testing standards in the project guidelines ([ProAndroidDev](https://proandroiddev.com/ai-generated-tests-the-hidden-cost-983e22743f70)).
  Used in: F0 (audit and prune), F12 (stop it coming back).
- **Robolectric tests are often the redundant ones.** A Peloton case study found most Robolectric
  tests redundant with a few taking most of the time; removing them all cut p95 unit test time from 41
  to 26 min ([DPE talk](https://dpe.org/wp-content/uploads/2024/06/Ward-Bonnefond-and-Douglas-Crossley-Peloton-.pdf)).
  Separating pure JVM tests from Robolectric and running them in parallel saved about 40 percent
  ([DEV Community](https://dev.to/pranayairan/parallel-unit-tests-in-android-4oe5)). Used in: F0
  (move logic assertions out of Robolectric), F2.
- **Parallel forks with Robolectric have one known trap.** The android-all jar download races when
  `maxParallelForks` is above 1; the workaround is to warm the jar cache serially first, or use
  Robolectric's offline mode ([Bitrise forum](https://discuss.bitrise.io/t/robolectric-tests-have-become-flaky/26010),
  [offline mode post](https://hashnode.com/posts/using-robolectric-in-offline-mode-a-fix-for-gradle-parallel-execution/62cf7c9d466c6ad9ff5d80bb)).
  Peloton and Wealthfront also found that "as many forks as cores" runs out of memory on CI; half
  the cores is the common formula ([Wealthfront](https://eng.wealthfront.com/?p=4127)). Used in F2, F3.
- **Gradle has no built in "run only the affected tests".** Options: Develocity Predictive Test
  Selection (commercial, learns from history, vendor claims 35 to 70 percent, best late in the
  pipeline, [docs](https://docs.gradle.com/develocity/predictive-test-selection)); Dropbox's
  [AffectedModuleDetector](https://github.com/dropbox/AffectedModuleDetector/) (free, module level
  only: tests only modules that changed or depend on a changed module); the open source
  [tiatesting](https://github.com/mtgleeson/tiatesting) (test level from VCS changes). Used in F11.
- **Playwright: shards across machines, few workers per shard.** `--shard=i/n` splits statically, so
  uneven files leave runners idle; keep workers per shard at 2 to 4 on CI, because shards times
  workers beyond the cores raises flakiness ([Playwright docs](https://playwright.dev/docs/test-sharding),
  [Currents](https://currents.dev/posts/optimizing-test-runtime-playwright-sharding-vs-workers),
  [60 to 8 minutes write up](https://bug0.com/blog/playwright-test-sharding-guide)). Playwright's own
  `fullyParallel` and a `--last-failed` re-run help locally. The search found nothing useful on test
  pyramid trimming or flake quarantine for Playwright; F8 follows the repo's own practice.
- Searches returned nothing on Compose screenshot tools (Paparazzi, Roborazzi) for this stack; the
  repo's own picture tests are treated as in F4.

## Goal (the user's words, 2026-11-26)

Bring CI to the local machine so a failure is fixed fast, and GitHub is not red all the time.

Targets (measured, see P8):

| | Today | Target |
| --- | --- | --- |
| Pre-push gate, one module changed | about 70 min (full) | 15 min or less |
| Pre-push gate, everything | about 70 min | 30 min or less |
| GitHub CI, all jobs, wall time | 45 min (Web chromium), 40 (app), 25 (desktop x3) | 20 min or less |
| A red run on `main` | most pushes since 2026-11-20 | none: `main` only moves to a green SHA |

## What the measurements say (2026-11-25 runs 45691822588 and 45691829607)

1. **Tests run one at a time on a 12 core machine.** `desktop` and `app` set no `maxParallelForks`.
   `app` also sets `forkEvery = 1` (a fresh JVM per class, kept on purpose: after many Robolectric
   render tests in one process Compose's frame clock stopped, CP101). 359 app classes took 36 min of
   test time in sequence; 206 desktop classes took 25 min.
2. **Playwright is one job per browser, 2 workers.** chromium 44 min, webkit 20 min plus 6 min of
   container setup, webkit-iphone 6 min. Slowest files: `scale.spec.ts` 7.7 min, `tv.spec.ts` 5.8,
   `screen-sweep.spec.ts` 5.2 (6.4 on webkit).
3. **The same tests run three to four times.** Linux gate, then Linux CI, then the same 1,069 desktop
   tests again on Windows and on macOS (21 to 26 min each), then again in the release run (129 min
   average per CI-LOCAL-PLAN).
4. **Picture making counts as testing.** `PromoShots` 1.6 min, `SeekPreviewShotsTest`,
   `LanguageShotsTest`, `SettingsLayoutShotsTest`, the `PhoneAudit*` classes (0.3 to 0.9 min each, 68
   screens at three fonts), 20 classes in all. They write pictures; some also assert.
5. **Why local green still meant GitHub red.** Each of these was found on 2026-11-25:

   | Cause | Example | Local gate did not see it because |
   | --- | --- | --- |
   | Desktop tests never ran in the gate | X11SideScroll, PlayerMouse, ScreenInvariants | the gate ran core, ui and app only (fixed in 5c2bd45) |
   | Credentials in `local.properties` | PhoneInvariants word-broken at font 2.0 | this machine has keys, CI has none |
   | Fonts | ScreenInvariants, bar cells | this machine has other fonts than CI's DejaVu; macOS has CJK ones |
   | No display (headless) | blank cursor threw `HeadlessException` | this machine has a display |
   | A slower runner | countdown, scroll waits, `scale.spec` 3 s budget | this machine is 12 cores |
   | A browser the gate does not run | webkit-only spec | the web group runs chromium only |
   | OS differences | Windows `\home\me`, macOS Cmd key | gate is Linux |
   | Network at test time | Robolectric `MavenArtifactFetcher` IOException, 57 class failures | this machine has the jars cached |

   So the cure is not "run more locally", it is "run locally in the same room CI uses".

## Principles

- **One clean room, same as CI.** Local checks run in a throwaway checkout with an SDK-only
  `local.properties`, `TZ=UTC`, `LANG=C.UTF-8`, DejaVu only, `-Djava.awt.headless=true`, offline
  Robolectric jars. A failure there is a failure on GitHub.
- **Parallel by default, serial only where a reason is written down.**
- **Fast set on every push, slow set nightly.** A test that only draws pictures is not a gate.
- **`main` is only ever a green SHA.** GitHub runs on a branch first; `main` moves by fast forward.
- **No loosened assertions to make CI pass.** A runner-speed allowance is one named multiplier in one
  place, not a new number in each spec.

## Tasks

Each header: `model:` (worker model and effort), `depends:`, `files:`. A task is done when its
"done when" holds and its commit is on `main`.

### F1: Clean-room local run (the biggest cause of red)
- model: opus high. depends: none. files: `scripts/ci-local.sh`, new `scripts/ci-clean-room.sh`,
  new `docs/agents/ci-clean-room.md`.
- Build `ci-clean-room.sh`: `git worktree add` a detached checkout of the commit being pushed (the
  hook today tests the checkout, not the commit, see `.githooks/pre-push`), write an SDK-only
  `local.properties`, set `TZ=UTC LANG=C.UTF-8 JAVA_TOOL_OPTIONS=-Djava.awt.headless=true`, point
  `FONTCONFIG_FILE` at a config that lists DejaVu only, run the Kotlin and web groups there, remove the
  worktree. Share the Gradle cache and the build cache with the main checkout so it stays warm.
- Run `xvfb-run` for desktop tests only where CI does (check `.github/actions` and `ci.yml`).
- Done when: on the commit before 4fa1897, the clean room reproduces `ScreenInvariantsTest`, the
  `PhoneInvariants*` word-broken failure and `PlayerMouseTest` locally (all three failed on CI only).
  Prove it by checking out that commit.

### F2: Parallel test forks
- model: sonnet high. depends: F1 (to prove no new hangs), F3 (jars fetched first), F0 (do not
  parallelise tests that are about to be deleted). files: `app/build.gradle.kts`,
  `desktop/build.gradle.kts`, `gradle.properties`.
- `app`: keep `forkEvery = 1`, add `maxParallelForks = (cores / 3).coerceIn(1, 4)` (3 GB heap each:
  4 forks is 12 GB). `desktop`: add `maxParallelForks` the same way; check
  `tmplayer.systemReducedMotion` and any shared temp or port the desktop tests use (grep for fixed
  ports and `File("/tmp`, `user.home`) and make them per fork.
- GitHub's Linux runner has 4 cores: use `min(forks, 2)` there through the same formula.
- Robolectric trap (Bitrise forum, offline-mode post): with more than one fork the android-all jar
  download races. F3 lands before F2 so the jars are fetched once, serially, before any fork starts.
- Done when: `:app:testDebugUnitTest` is at least 2.5x faster on this machine, `:desktop:test` at
  least 2x, three full runs in a row show no new failure and no exit 134 (the SIGABRT native graphics
  abort that `NativeJniWarmUp` prevents), and no test sits more than 10 min (the CP101 hang).

### F3: Robolectric offline, no network at test time
- model: sonnet medium. depends: none. files: `app/build.gradle.kts`,
  `.github/workflows/ci.yml`, `.github/workflows/release.yml`.
- Set `robolectric.offline=true` and `robolectric.dependency.dir` to a folder filled by one Gradle
  task (`robolectricJars`) that downloads the android-all jars once; cache that folder with
  `actions/cache` keyed on the SDK levels the tests use. A clean checkout and a cold runner then need
  no Maven at test time.
- Done when: a run with the network blocked (`podman run --network=none` or `unshare -n`) passes
  `:app:testDebugUnitTest --tests '*PhoneInvariants*'`, and the 57 `classMethod` failures of run
  45691822588 cannot recur.

### F4: Fast set and slow set (picture generators out of the gate)
- model: sonnet high. depends: none. files: the 20 classes (`PromoShots`, `*ShotsTest`, `PhoneAudit*`,
  `ScreenAuditShotsTest`, `SeekPreviewShotsTest`, `LanguageShotsTest`, `SettingsLayoutShotsTest`),
  `app/build.gradle.kts`, `desktop/build.gradle.kts`, `.github/workflows/nightly.yml`.
- Step 1, classify: for each of the 20, does it assert, or only write a picture? Output a table in
  the plan. A class that asserts is split: the assertion stays in the fast set, the picture goes to
  the slow set.
- Step 2: exactly the way `-Ptvux=true` already works, a `-Pshots=true` property that includes the
  picture classes; off by default (gate, push, PR), on in `nightly.yml` and in the batch gate before a
  release. `ScreenInvariantsTest` (5.1 min, a real check) stays in the fast set but is split per
  screen group so it parallelises (F2).
- Done when: the default `:desktop:test` and `:app:testDebugUnitTest` skip the picture classes, no
  assertion was lost (the table shows each), and the nightly still runs them.

### F5: Playwright: shard, parallel workers, one browser by default
- model: sonnet medium. depends: F1. files: `webapp/playwright.config.ts`,
  `.github/workflows/webapp.yml`, `scripts/ci-local.sh` (web group).
- GitHub: a matrix of `--shard=1/3`, `2/3`, `3/3` for chromium and for webkit; `workers` from CPU
  count (2 on a 4 core runner is about right, but locally use 6 to 8 of 12 cores); `blob` reporter
  merged at the end so one report exists. 44 min becomes about 15 and webkit about 9.
- Local gate: chromium sharded across containers or `--workers=8`; webkit and webkit-iphone only when
  `webapp/` changed engine-relevant code, otherwise left to GitHub. Today the local group is
  chromium only, which is why `profiles.spec` webkit slipped through (cheap fix: run the three
  webkit specs that exist for engine differences locally, `profiles`, `player`, `media-session`).
- `screen-sweep.spec.ts` (29 routes at 4 sizes, 5 to 6 min per browser) moves to nightly and to
  `workflow_dispatch`, with one fast smoke route per size kept in the default set.
- Done when: the gate's web group is 10 min or less on a `webapp/` change, 0 s on a change that does
  not touch `webapp/` or `core/` (it already skips), and the GitHub Web player run is 20 min or less.

### F6: Do not run everything on every OS
- model: opus high. depends: F1, F4. files: `.github/workflows/ci.yml`,
  `.github/actions/desktop-windows`, `.github/actions/desktop-macos`, `.github/actions/desktop-linux`,
  `desktop/build.gradle.kts`.
- Linux runs the full desktop suite. Windows and macOS run only the OS-specific classes: tag them
  (`@Tag("os")` or a source set or a package `com.tmplayer.desktop.os` plus `*Integration*`,
  `ShellBehindGate`, key handling, fonts, paths, `X11SideScroll`) and run `package + self-test`.
  Candidate to confirm class by class: which of the 1,069 tests depend on the OS at all.
- The 21 to 26 min on each OS is mostly the jpackage plus installer self-test; check how much is the
  test task by reading the step log before cutting.
- Done when: Windows and macOS CI jobs are 12 min or less and the list of OS-tagged classes is in
  `docs/agents/ci-clean-room.md` with why each is there.

### F7: `main` stays green: land on a branch first
- model: opus high. depends: F1, F6. files: new `scripts/land.sh`, `docs/agents/branches.md`,
  `.github/workflows/ci.yml` (trigger), `AGENTS.md` (one line).
- `scripts/land.sh`: push the current work to `ci/<date>-<sha>` (GitHub runs CI and Web player there
  for the OS-specific jobs), poll `gh run list` until done, and only on green fast-forward `main`
  (`git push origin <sha>:main`). On red it prints the failing job and test names from the log
  (the extractor used on 2026-11-25) and leaves `main` alone. `main` then never shows a red run.
- Branches `ci/*` are deleted by the user per the locked rules (no remote branch deletion by Claude):
  a weekly note lists stale ones. CI triggers on `ci/**` pushes and `workflow_dispatch`, not on
  `main` pushes.
- The nightly keeps running on `main`; a red nightly opens or updates one GitHub issue (title
  "Nightly red", with job and test names) instead of a red badge nobody reads, and closes it when
  green.
- Done when: a deliberate failing test pushed through `land.sh` leaves `main` untouched and prints
  the cause within the run's wall time, and a good push lands `main` with no red run on it.

### F8: Flakes and runner speed, once
- model: sonnet high. depends: F1, F2, F5. files: `webapp/e2e/helpers/*` (new `budget.ts`),
  `webapp/e2e/scale.spec.ts`, `webapp/e2e/home-defer.spec.ts`, the Kotlin timing tests.
- One `SLOW` factor (1 locally, 1.5 on a shared runner) read from `process.env.CI_SLOW` or the Gradle
  property, applied to wall-clock budgets only. A bounded-calls or cache-cap assertion never scales.
  This replaces the ad hoc `3 s -> 4 s` a worker put in `scale.spec.ts` on 2026-11-25 (commit 599b5ef
  in worktree `agent-a706db63216c5fd5d`, not merged).
- Tests that wait on time get a state wait (`expect.poll`, `waitUntil`), the same fix the 2026-11-25
  workers made for the countdown and far-scroll specs. A weekly list of tests that needed a retry
  (`--reporter=json`, count `flaky`) goes to `private/logs`; a test flaky twice in a week is fixed or
  quarantined with a dated reason, not left to retry.
- Done when: ten clean-room runs in a row have zero retries.

### F9: Release run reuses the proof
- model: sonnet medium. depends: F7. files: `.github/workflows/release.yml`.
- The release already skips tests when a passing CI run exists on an ancestor. With `land.sh` the
  landed SHA has a passing run by construction, so the release job should find it, skip the unit
  tests, and spend its time on building and signing only. Check the skip's condition still matches
  the new trigger (`ci/**` branch runs, not `main` runs).
- Done when: a tag on a landed SHA runs no unit tests and takes about the build time alone (target
  under 40 min versus the 129 min average).

### F10: Measure and keep it honest
- model: haiku low. depends: all. files: `scripts/ci-stats.sh` (new), this file.
- `scripts/ci-stats.sh`: last 20 runs per workflow, wall time, failures, retries, cancelled; prints
  the table of the Goal section. Run after each task; the plan's targets are the test.
- Done when: the Goal table has a "now" column filled from real runs and every target is met or the
  gap is written down.

### F0: Audit and prune unwanted tests (first, before any speed work)
- model: opus high for the table, sonnet medium for the deletions. depends: none. files: new
  `docs/agents/test-audit.md`, then the test files it names.
- Why first: speed work on tests that should not exist is wasted, and AI assisted work (this repo had
  1,483 commits in three weeks, 2,096 app tests in 359 classes, 1,069 desktop tests in 206 classes)
  accumulates repeats, shallow tests and picture generators counted as tests.
- Step 1, measure, no deleting. Add a JaCoCo or Kover report per module (`:core`, `:ui`, `:app`,
  `:desktop`) and write one table: class, tests, seconds (from `build/test-results/*.xml`), lines
  covered, lines covered that no other class covers, asserts yes or no, category. Categories:
  (a) pure logic, (b) Robolectric or Compose render, (c) picture generator, (d) guard for a named
  bug or rule, (e) shallow (asserts only that something is non null, exists, or does not throw).
- Step 2, mutation check on the candidates only: run Pitest (JVM, `info.solidsoft.pitest` Gradle
  plugin) on `:core` and `:ui` jvm classes the table marks as candidates, and Stryker on a few
  `webapp/src` files with a `*.spec.ts` unit file, to see which tests kill mutants no other test
  kills. Do not run mutation testing on Robolectric or desktop render tests (too slow, little value);
  judge those by the table and by what they assert.
- Step 3, the decision rules (write them at the top of the audit):
  - Delete a class only if: it covers no line another class does not, and kills no mutant another
    kills (where mutation ran), and it is not (d), and a reviewer reads its asserts and agrees.
  - Convert, not delete, where a Robolectric test asserts pure logic (Peloton's finding): move the
    assertion to a plain JVM test of the function, keep one Robolectric smoke test of the screen.
  - Merge near duplicates (the same screen asserted at three font sizes in three classes) into one
    parameterised test.
  - Never delete without a trace: each removal is one commit named `Test prune: <class>, why`.
- Protected, never pruned without the user's word: the tests AGENTS.md requires (focus and D-pad
  tests with `performKeyInput`, `GarbageInputTest`, `HomeScaleTest` and the other scale tests, the
  i18n catalog test, the dash grep), anything named for a bug id (HX, CP, WP, WB prefixes in the
  name or KDoc), `ScreenInvariantsTest` and `PhoneInvariants*`, the release and version tests
  (`ReleaseVersionTest`, `VersionCodeTest`, `SelfUpdateTest`).
- Done when: `docs/agents/test-audit.md` lists every class with a verdict, the deletions and
  conversions are committed one per class, the suites are green, and the measured test count and
  seconds before and after are in the audit. Target, not a promise: 20 to 30 percent fewer test
  seconds from deletion and conversion alone, before any parallelism.

### F11: Use every core to compile and test
- model: sonnet high. depends: F2 (shares `gradle.properties`, `app/build.gradle.kts`). files:
  `gradle.properties`, `scripts/ci-local.sh`, `settings.gradle.kts` (plugins), `docs/BUILDING.md`.
- Gradle: the work is limited by `org.gradle.workers.max` (defaults to the cores, 12 here) and by
  memory, not by the cores. Set `org.gradle.jvmargs=-Xmx6g` (the daemon holds Kotlin compile for
  four modules), `kotlin.daemon.jvmargs=-Xmx4g`, `kotlin.compiler.execution.strategy=in-process`
  (one JVM, fewer startups) or `daemon` (keep what measures faster; measure both on a cold and a
  warm build), `org.gradle.configuration-cache=true` (try it; if a plugin breaks it, list which),
  `org.gradle.vfs.watch=true`, and keep the build cache on.
- `scripts/ci-local.sh` today picks `WORKERS` for `--max-workers` (memory note: size builds to
  machine load). Make that one formula in one place: `workers = min(cores, free_memory_gb / 2.5)`,
  `test_forks = min(4, workers / 3)`, `playwright_workers = min(8, cores - 2)`, all printed at the
  start. On an idle machine this uses all 12 cores; with other builds running it backs off.
- Module level test selection (F11b, optional): add the free Dropbox AffectedModuleDetector plugin
  so a change that only touches `site/` or `webapp/` runs no Kotlin test and a change to `core`
  runs `core` and its dependants. The gate's `has` globs already approximate this for paths; the
  plugin does it by the real dependency graph. Try Develocity Predictive Test Selection only if the
  user wants a paid tool (not planned); tiatesting only after F0, to check it picks the right
  tests, because a wrong selection hides a failure.
- Compile once: the clean room (F1) and the normal checkout share `~/.gradle` and the build cache,
  so a compile in one is a cache hit in the other; check this with `--scan` free output
  (`--info` cache hit lines) and write the hit rate in the docs.
- Done when: a no-change `:desktop:test :app:testDebugUnitTest :core:jvmTest :ui:jvmTest` is up to
  date in under a minute, a cold build uses at least 9 of 12 cores for most of its length (watch
  `top` or `/proc/stat`), and the numbers are in `docs/BUILDING.md`.

### F12: Keep new bloat out (AI era rules for tests)
- model: sonnet medium. depends: F0. files: `AGENTS.md`, `CONTRIBUTING.md`, new
  `scripts/test-budget.py`, `scripts/ci-local.sh`.
- Rules in AGENTS.md (short, in "Building a feature"): a test names the behaviour it protects; a
  test that cannot fail when the code is wrong is not added (no `assertNotNull` only, no "does not
  throw" only, no snapshot with no assertion); a bug fix adds one regression test that fails
  without the fix; no new picture generator in the default test set (it goes under `-Pshots`); a
  Robolectric test only where the Android or Compose runtime is the thing under test, else a JVM test.
- `scripts/test-budget.py` reads `build/test-results/**/*.xml` after a run and fails the gate when:
  a single test takes over 30 s without a `@Slow`-style tag, a class takes over 90 s in the default
  set, the total test seconds grew more than 10 percent against the number stored in
  `private/logs/test-budget.json`, or a class has zero assertions (Kotlin: scan for `assert`,
  `assertEquals`, `assertIs`, `assertThat`, `assertTrue`, `assertIsDisplayed`, `expect(`;
  Playwright: `expect(`).
- When an agent writes tests for a new feature, its worker brief ends with "tests that fail when
  the code is wrong, none that only exercise it", and the reviewer subagent checks that in the diff.
- Done when: the script runs in the gate, the AGENTS.md rules are committed, and a deliberately empty
  test is rejected by it.

## Order and cost

1. F0 and F1 first, in parallel (different files): F0 removes work, F1 makes every later number
   trustworthy and is what stops red on GitHub.
2. F3 (Robolectric offline), then F2 and F11 (parallel forks, all cores): the speed.
3. F5 (Playwright shards) alongside, different files.
4. F4 and F6: move picture generators out, trim the OS jobs.
5. F12 (rules against new bloat) once F0's table exists.
6. F7 (land on a branch, `main` stays green) last of the builds, because it changes how pushes work.
7. F8, F9, F10 to close.

Rough effort: F0, F1, F6 and F7 are the careful ones (Opus). The rest are mechanical (Sonnet).
Three workers at once at most, each in its own worktree. Expected total after all: the Goal table
above; F0 plus F2 plus F11 plus F5 carry most of it.

## Decisions made by Claude (overrule in review)

- Keep `forkEvery = 1`. Parallel forks fix the speed without bringing back CP101.
- Windows and macOS keep running on GitHub (no hosted alternative, per CI-LOCAL-PLAN round 2); they
  run fewer tests, not none.
- `main` gets a landing script, not branch protection (the repo is personal and the user pushes
  directly; a script is a habit, protection is a wall).
- Mutation testing (Pitest, Stryker) is a one off audit tool in F0, not a CI job: it is slow and
  only needed to decide what to delete. F12's budget script is what stays in the gate.
- Pictures generated by tests go to the nightly. If a picture is part of a release (store
  screenshots), the release's batch gate runs `-Pshots=true`.

## Edge cases and caveats

- A parallel fork run needs about 12 GB for `app` (4 x 3 GB) on top of Gradle's daemon; on a
  smaller machine the formula gives fewer forks. Do not run a second Gradle build beside it.
- The clean room cannot reproduce macOS fonts or Windows paths. Those stay on GitHub, which is why
  F6 keeps the OS-tagged classes and F7 lands on a branch first.
- A cold clean-room checkout costs a few minutes the first time; the shared build cache and Gradle
  cache make later runs warm.
- Sharding makes a failing spec's log harder to find: the merged blob report and the failure
  extractor in `land.sh` cover that.
- F4 can hide a regression only a picture test would catch; the nightly and the release gate still run
  them, so it is found within a day, not never.

## Progress / handoff

- 2026-11-26: plan written. State of the 1.29.0 release at this point: `main` at cacb8e2 locally,
  GitHub's `main` at 1f7022f, nothing newer pushed. CI and Web player runs on 1f7022f were cancelled
  by the user's request after the second CI round (desktop and web still red: NavBarAccountTest,
  ScreenInvariantsTest on three OSes, nine chromium specs). Fixes for the desktop ones are merged
  locally (f731408, cacb8e2, fc24c7d); the web worker's four commits are unmerged on branch
  `worktree-agent-a706db63216c5fd5d` (599b5ef scale, f599967 toasts, e3086ca TV rail, 7422c1c Home
  deferral). Next: the user decides whether to finish 1.29.0 first or start F0 and F1.
- To finish 1.29.0 (still open): merge or drop the web worker's four commits, run the web specs
  that were red (home-defer, scale, toasts, tv-settings-rail) in the podman image, push through the
  gate, dispatch `CI` and `Web player` on the pushed head, then the user tags `v1.29.0` with
  `private/release-notes-1.29.0.txt`. Already done for the release: store changelog
  `1290099.txt` (3b829e2), `LOG_UPLOAD_URL` in `release.yml` (eb49f0d) and set as a repository
  secret, log inbox worker live at `https://tmplayer-log-inbox.dracu.workers.dev/v1/report`.
  User-only still: the tag, the download mirror's Worker deploy, the production web deploy.
