# Contributing to TMPlayer

Thanks for helping! This project has one guiding principle: **minimal work that works properly**. The less code we own, the less breaks.

## Dev setup

1. JDK 17+ and Android SDK platform 36. Any recent Android Studio works, but the project builds fine from the CLI.
2. Get your own `api_id`/`api_hash` from [my.telegram.org](https://my.telegram.org) and put them in `local.properties` (see README). Never commit credentials.
3. `./gradlew assembleDebug` to build, `./gradlew test` to run unit tests.

Test devices: cheap/low-spec Android TV is the target (Mi TV Stick: 1 GB RAM, 8 GB storage, Android 9). If it's smooth there, it's smooth everywhere. An Android TV emulator (API 28+, x86_64, 1 GB RAM profile) is the reference environment.

## Ground rules

- **Existing libraries first.** Before writing a component, check if TDLib, Media3, or an established GPL-compatible library already does it. Custom code needs a reason.
- **License hygiene (important).** This project is GPL-3.0. Record the provenance and licence of every dependency or adapted implementation, preserve required notices, and do not copy code whose terms are incompatible with GPL-3.0. Reading other projects to understand a pattern is fine; clean-room implementations against documented APIs are still preferred.
- **The TV UX rules in [PLAN.md §4](PLAN.md) are non-negotiable:** overscan-safe padding, ≥14 sp text, explicit focus handling, a loading/error state for every async surface, no typing after login.
- **Low-spec first:** minSdk 26, `armeabi-v7a` must keep working, watch RAM (≤ ~180 MB during playback) and APK size (per-ABI splits).
- **Tests for core logic.** The auth state machine, the streaming `TdDataSource` (including the seek matrix in PLAN.md §6), and storage-cap policy are unit-tested with fakes, and PRs touching them must keep/extend the tests. UI is verified on-device instead.

## Style

- Kotlin official style, coroutines + Flow; no DI framework, no new heavyweight dependencies without discussion.
- Comments only where the code can't say it (constraints, protocol quirks).
- Commits: short imperative subject, optionally `feat:`/`fix:`/`chore:` prefixes. Keep PRs small and focused.

## Reporting issues

Include: device model + Android TV version, what you did, what happened, and `adb logcat` output around the failure if you can. For playback issues, the file's container/codecs (e.g. from VLC's media info) helps a lot.

## Toolchain notes

These version constraints are not free choices, and changing them breaks the build:

- **Kotlin must be 2.3.x or newer.** `dev.g000sha256:tdl-coroutines` ships Kotlin 2.3 metadata;
  an older compiler rejects it outright.
- **`compileSdk` is 36** because `androidx.core` 1.17+ and `activity-compose` 1.11+ require it,
  and **AGP stays on 8.x** because `androidx.core` 1.19 / `lifecycle` 2.11 would drag in AGP 9
  and Gradle 9. `targetSdk` deliberately stays at 35.
- **media3 and NextLib versions are coupled**: `nextlib-media3ext` is published as
  `<media3-version>-<nextlib-version>`, so bumping one means bumping both to a pair that exists.
- **`org.json` is a test-only dependency and must stay that way.** Android already ships
  `org.json` at runtime, but the `android.jar` used to compile unit tests stubs every method to
  throw. Promoting it to `implementation` would ship a second copy of a library the platform
  already provides.

## Releasing

Releases are cut by pushing a tag. Nothing else produces a signed APK:

```bash
git tag v0.3.0
git push origin v0.3.0
```

`.github/workflows/release.yml` then derives `versionName` from the tag and `versionCode`
arithmetically (`major*10000 + minor*100 + patch`), so the code can only ever increase.
Android refuses to install an APK whose `versionCode` is lower than the one already on the
device, and a hand-rolled number is easy to get wrong.

Ordinary pushes and pull requests run `.github/workflows/ci.yml`, which tests and builds a
debug APK. That job never sees the signing key or the Telegram credentials, so a pull request
from a stranger cannot reach them.

### Repository secrets

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
a reviewed tag can reach the signing key.

### On the Telegram credentials

`api_id` and `api_hash` are compiled into the APK by `buildConfigField`, so anyone can extract
them from a published build. This is unavoidable and is how every Telegram client works;
Telegram Desktop's own `api_id` is public in its source. Register a **dedicated** app at
my.telegram.org for this project rather than reusing credentials tied to a personal account,
because an `api_id` that gets abused is acted on by Telegram, and it traces back to whoever
registered it.

Anyone building a fork should register their own and put them in `local.properties`. The build
works without them: the app just says so on the login screen instead of showing a QR code.
