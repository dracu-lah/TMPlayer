package com.tmplayer.data

import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.MessageDocument
import dev.g000sha256.tdl.dto.SearchMessagesFilterDocument
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
 * each call gets [TIMEOUT_MS], and a FLOOD_WAIT is waited out by the call that got it, alone, so
 * the other two go on, at most [TRIES] times. A wait Telegram asks for beyond [MAX_FLOOD_WAIT_S]
 * is not waited for at all: the row says it could not count and the viewer can ask again, which is
 * cheaper than holding a permit for a minute and a half.
 *
 * Only an answer is remembered ([TTL_MS]), never a failure, so a chat that failed is asked again
 * while a chat that answered is not asked twice in a session. Nothing here pages or polls by
 * itself: the picker asks for the rows on screen and for a bounded head start, and stops asking
 * the moment it closes (the idle CPU rule).
 */
object ChatVideoCount {

    /** Counted at a time, across the whole app. */
    const val AT_ONCE = 3

    /**
     * Per call, after which the row says it could not count.
     *
     * Generous for a round trip, and deliberately NOT long enough to cover a flood wait TDLib is
     * sitting out inside the call, which can run to a minute: a timed out call is given up on
     * rather than asked again, since TDLib may well still be holding the first one and a second
     * would simply sit behind it. That cost a fifth call in flight against a budget of three
     * ([AT_ONCE]) when this retried.
     */
    const val TIMEOUT_MS = 20_000L

    /** Attempts per chat. Only a flood wait is worth another: a wait Telegram timed for us. */
    const val TRIES = 3

    /** A wait longer than this is not waited out: Telegram is asking for too much to hold a row. */
    const val MAX_FLOOD_WAIT_S = 60

    /**
     * A chat with this many videos or more is not asked about ZIPs: a few more would change
     * neither its place in the picker nor its row ("About 120 videos"), and the extra call is
     * saved for the many chats that hold few or none. Below it, one document search per chat.
     */
    const val ZIP_PROBE_BELOW = 20

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
        val (answer, whole) = gate.withPermit { ask(calls, chatId) }
        if (answer is ChatCount.Known && whole) cache[chatId] = Pair(now(), answer)
        return answer
    }

    /** The count, and whether it is the whole answer (a probe that failed leaves it not worth remembering). */
    private suspend fun ask(calls: ChatCountCalls, chatId: Long): Pair<ChatCount, Boolean> {
        // TDLib's own figure first: it costs no round trip, and for a chat the app has opened
        // before it is the right number already.
        val videos = local(calls, chatId) ?: server(calls, chatId) ?: return Pair(ChatCount.Unknown, false)
        if (videos >= ZIP_PROBE_BELOW) return Pair(ChatCount.Known(videos), true)
        // A few videos or none: the chat may keep its videos in ZIPs, which are documents, so the
        // video filter above never saw them. The probe is one bounded search, under the permit the
        // count already holds. If it will not answer, the row says what it knows (never worse than
        // before), and that answer is not remembered, so the next ask tries the probe again.
        val zipped = zipped(calls, chatId)
        return Pair(ChatCount.Known(videos + (zipped ?: 0)), zipped != null)
    }

    private suspend fun zipped(calls: ChatCountCalls, chatId: Long): Int? =
        when (val answer = withTimeoutOrNull(TIMEOUT_MS) { calls.zippedVideos(chatId) }) {
            is CountAnswer.Counted -> answer.count.coerceAtLeast(0)
            // One try: a flood wait is not worth holding the permit for a refinement.
            is CountAnswer.Failed, null -> null
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
                // Timed out, which is not the same as refused: the call may still be running
                // inside TDLib, waiting out a flood of its own. Asking again would put a second
                // call beside the first and hold a second permit, so the row says it could not
                // count and offers the viewer another try instead.
                null -> return null
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

        override suspend fun zippedVideos(chatId: Long): CountAnswer =
            withContext(Dispatchers.IO) {
                val found = td.searchChatMessages(
                    chatId, null, ZippedVideos.QUERY, null, 0, 0, ZippedVideos.PAGE, SearchMessagesFilterDocument(),
                )
                when (found) {
                    is TdlResult.Success -> CountAnswer.Counted(
                        ZippedVideos.count(
                            found.result.messages.orEmpty().mapNotNull { (it.content as? MessageDocument)?.document }
                                .map { ZippedVideos.Named(it.fileName.orEmpty(), MediaText.fileSize(it.document.size, it.document.expectedSize)) },
                        ),
                    )
                    is TdlResult.Failure -> CountAnswer.Failed(found.message)
                }
            }
    }
}
