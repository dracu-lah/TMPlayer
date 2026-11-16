# EchoGram findings (read-only reading of the public Telegram group, 2026-11-15)

Group: "Echogram - TV for Telegram" (566 members), developer role: owner (Lior). Topics: Announcements (38 messages, locked), General Discussion, Internal Testers, Bug Report, Feature Request (~1,087 messages).
Notes are written as screens are read, newest first. Usernames are not recorded.

## Announcements and changelog by version

(newest first; dates are the day the post appeared in the topic; posts are edited later so store status lines change)

### tvOS 1.1.4 (10) - Nov 12
- Fixed Room db error (a reply to the 1.1.0/1.1.1 post). Marked live.

### Echogram 1.1.0 / 1.1.1 - posted Nov 4 (edited 4:00 PM later)
- Stores: Play store Live (1.1.0, 143); Amazon store Live (1.1.0, 143); Apple store tvOS Live (1.1.1, 7); Apple store iOS (iPhone, iPad) not approved yet by Apple.
- Collections: create collections from shows in Discover or long-press any video to add it to a custom collection.
- Cross-Device Sync: sync most settings, collections, playback data and My Channels across devices. Opt in through Settings -> Account -> Cloud sync. Auto syncs when leaving the app and when opening it, no manual sync.
- Home Carousels: reorder the Home screen carousels in Settings -> Appearance -> Home Carousels.
- Cast & DLNA: fixes and compatibility improvements for Android 17.
- Reactions: 6 hearts, 4 claps, 1 thumbs up.

### Developer note - Oct 22 (not a version)
- Asks the 562 members to review the app (less than 150 reviews and ratings), text reviews preferably; says reviews are needed for discoverability and to fund features like cloud sync "which also generate cost on my end".

### Echogram 1.0.7.0 (138) - Sep 18 (edited 10:51 AM later)
- Stores: Play store Live, Amazon store Live, Apple tvOS released (still 1.0 = 1.0.5.7 (132)), iOS still in review.
- Live Streams: watch live streams with Premium on Android, Google Play and Fire devices.
- Cover Photos: show cover photos instead of thumbnails when available.
- 2:3 Portrait Cards: new portrait card layout option.
- Better Audio: improved ExoPlayer FFmpeg support for more audio codecs.
- Loop Improvements: loop now resets when changing videos; configure in Playback settings.
- Reactions 10 thumbs up, 3 fire, 3 claps, 1 heart.

### Developer note - Sep 16
- tvOS version is released (App Store) but Apple review continues, so Premium may not be available before the process is complete; "not fully aligned with the latest version yet so expect some discrepancies" (6 thumbs up).

### Echogram 1.0.5.8 (134) - Sep 15 (edited 2:41 PM later)
- Stores: Play store released, Amazon released, Apple tvOS approved (released next day), iOS in review.
- Automatic storage optimization: storage is optimized every 7 days.
- Fixed ExoPlayer FFmpeg support for better audio codec compatibility.
- Videos can show cover photos instead of thumbnails when available.
- Loop playback resets automatically when changing videos; keep it sticky via Settings -> Playback -> Remember Loop.

### Echogram 1.0.5.7 (132) - Sep 13 (edited 12:36 PM later)
- Stores: Play store released; Amazon store canceled; Apple store rejected, in review Sep. 13 ("Apple's approval process for first release takes time and can be rejected").
- Demo account added to meet Google Play policy.
- New Account Management setting.
- Fixed auto-rotate issues.

### Developer note - Sep 4
- "Echogram is now officially on the Production track!" (Google Play) and asks for rating and review (10 hearts, 7 thumbs up).

### Echogram 1.0.5.6 (131) - Aug 30 (edited 4:18 PM later)
- Stores: Play store released, Amazon released, Apple in review Sep. 12.
- New: Content Controls: lock screens and features behind a PIN code to control what is accessible.
- Better audio support: FFmpeg extension added to ExoPlayer for more audio codecs.
- Fixed: DLNA devices were not being detected.
- Premium plans updated: Premium is cross-platform for everyone. Lifetime plan no longer available; choose Monthly or Annual. Existing Premium users (lifetime and old subscription) not affected.
- Squashed many bugs and various improvements.
- Reactions 9 thumbs up, 4 hearts, 3 fire.

### Developer note - Aug 16
- "Last reminder": pricing changes come with the upcoming version, Lifetime option completely removed, only monthly and yearly subscriptions. Existing lifetime/monthly subscribers not affected. Reactions 14 thumbs up, 3 hearts, 3 crying, 1 thumbs down. Refers to an earlier post "EchoGram's Next Chapter - EchoGram has officially pass..." (unclear, text cut).

### Echogram 1.0.5.4 (126) - Aug 12
- Stores: Play released, Amazon released.
- Fixed an issue where some devices could freeze or crash when trying to play a video; playback should now be stable on affected devices. Thanks two named testers for help ("Hopefully this is behind us").

### Echogram 1.0.5.2 (106) - Aug 7
- Fixes based on user logs. "Lowest memory and CPU footprint so far, ever." More ANRs and crashes squashed.

### Echogram 1.0.5.1 (101) - Aug 5
- "I did everything I could so far, until I can somehow reproduce this. I'll review the logs in the upcoming days and see how to proceed next." (a freeze/crash that the developer could not reproduce; the 1.0.5.4 post later says it was fixed.)

### Echogram 1.0.4 (98) - date not shown (between Aug 1 and Aug 5)
- Crash fixes.

### Echogram 1.0.3 (95) - Aug 1
- Discover: Trakt.tv removed due to an API policy change; Discover now uses TMDB only, nearly identical experience with minor changes to lists and content.
- Player: LibVLC now correctly maintains the current aspect ratio in FIT mode when hardware acceleration is enabled.
- TV: contact the developer and join the Telegram community by scanning a QR code.

### Developer note - Jul 31 (Trakt.tv)
- Trakt.tv removed all dev free API keys without an update, despite promising to keep 1 free key; other developers report the same; "I'll need to release a version to have this fixed" (leads to 1.0.3).

### Echogram 1.0.2 (93) - Jul 27
- Player engine optimized: up to 96% less CPU usage during playback.
- Fixed Search and Channel pagination.
- Discover: separate search format for Movies and Shows.
- Fixed media looping issues.
- Added Kurdish language support.
- Various bug fixes and performance improvements.

### Echogram 1.0.1 (90) - Jul 18
- Refreshed UI and navigation. TV: use left/right on the menu to navigate; press BACK from content to jump to the menu ("similar to Netflix new menu"). Mobile: double-tap Search or Discover to start searching.
- New Profile section and cleaner navigation (clarification: "not a real profile yet but definitely preparation to one").
- More Appearance and Discover settings.
- Better storage cleanup.
- Search fixes and pagination.
- New media menu (long press).
- 4:3 mode is back.
- Customizable Home carousels.

### "EchoGram's Next Chapter" - Jul 7 (announcement, not a version)
- EchoGram passed its first anniversary; next milestone Version 1.0 alongside iOS and Apple TV release.
- Will be renamed and rebranded (new name, refreshed UI, improved navigation). (Result: the app name appears as "Echogram" from here on.)
- Premium plans and pricing change with 1.0; advises buying Lifetime at the old price before the change.
- Early supporters who bought Lifetime keep lifetime access forever.
- Looking ahead: future features that depend on cloud services/infrastructure with operating costs may need a monthly or yearly subscription, even for Lifetime members; only features with recurring costs; all other new features free for Premium (20 hearts). (Cross-device cloud sync in 1.1.0 is the first case.)

### 1.0-rc.final (83) - Jun 22
- Complete player engine rewrite for better stability and performance.
- Discover supports TV show seasons and episodes.
- Refreshed UI across the app, including new 16:9 layouts.
- New storage management.
- Redesigned Settings screen with new options.
- Improved media title extraction.
- Premium stability improvements for Google Play users.
- iOS and tvOS support coming soon.
- Reactions 10 hearts, 5 thumbs up, 2 claps.

### Trakt.tv outage and sale - Feb 6 to May 12 (developer notes)
- Feb 6: "Trakt.tv API is currently down. Discover lists won't load until Trakt.tv issue is fixed. If you see some lists as expected, that's because the data is cached on your device." Later: still down, Most Recent list works, verified down in other apps using the API; later "Resolved". (Same provider whose free keys were removed Jul 31.)
- May 12: "Sale Alert": 35% off the Lifetime premium plan until June 21 (6 thumbs up).

### 1.0-rc.7.1 - Feb 3 (Play Store and FireOS released)
- Bug fixes and optimizations.

### 1.0-rc.7 - Feb 2 (Play Store and FireOS released)
- Discover: explore movies and TV shows powered by Trakt.tv + TMDB with curated lists (Trending, Recent, Anticipated, Popular), rich details, posters, metadata.
- "Found something you like? Instantly search your channels to see if it's already waiting for you" (search channels from a Discover title). Developer calls it a step closer to "a full streaming experience - not just watching, but discovering".

### 1.0-rc.6.1 - Jan 11
- Fixed ExoPlayer crashing about 1 minute before the video ends (reported by one user).

### 1.0-rc.6 - Jan 9 (Play Store and FireOS)
- Brand-new VLC player engine.
- Search within Topics.
- New player option "Ask every time" (choose player at each start).
- Many small improvements: no dimming on near-end buttons, duration shown as HH:MM:SS, more playback speed options (below 1x), general cleanup, optimizations and bug fixes.

### 1.0-rc.5 (Android, FireOS) - Nov 13, 2025
- LibVLC player more stable than ever.
- Playback Settings: new options for playback speed and screen size.
- General optimizations and bug fixes.

### 1.0-rc.4.2 (Android, FireOS) - Oct 28, 2025 (Play Store released, FireOS waiting for approval)
- Another fix for LibVLC playback stopping on some devices.
- Playlists now work properly in Search (if enabled).
- Gesture settings are separated for easier control.
- Improved Search click behavior on TV.
- Fixed player controls on devices with bottom navigation bars.
- Another attempt to fix FireOS subscription plan not loading.
- Developer follow-up: "Regarding VLC, it is still not fixed. I received your logs from the new version so I know the issue persists... I now found the issue and I'm working to release another fix soon."

### 1.0-rc.4.1 (Android, FireOS) - Oct 24, 2025
- Fixed player controls not working on TV. Jump To should work on both players.
- Added setting to hide gesture controls.

### v1.0-rc.4 (Android, FireOS) - Oct 23, 2025 (Play Store released and halted; FireOS canceled)
- Fixed playback issues: both LibVLC and ExoPlayer now play videos without interruptions.
- Premium gesture controls (touch devices): double tap seeks +-10 s, swipe up/down on the right is volume, on the left is brightness.
- New Storage Management: control cached files from Settings.
- Faster startup: optimized cold start.
- Fixed In-App Purchase issues and other small bugs.

### 1.0-rc.4-internal - Oct 22, 2025 (internal test, Google Play internal test program, max 100 testers; sign up by sending the Play email privately)
- "HOPEFULLY" fixed both LibVLC and ExoPlayer issues: they seemed related to the cleanup mechanism.
- ExoPlayer starts playback faster. Optimized app cold start. Attempt to fix Premium not being recognized. Storage cleanup added to Settings.

### Developer note - Oct 21, 2025
- Plans an internal test version "by this Thursday" to address player issues (ExoPlayer and LibVLC). "Since I'm not able to reproduce some of the issues you guys are reporting, I would appreciate your feedback."

### Developer note - Oct 17, 2025
- 1.0-rc.3 submitted to Amazon store for review. "All flows tested working. There might be some small compatibility issues."

### 1.0-rc.3 - oldest post in the topic (about Oct 2025; date cut)
- "Big update": Telegram Topics support for group chats; audio, image and GIF playback added; fresh new look; faster performance; LibVLC hardware acceleration options in Settings; fixed missing files, channel search and "Continue Watching"; Cast from Home works; "next version will also be on Amazon Fire OS".

### Version timeline (Android first unless noted)
| Version (build) | Date | Headline |
| --- | --- | --- |
| 1.0-rc.3 | ~Oct 2025 | Topics, audio/image/GIF, LibVLC HW accel options |
| 1.0-rc.4-internal | Oct 22, 2025 | cleanup-mechanism fix, internal test |
| 1.0-rc.4 | Oct 23, 2025 | playback fixes, gestures, storage mgmt |
| 1.0-rc.4.1 / 4.2 | Oct 24 / 28, 2025 | TV controls, hide gestures; LibVLC fix |
| 1.0-rc.5 | Nov 13, 2025 | LibVLC stability, playback speed and screen size |
| 1.0-rc.6 / 6.1 | Jan 9 / 11 | new VLC engine, Ask every time; ExoPlayer end crash |
| 1.0-rc.7 / 7.1 | Feb 2 / 3 | Discover (Trakt + TMDB), fixes |
| 1.0-rc.final (83) | Jun 22 | player engine rewrite, seasons, storage mgmt |
| 1.0.1 (90) | Jul 18 | new UI and navigation |
| 1.0.2 (93) | Jul 27 | -96% CPU, Kurdish |
| 1.0.3 (95) | Aug 1 | Trakt removed, TMDB only |
| 1.0.4 (98) | ~Aug 2-4 | crash fixes |
| 1.0.5.1 (101) / 1.0.5.2 (106) | Aug 5 / 7 | crash hunt, lower CPU/memory |
| 1.0.5.4 (126) | Aug 12 | freeze/crash on play fixed |
| 1.0.5.6 (131) | Aug 30 | Content Controls (PIN), FFmpeg audio, DLNA fix, new pricing |
| 1.0.5.7 (132) | Sep 13 | demo account, account management, auto-rotate |
| 1.0.5.8 (134) | Sep 15 | storage optimization every 7 days, loop reset |
| 1.0.7.0 (138) | Sep 18 | live streams, cover photos, 2:3 cards |
| 1.1.0 / 1.1.1 | Nov 4 | Collections, cloud sync, Home carousels, Android 17 cast |
| tvOS 1.1.4 (10) | Nov 12 | Room db error fix |
Platforms: Android (Google Play), Amazon Fire OS, Android TV (same app), tvOS (App Store, first approved Sep 16), iOS (still not approved on Nov 4).
Announcements topic fully read (38 messages, oldest to newest).

## Bugs reported

Bug Report topic: 1,391 messages total, has a pinned "Bug Reports - Please Read Before Posting" rule list (one rule, point 8: "Send diagnostic logs and notify me that you did"; app has Settings -> About -> Send diagnostic logs; logs live in the app private folder). Read newest first (as of Nov 14-15, 2026).

| # | Symptom | Platform / device | Reports | Developer answer |
| --- | --- | --- | --- | --- |
| B1 | Discover trailers do not play on Amazon Fire TV Cube (spinner forever on trailer button) | Fire TV Cube (Amazon Cube) | 1 (Nov 14) | "Try changing the setting on Discover settings" (a trailer source setting); user: works with YouTube |
| B2 | Collections disappear when "delete cache" is pressed even with cloud sync off | Android, v1.1.x | 1 (Nov 12-14) | Cloud sync keeps data on a server; clearing storage in Settings -> Storage warns it also deletes from the cloud unless unchecked; "file a proper bug report" |
| B3 | Episodes/chapters not loading from a collection; scanning fails to detect some videos present in the channel (MP4 and MKV), even in search outside collections; re-adding collection and refresh do not help; "I've checked the files they still exist" | Android, 1.1.x | 2 users (Nov 11-12) | "Already exists for premium users, cloud sync in settings -> account" (for cache-loss part); "reproduce and share the diagnostic log". Reporter later: re-scanning the season again made the videos play |
| B4 | Continue-watching across devices: after watching on phone then on TV, TV does not auto-advance to the next episode; only auto-advances on the device where it was first watched | Android TV + phone, cloud sync | 1 (Nov 12) | no answer seen |
| B5 | Opening a channel (PlayZone) shows "Unexpected message type: la7" instead of videos; user asks if it is because the channel's message was sent in Telegram "Article" mode | Android phone | 1 (Nov 12) | "I don't know. Open it and then share diagnostic logs and I'll check" |
| B6 | tvOS 1.1.1 (7): home and channel pages stay on a loading spinner; opening a channel shows "Unable to open database '.../Documents/echogram.db'. Was a proper path / name used in Room's database builder?" (login and folder list work) | Apple TV (tvOS) | 1 reporter, several posts (Nov 11-12) | "I can't seem to reproduce this but I am aware of this issue"; "tvOS handles persistency differently than iOS"; "7 was approved today by Apple"; uploaded 1.1.2 (8) to TestFlight and invited the reporter; fixed in tvOS 1.1.4 (10) "Fixed Room db error" (Nov 12 announcement) |
| B7 | tvOS: Settings -> About -> Send diagnostic logs shows "cannot send, please try again" | Apple TV 1.1.1 (7) | 1 | Dev: "I don't see the logs yet.. did you click it and saw it says it was sent?" -> TestFlight 1.1.2 (8); user could not send logs but emailed Apple ID for test track |
| B8 | tvOS 1.1.0 (6): app opens and closes by itself after a second, even after clearing cache, deleting and reinstalling; crash log (.ips, "EchoGramTV-2026-...") analysed by the reporter's AI as a Room/SQLite initialisation failure before the Telegram login screen (the first log sent was from build 4, dev asked for one from build 6 / App Store version: "Settings -> Privacy -> Analytics -> Analytics Data on the Apple TV"). Reporter: Apple TV HD A1842 (32 GB), tvOS 26.6.1 | Apple TV HD | 1 reporter, 2 others "this is what happens to me" (Nov 5-9) | "Thank you! Checking that"; later fixed by the Room db error fix in tvOS 1.1.4 (10) (probably the same cause, unclear) |
| B9 | Cast: device appears for casting (Chromecast) but not over DLNA; other casting apps do DLNA properly; asks quality difference of cast vs DLNA vs Android TV build | Android phone | 1 (Nov 4-5) | no answer seen |
| B10 | Search/refresh in Collections: seasons uploaded by different groups with different naming get all refreshed together; user wants a "refresh search" per season and for the search keyword to be limited to that season | Android | 1 (Nov 10) | "There is one... Long press a season chip" (a per-season refresh already exists as a long-press) |

## Feature requests (seen while reading the Bug Report topic)
- Android TV: minimise the interface: remove the search bar and filter chips, replace with icons at the top; the same user reports "still some bugs with the search function and the keyboard on Android TV" (Nov 9-10, 1 user, developer reacted with a heart, fire reactions).
- Collections: per-season "refresh search", search keyword restricted to a season, files not updated unless refresh is pressed (1 user, Nov 10).
- Collections not lost when clearing the cache (2 users; developer: cloud sync, premium).
| B11 | Search does not find videos posted as a Telegram album (media group): the title sits in the caption of the first item (a cover image), so searching the title in a channel finds nothing; it works when the title is on the video itself. A second case: a video named "Tunnel (2016)" can be found in Telegram but not with Echogram search (only some of 3 test copies "Tunnel 2016 0/1/2" appear). Another user: "We already got that one year ago" (known, old) | Android | 3 users (Nov 4, same thread) | "Echogram uses Telegram search API. The app doesn't filter results it receives from that API"; asked for a public test channel to see what data arrives; "it won't help to discuss further" without a shareable example. Workaround given by a user: put the title in the video caption, not the cover image's |
| B12 | Link sharing is one way: Echogram can share a link opening the video in the Telegram app, but a Telegram link cannot be opened in Echogram ("the link should be on echogram as it is") | Android | 1 (Nov 4) | no direct answer (deep links not discussed) |

Notes from screenshots: the Android player/video details sheet lists File Size, Mime Type, File Name, Resolution, Bitrate, Duration and Datacenter (e.g. "DC2, Amsterdam, NL"); Echogram shows cast devices ("Cast Devices" and "Nearby Devices (DLNA)" with "No DLNA devices found") in a "Connect to device" dialog on Home; Home has "Weiter ansehen" (Continue Watching), Livestreams row, "Kuerzlich hinzugefuegt" (Recently added) row; bottom navigation Home/Search/Discover/Profile.
| B13 | Telegram "Saved Messages" section: thousands of videos/photos saved there with another app, Echogram shows only a few; most MP4 cannot be read or seen | Android | 1 (Oct 23) | no developer answer seen (a user asked which OS); another user said a channel can be added as shortcut, answer "Too much work, I said 1000s" |
| B14 | DLNA: an old LG TV not found for casting over DLNA (Chromecast of a Philips TV was listed, DLNA list empty) | Android (Xiaomi 14, Android 16, app 1.0.7.0 (138) in the same thread as B11) | 1 (Oct 30-Nov 1) | no answer seen |
| B15 | Samsung Galaxy Z Fold 6: app crashes every time a video plays with the VLC engine (also with hardware acceleration off) | Samsung Z Fold 6 | 1 (Oct 20) | a community member: enable "Force stereo audio" (setting next to hardware acceleration): reporter "Advice seems unrelated, but it has solved the problem" |
| B16 | Channels that post a cover image + video as an album: the video entry has no poster; posters only show under the Image/GIF filter; 16:9 covers problem ("the first ... "); user asks to merge into one image+video item; wording "fatal error, please give an answer worthy of the gravity" | Android | 2 users (Oct 14-19, repeated "I am reiterating this problem") | developer reply not read (next message cut) |
| B17 | A tester posted an internal-test (rc.4 internal build) problem in the wrong topic | Android internal build | 1 | "This is an internal version. You should report next time on the right topic.. I'll check." |
| B18 | Cover art of music/audio files no longer shown (generic blue music-note tiles) in the audio grid; used to work in earlier versions; Telegram shows it. Seen on phone 1.1.0 (140) and NVIDIA Shield (Android TV) | Android phone, NVIDIA Shield | 1 user, several posts (Oct 7-10) | "Report the bug properly please. You didn't even mention the version"; asked how many channels are on the Home channels carousel (about 9) - cause not stated |
| B19 | "Not found language kurdish in echogram" (Kurdish was added in 1.0.2) | Android | 1 | answered by the reminder to report properly (unclear) |
| B20 | Live stream stuttering, has to pause and restart; on mobile data | Android phone | 1 (Oct 3-4) | a community member: "Get yourself better internet... use WiFi"; no developer answer seen |
| B21 | After clearing cache the Home channels carousel behaves oddly (cut text, "I cleared the cache because I wanted to test whether the ..."), tied to B18 thread | Android TV | 1 (Oct 7) | developer asked the channel count (about 9) |

Reading note: the Bug Report topic is chatty; most of the last two weeks (Nov 1-14) is dominated by three threads: tvOS launch crashes (Room database), album-search (title in cover caption), and cover art for audio/video.

## Feature requests

Feature Request topic: 1,100 messages (the group list showed 1,087 unread); oldest message Oct 21, 2025, newest Nov 14, 2026. The topic opens at the first unread (oldest); I scrolled to the bottom and read backwards. Screens below are newest first.

| # | Requested feature | Askers | Date | Developer answer / status | Workaround / notes |
| --- | --- | --- | --- | --- | --- |
| F1 | Collections should search only the channels the user selected, not all the groups/channels the user is in (finds a 720p 1 GB copy from another channel although the user has 1080p 3 GB in own channel; random videos for some episodes) | 3 users (Nov 13-14) | not answered in the screens read | Workaround: forward content into own channel and rename files (a user); another user: "Collections already works perfectly" (Nov 14) |
| F2 | Search could exclude archived chats, like the official Telegram app which searches chats and archived chats separately | 1 (Nov 13) | none seen | |
| F3 | Skip intro (TV): intro database (introdb.app, free API; anime-skip.com for anime) "would totally change the app TV viewing experience" | 1 asker (3 thumbs, 2 hearts, Nov 12); 1 objection (it also skips part of episodes before the intro and after credits) | developer asked for opinion: "It was discussed multiple times in the past. It's on my todo but not a high priority at the moment." | |
| F4 | Mobile player revamp: "very hard to pause and access subtitles (player similar to Netflix and YouTube would be nice)"; subtitle rendering bug when zooming the video | 1 | "ok hopefully soon" (no commitment) | |
| F5 | TV series section kept, but a separate Collection for movies; series search returns versions not in the user's language and misses own uploads (high quality, own language) | 1 (Nov 13) | none seen | |
| F6 | Order of actions when adding to a collection: do Search first, then pick the player, then add to the collection | 1 (Nov 11) | none seen | |
| F7 | Add whole folders (series organised per streaming service/broadcaster) to create a Collection; custom order of channels on Home instead of alphabetical | 1 (Nov 11, screenshots of a TV with Netflix/Prime/Apple TV+/Disney+/Paramount+ tiles) | none seen yet | Note: Home Carousels reorder shipped in 1.1.0 |
| F8 | Search results to show as a grid like Telegram's own media search with "279 of 309 ... Show as List" (shows counter) | 1 (Nov 10) | none seen | |
| F9 | Video player UI inspiration: TiviMate Player and SmartTube; file sizes/metadata are clutter (Hocoboy) vs wanted (Kaior Cesar) | 2 (older, Oct-Nov?) (unclear date) | | |
| F10 | App lock so no one can access the app and see history | 1 (Oct 21, 2025) | "I have it on my plans but not very high priority. It'll eventually be available but can't provide a timeline yet" (later shipped as Content Controls PIN in 1.0.5.6) | |
| F11 | Trakt.tv integration (watch status sync, scrobble) with a long data flow description | 1 (Oct 22, 2025) | Trakt later removed (Jul 31, 2026) and only TMDB kept | |
| F12 | Search by file name (Telegram search only matches message text/caption, not file names) | 1 (Nov 5), same thread as bug B11 | Developer: "I wish that was possible by telegram API. It's not like I'm ignoring that on purpose, it's just not up to me to decide how telegram search works and if it searches for filenames or not." Also: "Trust me, I wish it worked that way. I would be able to utilize it much better for the collection feature as well since a lot of channels include random redundant text on the message containing the media. A filename could be perfect for me to work with." | |
| F13 | Per channel media-card design (16:9 vs 2:3) overriding the general setting, maybe in the 3-dot menu | 1 (Nov 6) | none seen | 2:3 portrait cards shipped in 1.0.7.0 as a global option |
| F14 | Saved Messages style grid ("option to show media like this") with a button to see the media in the main list; search results to show within the whole list rather than only the search results | 1 (Nov 7) | none seen | |
| F15 | Plain statement of product philosophy by the developer (answering a user's thread on content identification): "the app is agnostic to your media files. For the app, your files are just random files. It has no idea what you use it for nor does it need to know. That's borderline illegal if it did. Some developers choose this approach and expose themselves to legal actions. I prefer to respect your privacy." (Nov 5-6; replying to "the best approach is for the app to continue with ... " a user quote) | | Relevant to TMPlayer's public wording (no piracy tool). |
| F16 | Cover art / posters for videos in channels: users want posters (black boxes with only the title are "not practical"); a Telegram bot was the workaround for MP4 (not MKV) and now "even the bots are failing"; another similar app links a FilmAffinity URL to a poster JPG plus the video file (Netflix-like with synopsis) | 3 users (Nov 2-5) | Developer (Nov 2): "No update was released to everyone. Just to internal testers, aside Apple TV which is released now and will get an update together with the release to Android by EOW. With all due respect to other apps, I have other plans and priorities. You are more than welcome to report what you consider as a bug or a feature request with all the information so I can re-consider its priority." and "allowing a client to add thumbnails to a video that exists on a different server? That's not even possible. Please write a feature request and not just an opinion" | Community: rename files and use scripts; Echogram's Discover is "the most polished and well-crafted part - no other app offers anything like it" (praise, 1 user, 3 claps 2 hearts) |
| F17 | A user survey to prioritise features; does not understand the purpose of "collections" when "more relevant things are missing" | 1 (Nov 2) | collections: 1.1.0 feature | |

Praise seen in this topic: "Collections already works perfectly" (Nov 14); "Discover feature is the most polished and well-crafted part - no other app offers anything like it" (Nov 3).
| F18 | Limit search to the selected/favourite channels ("nur ausgewaehlte Kanaele"): search returns all Telegram groups and channels incl. news channels, which ruins the overview; the Search screen has chips Videos / Audio / Bild & GIF / Kanaele and a "Letzte Suchanfragen" (recent searches) list with "Loeschen" | 3+ users: one German user (Oct 25, with 1 thumb + 1 reaction), "I mentioned this same thing before" (Oct 26), a Spanish-speaking user says he explained it to the developer 2-3 times and they ignore it ("extremely important": mark the channels/groups the search should use). Also the same as F1 for collections | no developer answer in the screens; likely planned (unclear) | |
| F19 | Separate "Continue watching" sections for movies and for TV series (a movie split into parts is easier) | 1 (Nov 1) | none seen | |
| F20 | Online subtitle search (alongside AI live translation) | 1 asked "do you have any idea when the online subtitle search ..." (Oct 20-21); a second user: original audio format and searching subtitles online would be great | "Once I'm done with this version, I'll look into what it means to support online subtitles alongside AI live translation. Can't commit to a timeline" (Oct 21) | |

Developer roadmap statement (Oct 22): "I read everything guys. Don't worry. Busy times but the public release is in the works with smart and custom collections, cloud sync and home sort." (6 thumbs up, 2 hugging, 1 fire). That became 1.1.0 (Collections, cloud sync, Home carousel order) on Nov 4.
| F21 | Posters and cast details pulled automatically from online, Tamil-language Discover, auto subtitles including Tamil, group the variants (same video in different qualities) | 1 (Oct 17) | (Discover shipped in rc.7 earlier; later versions) | |
| F22 | Stronger focus highlight for the selected channel on the main (TV) screen: currently a barely visible white box | 1 (Oct 19) | none seen | |
| F23 | Home layout with multiple rows instead of a single alphabetical row when only chosen channels are selected; user to move the whole row up/down flexibly; custom order | 2-3 (Oct 7-16, one reply "it's in the order of your folders in Telegram", the asker "I want to move the entire row up or down") | shipped as Home Carousels reorder in 1.1.0 | |
| F24 | Picture in Picture (PiP) on phone (typed just "Pip", the community asked for full sentences) | 1-2 (Oct 12) | none seen | |
| F25 | Sort videos in a channel (oldest first); default is newest on top; context: lecture 1 to 10, lecture 10 uploaded later | 1 (Oct 11) | none seen | |
| F26 | Export/import settings (especially with custom collections) to move a setup between devices | 1 (Oct 8, 1 thumb + reaction) | cloud sync in 1.1.0 covers settings and collections (Settings -> Account -> Cloud sync, premium) | |
| F27 | TV: arrange the Library tiles yourself (Feed, Saved, Privat, Dateien, IPTV: these are Telegram folders) and add favourites to the feeds | 1 (Oct 6) | a community member: order follows the order of your folders in Telegram, "delete folders, make them new"; developer reply not seen (the reorder became Home Carousels in 1.0.1/1.1.0) | |

Facts from screenshots (useful for comparison):
- Android TV left menu: Meine Kanaele (My channels), Bibliothek (Library), Premium, Einstellungen (Settings). Library tiles map to Telegram chat folders.
- Android phone bottom nav: Home, Search, Discover, Profile. Phone Playback settings list: Video player (ExoPlayer or VLC), Auto-rotate, Gesture controls, Continue watching ("Always continue"), Auto-play next video, Auto-play time (5 seconds), Remember loop, Double back to exit player... (list continues). Developer: "Inside the player. Playback settings button."
- Quality: "Stream is maximum quality that telegram provides. If the video has other qualities, you can select them on playback settings dialog under 'Quality'. Most videos don't have alternative qualities." (Echogram plays Telegram video streams and Telegram alternative qualities.)
| F28 | In-app language selection (or Android per-app language) | 1 (Oct 4) | "Thanks. Language selection is planned." | |
| F29 | Search chips for "Collections" and "Discover" next to movies, series, images & GIF, channels (five different searches already, plus two more on unread and personal) | 1 (Oct 4) | "For search I plan to add collections chip. Not sure about discover" | |
| F30 | Auto-play the previous video in the list (newest to oldest) instead of the more recent one; "re-request" | 1 repeat request (Oct 1-3; thumbs up + a reaction) | no answer in the screens | likely playback setting for auto-play direction |
| F31 | MKV default and forced audio/subtitle tracks should be selected by default; sometimes plays English with subtitles off "seemingly at random"; show the "title" tag next to language when several subtitles share a language | 2 users (Sep 29) | Developer (Sep 30): "I'll check. Looked at it multiple times and I can't reproduce." | |
| F32 | Search should use file names, not only captions (most files never appear in results) | 2-3 users (Sep 28 + Nov 5) | "I've explained this so many times..." (Sep 28); Telegram API limit (Nov 5) | A user renames/captions; one user sends the example by DM |
| F33 | Subtitle task items from a user ("can you please respond", Hocoboy) | 1 (Sep 27) | "Maybe in future versions once I take a look at subtitles tasks I have" | |
| F34 | Cover art through a bot for the system (a user asks to "Add a bot to the system so it includes the cover art") and choose max streaming quality (Echogram streams max quality) | 1 (Oct 4) | answered as the quality note above | |
| F35 | Locking the phone screen while a video plays should pause instead of exiting playback | 1 (Sep 26, 3 reactions) | none seen | |
| F36 | Subtitle styling: black box only as wide as the text (not full-width black border); subtitles like Netflix/YouTube; subtitle position raised (like a sample, thumbs down + 1 reaction) | 1 user (Hocoboy), 2 messages (Sep 24-26) | "I'll check later on" (Sep 24) | |
| F37 | Show video format and codec in the playback error ("This video format is not supported on your device. The codec could not decode the video stream." German UI "Wiedergabefehler") | 1 (Sep 27) | "I'll check" | |
| F38 | (Discussion, not request) ExoPlayer vs LibVLC: a user says LibVLC needs about 1.5 GB RAM for FHD vs 500-600 MB for ExoPlayer; another: "Use exoplayer for HDR, and libvlc only if y..." (Sep 24-25) | 3 users | | Echogram ships two engines, ExoPlayer (with FFmpeg audio extension) and LibVLC |
| F39 | HDR / Dolby Vision: on a 4K video the TV should detect HDR or Dolby Vision and switch display mode (ExoPlayer does, LibVLC does not) | 1 (Sep 25) | a community member: use ExoPlayer for HDR; LibVLC uses software decoding, no HDR | |
| F40 | Poster page generator: user inputs cover art + FilmAffinity link + video link and the app builds a Netflix-like layout with synopsis and play button (cover on main screen), "solution to avoiding extra costs" | 1 (Sep 23) | developer: "I definitely appreciate and open to any ideas. I just want to focus on my plans for a while, which some of them already include some of your recent suggestions anyway." (Sep 25) | |
| F41 | Online subtitle search like VLC | 1 follow up (Sep 24): "Will online subtitle searches become the norm?" | see F20 | |
| F42 | Favourites / watch later: "This is already implemented in EG. Longpress menu" (a user to another) | | already existing: long press menu has Mark as seen, Add to collection, Save video, Choose player, Search | Telegram Saved Messages is used for saving; a user: "forwarded to not found" / "message protected cant be forwarded" errors when the channel disables forwarding; saved empty |
| F43 | Per-folder favourites (Telegram folders are a Telegram function, favourites are not; "no, you wouldn't need your own server for that") | 2 users (Sep 23) | | |

Praise (Sep 23): "90% of my ideas so far have already been implemented by the developer" and "a huge thank you for your great work" (1 user; 4 reactions).
| F44 | "Favorites" category on Home: long-press a video to add it to a Favorites list shown on Home | 1 (Sep 18) | community: "He would need a dedicated server" (disagreed: it is like the Saved folder); workaround: create a Telegram folder named "Favorites" and put it on Echogram's Home screen; "I think the developer is already aware" | Shipped, in effect, as Collections / Save video (1.1.0) |
| F45 | Cover art workaround via a Telegram bot ("CoverXChangerBot", "show more" in bot search): forward a cover and the movie to the bot, it re-posts the movie with the cover as thumbnail; works for MP4 not MKV; "uploads a streaming video, not a video file"; if you share another video the old picture is injected until a new picture is sent | 4-5 users (Sep 17-19) | developer did not comment in these screens; the bot is a community workaround that stopped working by early Nov (see F16) | |

Other competitor names seen in the Feature Request topic: another app is named "FilmiGram" (a user account with that name takes part, Sep 18); "TiviMate Player" and "SmartTube" are named as UI inspiration (Nov); "VLC" for online subtitle search; Netflix/YouTube for subtitle styling and player. TMPlayer itself has not been mentioned in the screens read so far.
| F46 | 2:3 portrait (poster) cards for videos instead of 16:9 | repeated by 3+ users (Sep 16-17); another asks "how did you put the 2:3? I can't find it", "only displays 16:9 in Discover mode" | Developer (Sep 16-17): "Guys I'm not going to continue responding to endlessly repeating questions. I already answered on this and the internal version already has 2:3 cards now... Please use the search before you ask for something that was discussed multiple times." -> shipped in 1.0.7.0 (Sep 18) | Phone Home with 2:3 posters (German UI: Startseite / Suchen / Entdecken / Profil), user: "It already looks so much, much better!" |
| F47 | How to get cover images for videos from private channels (asked by an Italian user) | 1 | answered by the community: use the cover bot (CoverXChangerBot) | |

Developer tone (useful): he answers once, points to the search, asks for "clear examples" and versions in bug reports; he is open to ideas but states his own plans have priority.
| F48 | Option to add an external .srt subtitle from the Telegram cloud while watching | 1 (Sep 16, Hocoboy) | none seen | |
| F49 | Show the source channel/group avatar next to each search result (Search and Discover) and a filter to show only results from the channels/groups chosen for Echogram | 1 (Sep 17); same ask as F1/F18 | none seen | |

Facts: Settings -> Appearance (Spanish UI): Default screen (Home), Home carousels ("Continue Watching, Recently added, My Channels"), Media card design (Square / Wide 16:9, later 2:3). Cover-changer bots are used by users to make "covers" for videos (several are named in Telegram search, e.g. a thumbnail changer bot with ~1,300 monthly users).

### Reading progress note
Feature Request read newest first from Nov 14, 2026 back to Sep 16, 2026 (about 55 screens). Next: jump to Home and read the oldest 20 screens (Oct-Nov 2025... note the topic's first message is dated Oct 21, 2025 in this view; earlier Aug-Sep 2026 posts seen above).

## Developer decisions and rejections (summary)
- Rejected/not possible: search by file name (Telegram search API only matches message text/caption; "not up to me"); adding thumbnails/covers to a video held on another server ("not even possible"); a dedicated poster/FilmAffinity layout generator (not now, "my plans have priority").
- Deferred, no timeline: online subtitle search (VLC style) with AI live translation ("once I'm done with this version... can't commit"), subtitle position/style changes ("I'll check later on"), app lock (later shipped as Content Controls PIN in 1.0.5.6), intro skipping via introdb ("on my todo but not a high priority").
- Planned: in-app language selection; "Collections" chip in Search ("not sure about Discover").
- Shipped after asks: 2:3 cards (1.0.7.0), Home carousel reorder (1.0.1 customizable carousels, 1.1.0 reorder), cloud sync and collections (1.1.0), cover photos instead of thumbnails (1.0.5.8/1.0.7.0), live streams (1.0.7.0), audio codecs via FFmpeg (1.0.5.6), PIN content controls (1.0.5.6), storage cleanup (1.0.1, 1.0.5.8), Kurdish (1.0.2).
- Not reproducible answers: MKV default/forced audio and subtitle tracks ("looked at it multiple times and I can't reproduce", Sep 30); tvOS Room database error (fixed in tvOS 1.1.4 after TestFlight 1.1.2).
- Process: bug reports must have the version and diagnostic logs (Settings -> About -> Send diagnostic logs); repeated questions get no further answer; internal test builds are handled in Internal Testers (Google Play internal test, max 100 testers).

## Praise and complaints
- Praise: Discover "the most polished and well-crafted part, no other app offers anything like it"; "90% of my ideas have been implemented"; "Collections already works perfectly"; "It already looks so much, much better" (2:3 cards); "LibVLC... more stable than ever" in the developer's own words.
- Complaints: cover/poster art missing (blank/blue/black boxes) is the loudest theme (about 8 users, Sep-Nov); search scope (all chats and channels instead of selected channels: 5+ users); caption-only search; tvOS 1.1.0/1.1.1 crash on launch; LibVLC RAM use (1.5 GB for FHD vs 0.5 GB ExoPlayer), no HDR in LibVLC; collections lost on cache clear; Android TV search/keyboard bugs; mobile player hard to pause or reach subtitles; subtitle rendering when zooming; Premium pricing change (lifetime removed Aug 2026).
- Competitors and other apps named: FilmiGram (a user account with that name), TiviMate Player and SmartTube (UI inspiration), VLC (online subtitle search), Netflix and YouTube (subtitle style, player), "another similar app" that links FilmAffinity (unnamed). TMPlayer: not mentioned in any screen read.

## Reading log
- Announcements: read completely (38 posts, about 30 screens, newest to oldest, from tvOS 1.1.4 (10) on Nov 12 back to 1.0-rc.3 about Oct 2025). 24 version posts plus 8 developer notes. Dates are as shown by Telegram; a few are missing a year (the app's first public rc posts are Oct 2025, the rest 2026).
- Bug Report (1,391 messages): about 55 screens, newest first, from Nov 14 back to about Oct 3 (roughly 300 messages). Older bugs (before Oct 2026) not read.
- Feature Request (1,100 messages): about 55 screens, newest first, Nov 14 back to Sep 16 (and the very first screen of the unread marker, Oct 21-22 2025 posts: app lock and Trakt). The older part (roughly Oct 2025 to Aug 2026) and the oldest 20 screens were NOT reached: Telegram Desktop disappeared from sway (process gone, window con_id 46 closed) while I was scrolling to the top, so reading stopped.
- General Discussion: NOT read. Internal Testers: NOT read (skipped by rule). Praise/complaints above come from Bug Report and Feature Request only.
- Side effect to know: the scroll to the top sent about 500 Page Up key presses; if Telegram had already closed they went to the user's Alacritty terminal (focused window, no text typed, only the Prior key). Check the terminal scrollback if the display looks scrolled.
- No screenshots kept (folder emptied). No message was sent, edited, reacted to or marked.
