# Strings no code uses, kept in en.json only so CatalogTest stays green

CatalogTest fails when a locale holds a key en.json does not. The 21 locale files are the user's
(langsync), so removing a key from en.json alone breaks the build. Delete these keys from en.json AND
all locales together when the user next syncs, then drop the line here.

- main.back_again (T3 removed the exit confirmation, 2026-11-16)
- picker.count_about, picker.count_again, picker.count_many, picker.count_unknown_keys,
  picker.count_unknown_touch, picker.total_counting, picker.total_videos (T5, no video counts)
- picker.below_bar (old text with {minimum}; the new text is picker.below_bar_many)
- login.press_back_again (T1 removed ExitOnBack)
- support.note_touch, support.note_tv, support.popup_note, support.star_note, support.upi_note (D1)
- settings.mini_player, settings.mini_player_off, settings.mini_player_on (T12 removed the phone's mini player row; the desktop's mini.* and row_mini keys are still used)
- settings.low_storage, settings.low_storage_detail, settings.low_storage_off, settings.low_storage_on,
  main.low_storage_ask_keep, main.low_storage_ask_play, main.no_room_low_storage,
  player.not_enough_room_low_storage, downloads.free_low_storage, downloads.free_low_storage_done,
  setup.storage_body, setup.storage_low, setup.storage_low_detail, setup.storage_normal,
  setup.storage_normal_detail, tour.settings_storage, tour.settings_storage_page,
  tour.settings_storage_page_tv, tour.settings_storage_tv (T4, "Low storage mode" became "While
  streaming: Keep video | Save space"). Kept in use: settings.low_storage_alert,
  settings.alert_low_storage*, downloads.free_low_storage_detail, main.low_storage_not_now,
  main.low_storage_switch_play.
- New text to translate (T4): settings.stream_title, stream_keep, stream_keep_detail, stream_save,
  stream_save_detail, stream_recommended, stream_switch_title; main.stream_ask_keep, stream_ask_play,
  stream_no_room; player.not_enough_room_stream; downloads.free_save_space, free_save_space_done,
  free_switch; setup.stream_body; tour.settings_stream_page, settings_stream_page_tv. After the sync,
  drop `awaitingSync` in `ui/src/jvmTest/.../SetupTest.kt` (the every-locale test skips the Storage step).
- New text to translate: picker.reason_many, picker.reason_some, picker.below_bar_many, and D1's support card strings.
- New text to translate (T9): series.find_elsewhere, series.gap_missing, series.in_your_chats, series.not_in_chats, series.only_mine, series.only_mine_short, series.season_gap_note, series.show_all, series.show_all_short, series.specials. No key was dropped.
- login.password_title, errors.phone_invalid, errors.wrong_code, errors.premium_sign_in, errors.tdlib_unavailable, errors.tdlib_parameters, errors.no_api_credentials, connect.hint_blocked, connect.hint_proxy, player.stalled_no_line (FQ1, FQ2, FQ7 + Raju E: plainer words live in new keys; the old keys keep their text and arguments so every locale stays valid, and are unused now).
- New text to translate (FQ1, FQ2, FQ7, Raju E): login.telegram_password_title; errors.phone_check, errors.code_check, errors.premium_qr, errors.start_failed, errors.start_rejected, errors.no_credentials; connect.slow_plain, connect.hint_blocked_plain, connect.hint_proxy_plain, connect.more_help, connect.less_help; player.stalled_no_line_plain.
- metadata.skip_times_detail (T10: the row now reads metadata.skip_times_privacy; the old key keeps its text, unused on phone, TV and desktop).
- New text to translate (T10): player.skip_intro_mark, player.skip_in, player.skip_back_cancel, player.mark_intro, player.mark_intro_end, player.mark_intro_started, player.mark_intro_too_short, player.mark_intro_restarted, player.mark_intro_saved, player.mark_intro_remove, player.mark_intro_removed; metadata.skip_times_privacy; settings.skip_recap, settings.skip_intro, settings.skip_credits, settings.skip_preview, settings.skip_mode_ask, settings.skip_mode_ask_detail, settings.skip_mode_auto, settings.skip_mode_auto_detail, settings.skip_mode_off_detail, settings.skip_mode_note; keys.row_mark_intro. No key was dropped.
- Dead after R1 (show every video by default; the keys keep their text and arguments so every locale stays valid): settings.smallest, settings.largest, settings.size_limits, settings.size_limits_note, settings.size_limits_reset, settings.size_limits_reset_detail, size.largest, size.smallest, grid.size_all, grid.size_default, grid.size_hidden, grid.size_hidden_label, grid.size_label, grid.size_quick_note, grid.size_within, browse.size_limits_in_settings. Still used (a custom floor's empty state and the Sort sheet's custom limits line): size.no_minimum, size.no_limit, size.range_*, browse.hidden_by_size, browse.no_videos_between, browse.no_videos_from, browse.no_videos_up_to.
- New text to translate (R1): grid.filter_size_label, grid.hide_small_clips, grid.size_limit_on, grid.small_hidden, grid.small_hidden_all, grid.small_hidden_some, grid.small_hidden_some_all, home.small_left_out. Reused, already translated: grid.filter_show_them.
- Dead after R4 (offline mode): connection.offline (the desktop's passive Offline pill; the one card now says state.offline "Offline. Your downloads still play."). main.connect_to_play is still used (the play guard for chat grids and search, which have no page to go to).
- New text to translate (R4): home.row_on_phone, home.needs_internet, detail.connect_or_download. Reused, already translated: state.offline, common.retry, downloads.title, connect.retrying, errors.offline, common.reload.
- Unused since R3 (one question setup, 2026-11-16): setup.appearance_body, setup.appearance_title, setup.chats_all,
  setup.chats_all_detail, setup.chats_body, setup.chats_choose, setup.chats_choose_detail, setup.chats_title,
  setup.crash_ask_body, setup.crash_ask_body_desktop, setup.crash_ask_no, setup.crash_ask_title, setup.crash_ask_yes,
  setup.crash_body, setup.crash_holds, setup.crash_holds_desktop, setup.crash_privacy, setup.crash_title,
  setup.data_body, setup.data_title, setup.device_apply_detail, setup.device_not_now_detail, setup.group_appearance,
  setup.group_preferences, setup.later, setup.library_body, setup.library_everything, setup.library_everything_detail,
  setup.library_folders, setup.library_folders_detail, setup.library_title, setup.recommended, setup.skip, setup.start,
  tour.offer_body, tour.offer_no_thanks, tour.offer_take, tour.offer_title (the modal tour offer became the Tips card),
  picker.gate_body, picker.gate_title, picker.leave_body, picker.leave_title (replaced by setup.which_* and
  picker.leave_choosing_*; picker.gate_done_none and picker.leave_yes are still used). setup.choices_title,
  setup.step, setup.next, setup.back, setup.skip_all, setup.done, setup.use_recommended are still read by the tour
  or Settings: check with a grep before deleting. Also unused, from before R3: tour.settings_lock, tour.settings_playback.
- New text to translate (R3): setup.which_title, setup.which_body, setup.offline_title, setup.offline_body,
  picker.forward_hint, picker.leave_choosing_title, picker.leave_choosing_body, tips.title, tips.body, tips.show,
  tips.dismiss. No key was dropped or changed. The web strings list (webapp/strings.config.ts) now names
  picker.forward_hint and the four setup.* keys instead of picker.gate_title and picker.gate_body.
- Dead after R2 (Downloads tab, one list, Downloads and data page; the keys keep their text and arguments so every locale stays valid): settings.wifi_only, settings.wifi_only_on, settings.wifi_only_off, settings.save_mobile_data, settings.save_mobile_data_on, settings.save_mobile_data_off, settings.pause_downloads_while_playing, settings.pause_downloads_while_playing_on, settings.pause_downloads_while_playing_off, settings.low_storage_alert, settings.alert_low_storage, settings.alert_low_storage_detail, settings.page_storage, settings.keywords_wifi_only, settings.keywords_save_mobile_data, downloads.cached, downloads.cached_tab_note, downloads.cached_detail, downloads.empty_cached, downloads.empty_cache_limit, downloads.tab_cached, downloads.tab_cached_count, downloads.tab_downloaded, downloads.tab_downloaded_count, downloads.save_to_downloads (phone card; the grid's and the detail's keep theirs), player.wifi_only (the words moved to player.mobile_data_never), detail.round_stop (the round button says common.cancel), grid.stage_paused (the tile says grid.stage_paused_long). Still used: downloads.keep, downloads.downloaded, downloads.empty_downloaded, nav.profile (the television's and the desktop's picture; its text still lists Downloads: reword at the next sync).
- New text to translate (R2): settings.page_downloads_data, settings.mobile_data_title, settings.mobile_data_ask, settings.mobile_data_never, settings.mobile_data_allow, settings.mobile_data_ask_detail, settings.mobile_data_never_detail, settings.mobile_data_allow_detail, settings.keywords_mobile_data; downloads.auto_saved, downloads.auto_saved_note, downloads.cancelled, downloads.on_this_phone, downloads.on_this_phone_count, downloads.on_this_device, downloads.on_this_device_count, downloads.on_this_computer, downloads.on_this_computer_count; grid.stage_paused_long, grid.downloading_one_big; detail.rating_adults, detail.rating_everyone; player.mobile_data_never; web.downloads_title, web.downloads_body. Reused, already translated: nav.downloads, nav.you, downloads.keep, common.cancel, browse.undo, web.app_get.
- Dead after R2: tour.settings_stream_page, tour.settings_stream_page_tv (named the page "Storage and downloads"). New text to translate (R2): tour.settings_downloads_page, tour.settings_downloads_page_tv.
- Dead after TS "Why no poster?" (the detail pages' round "Show poster and overview" button is replaced by one line with the action "Turn on lookups"; the keys keep their text so every locale stays valid): metadata.turn_on, metadata.turn_on_detail, detail.round_show_info. New text to translate (TS): poster.why_fix, poster.why_name_none, poster.why_name_short, poster.why_nothing, poster.why_off, poster.why_off_action, poster.why_unsure, poster.why_wrong; series.pack_note, series.pack_span, series.pack_whole; detail.copy_best.
