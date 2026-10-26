# Releasing TMPlayer

Releases are cut by pushing a tag. Nothing else produces a signed APK or a desktop installer:

```bash
git tag -a v1.28.1 -F notes.txt
git push origin v1.28.1
```

The tag is annotated, and its message is the release notes: a `release: 1.28.1` subject, a blank
line, then one `- ` line per change, short and in plain words, no em or en dashes:

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
500 characters.

`.github/workflows/release.yml` then derives `versionName` from the tag and `versionCode`
arithmetically (`major*10000 + minor*100 + patch`), so the code can only ever increase.
Android refuses to install an APK whose `versionCode` is lower than the one already on the
device, and a hand-rolled number is easy to get wrong.

## What a release carries

One GitHub release holds the Android APK and the desktop app for Windows, Linux and macOS, and
nothing more than people install or the licence needs. The desktop installers are built on a
Windows, a Linux and an Apple Silicon macOS 26 runner, because the Compose packager only makes
installers for the system it runs on. For a tag `v<v>` the assets are:

| Asset | Built on |
| --- | --- |
| `TMPlayer-<v>-universal.apk` | Linux, signed with the release key |
| `TMPlayer-<v>-windows-x64.msi` | Windows, unsigned |
| `TMPlayer-<v>-macos-arm64.dmg` | macOS 26 on Apple Silicon, signed ad hoc (not notarized), from 1.26.0 |
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

Nothing else is attached. GitHub adds the source archives of the tagged tree by itself and shows
each file's SHA-256 beside it, so there is no checksum file. The release notes link to the
licence, `THIRD_PARTY_NOTICES.md` and the nextlib corresponding source instead of carrying them.
That source archive, `nextlib-corresponding-source-<nextlib>-<revision>.tar.gz` from
`scripts/build-nextlib-source.sh`, goes up by hand once, to the first release that carries that
nextlib version and patch revision, and is the same for every release while both stay there. The nextlib step in
`release.yml` works as the mediamp one: an empty `TAG` links the release's own copy and warns until
it is attached, then `TAG` names that release. `LINKED` is `1.11.1-0.16.0-tm2` (nextlib built
from source with the second revision of TMPlayer's patch, the light video mode of HX2c), first
carried by the release after 1.28.1, and its `TAG` is empty: that release must attach
`nextlib-corresponding-source-1.11.1-0.16.0-tm2.tar.gz` (`scripts/build-nextlib-source.sh`),
after which `TAG` names it. The `tm1` archive (the decoders alone) is what 1.28.1 links, on its own
release, where it is not attached yet (checked 2026-10-26): build it with
`scripts/build-nextlib-source.sh` at a commit before HX2c and attach it to v1.28.1 (1.21.0 to
1.26.0 linked the 1.10.1-0.13.0 archive on 1.21.0). The publish job fails if
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

The Windows files are not code signed, so SmartScreen warns on every machine; INSTALL.md and the
site say how to get past it.

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

## After the release job

`<v>` is the new version, `v<v>` its tag.

1. **The run and the release.** `gh run list --workflow release.yml --limit 1`, then
   `gh run watch <run-id>` and `gh release view v<v>`: every file is attached and the notes link
   the nextlib and mediamp sources. A warning about a missing source archive means the archive
   still has to be attached (see the source archives above).
2. **Provenance.** Check that the APK carries the build's attestation:

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
