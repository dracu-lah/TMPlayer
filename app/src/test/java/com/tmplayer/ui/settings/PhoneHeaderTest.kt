package com.tmplayer.ui.settings

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextLayoutResult
import com.tmplayer.data.FormFactor
import com.tmplayer.data.Updates
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.PAGE_HEADER_BACK_TAG
import com.tmplayer.ui.components.PAGE_HEADER_HAIRLINE_TAG
import com.tmplayer.ui.components.PAGE_HEADER_TAG
import com.tmplayer.ui.components.PAGE_HEADER_TITLE_TAG
import com.tmplayer.ui.components.VERSION_MARK_TAG
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The fixed top bar of Settings and its pages on a phone (user, 2026-11-25, real POCO M2 Pro): Back
 * sits in the header beside the page's name and no longer floats over the list, which used to hide
 * the first letters of rows after a scroll. On the test phone's window (393 x 873 dp, xxhdpi): after
 * scrolling, no row touches the Back button or the header, the title is whole at font 1.0 and 1.3,
 * the hairline shows only once the list has moved, Back has its TalkBack name and a 48 dp target,
 * and a press goes back. Pictures go to design/phone-header.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneHeaderTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    private var backs = 0

    @Before
    fun setUp() {
        Updates.enabled = true
        Updates.noteAnswered(1L)
        backs = 0
    }

    @After
    fun tearDown() {
        FormFactor.override(false)
        Updates.enabled = true
    }

    private fun show(page: SettingsPage?, fontScale: Float = 1f) {
        FormFactor.override(false)
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                TMPlayerTheme {
                    Box(Modifier.fillMaxSize().background(Tone.background)) {
                        SettingsScreen(
                            chats = emptyList(),
                            onLoggedOut = {},
                            onBack = { backs++ },
                            initialPage = page,
                            accountFolders = emptyList(),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun picture(name: String) {
        val bitmap: Bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val file = File("../design/phone-header/$name").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun scrollList(target: String) {
        rule.onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(hasTestTag(target))
        rule.waitForIdle()
    }

    /** The header's parts hold: a 48 dp Back at the start, the title whole and inside the window. */
    private fun checkHeader(backLabel: String, fontScale: Float) {
        val window = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val header = bounds(PAGE_HEADER_TAG)
        val back = bounds(PAGE_HEADER_BACK_TAG)
        val title = bounds(PAGE_HEADER_TITLE_TAG)
        val dp = rule.density.density
        assertEquals("a full 48 dp target wide", 48f * dp, back.width, 1f)
        assertEquals("a full 48 dp target high", 48f * dp, back.height, 1f)
        assertTrue("Back is at the start", back.left < title.left)
        assertTrue("Back is inside the header", back.top >= header.top && back.bottom <= header.bottom)
        assertTrue("the title is beside Back, not under it", title.left >= back.right - 1f)
        assertTrue("the title stays inside the window at font $fontScale", title.right <= window.right + 1f && title.left >= window.left)
        // The text is whole: no letter is cut by the ellipsis or the edge.
        val node = rule.onNodeWithTag(PAGE_HEADER_TITLE_TAG, useUnmergedTree = true).fetchSemanticsNode()
        val results = ArrayList<TextLayoutResult>()
        fun collect(n: androidx.compose.ui.semantics.SemanticsNode) {
            n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            n.children.forEach { collect(it) }
        }
        collect(node)
        assertTrue("the title has a text to measure", results.isNotEmpty())
        // (hasVisualOverflow is always true for text with no bound, so ask about the ellipsis.)
        results.forEach {
            assertFalse("the title is cut by an ellipsis at font $fontScale", (0 until it.lineCount).any { line -> it.isLineEllipsized(line) })
            assertTrue("the title is one line at font $fontScale", it.lineCount == 1)
            assertTrue("the title's text ${it.size.width} fits its box ${title.width}", it.size.width <= title.width + 1f)
        }
        // TalkBack: a button with its name, and a heading for the title.
        val config = rule.onNodeWithTag(PAGE_HEADER_BACK_TAG).fetchSemanticsNode().config
        assertEquals(listOf(backLabel), config.getOrNull(SemanticsProperties.ContentDescription))
        assertTrue("Back can be pressed", config.contains(SemanticsActions.OnClick))
        assertTrue("the title is a heading", rule.onNodeWithTag(PAGE_HEADER_TITLE_TAG).fetchSemanticsNode().config.contains(SemanticsProperties.Heading))
    }

    /** After a scroll no pressable row reaches the header or the Back button. */
    private fun checkNothingUnderBack() {
        val header = bounds(PAGE_HEADER_TAG)
        val back = bounds(PAGE_HEADER_BACK_TAG)
        val rows = rule.onAllNodes(androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes()
            .filter { it.boundsInRoot != back && it.boundsInRoot.height > 0f }
            .filter { it.boundsInRoot.bottom > 0f && it.boundsInRoot.top < 2000f }
        assertTrue("some rows are shown", rows.isNotEmpty())
        rows.forEach { row ->
            val r = row.boundsInRoot
            if (r.overlaps(header) && r.top < header.bottom) {
                // A full-screen or header-owned node; the only one allowed is Back itself.
                assertTrue("a row $r reaches into the header $header", r.top >= header.bottom - 1f || r == back)
            }
            assertFalse("a row $r is under Back $back", r.overlaps(back))
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w393dp-h873dp-port-xxhdpi")
    fun `the list has a fixed header, and after a scroll no row is under Back`() {
        show(null)
        checkHeader(L.commonBack, 1f)
        assertTrue("the title has the tile", rule.onAllNodes(hasTestTag(SETTINGS_ICON_TAG), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        // At the top there is nothing under the header to divide from.
        assertTrue(rule.onAllNodes(hasTestTag(PAGE_HEADER_HAIRLINE_TAG)).fetchSemanticsNodes().isEmpty())
        picture("settings-top.png")
        scrollList(VERSION_MARK_TAG)
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag(PAGE_HEADER_HAIRLINE_TAG).assertExists()
        checkHeader(L.commonBack, 1f)
        checkNothingUnderBack()
        picture("settings-scrolled.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w393dp-h873dp-port-xxhdpi")
    fun `the header stays whole at font 1_3`() {
        show(null, fontScale = 1.3f)
        checkHeader(L.commonBack, 1.3f)
        scrollList(VERSION_MARK_TAG)
        checkHeader(L.commonBack, 1.3f)
        checkNothingUnderBack()
        picture("settings-scrolled-font-1.3.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w393dp-h873dp-port-xxhdpi")
    fun `a page has its own title in the header and Back is named for the list`() {
        show(SettingsPage.Subtitles)
        checkHeader(L.aboutBackToSettings, 1f)
        rule.onNodeWithTag(PAGE_HEADER_TITLE_TAG).assertExists()
        assertTrue(
            "the page's name is the title",
            rule.onAllNodes(androidx.compose.ui.test.hasText(L.settingsPageSubtitlesAudio)).fetchSemanticsNodes().isNotEmpty(),
        )
        picture("page-top.png")
        checkNothingUnderBack()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w393dp-h873dp-port-xxhdpi")
    fun `a page at font 1_3 keeps its title whole`() {
        show(SettingsPage.Subtitles, fontScale = 1.3f)
        checkHeader(L.aboutBackToSettings, 1.3f)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w393dp-h873dp-port-xxhdpi")
    fun `pressing Back on the list leaves Settings`() {
        show(null)
        rule.onNodeWithTag(PAGE_HEADER_BACK_TAG).performClick()
        rule.waitForIdle()
        assertEquals(1, backs)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w393dp-h873dp-port-xxhdpi")
    fun `pressing Back on a page returns to the list and not out of Settings`() {
        show(SettingsPage.Subtitles)
        rule.onNodeWithTag(PAGE_HEADER_BACK_TAG).performClick()
        rule.waitForIdle()
        assertEquals("the page closed, Settings did not", 0, backs)
        rule.onNodeWithTag(SETTINGS_TITLE_TAG, useUnmergedTree = true).assertExists()
    }
}
