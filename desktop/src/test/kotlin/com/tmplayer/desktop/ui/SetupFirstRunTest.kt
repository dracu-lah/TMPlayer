package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tmplayer.data.DeviceFit
import com.tmplayer.data.Recommendation
import com.tmplayer.data.SettingsStore
import com.tmplayer.i18n.LanguageNotice
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Translator
import com.tmplayer.ui.nav.BackStack
import com.tmplayer.ui.nav.LocalBackStack
import com.tmplayer.ui.onboarding.SetupAndTour
import com.tmplayer.ui.onboarding.SetupScreen
import com.tmplayer.ui.onboarding.SetupState
import com.tmplayer.ui.onboarding.SetupStep
import com.tmplayer.ui.theme.TmMaterialTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The first run's shortcuts and its last press (ON-8, ON-9, ON-16, ON-20): one press to take the
 * recommended settings, the last page's writes surviving the screen leaving, and the remote's way
 * to the shortcut. The desktop's keyboard moves focus as a television's D-pad does.
 */
@OptIn(ExperimentalTestApi::class)
class SetupFirstRunTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-setup-first").resolve(SettingsStore.FILE_NAME).toFile()),
    )

    private fun button(label: String) = hasText(label) and hasClickAction()

    /**
     * ON-16: Done on the last page with an answer that writes three settings, in a host that drops
     * the screen the moment it is told the setup is done (as the shell does once the flag flips).
     */
    @Test
    fun lastPageWritesFinishAfterTheScreenLeaves() = runComposeUiTest {
        var showing by mutableStateOf(true)
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(1280.dp, 800.dp)) {
                    if (showing) {
                        SetupScreen(
                            settings,
                            remember { SetupState(listOf(SetupStep.Performance)) },
                            onDone = { showing = false },
                        )
                    }
                }
            }
        }
        onNode(button("Lighter")).performClick()
        onNode(button("Done")).performClick()
        waitForIdle()
        assertFalse(showing)
        waitUntil(timeoutMillis = WRITE_MS) {
            runBlocking { settings.lighterPictures.first() && settings.reduceMotion.first() && !settings.trickplay.first() }
        }
    }

    /**
     * ON-8 and ON-20 on a remote (the desktop's keyboard moves focus as a D-pad does): the focus
     * starts on Next, one Left press reaches "Use recommended settings", and Enter ends the setup
     * with every answer skipped, changing nothing.
     */
    @Test
    fun useRecommendedIsOnePressFromNextAndChangesNothing() = runComposeUiTest {
        var done = 0
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(1280.dp, 800.dp)) {
                    SetupAndTour(settings, listOf(SetupStep.Storage, SetupStep.Performance), onDone = { done++ })
                }
            }
        }
        waitUntil(timeoutMillis = WRITE_MS) { onAllNodes(button("Next") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        onNode(isFocused()).performKeyInput { pressKey(Key.DirectionLeft) }
        waitForIdle()
        onNode(button("Use recommended settings")).assertIsFocused()
        onNode(isFocused()).performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = WRITE_MS) { done > 0 }
        assertEquals(1, done)
        assertFalse(runBlocking { settings.lowStorageMode.first() })
        assertFalse(runBlocking { settings.lighterPictures.first() })
    }

    /** A device with recommendations gets them from the same press (the Device step starts on Apply). */
    @Test
    fun useRecommendedAppliesWhatTheDeviceIsRecommended() = runComposeUiTest {
        var done = false
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(1280.dp, 800.dp)) {
                    SetupAndTour(
                        settings,
                        listOf(SetupStep.Device),
                        onDone = { done = true },
                        fit = DeviceFit(shortOfMemory = true, shortOfStorage = false),
                        recommendations = listOf(Recommendation.LighterPictures, Recommendation.ReduceMotion),
                    )
                }
            }
        }
        onNode(button("Use recommended settings")).performClick()
        waitUntil(timeoutMillis = WRITE_MS) { done }
        assertTrue(runBlocking { settings.lighterPictures.first() && settings.reduceMotion.first() })
    }

    /** Pressing it twice ends the setup once. */
    @Test
    fun useRecommendedTwiceEndsOnce() = runComposeUiTest {
        var done = 0
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(1280.dp, 800.dp)) {
                    SetupAndTour(settings, listOf(SetupStep.Storage), onDone = { done++ })
                }
            }
        }
        onNode(button("Use recommended settings")).performClick()
        onNode(button("Use recommended settings")).performClick()
        waitUntil(timeoutMillis = WRITE_MS) { done > 0 }
        waitForIdle()
        assertEquals(1, done)
    }

    /** ON-9: Back on the first page does not end the setup; the button beside Next still does. */
    @Test
    fun backOnTheFirstPageDoesNotSkipTheSetup() = runComposeUiTest {
        var done = 0
        val back = BackStack()
        setContent {
            CompositionLocalProvider(LocalBackStack provides back) {
                TmMaterialTheme(dark = true) {
                    Box(Modifier.size(1280.dp, 800.dp)) {
                        SetupAndTour(settings, listOf(SetupStep.Storage), onDone = { done++ })
                    }
                }
            }
        }
        waitForIdle()
        assertFalse("nothing handles it: the system's Back applies", back.handleBack())
        waitForIdle()
        assertEquals(0, done)
        onNode(hasText("Theme and size")).assertExists()
        // The preferences' first page goes back to Appearance, still not out of the setup.
        onNode(button("Next")).performClick()
        waitForIdle()
        assertTrue(back.handleBack())
        waitForIdle()
        onNode(hasText("Theme and size")).assertExists()
        assertEquals(0, done)
    }

    /** ON-8: Performance is asked on a device short of memory only; Settings keeps the row. */
    @Test
    fun performanceIsAskedOnASlowDeviceOnly() = runComposeUiTest {
        var slow by mutableStateOf(false)
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(1280.dp, 800.dp)) {
                    key(slow) {
                        SetupAndTour(
                            settings,
                            listOf(SetupStep.Posters, SetupStep.Performance),
                            onDone = {},
                            fit = DeviceFit(shortOfMemory = slow, shortOfStorage = false),
                        )
                    }
                }
            }
        }
        // A device that is fine: Appearance, then Posters, and that is the last page.
        onNode(button("Next")).performClick()
        waitForIdle()
        onNode(button("Lighter")).assertDoesNotExist()
        onNode(button("Done")).assertExists()

        slow = true
        waitForIdle()
        onNode(button("Next")).performClick()
        waitForIdle()
        onNode(button("Next")).performClick()
        waitForIdle()
        onNode(button("Lighter")).assertExists()
        onNode(button("Done")).assertExists()
    }

    /** ON-10: the setup is in the language in use, so that language counts as announced. */
    @Test
    fun theSetupMarksItsLanguageAnnounced() = runComposeUiTest {
        Translator.use("es")
        try {
            setContent {
                TmMaterialTheme(dark = true) {
                    Box(Modifier.size(1280.dp, 800.dp)) {
                        SetupAndTour(settings, listOf(SetupStep.Storage), onDone = {})
                    }
                }
            }
            waitUntil(timeoutMillis = WRITE_MS) { runBlocking { settings.languageAnnounced.first() } == "es" }
            val saved = runBlocking { settings.language.first() }
            assertFalse(LanguageNotice.shouldShow(saved, "es", "es", hasCatalog = true))
            assertTrue("a later change announces", LanguageNotice.shouldShow(saved, "de", "es", hasCatalog = true))
        } finally {
            Translator.use(Languages.ENGLISH)
        }
    }

    private companion object {
        const val WRITE_MS = 3_000L
    }
}
