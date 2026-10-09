package com.tmplayer.player

import com.tmplayer.i18n.Translator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunHintTest {

    private val s = Translator.english()

    @Test
    fun `every gesture on by default, one line each`() {
        val lines = FirstRunHint.phone(TouchPrefs(), s)
        assertEquals(4, lines.size)
        assertTrue(lines[0].startsWith("Double tap"))
        assertEquals(listOf(s.settingsSeekGesture, s.settingsBrightnessGesture, s.settingsVolumeGesture), lines.drop(1))
    }

    @Test
    fun `a swipe switched off in Settings is not taught`() {
        val lines = FirstRunHint.phone(TouchPrefs(seekGesture = false, volumeGesture = false), s)
        assertEquals(listOf(s.settingsBrightnessGesture), lines.drop(1))
    }

    @Test
    fun `the double tap line says the jump that is set`() {
        val lines = FirstRunHint.phone(TouchPrefs(doubleTapMs = 30_000), s)
        assertTrue(lines.first(), lines.first().contains("30 seconds"))
    }
}
