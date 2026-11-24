package com.tmplayer.ui.lock

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.ThemeChoice
import com.tmplayer.tvux.Frame
import com.tmplayer.tvux.TvUxChecklist
import com.tmplayer.tvux.drawnChange
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.FocusContrast
import com.tmplayer.ui.theme.FocusStyle
import com.tmplayer.ui.theme.LocalDarkTheme
import com.tmplayer.ui.theme.LocalFocusStyle
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.contrastRatio
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A focused control must always look different from an unfocused one (user, 2026-11-25), also one
 * that rests in the accent, which is the television's focus fill too ([com.tmplayer.ui.theme.focusFillOver]).
 * For every TV control that rests in the accent (the profile pickers' primary button, the Switch
 * button of the profile panel), in the Dark and the Light theme and with High contrast focus: the
 * fill keeps its colour, one ring is drawn flush inside the edge (2 dp, 3 dp with High contrast, with
 * its outer line and keyline there), it stands at least 3:1 off the fill beside it, and nothing is
 * drawn outside the control's bounds.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvAccentFocusRenderTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    private var dark = true
    private var style = FocusStyle.tv(false)

    private fun show(choice: ThemeChoice, high: Boolean, content: @androidx.compose.runtime.Composable () -> Unit): TvUxChecklist {
        FormFactor.override(true)
        Motion.reduced = true
        runBlocking { SettingsStore(RuntimeEnvironment.getApplication()).setThemeChoice(choice) }
        rule.mainClock.autoAdvance = false
        val ux = TvUxChecklist(rule, "accentFocus")
        rule.setContent {
            TMPlayerTheme {
                CompositionLocalProvider(LocalFocusStyle provides FocusStyle.tv(high)) {
                    val d = LocalDarkTheme.current
                    val s = LocalFocusStyle.current
                    SideEffect { dark = d; style = s }
                    Box(Modifier.fillMaxSize().background(Tone.background)) { content() }
                }
            }
        }
        repeat(6) { Thread.sleep(100); ux.settle(300) }
        ux.windowForRemote()
        repeat(10) { if (ux.focused().isEmpty()) { Thread.sleep(50); ux.settle(300) } }
        return ux
    }

    private fun named(ux: TvUxChecklist, name: String): SemanticsNode =
        ux.focusables().firstOrNull { ux.nameOf(it).startsWith(name) }
            ?: error("no focusable '$name' in ${ux.focusables().map { ux.nameOf(it) }}")

    /** [name] at rest (the remote on [other]) against [name] focused: one flush ring, the fill kept, 3:1. */
    private fun assertOneRing(ux: TvUxChecklist, name: String, other: String, high: Boolean) {
        val node = named(ux, name)
        val neighbour = named(ux, other)
        assertTrue("$other takes the focus", ux.focus(neighbour))
        val restNode = ux.current(node)!!
        val rest: Frame = ux.frame()
        val rect = ux.layoutRect(restNode)
        val otherRect = ux.layoutRect(ux.current(neighbour)!!)
        assertTrue("$name takes the focus", ux.focus(restNode))
        val focused = ux.frame()
        val d = ux.density
        // Something is drawn, inside the control's own bounds.
        val skip = otherRect.takeUnless { it.overlaps(rect) }?.inflate(d * 2f)
        val drawn = drawnChange(rest, focused, rect.inflate(d * 24f), skip)
        assertNotNull("$name: focus draws something", drawn)
        val tol = d * 0.5f + 1f
        val allowed = rect.inflate(tol)
        assertTrue(
            "$name drew outside its bounds: $drawn over $rect",
            drawn!!.left >= allowed.left && drawn.top >= allowed.top && drawn.right <= allowed.right && drawn.bottom <= allowed.bottom,
        )
        // Down the middle from the top edge: the fill deep inside is unchanged, the ring is one band.
        val x = rect.center.x.toInt()
        val fillY = (rect.top + rect.height / 2f).toInt()
        val fillRest = rest.color(x - (rect.width / 4f).toInt(), fillY)
        val fillNow = focused.color(x - (rect.width / 4f).toInt(), fillY)
        assertTrue("$name: the fill stays the accent ($fillRest to $fillNow)", contrastRatio(fillRest, fillNow) < 1.2f)
        val ringPx = (style.ring.value * d).toInt().coerceAtLeast(1)
        val bandEnd = (rect.top + (style.outer.value + style.ring.value + style.inner.value) * d).toInt() + 2
        var changedRows = 0
        var best = 1f
        for (y in rect.top.toInt()..bandEnd) {
            val c = contrastRatio(rest.color(x, y), focused.color(x, y))
            if (c > 1.2f) changedRows++
            if (c > best) best = c
        }
        assertTrue("$name: a ring shows (best change $best:1)", best >= FocusContrast.Minimum)
        val most = ringPx + (if (high) ((style.outer.value + style.inner.value) * d).toInt() else 0) + 2
        assertTrue("$name: one thin indicator, $changedRows rows changed, at most $most", changedRows in 1..most)
        // The ring against the fill it sits on: the one just inside it.
        val ring = focused.color(x, (rect.top + (style.outer.value + style.ring.value / 2f) * d).toInt())
        val inside = focused.color(x, (rect.top + (style.outer.value + style.ring.value + style.inner.value) * d).toInt() + (3 * d).toInt())
        assertTrue("$name: ring $ring on fill $inside is ${contrastRatio(ring, inside)}:1", contrastRatio(ring, inside) >= FocusContrast.Minimum)
    }

    private fun scene(choice: ThemeChoice, high: Boolean) {
        val ux = show(choice, high) {
            Row(Modifier.padding(horizontal = 96.dp, vertical = 80.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                ProfileButton("Primary one", onClick = {}, primary = true)
                ProfileButton("Plain one", onClick = {})
                ProfileButton("Primary two", onClick = {}, primary = true)
            }
        }
        assertEquals("the look is the theme it names", choice != ThemeChoice.Light, dark)
        assertOneRing(ux, "Primary one", "Plain one", high)
        assertOneRing(ux, "Primary two", "Plain one", high)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun profilePrimaryDark() = scene(ThemeChoice.Dark, high = false)

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-notnight-xhdpi")
    fun profilePrimaryLight() = scene(ThemeChoice.Light, high = false)

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-mdpi")
    fun profilePrimaryDarkMdpi() = scene(ThemeChoice.Dark, high = false)

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-notnight-xhdpi")
    fun profilePrimaryLightHighContrast() = scene(ThemeChoice.Light, high = true)
}
