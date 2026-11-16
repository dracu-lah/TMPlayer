package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.components.PopoverChoice
import com.tmplayer.ui.components.ChoicePopover
import com.tmplayer.ui.components.rememberPopoverAnchor
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.TrackChoice
import com.tmplayer.i18n.L
import com.tmplayer.ui.theme.TmMaterialTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** The review wave's fixes in the desktop's third chunk and the shared components (R7). */
@OptIn(ExperimentalTestApi::class)
class ControlReviewR7Test {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-r7").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun learnt() = runBlocking { settings.learntLanguages.first() }

    @Test
    fun `Forget all asks first, Cancel keeps the languages and the confirm forgets them`() = runComposeUiTest {
        runBlocking { settings.setTrackChoice("The Long Road", TrackChoice(audioLanguage = "en", textLanguage = "fr", subtitlesOn = true)) }
        setContent { TmMaterialTheme(dark = true) { LearntLanguagesSetting(settings, scope) } }
        onNodeWithText(L.settingsLearntLanguages).performClick()
        waitForIdle()
        onNodeWithText(L.settingsLearntLanguagesForgetAll).performClick()
        waitForIdle()
        onNodeWithText(L.settingsLearntLanguagesForgetAllTitle).assertExists()
        assertEquals("a click on Forget all alone forgets nothing", 1, learnt().size)
        onNodeWithText(L.commonCancel).performClick()
        waitForIdle()
        assertEquals(1, learnt().size)
        onNodeWithText(L.settingsLearntLanguagesForgetAll).performClick()
        waitForIdle()
        // The confirm wears the same words as the button that asked; the dialog's is the last one.
        val nodes = onAllNodesWithText(L.settingsLearntLanguagesForgetAll)
        nodes[nodes.fetchSemanticsNodes().size - 1].performClick()
        waitUntil(timeoutMillis = 5_000L) { learnt().isEmpty() }
        assertTrue(learnt().isEmpty())
    }

    @Test
    fun `the popover's dim is the only click target besides its rows, and a long line wraps taller`() = runComposeUiTest {
        var dismissed = 0
        val picked = mutableListOf<Int>()
        val long = "A very long way to say the same thing in a language with long words and a big font"
        setContent {
            TmMaterialTheme(dark = true) {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                    Box(Modifier.fillMaxSize()) {
                        ChoicePopover(
                            open = true,
                            anchor = rememberPopoverAnchor(),
                            choices = listOf(PopoverChoice(1, "Short"), PopoverChoice(2, long)),
                            selected = 1,
                            onPick = { picked += it },
                            onDismiss = { dismissed++ },
                        )
                    }
                }
            }
        }
        waitForIdle()
        // The dim and the two rows: the card itself is no button that does nothing.
        assertEquals(3, onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
        onNodeWithText(long).assertHeightIsAtLeast(60.dp)
        onNodeWithText("Short").performClick()
        assertEquals(listOf(1), picked)
        assertEquals("a pick is not a tap on the dim", 0, dismissed)
    }
}
