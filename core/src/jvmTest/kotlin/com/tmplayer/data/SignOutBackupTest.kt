package com.tmplayer.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Save a backup before you sign out": the backup goes to the viewer's own Saved Messages first,
 * and whatever goes wrong comes back as words for the dialog, with the way out beside them (Sign
 * out anyway); nothing here throws, and nothing waits longer than the timeout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignOutBackupTest {

    @Test
    fun theFileIsTheBackupTheViewerWouldSaveAndGoesOnceToSavedMessages() = runTest {
        val sent = mutableListOf<Pair<String, String>>()
        val result = SignOutBackup.save({ "{\"backup\":1}" }, "tmplayer-backup-2026-11-16.json", { text, name -> sent += text to name; BackupSend.Sent })
        assertEquals(BackupSend.Sent, result)
        assertEquals(listOf("{\"backup\":1}" to "tmplayer-backup-2026-11-16.json"), sent)
    }

    @Test
    fun aFailureComesBackAsWordsAndNeverThrows() = runTest {
        val failed = SignOutBackup.save({ "x" }, "b.json", { _, _ -> BackupSend.Failed("Telegram said no") })
        assertEquals(BackupSend.Failed("Telegram said no"), failed)

        val crashed = SignOutBackup.save({ "x" }, "b.json", { _, _ -> error("TDLib closed") })
        assertTrue(crashed is BackupSend.Failed && crashed.reason.isNotBlank())

        val unreadable = SignOutBackup.save({ error("a store failed") }, "b.json", { _, _ -> BackupSend.Sent })
        assertTrue(unreadable is BackupSend.Failed)
    }

    @Test
    fun aDeadLineEndsAtTheTimeoutSoSigningOutIsNeverHeldUp() = runTest {
        val result = SignOutBackup.save({ "x" }, "b.json", { _, _ -> delay(10 * 60_000L); BackupSend.Sent }, timeoutMs = 60_000L)
        assertTrue(result is BackupSend.Failed)
        // Virtual time: the minute passed, not ten.
        assertEquals(60_000L, testScheduler.currentTime)
    }
}
