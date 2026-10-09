# Releasing TMPlayer

Releases are cut by pushing a tag. Nothing else produces a signed APK or a desktop installer:

```bash
git tag v0.3.0
git push origin v0.3.0
```

`.github/workflows/release.yml` then derives `versionName` from the tag and `versionCode`
arithmetically (`major*10000 + minor*100 + patch`), so the code can only ever increase.
Android refuses to install an APK whose `versionCode` is lower than the one already on the
device, and a hand-rolled number is easy to get wrong.

## What a release carries

One GitHub release holds the Android APK and the desktop app for Windows and Linux, and nothing
more than people install or the licence needs. The desktop installers are built on a Windows
runner and a Linux runner, because the Compose packager only makes installers for the system it
runs on. For a tag `v<v>` the assets are:

| Asset | Built on |
| --- | --- |
| `TMPlayer-<v>-universal.apk` | Linux, signed with the release key |
| `TMPlayer-<v>-windows-x64.msi` | Windows, unsigned |
| `TMPlayer-<v>-x86_64.AppImage`, `TMPlayer-<v>-linux-x64.tar.xz` | Linux, wrapped from the app folder by `desktop/packaging/linux/package.sh`; the tarball is what the AUR PKGBUILD builds from |

Nothing else is attached. GitHub adds the source archives of the tagged tree by itself and shows
each file's SHA-256 beside it, so there is no checksum file. The release notes link to the
licence, `THIRD_PARTY_NOTICES.md` and the nextlib corresponding source instead of carrying them.
That source archive went up once, with 1.21.0, and is the same for every release while nextlib
stays at 1.10.1-0.13.0; the publish job fails if `gradle/libs.versions.toml` moves nextlib on, so
a bump comes with a new archive and new links in `release.yml`.

Up to 1.21.0 a release also carried per-ABI APKs, a Windows portable zip, a deb, an rpm, a
Flatpak, a TMPlayer source tarball, the R8 mapping, the licence files, the nextlib source and
`SHA256SUMS`; none of those are attached any more.

The R8 mapping, which turns a stack trace from the released APK back into readable names, is kept
as a workflow artifact named `mapping-<v>` on the release run, for 90 days. Download it from
the run's page (or `gh run download <run id> -n mapping-<v>`) and keep it with your own
records if a crash from that build may need decoding later.

These names are what the website matches on: `site/app.js` reads the release list from the GitHub
API and finds each file by the end of its name (`universal.apk`, `windows-x64.msi`, `.AppImage`,
and so on), because the version in the middle rules out GitHub's fixed `latest/download/<name>`
links. Renaming an asset means updating the `KINDS` table in `site/app.js` in the same change, or
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

Two more are optional, and a release without them only lacks the feature: `SENTRY_DSN` (opted-in
crash reports) and `OPENSUBTITLES_API_KEY` (online subtitles, on every platform).

GitHub secrets are write-only: once set, nobody can read them back through the UI or API.
Gate the `release` environment behind required reviewers in repository settings so that only
a reviewed tag can reach the signing key and the credentials. Every job that uses that
environment, the desktop ones included, asks for approval separately.

## On the Telegram credentials

Anyone building a fork should register their own `api_id` and `api_hash` and put them in
`local.properties`. The reasoning, and what the build does without them, is in
[BUILDING.md](BUILDING.md).
