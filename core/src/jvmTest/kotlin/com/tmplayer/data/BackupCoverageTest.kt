package com.tmplayer.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every preference [SettingsStore] writes is a decision about backups: it travels ([Backups.SETTINGS],
 * a [Backups.MEMORY] prefix, or a list of its own in the file), or it is left behind on purpose and
 * named below with the reason. A new key that is neither fails here, so nobody adds a setting that
 * silently stays on the old phone.
 */
class BackupCoverageTest {

    /** Keys that travel as fields of their own rather than as settings or memories. */
    private val ownFields = setOf(
        "favorite_chats", // favourites
        "recent_searches", // recentSearches
        "resume_", // resume points, without a download's path
    )

    /** Left behind on purpose. See the note on [Backups.SETTINGS] for the why of each kind. */
    private val leftBehind = setOf(
        // The account and cold start snapshots, and the device's own bookkeeping.
        "account_snapshot", "chat_snapshot", "last_chat", "last_seen_version", "language_announced",
        "downloads_migrated", "download_queue", "download_queue_db", "developer_options",
        // The brightness the player was left at: this device's screen (WP-27); the switch travels.
        "player_brightness",
        // One video's own subtitle look ("This video only", WP-29): per video, this device.
        "substyle_",
        // Keep my place on all my devices: the Home question was shown, and when this device last synced.
        "keep_place_card_seen", "keep_place_wrote_at",
        // Locks: a PIN's hash has no place in a mailed file, and a restore never undoes a lock. The fingerprint
        // switch is tied to this device's sensor (WP-41).
        "app_lock_on_open", "app_lock_biometric", "app_pin", "app_pin_failures", "app_pin_wait_left_ms", "app_pin_wait_stamp",
        "kids_chats", "kids_lock",
        // Whether this device was signed in: a session belongs to the device, and a restore on
        // another one must not read as "Telegram signed you out" there (HX23).
        "signed_in",
        // Consent is given on the device that sends, and so is asking for it (S1).
        "crash_reports", "crash_ask_done",
        // How much this device writes to its own log file: the device's, like the consent above.
        "detailed_logs",
        // Test versions are asked for on each device, never brought along by a restore.
        "update_test_versions",
        // Installing updates by itself is a choice about this device and its Android release, and
        // the rest is that install's own bookkeeping (HX5).
        "update_auto_install", "update_auto_tried", "update_silent_refused", "update_installing",
        // Scan then jump (WP-79): a choice about this device's TV stick, made on the one that needs it.
        "scan_then_jump",
        // An interface size being tried (WP-10): the size before it, until Keep. A change in progress on this device.
        "ui_scale_tentative",
        // Hints, walkthroughs and notices already seen.
        "device_card_dismissed", "device_hint_shown", "first_video_tip_dismissed", "online_meta_invite_seen",
        "online_meta_notice_dismissed", "player_hint_seen", "tv_browse_hint_seen", "setup", "tour",
        // The notifications and support steps after the first run: this device's, never carried (OnboardingSteps).
        "onboarding_steps",
        "hide_default_groups", "external_hint_seen",
        // The one-time hint registry (WP-68): hints are per device, so a restore neither silences nor repeats them.
        "hints_seen",
        // The retired mini player switch (T12): removed on first read, never carried.
        "mini_player",
        // The update check's and the support card's bookkeeping.
        "update_last_check", "update_popup_shown", "update_skipped_version", "update_snoozed_until",
        "support_asked", "support_first_seen", "support_never", "support_recent", "support_snoozed_until",
        "support_supporter", "support_watches", "support_watch_ms", "support_asked_at", "support_windows",
        "support_nudged_at",
        // Which video each starred chat had at the last new videos check, and whether the offer
        // after a star was made: this account on this device, started afresh on another.
        "new_video_seen", "new_video_offered",
        // The star rung's bookkeeping (CP76), with the support card's above.
        "support_star_asked_at", "support_starred",
        // What the CP76 notifications already said and where each chat check got to: this
        // device's, started afresh on another.
        "episode_alert_seen", "episode_alert_told", "low_storage_alert", "piece_gaps", "update_alerted_version",
        // Resume across devices: which Saved Messages message and file, what this device held after
        // its last sync and for which account, when it last sent a file, and the last result. This
        // device's bookkeeping: found again by its caption on a restored device.
        "resume_sync_message", "resume_sync_keys", "resume_sync_file", "resume_sync_favourites", "resume_sync_at",
        "resume_sync_account", "resume_sync_sent_at", "resume_sync_status",
        // Where the sync file and the backups are kept (HX87): the private channel the viewer chose
        // is a chat id of one account (and the backup is read out of it, so it cannot say where to
        // look), its title and account, why it is not used right now (a refused post, a missing
        // right), which chat the remembered file message and the last new file belong to, and where
        // an older copy was left. All of it names chats and messages of this account on this device
        // and is chosen again, or found again by its caption, on a restored one.
        "sync_channel", "sync_channel_title", "sync_channel_account", "sync_channel_block",
        "sync_file_chat", "sync_sent_chat", "sync_old_copy",
        // Which kinds of picture this device kept up with in software, and whose "may stutter"
        // question was turned off (HX2): about this device's CPU and decoders, not another's.
        "decode_memory", "heavy_ask_skip",
        // How each player engine did here (PR2), from this device's decoders and native library.
        "engine_history",
        // What the remedy ladder learned per class of video (RL6): this device's decoders too.
        "remedy_memory",
        // Title reports (N1): which pairs this device sent or was asked about, and the few waiting
        // for a connection. File names the viewer agreed to send from this device, never carried to another.
        "title_ledger",
        // A problem report or feature request being written or waiting to go (HX64): words typed
        // on this device, perhaps a way to reply, never carried to another.
        "feedback_draft",
        // Records of files on this device's disk, and what Telegram said about them.
        "cached_video", "cached_video_ids", "wc_", "dl_", "duration_", "meta_",
        // Android's chosen downloads folder (HX26): a path on this device's disk (an SD card or a
        // USB drive, by its mount point), which means nothing on another one. A restore keeps
        // using the default, internal folder until asked again, rather than carry a stale path.
        "download_root", "download_root_pending",
    )

    private val source = listOf(
        File("src/commonMain/kotlin/com/tmplayer/data/SettingsStore.kt"),
        File("core/src/commonMain/kotlin/com/tmplayer/data/SettingsStore.kt"),
    ).first { it.isFile }.readText()

    /** Every name the store writes: a fixed name whole, a name built from ids by its prefix. */
    private val keys: Set<String> = Regex("""PreferencesKey\("([^"]*)"""").findAll(source)
        .map { it.groupValues[1] }
        .map { name -> if (name.startsWith("\$CACHED_PREFIX")) "wc_" else name.substringBefore('$') }
        .filter { it.isNotEmpty() }
        .toSet()

    @Test
    fun `every settings key travels in a backup or is left behind on purpose`() {
        assertTrue("found too few keys to trust the scan: $keys", keys.size > 50)
        val unaccounted = keys.filterNot { key ->
            key in Backups.SETTINGS || key in ownFields || key in leftBehind ||
                Backups.MEMORY.keys.any { prefix -> key.startsWith(prefix) }
        }
        assertTrue("Add these to Backups.SETTINGS or MEMORY, or to leftBehind here with a reason: $unaccounted", unaccounted.isEmpty())
    }

    /**
     * The watched store's file holds more than the watched marks: Watch later ("l_") and the named
     * lists (HX43: "nm_" names, "n_" lines) travel as fields of their own; the removals ("xw_",
     * "xl_", "xn_") are left behind on purpose, being the sync file's bookkeeping, and a restore
     * adds and never takes away.
     */
    @Test
    fun `the watched file's lists travel in a backup`() {
        val (state, info) = NamedListRules.create(NamedLists(), "Tutorials", 1_000)!!
        val record = WatchLaterRecord(-100, 5, 7, "Clip", "clip.mp4", "Chat", 10, 60, "video/mp4", 2_000)
        val withLine = NamedListRules.add(state, info.id, record, 2_000).first
        val removed = NamedListRules.remove(NamedListRules.add(withLine, info.id, record.copy(messageId = 6), 3_000).first, info.id, "-100_6", 4_000)
        val raw = NamedListKeys.encode(removed)
        val travelled = NamedListKeys.LINE to NamedListKeys.INFO
        assertTrue(raw.keys.all { it.startsWith(travelled.first) || it.startsWith(travelled.second) || it.startsWith(NamedListKeys.REMOVED) })
        val backup = (Backups.decode(Backups.encode(Backups.collect(emptyMap(), raw, com.tmplayer.online.MetaSettings(), 5_000, "t"))) as BackupRead.Ok).backup
        assertTrue(backup.lists.list(info.id)?.name == "Tutorials")
        assertTrue(backup.lists.entries.map { it.record } == listOf(record.copy(addedAt = 2_000)))
        assertTrue("removals stay behind", backup.lists.removed.isEmpty())
    }

    @Test
    fun `nothing is both carried and left behind, and every listed name is still written`() {
        val both = Backups.SETTINGS.keys.intersect(leftBehind)
        assertTrue("both carried and left behind: $both", both.isEmpty())
        val stale = (Backups.SETTINGS.keys + leftBehind).filterNot { it in keys }
        assertTrue("listed but no longer written by SettingsStore: $stale", stale.isEmpty())
    }
}
