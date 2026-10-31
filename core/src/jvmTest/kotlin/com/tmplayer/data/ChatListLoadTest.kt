package com.tmplayer.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the whole chat list (HX32): the archive past its first page, an archive that fails, a
 * chat TDLib will not describe, a folder that fails to read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatListLoadTest {

    /** A TDLib with [main] chats in the main list and [archive] in the archive, loaded 100 a page. */
    private class Calls(val main: Int, val archive: Int) : ChatListCalls {
        var mainLoaded = 0
        var archiveLoaded = 0
        val archivePages = mutableListOf<Int>()

        /** Failures to hand out, per call, in order. */
        val archiveLoadFailures = ArrayDeque<String>()
        val archiveReadFailures = ArrayDeque<String>()
        var archiveReads = 0
        var maxSummariesAtOnce = 0
        private var summariesNow = 0
        val missing = mutableSetOf<Long>()
        val failedFolders = mutableSetOf<Int>()
        val folderMembers = mutableMapOf<Int, List<Long>>()

        private fun archiveId(i: Int) = 100_000L + i

        override suspend fun loadChats(list: ChatListRef, limit: Int): ChatLoad {
            when (list) {
                ChatListRef.Main -> {
                    if (mainLoaded >= main) return ChatLoad.AllLoaded
                    mainLoaded = minOf(main, mainLoaded + 100)
                }
                ChatListRef.Archive -> {
                    archiveLoadFailures.removeFirstOrNull()?.let { return ChatLoad.Failed(it) }
                    if (archiveLoaded >= archive) return ChatLoad.AllLoaded
                    archiveLoaded = minOf(archive, archiveLoaded + 100)
                    archivePages += archiveLoaded
                }
                is ChatListRef.Folder -> Unit
            }
            return ChatLoad.Loaded
        }

        override suspend fun chatIds(list: ChatListRef, limit: Int): List<Long> = when (list) {
            ChatListRef.Main -> (1L..minOf(limit, mainLoaded).toLong()).toList()
            ChatListRef.Archive -> {
                archiveReads++
                archiveReadFailures.removeFirstOrNull()?.let { throw IllegalStateException(it) }
                (1..minOf(limit, archiveLoaded)).map(::archiveId)
            }
            is ChatListRef.Folder -> {
                if (list.id in failedFolders) throw IllegalStateException("Request aborted")
                folderMembers[list.id].orEmpty()
            }
        }

        override suspend fun summary(chatId: Long, myId: Long): ChatSummary? {
            summariesNow++
            maxSummariesAtOnce = maxOf(maxSummariesAtOnce, summariesNow)
            kotlinx.coroutines.yield()
            summariesNow--
            if (chatId in missing) return null
            return ChatSummary(chatId, "Chat $chatId", null, 0, ChatKind.Channel, isArchived = chatId >= 100_000L)
        }

        override suspend fun myId(): Long = 0L

        override fun folders(): List<Int> = listOf(1, 2)
    }

    @Test
    fun `an archive beyond the first hundred is read to its end`() = runTest {
        val calls = Calls(main = 250, archive = 1_500)
        val sync = ChatListLoad.sync(calls, limit = 300, known = emptyList(), archiveLimit = 4_000)
        assertTrue(sync.complete)
        assertEquals(250 + 1_500, sync.chats.size)
        assertEquals(1_500, sync.chats.count { it.isArchived })
        assertEquals(15, calls.archivePages.size)
        // Read in chunks of CHUNK, never the whole account at once.
        assertTrue("at once: ${calls.maxSummariesAtOnce}", calls.maxSummariesAtOnce <= ChatListLoad.CHUNK)
    }

    @Test
    fun `the archive stops at the hard ceiling`() = runTest {
        val calls = Calls(main = 10, archive = 9_000)
        val sync = ChatListLoad.sync(calls, limit = 300, known = emptyList(), archiveLimit = 4_000)
        assertEquals(40, calls.archivePages.size)
        assertEquals(10 + 4_000, sync.chats.size)
        assertTrue(sync.complete)
    }

    @Test
    fun `the first read takes only the first page of the archive`() = runTest {
        val calls = Calls(main = 50, archive = 1_500)
        calls.mainLoaded = 50
        calls.archiveLoaded = 1_500
        val first = ChatListLoad.cached(calls, 300, emptyList(), archiveLimit = 100)
        assertEquals(150, first.chats.size)
    }

    @Test
    fun `an archive that fails is reported, and asked again once after the wait it names`() = runTest {
        val calls = Calls(main = 120, archive = 300)
        calls.archiveLoadFailures += "Too Many Requests: retry after 2"
        val sync = ChatListLoad.sync(calls, 300, emptyList(), 4_000)
        // The one retry succeeded (virtual time, so the 2 s wait cost nothing): nothing is reported.
        assertNull(sync.archiveFailure)
        assertTrue(sync.complete)
        assertEquals(120 + 300, sync.chats.size)

        // Failing again on the retry stops the loop and says so, with the pages that did load kept.
        val again = Calls(main = 120, archive = 500)
        again.archiveLoadFailures += listOf("FLOOD_WAIT_1", "FLOOD_WAIT_1")
        val partial = ChatListLoad.sync(again, 300, emptyList(), 4_000)
        assertEquals("FLOOD_WAIT_1", partial.archiveFailure)
        assertTrue(partial.partial)
        assertFalse(partial.complete)
        assertEquals(120, partial.chats.size)
    }

    @Test
    fun `a flood wait too long to wait for is reported without a second ask`() = runTest {
        val calls = Calls(main = 10, archive = 300)
        calls.archiveLoadFailures += "FLOOD_WAIT_900"
        val sync = ChatListLoad.sync(calls, 300, emptyList(), 4_000)
        assertEquals("FLOOD_WAIT_900", sync.archiveFailure)
        assertTrue(calls.archiveLoadFailures.isEmpty())
        assertEquals(0, calls.archivePages.size)
    }

    @Test
    fun `an archive read that fails keeps the archived rows in hand and is retried once`() = runTest {
        val calls = Calls(main = 20, archive = 200)
        calls.mainLoaded = 20
        calls.archiveLoaded = 200
        val known = (1..50).map { ChatSummary(100_000L + it, "Old $it", null, 0, ChatKind.Channel, isArchived = true) }

        // One failure, then success: the retry covers it.
        calls.archiveReadFailures += "Request aborted"
        val retried = ChatListLoad.cached(calls, 300, known, 4_000)
        assertNull(retried.archiveFailure)
        assertEquals(2, calls.archiveReads)
        assertEquals(20 + 200, retried.chats.size)

        // Two failures: reported, and the archived rows already held stay.
        calls.archiveReads = 0
        calls.archiveReadFailures += listOf("Request aborted", "Request aborted")
        val failed = ChatListLoad.cached(calls, 300, known, 4_000)
        assertEquals("Request aborted", failed.archiveFailure)
        assertEquals(2, calls.archiveReads)
        assertTrue(failed.partial)
        assertEquals(20 + 50, failed.chats.size)
        assertEquals(50, failed.chats.count { it.isArchived })
    }

    @Test
    fun `a chat that cannot be described is counted, not silently dropped`() = runTest {
        val calls = Calls(main = 40, archive = 0)
        calls.mainLoaded = 40
        calls.missing += listOf(3L, 17L, 39L)
        val read = ChatListLoad.cached(calls, 300, emptyList(), 100)
        assertEquals(37, read.chats.size)
        assertEquals(3, read.dropped)
        assertTrue(read.partial)
        assertTrue(read.complete)

        val sync = ChatListLoad.sync(calls, 300, emptyList(), 100)
        assertEquals(3, sync.dropped)
        assertTrue(sync.partial)
    }

    @Test
    fun `a folder that fails to read is not loaded, not empty`() = runTest {
        val calls = Calls(main = 5, archive = 0)
        calls.folderMembers[1] = listOf(1L, 2L)
        calls.failedFolders += 2
        val membership = ChatListLoad.membership(calls, 2_000)
        assertEquals(setOf(2), membership.failed)
        assertEquals(listOf(1), membership.byChat[1L])

        // A row already in the failed folder keeps it, rather than losing it to the failed read.
        val rows = listOf(
            ChatSummary(1, "One", null, 0, ChatKind.Channel, folderIds = listOf(2)),
            ChatSummary(3, "Three", null, 0, ChatKind.Channel, folderIds = listOf(1)),
        )
        val withFolders = ChatListLoad.withFolders(rows, membership)
        assertEquals(listOf(1, 2), withFolders[0].folderIds)
        assertEquals(emptyList<Int>(), withFolders[1].folderIds)
    }

    @Test
    fun `folders that all read give no failures`() = runTest {
        val calls = Calls(main = 5, archive = 0)
        calls.folderMembers[2] = listOf(4L)
        val membership = ChatListLoad.membership(calls, 2_000)
        assertTrue(membership.failed.isEmpty())
        assertEquals(listOf(2), membership.byChat[4L])
    }
}
