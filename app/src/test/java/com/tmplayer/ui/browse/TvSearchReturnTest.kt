package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
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
 * The television's search keeps the remote's place across a result's page (PL-7): OK on a poster
 * opens the page, which is a window of its own over the list and takes the focus; when it closes
 * the remote is back on that poster, not on the first tile or the field (Mi TV, 2026-10-23).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class TvSearchReturnTest {

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

    private fun video(id: Long, name: String) = MediaItem(
        chatId = 7, messageId = id, fileId = id.toInt(), title = name, sizeBytes = 900_000_000, durationSec = 5_400,
        mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = (1000 - id).toInt(), fileName = name,
    )

    @Test
    fun `back from a result's page the remote is on the poster it was opened from`() {
        FormFactor.override(true)
        Motion.reduced = true
        val groups = (1L..15L).map { id -> ResultGroup(key = "g$id", copies = listOf(video(id, "Night.Harbour.Part.$id.2021.1080p.mkv"))) }
        val state = GlobalSearchState(query = GlobalSearch.parse("night harbour"), groups = groups)
        val away = FocusRequester()
        var pageOpen by mutableStateOf(false)
        var opened = ""
        val titles = groups.map { GlobalSearch.titleOf(it) }
        rule.setContent {
            TMPlayerTheme {
                val ret = rememberTileReturn()
                Column(Modifier.fillMaxSize().background(Tone.background).padding(end = Tv.SafeH)) {
                    // What the page that opens takes the remote to: somewhere outside the poster.
                    Box(Modifier.size(20.dp).focusRequester(away).focusable())
                    Box(Modifier.weight(1f)) {
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
                            onOpenGroup = { group ->
                                ret.open(group.key)
                                opened = group.key
                                pageOpen = true
                                away.requestFocus()
                            },
                            onFilters = {},
                            onSort = {},
                            onLoadMore = {},
                            onRetry = {},
                            tileReturn = ret,
                            pageOpen = pageOpen,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        val poster = rule.onNodeWithContentDescription(titles[2])
        poster.requestFocus()
        rule.waitForIdle()
        poster.assertIsFocused()

        poster.performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals("g3", opened)
        // The page has the remote now, not the poster.
        rule.runOnIdle { }

        pageOpen = false
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithContentDescription(titles[2]).assertIsFocused()
    }
}
