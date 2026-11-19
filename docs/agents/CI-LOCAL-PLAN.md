# CI: run it locally first, keep GitHub for what only it can build

Written 2026-11-18. Status: plan, nothing built yet.

## Findings (last 100 runs, 2026-11-14 to 2026-11-18)

- The repo is public, so GitHub-hosted minutes (Linux, Windows, macOS) cost nothing. The pain is
  wall time and noise, not the bill.
- Wall time per run: Release 129 min average, CI 27 min, Web player 17 min. CI's Linux desktop job
  alone took 77 min on 2026-11-17.
- Of 23 CI runs, 10 failed and 5 were cancelled by the next push. Of 18 Web player runs, 7 failed.
- The failures were real code or test failures found late, all reproducible on this Linux machine:
  Kotlin for iOS did not compile (twice), a `:core:jvmTest` failure, `:app` unit tests in the
  release, and two Playwright e2e timeouts.
- No free host builds macOS or Windows faster than GitHub. Codeberg, GitLab free and similar are
  Linux only and slower than this machine (12 cores, 30 GB). So: Windows, macOS and iOS stay on
  GitHub; everything Linux moves to this machine first.

## Phases

### P1: one local gate (`scripts/ci-local.sh`) and a pre-push hook
- `done when`: `scripts/ci-local.sh` runs the same commands as CI for what the push touched, and
  `git push` runs it through `.githooks/pre-push` (`git config core.hooksPath .githooks`).
- Kotlin changed: `:core:jvmTest :ui:jvmTest`, `:app:testDebugUnitTest`, `:app:assembleDebug`, and
  the "Kotlin for iOS" compile from `apple-ci.yml` (the Linux job).
- Web player changed: install, typecheck, build, e2e in podman
  (`mcr.microsoft.com/playwright`, same version as `package.json`), so the browser matches CI.
- Always: the dash grep, the site checks from `style.yml`, `scripts/*` self-tests.
- Escape hatch `SKIP_LOCAL_CI=1`; prints the one-line reason for a skip. Keeps a log in `private/logs`.

### P2: shrink GitHub CI to what it alone can do
- Decided 2026-11-18: nothing runs on a push to `main`. GitHub CI runs on pull requests, a nightly
  `schedule` on `main`, `workflow_dispatch` and release tags. (Windows and macOS breakage therefore
  shows up the next night or on a manual dispatch; dispatch them before a release.) The Linux `Test and build`, Web e2e and Apple Linux job move to pull requests,
  `workflow_dispatch` and a nightly `schedule` on `main` (catches anything the local run missed).
- Release stays the same on tags, but reuses the local gate's "passed at this SHA" record the way it
  already reuses CI (see the test-skip step in `release.yml`).
- Drops about 3 of 4 jobs per push and nearly all cancelled runs.

### P3: Linux jobs in containers (podman, rootless)
- Natives: `scripts/build-tdlib-linux.sh` and `build-mpv-linux-2-35.sh` already target glibc 2.35.
  Wrap them in an `ubuntu:22.04` image, build once, cache by recipe hash under `~/.cache/tmplayer`.
- Linux desktop package and self-test (`.github/actions/desktop-linux`) in the same image, so the
  77-minute CI job becomes a local run with a warm cache.
- Not containerised: Android emulator (needs KVM and `-gpu host`, already run on the host).

### P4: releases (decided 2026-11-18: B, build locally and upload)
- A: self-hosted runner on this machine for the tag-only jobs (Android, Linux, TV, publish).
  Windows and macOS jobs stay on GitHub. Risk: a runner on a public repo must never run
  `pull_request` workflows; limit it to `release.yml` on tags, ephemeral, with the signing key
  still in the `release` environment.
- B: no runner. Build and sign the APK and Linux packages locally and upload them to the tag's
  draft release; Windows and macOS attach from GitHub; publish stays a user-only step.

### P5: measure
- After P2, list the last 20 runs again: target CI wall time under 15 min on a push, zero
  cancelled runs from a normal push, failures only for Windows or macOS specifics.

## Round 2 (2026-11-18): Windows in a VM, macOS and iOS, other hosts

### Hosted alternatives (researched, mostly vendor pages, so verify before relying)
- Blacksmith: Linux, Windows (beta) and macOS, but GitHub organizations only, not a personal repo.
- Namespace and WarpBuild: paid, with small free allowances; macOS on request.
- Forgejo/Gitea Actions, Woodpecker, Codeberg, GitLab free: free but Linux only, you bring Windows and macOS.
- Verdict: nothing hosted is both free and macOS/Windows for a personal public repo, and GitHub is
  already free for us. Moving would add cost or lose platforms. Decision: stay on GitHub for
  Windows, macOS and iOS, and cut how often they run. Revisit only if a job outgrows free limits.

### P6: Windows build and self-test locally (spike first)
- `dockur/windows` under podman with `/dev/kvm` (present here): a Windows VM that builds the MSI with
  jpackage + WiX, then runs `.github/actions/desktop-windows/selftest.ps1` and the msiexec
  install/upgrade/uninstall steps over a shared folder. Wine + WiX cannot do the install self-test.
- Spike done when: one command builds `TMPlayer-<v>-windows-x64.msi` and the self-test passes in the VM.
- Costs: about 40 GB of disk for the image, 4 cores and 8 GB RAM while it runs, a first-boot install
  of 20 to 40 min, then reused from a snapshot. Microsoft's eval Windows is for evaluation, so use it
  for pre-checks only; the released MSI keeps coming from GitHub's Windows runner until the spike proves
  the output identical.
- Windows only tests (path rules, HX8 on Windows) run in the same VM.

### macOS and iOS: stay on GitHub, minutes only on tag or dispatch
- Apple's licence allows macOS only on Apple hardware, so a macOS VM or container on this Linux
  machine is not planned (dockur/macos itself says the same). Not an option for the DMG or the IPA.
- What moves local: everything Linux can check for iOS already (Kotlin for iOS compile, the
  `apple-ci.yml` Linux job, scripts) is in P1. The Xcode build and the IPA stay on GitHub's macOS runner.
- Cut the macOS runs: CI desktop-macos and Apple CI macOS run on tags, `workflow_dispatch` and the
  nightly only, never on pull requests or pushes.

## Edge cases and caveats
- A local pass is not a clean-machine pass: the nightly run on GitHub is the check for that.
- The hook must not block a push of docs only, or take longer than the cloud run did.
- Rapid pushes to `main` stay cheap once the push run is only Windows and macOS.
- A runner on this machine competes with Gradle builds for RAM (25 of 30 GB in use now).

## Progress / handoff
- 2026-11-18: findings and plan written; decisions: releases built locally and uploaded (B), no CI on push to main. Next: P1.
- 2026-11-18 P1 done (uncommitted): `scripts/ci-local.sh` (groups style, kotlin, ios, web, site) and
  `.githooks/pre-push` (`git config core.hooksPath .githooks`, already set on this machine; a fresh
  clone runs it once). The web group runs the Playwright e2e in podman in CI's own image.
- 2026-11-18 P2 done (uncommitted): `ci.yml`, `webapp.yml`, `apple-ci.yml` lost their `push` trigger
  (pull requests and dispatch remain; macOS desktop skips pull requests); new `nightly.yml` dispatches
  all three at 02:17 UTC when main had commits in the last 26 hours. Style, IndexNow and the small
  site workflows still run on push (under a minute each). `release.yml` still finds a passing CI run
  on an ancestor through the nightly or a manual dispatch; without one it runs the unit tests itself.
  Web deploys were already manual.
- 2026-11-18 P3 built: `scripts/linux-local.sh` (`--dry-run`, `--natives-only`, `--no-tests`,
  `--adopt`, `--version`) and the opt-in `scripts/ci-local.sh linux` group (not in the changed-paths
  list or `--all`). Natives are cached in `~/.cache/tmplayer/linux-natives/<recipe hash>`, the hash
  over the same files as the action's cache key; a miss runs the two podman build scripts, then the
  PINS.txt check. Packaging runs on the host with a JDK 21 found in `~/.jdks`, output in `dist/`
  (now ignored).
  - Proven: `bash -n`, shellcheck, dry runs; a real `--natives-only --adopt` run took the pinned
    files from `~/.cache/tmplayer-natives` into the cache and a second run restored them.
  - Not proven: a cold native build through the script (the scripts themselves built fine on
    2026-11-18) and the packaging half: this host lacks `zsyncmake` (`sudo dnf install zsync`, the
    user's step), so no real package or plain Ubuntu 22.04 run yet. Next: install zsync, run
    `scripts/linux-local.sh`, compare its wall time with CI's 77 min.
- 2026-11-18 P6 spike built, not booted: `scripts/windows-vm/` (`up.sh`, `build-msi.sh`, `down.sh`,
  `README.md`, `oem/` first-boot setup and a job runner over the shared folder, `build-msi.ps1` with
  the desktop-windows steps plus an uninstall check). dockur/windows 6.06, Windows Server 2025
  evaluation, 8 GB, 4 cores.
  - Proven: `bash -n`, shellcheck, the PowerShell files parse (pwsh 7.4), dry runs of all three.
  - Not proven: nothing has booted. `up.sh` gates on 12 GB available memory (5 to 8 GB were free)
    and 60 GB disk, so no ISO was downloaded. Open until the first run: dockur under rootless podman
    here, `\\host.lan\Data` as the scheduled task's account sees it, Gradle fetching the Android SDK
    in the VM, WiX 3 on .NET 3.5, and whether the MSI matches GitHub's. Next: with 12 GB free, run
    `up.sh`, then `build-msi.sh`; spike done when that passes.
  - macOS and iOS: nothing added (Apple's licence), as decided above.
- 2026-11-18 P4 done (merged, commits 9672cd5..b11a2c5): `scripts/release-local.sh <tag>` builds and
  signs the APK, the R8 mapping and (with `--linux`, needs `zsyncmake`) the Linux packages into
  `dist/local-release/<tag>/`, and prints the user-only `gh` commands that put them in a draft release
  `prebuilt-<tag>`. `release.yml` android and desktop-linux look for that draft
  (`scripts/release-prebuilt.sh`): checksums, this commit, this version, and the signing certificate
  pinned in `.github/release-signing-cert.sha256` (`scripts/verify-apk-signer.sh`); no draft means
  today's build, a bad draft stops the release. Not run end to end yet (no full build here).
  Gotcha: files built locally carry no GitHub attestation, but the site's security page says every
  file is attested: reword it or attest in the workflow before the first local release.
- Open: e2e `chats.spec.ts:42` (webkit, "Select all over a thousand chats asks first") failed in the
  first local run; the e2e run also rewrites tracked `design/web-sync/*.png` (restore with
  `git checkout -- design/web-sync`).
- 2026-11-18 later: review fixes merged (12 findings); hook now tests only the module that changed
  (`core/` runs `:core:jvmTest`, `ui/` `:ui:jvmTest`, `app/` `:app:testDebugUnitTest`, a build file change
  runs all three; the full Kotlin set took 20 minutes); WebKit and the debug APK need `--all`. Measured:
  `kotlin` 20 min cold, `ios` 10 s, `web` 16 min with WebKit (Chromium only is about half).
  Decisions taken by default: sibling fallback needs 3 files, nightly stays 02:17 UTC, Windows VM
  parked, the next beta is the first to use `release-local.sh`. Open: push (the user), the nightly's
  first real run on GitHub, one real `release-local.sh` run, the WebKit `chats.spec.ts:42` flake.
- 2026-11-18 paused. Pushed up to e674f78; six later local commits: the names dump (debug and
  preview builds only, `docs/BUILDING.md` "Names dump"), three parser fixes found in 6,028 real names
  (audio layout glued to x265 read as S1E265, a bare `-SP` read as a special, a size cut short after a
  dash read as episode one), and the leftovers filter. Open: run the dump with `--ez all true` (58
  chats, it stalled on "page failed (wda)" retries after chat 1), the test build `com.tmplayer.test`
  is still installed and signed in on the POCO, the full `:core:jvmTest :ui:jvmTest` run after the
  last parser fixes, the unfolded film copies without a year (same film, different quality, no year in
  the name), and the push (user).
- 2026-11-19: the all-chats names dump ran (59 chats, 33,909 videos, 885 leftovers; files in
  `private/name-survey/dump-2026-11-19-all/`). Full `:core:jvmTest :ui:jvmTest` pass on the parser fixes.
  Found, left as is: files named only `S1E01 [BDRip ...]` or `S01 EP01 720p` make a show called
  "bdrip" or "720p"; blanking that title turns 18 grouped episodes into 18 loose files (tried, reverted),
  so a better fix needs the chat's name. Yearless film copies show as unfolded in the dump only because
  the dump records length 0 and `SameVideo` needs a length for a film with no year. Open: the push (user).


- 2026-11-19 WB24: `webkit-iphone` (iPhone 13) joins `--all` / `CI_LOCAL_FULL=1` beside `webkit`, and
  its spec list (`IPHONE_SPECS` in `webapp/playwright.config.ts`) is the player specs plus FORM_SPECS
  (detail, a11y, titlelogo, choose-chats, sync, ux-audit). Run once alone in the Playwright image:
  142 listed, 70 passed, 72 skipped (the specs skip forms they do not cover), 0 failed. Cold, the
  install took about 4 min and the run 2.3 min. The GitHub `webapp.yml` matrix still lists only
  chromium and webkit (add `webkit-iphone` there when the user wants it in CI).
