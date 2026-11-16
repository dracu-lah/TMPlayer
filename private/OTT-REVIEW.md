# How TMPlayer compares with the big streaming apps and players

Research of 2026-10-19. It holds TMPlayer next to the streaming apps (Netflix, Prime Video, Disney+,
the Apple TV app, YouTube on TV, HBO Max, JioHotstar, Crunchyroll), the media servers and players
(Plex, Jellyfin and Findroid, Emby, Infuse, Kodi skins, VLC, Stremio, Nuvio) and the platform
guidance (Android TV and Google TV, Apple's tvOS guidelines). It looks at each surface on Android TV,
the Android phone and the desktop separately.

Only gaps that fit a player for the videos already in your own Telegram chats are listed. Anything
that needs a catalogue is left out: studio trailers, cast databases, paid tiers, recommendations
across a service, rent or buy, live channels. TMPlayer today means `main` at `ef057f3` plus the plan's
ticked items in the working plan (up to CP92) and the code read for this review.

**In trial, not re-planned here:** a separate experiment is building a TV top bar in the style of
Netflix's 2025 TV home, Back opening that menu, and a focused row card that grows into an info tile.
The tables name it where it applies.

**Value** is how much a viewer would notice: High, Medium, Low. **Cost** is S (a day or less), M (a
few days), L (a week or more).

## Android TV

| Area | What the leaders do | TMPlayer today | Gap | Value | Cost |
| --- | --- | --- | --- | --- | --- |
| Home layout and hero | Netflix's 2025 TV home moved its shortcuts (Search, My List, categories) to an always visible top bar and made the cards bigger; Prime Video's 2024 redesign did the same with a top bar and a hero rotator that plays video on TV devices; the Apple TV app on tvOS 26 shows portrait posters | Side rail, Home with a featured carousel (Play, Details, segment bars), then rows; clock and avatar at the top | Top bar and Back to the menu: **in trial**. A muted moving hero would cost a Telegram download per focused slide on a 1 GB stick, so it is not proposed | Low | M |
| Rows and cards, focus and scale | Compose for TV cards grow to 1.1x on focus with a 3 dp border and an optional glow; Netflix 2025 adds short callouts on cards | Focus scale and border, resolution and New badges, progress bars, portrait cards since 2026-10-15 | Card that grows into an info tile: **in trial**. Nothing else | Low | S |
| Details: seasons and episodes | Plex, Jellyfin, Emby, Infuse and Kodi let you mark a whole season or show watched or unwatched from its page | Series page with season tabs, episode stills, per episode watched marks, season download, course progress | No "Mark season watched" or "Mark show unwatched"; each episode is marked one by one | Medium | S |
| Continue watching and Up next | Jellyfin and Emby have a Next Up row; Plex and Infuse put the next episode of a show in progress on Home; the launcher row already does this through Watch Next's "next" type | Continue watching drops an episode once it passes 90 per cent; New episodes lists only episodes that arrived since you last watched; the TV launcher's Watch Next already offers the next episode | In app, a finished episode leaves Continue watching and its next episode does not take its place. Add Next up cards to Continue watching (or a row of its own) | High | S |
| Skip intro and credits, autoplay | Plex and Infuse can skip intros on their own (a setting); HBO Max shows Skip for 10 s and plays the next episode after 15 s, with an X to stay for the credits; Plex lets you pick the countdown | Skip intro, recap and credits pills (TheIntroDB, AniSkip, learned intro end per series), Next episode card and countdown, "still watching?" | No "skip automatically" choice: every skip needs OK. Countdown length is fixed | Medium | S |
| Player overlay | YouTube on TV (late 2025) groups the controls under the bar and opens a Description side panel with chapters; Netflix dims the picture and shows the title and synopsis when paused for a while | Decluttered row (subtitles, audio, speed, More, Episodes), paused cue, Playback details; the info panel was removed in 1.16.0 on purpose | After a long pause, nothing says what is playing. A paused card (title, episode, chat, overview from online metadata) after about 10 s. Conflicts with the 1.16.0 removal, so ask first | Medium | S |
| Scrubbing and previews | Plex, Jellyfin (trickplay), Infuse and YouTube show frames while seeking | Trickplay frames on Android since CP30; Telegram storyboards planned in CP80 | None beyond CP80 | Low | S |
| Track and subtitle pickers, stats | Infuse and Plex keep pickers short and show stats on request | Track picker with online subtitles, delays, styles, Playback details | None | Low | S |
| Search | Google TV searches across apps (for partners); Netflix 2025 tests natural language search | Voice through the remote, recent searches, word by word matching, search across all chats, @username and links | Google TV's system search needs partner onboarding; nothing to do now | Low | L |
| Profiles and kids | tvOS 26 can show a profile picker when the TV wakes, and profiles no longer need an account; Infuse 8.2 gives each profile its own home layout, history and parental controls; Netflix, Disney+ and Plex (managed users) all have profiles | One Telegram account; PIN for Settings, kids lock "Allowed chats only" | A family TV shares one Continue watching, one watched list and one set of speeds. Local viewers on one account would fix that | Medium | L |
| Downloads | Netflix keeps downloads to phones | Downloads, Low storage mode, season download, remove after watching | None on TV | Low | S |
| Settings | Plex and Jellyfin split settings into pages | Settings pages, PIN, setup again, backups | None | Low | S |
| Empty, loading, error, motion | Skeletons that keep their shape; errors with a next step | Skeletons, SlowAnswerNotice, CP91's empty states with actions | None | Low | S |
| Ambient and screensaver | Google TV's ambient mode and Apple TV's aerials take over when idle | Its own screensaver was removed on purpose (e0c736c); the system screensaver takes over | Not proposed: an earlier decision | Low | S |
| Accessibility | Android TV and Google TV have a system caption style; Netflix and Prime follow it by default | Own subtitle size, colour and outline; TalkBack labels on controls; reduce motion respected | Subtitles ignore the caption style set in the TV's own Accessibility settings. No high contrast focus ring | Medium | S |
| System integration | Google says new Continue watching integrations must use the Engage SDK, rolled out to partners in stages; existing Watch Next keeps working | Watch Next (continue and next), home screen channels (CP65), media session, frame rate match | Engage SDK is partner gated; keep Watch Next and watch for an open programme | Low | L |

## Android phone (portrait and landscape)

| Area | What the leaders do | TMPlayer today | Gap | Value | Cost |
| --- | --- | --- | --- | --- | --- |
| Home layout | Netflix's phone app adds a vertical clips feed; Plex's 2025 mobile redesign puts Watchlist in the top bar and leans on artwork | Featured carousel, rows, floating bar, Chats page | The clips feed needs a catalogue; nothing else | Low | S |
| Details and motion | The Apple TV app and Netflix grow the tapped poster into the details page | Show and video pages open with a plain transition | A shared element transition from card to page (Compose's SharedTransitionLayout). Nice, not needed | Low | M |
| Details: seasons and episodes | Same as TV: mark a season watched | Per episode only | Same as TV | Medium | S |
| Continue watching and Up next | Same as TV | Same as TV (shared code) | Next up cards, auto skip choice | High | S |
| Player overlay and gestures | VLC, MX Player and Netflix phone: double tap, hold for 2x, brightness and volume drags, lock | All of these, plus pinch, PiP with auto enter, Picture shape, A-B loop, frames, bookmarks | None | Low | S |
| Downloads | Netflix's Smart Downloads deletes a finished downloaded episode and fetches the next one, on Wi-Fi only, within the space already used | Remove after watching (deletes), season download, Downloads grouped by show, Wi-Fi rules for updates | Nothing fetches the next episode by itself, so a commute needs planning. "Download the next episode" pairs with remove after watching | High | M |
| Search | Voice, recent, suggestions | Phone dictation, recent searches, all chats, @username, links | None | Low | S |
| Profiles and kids | Netflix, Disney+, Prime profiles | Kids lock, PIN | Same as TV, lower value on a personal phone | Low | L |
| Settings | Searchable settings in large apps | Settings pages | None worth it on a phone | Low | S |
| Accessibility | System caption style followed; TalkBack labels; font scale | TalkBack labels, UI scale, reduce motion | System caption style not followed (CaptioningManager) | Medium | S |
| System integration | YouTube and Netflix put shortcuts on the launcher icon (long press); media notification, lock screen, PiP, cast | Media session and lock screen, background audio, PiP, Cast (CP73 built), notifications (CP76) | No launcher shortcuts: Resume the last video, Search, Downloads, and the last chats as dynamic shortcuts | Medium | S |
| Landscape, tablets, foldables | YouTube and Netflix use two panes on a tablet | Phone layouts tested in portrait and landscape; 5 columns in landscape | Not checked on a tablet or foldable. A device pass, then a list and detail split if it looks stretched | Low | M |

## Desktop (Windows, macOS, Linux)

| Area | What the leaders do | TMPlayer today | Gap | Value | Cost |
| --- | --- | --- | --- | --- | --- |
| Home and rows | Netflix and Plex web: hover lift, row arrows, See all | Wide hero, row arrows, sideways wheel, hover lift with Play, See all, keyboard moves (CP64) | None | Low | S |
| Details: seasons and episodes | Plex and Jellyfin web: mark a season watched from its menu | Per episode, right click | Same as TV | Medium | S |
| Continue watching and Up next | Same as TV | Same as TV | Next up cards, auto skip choice | High | S |
| Scrubbing and previews | YouTube, Plex web, Jellyfin web (trickplay) and Infuse on a Mac show a frame over the bar on hover | The time under the cursor only | No frame on hover. CP80's Telegram storyboards give it where Telegram has them; otherwise a frame from the part already downloaded, as Android's trickplay does | High | M |
| Player overlay | Netflix in a browser shows what is playing after a long pause | Bar, menu, shortcut sheet, mini player | Same paused card as TV, ask first | Medium | S |
| Pickers and stats | VLC and mpv frontends: track menus, a stats overlay | Track menus, online subtitles, subtitle drop, Playback details | None | Low | S |
| Search | Command palettes in desktop apps | Search palette (Ctrl+K), links pasted into it | None | Low | S |
| Profiles | Plex and Infuse profiles | None | Same as TV, lowest value here | Low | L |
| Downloads | Netflix's Windows app had Smart Downloads | Downloads page, taskbar and dock progress | Same as phone: download the next episode | Medium | M |
| Accessibility | Screen readers (NVDA, VoiceOver) read the controls | Visible keyboard focus, labels on buttons; Compose for Desktop's screen reader support is partial | Not tested with NVDA or VoiceOver. An audit pass | Low | M |
| macOS integration | IINA, VLC and Infuse appear in Now Playing (Control Center, media keys, AirPods) and have a full menu bar (Playback, View, Window) | No Now Playing: `MediaSession.create` returns NoMediaSession on macOS. No menu bar beyond the default | Media keys and Control Center do nothing on a Mac; no menu bar with the shortcuts in it | High | M |
| Windows integration | SMTC; VLC and Windows Media Player put previous, play or pause and next under the taskbar thumbnail; a Jump List on the taskbar icon | SMTC, toasts, taskbar progress, single instance | No thumbnail toolbar buttons, no Jump List (Resume, Downloads, Search) | Low | M |
| Linux integration | MPRIS, desktop file actions | MPRIS, notifications, dock progress through LauncherEntry | No desktop file actions (Resume, Downloads) in the AppImage's integration | Low | S |
| Casting | VLC casts to Chromecast | Cast (CP73 built) | None | Low | S |

## Already ahead

- Plays while the file is still arriving, and seeking re-aims the download; no server, no account but your own.
- Low storage mode: a video larger than the free space still plays.
- Series pages, copies of one video folded into one card, course progress for numbered lessons, forum topics as tabs.
- Skip intro, recap and credits from open sources, with a learned intro end per series when there are none.
- Speed remembered per series, chat and topic; preferred audio and subtitle languages; resume rewind.
- Resume on your other devices through your own Saved Messages (CP89), with no server.
- Home screen channels and Watch Next on Android TV; MPRIS and SMTC on the desktop; Cast from phone and desktop.
- Kids lock with allowed chats, PIN, Black theme, night mode for sound, skip silence for talks.
- One app on TV, phone, Windows, macOS and Linux, free and open source, in 21 languages.
- Empty and slow states that always offer a next step (CP91).

## Proposed plan items

Each item lists its platforms and what it waits for. The checkboxes are written the way PLAN.md
writes them, ready to paste.

### CP93 Next up and watched marks (phone, TV, desktop; needs nothing)
- Next up: when an episode passes the watched line and the show has a next episode in the same chat (the order `WatchNext.next` already uses for the launcher), Continue watching gets a "Next: S01E05" card for it in place of the finished one; removing the card hides the show until a new episode is watched
- "Mark season watched" and "Mark season unwatched" on the season tabs' menu, and "Mark show unwatched" on the show page, on phone (hold), TV (menu key or long OK) and desktop (right click), with a count in the confirmation
- Remove after watching applies to a season marked watched only after a confirmation that names how many downloads go
- "Skip automatically" under the skip times setting: Off (default), Intro and recap, Intro, recap and credits; an automatic skip shows a short "Skipped intro, Back to undo" chip
- Next episode countdown: 5, 10, 15 (default) or 30 s, in the autoplay setting
- Unit tests for the Next up choice (finished, next missing, next in another copy, kids lock) and the mark season count

### CP94 Download the next episode (phone, desktop; TV off by default; needs CP93's next episode rule)
- A switch "Download the next episode" under Downloads, off by default: when a downloaded episode is marked watched, the next episode in watching order is queued
- Wi-Fi only unless "Save mobile data" is off, never in Low storage mode, never for a restricted chat, and only when the free space check passes
- Pairs with Remove after watching: the setup and Settings explain the two together ("keeps one episode ahead without using more space")
- The queued download shows "Added for you" in Downloads and can be cancelled like any other
- Carried in backups; unit tested for the next pick, a missing next, Wi-Fi rules and a full disk

### CP95 Player: paused card and desktop scrub frames (phone, TV, desktop; ask first for the paused card, which reverses part of 1.16.0; scrub frames need CP80 or the local fallback)
- Ask the user whether a paused card is wanted, given the info panel removed in 1.16.0; stop here if not
- Paused card: after about 10 s paused with the controls hidden, the picture dims and the title, episode, chat and the online overview (when posters and details are on) show at the lower left; any key or tap brings the controls back; off in Settings, Player
- Desktop scrub frames: a frame over the bar on hover and drag, from CP80's Telegram storyboard when the video has one, otherwise from the part already downloaded through a second mpv instance, with the Android trickplay memory rules
- Desktop: frames off when "Thumbnails while seeking" is off or the device is short of memory (CP68)
- Render tests for the paused card on TV and desktop, light and dark, right to left

### CP96 Viewers on one account (TV first, then phone and desktop; L; needs nothing, after CP93)
- Up to five local viewers, each with a name and a colour avatar, on the same Telegram account; Telegram data stays shared
- Each viewer has their own Continue watching, Watched, Watch later, speed and track memory, Next up and Home row order
- "Who is watching" on open, off by default; switch viewers from the avatar at the top of Home
- The kids lock can be tied to a viewer, and leaving that viewer asks for the PIN
- Resume sync (CP89) and backups carry each viewer separately; the launcher's Watch Next and channels follow the current viewer
- Migration: everything existing becomes the first viewer; unit tests for the split stores and the migration

### CP97 Desktop OS integration (macOS, Windows, Linux; needs nothing)
- macOS Now Playing: title, chat, artwork, position and rate in Control Center, with play, pause, seek and next or previous from media keys and AirPods (MPNowPlayingInfoCenter and MPRemoteCommandCenter through a small native bridge), behind the existing `MediaSession` interface
- macOS menu bar: TMPlayer, File, Playback, View, Window and Help menus that carry the existing shortcuts (Compose `MenuBar`, Mac only)
- Windows taskbar thumbnail buttons: previous, play or pause, next while a video plays (ITaskbarList3), icons kept in step with the player
- Windows Jump List and Linux desktop file actions: Resume the last video, Downloads, Search
- Check on the win11 VM, the AppImage here and by the user's friends on a Mac

### CP98 Android shortcuts and accessibility (phone, TV; needs nothing)
- Launcher shortcuts on a phone: Search and Downloads as static shortcuts, Resume the last video and the two most recent chats as dynamic ones, cleared on sign out and filtered by the kids lock
- Subtitle style "Follow system" (default for new installs): size, colours and edge from the system caption settings (CaptioningManager) on phone and TV, with the own style still selectable
- A high contrast focus ring option on TV (thicker, white with a dark outline), also on when the system's high contrast text is on
- TalkBack pass over the screens added since CP79 (link sheets, comments, forward picker, chat info) and the new controls in CP93 to CP95
- Engage SDK: note in PLAN.md that Watch Next stays until Google opens Engage beyond its partners; no code

## Sources

- Netflix 2025 TV home redesign: https://alternativeto.net/news/2025/5/netflix-announces-global-tv-app-redesign-ai-search-and-vertical-tiktok-style-video-feed
- Netflix TV redesign on Apple TV: https://www.idownloadblog.com/2025/05/07/netflix-apple-tv-redesign-2/
- Netflix home page changes: https://www.indailysa.com.au/news/in-depth/2025/05/09/netflix-homepage-changes
- Netflix Smart Downloads: https://help.netflix.com/en/node/113296
- Prime Video 2024 redesign: https://www.aboutamazon.com/md/news/entertainment/prime-video-makes-it-easier-to-find-your-favorite-content
- Prime Video top bar on TV: https://www.neowin.net/news/the-amazon-prime-video-tv-app-is-getting-a-ui-refresh-with-a-new-top-navigation-bar-and-more/
- Apple tvOS 26, posters and profiles: https://support.apple.com/en-euro/guide/tv/atvb457e13ae/tvos
- tvOS 26 review: https://www.pcworld.com/article/2847724/apple-tv-isnt-changing-much-in-2025.html
- YouTube on TV watch page redesign: https://www.techradar.com/televisions/youtube-finally-gets-the-tv-update-weve-been-waiting-for-and-there-are-5-handy-upgrades
- YouTube dual column TV layout: https://androidcentral.com/apps-software/youtube-dual-column-tv-redesign-detailed
- HBO Max playback controls: https://help.hbomax.com/sb/Answer/Detail/000002510
- Plex Up Next and credits: https://forums.plex.tv/t/play-next-episode/860513
- Plex 2025 mobile redesign: https://www.iphoneincanada.ca/2025/03/31/plex-app-makeover-whats-new
- Infuse 8.2 profiles and Up Next: https://firecore.com/blog
- Infuse 8: https://community.firecore.com/t/infuse-8-now-available/51308
- Jellyfin home sections: https://forum.jellyfin.org/showthread.php?tid=6280
- Findroid: https://github.com/jarnedemeulemeester/findroid
- Compose for TV card scale, border and glow: https://developer.android.com/reference/kotlin/androidx/tv/material3/CardDefaults
- Android TV navigation guidance: https://developer.android.com/design/ui/tv/guides/foundations/navigation-on-tv
- Engage SDK FAQ (Continue watching on Google TV): https://developer.android.com/guide/playcore/engage/faq
- Android dynamic shortcuts: https://developer.android.com/guide/app-actions/dynamic-shortcuts
- Windows thumbnail toolbar: https://learn.microsoft.com/en-us/windows/win32/api/shobjidl_core/nf-shobjidl_core-itaskbarlist3-thumbbaraddbuttons
- tvOS guidelines summary: https://github.com/BasThomas/tvOS-guidelines
- Earlier competitor research: [COMPETITORS.md](COMPETITORS.md)
