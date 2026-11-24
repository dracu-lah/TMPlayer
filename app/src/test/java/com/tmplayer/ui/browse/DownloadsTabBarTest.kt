package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.tmplayer.data.FormFactor
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Downloads as a place of its own on the phone (R2): the floating bar's four places (Home, Chats,
 * Search, Downloads), the number running on the last, no profile picture, and the gear that opens
 * the card of Favourites, History, Watch later and Settings, which has no Downloads row any more.
 * PNGs under `build/device-test/downloads-tab/` for checking by eye, at a normal and twice the font.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DownloadsTabBarTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private val calls = mutableListOf<String>()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private fun nav(running: Int) = TouchNav(
        account = null,
        favoriteCount = 3,
        downloadCount = running,
        watchingCount = 2,
        watchedCount = 5,
        updateVersion = null,
        onHome = { calls += "home" },
        onChats = { calls += "chats" },
        onSearch = { calls += "search" },
        onSettings = { calls += "settings" },
        onFavourites = { calls += "favourites" },
        onDownloads = { calls += "downloads" },
        onHistory = { calls += "history:$it" },
        onUpdate = { calls += "update" },
    )

    private fun show(
        running: Int,
        current: TouchDestination?,
        gear: Boolean = true,
        fontScale: Float = 1f,
        chrome: TouchChrome = TouchChrome(),
    ): TouchChrome {
        FormFactor.override(false)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                TMPlayerTheme {
                    Box(Modifier.fillMaxSize().background(Tone.background)) {
                        TouchNavHost(nav(running), chrome, "page") {
                            CompositionLocalProvider(LocalTouchPageKey provides "page") {
                                TouchPage(TouchPageSpec(current = current))
                                Box(Modifier.fillMaxSize()) {
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        return chrome
    }

    private fun shot(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val file = File("../build/device-test/downloads-tab/$name").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    private fun downloadsButton(running: Int) =
        rule.onNodeWithContentDescription(if (running > 0) L.navInProgress(L.navDownloads, running.toString()) else L.navDownloads)

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `the bar has four places and no profile picture`() {
        show(running = 0, current = TouchDestination.Home)
        rule.onNodeWithContentDescription(L.browseTabHome).assertIsDisplayed().assertIsSelected()
        rule.onNodeWithContentDescription(L.browseTabChats).assertIsDisplayed()
        rule.onNodeWithContentDescription(L.commonSearch).assertIsDisplayed()
        downloadsButton(0).assertIsDisplayed()
        // The picture left the bar for the gear: nothing on it is named as a profile.
        assertTrue(rule.onAllNodesWithContentDescription(L.navProfile).fetchSemanticsNodes().isEmpty())
        shot("bar-home-dark.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `the running count is on the Downloads button and says nothing at zero`() {
        show(running = 0, current = TouchDestination.Home)
        rule.onAllNodesWithTag(BAR_BADGE_TAG, useUnmergedTree = true).assertCountEquals(0)
        assertTrue(rule.onAllNodesWithText("0", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `TalkBack hears one group of four places, each with its own, and the count as in progress`() {
        show(running = 2, current = TouchDestination.Home)
        val group = androidx.compose.ui.test.SemanticsMatcher("a collection of 4 in one row") {
            val info = it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.CollectionInfo)
            info != null && info.rowCount == 1 && info.columnCount == 4
        }
        rule.onNode(group).assertExists()
        for ((index, name) in listOf(L.browseTabHome, L.browseTabChats, L.commonSearch).withIndex()) {
            val item = rule.onNodeWithContentDescription(name).fetchSemanticsNode().config
                .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.CollectionItemInfo)
            assertEquals(name, index, item?.columnIndex)
        }
        val last = downloadsButton(2).fetchSemanticsNode().config
            .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.CollectionItemInfo)
        assertEquals(3, last?.columnIndex)
        assertTrue(L.navInProgress(L.navDownloads, "2").contains("2"))
        assertTrue("no middle dot or bare number", !L.navInProgress(L.navDownloads, "2").contains('\u00b7'))
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `two running downloads show a 2 and the button is read with it`() {
        show(running = 2, current = TouchDestination.Home)
        rule.onNodeWithTag(BAR_BADGE_TAG, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("2", useUnmergedTree = true).assertIsDisplayed()
        downloadsButton(2).assertIsDisplayed()
        shot("bar-badge-2-dark.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `a hundred and more is 99 plus, never a wide number`() {
        show(running = 120, current = TouchDestination.Home)
        rule.onNodeWithText("99+", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `on the Downloads page its button is the lit one, and pressing it again goes to the top, not nowhere`() {
        val chrome = show(running = 1, current = TouchDestination.Downloads)
        downloadsButton(1).assertIsSelected()
        rule.onNodeWithContentDescription(L.browseTabHome).assertIsNotSelected()
        val reselected = mutableListOf<TouchDestination>()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            chrome.reselected.collect { reselected += it }
        }
        downloadsButton(1).performClick()
        rule.waitForIdle()
        job.cancel()
        // Already there: the page is told to go to its top, and the app is not asked to open it again.
        assertEquals(listOf(TouchDestination.Downloads), reselected)
        assertTrue(calls.isEmpty())
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `from Home the Downloads button opens Downloads`() {
        show(running = 0, current = TouchDestination.Home)
        downloadsButton(0).performClick()
        rule.waitForIdle()
        assertEquals(listOf("downloads"), calls)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `the gear opens the card of your lists and Settings, with no Downloads row`() {
        val chrome = show(running = 3, current = TouchDestination.Home)
        assertEquals(false, chrome.youOpen)
        rule.onNodeWithContentDescription(L.navAccount).assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(true, chrome.youOpen)
        rule.onNodeWithText(L.browseTabFavourites).assertIsDisplayed()
        rule.onNodeWithText(L.browseTabHistory).assertIsDisplayed()
        rule.onNodeWithText(L.browseTabWatchLater).assertIsDisplayed()
        rule.onNodeWithText(L.navSettings).assertIsDisplayed()
        // Downloads is only the bar's own caption now: one place for it, not two.
        assertEquals(1, rule.onAllNodes(hasText(L.navDownloads)).fetchSemanticsNodes().size)
        shot("gear-card-dark.png")

        rule.onNodeWithText(L.navSettings).performClick()
        rule.waitForIdle()
        assertEquals(listOf("settings"), calls)
        assertEquals("a pick closes the card", false, chrome.youOpen)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `Back closes the card before it leaves the page`() {
        val chrome = show(running = 0, current = TouchDestination.Home)
        rule.onNodeWithContentDescription(L.navAccount).performClick()
        rule.waitForIdle()
        assertTrue(chrome.youOpen)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(false, chrome.youOpen)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h640dp-port-night-xhdpi")
    fun `a narrow phone at twice the font size keeps all four places on the bar`() {
        show(running = 12, current = TouchDestination.Downloads, fontScale = 2f)
        rule.onNodeWithContentDescription(L.browseTabHome).assertIsDisplayed()
        rule.onNodeWithContentDescription(L.browseTabChats).assertIsDisplayed()
        rule.onNodeWithContentDescription(L.commonSearch).assertIsDisplayed()
        downloadsButton(12).assertIsDisplayed()
        shot("bar-narrow-2x-dark.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-notnight-xhdpi")
    fun `the bar in light`() {
        show(running = 2, current = TouchDestination.Downloads)
        downloadsButton(2).assertIsDisplayed()
        shot("bar-downloads-light.png")
    }

    @Test
    fun `the highlight follows the bar's order, Downloads after Search`() {
        assertEquals(0, barHighlightIndex(TouchDestination.Home))
        assertEquals(1, barHighlightIndex(TouchDestination.Chats))
        // Search stands between them as a button, not a place, so Downloads is the fourth slot.
        assertEquals(3, barHighlightIndex(TouchDestination.Downloads))
        assertNull(barHighlightIndex(null))
    }
}
