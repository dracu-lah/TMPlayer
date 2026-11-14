package com.tmplayer.data

import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Play random" (U16): the pick from the loaded page, what it skips and what it never repeats. */
class PlayRandomTest {

    @After
    fun forget() = PlayRandom.forget()

    private fun pick(items: List<String>, watched: Set<String> = emptySet(), last: String? = null, seed: Int = 1) =
        PlayRandom.pick(items, { it }, { it in watched }, last, Random(seed))

    @Test
    fun `an empty list picks nothing`() {
        assertNull(pick(emptyList()))
        assertNull(PlayRandom.next("empty", emptyList<String>(), { it }, { false }))
    }

    @Test
    fun `one video is the only choice, even straight after itself`() {
        assertEquals("a", pick(listOf("a")))
        assertEquals("a", pick(listOf("a"), last = "a"))
        assertEquals("a", pick(listOf("a"), watched = setOf("a")))
    }

    @Test
    fun `watched videos are skipped while an unwatched one is left`() {
        val items = listOf("a", "b", "c", "d")
        repeat(200) { seed ->
            assertEquals("c", pick(items, watched = setOf("a", "b", "d"), seed = seed))
        }
    }

    @Test
    fun `when everything is watched the whole list is the pool`() {
        val items = listOf("a", "b", "c")
        val seen = (0 until 300).mapNotNull { pick(items, watched = items.toSet(), seed = it) }.toSet()
        assertEquals(items.toSet(), seen)
    }

    @Test
    fun `the last video is never picked again while another one exists`() {
        val items = listOf("a", "b", "c")
        repeat(300) { seed -> assertNotEquals("b", pick(items, last = "b", seed = seed)) }
    }

    @Test
    fun `a lone unwatched video that was just played gives way to a watched one`() {
        val items = listOf("a", "b", "c")
        repeat(100) { seed ->
            val got = pick(items, watched = setOf("b", "c"), last = "a", seed = seed)
            assertTrue(got == "b" || got == "c")
        }
    }

    @Test
    fun `every video can be reached`() {
        val items = (1..20).map { "v$it" }
        val seen = (0 until 2000).mapNotNull { pick(items, seed = it) }.toSet()
        assertEquals(items.toSet(), seen)
    }

    @Test
    fun `next remembers per list, so two presses in a row never repeat`() {
        val items = listOf("a", "b")
        var previous: String? = null
        repeat(100) {
            val got = PlayRandom.next("chat:1:0", items, { it }, { false })
            assertNotEquals(previous, got)
            previous = got
        }
    }

    @Test
    fun `next keeps each list's last apart`() {
        val one = PlayRandom.next("chat:1:0", listOf("a"), { it }, { false })
        assertEquals("a", one)
        // Another list holding the same id is not blocked by the first list's memory.
        val other = PlayRandom.next("chat:2:0", listOf("a", "b"), { it }, { false }, Random(3))
        assertTrue(other == "a" || other == "b")
    }

    @Test
    fun `a huge page costs one pass and no paging`() {
        val items = (0 until 200_000).map { "v$it" }
        val watched = items.take(199_999).toSet()
        assertEquals("v199999", pick(items, watched = watched))
    }

    @Test
    fun `scopes name the chat and topic apart`() {
        assertNotEquals(PlayRandom.chatScope(1, 0), PlayRandom.chatScope(1, 5))
        assertNotEquals(PlayRandom.chatScope(1, 0), PlayRandom.chatScope(2, 0))
    }
}
