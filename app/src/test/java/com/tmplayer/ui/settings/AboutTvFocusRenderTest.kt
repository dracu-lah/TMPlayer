package com.tmplayer.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text as TvText
import com.tmplayer.data.FormFactor
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.ThemeChoice
import com.tmplayer.data.SupportRegion.Method
import com.tmplayer.i18n.L
import com.tmplayer.tvux.Frame
import com.tmplayer.tvux.TvUxChecklist
import com.tmplayer.tvux.drawnChange
import com.tmplayer.tvux.edgeContrast
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.about.SupportCard
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.components.TmSecondaryButton
import com.tmplayer.ui.components.TvConfirm
import com.tmplayer.ui.theme.FocusContrast
import com.tmplayer.ui.theme.FocusRole
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
import kotlin.math.abs

/**
 * Nothing on a television grows past its bounds (WP-03): the About page's Buy me a coffee and
 * GitHub sponsor rows, a `TmButton` in a `TvConfirm` and in a toolbar row, and a support card, each
 * focused and at rest in the Dark, the Light and the High contrast look. Every check reads the
 * drawn frame, not only the layout bounds (the old Coffee button grew in a graphics layer, which a
 * bounds check alone does not see):
 *
 * - the layout rect is the same focused and at rest;
 * - every pixel that changes under focus is inside that rect (a ring is drawn in, never out);
 * - the recipe draws a ring only under High contrast ([FocusRole.Row] and [FocusRole.Button]); there
 *   it shows at the edge and stands 3:1 off the fill it sits on; everywhere else the fill swap
 *   alone carries the focus (one indicator per control).
 *
 * Frames of the buttons and the dialog are written to `build/device-test/review/` too.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AboutTvFocusRenderTest {

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

    private enum class Look(val high: Boolean) { Dark(false), Light(false), High(true) }

    private var dark = true
    private var style = FocusStyle.tv(false)

    private fun show(look: Look, content: @Composable () -> Unit): TvUxChecklist {
        FormFactor.override(true)
        Motion.reduced = false
        // The theme is the viewer's own choice on a television (it follows no system mode there).
        runBlocking { SettingsStore(RuntimeEnvironment.getApplication()).setThemeChoice(if (look == Look.Light) ThemeChoice.Light else ThemeChoice.Dark) }
        rule.mainClock.autoAdvance = false
        val ux = TvUxChecklist(rule, "aboutTv")
        rule.setContent {
            TMPlayerTheme {
                CompositionLocalProvider(LocalFocusStyle provides FocusStyle.tv(look.high)) {
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
        assertEquals("the $look look is the theme it names", look != Look.Light, dark)
        return ux
    }

    private fun named(ux: TvUxChecklist, name: String): SemanticsNode =
        ux.focusables().firstOrNull { ux.nameOf(it).startsWith(name) }
            ?: error("no focusable '$name' in ${ux.focusables().map { ux.nameOf(it) }}")

    /** What one control looks like at rest and focused, as the frames and layout rects. */
    private class Pair2(val rest: Frame, val focused: Frame, val restRect: Rect, val nowRect: Rect, val other: Rect)

    /** The remote on [other] (rest), then on [name] (focused), both frames and the layout rects of [name]. */
    private fun measure(ux: TvUxChecklist, name: String, other: String): Pair2 {
        val node = named(ux, name)
        val neighbour = named(ux, other)
        assertTrue("$other takes the focus", ux.focus(neighbour))
        val restNode = ux.current(node)!!
        val rest = ux.frame()
        val restRect = ux.layoutRect(restNode)
        val otherRect = ux.layoutRect(ux.current(neighbour)!!)
        assertTrue("$name takes the focus", ux.focus(restNode))
        val focusedNode = ux.current(node)!!
        return Pair2(rest, ux.frame(), restRect, ux.layoutRect(focusedNode), otherRect)
    }

    /** The layout is the same and everything that changed on focus is inside it (the ring is drawn in). */
    private fun assertStaysInside(ux: TvUxChecklist, name: String, m: Pair2) {
        val tol = ux.density * 0.5f + 1f
        assertTrue("$name layout ${m.restRect} at rest, ${m.nowRect} focused", abs(m.restRect.width - m.nowRect.width) <= tol && abs(m.restRect.height - m.nowRect.height) <= tol)
        assertTrue("$name moved", (m.restRect.center - m.nowRect.center).getDistance() <= tol)
        val region = m.restRect.inflate(ux.density * 24f)
        // The neighbour changes too (it lost the focus): leave out its rect, as the suite does.
        val skip = m.other.takeUnless { it.overlaps(m.restRect) }?.inflate(ux.density * 2f)
        val drawn = drawnChange(m.rest, m.focused, region, skip)
        assertNotNull("$name draws a focus", drawn)
        val allowed = m.restRect.inflate(tol)
        assertTrue(
            "$name drew outside its bounds: drawn $drawn over layout ${m.restRect}",
            drawn!!.left >= allowed.left && drawn.top >= allowed.top && drawn.right <= allowed.right && drawn.bottom <= allowed.bottom,
        )
    }

    /** The ring at the middle of the top edge against the fill just inside it, and against the rest frame. */
    private fun ringAndFill(ux: TvUxChecklist, m: Pair2): Triple<Color, Color, Color> {
        val x = m.restRect.center.x.toInt()
        val d = ux.density
        val ringY = (m.restRect.top + d * (style.outer.value + style.ring.value / 2f)).toInt()
        val fillY = (m.restRect.top + d * (style.outer.value + style.ring.value + style.inner.value + 3f)).toInt()
        return Triple(m.focused.color(x, ringY), m.focused.color(x, fillY), m.rest.color(x, ringY))
    }

    private fun assertRing(ux: TvUxChecklist, name: String, m: Pair2) {
        val (ring, fill, _) = ringAndFill(ux, m)
        assertTrue("$name: ring $ring against the fill $fill is ${contrastRatio(ring, fill)}:1", contrastRatio(ring, fill) >= FocusContrast.Minimum)
        // The whole edge says "focused" against the rest frame, the outer line and ring included.
        val edge = edgeContrast(m.rest, m.focused, m.restRect, step = ux.density * 2f, outside = ux.density * 3f, inside = ux.density * 4f)
        assertTrue("$name: the focused edge against rest is $edge:1", edge >= FocusContrast.Minimum)
    }

    private fun assertNoRing(ux: TvUxChecklist, name: String, m: Pair2) {
        val (edge, fill, _) = ringAndFill(ux, m)
        assertTrue("$name: no ring, only the fill: edge $edge fill $fill", contrastRatio(edge, fill) < 1.5f)
    }

    // ----- About -------------------------------------------------------------------------------

    private fun aboutRows(look: Look) {
        val ux = show(look) { AboutScreen(onBack = {}) }
        // The support rows are further down the page: the remote walks to them, as a viewer's does.
        var presses = 0
        while (listOf(L.supportBuyCoffee, L.supportSponsorGithub).any { n -> ux.focusables().none { ux.nameOf(it).startsWith(n) } } && presses++ < 30) ux.key(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        for ((name, other) in listOf(L.supportBuyCoffee to L.supportSponsorGithub, L.supportSponsorGithub to L.supportBuyCoffee)) {
            val m = measure(ux, name, other)
            assertStaysInside(ux, name, m)
            // The row role: the fill alone on a television, a ring only under High contrast.
            assertEquals(style.highContrast, FocusRole.Row.recipe.ringShown(style, dark))
            if (style.highContrast) assertRing(ux, name, m) else assertNoRing(ux, name, m)
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun aboutRowsDark() = aboutRows(Look.Dark)

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-notnight-xhdpi")
    fun aboutRowsLight() = aboutRows(Look.Light)

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun aboutRowsHighContrast() = aboutRows(Look.High)

    // ----- TmButton ----------------------------------------------------------------------------

    private fun toolbar(): @Composable () -> Unit = {
        Column(Modifier.padding(horizontal = 80.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TmButton(onClick = {}) { TvText("Save") }
                TmSecondaryButton(onClick = {}) { TvText("Cancel") }
                TmButton(onClick = {}) { TvText("Sort") }
            }
        }
    }

    private fun buttons(look: Look, names: List<String>, content: @Composable () -> Unit) {
        val ux = show(look, content)
        for ((i, name) in names.withIndex()) {
            val m = measure(ux, name, names[(i + 1) % names.size])
            assertStaysInside(ux, name, m)
            if (FocusRole.Button.recipe.ringShown(style, dark)) assertRing(ux, name, m) else assertNoRing(ux, name, m)
            if (i == 0) shot(ux, "button-${look.name.lowercase()}-rest.png", m.rest)
            if (i == 0) shot(ux, "button-${look.name.lowercase()}-focused.png", m.focused)
        }
    }

    private fun shot(ux: TvUxChecklist, name: String, frame: Frame) {
        val bitmap = android.graphics.Bitmap.createBitmap(frame.width, frame.height, android.graphics.Bitmap.Config.ARGB_8888)
        val px = IntArray(frame.width * frame.height) { frame.at(it % frame.width, it / frame.width) }
        bitmap.setPixels(px, 0, frame.width, 0, 0, frame.width, frame.height)
        val file = java.io.File("../build/device-test/review/wp03-$name").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(ux.density > 0 && file.length() > 0)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun toolbarDark() = buttons(Look.Dark, listOf("Save", "Cancel", "Sort"), toolbar())

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-notnight-xhdpi")
    fun toolbarLight() = buttons(Look.Light, listOf("Save", "Cancel", "Sort"), toolbar())

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun toolbarHighContrast() = buttons(Look.High, listOf("Save", "Cancel", "Sort"), toolbar())

    private fun confirm(): @Composable () -> Unit = {
        TvConfirm(
            title = "Remove this list?",
            message = "The videos stay where they are.",
            confirmLabel = "Remove",
            onConfirm = {},
            onDismiss = {},
            destructive = false,
        )
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun confirmDark() = buttons(Look.Dark, listOf("Remove", "Cancel"), confirm())

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-notnight-xhdpi")
    fun confirmLight() = buttons(Look.Light, listOf("Remove", "Cancel"), confirm())

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun confirmHighContrast() = buttons(Look.High, listOf("Remove", "Cancel"), confirm())

    // ----- SupportCard -------------------------------------------------------------------------

    private fun cards(look: Look) {
        val ux = show(look) {
            Column(Modifier.padding(horizontal = 80.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SupportCard(Method.Coffee, "Buy me a coffee", "Opens a code", onClick = {})
                SupportCard(Method.Upi, "UPI", "Pay with a UPI app", onClick = {})
            }
        }
        for ((name, other) in listOf("Buy me a coffee" to "UPI", "UPI" to "Buy me a coffee")) {
            val m = measure(ux, name, other)
            // A card does not lift on a television: the same layout, and nothing drawn past it.
            assertStaysInside(ux, name, m)
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun supportCardsDark() = cards(Look.Dark)

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-notnight-xhdpi")
    fun supportCardsLight() = cards(Look.Light)

    // ----- Review fixes A: the stand-alone About list and the sign out prompt ---------------------

    /** The stand-alone TV About list ends at the safe line: walking to its last row never leaves a row in the bottom 27 dp (F-16). */
    private fun aboutKeepsTheSafeLine() {
        val ux = show(Look.Dark) { AboutScreen(onBack = {}) }
        ux.safeArea()
        var presses = 0
        var last = ux.focused().map { ux.nameOf(it) }
        var steady = 0
        while (steady < 2 && presses++ < 80) {
            ux.key(android.view.KeyEvent.KEYCODE_DPAD_DOWN, 250)
            ux.safeArea()
            val now = ux.focused().map { ux.nameOf(it) }
            steady = if (now == last) steady + 1 else 0
            last = now
        }
        assertTrue("walked to the last row: $last", presses < 80)
        val outside = ux.findings.filter { it.rule == "safeArea" }
        assertTrue("rows in the margin: $outside", outside.isEmpty())
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun aboutListEndsAtTheSafeLine960() = aboutKeepsTheSafeLine()

    @Test
    @Config(sdk = [35], qualifiers = "w1920dp-h1080dp-land-television-night-mdpi")
    fun aboutListEndsAtTheSafeLine1920() = aboutKeepsTheSafeLine()

    /** Sign out's Cancel, Sign out and the backup tick are 48 dp focus rects (F-17). */
    private fun signOutTargets() {
        val ux = show(Look.Dark) {
            TvConfirm(
                title = L.settingsSignOutTitle,
                message = "You can sign in again.",
                confirmLabel = L.settingsSignOutConfirm,
                onConfirm = {},
                onDismiss = {},
                extra = { TickRow("Save my settings backup to my Saved Messages first", checked = false, onToggle = {}) },
            )
        }
        ux.targets()
        val names = ux.focusables().map { ux.nameOf(it) }
        assertTrue("Cancel, Sign out and the tick are all there: $names", listOf(L.commonCancel, L.settingsSignOutConfirm, "Save my settings backup").all { n -> names.any { it.startsWith(n) } })
        assertTrue("under 48 dp: ${ux.findings}", ux.findings.none { it.rule == "targets" })
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun signOutPromptTargets960() = signOutTargets()

    @Test
    @Config(sdk = [35], qualifiers = "w1920dp-h1080dp-land-television-night-mdpi")
    fun signOutPromptTargets1920() = signOutTargets()
}
