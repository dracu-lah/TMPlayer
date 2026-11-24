package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.MediaItem
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.data.WatchedStore
import com.tmplayer.desktop.DesktopLayout
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.DesktopWatchCache
import com.tmplayer.desktop.os.OsInfo
import com.tmplayer.i18n.L
import com.tmplayer.ui.components.LocalRemoteArrows
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.nav.LocalBackStack
import com.tmplayer.ui.theme.FocusVisibility
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * Downloads as a place of its own on the desktop (R2): its button on the floating bar after Search
 * with the number running, Ctrl+J (Cmd+J on a Mac) to open it from anywhere and again to close it,
 * and on the big-screen mode's bar its tab after Chats, reached by the remote's Right.
 */
@OptIn(ExperimentalTestApi::class)
class DownloadsNavTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-dlnav").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val dir = Files.createTempDirectory("tm-dlnav-extras").toFile()
    private val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-dlnav-watched").resolve(WatchedStore.FILE_NAME).toFile())),
        services = {
            DesktopExtras(
                prefs = DesktopPrefs(dir.resolve("desktop.properties")),
                updates = UpdateScheduler(settings.updatePrefs, check = { _, _ -> false }, onSkip = {}),
                watchCache = DesktopWatchCache(settings, { dir.resolve("cache") }),
            )
        },
    )

    @Before
    fun clean() {
        FocusVisibility.visible = true
    }

    @After
    fun restore() {
        BigScreen.override = null
        BigScreen.layout = DesktopLayout.Desktop
        OfflineDownloads.forget(901)
        OfflineDownloads.forget(902)
    }

    private fun running(id: Long) = OfflineDownloads.note(
        OfflineDownloads.Progress(
            request = DownloadRequest.from(
                MediaItem(
                    chatId = 2, messageId = id, fileId = id.toInt(), title = "Clip $id", sizeBytes = 100_000_000, durationSec = 0,
                    mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 0,
                ),
                "Chat",
            ),
            downloadedBytes = 10_000_000,
            totalBytes = 100_000_000,
            stage = OfflineDownloads.Stage.Running,
            order = id,
        ),
    )

    @Composable
    private fun Window(content: @Composable () -> Unit = {}) {
        TmMaterialTheme(dark = true) {
            val focus = LocalFocusManager.current
            SideEffect { shell.focusManager = focus }
            CompositionLocalProvider(LocalBackStack provides shell.backStack, LocalRemoteArrows provides BigScreen.on) {
                Box(
                    Modifier
                        .size(1280.dp, 720.dp)
                        .onPreviewKeyEvent(shell::onPreviewKey)
                        .onKeyEvent(shell::onKey),
                ) {
                    BackHandler(enabled = shell.openChat == null && shell.destination != Destination.Home) { shell.backFromPage() }
                    NavFrame(shell, null, null) { content() }
                }
            }
        }
    }

    private fun button(label: String) = hasContentDescription(label) and hasClickAction()

    /**
     * The window, once its bar is up. The bar waits for Settings' "Icons only in the menu" to be
     * read from the store, which runs off the UI thread and is not something waitForIdle knows
     * about: on the slower macOS and Windows runners the first look found no bar yet.
     */
    private fun ComposeUiTest.window() {
        setContent { Window() }
        waitUntil(timeoutMillis = 10_000) { onAllNodes(button(L.browseTabHome)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.chord(key: Key) {
        val command = if (OsInfo.isMac) Key.MetaLeft else Key.CtrlLeft
        onNode(isFocused()).performKeyInput { withKeyDown(command) { pressKey(key) } }
        waitForIdle()
    }

    @Test
    fun `the bar has Downloads after Search, before the picture, and pressing it opens the page`() = runComposeUiTest {
        shell.go(Destination.Home)
        window()
        onNode(button(L.navDownloads)).assertExists()
        onNode(button(L.commonSearch)).assertExists()
        val search = onNode(button(L.commonSearch)).fetchSemanticsNode().boundsInRoot
        val downloads = onNode(button(L.navDownloads)).fetchSemanticsNode().boundsInRoot
        val you = onNode(button(L.navAccount)).fetchSemanticsNode().boundsInRoot
        assertTrue("Downloads after Search", downloads.left >= search.right)
        assertTrue("Downloads before the picture", you.left >= downloads.right)
        // The picture's menu no longer lists it.
        onNode(button(L.navAccount)).performClick()
        assertEquals("the card has no Downloads row, only the bar's button", 1, onAllDownloads())
        shell.profileOpen = false
        onNode(button(L.navDownloads)).performClick()
        assertEquals(Destination.Downloads, shell.destination)
        onNode(button(L.navDownloads)).assertIsSelected()
    }

    private fun ComposeUiTest.onAllDownloads(): Int =
        onAllNodes(androidx.compose.ui.test.hasText(L.navDownloads)).fetchSemanticsNodes().size

    @Test
    fun `the number running is on the button and read with its name`() = runComposeUiTest {
        running(901)
        running(902)
        shell.go(Destination.Home)
        window()
        onNode(button("${L.navDownloads}, 2")).assertExists()
        onNode(hasTestTag(NAV_BADGE_TAG), useUnmergedTree = true).assertExists()
        onNodeWithText("2", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `no count at zero`() = runComposeUiTest {
        shell.go(Destination.Home)
        window()
        onNode(button(L.navDownloads)).assertExists()
        assertEquals(0, onAllNodes(hasTestTag(NAV_BADGE_TAG), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `Ctrl+J opens Downloads from anywhere and again closes it back to the page it came from`() = runComposeUiTest {
        shell.go(Destination.Home)
        window()
        // Something focused inside the window, as a key event needs.
        onNode(button(L.browseTabHome)).requestFocus()
        onNode(button(L.browseTabHome)).assertIsFocused()
        chord(Key.J)
        assertEquals(Destination.Downloads, shell.destination)
        // Again from the page itself: the panel closes, Home is under it.
        shell.focusManager?.moveFocus(FocusDirection.Enter)
        onNode(isFocused()).performKeyInput { withKeyDown(if (OsInfo.isMac) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.J) } }
        waitForIdle()
        assertEquals(Destination.Home, shell.pageDestination)
        assertTrue(!shell.modalOpen)
    }

    @Test
    fun `Ctrl+Shift+J is not Downloads`() = runComposeUiTest {
        shell.go(Destination.Home)
        window()
        onNode(button(L.browseTabHome)).requestFocus()
        val command = if (OsInfo.isMac) Key.MetaLeft else Key.CtrlLeft
        onNode(isFocused()).performKeyInput { withKeyDown(command) { withKeyDown(Key.ShiftLeft) { pressKey(Key.J) } } }
        waitForIdle()
        assertEquals(Destination.Home, shell.destination)
    }

    @Test
    fun `on the big-screen bar Downloads is a tab after Chats and the remote's Right reaches it`() = runComposeUiTest {
        BigScreen.layout = DesktopLayout.Desktop
        BigScreen.override = DesktopLayout.Tv
        shell.showChats(shell.chatSection)
        window()
        val chats = onNode(hasContentDescription(L.navChats) and hasClickAction())
        chats.requestFocus()
        chats.assertIsFocused()
        val chatsBounds = chats.fetchSemanticsNode().boundsInRoot
        val downloadsBounds = onNode(button(L.navDownloads)).fetchSemanticsNode().boundsInRoot
        assertTrue("Downloads after Chats", downloadsBounds.left >= chatsBounds.right)
        onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        onNode(button(L.navDownloads)).assertIsFocused()
        onNode(isFocused()).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(Destination.Downloads, shell.destination)
    }
}
