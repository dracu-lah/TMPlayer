package com.tmplayer.ui.settings

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
import androidx.compose.ui.semantics.getOrNull
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
 * The television's Appearance page with the remote on a row, in Light, Dark and Pure black, with and
 * without High contrast focus: the frames in `build/device-test/review/`, and (WP-06) a pixel read
 * of the focused row's top edge: ONE indicator (user, 2026-11-25), the fill alone
 * ([com.tmplayer.ui.theme.Tone.focusFill]) and with High contrast focus the fill and the ring in
 * [com.tmplayer.ui.theme.focusRingColor]; a row the remote is not on has neither. The rows draw the shared
 * [com.tmplayer.ui.theme.focusRingOnTv] (the Row role of `FocusRole`); the picker line is read back
 * through in [TvFocusRenderTest]. Its own class: a Settings page left in the process throws the
 * focus requests of the next test off.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class TvFocusSettingsRenderTest {

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

    /** The ring's colour as the theme on screen draws it, read from inside the composition. */
    private var ringColour: Color = Color.Unspecified
    private var fillColour: Color = Color.Unspecified

    /**
     * The television's Appearance page with the remote on a row (its landing row, then one down):
     * the frame, and the ring read back from its pixels.
     */
    private fun shoot(page: Page, high: Boolean) {
        FormFactor.override(true)
        runBlocking {
            settings.setThemeChoice(page.choice)
            settings.setPureBlack(page.black)
            settings.setHighContrastFocus(high)
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            TMPlayerTheme {
                val ring = com.tmplayer.ui.theme.focusRingColor()
                val fill = Tone.focusFill
                androidx.compose.runtime.SideEffect { ringColour = ring; fillColour = fill }
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    com.tmplayer.ui.settings.SettingsScreen(
                        chats = emptyList(),
                        onLoggedOut = {},
                        initialPage = com.tmplayer.ui.settings.SettingsPage.Appearance,
                        accountFolders = emptyList(),
                    )
                }
            }
        }
        idle(1_000)
        // Set once the page is up, as AppearanceRenderTest does: set before, the page's own start-up
        // read the defaults.
        runBlocking {
            settings.setThemeChoice(page.choice)
            settings.setPureBlack(page.black)
            settings.setHighContrastFocus(high)
        }
        repeat(10) { Thread.sleep(100); idle(200) }
        windowForRemote(activity)
        for (n in 1..2) {
            activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_DOWN))
            activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN))
            idle(500)
        }
        // On to a whole row when the two presses landed on a swatch or a chip: the ring is read on a row.
        var presses = 0
        while (rowsOf(activity).none { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Focused) == true } && presses++ < 6) {
            activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_DOWN))
            activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN))
            idle(500)
        }
        val frame = capture(activity)
        save("focus-tv-${page.name.lowercase()}-${if (high) "high" else "standard"}-settings.png", frame)
        assertRing(activity, frame, "${page.name} ${if (high) "high contrast" else "standard"}", high)
    }

    /** Every semantics node under [node], the node itself first. */
    private fun walk(node: androidx.compose.ui.semantics.SemanticsNode, out: MutableList<androidx.compose.ui.semantics.SemanticsNode>) {
        out += node
        node.children.forEach { walk(it, out) }
    }

    private fun rowsOf(activity: ComponentActivity): List<androidx.compose.ui.semantics.SemanticsNode> {
        val roots = mutableListOf<androidx.compose.ui.platform.ViewRootForTest>()
        fun find(v: android.view.View) {
            if (v is androidx.compose.ui.platform.ViewRootForTest) roots += v
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))
        }
        find(activity.window.decorView)
        val all = mutableListOf<androidx.compose.ui.semantics.SemanticsNode>()
        roots.forEach { walk(it.semanticsOwner.rootSemanticsNode, all) }
        // Whole rows: wide enough to be a row, tall enough to be one and not a swatch or a chip.
        return all.filter { n ->
            n.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Focused) &&
                n.boundsInWindow.width > 400f && n.boundsInWindow.height > 80f
        }
    }

    /** [colour] on the row's top edge: a pixel of it in the first 14 px of the middle column. */
    private fun colourOnTopEdge(frame: Bitmap, bounds: androidx.compose.ui.geometry.Rect, colour: Color): Boolean {
        val x = bounds.center.x.toInt().coerceIn(0, frame.width - 1)
        return (0 until 14).any { dy ->
            val y = (bounds.top.toInt() + dy).coerceIn(0, frame.height - 1)
            val c = Color(frame.getPixel(x, y))
            kotlin.math.abs(c.red - colour.red) < 0.02f && kotlin.math.abs(c.green - colour.green) < 0.02f && kotlin.math.abs(c.blue - colour.blue) < 0.02f
        }
    }

    private fun assertRing(activity: ComponentActivity, frame: Bitmap, what: String, high: Boolean) {
        val rows = rowsOf(activity)
        val focused = rows.filter { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Focused) == true }
        val others = rows.filter { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Focused) != true }
        assertTrue("$what: one row has the remote", focused.isNotEmpty())
        assertTrue("$what: the focused row is filled ($fillColour)", focused.any { colourOnTopEdge(frame, it.boundsInWindow, fillColour) })
        // The ring is drawn only when the viewer asked for High contrast focus; in daylight the ring colour is the fill's own.
        if (high) {
            assertTrue("$what: the focused row draws the ring ($ringColour)", focused.any { colourOnTopEdge(frame, it.boundsInWindow, ringColour) })
        } else if (ringColour != fillColour) {
            assertTrue("$what: the focused row is the fill alone, no ring ($ringColour)", focused.none { colourOnTopEdge(frame, it.boundsInWindow, ringColour) })
        }
        assertTrue("$what: a row the remote is not on has no ring or fill", others.isNotEmpty() && others.none { colourOnTopEdge(frame, it.boundsInWindow, ringColour) || colourOnTopEdge(frame, it.boundsInWindow, fillColour) })
    }

    /** A window the remote works in: out of touch mode and holding focus, as on a television. */
    private fun windowForRemote(activity: ComponentActivity) {
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
    }

    private fun capture(activity: ComponentActivity): Bitmap {
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        return bitmap
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
    fun lightHigh() = shoot(Page.Light, high = true)

    @Test
    fun darkStandard() = shoot(Page.Dark, high = false)

    @Test
    fun darkHigh() = shoot(Page.Dark, high = true)

    @Test
    fun blackStandard() = shoot(Page.Black, high = false)

    @Test
    fun blackHigh() = shoot(Page.Black, high = true)
}
