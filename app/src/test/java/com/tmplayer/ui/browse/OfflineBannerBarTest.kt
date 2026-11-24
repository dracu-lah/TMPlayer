package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.connect.OfflineBanner
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

/**
 * The offline banner and the floating tab bar (R4, Raju 9.1): the banner stands above the bar and
 * never over it, so a tap on Chats while it shows still reaches Chats, at the normal font size and
 * at 2.0, and the banner's own buttons work.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
class OfflineBannerBarTest {

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

    private var chats = 0
    private var searched = 0
    private var retried = 0
    private var downloads = 0

    private val nav = TouchNav(
        account = null, favoriteCount = 0, downloadCount = 0, updateVersion = null,
        onHome = {}, onChats = { chats++ }, onSearch = { searched++ }, onSettings = {}, onFavourites = {},
        onDownloads = { downloads++ }, onHistory = {}, onUpdate = {},
    )

    private fun show(fontScale: Float, bannerUp: Boolean = true) {
        FormFactor.override(false)
        Motion.reduced = true
        val chrome = TouchChrome()
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                TMPlayerTheme {
                    Box(Modifier.fillMaxSize().background(Tone.background)) {
                        TouchNavHost(
                            nav, chrome, "home",
                            bannerShown = bannerUp,
                            banner = { OfflineBanner(onOpenDownloads = { downloads++ }) },
                        ) {
                            CompositionLocalProvider(LocalTouchPageKey provides "home") {
                                TouchPage(TouchPageSpec(current = TouchDestination.Home))
                                LazyColumn(Modifier.fillMaxSize()) { items(40) { Text("Row $it", Modifier.padding(16.dp)) } }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `a tap on Chats reaches Chats while the offline banner shows`() {
        show(1f)
        rule.onNodeWithText(L.stateOffline).assertIsDisplayed()
        rule.onNodeWithText(L.browseTabChats).assertIsDisplayed().performClick()
        assertEquals(1, chats)
        // The bar's other buttons answer too: Search is not under the banner either.
        rule.onNodeWithText(L.commonSearch).performClick()
        assertEquals(1, searched)
    }

    @Test
    fun `the banner stands above the bar, not over it`() {
        show(1f)
        val banner = rule.onNodeWithText(L.commonRetry).getUnclippedBoundsInRoot()
        val chatsButton = rule.onNodeWithText(L.browseTabChats).getUnclippedBoundsInRoot()
        assertTrue("Try again ends above the bar's captions: $banner vs $chatsButton", banner.bottom <= chatsButton.top)
    }

    @Test
    fun `the banner's buttons work`() {
        show(1f)
        // "Downloads" is on the banner and, since R2, on the bar as a place: the banner's stands above.
        val words = rule.onAllNodesWithText(L.downloadsTitle)
        assertEquals(2, words.fetchSemanticsNodes().size)
        val banners = (0..1).minBy { words[it].fetchSemanticsNode().boundsInRoot.top }
        words[banners].performClick()
        assertEquals(1, downloads)
        // The bar's own button asks for the page too, and is not the banner's.
        words[1 - banners].performClick()
        assertEquals(2, downloads)
        rule.onNodeWithText(L.commonRetry).assertIsDisplayed()
    }

    @Test
    fun `at 2 point 0 font the banner still stands above a tappable bar`() {
        show(2f)
        rule.onNodeWithText(L.stateOffline).assertIsDisplayed()
        val banner = rule.onNodeWithText(L.commonRetry).getUnclippedBoundsInRoot()
        val chatsButton = rule.onNodeWithContentDescription(L.browseTabChats).getUnclippedBoundsInRoot()
        assertTrue("$banner vs $chatsButton", banner.bottom <= chatsButton.top)
        rule.onNodeWithContentDescription(L.browseTabChats).performClick()
        assertEquals(1, chats)
    }

    @Test
    fun `without the banner nothing is reserved for it`() {
        show(1f, bannerUp = false)
        rule.onNodeWithText(L.stateOffline).assertDoesNotExist()
        rule.onNodeWithText(L.browseTabChats).performClick()
        assertEquals(1, chats)
    }
}
