package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * WP-15: the remote comes back to the tile the player was started from, in a grid of 5,000 videos:
 * after the shell stopped behind the player and was scrolled back to the top meanwhile, after the
 * system destroyed the shell ("Don't keep activities") and built it again with a list that starts
 * at the top, and when the video is gone by then (its neighbour takes the remote).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class ReturnAfterPlayerTest {

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

    /** The shell's lifecycle, driven by the test the way the player's activity starting and closing drives it. */
    private class Shell : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
        fun resume() { registry.currentState = Lifecycle.State.RESUMED }
        fun cover() { registry.currentState = Lifecycle.State.CREATED }
    }

    private val shell = Shell()
    private var items by mutableStateOf((0 until 5_000).map { "t$it" })
    private lateinit var state: LazyGridState
    private lateinit var scope: CoroutineScope

    private fun tag(i: Int) = "t$i"

    @Composable
    private fun Grid() {
        FormFactor.override(true)
        TMPlayerTheme {
            CompositionLocalProvider(LocalLifecycleOwner provides shell) {
                val ret = rememberTileReturn()
                // A fresh state, as a killed activity builds it: the list starts at the top.
                state = remember { LazyGridState() }
                scope = rememberCoroutineScope()
                LazyVerticalGrid(GridCells.Fixed(5), Modifier.fillMaxSize().background(Tone.background), state = state) {
                    items(items, key = { it }) { key ->
                        Box(
                            Modifier
                                .size(40.dp)
                                .testTag(key)
                                .returnTile(ret, key)
                                .focusable()
                                .onKeyEvent {
                                    if (it.key == Key.DirectionCenter && it.type == KeyEventType.KeyDown) {
                                        // The player starts from this tile.
                                        ret.open(key, items.indexOf(key))
                                        true
                                    } else {
                                        false
                                    }
                                },
                        )
                    }
                }
                ReturnToTile(
                    ret,
                    pageOpen = false,
                    ready = items.isNotEmpty(),
                    nearest = { at -> if (at < 0) null else items.getOrNull(at.coerceAtMost(items.lastIndex)) },
                ) { key ->
                    val at = items.indexOf(key)
                    if (at < 0) return@ReturnToTile false
                    state.scrollToItem(at)
                    true
                }
            }
        }
    }

    private fun scrollTo(index: Int) {
        rule.runOnIdle { scope.launch { state.scrollToItem(index) } }
        rule.waitForIdle()
    }

    private fun playFrom(index: Int) {
        scrollTo(index)
        rule.onNodeWithTag(tag(index)).requestFocus()
        rule.waitForIdle()
        rule.onNodeWithTag(tag(index)).assertIsFocused()
        rule.onNodeWithTag(tag(index)).performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        // The player covers the shell.
        rule.runOnUiThread { shell.cover() }
        rule.waitForIdle()
    }

    private fun comeBack() {
        rule.runOnUiThread { shell.resume() }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    @Test
    fun `back from the player the remote is on tile 4000 of 5000 though the list went to the top`() {
        Motion.reduced = true
        rule.runOnUiThread { shell.resume() }
        rule.setContent { Grid() }
        rule.waitForIdle()
        playFrom(4_000)
        scrollTo(0)
        comeBack()
        rule.onNodeWithTag(tag(4_000)).assertIsFocused()
    }

    @Test
    fun `back after the activity was destroyed and built again the remote is on tile 4000`() {
        Motion.reduced = true
        rule.runOnUiThread { shell.resume() }
        val restoration = StateRestorationTester(rule)
        restoration.setContent { Grid() }
        rule.waitForIdle()
        playFrom(4_000)
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        comeBack()
        rule.onNodeWithTag(tag(4_000)).assertIsFocused()
    }

    @Test
    fun `a video gone while the player ran leaves the remote on the tile that took its place`() {
        Motion.reduced = true
        rule.runOnUiThread { shell.resume() }
        rule.setContent { Grid() }
        rule.waitForIdle()
        playFrom(4_000)
        rule.runOnUiThread { items = items - tag(4_000) }
        scrollTo(0)
        comeBack()
        // The next video now stands at index 4000.
        rule.onNodeWithTag(tag(4_001)).assertIsFocused()
    }

    @Test
    fun `a resume with no player started leaves the remote where it was`() {
        Motion.reduced = true
        rule.runOnUiThread { shell.resume() }
        rule.setContent { Grid() }
        rule.waitForIdle()
        scrollTo(300)
        rule.onNodeWithTag(tag(300)).requestFocus()
        rule.waitForIdle()
        rule.runOnUiThread { shell.cover() }
        rule.waitForIdle()
        comeBack()
        rule.onNodeWithTag(tag(300)).assertIsFocused()
    }
}
