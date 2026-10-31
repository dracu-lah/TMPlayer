package com.tmplayer.data

import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.SearchMessagesFilterVideo
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The counts behind the picker.
 *
 * What the budget is made of (AGENTS.md, scale): at most [AT_ONCE] chats are counted at a time,
 * each call gets [TIMEOUT_MS] and at most [TRIES] attempts, and a FLOOD_WAIT is waited out by the
 * call that got it, alone, so the other two go on. A wait Telegram asks for beyond
 * [MAX_FLOOD_WAIT_S] is not waited for at all: the row says it could not count and the viewer can
 * ask again, which is cheaper than holding a permit for a minute and a half.
 *
 * Only an answer is remembered ([TTL_MS]), never a failure, so a chat that failed is asked again
 * while a chat that answered is not asked twice in a session. Nothing here pages or polls by
 * itself: the picker asks for the rows on screen and for a bounded head start, and stops asking
 * the moment it closes (the idle CPU rule).
 */
object ChatVideoCount {

    /** Counted at a time, across the whole app. */
    const val AT_ONCE = 3

    /** Per call, after which the row says it could not count. */
    const val TIMEOUT_MS = 15_000L

    /** Attempts per chat, the flood waits among them. */
    const val TRIES = 3

    /** A wait longer than this is not waited out: Telegram is asking for too much to hold a row. */
    const val MAX_FLOOD_WAIT_S = 60

    /** How long an answer is kept. Long enough for a session with the picker, short enough to be fresh. */
    const val TTL_MS = 10 * 60 * 1000L

    private val cache = ConcurrentHashMap<Long, Pair<Long, ChatCount>>()
    private val gate = Semaphore(AT_ONCE)

    /** The count for [chatId] if it is already known and still fresh, without asking Telegram. */
    fun cached(chatId: Long, now: Long): ChatCount? =
        cache[chatId]?.takeIf { now - it.first < TTL_MS }?.second

    /** Forgets every count. Sign out, and a test between its cases. */
    fun forget() = cache.clear()

    /**
     * [chatId]'s videos: the fresh answer in hand, or one asked of TDLib's database and then, if
     * it will not say, of the server. Cancellation is passed on untouched, so a row scrolled off
     * screen stops its own count and gives its permit back.
     */
    suspend fun of(calls: ChatCountCalls, chatId: Long, now: () -> Long = { nowMs() }): ChatCount {
        cached(chatId, now())?.let { return it }
        val answer = gate.withPermit { ask(calls, chatId) }
        if (answer is ChatCount.Known) cache[chatId] = Pair(now(), answer)
        return answer
    }

    private suspend fun ask(calls: ChatCountCalls, chatId: Long): ChatCount {
        // TDLib's own figure first: it costs no round trip, and for a chat the app has opened
        // before it is the right number already.
        local(calls, chatId)?.let { return ChatCount.Known(it) }
        return server(calls, chatId)?.let { ChatCount.Known(it) } ?: ChatCount.Unknown
    }

    private suspend fun local(calls: ChatCountCalls, chatId: Long): Int? {
        // One try, no waiting: the database either knows or it does not.
        val count = call(calls, chatId, localOnly = true, tries = 1)
        // -1 is TDLib saying it has not counted this chat, which is not an answer of zero.
        return count?.takeIf { it >= 0 }
    }

    private suspend fun server(calls: ChatCountCalls, chatId: Long): Int? =
        call(calls, chatId, localOnly = false, tries = TRIES)?.coerceAtLeast(0)

    private suspend fun call(calls: ChatCountCalls, chatId: Long, localOnly: Boolean, tries: Int): Int? {
        repeat(tries) {
            // The raw result, not valueOrNull: the failure's own words carry the flood wait, and
            // swallowing them would turn "ask again in 20 seconds" into "this chat is empty".
            when (val answer = withTimeoutOrNull(TIMEOUT_MS) { calls.videoCount(chatId, localOnly) }) {
                is CountAnswer.Counted -> return answer.count
                is CountAnswer.Failed -> {
                    val wait = Failures.floodWaitSeconds(answer.message)
                    // Not a flood wait, or one too long to sit out: nothing is gained by asking again.
                    if (wait == null || wait > MAX_FLOOD_WAIT_S) return null
                    delay(wait * 1000L + 250L)
                }
                // Timed out. Another try, in case the first call was simply lost.
                null -> Unit
            }
        }
        return null
    }

    private fun nowMs(): Long = System.currentTimeMillis()

    /** The live TDLib client behind the counter. */
    fun calls(td: TdlClient): ChatCountCalls = object : ChatCountCalls {
        override suspend fun videoCount(chatId: Long, localOnly: Boolean): CountAnswer =
            withContext(Dispatchers.IO) {
                when (val answer = td.getChatMessageCount(chatId, null, SearchMessagesFilterVideo(), localOnly)) {
                    is TdlResult.Success -> CountAnswer.Counted(answer.result.count)
                    is TdlResult.Failure -> CountAnswer.Failed(answer.message)
                }
            }
    }
}
