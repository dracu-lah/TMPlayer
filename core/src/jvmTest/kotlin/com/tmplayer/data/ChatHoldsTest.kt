package com.tmplayer.data

import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Translator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The line under "No playable videos" that says what the chat does hold (U18). */
class ChatHoldsTest {

    private val base = "No playable videos in this chat."

    @Before
    fun english() = Translator.use(Languages.ENGLISH)

    @Test
    fun `a chat of photos says so`() {
        assertEquals("$base\n\nIt holds 340 photos.", ChatHolds(photos = 340, audio = 0, files = 0).under(base))
    }

    @Test
    fun `one of a kind is singular and kinds follow in a fixed order`() {
        assertEquals(
            "$base\n\nIt holds 1 photo. It holds 2 audio files. It holds 1 file that is not a video.",
            ChatHolds(photos = 1, audio = 2, files = 1).under(base),
        )
    }

    @Test
    fun `a chat that holds nothing else keeps the plain message`() {
        assertEquals(base, ChatHolds(0, 0, 0).under(base))
    }

    @Test
    fun `a count that did not come back is left out, not guessed`() {
        assertEquals(base, ChatHolds().under(base))
        assertEquals("$base\n\nIt holds 3 audio files.", ChatHolds(photos = null, audio = 3, files = null).under(base))
    }

    @Test
    fun `TDLib's minus one means unknown, never none`() {
        assertNull(ChatHolds.known(-1))
        assertNull(ChatHolds.known(null))
        assertEquals(0, ChatHolds.known(0))
        assertEquals(7, ChatHolds.known(7))
    }

    @Test
    fun `huge counts are written whole`() {
        assertEquals(listOf("It holds 2147483647 photos."), ChatHolds(photos = Int.MAX_VALUE).sentences().map { it.replace(",", "") })
    }
}
