package com.tmplayer.desktop.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tmplayer.i18n.L
import com.tmplayer.ui.browse.AddChatsRequest
import com.tmplayer.ui.components.StateAction
import com.tmplayer.ui.components.UiState
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Every chat removed: the empty Chats page offers Choose chats, and pressing it opens the picker
 * instead of reloading the same empty list (it did nothing before, and had no icon).
 */
@OptIn(ExperimentalTestApi::class)
class EmptyChooseChatsTest {

    @Before
    @After
    fun clean() {
        AddChatsRequest.take()
    }

    @Test
    fun `the empty state shows Choose chats and pressing it hands the action on`() = runComposeUiTest {
        var got: StateAction? = null
        setContent {
            TmMaterialTheme(dark = true) {
                StateBox<Unit>(UiState.Empty(L.browseNoChatsAdded, StateAction.AddChats), onAction = { got = it }) {}
            }
        }
        onNodeWithText(L.pickerChooseChats).performClick()
        assertEquals(StateAction.AddChats, got)
    }

    @Test
    fun `Choose chats asks for the picker and does not reload`() {
        var reloads = 0
        StateAction.AddChats.runOnChatsList { reloads++ }
        assertEquals(0, reloads)
        assertTrue("the picker was asked for", AddChatsRequest.take())
    }

    @Test
    fun `the other actions reload and do not ask for the picker`() {
        var reloads = 0
        StateAction.Refresh.runOnChatsList { reloads++ }
        StateAction.KeepLooking.runOnChatsList { reloads++ }
        assertEquals(2, reloads)
        assertFalse(AddChatsRequest.take())
    }

    @Test
    fun `only Choose chats carries a glyph before its words`() {
        assertNotNull(StateAction.AddChats.buttonIcon)
        StateAction.entries.filter { it != StateAction.AddChats }.forEach { assertNull(it.name, it.buttonIcon) }
    }
}
