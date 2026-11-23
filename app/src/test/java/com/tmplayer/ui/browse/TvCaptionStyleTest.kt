package com.tmplayer.ui.browse

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** A television's tile captions are at least 14 sp, whatever the 12 sp and 11 sp styles ask for. */
class TvCaptionStyleTest {
    private fun sp(v: Float) = TextUnit(v, TextUnitType.Sp)

    @Test
    fun smallStylesAreRaised() {
        for (size in listOf(11f, 12f, 13.9f)) {
            val raised = tvCaptionStyle(TextStyle(fontSize = sp(size), lineHeight = sp(16f)))
            assertEquals(14f, raised.fontSize.value, 0f)
            assertEquals(18f, raised.lineHeight.value, 0f)
        }
        assertEquals(14f, tvCaptionStyle(TextStyle()).fontSize.value, 0f)
    }

    @Test
    fun largeStylesAreLeftAlone() {
        val big = TextStyle(fontSize = sp(16f), lineHeight = sp(22f))
        assertSame(big, tvCaptionStyle(big))
    }
}
