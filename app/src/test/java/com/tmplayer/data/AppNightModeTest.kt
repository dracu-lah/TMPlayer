package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** WP-44: the chosen theme maps to the night mode Android keeps for the app (Android 12 and later). */
class AppNightModeTest {

    @Test
    fun `the chosen theme maps to the night mode Android keeps for the app`() {
        assertEquals(android.app.UiModeManager.MODE_NIGHT_NO, AppNightMode.modeFor(ThemeChoice.Light))
        assertEquals(android.app.UiModeManager.MODE_NIGHT_YES, AppNightMode.modeFor(ThemeChoice.Dark))
        assertEquals(android.app.UiModeManager.MODE_NIGHT_AUTO, AppNightMode.modeFor(ThemeChoice.System))
    }
}
