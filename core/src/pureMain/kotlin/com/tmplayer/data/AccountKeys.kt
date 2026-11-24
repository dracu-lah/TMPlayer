package com.tmplayer.data

/**
 * Which settings belong to the Telegram account and which to the device (HX88). The account's are
 * kept apart per profile ([AccountScopedDataStore]); the device's are shared by every profile.
 *
 * Every name `SettingsStore` writes is in one of the lists ([ACCOUNT], [ACCOUNT_PREFIXES] or
 * [DEVICE]); AccountKeysTest fails on a new one that is in none. A name in none is the device's.
 */
object AccountKeys {

    /** The marker of a profile's own copy of an account key: `@<folder>:<name>`. */
    const val MARK = '@'

    /** Chats, favourites, sync, the cold start snapshots and the alerts' bookkeeping: this account's. */
    val ACCOUNT: Set<String> = setOf(
        "favorite_chats", "hidden_chats", "added_chats", "added_chats_mode",
        "resume_sync", "resume_sync_message", "resume_sync_keys", "resume_sync_file", "resume_sync_favourites",
        "resume_sync_at", "resume_sync_account", "resume_sync_sent_at", "resume_sync_status",
        "sync_channel", "sync_channel_title", "sync_channel_account", "sync_channel_block",
        "sync_file_chat", "sync_sent_chat", "sync_old_copy",
        "keep_place_card_seen", "keep_place_wrote_at",
        "last_chat", "kids_chats", "chat_snapshot", "account_snapshot", "recent_searches", "keep_next_shows",
        "new_video_seen", "new_video_offered", "episode_alert_seen", "episode_alert_told", "piece_gaps",
        "cached_video_ids", "cached_video", "download_queue", "download_queue_db", "downloads_migrated", "signed_in",
    )

    /**
     * Names built from a chat or a video: resume places and their offsets, a chat's topic, sort and
     * filters, downloads, the watch cache and the copy picked for a video.
     */
    val ACCOUNT_PREFIXES: List<String> = listOf(
        "resume_", "duration_", "meta_", "delay_", "topic_", "vsort_", "vfilter_", "vfacets_", "dl_", "wc_", "copy_pick_",
    )

    /**
     * The device's, named so a prefix above never takes them ("audio_passthrough" is not a show's
     * audio memory) and so the coverage test knows each was decided. Shows' track memories
     * (`audio_`, `text_`, `subs_`, `series_name_`) and speeds go by a show's name, not an id, and are
     * the player's: shared.
     */
    val DEVICE: Set<String> = setOf(
        "player_hint_seen", "first_video_tip_dismissed", "tv_browse_hint_seen", "hints_seen", "online_meta_notice_dismissed",
        "online_meta_invite_seen", "open_last_chat", "download_before_playing", "autoplay_next", "detail_first",
        "series_only_mine", "compact_nav", "downmix_stereo", "volume_boost", "night_mode", "even_volume",
        "player_volume", "skip_silence", "background_audio", "pip_on_leave", "mini_player", "rewind_on_resume_ms",
        "wifi_only_downloads", "pause_downloads_while_playing", "save_mobile_data", "mobile_data",
        "remove_after_watching", "low_storage_mode", "download_root", "download_root_pending", "lighter_pictures",
        "reduce_motion", "high_contrast_focus", "device_card_dismissed", "device_hint_shown", "trickplay",
        "watch_next", "tv_channels", "home_carousel", "home_continue", "home_recent", "home_starred_rows",
        "home_order", "crash_reports", "detailed_logs", "crash_ask_done", "min_size_bytes", "max_size_bytes",
        "chat_filter", "chat_sort", "hide_default_groups", "quality_habit", "decode_memory", "title_ledger",
        "feedback_draft", "heavy_ask_skip", "app_pin", "app_pin_failures", "app_pin_wait_left_ms",
        "app_pin_wait_stamp", "app_lock_on_open", "app_lock_biometric", "kids_lock", "setup", "tour", "onboarding_steps", "history_tab", "history_sort",
        "downloads_sort", "theme_choice", "pure_black", "accent", "accent_custom", "app_typeface", "high_contrast",
        "language", "language_announced", "last_seen_version", "dynamic_colour", "ui_scale", "ui_scale_tentative", "scan_then_jump", "video_scale",
        "remember_searches", "update_notify", "update_last_check", "update_skipped_version", "update_snoozed_until",
        "update_popup_shown", "update_test_versions", "update_auto_install", "update_auto_tried",
        "update_silent_refused", "update_installing", "support_first_seen", "support_snoozed_until", "support_never",
        "support_watches", "support_watch_ms", "support_asked", "support_supporter", "support_recent",
        "support_asked_at", "support_windows", "support_nudged_at", "support_star_asked_at", "support_starred",
        "new_video_alerts", "sponsor_reminders", "notify_downloads", "notify_episodes", "notify_low_storage",
        "notify_pieces", "notify_update", "low_storage_alert", "update_alerted_version", "screen_orientation",
        "player_tap_plays_pauses", "double_tap_ms", "hold_speed", "controls_timeout_ms", "gesture_seek",
        "gesture_brightness", "remember_brightness", "player_brightness", "gesture_volume", "player_haptics", "show_remaining_time", "subtitle_size",
        "subtitle_box", "subtitle_position", "subtitle_box_level", "subtitle_colour", "subtitle_size_percent",
        "subtitle_position_percent", "subtitle_colour_rgb", "subtitle_edge",
        "subtitle_face", "subtitle_bold", "video_decoder", "default_player", "external_hint_seen", "player_engine",
        "engine_switch_on_error", "suggest_vlc", "engine_history", "remedy_memory", "frame_rate_matching",
        "tunneled_playback", "audio_passthrough", "playback_stats", "play_chat_order", "play_all_skip_watched", "keep_repeat",
        "preferred_audio_language", "preferred_subtitle_language", "subtitle_mode", "developer_options",
        "audio_", "text_", "subs_", "series_name_",
        // One video's own subtitle look (WP-29, "This video only"): per video, this device.
        "substyle_",
    )

    /** Whether the setting [name] is the account's. */
    fun isAccount(name: String): Boolean {
        if (name in DEVICE) return false
        if (name in ACCOUNT) return true
        return ACCOUNT_PREFIXES.any { name.startsWith(it) }
    }

    /** `@<folder>:` */
    fun markOf(folder: String): String = "$MARK$folder:"
}
