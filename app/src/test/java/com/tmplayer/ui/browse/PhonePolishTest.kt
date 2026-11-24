package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.pageBackScale
import com.tmplayer.ui.theme.TMPlayerTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** WP-44: Select all says how many it takes, the bar caption is 12 sp capped at 1.3, the page shrinks under a back gesture. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-port-night-xhdpi")
class PhonePolishTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private fun selectAllNodes(label: String): Int =
        rule.onAllNodesWithText(label).fetchSemanticsNodes().size +
            rule.onAllNodesWithContentDescription(label).fetchSemanticsNodes().size

    @Test
    fun selectAllSaysHowManyAreShown() {
        FormFactor.override(false)
        rule.setContent {
            TMPlayerTheme { SelectionBar(count = 2, onDownload = null, onSelectAll = {}, onCancel = {}, edge = 0.dp, shown = 300) }
        }
        rule.waitForIdle()
        assertTrue(selectAllNodes(L.gridSelectAllShown(300)) > 0)
        assertEquals("the plain words are not drawn beside it", 0, selectAllNodes(L.gridSelectAll))
    }

    @Test
    fun withoutACountItKeepsThePlainWords() {
        FormFactor.override(false)
        rule.setContent {
            TMPlayerTheme { SelectionBar(count = 2, onDownload = null, onSelectAll = {}, onCancel = {}, edge = 0.dp) }
        }
        rule.waitForIdle()
        assertTrue(selectAllNodes(L.gridSelectAll) > 0)
    }

    @Test
    fun theBarCaptionIsTwelveAndCappedAtOnePointThree() {
        assertEquals(12, BAR_CAPTION_SP)
        assertEquals(1.3f, BAR_CAPTION_MAX_SCALE, 0f)
    }

    @Test
    fun thePageShrinksWithTheGestureUnlessMotionIsReduced() {
        val plain = Modifier
        assertSame("at rest nothing is added", plain, plain.pageBackScale(0f, reduced = false))
        assertSame("reduced motion keeps the page put", plain, plain.pageBackScale(0.8f, reduced = true))
        assertNotSame(plain, plain.pageBackScale(0.5f, reduced = false))
    }
}
