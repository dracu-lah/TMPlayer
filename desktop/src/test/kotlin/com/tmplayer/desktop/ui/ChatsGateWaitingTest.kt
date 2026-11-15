package com.tmplayer.desktop.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tmplayer.data.ChatsGate
import com.tmplayer.ui.browse.ChatsGateWaiting
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The gate's wait (ON-7): words under the spinner, and a way out after four seconds. */
@OptIn(ExperimentalTestApi::class)
class ChatsGateWaitingTest {

    @Before @After fun reset() = ChatsGate.clearWaiver()

    @Test
    fun `the wait says what it waits for and offers a way out after four seconds`() = runComposeUiTest {
        setContent { TmMaterialTheme(dark = true) { ChatsGateWaiting() } }
        onNode(hasText("Loading your chats")).assertExists()
        onNode(hasText("Continue without choosing")).assertDoesNotExist()
        mainClock.advanceTimeBy(ChatsGate.WAY_OUT_MS + 100)
        waitForIdle()
        onNode(hasText("Continue without choosing") and hasClickAction()).assertExists()
        assertFalse(ChatsGate.waived.value)
        onNode(hasText("Continue without choosing")).performClick()
        assertTrue(ChatsGate.waived.value)
    }
}
