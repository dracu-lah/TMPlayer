package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.WatchedStore
import com.tmplayer.ui.nav.BackStack
import com.tmplayer.ui.nav.LocalBackStack
import com.tmplayer.ui.nav.backInput
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files

/**
 * Rule 8 of the UX interaction plan on the desktop: in a list, Ctrl+A selects all, Ctrl+Space (or
 * Ctrl+click) toggles one, Shift+click takes a range, Esc clears the selection, and a count shows.
 * Driven with made-up videos, off screen.
 */
@OptIn(ExperimentalTestApi::class)
class ListSelectionKeysTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-sel").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-sel-watched").resolve(WatchedStore.FILE_NAME).toFile())),
        services = { error("not used by these pages") },
    )

    private fun video(at: Int, saveable: Boolean = true) = MediaItem(
        chatId = 2, messageId = at + 1L, fileId = at + 1, title = "Video $at", sizeBytes = 300L * 1024 * 1024,
        durationSec = 1500, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 0,
        fileName = "Video $at.mkv", canBeSaved = saveable,
    )

    private val videos = (0 until 10).map { video(it) }

    private fun poster(n: Int) = hasText("Video $n") and hasClickAction()

    private fun androidx.compose.ui.test.ComposeUiTest.grid(items: List<MediaItem>) {
        val stack = BackStack()
        setContent {
            CompositionLocalProvider(LocalBackStack provides stack) {
                TmMaterialTheme(dark = true) {
                    Box(Modifier.size(1000.dp, 640.dp).backInput(stack)) { VideoGrid(shell, items, "Film Club") }
                }
            }
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.press(key: Key, ctrl: Boolean = false) =
        onNode(isFocused()).performKeyInput {
            if (ctrl) withKeyDown(Key.CtrlLeft) { pressKey(key) } else pressKey(key)
        }

    private fun androidx.compose.ui.test.ComposeUiTest.count(n: Int) =
        onNodeWithText("$n selected").assertExists()

    @Test
    fun `Ctrl+A picks every video a chat lets you keep and Esc puts them down`() = runComposeUiTest {
        grid(videos)
        onNode(poster(0)).requestFocus()
        press(Key.A, ctrl = true)
        count(10)
        press(Key.Escape)
        waitForIdle()
        onNodeWithText("10 selected").assertDoesNotExist()
    }

    @Test
    fun `Ctrl+A leaves out a video the chat will not let anyone keep`() = runComposeUiTest {
        grid(videos.mapIndexed { at, v -> if (at == 3) v.copy(canBeSaved = false) else v })
        onNode(poster(0)).requestFocus()
        press(Key.A, ctrl = true)
        count(9)
    }

    @Test
    fun `Ctrl+Space toggles the focused poster and Space then picks instead of playing`() = runComposeUiTest {
        grid(videos)
        onNode(poster(0)).requestFocus()
        press(Key.Spacebar, ctrl = true)
        count(1)
        press(Key.DirectionRight)
        onNode(poster(1)).assertIsFocused()
        // Something is picked, so Space adds this one rather than opening the player.
        press(Key.Spacebar)
        count(2)
        assertFalse("the player must not open while a selection is up", shell.nowPlaying != null)
        press(Key.Spacebar)
        count(1)
    }
}
