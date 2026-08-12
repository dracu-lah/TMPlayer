# Installing TMPlayer on an Android TV device

TMPlayer is sideloaded. It is not on the Play Store, and it never will be (it needs your own
Telegram API credentials to exist at all).

## 1. Download the APK

Download `TMPlayer-<version>-universal.apk` from the Releases page. It contains the native code
for every supported Android TV architecture, so the same file works on older sticks, newer 64-bit
boxes and the Android TV emulator.

## 2. Turn on debugging (once)

On the TV:

1. **Settings → Device Preferences → About**
2. Click **Build** seven times until it says *You are now a developer*
3. **Settings → Device Preferences → Developer options → USB debugging** → on
4. Same screen: **Network debugging** (sometimes *ADB over network*) → on, and note the IP

Also find the IP under **Settings → Network & Internet → your Wi-Fi** if it is not shown.

## 3. Install

From a computer on the same Wi-Fi, with [adb](https://developer.android.com/tools/adb)
installed:

```bash
adb connect 192.168.1.42:5555          # your TV's IP
adb install -r TMPlayer-<version>-universal.apk
```

Accept the *Allow USB debugging?* prompt that appears on the TV.

If you see `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, an older build signed with a different key is
already there. Run `adb uninstall com.tmplayer` first.

The original 1080p Mi TV Stick (`MiTV-AESP0`, Android TV 9) is supported. Its system is 32-bit
ARM even though its processor is based on Cortex-A53; use the universal APK, which includes
`armeabi-v7a`. If its on-screen installer only says *App not installed*, use the ADB command
above to get the real error. A signature mismatch, a version downgrade, too little free space
to unpack the APK, or an incomplete copy are the usual causes, rather than Android 9 itself.

No computer? Any sideload app works (Downloader by AFTVnews, Send Files to TV, a USB stick
with a file manager). The APK is a normal Android package.

## 4. First run

TMPlayer appears in the Android TV launcher's app row with a blue play-button icon.

1. Open it. A QR code appears within a few seconds.
2. On your phone: **Telegram → Settings → Devices → Link Desktop Device**.
3. Point the phone at the TV. The code refreshes itself if it expires; just scan the new one.
4. If your account has two-step verification, type the password with the on-screen keyboard.
   This is the only typing TMPlayer ever asks for.

You land on your chat list. Open any chat to see only its playable videos, and press
**OK** on one to start streaming.

## Using the remote

| Key | What it does |
| --- | --- |
| **OK** | Play / pause, or show the controls |
| **Left / Right** (controls hidden) | Jump back / forward 10 seconds |
| **Left / Right** (controls showing) | Scrub the progress bar, then press **OK** to jump there |
| **⏪ / ⏩** (if your remote has them) | Jump 30 seconds |
| **Subtitle icon** in the controls | Pick a subtitle track, or turn them off |
| **Speaker icon** in the controls | Pick an audio track (dual-audio releases) |
| **Back** | Leave the player; the position is remembered |

## Storage

Telegram keeps what it has streamed so re-watching is instant. TMPlayer caps that at **1 GB**
and trims it after every video. Change the cap or clear it now under **Settings** on the chat
list screen. Worth doing on an 8 GB stick if you keep other apps installed.

## Troubleshooting

**"No Telegram API credentials in this build"**: the APK was built without `TG_API_ID` /
`TG_API_HASH`. See [docs/BUILDING.md](docs/BUILDING.md).

**Video stutters or the picture is black but audio plays.** The file's video codec has no
hardware decoder on this device (common with 4K HEVC 10-bit on the 1080p Mi TV Stick). Nothing
in the app can fix that; a lower-resolution copy will play.

**"Telegram stopped sending file …"**: the connection dropped mid-stream. Press Back and play
again; it resumes from where you were.

**The app is missing from the launcher.** Some TVs hide sideloaded apps in a separate row at
the bottom, or under **Settings → Apps → See all apps**.
