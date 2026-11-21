package com.tmplayer.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.unit.Density
import com.tmplayer.data.FormFactor
import com.tmplayer.data.SupportReminder
import com.tmplayer.ui.DrainMainLooperRule
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
 * Settings' own dialogs and cards at the smallest phone width and the largest system font (200%):
 * every button stays on the screen and none is drawn over another.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsLargeFontTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private val counters = SupportReminder.Counters(completedWatches = 7, watchTimeMs = 11L * 60 * 60 * 1000)

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp-port-night-xhdpi")
    fun `the support window keeps its buttons on the screen at a 200 percent font`() {
        FormFactor.override(false)
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 2f)) {
                TMPlayerTheme {
                    Box(Modifier.fillMaxSize().background(Tone.background)) {
                        CoffeeDialog(counters, asking = true, onLater = {}, onNever = {})
                    }
                }
            }
        }
        rule.waitForIdle()
        val words = com.tmplayer.i18n.L
        val labels = listOf(words.supportSponsorGithub, words.supportStarGithub, words.supportDontShow, words.supportMaybeLater)
        val width = rule.activity.resources.displayMetrics.widthPixels.toFloat()
        val rects = labels.map { label ->
            val node = rule.onAllNodesWithText(label).onFirst().fetchSemanticsNode()
            node.boundsInRoot
        }
        rects.forEachIndexed { i, r ->
            assertTrue("${labels[i]} $r runs off the left", r.left >= 0f)
            assertTrue("${labels[i]} $r runs off the right of $width", r.right <= width)
            assertTrue("${labels[i]} has a width", r.width > 0f)
        }
        // Sponsor and Star: one beside the other or one under the other, never over each other.
        assertTrue("Sponsor and Star overlap", !rects[0].overlaps(rects[1]))
        assertTrue("the last two overlap", !rects[2].overlaps(rects[3]))
        assertEquals(4, rects.size)
    }

    private fun cards(content: @androidx.compose.runtime.Composable () -> Unit) {
        FormFactor.override(false)
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 2f)) {
                TMPlayerTheme { Box(Modifier.fillMaxSize().background(Tone.background)) { content() } }
            }
        }
        rule.waitForIdle()
    }

    private fun assertButtonsFit(vararg labels: String) {
        val width = rule.activity.resources.displayMetrics.widthPixels.toFloat()
        val rects = labels.map { rule.onAllNodesWithText(it).onFirst().fetchSemanticsNode().boundsInRoot }
        rects.forEachIndexed { i, r ->
            assertTrue("${labels[i]} $r is off the screen of $width", r.left >= 0f && r.right <= width)
        }
        for (a in rects.indices) for (b in a + 1 until rects.size) {
            assertTrue("${labels[a]} overlaps ${labels[b]}", !rects[a].overlaps(rects[b]))
        }
        // Whole words: a button reads on one line, it is the row that wraps.
        for (label in labels) {
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            rule.onAllNodesWithText(label).onFirst().fetchSemanticsNode().config
                .getOrNull(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            assertEquals("$label wraps inside its button", 1, results.first().lineCount)
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp-port-night-xhdpi")
    fun `the language notice keeps both buttons whole at a 200 percent font`() {
        cards { LanguageNoticeCard(language = "es", onKeep = {}, onChange = {}) }
        val w = com.tmplayer.i18n.L
        assertButtonsFit(w.commonChange, w.languageKeep)
    }
}
