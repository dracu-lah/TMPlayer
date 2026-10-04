# Building TMPlayer

## What you need

JDK 21 and Android SDK platform 36. JDK 17 still builds the Android app, but the desktop app
needs 21, so 21 is the one to install. Any recent Android Studio works, but the project builds
fine from the command line. The Android app runs on API 26 and up; the desktop app on Windows 10
and 11 and on 64-bit Linux.

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
./gradlew :core:jvmTest     # tests of the shared core, on a plain JVM
./gradlew test              # the Android app's own unit tests
./gradlew assembleDebug     # one debug APK, every architecture, emulator included
./gradlew assembleRelease   # signed release, needs keystore.properties
```

## The desktop app

The `:desktop` module is a Compose Desktop app over the shared `:core` and `:ui` modules, playing
through libmpv by way of [mediamp](https://github.com/open-ani/mediamp). It needs **JDK 21**
(`kotlin { jvmToolchain(21) }`: Gradle looks for an installed JDK 21 and does not download one) and
reads the same `TG_API_ID` and `TG_API_HASH` from `local.properties` as the Android build. No
Android SDK is involved in building it.

```bash
./gradlew :desktop:run            # the app, signed in with your own account
./gradlew :desktop:test           # its unit tests, including the fake-data render tests
./gradlew :desktop:runPlayerDev --args="--file /path/to/clip.mkv"
```

`runPlayerDev` opens the player on its own over a file on disk, through the same streaming path
the app uses for a Telegram file, so the player can be worked on without an account.
`--args="--file clip.mkv --growing 4"` feeds the file in at 4 MB a second, to see how it behaves
while a download is still arriving.

Installers come from the Compose Gradle plugin, and only for the system you build on: there is no
cross compilation, which is why the release builds each one on its own runner.

```bash
./gradlew :desktop:packageDistributionForCurrentOS   # whatever this OS makes
./gradlew :desktop:packageMsi                        # Windows
./gradlew :desktop:packageDeb :desktop:packageRpm    # Linux, for local use; releases ship the AppImage
./gradlew :desktop:createDistributable               # the plain app folder, no installer
```

They land under `desktop/build/compose/binaries/main/`. The AppImage and the Linux tarball
are wrapped from the `createDistributable` folder by the release workflow, with
`desktop/packaging/linux/package.sh`. The libmpv runtime pulled in is the one for
the host only, since each is 30 to 75 MB and the other systems' would never load.

Linux notes: on a tiling window manager set `_JAVA_AWT_WM_NONREPARENTING=1` or the window stays
blank; hardware decoding may fail on Fedora, where the bundled libva looks in Debian's driver
path, and software decoding takes over.

## Test devices

Cheap, low-spec Android TV is the target: a Mi TV Stick with 1 GB RAM, 8 GB storage and Android 9.
If it is smooth there, it is smooth everywhere. An Android TV emulator (API 28 or newer, x86_64,
1 GB RAM profile) is the reference environment.

## Toolchain notes

These version constraints are not free choices, and changing them breaks the build:

- **Kotlin must be 2.4.x or newer.** `dev.g000sha256:tdl-coroutines` 15 is built against
  `kotlin-stdlib` 2.4.10 and ships Kotlin 2.4 metadata; an older compiler rejects it outright.
- **`compileSdk` is 37** because `tdl-coroutines` 14+ declares that minimum in its AAR metadata,
  and **AGP stays on 8.x** because `androidx.core` 1.19 / `lifecycle` 2.11 would drag in AGP 9
  and Gradle 9. AGP 8.13 is only tested up to API 36.1, so `gradle.properties` silences its
  warning about 37; it builds and tests cleanly. `targetSdk` deliberately stays at 35.
- **media3 and NextLib versions are coupled**: `nextlib-media3ext` is published as
  `<media3-version>-<nextlib-version>`, so bumping one means bumping both to a pair that exists.
- **`org.json` belongs to `:core`'s jvm target only and must stay that way.** Android already
  ships `org.json` at runtime, and the Android side of `:core` compiles against that copy. Adding
  it to `commonMain` or to `:app` would ship a second copy of a library the platform already
  provides.
- **`:core` is Kotlin Multiplatform with two targets, android and jvm,** and nothing else. A
  source set shared only by JVM targets may use the JDK, so its `commonMain` is ordinary JVM
  Kotlin. It applies the Compose compiler plugin without drawing anything: that is what records
  its models as stable for the app's screens, as they were before they moved.

Cutting a release is a separate topic: see [RELEASING.md](RELEASING.md).
