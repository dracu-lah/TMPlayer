package com.tmplayer.desktop.ui

import androidx.compose.ui.test.ExperimentalTestApi
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
}
