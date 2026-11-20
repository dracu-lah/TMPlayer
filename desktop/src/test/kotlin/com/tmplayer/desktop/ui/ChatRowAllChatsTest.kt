package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
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
import com.tmplayer.ui.browse.BrowseTab
import com.tmplayer.ui.browse.BrowseSection
import com.tmplayer.ui.browse.filterChats
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The desktop chat row with "All chats" on (HX86): H and the row menu turn the switch off where
 * Remove would be, never removing the chat; with it off they remove as before. And the Favourites
 * page lists a starred chat that is not in the picks and opens it with Enter.
 */
@OptIn(ExperimentalTestApi::class)
class ChatRowAllChatsTest {

    private val chats = listOf(
        ChatSummary(id = 1, title = "Quiet", miniThumbnail = null, photoFileId = 0, kind = ChatKind.Channel),
        ChatSummary(id = 2, title = "Busy", miniThumbnail = null, photoFileId = 0, kind = ChatKind.Channel),
    )

    private fun row(title: String) =
        hasText(title) and hasClickAction() and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Focused)

    private fun androidx.compose.ui.test.ComposeUiTest.show(
        list: List<ChatSummary>,
        favourites: Set<Long>,
        actions: ChatRowActions,
        opened: MutableList<Long> = mutableListOf(),
    ) = setContent {
        TmMaterialTheme(dark = true) {
            Box(Modifier.size(900.dp, 500.dp)) {
                val state = remember { LazyListState() }
                val nav = rememberKeyboardNav(remember(state) { ListSurface(state) })
                ChatList(list, favourites, state, nav, onOpen = { opened += it.id }, onStar = {}, actions = actions)
            }
        }
    }

    @Test
    fun `with All chats on, h and the menu turn it off and Remove is not offered`() = runComposeUiTest {
        val done = mutableListOf<String>()
        show(
            chats, emptySet(),
            ChatRowActions(onHide = { done += "hide ${it.id}" }, onTurnOffAll = { done += "off" }, allChatsOn = true),
        )
        onNode(row("Busy")).requestFocus()
        onNode(isFocused()).performKeyInput { pressKey(Key.H) }
        assertEquals(listOf("off"), done)

        done.clear()
        onNode(row("Quiet")).requestFocus()
        onNode(isFocused()).performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.F10) } }
        onNodeWithText("Remove from TMPlayer").assertDoesNotExist()
        onNodeWithText("Turn off All chats").assertExists()
        onNodeWithText("Turn off All chats").performClick()
        assertEquals(listOf("off"), done)
    }

    @Test
    fun `with All chats off, h and the menu still remove`() = runComposeUiTest {
        val done = mutableListOf<String>()
        show(
            chats, emptySet(),
            ChatRowActions(onHide = { done += "hide ${it.id}" }, onTurnOffAll = { done += "off" }, allChatsOn = false),
        )
        onNode(row("Busy")).requestFocus()
        onNode(isFocused()).performKeyInput { pressKey(Key.H) }
        assertEquals(listOf("hide 2"), done)
        onNode(row("Quiet")).requestFocus()
        onNode(isFocused()).performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.F10) } }
        onNodeWithText("Turn off All chats").assertDoesNotExist()
        onNodeWithText("Remove from TMPlayer").assertExists()
    }

    @Test
    fun `Favourites lists a starred chat outside the picks and Enter opens it`() = runComposeUiTest {
        val section = BrowseSection.of(BrowseTab.Favorites)
        // The pool the page is handed (BrowseData.withStarred) holds the starred chat the picks leave out.
        val shown = filterChats(chats, section, setOf(2L), "")
        assertEquals(listOf(2L), shown.map { it.id })
        val opened = mutableListOf<Long>()
        show(shown, setOf(2L), ChatRowActions(), opened)
        onNode(row("Busy")).requestFocus()
        onNode(isFocused()).performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf(2L), opened)
    }
}
