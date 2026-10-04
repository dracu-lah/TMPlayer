package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SettingsStore
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

/**
 * The browse pages from the keyboard alone (B2.1, WAI-ARIA grid): the posters and the chat list
 * driven off screen with made-up items.
 */
@OptIn(ExperimentalTestApi::class)
class BrowseKeyboardTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-keys").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        services = { error("not used by these pages") },
    )

    private val videos = (0 until 10).map { at ->
        MediaItem(
            chatId = 2, messageId = at + 1L, fileId = 0, title = "Video $at", sizeBytes = 300L * 1024 * 1024,
            durationSec = 1500, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 0,
            fileName = "Video $at.mkv",
        )
    }

    private fun poster(n: Int) = hasText("Video $n") and hasClickAction()

    @Test
    fun `arrows home end and page keys move between posters`() = runComposeUiTest {
        // 1000 dp less the padding is four 208 dp posters a row: rows 0-3, 4-7, 8-9.
        setContent { TmMaterialTheme(dark = true) { Box(Modifier.size(1000.dp, 640.dp)) { VideoGrid(shell, videos, "Film Club") } } }
        onNode(poster(0)).requestFocus()
        onNode(poster(0)).assertIsFocused()

        fun press(key: Key) = onNode(isFocused()).performKeyInput { pressKey(key) }

        press(Key.DirectionRight)
        onNode(poster(1)).assertIsFocused()
        press(Key.DirectionDown)
        onNode(poster(5)).assertIsFocused()
        press(Key.MoveEnd)
        onNode(poster(7)).assertIsFocused()
        press(Key.DirectionRight) // the row's end: no wrap
        onNode(poster(7)).assertIsFocused()
        press(Key.MoveHome)
        onNode(poster(4)).assertIsFocused()
        press(Key.DirectionLeft) // the row's start: no wrap
        onNode(poster(4)).assertIsFocused()
        press(Key.DirectionDown) // into the short last row, which may need a scroll
        onNode(poster(8)).assertIsFocused()
        press(Key.DirectionUp)
        press(Key.DirectionUp)
        onNode(poster(0)).assertIsFocused()
        onNode(isFocused()).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveEnd) } }
        onNode(poster(9)).assertIsFocused()
        press(Key.PageUp)
        onNode(poster(1)).assertIsFocused()
    }

    @Test
    fun `shift F10 opens the overflow of the focused poster`() = runComposeUiTest {
        setContent { TmMaterialTheme(dark = true) { Box(Modifier.size(1000.dp, 640.dp)) { VideoGrid(shell, videos, "Film Club") } } }
        onNode(poster(2)).requestFocus()
        onNode(isFocused()).performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.F10) } }
        onNodeWithText("Play from start").assertExists()
        onNodeWithText("Copy link").assertExists()
    }

    @Test
    fun `enter and p play the focused poster`() = runComposeUiTest {
        setContent { TmMaterialTheme(dark = true) { Box(Modifier.size(1000.dp, 640.dp)) { VideoGrid(shell, videos, "Film Club") } } }
        onNode(poster(3)).requestFocus()
        onNode(isFocused()).performKeyInput { pressKey(Key.Enter) }
        assertEquals("Video 3", shell.nowPlaying?.item?.title)
        shell.closePlayer()
        onNode(poster(3)).requestFocus()
        onNode(isFocused()).performKeyInput { pressKey(Key.P) }
        assertEquals("Video 3", shell.nowPlaying?.item?.title)
    }

    @Test
    fun `the chat list moves a row at a time and opens with enter`() = runComposeUiTest {
        val chats = (1L..30L).map {
            ChatSummary(id = it, title = "Chat $it", miniThumbnail = null, photoFileId = 0, kind = ChatKind.Channel, unreadCount = 0)
        }
        var opened by mutableStateOf<ChatSummary?>(null)
        var starred by mutableStateOf(emptySet<Long>())
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(900.dp, 500.dp)) {
                    val list = remember { LazyListState() }
                    val nav = rememberKeyboardNav(remember(list) { ListSurface(list) })
                    ChatList(chats, starred, list, nav, onOpen = { opened = it }, onStar = { starred = starred + it.id })
                }
            }
        }
        fun row(n: Int) = hasText("Chat $n") and hasClickAction() and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Focused)
        onNode(row(1)).requestFocus()
        onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
        onNode(row(2)).assertIsFocused()
        onNode(isFocused()).performKeyInput { pressKey(Key.PageDown) }
        // A page is the rows on screen less one, so well past the third row.
        onNode(isFocused()).assert(!(hasText("Chat 1") or hasText("Chat 2") or hasText("Chat 3")))
        onNode(isFocused()).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveEnd) } }
        onNode(row(30)).assertIsFocused()
        onNode(isFocused()).performKeyInput { pressKey(Key.S) }
        assertEquals(setOf(30L), starred)
        onNode(isFocused()).performKeyInput { pressKey(Key.Enter) }
        assertEquals(30L, opened?.id)
    }

    @Test
    fun `p m a and r pin mute archive and mark read the focused chat, and shift F10 opens its menu`() = runComposeUiTest {
        val chats = listOf(
            ChatSummary(id = 1, title = "Quiet", miniThumbnail = null, photoFileId = 0, kind = ChatKind.Channel, unreadCount = 0),
            ChatSummary(id = 2, title = "Busy", miniThumbnail = null, photoFileId = 0, kind = ChatKind.Channel, unreadCount = 4),
        )
        val done = mutableListOf<String>()
        val actions = ChatRowActions(
            onTogglePinned = { done += "pin ${it.id}" },
            onToggleMuted = { done += "mute ${it.id}" },
            onToggleArchived = { done += "archive ${it.id}" },
            onMarkRead = { done += "read ${it.id}" },
        )
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(900.dp, 500.dp)) {
                    val list = remember { LazyListState() }
                    val nav = rememberKeyboardNav(remember(list) { ListSurface(list) })
                    ChatList(chats, emptySet(), list, nav, onOpen = {}, onStar = {}, actions = actions)
                }
            }
        }
        fun row(title: String) = hasText(title) and hasClickAction() and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Focused)
        onNode(row("Busy")).requestFocus()
        onNode(isFocused()).performKeyInput { pressKey(Key.P) }
        onNode(isFocused()).performKeyInput { pressKey(Key.M) }
        onNode(isFocused()).performKeyInput { pressKey(Key.A) }
        onNode(isFocused()).performKeyInput { pressKey(Key.R) }
        assertEquals(listOf("pin 2", "mute 2", "archive 2", "read 2"), done)

        // Nothing unread: R has nothing to clear, and the menu does not offer it.
        done.clear()
        onNode(row("Quiet")).requestFocus()
        onNode(isFocused()).performKeyInput { pressKey(Key.R) }
        assertEquals(emptyList<String>(), done)
        onNode(isFocused()).performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.F10) } }
        onNodeWithText("Pin to the top").assertExists()
        onNodeWithText("Mute").assertExists()
        onNodeWithText("Archive").assertExists()
        onNodeWithText("Mark as read").assertDoesNotExist()
        onNodeWithText("Pin to the top").performClick()
        assertEquals(listOf("pin 1"), done)
    }
}
