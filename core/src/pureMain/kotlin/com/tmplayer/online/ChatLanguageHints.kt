package com.tmplayer.online

import com.tmplayer.data.MediaName
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The language words in each chat's title ("Malayalam Movies HD" gives `ml`), noted as the chat
 * list is read and asked for when a video's lookup is built ([MetaQuery.withHints]): a hint for
 * which of two shows of one name is meant, scored against TMDB's original language by [MetaPick].
 *
 * Thread safe without a lock: the chats are spread over [SHARDS] shards, each one an immutable map
 * swapped by compare and set, so a writer never loses another's entry, a reader never sees a half
 * built answer (a chat is known and its hints are one entry), and a new chat copies only its own
 * shard (about 80 entries at 5,000 chats), not the whole registry. Bounded: a shard holds at most
 * [PER_SHARD] chats and a chat past that is simply not noted (its videos ask without hints).
 */
@OptIn(ExperimentalAtomicApi::class)
object ChatLanguageHints {
    private const val SHARDS = 64
    private const val PER_SHARD = 128

    private val shards = Array(SHARDS) { AtomicReference<Map<Long, List<String>>>(emptyMap()) }

    private fun shardOf(chatId: Long): AtomicReference<Map<Long, List<String>>> =
        shards[((chatId xor (chatId ushr 32)).toInt() and Int.MAX_VALUE) % SHARDS]

    /** Notes [title] as chat [chatId]'s name (a rename replaces the earlier note). */
    fun note(chatId: Long, title: String) {
        val found = MediaName.languageHints(title)
        val shard = shardOf(chatId)
        while (true) {
            val before = shard.load()
            val known = before[chatId]
            if (known != null && known == found) return
            if (known == null && before.size >= PER_SHARD) return
            if (shard.compareAndSet(before, before + (chatId to found))) return
        }
    }

    /** The language hints of chat [chatId]'s title, empty for a chat whose title names none or is not known. */
    fun of(chatId: Long): List<String> = shardOf(chatId).load()[chatId].orEmpty()

    /** Whether the chat's title has been read. */
    fun knows(chatId: Long): Boolean = shardOf(chatId).load().containsKey(chatId)

    /** For tests: forget everything. */
    fun clear() {
        for (shard in shards) shard.store(emptyMap())
    }
}
