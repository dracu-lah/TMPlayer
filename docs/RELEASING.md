# Releasing TMPlayer

Releases are cut by pushing a tag. Nothing else produces a signed APK or a desktop installer.
Every release goes out first as a test version, a GitHub pre-release tagged `vX.Y.Z-beta.N` from
`main`; it is installed and tried on the real devices, and the release `vX.Y.Z` follows from the
same commit or one with the fixes (see [Test versions](#test-versions-pre-releases) below):

```bash
git tag -a v1.28.2-beta.1 -F notes.txt   # first: a pre-release, offered only to opted-in installs
git push origin v1.28.2-beta.1
# ...tried on the phone, the TV and the desktops; fixes go to main and out as beta.2, beta.3...
git tag -a v1.28.2 -F notes.txt           # then the release everyone gets
git push origin v1.28.2
```

The tag is annotated, and its message is the release notes: a `release: 1.28.1` subject (or
`release: 1.28.2-beta.1`), a blank line, then one `- ` line per change, short and in plain words,
no em or en dashes:

```
release: 1.28.1

- An Episodes list in the player on phone, TV and desktop
- OK pauses in one press on TV
- Buttons in the light theme are readable again
```

That list heads the GitHub release page, the Telegram post and the site's changelog page as it
is. The update popup in installed apps shows one short paragraph, so the feed
(`scripts/write-latest-json.py`) turns each item into a sentence there. The store changelog,
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, takes the same list, kept under
500 characters; it is named by the new numbering from 1.28.2 on (`1280299.txt`), and a test
version has none.

`.github/workflows/release.yml` then derives `versionName` from the tag and `versionCode`
arithmetically (`scripts/release-version.py`, below), so the code can only ever increase.
Android refuses to install an APK whose `versionCode` is lower than the one already on the
device, and a hand-rolled number is easy to get wrong.

## Before you tag

- **The 16 KB page-size gate.** Google Play needs apps that target Android 15 or later to run on
  devices with 16 KB memory pages, and a native library whose ELF segments are aligned to less than
  `0x4000` does not load there. After any change that touches a native library (a new FFmpeg or
  TDLib build, libVLC, libmpv, the media3 extension, an NDK or AGP bump), build the release and run:

  ```sh
  ./gradlew :app:assembleRelease
  scripts/check-16kb.sh app/build/outputs/apk/release/app-universal-release.apk
  ```

  It runs `zipalign -c -P 16 -v 4` on the APK and reads the `p_align` of every LOAD segment of every
  `.so` in it. A 64-bit library below `0x4000` or a zip that is not 16 KB aligned fails (exit 1,
  with the file named); a 32-bit one only warns, since 16 KB devices run 64-bit apps (the 32-bit
  TDLib is `0x1000` today). The release workflow runs the same script on the universal APK right
  after the build and before the APK is handed on, so a failure stops the release before it is
  published, and it publishes nothing itself. Run it by hand first on a build that adds or bumps a
  native package; `scripts/check-16kb.sh --self-test` proves the script itself still catches a 4 KB
  library. It also takes an `.aab`, a `.jar`, a single `.so` or a desktop bundle folder.

## Version numbers

Every build has a stage: test version N (`-beta.N`, N from 1 to 98) is stage N, the release is 99,
and any other suffix (a local `-dev`, CI's `-ci.N`, the Mac test build's `-test.N`) is 0. So every
test version of X.Y.Z sorts after X.Y.(Z-1) and before X.Y.Z, on every platform:

| | Rule | 1.28.2-beta.1 | 1.28.2-beta.10 | 1.28.2 |
| --- | --- | --- | --- | --- |
| Android `versionCode` | major\*1000000 + minor\*10000 + patch\*100 + stage | 1280201 | 1280210 | 1280299 |
| MSI ProductVersion, macOS `CFBundleVersion` | major.minor.(patch\*100 + stage) | 1.28.201 | 1.28.210 | 1.28.299 |
| macOS `CFBundleShortVersionString` | the release's x.y.z | 1.28.2 | 1.28.2 | 1.28.2 |
| The app's version name, the updaters' order | the tag | 1.28.2-beta.1 | 1.28.2-beta.10 | 1.28.2 |

- **Android.** Until 1.28.1 the code was `major*10000 + minor*100 + patch` (12801). Every new code
  is above a million, so the first one is an ordinary upgrade. Minor and patch go up to 99, and the
  highest code, 2099.99.99, stays under Google Play's ceiling of 2100000000 and so under an int's.
  A local build gets the code of its name (`-PtmVersionName=1.28.2-dev` is 1280200, below the
  first test version, so it never blocks one); `-PtmVersionCode` still overrides it.
- **Windows.** Windows Installer compares only the first three fields of a ProductVersion, the
  third up to 65535, so the stage lives inside the third. Test to test, stable to test and test to
  stable are all upgrades under the one upgrade code; nothing relies on a downgrade. The MSI shows
  1.28.299 under Installed apps. `msi-update.ps1` and the CI self-test check for that number. When
  winget is live, its manifest maps it: `AppsAndFeaturesEntries` `DisplayVersion: 1.28.299` for
  `PackageVersion: 1.28.2` (check the PR the winget job opens says so).
- **macOS.** Finder shows the release's x.y.z with the build number beside it.
- **AppImage.** A release embeds the update channel `latest` (GitHub's latest release, never a
  pre-release); a test version embeds `latest-all`, so AppImageUpdate moves it to whatever is newest
  instead of back to the release before it (`desktop/packaging/linux/package.sh`).
- **One rule, four copies, held together.** `ReleaseVersion` in `:core` is the reference;
  `scripts/release-version.py` (the workflow), `app/build.gradle.kts` and
  `desktop/build.gradle.kts` repeat it. `core/src/jvmTest/resources/release-versions.txt` lists the
  cases: `ReleaseVersionTest` and `release-version.py --check` (run by the workflow before it
  derives anything) both read it, and `VersionCodeTest` (app) and `SelfUpdateTest` (desktop) hold
  each build's own number to `ReleaseVersion`.

One leftover of the switch: an installed 1.28.1 or older on Windows that updates itself to 1.28.2
looks for ProductVersion 1.28.2, finds 1.28.299, and so runs msiexec a second time (about 15
seconds more) and leaves a "failed" marker that 1.28.2 clears on its first start, without a
message. From 1.28.2 on the script knows the numbering.

## Test versions (pre-releases)

A tag `vX.Y.Z-beta.N` goes through the same workflow and carries the same files as a release:
the APK, the MSI, the DMG, the AppImage with its `.zsync` and the tarball, signed and attested the
same way. Then it stops at the release page:

| Step of `release.yml` | `vX.Y.Z` | `vX.Y.Z-beta.N` |
| --- | --- | --- |
| version | name, versionCode, `prerelease=false` | name, versionCode, `prerelease=true` |
| android, desktop-linux, desktop-windows, desktop-macos | build, test, package | the same |
| publish: check, attest, source links | yes | yes |
| publish: the release page | latest | pre-release, never latest, with a line saying what it is |
| publish: `latest.json`, `releases.json`, `tmplayer.org/apk`, PKGBUILD (the commit to main) | yes | skipped |
| publish: AUR | yes, with the key | skipped |
| publish: IndexNow | yes | skipped |
| publish: Telegram channel post | yes | skipped |
| winget | yes, with the token | skipped |

The site never lists one: the download page takes the newest release that is not a pre-release,
and `scripts/release-data.py` (the changelog, its RSS and the home page's pill) leaves them out.
A test version never gets a store changelog file.

Installed copies on the phone, the TV and the desktop offer a test version only with Settings,
About, **Get test versions** on (off by default, and switched on only after a warning). With it on,
each check also reads GitHub's release list, pre-releases included, and offers whichever is newer:
the newest test version or the release. Turning it off keeps the test version installed until the
next release passes it. An installed test version is offered its release whatever the setting
says, since 1.28.2 is newer than 1.28.2-beta.N. Store builds (F-Droid, Play, Amazon) have no
updater at all.

**The cadence guard** (no release less than 7 days after the last, nor a third in 7 days) counts
releases only. A pre-release prompts nobody who has not opted in, so test versions neither count
nor wait: `gh release list` marks them `Pre-release`; count the other rows.

**F-Droid**, if its recipe comes back (on hold, `docs/distribution/telegram-apps.md`): F-Droid
builds from tags, so the recipe must skip test versions: `UpdateCheckMode: Tags ^v[0-9]+\.[0-9]+\.[0-9]+$`
and `AutoUpdateMode: Version`, with `CurrentVersionCode` in the new numbering (1280299 for 1.28.2).

## What a release carries

One GitHub release holds the Android APK and the desktop app for Windows, Linux and macOS, and
nothing more than people install or the licence needs. The desktop installers are built on a
Windows, a Linux and an Apple Silicon macOS 26 runner, because the Compose packager only makes
installers for the system it runs on. For a tag `v<v>` the assets are:

| Asset | Built on |
| --- | --- |
| `TMPlayer-<v>-universal.apk` | Linux, signed with the release key |
| `TMPlayer-<v>-windows-x64.msi` | Windows, unsigned (SmartScreen help: tmplayer.org/install-warnings/) |
| `TMPlayer-<v>-macos-arm64.dmg` | macOS 26 on Apple Silicon, signed ad hoc (not notarized), from 1.26.0 (Gatekeeper help: tmplayer.org/install-warnings/) |
| `TMPlayer-<v>-x86_64.AppImage`, `TMPlayer-<v>-linux-x64.tar.xz` | Linux, wrapped from the app folder by `desktop/packaging/linux/package.sh`; the tarball is what the AUR PKGBUILD builds from |
| `TMPlayer-<v>-x86_64.AppImage.zsync` | Linux, beside the AppImage: not a download, it lets an installed AppImage fetch only what changed |

Releases up to 1.25.0 also carried `TMPlayer.apk`, the same APK under a fixed name for the
`tmplayer.org/apk` short link. That link now points at the versioned APK: the publish job runs
`scripts/point-apk-redirect.py <v>` and commits `site/vercel.json` with the update feed, so a
release carries one APK.

The macOS build bundles Homebrew's libraries, so its corresponding source is a second archive,
`mediamp-corresponding-source-<mediamp>-macos.tar.gz` from `scripts/build-mediamp-source.sh --macos`.
It is attached by hand, beside the Linux and Windows archive, to the first release that carries
that mediamp version, after which `MAC_TAG` in `release.yml` names that release, exactly as `TAG`
does for the Linux and Windows archive. mediamp 0.5.2 is first carried by 1.27.0, and both archives
are on that release, so `TAG` and `MAC_TAG` are `v1.27.0`.

The Linux AppImage and tarball carry TDLib and libmpv libraries rebuilt for glibc 2.35
(`.github/actions/linux-natives`, recipes in `natives/`, hashes in `natives/linux-2-35/PINS.txt`).
Their corresponding source is a further archive, `scripts/build-mpv-linux-2-35.sh --source-archive`
(`natives/mpv-linux/README.md`), which goes beside the mediamp one on the first release that ships
them. A tdl-coroutines or mediamp bump means rebuilding, new pins, and a look at both recipes first.
The `desktop-linux` job then runs the AppImage and the tarball in a plain `ubuntu:22.04` container
(`.github/actions/desktop-linux/plain-linux-check.sh`: the glibc floor check and `--self-test`) before the
upload to the publish job, so a library that needs a newer glibc or libstdc++ stops the release;
`desktop/packaging/linux/test-floor-gate.sh` (with `CONTAINER_ENGINE=podman` locally) proves the gate
still fails on the jar's GCC 13 TDLib.

Nothing else is attached. GitHub adds the source archives of the tagged tree by itself and shows
each file's SHA-256 beside it, so there is no checksum file. The release notes link to the
licence, `THIRD_PARTY_NOTICES.md` and the nextlib corresponding source instead of carrying them.
That source archive, `nextlib-corresponding-source-<nextlib>-<revision>.tar.gz` from
`scripts/build-nextlib-source.sh`, goes up by hand once, to the first release that carries that
nextlib version and patch revision, and is the same for every release while both stay there. The nextlib step in
`release.yml` works as the mediamp one: an empty `TAG` links the release's own copy and warns until
it is attached, then `TAG` names that release. `LINKED` is `1.11.1-0.16.0-tm4` (nextlib built
from source with the third revision of TMPlayer's patch: the light video mode and the wider set of
LGPL decoders of C1). Its `nextlib-corresponding-source-1.11.1-0.16.0-tm4.tar.gz`
(`scripts/build-nextlib-source.sh`) is attached by hand to the first release that carries tm4, so
`TAG` stays empty until then and is set to that release afterwards. The `tm2` archive (light mode)
is attached to 1.28.2, the `tm1` archive (the decoders alone) is what 1.28.1 links, attached to its own release (1.21.0 to 1.26.0 linked the 1.10.1-0.13.0 archive on 1.21.0). The publish job fails if
`gradle/libs.versions.toml` moves nextlib or mediamp on, or `TM_REVISION` in
`scripts/build-media3ext.sh` moves the patch on, without a matching `LINKED` or `COVERED`, so a
bump comes with a new archive.

Up to 1.21.0 a release also carried per-ABI APKs, a Windows portable zip, a deb, an rpm, a
Flatpak, a TMPlayer source tarball, the R8 mapping, the licence files, the nextlib source and
`SHA256SUMS`; none of those are attached any more.

The R8 mapping, which turns a stack trace from the released APK back into readable names, is kept
as a workflow artifact named `mapping-<v>` on the release run, for 90 days. Download it from
the run's page (or `gh run download <run id> -n mapping-<v>`) and keep it with your own
records if a crash from that build may need decoding later.

These names are what the website matches on: `site/src/scripts/app.js` reads the release list from the GitHub
API and finds each file by the end of its name (`universal.apk`, `windows-x64.msi`, `.AppImage`,
`macos-arm64.dmg` and so on), because the version in the middle rules out GitHub's fixed `latest/download/<name>`
links. Renaming an asset means updating the `KINDS` table in `site/src/scripts/app.js` in the same change, or
the download page falls back to linking the release page for that row.

The Windows files are not code signed, so SmartScreen warns on every machine, and the macOS DMG is
signed ad hoc and not notarized, so Gatekeeper blocks the first open. INSTALL.md and the site's help page
tmplayer.org/install-warnings/ (`site/src/pages/install-warnings/`, WP-53, decision D6: free help now, signing
only when the user funds it) say how to get past both; keep its wording in step if a release changes how a
file is signed or notarized, and link it from any release note that mentions a warning.

The desktop jobs need the same Telegram credentials as the Android build, and they run in the
`release` environment too, so each one waits for the same reviewer approval before it starts.
Approve them all from the run's page, or with `gh`:

```bash
gh run list --workflow release.yml --limit 1
gh api repos/dracu-lah/TMPlayer/actions/runs/<run-id>/pending_deployments \
  -f "environment_ids[]=<id>" -f state=approved -f comment="release"
```

Ordinary pushes and pull requests run `.github/workflows/ci.yml`, which tests and builds a
debug APK. That job never sees the signing key or the Telegram credentials, so a pull request
from a stranger cannot reach them.

So tag a commit CI has already passed. The release's Android job then skips the unit tests,
because it finds that passing CI run on an ancestor of the tag with nothing since but docs, the
site, Markdown, the store listing text or the PKGBUILD. Tagging code CI has not seen still works,
it just runs the tests itself, which adds about 20 minutes. The desktop jobs always run their
own tests, because they also check the installers.

## Building the release on your machine

The Android APK, its R8 mapping and the Linux packages can be built and signed here instead of on
GitHub, which saves the release run most of its time; the MSI, the DMG and the TV packages still
build on GitHub. Nothing about the publish job changes.

```sh
scripts/release-local.sh --dry-run --linux v1.28.7   # the plan and every check, builds nothing
SKIP_TESTS=1 scripts/release-local.sh --linux v1.28.7
```

- **What it builds.** HEAD, which must be clean and is the commit you then tag, with the release
  job's steps: unit tests (`SKIP_TESTS=1` skips them, as the job does when CI passed), the FFmpeg
  extension (`JOBS`), `:app:assembleRelease` with the same `-P` flags, `check-16kb.sh` and
  `verify-apk-signer.sh`. `--linux` adds the `desktop-linux` action's steps: desktop tests, the app
  image, the glibc floor, `package.sh`, every self-test and the plain Ubuntu 22.04 check in podman.
  Leave `--linux` out to have GitHub build the Linux packages as before.
- **What it reads.** `keystore.properties` and `local.properties` in the checkout, through Gradle as
  any local build does; the script only checks their entries are set and never prints them.
  `SENTRY_AUTH_TOKEN` (environment, else `local.properties`) uploads the mapping to Sentry. JDK 21
  from `JAVA_HOME` or `~/.jdks`. `--linux` needs the pinned natives in `~/.cache/tmplayer-natives`
  (`TMPLAYER_LINUX_NATIVES`), `zsyncmake` (Fedora: `zsync`), podman and about 8 GB of disk.
- **What it writes.** `dist/local-release/<tag>/`: the APK, `mapping-<v>.txt`, with `--linux` the
  AppImage, its `.zsync` and the tarball, plus `BUILD-INFO.txt` (tag, version, versionCode, commit,
  whether tests ran) and `SHA256SUMS`. Then it prints the commands, and runs none of them:
  `gh release create prebuilt-<tag> --draft ...` with the files (`gh release upload --clobber` when
  the draft exists), then the tag and its push as above.
- **What the release run does with them.** The `android` and `desktop-linux` jobs look for the
  draft release `prebuilt-<tag>` (`scripts/release-prebuilt.sh`). With their files in it, they
  check each against `SHA256SUMS`, check `BUILD-INFO.txt` names this tag, version and the tagged
  commit, check the APK is signed by the one certificate in `.github/release-signing-cert.sha256`
  and is `com.tmplayer` at this versionCode, run the 16 KB check, and for Linux the plain Ubuntu
  22.04 self-test; then they skip their build and hand the files on as `release-android`,
  `mapping-<v>` and `release-linux`. A draft without a platform's files leaves that platform to
  build as before; a draft whose files fail a check stops the release. The jobs still wait for the
  `release` environment's approval.
- **The draft.** Its tag `prebuilt-<tag>` is never created, because a draft makes no tag, and the
  publish job makes the release `<tag>` fresh, so the mapping and `SHA256SUMS` never go public.
  Never publish the draft. It stays as a private copy of the mapping, with no 90 day limit;
  `gh release delete prebuilt-<tag>` removes it when it is no longer wanted.
- **Provenance.** A file built here gets no build attestation (the publish job attests only what
  the run built), so `gh attestation verify` in step 2 below fails for the APK and the Linux
  packages of such a release. The APK's signature, checked against the pinned certificate, is what
  vouches for it.
- **A new signing key** means a new `.github/release-signing-cert.sha256` (`apksigner verify
  --print-certs` on an APK signed with it), in a commit of its own.

Trust: the APK is bound to the release key (the run checks its signature against the certificate
pinned in `.github/release-signing-cert.sha256`). The Linux packages are only checked against the
draft's own `SHA256SUMS` and `BUILD-INFO`, so whoever can write to the repo can put files in a draft.
That person could edit the workflows too, so it is not a new right, but it means the tarball and
AppImage rest on repository write access, not on a key. Re-running `release-local.sh` for a tag
that has a draft means deleting the draft and creating a fresh one (the script prints both
commands), so files of two different builds never sit in one draft.

## After the release job

`<v>` is the new version, `v<v>` its tag. For a test version only steps 1 and 2 apply, and
`gh release view v<v> --json isPrerelease` says `true`; then install it on the devices (the APK
over the installed release, the MSI, the DMG, the AppImage) and turn on Get test versions on one
of them to see the in-app offer.

1. **The run and the release.** `gh run list --workflow release.yml --limit 1`, then
   `gh run watch <run-id>` and `gh release view v<v>`: every file is attached and the notes link
   the nextlib and mediamp sources. A warning about a missing source archive means the archive
   still has to be attached (see the source archives above).
2. **Provenance.** Check that the APK carries the build's attestation (not for an APK built on
   your machine, see above; check the MSI instead):

   ```sh
   cd "$(mktemp -d)"
   gh release download v<v> -p 'TMPlayer-<v>-universal.apk'
   gh attestation verify TMPlayer-<v>-universal.apk --repo dracu-lah/TMPlayer
   ```

3. **The feed and the site.** `git pull`, then `jq . site/public/latest.json`: the new version, its
   changes and each file's SHA-256.
4. **The Telegram post** on t.me/tmplayerapp went out (the run's summary has a "Telegram:" line).
5. **AUR.** `desktop/packaging/aur/PKGBUILD` on `main` names `pkgver=<v>` (the feed step runs
   `scripts/aur-bump.py`). Once the `AUR_SSH_PRIVATE_KEY` secret is set, the next step pushes it,
   and aur.archlinux.org/packages/tmplayer-bin shows the new version. For a release that went out
   some other way: `python3 scripts/aur-bump.py <v>`. One-time setup: `docs/distribution/aur.md`.
6. **winget.** Once `TMPlayer.TMPlayer` is on winget and the `WINGET_TOKEN` secret is set, the
   `winget` job opens a PR in microsoft/winget-pkgs:
   `gh search prs --repo microsoft/winget-pkgs --author dracu-lah --state open`, and answer a
   Needs-Author-Feedback label within 5 days. Until the first manual submission it skips or
   fails without failing the release (`docs/distribution/winget.md`).

## Repository secrets

The release job needs six secrets. Set them once with the GitHub CLI:

```bash
gh secret set TG_API_ID          # the app's api_id from my.telegram.org
gh secret set TG_API_HASH        # the matching api_hash
gh secret set KEY_ALIAS          # from keystore.properties
gh secret set KEYSTORE_PASSWORD  # from keystore.properties
gh secret set KEY_PASSWORD       # from keystore.properties
base64 -w0 release.keystore | gh secret set KEYSTORE_BASE64
```

Three more are optional, and a release without them only lacks the feature: `SENTRY_DSN` (opted-in
crash reports), `OPENSUBTITLES_API_KEY` (online subtitles, on every platform) and `SENTRY_AUTH_TOKEN`.
The last is a Sentry organization token (scope `org:ci`, made under Settings, Auth Tokens); with it
the release build uploads its R8 mapping to Sentry under the UUID it writes into the APK, so a
crash report's stack trace reads as the source does. Without it the reports arrive minified.
`sentry-cli login` stores the same kind of token in `~/.sentryclirc`; to upload from a local
release build, `export SENTRY_AUTH_TOKEN=...` first.

GitHub secrets are write-only: once set, nobody can read them back through the UI or API.
Gate the `release` environment behind required reviewers in repository settings so that only
a reviewed tag can reach the signing key and the credentials. Every job that uses that
environment, the desktop ones included, asks for approval separately.

## On the Telegram credentials

Anyone building a fork should register their own `api_id` and `api_hash` and put them in
`local.properties`. The reasoning, and what the build does without them, is in
[BUILDING.md](BUILDING.md).
