# Telegram's app list, directories and forums

## telegram.org/apps

Status: **no public submission path.** Checked 2026-10-05.

<https://telegram.org/apps> has an "Unofficial apps" section, but it lists exactly three entries,
the same for years: Telegram CLI, Unigram and MadelineProto (plus the official TDLib based
Telegram X under source code). There is no form, no "submit your app" link, and nothing about a
listing in the API docs (<https://core.telegram.org/api>, <https://core.telegram.org/tdlib>).
Telegram's press page routes press only through the @PressBot account, which is for journalists.

Realistic options, none guaranteed:

1. **Do nothing here.** Recommended. Its list is not a directory, and an unanswered request costs
   nothing but is very unlikely to change it.
2. If you want to try, a short, polite message through @PressBot (linked from
   telegram.org/press) is the only reachable channel found. It is meant for press, so frame it as
   a project note, and expect no reply.
3. Put the effort into the places below instead.

What matters more than the listing is staying inside Telegram's **API Terms of Service**
(<https://core.telegram.org/api/terms>), which TMPlayer appears to do:

- 2.1 own `api_id`: yes, from CI secrets.
- 2.2 "users must be aware ... that your app uses the Telegram API", prominently in store
  descriptions: the fastlane description, winget locale and drafts here all say it.
- 2.3 the title must not contain "Telegram" (unless preceded by "Unofficial"): "TMPlayer" is fine.
  Keep "Telegram" out of every store title, including AlternativeTo and Uptodown.
- 2.4 no Telegram logo: the icon is TMPlayer's own.
- 3.3 channel sponsored messages must be supported: `SponsoredMessageRepository` does this.

Draft, only if you decide to send something (to @PressBot):

```
Hello. TMPlayer is a free, GPL-3.0, unofficial Telegram client built on TDLib that does one
thing: plays the videos in your chats and channels on Android TV, phones, Windows and Linux,
streaming while they download. It follows the API terms (own api_id, unofficial wording, no
Telegram logo, sponsored messages shown). If there is ever room for it among the unofficial
apps on telegram.org/apps, it would help TV users find a safe option.
https://tmplayer.org  https://github.com/dracu-lah/TMPlayer
```

## AlternativeTo

Status: draft. Submit at <https://alternativeto.net> (log in, "Add new application").

- **Name:** TMPlayer
- **Short description (tagline):** Watch the videos in your Telegram chats on your TV, phone or
  computer, while they download.
- **Description:**

  > TMPlayer is a free and open source video player for the videos in your own Telegram chats
  > and channels. It runs on Android TV, Fire TV, Android phones, Windows and Linux, and starts
  > playing within seconds while the file is still downloading; seeking re-aims the download
  > instead of waiting. Sign in on a TV by scanning a QR code with your phone. Pick a chat and
  > only its playable videos appear, as a poster grid with what you were watching first. It plays
  > MKV, MP4, AVI and TS with embedded subtitles and multiple audio tracks, recognises episode
  > names and moves on to the next episode, and remembers where you stopped. No ads of its own,
  > no account except your Telegram one, no server in between.
  >
  > TMPlayer is an unofficial app, not made by or affiliated with Telegram. It uses the Telegram
  > API through TDLib and needs your own Telegram account.

- **Website:** https://tmplayer.org
- **Source:** https://github.com/dracu-lah/TMPlayer
- **License:** Open source (GPL-3.0), Free
- **Platforms:** Android, Android TV, Windows, Linux, Fire TV (if offered), AppImage
- **Category:** Video and Movies (or Audio and Music, Media Player)
- **Tags / features:** video-player, media-player, telegram, streaming, android-tv,
  subtitle-support, ad-free, open-source, lightweight, remote-control. Do not pick
  "no registration required": it needs a Telegram account.
- **Alternative to** (add TMPlayer as an alternative on each page):
  - Kodi: the TV media centre people try first for this
  - VLC Media Player: the general player people otherwise download Telegram files into
  - Telegram Desktop: plays Telegram videos on Windows and Linux
  - Tevegram: proprietary Telegram client for Android TV, premium tier
    (`cassian.telegram.ooa.pro` on Google Play)
  - Echogram: proprietary Telegram video player for Android and Android TV
    (`com.liori.echogram` on Google Play)

  Tevegram and Echogram may not have AlternativeTo pages yet; if not, add them only if you want
  to, since they are other people's products.

## Uptodown

Status: notes only. Uptodown hosts APKs and has a developer console:
<https://en.uptodown.com/developers-console> (docs: "How to publish an app on Uptodown" and "How
to claim authorship of an app published on Uptodown" in their support centre).

- Register in the Developers Console, ideally with the address shown on tmplayer.org or GitHub,
  which makes an authorship claim straightforward if they have already mirrored the APK.
- "Add new app", upload `TMPlayer-<version>-universal.apk` (the same signed file as GitHub, so
  users can move between sources without reinstalling).
- Title: `TMPlayer` (no "Telegram" in the title, per Telegram's API terms). Reuse the fastlane
  short and full description and the screenshots in `fastlane/metadata/android/en-US/images`.
- Category: Video, or Multimedia.
- Updates are a manual upload per release unless their console offers a GitHub import; check
  when signing up. Weigh that against the release cadence: one more manual step per release.
- Uptodown shows its own ads on its pages, not in the app. That is fine, but it is one reason to
  keep GitHub, the site and IzzyOnDroid as the recommended sources.

## XDA Forums

Status: draft thread. Post in XDA's Android apps forum (the "Android Apps and Games" area; pick the
Android TV or Google TV sub-forum if one is current). XDA's editor takes BBCode.

**Title:** [APP][8.0+][Android TV, phone, Windows, Linux] TMPlayer: stream your Telegram videos while they download (free, open source)

**Body:**

```
[CENTER][IMG]https://raw.githubusercontent.com/dracu-lah/TMPlayer/v1.22.0/site/icon-512.png[/IMG]
[SIZE=6][B]TMPlayer[/B][/SIZE]
Your Telegram videos on the TV, in your hand and on your computer.[/CENTER]

[B]What it is[/B]
TMPlayer plays the videos in your own Telegram chats and channels. It starts within seconds while the file is still downloading, and seeking re-aims the download instead of waiting for it. One APK for Android TV and phones (Android 8.0 and up), plus Windows and Linux builds.

It is an unofficial client built on TDLib. It is not made by or affiliated with Telegram, and you sign in with your own Telegram account.

[B]Features[/B]
[LIST]
[*]Sign in on the TV by scanning a QR code with Telegram on your phone, or with your number. 2FA works.
[*]Shows media, not messages: pick a chat, see only its playable videos as a poster grid.
[*]MKV, MP4, AVI, TS. Embedded subtitles (PGS and VobSub too) and audio track switching. DTS, TrueHD and E-AC3 in software when the stick cannot.
[*]Episode names (S02E04) understood: previous, next and autoplay of the next episode.
[*]Resume where you stopped, Continue watching, favourite chats.
[*]Caches only the video you are watching; keeps the ones you download.
[*]Remote friendly TV UI, Material 3 phone UI with seek, brightness and volume gestures.
[*]Tested on an 8 GB stick with 1 GB of RAM.
[/LIST]

[B]Privacy[/B]
No ads of its own, no analytics, no server in between. Crash reports are off unless you turn them on. Telegram's own sponsored messages appear in public channels, as Telegram's API terms require.

[B]Screenshots[/B]
[IMG]https://raw.githubusercontent.com/dracu-lah/TMPlayer/v1.22.0/site/screenshots/tv-grid.webp[/IMG]
[IMG]https://raw.githubusercontent.com/dracu-lah/TMPlayer/v1.22.0/site/screenshots/phone-grid.webp[/IMG]

[B]Download[/B]
[LIST]
[*]Android TV and phones: [URL=https://github.com/dracu-lah/TMPlayer/releases/latest]universal APK on GitHub[/URL] (about 27 MB)
[*]Windows 10 and 11: MSI on the same page (per user, unsigned, so SmartScreen asks once)
[*]Linux: AppImage on the same page, or the AUR recipe
[*]All platforms: [URL=https://tmplayer.org/download/]tmplayer.org/download[/URL]
[/LIST]
On a TV: enable network debugging, then [CODE]adb connect <tv-ip>:5555
adb install TMPlayer-1.22.0-universal.apk[/CODE]

[B]Links[/B]
Source (GPL-3.0): [URL]https://github.com/dracu-lah/TMPlayer[/URL]
Website: [URL]https://tmplayer.org[/URL]
News: [URL]https://t.me/tmplayerapp[/URL]
Bugs: [URL]https://github.com/dracu-lah/TMPlayer/issues[/URL]

[B]Version[/B] 1.22.0: smaller downloads; the APK is about half its old size.
```

Check before posting: XDA sometimes strips WebP previews; if the screenshots do not show, attach
the PNGs from `fastlane/metadata/android/en-US/images/` instead.

## F-Droid main repository

Status: **not recommended; IzzyOnDroid is the route.** F-Droid builds every app itself from
source on its build server and signs it with its own key (unless reproducible). For TMPlayer:

- **TDLib** comes as prebuilt `.so` files inside the `tdl-coroutines` artifact. F-Droid's scanner
  rejects prebuilt native code, so TDLib (a large C++ build) would need building from source as a
  srclib, and `tdl-coroutines` rebuilt against it.
- **FFmpeg** audio decoders come prebuilt inside NextLib's `media3ext` artifact; same problem.
- **Telegram API credentials** are injected from CI secrets. F-Droid has no secrets, so the
  `api_id` and `api_hash` would have to be public in the F-Droid metadata (Telegram FOSS does
  this), which you may not want for your own app's id.
- **Sentry** would likely have to be stripped from the F-Droid build or flagged as Tracking.
- **The in-app updater** must be disabled in F-Droid builds.
- **Anti-features:** NonFreeNet at least.
- **Signing:** an F-Droid signed APK cannot update a GitHub one or the other way round, unless
  reproducible builds are set up so F-Droid ships your signature.

Every one of these is solvable, but together it is weeks of work and ongoing upkeep for an audience
that IzzyOnDroid (same F-Droid clients, your own APK, no rebuild) already reaches. Revisit only if
F-Droid users ask in numbers.
