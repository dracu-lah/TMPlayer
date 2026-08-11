<p align="center">
  <img src="art/player.svg" width="88" alt="TMPlayer logo" />
</p>

<h1 align="center">TMPlayer</h1>

<p align="center">Your Telegram videos, on the TV and in your hand. Start watching before the download finishes.</p>

<p align="center">
  <img alt="License" src="https://img.shields.io/badge/license-GPL--3.0-blue" />
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android%208.0%2B%20(TV%20and%20phone)-3ddc84" />
  <img alt="Tests" src="https://img.shields.io/badge/tests-152%20passing-brightgreen" />
</p>

---

You have videos in Telegram chats and channels. Watching one on the TV usually means downloading
the whole file to another device first, copying it across, and finding somewhere to keep it.

TMPlayer downloads it too. The difference is that it does not make you wait: scan a QR code with
your phone, pick a video, and it starts playing within seconds while the rest arrives in the
background. Skip well ahead and it fetches that part next, because the download moves to wherever
you are instead of grinding through everything before it.

It runs on an 8 GB TV stick with a gigabyte of RAM, because that is what it was built and tested
on. The same build is a phone app on a phone: a Material 3 layout with an app bar, a dense
captionless grid and a back arrow where a television gets a navigation rail and a focus ring.

<p align="center">
  <img src="docs/screenshots/tv-chats.webp" width="90%" alt="Browsing chats on TMPlayer on a television" />
</p>

## What it does

**Signs in with a QR code, or with your number.** On a TV: open Telegram on your phone, go to
Settings → Devices → Link Desktop Device, point it at the screen. No typing an email address with a
D-pad. On a phone the number comes first, with the country picker and the code boxes Telegram
itself uses, and the QR route is there for an account that lives on another handset. Two-step
verification works either way, and a short walkthrough on first run says what to press, replayable
from Settings.

**Shows media, not messages.** Browse the chats and channels you already have, then see only their
playable videos. Text messages never appear. Video documents are included too, so an `.mkv` sent
as a file is not missed.

**Starts fast and seeks properly.** Playback begins while the file is still arriving, and seeking
re-aims the download rather than waiting for it. This is the whole reason the app exists. Bring the
controls up and the corner says how much of the video has arrived, and how quickly.

**Or waits, if your connection would rather.** Settings has a *Download the whole video first*
switch, off by default. On, the video is fetched in full before it starts, which beats being
stopped every few minutes on a line that cannot keep up with playback.

**Handles real-world media files.** MKV, MP4, AVI, TS, and the rest. Embedded subtitles,
including image-based PGS and VobSub, switchable on both a remote and a phone. Multiple audio
tracks you can switch during playback.
DTS, TrueHD and E-AC3 decode in software when the stick has no silicon for them, while video stays
on the hardware decoder.

**Understands episode filenames.** A name carrying `S02E04`, `2x04`, or `Season 2 Episode 4`
is recognized locally. During playback, previous and next controls move between neighbouring
episodes already present in the same chat, on a remote and under a thumb alike, and when one
finishes the next starts on its own after a countdown you can stop. A switch in Settings turns
that off.

**Remembers where you stopped.** Every video gets its own saved position. Hold OK in Continue
watching to forget one, or clear the whole list at once from its heading or from Settings.
Starring a chat keeps it in Favourites, and the chat you last watched from reopens on launch.

**Keeps one video on the device.** An 8 GB stick cannot hold a library. TMPlayer caches the video you
are watching and clears the previous one, telling you exactly how much space that freed. A setting
turns that into a question first, for anyone who would rather be asked. Coming back to a video you
stopped half way through keeps the half already on disk instead of starting the download again, and
backing out of one stops the download rather than leaving it running in the background.

**Is a phone app on a phone.** The same library, drawn the way Android draws things in the hand:
a Material 3 type scale, full-bleed chat rows, search that expands into the app bar, actions as
toolbar icons, real switches and dialogs and sheets, a back arrow on every screen, and a player
that hides the system bars instead of playing underneath them.

**Stays out of the way.** Rows or tiles, chosen beside the list it rearranges and remembered.
Size limits keep a chat full of tiny clips from burying longer videos. A resolution badge on
each tile and nothing else, because the codec was never going to change anyone's mind. A tile
cuts a long filename short, so the whole name sits in the bottom corner of the screen for whatever
the remote is standing on, the way a browser shows the link under the cursor.

**Catches up on demand.** A Refresh button beside every listing, for a video posted while the
screen was open. Nothing is on a timer: a television that re-fetches by itself is a television
that moves what you were about to press. Coming back after the TV has been asleep, the app waits
for Telegram to answer before it says a chat is empty.

**Forgives how you type.** Search is matched word by word rather than as a literal run of
characters, so an accent nobody types, a swapped pair of letters, or one wrong keyword among right
ones still finds the thing. Exact matches still come first.

**Finds things by voice.** Press the microphone and say a channel or a file name. It uses the
microphone already in your remote, or your phone's own dictation, and asks for no permissions to
do it.

**Keeps itself current.** It checks GitHub once a launch, puts an amber *Update* on the rail when
there is a newer release, and downloads and installs it for you. Signing out clears everything it
ever kept: the Telegram session, your history, favourites, settings, and downloaded media.

<p align="center">
  <img src="docs/screenshots/tv-settings.webp" width="90%" alt="TMPlayer settings on a television" />
</p>

The phone gets its own layout out of the same APK: the chat list, then the chat's videos as tiles.

<p align="center">
  <img src="docs/screenshots/phone.webp" width="80%" alt="TMPlayer on a phone: the chat list beside a chat's videos as a grid" />
</p>

## Install it

Grab the universal APK from [Releases](../../releases). The same file works on every supported
Android TV architecture, so there is no CPU variant to identify.

On the TV, turn on Developer options (Settings → About → press *Build* seven times), enable network
debugging, then:

```bash
adb connect <tv-ip>:5555
adb install TMPlayer-<version>-universal.apk
```

[INSTALL.md](INSTALL.md) covers the sideloading steps in full, along with the remote-control
reference and what to do when something misbehaves.

## What it is not

It hosts nothing, indexes nothing and uploads nothing. There is no server, no bot, and no account
except your own. It shows you media from chats you already joined, and what you do with that is
your responsibility.

It is also not a general Telegram client. You cannot read messages, reply, or send anything. It
plays videos, and that is all it does.

## How it works

[TDLib](https://core.telegram.org/tdlib), Telegram's own client library, handles the account, the
chats and the files, through [tdl-coroutines](https://github.com/g000sha256/tdl-coroutines), which
ships prebuilt natives for every ABI here.

The interesting part is a small [Media3](https://developer.android.com/media/media3) `DataSource`
that maps ExoPlayer's reads onto TDLib's offset-based partial downloads. Seek to a new position and
it points TDLib at that byte instead of waiting for the intervening gigabytes. The window
arithmetic lives in one pure class with the awkward cases pinned down by unit tests, because that
is where a streaming player either works or does not.

The player itself is Media3's leanback integration: the standard Android TV transport controls,
not a hand-rolled imitation of them.

## Build it

You need JDK 17 or newer (21 is what this is built with) and Android SDK platform 36. The app
itself runs on API 26 and up.

Telegram requires every client to identify itself, so you need your own credentials. They are free
and take about two minutes at [my.telegram.org](https://my.telegram.org) → *API development
tools*. Put them in `local.properties` at the repo root:

```properties
sdk.dir=/path/to/Android/Sdk
TG_API_ID=1234567
TG_API_HASH=your_api_hash
```

Then:

```bash
./gradlew test              # unit tests
./gradlew assembleDebug     # per-ABI debug APKs
./gradlew assembleRelease   # signed release, needs keystore.properties
```

The build works without credentials; the app just says so on the login screen instead of drawing
a QR code, which is what CI does on every pull request.

## Contributing

[CONTRIBUTING.md](CONTRIBUTING.md) has the details. The short version: keep it small, prefer an
existing library to new code, put logic that can be tested behind a pure function and test it, and
keep the provenance and licence of every dependency or adapted implementation explicit.

Some version constraints in this repo look arbitrary and are not. Each one is documented in
CONTRIBUTING.md with the reason it exists. Please read that before bumping anything.

## Legal

Unofficial client. Not affiliated with, endorsed by, or connected to Telegram FZ-LLC. It uses the
official Telegram API through TDLib with your own credentials, as
[permitted for third-party clients](https://core.telegram.org/api/obtaining_api_id).

TMPlayer does not provide media, recommend channels, or bypass access controls. Use it only with
content you own or are authorized to access. See the website's [Privacy](https://tmplayer.org/privacy)
and [Lawful use](https://tmplayer.org/legal) pages.

## License

[GNU GPL-3.0](LICENSE) © 2026 dracu-lah

Dependency licences and source locations are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Player mark via [SVG Repo](https://www.svgrepo.com), recoloured.
