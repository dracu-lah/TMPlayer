# Settings audit (U20, 2026-11-15)

Every settings row on the Android phone, the Android TV, the desktop and the web app, where it
lives, what it stores and which strings it shows. Written before the restructure, so each row is
listed on the page it was on; the "Old row to new place" table at the end says where it went.

How to read the tables:

- **Title / detail** are catalog keys in `core/src/commonMain/resources/i18n/en.json`
  (`settings.autoplay` is `s.settingsAutoplay` in Kotlin). `x_on/_off` means the detail swaps
  between `x_on` and `x_off`.
- **Stored** is the DataStore name in `SettingsStore.kt` (the same names `SettingsBook` and the
  backup file use). `DesktopSettings.x` is the desktop's own file; `online` and `metadata` rows
  are stored by `OnlineSubtitles` and `OnlineMetadata`.
- **Forms:** P phone, T TV (both `app`, `SettingsScreen.kt` and the files beside it), D desktop
  (`desktop/.../ui/SettingsPages.kt` and the files beside it), W web (`webapp/src/screens/Settings.tsx`).

## Android (phone and TV), before

Category list (`SettingsScreen.kt`, `page == null`): account header, "Recommended for this device"
card, then Appearance, Playback, Subtitles, Player gestures (P), Library, Home, Launcher (T, where
the launcher has Play next or channels), Notifications (P), Storage and downloads, Account and
security, About and help, Redo setup choices.

### Appearance and language (`settings.page_appearance`)

| Row | Title | Detail | Stored | Forms |
| --- | --- | --- | --- | --- |
| Preview card | none | none | none | P T |
| Theme | `settings.theme` | `theme.*` description | `theme_choice` | P (segments), T (stepper) |
| Pure black | `settings.pure_black` | `theme.black_detail` | `pure_black` | P T, folded under Light |
| Accent | `settings.accent` | `settings.accent_detail` | `accent`, `accent_custom` | P T |
| Typeface | `settings.typeface` | `settings.typeface_detail` | `app_typeface` | P T |
| High contrast | `settings.high_contrast` | `settings.high_contrast_detail` | `high_contrast` | P T |
| Language | `settings.language` | `languageRowDetail` | `language` | P T |
| Layout | `interface.layout` | `layoutRowDetail` | FormFactor's own file | P T |
| Interface size | `interface.size` | `interface.size_detail` | `ui_scale` | P T |
| Lighter pictures | `settings.lighter_pictures` | `_on/_off` | `lighter_pictures` | P T |
| Reduce motion | `settings.reduce_motion` | `_on/_off` | `reduce_motion` | P T |
| Back to the defaults / Undo | `settings.appearance_defaults`, `settings.appearance_undo` | `_detail`, `_done` | the rows above | P T |
| Wallpaper colours | `settings.wallpaper_colours` | `_on/_off` | `dynamic_colour` | P, Android 12+ |

### Playback (`settings.playback`)

| Row | Title | Detail | Stored | Forms |
| --- | --- | --- | --- | --- |
| Autoplay | `settings.autoplay` | `_on/_off` | `autoplay_next` | P T |
| Decoder | `settings.decoder` | decoder label | `video_decoder` | P T |
| Default player | `settings.default_player` | `defaultPlayerSummary` | `default_player` | P T |
| Player engine | `settings.engine` | engine label, `settings.engine_vlc_unavailable` | `player_engine` | P T |
| Switch engine on decoder error | `settings.engine_switch` | `_on/_off` | `engine_switch_on_error` | P T |
| Suggest VLC | `settings.engine_suggest_vlc` | `_on/_off` | `suggest_vlc` | P T |
| Advanced (expander) | `settings.advanced` | `settings.advanced_detail` | none | P T |
| Frame-rate matching | `settings.frame_rate_matching` | `_on/_off` | `frame_rate_matching` | T, Android 12+, under Advanced |
| Tunneled playback | `settings.tunneled` | `settings.tunneled_detail` | `tunneled_playback` | T, under Advanced |
| Playback stats overlay | `settings.playback_stats` | `_on/_off` | `playback_stats` | P T, under Advanced |
| Open a video's page first | `settings.detail_first` | `_on/_off` | `detail_first` | P T |
| Download the whole video first | `settings.download_first` | `_on/_off` | `download_before_playing` | P T |
| Downmix to stereo | `settings.downmix` | `_on/_off` | `downmix_stereo` | P T |
| Even out volume | `player.even_volume` | `settings.even_volume_on/_off` | `even_volume` | P T |
| Background audio | `settings.background_audio` | `_on/_off` | `background_audio` | P |
| Picture in picture on leave | `settings.picture_in_picture_leave` | `_on/_off` | `pip_on_leave` | P, where Android has PiP |
| Preferred audio language | `settings.audio_language` | `audioSummary` | `preferred_audio_language` | P T |
| Thumbnails while seeking | `settings.trickplay` | `_on/_off/_low_memory` | `trickplay` | nowhere while `Trickplay.PICTURES_SHOWN` is false |
| Hide controls after | `settings.controls_timeout` | `settings.controls_timeout_detail` | `controls_timeout_ms` | P T |
| Rewind on resume | `settings.resume_rewind` | `settings.resume_rewind_detail` | `rewind_on_resume_ms` | P T |

### Subtitles (`settings.subtitles`)

| Row | Title | Detail | Stored | Forms |
| --- | --- | --- | --- | --- |
| Subtitle style (dialog) | `settings.subtitle_style` | style summary | `subtitle_size`, `_box`, `_position`, `_box_level`, `_colour`, `_edge`, `_face`, `_bold` | P T |
| Subtitles on open | `settings.subtitle_mode` | mode label | `subtitle_mode` | P T |
| Subtitle language | `settings.subtitle_language` | `subtitleSummary` | `preferred_subtitle_language` | P T |
| Learnt languages | `settings.learnt_languages` | `_empty`, `_count` | per series memory | P T |
| Online subtitles: status / sign in / sign out | `online.title`, `online.sign_in`, `online.sign_out` | `OnlineWords.status` | OpenSubtitles account | P T, build with a key |
| Find subtitles for me | `online.suggest_toggle` | `online.suggest_on/_off` | online account | P T |
| Machine translated | `online.machine_toggle` | `online.machine_on/_off` | online account | P T |
| SubDL key | `online.subdl` | `online.subdl_*` | online account | P T |
| Msone | `msone.title` | msone state | online account | P T, hidden flag |
| Clear subtitle cache | `online.clear_cache` | `online.cache_detail`, `online.cache_empty` | cache | P T |

### Player gestures (`settings.page_gestures`, phone only)

| Row | Title | Detail | Stored |
| --- | --- | --- | --- |
| Vibration | `settings.haptics` | `settings.haptics_detail` | `player_haptics` |
| Tap plays and pauses | `settings.tap_plays` | `_on/_off` | `player_tap_plays_pauses` |
| Double tap | `settings.double_tap` | `settings.double_tap_detail` | `double_tap_ms` |
| Hold to speed up | `settings.hold_speed` | `settings.hold_speed_detail` | `hold_speed` |
| Swipe to seek | `settings.seek_gesture` | `_detail` | `gesture_seek` |
| Brightness swipe | `settings.brightness_gesture` | `_detail` | `gesture_brightness` |
| Volume swipe | `settings.volume_gesture` | `_detail` | `gesture_volume` |

### Library (`settings.library`)

| Row | Title | Detail | Stored | Forms |
| --- | --- | --- | --- | --- |
| Open the last chat | `settings.open_last_chat` | `_named/_unnamed/_off` | `open_last_chat` | P T |
| Forget the last chat | `settings.forget_last_chat` | `_detail` | `last_chat` | P T, while set |
| Lists: Remember searches | `settings.remember_searches` | `_on/_off` | `remember_searches` | P T |
| Account: Sync across my devices | `syncplace.switch` | `syncplace.switch_on/_off`, the privacy line `syncplace.privacy` | `resume_sync` | P T D |
| Account: Sync now / Replace | `SyncWords.action` | `SyncWords.status` | sync bookkeeping | P T, while on |
| Lists: Turn off and delete the sync file | `settings.resume_sync_delete` | `_detail` | sync file | P T, while on |
| Lists: Chosen chats | `picker.row_title` | `chosenChatsDetail` | `added_chats`, `added_chats_mode` | P T |
| Lists: Clear favourites | `settings.clear_favourites` | `_detail` | `favorite_chats` | P T, while any |
| Lists: Clear history | `browse.clear_history` | `browse.clear_history_detail` | history | P T, while any |
| Posters and details | `metadata.toggle` | `metadata.toggle_on/_off` | metadata settings | P T |
| Skip intro times | `metadata.skip_times` | `metadata.skip_times_privacy` | metadata settings | P T D, while on, then four per kind rows (`settings.skip_recap` and kin) |
| TMDB key | `metadata.key` | `MetaWords.key` | metadata settings | P T |
| OMDb key | `metadata.omdb_key` | `MetaWords.omdbKey` | metadata settings | P T |
| Clear details cache | `metadata.clear_cache` | `metadata.cache_detail/_empty` | cache | P T |

### Home (`settings.page_home`) and Launcher (`settings.page_tv_home`)

| Row | Title | Detail | Stored | Forms |
| --- | --- | --- | --- | --- |
| Carousel | `settings.home_carousel` | `_on/_off/_no_online` | `home_carousel` | P T |
| Continue watching | `settings.home_continue` | `_on/_off` | `home_continue` | P T |
| Recently added | `settings.home_recent` | `_on/_off` | `home_recent` | P T |
| Rows of starred chats | `settings.home_starred_rows` | `_on/_off` | `home_starred_rows` | P T |
| Note | `settings.home_follows_added_chosen` | none | none | P T |
| Play next row | `settings.watch_next` | `_on/_off` | `watch_next` | T, where supported |
| TMPlayer channel | `settings.tv_channels` | `_on/_off` | `tv_channels` | T, where supported |

### Notifications (`settings.page_notifications`, phone only)

`settings.alert_downloads`, `settings.alert_episodes`, `settings.new_videos`, `settings.alert_pieces`,
`settings.alert_update` (with `Updates.enabled`), `settings.sponsor_reminders` (while the support
ladder runs), each with its `_detail`, stored as `notify_downloads`, `notify_episodes`,
`new_video_alerts`, `notify_pieces`, `notify_update`, `sponsor_reminders`.

### Downloads and data (`settings.page_downloads_data`; was Storage and downloads)

| Row | Title | Detail | Stored | Forms |
| --- | --- | --- | --- | --- |
| Storage card, Free up space | `downloads.*` | split | none | P T |
| Download location | `storage.location` | location state | `download_root` | P T, more than one volume |
| Remove after watching | `downloads.remove_after_watching` | `_body` | `remove_after_watching` | P T |
| While streaming: Keep video / Save space (two tabs) | `settings.stream_title` | `settings.stream_keep_detail` / `stream_save_detail` under the tabs | `low_storage_mode` (on is Save space) | P T D |
| Download the whole video first | `settings.download_first` | `_on/_off` | `download_before_playing` | P T D |
| On mobile data: Ask first / Never / Allow (three tabs; R2, replaces Wi-Fi only and Save mobile data) | `settings.mobile_data_title` | `settings.mobile_data_ask_detail` / `_never_detail` / `_allow_detail` under the tabs | `mobile_data` (`ask`, `never`, `allow`; until chosen read from `wifi_only_downloads` and `save_mobile_data`: Never if Wi-Fi only, else Ask first; Wi-Fi only is kept in step) | P |

Gone in R2, behaviour kept on (nothing reads their stored value as an off switch): the low storage notification
(`notify_low_storage`) and Pause downloads while playing (`pause_downloads_while_playing`). `save_mobile_data` still
bounds a stream on a metered line for whoever set it; the setup choices' Mobile data step still writes it.

### Account and security (`settings.page_account`)

| Row | Title | Detail | Stored | Forms |
| --- | --- | --- | --- | --- |
| Set / Change the PIN | `lock.set_pin`, `lock.change_pin` | `_detail` | `app_pin` | P T |
| Lock on open | `lock.on_open` | `lock.on_open_detail` | `app_lock_on_open` | P T, with a PIN |
| Kids lock | `lock.kids` | `AppLockWords.kidsDetail` | `kids_lock` | P T |
| Allowed chats | `lock.allowed_chats` | `_detail` | `kids_chats` | P T |
| Remove the PIN | `lock.remove_pin` | `_detail` | `app_pin` | P T, with a PIN |
| Devices | `settings.devices` | `settings.devices_detail` | none | P T |
| Sign out | `settings.sign_out` | dialog | none | P T |

### About and help (`settings.page_about`)

| Row | Title | Detail | Stored | Forms |
| --- | --- | --- | --- | --- |
| Update waiting | `UpdateWords.settingsRow` | `settings.update_detail` | none | P T, `Updates.enabled` |
| Check for updates | `settings.check_updates` | `_detail` | `update_last_check` | P T, `Updates.enabled` |
| Tell me when a new version is out | `settings.update_notify` | `_detail` | `update_notify` | P T, `Updates.enabled` |
| Get test versions | `settings.test_versions` | `_on/_off` | `update_test_versions` | P T, `Updates.enabled` |
| Install updates by itself | `settings.auto_install` | `_on/_off/_blocked` | `update_auto_install` | P T, Android 12+ |
| Back up / Restore | `backup.back_up`, `backup.restore` | `_detail` | the backup file | P T |
| Proxy | `proxy.title` | `proxy.in_use`, `proxy.settings_detail_off` | TDLib | P T |
| Quick tour | `settings.tour` | `settings.tour_body` | `tour` | P T |
| What's new | `settings.whats_new` | `_detail` | none | P T |
| Changelog | `settings.changelog` | `_detail` | none | P T |
| Report a problem | `settings.report_problem` | `_detail` | none | P T |
| Save logs | `logs.save` | `logs.save_detail` | none | P T |
| Playback test | `playbacktest.title` | `playbacktest.detail` | last result file | P T |
| Send to the developer | `logs.send_dev` | `_detail` | none | P T, build with an inbox |
| Keep detailed logs | `logs.detailed` | `_on/_off` | `detailed_logs` | P T |
| Save name check | `logs.name_report` | `_detail` | `developer_options` | P T, developer options |
| Privacy | `about.privacy` | `settings.privacy_detail` | none | P T |
| Send crash reports | `settings.crash_reports` | `_on/_off` | `crash_reports` | P T, build with a DSN |
| Lawful use | `about.lawful_use` | `_detail` | none | P T |
| Support | `About.SUPPORT_TITLE` | `settings.support_detail` | support counters | P T, ladder on |
| About TMPlayer | `settings.about` | `_detail` | none | P T |
| Note | `settings.privacy_note` | none | none | P T |

### Redo setup choices (`settings.redo_setup_choices`)

The setup's answers (Posters, Storage, Performance, Mobile data, Device, Crash reports), each with
"Also in" pointing at Library, Storage or Appearance. This is the "one row plus a link" pattern
already; it stays.

## Desktop, before

Same page names as the phone (`SettingsCategory`), with Mouse in the player in place of Player
gestures, no Launcher, and Notifications kept. The differences from the phone, row by row:

- Appearance: segments where the phone has steppers; adds Start in full screen
  (`DesktopSettings.startFullscreen`), Compact side bar (`compact_nav`) and Use the system title
  bar (`DesktopSettings.systemTitleBar`, only where the app draws its own window buttons). No
  Lighter pictures, Reduce motion or Wallpaper colours.
- Playback: Autoplay, Open a video's page first, Download the whole video first, Downmix
  (`DesktopSettings.downmix`, not `downmix_stereo`), Even out volume, Preferred audio language,
  Software decoding (`DesktopSettings.softwareDecoding`, the desktop's Decoder), Hide controls
  after, Rewind on resume. No default player, engine, Advanced, background audio or PiP.
- Subtitles: style dialog, mode, language, learnt languages, online subtitles (no Find subtitles
  for me: the desktop player does not fetch by itself).
- Mouse in the player: Hold to speed up (`hold_speed`, the phone's setting), Wheel seeks
  (`DesktopSettings.wheelSeeks`).
- Library: Smallest and Largest steppers with Reset in place of the size dialog; otherwise the
  phone's rows.
- Home, Notifications: the phone's rows (desktop details for episodes, new videos and updates).
- Storage: Free up space, Location (Change and Reset, `DesktopSettings.storageRoot`), Cache limit
  (`DesktopSettings.cacheLimitBytes`), Remove after watching, While streaming (Keep video or
  Save space), Scan the folder. (R2: the low storage notification and Pause downloads while playing have no
  switch, they are always on; the desktop has no mobile data row.)
- Account: the phone's rows.
- About: Version group (no auto install, no detailed logs, no playback test, no send to the
  developer), Backup, Help with Proxy, Tour, What's new, Changelog, Report a problem, Save logs,
  Save name check, Privacy (`settings.privacy`), Send crash reports, Lawful use, Support, About.
- No search box. Deep link in: `ShellState.openSettings(SettingsCategory.Home)` from Home's
  "Open settings" card.

## Web app, before

`/settings/:page?`, pages `appearance`, `home`, `playback`, `library`, `storage`, `about`; the TV
form shows one long page of panels (`TvSettings`) with the same settings.

| Page | Rows |
| --- | --- |
| Appearance | Theme (`theme`), Layout (not on a phone) |
| Home | the four Home switches, the note |
| Playback | Rewind on resume (`rewindMs`), Msone (hidden flag) |
| Library | Chosen chats (link to `/added-chats`), Posters (`posters`), TMDB note, Size limits (`#size`, `sizeMin`, `sizeMax`), Clear history |
| Storage | Install as an app, storage used, Clear cache, Keep storage |
| About | Chip in, Third-party notices, Terms and privacy, licence line |

Deep links in: Home's card to `/settings/home`, a chat's size note to `/settings/library#size`.
The web keeps its own `Settings` object (`webapp/src/store.ts`), not SettingsStore; nothing there
is moved by U20 (see "Not done").

## Findings

### The same job in several places

1. **Track languages split over two pages.** Preferred audio language was on Playback, subtitle
   mode and language on Subtitles, all one `TrackPreferences` and one picker. Fix: one page,
   Subtitles and audio.
2. **Sound rows on Playback** (Downmix, Even out volume) beside the picture rows, while the audio
   language sat elsewhere. Fix: with the audio language.
3. **Where the video comes from** (Download the whole video first on Playback; While streaming,
   Save mobile data, Wi-Fi only and Pause downloads while playing on Storage). Fix: Download the
   whole video first joins the others on Downloads and storage.
4. **Crash reports** on About and help, the setup's crash step and the one-time "Help fix crashes?"
   card (S1). The card and the setup only ask; the switch has one home now, Privacy and lock,
   beside the privacy page it is explained on.
5. **Updates told twice:** "Tell me when a new version is out" (`update_notify`, the in-app check)
   on About and "Update ready" (`notify_update`, the system notification) on Notifications. Two
   different keys and behaviours, so both stay; listed under Candidates.
6. **Clearing caches:** the subtitle and details caches each have Clear on their own page while
   Free up space reviews the posters and the played videos. Different stores; left, listed.
7. **Decoder** on its own row and in the player's menu: the menu is for the video playing, the row
   for every video; both stay (player files are out of scope).

### Rows that only differ by platform

Theme (segments on P and D, stepper on T), Decoder (P T) and Software decoding (D), Downmix
(`downmix_stereo` on P T, `DesktopSettings.downmix` on D), Player gestures (P) and Mouse (D), Size
limits (dialog on P T, two steppers on D), Location (volume list on P T, folder on D). Each is one
row per platform already, in the same place; nothing to merge.

### Rows nobody can reach

- Thumbnails while seeking: drawn only while `Trickplay.PICTURES_SHOWN`, which is false. The
  switch is still stored and read by the player; listed under Candidates.
- `settings.page_about_summary`, `settings.page_home_summary`,
  `settings.page_notifications_summary`: summaries no list shows.

### Controls that do nothing on their platform

- **Suggest VLC when playback cannot keep up** (`suggest_vlc`): stored and written by its switch,
  read by nothing (`suggestVlcNow` has no caller). Removed from Settings; the key keeps being read
  so a backup with it restores, and nothing writes it now.
- **Player engine** and **Switch engine on decoder error** in a build without libVLC (every
  released build, PR2): Automatic and ExoPlayer are the same engine there, and there is nothing to
  switch to. Hidden while VLC is not offered, unless VLC is the stored choice (so the row can say
  it is unavailable and be put back).

### Detail text that repeats the title

- Phone and TV: none word for word. Close: Subtitles on open shows only the mode's label.
- Desktop: the style dialog's Colour, Edge, Typeface and Bold say the value the control shows.
- Web: Playback's line in the list is its only row's title (`settings.resume_rewind`).

### Rows in the wrong group

- Preferred audio language, Downmix, Even out volume: Playback, now Subtitles and audio.
- Download the whole video first: Playback, now Downloads and storage.
- Send crash reports, Privacy: About and help, now Privacy and lock.

### New rows of this week, one home each

| Row | Home |
| --- | --- |
| Default player | Playback |
| Player engine | Playback (hidden without VLC) |
| Pause downloads while playing | Downloads and storage |
| Sync across my devices | Privacy and lock (the Account page), Signed in |
| Find subtitles for me | Subtitles and audio, Online subtitles |
| Keep detailed logs | Help and about, beside Save logs |
| Playback test | Help and about, beside Save logs |
| Crash ask card | asks once; its answer is Send crash reports on Privacy and lock |

### Dead keys

- Stored but never read: `suggest_vlc` (above).
- Read but never written: `support_asked` (the old support ladder, read for its count) and
  `hidden_chats` (read once to move it into the chosen chats, then removed). Both are migrations;
  they stay.
- Every other name `SettingsStore` writes is read, and `BackupCoverageTest` holds the backup list
  to it exactly.

### Strings no code uses

En keys no Kotlin or web source reads, found by matching each key and its accessor name
against the sources of `app`, `ui`, `core`, `desktop` and `webapp`. Every one of the settings ones is
in all 21 other catalogs, so it stays in `en.json` until the user removes it from all 22 at once
(`CatalogTest` fails on a key another catalog has and English does not):

`settings.alert_low_storage`, `settings.autoplay_detail`, `settings.autoplay_next`,
`settings.back_to_chats`, `settings.cached_count`, `settings.cached_none`, `settings.cached_one`,
`settings.cached_videos`, `settings.clear_all*` (6), `settings.clear_cache*` (4),
`settings.clear_failed`, `settings.clear_pictures*` (7), `settings.clearing`,
`settings.continue_detail`, `settings.continue_empty`, `settings.deleting`,
`settings.download_first_detail`, `settings.downloads_untouched`, `settings.downmix_detail`,
`settings.favourites_detail`, `settings.favourites_empty`, `settings.forget_last*` (3),
`settings.freed`, `settings.hidden_chat_one`, `settings.hidden_chats*` (8), `settings.history`,
`settings.home_chats_*` (3), `settings.home_choose_chats`, `settings.home_recent_from*` (2),
`settings.home_source_*` (4), `settings.learnt_languages_forget_all_done`,
`settings.learnt_languages_forgotten`, `settings.nothing_cached`, `settings.nothing_to_clear`,
`settings.notify_updates`, `settings.notify_updates_detail`, `settings.on_launch`,
`settings.open_last`, `settings.open_last_detail`, `settings.page_about_summary`,
`settings.page_home_summary`, `settings.page_notifications_summary`,
`settings.resume_sync_started`, `settings.sign_out_detail`, `settings.sign_out_loses`,
`settings.storage_*` (9), `settings.subtitle_box_detail`, `settings.updates`,
`settings.watched_empty`, `storage.cached_*` (3), `storage.card_summary`, `storage.clear_*` (10),
`storage.cleared_all`, `storage.freed`, `storage.nothing_to_clear`, `storage.pictures_cleared`,
`storage.scan_button`, `storage.title`, `lock.enter_pin`, `theme.black`, `setup.chats_*` (5),
`setup.choices_title`, `setup.library_*` (6), `setup.skip`, `setup.start`.

Outside Settings the same search finds about 200 more (`browse`, `downloads`, `grid`, `groups`,
and so on), all in every catalog; `player.zip_too_large` is in no catalog and no code (left for the
player work in flight).

## After: the groups (U20, step 2)

One order on the phone, the television and the desktop (`SettingsGroup`, core), each page with a
one-line summary in the list (`settings.group_*_summary`, or a live value: the free space, the
lock's state, an update waiting):

| Page | What it is for | Forms |
| --- | --- | --- |
| Playback | how a video starts and plays: autoplay, the default player, the engine and decoder, the controls, rewinding on resume, Advanced | P T D |
| Player gestures / Mouse in the player | the player's touch gestures, or the mouse | P / D |
| Subtitles and audio | subtitle look and languages, the audio language, remembered languages, Sound (downmix, even volume), Online subtitles | P T D |
| Downloads and data | the free space, where downloads go, On mobile data, While streaming, Download the whole video first, Remove after watching | P T D |
| Notifications | every notification, one switch each | P D |
| Appearance and language | theme, colours, type, language, layout, size, motion | P T D |
| Privacy and lock | App lock, Privacy (the privacy page, Send crash reports), Signed in (devices, Sign out) | P T D |
| Library | which videos show, the last chat, Lists, Posters and details | P T D |
| Home, Launcher | Home's rows; the TV launcher's rows | P T D, T |
| About and help | version and updates, backup, help and logs, support, About | P T D |
| Redo setup choices | the setup's answers, each with "Also in" its page | P T D |

`SettingsMap` (core) lists every row by page with its title and detail keys, the forms it is on
and what it needs to be drawn (a build feature, a device feature, or the viewer's own state). It
is the search box's index and the deep link test's list, and the screen tests hold it to the
pages both ways: `SettingsMapScreenTest` (phone and TV, every page), `SettingsMapDesktopTest`.

Search: a box over the list on the phone and the desktop (Ctrl+F there), shown past 25 rows
(`SettingsSearch`, table tested). It matches word starts in titles, the lines under them and the
page names, accents folded, and opens the page on the row, lit for a moment. A television has no
box: its pages are a few D-pad presses apart and typing with a remote costs more than walking.

## Old row to new place

| Row | Before | After |
| --- | --- | --- |
| Preferred audio language | Playback | Subtitles and audio, under the subtitle language |
| Downmix to stereo, Even out volume | Playback | Subtitles and audio, Sound |
| Download the whole video first | Playback | Storage and downloads, under the location |
| Privacy (the privacy page) | About and help, Help | Privacy and lock, Privacy |
| Send crash reports | About and help, Help | Privacy and lock, Privacy |
| Decoder | Playback, first | Playback, beside the player engine |
| Player engine, Switch engine on decoder error | Playback | Playback, only where VLC is offered or chosen |
| Suggest VLC when playback cannot keep up | Playback | removed (read by nothing) |
| Page list | Appearance first | Playback first, the groups' order on every form |
| Subtitles | "Subtitles" | "Subtitles and audio" |
| Account and security | "Account and security" | "Privacy and lock" |
| Setup choices, Mobile data on a TV | "Also in" Storage, which has no such row there | no link on a TV |

Every other row stays where it was. No stored key changed: each row reads and writes the same
DataStore name as before, so a backup from before U20 restores into the same rows
(`BackupCoverageTest` unchanged).

## Links into Settings (U20, step 3)

`SettingsLink` (core) lists every way in from outside: Home's "Open settings" card, Setup choices'
"Also in" (Posters, Storage, Performance, Mobile data), and the help lines that name a place
("Change it any time in Settings, Playback", "Sign in under Settings, Online subtitles", "Choose
them in Settings, App lock", "Turn them back on in Settings, Posters and details", the size note,
not enough space, Wi-Fi only, Save name check, the crash question). `SettingsMapTest` checks each
lands on a row that exists on each of its forms; `SettingsLinksSourceTest` reads the sources so a
new one cannot slip past (a page opened by name outside Settings, a web `/settings/<page>#anchor`,
a line of help text naming "Settings, <place>").

## Candidates to remove (for the user to decide)

| What | Why it might go | Why it is still here |
| --- | --- | --- |
| Tell me when a new version is out (`update_notify`) | Reads as the same job as the Update ready notification (`notify_update`) | It is the in-app check, the other is the system notification; merging changes behaviour |
| Clear posters and details, Clear online subtitles | Storage's Free up space already reviews the posters | Different stores (the details' answers, the subtitle files); Free up space does not clear them |
| Thumbnails while seeking | Never drawn while `Trickplay.PICTURES_SHOWN` is false | The player still reads it; turning the pictures back on brings the row back |
| Player engine, Switch engine on decoder error | Hidden in every released build (no libVLC) | A `-PtmVlc=true` build uses them |
| Playback stats overlay | Most viewers never need it | It is the one way to see the decoder and dropped frames; already folded under Advanced |
| Desktop Preview card titled "Preview" | The phone's and the TV's have no title | Harmless; kept so the desktop's page reads the same |
| The unused settings strings above (about 130 before U20) | No code reads them | In all 21 other catalogs: remove from all 22 at once |

## Not done in U20, and why

- The web app's Settings follows the map since WB20 (2026-11-20): Home and library are one page
  (`/settings/library` still lands on it), a search box over the rows (phone and desktop; the TV keeps
  its one long page) answers with the apps' `SettingsSearch` through WebRules, `/settings/<page>#<row id>`
  opens a row by its map id, and About shows What's new. The web has no player gestures, decoder or
  notification rows, so those never appear in its results (`webapp/src/ui/settingsMap.ts` lists what it draws).
- The tour's Settings picture (`tour-*-settings*`) still shows the old list order and names:
  retaking it needs the promo build and `scripts/annotate-shot.py` then `scripts/tour-badges.py`.
  The tour's four lines are rewritten for the new pages and keep their badges' order.

## Removed (U20, step 5), on the plan's rule only

- The Suggest VLC switch (a control nothing acted on) and its three strings
  (`settings.engine_suggest_vlc`, `_on`, `_off`), which no code and no catalog used. Its stored
  name is still read (`suggestVlcNow`) and travels in a backup; nothing writes it now.
- Nothing else: every other row is a live feature or the only way to a setting.

## Keys U20 left unused, for the other 21 catalogs

English keeps them until the user removes them from all 22 catalogs at once (`CatalogTest`):
`settings.page_about_backup_summary`, `settings.page_account`, `settings.page_account_summary`,
`settings.page_playback_summary`, `settings.page_playback_summary_desktop`,
`settings.page_storage_summary`, `settings.page_subtitles_summary_online`, `settings.subtitles`,
`tour.settings_lock`, `tour.settings_playback`, `tour.settings_storage`, `tour.settings_storage_tv`.

New keys to translate: `settings.group_about_summary`, `settings.group_playback_summary`,
`settings.group_playback_summary_desktop`, `settings.group_privacy_lock_summary`,
`settings.group_storage_summary`, `settings.group_storage_summary_desktop`,
`settings.group_subtitles_audio_summary`, `settings.group_subtitles_audio_summary_online`,
`settings.page_privacy_lock`, `settings.page_subtitles_audio`, `settings.search`,
`settings.search_clear`, `settings.search_none`, `settings.sound`,
`tour.settings_playback_page`, `tour.settings_privacy_page`, `tour.settings_storage_page`,
`tour.settings_storage_page_tv`.
