package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.data.WatchedStore
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.DesktopWatchCache
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * UX rule 3 on the desktop: Settings' search words are per visit. A page opened from a result keeps
 * them for Back, and once Settings has been left and is come back to the box is empty. The host
 * keeps a page's saved state the way the shell's page holder does.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsSearchLeaveDesktopTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-search-leave").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val dir = Files.createTempDirectory("tm-search-leave-extras").toFile()
    private val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-search-leave-watched").resolve(WatchedStore.FILE_NAME).toFile())),
        services = {
            DesktopExtras(
                prefs = DesktopPrefs(dir.resolve("desktop.properties")),
                updates = UpdateScheduler(settings.updatePrefs, check = { _, _ -> false }, onSkip = {}),
                watchCache = DesktopWatchCache(settings, { dir.resolve("cache") }),
            )
        },
    )

    private var shown by mutableStateOf("settings")

    private fun ComposeUiTest.host() {
        setContent {
            TmMaterialTheme(dark = true) {
                Surface(Modifier.fillMaxSize(), color = Tone.background) {
                    val holder = rememberSaveableStateHolder()
                    holder.SaveableStateProvider(shown) {
                        if (shown == "settings") SettingsPage(shell, "2.0.0") else Text("elsewhere")
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.typed(): String =
        onNode(hasSetTextAction()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun ComposeUiTest.settle() {
        waitForIdle()
        mainClock.advanceTimeBy(500)
        waitForIdle()
    }

    @Test
    fun `leaving Settings and returning finds the box empty`() = runComposeUiTest {
        shown = "settings"
        host()
        onNode(hasSetTextAction()).performTextInput("downmix")
        settle()
        assertEquals("downmix", typed())
        shown = "elsewhere"
        settle()
        shown = "settings"
        settle()
        assertEquals("", typed())
    }

    @Test
    fun `a page opened from a result keeps the words`() = runComposeUiTest {
        shown = "settings"
        host()
        onNode(hasSetTextAction()).performTextInput("downmix")
        settle()
        onNode(hasText("Downmix to stereo") and hasClickAction()).performClick()
        settle()
        assertTrue(onAllNodesWithText("Even out volume").fetchSemanticsNodes().isNotEmpty())
        shown = "elsewhere"
        settle()
        shown = "settings"
        settle()
        // Back where it was: the page is still open and its words are kept for the way back.
        assertTrue(onAllNodesWithText("Even out volume").fetchSemanticsNodes().isNotEmpty())
    }
}
