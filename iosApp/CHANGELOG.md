# TMPlayer for iPhone and iPad: changes

Every iOS and iPadOS release of TMPlayer, newest first. A version's section is the text of its
GitHub release and of its entry in the AltStore source, so it is written for the people who use the
app: what changed for them, in plain words. The release stops if its section is missing or empty
(`iosApp/release/altstore_source.py notes`).

<!--
A section starts with "## <version>", or "## <version> build <n>" for an iOS-only fix, and may
add the date in brackets, for example:

## 1.28.5 build 2 (2026-11-20)

- What the fix changes, in plain words.

A test version, tagged apple-v1.28.6-beta.1, has "## 1.28.6-beta.1" and is a pre-release only (docs/IOS.md, "Test versions").

The version and build are the ones the tag names: apple-v1.28.5 releases 1.28.5, build 1, and
apple-v1.28.5-2 its build 2. docs/IOS.md, "Releasing", has the rest.
-->

## 1.28.6-beta.1

A test version for people trying TMPlayer before the release. It is the same app, so the release installs over it. What is new since 1.28.5:

- Skip intro, recap, credits and preview from one Skip button, from the file's chapters, online lists and your own marks, and you can mark an intro yourself
- Quiet loud scenes: a switch in the player that holds loud parts back and makes dialogue clearer
- While streaming: choose Keep video or Save space, and TMPlayer offers Save space when a video does not fit
- On mobile data: one answer (Ask first, Never or Allow) replaces the Wi-Fi only and Save mobile data switches
- Keep my place on all my devices: one small file in your own Saved Messages carries where you stopped, bookmarks, lists and corrections
- Offline: Home shows what is already on this device, with a Needs internet cover on the rest
- Series: Specials, whole seasons in one file, a note for episodes not in your chats yet, Find in other chats and Only mine
- Subtitles: Size and Position now apply to styled (ASS) tracks
- When a picture looks wrong, the player tries another way to show it and keeps the last minute for Send logs
- Choose chats asks Which chats have your videos? and no longer counts videos
- Hide small clips under a size you choose
- Buttons, switches and menu rows are named and at least 48 pt, and large text no longer cuts words

## 1.28.6

- Skip intro, recap, credits and preview from one Skip button, from the file's chapters, online lists and your own marks, and you can mark an intro yourself
- Quiet loud scenes: a switch in the player that holds loud parts back and makes dialogue clearer
- While streaming: choose Keep video or Save space, and TMPlayer offers Save space when a video does not fit
- On mobile data: one answer (Ask first, Never or Allow) replaces the Wi-Fi only and Save mobile data switches
- Keep my place on all my devices: one small file in your own Saved Messages carries where you stopped, bookmarks, lists and corrections
- Offline: Home shows what is already on this device, with a Needs internet cover on the rest
- Series: Specials, whole seasons in one file, a note for episodes not in your chats yet, Find in other chats and Only mine
- Subtitles: Size and Position now apply to styled (ASS) tracks
- When a picture looks wrong, the player tries another way to show it and keeps the last minute for Send logs
- Choose chats asks Which chats have your videos? and no longer counts videos
- Hide small clips under a size you choose
- Buttons, switches and menu rows are named and at least 48 pt, and large text no longer cuts words

## History: TMPlayer for Apple

The port started from TMPlayer for Apple by Pietro Giuseppe D'Amico
(github.com/Killua99x/TMPlayer-for-apple, GPL-3.0), released there under the bundle identifier
io.github.killua99x.tmplayer. Its changes, as it published them, are kept below for the record;
docs/IOS.md, "Changelog of the port", says where each one is in this repository. They are not
release notes of this app (their headings are one level down, so the release script never reads
them as such). In its build 14 that app took its own name and icon; this one is TMPlayer, with
TMPlayer's.

### 1.28.5 build 15 (2026-11-16)

- Home, the chats, Settings and About now run to the bottom edge of the screen, as iOS's own lists
  do: they no longer stop short above the home indicator and leave an empty strip there. The last
  row still ends above it.
- A downloaded video plays from the device at once, without waiting for Telegram's sign in, which
  could leave it on "This video hasn't started".

### 1.28.5 build 14 (2026-11-16)

- TMPlayer for Apple has its own name and icon: a white chat bubble with a play button on blue,
  and "TMPlayer for Apple" under it and at the top of sign in, Home and About. The name TMPlayer
  and its logo belong to the original app for Android, TV and computers, which this one is based
  on. On the Home Screen, iOS may shorten the name to fit under the icon.

### 1.28.5 build 13 (2026-11-16)

- Coming back to TMPlayer after reading the sign in code in Telegram goes back to the code step.
  If iOS had closed the app meanwhile, it showed "This account requires a step TMPlayer cannot
  do", and trying again asked Telegram for a new code each time, until Telegram stopped sending
  them.
- "Scan a QR code instead" on the code step shows the QR code at once, instead of starting the
  sign in over, which could keep a spinner up for half a minute.

### 1.28.5 build 12 (2026-11-15)

- Save logs and Send logs now hold what the app did on an iPhone or iPad: each sign in step,
  Telegram's own messages and the player's, kept across restarts and updates. Until now the file
  held only its first few lines.
- Logs and bug reports name the device as iOS does, such as "iPad (iPad13,18), iPadOS 27.0.1",
  instead of calling it Android, and give the app's build.
- When the sign in code goes to the Telegram app, the code screen offers "Scan a QR code instead".
  Telegram sometimes does not deliver the code, and the QR code needs none.

### 1.28.5 build 11 (2026-11-15)

- With large text, Home's first card, and the pages shown when a list is empty or something
  failed, now scroll far enough to read their last line and reach their buttons above the bar at
  the bottom.

### 1.28.5 build 10 (2026-11-15)

- On a narrow screen, such as an iPhone with Display Zoom, the player's buttons no longer hide
  behind Episodes. Episodes and the copy button show only their icons when the words do not
  fit, and if there is still no room, speed is left to More.
- Info and help no longer ends with a separate version line: the About TMPlayer row just above
  it already shows the version and build.

### 1.28.5 build 9 (2026-11-15)

- The setup's last step is called "Storage and data", without the reports this app does not send.
- The chat picker no longer says the chats with the most videos are already ticked: it
  recommends them, and you tick them.
- Renaming a video keeps its page under the box, instead of showing the screen behind it.
- On an iPad, the theme, rotation and privacy lines say "device" instead of "phone".
- Info and help ends with the version and build installed, such as "Version 1.28.5 (build 9)",
  and the other places that show the version say the build the same way.

### 1.28.5 build 8 (2026-11-14)

- Settings, About and a bug report show the app's version and build, where they showed 0.
- Choosing a poster without a TMDB key of your own says how to add one, instead of saying
  that TMDB refused a key.

### 1.28.5 build 7 (2026-11-14)

- On an iPad, the player's title and buttons no longer sit under the clock and battery when
  the controls come up.

### 1.28.5 build 6 (2026-11-14)

- A failure while opening a video no longer closes the app.

### 1.28.5 build 5 (2026-11-14)

- Sound and picture stay together. After opening a video where you left it, the sound could run
  seconds ahead and the picture then rushed to catch up.

### 1.28.5 build 4 (2026-11-14)

- A video with sound in more than one language now plays one language at a time and lists them
  all, with all of its subtitles, as on Android. Some MP4 files played two languages over each
  other and listed only one.
- "TMPlayer is struggling on this device" no longer appears when it is other apps that fill the
  device's memory.

### 1.28.5 build 3 (2026-11-14)

- Removing your own TMDB key now clears the posters and details it found, as adding or
  changing one does: series and anime find theirs again from TVmaze and AniList, and other videos
  show their Telegram picture again.

### 1.28.5 build 2 (2026-11-14)

- Pictures on Home and in a video's details no longer go black, or show another video's picture,
  after the app is opened again.
- Adding your own TMDB key now finds posters and details again by itself, with no need to clear
  them in Settings.

### 1.28.5 (2026-11-14)

The first version for iPhone and iPad, built from TMPlayer 1.28.5.

- Sign in with your phone number or a QR code, then browse your chats and channels and see only
  their videos.
- Playback starts while the video is still downloading, and seeking moves the download to the new
  spot.
- MP4 plays in Apple's own player, which uses the least battery; MKV, AVI, TS and the rest in mpv.
  Both use the hardware decoder, with embedded subtitles (PGS and VobSub too) and several audio
  tracks.
- Episodes recognised from file names, Continue watching, Favourites and Downloads, as in TMPlayer
  for Android.
- One app for iPhone and iPad: the layout follows the window, in either orientation and beside
  another app.
- 22 languages: Czech is new in this version, and the Russian wording is fixed throughout.
- Not on iPhone and iPad: casting (Google Cast, DLNA, AirPlay video), Picture in Picture, and
  downloads while the app is in the background.

Needs iOS or iPadOS 26.4 or later. Unofficial, not affiliated with Telegram.
