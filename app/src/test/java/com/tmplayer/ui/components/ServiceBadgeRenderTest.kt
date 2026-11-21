package com.tmplayer.ui.components

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.data.NameColour
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The service tile on the phone, upright and at a 2.0 font (HX93), composed for real: the TMDB mark
 * on its white chip, a service with no mark in its name's colour, the tile the same size at a large
 * font, and the name beside it when asked.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-port-night-xhdpi")
class ServiceBadgeRenderTest {

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

    /** One variant of the badge, drawn in a host of its own so that a test can lay several side by side. */
    private class Variant(val tag: String, val service: Service, val fontScale: Float = 1f, val showName: Boolean = false)

    /** Draws every [variants] in one composition (a rule sets its content once) and returns each host's picture. */
    private fun draw(vararg variants: Variant): List<Bitmap> {
        Motion.reduced = true
        rule.setContent {
            val base = LocalDensity.current
            TMPlayerTheme {
                Column {
                    for (v in variants) {
                        CompositionLocalProvider(LocalDensity provides Density(base.density, v.fontScale)) {
                            Box(Modifier.testTag(v.tag).width(200.dp)) { ServiceBadge(v.service, size = 28.dp, showName = v.showName) }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        return variants.map { rule.onNodeWithTag(it.tag).captureToImage().asAndroidBitmap() }
    }

    private fun draw(service: Service) = draw(Variant("host", service)).single()

    private fun Bitmap.dp(value: Int): Int = (value * resources().displayMetrics.density).toInt()
    private fun resources() = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().resources

    @Test
    fun `the TMDB logo is on a white chip and drawn`() {
        val bitmap = draw(Service.Tmdb)
        // The chip's white above the letters, and something that is not white where the logo is.
        assertEquals(0xFFFFFFFF.toInt(), bitmap.getPixel(bitmap.dp(14), bitmap.dp(3)))
        val nonWhite = (0 until bitmap.dp(28)).any { x -> (0 until bitmap.dp(28)).any { y -> bitmap.getPixel(x, y).let { it != 0xFFFFFFFF.toInt() && (it ushr 24) == 0xFF } } }
        assertTrue("no logo drawn", nonWhite)
    }

    @Test
    fun `a service with no logo wears its initial in its name colour`() {
        val bitmap = draw(Service.AniList)
        val pixel = bitmap.getPixel(bitmap.dp(14), bitmap.dp(25))
        val (light, deep) = NameColour.pairOf(Service.AniList.label)
        for (shift in listOf(16, 8, 0)) {
            val got = (pixel ushr shift) and 0xFF
            val low = minOf((light ushr shift) and 0xFF, (deep ushr shift) and 0xFF)
            val high = maxOf((light ushr shift) and 0xFF, (deep ushr shift) and 0xFF) + 40
            assertTrue("channel $shift was $got, outside $low..$high", got in low..high)
        }
    }

    @Test
    fun `a 2 point 0 font leaves the tile as big as it was`() {
        val (normal, large) = draw(Variant("normal", Service.Tmdb, 1f), Variant("large", Service.Tmdb, 2f))
        assertEquals(normal.width, large.width)
        assertEquals(0xFFFFFFFF.toInt(), large.getPixel(large.dp(14), large.dp(3)))
        assertNotEquals("the tile is drawn", 0, large.getPixel(large.dp(14), large.dp(14)) ushr 24)
    }

    @Test
    fun `with the name the badge is wider than the tile`() {
        val (tile, withName) = draw(Variant("tile", Service.TvMaze), Variant("named", Service.TvMaze, showName = true))
        // Both hosts are 200 dp wide; the name's text adds light pixels to the right of the tile.
        val right = withName.dp(60)
        assertTrue("no name drawn", (right until withName.dp(120)).any { x -> (0 until withName.dp(28)).any { y -> (withName.getPixel(x, y) ushr 24) == 0xFF } })
        assertTrue("the tile alone has no name", (right until tile.dp(120)).all { x -> (0 until tile.dp(28)).all { y -> (tile.getPixel(x, y) ushr 24) != 0xFF || tile.getPixel(x, y) == tile.getPixel(tile.dp(150), 1) } })
    }

    @Test
    fun `the tile is decoration, never a stop and never read`() {
        Motion.reduced = true
        rule.setContent { TMPlayerTheme { Column { ServiceBadge(Service.Tmdb, showName = true) } } }
        rule.waitForIdle()
        rule.onAllNodes(isFocusable()).assertCountEquals(0)
        // The name is the one thing read.
        rule.onNodeWithText("TMDB").assertExists()
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("TMDB")).assertCountEquals(0)
    }
}
