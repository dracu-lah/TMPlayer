package com.tmplayer.data

/**
 * One chat as the picker's Recommended section weighs it: its id, how far down the chat list it
 * sits ([order], Telegram's own recency), and how many videos it holds, or null while that is not
 * known or could not be counted. The figure only ranks: no screen shows it.
 */
data class CountedChat(val id: Long, val order: Int, val videos: Int?, val savedMessages: Boolean = false)

/**
 * Which chats the picker recommends (HX27). It lists them; nothing is ticked for the viewer.
 *
 * The numbers are here, in one place, rather than spread through the screen, so they can be moved
 * after seeing real accounts: [MIN_VIDEOS] is the line between a chat that is a video library and
 * a chat where a few clips were shared, and [MAX_BAND] caps the band.
 *
 * Telegram's count is evidence for the ranking and never a figure to print (it can be wrong), so
 * the screens say why in words ("Has many videos") and show no number.
 *
 * An account where nothing reaches the line (a new sign in, a viewer whose chats are all chatter)
 * must not meet an empty section, so the [FALLBACK] fullest chats are offered instead, unticked:
 * a chat with three videos is worth showing and not worth choosing for someone.
 */
object ChatSuggestions {

    /** Videos a chat needs before it is recommended. */
    const val MIN_VIDEOS = 20

    /** At most this many chats are recommended: the band is a short list, the rest is the list below it. */
    const val MAX_BAND = 30

    /** Offered when nothing reaches [MIN_VIDEOS], and never ticked. */
    const val FALLBACK = 5

    /**
     * @param ids the recommended chats, in the order the section lists them.
     * @param belowBar true when nothing reached [MIN_VIDEOS] and these are the fullest chats
     *   instead, which is what the section's note says out loud.
     */
    data class Suggestion(
        val ids: List<Long> = emptyList(),
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
        return Suggestion(ids = over.take(MAX_BAND).map { it.id })
    }

    /**
     * The band after more counts came in, over [chats] (everything counted so far).
     *
     * While [settled] is false (the viewer has not ticked, starred, searched or filtered) the band
     * is simply ranked again, so a video channel found late takes its place at the top. Once the
     * viewer has touched the list no row moves under a finger: chats that qualify later are added at
     * the end, and once the band is full they take the place of the last rows that have been
     * outranked (never one in [pinned], the chats the viewer ticked), so a channel of 20,000 videos
     * found late is not shut out by a full band. The one exception is the fallback band (nothing
     * had reached the bar), which a real recommendation replaces, since it only said "nothing yet".
     */
    fun refine(current: Suggestion, chats: List<CountedChat>, settled: Boolean, pinned: Set<Long> = emptySet()): Suggestion {
        val ranked = recommend(chats)
        if (ranked.ids.isEmpty()) return current
        if (!settled || current.ids.isEmpty()) return ranked
        if (ranked.belowBar) return current
        if (current.belowBar) return ranked
        val have = current.ids.toHashSet()
        val more = ranked.ids.filter { it !in have }
        if (more.isEmpty()) return current
        val out = current.ids.toMutableList()
        var next = 0
        while (out.size < MAX_BAND && next < more.size) out += more[next++]
        if (next < more.size) {
            val stillRanked = ranked.ids.toHashSet()
            // The last rows that were outranked, filled front to back so the fuller chat sits higher.
            val slots = out.indices.filter { out[it] !in stillRanked && out[it] !in pinned }.takeLast(more.size - next)
            for (slot in slots) out[slot] = more[next++]
        }
        return if (out == current.ids) current else Suggestion(ids = out)
    }
}
