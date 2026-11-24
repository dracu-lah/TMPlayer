package com.tmplayer.ui.browse

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The phone's and tablet's account picture and its menu as drawn (WP-103; the menu a bottom sheet on a phone, a card beside a rail), with the numbers that
 * must hold in each form: the 40 dp picture sits inside its 48 dp target at the top end (the top
 * start in Arabic), the menu's head sits inside the card, and no row of the card is cut at its
 * sides, at the phone's 360 x 800 dp upright and on its side, a tablet, twice the font and Arabic.
 * A 64 character name stays inside the card in two lines. Frames go to build/device-test/review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneAccountRenderTest {

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

    private val longName = "Asha " + "Ramanathan".repeat(6).take(59)

    private fun show(name: String = "Asha Rao", fontScale: Float = 1f, update: String? = "1.30.0") {
        FormFactor.override(false)
        Motion.reduced = true
        val nav = TouchNav(
            account = Account(name = name, username = "asha", miniThumbnail = null, photoFileId = 0),
            favoriteCount = 3, downloadCount = 0, watchingCount = 2, watchedCount = 5, updateVersion = update,
            onHome = {}, onChats = {}, onSearch = {}, onSettings = {},
            onFavourites = {}, onDownloads = {}, onHistory = {}, onUpdate = {},
            onSwitchOrAdd = {}, onSignOut = {},
            profile = Profile(id = "a", folder = null, name = name),
        )
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                TMPlayerTheme {
                    Box(Modifier.fillMaxSize().background(Tone.background)) {
                        TouchNavHost(nav, TouchChrome(), "page") {
                            CompositionLocalProvider(LocalTouchPageKey provides "page") {
                                TouchPage(TouchPageSpec(current = TouchDestination.Home, refresh = {}))
                                Box(Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private val density get() = rule.activity.resources.displayMetrics.density

    /** The picture's own 48 dp target: the clickable that says "Account, ...". */
    private fun account() = rule.onNode(hasClickAction() and hasContentDescription(L.navAccount, substring = true))

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun frame(out: String): Bitmap {
        val roots = rule.onAllNodes(isRoot())
        val count = roots.fetchSemanticsNodes().size
        val bitmap = roots[0].captureToImage().asAndroidBitmap()
        // A bottom sheet is a window of its own: its frame goes beside the page's.
        if (count > 1) {
            val sheet = roots[count - 1].captureToImage().asAndroidBitmap()
            val sheetFile = File("../build/device-test/review/${out.replace(".png", "-sheet.png")}").absoluteFile
            sheetFile.parentFile?.mkdirs()
            sheetFile.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val file = File("../build/device-test/review/$out").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
        return bitmap
    }

    private fun inside(inner: Rect, outer: Rect, slack: Float = 0.5f): Boolean =
        inner.left >= outer.left - slack && inner.top >= outer.top - slack &&
            inner.right <= outer.right + slack && inner.bottom <= outer.bottom + slack

    /** The picture in its target, at the [end] of the window (the start in Arabic), clear of its edge. */
    private fun checkButton(endIsRight: Boolean) {
        val target = account().fetchSemanticsNode().boundsInRoot
        val picture = bounds(ACCOUNT_PICTURE_TAG)
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals("a 48 dp target", 48f * density, target.width, 1.5f)
        assertEquals("a 40 dp picture", 40f * density, picture.width, 1.5f)
        assertTrue("the picture inside its target", inside(picture, target))
        if (endIsRight) assertTrue("at the top end", target.left > root.center.x) else assertTrue("at the top start", target.right < root.center.x)
        assertTrue("inside the window", inside(target, root))
    }

    /** The menu open: its head inside the card, and every row as wide as the card's inside, none cut at a side. */
    private fun checkMenu(out: String) {
        account().performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        val card = bounds(ACCOUNT_MENU_TAG)
        val root = rule.onAllNodes(isRoot()).fetchSemanticsNodes().last().boundsInRoot
        // A sheet scrolls where the window is short, so only its sides are held to the window.
        assertTrue("the card inside the window", card.left >= root.left - 0.5f && card.right <= root.right + 0.5f)
        // No stray block: the tiles are one row, the version is the last thing, Sign out is not here.
        rule.onNodeWithTag(com.tmplayer.ui.components.LIBRARY_TILES_TAG, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(com.tmplayer.ui.components.VERSION_MARK_TAG, useUnmergedTree = true).assertExists()
        assertTrue(rule.onAllNodes(hasText(L.settingsSignOutConfirm)).fetchSemanticsNodes().isEmpty())
        val head = bounds(ACCOUNT_HEAD_TAG)
        assertTrue("the head inside the card", head.left >= card.left - 0.5f && head.right <= card.right + 0.5f && head.top >= card.top - 0.5f)
        for (label in listOf(L.profilesAdd, L.navSettings, L.browseTabFavourites, L.browseTabHistory, L.browseTabWatchLater)) {
            val nodes = rule.onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes()
            assertTrue("'$label' is in the menu", nodes.isNotEmpty())
            // A short window or a large font scrolls the card: each row is brought in before it is measured.
            rule.onAllNodes(hasText(label) and hasClickAction())[0].performScrollTo()
            rule.waitForIdle()
            val row = rule.onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes().first().boundsInRoot
            assertTrue("'$label' is not cut at the card's sides", row.left >= card.left - 0.5f && row.right <= card.right + 0.5f)
            assertTrue("'$label' is a full 48 dp row", row.height >= 48f * density - 1f)
        }
        frame(out)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h800dp-port-night-xhdpi")
    fun `phone upright`() {
        show()
        checkButton(endIsRight = true)
        frame("phone-account-360-port.png")
        checkMenu("phone-account-menu-360-port.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w800dp-h360dp-land-night-xhdpi")
    fun `phone on its side`() {
        show()
        checkButton(endIsRight = true)
        checkMenu("phone-account-menu-800-land.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "sw600dp-w800dp-h1280dp-port-night-xhdpi")
    fun `tablet`() {
        show()
        checkButton(endIsRight = true)
        checkMenu("phone-account-menu-tablet.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h800dp-port-notnight-xhdpi")
    fun `phone at twice the font, light`() {
        show(fontScale = 2f)
        checkButton(endIsRight = true)
        checkMenu("phone-account-menu-360-font2x.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "ar-ldrtl-w360dp-h800dp-port-night-xhdpi")
    fun `Arabic puts the picture and the card at the top start`() {
        show()
        checkButton(endIsRight = false)
        checkMenu("phone-account-menu-360-rtl.png")
        val card = bounds(ACCOUNT_MENU_TAG)
        val root = rule.onAllNodes(isRoot()).fetchSemanticsNodes().last().boundsInRoot
        assertTrue("the card hangs from the start side", card.left < root.center.x)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h800dp-port-night-xhdpi")
    fun `a 64 character name stays in the card`() {
        show(name = longName)
        account().performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        val card = bounds(ACCOUNT_MENU_TAG)
        val head = bounds(ACCOUNT_HEAD_TAG)
        assertTrue("the head inside the card", head.left >= card.left - 0.5f && head.right <= card.right + 0.5f)
        // Two lines of the title at most, the @username under them: well under a third of the window.
        assertTrue("two lines at most", head.height < 120f * density)
        frame("phone-account-menu-long-name.png")
    }
}
