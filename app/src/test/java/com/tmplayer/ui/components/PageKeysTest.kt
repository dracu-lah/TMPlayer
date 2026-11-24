package com.tmplayer.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Page and Channel keys in a long list, and Back to the top (WP-12), over a real [LazyVerticalGrid]
 * of 20,000 synthetic items (a LazyGridState cannot be stubbed). A television window of 960 by 540 dp
 * with tiles 100 dp square in 4 columns shows 5 whole rows, so a screenful is 20 tiles.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class PageKeysTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    private lateinit var state: LazyGridState
    private var tops = 0
    private var leaves = 0
    private var focusedAt = -1

    private fun grid(count: Int = 20_000, onTop: (() -> Unit)? = null, tv: Boolean = true) {
        FormFactor.override(tv)
        Motion.reduced = true
        rule.setContent {
            TMPlayerTheme {
                // The screen's own Back, composed before the grid: what "leave" is when the grid lets Back through.
                androidx.activity.compose.BackHandler { leaves++ }
                state = rememberLazyGridState()
                val tiles = rememberPageTiles()
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        state = state,
                        modifier = Modifier.fillMaxSize().pageKeys(state, tiles, onTop),
                    ) {
                        items(count, key = { it }) { i ->
                            Box(Modifier.size(100.dp).pageTile(tiles, i).onFocusChanged { if (it.isFocused) focusedAt = i }.testTag("t$i").focusable())
                        }
                    }
                    BackToTop(state, tiles)
                }
            }
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onNodeWithTag("t$focusedAt").performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun jumpTo(index: Int) {
        rule.runOnUiThread { runBlocking { state.scrollToItem(index) } }
        rule.waitForIdle()
    }

    @Test
    fun `Page Down moves exactly one screenful and the tile is focused`() {
        grid()
        rule.onNodeWithTag("t2").requestFocus()
        press(Key.PageDown)
        // 5 whole rows of 4: the tile in column 2, 20 further on.
        rule.onNodeWithTag("t22").assertIsFocused()
        press(Key.PageDown)
        rule.onNodeWithTag("t42").assertIsFocused()
    }

    @Test
    fun `Channel keys and Fast forward are the same keys and Up goes back one screenful`() {
        grid()
        rule.onNodeWithTag("t1").requestFocus()
        press(Key.ChannelDown)
        rule.onNodeWithTag("t21").assertIsFocused()
        press(Key.MediaFastForward)
        rule.onNodeWithTag("t41").assertIsFocused()
        press(Key.ChannelUp)
        rule.onNodeWithTag("t21").assertIsFocused()
        press(Key.MediaRewind)
        rule.onNodeWithTag("t1").assertIsFocused()
    }

    @Test
    fun `Page Up at the first row focuses the first tile and the second press asks onTop`() {
        grid(onTop = { tops++ })
        rule.onNodeWithTag("t3").requestFocus()
        press(Key.PageUp)
        rule.onNodeWithTag("t0").assertIsFocused()
        assertEquals(0, tops)
        press(Key.PageUp)
        rule.onNodeWithTag("t0").assertIsFocused()
        assertEquals(1, tops)
    }

    @Test
    fun `from row 500 a Page key moves one screenful and the end lands on the last tile`() {
        grid()
        jumpTo(2_000)
        rule.onNodeWithTag("t2000").requestFocus()
        press(Key.PageDown)
        rule.onNodeWithTag("t2020").assertIsFocused()
        press(Key.PageUp)
        press(Key.PageUp)
        rule.onNodeWithTag("t1980").assertIsFocused()
        jumpTo(19_999)
        rule.onNodeWithTag("t19999").requestFocus()
        press(Key.PageDown)
        // Nothing past the end, and no looping back.
        rule.onNodeWithTag("t19999").assertIsFocused()
        assertTrue(state.firstVisibleItemIndex > 19_900)
    }

    @Test
    fun `the jump never composes the rows it skips`() {
        grid()
        rule.onNodeWithTag("t0").requestFocus()
        repeat(3) { press(Key.PageDown) }
        // 60 tiles on, a screenful composed (about 30 with the prefetch), never the 20,000.
        assertTrue(state.layoutInfo.visibleItemsInfo.size < 60)
        rule.onNodeWithTag("t60").assertIsFocused()
    }

    @Test
    fun `Back from row 500 lands on tile 0 and Back again leaves`() {
        grid()
        jumpTo(2_000)
        rule.onNodeWithTag("t2000").requestFocus()
        rule.waitForIdle()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.waitUntil(2_000) { state.firstVisibleItemIndex == 0 }
        rule.onNodeWithTag("t0").assertIsFocused()
        assertEquals("the first Back only goes to the top", 0, leaves)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals("the second Back leaves", 1, leaves)
    }

    @Test
    fun `Back near the top leaves at once`() {
        grid()
        rule.onNodeWithTag("t10").requestFocus()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(1, leaves)
    }

    @Test
    fun `an empty grid ignores the keys`() {
        grid(count = 0)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(1, leaves)
    }

    @Test
    fun `on a phone Page keys page but a headset's Fast forward and Rewind do not`() {
        grid(tv = false)
        rule.onNodeWithTag("t1").requestFocus()
        press(Key.MediaFastForward)
        rule.onNodeWithTag("t1").assertIsFocused()
        press(Key.MediaRewind)
        rule.onNodeWithTag("t1").assertIsFocused()
        press(Key.PageDown)
        rule.onNodeWithTag("t21").assertIsFocused()
    }

    /** History's shape: day headings that take no focus between the rows, in a LazyColumn. */
    @Test
    fun `a list with day headings lands the remote on a row, never on a heading`() {
        FormFactor.override(true)
        Motion.reduced = true
        lateinit var list: androidx.compose.foundation.lazy.LazyListState
        var row = -1
        rule.setContent {
            TMPlayerTheme {
                list = androidx.compose.foundation.lazy.rememberLazyListState()
                val tiles = rememberPageTiles()
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().pageKeys(list, tiles), state = list) {
                    for (d in 0 until 400) {
                        item(key = "day$d") { Box(Modifier.fillMaxWidth().height(40.dp).testTag("h$d")) }
                        items(5, key = { "r$d-$it" }) { i ->
                            Box(
                                Modifier.fillMaxWidth().height(60.dp).pageTile(tiles, "r$d-$i")
                                    .onFocusChanged { if (it.isFocused) row = d * 5 + i }
                                    .testTag("r$d-$i").focusable(),
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("r0-0").requestFocus()
        rule.onNodeWithTag("r0-0").performKeyInput { pressKey(Key.PageDown) }
        rule.waitForIdle()
        val first = row
        assertTrue("moved down to a row: $first", first in 3..12)
        rule.onNodeWithTag("r${first / 5}-${first % 5}").performKeyInput { pressKey(Key.PageDown) }
        rule.waitForIdle()
        assertTrue("moved on again: $row after $first", row > first)
        val deep = row
        rule.onNodeWithTag("r${deep / 5}-${deep % 5}").performKeyInput { pressKey(Key.PageUp) }
        rule.waitForIdle()
        assertTrue("moved back up: $row from $deep", row < deep)
    }

    /** The TV search's shape: a full width field above the poster grid, which takes no tile. */
    @Test
    fun `a grid with a full width header lands on the posters and Page Up goes to the first poster`() {
        FormFactor.override(true)
        Motion.reduced = true
        var poster = -1
        rule.setContent {
            TMPlayerTheme {
                val gridState = rememberLazyGridState()
                val tiles = rememberPageTiles()
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().pageKeys(gridState, tiles),
                ) {
                    item(key = "field", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().height(60.dp).testTag("field").focusable())
                    }
                    items(2_000, key = { "p$it" }) { i ->
                        Box(Modifier.size(100.dp).pageTile(tiles, "p$i").onFocusChanged { if (it.isFocused) poster = i }.testTag("p$i").focusable())
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("p2").requestFocus()
        rule.onNodeWithTag("p2").performKeyInput { pressKey(Key.PageDown) }
        rule.waitForIdle()
        assertTrue("a poster a screenful on: $poster", poster in 14..30)
        rule.onNodeWithTag("p$poster").performKeyInput { pressKey(Key.PageUp) }
        rule.waitForIdle()
        rule.onNodeWithTag("p2").assertIsFocused()
        rule.onNodeWithTag("p2").performKeyInput { pressKey(Key.PageUp) }
        rule.waitForIdle()
        rule.onNodeWithTag("p0").assertIsFocused()
    }
}
