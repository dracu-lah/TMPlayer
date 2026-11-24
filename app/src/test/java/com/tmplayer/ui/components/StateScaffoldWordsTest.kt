package com.tmplayer.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.focusable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tv
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Every wait has words (WP-92) on a television at 960 x 540 dp: a skeleton draws no label for the
 * first second and one line from then on, below its first row and with no focus of its own;
 * a screen reader finds the skeleton as one indeterminate progress bar saying what loads; the
 * remote stays where it was until the content arrives; and the spinner stands still under Reduce
 * motion. Frame in `build/device-test/review/`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class StateScaffoldWordsTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private val chats = "Loading your chats"

    private val indeterminate = SemanticsMatcher("is an indeterminate progress bar") {
        it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) == ProgressBarRangeInfo.Indeterminate
    }

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
        Motion.television = false
    }

    private fun tv(content: @androidx.compose.runtime.Composable () -> Unit) {
        FormFactor.override(true)
        Motion.television = true
        rule.mainClock.autoAdvance = false
        rule.setContent { TmMaterialTheme(dark = true) { content() } }
    }

    @Test
    fun `no label at half a second, the label from a second, and the skeleton is described`() {
        tv { StateScaffold<Unit>(UiState.Loading(chats), loading = { ChatListSkeleton(columns = 2) }) {} }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText(chats).assertDoesNotExist()
        rule.onNode(hasContentDescription(chats) and indeterminate).assertExists()
        rule.mainClock.advanceTimeBy(600)
        rule.onNodeWithText(chats).assertExists()
        rule.onNode(hasContentDescription(chats) and indeterminate).assertExists()
    }

    @Test
    fun `the label is not over the first skeleton row and fits the screen`() {
        tv { StateScaffold<Unit>(UiState.Loading(chats), loading = { ChatListSkeleton(columns = 2) }) {} }
        rule.mainClock.advanceTimeBy(1_100)
        val bounds = rule.onNodeWithText(chats).getBoundsInRoot()
        // The television's first row: the safe margin and an 84 dp card.
        assertTrue("label top ${bounds.top} is over the first row", bounds.top >= Tv.SafeV + 84.dp)
        assertTrue(bounds.bottom <= 540.dp && bounds.right <= 960.dp && bounds.left >= 0.dp)
        val frame = rule.onRoot().captureToImage().asAndroidBitmap()
        File("../build/device-test/review").apply { mkdirs() }.resolve("skeleton-label-tv.png")
            .outputStream().use { frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `the media grid skeleton says what loads too`() {
        tv { StateScaffold<Unit>(UiState.Loading("Finding videos"), loading = { MediaGridSkeleton() }) {} }
        rule.mainClock.advanceTimeBy(1_100)
        rule.onNode(hasContentDescription("Finding videos") and indeterminate).assertExists()
        rule.onNodeWithText("Finding videos").assertExists()
    }

    @Test
    fun `the remote stays on the opener while the skeleton waits and the tile takes it once`() {
        val opener = FocusRequester()
        val tile = FocusRequester()
        var state: UiState<Unit> by mutableStateOf(UiState.Loading(chats))
        tv {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.size(40.dp).testTag("opener").focusRequester(opener).focusable())
                Box(Modifier.fillMaxSize()) {
                    StateScaffold(state, loading = { ChatListSkeleton(columns = 2) }) {
                        Box(Modifier.size(40.dp).testTag("tile").focusRequester(tile).focusable())
                        androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { tile.requestFocus() } }
                    }
                }
            }
        }
        rule.onNodeWithTag("opener").requestFocus()
        rule.onNodeWithTag("opener").assertIsFocused()
        rule.mainClock.advanceTimeBy(1_100)
        rule.onNodeWithText(chats).assertExists()
        // The label and the skeleton hold no focus: the remote is where the viewer put it.
        rule.onNodeWithTag("opener").assertIsFocused()
        rule.runOnUiThread { state = UiState.Content(Unit) }
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        repeat(5) { rule.mainClock.advanceTimeByFrame() }
        rule.onNodeWithTag("tile").assertIsFocused()
    }

    private fun spinnerFrame(): IntArray {
        val bitmap = rule.onNodeWithTag("spinner").captureToImage().asAndroidBitmap()
        return IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
    }

    @Test
    fun `under Reduce motion two frames of the spinner 200 ms apart are the same`() {
        Motion.reduced = true
        tv { Box(Modifier.testTag("spinner")) { Spinner() } }
        rule.mainClock.advanceTimeBy(50)
        val first = spinnerFrame()
        rule.mainClock.advanceTimeBy(200)
        val second = spinnerFrame()
        assertTrue("the arc is drawn", first.any { it ushr 24 != 0 && it != first[0] })
        assertTrue("still under Reduce motion", first.contentEquals(second))
        // It still says it is a progress bar.
        rule.onNode(SemanticsMatcher("a progress bar") { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
            .assertExists()
    }

    @Test
    fun `without Reduce motion the spinner moves`() {
        tv { Box(Modifier.testTag("spinner")) { Spinner() } }
        rule.mainClock.advanceTimeBy(50)
        val first = spinnerFrame()
        rule.mainClock.advanceTimeBy(200)
        val second = spinnerFrame()
        assertFalse("moves when motion is on", first.contentEquals(second))
    }
}
