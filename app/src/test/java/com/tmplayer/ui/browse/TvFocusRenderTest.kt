package com.tmplayer.ui.browse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.ThemeChoice
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.TvChoiceRow
import com.tmplayer.ui.theme.FocusContrast
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.contrastRatio
import com.tmplayer.ui.theme.floatingSurface
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The television's focus as it is drawn (T8, EG-F22), in light, dark and Pure black, with and
 * without the High contrast focus switch: a tile with pale art (a Home or grid tile), a tile with
 * dark art, a picker line that fills under focus, each focused in turn, then the pixels read back.
 *
 * Every mark has to stand out from the page by 3:1 at its outer edge; with High contrast focus on,
 * the pale tile's ring also stands out from the white pixel next to it, which is what the contrasting
 * line and its keyline are for. Frames go to `build/device-test/review/` and to TMPLAYER_SHOTS=<dir>
 * when that is set. The D-pad's own movement is held by the existing focus tests.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class TvFocusRenderTest {

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private val settings get() = SettingsStore(RuntimeEnvironment.getApplication())

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    private enum class Page(val choice: ThemeChoice, val black: Boolean) {
        Light(ThemeChoice.Light, false),
        Dark(ThemeChoice.Dark, false),
        Black(ThemeChoice.Dark, true),
    }

    private fun shoot(page: Page, high: Boolean) {
        FormFactor.override(true)
        runBlocking {
            settings.setThemeChoice(page.choice)
            settings.setPureBlack(page.black)
            settings.setHighContrastFocus(high)
        }
        val requesters = mapOf("pale" to FocusRequester(), "dark" to FocusRequester(), "line" to FocusRequester())
        val rects = mutableMapOf<String, Rect>()
        val got = mutableMapOf<String, Boolean>()
        fun Modifier.target(tag: String) = onGloballyPositioned { rects[tag] = it.boundsInRoot() }.focusRequester(requesters.getValue(tag))
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            TMPlayerTheme {
                Column(
                    Modifier.fillMaxSize().background(Tone.background).padding(horizontal = 48.dp, vertical = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(40.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        for ((tag, art) in listOf("pale" to Color.White, "dark" to Color(0xFF10141F))) {
                            VideoTile(
                                title = "Tile $tag",
                                onClick = {},
                                onFocused = { got[tag] = true },
                                modifier = Modifier.width(220.dp).target(tag),
                            ) { Box(Modifier.fillMaxSize().background(art)) }
                        }
                    }
                    Column(Modifier.width(420.dp).floatingSurface().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvChoiceRow("English", selected = true, modifier = Modifier.target("line"), onClick = {})
                        TvChoiceRow("French", selected = false, onClick = {})
                    }
                }
            }
        }
        idle(2_000)
        // A window the remote works in: out of touch mode and holding focus, as on a television.
        val viewRoot = activity.window.decorView.parent
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "ensureTouchMode",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "windowFocusChanged",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
        idle(300)
        val name = "focus-tv-${page.name.lowercase()}-${if (high) "high" else "standard"}"
        for (tag in listOf("pale", "dark", "line")) {
            requesters.getValue(tag).requestFocus()
            idle(800)
            if (tag != "line") assertTrue("$tag took focus", got[tag] == true)
            val bitmap = capture(activity)
            save("$name-$tag.png", bitmap)
            check("$name $tag", activity, bitmap, rects.getValue(tag), high, tag == "pale", gap = if (tag == "line") 14 else 30)
        }
    }

    private fun idle(ms: Long) {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        var left = ms
        while (left > 0) {
            val step = minOf(left, 20L)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(step))
            left -= step
        }
    }

    private fun capture(activity: ComponentActivity): Bitmap {
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        return bitmap
    }

    /**
     * Reads the mark down the middle of [bounds]' top edge: from outside the edge inwards, the first
     * pixel that is not the page's is where the mark starts, and the pixels after it for as long as
     * the thickest mark is are the mark. Some of them stand out from the page by 3:1; with [high] on
     * a pale tile, some stand out from the art under it as well.
     */
    private fun check(what: String, activity: ComponentActivity, bitmap: Bitmap, bounds: Rect, high: Boolean, paleArt: Boolean, gap: Int) {
        val d = activity.resources.displayMetrics.density
        val x = ((bounds.left + bounds.right) / 2).toInt()
        fun at(y: Int) = Color(bitmap.getPixel(x, y.coerceIn(0, bitmap.height - 1)))
        val top = bounds.top.toInt()
        val page = at(top - (gap * d).toInt())
        var edge = top - ((gap / 2) * d).toInt()
        while (edge < top + (8 * d).toInt() && contrastRatio(at(edge), page) < 1.2f) edge++
        val mark = (0 until (9 * d).toInt()).map { at(edge + it) }
        val fromPage = mark.maxOf { contrastRatio(it, page) }
        assertTrue("$what: the mark stands out from the page by $fromPage, wanted ${FocusContrast.Minimum}", fromPage >= FocusContrast.Minimum)
        if (high && paleArt) {
            val art = at(top + (45 * d).toInt())
            val fromArt = mark.maxOf { contrastRatio(it, art) }
            assertTrue("$what: the mark stands out from the pale art by $fromArt, wanted ${FocusContrast.Minimum}", fromArt >= FocusContrast.Minimum)
        }
    }

    private fun save(name: String, bitmap: Bitmap) {
        val dirs = listOfNotNull(
            File("../build/device-test/review").absoluteFile,
            System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() }?.let(::File),
        )
        for (dir in dirs) {
            val file = dir.apply { mkdirs() }.resolve(name)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(file.length() > 0)
        }
    }

    @Test
    fun lightStandard() = shoot(Page.Light, high = false)

    @Test
    fun darkStandard() = shoot(Page.Dark, high = false)

    @Test
    fun blackStandard() = shoot(Page.Black, high = false)

    @Test
    fun lightHigh() = shoot(Page.Light, high = true)

    @Test
    fun darkHigh() = shoot(Page.Dark, high = true)

    @Test
    fun blackHigh() = shoot(Page.Black, high = true)




}
