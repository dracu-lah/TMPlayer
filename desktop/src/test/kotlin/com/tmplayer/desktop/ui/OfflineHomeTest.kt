package com.tmplayer.desktop.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.HomeRow
import com.tmplayer.data.OfflineHome
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.data.WatchedStore
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.DesktopWatchCache
import com.tmplayer.i18n.L
import com.tmplayer.ui.browse.LocalOfflineHome
import com.tmplayer.ui.browse.OfflineHomeScope
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.file.Files

/**
 * Offline Home on the desktop (R4): "On this phone" leads, the rest is greyed with Needs internet,
 * a click on a greyed card opens its page (never the player), and online nothing changes.
 */
@OptIn(ExperimentalTestApi::class)
class OfflineHomeTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-off").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val dir = Files.createTempDirectory("tm-off-extras").toFile()
    private val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-off-watched").resolve(WatchedStore.FILE_NAME).toFile())),
        services = {
            DesktopExtras(
                prefs = DesktopPrefs(dir.resolve("desktop.properties")),
                updates = UpdateScheduler(settings.updatePrefs, check = { _, _ -> false }, onSkip = {}),
                watchCache = DesktopWatchCache(settings, { dir.resolve("cache") }),
            )
        },
    )

    private fun record(message: Long, title: String) = ResumeRecord(
        chatId = 2, messageId = message, fileId = 0, title = title, chatTitle = "Films",
        sizeBytes = 900_000_000, durationSec = 6_000, positionMs = 600_000, durationMs = 6_000_000, updatedAt = message,
    )

    private val saved = record(1, "Harbour Notes")
    private val far = record(2, "Distant Lights")
    private val rows = listOf(HomeRow.Continue(listOf(far, saved)))

    @Test
    fun `offline, On this phone leads and the other card is greyed and opens its page`() = runComposeUiTest {
        setContent {
            TmMaterialTheme(dark = true) {
                CompositionLocalProvider(LocalOfflineHome provides OfflineHome(true, downloads = listOf(saved))) {
                    OfflineHomeScope(rows) { shown ->
                        HomeRowsView(shell, shown, emptyList(), emptyMap(), onRowShown = {}, onArtWanted = {}, onRefresh = {})
                    }
                }
            }
        }
        onNodeWithText(L.homeRowOnPhone).assertExists()
        onAllNodesWithText(L.homeNeedsInternet).assertCountEquals(1)
        val card = hasClickAction() and hasAnyDescendant(hasText("Distant Lights", substring = true))
        // Space plays a card: on a greyed one it opens the page, and a click does the same.
        onAllNodes(card, useUnmergedTree = true).onFirst().requestFocus()
        onNode(androidx.compose.ui.test.isFocused()).performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Spacebar) }
        waitForIdle()
        // Its page, not the player.
        assertNotNull("detail=${shell.detail} playing=${shell.nowPlaying?.item?.title} up=${shell.playerUp}", shell.detail)
        assertFalse(shell.playerUp)
        assertEquals("Distant Lights", shell.detail?.item?.title)
    }

    @Test
    fun `online nothing is greyed`() = runComposeUiTest {
        setContent {
            TmMaterialTheme(dark = true) {
                OfflineHomeScope(rows) { shown ->
                    HomeRowsView(shell, shown, emptyList(), emptyMap(), onRowShown = {}, onArtWanted = {}, onRefresh = {})
                }
            }
        }
        onAllNodesWithText(L.homeNeedsInternet).assertCountEquals(0)
        onAllNodesWithText(L.homeRowOnPhone).assertCountEquals(0)
    }
}
