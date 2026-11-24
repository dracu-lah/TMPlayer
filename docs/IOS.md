# TMPlayer for iPhone and iPad

The iOS and iPadOS port started from TMPlayer for Apple by Pietro Giuseppe D'Amico
(github.com/Killua99x/TMPlayer-for-apple, GPL-3.0).

That fork took TMPlayer 1.28.5 and added an iOS and iPadOS app; its code was brought into this
repository on 2026-11-17 and adapted to TMPlayer's current code (see "Changelog of the port"). Every
copyright and licence header it carries is kept. Here the app is TMPlayer itself: TMPlayer's name,
icon, links, support page and contact.

One universal app for iPhone and iPad, iOS and iPadOS 26.4 or later, installed by sideloading
(AltStore, SideStore, Sideloadly or LiveContainer). It is not in the App Store.

## Contents

- [How the port is built](#how-the-port-is-built)
- [Identifiers](#identifiers)
- [Setting up a Mac](#setting-up-a-mac)
- [Building and running](#building-and-running)
- [Tests](#tests)
- [What Linux can check](#what-linux-can-check)
- [Installing on a device](#installing-on-a-device)
- [Continuous integration](#continuous-integration)
- [Releasing](#releasing)
- [The AltStore source](#the-altstore-source)
- [Lowering the minimum iOS](#lowering-the-minimum-ios)
- [Changelog of the port](#changelog-of-the-port)
- [Known issues](#known-issues)
- [Needs a Mac or an Apple account (later)](#needs-a-mac-or-an-apple-account-later)

## How the port is built

| Part | Where | What it is |
| --- | --- | --- |
| Xcode project | `iosApp/` | The SwiftUI shell (`TMPlayerApp.swift`), the video engines (`Player/Engines`), the app icon, Info.plist, Swift tests. `project.yml` is the source (XcodeGen); `TMPlayer.xcodeproj` is generated from it and committed. |
| Kotlin app | `:ios` (`ios/`) | Android's phone screens, data layer and player, compiled for iOS from `app/src/main/java` at build time, over stand-ins for the Android and Media3 APIs they call (`ios/src/iosStandIns`), plus iOS versions of the Android system pieces (`ios/src/iosMain`). |
| Framework | `:ios:kit` (`ios/kit/`) | `TMPlayerKit`, the static framework Xcode links: only the few classes Swift calls (the activity shell, the video engine bridge, logs, the performance probes). |
| Shared code | `:core`, `:ui` | `iosMain` and `iosStandIns` source sets: Foundation versions of the platform pieces, and stand-ins for the JDK (`java.io`, `java.net`, `java.time`, `org.json`) so `core/src/commonMain` compiles as it is. |
| Variant | `gradle/ios-apple-variant.gradle.kts` | The few exact text replacements iOS needs in shared and Android sources, applied to copies at build time. A replacement whose text is gone stops the build. |
| Real locks | `gradle/ios-real-locks.gradle.kts` | `@Synchronized` locks nothing on Kotlin/Native: files that use it are compiled for iOS from copies with real locks. |
| Release | `iosApp/release/`, `.github/workflows/apple-release.yml`, `altstore/source.json` | The unsigned IPA, the GitHub release and the AltStore source. |

### Chosen instead, for now

Rather than rewrite the JVM half of `:core` and Android's screens for Kotlin/Native, the port compiles
them as they are against stand-ins: small Kotlin versions of the JDK, Android and Media3 classes
they use, backed by Foundation, UIKit and the app's video engine. A change to `app/` or `core/` that
uses an API no stand-in has breaks only the iOS build, so the iOS build is checked on a Mac (or the
copies on Linux, see "What Linux can check") before such a change is merged. Files that cannot use
stand-ins have iOS versions with the same names in `ios/src/iosMain`; `ios/build.gradle.kts`
lists which Android files are copied and which are left out.

### Android's phone screens on iOS

`:ios`'s `androidScreensForIos` copies `app/src/main/java/com/tmplayer/ui/**`, `data/**`,
`MainActivity.kt` and `App.kt` (minus Android system integration: services, notifications, TV
channels, the installer, crash exits, storage access) and the `nocast` flavour. On the way it
makes four mechanical rewrites, listed in that task: `decorFitsSystemWindows` to
`usePlatformInsets`, ViewModel factories to `KClass`, `Dialog` to one that closes on the back
swipe, and the Home carousel's frame-per-vsync timer to a plain timer.

### In app trailers on iOS

Android's trailer player (`app/.../ui/trailer/TrailerPlayer.kt`, `TrailerWebPage.kt`) is a WebView
and is left out of the copy; `TrailerControls.kt` (the state machine `TrailerSession` over core's
`TrailerRules`, the remote's keys, the mobile data rule `trailerMayStream`) has no Android types
and is copied as it is. `MainActivity` calls `TrailerPlayerRoot`, which `:ios` supplies in
`ios/src/iosMain/.../ui/trailer/TrailerPlayerRoot.kt` (`IosTrailerOpener`, provided through
`LocalTrailerOpener`). Kotlin builds the page (`TrailerEmbed.pageHtml`), runs the 12 s wait and the
fallback to the YouTube app or Safari; `:ios:kit`'s `Trailers.install` (same shape as `Engines`)
hands Swift's `TrailerSheetPresenter` to it, and `iosApp/TMPlayer/Player/TrailerSheet.swift` shows
the sheet: a `WKWebView` with `https://www.youtube-nocookie.com` as base URL, the
`TMPlayerTrailer` message handler, inline media with no gesture requirement (the tap was it), a
navigation policy limited to YouTube's hosts, a non-persistent data store, paused when the app
resigns active, torn down when the sheet goes. Setting off, offline, or "On mobile data: Never" on
a metered connection never reach Swift.

### Android's phone player on iOS, unchanged

`androidPlayerForIos` copies Android's player (`PlayerActivity` and the files it needs) over the
Media3 stand-ins (`ios/src/iosStandIns/kotlin/androidx/media3`), whose `ExoPlayer` plays on the app's
video engine (`ios/src/iosMain/kotlin/com/tmplayer/ios/media3/EngineExoPlayer.kt`).

### Player: libmpv through MPVKit

Two engines behind one interface (`iosApp/TMPlayer/Player/Engines`, `ChoosingEngine.swift`):

- **Apple's AVPlayer** for MP4 and MOV with codecs it plays: the least battery.
- **mpv** for everything else (MKV, AVI, TS, PGS and VobSub subtitles), through MPVKit's `MPVKit`
  product, its LGPL build. Never `MPVKit-GPL`.

Both read the video from TDLib while it downloads (`TdStreamProtocol.swift`, `AppleStreamLoader.swift`).
`TMPlayerTests/EngineConformanceTests.swift` holds every engine to the same rules.

### Not in scope

Not on iPhone and iPad yet: casting (Google Cast, DLNA), downloads
while the app is in the background, crash reports (Sentry's Android SDK), the in-app updater
(AltStore or SideStore offers updates), Android TV features. Videos only, as everywhere in TMPlayer.

### AirPlay and picture in picture (WP-31, 2026-11-24)

For the files Apple's player plays (MP4 and MOV), the player's More panel has an **AirPlay** line and
**Picture in picture**; both belong to AVPlayer, so for a file mpv plays (an MKV, say) the line is
still there and says why not when pressed ("AirPlay works for MP4 and MOV videos", "Picture in
picture works for MP4 and MOV videos"). The web has no cast (Remote Playback cannot cast an MSE or
service worker stream, and relaying bytes is ruled out by Telegram's terms).

- `AppleEngine.swift`: `allowsExternalPlayback` and `usesExternalPlaybackWhileExternalScreenIsActive`
  on the AVPlayer, false when the player is made and set to the player's savable verdict
  (`setExternalPlaybackAllowed`, from `PlayerActivity`'s `savable` through `ExternalPlaybackVerdict`
  and `IosExternalPlayback.externalPlaybackAllowed`, SEC2-01): Control Center's and the lock screen's
  route button reach AVPlayer without the More panel's row, so a chat that restricts saving, or one
  not asked yet, never plays on an AirPlay screen; a verdict that turns false while the video is on
  one pauses it. Screen mirroring is the system's and stays out of scope (it shows the whole screen,
  as for any app). Mac check: a restricted chat's MP4, Control Center's route button lists the Apple
  TV but the video stays on the phone; a savable one goes over; an `AVRoutePickerView` the surface holds in its corner out of sight, which the
  row presses (`showAirPlayPicker`); an `AVPictureInPictureController` on the same `AVPlayerLayer`
  (`startPictureInPicture`, the delegate reports opening and closing). The background observer keeps
  the layer on the player while the corner window is up, the picture is on an AirPlay screen, or the
  system may open the window by itself and the video plays; otherwise it detaches it as before, so
  the sound goes on.
- `ChoosingEngine.swift` implements `ExternalPlaybackEngine` (`ios/kit/.../VideoEngine.kt`) and answers
  for the engine playing; `NativePlaybackEngine` hands it to `android.app.IosExternalPlayback`
  (`ui/src/iosStandIns`), which the Android player reaches through `hasAirPlay`,
  `showAirPlayPicker`, `pictureInPictureRefusal` (`LegacyApiCompat.kt`, with iOS twins in
  `AndroidSystemOnIos.kt`; Android answers false or null) and the activity's
  `enterPictureInPictureMode`, `setPictureInPictureParams` (the "Picture in picture when leaving"
  setting reaches `canStartPictureInPictureAutomaticallyFromInline`) and `onPictureInPictureModeChanged`.
- Rules: the AirPlay line is left out where the chat restricts saving (and until that is known),
  and for a stream while offline (a saved video plays from the phone); the audio session is
  `.playback` and `UIBackgroundModes` has `audio` already (`PlaybackAudio.swift`, `Info.plist`); a PIN
  profile's Now Playing is the existing `NowPlaying.kt`'s.
- Not verifiable on Linux: the Swift (Mac), `ios/kit` (its cinterop does not build on Linux), and
  AirPlay of a video streamed from Telegram, which AVPlayer reads through a custom resource loader
  (`AppleStreamLoader`): the receiver cannot open that address, so iOS may mirror instead of
  handing the stream over. A saved video (a file path) has no such limit. Check on an iPhone with
  an Apple TV, and picture in picture with the screen locked and from the Home screen.
- Mac checks from the second playback review: with a chat that restricts saving open, Control
  Center's AirPlay must not show the video on the Apple TV (PB2-04, the engine's
  `allowsExternalPlayback`); and the AirPlay line must open the route list, since it presses a
  button inside `AVRoutePickerView` that a new iOS may move: where none is found the line should
  say so rather than do nothing (PB2-21, `showAirPlayPicker` returning false reaches
  `playerAirplayNotForThisFile`).

Fingerprint or face on the app lock (Android has it, WP-41) is not on iOS yet: `BiometricPrompter` there is a no-op
(`ios/src/iosMain/.../ui/lock/BiometricPrompter.kt`), LocalAuthentication later.

## Identifiers

Decision (2026-11-17): the iOS app is `org.tmplayer.ios`, set once and never changed after the
first release, since AltStore and SideStore update an installed app by it.

| Where | Value |
| --- | --- |
| App bundle identifier (`iosApp/project.yml`, `TMPlayer.xcodeproj`, `BuildConfig.APPLICATION_ID` in `ios/build.gradle.kts`, the fallback in `ui/src/iosStandIns/.../Content.kt`) | `org.tmplayer.ios` |
| Unit and UI test bundles | `org.tmplayer.ios.tests`, `org.tmplayer.ios.uitests` |
| AltStore source identifier (`altstore/source.json`) | `org.tmplayer.ios.source` |
| Release tags | `apple-v<version>[-<build>]`, such as `apple-v1.28.5` and `apple-v1.28.5-2` |
| IPA | `TMPlayer-ios-<version>[-<build>].ipa` |

The fork's `io.github.killua99x.tmplayer` is not used, so the two apps install side by side and
neither updates the other. `python3 -I -m unittest discover -s iosApp/release` fails if any of these
places disagree.

## Setting up a Mac

`scripts/ios-setup.sh` checks all of this and prints what is missing (it changes nothing).

1. **A Mac with Apple silicon.** The Kotlin framework is built for `iosArm64` and
   `iosSimulatorArm64` only. 16 GB of memory is comfortable; 8 GB works for Debug builds and
   manages the Release link (about 6 GB of heap) only with nothing else open.
2. **Xcode with the iOS 26.4 SDK or later**, from the App Store. Open it once, accept the licence,
   add the iOS simulator runtime (Settings, Components), then
   `sudo xcode-select -s /Applications/Xcode.app`.
3. **JDK 21**, as for every TMPlayer build (docs/BUILDING.md): `brew install openjdk@21`. Xcode's
   build phase looks for it in `JAVA_HOME`, then `/usr/libexec/java_home -v 21`, then Homebrew.
4. **Telegram's api id and hash**, your own, from <https://my.telegram.org> (API development
   tools), in `local.properties` at the repository's root, the same file Android reads:

   ```properties
   TG_API_ID=1234567
   TG_API_HASH=0123456789abcdef0123456789abcdef
   # Optional, as on Android:
   TMDB_API_KEY=
   OMDB_API_KEY=
   OPENSUBTITLES_API_KEY=
   ```

   Never commit them (`local.properties` is ignored). Without them the app builds and says on its
   sign in screen that it cannot sign in. On Android `local.properties` also needs `sdk.dir`; an
   iOS build does not.
5. **XcodeGen**, only to change `iosApp/project.yml`: `brew install xcodegen`, then
   `cd iosApp && xcodegen generate`, and commit the regenerated `TMPlayer.xcodeproj` with it.
6. **MPVKit** comes by itself: Xcode resolves the Swift package (`MPVKit` 1.0.0, pinned in
   `Package.resolved`) on the first build. Nothing to install.

The iOS targets are on by default on a Mac (`HostManager.hostIsMac`); `-Ptmplayer.ios=false` turns
them off for an Android or desktop build there.

## Building and running

- **Xcode:** open `iosApp/TMPlayer.xcodeproj`, pick the `TMPlayer` scheme and an iPhone or iPad
  simulator, Run. The first build compiles the Kotlin framework (`:ios:kit:embedAndSignAppleFrameworkForXcode`,
  a build phase) and takes several minutes; later ones only what changed.
- **Command line, simulator:**

  ```sh
  xcodebuild -project iosApp/TMPlayer.xcodeproj -scheme TMPlayer \
    -destination 'platform=iOS Simulator,name=iPhone 17' build
  ```

- **On your own iPhone or iPad from Xcode:** Signing and Capabilities, pick your Apple ID's
  personal team (a free account works for 7 days), connect the device, Run.
- **Unsigned IPA, as the release makes it:** `iosApp/release/build-ipa.sh <version> <build> <folder>`,
  for example `iosApp/release/build-ipa.sh 1.28.5 1 ~/Desktop/ipa`. It builds the Release framework
  alone (stopping the Gradle and Kotlin daemons around it, `TM_KOTLIN_HEAP` sets the heap, 6000m by
  default), archives without signing, checks the version and build inside the app and writes the IPA,
  its dSYMs and `SHA256SUMS.txt`.
- **Screenshots build:** `-PtmPromo=true` (or `ORG_GRADLE_PROJECT_tmPromo=true` for Xcode) compiles
  the promo fixture in; launch with `-promo`. `iosApp/screenshots/make-videos.sh` makes the video the
  player screenshot plays. This is the way to take real simulator pictures, which should replace the
  ones in "Pictures for the source" below once a Mac is at hand.

## Tests

| What | Command | Where |
| --- | --- | --- |
| Shared Kotlin tests on iOS (the `js-test-sources.txt` set of `core/src/jvmTest`) | `./gradlew :core:iosSimulatorArm64Test` | Mac |
| The View toolkit's stand-ins against Android's behaviour | `./gradlew :ui:iosSimulatorArm64Test` | Mac |
| The Media3 stand-ins and iOS pieces | `./gradlew :ios:iosSimulatorArm64Test` | Mac |
| Every video engine against the same rules (`EngineConformanceTests`, `Media3BridgeTests`) | `xcodebuild test -project iosApp/TMPlayer.xcodeproj -scheme TMPlayer -destination 'platform=iOS Simulator,name=iPhone 17'` | Mac |
| The accessibility audit on the sign in, Home, a video's page, the player, its status sheet and Settings (`AccessibilityAuditTests`, `performAccessibilityAudit`; `TM_LARGER_TEXT=1` adds Larger Text; the sign in alone from GitHub with `gh workflow run apple-ci.yml -f accessibility_audit=true`) | `xcodebuild test -project iosApp/TMPlayer.xcodeproj -scheme TMPlayerDeviceCheck -destination 'platform=iOS Simulator,name=iPhone 17' -only-testing:TMPlayerUITests/AccessibilityAuditTests` | Mac (screens after the sign in need a signed in simulator or device) |
| On a real device, with a viewer's taps (`PlayerOnDeviceTests`, `AboutOnDeviceTests`, `ResourcesOnDeviceTests`) | `xcodebuild test -project iosApp/TMPlayer.xcodeproj -scheme TMPlayerDeviceCheck -destination 'id=<device>'` | Mac and device |
| Release scripts, the AltStore source (fields, order, checksums, news, screenshots, schema with `pip install jsonschema`), identifiers, versions, the Apple workflows' guards, no committed secrets | `python3 -I -m unittest discover -s iosApp/release` | any |
| The same, the Kotlin compile for iOS and (when `APPLE_MAC_CI` is `true`) the Xcode build and Swift tests | `.github/workflows/apple-ci.yml` | GitHub, see "Continuous integration" |
| The iOS copies of shared and Android sources (variant replacements still apply) | see below | any |
| Shared fixes the port brought (sign in, logs, player row, large text) | `./gradlew :core:jvmTest :app:testDebugUnitTest` | any |

## What Linux can check

No Xcode, no simulator, no link. What runs on Linux:

`.github/workflows/apple-ci.yml` runs all of this on every pull request ("Continuous integration").

```sh
python3 -I -m unittest discover -s iosApp/release
python3 -I iosApp/release/altstore_source.py check --project iosApp/project.yml
# The source against its JSON schema (pip install jsonschema):
python3 -I iosApp/release/validate_schema.py altstore/source.json altstore/source.schema.json
# The iOS copies of shared and Android sources (the variant's replacements still apply):
./gradlew -Ptmplayer.ios=true :core:appleSourcesForIos :ui:appleSourcesForIos \
  :ios:androidScreensForIos :ios:androidPlayerForIos --max-workers=3
# The Kotlin for iOS, compiled (Kotlin/Native cross-compiles klibs on Linux; nothing links):
./gradlew -Ptmplayer.ios=true :core:compileKotlinIosArm64 :ui:compileKotlinIosArm64 \
  :ios:compileKotlinIosArm64 --max-workers=3
scripts/ios-setup.sh    # says a Mac is needed, exits 2
```

With the property, Kotlin configures the iOS targets and disables what this host cannot build:
`:ios:kit`, whose cinterops (the performance sampler) need a Mac, the framework link and the
simulator tests. The first run downloads the Kotlin/Native toolchain (about 800 MB in `~/.konan`).
Without `-Ptmplayer.ios=true` nothing about iOS is configured: Linux and Windows builds take the
same time as before.

## Installing on a device

The IPA is unsigned; the installer signs it with your own Apple ID. TMPlayer is an unofficial player
for the videos already in your own Telegram chats and is not affiliated with Telegram.

| Channel | What you do | Updates |
| --- | --- | --- |
| **AltStore** | Add TMPlayer's source (below), install TMPlayer from it | Offered in AltStore, installed from there |
| **SideStore** | The same source, the same steps | Offered in SideStore |
| **Sideloadly** | Download the IPA from a release tagged `apple-v...` and sign it with your Apple ID | None: download the next one |
| **LiveContainer** | Install the IPA from the release into LiveContainer | None: download the next one |
| AltStore PAL (EU) | Needs a notarized build: not offered yet | |
| TrollStore | Not offered: it works only on a few older iOS versions, none of them iOS 26.4 or later | |
| TestFlight, App Store | Not offered yet: they need an Apple Developer account (last section) | |

A free Apple ID's signature lasts 7 days, and a free account may have three sideloaded apps at once
(AltStore and SideStore count as one); AltStore and SideStore refresh the signature in the
background while the phone and the computer or VPN they need are reachable.

### The source to add

```text
https://raw.githubusercontent.com/dracu-lah/TMPlayer/main/altstore/source.json
```

In AltStore or SideStore: Sources, plus, paste the address. The two apps also open it from a link, which
adds the source to the app that handles the link:

```text
altstore://source?url=https://raw.githubusercontent.com/dracu-lah/TMPlayer/main/altstore/source.json
sidestore://source?url=https://raw.githubusercontent.com/dracu-lah/TMPlayer/main/altstore/source.json
```

`python3 -I iosApp/release/altstore_source.py links --repo dracu-lah/TMPlayer` prints the three lines
(use `--branch` for another branch). GitHub's Markdown, in a README or a release's text, drops links
that are not http or https, so these two work as an `<a href>` on a web page (tmplayer.org, whose
install page is the place for them, later), in a message, or typed in Safari. The buttons for such a
page:

```html
<a href="altstore://source?url=https://raw.githubusercontent.com/dracu-lah/TMPlayer/main/altstore/source.json"><img alt="Add to AltStore" src="https://img.shields.io/badge/Add%20to-AltStore-2AABEE"></a>
<a href="sidestore://source?url=https://raw.githubusercontent.com/dracu-lah/TMPlayer/main/altstore/source.json"><img alt="Add to SideStore" src="https://img.shields.io/badge/Add%20to-SideStore-2AABEE"></a>
```

(shields.io badges stand in until TMPlayer's own button pictures exist.) SideStore reads AltStore's
source format as it is, so one file serves both; the release's text lists the same source and links.

### Sideloadly, and installing the IPA yourself

Download `TMPlayer-ios-<version>.ipa` from the latest release whose tag starts `apple-v`, and check it
against `SHA256SUMS.txt` on the same page before installing it:

```sh
shasum -a 256 -c SHA256SUMS.txt     # macOS (sha256sum -c on Linux; certutil -hashfile <file> SHA256 on Windows)
```

(The file also lists the dSYMs zip; the check reports it missing if you did not download it, which is
fine.) In Sideloadly drag the IPA in, choose the device, sign in with your Apple ID and Start. The app
is not updated by itself: a newer IPA installed over it keeps its data, since the bundle identifier is
the same.

### LiveContainer

LiveContainer runs an IPA inside itself, so TMPlayer is installed there by opening the same IPA from the
release (Apps, plus, pick the file). It is the same file as for Sideloadly, with the same checksum, and
it is not updated by itself. Unverified here: whether the player's sound with the screen locked and the
Telegram sign in's storage work as they do in an installed copy (see the last section).

## Continuous integration

`.github/workflows/apple-ci.yml` runs on every pull request and push to `main` that touches `ios/`,
`iosApp/`, `altstore/`, `gradle/`, the sources of `core`, `ui` and `app` (the phone screens and player
are compiled for iOS from copies, so a change there can break only the iOS build), and by hand. It
never sees a secret: Telegram's api id and hash are dummies made at run time.

| Job | Runs | Does |
| --- | --- | --- |
| `linux` | always, on `ubuntu-latest` | `python3 -I -m unittest discover -s iosApp/release`; `altstore_source.py check --project iosApp/project.yml`; the source against `altstore/source.schema.json` (`validate_schema.py`, `jsonschema` in a virtual environment); the dash rule on the iOS files; `bash -n` and `sh -n` on the scripts; then the iOS copies and the three klibs, `./gradlew -Ptmplayer.ios=true :core:compileKotlinIosArm64 :ui:compileKotlinIosArm64 :ios:compileKotlinIosArm64` (`~/.konan`, about 800 MB, cached, and Gradle's cache by `setup-gradle`) |
| `macos` | only when the repository variable `APPLE_MAC_CI` is `true` | `xcodegen generate` (a difference from the committed project is a warning), the newest iPhone simulator by its identifier, `xcodebuild build` for it and `xcodebuild test -only-testing:TMPlayerTests`, with `~/.konan`, Gradle and the Swift packages (`~/spm`, keyed on `Package.resolved`) cached; the `.xcresult` is uploaded |

Variables: `APPLE_MAC_CI` (`true` turns the macOS job on; unset, a fork without macOS minutes is not
charged), `APPLE_CI_RUNNER` (a runner label, `macos-26` when unset), `APPLE_CI_KOTLIN_HEAP` (the Kotlin
compiler's heap for the Debug framework, `3g` when unset). Every action is at a major version the other
workflows already use (a test in `test_altstore_source.py` keeps it so). The Linux job's Kotlin compile
is what T9 ran by hand (`docs/IOS.md`, "What Linux can check"); the macOS job has not run yet (last
section).

## Releasing

The user tags and approves; nothing here is done by an agent (AGENTS.md, publishing rules).

1. Write the version's notes in `iosApp/CHANGELOG.md` as `## <version>` (first build of a version)
   or `## <version> build <n>` (an iOS-only fix). The release stops if the section is missing.
2. Put iPhone and iPad pictures, if there are new ones, in `iosApp/screenshots/iphone/` and `/ipad/`
   as PNG or JPEG (see "Pictures for the source" in "The AltStore source"); the release lists them in
   the source by name order.
3. Repository secrets: `TG_API_ID`, `TG_API_HASH` (required), `TMDB_API_KEY`, `OMDB_API_KEY`,
   `OPENSUBTITLES_API_KEY` (optional). Variables, all optional: `APPLE_RELEASE_RUNNER` (a runner
   label, `macos-26` when unset), `APPLE_RELEASE_KOTLIN_HEAP` (`6000m` when unset),
   `APPLE_DEVELOPER_NAME` (the developer name AltStore shows, `TMPlayer` when unset),
   `APPLE_SOURCE_PUSH` (see step 5), `APPLE_RELEASE_REPO` (the `owner/name` the workflow runs in,
   `dracu-lah/TMPlayer` when unset).
4. Tag `apple-v<version>` or `apple-v<version>-<build>` and push the tag. The version is x.y or
   x.y.z. A test version is `apple-v<version>-beta.<n>` (see "Test versions" below).
5. `.github/workflows/apple-release.yml` (only in `dracu-lah/TMPlayer`, a fork skips it), one at a time
   (`concurrency`), runs the release scripts' tests and `check`, builds the unsigned IPA and dSYMs with
   `build-ipa.sh`, writes `SHA256SUMS.txt` for both and verifies it, creates the GitHub release with the
   notes, how to install, the checksums and the corresponding source links (this repository at the tag,
   MPVKit at its version), then adds the version to `altstore/source.json` with
   `iosApp/release/altstore_source.py add` (SHA-256, size, minimum iOS from the IPA, a news post) and
   checks it with `--published`. The source change reaches the default branch by one of two ways:
   - `APPLE_SOURCE_PUSH` is `true`: pushed straight to it; if the branch rules refuse the push
     (pull requests required), the next way is used.
   - otherwise: pushed to a branch `altstore-source-<tag>-<run id>` and opened as a pull request with
     `gh pr create` (the repository setting Settings, Actions, General, "Allow GitHub Actions to
     create and approve pull requests" must be on; if it is not, the job fails after the release with
     the branch name, and a pull request is opened by hand). AltStore and SideStore see the version
     once the pull request is merged.

A run started again for the same tag replaces the release's files and keeps its text; the source
entry for the same version and build is replaced, and keeps its date when the IPA is the same.

### Test versions

Decision (user, 2026-11-18): `apple-v1.28.6-beta.2` builds a test version of the iPhone and iPad app,
as the Android, TV and desktop `v1.28.6-beta.2` does. Same app (bundle id `org.tmplayer.ios`), so it
installs over the release and the release installs over it. Testers get it from the release page
only: no source, so AltStore and SideStore never offer it.

| | `apple-v1.28.6` | `apple-v1.28.6-beta.2` |
| --- | --- | --- |
| Version, build in the IPA | 1.28.6, build 1 | 1.28.6, build `0.2` (below the release's 1, above `0.1`) |
| Files | `TMPlayer-ios-1.28.6.ipa` | `TMPlayer-ios-1.28.6-beta.2.ipa`, dSYMs, `SHA256SUMS.txt` |
| Notes | `## 1.28.6` in `iosApp/CHANGELOG.md` | `## 1.28.6-beta.2` (the release stops if it is missing) |
| GitHub release | a release, never "Latest" | a pre-release, never "Latest" |
| `altstore/source.json`, news post, pull request | yes | none |

- A dry run of one: Run workflow with `dry_run` on and `version` = `1.28.6-beta.2`.
- Not checked: that an installer accepts a build number that starts with 0 over an installed
  release. The first test version is the check; if one refuses, decide the numbering again then.
- Like Android's, a test version is no release: it neither counts for nor waits on the release
  cadence guard.

### Dry run, and a lower-iOS trial

Actions, Apple release, Run workflow, from a branch:

- `dry_run` (on by default): builds the IPA from that branch and uploads the IPA, the dSYMs and
  `SHA256SUMS.txt` as the workflow's artifact (14 days). It makes no release, no source change and no
  pull request, but does make the source's entry on a copy and checks it with `--published`, so the
  whole path is exercised. The version and build are the inputs `version` and `build`, or
  `project.yml`'s. A missing section in `CHANGELOG.md` is a warning, not a stop. A run with `dry_run`
  off must start from an `apple-v` tag, so a release never comes from a branch.
- `min_ios` (empty is the project's): a manual lower-iOS test. It builds with
  `IPHONEOS_DEPLOYMENT_TARGET=<min_ios>` (`TM_MIN_IOS` in `build-ipa.sh`, which can be used on a Mac
  too) to see whether a lower floor builds, and is always a dry run, so such an IPA is never released.
  See "Lowering the minimum iOS" for what to read from it.

## The AltStore source

`altstore/source.json`, AltStore's source format v2
(<https://faq.altstore.io/developers/make-a-source>), which SideStore reads as it is.
`altstore/source.schema.json` is its JSON schema, written from that documentation (a structural check
in CI, not AltStore's own), and `iosApp/release/altstore_source.py` makes and checks it with the
standard library alone:

| Command | Does |
| --- | --- |
| `add --ipa --download-url [--version --build --notes-file --repo --branch --developer --news --release-url]` | Puts the IPA's version in `apps[0].versions`, newest first (version numbers, then build), with `buildVersion`, `date`, `localizedDescription` (the notes), `downloadURL`, `size`, `sha256` and `minOSVersion` (read from the IPA's `MinimumOSVersion`). Older versions stay. The same version and build again replaces its entry (and keeps its date for the same IPA, so a second run changes nothing); another build of the same version is its own entry. Also sets the app's `version`, `versionDate`, `versionDescription`, `downloadURL` and `size` to the newest (older AltStore and SideStore read them there), `appPermissions` (`entitlements` empty for an unsigned IPA, `privacy` the IPA's usage descriptions), the icon, `sourceURL` and, with `--repo`, the screenshots found. `--news` posts the release |
| `screenshots --repo` | Lists `iosApp/screenshots/iphone/*` and `/ipad/*` (PNG or JPEG, by name) as `apps[0].screenshots.iphone` and `.ipad` with raw GitHub addresses and each picture's width and height read from its header |
| `news --title [--caption --url --image-url --tint-color --app --notify --identifier --date --keep]` | Adds a post to `news` (newest first, 20 kept); the same `identifier` replaces it |
| `links --repo` | Prints the source's address and the `altstore://` and `sidestore://` links |
| `notes --version --build` | Prints a version's notes from `iosApp/CHANGELOG.md` |
| `check [--project] [--published] [--root]` | Checks the fields, the shape of every address (https; nothing is fetched), tint colours, the checksums, that versions are newest first and none is listed twice, news posts, and the app identifier against `project.yml`. `--published` also needs a version, the developer name, a `sha256` and a release `.ipa` address on every version, the app's own `version` fields to be the newest's, and every raw GitHub address in this repository to name a file that exists in it |

The source's own fields: `name`, `identifier` (`org.tmplayer.ios.source`), `subtitle`, `description`,
`tintColor` (`#2AABEE`), `iconURL`, `website`, `sourceURL`, `featuredApps`, `apps`, `news`. The app's:
`name`, `bundleIdentifier`, `developerName`, `subtitle`, `localizedDescription`, `iconURL`, `tintColor`,
`category`, `screenshots`, `versions`, `appPermissions`. Not set: `headerURL`.

AltStore reads `news` and shows it above the apps. The release adds one post by itself; a post by hand
(an announcement, a known problem) is
`altstore_source.py news --title "..." --caption "..." --url https://tmplayer.org/ --notify` and a
commit. `check --published` on a copy of the file is the last step before a hand edit is pushed.

### Pictures for the source

`iosApp/screenshots/iphone/` (1320x2868, the 6.9 inch iPhone) and `iosApp/screenshots/ipad/`
(2064x2752, the 13 inch iPad) hold six pictures each, `01-home.png` to `06-settings.png`: Home, a
chat's video grid, a video's page, the player, Downloads and Settings, dark. **They are Compose
renders at iOS sizes, not simulator captures.** TMPlayer's screens are shared Compose, so
`scripts/ios-store-pictures.py` draws the real ones from the Android promo build (`app/src/promo`,
the demo library only: invented videos, no account) on an emulator whose display is set to the
iPhone's or iPad's size and density, with the system bars hidden. Fonts, the keyboard and the iOS
chrome are Android's, so a Mac capture (`-promo`, "Building and running") should replace them when one
is available. They are PNG, never recompressed (AltStore reads PNG and JPEG only;
[docs/agents/screenshots.md](agents/screenshots.md)). To redo them:

```sh
./gradlew :app:assemblePromo -PpromoShots
emulator -avd tmplayer_promo -port 5558 -no-window -no-audio -gpu host -no-snapshot-load  # in the background
python3 -I scripts/ios-store-pictures.py --serial emulator-5558 --apk app/build/outputs/apk/promo/app-promo.apk
python3 -I iosApp/release/altstore_source.py screenshots --repo dracu-lah/TMPlayer
```

The promo build is its own package (`com.tmplayer.promo`) and never ships in a release. The source
(`altstore/source.json`) lists the pictures by their raw GitHub address, so they work once on `main`.

`altstore/source.json` lists no version until the first release; until then
`altstore_source.py check --published` refuses it, by design.

## Lowering the minimum iOS

Audit of 2026-11-17, made on Linux by reading the sources; the floor itself is unchanged (iOS 26.4,
`iosApp/project.yml`). Recommendation: **iOS 17.0**, once TDLib allows it (the one blocker, below).

### What was searched

`@available`, `#available`, `#unavailable`, `glassEffect`, `Glass`, `Liquid`, `UIDesignRequiresCompatibility`,
`iOS 2x` in `iosApp/` (the 8 Swift files, 3,515 lines, `Info.plist`, `project.yml`), every
`platform.*` import in `ios/`, `core/src/iosMain` and `ui/src/iosMain`, and the Swift APIs by name.

### Findings

- **No iOS 26 only API in the Swift sources.** There is no `@available` or `#available` anywhere, no
  `glassEffect`, no Liquid Glass call, and no other symbol that exists only from iOS 18 or 26. The
  Swift is a SwiftUI shell (`@main App`, `WindowGroup`, a `UIViewControllerRepresentable` that hosts
  Kotlin's view controller, `.ignoresSafeArea()`), UIKit view controllers, AVFoundation and mpv.
- **Liquid Glass comes by itself, not by a call.** Built with the iOS 26 SDK, system pieces UIKit
  draws (the share sheet, the document picker, alerts) take iOS 26's look on iOS 26 and the old one
  on older systems, with no code. Compose draws the rest. `UIDesignRequiresCompatibility` is not
  set, and does not need to be for a lower floor.
- **The newest APIs the Swift does call** (from Apple's documentation, as remembered; the compiler
  states any miss when the target is lowered, see the steps):

  | API | Where | Needs |
  | --- | --- | --- |
  | `UIWindowScene.requestGeometryUpdate(.iOS(interfaceOrientations:))`, `setNeedsUpdateOfSupportedInterfaceOrientations()` | `Player/ActivityViewController.swift` (lines 162, 164, 261) | iOS 16 |
  | `AVAsset.load(_:)` and `AVAssetTrack.load(.isDecodable)`, `loadMediaSelectionGroup`, `loadChapterMetadataGroups`, `loadAssociatedTracks`, `AVMetadataItem.load(.stringValue)` | `Player/Engines/AppleEngine.swift` (lines 116 to 579) | iOS 15 |
  | `UTType` (`UniformTypeIdentifiers`) | `Player/Engines/ChoosingEngine.swift` (289, 291) | iOS 14 |
  | `App`, `WindowGroup`, `.ignoresSafeArea()` | `TMPlayerApp.swift` | iOS 14 |
  | `AVAudioSession.setCategory(_:mode:policy:options:)` | `Player/PlaybackAudio.swift` | iOS 11 |
  | `Task { @MainActor }`, `async`/`await` | `AppleEngine.swift` | iOS 13 (back-deployed by the Swift runtime) |

  So the Swift compiles unchanged down to **iOS 16**, with no gating. Below 16 it would need:
  `requestGeometryUpdate` behind `#available(iOS 16, *)` with
  `UIViewController.attemptRotationToDeviceOrientation()` as the fallback (the orientation lock
  would turn less cleanly), and below 15 the completion-handler `loadValuesAsynchronously` instead
  of `asset.load`. Not proposed: each step below 16 adds gating work and reaches fewer people.
- **Kotlin side.** The `platform.*` APIs imported on iOS (`UIApplication`, `UIViewController`,
  `UIDocumentPickerViewController`, `UTType`, feedback generators, `NSProcessInfo` thermal state,
  `VTIsHardwareDecodeSupported`, ...) are all iOS 14 or older, and `os_proc_available_memory` is not
  used. Kotlin/Native's own minimum for the compiled klibs is lower than every floor considered here.
  Compose Multiplatform's own minimum for iOS and MPVKit's (its `Package.swift`, resolved only on a
  Mac) are **unverified here**: check both when the trial below runs.
- **The blocker is TDLib, not Swift.** tdl-coroutines 15.0.0 (`gradle/libs.versions.toml`, `tdl`)
  ships TDLib built for iOS 26.4. `project.yml` says why the floor sits there: linking it into an app
  for an older iOS only trades the linker's warning for crashes on those devices. A lower floor needs
  a TDLib built for it: an older tdl-coroutines release (find the newest built for a lower minimum
  with `vtool -show-build` on its framework, `minos`), or TDLib built from its sources for
  `-miphoneos-version-min=17.0` (TDLib's `example/ios` build scripts, on a Mac), then published or
  vendored the way tdl-coroutines is used.

### Why 17.0

It is the lowest floor with no Swift gating worth keeping (16 also has none, and is the real code
minimum), it covers iPhone XS and XR and later and every iPad that runs iPadOS 17, and iOS 17 is the
oldest system a TDLib build of recent years is likely to be made for. Choose 16.0 if the TDLib binary
found allows it and devices on 16 matter to the people who sideload.

### Steps, in order

1. **Trial build (any Mac or the macOS runner):** Actions, Apple release, Run workflow, `min_ios` =
   `17.0` (or `TM_MIN_IOS=17.0 iosApp/release/build-ipa.sh ...`). It builds the Release IPA with that
   deployment target and uploads it as an artifact; a Swift API newer than 17 is a compile error with
   its name, which is the exact list of files to gate (expected: none). Read the log for the linker's
   "was built for newer 'iOS' version" warnings: they name the TDLib objects built for 26.4.
2. **TDLib for the lower floor:** get or build it (above). Check with `vtool -show-build` that its
   `minos` is the floor. Point `libs.versions.toml` at it, then run `:ios:kit:linkReleaseFrameworkIosArm64`.
3. **Run it on an iOS 17 simulator runtime and a device on 17** (Xcode, Settings, Components): sign in,
   play an MP4 (AVPlayer) and an MKV (mpv), rotate, lock the screen with sound on, download. TDLib at
   a wrong minimum fails at launch or at the first call, so this is the real test, not the build.
4. **Change the floor** in one place and regenerate: `options.deploymentTarget.iOS` in
   `iosApp/project.yml`, `xcodegen generate` in `iosApp/` (updates both `IPHONEOS_DEPLOYMENT_TARGET`
   lines in `project.pbxproj`; `test_the_minimum_ios_agrees` fails if they differ), and the text that
   says 26.4: `docs/IOS.md` (the introduction, "Setting up a Mac", Known issues), `CONTRIBUTING.md`,
   `scripts/ios-setup.sh` (it checks the SDK, which stays 26.x if Xcode 26 builds it), `iosApp/CHANGELOG.md`'s
   release text, the store and site text that names the minimum. `minOSVersion` in the AltStore source
   follows by itself, read from each IPA.
5. **Keep it honest in CI:** add an iOS 17 runtime to the `macos` job (a second destination for
   `TMPlayerTests`), so a later change that calls a newer API fails there.
6. If the floor goes below 16: gate `requestGeometryUpdate` as above and re-run steps 1 to 3.

## Changelog of the port

What each build of TMPlayer for Apple changed (its `iosApp/CHANGELOG.md`, kept at the end of ours)
and where it is in this repository. Build 1 is the whole port; builds 2 to 15 are fixes. Paths under
`ios/`, `iosApp/` and the `ios*` source sets of `core/` and `ui/` came over whole; the rest was merged
into TMPlayer's current code by hand.

| Build | Fix | Where it landed |
| --- | --- | --- |
| 1 | The app: sign in, chats, Home, player (AVPlayer and mpv), downloads, 22 languages | `ios/`, `iosApp/`, `core` and `ui` `iosMain` and `iosStandIns`, `ui/src/iosTest`, `gradle/ios-*.gradle.kts` |
| 2 | Pictures on Home and in details went black or wrong after the app reopened (the app container moves between launches) | `ios/src/iosMain/.../data/MovedContainer.kt`, `ios/kit/.../ActivityShell.kt` |
| 2 | Adding a TMDB key finds posters again by itself | `ios/kit/.../OwnKeyWatch.kt` |
| 3 | Removing a TMDB key clears what it found | `ios/kit/.../OwnKeyWatch.kt`, `ui/src/iosMain/.../ThumbnailDecoding.kt` |
| 4 | MP4 with several audio languages played them over each other | `iosApp/TMPlayer/Player/Engines/AppleEngine.swift` |
| 4 | "Struggling on this device" shown when other apps filled memory | `ios/kit/.../PerfProbe.kt`, `ActivityShell.kt`, `ThumbnailDecoding.kt` |
| 5 | Sound ran ahead of the picture after resuming | `iosApp/TMPlayer/Player/Engines/MpvEngine.swift` |
| 6 | A failure while opening a video closed the app | `ios/src/iosMain/.../media3/EngineExoPlayer.kt`, `MpvEngine.swift`, `TMPlayerApp.swift` |
| 7 | iPad: the player's title and buttons sat under the status bar | `ios/kit/.../ActivityScreen.kt`, `AndroidViewHost.kt`, `PlayerSafeArea.kt`, `ActivityViewController.swift` |
| 8 | Version and build showed 0 | `ios/src/iosMain/.../data/AndroidSystemOnIos.kt`; variant: `LanguageAndFeedback.kt` reads `Updates.installedVersion` |
| 8 | Choosing a poster with no TMDB key read as a refused key | variant: `PosterPicker.kt` |
| 9 | Renaming a video showed the screen behind the box | `app/.../ui/browse/MediaDetailSheet.kt` (every platform) |
| 9 | "phone" where an iPad is a device: rotation and privacy lines | new keys `player.rotation_follow_device`, `settings.privacy_detail_device`; variant: `ScreenOrientation.kt`, `SettingsScreen.kt` |
| 9 | "phone" in the theme line | not carried over, see Known issues |
| 9 | The chat picker said the top chats were already ticked | already fixed here (`chat_picker.gate_body` says Recommended) |
| 9 | Setup's last step "Storage, data and reports" without reports | not needed: TMPlayer's setup is now the chat picker alone (R3) |
| 10 | Narrow screens: the player's round buttons hid behind Episodes and the copy pill | `app/.../player/PlayerControls.kt` `fitRow` steps (every phone, Android at 411 dp too), `player_controls.xml`, `PlayerExtrasRenderTest` |
| 10 | Info and help's separate version line removed | `ios/src/iosMain/.../data/AndroidSystemOnIos.kt` |
| 11 | Large text: Home's first card and the empty and error pages could not scroll their last line out from under the bar | `app/.../ui/browse/FirstRun.kt`, `app/.../ui/components/StateScaffold.kt` (every platform), `LargeTextUnderBarTest` |
| 12 | Save logs and Send logs held almost nothing on iOS | `core/src/pureMain/.../platform/KeptLogLines.kt`, `core/src/jvmSharedMain/.../KeptLog.kt`, `core/src/iosMain/.../KeptLog.kt` and `Logger.ios.kt`, `app/.../data/LogReport.kt`, `Td.kt` (TDLib's log file), `ios/kit/.../NativeLog.kt`, `AppLog.swift`, `KeptLogLinesTest`, `GarbageInputTest` |
| 12 | Logs and bug reports called the device Android | variant: `LanguageAndFeedback.kt` uses `appleDevice()` |
| 12 | "Scan a QR code instead" on the code step when the code went to the Telegram app | `ui/src/shared/.../auth/SignInWords.kt`, `app/.../ui/auth/LoginScreen.kt` (every platform), `CodeStepQrTest` |
| 12 | A session that ended: the log says whether TDLib's database was there | `Td.kt` (`databaseAtStart`), `ui/src/shared/.../connect/SessionEnded.kt` |
| 13 | Coming back after reading the code: back on the code step instead of a dead end | `core/src/pureMain/.../data/AuthState.kt`, `Td.kt`, `AuthReducerTest` |
| 13 | "Scan a QR code instead" shows the code at once | `Td.refreshQrCode` |
| 13 | The code's destination in the log, masked | `core/src/pureMain/.../data/PhoneMask.kt`, `PhoneMaskTest` |
| 14 | Own name and icon ("TMPlayer for Apple", chat bubble mark) | not carried over: this app is TMPlayer, with TMPlayer's icon (`iosApp/make-app-icon.py` from Android's launcher icon) |
| 14 | Its README, privacy page and issue template | issue template adapted as `.github/ISSUE_TEMPLATE/apple_bug_report.yml`; the rest is TMPlayer's own |
| 15 | Lists stopped short above the home indicator | variant: `AboutScreen.kt`, `SettingsScreen.kt`, `PhoneBrowse.kt`, `BrowseScreen.kt`; `ios/src/iosMain/.../ios/HomeIndicator.kt` |
| 15 | A downloaded video waited for the sign in | `Td.awaitAnySession` (shared), variant: `PlayerActivity.kt` (iOS only) |
| 15 | Promo names made plain for screenshots | variant `iosPromo`; `ios/src/iosMain/.../ios/PromoNames.kt`, `PromoPictures.kt` |

Also from the port, for every platform: the browser's three-letter language codes moved to
`core/src/pureMain/.../i18n/ThreeLetterCodes.kt`, shared with iOS.

## Known issues

Found while bringing the port over; fixed ones are listed under the next heading.

- **iOS 26.4 or later only.** tdl-coroutines 15.0.0 ships TDLib built for iOS 26.4; lower the
  deployment target only with TDLib binaries built for an older iOS.
- **CI covers the Kotlin compile, not the link.** A change to `app/` or `core/` that uses an API no
  stand-in has fails `apple-ci.yml`'s Linux job before merge. Linking the framework, the Xcode
  build and the Swift tests run only when the repository variable `APPLE_MAC_CI` is `true`, and a Mac
  build is still needed before a release.
- **`:ios:compileKotlinIosArm64` fails on Linux (2026-11-21), outside the profiles.** `:core` and
  `:ui` compile again (HX88 B2 fixed AccountStore's lock, `Path`, `URI.rawUserInfo` and the region
  name). Left, from other work that reached Android's player and data without stand-ins: 46 errors
  in `PlayerMorePanel.kt` and `PlayerActivity.kt` (`PlayerListTarget`), `UpdateDexMetadata.kt`
  (`ZipFile`), `AudioPolicy.kt` and `LevelLenientVideoRenderer.kt` (Media3 renderer capabilities),
  `PlayerActivity.kt` (`PcmOnlyOutputProvider`, `hwFit`). Exclude or stand them in before an iOS build.
- **The source change after a release is a push or a pull request.** With `APPLE_SOURCE_PUSH` not
  `true`, or a push the branch rules refuse, the workflow opens a pull request (`gh pr create`),
  which needs the setting "Allow GitHub Actions to create and approve pull requests"; the source is
  not updated until it is merged.
- **Theme, "Match the phone" on an iPad.** The theme line comes from `core/src/pureMain` (`Appearance`),
  which is the same compilation for every platform, so the variant cannot change it; a platform word
  in the catalog would.
- **Local tags.** Fetching the fork brought its `apple-v1.28.5-*` tags into the local repository. They
  are the fork's releases, not this repository's: never push them (`git push --tags`), and delete them
  locally once the fork's ref is no longer needed.
- **`TMPlayer.xcodeproj` edited by hand** for the identifiers and build number above. Regenerate it
  with `xcodegen generate` on a Mac and commit any difference.
- **Screenshots not imported.** The fork's screenshots show its own name and icon; TMPlayer's own are
  in `iosApp/screenshots` ("Pictures for the source").
- **iOS answers that are guesses until a Mac run.** The player files written after the port's base
  compile for iOS over new stand-ins whose answers are best guesses: the thermal state's mapping,
  `Process.getStartElapsedRealtime` (the first use), `ActivityManager` free memory (0), view tree
  observers (kept, never called), Compose's `AndroidView` (draws nothing, so the Playback test's
  preview is blank on iOS), `LoopbackStream.start` (throws: no casting), metered networks (from
  NetworkMonitor). External players, decoder listing and VLC have iOS versions that offer none.
- **The stand-in `Configuration` follows the host (WP-64, 2026-11-24).** `Resources.configuration`
  reads the key window's size in points (the shorter side is `smallestScreenWidthDp`, wider than tall
  is landscape), the density and light or dark from the screen, and `fontScale` from Dynamic Type
  (`UIFontMetrics` scaling 100 points), so the player's Views (`PlayerControls` label sizes,
  `StatusSheetShape` width) follow Larger Text and an iPad's Split View; `scaledDensity` is the
  density times that scale, so an sp in a View's layout does too. Unchecked until a Mac run:
  `HostConfigurationTest` (`./gradlew :ui:iosSimulatorArm64Test`), and a VoiceOver pass on an iPhone.
- **Support links on iOS** (`SUPPORT_LINKS = true`): fine for a sideloaded app; an App Store build
  would have to drop them.
- **Not ported:** casting (AirPlay and Picture in Picture are, for Apple's player's files), background downloads, crash reports (see "Not in scope").

### Fixed while importing

- Bundle identifiers, the AltStore source's identifier and URLs and the developer name claimed the
  fork's namespace: one set of TMPlayer identifiers now (see "Identifiers").
- `altstore/source.json` listed the fork's build 15 IPA under a bundle identifier that is not this
  app's: emptied to a template until the first release here.
- The build number restarts at 1 with the new identifier (`project.yml`, `TMPlayer.xcodeproj`).
- The release workflow ran only in the fork (`TMPlayer-for-apple`) and named its developer: it runs
  in `TMPlayer` now, the developer name is a variable, the IPA is `TMPlayer-ios-...`, the privacy link
  is tmplayer.org's.
- `altstore_source.py` left files open (ResourceWarning) and a changelog that was not UTF-8 crashed it:
  read through one helper that closes them and stops with a message.
- The app's display name and icon were the fork's: TMPlayer's.
- TMPlayer's code written since 1.28.5 did not compile for iOS: `:core`, `:ui` and `:ios` now do
  (checked on Linux), the 50 player files written since joining the copy with the `novlc` flavour,
  Android-only ones as iOS versions (`ios/src/iosMain/.../player/*OnIos.kt`), and gzip
  streams over the system zlib and `UUID` (the sync file and the search session), `StackOverflowError`
  named, `setFixedLengthStreamingMode`, `PlayRandom` without an access-ordered `LinkedHashMap`
  subclass, and `CrashContext` (Sentry) and the cast UI (`AddTvDialog`, `CastConverting`) left out
  with the rest of casting.
- The JSON reader on iOS recursed without a limit: a file of nothing but brackets (outside data)
  would have ended the app, since Kotlin/Native cannot catch a stack overflow. It refuses nesting
  past 256 now.
- References to the fork's design documents, which it never published (`IOS_PORT_PLAN.md`,
  `IOS_RELEASING.md`, `IOS_VIDEO_ENGINES.md`, `iosApp/README.md`), point here.

## Needs a Mac or an Apple account (later)

Skipped in the import (2026-11-17): none of this can be done or checked on Linux.

- Building anything for iOS: the Kotlin framework (`:ios:kit`), the app, the IPA
  (`build-ipa.sh`), and `xcodegen generate` to confirm the committed project matches `project.yml`.
- Running the Kotlin tests on the simulator (`:core`, `:ui`, `:ios` `iosSimulatorArm64Test`) and the
  Swift tests (`EngineConformanceTests`, `Media3BridgeTests`, the `TMPlayerDeviceCheck` UI tests).
- Linking the framework and running what compiles on Linux: the iOS answers below that are guesses
  (see Known issues), the uncaught exception hook (the process must still end and SafeMode count the
  crash), read-ahead through `AheadFirstLoadControl`, the executors on dispatch queues, and the
  "Open in another app" message with no other player to offer.
- **In app trailers (2026-11-17, UNVERIFIED: `TrailerSheet.swift` was never compiled; swift is not
  installed on the Linux box, so not even `swiftc -parse` ran).** On a Mac: build, then
  `xcodegen generate` (the file was added to `project.pbxproj` by hand with made up ids; a diff is
  only a warning in CI). On an iPhone and an iPad, with Play trailers in TMPlayer on:
  1. Open a video's page, check Network (or a proxy) shows nothing from youtube.com before the tap.
     Tap Trailer: a sheet opens (iPad: a centred card; iPhone: a card, full screen on its side) with
     Close and Watch on YouTube above a picture that plays by itself, inline, with sound even with
     the silent switch on.
  2. Tap YouTube's pause and play; seek. Rotate the iPad. Lock the phone or go Home: it stops, and
     stays paused on return. Close (and Escape on an iPad keyboard, and the swipe down): the sheet
     goes, nothing keeps playing or loading, music from another app can resume.
  3. Watch on YouTube: the sheet closes and the YouTube app or Safari opens the trailer. Tap the
     video title in the player: same, once.
  4. A trailer YouTube refuses (errors 101, 150, 153; a video that is not embeddable) or Airplane
     mode after opening: the plain notice for 1.5 s, then the YouTube app or Safari. Same after 12 s
     with no `ready`.
  5. Settings, Play trailers in TMPlayer off: Trailer goes straight outside. On mobile data with
     "Never": same.
  6. VoiceOver reads the sheet's name, Close and Watch on YouTube, and the notice; Larger Text at
     the biggest accessibility size stacks the two buttons and keeps the picture.
  7. If the picture stays black or the page says it is loading until the 12 s fallback, suspect the
     navigation policy first (`decidePolicyFor` refuses subframes outside YouTube's hosts), then the
     non-persistent data store.
- Running `scripts/ios-setup.sh` on a Mac.
- A real device pass on an iPhone and an iPad (sign in, play MP4 and MKV, downloads, rotation,
  Split View, large text, VoiceOver).
- The release: the macOS runner, the `apple-v*` tag, the GitHub release and the AltStore source's
  first version.
- **Written on 2026-11-17 and never run (unverified), all of it needing a Mac, a macOS runner or an
  Apple account:**
  - `apple-ci.yml`'s `macos` job: `brew install xcodegen` and `xcodegen generate` matching the
    committed project, the simulator pick by `simctl list -j`, `xcodebuild build` and
    `xcodebuild test -only-testing:TMPlayerTests` with `-clonedSourcePackagesDirPath`, the Gradle
    heap through `GRADLE_OPTS` in Xcode's script phase, and the three caches. Its Linux job's last
    step (the klib compile) also has not run on GitHub yet: the same tasks were run by hand.
  - `apple-release.yml` since this change: the `dry_run` and `min_ios` inputs, the guard
    `github.repository == (vars.APPLE_RELEASE_REPO || 'dracu-lah/TMPlayer')`, the artifact upload,
    `SHA256SUMS.txt` over the IPA and the dSYMs, the release's install text, the source change by
    push or by `gh pr create` (and whether the repository allows Actions to open pull requests), and
    `TM_MIN_IOS` in `build-ipa.sh`.
  - The AltStore source against AltStore and SideStore themselves: `altstore/source.schema.json` was
    written from the documentation and AltStore's own parser was never run on this file. Check that
    both apps accept the source (the `screenshots` object with `iphone` and `ipad`, `sha256`,
    `news`, `tintColor` with the `#`), show the news post and the screenshots, and read the app's
    `appPermissions` as the IPA's.
  - `altstore://source?url=` and `sidestore://source?url=` opening the right app from a web page.
  - iPhone and iPad pictures in `iosApp/screenshots/iphone` and `/ipad`: they are Compose renders at
    iOS sizes, not seen on an iPhone or iPad. Check that AltStore and SideStore load them from their
    raw addresses once on `main` (the sizes in the source), and replace them with simulator captures.
  - Installing the IPA with Sideloadly and LiveContainer (the app's sound with the screen locked and
    TDLib's storage under LiveContainer).
  - The lower-iOS trial (`min_ios`) and everything in "Lowering the minimum iOS", which needs a TDLib
    built for the lower floor first.
- Signing with an Apple Developer account, notarization, AltStore PAL, TestFlight, the App Store.
