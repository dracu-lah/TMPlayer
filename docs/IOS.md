# TMPlayer for iPhone and iPad

The iOS and iPadOS port started from TMPlayer for Apple by Pietro Giuseppe D'Amico
(github.com/Killua99x/TMPlayer-for-apple, GPL-3.0).

That fork took TMPlayer 1.28.5 and added an iOS and iPadOS app; its code was brought into this
repository on 2026-11-17 and adapted to TMPlayer's current code (see "Changelog of the port"). Every
copyright and licence header it carries is kept. Here the app is TMPlayer itself: TMPlayer's name,
icon, links, support page and contact.

One universal app for iPhone and iPad, iOS and iPadOS 26.4 or later, installed by sideloading
(AltStore, SideStore or Sideloadly). It is not in the App Store.

## Contents

- [How the port is built](#how-the-port-is-built)
- [Identifiers](#identifiers)
- [Setting up a Mac](#setting-up-a-mac)
- [Building and running](#building-and-running)
- [Tests](#tests)
- [What Linux can check](#what-linux-can-check)
- [Installing on a device](#installing-on-a-device)
- [Releasing](#releasing)
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

Not on iPhone and iPad yet: casting (Google Cast, DLNA, AirPlay video), Picture in Picture, downloads
while the app is in the background, crash reports (Sentry's Android SDK), the in-app updater
(AltStore or SideStore offers updates), Android TV features. Videos only, as everywhere in TMPlayer.

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
  player screenshot plays.

## Tests

| What | Command | Where |
| --- | --- | --- |
| Shared Kotlin tests on iOS (the `js-test-sources.txt` set of `core/src/jvmTest`) | `./gradlew :core:iosSimulatorArm64Test` | Mac |
| The View toolkit's stand-ins against Android's behaviour | `./gradlew :ui:iosSimulatorArm64Test` | Mac |
| The Media3 stand-ins and iOS pieces | `./gradlew :ios:iosSimulatorArm64Test` | Mac |
| Every video engine against the same rules (`EngineConformanceTests`, `Media3BridgeTests`) | `xcodebuild test -project iosApp/TMPlayer.xcodeproj -scheme TMPlayer -destination 'platform=iOS Simulator,name=iPhone 17'` | Mac |
| On a real device, with a viewer's taps (`PlayerOnDeviceTests`, `AboutOnDeviceTests`, `ResourcesOnDeviceTests`) | `xcodebuild test -project iosApp/TMPlayer.xcodeproj -scheme TMPlayerDeviceCheck -destination 'id=<device>'` | Mac and device |
| Release scripts, identifiers, versions, no committed secrets | `python3 -I -m unittest discover -s iosApp/release` | any |
| The iOS copies of shared and Android sources (variant replacements still apply) | see below | any |
| Shared fixes the port brought (sign in, logs, player row, large text) | `./gradlew :core:jvmTest :app:testDebugUnitTest` | any |

## What Linux can check

No Xcode, no simulator, no link. What runs on Linux:

```sh
python3 -I -m unittest discover -s iosApp/release
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

The IPA is unsigned; the installer signs it with your own Apple ID.

- **AltStore or SideStore:** add the source
  `https://raw.githubusercontent.com/dracu-lah/TMPlayer/main/altstore/source.json`, install TMPlayer,
  and the app offers each update from there. A free Apple ID's signature lasts 7 days; AltStore and
  SideStore refresh it.
- **Sideloadly:** download the IPA from a release tagged `apple-v...`, drag it in, sign with your
  Apple ID. No automatic updates.
- **AltStore PAL** (EU): needs a notarized build, so not yet (see the last section).

## Releasing

The user tags and approves; nothing here is done by an agent (AGENTS.md, publishing rules).

1. Write the version's notes in `iosApp/CHANGELOG.md` as `## <version>` (first build of a version)
   or `## <version> build <n>` (an iOS-only fix). The release stops if the section is missing.
2. Repository secrets: `TG_API_ID`, `TG_API_HASH` (required), `TMDB_API_KEY`, `OMDB_API_KEY`,
   `OPENSUBTITLES_API_KEY` (optional). Variables, all optional: `APPLE_RELEASE_RUNNER` (a runner
   label, `macos-26` when unset), `APPLE_RELEASE_KOTLIN_HEAP` (`6000m` when unset),
   `APPLE_DEVELOPER_NAME` (the developer name AltStore shows, `TMPlayer` when unset).
3. Tag `apple-v<version>` or `apple-v<version>-<build>` and push the tag. The version is x.y or
   x.y.z: a beta (`1.28.6-beta.1`) has no iOS release.
4. `.github/workflows/apple-release.yml` (only in the `TMPlayer` repository, a fork skips it) checks
   the source against the project, builds the unsigned IPA and dSYMs with `build-ipa.sh`, creates the
   GitHub release with the notes and the corresponding source links (this repository at the tag,
   MPVKit at its version), then adds the version to `altstore/source.json` with
   `iosApp/release/altstore_source.py add` and pushes that commit to the default branch.

`altstore/source.json` lists no version until the first release; until then
`altstore_source.py check --published` refuses it, by design.

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
- **The iOS build is not in CI on every change.** A change to `app/` or `core/` that uses an API no
  stand-in has breaks only the iOS build. The Linux compile check above catches it without a Mac;
  a CI job running it on pull requests (Linux runner, about 800 MB of toolchain cached) would
  catch it before merge, and a Mac build is still needed before a release.
- **The release pushes to the default branch.** The workflow commits `altstore/source.json` straight
  to `main`; with a branch protection rule that requires pull requests, that step fails and needs a
  pull request instead (or a bypass for the workflow).
- **Theme, "Match the phone" on an iPad.** The theme line comes from `core/src/pureMain` (`Appearance`),
  which is the same compilation for every platform, so the variant cannot change it; a platform word
  in the catalog would.
- **Local tags.** Fetching the fork brought its `apple-v1.28.5-*` tags into the local repository. They
  are the fork's releases, not this repository's: never push them (`git push --tags`), and delete them
  locally once the fork's ref is no longer needed.
- **`TMPlayer.xcodeproj` edited by hand** for the identifiers and build number above. Regenerate it
  with `xcodegen generate` on a Mac and commit any difference.
- **Screenshots not imported.** The fork's screenshots show its own name and icon; make TMPlayer's
  with the promo build.
- **iOS answers that are guesses until a Mac run.** The player files written after the port's base
  compile for iOS over new stand-ins whose answers are best guesses: the thermal state's mapping,
  `Process.getStartElapsedRealtime` (the first use), `ActivityManager` free memory (0), view tree
  observers (kept, never called), Compose's `AndroidView` (draws nothing, so the Playback test's
  preview is blank on iOS), `LoopbackStream.start` (throws: no casting), metered networks (from
  NetworkMonitor). External players, decoder listing and VLC have iOS versions that offer none.
- **Support links on iOS** (`SUPPORT_LINKS = true`): fine for a sideloaded app; an App Store build
  would have to drop them.
- **Not ported:** casting, Picture in Picture, background downloads, crash reports (see "Not in scope").

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
- Running `scripts/ios-setup.sh` on a Mac.
- A real device pass on an iPhone and an iPad (sign in, play MP4 and MKV, downloads, rotation,
  Split View, large text, VoiceOver).
- The release: the macOS runner, the `apple-v*` tag, the GitHub release and the AltStore source's
  first version.
- Signing with an Apple Developer account, notarization, AltStore PAL, TestFlight, the App Store.
