# Amazon Appstore listing pack

Used by **U23** in the working plan. Written 2026-10-06 for CP30. Status: **draft, nothing
submitted.** The user submits it from the Amazon Developer Console; Claude never does.

Amazon's field limits and asset sizes below were checked on 2026-10-06 against
<https://developer.amazon.com/docs/app-submission/appstore-details.html>. Anything marked
**(unverified)** could not be confirmed from Amazon's own pages and should be checked in the console.

## Read this first: the in-app updater

The release APK carries the in-app updater (`REQUEST_INSTALL_PACKAGES`, downloads the next APK from
GitHub and installs it). Google Play forbids self updates outside the store outright. Amazon's policy
pages do not say it in so many words **(unverified)**, but an app that installs its own updates
from somewhere other than the Appstore is a likely rejection, and on an Appstore install it would
also fight Amazon's own updates (different signature after Amazon re-signs, see below).

Recommendation: submit a build without the updater. The plan (CP34) makes it an `amazon` variant
on the F-Droid switch (`-PtmFdroid=true`: no update check, no `REQUEST_INSTALL_PACKAGES`) and
without Cast, since Fire TV has no Google services. Until it exists, the GitHub `universal.apk` is
the only candidate and the submission should wait. This is a decision for the user.

## Text

**App title:** `TMPlayer`

No "Telegram" in the title (Telegram API terms 2.3). Amazon gives no maximum for the title.

**Short description** (2,000 bytes max; Fire TV shows only the first 200 characters, so the first
sentence carries it):

> Play the videos in your own Telegram chats and channels on Fire TV, streaming while they download. Unofficial app, not affiliated with Telegram; needs your own Telegram account.

(176 characters.)

**Long description** (4,000 characters max; shown on the website and on Fire tablets, not on
Fire TV):

> TMPlayer plays the videos in your own Telegram chats and channels on your TV. It starts within seconds while the file is still downloading, and seeking re-aims the download instead of waiting for it.
>
> Sign in by scanning a QR code with Telegram on your phone, or with your phone number. Two-step verification works. Pick a chat and only its playable videos appear, as a poster grid, with what you were watching first.
>
> What it plays: MKV, MP4, AVI and TS, with embedded subtitles (including PGS and VobSub) and switchable audio tracks. DTS, TrueHD and E-AC3 are decoded in software when the device cannot.
>
> Series: every season and episode in order, with season packs and episode ranges understood, so the next episode is one press away and can play on its own. Anime gets its own episode titles and pictures. Skip intro, recap and credits buttons use the chapters inside the file first, then community timing databases. TMPlayer remembers where you stopped and keeps a Continue watching row.
>
> Easy to start: after you sign in, one question asks which chats hold your videos. Everything else is chosen for the device. While streaming you can choose Save space (keeps only the part you are watching, good for a stick with little room) or Keep video. The Downloads tab shows what is running, paused or saved, and Free up space gives room back without touching Telegram. Offline, Home puts what is on the device first.
>
> Built for the remote: the whole app works with the D-pad, the Menu button opens the player menu, and rewind and fast forward work as you expect. Small badges show 4K, 1080p, HDR, Dolby Vision, Atmos or 5.1 where the file says so. If the picture stops while the sound goes on, TMPlayer tries another decoder or player by itself. Thumbnails while you scrub come from the part of the video already downloaded, and are switched off on devices with 1 GB of memory to keep playback smooth.
>
> Sync across my devices: optional, off until you say yes. Where you stopped, what you watched, Watch later, favourites and bookmarks are kept in one small file in your own Saved Messages, so a TV, phone and computer agree.
>
> Private by design: TMPlayer talks directly to Telegram from your device. There is no TMPlayer account and no server in between. No ads of its own and no analytics. Crash reports are sent only if you agree. Optional lookups for posters, subtitles and skip times send only a title or an id, never your videos or anything from Telegram.
>
> Free and open source under the GPL-3.0 licence: https://github.com/dracu-lah/TMPlayer
>
> TMPlayer is an unofficial app. It is not made by, affiliated with or endorsed by Telegram. It uses the Telegram API through TDLib and needs your own Telegram account. As Telegram's API terms require, Telegram's own sponsored messages can appear in large public channels; TMPlayer adds no ads of its own.

**Product feature bullets** (three to five, one per line):

- Starts playing within seconds while the video is still downloading
- Sign in by scanning a QR code with Telegram on your phone
- MKV, MP4, AVI and TS with embedded subtitles and multiple audio tracks
- Recognises episodes, plays the next one and offers skip intro; remembers where you stopped
- Save space or Keep video, a Downloads tab, and a Home that works offline

**Keywords** (comma separated):

`video player, media player, telegram videos, streaming, mkv player, subtitles, tv player, fire tv player, series, episodes, open source`

Do not use "Telegram" as a standalone keyword that suggests an official app; "telegram videos" says
what it plays.

**Category:** Video (Movies and TV, or the console's "Video Players" subcategory if offered)
**(unverified: exact category names appear only in the console).**

## Contact and policy

- **Website:** https://tmplayer.org
- **Support email:** hello@tmplayer.org (U04 is still open; use whatever address the user prefers)
- **Privacy policy URL:** https://tmplayer.org/privacy/ (the page in `site/src/pages/privacy/`)
- **Terms (if asked):** https://tmplayer.org/legal/

## Pricing and availability

- Free, all marketplaces the user wants. No in-app purchases, no subscriptions.
- **Amazon DRM: No.** The app is GPL-3.0; DRM wrapping is pointless and would complicate
  verification of the file.
- **Amazon re-signing:** Amazon signs apps with an Amazon key unless the developer opts to keep
  their own signature **(unverified for the current console; check the "App signing" step)**. An
  Appstore install and a GitHub install will then have different signatures, so a user cannot move
  from one to the other without uninstalling (and signing in to Telegram again). Say so on the
  site's Fire TV page when the listing goes live.

## Device targeting

- **minSdk 23** (Android 6.0), targetSdk 35 (`app/build.gradle.kts`). On Amazon devices that is
  **Fire OS 6 and newer** (Fire OS 6 is Android 7.1, Fire OS 7 is Android 9, Fire OS 8 is Android
  11). Fire OS 5 (Android 5.1) is below the minimum, so the console should exclude those devices
  by itself.
- **Fire TV:** every Fire TV Stick, Stick Lite, Stick 4K, 4K Max, Fire TV Cube, Fire TV Edition
  and Omni television on Fire OS 6, 7 or 8. Not Vega OS devices: they do not run Android apps.
- **No phones.** Amazon closed the Appstore on other makers' Android phones on 2025-08-20; only
  Amazon's own devices remain (checked 2026-10-19,
  <https://developer.amazon.com/docs/app-submission/release-notes.html>).
- **Vega OS** (Fire TV Stick 4K Select and later) takes only VPKG apps built for Vega, and allows
  no sideloading at all, so neither the Appstore listing nor the APK reaches those sticks. Supporting
  them would mean a separate app; the site's Fire TV page should say which sticks work.
- **Fire tablets:** the phone layout runs on them (portrait and landscape). Untested on a real
  Fire tablet; target them only if the user wants tablet users at all.
- **1 GB devices** (Fire TV Stick Lite, Stick 3rd gen, older Sticks): supported, with trickplay
  thumbnails switched off automatically.

## What the app does differently on Fire OS (CP30)

Useful for the "testing instructions" box and for reviewers:

- **Fire OS detection:** the app recognises Fire OS by the `amazon.hardware.fire_tv` system feature
  (and Amazon as the manufacturer), and adjusts the points below.
- **Remote:** a short press of Menu (three lines) opens the player menu, a long press still offers
  another app; holding rewind or fast forward scans in growing steps, three and then six times
  the single jump.
- **Voice search:** on Fire TV the microphone button belongs to Alexa, and no system speech
  recogniser is offered to apps, so TMPlayer never calls one there. Its own mic button opens the
  Fire TV keyboard on the search field instead, which says "Hold the mic button on the remote to
  dictate". On a Fire tablet the mic button opens the keyboard, which has a mic key of its own.
- **No Watch Next row:** Fire TV's home screen does not show the Android TV Watch Next row, so the
  setting is hidden on Fire OS.
- **Trickplay:** thumbnails while scrubbing come only from the already downloaded part of the video,
  behind a setting, and are off on devices with about 1 GB of memory.

**Testing instructions for the reviewer** (paste into the console):

> TMPlayer needs a Telegram account to show anything. Sign in by scanning the QR code on the TV with the Telegram app on a phone (Settings, Devices, Link Desktop Device), or enter a phone number and the code Telegram sends. Then open any chat or channel that contains videos and press OK on a video to play it. The app has no account of its own and no paid features.

Without a reviewer account this may get rejected as "cannot test". A reviewer demo mode is planned
for Google Play (CP35); if Amazon rejects for this reason, the same demo mode answers it.

## Content rating questionnaire

Suggested answers, each with the reason. The console asks per category; answer "None" unless
listed.

| Question | Answer | Why |
| --- | --- | --- |
| Violence, sexual content, profanity, drugs, gambling (in the app's own content) | None | TMPlayer ships no media of its own. |
| User generated content or user to user communication | **Yes** | It shows videos from the user's Telegram chats and channels, which other people post. It does not post, send messages or let users chat from the TV. |
| Unrestricted web or content access | **Yes** | Through Telegram, the user can reach any public channel's videos; the app does not filter them. |
| Shares the user's location | No | No location permission. |
| Collects personal information | No (see note) | No TMPlayer account or server. The Telegram session stays on the device. |
| Advertising | No ads of its own; note Telegram sponsored messages | The API terms (3.3) require Telegram's sponsored messages in large public channels. Mention it in the free text box if there is one. |
| In-app purchases | No | None. |
| Links to external sites | Yes | Sponsor links (GitHub Sponsors, Buy Me a Coffee) shown as QR codes on TV and as links on phones; voluntary, unlock nothing. Also the GitHub and site links in About. |
| Data collection, crash reporting | Opt-in only | Sentry crash reports, off by default, sent only after the user turns them on in Settings or agrees to send one when the app asks after a crash. |

Expect an outcome around "Guidance Suggested" or a teen rating because of user generated content
and unrestricted access; that is normal for messaging and browser style apps **(unverified)**.

## Image assets

Amazon accepts **PNG or JPG only**, not WebP, and the file extension must match the real type. The
repository keeps every screenshot as WebP (AGENTS.md), so make PNG copies at submission time and do
not commit them. A script that builds every file below is kept outside the repo; the commands are:

| Asset | Size | Rule | Source |
| --- | --- | --- | --- |
| Small icon | 114 x 114 | PNG, transparent | `site/public/icon-512.png`, resampled |
| Large icon | 512 x 512 | PNG, transparent | `site/public/icon-512.png` as is |
| Fire TV app icon | 1280 x 720 | PNG, no transparency | the TV banner (`tv_banner.xml`) with the name under it |
| Fire TV background | 1920 x 1080 | JPG or 24-bit PNG, no transparency, required | dark backdrop with the player mark |
| Fire TV screenshots | 1920 x 1080, 3 to 10 | JPG or 24-bit PNG, no transparency | `site/public/screenshots/tv-*.webp` |
| Tablet screenshots | 3 to 10, one of Amazon's listed sizes | PNG or JPG | the same 1920 x 1080 TV shots (1920 x 1080 is on the tablet list; the 1080 x 2400 phone shots are not) |
| Promotional image | 1024 x 500 | optional | not made |
| Video | up to 5 | optional | not made |

Screenshots, pixel-identical to the WebP files, 24-bit, no alpha:

```sh
D=amazon-assets; mkdir -p $D/screenshots
n=1
for s in tv-grid tv-chats tv-settings tv-grid-light tv-chats-light tv-settings-light; do
  magick site/public/screenshots/$s.webp -alpha off -define png:color-type=2 $D/screenshots/$n-$s.png
  n=$((n+1))
done
```

Icons:

```sh
cp site/public/icon-512.png $D/icon-large-512.png
magick site/public/icon-512.png -filter Lanczos -resize 114x114 $D/icon-small-114.png
```

The 1280 x 720 icon and the 1920 x 1080 background are drawn from the `tv_banner.xml` paths and
rendered with headless Chromium (Poppins 600 from `site/public/fonts/` for the name). A version with only
the mark, no name, is beside it in case Amazon's preview crops the text.

Reuse note (R6): no new screenshots were taken for Amazon. The TV shots on the site are the same
Android TV layout Fire TV gets.

## The file to upload

- Today: `TMPlayer-<v>-universal.apk` from the GitHub release (arm64-v8a and armeabi-v7a, release
  signed). Fire TV sticks are 32 bit ARM or 64 bit ARM, both covered.
- Recommended: the `amazon` variant without the updater (see the top of this file), once it exists.
- Every Amazon update is a manual upload per release in the console. Weigh that against the
  release cadence.

## Checklist for U23

1. Decide the updater question above.
2. Amazon Developer account (U21).
3. New app, Android, enter the text above.
4. Upload the APK; Amazon DRM: No; check the signing step.
5. Device support: confirm Fire OS 5 devices are excluded; decide on tablets.
6. Images from the scratch folder, content rating as above, privacy URL.
7. Reviewer testing instructions.
8. Submit (the user, not Claude).
