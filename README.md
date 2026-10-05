<p align="center">
  <img src="art/player.svg" width="88" alt="TMPlayer logo" />
</p>

<h1 align="center">TMPlayer</h1>

<p align="center">Your Telegram videos, on the TV, in your hand and on your computer. Start watching before the download finishes.</p>

<p align="center">
  <img alt="License" src="https://img.shields.io/badge/license-GPL--3.0--or--later-blue" />
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android%208.0%2B%20(TV%20and%20phone)-3ddc84" />
  <img alt="Desktop" src="https://img.shields.io/badge/desktop-Windows%20%7C%20Linux-0078d4" />
  <img alt="Tests" src="https://img.shields.io/badge/tests-209%20passing-brightgreen" />
</p>

<p align="center">
  <a href="https://t.me/tmplayerapp"><img alt="Telegram channel" src="https://img.shields.io/badge/Telegram-channel-26A5E4?logo=telegram&logoColor=white" /></a>
  <a href="https://github.com/dracu-lah/TMPlayer/discussions"><img alt="GitHub Discussions" src="https://img.shields.io/badge/GitHub-Discussions-181717?logo=github" /></a>
  <a href="https://github.com/sponsors/dracu-lah"><img alt="Sponsor on GitHub" src="https://img.shields.io/badge/Sponsor-GitHub-EA4AAA?logo=githubsponsors&logoColor=white" /></a>
  <a href="https://buymeacoffee.com/nevil.dev"><img alt="Buy me a coffee" src="https://img.shields.io/badge/Buy%20me%20a%20coffee-FFDD00?logo=buymeacoffee&logoColor=black" /></a>
</p>

---

TMPlayer plays the videos in your own Telegram chats and channels on an Android TV, a phone, or a
Windows or Linux computer. It starts within seconds while the file is still downloading, and
seeking re-aims the download instead of waiting for it.

Sign in by scanning a QR code with your phone, pick a chat, and only its playable videos appear.
No messages, no server, no account except yours.

<p align="center">
  <img src="docs/screenshots/tv-chats.webp" width="90%" alt="Browsing chats on TMPlayer on a television" />
</p>

<p align="center">
  <img src="docs/screenshots/devices.webp" width="90%" alt="A chat's videos in TMPlayer on a television and on a phone at the same time, each tile carrying its quality, running time and size" />
</p>

## Runs on

- **Android 8.0 and up**, TV and phone from the same APK. It was built and tested on an 8 GB TV
  stick with a gigabyte of RAM, so a cheap device is the target rather than an afterthought.
- **Windows 10 and 11**, 64-bit, as an MSI installer.
- **Linux**, 64-bit x86 with glibc 2.38 or newer, as an AppImage, with an AUR recipe in
  [desktop/packaging/aur](desktop/packaging/aur).

macOS is planned for later. Everything is on the same [GitHub release](../../releases), and
[tmplayer.org/download](https://tmplayer.org/download/) lists it by platform.

## Install

### Android TV and phones

Grab the universal APK from [Releases](../../releases). The same file works on every supported
architecture, so there is no CPU variant to identify.

On the TV, turn on Developer options (Settings, About, press *Build* seven times), enable network
debugging, then:

```bash
adb connect <tv-ip>:5555
adb install TMPlayer-<version>-universal.apk
```

### Windows

Download `TMPlayer-<version>-windows-x64.msi` and open it. The build is not signed yet, so
SmartScreen says *Windows protected your PC*: click **More info**, then **Run anyway**. It installs
for your user only, with a Start menu entry and a desktop shortcut.

### Linux

```bash
chmod +x TMPlayer-<version>-x86_64.AppImage
./TMPlayer-<version>-x86_64.AppImage
```

Arch: build from [desktop/packaging/aur](desktop/packaging/aur), or use the AppImage. The deb, rpm,
Flatpak and Windows portable zip are no longer published; [INSTALL.md](INSTALL.md#moving-from-a-format-that-is-no-longer-published)
says how to move over.

[INSTALL.md](INSTALL.md) has the full steps for every platform, how to put the app on the desktop,
in the app menu and on the taskbar, the remote, touch and keyboard references, and troubleshooting.

## Build it yourself

You need JDK 21, Android SDK platform 36, and your own free Telegram credentials from
[my.telegram.org](https://my.telegram.org) in `local.properties`:

```properties
sdk.dir=/path/to/Android/Sdk
TG_API_ID=1234567
TG_API_HASH=your_api_hash
```

[.env.example](.env.example) lists every optional value the build reads, what each one turns on,
and how to set the same ones as repository secrets. All of them are optional: with none of them
present the app builds, runs and carries none of this project's own endpoints, which is what a
fork and every CI run get.

```bash
./gradlew test
./gradlew assembleDebug        # the Android app
./gradlew :desktop:run         # the desktop app, on the computer you are at
```

[docs/BUILDING.md](docs/BUILDING.md) covers release builds, the desktop app and its installers,
the reference test devices, and the version pins that are not free choices.

## Read more

- [Feature list](docs/FEATURES.md): everything the app does, and what it deliberately does not.
- [Changelog](https://tmplayer.org/changelog/): every release, with an RSS feed.
- [Install guide](INSTALL.md): sideloading, Windows and Linux, shortcuts, the remote and the keyboard, troubleshooting.
- [Architecture](docs/ARCHITECTURE.md): TDLib, the streaming `DataSource`, why it is built this way.
- [Building](docs/BUILDING.md) and [releasing](docs/RELEASING.md).
- [Contributing](CONTRIBUTING.md): ground rules, style, how to report an issue.
- [Third-party notices](THIRD_PARTY_NOTICES.md): dependency licences and source locations.

## Community and support

- **News:** the [Telegram channel](https://t.me/tmplayerapp) announces every release.
- **Help and chat:** the [Telegram group](https://t.me/tmplayer_chat) or
  [GitHub Discussions](https://github.com/dracu-lah/TMPlayer/discussions). Bugs go in
  [Issues](https://github.com/dracu-lah/TMPlayer/issues); security reports follow [SECURITY.md](SECURITY.md).
- **Chip in:** TMPlayer has no ads of our own (Telegram's sponsored messages appear in public channels, as Telegram requires) and no paid tier. If it helps you,
  [sponsor it on GitHub](https://github.com/sponsors/dracu-lah) or
  [buy me a coffee](https://buymeacoffee.com/nevil.dev). Either way, a star helps the next person trust it.
- **Email:** [hello@tmplayer.org](mailto:hello@tmplayer.org).

## Legal

Unofficial client. Not affiliated with, endorsed by, or connected to Telegram FZ-LLC. It uses the
official Telegram API through TDLib with your own Telegram account, as
[permitted for third-party clients](https://core.telegram.org/api/obtaining_api_id).

TMPlayer does not provide media, recommend channels, or bypass access controls. Use it only with
content you own or are authorized to access. See the website's [Privacy](https://tmplayer.org/privacy)
and [Lawful use](https://tmplayer.org/legal) pages.

## License

[GNU GPL-3.0-or-later](LICENSE) © 2026 dracu-lah, with an [additional permission for OpenSSL](LICENSE-OPENSSL-EXCEPTION.md)
(the Android app's TDLib links OpenSSL 1.1.1w). Bundled libraries and their sources:
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Player mark via [SVG Repo](https://www.svgrepo.com), recoloured.
