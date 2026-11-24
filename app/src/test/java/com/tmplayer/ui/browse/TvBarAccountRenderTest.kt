package com.tmplayer.ui.browse

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.tmplayer.data.Account
import com.tmplayer.data.FormFactor
import com.tmplayer.data.Profile
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
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
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The television's account button as drawn (WP-01): the picture with its first name at rest in
 * every state it can be in (the name only, the saved photo, Telegram's photo, no name at all), and
 * the numbers that must hold: the tabs stand still when the picture takes focus, the focus rect is
 * 48 dp, the ring and its halo are drawn inside it and inside the bar, the picture is never the bare
 * [Tone.surfaceHigh] disc while a name or a photo is known, and the longest name with five tabs
 * pushes nothing off the bar. At 1080p (960 x 540 dp, xhdpi) and at 960 x 540 px (mdpi). Frames go
 * to build/device-test/review. Left to the device: how it reads from a sofa on a real panel.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class TvBarAccountRenderTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private var surfaceHigh = Color.Unspecified
    private var accent = Color.Unspecified
    private val profileFocus = FocusRequester()

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    private val red = 0xFFD02020.toInt()

    /** A solid picture, as bytes (Telegram's inline preview) or a file (the profile's saved photo). */
    private fun picture(): ByteArray {
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(red)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun savedPhoto(): String =
        File.createTempFile("saved-photo", ".png").also { it.deleteOnExit(); it.writeBytes(picture()) }.path

    private fun open(
        account: Account?,
        profile: Profile?,
        update: String? = null,
        fontScale: Float = 1f,
        downloads: Int = 2,
    ) {
        FormFactor.override(true)
        Motion.reduced = true
        val nav = TouchNav(
            account = account, favoriteCount = 0, downloadCount = downloads, updateVersion = update,
            onHome = {}, onChats = {}, onSearch = {}, onSettings = {},
            onFavourites = {}, onDownloads = {}, onHistory = {}, onUpdate = {},
            profile = profile,
        )
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                TMPlayerTheme {
                    surfaceHigh = Tone.surfaceHigh
                    accent = Tone.accent
                    Column(Modifier.fillMaxSize().background(Tone.background)) {
                        TvTopBar(
                            nav = nav, current = TvBarTab.Chats, inProfile = false, searchOpen = false,
                            onHome = {}, onChats = {}, onSearch = {}, onRefresh = null,
                            currentFocus = FocusRequester(), contentFocus = FocusRequester(), overArt = false,
                            profileFocus = profileFocus,
                            // Chats carries the Choose chats button: five items after the picture.
                            onChooseChats = {},
                        )
                    }
                }
            }
        }
        // A window the remote works in: out of touch mode and holding focus, as on a television.
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
    }

    private val density get() = rule.activity.resources.displayMetrics.density

    private fun button(name: String) = rule.onNodeWithContentDescription(name)

    private fun frame(out: String): Bitmap {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val file = File("../build/device-test/review/$out").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    /** The picture's centre in pixels: the 48 dp rect is the button's first 48 dp. */
    private fun centre(name: String): Pair<Float, Float> {
        val b = button(name).fetchSemanticsNode().boundsInRoot
        return (b.left + 24f * density) to (b.top + 24f * density)
    }

    private fun close(a: Int, b: Int, tolerance: Int = 10): Boolean =
        abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) <= tolerance &&
            abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) <= tolerance &&
            abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)) <= tolerance

    /** A pixel inside the 40 dp disc, left of where a letter would be drawn. */
    private fun discPixel(bitmap: Bitmap, name: String): Int {
        val (cx, cy) = centre(name)
        return bitmap.getPixel((cx - 15f * density).toInt(), cy.toInt())
    }

    private fun waitForPicture(name: String, want: (Bitmap) -> Boolean): Bitmap {
        var bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        var tries = 0
        while (!want(bitmap) && tries++ < 100) {
            Thread.sleep(50)
            rule.waitForIdle()
            bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        }
        return bitmap
    }

    private val asha = Account(name = "Asha Rao", username = "asha", miniThumbnail = null, photoFileId = 0)

    // The pictures ---------------------------------------------------------------------------

    @Test
    fun `a name alone shows its letter on the profile colour, never the bare disc`() {
        open(account = null, profile = Profile(id = "a", folder = null, name = "Maya Lee"))
        val bitmap = frame("tv-bar-account-name-only.png")
        val pixel = discPixel(bitmap, "Maya Lee")
        assertNotEquals("a coloured disc, not surfaceHigh", surfaceHigh.toArgb(), pixel)
        rule.onAllNodesWithTag("tv-bar-first-name", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `the saved photo is drawn when Telegram has not answered`() {
        open(account = null, profile = Profile(id = "a", folder = null, name = "Maya Lee", photo = savedPhoto()))
        val bitmap = waitForPicture("Maya Lee") { close(discPixel(it, "Maya Lee"), red) }
        frame("tv-bar-account-saved-photo.png")
        val (cx, cy) = centre("Maya Lee")
        assertTrue("the picture's centre is the photo", close(bitmap.getPixel(cx.toInt(), cy.toInt()), red))
    }

    @Test
    fun `Telegram's photo is drawn over the saved one once it is there`() {
        // The inline preview stands for Telegram's picture here; the saved photo is a different colour.
        open(
            account = Account(name = "Asha Rao", username = "asha", miniThumbnail = picture(), photoFileId = 0),
            profile = Profile(id = "a", folder = null, name = "Asha Rao", photo = null),
        )
        val bitmap = waitForPicture("Asha Rao, @asha") { close(discPixel(it, "Asha Rao, @asha"), red, 40) }
        frame("tv-bar-account-telegram-photo.png")
        val (cx, cy) = centre("Asha Rao, @asha")
        assertNotEquals("the centre is a photo, not surfaceHigh", surfaceHigh.toArgb(), bitmap.getPixel(cx.toInt(), cy.toInt()))
        assertTrue("and it is the photo's red", close(bitmap.getPixel(cx.toInt(), cy.toInt()), red, 60))
    }

    @Test
    fun `with no name at all the person glyph stands on the surface disc`() {
        open(account = null, profile = null)
        val bitmap = frame("tv-bar-account-no-name.png")
        assertEquals(surfaceHigh.toArgb(), discPixel(bitmap, L.navAccount))
        rule.onAllNodesWithTag("tv-bar-first-name", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `a saved photo whose file is gone shows the 22 sp letter, not the small one`() {
        open(account = null, profile = Profile(id = "a", folder = null, name = "Maya Lee", photo = "/nonexistent/photo.png"))
        rule.waitForIdle()
        val bitmap = frame("tv-bar-account-missing-photo.png")
        val (cx, cy) = centre("Maya Lee")
        // Rows of near-white pixels (the letter's ink) in the disc: a 22 sp "M" is about 16 dp tall, the old 12 sp one about 9.
        var top = Int.MAX_VALUE
        var bottom = -1
        for (y in (cy - 20 * density).toInt()..(cy + 20 * density).toInt()) {
            for (x in (cx - 14 * density).toInt()..(cx + 14 * density).toInt()) {
                val p = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(p) > 235 && android.graphics.Color.green(p) > 235 && android.graphics.Color.blue(p) > 235) {
                    top = minOf(top, y)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        assertTrue("a letter is drawn", bottom > top)
        assertTrue("it is the 22 sp letter (${(bottom - top) / density} dp tall)", (bottom - top) / density >= 13f)
        assertTrue("and it stays inside the disc", (bottom - top) / density <= 30f)
    }

    // The numbers ----------------------------------------------------------------------------

    private fun tabsLeft(): List<Float> = listOf(L.commonSearch, L.browseTabHome, L.browseTabChats, L.navDownloads)
        .map { rule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.left }

    @Test
    fun `focus on the picture moves no tab, the focus rect is 48 dp and the ring is inside the bar`() {
        open(account = asha, profile = Profile(id = "a", folder = null, name = "Asha Rao"))
        val restTabs = tabsLeft()
        val rest = frame("tv-bar-account-rest.png")
        val node = button("Asha Rao, @asha").fetchSemanticsNode().boundsInRoot
        assertTrue("a 48 dp focus rect", node.height >= 48f * density - 1f)

        rule.runOnIdle { profileFocus.requestFocus() }
        rule.waitForIdle()
        val focused = frame("tv-bar-account-focused.png")
        assertEquals("no tab moves when the picture takes focus", restTabs, tabsLeft())

        // The ring: pixels in the accent between the picture's edge and the rect's, none outside the rect.
        val (cx, cy) = centre("Asha Rao, @asha")
        var found = 0
        var outermost = 0.0
        var innermost = 99.0
        var top = Int.MAX_VALUE
        var left = Int.MAX_VALUE
        for (y in 0 until focused.height / 3) {
            for (x in 0 until focused.width / 2) {
                val r = hypot(x - cx.toDouble(), y - cy.toDouble()) / density
                if (r < 20.2 || r > 40.0) continue
                if (close(focused.getPixel(x, y), accent.toArgb(), 6) && !close(rest.getPixel(x, y), accent.toArgb(), 6)) {
                    found++
                    outermost = maxOf(outermost, r)
                    innermost = minOf(innermost, r)
                    top = minOf(top, y)
                    left = minOf(left, x)
                }
            }
        }
        assertTrue("the ring is drawn ($found pixels)", found > 100)
        assertTrue("the ring is inside the 48 dp rect (outermost $outermost dp)", outermost <= 24.0 + 1.0 / density)
        // Reduced motion (the picture stays 20 dp): the ring starts at the picture's edge, no gap.
        assertTrue("the ring meets the picture (innermost $innermost dp)", innermost <= 20.8)
        assertTrue("inside the bar at the top", top >= 0)
        assertTrue("inside the screen at the start", left >= 0)
        // The halo of the page's own ground is the outermost band, and still within the rect.
        assertTrue("the rect is inside the bar's own height", node.bottom <= TV_BAR_HEIGHT.value * density + 1f)
    }

    @Test
    fun `the longest name and five items push no tab off the bar`() {
        val longest = "Wolfeschlegelsteinhausenbergerdorffwelchevoralternwaren"
        open(account = Account(name = longest, username = "w", miniThumbnail = null, photoFileId = 0), profile = null, update = "9.9.9")
        frame("tv-bar-account-longest-name.png")
        val width = rule.activity.window.decorView.width.toFloat()
        val mark = rule.onNodeWithContentDescription("TMPlayer").fetchSemanticsNode().boundsInRoot
        // The picture alone, no name drawn (user, 2026-11-25): a long name takes no room on the bar.
        rule.onAllNodesWithTag("tv-bar-first-name", useUnmergedTree = true).assertCountEquals(0)
        assertTrue("no text for the name at all", rule.onAllNodesWithText(longest, substring = true).fetchSemanticsNodes().isEmpty())
        val picture = button("$longest, @w, ${L.navUpdateWaiting}").fetchSemanticsNode().boundsInRoot
        var previous = picture.right
        val gaps = ArrayList<Float>()
        for (name in listOf(L.commonSearch, L.browseTabHome, L.browseTabChats, L.navDownloads)) {
            val b = rule.onNodeWithText(name).fetchSemanticsNode().boundsInRoot
            assertTrue("$name is after the picture", b.left >= picture.right)
            assertTrue("$name is before the mark", b.right <= mark.left)
            gaps += b.left - previous
            previous = b.right
        }
        // One consistent gap between the items (the first one carries the picture's own margin).
        assertTrue("one gap between the tabs: $gaps", gaps.drop(1).all { kotlin.math.abs(it - gaps[1]) < 1.5f * density })
        // Watch later left the bar (WP-14): the account menu and History keep it.
        rule.onAllNodesWithContentDescription(L.browseTabWatchLater).assertCountEquals(0)
        val choose = rule.onNodeWithContentDescription(L.pickerChooseChats).fetchSemanticsNode().boundsInRoot
        assertTrue("Choose chats is on the bar", choose.right <= mark.left && choose.right <= width)
        assertTrue("the mark is on the screen", mark.right <= width)
    }

    @Test
    fun `at 130 percent text the picture stays 48 dp with no name beside it and the bar keeps its height`() {
        open(account = asha, profile = null, fontScale = 1.3f)
        rule.onAllNodesWithTag("tv-bar-first-name", useUnmergedTree = true).assertCountEquals(0)
        val node = button("Asha Rao, @asha").fetchSemanticsNode().boundsInRoot
        assertEquals("the button is 48 dp whatever the text size", 48f * density, node.height, 1f)
        assertEquals("and 48 dp wide: the picture alone", 48f * density, node.width, 1f)
        assertTrue("inside the bar", node.bottom <= TV_BAR_HEIGHT.value * density)
        frame("tv-bar-account-font13.png")
    }

    // The same at 960 x 540 px ----------------------------------------------------------------

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-mdpi")
    fun `at 960 by 540 pixels the tabs stand still and the picture is 48 dp`() {
        open(account = asha, profile = Profile(id = "a", folder = null, name = "Asha Rao"))
        val restTabs = tabsLeft()
        frame("tv-bar-account-rest-960.png")
        assertTrue(button("Asha Rao, @asha").fetchSemanticsNode().boundsInRoot.height >= 48f * density - 1f)
        rule.runOnIdle { profileFocus.requestFocus() }
        rule.waitForIdle()
        frame("tv-bar-account-focused-960.png")
        assertEquals(restTabs, tabsLeft())
    }

    @Test
    @Config(sdk = [35], qualifiers = "ar-ldrtl-w960dp-h540dp-land-television-night-xhdpi")
    fun `right to left puts the picture at the start, on the right, with its name`() {
        open(account = asha, profile = null)
        frame("tv-bar-account-rtl.png")
        val width = rule.activity.window.decorView.width.toFloat()
        val node = button("Asha Rao, @asha").fetchSemanticsNode().boundsInRoot
        assertTrue("at the start of a right to left bar", node.center.x > width / 2f)
        assertTrue("on the screen", node.right <= width && node.left >= 0f)
    }
}
