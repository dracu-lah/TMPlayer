# Contributing to TMPlayer

Thanks for helping! This project has one guiding principle: **minimal work that works properly**. The less code we own, the less breaks.

## Dev setup

Everything about JDK versions, Telegram credentials, the Gradle commands, the reference test
devices and the version pins you must not casually bump lives in
[docs/BUILDING.md](docs/BUILDING.md). Read it before your first build, and before changing any
dependency version. Cutting a release is [docs/RELEASING.md](docs/RELEASING.md).

In short: JDK 21, your own Telegram credentials in `local.properties`, then

```bash
./gradlew test assembleDebug                 # the Android app
./gradlew :desktop:test :desktop:run         # the desktop app, on Windows or Linux
./gradlew :desktop:runPlayerDev --args="--file /path/to/clip.mkv"   # the desktop player alone
```

Never commit credentials.

## Ground rules

- **Existing libraries first.** Before writing a component, check if TDLib, Media3, or an established GPL-compatible library already does it. Custom code needs a reason.
- **License hygiene (important).** This project is GPL-3.0. Record the provenance and licence of every dependency or adapted implementation, preserve required notices, and do not copy code whose terms are incompatible with GPL-3.0. Reading other projects to understand a pattern is fine; clean-room implementations against documented APIs are still preferred.
- **The TV UX rules in [PLAN.md §4](PLAN.md) are non-negotiable:** overscan-safe padding, at least 14 sp text, explicit focus handling, a loading/error state for every async surface, no typing after login.
- **Low-spec first:** minSdk 26, `armeabi-v7a` must keep working, watch RAM (around 180 MB or less during playback) and APK size (per-ABI splits).
- **Tests for core logic.** The auth state machine, the streaming `TdDataSource` (including the seek matrix in PLAN.md §6), and storage-cap policy are unit-tested with fakes, and PRs touching them must keep/extend the tests. UI is verified on-device instead.

## Style

- Kotlin official style, coroutines + Flow; no DI framework, no new heavyweight dependencies without discussion.
- Comments only where the code can't say it (constraints, protocol quirks).
- Commits: short imperative subject, optionally `feat:`/`fix:`/`chore:` prefixes. Keep PRs small and focused.
- No em dashes or en dashes anywhere: comments, KDoc, commit messages, UI strings, site copy. Use a colon, a comma or a full stop. Screenshots are WebP; see [CLAUDE.md](CLAUDE.md) for the conversion command and where each image is referenced from.

## Reporting issues

Include the platform (Android phone, Android TV, Windows, or Linux with its distribution and version), the app version, the device or computer, what you did, what happened, and logs around the failure if you can: `adb logcat` on Android, or the output of starting the desktop app from a terminal. Say which file you installed on Linux (deb, rpm, AppImage, Flatpak, tarball or AUR). For playback issues, the file's container and codecs (from VLC's or mpv's media info) helps a lot.
