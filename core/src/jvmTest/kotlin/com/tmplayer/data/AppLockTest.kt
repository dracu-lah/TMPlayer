package com.tmplayer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The app lock (CP57): the PIN is only ever a salted hash, wrong guesses earn a growing wait that
 * survives a restart, and the kids lock screens every list of chats the same way.
 */
class AppLockTest {

    private val dir = Files.createTempDirectory("tm-lock").toFile()
    private val settings = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))

    // ---- the hash --------------------------------------------------------------------------

    @Test
    fun `a PIN is four digits and nothing else`() {
        assertTrue(PinHash.isValid("0420"))
        assertFalse(PinHash.isValid("420"))
        assertFalse(PinHash.isValid("04200"))
        assertFalse(PinHash.isValid("04a0"))
        assertFalse(PinHash.isValid("٠١٢٣"))
    }

    @Test
    fun `the right PIN verifies and every other one does not`() {
        val stored = PinHash.hash("2580")
        assertTrue(PinHash.verify("2580", stored))
        assertFalse(PinHash.verify("2581", stored))
        assertFalse(PinHash.verify("0000", stored))
        assertFalse(PinHash.verify("258", stored))
    }

    @Test
    fun `the PIN itself is never in the stored line, and each line has its own salt`() {
        val first = PinHash.hash("1234")
        val second = PinHash.hash("1234")
        assertFalse(first.contains("1234"))
        assertNotEquals(first, second)
        assertTrue(PinHash.verify("1234", first))
        assertTrue(PinHash.verify("1234", second))
    }

    @Test
    fun `the same salt gives the same line`() {
        val salt = ByteArray(16) { it.toByte() }
        assertEquals(PinHash.hash("9999", salt), PinHash.hash("9999", salt))
    }

    @Test
    fun `a damaged line never opens`() {
        assertFalse(PinHash.verify("1234", null))
        assertFalse(PinHash.verify("1234", ""))
        assertFalse(PinHash.verify("1234", "v1:abc:def:ghi"))
        assertFalse(PinHash.verify("1234", "v2:20000:AAAA:AAAA"))
        assertFalse(PinHash.verify("1234", "v1:20000:!!!:AAAA"))
    }

    // ---- the back-off ----------------------------------------------------------------------

    @Test
    fun `three misses are free, then the wait doubles up to a ceiling`() {
        assertEquals(0L, PinBackoff.waitAfter(1))
        assertEquals(0L, PinBackoff.waitAfter(2))
        assertEquals(30_000L, PinBackoff.waitAfter(3))
        assertEquals(60_000L, PinBackoff.waitAfter(4))
        assertEquals(120_000L, PinBackoff.waitAfter(5))
        assertEquals(PinBackoff.MAX_WAIT_MS, PinBackoff.waitAfter(9))
        assertEquals(PinBackoff.MAX_WAIT_MS, PinBackoff.waitAfter(500))
    }

    @Test
    fun `attempts count down the free tries and then wait`() {
        var attempts = PinAttempts()
        assertEquals(3, attempts.freeLeft)
        attempts = attempts.failed(now = 1_000)
        attempts = attempts.failed(now = 2_000)
        assertEquals(1, attempts.freeLeft)
        assertEquals(0L, attempts.waitMs(now = 2_000))
        attempts = attempts.failed(now = 3_000)
        assertEquals(0, attempts.freeLeft)
        assertEquals(30_000L, attempts.waitMs(now = 3_000))
        assertEquals(10_000L, attempts.waitMs(now = 23_000))
        assertEquals(0L, attempts.waitMs(now = 40_000))
    }

    @Test
    fun `the store counts misses, makes them wait, and forgets them on the right PIN`() = runBlocking {
        assertEquals(PinCheck.NoPin, settings.checkPin("1234", now = 0))
        settings.setPin("1234")
        assertTrue(settings.pinSetNow())

        assertTrue(settings.checkPin("1111", now = 0) is PinCheck.Wrong)
        assertTrue(settings.checkPin("2222", now = 0) is PinCheck.Wrong)
        val third = settings.checkPin("3333", now = 0) as PinCheck.Wrong
        assertEquals(30_000L, third.attempts.waitMs(0))

        // Inside the wait even the right PIN is not looked at.
        assertEquals(PinCheck.Waiting(20_000L), settings.checkPin("1234", now = 10_000))
        // Written down rather than held in memory, so a restart reads the same wait back.
        assertEquals(PinAttempts(3, 30_000L), settings.pinAttemptsNow(now = 10_000))

        assertEquals(PinCheck.Right, settings.checkPin("1234", now = 30_000))
        assertEquals(PinAttempts(), settings.pinAttemptsNow())
    }

    @Test
    fun `changing the PIN forgets the misses against the old one`() = runBlocking {
        settings.setPin("1234")
        settings.checkPin("0000", now = 0)
        settings.setPin("5678")
        assertEquals(PinAttempts(), settings.pinAttemptsNow())
        assertEquals(PinCheck.Right, settings.checkPin("5678", now = 0))
        assertTrue(settings.checkPin("1234", now = 0) is PinCheck.Wrong)
    }

    @Test
    fun `removing the PIN turns off what it guarded`() = runBlocking {
        settings.setPin("1234")
        settings.setLockOnOpen(true)
        settings.setKidsChats(setOf(7L))
        settings.setKidsLock(true)
        assertEquals(AppLockSettings(true, true, true, setOf(7L)), settings.appLock.first())

        settings.removePin()
        assertEquals(AppLockSettings(false, false, false, setOf(7L)), settings.appLock.first())
        assertNull(settings.kidsAllowedChats.first())
    }

    @Test
    fun `signing out, here or from another device, clears the lock`() = runBlocking {
        settings.setPin("1234")
        settings.setKidsLock(true)
        settings.clearEverything()
        assertFalse(settings.pinSetNow())

        settings.setPin("1234")
        settings.setKidsChats(setOf(1L))
        settings.clearAppLock()
        assertEquals(AppLockSettings(), settings.appLock.first())
    }

    // ---- the kids lock ---------------------------------------------------------------------

    private fun chat(id: Long) = ChatSummary(id, "Chat $id", null, 0, ChatKind.Channel)

    private fun video(chatId: Long, messageId: Long) = MediaItem(
        chatId = chatId, messageId = messageId, fileId = 1, title = "V", sizeBytes = 1,
        durationSec = 0, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 0,
    )

    @Test
    fun `off, the filter lets everything through`() {
        val chats = listOf(chat(1), chat(2))
        assertEquals(chats, KidsLock.chats(chats, allowed = null))
        assertTrue(KidsLock.allows(null, 99))
    }

    @Test
    fun `on, only the allowed chats are left, in their order`() {
        val chats = listOf(chat(3), chat(1), chat(2))
        assertEquals(listOf(chat(3), chat(2)), KidsLock.chats(chats, setOf(2L, 3L)))
        val videos = listOf(video(1, 10), video(2, 11), video(1, 12))
        assertEquals(listOf(video(2, 11)), KidsLock.media(videos, setOf(2L)))
    }

    @Test
    fun `on with nothing chosen shows nothing, not everything`() {
        assertTrue(KidsLock.chats(listOf(chat(1)), emptySet()).isEmpty())
    }

    @Test
    fun `favourites are counted as the Favourites tab lists them`() {
        val starred = setOf(1L, 2L)
        assertEquals(starred, KidsLock.favourites(starred, allowed = null))
        // Two starred, one of them hidden by the lock: the badge says 1, not 2.
        assertEquals(setOf(2L), KidsLock.favourites(starred, setOf(2L, 3L)))
        assertTrue(KidsLock.favourites(starred, emptySet()).isEmpty())
    }

    @Test
    fun `a chat starred but not added leaves the Favourites tab while its star stays stored (HX27, HX30)`() {
        val starred = setOf(1L, 2L)
        // The gate the Favourites tab reads is the kids lock and the chats added, met in the
        // middle (AddedChats.gate, read through ChatGate.browsing): chat 2 was never added, so
        // it is left out of what the tab lists, exactly as a chat the kids lock hides would be.
        val browsing = AddedChats.gate(kids = null, added = setOf(1L, 3L))
        assertEquals(setOf(1L), KidsLock.favourites(starred, browsing))
        // The star itself is a question KidsLock.favourites never answers from the stored set:
        // it only narrows what is already there, so chat 2's star is still in [starred] here,
        // ready to reappear in the tab the moment it is added again.
        assertTrue(2L in starred)
    }

    @Test
    fun `clearing favourites under the lock keeps the hidden ones`() = runBlocking {
        settings.toggleFavorite(1L)
        settings.toggleFavorite(2L)
        settings.clearFavorites(allowed = setOf(2L))
        assertEquals(setOf(1L), settings.favorites.first())
        settings.clearFavorites()
        assertTrue(settings.favorites.first().isEmpty())
    }

    @Test
    fun `a lock without a PIN is not in force`() = runBlocking {
        settings.setKidsChats(setOf(5L))
        settings.setKidsLock(true)
        assertNull(settings.kidsAllowedChats.first())
        settings.setPin("1234")
        assertEquals(setOf(5L), settings.kidsAllowedChats.first())
    }

    @Test
    fun `continue watching only offers the allowed chats`() = runBlocking {
        fun line(chat: String) = ResumeRecord.encode(3, "Film", chat, 1L, 2400, System.currentTimeMillis())
        settings.saveResumePosition(5, 9, 600_000L, 40 * 60_000L, line("Allowed"))
        settings.saveResumePosition(6, 9, 600_000L, 40 * 60_000L, line("Not allowed"))
        assertEquals(2, settings.continueWatching.first().size)

        settings.setPin("1234")
        settings.setKidsChats(setOf(5L))
        settings.setKidsLock(true)
        assertEquals(listOf(5L), settings.continueWatching.first().map { it.chatId })

        settings.setKidsLock(false)
        assertEquals(2, settings.continueWatching.first().size)
    }

    @Test
    fun `a wait is timed on the steady clock and survives it starting again`() {
        // Written at 100 s with 30 s left; 10 s later, 20 s are left.
        assertEquals(20_000L, PinAttempts.restored(3, 30_000L, 100_000L, now = 110_000L).waitMs(110_000L))
        // The clock started again (a reboot): none of the wait was served.
        assertEquals(30_000L, PinAttempts.restored(3, 30_000L, 100_000L, now = 5_000L).waitMs(5_000L))
        // A stored figure past the ceiling is held to it.
        assertEquals(PinBackoff.MAX_WAIT_MS, PinAttempts.restored(9, Long.MAX_VALUE, 0L, now = 0L).waitMs(0L))
        assertEquals(0L, PinAttempts.restored(3, 30_000L, 100_000L, now = 200_000L).waitMs(200_000L))
    }

    @Test
    fun `saved logs still scrub the names of chats the kids lock hides`() = runBlocking {
        fun line(chat: String) = ResumeRecord.encode(3, "Film", chat, 1L, 2400, System.currentTimeMillis())
        settings.saveResumePosition(5, 9, 600_000L, 40 * 60_000L, line("Allowed chat"))
        settings.saveResumePosition(6, 9, 600_000L, 40 * 60_000L, line("Hidden family chat"))
        settings.setPin("1234")
        settings.setKidsChats(setOf(5L))
        settings.setKidsLock(true)
        assertTrue("Hidden family chat" in LogExport.knownNames(settings, null))
    }

    @Test
    fun `the gate follows what it is set to`() = runBlocking {
        ChatGate.set(setOf(4L))
        assertEquals(setOf(4L), ChatGate.awaitReady())
        ChatGate.set(null)
        assertNull(ChatGate.awaitReady())
    }
}
