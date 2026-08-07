<p align="center">
  <img src="art/logo.svg" width="72" alt="TMPlayer logo" />
</p>

<h1 align="center">TMPlayer</h1>

<p align="center">Watch movies from your Telegram chats on Android TV — QR login, instant streaming, proper seeking.</p>

---

TMPlayer is an unofficial, open-source Telegram client purpose-built for TVs (designed around the Mi TV Stick and other low-spec Android TV devices). Log in with a QR code, browse your chats as a clean media-only library, and press play — files stream directly from Telegram's servers while they download, with real forward/backward seeking, subtitles, and resume.

No servers. No bots. No accounts other than your own Telegram. Nothing is hosted, indexed, or uploaded by this app.

## Features

- [x] **QR code login** — scan with the Telegram app on your phone (Settings → Devices → Link Desktop Device). Two-step verification supported.
- [x] **Media-only chat library** — all your chats and channels, showing just their videos and movie files in a big poster grid.
- [x] **Stream-while-downloading playback** — starts in seconds; seeking moves the download to the target offset instead of waiting for the file.
- [x] **Subtitles & audio tracks** — embedded SRT/ASS/VTT and image-based PGS/VobSub, plus dual-audio switching; FFmpeg-backed audio decoding for codecs TV sticks lack (DTS, TrueHD, E-AC3).
- [x] **Resume watching** — every file remembers where you stopped.
- [x] **8 GB-friendly storage guard** — the streaming cache is capped (1 GB by default, 1/2/4 GB in Settings) and trimmed after every film.
- [x] **10-foot UI** — plain, dark, oversized; every screen has proper loading and error states; zero typing after login.

See [PLAN.md](PLAN.md) for the full design document, and [INSTALL.md](INSTALL.md) to put it on your TV.

## How it works

- [TDLib](https://core.telegram.org/tdlib) (Telegram's official client library) handles auth, chats, and file access — via [tdl-coroutines](https://github.com/g000sha256/tdl-coroutines), which bundles prebuilt natives for `armeabi-v7a`, `arm64-v8a`, and `x86_64`.
- A small custom [Media3](https://developer.android.com/media/media3) `DataSource` bridges TDLib's offset-based partial downloads into ExoPlayer — the same technique official Telegram apps use for streaming (clean-room implementation from TDLib docs, Apache-licensed).
- The player UI is Media3's leanback integration — standard Android TV transport controls, not a custom player.

## Building

Requirements: JDK 17+ (21 recommended), Android SDK platform 35.

1. Get your own Telegram API credentials at [my.telegram.org](https://my.telegram.org) → *API development tools* (free, 2 minutes). Every fork must use its own pair — they are not distributed with this repo.
2. Create `local.properties` in the repo root:

   ```properties
   sdk.dir=/path/to/Android/Sdk
   TG_API_ID=1234567
   TG_API_HASH=your_api_hash
   ```

3. Build:

   ```bash
   ./gradlew assembleDebug          # per-ABI debug APKs
   ./gradlew assembleRelease        # minified release (needs your keystore, see below)
   ./gradlew test                   # JVM unit tests
   ```

Release signing: create a keystore (`keytool -genkeypair …`) and a `keystore.properties` (both are gitignored); CI/store distribution is out of scope — this app is meant to be sideloaded.

## Installing on a TV

Enable Developer options on the TV (Settings → About → click *Build* 7×), turn on USB/network debugging, then:

```bash
adb connect <tv-ip>:5555
adb install app/build/outputs/apk/release/app-armeabi-v7a-release.apk   # Mi TV Stick 2020
adb install app/build/outputs/apk/release/app-arm64-v8a-release.apk    # most other devices
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Short version: keep it minimal, use existing libraries, write tests for core logic, never copy GPL code into this Apache-2.0 project.

## Legal

- Unofficial client. Not affiliated with, endorsed by, or connected to Telegram FZ-LLC.
- Uses the official Telegram API via TDLib with user-provided credentials, as [permitted for third-party clients](https://core.telegram.org/api/obtaining_api_id).
- TMPlayer hosts no content and provides no content. It displays media from chats **you** joined with **your** account. You are responsible for what you access.

## License

[Apache-2.0](LICENSE) © 2026 dracu-lah

Logo icon via [SVG Repo](https://www.svgrepo.com).
