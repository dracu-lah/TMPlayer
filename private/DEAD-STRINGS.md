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
