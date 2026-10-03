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

From 1.18.0 on, one GitHub release holds the Android APKs and the desktop app for Windows and
Linux. The desktop installers are built on a Windows runner and a Linux runner, because the
Compose packager only makes installers for the system it runs on. For `v1.18.0` the assets are:

| Asset | Built on |
| --- | --- |
| `TMPlayer-1.18.0-universal.apk`, `-arm64-v8a.apk`, `-armeabi-v7a.apk` | Linux, signed with the release key |
| `TMPlayer-1.18.0-windows-x64.msi`, `TMPlayer-1.18.0-windows-x64-portable.zip` | Windows, unsigned |
| `tmplayer_1.18.0_amd64.deb`, `tmplayer-1.18.0.x86_64.rpm` | Linux |
| `TMPlayer-1.18.0-x86_64.AppImage`, `TMPlayer-1.18.0-linux-x64.tar.gz`, `TMPlayer-1.18.0.flatpak` | Linux, wrapped from the app folder with the recipes in `desktop/packaging/` |
| `SHA256SUMS-1.18.0.txt` | over every file above |

These names are what the website matches on: `site/app.js` reads the release list from the GitHub
API and finds each file by the end of its name (`universal.apk`, `windows-x64.msi`, `_amd64.deb`,
and so on), because the version in the middle rules out GitHub's fixed `latest/download/<name>`
links. Renaming an asset means updating the `KINDS` table in `site/app.js` in the same change, or
the download page falls back to linking the release page for that row.

The Windows files are not code signed, so SmartScreen warns on every machine; INSTALL.md and the
site say how to get past it. macOS is not built for release yet.

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

GitHub secrets are write-only: once set, nobody can read them back through the UI or API.
Gate the `release` environment behind required reviewers in repository settings so that only
a reviewed tag can reach the signing key and the credentials. Every job that uses that
environment, the desktop ones included, asks for approval separately.

## On the Telegram credentials

Anyone building a fork should register their own `api_id` and `api_hash` and put them in
`local.properties`. The reasoning, and what the build does without them, is in
[BUILDING.md](BUILDING.md).
