package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performClick
import com.tmplayer.i18n.L
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.data.VideoFacets
import com.tmplayer.data.VideoFilter
import com.tmplayer.data.VideoSort
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
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
 * The television's Sort and filter panel. With Play random in it (U16): the line leads the sheet,
 * above the order in force, and a press runs it. By remote alone (U6): every line of the filter
 * groups (Length, Picture, With, Language, Added) is reached with Down from the order in force,
 * OK picks, the panel closes and the remote is back on the Sort button it was opened from.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class VideoSortSheetFocusTest {

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

    private var picked: VideoFacets? = null

    private fun open() {
        FormFactor.override(true)
        Motion.reduced = true
        rule.setContent {
            TMPlayerTheme {
                var sorting by remember { mutableStateOf(false) }
                var facets by remember { mutableStateOf(VideoFacets.NONE) }
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    Box(Modifier.padding(24.dp).clickable { sorting = true }) { Text("Sort") }
                }
                if (sorting) {
                    VideoSortSheet(
                        VideoMenuState(
                            sort = VideoSort.Newest,
                            filter = VideoFilter.All,
                            onSort = { sorting = false },
                            onFilter = { sorting = false },
                            facets = facets,
                            languages = listOf("ml", "ta"),
                            onFacets = {
                                facets = it
                                picked = it
                                sorting = false
                            },
                        ),
                        onDismiss = { sorting = false },
                    )
                }
            }
        }
        remoteMode()
    }

    /**
     * Every window the remote works in, the panel's own included once it is open: out of touch
     * mode, the newest one holding focus, as on a television.
     */
    private fun remoteMode() {
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        val roots = org.robolectric.util.ReflectionHelpers.getField<List<Any>>(global, "mRoots").toList()
        for (viewRoot in roots) {
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
                viewRoot, "ensureTouchMode",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
            )
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
                viewRoot, "windowFocusChanged",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, roots.last() === viewRoot),
            )
        }
        rule.waitForIdle()
    }

    /** A key to whatever the remote is on inside the panel (the page under it keeps a focus of its own). */
    private fun press(key: Key) {
        rule.onAllNodes(isFocused() and hasClickAction() and !hasText("Sort")).onFirst().performKeyInput { pressKey(key) }
    }

    /** Down until [label] is focused, at most [limit] presses; how many it took. */
    private fun downTo(label: String, limit: Int = 60): Int {
        repeat(limit) { step ->
            if (rule.onAllNodes(isFocused() and hasText(label), useUnmergedTree = false).fetchSemanticsNodes().isNotEmpty()) return step
            press(Key.DirectionDown)
            rule.waitForIdle()
        }
        throw AssertionError("$label never took the remote")
    }

    @Test
    fun `every new group is reached with Down, OK picks, and the remote goes back to Sort`() {
        open()
        rule.onNodeWithText("Sort").requestFocus()
        rule.onNodeWithText("Sort").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        remoteMode()
        rule.onNodeWithText("Newest").requestFocus()
        rule.onNodeWithText("Newest").assertIsFocused()
        for (line in listOf("Under 20 minutes", "4K", "720p and up", "HDR", "Subtitles", "Malayalam", "In the past year")) downTo(line)
        // Back up to HDR and pick it.
        repeat(80) {
            if (rule.onAllNodes(isFocused() and hasText("HDR")).fetchSemanticsNodes().isNotEmpty()) return@repeat
            press(Key.DirectionUp)
            rule.waitForIdle()
        }
        rule.onAllNodes(isFocused() and hasText("HDR")).onFirst().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(VideoFacets(hdr = true), picked)
        assertTrue(rule.onAllNodes(hasText("In the past year")).fetchSemanticsNodes().isEmpty())
        remoteMode()
        rule.onNodeWithText("Sort").assertIsFocused()
    }

    private fun openPlain(playRandom: (() -> Unit)?) {
        FormFactor.override(true)
        Motion.reduced = true
        val state = VideoMenuState(VideoSort.Newest, VideoFilter.All, onSort = {}, onFilter = {}, onPlayRandom = playRandom)
        rule.setContent { TMPlayerTheme { VideoSortSheet(state, onDismiss = {}) } }
        rule.waitForIdle()
    }

    @Test
    fun `Play random leads the sheet and its press runs it`() {
        var played = 0
        openPlain { played++ }
        val random = rule.onNodeWithText(L.gridPlayRandom).getUnclippedBoundsInRoot()
        val newest = rule.onNodeWithText(L.gridSortNewest).getUnclippedBoundsInRoot()
        assertTrue("above the order lines", random.top < newest.top)
        rule.onNodeWithText(L.gridPlayRandom).performClick()
        assertEquals(1, played)
    }

    @Test
    fun `without a list to pick from the sheet is as it was`() {
        openPlain(null)
        rule.onNodeWithText(L.gridSortNewest).assertExists()
        rule.onNodeWithText(L.gridPlayRandom).assertDoesNotExist()
    }

    private var clipSwitch = false
    private var resets = 0

    private fun openWithSizeSwitch(custom: String? = null) {
        FormFactor.override(true)
        Motion.reduced = true
        rule.setContent {
            TMPlayerTheme {
                var on by remember { mutableStateOf(false) }
                clipSwitch = on
                // Like the chat's screen: the switch does not close the panel; the grid changes behind it.
                VideoSortSheet(
                    VideoMenuState(
                        VideoSort.Newest, VideoFilter.All, onSort = {}, onFilter = {},
                        hideSmall = on, onHideSmall = { on = it; clipSwitch = it },
                        customLimits = custom, onResetLimits = { resets++ },
                    ),
                    onDismiss = {},
                )
            }
        }
        remoteMode()
    }

    @Test
    fun `Down reaches the Hide small clips switch, OK toggles it and the remote stays on it`() {
        openWithSizeSwitch()
        rule.onNodeWithText("Newest").requestFocus()
        val label = L.gridHideSmallClips("50 MB")
        downTo(label)
        assertEquals(false, clipSwitch)
        press(Key.DirectionCenter)
        rule.waitForIdle()
        assertEquals("the switch is on", true, clipSwitch)
        // The panel is still open and the remote is still on the switch (focus stays after toggling).
        rule.onNodeWithText(label).assertIsFocused()
        press(Key.DirectionCenter)
        rule.waitForIdle()
        assertEquals("and off again", false, clipSwitch)
        rule.onNodeWithText(label).assertIsFocused()
    }

    @Test
    fun `a floor chosen before gets one line with a Reset, below the switch`() {
        openWithSizeSwitch(custom = "You'll see videos from 700 MB upwards.")
        rule.onNodeWithText("Newest").requestFocus()
        val line = L.gridSizeLimitOn("You'll see videos from 700 MB upwards.")
        downTo(L.gridHideSmallClips("50 MB"))
        press(Key.DirectionDown)
        rule.onNodeWithText(line).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(1, resets)
    }

    @Test
    fun `no custom limits means no extra line`() {
        openWithSizeSwitch()
        rule.onNodeWithText(L.gridHideSmallClips("50 MB")).assertExists()
        rule.onNodeWithText(L.gridFilterSizeLabel).assertExists()
    }
}
