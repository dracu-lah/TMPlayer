package com.tmplayer.desktop.player

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import com.tmplayer.i18n.L
import com.tmplayer.ui.i18n.ProvideStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "Add to a list" (HX43) in the desktop player's More options: on the first page for a video from a
 * message, absent for one with none, and reached from the keyboard as well as the mouse; choosing it
 * closes the menu and hands the player [MenuAction.AddToList], which opens the shared picker.
 */
@OptIn(ExperimentalTestApi::class)
class AddToListMenuTest {

    private fun menu(addToList: Boolean, closed: () -> Unit, acted: (MenuAction) -> Unit): @androidx.compose.runtime.Composable () -> Unit = {
        ProvideStrings {
            PlayerTheme {
                PlayerMenu(
                    menu = MenuAt(MenuPage.Main, MenuAt.Anchor.Subtitles),
                    status = PlaybackStatus(opened = true, durationMs = 2_634_000, positionMs = 1_260_000, playing = true),
                    tracks = emptyList(),
                    fullscreen = false,
                    ignoreClicks = false,
                    miniPlayerAvailable = false,
                    alwaysOnTopAvailable = false,
                    fromTelegram = true,
                    savable = false,
                    watchedLabel = L.playerMarkWatched,
                    sleepTimer = null,
                    onOpenMenu = {},
                    onClose = closed,
                    onAction = acted,
                    addToList = addToList,
                )
            }
        }
    }

    @Test
    fun aClickClosesTheMenuAndAsksForTheList() = runComposeUiTest {
        var action: MenuAction? = null
        var closed = 0
        // savable false is a chat that restricts saving: a list keeps a name, so the line stays.
        setContent(menu(true, { closed++ }, { action = it }))
        onNodeWithText(L.listsAddTo).performClick()
        assertEquals(MenuAction.AddToList, action)
        assertEquals(1, closed)
    }

    @Test
    fun theKeyboardReachesIt() = runComposeUiTest {
        var action: MenuAction? = null
        setContent(menu(true, {}, { action = it }))
        onNodeWithText(L.listsAddTo).requestFocus()
        onNodeWithText(L.listsAddTo).performKeyInput { pressKey(Key.Enter) }
        assertEquals(MenuAction.AddToList, action)
    }

    @Test
    fun aFileWithNoMessageHasNoLine() = runComposeUiTest {
        var action: MenuAction? = null
        setContent(menu(false, {}, { action = it }))
        onNodeWithText(L.listsAddTo).assertDoesNotExist()
        assertNull(action)
    }
}
