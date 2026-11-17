package com.tmplayer.data

import com.tmplayer.data.DeviceForm.Desktop
import com.tmplayer.data.DeviceForm.Phone
import com.tmplayer.data.DeviceForm.Tv

/**
 * Settings' pages, grouped by what the viewer is trying to do (U20, 2026-11-15), in the order the
 * list shows them on every platform. The phone's `SettingsPage` and the desktop's
 * `SettingsCategory` carry the same names in the same order (SettingsMapTest), each leaving out
 * the pages it has not got: [TvHome] is a television's. The player gestures (a phone's) and the
 * mouse (a desktop's) are rows of [Playback], and Home's rows are on [Library], whose page is
 * called Home and library (Raju pass 2, 11.1): one page per job, not three for "what plays" and
 * two for "what shows".
 *
 * [title] and [summary] are catalog keys: the page's name and its one line in the list when the
 * list has no live value to show there instead (the free space, the lock's state, an update).
 */
enum class SettingsGroup(val title: String, val summary: String) {
    Playback("settings.playback", "settings.group_playback_summary"),
    Subtitles("settings.page_subtitles_audio", "settings.group_subtitles_audio_summary"),
    Storage("settings.page_downloads_data", "settings.group_storage_summary"),
    Notifications("settings.page_notifications", "settings.page_notifications_summary_short"),
    Appearance("settings.page_appearance", "settings.page_appearance_summary"),
    Account("settings.page_privacy_lock", "settings.group_privacy_lock_summary"),
    Library("settings.page_home_library", "settings.page_home_library_summary"),
    TvHome("settings.page_tv_home", "settings.page_tv_home_summary"),
    About("settings.page_about", "settings.group_about_summary"),
    SetupChoices("settings.redo_setup_choices", "setup.choices_summary"),
    ;

    companion object {
        fun of(name: String): SettingsGroup? = entries.firstOrNull { it.name == name }
    }
}

/**
 * What a row needs before it is drawn, beyond the form: a build that has the feature, a device
 * that can do it, or something the viewer has (favourites to clear, a PIN to change). A row with
 * a need is hidden, never greyed, where the need is not met, and Settings' search leaves it out
 * there too.
 */
enum class SettingsNeed {
    /** libVLC in this build and loading here, or VLC already the stored choice (PR2). */
    Vlc,

    /** Playback's Advanced group opened; search opens it on the way to the row. */
    Advanced,

    /** Android 12 or later (wallpaper colours, frame-rate matching). */
    Android12,

    /** Android's picture in picture. */
    PictureInPicture,

    /** A build with an OpenSubtitles key. */
    OnlineSubtitles,

    /** A build with online posters and details. */
    OnlineMetadata,

    /** A build that updates itself (not a store's). */
    Updates,

    /** Installing an update without asking (Android 12 and later, a build that updates itself). */
    AutoInstall,

    /** A build with a crash report address. */
    CrashReports,

    /** A build with a log inbox (L5). */
    LogInbox,

    /** The launcher has a Play next row. */
    LauncherPlayNext,

    /** The launcher has channels. */
    LauncherChannels,

    /** Shown for what the viewer has or has switched on: never searched for. */
    State,
}

/**
 * One row of Settings: [id] is its job, the same on every form that has it. [title] and [details]
 * are catalog keys, the title as the row says it (or [desktopTitle] where the desktop words it
 * apart) and the lines it can show under it, which search reads as well. [drawnAs] are other
 * titles the same row takes as its state changes (Set a PIN becomes Change the PIN).
 */
class SettingsRow(
    val id: String,
    val group: SettingsGroup,
    val title: String,
    val forms: Set<DeviceForm>,
    val details: List<String> = emptyList(),
    val need: SettingsNeed? = null,
    val desktopTitle: String? = null,
    val drawnAs: List<String> = emptyList(),
    /**
     * A catalog key whose text is the other words a person types for this row ("bigger", "font",
     * "log out"): the search matches meanings, not only titles (Raju 11.2). In the viewer's
     * language, so each locale lists its own; a locale without the key falls back to English.
     */
    val keywords: String? = null,
) {
    fun titleOn(form: DeviceForm): String = if (form == Desktop) desktopTitle ?: title else title

    /** Every title key the row can be drawn with on [form]. */
    fun titlesOn(form: DeviceForm): List<String> = listOf(titleOn(form)) + drawnAs

    override fun toString(): String = "SettingsRow($id in $group)" // i18n-ok: debug toString
}

/**
 * Every row of Settings on the phone, the television and the desktop, by page, in each page's
 * order (U20). The one map the search box reads, the deep link test checks ([SettingsLinks]) and
 * the screen tests hold the pages to: a row drawn on a page and missing here, or listed here and
 * missing there, fails a test, so the map cannot drift from what the viewer sees.
 *
 * Each job has one home: a row two pages wanted is on one of them, and the other links to it.
 * The web app keeps its own shorter Settings (docs/settings-audit.md).
 */
object SettingsMap {
    private val PTD = setOf(Phone, Tv, Desktop)
    private val PT = setOf(Phone, Tv)
    private val P = setOf(Phone)
    private val T = setOf(Tv)
    private val D = setOf(Desktop)
    private val PD = setOf(Phone, Desktop)

    private fun MutableList<SettingsRow>.row(
        id: String,
        group: SettingsGroup,
        title: String,
        forms: Set<DeviceForm>,
        details: List<String> = emptyList(),
        need: SettingsNeed? = null,
        desktopTitle: String? = null,
        drawnAs: List<String> = emptyList(),
        keywords: Boolean = false,
    ) {
        add(SettingsRow(id, group, title, forms, details, need, desktopTitle, drawnAs, if (keywords) "settings.keywords_$id" else null))
    }

    private fun onOff(key: String) = listOf("${key}_on", "${key}_off")

    val rows: List<SettingsRow> = buildList {
        val g = SettingsGroup.Playback
        row("autoplay", g, "settings.autoplay", PTD, onOff("settings.autoplay"))
        row("play_chat_order", g, "settings.play_chat_order", PTD, onOff("settings.play_chat_order"))
        row("keep_repeat", g, "settings.keep_repeat", PTD, onOff("settings.keep_repeat"))
        row("default_player", g, "settings.default_player", PT, listOf("settings.default_player_ask"))
        row("player_engine", g, "settings.engine", PT, listOf("settings.engine_automatic_detail", "settings.engine_vlc_detail"), SettingsNeed.Vlc)
        row("engine_switch", g, "settings.engine_switch", PT, onOff("settings.engine_switch"), SettingsNeed.Vlc)
        row("decoder", g, "settings.decoder", PT)
        row("detail_first", g, "settings.detail_first", PTD, onOff("settings.detail_first") + onOff("settings.detail_first_click"))
        row("background_audio", g, "settings.background_audio", P, onOff("settings.background_audio"))
        row("picture_in_picture", g, "settings.picture_in_picture_leave", P, onOff("settings.picture_in_picture_leave"), SettingsNeed.PictureInPicture)
        row("controls_timeout", g, "settings.controls_timeout", PTD, listOf("settings.controls_timeout_detail"), desktopTitle = "settings.hide_controls")
        row("resume_rewind", g, "settings.resume_rewind", PTD, listOf("settings.resume_rewind_detail"))
        // For a TV that discovery never finds: opens the same address dialog as the Cast picker's last line. A television has no casting.
        row("add_tv", g, "cast.add_entry", PD, listOf("settings.add_tv_detail"))
        row("advanced", g, "settings.advanced", PT, listOf("settings.advanced_detail"))
        row("frame_rate_matching", g, "settings.frame_rate_matching", T, onOff("settings.frame_rate_matching"), SettingsNeed.Advanced)
        row("tunneled", g, "settings.tunneled", T, listOf("settings.tunneled_detail"), SettingsNeed.Advanced)
        row("playback_stats", g, "settings.playback_stats", PT, onOff("settings.playback_stats"), SettingsNeed.Advanced)

        // Touch only (a remote has a button for each), then the mouse where the phone has these: the player's
        // own controls, after the rows about what plays. The decoder choice on a desktop comes last, under Advanced.
        val m = g
        row("haptics", m, "settings.haptics", P, listOf("settings.haptics_detail"))
        row("tap_plays", m, "settings.tap_plays", P, onOff("settings.tap_plays"))
        row("double_tap", m, "settings.double_tap", P, listOf("settings.double_tap_detail"))
        row("hold_speed", m, "settings.hold_speed", P, listOf("settings.hold_speed_detail"))
        row("seek_gesture", m, "settings.seek_gesture", P, listOf("settings.seek_gesture_detail"))
        row("brightness_gesture", m, "settings.brightness_gesture", P, listOf("settings.brightness_gesture_detail"))
        row("volume_gesture", m, "settings.volume_gesture", P, listOf("settings.volume_gesture_detail"))

        val mouse = g
        row("mouse_hold_speed", mouse, "settings.hold_speed", D, listOf("settings.hold_speed_detail_desktop"))
        row("wheel_seeks", mouse, "settings.wheel_seeks", D, onOff("settings.wheel_seeks"))
        row("software_decoding", g, "settings.software_decoding", D, listOf("settings.software_decoding_detail"))

        val sub = SettingsGroup.Subtitles
        row("subtitle_style", sub, "settings.subtitle_style", PTD)
        row("subtitle_mode", sub, "settings.subtitle_mode", PTD)
        row("subtitle_language", sub, "settings.subtitle_language", PTD, listOf("settings.track_languages_note"))
        row("audio_language", sub, "settings.audio_language", PTD, listOf("settings.track_languages_note"))
        row("learnt_languages", sub, "settings.learnt_languages", PTD, listOf("settings.learnt_languages_empty"))
        row("downmix", sub, "settings.downmix", PTD, onOff("settings.downmix"))
        row("even_volume", sub, "player.even_volume", PTD, onOff("settings.even_volume"))
        row("online_account", sub, "online.sign_in", PTD, need = SettingsNeed.OnlineSubtitles, drawnAs = listOf("online.sign_out", "online.title"))
        row("find_subtitles", sub, "online.suggest_toggle", PT, onOff("online.suggest"), SettingsNeed.OnlineSubtitles)
        row("machine_subtitles", sub, "online.machine_toggle", PTD, onOff("online.machine"), SettingsNeed.OnlineSubtitles)
        row("subdl_key", sub, "online.subdl", PTD, need = SettingsNeed.OnlineSubtitles)
        row("msone", sub, "msone.title", PTD, need = SettingsNeed.State)
        row("online_cache", sub, "online.clear_cache", PTD, need = SettingsNeed.OnlineSubtitles)

        val st = SettingsGroup.Storage
        row("free_up_space", st, "downloads.free_up_space", PTD, keywords = true)
        row("download_location", st, "storage.location", PTD)
        row("cache_limit", st, "storage.cache_limit", D)
        row("download_first", st, "settings.download_first", PTD, onOff("settings.download_first"))
        row("remove_after_watching", st, "downloads.remove_after_watching", PTD, listOf("downloads.remove_after_watching_body"), keywords = true)
        row("low_storage", st, "settings.stream_title", PTD, listOf("settings.stream_keep_detail", "settings.stream_save_detail"), keywords = true)
        // The twin switches Wi-Fi only and Save mobile data became one question (R2); the low space alert and
        // pausing downloads while playing are always on, so neither has a row.
        row("mobile_data", st, "settings.mobile_data_title", P, listOf("settings.mobile_data_ask_detail", "settings.mobile_data_never_detail", "settings.mobile_data_allow_detail"), keywords = true)
        row("scan_folder", st, "storage.scan", D)

        val n = SettingsGroup.Notifications
        row("alert_downloads", n, "settings.alert_downloads", PD, listOf("settings.alert_downloads_detail"))
        row("alert_episodes", n, "settings.alert_episodes", PD, listOf("settings.alert_episodes_detail"))
        row("alert_new_videos", n, "settings.new_videos", PD, listOf("settings.new_videos_detail"))
        row("alert_pieces", n, "settings.alert_pieces", PD, listOf("settings.alert_pieces_detail"))
        row("alert_update", n, "settings.alert_update", PD, listOf("settings.alert_update_detail"), SettingsNeed.Updates)
        row("sponsor_reminders", n, "settings.sponsor_reminders", PD, need = SettingsNeed.State)

        val a = SettingsGroup.Appearance
        // The desktop titles its live preview card; the phone's and the television's have none.
        row("appearance_preview", a, "settings.appearance_preview", D)
        row("theme", a, "settings.theme", PTD)
        row("pure_black", a, "settings.pure_black", PTD, listOf("theme.black_detail"))
        row("accent", a, "settings.accent", PTD, listOf("settings.accent_detail"))
        row("typeface", a, "settings.typeface", PTD, listOf("settings.typeface_detail"), keywords = true)
        row("high_contrast", a, "settings.high_contrast", PTD, listOf("settings.high_contrast_detail"))
        row("language", a, "settings.language", PTD)
        row("layout", a, "interface.layout", PTD, desktopTitle = "settings.layout")
        row("interface_size", a, "interface.size", PTD, listOf("interface.size_detail"), keywords = true)
        row("lighter_pictures", a, "settings.lighter_pictures", PT, onOff("settings.lighter_pictures"))
        row("reduce_motion", a, "settings.reduce_motion", PT, onOff("settings.reduce_motion"))
        row("high_contrast_focus", a, "settings.high_contrast_focus", T, listOf("settings.high_contrast_focus_detail"))
        row("start_fullscreen", a, "settings.start_fullscreen", D, onOff("settings.start_fullscreen"))
        row("compact_nav", a, "settings.compact_nav", D)
        row("system_title_bar", a, "settings.system_title_bar", D, need = SettingsNeed.State)
        row("appearance_defaults", a, "settings.appearance_defaults", PTD, listOf("settings.appearance_defaults_detail"))
        row("wallpaper_colours", a, "settings.wallpaper_colours", P, onOff("settings.wallpaper_colours"), SettingsNeed.Android12)

        val lock = SettingsGroup.Account
        row("pin", lock, "lock.set_pin", PTD, listOf("lock.set_pin_detail"), drawnAs = listOf("lock.change_pin"))
        row("lock_on_open", lock, "lock.on_open", PTD, listOf("lock.on_open_detail"), SettingsNeed.State)
        row("kids_lock", lock, "lock.kids", PTD)
        row("allowed_chats", lock, "lock.allowed_chats", PTD)
        row("remove_pin", lock, "lock.remove_pin", PTD, listOf("lock.remove_pin_detail"), SettingsNeed.State)
        row("privacy_page", lock, "about.privacy", PTD, listOf("settings.privacy_detail"), desktopTitle = "settings.privacy")
        row("crash_reports", lock, "settings.crash_reports", PTD, onOff("settings.crash_reports"), SettingsNeed.CrashReports)
        row("devices", lock, "settings.devices", PTD, listOf("settings.devices_detail"))
        row("resume_sync", lock, "syncplace.switch", PTD, onOff("syncplace.switch"))
        row("sign_out", lock, "settings.sign_out", PTD, keywords = true)

        val h = SettingsGroup.Library
        row("home_carousel", h, "settings.home_carousel", PTD, onOff("settings.home_carousel"))
        row("home_continue", h, "settings.home_continue", PTD, onOff("settings.home_continue"))
        row("home_recent", h, "settings.home_recent", PTD, onOff("settings.home_recent"))
        row("home_starred_rows", h, "settings.home_starred_rows", PTD, onOff("settings.home_starred_rows"))
        row("home_order", h, "settings.home_order", PTD, listOf("settings.home_order_hint", "settings.home_order_hint_tv"))

        val lib = SettingsGroup.Library
        row("open_last_chat", lib, "settings.open_last_chat", PTD)
        row("forget_last_chat", lib, "settings.forget_last_chat", PTD, need = SettingsNeed.State)
        row("remember_searches", lib, "settings.remember_searches", PTD, onOff("settings.remember_searches"))
        row("resume_sync_delete", lib, "settings.resume_sync_delete", PTD, need = SettingsNeed.State)
        row("chosen_chats", lib, "picker.row_title", PTD)
        row("clear_favourites", lib, "settings.clear_favourites", PTD, need = SettingsNeed.State)
        row("clear_history", lib, "browse.clear_history", PTD, listOf("browse.clear_history_detail"), SettingsNeed.State)
        row("posters", lib, "metadata.toggle", PTD, onOff("metadata.toggle"), SettingsNeed.OnlineMetadata)
        row("skip_times", lib, "metadata.skip_times", PTD, listOf("metadata.skip_times_privacy"), SettingsNeed.OnlineMetadata)
        // One line per kind of skip, drawn only while the switch above is on (T10).
        row("skip_mode_recap", lib, "settings.skip_recap", PTD, listOf("settings.skip_mode_note"), SettingsNeed.State)
        row("skip_mode_intro", lib, "settings.skip_intro", PTD, listOf("settings.skip_mode_note"), SettingsNeed.State)
        row("skip_mode_credits", lib, "settings.skip_credits", PTD, listOf("settings.skip_mode_note"), SettingsNeed.State)
        row("skip_mode_preview", lib, "settings.skip_preview", PTD, listOf("settings.skip_mode_note"), SettingsNeed.State)
        row("tmdb_key", lib, "metadata.key", PTD, need = SettingsNeed.OnlineMetadata)
        row("omdb_key", lib, "metadata.omdb_key", PTD, need = SettingsNeed.OnlineMetadata)
        row("details_cache", lib, "metadata.clear_cache", PTD, need = SettingsNeed.OnlineMetadata)

        val tv = SettingsGroup.TvHome
        row("watch_next", tv, "settings.watch_next", T, onOff("settings.watch_next"), SettingsNeed.LauncherPlayNext)
        row("tv_channels", tv, "settings.tv_channels", T, onOff("settings.tv_channels"), SettingsNeed.LauncherChannels)

        val ab = SettingsGroup.About
        row("check_updates", ab, "settings.check_updates", PTD, need = SettingsNeed.Updates)
        row("update_notify", ab, "settings.update_notify", PTD, listOf("settings.update_notify_detail"), SettingsNeed.Updates)
        row("test_versions", ab, "settings.test_versions", PTD, onOff("settings.test_versions"), SettingsNeed.Updates)
        row("auto_install", ab, "settings.auto_install", PT, onOff("settings.auto_install"), SettingsNeed.AutoInstall)
        row("back_up", ab, "backup.back_up", PTD, listOf("backup.back_up_detail"))
        row("restore", ab, "backup.restore", PTD, listOf("backup.restore_detail"))
        row("proxy", ab, "proxy.title", PTD, listOf("proxy.settings_detail_off"))
        row("tour", ab, "settings.tour", PTD, listOf("settings.tour_body"))
        row("whats_new", ab, "settings.whats_new", PTD)
        row("changelog", ab, "settings.changelog", PTD, listOf("settings.changelog_detail"))
        row("report_problem", ab, "settings.report_problem", PTD, listOf("settings.report_problem_detail"))
        row("save_logs", ab, "logs.save", PTD, listOf("logs.save_detail"))
        row("detailed_logs", ab, "logs.detailed", PT, onOff("logs.detailed"))
        row("playback_test", ab, "playbacktest.title", PT, listOf("playbacktest.detail"))
        row("send_logs", ab, "logs.send_dev", PT, listOf("logs.send_dev_detail"), SettingsNeed.LogInbox)
        row("name_report", ab, "logs.name_report", PTD, listOf("logs.name_report_detail"), SettingsNeed.State)
        row("lawful_use", ab, "about.lawful_use", PTD, listOf("about.lawful_use_detail"), desktopTitle = "settings.lawful_use")
        row("support", ab, "about.support_title", PTD, listOf("settings.support_detail"), SettingsNeed.State, drawnAs = listOf("about.support_thanks_title"))
        row("about", ab, "settings.about", PTD)
    }

    private val byId: Map<String, SettingsRow> = rows.associateBy { it.id }

    fun row(id: String): SettingsRow? = byId[id]

    /** The rows [form] has, in the order its pages draw them, the pages in [SettingsGroup]'s order. */
    fun on(form: DeviceForm): List<SettingsRow> = rows.filter { form in it.forms }.sortedBy { it.group.ordinal }

    /** The rows on [group] for [form]. */
    fun on(form: DeviceForm, group: SettingsGroup): List<SettingsRow> = rows.filter { it.group == group && form in it.forms }
}
