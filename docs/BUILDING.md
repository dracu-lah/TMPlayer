# Building TMPlayer

## What you need

JDK 17 or newer (21 is what this is built with) and Android SDK platform 36. Any recent Android
Studio works, but the project builds fine from the command line. The app itself runs on API 26
and up.

## Telegram credentials

Telegram requires every client to identify itself, so you need your own credentials. They are free
and take about two minutes at [my.telegram.org](https://my.telegram.org), under *API development
tools*. Put them in `local.properties` at the repo root, and never commit them:

```properties
sdk.dir=/path/to/Android/Sdk
TG_API_ID=1234567
TG_API_HASH=your_api_hash
```

The build works without credentials. The app just says so on the login screen instead of drawing
a QR code, which is what CI does on every pull request.

`api_id` and `api_hash` are compiled into the APK by `buildConfigField`, so anyone can extract
them from a published build. This is unavoidable and is how every Telegram client works: Telegram
Desktop's own `api_id` is public in its source. Register a **dedicated** app at my.telegram.org
for this project rather than reusing credentials tied to a personal account, because an `api_id`
that gets abused is acted on by Telegram, and it traces back to whoever registered it.

## The commands

```bash
./gradlew test              # unit tests
./gradlew assembleDebug     # per-ABI debug APKs
./gradlew assembleRelease   # signed release, needs keystore.properties
```

## Test devices

Cheap, low-spec Android TV is the target: a Mi TV Stick with 1 GB RAM, 8 GB storage and Android 9.
If it is smooth there, it is smooth everywhere. An Android TV emulator (API 28 or newer, x86_64,
1 GB RAM profile) is the reference environment.

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

Cutting a release is a separate topic: see [RELEASING.md](RELEASING.md).
