package com.tmplayer.online

import com.tmplayer.data.MediaName
import kotlin.concurrent.Volatile

/**
 * The language words in each chat's title ("Malayalam Movies HD" gives `ml`), noted as the chat
 * list is read and asked for when a video's lookup is built ([MetaQuery.withHints]): a hint for
 * which of two shows of one name is meant, scored against TMDB's original language by [MetaPick].
 *
 * Kept as an immutable map swapped whole, so readers on any thread never see a half built one,
 * and bounded: a chat list of thousands holds only the chats whose title names a language.
 */
object ChatLanguageHints {
    private const val MAX_CHATS = 5000

    @Volatile
    private var hints: Map<Long, List<String>> = emptyMap()

    @Volatile
    private var seen: Set<Long> = emptySet()

    /** Notes [title] as chat [chatId]'s name (a rename replaces the earlier note). */
    fun note(chatId: Long, title: String) {
        val found = MediaName.languageHints(title)
        if (chatId in seen && hints[chatId].orEmpty() == found) return
        if (seen.size >= MAX_CHATS && chatId !in seen) return
        seen = seen + chatId
        hints = if (found.isEmpty()) hints - chatId else hints + (chatId to found)
    }

    /** The language hints of chat [chatId]'s title, empty for a chat whose title names none or is not known yet. */
    fun of(chatId: Long): List<String> = hints[chatId].orEmpty()

    /** Whether the chat's title has been read, so a question built without hints is final. */
    fun knows(chatId: Long): Boolean = chatId in seen

    /** For tests: forget everything. */
    fun clear() {
        hints = emptyMap()
        seen = emptySet()
    }
}
