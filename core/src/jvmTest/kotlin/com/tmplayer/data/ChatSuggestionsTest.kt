package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSuggestionsTest {

    private fun chat(id: Long, videos: Int?, order: Int = id.toInt(), saved: Boolean = false) =
        CountedChat(id, order, videos, saved)

    @Test
    fun `the chats over the bar are recommended, fullest first`() {
        val answer = ChatSuggestions.recommend(
            listOf(chat(1, 25), chat(2, 4), chat(3, 900), chat(4, ChatSuggestions.MIN_VIDEOS)),
        )
        assertEquals(listOf(3L, 1L, 4L), answer.ids)
        assertFalse(answer.belowBar)
    }

    @Test
    fun `exactly the bar counts, one under it does not`() {
        val answer = ChatSuggestions.recommend(listOf(chat(1, ChatSuggestions.MIN_VIDEOS - 1), chat(2, ChatSuggestions.MIN_VIDEOS)))
        assertEquals(listOf(2L), answer.ids)
        assertFalse(answer.belowBar)
    }

    @Test
    fun `no more than the cap are recommended, the fullest of them`() {
        val many = (1L..40L).map { chat(it, 100 + it.toInt()) }
        val answer = ChatSuggestions.recommend(many)
        assertEquals(ChatSuggestions.MAX_BAND, answer.ids.size)
        // The fullest are the ones offered, not the first ones listed by Telegram.
        assertTrue(answer.ids.contains(40L))
        assertFalse(answer.ids.contains(1L))
    }

    @Test
    fun `nothing reaches the bar, so the fullest few are offered unticked`() {
        val answer = ChatSuggestions.recommend((1L..9L).map { chat(it, it.toInt()) })
        assertEquals(ChatSuggestions.FALLBACK, answer.ids.size)
        assertEquals(listOf(9L, 8L, 7L, 6L, 5L), answer.ids)
        assertTrue(answer.belowBar)
    }

    @Test
    fun `an account whose chats hold no videos recommends nothing at all`() {
        val answer = ChatSuggestions.recommend(listOf(chat(1, 0), chat(2, null), chat(3, 0)))
        assertEquals(emptyList<Long>(), answer.ids)
        assertFalse(answer.belowBar)
    }

    @Test
    fun `a chat that could not be counted is left out rather than read as empty`() {
        val answer = ChatSuggestions.recommend(listOf(chat(1, null), chat(2, 50)))
        assertEquals(listOf(2L), answer.ids)
    }

    @Test
    fun `two chats holding the same number keep Telegram's order between them`() {
        val answer = ChatSuggestions.recommend(listOf(chat(7, 30, order = 5), chat(8, 30, order = 2)))
        assertEquals(listOf(8L, 7L), answer.ids)
    }

    @Test
    fun `Saved Messages is never recommended, since the picker gives it its own row`() {
        val answer = ChatSuggestions.recommend(listOf(chat(1, 5_000, saved = true), chat(2, 40)))
        assertEquals(listOf(2L), answer.ids)
    }

    private fun suggestion(vararg ids: Long, below: Boolean = false) = ChatSuggestions.Suggestion(ids.toList(), below)

    @Test
    fun `a channel with thousands of videos found late takes the top while the viewer has not touched the band`() {
        val first = ChatSuggestions.recommend(listOf(chat(1, 40), chat(2, 60)))
        val later = ChatSuggestions.refine(first, listOf(chat(1, 40), chat(2, 60), chat(900, 20_000, order = 900)), settled = false)
        assertEquals(listOf(900L, 2L, 1L), later.ids)
    }

    @Test
    fun `once the viewer has touched the band nothing moves, a later one is added at the end`() {
        val first = suggestion(2, 1)
        val later = ChatSuggestions.refine(first, listOf(chat(1, 40), chat(2, 60), chat(900, 20_000, order = 900)), settled = true)
        assertEquals(listOf(2L, 1L, 900L), later.ids)
        // Nothing new: the very same band.
        assertEquals(later, ChatSuggestions.refine(later, listOf(chat(1, 40), chat(2, 60), chat(900, 20_000, order = 900)), settled = true))
    }

    @Test
    fun `a full settled band gives the last outranked row to a fuller chat, and keeps what the viewer ticked`() {
        val all = (1L..30L).map { chat(it, 100 + it.toInt()) }
        val full = ChatSuggestions.recommend(all)
        assertEquals(30, full.ids.size)
        val withBig = all + chat(900, 20_000, order = 900)
        // Chat 1 holds the fewest videos, so it is the one outranked.
        val later = ChatSuggestions.refine(full, withBig, settled = true)
        assertEquals(full.ids.dropLast(1) + 900L, later.ids)
        assertEquals(full.ids.size, later.ids.size)
        // The viewer ticked it: it stays, and the band does not grow past the cap.
        assertEquals(full, ChatSuggestions.refine(full, withBig, settled = true, pinned = setOf(1L)))
    }

    @Test
    fun `a band that only said nothing reached the bar is replaced by a real one, and never by another fallback`() {
        val fallback = ChatSuggestions.recommend(listOf(chat(1, 3), chat(2, 4)))
        assertTrue(fallback.belowBar)
        val real = ChatSuggestions.refine(fallback, listOf(chat(1, 3), chat(2, 4), chat(900, 20_000)), settled = true)
        assertEquals(listOf(900L), real.ids)
        assertFalse(real.belowBar)
        assertEquals(real, ChatSuggestions.refine(real, listOf(chat(900, 20_000), chat(5, 2)), settled = true))
    }

    @Test
    fun `no new count keeps the band as it is`() {
        val current = suggestion(1, 2)
        assertEquals(current, ChatSuggestions.refine(current, listOf(chat(3, null), chat(4, 0)), settled = false))
    }
}
