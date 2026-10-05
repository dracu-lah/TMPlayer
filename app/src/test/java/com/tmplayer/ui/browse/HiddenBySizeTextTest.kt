package com.tmplayer.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Test

class HiddenBySizeTextTest {

    @Test
    fun `one video and several read as English`() {
        assertEquals(
            "1 video hidden by the size limits.",
            hiddenBySizeText(1),
        )
        assertEquals(
            "3 videos hidden by the size limits.",
            hiddenBySizeText(3),
        )
    }

    @Test
    fun `self-destructing videos are counted and pointed back to Telegram`() {
        assertEquals("1 self-destructing video is not shown: open it in Telegram.", hiddenSelfDestructingText(1))
        assertEquals("2 self-destructing videos are not shown: open them in Telegram.", hiddenSelfDestructingText(2))
    }

    @Test
    fun `the note carries only the sentences that have something to count`() {
        assertEquals(hiddenBySizeText(3), hiddenVideosText(bySize = 3, selfDestructing = 0))
        assertEquals(hiddenSelfDestructingText(1), hiddenVideosText(bySize = 0, selfDestructing = 1))
        assertEquals(
            hiddenBySizeText(1) + " " + hiddenSelfDestructingText(2),
            hiddenVideosText(bySize = 1, selfDestructing = 2),
        )
    }
}
