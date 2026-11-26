package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Translator
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Invariants over the desktop's screens (the 2026-11-25 audit), on the same made-up screens
 * [ScreenAuditShotsTest] draws, at the window sizes and font scales a desktop has: the floating
 * bar names every place whole in every language, no text is hard clipped, no short label is cut
 * with an ellipsis, a label sits in the middle of its button, and the last thing on a page can be
 * scrolled clear of the floating bar. Each failure lists the screen, the size and the words.
 */
@OptIn(ExperimentalTestApi::class)
class ScreenInvariantsTest {

    private val fx = ScreenAuditShotsTest()

    @After
    fun after() {
        Translator.use(Languages.ENGLISH)
        fx.reset()
    }

    private fun SemanticsNode.walk(): Sequence<SemanticsNode> = sequence {
        yield(this@walk)
        for (child in children) yieldAll(child.walk())
    }

    private fun SemanticsNode.layouts(): List<TextLayoutResult> {
        // A text field scrolls what is typed in it and an empty line has nothing to cut: neither is a clipped label.
        if (config.contains(SemanticsProperties.EditableText)) return emptyList()
        val action = config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return emptyList()
        return mutableListOf<TextLayoutResult>().also { action(it) }.filter { it.layoutInput.text.text.isNotBlank() }
    }

    /** Whether some of the words are not drawn: a line ellipsised, lines past the cap, a word wider than its box, or too tall for it. */
    private fun TextLayoutResult.cut(): Boolean = cutWhy() != null

    /** Why [cut] holds, with the numbers, for the failure message (a font on one CI system cuts what another does not); null when whole. */
    private fun TextLayoutResult.cutWhy(): String? {
        val c = layoutInput.constraints
        val box = c.maxWidth
        val widest = (0 until lineCount).maxOfOrNull { getLineRight(it) } ?: 0f
        return when {
            // Only when the words are taller than the room they were given: on macOS a Chinese, Japanese or Korean line
            // reports didOverflowHeight with 14 in 22 or 29 in no limit at all (the fallback font's metrics settle after
            // the box was sized), and nothing is cut then (run 45727885135).
            didOverflowHeight && multiParagraph.height > c.maxHeight -> "too tall: ${multiParagraph.height} in ${c.maxHeight}, $lineCount lines"
            multiParagraph.didExceedMaxLines -> "past ${layoutInput.maxLines} lines: $lineCount laid out, width ${multiParagraph.width} of $box"
            box != Constraints.Infinity && widest > box + 1f -> "too wide: $widest in $box"
            (0 until lineCount).any { isLineEllipsized(it) } -> "ellipsised: width $widest of $box"
            else -> null
        }
    }

    private fun TextLayoutResult.ellipsised(): Boolean = (0 until lineCount).any { isLineEllipsized(it) }

    private fun SemanticsNode.words(): String = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()

    /** Draws [screen] in a window of [w] by [h] dp at [fontScale] and hands the whole semantic tree to [check]. */
    private fun inWindow(screen: AuditScreen, w: Int, h: Int, fontScale: Float, check: ComposeUiTest.(SemanticsNode, Rect) -> Unit) = runComposeUiTest {
        fx.shell.go(screen.dest)
        screen.before()
        try {
            mainClock.autoAdvance = false
            setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, fontScale),
                    LocalWindowChrome provides fx.chrome(),
                    com.tmplayer.ui.components.LocalWindowControlsInset provides com.tmplayer.ui.components.windowControlsInset(3),
                ) {
                    TmMaterialTheme(dark = true) {
                        Surface(Modifier.size(w.dp, h.dp), color = Tone.background) {
                            Box(Modifier.size(w.dp, h.dp)) {
                                NavFrame(fx.shell, NavUpdate("Update", "2.0.1"), fx.longAccount, barShown = screen.bar) {
                                    if (screen.modal) {
                                        HomeRowsView(fx.shell, emptyList(), fx.chats, emptyMap(), onRowShown = {}, onArtWanted = {}, onRefresh = {})
                                    } else {
                                        screen.content()
                                    }
                                }
                                if (screen.modal) ModalPageHost(fx.shell) { screen.content() }
                                WindowControlsFallback(Modifier.align(Alignment.TopEnd))
                            }
                        }
                    }
                }
            }
            // Spinners and shimmers never go idle: the clock is stepped by hand instead of waited on.
            mainClock.advanceTimeBy(1_000)
            // A dialog or a popup is a root of its own beside the window's.
            for (root in onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()) check(root, root.boundsInRoot)
        } finally {
            screen.after()
            fx.shell.go(Destination.Home)
        }
    }

    private fun screens(): List<AuditScreen> = fx.pageScreens() + fx.settingScreens() + fx.overlayScreens()

    /** Every window and font scale the sweeps below run at. */
    private val cases = listOf(Triple(800, 600, 1.3f), Triple(1280, 800, 1f))

    private fun visible(node: SemanticsNode, window: Rect): Boolean {
        val b = node.boundsInRoot
        // A lazy list keeps an item it scrolled past composed but unplaced (its semantics keep their last bounds): not drawn.
        val placed = generateSequence(node) { it.parent }.all { it.layoutInfo.isPlaced }
        return placed && b.width > 0 && b.height > 0 && b.overlaps(window) && !node.config.contains(SemanticsProperties.InvisibleToUser)
    }

    @Test
    fun `the floating bar names every place whole in every language and at every font`() {
        val page = AuditScreen("bar") { Box(Modifier) }
        val problems = mutableListOf<String>()
        for (language in Languages.all) {
            Translator.use(language.tag)
            for (fontScale in listOf(1f, 1.3f, 2f)) {
                val names = listOf(L.browseTabHome, L.navChats, L.commonSearch, L.navDownloads)
                inWindow(page, 800, 600, fontScale) { root, window ->
                    for (node in root.walk()) {
                        val words = node.words()
                        if (words !in names) continue
                        val why = node.layouts().firstNotNullOfOrNull { it.cutWhy() }
                        if (why != null) problems += "${language.tag} x$fontScale: \"$words\" is cut ($why)"
                    }
                    val tabs = root.walk().filter { it.config.getOrNull(SemanticsProperties.Role)?.toString() == "Tab" }.toList()
                    val inside = tabs.all { window.contains(it.boundsInRoot.topLeft) && window.contains(it.boundsInRoot.bottomRight) }
                    if (!inside) problems += "${language.tag} x$fontScale: the bar runs out of the window"
                }
            }
        }
        assertTrue(problems.distinct().joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `no text is hard clipped on any screen`() {
        val problems = mutableListOf<String>()
        for (screen in screens()) for ((w, h, fs) in cases) {
            inWindow(screen, w, h, fs) { root, window ->
                for (node in root.walk()) {
                    if (!visible(node, window)) continue
                    for (layout in node.layouts()) {
                        val why = if (layout.layoutInput.overflow == TextOverflow.Clip) layout.cutWhy() else null
                        if (why != null) {
                            problems += "${screen.name} ${w}x$h x$fs: \"${layout.layoutInput.text.text.take(40)}\" is clipped ($why)"
                        }
                    }
                }
            }
        }
        assertTrue(problems.distinct().joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `no short label is cut with an ellipsis`() {
        val problems = mutableListOf<String>()
        for (screen in screens()) for ((w, h, fs) in cases) {
            inWindow(screen, w, h, fs) { root, window ->
                for (node in root.walk()) {
                    if (!visible(node, window)) continue
                    for (layout in node.layouts()) {
                        val text = layout.layoutInput.text.text
                        if (layout.ellipsised() && text.length <= 26) {
                            problems += "${screen.name} ${w}x$h x$fs: \"$text\" is cut"
                        }
                    }
                }
            }
        }
        assertTrue(problems.distinct().joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `a label sits in the middle of its button`() {
        val problems = mutableListOf<String>()
        for (screen in screens()) for ((w, h, fs) in cases) {
            inWindow(screen, w, h, fs) { root, window ->
                for (node in root.walk()) {
                    if (!visible(node, window) || !node.config.contains(SemanticsActions.OnClick)) continue
                    val role = node.config.getOrNull(SemanticsProperties.Role)?.toString()
                    if (role != "Button" && role != "Tab") continue
                    val texts = node.walk().filter { it !== node && it.layouts().isNotEmpty() }.toList()
                    // One line of words and nothing else tall beside it: a row with an icon is centred on its line too.
                    val only = texts.singleOrNull() ?: continue
                    val box = node.boundsInRoot
                    val words = only.boundsInRoot
                    val line = only.layouts().firstOrNull()?.getLineBottom(0)?.let { it - only.layouts()[0].getLineTop(0) } ?: continue
                    // Words that stand outside the button (a caption under a round icon button) are laid out by the design.
                    if (words.top >= box.bottom || words.bottom <= box.top) continue
                    if (words.height > line * 1.5f || box.height > 120f) continue
                    val dy = abs((box.top + box.bottom) / 2 - (words.top + words.bottom) / 2)
                    if (dy > 2f) problems += "${screen.name} ${w}x$h x$fs: \"${only.words().take(30)}\" is $dy px off the middle of its button"
                }
            }
        }
        assertTrue(problems.distinct().joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `the last thing on a page scrolls clear of the floating bar`() {
        val problems = mutableListOf<String>()
        val wanted = setOf("home", "history-continue", "history-watched", "series", "favourites-chats")
        for (screen in screens().filter { it.name in wanted }) for ((w, h, fs) in cases) {
            inWindow(screen, w, h, fs) { _, window ->
                // To the end of every scroller on the page, again (up to ten times) until none has further to go: a show's page draws its
                // list once its details have come (or a moment has passed), which on a slow machine is after the first
                // scroll, and a lazy list only knows its end once it has reached it.
                for (round in 0 until 10) {
                    for (scroller in onAllNodes(hasScrollAction(), useUnmergedTree = true).fetchSemanticsNodes()) {
                        val by = scroller.config.getOrNull(SemanticsActions.ScrollBy)?.action ?: continue
                        runOnUiThread { by(0f, 1_000_000f) }
                    }
                    mainClock.advanceTimeBy(1_000)
                    val ranges = onAllNodes(hasScrollAction(), useUnmergedTree = true).fetchSemanticsNodes()
                        .mapNotNull { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) }
                    if (ranges.isNotEmpty() && ranges.all { it.value() >= it.maxValue() - 0.5f }) break
                }
                val root = onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().first()
                val tabs = root.walk().filter { it.config.getOrNull(SemanticsProperties.Role)?.toString() == "Tab" }.toList()
                if (tabs.isEmpty()) return@inWindow
                val bar = Rect(
                    tabs.minOf { it.boundsInRoot.left },
                    tabs.minOf { it.boundsInRoot.top } - 6f,
                    tabs.maxOf { it.boundsInRoot.right },
                    tabs.maxOf { it.boundsInRoot.bottom } + 6f,
                )
                for (node in root.walk()) {
                    if (!visible(node, window) || !node.config.contains(SemanticsActions.OnClick)) continue
                    val role = node.config.getOrNull(SemanticsProperties.Role)?.toString()
                    if (role == "Tab" || node.boundsInRoot.height < 8f || node.boundsInRoot.width > window.width * 0.9f) continue
                    // The account's picture button stands in the bar, past the last tab.
                    if (node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("Account") } == true) continue
                    if (node.boundsInRoot.overlaps(bar) && node.boundsInRoot.bottom > bar.top + 2f) {
                        val name = node.words().ifBlank { node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: "a control" }
                        problems += "${screen.name} ${w}x$h x$fs: ${name.take(30)} (${role} ${node.boundsInRoot}, bar $bar) is under the floating bar at the end of the page"
                    }
                }
            }
        }
        assertTrue(problems.distinct().joinToString("\n"), problems.isEmpty())
    }
}
