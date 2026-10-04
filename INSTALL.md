# Installing TMPlayer

TMPlayer is not in any store, and it never will be (it needs its own Telegram API credentials to
exist at all). Every build is on the [Releases](../../releases) page, and the
[download page](https://tmplayer.org/download/) lists them by platform.

- [Android TV and phones](#installing-tmplayer-on-an-android-tv-device): sideload the APK
- [Windows](#windows): an MSI installer or a portable zip
- [Linux](#linux): deb, rpm, AppImage, Flatpak, a tarball, or the AUR recipe
- [Desktop, menu and taskbar shortcuts](#putting-it-on-the-desktop-in-the-menu-and-on-the-taskbar)
- [The desktop keyboard](#using-the-keyboard-and-mouse)

macOS is planned for later; there is no Mac build yet.

# Installing TMPlayer on an Android TV device

The same APK installs on a phone: open the file and say yes when Android asks. The steps below are
for a television, where there is no browser to open it from.

## 1. Download the APK

Download `TMPlayer-<version>-universal.apk` from the Releases page. It contains the native code
for every supported Android TV architecture, so the same file works on older sticks, newer 64-bit
boxes and the Android TV emulator. If you are not sure, this is the file to take.

Two smaller APKs sit beside it, carrying one architecture each. Most of the download is native
code, so dropping the architecture you do not have takes roughly 40 per cent off:

| File | For |
| --- | --- |
| `-arm64-v8a.apk` | 64-bit ARM: Chromecast with Google TV, Shield, Fire TV Stick 4K, and any phone from about 2017 on |
| `-armeabi-v7a.apk` | The older 32-bit sticks, including the original Mi TV Stick |

`adb shell getprop ro.product.cpu.abi` reports which one a device wants. Guessing wrongly is safe
in both directions, though they fail differently. The 64-bit file on a 32-bit stick is refused
outright, at install time, with `INSTALL_FAILED_NO_MATCHING_ABIS`. The 32-bit file on a 64-bit
device does install and does work, because a 64-bit Android runs 32-bit code, but it runs the app
as a 32-bit process for no reason: take `arm64-v8a` if the device reports it.

Each release also carries `SHA256SUMS-<version>.txt`. Check a download against it with
`sha256sum -c` if you care to; a truncated transfer otherwise looks like a broken app.

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
| **Left / Right** | Jump back 5 / forward 10 seconds, right on the picture, nothing else comes up |
| **OK** | Show the controls, with the seek bar focused; on the bar OK is play / pause, so OK twice from the bare picture pauses |
| **Left / Right** (on the seek bar) | Travel: 30 seconds a press, presses pile up and land as one seek a moment after the last |
| **Down** (controls up) | The button row: play / pause, jumps, subtitles, audio, speed, picture shape |
| **0 - 9** (if your remote has them) | Jump to that tenth of the video; 5 is halfway |
| **⏪ / ⏩** (if your remote has them) | Jump back 5 / forward 10 seconds |
| **Hold OK** (controls up) | Open the video in another app |
| **Back** | Close what is on top: the controls, then the player; the position is remembered |

## Using a phone

| Touch | What it does |
| --- | --- |
| **Tap** | Show or hide the controls. Settings, Player can make a tap play and pause instead |
| **Big button in the middle** | Play / pause; the arrows either side jump, the outer buttons change episode |
| **Double tap left / right** | Jump back / forward 10 seconds; keep tapping to add 10 more each time |
| **Double tap the middle** | Play / pause |
| **Hold** | Play at 2x for as long as your finger stays down |
| **Drag sideways** | Travel through the video; it jumps when you let go |
| **Drag up / down, left side** | Brightness |
| **Drag up / down, right side** | Volume |
| **Pinch** | Fit, fill or stretch the picture |
| **Tap the total time** | Show the time remaining instead |
| **Hold a button** | Show its name |
| **⋮ menu** | Lock the screen, picture in picture, speed, start over, open in another app, details |

The jump length, the hold speed, how long the controls stay up, each drag and the vibration are
all in Settings, Player.

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

---

# Installing TMPlayer on a computer

The desktop app runs on **Windows 10 and 11 (64-bit)** and on **64-bit x86 Linux**. It signs in
to the same Telegram account, shows the same library and streams the same way as the Android app,
played through mpv, which is bundled: there is nothing to install beside it. macOS is planned for
later; there is no build for it yet.

Every file is on the same GitHub release as the APKs, from version 1.18.0 on:

| File | For |
| --- | --- |
| `TMPlayer-<version>-windows-x64.msi` | Windows installer, per user, no administrator needed |
| `TMPlayer-<version>-windows-x64-portable.zip` | Windows, nothing installed: unzip and run `TMPlayer.exe` |
| `tmplayer_<version>_amd64.deb` | Debian, Ubuntu, Linux Mint, Pop!_OS |
| `tmplayer-<version>.x86_64.rpm` | Fedora, RHEL and rebuilds, openSUSE |
| `TMPlayer-<version>-x86_64.AppImage` | Any distribution, one file |
| `TMPlayer-<version>.flatpak` | Any distribution with Flatpak |
| `TMPlayer-<version>-linux-x64.tar.gz` | Any distribution, the plain app folder |
| `SHA256SUMS-<version>.txt` | Checksums for all of the above |

Check a download with `sha256sum -c --ignore-missing SHA256SUMS-<version>.txt` in the folder that
holds both (on Windows, `Get-FileHash <file>` in PowerShell and compare by eye).

## Windows

1. Download `TMPlayer-<version>-windows-x64.msi`.
2. Open it. The build is not signed yet, so SmartScreen says **Windows protected your PC**. Click
   **More info**, then **Run anyway**.
3. Click through the installer. It installs for your user only, into your own profile, so it does
   not ask for an administrator password.
4. Open TMPlayer from the Start menu or the desktop shortcut.

Installing a newer MSI later upgrades the app in place; your sign-in, history and downloads live in
your user profile and are kept. Remove it under **Settings, Apps, Installed apps**.

**Portable zip.** Unzip `TMPlayer-<version>-windows-x64-portable.zip` anywhere, a USB stick
included, and run `TMPlayer.exe` from the folder. SmartScreen can show the same warning the first
time. If it asks about every file, right click the zip before unzipping, choose **Properties**,
tick **Unblock** and unzip it again.

## Linux

64-bit x86 only for now. Pick by how you like to install software; every format is the same app.

The deb, rpm, AppImage and tarball need a 2024 or newer system (glibc 2.38): Ubuntu 24.04, Debian
13, Linux Mint 22, Fedora 39, openSUSE Tumbleweed, RHEL 10, Arch, or anything newer. On an older
system, such as Ubuntu 22.04, Debian 12 or RHEL 9, take the Flatpak, which brings its own runtime.

**Debian, Ubuntu, Mint and relatives:**

```bash
sudo apt install ./tmplayer_<version>_amd64.deb
```

**Fedora, RHEL and rebuilds:**

```bash
sudo dnf install ./tmplayer-<version>.x86_64.rpm
```

**openSUSE:**

```bash
sudo zypper install --allow-unsigned-rpm ./tmplayer-<version>.x86_64.rpm
```

**Flatpak, on any distribution.** The bundle takes the `org.freedesktop.Platform` runtime from
Flathub, so add that remote first if you do not have it (the first line does nothing if you do):

```bash
flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
flatpak install --user ./TMPlayer-<version>.flatpak
```

**AppImage, on any distribution:**

```bash
chmod +x TMPlayer-<version>-x86_64.AppImage
./TMPlayer-<version>-x86_64.AppImage
```

Or tick *Allow executing file as program* in the file's properties and double click it.

**Tarball, on any distribution:**

```bash
mkdir -p ~/.local/opt
tar xzf TMPlayer-<version>-linux-x64.tar.gz -C ~/.local/opt
~/.local/opt/TMPlayer/bin/TMPlayer
```

**Arch Linux.** A PKGBUILD for the AUR lives in [desktop/packaging/aur](desktop/packaging/aur): build it with
`makepkg -si` from that folder. The AppImage and the Flatpak also run on Arch as they are.

To upgrade, install the newer file the same way: the deb, rpm and Flatpak replace the old version,
and for the AppImage or tarball you replace the file or folder. Your sign-in and history live in
your home directory and are kept. To remove it: `sudo apt remove tmplayer`, `sudo dnf remove
tmplayer`, or `flatpak uninstall` with the ID that `flatpak list` shows.

## First run on a computer

1. Open TMPlayer. A QR code fills the window within a few seconds.
2. On your phone: **Telegram, Settings, Devices, Link Desktop Device**, and point the camera at the
   screen. **Log in by phone number** is under the code, for an account with no phone to hand.
3. If your account has two-step verification, type the password.

You land on your chats. The sidebar has Chats, Favourites, Continue, Downloads and Settings; open a
chat for its videos, hover a tile for its Play button, or right click it to play from the start,
download it or copy its Telegram link.

## Updating on a computer

Ten seconds after it starts, signed in or not, and every six hours while it runs, TMPlayer looks
for a newer release (Settings, Updates turns that off or checks now). It reads
`tmplayer.org/latest.json` and asks GitHub only when the site cannot be reached. When a newer
version is out, an amber **Update** item with the version beside it appears at the bottom of the
side bar (on the narrow rail, the version sits on the icon). It stays until the new version is
installed or you skip it, and clicking it opens a popup: **TMPlayer 1.20.0 is out**, with what is
new and three buttons, **Update now**, **Remind me later** and **Skip this version**. The popup
also opens by itself once per version, never while a video plays. **Remind me later** keeps the
item and brings the popup back a day later; **Skip this version** hides both until a newer one.
The phone and the TV do the same with the amber **Update** row in the drawer and the rail.

| Installed from | What **Update now** does |
|---|---|
| Windows installer (MSI) | Downloads the new MSI, closes, installs it without an administrator prompt, and opens again |
| Windows portable zip | Unpacks the new version beside the folder, closes, swaps the folders, and opens again |
| AppImage | Replaces the AppImage file in place; **Restart now** in the popup runs the new one |
| deb or rpm | Installs the package with `pkexec`, so your system asks for your password; then **Restart now** |
| Flatpak, tarball | **Open release page**; Flatpak updates come through your software centre or `flatpak update`, and the tarball is unpacked over the old one |
| Android phone or TV | Downloads the APK (on mobile data the button says how much; with Wi-Fi only on, it waits for Wi-Fi), then Android asks you to confirm |

The download, the check and **Restart now** all happen inside the popup; **Later** leaves the
item reading **Restart to update**. Every download is checked against its SHA-256 before anything
is installed, and nothing happens until you press the button. Your sign in, downloads and settings
stay.

The deb and rpm route needs a polkit agent for the password prompt. Full desktops (GNOME, KDE,
Xfce, Cinnamon) run one; on sway or another bare compositor, start one (for example
`lxpolkit` or `polkit-gnome-authentication-agent-1`) or install the package by hand.

## Putting it on the desktop, in the menu and on the taskbar

### Windows

- **Installed with the MSI:** TMPlayer is already in the Start menu and on the desktop. To pin it
  to the taskbar, open Start, find TMPlayer, right click it and choose **Pin to taskbar**. Or,
  while it is running, right click its taskbar icon and choose **Pin to taskbar**.
- **From the portable zip:** right click `TMPlayer.exe`, then **Send to**, then **Desktop (create
  shortcut)**. On Windows 11 these are under **Show more options**. For a Start menu entry, press
  `Win`+`R`, type `shell:programs`, and move that shortcut into the folder that opens. Pinning to
  the taskbar works from the same right click menu, or from the running app's taskbar icon.

### Linux: deb, rpm and Flatpak

These add TMPlayer to the app menu on their own, under Multimedia or Sound and Video.

- **GNOME:** open Activities, search for TMPlayer, right click it and choose **Pin to Dash**
  (**Add to Favourites** on older releases). Right clicking the running app's icon in the dash
  does the same.
- **KDE Plasma:** open the application launcher, right click TMPlayer and choose **Pin to Task
  Manager**, or **Add to Favourites** for the launcher's own list. Right clicking the running
  app's icon in the task manager offers **Pin to Task Manager** too.
- **Xfce, Cinnamon, MATE:** right click the entry in the menu and choose **Add to Panel** or
  **Add to Favourites**.

### Linux: AppImage and tarball

A single file has nowhere to register a menu entry, so you write one. These commands move the
AppImage somewhere permanent (renamed, so an update is a drop-in replacement), fetch the icon and
create a launcher in `~/.local/share/applications`:

```bash
mkdir -p ~/Applications ~/.local/share/icons ~/.local/share/applications
mv ~/Downloads/TMPlayer-<version>-x86_64.AppImage ~/Applications/TMPlayer.AppImage
chmod +x ~/Applications/TMPlayer.AppImage
curl -Lo ~/.local/share/icons/tmplayer.png https://tmplayer.org/icon-512.png

cat > ~/.local/share/applications/tmplayer.desktop <<EOF
[Desktop Entry]
Type=Application
Name=TMPlayer
Comment=Telegram media player
Exec=$HOME/Applications/TMPlayer.AppImage
Icon=$HOME/.local/share/icons/tmplayer.png
Categories=AudioVideo;Video;Player;
Terminal=false
StartupWMClass=com-tmplayer-desktop-MainKt
EOF

chmod +x ~/.local/share/applications/tmplayer.desktop
update-desktop-database ~/.local/share/applications
```

The shell expands `$HOME` while writing the file, which matters: a launcher needs full paths and
does not understand `~`. The finished file reads, for a user called `you`:

```ini
[Desktop Entry]
Type=Application
Name=TMPlayer
Comment=Telegram media player
Exec=/home/you/Applications/TMPlayer.AppImage
Icon=/home/you/.local/share/icons/tmplayer.png
Categories=AudioVideo;Video;Player;
Terminal=false
StartupWMClass=com-tmplayer-desktop-MainKt
```

For the tarball unpacked into `~/.local/opt`, use the same commands without the `mv` and `chmod`
of the AppImage, and change one line:

```ini
Exec=/home/you/.local/opt/TMPlayer/bin/TMPlayer
```

TMPlayer then appears in the app menu (log out and in again if your desktop is slow to notice),
and you can pin it as described above.

Prefer not to type it? [Gear Lever](https://flathub.org/apps/it.mijorus.gearlever) and
[AppImageLauncher](https://github.com/TheAssassin/AppImageLauncher) both move an AppImage into
place and create its menu entry for you.

### Linux: an icon on the desktop

Copy a launcher onto the desktop and mark it trusted. For the AppImage or tarball launcher made
above:

```bash
cp ~/.local/share/applications/tmplayer.desktop ~/Desktop/
chmod +x ~/Desktop/tmplayer.desktop
gio set ~/Desktop/tmplayer.desktop metadata::trusted true
```

For a deb, rpm or Flatpak install, find the launcher the package added and copy that one the same
way:

```bash
find /usr/share/applications ~/.local/share/flatpak/exports/share/applications \
  /var/lib/flatpak/exports/share/applications -iname '*tmplayer*.desktop' 2>/dev/null
```

GNOME shows desktop icons only with the Desktop Icons NG extension, and the first time you need to
right click the icon and choose **Allow Launching**. KDE Plasma asks once whether to run it. Xfce
and Cinnamon read the trusted flag the last command set.

## Using the keyboard and mouse

The player's keys follow YouTube's letters, with mpv's and VLC's punctuation working as well.
None of them fire while a search field has focus.

| Key | What it does |
| --- | --- |
| **Space**, **K** | Play or pause (also a single click on the picture) |
| **Left / Right** | Back / forward 5 seconds |
| **J / L** | Back / forward 10 seconds (also Shift with the mouse wheel) |
| **Shift+Left / Shift+Right** | Back / forward 1 minute |
| **Ctrl+Alt+Left / Ctrl+Alt+Right** | Back / forward 5 minutes |
| **, / .** | One frame back / forward, while paused |
| **0 - 9** | Jump to that tenth of the video; 5 is halfway. **Home** and **End** go to the start and the end |
| **Up / Down** | Volume (also the mouse wheel) |
| **M** | Mute |
| **F**, **F11**, **Alt+Enter** | Fullscreen (also a double click); **Esc** leaves it |
| **S / Shift+S / C** | Next / previous subtitles, subtitles on or off |
| **A / Shift+A** | Next / previous audio track |
| **] / [ / Backspace** | Faster / slower / back to normal speed |
| **Shift+N / Shift+P** | Next / previous episode (also Page Down / Page Up) |
| **Ctrl+T** | Keep the window on top |
| **Ctrl+P** | Mini player: a small always on top picture in the corner, and back |
| **?** | Show every shortcut |
| **I** | Playback details |
| **Esc**, **Backspace** | Back to the grid (also the mouse's back button) |
| **Ctrl+Q** | Quit |

Right click the picture for the player's menu: audio, subtitles, speed, picture shape and the
rest. In the grid, **/** or **Ctrl+F** searches and **Ctrl+,** opens Settings. The media keys on
a keyboard work while the window has focus, and on Linux the desktop's own media controls drive it
through MPRIS.

## Troubleshooting on a computer

**Windows shows no Run anyway button.** Click **More info** first; the button only appears after
it. On a managed work computer a policy can forbid unsigned apps, and nothing in the app can
change that.

**The AppImage does nothing when opened.** Usually FUSE is missing, which Ubuntu 22.04 and newer
no longer install by default. Run it from a terminal to see the message, then install `libfuse2t64`
(Ubuntu 24.04 and newer) or `libfuse2` (22.04). Or skip FUSE with
`./TMPlayer-<version>-x86_64.AppImage --appimage-extract-and-run`.

**Flatpak says a runtime is missing.** Add the Flathub remote with the `flatpak remote-add` line
above and install again.

**The window is blank or grey on a tiling window manager** (sway, i3, Hyprland and the like).
Start it with `_JAVA_AWT_WM_NONREPARENTING=1` set in the environment.

**A build you made yourself never shows a QR code.** It was built without `TG_API_ID` /
`TG_API_HASH` in `local.properties`. See [docs/BUILDING.md](docs/BUILDING.md).
