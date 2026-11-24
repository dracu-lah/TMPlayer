package com.tmplayer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

/**
 * An interface size being tried (WP-10): applied at once, the old size kept marked, put back by
 * Undo, the countdown or the next start, kept by Keep.
 */
class UiScaleTrialTest {

    private fun store() = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-scale").resolve(SettingsStore.FILE_NAME).toFile()),
    )

    @Test
    fun `a size tried applies at once and goes back when it is not kept`() = runBlocking {
        val settings = store()
        settings.setUiScale(UiScale.Larger)
        settings.tryUiScale(UiScale.Largest, UiScale.Larger)
        assertEquals(UiScale.Largest, settings.uiScale.first())
        assertEquals(UiScale.Larger, settings.revertUiScale())
        assertEquals(UiScale.Larger, settings.uiScale.first())
        assertNull("the mark went with it", settings.revertUiScale())
    }

    @Test
    fun `keeping a size clears the mark and leaves the size`() = runBlocking {
        val settings = store()
        settings.tryUiScale(UiScale.Larger, UiScale.Default)
        settings.keepUiScale()
        assertEquals(UiScale.Larger, settings.uiScale.first())
        // A start after a kept size changes nothing (nothing was left marked).
        assertNull(settings.revertUiScale())
        assertEquals(UiScale.Larger, settings.uiScale.first())
    }

    @Test
    fun `a size tried twice goes back to the first old size, not the one in between`() = runBlocking {
        val settings = store()
        settings.setUiScale(UiScale.Default)
        settings.tryUiScale(UiScale.Larger, UiScale.Default)
        settings.tryUiScale(UiScale.Largest, UiScale.Larger)
        assertEquals(UiScale.Default, settings.revertUiScale())
        assertEquals(UiScale.Default, settings.uiScale.first())
    }

    @Test
    fun `Back to the defaults clears a mark left over`() = runBlocking {
        val settings = store()
        settings.tryUiScale(UiScale.Largest, UiScale.Default)
        settings.resetAppearance()
        assertNull(settings.revertUiScale())
        assertEquals(UiScale.Default, settings.uiScale.first())
    }
}
