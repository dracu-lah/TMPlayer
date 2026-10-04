package com.tmplayer.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Test

class HiddenBySizeTextTest {

    @Test
    fun `one video and several read as English`() {
        assertEquals(
            "1 video is hidden by the video size limits. Change them in Settings to see everything.",
            hiddenBySizeText(1),
        )
        assertEquals(
            "3 videos are hidden by the video size limits. Change them in Settings to see everything.",
            hiddenBySizeText(3),
        )
    }
}
