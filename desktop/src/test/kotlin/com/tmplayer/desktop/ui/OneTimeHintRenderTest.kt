package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import com.tmplayer.data.HintIds
import com.tmplayer.data.SettingsStore
import com.tmplayer.ui.components.HintStyle
import com.tmplayer.ui.components.OneTimeHint
import com.tmplayer.ui.components.ProvideHintSlot
import com.tmplayer.ui.theme.TmMaterialTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

/** [OneTimeHint] in a real composition (WP-68): the per screen cap, and counting once per visit. */
@OptIn(ExperimentalTestApi::class)
class OneTimeHintRenderTest {

    private fun settings() = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-hint-render").resolve(SettingsStore.FILE_NAME).toFile()),
    )

    private fun androidx.compose.ui.test.ComposeUiTest.shown(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun `inside a provided slot two hints show one, without it both show`() = runComposeUiTest {
        val capped = settings()
        val loose = settings()
        setContent {
            TmMaterialTheme(dark = true) {
                Column {
                    ProvideHintSlot {
                        OneTimeHint(capped, "first", "Capped one", style = HintStyle.Bubble)
                        OneTimeHint(capped, "second", "Capped two", style = HintStyle.Bubble)
                    }
                    OneTimeHint(loose, "first", "Loose one", style = HintStyle.Bubble)
                    OneTimeHint(loose, "second", "Loose two", style = HintStyle.Bubble)
                }
            }
        }
        waitUntil(timeoutMillis = 5_000L) { shown("Loose one") == 1 && shown("Loose two") == 1 }
        waitForIdle()
        assertEquals("one hint on a provided screen", 1, shown("Capped one") + shown("Capped two"))
        assertEquals(1, shown("Loose one"))
        assertEquals(1, shown("Loose two"))
    }

    @Test
    fun `a counted hint is counted once per visit however often focus comes back`() = runComposeUiTest {
        val store = settings()
        var focused by mutableStateOf(false)
        setContent {
            TmMaterialTheme(dark = true) {
                OneTimeHint(store, "tile_options", "Hold OK", visible = focused, maxShows = 5, style = HintStyle.Bubble)
            }
        }
        repeat(4) {
            focused = true
            waitUntil(timeoutMillis = 5_000L) { shown("Hold OK") == 1 }
            focused = false
            waitUntil(timeoutMillis = 5_000L) { shown("Hold OK") == 0 }
        }
        assertEquals(1, runBlocking { store.hintCount("tile_options") })
    }

    @Test
    fun `nothing shows once the tour was skipped twice`() = runComposeUiTest {
        val store = settings()
        runBlocking {
            store.markTourSkipped()
            store.markTourSkipped()
        }
        setContent { TmMaterialTheme(dark = true) { OneTimeHint(store, "a", "Quiet", style = HintStyle.Bubble) } }
        waitForIdle()
        mainClock.advanceTimeBy(500)
        assertEquals(0, shown("Quiet"))
        assertEquals(0, runBlocking { store.hintCount("a") })
        assertEquals(2, runBlocking { store.hintCount(HintIds.TOUR_SKIPPED) })
    }
}
