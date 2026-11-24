package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performMultiModalInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.data.WatchedStore
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.DesktopWatchCache
import com.tmplayer.desktop.os.OsInfo
import com.tmplayer.ui.nav.BackStack
import com.tmplayer.ui.nav.LocalBackStack
import com.tmplayer.ui.nav.backInput
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Rule 8 and rule 11 on the Downloads page of the desktop: Ctrl+A starts a selection from nothing,
 * Ctrl+click and Ctrl+Space toggle a row, Shift+click takes a range in the page's order, Esc puts
 * the selection down, and after a row is deleted the keyboard lands on a neighbour.
 */
@OptIn(ExperimentalTestApi::class)
class DownloadsSelectionKeysTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-dl-sel").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val dir = Files.createTempDirectory("tm-dl-sel-extras").toFile()
    private val prefs = DesktopPrefs(dir.resolve("desktop.properties"))
    private val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-dl-sel-watched").resolve(WatchedStore.FILE_NAME).toFile())),
        services = {
            DesktopExtras(
                prefs = prefs,
                updates = UpdateScheduler(settings.updatePrefs, check = { _, _ -> false }, onSkip = {}),
                watchCache = DesktopWatchCache(settings, { dir.resolve("cache") }),
            )
        },
    )

    private val names = listOf("Alpha lecture", "Bravo lecture", "Charlie lecture", "Delta lecture", "Echo lecture")

    private fun folderWithFive(): File {
        val folder = dir.resolve("Downloads/TMPlayer").apply { mkdirs() }
        kotlinx.coroutines.runBlocking {
            names.forEachIndexed { at, title ->
                val mb = 10L + at
                val file = folder.resolve("$title.mkv").apply { java.io.RandomAccessFile(this, "rw").use { it.setLength(mb * 1024 * 1024) } }
                val item = MediaItem(
                    chatId = 2, messageId = 100L + at, fileId = 100 + at, title = title, sizeBytes = mb * 1024 * 1024, durationSec = 0,
                    mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null, date = 0,
                )
                settings.noteDownload(item, "Old lectures", file.absolutePath)
                // The page orders rows by the time each was noted (newest first); in a warm JVM five notes land
                // in one millisecond and tie, which scrambles the order the focus handoff test counts on.
                Thread.sleep(3)
            }
        }
        return folder
    }

    private fun androidx.compose.ui.test.ComposeUiTest.page(folder: File) {
        val stack = BackStack()
        setContent {
            CompositionLocalProvider(LocalBackStack provides stack) {
                TmMaterialTheme(dark = true) {
                    Box(Modifier.size(1280.dp, 900.dp).backInput(stack)) { DownloadsPage(shell, folder) }
                }
            }
        }
        // The rows arrive from the store a moment after the page is up.
        waitUntil(timeoutMillis = 15_000) { onAllNodes(hasText(names.last(), substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.picked(n: Int) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("$n selected", substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun androidx.compose.ui.test.ComposeUiTest.row(title: String) = onNode(hasText(title, substring = true) and androidx.compose.ui.test.hasClickAction())

    /**
     * The key a click is held with to toggle a row: Cmd on a Mac, where Ctrl+click is the right
     * click and opens the row's menu (the macOS runner did, and the test then found two roots).
     */
    private val toggleKey = if (OsInfo.isMac) Key.MetaLeft else Key.CtrlLeft

    /**
     * A click with [modifier] held, all sent to the row's own window rather than to onRoot(),
     * which fails whenever a popup (a menu, a tooltip) is open as a second root.
     */
    private fun androidx.compose.ui.test.ComposeUiTest.clickWith(modifier: Key, title: String) {
        row(title).performMultiModalInput {
            key { keyDown(modifier) }
            mouse { click() }
            key { keyUp(modifier) }
        }
    }

    @Test
    fun `Ctrl+A on the page starts a selection and Esc ends it`() = runComposeUiTest {
        page(folderWithFive())
        row(names[0]).requestFocus()
        onNode(isFocused()).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.A) } }
        picked(5)
        onNode(isFocused()).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        onNodeWithText("5 selected", substring = true).assertDoesNotExist()
    }

    @Test
    fun `Ctrl+click toggles one row and Shift+click takes the range to it`() = runComposeUiTest {
        page(folderWithFive())
        clickWith(toggleKey, names[0])
        picked(1)
        clickWith(Key.ShiftLeft, names.last())
        // The page lists newest first; whatever its order, a range takes every row between the two.
        picked(5)
    }

    @Test
    fun `Ctrl+Space toggles the focused row`() = runComposeUiTest {
        page(folderWithFive())
        row(names[2]).requestFocus()
        onNode(isFocused()).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Spacebar) } }
        picked(1)
        onNode(isFocused()).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Spacebar) } }
        waitForIdle()
        onNodeWithText("1 selected", substring = true).assertDoesNotExist()
    }

    @Test
    fun `Delete asks about the ticked rows and does nothing with none ticked (WP-51)`() = runComposeUiTest {
        page(folderWithFive())
        row(names[2]).requestFocus()
        // Nothing ticked: the key is not taken and no question comes.
        onNode(isFocused()).performKeyInput { pressKey(Key.Delete) }
        waitForIdle()
        onNodeWithText("Delete this download?", substring = true).assertDoesNotExist()
        clickWith(toggleKey, names[0])
        clickWith(toggleKey, names[1])
        picked(2)
        row(names[1]).requestFocus()
        onNode(isFocused()).performKeyInput { pressKey(Key.Delete) }
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Delete 2 downloads?", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `a deleted row hands the keyboard to the row after it, then the one before`() = runComposeUiTest {
        page(folderWithFive())
        // The page lists newest first: Echo, Delta, Charlie, Bravo, Alpha.
        row(names[2]).requestFocus()
        kotlinx.coroutines.runBlocking { settings.forgetDownload(2, 102) }
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText(names[2], substring = true)).fetchSemanticsNodes().isEmpty() }
        waitForIdle()
        row(names[1]).assertIsFocused()
        // The last row of the list: the one above it.
        row(names[0]).requestFocus()
        kotlinx.coroutines.runBlocking { settings.forgetDownload(2, 100) }
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText(names[0], substring = true)).fetchSemanticsNodes().isEmpty() }
        waitForIdle()
        row(names[1]).assertIsFocused()
    }
}
