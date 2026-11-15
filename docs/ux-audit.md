# UX and UI audit (U19) and large screens, first pass (U17)

2026-11-15. What a first time viewer meets on each form factor the repository can draw without a
device: the Compose render tests (phone, TV, desktop and, new here, tablet and foldable sizes), the
web app over its fake Telegram and the site, both in headless Chromium at 390, 768 and 1280 wide.
Clear problems were fixed, one commit per group; the rest is listed with the reason it was left.
No phone, TV or emulator was used: the device checks are at the end.

Screenshots are under [design/ux-audit/](../design/ux-audit/) (WebP, quality 88). Each one cited
here was looked at; a capture that came out wrong is said to be so and not used as evidence.

## How to repeat it

- Android: `./gradlew :app:testDebugUnitTest --tests '*RenderTest'` writes PNGs to
  `build/device-test/review/` (tablet, foldable, phone and TV qualifiers in each test).
- Desktop: `TMPLAYER_SHOTS=<dir> ./gradlew :desktop:test` writes every desktop and TV shot to `<dir>`.
- Web app: start the test server (`E2E_PORT=8197 pnpm exec vite --config e2e/vite.e2e.ts --port 8197`
  in `webapp/`), then `node e2e/shots.mjs <dir> 8197`: nine pages at 390, 768 and 1280 in system
  Chromium, and a list of controls smaller than 44 px on the touch widths.
- Site: `pnpm build && pnpm preview`, then headless Chromium at 390, 768 and 1280 wide.

## Fixed

| Screen | Platforms | Issue | Fix | Evidence |
| --- | --- | --- | --- | --- |
| A chat's Sort menu | phone, TV, desktop | Oldest and Last played wore the same clock glyph; Single videos the only filled glyph among outlines; Unwatched first and the Unwatched filter, one idea, wore two glyphs | One glyph per line in the shared `VideoSortMenu` (Oldest: rewind; Unwatched first: the filter's; Single videos: outline play) | `37240eb`; `VideoMenuIconsTest`; [before](../design/ux-audit/sort-menu-desktop-before.webp), [after](../design/ux-audit/sort-menu-desktop-after.webp) |
| A chat's floating bar | phone | The star button's caption read "Star"; the desktop, the web app and the list it fills say "Favourite" | Caption from `browse.favourite`, translated in every catalog | `f29b051`; [before](../design/ux-audit/chat-bar-phone-before.webp), [after](../design/ux-audit/chat-bar-phone-after.webp) |
| Settings, Appearance | phone, TV, desktop | High contrast wore the outline moon right under Pure black's filled moon | The eye for High contrast | `462749c`; the [after](../design/ux-audit/settings-tablet-land-after.webp) shot below shows it |
| Send to developer | phone, TV | The question focused Send report, against its own comment and the title report's rule: an OK meant for something else could send | Cancel is focused | `de491a5`; `SendToDeveloperPanelTest` (the remote's focus, by test: Robolectric draws no dialog window to a PNG) |
| Send to developer, Playback test | phone, TV | Their buttons stood primary first at the start edge; `TvConfirm`, the title report and the default player chooser put the safe press first and the step last at the end edge | One shared `PanelActions` row (app `ui/components`), which also wraps a long translation | `de491a5`; same test checks the order |
| Chats, a chat (web) | web phone and tablet | Back, Chat info, Refresh, Choose chats, the favourite star, See all and the tonal buttons were 36 to 44 px under a finger | Under a coarse pointer each answers a 48 px square round its middle, drawn as before | `ed32fdd`; e2e `a11y.spec.ts` "small controls answer 48 px under a finger" (fails without the change); the controls as drawn: [web chat at 390](../design/ux-audit/web-chat-390.webp) |
| Web app build | web | `:core:webRules` no longer compiled (`HashMap.putIfAbsent` in `QueuePlan`, missing in Kotlin/JS), so the web app could not be built | `getOrPut` | `33dd3d7` |
| Settings | tablet, open foldable | Rows ran the whole 1280 dp: a switch a screen away from its label, lines too long to read | Shared `LargeScreen` (ui) centres the column at 720 dp on a window whose shorter side is at least 600 dp; a phone either way up gets nothing | `1a2f623`; `LargeScreenTest`; [before](../design/ux-audit/settings-tablet-land-before.webp), [after](../design/ux-audit/settings-tablet-land-after.webp) |

## Looked at and found fine

- Phone and TV render tests already in the repository (tiles at 1.3 and 2.0 font scale, German
  captions on the floating bar, right to left, TV search walk): no clipping found.
- Mini player bar: every control 48 dp, one line title with an ellipsis
  ([desktop render](../design/ux-audit/mini-player-phone.webp)).
- Default player chooser: Always then Just once at the end edge, wrapping (the convention
  `PanelActions` now shares).
- Play random: first in the Sort menu on every device, absent for an empty list (U16 tests).
- Large screens (U17): a chat's grid picks 7 columns at 1280 dp and 5 at 800 dp
  ([tablet grid](../design/ux-audit/tiles-tablet-land.webp)); the player's controls keep their size
  and stretch only the seek bar ([tablet controls](../design/ux-audit/seek-tablet-land.webp)); a
  chat's chrome on a tablet ([tablet chat](../design/ux-audit/chat-chrome-tablet-land.webp)).
- Site at 390, 768 and 1280 (home, support, troubleshooting, web, android-tv, alternatives, de): no
  clipping and no sideways scroll. Its download counters count up, so shots taken at different
  moments show different numbers: not a bug.
- Web app at 390, 768 and 1280 (Home, Chats, a chat, Search, Settings, Favourites, History,
  Recent, Chat info): no sideways scroll at any width.

## Not fixed, and why

| Screen | Platforms | Issue | Why not now |
| --- | --- | --- | --- |
| Chat info and an invite link | phone, desktop, web | A channel reads "Channels · 12,400 subscribers": the plural chip label for one chat | Needs a singular string in all 22 catalogs; an English-only key would show English in the other 21. Add `chats.kind_channel` and friends with the next translation pass |
| A chat's Sort menu | web | No Last played order, no Length, Picture, With, Language or Added filters, no Play random | A parity gap already tracked in the reliability plan ("web app for Play random and filters") |
| A chat's Sort menu | phone, TV, desktop | Each pick closes the menu, so two filters take four presses | Closing shows each pick's effect at once, a stated choice; candidate: keep it open for the With toggles only |
| A channel's grid with a sponsored message | web 1280 | The sponsored card comes after Telegram's count of cards, so the row above it holds 3 of 6 | Telegram sets the spacing (`docs/agents/telegram-terms.md`); placing it after a full row changes what the terms ask |
| Settings on a tablet | tablet | The page's app bar stays at the start edge while the rows are centred | The app bar is shared by every page; a first pass keeps it |
| Chats, Downloads, Search, Home rows on a tablet | tablet, foldable | Not rendered | They need their view models and TDLib; a render with `SyntheticAccount` is the next step. Two-pane browse and detail is not cheap and was not started |
| Home carousel at tablet and foldable sizes | tablet, foldable | The render test draws no picture or title under Robolectric | A capture limit, not evidence either way: check on a device |
| QR sign in, Refresh | TV, desktop | The pill is 44 dp tall (`QrScanned.kt:276`) | Pressed with a remote or a mouse there; a touch laptop is the only miss |
| Desktop small icon buttons | desktop | Clear search 28 px, window controls 40 px, a download's checkbox 20 px | Pointer targets on a desktop, where 48 is not the rule |
| Continue watching widget (read only, no preview renders) | phone | The compact layout (40 dp high, 13 sp and 11 sp lines, a 3 dp bar) cannot hold its text at 200 percent font scale; no `android:description` for the Android 12 picker; padding 10 dp in one layout, 12 by 6 in the other | Launcher cell sizes vary by device: check on the phone first, then trim to one line at large scale |
| Title report | phone, desktop | Material dialog text buttons where the TV panel has filled buttons | The platform's own dialog on a phone and a computer; left |
| Settings, Appearance | phone, tablet | Typeface and Interface size wear the same "Aa" glyph, three rows apart | The icon set has no second type glyph; a new one belongs in `TmIcons` with the design pass |
| Web Settings | web | Sign out is the largest, red, full width button on the page | Settings pages were restructured already (not rewritten here) |
| Stalled opening sheet, stats overlay | phone, TV | Not reviewed in depth | They live in the player's View code, out of scope for this pass |

## Candidates to remove

Listed for the user; nothing live was removed.

- `grid.star_short` ("Star"): no code reads it since `f29b051`. It is still in the 22 catalogs, and
  the catalog test needs every catalog's keys to be in `en.json`, so it goes from all of them at once.
- 283 more `en.json` keys with no code reference (284 with `star_short`, list below). Found by matching each key's
  generated accessor (`grid.star_short` is `gridStarShort`) against every Kotlin source, and its
  `namespace.key` or bare key against the web app, the site, the scripts and the workers, after
  setting aside the web app's keys built at run time (`player.zip_*`, `player.scale_*`,
  `player.skip_*`, `subtitles.size_*`, `colour_*`, `edge_*`, `position_*`, `theme.*`,
  `settings.layout_*`, `msone.state_*`). A heuristic: check a key by hand before dropping it. Most
  look left over from screens that were rebuilt (the old Free up space and Downloads chart, the
  folder prompt, the Settings rows moved in U20). The rules allow removing a string no code uses,
  but the catalog test needs every catalog's keys in `en.json` and the other 21 catalogs are the
  user's, so they go from all 22 at once, by the user.

<details><summary>Keys with no code reference (284)</summary>

- `browse`: add_chats, back_to_chats, chat_hidden, chat_hidden_where, hide_chat, hide_chat_detail, remove_chat_detail, search_results_chats, search_results_videos, search_scope, search_scope_all_videos, search_scope_chats, search_videos_failed, search_videos_none, search_videos_searching, signed_in
- `chats`: favourites_blurb
- `common`: show_hidden
- `connect`: help
- `connection`: back_online
- `detail`: in_chat
- `device`: posters_note
- `downloads`: cached_note, chart_app, chart_legend_line, chart_of_total, choose_to_delete, choose_to_delete_detail, delete_all, delete_all_body, delete_all_title, delete_cached, empty_active, free_pictures, free_pictures_detail, free_pictures_message, free_pictures_title, free_recent, free_recent_detail, free_unfinished, free_unfinished_detail, free_unfinished_message, free_unfinished_title, frees, nothing_to_free, paused_at, removed_title, resuming_title, saving_title, storage_also, storage_in_downloads, storage_other, unmatched_note
- `grid`: filter_showing, round_none, section_films, section_videos, size_all, size_label, size_within, star_short
- `groups`: favourites_untouched, first_video_tip, hidden_toast, home_first_body, home_no_folders, open_folder, prompt_confirm, prompt_favourites, prompt_message, prompt_no_folders, prompt_title, prompt_undo, setting, setting_no_folders, setting_off, setting_on, shown_toast
- `home`: new_episode, none_chosen, row_chat_movies, row_chat_shows, row_movies, row_shows
- `keep`: exported_gallery
- `lock`: enter_pin
- `login`: change_number
- `main`: back_online, favourite_added, favourite_removed, favourites_cleared, manage_downloads, marked_read, marked_unwatched, marked_watched, pinned, unarchived, unmuted, unpinned
- `meta`: generic_name
- `metadata`: rating
- `nav`: in_progress
- `picker`: add_later, add_more, add_more_detail, added_count, all_chats, clear_scope, select_scope
- `player`: not_enough_space, stereo_starting
- `search`: page_blurb
- `settings`: alert_low_storage, autoplay_detail, autoplay_next, back_to_chats, cached_count, cached_none, cached_one, cached_videos, check, clear_all, clear_all_detail, clear_all_message, clear_all_none, clear_all_title, clear_all_untouched, clear_cache, clear_cache_detail, clear_cache_message, clear_cache_title, clear_failed, clear_pictures, clear_pictures_confirm, clear_pictures_detail, clear_pictures_message, clear_pictures_none, clear_pictures_title, clear_pictures_untouched, clearing, continue_detail, continue_empty, deleting, download_first_detail, downloads_untouched, downmix_detail, favourites_detail, favourites_empty, forget_last, forget_last_detail, forget_last_done, freed, hidden_chat_one, hidden_chats, hidden_chats_all_shown, hidden_chats_detail, hidden_chats_none, hidden_chats_note, hidden_chats_show, hidden_chats_show_all, hidden_chats_shown, home_chats_no_match, home_chats_note, home_chats_title, home_choose_chats, home_recent_from, home_recent_from_note, home_source_auto, home_source_auto_detail, home_source_chosen, home_source_chosen_detail, learnt_languages_forget_all_done, learnt_languages_forgotten, nothing_cached, nothing_to_clear, notify_updates, notify_updates_detail, on_launch, open_last, open_last_detail, page_about_backup_summary, page_about_summary, page_account, page_account_summary, page_home_summary, page_notifications_summary, page_playback_summary, page_playback_summary_desktop, page_storage_summary, page_subtitles_summary_online, resume_sync_started, sign_out_detail, sign_out_loses, storage_cached, storage_description, storage_description_measuring, storage_downloads, storage_legend, storage_pictures, storage_split, storage_using, subtitle_box_detail, watched_empty
- `setup`: chats_all, chats_all_detail, chats_body, chats_choose, chats_choose_detail, chats_title, choices_title, library_body, library_everything, library_everything_detail, library_folders_detail, library_title
- `signin`: code_app, code_call, code_fragment, code_missed_call, code_sent, code_sms, resend, unreachable
- `state`: connecting_first_launch, connecting_session, connecting_still_trying
- `storage`: cached_none, cached_summary, cached_videos, card_summary, clear_all, clear_all_detail, clear_all_message, clear_all_note, clear_all_title, clear_cache, clear_cache_detail, clear_cache_message, clear_cache_note, clear_cache_title, clear_pictures, clear_pictures_detail, cleared_all, freed, nothing_to_clear, pictures_cleared, scan_button
- `subtitles`: default_look_done
- `tour`: settings_lock, settings_playback, settings_storage, settings_storage_tv
- `watched`: marked_unwatched, marked_watched
- `web`: app_install_web, app_short, app_title, beta, chats_empty, coffee_card_note, coffee_hide, coffee_setting, coffee_setting_detail, coffee_sponsor_github, install_later, install_show_how, low_storage_always, needs_app_badge, needs_app_too_heavy, notify_allow, notify_allow_ask, notify_allow_blocked, notify_allow_on, notify_allow_unsupported, notify_episodes_detail, notify_new_videos_detail, notify_summary, proxy_detail, proxy_unavailable, signout_body, stopped_loading, switch_decoder, tv_unreachable_body, tv_unreachable_title

</details>

## Device checks left for the user

- TV: Send to developer opens on Cancel; Left reaches Send report.
- Tablet (or a phone's freeform window at tablet size): Settings centred, scroll from the margins.
- Web on a phone: the round buttons at the top of a chat answer a slightly missed press.
- Widget at 200 percent font scale on the phone.
