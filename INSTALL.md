# Installing TMPlayer

TMPlayer is not in any store, and it never will be (it needs its own Telegram API credentials to
exist at all). Every build is on the [Releases](../../releases) page, and the
[download page](https://tmplayer.org/download/) lists them by platform.

- [Android TV and phones](#installing-tmplayer-on-an-android-tv-device): sideload the APK
- [Windows](#windows): an MSI installer
- [Linux](#linux): an AppImage, or the AUR recipe on Arch
- [Moving from the deb, rpm, Flatpak or Windows zip](#moving-from-a-format-that-is-no-longer-published)
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
ARM even though its processor is based on Cortex-A53; the universal APK carries 32-bit code for
it. If its on-screen installer only says *App not installed*, use the ADB command
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
| **⋮ menu** | Lock the screen, picture in picture, speed, start over, open in another app, details, save to Downloads |

The jump length, the hold speed, how long the controls stay up, each drag and the vibration are
all in Settings, Player.

## Storage

TMPlayer keeps two kinds of video on the device, and keeps them apart.

- **Cached**: the video you last played, so playing it again needs no download. One at a time:
  starting another video replaces it, without asking. Thumbnails and previews are cached too.
- **Downloads**: videos you chose to keep, with **Download** or **Save to Downloads**. They live
  in TMPlayer's own Downloads folder, play without a connection, and stay until you delete them.
  A finished download moves out of the cache into that folder; if you are watching it at the
  time, the move waits until you stop.

A cached video can become a download at any time: **Save to Downloads** in the tile's menu, the
player's menu, or the **Cached from playback** tab of the Downloads screen. **Settings, Storage**
shows what each kind takes, lists the cached videos, and clears the cache, the pictures and
previews, or both, without touching your downloads. Signing out keeps your downloads unless you
tick **Also delete my downloads**.

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

Every file is on the same GitHub release as the APK:

| File | For |
| --- | --- |
| `TMPlayer-<version>-windows-x64.msi` | Windows installer, per user, no administrator needed |
| `TMPlayer-<version>-x86_64.AppImage` | Linux, any distribution, one file |
| `TMPlayer-<version>-linux-x64.tar.gz` | The plain app folder, which the Arch PKGBUILD builds from |
| `SHA256SUMS-<version>.txt` | Checksums for all of the above |

Releases up to 1.21.0 also carried a Windows portable zip, a deb, an rpm and a Flatpak. Those are
no longer built; see [Moving from a format that is no longer published](#moving-from-a-format-that-is-no-longer-published).

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

## Linux

64-bit x86 only for now, and a 2024 or newer system (glibc 2.38): Ubuntu 24.04, Debian 13, Linux
Mint 22, Fedora 39, openSUSE Tumbleweed, RHEL 10, Arch, or anything newer.

**AppImage, on any distribution:**

```bash
chmod +x TMPlayer-<version>-x86_64.AppImage
./TMPlayer-<version>-x86_64.AppImage
```

Or tick *Allow executing file as program* in the file's properties and double click it.

**Arch Linux.** A PKGBUILD for the AUR lives in [desktop/packaging/aur](desktop/packaging/aur): build it with
`makepkg -si` from that folder. It builds from the release's `linux-x64.tar.gz`. The AppImage also
runs on Arch as it is.

To upgrade, the AppImage updates itself from inside the app (see below), or you replace the file
by hand. Your sign-in and history live in your home directory and are kept.

## Moving from a format that is no longer published

The Windows portable zip, the deb, the rpm and the Flatpak were built up to 1.21.0 and are not
built any more. A copy installed from one of them keeps working, but it cannot update itself: when
a newer version is out, its update popup still tells you about it, and its button opens the
release page, where there is no file in that format. Remove the old copy and install the MSI or
the AppImage instead.

| Installed from | Remove it with | Then take | Sign-in and settings |
| --- | --- | --- | --- |
| Windows portable zip | Close TMPlayer and delete its folder | The MSI | Kept: they live in `%LOCALAPPDATA%\TMPlayer`, not in the folder |
| deb | `sudo apt remove tmplayer` | The AppImage | Kept: they live in your home directory |
| rpm | `sudo dnf remove tmplayer` (openSUSE: `sudo zypper remove tmplayer`) | The AppImage | Kept: they live in your home directory |
| Flatpak | `flatpak uninstall io.github.dracu_lah.TMPlayer` | The AppImage | Not carried over by themselves, see below |

Your downloads stay in `~/Downloads/TMPlayer` (or `Downloads\TMPlayer` on Windows) in every case,
and the new copy finds them there.

**From the Flatpak.** A Flatpak keeps its sign-in, settings and cache inside its own sandbox folder,
`~/.var/app/io.github.dracu_lah.TMPlayer`, where the AppImage does not look. Either sign in again
in the AppImage, or copy them across before the first launch of the AppImage:

```bash
mkdir -p ~/.local/share ~/.config
cp -a ~/.var/app/io.github.dracu_lah.TMPlayer/data/TMPlayer ~/.local/share/
cp -a ~/.var/app/io.github.dracu_lah.TMPlayer/config/TMPlayer ~/.config/
```

`flatpak uninstall` leaves that folder in place, so this works after uninstalling too; once the
AppImage is signed in, remove it with `rm -rf ~/.var/app/io.github.dracu_lah.TMPlayer`. If you
picked a storage location in the Flatpak's settings, check it again in the AppImage's
**Settings, Storage**.

## First run on a computer

1. Open TMPlayer. A QR code fills the window within a few seconds.
2. On your phone: **Telegram, Settings, Devices, Link Desktop Device**, and point the camera at the
   screen. **Log in by phone number** is under the code, for an account with no phone to hand.
3. If your account has two-step verification, type the password.

You land on your chats. The sidebar has Chats, Favourites, Continue, Downloads and Settings; open a
chat for its videos, hover a tile for its Play button, or right click it to play from the start,
download it or copy its Telegram link. Ctrl+click (Cmd+click on a Mac) picks several tiles and
Shift+click a run of them; **Download selected** then queues them all.

## Storage on a computer

A computer keeps the same two kinds of video as the phone, with more room for the first:

- **Cached**: what playing leaves behind, so a video played again starts at once. The desktop keeps
  as many as fit under **Settings, Storage, Cache limit** (10 GB to start, anything from 2 to
  100 GB) and deletes the least recently played first once it is over. TMPlayer may delete cache
  at any time; nothing in it is yours to keep.
- **Downloads**: videos you chose to keep. Each is a plain file under its own name, moved out of
  the cache into the Downloads folder when it finishes, and kept until you delete it. They play
  without a connection, and **Open in another app** hands one to any other player.

Where the files are, until you choose otherwise:

| | Downloads | Cache |
|---|---|---|
| Linux | `~/Downloads/TMPlayer` (your XDG Downloads folder) | `~/.cache/TMPlayer/cache` |
| Windows | `Downloads\TMPlayer` (your Downloads folder, wherever it has been moved) | `%LOCALAPPDATA%\TMPlayer\Cache\cache` |

Your sign in and settings stay in the per user data and config folders and never move.

**Moving them:** **Settings, Storage, Storage location, Change** and pick a folder, a second drive
for instance. TMPlayer makes a `TMPlayer` folder inside it with `downloads`, `cache` and `updates`,
moves your downloads across (a rename on the same drive, a checked copy to another, with progress
in the system's notifications), clears the cache rather than copying it, and keeps you signed in.
Playback and downloads pause while it runs. If the app is closed part way, it finishes the move on
the next launch. **Reset** moves everything back to the folders above. A folder TMPlayer used
before is recognised and its downloads taken back on; **Scan the downloads folder** lists any file
there that TMPlayer does not know, to keep or delete.

The Storage card shows what downloads, cached videos and pictures take, and the free space on each
drive involved. **Clear cache**, **Clear pictures and previews** and **Clear everything except
downloads** never touch a download. Signing out keeps your downloads unless you tick **Also delete
my downloads**.

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
| AppImage | Replaces the AppImage file in place; **Restart now** in the popup runs the new one |
| Arch package, tarball | **Open release page**; rebuild the PKGBUILD, or unpack the tarball over the old one |
| Windows portable zip, deb, rpm, Flatpak | Announces the new version and opens the release page, which no longer has that format; see [Moving from a format that is no longer published](#moving-from-a-format-that-is-no-longer-published) |
| Android phone or TV | Downloads the APK (on mobile data the button says how much; with Wi-Fi only on, it waits for Wi-Fi), then Android asks you to confirm |

The download, the check and **Restart now** all happen inside the popup; **Later** leaves the
item reading **Restart to update**. Every download is checked against its SHA-256 before anything
is installed, and nothing happens until you press the button. Your sign in, downloads and settings
stay.

## Putting it on the desktop, in the menu and on the taskbar

### Windows

The MSI puts TMPlayer in the Start menu and on the desktop. To pin it to the taskbar, open Start,
find TMPlayer, right click it and choose **Pin to taskbar**. Or, while it is running, right click
its taskbar icon and choose **Pin to taskbar**.

### Linux: once it is in the app menu

The Arch package adds TMPlayer to the app menu on its own, under Multimedia or Sound and Video. For
the AppImage, make the entry first with the commands in the next section.

- **GNOME:** open Activities, search for TMPlayer, right click it and choose **Pin to Dash**
  (**Add to Favourites** on older releases). Right clicking the running app's icon in the dash
  does the same.
- **KDE Plasma:** open the application launcher, right click TMPlayer and choose **Pin to Task
  Manager**, or **Add to Favourites** for the launcher's own list. Right clicking the running
  app's icon in the task manager offers **Pin to Task Manager** too.
- **Xfce, Cinnamon, MATE:** right click the entry in the menu and choose **Add to Panel** or
  **Add to Favourites**.

### Linux: putting the AppImage in the app menu

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

TMPlayer then appears in the app menu (log out and in again if your desktop is slow to notice),
and you can pin it as described above.

Prefer not to type it? [Gear Lever](https://flathub.org/apps/it.mijorus.gearlever) and
[AppImageLauncher](https://github.com/TheAssassin/AppImageLauncher) both move an AppImage into
place and create its menu entry for you.

### Linux: an icon on the desktop

Copy a launcher onto the desktop and mark it trusted. For the AppImage launcher made above:

```bash
cp ~/.local/share/applications/tmplayer.desktop ~/Desktop/
chmod +x ~/Desktop/tmplayer.desktop
gio set ~/Desktop/tmplayer.desktop metadata::trusted true
```

For the Arch package, copy the launcher it added,
`/usr/share/applications/io.github.dracu_lah.TMPlayer.desktop`, the same way.

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
a keyboard work while the window has focus. On Linux the desktop's own media controls drive it
through MPRIS; on Windows the video's title shows in the media flyout by the volume control (and on
the lock screen), whose play, pause and stop buttons, like the media keys, reach the player even
when another window has focus.

## Troubleshooting on a computer

**Windows shows no Run anyway button.** Click **More info** first; the button only appears after
it. On a managed work computer a policy can forbid unsigned apps, and nothing in the app can
change that.

**The AppImage does nothing when opened.** Usually FUSE is missing, which Ubuntu 22.04 and newer
no longer install by default. Run it from a terminal to see the message, then install `libfuse2t64`
(Ubuntu 24.04 and newer) or `libfuse2` (22.04). Or skip FUSE with
`./TMPlayer-<version>-x86_64.AppImage --appimage-extract-and-run`.

**The window is blank or grey on a tiling window manager** (sway, i3, Hyprland and the like).
Start it with `_JAVA_AWT_WM_NONREPARENTING=1` set in the environment.

**A build you made yourself never shows a QR code.** It was built without `TG_API_ID` /
`TG_API_HASH` in `local.properties`. See [docs/BUILDING.md](docs/BUILDING.md).

## Known gaps

**Fullscreen on Windows 11 has not been checked by hand yet.** Going fullscreen from a maximized
window used to leave the window under the taskbar on the way back. The fix is checked on every
push on a Windows Server runner (one monitor, no Windows 11 taskbar), so the cases below still
want someone with Windows 11 to try them and tick them off:

- [ ] Maximized, **F**, **F**: the window comes back maximized, with the taskbar visible below it.
- [ ] Not maximized, **F**, **F**: the window comes back to the same size and place.
- [ ] The same two with the taskbar at the top, and on the left.
- [ ] With the taskbar set to hide automatically, it still pops up over the restored window.
- [ ] A second monitor at a different scale: fullscreen fills that monitor, and back lands there.
- [ ] **Win+Up** while fullscreen does not leave fullscreen half way; **F** then comes back maximized.
- [ ] Minimize from the taskbar while fullscreen, then restore: still fullscreen, and **F** comes back as before.
