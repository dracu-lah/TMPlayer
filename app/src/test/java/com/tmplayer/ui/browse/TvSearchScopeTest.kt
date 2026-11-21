package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.tmplayer.data.FormFactor
import com.tmplayer.data.GlobalSearch
import com.tmplayer.data.MediaItem
import com.tmplayer.data.ResultGroup
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The television's search says how much it found and offers the chats that were not added (HX62,
 * EG-F8): the count line sits above the posters, "Search my other chats" is a pill the remote
 * reaches (Up from the first poster, Down from the field when nothing was found) and OK on it asks.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class TvSearchScopeTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    private fun video(id: Long, name: String, chat: Long) = MediaItem(
        chatId = chat, messageId = id, fileId = id.toInt(), title = name, sizeBytes = 900_000_000, durationSec = 5_400,
        mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = (1000 - id).toInt(), fileName = name,
    )

    private fun show(state: GlobalSearchState, onOthers: () -> Unit = {}, onAdd: () -> Unit = {}) {
        FormFactor.override(true)
        Motion.reduced = true
        rule.setContent {
            TMPlayerTheme {
                Column(Modifier.fillMaxSize().background(Tone.background).padding(end = Tv.SafeH)) {
                    TvSearchScreen(
                        state = state,
                        text = "night harbour",
                        onText = {},
                        onSubmit = {},
                        chats = emptyList(),
                        favourites = emptySet(),
                        recentSearches = emptyList(),
                        suggestions = emptyList(),
                        progress = { null },
                        onOpenChat = {},
                        onOpenGroup = {},
                        onFilters = {},
                        onSort = {},
                        onLoadMore = {},
                        onRetry = {},
                        onSearchOthers = onOthers,
                        onAddChats = onAdd,
                    )
                }
            }
        }
        rule.waitForIdle()
        // Out of touch mode and with the window's focus, as a television's screen is.
        val viewRoot = rule.activity.window.decorView.parent
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "ensureTouchMode",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "windowFocusChanged",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
        rule.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0).requestFocus()
        rule.waitForIdle()
    }

    @Test
    fun `the count line is shown, the other chats pill leads the posters, Down reaches the first poster, OK asks`() {
        val groups = (1L..3L).map { id -> ResultGroup(key = "g$id", copies = listOf(video(id, "Night.Harbour.Part.$id.2021.1080p.mkv", id))) }
        val state = GlobalSearchState(query = GlobalSearch.parse("night harbour"), groups = groups, addedOnly = true)
        var asked = 0
        show(state, onOthers = { asked++ })
        rule.onNodeWithTag("search-count").assertIsDisplayed().assertTextContains("3", substring = true)
        val pill = rule.onNodeWithTag("search-other-chats")
        pill.requestFocus()
        rule.waitForIdle()
        pill.assertIsFocused()
        // OK on the pill asks for the other chats.
        pill.performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(1, asked)
        // Down from it is the first poster.
        pill.performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        val poster = rule.onNodeWithContentDescription(GlobalSearch.titleOf(groups[0]))
        poster.assertIsFocused()
        // And Up from the first poster comes back to it.
        poster.performKeyInput { pressKey(Key.DirectionUp) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("search-other-chats").assertIsFocused()
    }

    @Test
    fun `nothing in the added chats, the other chats pill and Add more chats are reached and pressed by the remote`() {
        val state = GlobalSearchState(query = GlobalSearch.parse("night harbour"), addedOnly = true)
        var asked = 0
        var added = 0
        show(state, onOthers = { asked++ }, onAdd = { added++ })
        val others = rule.onNodeWithTag("search-other-chats")
        rule.mainClock.advanceTimeBy(1_000)
        others.requestFocus()
        rule.waitForIdle()
        others.assertIsFocused()
        others.performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(1, asked)
        others.performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        rule.onNode(isFocused()).assertTextContains("Add more chats")
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(1, added)
    }

    @Test
    fun `with no chat added the note says every chat was searched, with Add more chats for the remote`() {
        val groups = listOf(ResultGroup(key = "g1", copies = listOf(video(1, "Night.Harbour.2021.1080p.mkv", 1))))
        val state = GlobalSearchState(query = GlobalSearch.parse("night harbour"), groups = groups, addedOnly = false)
        var added = 0
        show(state, onAdd = { added++ })
        rule.onNodeWithTag("search-count-row").assertIsDisplayed()
        // The other chats pill is not offered when every chat was searched.
        assertEquals(0, rule.onAllNodesWithTagCount("search-other-chats"))
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
