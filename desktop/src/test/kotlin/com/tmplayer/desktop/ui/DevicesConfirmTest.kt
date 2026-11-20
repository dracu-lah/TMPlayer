package com.tmplayer.desktop.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tmplayer.data.AccountSession
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Translator
import com.tmplayer.ui.settings.DevicesScreen
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Active sessions (HX89): ending a session always asks first, in place, naming the device, with
 * Cancel focused so a stray Enter or remote OK changes nothing, and this device's own session has
 * no End button at all. The page is the one screen phone, TV and desktop share; nothing here ends
 * a real session, because only the question is answered.
 */
@OptIn(ExperimentalTestApi::class)
class DevicesConfirmTest {

    @Before
    fun english() {
        Translator.use(Languages.ENGLISH)
    }

    private fun session(id: Long, current: Boolean, device: String) = AccountSession(
        id, current, AccountSession.Kind.Phone, device, "Android", "15", "TMPlayer", "1.27.0", false,
        loginDate = 100, lastActiveDate = 200, ip = "203.0.113.5", location = "Berlin, Germany",
    )

    private val sessions = listOf(
        session(1, current = true, device = "This Desk"),
        session(2, current = false, device = "Pixel 8"),
        session(3, current = false, device = "Living Room TV"),
    )

    @Test
    fun `this device has no End button, each other one has its own`() = runComposeUiTest {
        setContent { TmMaterialTheme(dark = true) { DevicesScreen(onClose = {}, preview = sessions) } }
        assertEquals("one End per other device", 2, onAllNodesWithText(L.sessionsEnd).fetchSemanticsNodes().size)
    }

    @Test
    fun `End asks first, naming the device, with Cancel focused, and Cancel ends nothing`() = runComposeUiTest {
        setContent { TmMaterialTheme(dark = true) { DevicesScreen(onClose = {}, preview = sessions) } }
        onAllNodesWithText(L.sessionsEnd)[0].performClick()
        waitForIdle()
        onNodeWithText(L.sessionsEndTitle).assertExists()
        onNodeWithText(L.sessionsEndConfirm("Pixel 8")).assertExists()
        onNodeWithText(L.commonCancel).assertIsFocused()
        onNodeWithText(L.commonCancel).performClick()
        waitForIdle()
        onNodeWithText(L.sessionsEndTitle).assertDoesNotExist()
        assertEquals("the End buttons are back", 2, onAllNodesWithText(L.sessionsEnd).fetchSemanticsNodes().size)
    }

    @Test
    fun `End all names every other device and has Cancel focused`() = runComposeUiTest {
        setContent { TmMaterialTheme(dark = true) { DevicesScreen(onClose = {}, preview = sessions) } }
        onNodeWithText(L.sessionsEndAll).performClick()
        waitForIdle()
        onNodeWithText(L.sessionsEndAllTitle).assertExists()
        // The question lists the other devices only, not the one in use.
        onNodeWithText(L.sessionsEndAllConfirmDevices("Pixel 8, Living Room TV")).assertExists()
        onNodeWithText(L.commonCancel).assertIsFocused()
    }
}
