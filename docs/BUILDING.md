# Building TMPlayer

## What you need

JDK 21 and Android SDK platform 37 with build tools 36.0.0 or newer (AGP 9.4's floor; Gradle
fetches missing SDK packages itself when the licences are accepted). JDK 17 is the least Gradle 9
and AGP 9 will run on and still builds the Android app, but the desktop app needs 21, so 21 is the
one to install. Newer JDKs are not supported by the build yet: JDK 25 fails, so point
`JAVA_HOME` (or Android Studio's Gradle JDK) at a 21. Any recent Android Studio works, but the project builds
fine from the command line. The Android app runs on API 23 and up; the desktop app on Windows 10
and 11, on 64-bit Linux and on macOS 26 or newer on Apple Silicon.

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

## Online subtitles (optional)

Online subtitles come from OpenSubtitles.com through one app key, `OPENSUBTITLES_API_KEY` in
`local.properties` (or the environment). Without it the build simply has no online subtitles: no
Settings section, no "Search online" in the subtitle picker. A fork should register its own key at
[opensubtitles.com/consumers](https://www.opensubtitles.com/consumers). Viewers never enter a key.
Downloads work without an account; signing in with a free one gives the viewer their own daily quota.

## The commands

```bash
./gradlew :core:jvmTest :ui:jvmTest   # tests of the shared core and Compose half, on a plain JVM
./gradlew :app:testDebugUnitTest      # the Android app's own unit tests (AGP 9 makes them for debug only)
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
./gradlew :desktop:packageReleaseMsi                 # Windows, ProGuard shrunk
./gradlew :desktop:packageDeb :desktop:packageRpm    # Linux, for local use; releases ship the AppImage
./gradlew :desktop:createReleaseDistributable        # the plain app folder, no installer, shrunk
```

The release tasks run ProGuard and land under `desktop/build/compose/binaries/main-release/`
(the plain `package*` and `createDistributable` tasks skip it and use `binaries/main/`). The AppImage and the Linux tarball
are wrapped from the `createReleaseDistributable` folder by the release workflow, with
`desktop/packaging/linux/package.sh`. The libmpv runtime pulled in is the one for
the host only, since each is 30 to 75 MB and the other systems' would never load.

Every release task first runs `./gradlew :desktop:verifyReleaseClasses`, which loads and links each
`com.tmplayer` class of ProGuard's jars with the bytecode verifier on (a few seconds), and the
packaged app's `--self-test` does the same on its bundled runtime. ProGuard writes each method's
stack map frames again, and in 1.28.1 it wrote some the JVM refused, which crashed the app on the
first show it drew (HX1). Should the check fail, split the method it names, as `SeriesPanel` was.

Linux notes: on a tiling window manager set `_JAVA_AWT_WM_NONREPARENTING=1` or the window stays
blank; hardware decoding may fail on Fedora, where the bundled libva looks in Debian's driver
path, and software decoding takes over.

## Test devices

Cheap, low-spec Android TV is the target: a Mi TV Stick with 1 GB RAM, 8 GB storage and Android 9.
If it is smooth there, it is smooth everywhere. An Android TV emulator (API 28 or newer, x86_64,
1 GB RAM profile) is the reference environment.

## Toolchain notes

These version constraints are not free choices, and changing them breaks the build. The whole
toolchain was moved forward on 2026-10-18 (Gradle 9.8.1, AGP 9.4.1, Kotlin 2.4.21, media3 1.11.1).

- **Kotlin must be 2.4.x or newer.** `dev.g000sha256:tdl-coroutines` 15 is built against
  `kotlin-stdlib` 2.4.10 and ships Kotlin 2.4 metadata; an older compiler rejects it outright.
- **AGP 9, because the libraries now require it.** `androidx.core` 1.19, Compose 1.12 (the BOM
  from 2026.09.00 on) and the rest of that generation declare `minAndroidGradlePluginVersion`
  9.1.0 in their AAR metadata, so staying on AGP 8 would freeze every AndroidX dependency.
  AGP 9.4 needs Gradle 9.6 or newer and supports API 37, so no `compileSdk` warning has to be
  silenced any more.
- **AGP 9 compiles Kotlin itself.** There is no `org.jetbrains.kotlin.android` plugin: `:app`
  applies `com.android.application` and the Compose compiler plugin, configures Kotlin under
  `kotlin { compilerOptions { } }`, and adds extra source folders through
  `android.sourceSets.<name>.kotlin.directories` (a folder added to `java` is no longer
  compiled as Kotlin). AGP 9 also only makes unit test tasks for the tested build type, so the
  app's tests are `:app:testDebugUnitTest` and there is no `testReleaseUnitTest`.
- **Kotlin and AGP compatibility.** The Kotlin Gradle plugin's own table lists 2.4.21 as fully
  supported with Gradle up to 9.7.0 and AGP up to 9.3.1. Gradle 9.8.1 and AGP 9.4.1 build, test
  and package everything here with no compatibility warning, so they are used; if a later Kotlin
  or AGP bump misbehaves, AGP 9.3.x on Gradle 9.7.x is the officially tested pair to fall back
  to. Check https://kotlinlang.org/docs/gradle-configure-project.html before moving either.
- **`:core` and `:ui` use `com.android.kotlin.multiplatform.library`**, AGP's plugin for the
  Android target of a Kotlin Multiplatform library, configured in `kotlin { android { } }`
  (`androidLibrary { }` is the deprecated spelling). AGP 9 does not allow
  `org.jetbrains.kotlin.multiplatform` beside `com.android.library`. The plugin has one variant
  and no build types, so neither module has a `BuildConfig` (the app's flags reach them through
  the platform interfaces), no Android resources (Android strings and drawables live in `:app`,
  the desktop's fonts and onboarding pictures in `ui/src/jvmMain/resources`, so the plugin's
  known gap with Compose Multiplatform resources does not apply) and no Android host tests:
  their tests are `jvmTest`.
- **`compileSdk` is 37** because `tdl-coroutines` 14+ declares that minimum in its AAR metadata.
  `targetSdk` deliberately stays at 35.
- **The FFmpeg extension is built from source** for releases: `scripts/build-media3ext.sh`
  builds nextlib-media3ext at its pinned commit with `scripts/media3ext/tmplayer-decoders.patch`
  (FFmpeg's mpeg4, msmpeg4v2, msmpeg4v3, vc1 and mjpeg decoders) into
  `build/media3ext/nextlib-media3ext-<version>-tm1.aar`, which `:app` uses when it is there. It
  needs NDK 25.2.9519653, CMake 3.22.1, meson, ninja and nasm, and about an hour with `JOBS=2`.
  Without the AAR a debug build takes nextlib from Maven Central (the same decoders minus those
  five) and says so; the release job and F-Droid's recipe pass `-PtmRequireMedia3ext=true`, which
  fails instead. A nextlib bump means checking that the patch still applies.
- **media3 and NextLib versions are coupled**: `nextlib-media3ext` is published as
  `<media3-version>-<nextlib-version>`, and its FFmpeg renderers are built against that exact
  media3, so bumping one means bumping both to a pair that exists. A nextlib bump also changes
  the GPL corresponding source the release links to, which `release.yml` checks (see
  RELEASING.md), and a mediamp bump does the same for the desktop's mpv runtime.
- **Media3 1.11 sizes PCM AudioTrack buffers by one fixed length** and ignores the old floor and
  ceiling. The player asks for 1.5 s with `setTargetPcmBufferDurationUs` (the stutter fix for
  FFmpeg decoded tracks on the stick); a future media3 that renames that again has to keep it.
- **AGP 9 shrinks resources inside R8** (`android.r8.optimizedResourceShrinking`). Compared with
  AGP 8 the release APK carries about 360 fewer resource entries: library layouts, strings and
  PNG fallbacks that R8 finds no reference to (unused leanback widgets, density PNGs behind
  vectors, which minSdk 23 always uses). A resource looked up by name with `getIdentifier` in a
  release build would need a keep rule.
- **`org.json` belongs to `:core`'s jvm target only and must stay that way.** Android already
  ships `org.json` at runtime, and the Android side of `:core` compiles against that copy. Adding
  it to `commonMain` or to `:app` would ship a second copy of a library the platform already
  provides.
- **`:core` is Kotlin Multiplatform with three targets: android, jvm and js.** Its
  `commonMain` is read from `src/pureMain/kotlin` and must stay pure Kotlin, with no JDK and no
  TDLib, because the js target (the web player's rules, `:core:webRules`) compiles it too. What needs TDLib or the JDK goes in
  `jvmSharedMain` (`src/commonMain/kotlin`), shared by android and jvm only. It applies the Compose
  compiler plugin without drawing anything: that is what records its models as stable for the
  app's screens, as they were before they moved.

Cutting a release is a separate topic: see [RELEASING.md](RELEASING.md).
