package com.tmplayer.desktop.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tmplayer.data.AddedChat
import com.tmplayer.data.AddedChats
import com.tmplayer.data.AddedChoice
import com.tmplayer.data.AddedMode
import com.tmplayer.ui.browse.AddChatsRequest
import com.tmplayer.ui.browse.HomeEmptyCard
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's empty page says the right thing for the account's choice (ON-15). */
@OptIn(ExperimentalTestApi::class)
class HomeEmptyCardTest {

    @After fun reset() = AddedChats.set(AddedChoice(AddedMode.Unanswered, emptyList()))

    @Test
    fun `chats not chosen get the forward a video advice`() = runComposeUiTest {
        AddedChats.set(AddedChoice(AddedMode.Unanswered, emptyList()))
        var saved = 0
        setContent { TmMaterialTheme(dark = true) { HomeEmptyCard(onOpenSaved = { saved++ }) } }
        onNode(hasText("Get your first video in")).assertExists()
        onNode(hasText("Open Saved Messages") and hasClickAction()).performClick()
        assertEquals(1, saved)
    }

    @Test
    fun `chosen chats with nothing to show say so and offer Choose chats`() = runComposeUiTest {
        AddedChats.set(AddedChoice(AddedMode.Picked, listOf(AddedChat(1, "Films"))))
        var saved = 0
        setContent { TmMaterialTheme(dark = true) { HomeEmptyCard(onOpenSaved = { saved++ }) } }
        onNode(hasText("Nothing to show from your chosen chats yet")).assertExists()
        onNode(hasText("Get your first video in")).assertDoesNotExist()
        onNode(hasText("Choose chats") and hasClickAction()).performClick()
        assertEquals(0, saved)
        assertTrue(AddChatsRequest.pending.value)
    }
}
