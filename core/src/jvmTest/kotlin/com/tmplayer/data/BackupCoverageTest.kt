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
        "downloads_migrated", "download_queue", "developer_options",
        // Locks: a PIN's hash has no place in a mailed file, and a restore never undoes a lock.
        "app_lock_on_open", "app_pin", "app_pin_failures", "app_pin_wait_left_ms", "app_pin_wait_stamp",
        "kids_chats", "kids_lock",
        // Consent is given on the device that sends.
        "crash_reports",
        // Hints, walkthroughs and notices already seen.
        "device_card_dismissed", "device_hint_shown", "first_video_tip_dismissed", "online_meta_invite_seen",
        "online_meta_notice_dismissed", "player_hint_seen", "tv_browse_hint_seen", "setup", "tour",
        "hide_default_groups",
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
        // Resume across devices: which Saved Messages message, and what this device last wrote to
        // it. Found again by its tag on a restored device.
        "resume_sync_message", "resume_sync_keys",
        // Which kinds of picture this device kept up with in software, and whose "may stutter"
        // question was turned off (HX2): about this device's CPU and decoders, not another's.
        "decode_memory", "heavy_ask_skip",
        // Records of files on this device's disk, and what Telegram said about them.
        "cached_video", "cached_video_ids", "wc_", "dl_", "duration_", "meta_",
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

    @Test
    fun `nothing is both carried and left behind, and every listed name is still written`() {
        val both = Backups.SETTINGS.keys.intersect(leftBehind)
        assertTrue("both carried and left behind: $both", both.isEmpty())
        val stale = (Backups.SETTINGS.keys + leftBehind).filterNot { it in keys }
        assertTrue("listed but no longer written by SettingsStore: $stale", stale.isEmpty())
    }
}
