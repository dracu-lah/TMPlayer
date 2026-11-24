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
scripts/check-16kb.sh app/build/outputs/apk/release/app-universal-release.apk   # 16 KB page-size gate
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

The Linux downloads run on glibc 2.35 (Ubuntu 22.04 and newer), but the TDLib and libmpv that
tdl-coroutines and mediamp publish were built on Ubuntu 24.04 and need 2.38. So the release builds
its own: `scripts/build-tdlib-linux.sh` and `scripts/build-mpv-linux-2-35.sh` (podman and an
Ubuntu 22.04 image, about 20 minutes, the files land in `~/.cache/tmplayer-natives/{tdlib,mpv}`,
recipes under `natives/`), and the Gradle build swaps them into the two jars:

```bash
./gradlew :desktop:createReleaseDistributable -PtmplayerLinuxNatives=$HOME/.cache/tmplayer-natives
desktop/packaging/linux/glibc-floor.sh desktop/build/compose/binaries/main-release/app/TMPlayer
```

(`TMPLAYER_LINUX_NATIVES` does the same from the environment.) Every file must match its SHA-256 in
`natives/linux-2-35/PINS.txt`, or the build stops saying which one; a recipe change means new
hashes there. Without the property nothing is swapped: fine for `:desktop:run` and the tests on a
Linux that has glibc 2.38 or newer, but the floor check then fails. Only a Linux x64 host uses it.
CI builds the files once per recipe and caches them (`.github/actions/linux-natives`).

An app that ran before has the old libraries in `~/.local/share/TMPlayer/natives`, and mediamp never
overwrites a file there. So the same property also puts `BuildInfo.NATIVES_ID` (a SHA-256 of the
pinned lines, changing exactly when a shipped library does) into the build, and `os/NativeRefresh`
compares it with `tmplayer-natives.id` in that folder before mediamp unpacks: a different or missing
id removes the files in the runtime's own manifest (`mpv-natives-linux-x64.txt`), nothing else, then
writes the id. A build without the property has a blank id and leaves the folder alone. A read only
or full folder is logged (`NativeRefresh` in the Send logs file) and skipped.

Linux notes: on a tiling window manager set `_JAVA_AWT_WM_NONREPARENTING=1` or the window stays
blank; hardware decoding may fail on Fedora, where the bundled libva looks in Debian's driver
path, and software decoding takes over.

Links and launcher: TMPlayer does not register `tg://` anywhere (no MSI entry, no `x-scheme-handler`
line in the `.desktop` file): Telegram Desktop owns it, and a second handler would take the scheme
from it. Links reach the app by being pasted into the search or dropped on the window. The Linux
launcher's right click offers Continue watching, Downloads and Search (`tmplayer --go=resume`,
`--go=downloads`, `--go=search`, which a second launch hands to the running window).
`DesktopIntegrationTest` fails if a `tg://` line appears in the build file or the packaging folder.
On GNOME Wayland the window is drawn by XWayland, which can look soft at 125, 150 and 200 percent:
check it on a real session first, and if it does, `GDK_SCALE` (or a JBR with the `WLToolkit`
option) is the lever; neither is set by the launcher yet.

### Screen readers and the OS accessibility settings (WP-50)

What the app does by itself: pages, Home rows and Downloads sections are headings; a video card
carries its state (percent watched, downloading, kept, watched, selected) after its title; the
player's time bar is a slider (range, "1:02 of 1:30:00", Left and Right step 5 s) and its volume
slider is always in the tree. High contrast, Reduce motion and the OS text size follow the system
until the viewer chooses (Settings, Appearance; the choice is per computer in `desktop.properties`),
polled every 2 s on Windows (registry, `SystemParametersInfo`) and every 15 s on Linux
(`gsettings`) and macOS (`defaults`). Ctrl+= and Ctrl+- step the interface size, Ctrl+0 resets, and
Huge (1.5) is a desktop-only step.

What is not known, and is the manual pass to run on a real machine before anything is promised on
the site: how much of the Compose tree each reader sees.

- **Windows, NVDA and Narrator:** Compose Desktop exposes its semantics through the Java Access
  Bridge. The bridge is off by default: run `jabswitch -enable` from a JDK of the same major version
  (it writes `%USERPROFILE%\.accessibility.properties`), restart the screen reader and the app.
  The jpackage runtime does not ship `jabswitch`, so for now it is a step for the person running
  the pass; if NVDA reads the window, the installer can write that file.
- **Linux, Orca:** needs `java-atk-wrapper` (Fedora `java-atk-wrapper`, Debian
  `libatk-wrapper-java-jni`) on the runtime's class path and `assistive_technologies` set in
  `~/.accessibility.properties` or `$JAVA_HOME/conf/accessibility.properties`; the jpackage runtime
  has neither. Expect Orca to see only the window until that is shown to work.
- **macOS, VoiceOver:** Compose Desktop maps its semantics to NSAccessibility; no setup. Check Home,
  a grid, Settings and Downloads, and that the player's bar reads as a slider.

Record the result of each pass here, with the date and the app version, and the site's
accessibility line follows it (the user approves that copy).

### In-app trailers (the Chromium download)

Trailers play inside the desktop app through JCEF, the Java Chromium Embedded Framework, by way of
[jcefmaven](https://github.com/jcefmaven/jcefmaven) (`desktop/src/main/kotlin/.../trailer/`).
KCEF and compose-webview-multiplatform were looked at first: KCEF was archived by its author in
October 2025 ("not recommended"), and compose-webview-multiplatform's desktop side is KCEF.
Only JCEF's Java side is a build dependency (jcef-api, jcefmaven, commons-compress with its three
commons jars, Gson: about 3 MB of jars, 1.7 MB after ProGuard; JOGL is excluded, since only the
off-screen mode needs it). So the installer, the AppImage and the DMG barely change, and an
offline Gradle build (a Flatpak sources list, see `distribution/flathub.md`) gains only those
jars, never Chromium.

Chromium itself is downloaded on the viewer's first trailer, after a dialog that says what it is,
its size and the free space (Download or Watch on YouTube), from Maven Central (jcefmaven's GitHub
release as the second source), checked against the size and SHA-256 pinned in `CefNatives.kt`,
and unpacked under the app's data folder (`<data>/browser`, or `<storage location>/TMPlayer/browser`).
JCEF 146.0.10, Chromium 146.0.7680.179:

| System | Download | On disk | Supported |
| --- | --- | --- | --- |
| Linux x64 | 141 MB | 356 MB | yes, the AppImage's system; checked end to end on Fedora 43 (sway) |
| Linux arm64 | 158 MB | 455 MB | pinned; TMPlayer ships no arm64 Linux build yet |
| Windows x64 | 155 MB | 352 MB | yes, the MSI's system |
| Windows arm64 | 153 MB | 358 MB | pinned; the x64 MSI runs emulated there and takes the x64 Chromium |
| macOS arm64 | 130 MB | 305 MB | yes, the DMG's system |
| macOS x64 | 138 MB | 315 MB | pinned; Intel Macs are not offered a DMG |

The dialog asks for the download and the unpacked folder side by side plus 100 MB (535 to 715 MB
free, by system), because both exist for a moment. 32 bit systems and anything else open trailers
in YouTube.

JCEF starts Chromium on AWT's event thread, and where that never returns the window would freeze.
jcefmaven 152.0.6 did exactly that on Fedora 43 under sway (2026-11-17; 141 and 146 start there),
so the first start is tried in a child TMPlayer process (`--trailer-probe`, `trailer/CefProbe.kt`)
with a 20 s limit, and only a child that started lets the app start it itself. The answer is kept
beside the install (`probe.ok`, or `probe.failed` for a week).

To check it on a real machine without signing in:

```bash
./gradlew :desktop:runTrailerSmoke --args="--dir /tmp/tm-browser"            # downloads, installs, plays, exits 0 once it plays
./gradlew :desktop:runTrailerSmoke --args="--dir /tmp/tm-browser --compose"  # the same through the app's sheet, keys and bridge
./gradlew :desktop:runTrailerSmoke -PcefLog=/tmp/cef.log --args="--dir /tmp/tm-browser"   # with Chromium's own log
```

A jcefmaven bump in `gradle/libs.versions.toml` needs new pins: for each platform, download
`me/friwi/jcef-natives-<platform>/<tag>/jcef-natives-<platform>-<tag>.jar` from Maven Central,
check it against the `.sha1` beside it, and take its size, `sha256sum` and the sum of the file
sizes in its `.tar.gz` (`unzip -p <jar> '*.tar.gz' | tar -tzvf - | awk '{s+=$3} END {print s}'`).

## Test devices

Cheap, low-spec Android TV is the target: a Mi TV Stick with 1 GB RAM, 8 GB storage and Android 9.
If it is smooth there, it is smooth everywhere. An Android TV emulator (API 28 or newer, x86_64,
1 GB RAM profile) is the reference environment.

### Names dump (debug and preview builds)

How the videos in your chats are really named, and how the shelf groups them, in one command
instead of scrolling. Only the `debug` and `preview` builds have it (`app/src/dev`, declared in
`app/src/debug` and `app/src/preview`); a release compiles none of it. Sign in on the build first.
The dump runs in a foreground service (a "Dumping video names" notification), so the app may be
out of sight and the screen off; if logcat says the service was refused, open the app and keep it
open, since Android freezes a background app.

```sh
adb -s d3f7da23 shell am broadcast -n com.tmplayer.test/com.tmplayer.dev.NameDumpReceiver -a com.tmplayer.DUMP_NAMES
adb -s d3f7da23 logcat -s TMDump        # progress, counts only; "done:" when finished
adb -s d3f7da23 pull /sdcard/Android/data/com.tmplayer.test/files/dump/ <dir>
```

- No extras: the chats added in the app (every channel and group when nothing was picked).
- `--ez all true`: every channel and group of the chat list, at most 60; never People or Saved Messages.
- `--es chats "Title A,Title B"`: chats whose title contains one of these, any case.
- `--ei cap 3000`: videos read per chat (default 3000), paged like the chat screen, FLOOD_WAIT waited out.

The debug build is `com.tmplayer` instead of `com.tmplayer.test` in both paths. `dump/` holds
`names.jsonl` (one video per line: chat, kind, file name, caption's first line, size, length,
date, the parsed name and the shelf: `show:<key>` with `showSeason`/`showEpisode`,
`film-copy-group` or `file`), `summary.txt` (per chat counts, then LEFTOVERS: files whose name
holds a 1 to 3 digit number that is not a resolution, year or codec, the likely missed episodes)
and `done` (`ok ...` or `failed: ...`). Every file is written whole and then renamed into place.

## Toolchain notes

These version constraints are not free choices, and changing them breaks the build. The whole
toolchain was moved forward on 2026-10-18 (Gradle 9.8.1, AGP 9.4.1, Kotlin 2.4.21, media3 1.11.1).

- **Kotlin must be 2.4.x or newer.** `dev.g000sha256:tdl-coroutines` 15 is built against
  `kotlin-stdlib` 2.4.10 and ships Kotlin 2.4 metadata; an older compiler rejects it outright.
- **tdl-coroutines 15.0.0 can kill TDLib's receiver (HX34).** The wrapper decodes each update
  from TDLib's JSON on its own receive loop (`TdlEngine`, a private scope with a plain `Job` and no
  exception handler), and a field it requires but TDLib left out throws
  `NoSuchElementException: Key background is missing in the map` (or `Key photo ...`). The throw
  never reaches our code: it ends that loop, cancels the wrapper's scope and goes to the thread's
  uncaught handler, so the app dies, and swallowing it there would leave a client that never
  answers again. 17.0.0 (2026-10-27, TDLib 1.8.69) fixes the gift without a background (its
  #162); it is a two-release TDLib jump with API breaks, so it is its own task: bump `tdl`,
  fix the compile errors, then a phone and TV pass. 16.0.0 also fixes updates after an
  authorization close and the receiver start.
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
- **16 KB page size.** Every 64-bit native library in the APK must have its ELF LOAD segments
  aligned to `0x4000` or more, and the zip must keep an uncompressed `.so` on a 16 KB boundary, or
  the app does not load on a 16 KB device and Google Play refuses the upload (apps targeting
  Android 15 or later, from 2025-11-01). NDK 25.2, which builds the FFmpeg extension, links at 4 KB
  by default, so `scripts/media3ext/tmplayer-decoders.patch` passes `-Wl,-z,max-page-size=16384`
  (NDK r28 and newer default to it). TDLib's 32-bit library is `0x1000`, which is allowed: 16 KB
  devices run 64-bit apps. `scripts/check-16kb.sh <apk|aab|jar|so|folder>` checks all of it
  (`--self-test` tests the script itself with generated 4 KB and 16 KB fixtures); the release job
  runs it on the universal APK, and `docs/RELEASING.md` lists it before tagging. Run it by hand on
  a build that adds or bumps a native package before the PR opens: libVLC (PR2, 3.7.7 measured
  `0x4000` on its 64-bit libraries, `docs/distribution/libvlc-spike.md`) and libmpv (PR2b) are
  checked this way before they ship, and a desktop bundle folder takes the same call.
- **`compileSdk` is 37** because `tdl-coroutines` 14+ declares that minimum in its AAR metadata.
  `targetSdk` deliberately stays at 35.
- **Local network permission (DLNA casting).** Verified 2026-11-15 against the Android developer
  docs, not assumed. From Android 17 (API 37) an app that **targets 37 or higher** needs the runtime
  permission `ACCESS_LOCAL_NETWORK` (group `NEARBY_DEVICES`, so a user who allowed Bluetooth nearby
  devices is not asked again) for any TCP connection and any UDP unicast, multicast or broadcast to
  or from the LAN: SSDP discovery, the description fetch, SOAP and the cast server all count, and a
  failure is a timeout (TCP) or `EPERM` (UDP). An app that targets **36 or lower** is granted it
  implicitly with `INTERNET`, and the docs say not to declare or request it there, so at `targetSdk`
  35 nothing is needed and nothing is asked. `NEARBY_WIFI_DEVICES` was only the Android 16 opt-in
  test switch (`RESTRICT_LOCAL_NETWORK`) and is deprecated; it is not what to request. Google's own
  way round it is the Cast output switcher or an `NsdManager` picker (mDNS only), neither of which
  covers DLNA's SSDP. Test with `adb shell am compat disable RESTRICT_LOCAL_NETWORK <package>`
  (Android 16 opt-in) or by targeting 37 on an Android 17 device.
  Before raising `targetSdk` to 37 (CP15/U30): add `<uses-permission
  android:name="android.permission.ACCESS_LOCAL_NETWORK" />` to the manifest. The request flow is
  already in the code behind the target check (`LocalNetworkPermission` in `app/.../cast/`: asked
  when the Cast picker opens, a denied answer says so in the picker and starts no search), so
  that line is the only change. The manifest holds `CHANGE_WIFI_MULTICAST_STATE` for SSDP.
  Sources: <https://developer.android.com/privacy-and-security/local-network-permission> and the
  `ACCESS_LOCAL_NETWORK` entry of
  <https://developer.android.com/reference/android/Manifest.permission#ACCESS_LOCAL_NETWORK>.
- **The FFmpeg extension is built from source** for releases: `scripts/build-media3ext.sh`
  builds nextlib-media3ext at its pinned commit with `scripts/media3ext/tmplayer-decoders.patch`
  (the extra FFmpeg decoders listed below, the container reader and the light video mode further
  down) into `build/media3ext/nextlib-media3ext-<version>-tm4.aar`, which `:app` uses when it is
  there.
  It needs NDK 25.2.9519653, CMake 3.22.1, meson, ninja and nasm, and about 8 minutes for the four ABIs with
  `JOBS=4` on a 12 thread machine (one ABI takes about 2 once FFmpeg's sources are cached; `TM_ABIS=armeabi-v7a` builds just that
  one into a `-partial-` AAR that `:app` never uses; `GRADLE_WORKERS` sets Gradle's workers apart
  from `JOBS`). Without the AAR a debug build takes nextlib from Maven Central (only its own
  decoders, no light mode) and says so; the release job and F-Droid's recipe pass
  `-PtmRequireMedia3ext=true`, which fails instead. A nextlib bump means checking that the patch
  still applies. Any change to the patch bumps `TM_REVISION` (`tm4` now), which renames the AAR
  and the corresponding source archive the release notes link (see RELEASING.md).
- **The decoders in the AAR** (tm3 and tm4; the list is `ENABLED_DECODERS` in the patch's `ffmpeg/setup.sh`,
  the single source the host test reads too). Nextlib's own: Vorbis, Opus, FLAC, ALAC, PCM A-law and
  mu-law, MP3, AMR-NB and WB, AAC, AC-3, E-AC-3, DTS, MLP, TrueHD, H.264, HEVC, MPEG-1 and 2 video,
  VP8, VP9 and AV1 (dav1d); tm1's MPEG-4 Part 2, DivX 3 (msmpeg4), VC-1 and Motion JPEG. tm3 adds:
  old video, MPEG-1 (explicit), WMV 1, 2 and 3, H.263 and H.263+, Sorenson/FLV, Theora, DV,
  SVQ1, SVQ3, VP6 and VP6F, RealVideo 1 to 4, Cinepak; exotic video, ProRes, FFV1, Huffyuv,
  FFVHuff, Ut Video, MJPEG-B; audio, WMA 1 and 2, WMA Pro, WMA Lossless, MP1, MP2, Cook,
  RealAudio 1.0 and 2.0 (`real_144`, `real_288`), QCELP, GSM and MS GSM, SIPR, ATRAC3 and
  ATRAC3+, Speex, WavPack, TTA, Monkey's Audio, Nellymoser, all 35 PCM kinds and 54 ADPCM kinds
  that are LGPL; and, folded into tm4 before its first release, G.723.1 and Siren (Vivo's sound). Left out: the eight ADPCM decoders and AHX that FFmpeg builds only with
  `--enable-gpl` (circus, ima_escape, ima_hvqm2, ima_hvqm4, ima_magix, ima_pda, n64, psxc), and the
  `*_at` decoders (Apple's AudioToolbox). The AAR stays LGPL v3: no `--enable-gpl`, nothing
  non-free. The build also has every FFmpeg parser nextlib enables (68), the demuxers of the
  container reader below (tm4; nextlib enabled all 367 and twelve network protocols, which tm4
  drops) and no encoder, muxer, protocol or filter. Media3's extractors name only some of these codecs, so
  `FfmpegLibrary` maps Media3's MIME types where they exist (MPEG-1 audio layers to mp1 and mp2,
  H.263, MS GSM) and takes every other decoder by the MIME type `video/x-ffmpeg-<decoder>` or
  `audio/x-ffmpeg-<decoder>` (the decoder's own name: `real_144`, `atrac3plus`, `g722`, `msmpeg4`
  differ from the configure names), with the container's extra data in the format's
  initialization data; the sample rate, channels, block size, bit rate, bits per coded sample,
  FourCC and picture size reach FFmpeg through `ffcodecparams.h`. The formats Media3's extractors
  read reach the new decoders by Media3's MIME types (MPEG-1, H.263, MP2, MS GSM); the rest come
  from the container reader below.
- **The container reader** (tm4, C2): libavformat behind a Media3 extractor
  (`app/.../player/demux/FfmpegExtractor`, added after every Media3 extractor by `FfmpegExtractors`,
  so it only gets a file none of them sniffs). Its demuxers (`ENABLED_DEMUXERS` in the patch's
  `ffmpeg/setup.sh`, `kDemuxers` in `ffdemux.h`): ASF/WMV/WMA, RealMedia and ivr, xWMA, DV, NUT,
  MXF, NSV, WTV, Monkey's Audio, TTA, WavPack, AIFF, AU, CAF, VOC, Wave64, Ogg (Theora), MPEG
  program streams (MPEG-1 system streams, which Media3's PsExtractor does not sniff; a VOB stays
  Media3's), SMJPEG and Vivo. No protocol is compiled and nested opens are refused: the bytes come
  from Media3's `ExtractorInput`, a read elsewhere being a reopen of `TdDataSource` at that byte.
  Streams keep Media3's MIME type where a device decoder or a receiver may take them (MPEG-2, H.264
  and HEVC in Annex B, AC-3, E-AC-3, DTS, TrueHD, MP1 to MP3) and are named by their FFmpeg
  decoder otherwise. libavformat.so is about 0.56 MB raw per ABI. AVI, FLV and Matroska files whose
  codec Media3 drops (WMV in AVI, RealVideo in Matroska) are still Media3's, without that track.
- **Host tests of the container reader**: `run-demux-tests.sh <dir>` (after `build-host-ffmpeg.sh`)
  makes a 6 s clip per container with the host's ffmpeg and runs the AAR's own `ffdemux.h` over it
  (`demuxtest.cpp`): the probe, every packet in range, the first packets of each stream decoded,
  a seek to the middle that reads a small part of the file and lands at or before the target, and
  12 damaged copies (cut, flipped, zeroed, random) that must not crash or hang. ivr, xWMA, NSV,
  Vivo, SMJPEG, APE and RealMedia with RealVideo 3/4 or Cook are untested (no host muxer or
  encoder).
- **Host tests of the decoders** run without a device: `scripts/media3ext/bench/build-host-ffmpeg.sh
  <dir>` builds the AAR's FFmpeg 9.0.1 for the host plus a reference `ffmpeg` of the same version,
  then `run-codec-tests.sh <dir>` makes a clip per decoder with the host's ffmpeg, decodes it the
  way the JNI glue sets a decoder up (`codectest.cpp` with the patch's own `ffcodecparams.h`) and
  compares the md5 with the reference's, then decodes the same clip eight times with its extra
  data and packets damaged (flipped, zeroed, cut, random) to show no decoder crashes or hangs on a
  bad file. It lists the decoders no clip could be made for (no host
  encoder: WMV 3, VP6, RealVideo 3 and 4, SVQ3, MJPEG-B, MP1, WMA Pro and Lossless, Cook, RealAudio
  2.0, QCELP, SIPR, ATRAC3, Monkey's Audio and most rare ADPCM and PCM kinds) as untested.
- **The light video mode** (HX2c) is for pictures the device decodes in software and cannot keep
  up with: `DeviceDecode` hands NextLib `DecodeJudge.wantsLightDecode` through
  `FfmpegLibrary.setLightVideoPolicy` (by reflection, so the Maven Central fallback still
  compiles), asked each time a video decoder is created. For those formats AV1 runs with dav1d's
  frame threads (frame delay 2) instead of `AV_CODEC_FLAG_LOW_DELAY`, film grain is exported
  instead of drawn, and frames go to the window as YV12 (a copy, or a rounded 10 to 8 bit shift
  with NEON, in `fflight.h`) instead of swscale's single-threaded RGBA conversion; a window that
  refuses YV12, and an emulator, gets RGBA after the same 8-bit step. A hardware decoder or a
  phone the table says keeps up sees no change. `adb logcat -s ffmpeg_jni` shows "light mode"
  when it is on; `adb shell setprop debug.tmplayer.av1_frame_delay 3` tries another frame delay
  on a device without a rebuild. `scripts/media3ext/bench/` times all of it on the host
  (`build-host-ffmpeg.sh`, then `run-bench.sh`), with no device or emulator.
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
