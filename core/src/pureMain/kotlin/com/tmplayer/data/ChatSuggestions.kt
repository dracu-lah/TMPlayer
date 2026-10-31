package com.tmplayer.data

/**
 * One chat as the picker's Recommended section weighs it: its id, how far down the chat list it
 * sits ([order], Telegram's own recency), and how many videos it holds, or null while that is not
 * known or could not be counted.
 */
data class CountedChat(val id: Long, val order: Int, val videos: Int?, val savedMessages: Boolean = false)

/**
 * Which chats the picker recommends and ticks for the viewer (HX27).
 *
 * The numbers are here, in one place, rather than spread through the screen, so they can be moved
 * after seeing real accounts: [MIN_VIDEOS] is the line between a chat that is a video library and
 * a chat where a few clips were shared, and [MAX_PRETICKED] keeps a first run from ticking two
 * hundred chats that then all have to be scanned.
 *
 * An account where nothing reaches the line (a new sign in, a viewer whose chats are all chatter)
 * must not meet an empty section, so the [FALLBACK] fullest chats are offered instead, unticked:
 * a chat with three videos is worth showing and not worth choosing for someone.
 */
object ChatSuggestions {

    /** Videos a chat needs before it is recommended. */
    const val MIN_VIDEOS = 20

    /** At most this many are ticked for the viewer, fullest first. */
    const val MAX_PRETICKED = 30

    /** Offered when nothing reaches [MIN_VIDEOS], and never ticked. */
    const val FALLBACK = 5

    /**
     * @param ids the recommended chats, in the order the section lists them.
     * @param ticked the ones ticked for the viewer, always a subset of [ids].
     * @param belowBar true when nothing reached [MIN_VIDEOS] and these are the fullest chats
     *   instead, which is what the section's foot says out loud.
     */
    data class Suggestion(
        val ids: List<Long> = emptyList(),
        val ticked: Set<Long> = emptySet(),
        val belowBar: Boolean = false,
    )

    /**
     * The recommendation over [chats].
     *
     * Fullest first, and where two hold the same number the chat Telegram lists first wins, so the
     * order is settled and does not shuffle between two runs over the same account. A chat whose
     * count is not known is left out entirely rather than treated as empty: it may be the fullest
     * one, and the picker asks again for it.
     *
     * Saved Messages is never here. The picker gives it a row of its own at the top, since it is
     * the one chat that is always the viewer's own, and a recommendation would only repeat it.
     */
    fun recommend(chats: List<CountedChat>): Suggestion {
        val counted = chats.asSequence()
            .filter { !it.savedMessages && it.videos != null && it.videos > 0 }
            .sortedWith(compareByDescending<CountedChat> { it.videos ?: 0 }.thenBy { it.order })
            .toList()
        if (counted.isEmpty()) return Suggestion()
        val over = counted.filter { (it.videos ?: 0) >= MIN_VIDEOS }
        if (over.isEmpty()) {
            // Shown so the section is never empty, unticked because none of them clears the bar.
            return Suggestion(ids = counted.take(FALLBACK).map { it.id }, belowBar = true)
        }
        val ids = over.map { it.id }
        return Suggestion(ids = ids, ticked = ids.take(MAX_PRETICKED).toSet())
    }

    /** Above this a row says "many": an exact figure stops meaning anything, and TDLib's is stale anyway. */
    const val MANY = 999

    /**
     * How a count reads on a row: the number up to [MANY], then "many", and nothing at all when
     * it could not be counted. The words are the screen's; this only decides which of the four it
     * is, so the rule can be tested without a screen, and the web app can share it.
     */
    fun shape(count: ChatCount?): CountShape = when {
        count == null -> CountShape.Counting
        count is ChatCount.Known && count.videos > MANY -> CountShape.Many
        count is ChatCount.Known -> CountShape.Exact(count.videos)
        else -> CountShape.Unknown
    }

    sealed interface CountShape {
        /** No answer yet: the row keeps its placeholder, and its height does not change. */
        data object Counting : CountShape

        data class Exact(val videos: Int) : CountShape

        /** Past the point where an exact figure means anything. */
        data object Many : CountShape

        /** Telegram would not count it: the row says so and offers another try. */
        data object Unknown : CountShape
    }
}
