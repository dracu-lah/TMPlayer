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
./gradlew :desktop:test :desktop:run         # the desktop app, on Windows, Linux or macOS
./gradlew :desktop:runPlayerDev --args="--file /path/to/clip.mkv"   # the desktop player alone
```

Never commit credentials.

## Ground rules

- **Existing libraries first.** Before writing a component, check if TDLib, Media3, or an established GPL-compatible library already does it. Custom code needs a reason.
- **License hygiene (important).** This project is GPL-3.0-or-later. Record the provenance and licence of every dependency or adapted implementation, preserve required notices, and do not copy code whose terms are incompatible with GPL-3.0. Reading other projects to understand a pattern is fine; clean-room implementations against documented APIs are still preferred.
- **TV rules, non-negotiable** (the 1 GB stick and a remote are the floor):
  - Everything inside the overscan: a television crops its outer few per cent, so screens keep
    their content clear of the edges.
  - No text smaller than 14 sp on screen, ever.
  - Explicit focus: every screen sets its first focus, focus comes back to the same card after the
    player, and Back never traps.
  - Every async surface has a loading state and an error state (a page of chats, a grid, a
    thumbnail, buffering, sign in), never a blank screen.
  - No typing after sign in is required: everything works with the D-pad alone.
- **Low-spec first:** minSdk 23 (Android 6.0), `armeabi-v7a` must keep working, watch RAM (around 180 MB or less during playback) and the universal APK's size, since it is the only one published.
- **Tests for core logic.** The auth state machine, the streaming `TdDataSource` (including the seek matrix in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#seek-matrix)), and storage-cap policy are unit-tested with fakes, and PRs touching them must keep/extend the tests. UI is verified on-device instead.

## Style

- Kotlin official style, coroutines + Flow; no DI framework, no new heavyweight dependencies without discussion.
- Comments only where the code can't say it (constraints, protocol quirks).
- Commits: short imperative subject, optionally `feat:`/`fix:`/`chore:` prefixes. Keep PRs small and focused.
- No em or en dashes anywhere, and screenshots are WebP: [AGENTS.md](AGENTS.md) has both rules and the commands.

## Translations

TMPlayer ships in 22 languages. English is the source; the other 21 start as machine translations
and get better as people who speak them correct them. Fixing a few words you noticed is as welcome
as reviewing a whole language.

- The app's text is in [`core/src/commonMain/resources/i18n/`](core/src/commonMain/resources/i18n),
  one JSON file per language (`es-419.json`, `pt-BR.json`, `zh-TW.json` and so on), the same keys as
  `en.json`. The website's text is in [`site/i18n/`](site/i18n), laid out the same way.
- Change the value, never the key. Keep every `{placeholder}` exactly as it is in English. Plurals
  are ICU messages: `{count, plural, one {# video} other {# videos}}`. Keep the structure, translate
  the words inside the braces, and add the forms your language needs (Russian, Ukrainian, Polish, Czech and
  Arabic need `few` and `many`); the tests name any that are missing.
- Keep it short where English is short: the TV shows labels at a large size on a narrow rail.
- Leave names as they are: TMPlayer, Telegram, Android TV, TDLib, mpv, APK and the like.
- A key you leave out reads in English, so a partial file is fine.
- Run `./gradlew :core:jvmTest` before opening the pull request. It fails on a broken placeholder or
  a missing plural form.

Not set up to build? Open a [translation issue](https://github.com/dracu-lah/TMPlayer/issues/new?template=translation.yml)
with the screen, the current text and your suggestion, and it will be fixed for you.

## Reporting issues

Include the platform (Android phone, Android TV, Windows, macOS, or Linux with its distribution and version), the app version, the device or computer, what you did, what happened, and logs around the failure if you can: `adb logcat` on Android, or the output of starting the desktop app from a terminal. Say which file you installed on Linux (AppImage, tarball or AUR, or an older deb, rpm or Flatpak). For playback issues, the file's container and codecs (from VLC's or mpv's media info) helps a lot.
