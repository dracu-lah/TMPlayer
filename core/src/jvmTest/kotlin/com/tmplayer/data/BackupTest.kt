package com.tmplayer.data

import com.tmplayer.online.MetaSettings
import com.tmplayer.online.MetadataStore
import com.tmplayer.player.SpeedMemory
import com.tmplayer.player.TrackPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.ZoneOffset

class BackupTest {

    private class Device(name: String) {
        val dir = Files.createTempDirectory("tm-backup-$name").toFile()
        val settings = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))
        val watched = WatchedStore(WatchedStore.openDataStore(dir.resolve(WatchedStore.FILE_NAME)))
        val metadata = MetadataStore(dir.resolve("metadata.properties"))
        val bookmarks = BookmarkStore(BookmarkStore.openDataStore(dir.resolve(BookmarkStore.FILE_NAME)))
        fun service(at: Long = NOW) = BackupService(settings, watched, metadata, "1.23.0", now = { at }, bookmarks = bookmarks)
    }

    private fun watchedRecord(messageId: Long, at: Long, manual: Boolean = false) = WatchedRecord(
        chatId = -100L, messageId = messageId, fileId = 7, title = "Ep $messageId", chatTitle = "Show",
        sizeBytes = 1_000, durationSec = 1_400, watchedAt = at, manual = manual,
    )

    private fun laterRecord(messageId: Long, at: Long) = WatchLaterRecord(
        chatId = -100L, messageId = messageId, fileId = 9, title = "Film $messageId", fileName = "film.mkv",
        chatTitle = "Films", sizeBytes = 2_000, durationSec = 5_400, mimeType = "video/x-matroska", addedAt = at,
    )

    private fun resumeLine(updatedAt: Long, title: String = "Film") = ResumeRecord.encode(
        fileId = 11, title = title, chatTitle = "Films", sizeBytes = 5_000, durationSec = 6_000,
        updatedAt = updatedAt, localPath = "/data/user/0/com.tmplayer/files/downloads/film.mkv",
        state = ResumeState(audioTrack = 1, audioLanguage = "eng", speed = 1.25f),
    )

    private fun backup(
        settings: Map<String, Any> = emptyMap(),
        favourites: Set<Long> = emptySet(),
        memory: Map<String, Any> = emptyMap(),
        resume: List<ResumeRecord> = emptyList(),
        watched: List<WatchedRecord> = emptyList(),
        searches: List<String> = emptyList(),
    ) = Backup(
        createdAt = NOW, appVersion = "1.23.0", settings = settings, metadata = emptyMap(),
        favourites = favourites, recentSearches = searches, memory = memory, resume = resume, watched = watched,
    )

    @Test
    fun `a backup restores onto an empty device and leaves the private parts behind`() = runBlocking {
        val old = Device("old")
        old.settings.setAutoplayNext(false)
        old.settings.rememberSpeed(SpeedMemory.Scopes(series = "keralacrimefiles"), 1.5f)
        old.settings.rememberSpeed(SpeedMemory.Scopes(chat = "-1003:7"), 1.75f)
        old.settings.setTrackPreferences(TrackPreferences.from("mal", "eng", null))
        old.watched.watchLater.add(laterRecord(8, at = 30))
        old.settings.setThemeChoice(ThemeChoice.Dark)
        old.settings.setPureBlack(true)
        old.settings.setAccent(Accent.Monochrome)
        old.settings.setCrashReports(true)
        old.settings.toggleFavorite(-1001L)
        old.settings.toggleFavorite(-1002L)
        old.settings.addRecentSearch("severance")
        old.settings.setTrackChoice("Kerala Crime Files", TrackChoice(audioLanguage = "mal", subtitlesOn = true))
        old.settings.rememberTopic(-1003L, 42)
        old.settings.saveResumePosition(-100L, 5L, 600_000L, 3_600_000L, resumeLine(updatedAt = 500))
        old.watched.markWatched(watchedRecord(1, at = 10, manual = true))
        old.metadata.update { it.copy(enabled = true, ownKey = "secret-tmdb-key", omdbKey = "secret-omdb-key", trailersInApp = false) }

        val text = old.service().export()
        assertFalse("no API key in the file", text.contains("secret-tmdb-key"))
        assertFalse("no OMDb key in the file either (WP-11: typed on the device, never carried)", text.contains("secret-omdb-key"))
        assertFalse("no device path in the file", text.contains("/data/user/0"))
        assertFalse("crash reports are a per device consent", text.contains("crash_reports"))

        val fresh = Device("fresh")
        val read = fresh.service().read(text) as BackupRead.Ok
        val plan = fresh.service().plan(read.backup)
        assertEquals(2, plan.favouritesAdded)
        assertEquals(1, plan.watchedAdded)
        assertEquals(1, plan.watchLaterAdded)
        assertEquals(1, plan.resumeAdded)
        assertEquals(1, plan.searchesAdded)
        fresh.service().restore(read.backup)

        assertFalse(fresh.settings.autoplayNextNow())
        assertEquals(1.5f, fresh.settings.openingSpeed(null, SpeedMemory.Scopes(series = "keralacrimefiles")))
        assertEquals(1.75f, fresh.settings.openingSpeed(null, SpeedMemory.Scopes(chat = "-1003:7")))
        assertEquals(old.settings.trackPreferencesNow(), fresh.settings.trackPreferencesNow())
        assertEquals(listOf(8L), fresh.watched.watchLater.items.first().map { it.messageId })
        assertEquals(ThemeChoice.Dark, fresh.settings.themeChoice.first())
        assertTrue(fresh.settings.pureBlack.first())
        assertEquals(Accent.Monochrome, fresh.settings.accent.first())
        assertFalse(fresh.settings.crashReportsNow())
        assertEquals(setOf(-1001L, -1002L), fresh.settings.favorites.first())
        assertEquals(listOf("severance"), fresh.settings.recentSearches.first())
        assertEquals("mal", fresh.settings.trackChoice("Kerala Crime Files").audioLanguage)
        assertEquals(42, fresh.settings.chosenTopic(-1003L))
        val resumed = fresh.settings.resumeRecord(-100L, 5L)!!
        assertEquals(600_000L, resumed.positionMs)
        assertEquals(1.25f, resumed.state?.speed)
        assertNull(resumed.localPath)
        assertTrue(fresh.watched.history.first().single().manual)
        assertEquals(MetaSettings(enabled = true, trailersInApp = false), fresh.metadata.now)
        assertEquals("a restore brings no key", "", fresh.metadata.now.omdbKey + fresh.metadata.now.ownKey)
    }

    @Test
    fun `save mobile data travels in a backup and outlives a sign out`() = runBlocking {
        val old = Device("save-data")
        old.settings.setSaveMobileData(true)
        val text = old.service().export()

        val fresh = Device("save-data-fresh")
        assertFalse(fresh.settings.saveMobileDataNow())
        fresh.service().restore((fresh.service().read(text) as BackupRead.Ok).backup)
        assertTrue(fresh.settings.saveMobileDataNow())

        // How sparingly this phone may use its data allowance is the phone's, not the account's.
        old.settings.clearEverything()
        assertTrue(old.settings.saveMobileData.first())
    }

    @Test
    fun `skip silence and the resume rewind travel, the locks stay on the device`() = runBlocking {
        val old = Device("player")
        old.settings.setSkipSilence(true)
        old.settings.setResumeRewindMs(ResumeRewind.CHOICES_MS.last())
        old.settings.setPin("1234")
        old.settings.setKidsLock(true)

        val text = old.service().export()
        assertFalse("no PIN hash in the file", text.contains("app_pin"))
        assertFalse("the kids lock is this device's", text.contains("kids_lock"))

        val fresh = Device("player-fresh")
        val read = fresh.service().read(text) as BackupRead.Ok
        fresh.service().restore(read.backup)
        assertTrue(fresh.settings.skipSilenceNow())
        assertEquals(ResumeRewind.CHOICES_MS.last(), fresh.settings.resumeRewindMs.first())
        assertFalse(fresh.settings.appLock.first().pinSet)
    }

    @Test
    fun `the file reads back as it was written`() {
        val original = backup(
            settings = mapOf("autoplay_next" to false, "hold_speed" to 1.75f, "language" to "ml", "min_size_bytes" to 5L),
            favourites = setOf(-1001L),
            memory = mapOf(
                "audio_keralacrimefiles" to "mal", "subs_keralacrimefiles" to true, "topic_-1003" to 42L,
                "speed_keralacrimefiles" to 1.25f, "speed_-1003:7" to 1.5f,
            ),
            resume = listOf(
                ResumeRecord(-100, 5, 11, "Film \u001F one", "Films", 5_000, 6_000, 600_000, 3_600_000, 500,
                    state = ResumeState(subtitleTrack = ResumeState.SUBTITLES_OFF)),
            ),
            watched = listOf(watchedRecord(1, at = 10, manual = true)),
            searches = listOf("severance", "the bear"),
        ).copy(watchLater = listOf(laterRecord(8, at = 30)), metadata = mapOf("enabled" to true, "skip_times" to false, "trailers_in_app" to false))
        val read = Backups.decode(Backups.encode(original)) as BackupRead.Ok
        assertEquals(original.copy(resume = original.resume.map { it.copy(title = "Film   one") }), read.backup)
    }

    @Test
    fun `a file from a newer TMPlayer is refused politely`() {
        val past = Backups.VERSION + 1
        val newer = Backups.encode(backup()).replace("\"version\": 1", "\"version\": $past")
        val read = Backups.decode(newer)
        assertEquals(BackupRead.TooNew(past), read)
        assertEquals(com.tmplayer.i18n.L.backupTooNew, BackupWords.refusal(read))
    }

    @Test
    fun `anything that is not a backup says so`() {
        assertEquals(BackupRead.NotABackup, Backups.decode("not json at all"))
        assertEquals(BackupRead.NotABackup, Backups.decode("{\"name\": \"something else\"}"))
        assertEquals(BackupRead.NotABackup, Backups.decode("{\"format\": \"tmplayer-backup\", \"version\": 0}"))
    }

    @Test
    fun `settings of the wrong kind or not on the list are skipped`() {
        val text = """
            {"format": "tmplayer-backup", "version": 1,
             "settings": {"autoplay_next": {"text": "yes"}, "download_queue": {"text": "x"}, "trickplay": {"bool": false}},
             "memory": {"audio_show": {"bool": true}, "text_show": {"text": "eng"}, "something_else": {"text": "x"}}}
        """.trimIndent()
        val backup = (Backups.decode(text) as BackupRead.Ok).backup
        assertEquals(mapOf<String, Any>("trickplay" to false), backup.settings)
        assertEquals(mapOf<String, Any>("text_show" to "eng"), backup.memory)
    }

    @Test
    fun `of two resume points the newer one is kept`() {
        val device = mapOf<String, Any>(
            "resume_-100_5" to 100_000L, "meta_-100_5" to resumeLine(updatedAt = 900),
            "resume_-100_6" to 100_000L, "meta_-100_6" to resumeLine(updatedAt = 100),
        )
        fun record(messageId: Long, at: Long) =
            ResumeRecord(-100, messageId, 11, "Film", "Films", 5_000, 6_000, 700_000, 3_600_000, at)
        val writes = Backups.settingsWrites(device, backup(resume = listOf(record(5, at = 500), record(6, at = 500), record(7, at = 1))))
        assertFalse("this device's is newer", writes.containsKey("resume_-100_5"))
        assertEquals(700_000L, writes["resume_-100_6"])
        assertEquals(700_000L, writes["resume_-100_7"])
    }

    @Test
    fun `lists are joined, memories already here stay, settings are replaced`() {
        val device = mapOf<String, Any>(
            "favorite_chats" to setOf("-1"),
            "recent_searches" to "dune",
            "audio_show" to "eng",
            "autoplay_next" to true,
        )
        val writes = Backups.settingsWrites(
            device,
            backup(
                settings = mapOf("autoplay_next" to false),
                favourites = setOf(-1L, -2L),
                memory = mapOf("audio_show" to "mal", "audio_other" to "hin"),
                searches = listOf("severance", "DUNE"),
            ),
        )
        assertEquals(setOf("-1", "-2"), writes["favorite_chats"])
        assertEquals("dune\nseverance", writes["recent_searches"])
        assertFalse(writes.containsKey("audio_show"))
        assertEquals("hin", writes["audio_other"])
        assertEquals(false, writes["autoplay_next"])
    }

    @Test
    fun `a watched mark keeps the newer time and stays manual once manual`() {
        val mine = watchedRecord(1, at = 50, manual = true)
        val device = mapOf<String, Any>("w_${mine.key}" to mine.encode())
        val writes = Backups.watchedWrites(device, backup(watched = listOf(watchedRecord(1, at = 90), watchedRecord(2, at = 5))))
        val kept = WatchedRecord.decode(mine.key, writes["w_${mine.key}"] as String)!!
        assertEquals(90L, kept.watchedAt)
        assertTrue(kept.manual)
        assertTrue(writes.containsKey("w_${watchedRecord(2, at = 5).key}"))
        assertTrue(Backups.watchedWrites(device, backup(watched = listOf(watchedRecord(1, at = 10)))).isEmpty())
    }

    @Test
    fun `watch later is joined, keeping the later added`() {
        val mine = laterRecord(1, at = 50)
        val device = mapOf<String, Any>("l_${mine.key}" to mine.encode())
        val writes = Backups.watchedWrites(device, backup().copy(watchLater = listOf(laterRecord(1, at = 20), laterRecord(2, at = 5))))
        assertFalse("this device's was added later", writes.containsKey("l_${mine.key}"))
        assertTrue(writes.containsKey("l_${laterRecord(2, at = 5).key}"))
        val newer = Backups.watchedWrites(device, backup().copy(watchLater = listOf(laterRecord(1, at = 90))))
        assertEquals(90L, WatchLaterRecord.decode(mine.key, newer["l_${mine.key}"] as String)!!.addedAt)
    }

    @Test
    fun `bookmarks travel, joined by video and moment, and a note here stays`() = runBlocking {
        val old = Device("bm-old")
        old.bookmarks.add(-100L, 1L, positionMs = 60_000, note = "Lemma", now = 10)
        old.bookmarks.add(-100L, 1L, positionMs = 120_000, note = "Proof", now = 20)
        old.bookmarks.add(-200L, 5L, positionMs = 5_000, now = 30)
        val text = old.service().export()

        val fresh = Device("bm-new")
        fresh.bookmarks.add(-100L, 1L, positionMs = 60_400, note = "Mine", now = 40)
        val backup = (fresh.service().read(text) as BackupRead.Ok).backup
        assertEquals(3, backup.bookmarks.size)
        val plan = fresh.service().plan(backup)
        assertEquals(2, plan.bookmarksAdded)
        assertTrue(BackupWords.restoreMessage(plan).contains(com.tmplayer.i18n.L.backupRestoreBookmarks(2)))

        fresh.service().restore(backup)
        assertEquals(listOf("Mine", "Proof"), fresh.bookmarks.bookmarksNow(-100L, 1L).map { it.note })
        assertEquals(1, fresh.bookmarks.bookmarksNow(-200L, 5L).size)
        // Restoring the same file again adds nothing.
        assertEquals(0, fresh.service().plan(backup).bookmarksAdded)
        fresh.service().restore(backup)
        assertEquals(2, fresh.bookmarks.bookmarksNow(-100L, 1L).size)
    }

    @Test
    fun `the plan counts nothing when the device already has it all`() {
        val plan = Backups.plan(backup(settings = mapOf("autoplay_next" to true)), mapOf("autoplay_next" to true), emptyMap(), MetaSettings())
        assertTrue(plan.nothing)
        assertEquals(com.tmplayer.i18n.L.backupRestoreNothing, BackupWords.restoreMessage(plan.copy(backup = plan.backup.copy(createdAt = 0))))
    }

    @Test
    fun `the file is named for the day`() {
        assertEquals("tmplayer-backup-2026-10-13.json", Backups.fileName(NOW, ZoneOffset.UTC))
    }

    private companion object {
        /** 2026-10-13 12:00 UTC. */
        const val NOW = 1_791_892_800_000L
    }
}
