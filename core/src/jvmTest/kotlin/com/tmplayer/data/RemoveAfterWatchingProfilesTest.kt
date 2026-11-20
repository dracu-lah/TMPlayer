package com.tmplayer.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Remove after watching" over a profile switch (HX88): the next profile's watched list is not
 * a list of newly watched videos, so a switch deletes nothing; a video marked after the switch,
 * in the profile now open, still goes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoveAfterWatchingProfilesTest {

    private fun download(messageId: Long) = ResumeRecord(
        chatId = -100L, messageId = messageId, fileId = messageId.toInt(), title = "Ep $messageId",
        chatTitle = "Show", sizeBytes = 1_000, durationSec = 1_400, positionMs = 0, durationMs = 0,
        updatedAt = 0, localPath = "/dl/$messageId.mkv",
    )

    private fun marks(vararg ids: Long) = ids.associate { id ->
        val record = WatchedRecord(
            chatId = -100L, messageId = id, fileId = id.toInt(), title = "Ep $id",
            chatTitle = "Show", sizeBytes = 1_000, durationSec = 1_400, watchedAt = id, manual = true,
        )
        record.key to record
    }

    @Test
    fun `A watched 1, the switch to B who watched 1 and 2 deletes nothing, a new mark in B still goes`() = runTest {
        val a = AccountStamp(1L, null)
        val b = AccountStamp(2L, "pbbbbb")
        var open = a
        val watched = MutableStateFlow(marks(1))
        val downloads = mutableListOf(download(1), download(2), download(3))
        val deleted = mutableListOf<Long>()
        val watcher = RemoveAfterWatching(
            enabled = MutableStateFlow(true),
            watched = watched,
            downloads = { downloads.toList() },
            busy = { false },
            profile = { open },
            delete = { record ->
                deleted += record.messageId
                downloads -= record
                true
            },
        )
        val job = launch { watcher.run() }
        runCurrent()
        // The switch: the stamp moves first, then B's list arrives.
        open = b
        watched.value = marks(1, 2)
        runCurrent()
        assertTrue("B's watched videos are not newly watched: $deleted", deleted.isEmpty())
        // B marks 3 watched: that one goes, as the setting asks.
        watched.value = marks(1, 2, 3)
        runCurrent()
        assertEquals(listOf(3L), deleted)
        job.cancel()
    }
}
