package com.tmplayer.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which archives a chat lists under its videos (greyed), and the reason each one carries (U10). */
class ArchiveListingTest {

    private val gb = 1024L * 1024 * 1024

    private fun file(name: String, size: Long = 300L * 1024 * 1024, locked: Boolean = false) =
        ArchiveFile(1, 1, 1, name, size, 0, Archives.kindOf(name) ?: ArchiveKind.Other, Archives.isVolume(name), locked)

    @Test
    fun `an archive named like a video is listed, a backup is not`() {
        assertTrue(Archives.worthListing("Night.Train.2019.1080p.rar", 80_000_000))
        assertTrue(Archives.worthListing("Show.S01E02.7z", 5_000_000))
        assertTrue(Archives.worthListing("Movie.mkv.rar", 1_000))
        assertTrue(Archives.worthListing("Course.part2.rar", 600L * 1024 * 1024))
        assertFalse(Archives.worthListing("Phone backup 2026.zip", 80_000_000))
        assertFalse(Archives.worthListing("Magisk-module.zip", 3_000_000))
        assertFalse(Archives.worthListing("photos.tar.gz", 200_000_000))
        assertFalse(Archives.worthListing("Holiday.mkv", 3 * gb))
    }

    @Test
    fun `a large archive with any name is a video box`() {
        assertTrue(Archives.worthListing("stuff.rar", Archives.UNNAMED_VIDEO_BYTES))
        assertFalse(Archives.worthListing("stuff.rar", Archives.UNNAMED_VIDEO_BYTES - 1))
    }

    @Test
    fun `a zip the chat already lists as a video is not listed twice`() {
        assertFalse(Archives.worthListing("Night.Train.2019.1080p.zip", 900L * 1024 * 1024))
        assertFalse(Archives.worthListing("Night.Train.2019.1080p.zip.001", 900L * 1024 * 1024))
        // A small release named zip is not listed as a video, so it is named here.
        assertTrue(Archives.worthListing("Trailer.1080p.zip", 5_000_000))
    }

    @Test
    fun `the reason is the first thing wrong, a piece before a password before size`() {
        assertEquals(ArchiveWhy.Volume, file("Show.S01.1080p.part2.rar", locked = true).why)
        assertEquals(ArchiveWhy.Password, file("Show.S01.1080p.rar", locked = true).why)
        assertEquals(ArchiveWhy.Huge, file("Show.S01.1080p.7z", size = 3 * gb).why)
        assertEquals(ArchiveWhy.Compressed, file("Show.S01.1080p.rar").why)
        assertEquals(ArchiveWhy.Volume, file("Show.S01.1080p.r00").why)
        assertEquals(ArchiveWhy.Volume, file("Show.S01.1080p.7z.003").why)
    }

    @Test
    fun `every reason says the file cannot be played and what to do`() {
        for (name in listOf("A.1080p.rar", "A.1080p.part1.rar", "A.1080p.7z")) {
            val reason = file(name).reason
            assertTrue(reason.startsWith("Compressed"), reason)
            assertTrue("cannot be played" in reason, reason)
        }
        assertTrue("password" in file("A.1080p.rar", locked = true).reason)
        assertTrue("very large" in file("A.1080p.rar", size = 3 * gb).reason)
    }

    @Test
    fun `a password is read from the name or the caption`() {
        assertTrue(Archives.looksLocked("Season 1 (password 1234).rar", null))
        assertTrue(Archives.looksLocked("Show.rar", "pass: abc"))
        assertTrue(Archives.looksLocked("Show.rar", "PWD=x"))
        assertFalse(Archives.looksLocked("Bypass.2019.1080p.rar", "Season finale"))
        assertFalse(Archives.looksLocked("Show.rar", null, ""))
    }

    @Test
    fun `a handed over archive says why it cannot play, except a zip the player inspects itself`() {
        val l = com.tmplayer.i18n.L
        assertEquals(l.gridArchiveReason("RAR"), Archives.blockedOnOpen("Season 1.rar"))
        assertEquals(l.gridArchiveReason("7z"), Archives.blockedOnOpen("Course.7z"))
        assertEquals(l.gridArchiveReason("archive"), Archives.blockedOnOpen("backup.tar.gz"))
        assertEquals(l.gridArchiveVolumeReason("RAR"), Archives.blockedOnOpen("Lectures.part2.rar"))
        assertEquals(l.gridArchiveVolumeReason("ZIP"), Archives.blockedOnOpen("Season.z01"))
        assertEquals(l.gridArchivePasswordReason("RAR"), Archives.blockedOnOpen("Show.rar", caption = "password: 1234"))
        assertEquals(l.gridArchiveHugeReason("7z"), Archives.blockedOnOpen("Box.7z", sizeBytes = 3 * gb))
        assertEquals(l.gridArchiveReason("RAR"), Archives.blockedOnOpen("download", "application/vnd.rar"))
        // A ZIP, or the pieces of a split one, is the player's to open and inspect.
        assertNull(Archives.blockedOnOpen("Season 1.zip"))
        assertNull(Archives.blockedOnOpen("Season 1.zip.002"))
        // Videos, audio and nothing at all are left alone, and absurd names do not throw.
        assertNull(Archives.blockedOnOpen("Holiday.mkv", "video/x-matroska"))
        assertNull(Archives.blockedOnOpen("Lecture 3.mp3", "audio/mpeg"))
        assertNull(Archives.blockedOnOpen(""))
        assertNull(Archives.blockedOnOpen("a".repeat(100_000)))
        assertEquals(l.gridArchiveReason("RAR"), Archives.blockedOnOpen("é".repeat(5_000) + ".RAR"))
    }

    @Test
    fun `a huge pile of names is cheap to judge`() {
        val started = System.nanoTime()
        repeat(100_000) { Archives.worthListing("Some.Show.S01E${it % 99}.1080p.WEB.rar", it.toLong()) }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
    }
}
