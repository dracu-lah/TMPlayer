package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSuggestionsTest {

    private fun chat(id: Long, videos: Int?, order: Int = id.toInt(), saved: Boolean = false) =
        CountedChat(id, order, videos, saved)

    @Test
    fun `the chats over the bar are recommended, fullest first, and ticked`() {
        val answer = ChatSuggestions.recommend(
            listOf(chat(1, 25), chat(2, 4), chat(3, 900), chat(4, ChatSuggestions.MIN_VIDEOS)),
        )
        assertEquals(listOf(3L, 1L, 4L), answer.ids)
        assertEquals(setOf(3L, 1L, 4L), answer.ticked)
        assertFalse(answer.belowBar)
    }

    @Test
    fun `exactly the bar counts, one under it does not`() {
        val answer = ChatSuggestions.recommend(listOf(chat(1, ChatSuggestions.MIN_VIDEOS - 1), chat(2, ChatSuggestions.MIN_VIDEOS)))
        assertEquals(listOf(2L), answer.ids)
        assertFalse(answer.belowBar)
    }

    @Test
    fun `no more than the cap are ticked, though all of them are offered`() {
        val many = (1L..40L).map { chat(it, 100 + it.toInt()) }
        val answer = ChatSuggestions.recommend(many)
        assertEquals(40, answer.ids.size)
        assertEquals(ChatSuggestions.MAX_PRETICKED, answer.ticked.size)
        // The fullest are the ones ticked, not the first ones listed by Telegram.
        assertTrue(answer.ticked.contains(40L))
        assertFalse(answer.ticked.contains(1L))
    }

    @Test
    fun `nothing reaches the bar, so the fullest few are offered unticked`() {
        val answer = ChatSuggestions.recommend((1L..9L).map { chat(it, it.toInt()) })
        assertEquals(ChatSuggestions.FALLBACK, answer.ids.size)
        assertEquals(listOf(9L, 8L, 7L, 6L, 5L), answer.ids)
        assertEquals(emptySet<Long>(), answer.ticked)
        assertTrue(answer.belowBar)
    }

    @Test
    fun `an account whose chats hold no videos recommends nothing at all`() {
        val answer = ChatSuggestions.recommend(listOf(chat(1, 0), chat(2, null), chat(3, 0)))
        assertEquals(emptyList<Long>(), answer.ids)
        assertEquals(emptySet<Long>(), answer.ticked)
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

    @Test
    fun `a count reads as a number, as many, or as nothing at all`() {
        assertEquals(ChatSuggestions.CountShape.Counting, ChatSuggestions.shape(null))
        assertEquals(ChatSuggestions.CountShape.Unknown, ChatSuggestions.shape(ChatCount.Unknown))
        assertEquals(ChatSuggestions.CountShape.Exact(0), ChatSuggestions.shape(ChatCount.Known(0)))
        assertEquals(ChatSuggestions.CountShape.Exact(ChatSuggestions.MANY), ChatSuggestions.shape(ChatCount.Known(ChatSuggestions.MANY)))
        assertEquals(ChatSuggestions.CountShape.Many, ChatSuggestions.shape(ChatCount.Known(ChatSuggestions.MANY + 1)))
    }
}
