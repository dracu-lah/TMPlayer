# Contributing to TMPlayer

Thanks for helping! This project has one guiding principle: **minimal work that works properly** — the less code we own, the less breaks.

## Dev setup

1. JDK 17+ and Android SDK (API 35). Any recent Android Studio works, but the project builds fine from the CLI.
2. Get your own `api_id`/`api_hash` from [my.telegram.org](https://my.telegram.org) and put them in `local.properties` (see README). Never commit credentials.
3. `./gradlew assembleDebug` to build, `./gradlew test` to run unit tests.

Test devices: cheap/low-spec Android TV is the target (Mi TV Stick: 1 GB RAM, 8 GB storage, Android 9). If it's smooth there, it's smooth everywhere. An Android TV emulator (API 28+, x86_64, 1 GB RAM profile) is the reference environment.

## Ground rules

- **Existing libraries first.** Before writing a component, check if TDLib, Media3, or an established Apache/MIT library already does it. Custom code needs a reason.
- **License hygiene (important).** This project is Apache-2.0. Do **not** copy code from GPL projects (official Telegram apps, Telegram X, VLC…). Reading them to understand a pattern is fine; the implementation here must be written clean-room against documented APIs.
- **The TV UX rules in [PLAN.md §4](PLAN.md) are non-negotiable:** overscan-safe padding, ≥14 sp text, explicit focus handling, a loading/error state for every async surface, no typing after login.
- **Low-spec first:** minSdk 26, `armeabi-v7a` must keep working, watch RAM (≤ ~180 MB during playback) and APK size (per-ABI splits).
- **Tests for core logic.** The auth state machine, the streaming `TdDataSource` (including the seek matrix in PLAN.md §6), and storage-cap policy are unit-tested with fakes — PRs touching them must keep/extend the tests. UI is verified on-device instead.

## Style

- Kotlin official style, coroutines + Flow; no DI framework, no new heavyweight dependencies without discussion.
- Comments only where the code can't say it (constraints, protocol quirks).
- Commits: short imperative subject, optionally `feat:`/`fix:`/`chore:` prefixes. Keep PRs small and focused.

## Reporting issues

Include: device model + Android TV version, what you did, what happened, and `adb logcat` output around the failure if you can. For playback issues, the file's container/codecs (e.g. from VLC's media info) helps a lot.
