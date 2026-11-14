package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tmplayer.data.FormFactor
import com.tmplayer.data.VideoFilter
import com.tmplayer.data.VideoSort
import com.tmplayer.i18n.L
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
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
 * The television's Sort menu with Play random in it (U16): the line leads the sheet, above the
 * order in force, and a press runs it. (The sheet is a dialog window, whose focus Robolectric does
 * not simulate: the opening focus stays on the order in force by construction, `orderGroup` in
 * VideoSortSheet, and is to be checked on a TV.)
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class VideoSortSheetFocusTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    private fun open(playRandom: (() -> Unit)?) {
        FormFactor.override(true)
        Motion.reduced = true
        val state = VideoMenuState(VideoSort.Newest, VideoFilter.All, onSort = {}, onFilter = {}, onPlayRandom = playRandom)
        rule.setContent { TMPlayerTheme { VideoSortSheet(state, onDismiss = {}) } }
        rule.waitForIdle()
    }

    @Test
    fun `Play random leads the sheet and its press runs it`() {
        var played = 0
        open { played++ }
        val random = rule.onNodeWithText(L.gridPlayRandom).getUnclippedBoundsInRoot()
        val newest = rule.onNodeWithText(L.gridSortNewest).getUnclippedBoundsInRoot()
        assertTrue("above the order lines", random.top < newest.top)
        rule.onNodeWithText(L.gridPlayRandom).performClick()
        assertEquals(1, played)
    }

    @Test
    fun `without a list to pick from the sheet is as it was`() {
        open(null)
        rule.onNodeWithText(L.gridSortNewest).assertExists()
        rule.onNodeWithText(L.gridPlayRandom).assertDoesNotExist()
    }
}
