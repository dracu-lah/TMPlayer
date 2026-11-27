# app/

The Android app, phone and TV: activities, ExoPlayer, `TdDataSource`, services.

- Source sets: `main`, `fdroid`, `vlc`/`novlc`, `cast`/`nocast`, `preview`, `debug`, `dev`. A class used
  by one flavour lives in that flavour's folder.
- Android 6.0 is the floor: guard newer APIs (see `LegacyApiCompat.kt`).
- TV is a platform: every change needs its D-pad path and a look at idle CPU on a weak stick. Key rules
  per context are in docs/agents/tv-keys.md (read before binding a key).
- Tests: `./gradlew :app:testDebugUnitTest` (Robolectric, JDK 21). It is slow (a JVM per class, about
  an hour on CI). A Compose test that asserts right after an async write must `waitUntil` the end state.
- Build, credentials and test devices: docs/BUILDING.md.
