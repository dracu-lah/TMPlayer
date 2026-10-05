package com.tmplayer.data

import com.tmplayer.player.SyncDelays
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The Kodi resume rules ([ResumeRules]), the [ResumeState] written beside a position, and both
 * through the real [SettingsStore], the way a player's ten second heartbeat writes them.
 */
class ResumeRulesTest {

    private val film = 2 * 60 * 60 * 1000L // two hours

    @Test
    fun `nothing is kept in the first three minutes`() {
        assertFalse(ResumeRules.keeps(0L, film))
        assertFalse(ResumeRules.keeps(179_999L, film))
        assertTrue(ResumeRules.keeps(180_000L, film))
        // A stream that has not said how long it is yet: the start rule alone.
        assertFalse(ResumeRules.keeps(120_000L, 0L))
        assertTrue(ResumeRules.keeps(30 * 60_000L, 0L))
    }

    @Test
    fun `nothing is kept in the last eight percent`() {
        val credits = (film * 0.92).toLong()
        assertTrue(ResumeRules.keeps(credits - 1, film))
        assertFalse(ResumeRules.keeps(credits, film))
        assertFalse(ResumeRules.keeps(film, film))
    }

    @Test
    fun `a short clip never gets a resume point`() {
        // Two and a half minutes: under the start rule all the way to the end.
        val clip = 150_000L
        for (at in 0L..clip step 10_000L) assertFalse("at $at", ResumeRules.keeps(at, clip))
    }

    @Test
    fun `watched from ninety percent`() {
        val ninety = (film * 0.9).toLong()
        assertFalse(ResumeRules.watched(ninety - 1, film))
        assertTrue(ResumeRules.watched(ninety, film))
        assertTrue(ResumeRules.watched(film, film))
        // Without a length nothing can be called watched.
        assertFalse(ResumeRules.watched(film, 0L))
        assertFalse(ResumeRules.watched(film, -1L))
    }

    @Test
    fun `a forty minute episode keeps between three and about thirty seven minutes`() {
        val episode = 40 * 60_000L
        assertTrue(ResumeRules.keeps(3 * 60_000L, episode))
        assertTrue(ResumeRules.keeps(36 * 60_000L, episode))
        assertFalse(ResumeRules.keeps(37 * 60_000L, episode))
        assertTrue(ResumeRules.watched(36 * 60_000L, episode))
    }

    @Test
    fun `the state round trips`() {
        val state = ResumeState(audioTrack = 1, audioLanguage = "eng", subtitleTrack = 2, subtitleLanguage = "mal", speed = 1.25f)
        assertEquals(state, ResumeState.decode(state.encode()))
        val off = ResumeState(audioTrack = 0, subtitleTrack = ResumeState.SUBTITLES_OFF, speed = 1f)
        assertEquals(off, ResumeState.decode(off.encode()))
        assertTrue(ResumeState.decode(off.encode())!!.subtitlesOff)
    }

    @Test
    fun `a state that cannot be read is dropped, not half used`() {
        assertNull(ResumeState.decode(""))
        assertNull(ResumeState.decode("1,eng"))
        assertNull(ResumeState.decode(",,,,"))
        // A broken speed is left out; the tracks that did read are kept.
        assertEquals(ResumeState(audioTrack = 2), ResumeState.decode("2,,,,fast"))
        assertNull(ResumeState.decode("-3,,-7,,0"))
    }

    @Test
    fun `the same track means the same language, or none to compare`() {
        assertTrue(ResumeState.sameTrack("eng", "eng"))
        assertTrue(ResumeState.sameTrack("ENG", "eng"))
        assertTrue(ResumeState.sameTrack(null, "eng"))
        assertTrue(ResumeState.sameTrack("eng", null))
        assertFalse(ResumeState.sameTrack("eng", "hin"))
    }

    @Test
    fun `a line with a state still decodes, and a line without one is what older builds wrote`() {
        val state = ResumeState(audioTrack = 1, subtitleTrack = ResumeState.SUBTITLES_OFF, speed = 1.5f)
        val line = ResumeRecord.encode(42, "Night Train.mkv", "Film Club", 10L, 600, 5L, state = state)
        val record = ResumeRecord.decode("1_2", line, 240_000L, 600_000L)!!
        assertEquals(state, record.state)
        assertNull(record.localPath)
        assertEquals(5L, ResumeRecord.updatedAtOf(line))

        val withPath = ResumeRecord.encode(42, "Night Train.mkv", "Film Club", 10L, 600, 5L, "/d/x.mkv", state)
        val both = ResumeRecord.decode("1_2", withPath, 0L, 0L)!!
        assertEquals("/d/x.mkv", both.localPath)
        assertEquals(state, both.state)

        val plain = ResumeRecord.encode(42, "Night Train.mkv", "Film Club", 10L, 600, 5L)
        assertEquals(6, plain.split('\u001F').size)
        assertNull(ResumeRecord.decode("1_2", plain, 0L, 0L)!!.state)
    }

    @Test
    fun `the heartbeat writes position, tracks, speed and delays together, and the rules decide what stays`() = runBlocking {
        val dir = Files.createTempDirectory("tm-resume").toFile()
        val settings = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))
        val episode = 40 * 60_000L
        val state = ResumeState(audioTrack = 1, audioLanguage = "eng", subtitleTrack = 0, subtitleLanguage = "eng", speed = 1.25f)
        fun line(s: ResumeState?) = ResumeRecord.encode(3, "Night Train", "Film Club", 1L, 2400, System.currentTimeMillis(), state = s)

        // The offsets are written per file the moment they change; the beat writes the rest.
        settings.setSyncDelays(5, 9, SyncDelays(subtitleMs = 300, audioMs = -100))
        // Two minutes in: sampling, nothing written.
        settings.saveResumePosition(5, 9, 120_000L, episode, line(state))
        assertNull(settings.resumeRecord(5, 9))

        // Ten minutes in: everything back as it was.
        settings.saveResumePosition(5, 9, 600_000L, episode, line(state))
        val saved = settings.resumeRecord(5, 9)!!
        assertEquals(600_000L, saved.positionMs)
        assertEquals(episode, saved.durationMs)
        assertEquals(state, saved.state)
        assertEquals(SyncDelays(300, -100), settings.syncDelays(5, 9))
        assertEquals(1, settings.continueWatching.first().size)

        // The next beat, ten seconds on and at a new speed, replaces it.
        settings.saveResumePosition(5, 9, 610_000L, episode, line(state.copy(speed = 2f)))
        assertEquals(2f, settings.resumeRecord(5, 9)!!.state?.speed)

        // In the credits: forgotten, and Continue watching has nothing to offer.
        settings.saveResumePosition(5, 9, (episode * 0.95).toLong(), episode, line(state))
        assertNull(settings.resumeRecord(5, 9))
        assertEquals(0L, settings.resumePosition(5, 9))
        assertTrue(settings.continueWatching.first().isEmpty())
    }
}
