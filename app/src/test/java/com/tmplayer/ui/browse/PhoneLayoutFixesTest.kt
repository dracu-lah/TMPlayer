package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tmplayer.data.Account
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.LIBRARY_TILE_TAG
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Two phone layout bugs from the real phone (user, 2026-11-25): on its side Home's "TMPlayer" sat
 * under the account's picture ("TMPl" then the avatar), and the account sheet's three library tiles
 * kept their content at the top over an empty foot when no tile had a count.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneLayoutFixesTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    private val density get() = rule.activity.resources.displayMetrics.density

    // Landscape: the name and the picture ---------------------------------------------------------

    private fun corner(fontScale: Float) {
        FormFactor.override(false)
        Motion.reduced = true
        val nav = TouchNav(
            account = Account(name = "Asha Rao", username = "asha", miniThumbnail = null, photoFileId = 0),
            favoriteCount = 0, downloadCount = 0, watchingCount = 0, watchedCount = 0, updateVersion = null,
            onHome = {}, onChats = {}, onSearch = {}, onSettings = {},
            onFavourites = {}, onDownloads = {}, onHistory = {}, onUpdate = {},
        )
        val chrome = TouchChrome()
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                TMPlayerTheme {
                    Box(Modifier.fillMaxSize().background(Tone.background)) {
                        TouchNavHost(nav, chrome, "home") {
                            CompositionLocalProvider(LocalTouchPageKey provides "home") {
                                TouchPage(TouchPageSpec(current = TouchDestination.Home))
                                Box(Modifier.fillMaxSize()) { PhoneHomeCorner(start = 16.dp, under = 24.dp) }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun checkCorner(fontScale: Float) {
        corner(fontScale)
        val name = rule.onNode(hasContentDescription("TMPlayer", substring = true)).fetchSemanticsNode().boundsInRoot
        val avatar = rule.onNodeWithTag(ACCOUNT_BUTTON_TAG).fetchSemanticsNode().boundsInRoot
        val window = rule.onNode(androidx.compose.ui.test.isRoot()).fetchSemanticsNode().boundsInRoot
        assertFalse("the name $name and the picture $avatar must not meet", name.overlaps(avatar))
        assertTrue("the name is at the start, left of the middle: $name", name.right < window.width / 2f)
        assertTrue("the picture is at the end: $avatar", avatar.left > window.width / 2f)
        assertTrue("the name is whole on the screen: $name", name.left >= 0f && name.right <= window.width)
        val layout = rule.onAllNodes(hasText("TMPlayer"), useUnmergedTree = true).fetchSemanticsNodes().firstNotNullOfOrNull { node ->
            val out = ArrayList<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
            out.firstOrNull()
        }
        // The name is measured at its own width: what it needs is what its box has (hasVisualOverflow
        // is always true for a text laid out unbounded, so it is not asked).
        if (layout != null) assertTrue("the name is not clipped", layout.multiParagraph.maxIntrinsicWidth <= name.width + 1f)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w873dp-h393dp-land-440dpi")
    fun `on its side the name is at the start and the picture at the end, never meeting`() = checkCorner(1f)

    @Test
    @Config(sdk = [35], qualifiers = "w873dp-h393dp-land-440dpi")
    fun `on its side at 1_3 and 2 times the font they still do not meet`() {
        checkCorner(1.3f)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w640dp-h320dp-land-night-xhdpi")
    fun `a small phone on its side at twice the font`() = checkCorner(2f)

    // The account sheet's tiles --------------------------------------------------------------------

    private fun sheet(starred: Int, watching: Int, watched: Int) {
        FormFactor.override(false)
        Motion.reduced = true
        rule.setContent {
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(FloatingTone.sheet)) {
                    AccountMenuContent(
                        account = Account(name = "Asha Rao", username = "asha", miniThumbnail = null, photoFileId = 0),
                        profile = null,
                        name = "Asha Rao",
                        first = androidx.compose.ui.focus.FocusRequester(),
                        onRefresh = {},
                        favoriteCount = starred,
                        watchingCount = watching,
                        watchedCount = watched,
                        onFavourites = {}, onHistory = {}, onWatchLater = {}, onSettings = {}, onUpdate = {}, onAbout = {},
                        onSwitchProfile = null,
                        onSwitchOrAdd = {},
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun tile(tag: String) = rule.onNodeWithTag("$LIBRARY_TILE_TAG-$tag", useUnmergedTree = true).fetchSemanticsNode()

    private fun checkTiles(withCounts: Boolean) {
        if (withCounts) sheet(7, 3, 12) else sheet(0, 0, 0)
        val tiles = listOf("favourites", "history", "watch-later").map { tile(it) }
        val heights = tiles.map { it.boundsInRoot.height }
        assertTrue("one tile height: $heights", heights.all { abs(it - heights.first()) < 1.5f })
        assertTrue("a 48 dp target at the least: $heights", heights.all { it >= 48f * density })
        assertTrue("one tile top: ${tiles.map { it.boundsInRoot.top }}", tiles.all { abs(it.boundsInRoot.top - tiles.first().boundsInRoot.top) < 1.5f })
        // The content (icon over name, over the count lines) stands in the middle of its tile as a group:
        // the room above its icon is the room under its last line, within a pixel or two.
        tiles.forEach { node ->
            val box = node.boundsInRoot
            val texts = node.children.filter { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text) != null }
            assertTrue("a tile has its name", texts.isNotEmpty())
            val firstText = texts.minOf { it.boundsInRoot.top }
            val lastText = texts.maxOf { it.boundsInRoot.bottom }
            // The icon is 24 dp, then 4 dp before the name.
            val groupTop = firstText - (24f + 4f) * density
            val above = groupTop - box.top
            val below = box.bottom - lastText
            assertEquals("content centred in ${box.height}: $above above, $below below", above, below, 2f)
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w393dp-h873dp-port-440dpi")
    fun `with no counts the three tiles stand level with their content in the middle`() = checkTiles(withCounts = false)

    @Test
    @Config(sdk = [35], qualifiers = "w393dp-h873dp-port-440dpi")
    fun `with counts on two tiles the three are still one height and centred`() = checkTiles(withCounts = true)
}
