package com.tmplayer.ui.browse

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text as TvText
import com.tmplayer.data.FormFactor
import com.tmplayer.data.Profile
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.ThemeChoice
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.components.TvChoiceRow
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingSurface
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * One focus indicator per control on a television (user, 2026-11-25: a filled pill with a second
 * thin ring a few pixels outside it, and thick borders everywhere). For the top bar's Home tab, a
 * choice row, a button and a tile, in the Dark and the Light theme, at 960 x 540 px (mdpi) and at
 * 1080p (xhdpi), the frame is read down a vertical line through the control's top edge at rest and
 * focused: the focus adds exactly one band of colour (a fill for a pill, a row or a button; a ring
 * of 2 dp at most for a tile that has no fill), never a fill and a ring, and never a gap ring.
 * With High contrast focus on, the viewer asked for the loud look and more than one band is right.
 *
 * Frames are written to `build/device-test/review/tv-focus-<label>-<scene>-<width>.png`; the label is the
 * `TVFOCUS_LABEL` environment variable (default "after"), so the same test run on an older tree writes
 * the "before" pictures (`design/tv-focus/`).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvOneIndicatorRenderTest {

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

    private val settings get() = SettingsStore(RuntimeEnvironment.getApplication())
    private val density get() = rule.activity.resources.displayMetrics.density

    private val rects = mutableMapOf<String, Rect>()
    private val requesters = mapOf(
        "park" to FocusRequester(),
        "tab" to FocusRequester(),
        "row" to FocusRequester(),
        "button" to FocusRequester(),
        "tile" to FocusRequester(),
    )

    private fun Modifier.target(tag: String) =
        onGloballyPositioned { rects[tag] = it.boundsInRoot() }.focusRequester(requesters.getValue(tag))

    private fun scene(choice: ThemeChoice, high: Boolean) {
        FormFactor.override(true)
        Motion.reduced = true
        runBlocking { settings.setThemeChoice(choice); settings.setHighContrastFocus(high) }
        val nav = TouchNav(
            account = null, favoriteCount = 0, downloadCount = 0, updateVersion = null,
            onHome = {}, onChats = {}, onSearch = {}, onSettings = {},
            onFavourites = {}, onDownloads = {}, onHistory = {}, onUpdate = {},
            profile = Profile(id = "a", folder = null, name = "Maya Lee"),
        )
        rule.setContent {
            TMPlayerTheme {
                Column(Modifier.fillMaxSize().background(Tone.background)) {
                    TvTopBar(
                        nav = nav, current = TvBarTab.Home, inProfile = false, searchOpen = false,
                        onHome = {}, onChats = {}, onSearch = {}, onRefresh = null,
                        currentFocus = requesters.getValue("tab"), contentFocus = FocusRequester(), overArt = false,
                                    )
                    Row(Modifier.padding(horizontal = 48.dp, vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        Column(Modifier.width(360.dp).floatingSurface().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TvChoiceRow("English", selected = true, onClick = {})
                            TvChoiceRow("French", selected = false, modifier = Modifier.target("row"), onClick = {})
                            TmButton(onClick = {}, modifier = Modifier.target("button")) { TvText("Save") }
                        }
                        VideoTile(
                            title = "A tile",
                            onClick = {},
                            modifier = Modifier.width(220.dp).target("tile"),
                        ) { Box(Modifier.fillMaxSize().background(Color(0xFF10141F))) }
                        Box(Modifier.size(24.dp).target("park").focusable())
                    }
                }
            }
        }
        val viewRoot = rule.activity.window.decorView.parent
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "ensureTouchMode",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "windowFocusChanged",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
        rule.waitForIdle()
        // The Home tab's own rect (the bar gives it no tag): the merged node named Home.
        rects["tab"] = rule.onNode(hasText("Home") and hasClickAction()).fetchSemanticsNode().boundsInRoot
    }

    private fun frame(): Bitmap {
        rule.waitForIdle()
        return rule.onRoot().captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun save(bitmap: Bitmap, scene: String) {
        val label = System.getenv("TVFOCUS_LABEL") ?: "after"
        val width = rule.activity.resources.displayMetrics.widthPixels
        val file = File("../build/device-test/review/tv-focus-$label-$scene-$width.png").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun close(a: Int, b: Int, tolerance: Int = 12): Boolean =
        abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) <= tolerance &&
            abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) <= tolerance &&
            abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)) <= tolerance

    /** The bands of colour down a vertical line: runs of at least 2 px (an anti-aliased pixel is not a band). */
    private fun bands(bitmap: Bitmap, x: Int, y0: Int, y1: Int, minRun: Int = 2): List<Pair<Int, Int>> {
        val runs = mutableListOf<Pair<Int, Int>>() // colour to length
        for (y in y0.coerceAtLeast(0)..y1.coerceAtMost(bitmap.height - 1)) {
            val c = bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), y)
            val last = runs.lastOrNull()
            if (last != null && close(last.first, c)) runs[runs.lastIndex] = last.first to last.second + 1 else runs += c to 1
        }
        return runs.filter { it.second >= minRun }.fold(mutableListOf()) { acc, r ->
            if (acc.isNotEmpty() && close(acc.last().first, r.first)) acc[acc.lastIndex] = acc.last().first to acc.last().second + r.second else acc += r
            acc
        }
    }

    /**
     * What focus on [tag] adds, as the bands down [dx] dp in from the control's left edge over the
     * band from 10 dp above its top edge to 14 dp below it: the focused frame's bands against the
     * rest frame's.
     */
    private fun added(tag: String, dx: Float, above: Float, scene: String, fromRight: Boolean = false): Pair<Int, List<Pair<Int, Int>>> {
        requesters.getValue("park").requestFocus()
        val rest = frame()
        requesters.getValue(tag).requestFocus()
        val focused = frame()
        save(focused, "$scene-$tag")
        val r = rects.getValue(tag)
        val x = (if (fromRight) r.right - dx * density else r.left + dx * density).toInt()
        val y0 = (r.top - above * density).toInt()
        val y1 = (r.top + 14 * density).toInt()
        // At mdpi a 2 dp ring is 2 px, and where it lands between two pixel rows it is two blended
        // pixels: a run of one is a band there. A gap ring would still add whole bands.
        val minRun = if (density < 1.5f && tag == "tile") 1 else 2
        val before = bands(rest, x, y0, y1, minRun)
        val after = bands(focused, x, y0, y1, minRun)
        assertTrue("$tag: the focus draws something ($scene)", after != before)
        return (after.size - before.size) to after
    }

    private fun check(choice: ThemeChoice, high: Boolean, label: String) {
        scene(choice, high)
        save(frame(), "$label-rest")
        // From a few dp over the control's edge inwards: a pill, a row and a button are the page and
        // then the fill (2 bands, a ring on top of the fill would be a third); a tile has no fill, so
        // it is the page, one ring, then the art (3 bands). High contrast focus is the loud look the
        // viewer asked for: it only has to draw something more than the plain look.
        val spots = listOf(
            Spot("tab", 9f, 2f, false, 2),
            Spot("row", 20f, 3f, true, 2),
            Spot("button", 14f, 3f, false, 2),
            Spot("tile", 30f, 8f, false, 3),
        )
        for (spot in spots) {
            val (extra, after) = added(spot.tag, spot.dx, spot.above, label, spot.fromRight)
            if (high) {
                assertTrue("${spot.tag} $label: High contrast draws its focus: $after", after.size >= spot.bands && extra >= 0)
            } else {
                assertTrue("${spot.tag} $label: one indicator, wanted ${spot.bands} bands from the page in, got ${after.size}: $after", after.size in spot.bands..(spot.bands + if (density < 1.5f && spot.tag == "tile") 2 else 0))
            }
        }
        if (!high) {
            // A tile's ring is 2 dp: its band is no thicker than 2 dp and a pixel or two of anti-aliasing.
            val (_, after) = added("tile", 30f, 8f, label)
            val ring = after.drop(1).dropLast(1).sumOf { it.second }
            assertTrue("the tile's ring is thin: $after at density $density", ring <= (2 * density).toInt() + 2)
        }
    }

    private class Spot(val tag: String, val dx: Float, val above: Float, val fromRight: Boolean, val bands: Int)

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun darkAt1080p() = check(ThemeChoice.Dark, high = false, label = "dark")

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-notnight-xhdpi")
    fun lightAt1080p() = check(ThemeChoice.Light, high = false, label = "light")

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-mdpi")
    fun darkAt960x540() = check(ThemeChoice.Dark, high = false, label = "dark")

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun highContrastAddsItsBands() = check(ThemeChoice.Dark, high = true, label = "high")
}
