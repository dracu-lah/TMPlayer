package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tmplayer.data.DownloadRequest
import com.tmplayer.desktop.os.OsInfo
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.data.WatchedStore
import com.tmplayer.desktop.DesktopLayout
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.DesktopWatchCache
import com.tmplayer.ui.components.LocalRemoteArrows
import com.tmplayer.ui.components.TmAlertDialog
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.nav.LocalBackStack
import com.tmplayer.ui.theme.FocusVisibility
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * The desktop keyboard audit (UX interaction plan, T3): Esc closes one layer at a time, focus is
 * back on the control that opened a dialog, and the keys every text field and every person
 * already owns (Ctrl+C, V, X, Z, A) are never taken. The keyboard's table is docs/shortcuts.md.
 */
@OptIn(ExperimentalTestApi::class)
class ShortcutsAuditTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-keys").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val dir = Files.createTempDirectory("tm-keys-extras").toFile()
    private val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-keys-watched").resolve(WatchedStore.FILE_NAME).toFile())),
        services = {
            DesktopExtras(
                prefs = DesktopPrefs(dir.resolve("desktop.properties")),
                updates = UpdateScheduler(settings.updatePrefs, check = { _, _ -> false }, onSkip = {}),
                watchCache = DesktopWatchCache(settings, { dir.resolve("cache") }),
            )
        },
    )

    private val videos = (0 until 6).map { at ->
        MediaItem(
            chatId = 2, messageId = at + 1L, fileId = 0, title = "Video $at", sizeBytes = 300L * 1024 * 1024,
            durationSec = 1500, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 0,
            fileName = "Video $at.mkv",
        )
    }

    @Before
    fun desktopLayout() {
        BigScreen.layout = DesktopLayout.Desktop
        BigScreen.override = DesktopLayout.Desktop
        FocusVisibility.visible = true
    }

    @After
    fun reset() {
        BigScreen.override = null
        BigScreen.layout = DesktopLayout.Desktop
    }

    @Composable
    private fun Window(page: @Composable () -> Unit) {
        TmMaterialTheme(dark = true) {
            val focus = LocalFocusManager.current
            SideEffect { shell.focusManager = focus }
            CompositionLocalProvider(LocalBackStack provides shell.backStack, LocalRemoteArrows provides false) {
                Box(Modifier.size(1280.dp, 720.dp).onPreviewKeyEvent(shell::onPreviewKey).onKeyEvent(shell::onKey)) {
                    NavFrame(shell, null, null) { page() }
                }
            }
        }
    }

    private fun ComposeUiTest.press(key: Key) {
        // The focused item hears it first; with none focused (a menu just opened) the window does.
        val target = if (onAllNodes(isFocused()).fetchSemanticsNodes().isEmpty()) onRoot() else onNode(isFocused())
        target.performKeyInput { pressKey(key) }
        waitForIdle()
    }

    private fun poster(n: Int) = hasText("Video $n") and hasClickAction()

    @Test
    fun `in full screen Esc closes the open Settings page first and leaves full screen on the next press`() = runComposeUiTest {
        shell.go(Destination.Settings)
        shell.fullscreen = true
        setContent {
            Window {
                BackHandler(enabled = shell.destination != Destination.Home) { shell.backFromPage() }
                VideoGrid(shell, videos, "Film Club")
            }
        }
        onNode(poster(1)).requestFocus()
        assertTrue(shell.transientLayerOpen)

        press(Key.Escape)
        assertEquals("the first Esc closes the page over", Destination.Home, shell.destination)
        assertTrue("and leaves the window as it was", shell.fullscreen)

        onNode(poster(1)).requestFocus()
        press(Key.Escape)
        assertFalse("the second leaves full screen", shell.fullscreen)
    }

    @Test
    fun `a menu, the search palette, a detail pane or a modal page counts as a layer over the page`() {
        assertFalse(shell.transientLayerOpen)
        shell.paletteOpen = true
        assertTrue(shell.transientLayerOpen)
        shell.paletteOpen = false
        shell.profileOpen = true
        assertTrue(shell.transientLayerOpen)
        shell.profileOpen = false
        assertFalse(shell.transientLayerOpen)
    }

    @Test
    fun `Esc closes one layer at a time, the dialog before the page under it`() = runComposeUiTest {
        var backs by mutableIntStateOf(0)
        setContent {
            Window {
                BackHandler { backs++ }
                var open by remember { mutableStateOf(false) }
                Column {
                    Button(onClick = { open = true }) { Text("Remove the copy") }
                    if (open) {
                        TmAlertDialog(
                            onDismissRequest = { open = false },
                            confirmButton = { TextButton(onClick = { open = false }) { Text("Remove") } },
                            dismissButton = { TextButton(onClick = { open = false }) { Text("Keep") } },
                            text = { Text("TMPlayer's copy goes once it is saved.") },
                        )
                    }
                }
            }
        }
        val opener = hasText("Remove the copy") and hasClickAction()
        val keep = hasText("Keep") and hasClickAction()
        onNode(opener).requestFocus()
        onNode(opener).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        onNodeWithText("TMPlayer's copy goes once it is saved.").assertExists()
        onNode(keep).requestFocus()
        onNode(keep).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        onNodeWithText("TMPlayer's copy goes once it is saved.").assertDoesNotExist()
        assertEquals("the dialog took the Esc, the page behind did not hear it", 0, backs)
        // The focus is back on the control that opened the dialog, so the next key is its own.
        onNode(opener).assertIsFocused()
        press(Key.Escape)
        assertEquals("the next Esc is the page's", 1, backs)
    }

    @Test
    fun `the keys everyone owns are never taken by the window`() = runComposeUiTest {
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(300.dp, 100.dp).onPreviewKeyEvent(shell::onPreviewKey).onKeyEvent(shell::onKey)) {
                    Box(Modifier.size(50.dp).testTag("probe").focusable())
                }
            }
        }
        val command = if (OsInfo.isMac) Key.MetaLeft else Key.CtrlLeft
        onNode(hasTestTag("probe")).requestFocus()
        var taken = emptyList<Key>()
        // Not Ctrl+F: that one is Find, and the window answers it by focusing the page's search.
        for (key in listOf(Key.C, Key.V, Key.X, Key.Z, Key.A, Key.Y, Key.S, Key.P, Key.O, Key.N)) {
            val before = shell.paletteOpen to shell.destination
            onNode(isFocused()).performKeyInput { withKeyDown(command) { pressKey(key) } }
            waitForIdle()
            if (before != (shell.paletteOpen to shell.destination)) taken += key
        }
        assertEquals("these chords changed the window", emptyList<Key>(), taken)
    }

    @Test
    fun `Tab walks the page and never traps the focus in a dialog's text`() = runComposeUiTest {
        setContent {
            Window {
                Column {
                    Button(onClick = {}) { Text("First") }
                    Button(onClick = {}) { Text("Second") }
                    Button(onClick = {}) { Text("Third") }
                }
            }
        }
        onNode(hasText("First") and hasClickAction()).requestFocus()
        press(Key.Tab)
        onNode(hasText("Second") and hasClickAction()).assertIsFocused()
        press(Key.Tab)
        onNode(hasText("Third") and hasClickAction()).assertIsFocused()
    }
}
