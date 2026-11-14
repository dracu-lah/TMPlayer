package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tmplayer.data.SettingsStore
import com.tmplayer.ui.onboarding.CrashAskHost
import com.tmplayer.ui.onboarding.CrashReporting
import com.tmplayer.ui.theme.TmMaterialTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * S1 on the desktop: the "Help fix crashes?" card shows once to an install never asked, is marked
 * asked as it shows, writes Settings' switch on an answer and starts or stops reporting, and never
 * shows again. Not in a build with no DSN, not under the kids lock, not while another card is up.
 */
@OptIn(ExperimentalTestApi::class)
class CrashAskRenderTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-crash-ask").resolve(SettingsStore.FILE_NAME).toFile()),
    )

    private val title = hasText("Help fix crashes?")

    @Test
    fun yesTurnsReportsOnAndTheCardNeverReturns() = runComposeUiTest {
        var started: Boolean? = null
        var run by mutableIntStateOf(0)
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(1280.dp, 800.dp)) {
                    // A new key is a new run of the app: nothing remembered but what was stored.
                    key(run) {
                        CrashAskHost(settings, CrashReporting(true) { started = it }, inShell = true, otherUp = false)
                    }
                }
            }
        }
        waitUntil(timeoutMillis = WRITE_MS) { onAllNodes(title).fetchSemanticsNodes().isNotEmpty() }
        // Marked asked as it shows, with no answer written: that is still off.
        waitUntil(timeoutMillis = WRITE_MS) { runBlocking { settings.crashReportsAsked.first() } }
        assertFalse(runBlocking { settings.crashReportsNow() })
        // Not blocking: the keyboard stays where it was.
        onNode(isFocused()).assertDoesNotExist()
        onNode(hasText("No") and hasClickAction()).assertExists()
        onNode(hasText("Read the privacy policy") and hasClickAction()).assertExists()

        onNode(hasText("Yes, send reports") and hasClickAction()).performClick()
        waitForIdle()
        onNode(title).assertDoesNotExist()
        waitUntil(timeoutMillis = WRITE_MS) { runBlocking { settings.crashReportsNow() } && started == true }

        run++
        waitForIdle()
        onNode(title).assertDoesNotExist()
    }

    @Test
    fun noKeepsReportsOffAndIsAnAnswer() = runComposeUiTest {
        var started: Boolean? = null
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(1280.dp, 800.dp)) {
                    CrashAskHost(settings, CrashReporting(true) { started = it }, inShell = true, otherUp = false)
                }
            }
        }
        waitUntil(timeoutMillis = WRITE_MS) { onAllNodes(title).fetchSemanticsNodes().isNotEmpty() }
        onNode(hasText("No") and hasClickAction()).performClick()
        waitForIdle()
        onNode(title).assertDoesNotExist()
        waitUntil(timeoutMillis = WRITE_MS) { started == false }
        assertFalse(runBlocking { settings.crashReportsNow() })
        assertTrue(runBlocking { settings.crashReportsAsked.first() })
    }

    @Test
    fun aReturningInstallThatAnsweredIsNotAsked() = runComposeUiTest {
        runBlocking { settings.setCrashReports(false) }
        setContent {
            TmMaterialTheme(dark = true) {
                CrashAskHost(settings, CrashReporting(true), inShell = true, otherUp = false)
            }
        }
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        onNode(title).assertDoesNotExist()
    }

    @Test
    fun noDsnOrAnotherCardShowNothingAndUseNothingUp() = runComposeUiTest {
        var available by mutableStateOf(false)
        var otherUp by mutableStateOf(false)
        setContent {
            TmMaterialTheme(dark = true) {
                CrashAskHost(settings, CrashReporting(available), inShell = true, otherUp = otherUp)
            }
        }
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        onNode(title).assertDoesNotExist()

        available = true
        otherUp = true
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        onNode(title).assertDoesNotExist()
        assertFalse(runBlocking { settings.crashReportsAsked.first() })

        // The other card closes: now it is asked.
        otherUp = false
        waitUntil(timeoutMillis = WRITE_MS) { onAllNodes(title).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theKidsLockShowsNothingAndUsesNothingUp() = runComposeUiTest {
        runBlocking {
            settings.setPin("2468")
            settings.setKidsLock(true)
        }
        setContent {
            TmMaterialTheme(dark = true) {
                CrashAskHost(settings, CrashReporting(true), inShell = true, otherUp = false)
            }
        }
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        onNode(title).assertDoesNotExist()
        assertEquals("still unasked, for a grown-up later", false, runBlocking { settings.crashReportsAsked.first() })
    }

    private companion object {
        const val WRITE_MS = 10_000L
    }
}
