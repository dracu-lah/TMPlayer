# Competitors, gaps and dead ends

Research of 2026-10-18 for the goal that no other way of playing the videos in your own Telegram
chats does anything better than TMPlayer. Three parts: who else does this and how, what TMPlayer
should add or fix (ranked), and the words people search with. Plan items are named by their
checkpoint in [PLAN.md](PLAN.md). Code references are to the working tree of that day, which had
uncommitted work on the connecting, storage and split piece reports, so a few of the dead ends
below may already be closing.

## The field

| Name | Platforms | Price | Strengths | Weaknesses | Sources |
| --- | --- | --- | --- | --- | --- |
| Telegram (official apps) | Phone, desktop, web; no TV app | Free, Premium for faster downloads | Casts to Chromecast from Android since 2025; picks a lighter quality on a weak line in large channels; hold to speed up | No TV app, no library, no series, no resume across a show; Telegram Web on a TV browser is awkward with a remote; frequent buffering reports | [9to5Google](https://9to5google.com/2025/03/10/telegram-chromecast-support-android-update/), [AlternativeTo news](https://alternativeto.net/news/2024/11/telegram-s-latest-update-enhances-video-quality-speed-controls-and-pip-for-large-channels), [bug tracker](https://bugs.telegram.org/c/60594), [AirDroid guide](https://airdroid.com/screen-mirror/telegram-on-tv) |
| Tevegram | Android TV (APK), Apple TV | Android free; Apple TV plays video only with Premium ($2.99 a month, $5.99 a year, $13.99 once) | Rows for New and Continue watching, folders and forum topics, autoplay next, live streams from channels shown first | Video behind a paywall on Apple TV; Android build last seen in 2024; hardly any reviews | [APKMirror](https://www.apkmirror.com/apk/clapps/tevegram-telegram-for-tv/), [App Store](https://apps.apple.com/app/id6477915879) |
| EchoGram | Android phone and TV | Free with a Premium tier | MKV, subtitles, audio tracks, casting, resume across devices, browse by folder | Closed source; listings disagree on versions; no desktop | [APKCombo](https://apkcombo.com/echogram-player-for-telegram/com.liori.echogram/), [Play](https://play.google.com/store/apps/details?id=com.liori.echogram) |
| TMP, Telegram Media Player (com.tmp.com) | Android TV | Free | Recommended by Spanish TV guides as the "media only" option | Old; plays media only, with no library features | [Xataka Android](https://www.xatakandroid.com/tutoriales/asi-puedes-ver-telegram-todo-contenido-multimedia-tu-tele-android-tv-google-tv) |
| KiloGram | Android TV | Free | TDLib, D-pad layout, PIN lock, a media player | A full client rather than a player; about 10 installs | [APKCombo](https://apkcombo.com/kilogram/xyz.d3nmoh.kilogram/) |
| TeleStremio (Telegram-Stremio) | Android phone as a Stremio add-on, so any Stremio device | Free, open source | Joins split `.mkv.001` pieces with seeking; streams uncompressed ZIPs and says plainly when a compressed one cannot stream; backup to Saved Messages; resumable channel scans; dead link and duplicate checks; Tailscale for away from home | Needs Stremio and a phone kept awake as a server; battery optimisation breaks it | [Releases](https://github.com/weebzone/Telegram-Stremio/releases) |
| Bots and indexes (smart-tv-telegram, CastBot, TGindex) | Server plus a browser, VLC, Kodi, DLNA or Chromecast | Free, self hosted | Send to any receiver on the network | You run a server; CastBot tops out around 4.5 Mb/s; often used for sharing, which TMPlayer must never resemble | [smart-tv-telegram](https://pypi.org/project/smart-tv-telegram/), [CastBot](https://pypi.org/project/castbot/), [TGindex](https://github.com/imner0/TGindex) |
| Telecloud Radio | Android | Free, GPL-3.0 | A chat's audio as a playlist, multiroom output | Audio only | [ecosyste.ms](https://awesome.ecosyste.ms/projects/github.com%2Fcapullo-tech%2Ftelecloud-radio) |
| VLC, MX Player, Just Player, mpv-android, Nova | Android, desktop | Free; MX Player carries ads | Plays anything; Just Player keeps Bluetooth audio in sync and stays small; Nova keeps a poster library and plays over DLNA | MX Player's top complaint is ads during playback; VLC gives little feedback while swiping to seek | [MX Player reviews](https://marlvel.ai/apps/mx-player-video-player), [VLC review](https://karl-voit.at/2024/03/09/vlc-for-android), [Just Player](https://www.alternativeto.net/software/just-video-player/), [Nova](https://alternativeto.net/software/nova-video-player) |
| Kodi, Plex, Jellyfin, Infuse, Nuvio | TV, phone, desktop | Free to paid | The library standard: posters, Up Next, Trakt sync, profiles, a pure black theme, a clock on the TV pause screen | A server or add-ons to set up; Plex is called bloated | [Infuse forum](https://community.firecore.com/t/advantages-of-infuse-over-plex/17485?page=2), [Nuvio settings](https://nuvio-wiki.duckdns.org/settings/) |

What this says: nobody else is free, open source and on TV, phone and desktop at once, and nobody
else has TMPlayer's series pages, skip intro, Low storage mode or course progress. The gaps are in
reaching Telegram (casting, proxies, links, quality copies), in files that arrive in odd shapes
(pieces, archives, live streams, audio), and in the few places where the app waits without saying
why.

## Ranked: what to add or fix

Size is S (a day or less), M (a few days) or L (a week or more). Platform: P phone, T TV, D desktop.

1. **Say why a video is not starting.** When a video has been opening for about 10 s and Telegram
   has sent nothing, the sheet still says only "Starting" (`renderLoaderStatus` shows the speed only
   above `MIN_MEANINGFUL_SPEED`). After the delay say that Telegram has not started sending it, and
   offer Try again, Download first and Back. Matches the silent "Connecting" reports and Telegram's
   own buffering threads ([bug tracker](https://bugs.telegram.org/c/60594)). S, P T D. Not in plan
   (CP83 covers only the Premium speed note).
2. **Android sign in that cannot reach Telegram gets the desktop's answer.** On Android a stalled
   connect shows only Send logs; the desktop shows the reasons, Retry and Send logs. Add the same,
   plus a Proxy button once CP77 lands. S, P T. Partly in plan (CP77), the parity part is not.
3. **"Can't reach Telegram" after sign in while the network is up.** The banner only appears after
   the device itself went offline, so a network that blocks Telegram leaves every screen loading
   until the 15 s slow notice. Show a banner with Retry and Proxy when connected stays false for
   about 20 s with the network up. S, P T D. Not in plan.
4. **Proxies before sign in (CP77).** The only way through for a TV in a country that blocks
   Telegram, and the official apps all have it. M, P T D. In plan.
5. **Cast to Chromecast and Google TV (CP73).** Telegram's Android app has cast since March 2025
   and EchoGram lists it, so a phone user expects the button. L, P D. In plan.
6. **Telegram's own lighter copies (CP80).** Telegram picks 360p or 720p on a weak line since late
   2024; TMPlayer always streams the original. Pair it with item 1 as a "Play a lighter copy"
   action. M, P T D. In plan.
7. **Split pieces as one video (CP75).** TeleStremio already joins `.mkv.001` pieces with seeking.
   M, P T D. In plan.
8. **Stream uncompressed ZIPs, and say so for the rest.** TeleStremio streams STORE mode ZIPs and
   ZIP64, and tells you when a compressed one cannot stream. TMPlayer drops every archive silently
   (`SplitPieces.kt:313`). At minimum list them greyed with "Compressed, cannot be played". M, P T
   D. Not in plan.
9. **Empty states that point somewhere.** "Nothing matches" in a chat or the chat list offers no
   next step (`BrowseViewModel.kt:1010`, `BrowseScreen.kt:2968`). Offer Search all chats and, with
   CP74, Find @name on Telegram. "No playable videos" should say what the chat does hold (archives,
   photos, comments with videos). S, P T D. Partly in plan (CP74, CP81).
10. **@username, t.me links and invite links (CP74, CP79).** The "chat cannot be found" report.
    M, P T D. In plan.
11. **Resume where you stopped on any device.** EchoGram sells resume across devices; TeleStremio
    keeps its backup in Saved Messages. TMPlayer's progress lives on one device, and a backup is a
    file (CP53). An opt-in sync through the user's own Telegram (one pinned file in Saved Messages)
    keeps the no server promise. M, P T D. Not in plan.
12. **Live streams and video chats from channels.** Tevegram puts a channel's live stream at the
    top of its library. TDLib can play a group call stream. L, P T D. Not in plan.
13. **Audio files as a playlist.** Lectures and talks are often posted as audio; Telecloud Radio
    exists for it. Background audio and the course progress already fit. M, P T D. Not in plan.
14. **Night mode for sound.** VLC, Kodi, Plex and Infuse compress loud scenes for late watching.
    Android can do it in a Media3 audio processor; the desktop's bundled FFmpeg lacks dynaudnorm
    (PLAN, CP07b), so it needs `acompressor` or a rebuilt filter set. M, P T D. Not in plan.
15. **Trakt sync.** Nuvio and Kodi users expect it; the plan has AniList and Simkl (CP62, waiting
    on keys) and skipped Trakt. M, P T D. Partly in plan.
16. **Helpful notifications (CP76)**, new episodes first. M, P D. In plan.
17. **Several accounts (CP84)** and sessions (CP83). L, P T D. In plan.
18. **A pure black theme.** Nuvio offers an AMOLED black; OLED TVs and phones are the audience. S,
    P T D. Not in plan.
19. **A clock on the TV pause screen.** Nuvio and Kodi show one. The "ends at" readout was removed
    on purpose in 1.16.0, so this conflicts with an earlier choice: ask before adding. S, T. Not in
    plan.
20. **Store listing that mentions phones.** The short description says "for Android TV and desktop"
    (`fastlane/metadata/android/en-US/short_description.txt`) and the full description has four
    bullets out of a possible 4000 characters. S. Not in plan.
21. **A phone page on the site.** There are pages for Android TV, Fire TV and the desktop but none
    for an Android phone, and the home title says "TV and PC". S. Not in plan.
22. **Troubleshooting guides** for the reports themselves: a video that will not start or keeps
    buffering, a video posted in pieces (after CP75), Telegram blocked on this network (after
    CP77), "phone storage full". S each. Not in plan.

## Dead ends found in the code

- `app/src/main/java/com/tmplayer/player/PlayerActivity.kt:5522` to `:5529`: the opening sheet
  adds the speed only when bytes flow; at zero it stays "Starting" for as long as Telegram stays
  quiet, with no button but Back (`showStatus` hides Retry and the failure actions at `:5476`).
  Item 1.
- `app/src/main/java/com/tmplayer/MainActivity.kt:1115` to `:1116`: a stalled sign in on Android
  adds only Send logs, while `desktop/src/main/kotlin/com/tmplayer/desktop/ui/SignIn.kt:125` to
  `:138` adds the reasons (`signin.unreachable`) and Retry (`Td.retryConnecting`, which nothing on
  Android calls). Item 2.
- `app/src/main/java/com/tmplayer/MainActivity.kt:705` to `:716` and
  `desktop/src/main/kotlin/com/tmplayer/desktop/ui/ConnectionBanner.kt:46` to `:58`: the Offline
  and Reconnecting notices need the device to have been offline first. Online but Telegram
  unreachable shows nothing. `core/src/commonMain/kotlin/com/tmplayer/data/Td.kt:349` folds
  Connecting, ConnectingToProxy and WaitingForNetwork into one `false`, so the app cannot yet tell
  a blocked network from a slow one. Item 3.
- `ui/src/shared/kotlin/com/tmplayer/ui/browse/BrowseViewModel.kt:1010` to `:1016`: no match in a
  chat, a topic or the whole list, and "No playable videos", carry no action, unlike the size
  limit case two lines up, which offers Show them. Item 9.
- `app/src/main/java/com/tmplayer/ui/browse/BrowseScreen.kt:2968`: every tab's empty page
  (Favourites, Watch later, Unread, Archived, Saved) is text only. The text says what to do, but on
  a TV a button that goes to Chats saves a trip through the rail.
- `ui/src/shared/kotlin/com/tmplayer/ui/browse/BrowseViewModel.kt:493`: "No chats yet. Open
  Telegram on your phone, then come back." has no Refresh, and a new sign in on a large account can
  meet it while TDLib is still loading the list.
- `app/src/main/java/com/tmplayer/ui/downloads/DownloadsScreen.kt:891`: an empty Downloads tab
  names the folder but offers no way in (Download a season, or Browse).
- `core/src/commonMain/kotlin/com/tmplayer/data/SplitPieces.kt:313` to `:319`: archives and their
  volumes are recognised and then dropped, so a chat of ZIPs reads as "No playable videos". Item 8.
- Known and still open (PLAN, "Open after 1.26.0"): the desktop neither exits nor answers SIGTERM
  when startup fails.

What is already good and should be the pattern everywhere: `SlowAnswerNotice` (a line and Retry
after 15 s, nothing without a button), size limit empties that offer to lift the limit, Keep
looking when the walk back stopped early, the low storage sheet that says how much is short and
what TMPlayer itself holds, and skeletons that keep their shape. The fixes above are those same
patterns carried to the places that missed them.

## Keywords

Rules kept: TMPlayer plays the videos already in your own Telegram chats; public copy says video;
no titles, nothing about getting content. Where a phrase people search would break those rules
(searches for free channels of shows are common), TMPlayer does not chase it.

**What people search** (from guides, store titles and threads above): "Telegram for Android TV",
"Telegram on TV", "Telegram TV app", "watch Telegram videos on TV", "Telegram on Google TV",
"Telegram on Fire TV Stick", "Telegram video player", "Telegram media player", "Telegram video
not playing", "Telegram video buffering", "cast Telegram to TV", "Telegram without phone on TV".
Competitors rank with "Telegram for TV" (Tevegram), "Video Player for your Telegram Content"
(EchoGram) and "Telegram Media Player" (com.tmp.com).

**Site titles and descriptions**
- Home title: "TMPlayer: Telegram video player for TV, phone and PC" in place of "for TV and PC".
- A phone page, `/android/`: "Telegram video player for Android phones", description with
  "stream Telegram videos without downloading them first", "MKV and subtitles", "picture in
  picture", "background audio".
- Android TV page: add "Google TV" and box names people search with ("Chromecast with Google TV",
  "NVIDIA Shield", "Mi Box", "TV box", "projector") in the body and FAQ, not the title.
- Features page: "MKV player for Telegram", "Telegram video subtitles", "skip intro",
  "resume Telegram videos where you stopped".
- New guide pages, each a question people ask: "Telegram video not playing or stuck loading",
  "Play a Telegram video that was posted in parts" (after CP75), "Telegram blocked: connect through
  a proxy" (after CP77), "Watch Telegram course and lecture videos on a TV" (course progress is a
  real, harmless audience), "Watch your Saved Messages videos on a TV".
- After CP73: "Cast Telegram videos to a Chromecast" into the casting guide's title.
- FAQ: "Is there a free Telegram TV app?" and "Does TMPlayer need Telegram Premium?" (Tevegram
  charges for video playback on Apple TV; TMPlayer is free).

**Store listing** (fastlane, and the Amazon listing for U23)
- Title: keep "TMPlayer"; where a store allows a subtitle, "Video player for your Telegram chats".
- Short description (80 characters): "Play the videos in your Telegram chats on Android TV, phone
  and PC." (66).
- Full description: grow it to about 2000 characters with plain sentences on Android TV and
  Google TV, Fire TV, phones, MKV and subtitles, resume and series pages, skip intro, downloads
  and Low storage mode, QR sign in, free and open source, unofficial.
- Amazon keyword field: telegram tv, telegram video player, telegram media player, fire tv
  telegram, mkv player, subtitles, open source.
- Words to keep out everywhere: film, movie, watch any, channel lists, uploader, free content.

## Addendum 2026-11-13: GitHub, Codeberg and GitLab sweep

Searched GitHub with `gh search repos`, and Codeberg and GitLab through web search. Codeberg and GitLab
hold no Telegram video player (only a Telegram X FOSS fork on Codeberg); everything relevant is on GitHub.
All of these are young (2026) and mostly under 10 stars, except Telegram-Stremio (682).

| Project | What it is | What it has that TMPlayer lacked |
| --- | --- | --- |
| [weebzone/Telegram-Stremio](https://github.com/weebzone/Telegram-Stremio) (GPL-3.0) | Self-hosted FastAPI and MongoDB server, plus the TeleStremio app, for Stremio and Nuvio | Plays `.zip.001` split archives; subtitle filename rules; season-pack files; stamps the IMDb/TMDB link into captions so a re-forward matches; Skip channel with a "what to fix" note; per-user quality sort |
| [subinps/TelePlay](https://github.com/subinps/TelePlay) (MIT) | Server plus web, Compose TV app | Multi-bot parallel downloads; signed time-limited share links (TMPlayer must not copy these) |
| [titlog/telegram-tizen](https://github.com/titlog/telegram-tizen) (MIT) | Samsung Tizen TV player, no server, QR sign in | A Samsung TV client |
| [seep-labs/cablegram-apps](https://github.com/seep-labs/cablegram-apps) (GPL-3.0) | Phone as library and remote, apps for Android, webOS, Tizen | Profiles with PINs, "Check this TV" diagnostic, TMDB caption tags, subtitle nudge |
| [risingsunhq/telesera](https://github.com/risingsunhq/telesera), [sostk/webos-telegram](https://github.com/sostk/webos-telegram), [arrietaoadrian/telegram-webos-3.x](https://github.com/arrietaoadrian/telegram-webos-3.x), [ankush29922/TeleFlix_LGTV](https://github.com/ankush29922/TeleFlix_LGTV) | LG webOS clients (rooted engine on the TV, or a Node or Windows server) | Pin to Home on colour keys, jump to page by number keys; webOS 3.x via an ES5 app |
| [RoxyBasicNeedBot/Krypton-Player](https://github.com/RoxyBasicNeedBot/Krypton-Player), [MDaV05/cadence](https://github.com/MDaV05/cadence), ARVIO | Players with a Telegram source | Trivia overlay, multi-source library |

Built from this sweep: `ZipArchive` (core), the directory reader for stored videos inside a ZIP, split
or not. Still to wire: the player slice source (Android and desktop), listing archives as playable,
and the "Compressed, cannot be played" note.
