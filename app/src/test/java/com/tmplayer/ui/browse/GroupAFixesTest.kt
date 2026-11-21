package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Raju's group A on the phone (4.2, 4.4): the "Jump back in" strip only over a list longer than
 * itself, and the star mode Home's "Choose favourites" opens on the chat list.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xxhdpi")
class GroupAFixesTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        Motion.reduced = false
        FormFactor.override(false)
    }

    private fun chats(n: Int) = (1..n).map { ChatSummary(it.toLong(), "Chat $it", miniThumbnail = null, photoFileId = 0, kind = ChatKind.Channel) }

    private fun list(
        count: Int,
        starMode: Boolean = false,
        onStar: (ChatSummary) -> Unit = {},
        onOpen: (ChatSummary) -> Unit = {},
        onDone: () -> Unit = {},
    ) {
        Motion.reduced = true
        FormFactor.override(false)
        val all = chats(count)
        rule.setContent {
            TMPlayerTheme {
                Box(Modifier.fillMaxSize()) {
                    ChatSection(
                        chats = all,
                        favorites = emptySet(),
                        recentChats = all.take(8),
                        insets = TouchInsets,
                        autoFocus = false,
                        onOpenChat = onOpen,
                        onHold = {},
                        launchChatId = 0,
                        starMode = starMode,
                        onStar = onStar,
                        onStarDone = onDone,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun listed(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    @Test
    fun `the strip is not worth its place over a short list`() {
        assertFalse(jumpBackInShown(0))
        assertFalse(jumpBackInShown(2))
        assertFalse("a strip of all eight chats over a list of eight says everything twice", jumpBackInShown(8))
        assertTrue(jumpBackInShown(9))
    }

    @Test
    fun `two chats show no Jump back in strip and each chat once`() {
        list(2)
        rule.onNodeWithText("Jump back in").assertDoesNotExist()
        assertEquals("Chat 1 is listed once", 1, listed("Chat 1"))
        rule.onNodeWithText("Chat 2").assertIsDisplayed()
    }

    @Test
    fun `a long list keeps its strip`() {
        list(12)
        rule.onNodeWithText("Jump back in").assertIsDisplayed()
    }

    @Test
    fun `in star mode a tap stars the chat instead of opening it, under a line with Done`() {
        val starred = mutableListOf<Long>()
        val opened = mutableListOf<Long>()
        var done = 0
        list(3, starMode = true, onStar = { starred += it.id }, onOpen = { opened += it.id }, onDone = { done++ })
        rule.onNodeWithText("Tap a chat to star it. Tap it again to take the star off.").assertIsDisplayed()
        rule.onNodeWithText("Chat 2").performClick()
        rule.waitForIdle()
        assertEquals(listOf(2L), starred)
        assertTrue("a tap in star mode never opens the chat", opened.isEmpty())
        rule.onNodeWithText("Done").performClick()
        assertEquals(1, done)
    }

    @Test
    fun `the favourites card on the phone has a button and short words, the television's has none`() {
        Motion.reduced = true
        FormFactor.override(false)
        var chose = 0
        rule.setContent {
            TMPlayerTheme {
                androidx.compose.foundation.layout.Column {
                    FavouritesHintCard(
                        "Star a chat to get a row of its videos here.",
                        start = 16.dp, end = 16.dp, buttonLabel = "Choose favourites", onChoose = { chose++ },
                    )
                    FavouritesHintCard("Hold OK on a chat", start = 16.dp, end = 16.dp)
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Star a chat to get a row of its videos here.").assertIsDisplayed()
        assertEquals("one button, on the card that was given a way to act", 1, listed("Choose favourites"))
        rule.onNodeWithText("Choose favourites").performClick()
        assertEquals(1, chose)
    }

    @Test
    fun `out of star mode a tap opens the chat and there is no line`() {
        val starred = mutableListOf<Long>()
        val opened = mutableListOf<Long>()
        list(3, onStar = { starred += it.id }, onOpen = { opened += it.id })
        rule.onNodeWithText("Tap a chat to star it. Tap it again to take the star off.").assertDoesNotExist()
        rule.onNodeWithText("Chat 2").performClick()
        rule.waitForIdle()
        assertEquals(listOf(2L), opened)
        assertTrue(starred.isEmpty())
    }
}
