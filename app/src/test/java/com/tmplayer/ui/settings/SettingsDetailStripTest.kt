package com.tmplayer.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.icons.TmIcons
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
 * The detail strip under the television's Settings pane (WP-74): the focused row's sentence shows
 * in a strip of one fixed height, a focus move recomposes the strip and never the page, and no
 * node is a live region.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class SettingsDetailStripTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    private var pageCompositions = 0

    private val long = "A long sentence that goes on and on about what this row does, why it exists, " +
        "what it changes elsewhere, how to undo it and which other rows it affects, so that it needs " +
        "more than two lines of the strip to be said in full by anyone who asks."

    private val descriptions = listOf("", "One short line.", long) + List(9) { "Row ${it + 3} says something." }

    @After
    fun tearDown() = FormFactor.override(false)

    private fun show() {
        FormFactor.override(true)
        rule.setContent {
            TMPlayerTheme {
                val pages = SettingsRail.pages()
                val railFocus = remember { SettingsPage.entries.associateWith { FocusRequester() } }
                val landing = remember { FocusRequester() }
                val breadcrumb = remember { SettingsBreadcrumb() }
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    TvSettingsPane(
                        pages = pages,
                        open = pages.first(),
                        title = { it.name },
                        icon = { TmIcons.Play },
                        railFocus = railFocus,
                        breadcrumb = breadcrumb,
                        paneTitle = "Pane",
                        paneKey = "pane",
                        onFollow = {},
                        onEnterPane = {},
                        onBackInPane = {},
                        landing = landing,
                        entry = landing,
                        arrived = remember { BooleanArray(1) { true } },
                    ) {
                        // Counted outside the list: it runs again only when the page recomposes.
                        SideEffect { pageCompositions++ }
                        LazyColumn(Modifier.fillMaxSize()) {
                            itemsIndexed(descriptions) { index, text ->
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .then(if (index == 0) Modifier.focusRequester(landing) else Modifier)
                                        .detailOnFocus(text)
                                        .testTag("row$index")
                                        .focusable(),
                                ) { Text("Row $index") }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun strip() = rule.onNodeWithTag(SETTINGS_DETAIL_TAG, useUnmergedTree = true)

    private fun stripText(): String = strip().fetchSemanticsNode().children
        .firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.Text) }
        ?.joinToString { it.text } ?: ""

    private fun down() {
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
    }

    @Test
    fun `the strip follows the focused row, keeps one height, and a focus move never recomposes the page`() {
        show()
        rule.onNodeWithTag("row0").requestFocus()
        rule.waitForIdle()
        rule.onNodeWithTag("row0").assertIsFocused()
        val height = strip().getBoundsInRoot().height
        assertEquals("", stripText())

        val before = pageCompositions
        down()
        rule.onNodeWithTag("row1").assertIsFocused()
        assertEquals("One short line.", stripText())
        assertEquals("a one line sentence keeps the strip's height", height, strip().getBoundsInRoot().height)

        down()
        rule.onNodeWithTag("row2").assertIsFocused()
        assertTrue(stripText().startsWith("A long sentence"))
        assertEquals("a three line sentence keeps the strip's height", height, strip().getBoundsInRoot().height)

        // Nine more moves over the rows: the strip changes each time, the page composes nowhere.
        repeat(9) { down() }
        assertEquals("Row 11 says something.", stripText())
        assertEquals("a focus move recomposes the strip and never the page", before, pageCompositions)
    }

    @Test
    fun `no node in the pane is a live region`() {
        show()
        rule.onNodeWithTag("row0").requestFocus()
        rule.waitForIdle()
        down()
        assertEquals("One short line.", stripText())
        fun walk(node: SemanticsNode) {
            assertTrue("live region on node ${node.id}", !node.config.contains(SemanticsProperties.LiveRegion))
            node.children.forEach(::walk)
        }
        walk(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
    }
}
