package com.tmplayer.desktop.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.abs

/**
 * Layout-consistency checks for a Compose screen, the kind of mistake a screenshot shows and a
 * behaviour test never does: a heading at 32 dp over cards at 42, two rows of one list whose names
 * start on different edges, buttons of one row with unequal gaps, the same role set in two text
 * sizes. Each takes the test tags of the nodes (siblings in one group) and fails with the numbers.
 *
 * Distances are in dp: the tests run at density 1 unless [density] says otherwise. [tolerance] is
 * 1 dp, a rounding of the layout, never a design difference.
 */
@OptIn(ExperimentalTestApi::class)
class LayoutChecks(
    private val ui: ComposeUiTest,
    private val density: Float = 1f,
    private val tolerance: Float = 1f,
) {
    private fun node(tag: String): SemanticsNode = ui.onNodeWithTag(tag).fetchSemanticsNode()

    private fun nodes(tags: List<String>): List<SemanticsNode> = tags.map(::node)

    private fun Float.dp() = this / density

    /** Every node of [tags] starts on one left edge (a column of headings, rows and cards). */
    fun sharedLeftEdge(tags: List<String>) {
        val lefts = nodes(tags).map { it.boundsInRoot.left.dp() }
        assertTrue("left edges of $tags differ by more than $tolerance dp: $lefts", lefts.max() - lefts.min() <= tolerance)
    }

    /** Every node of [tags] ends on one right edge (a heading's See all and the dots over the same page). */
    fun sharedRightEdge(tags: List<String>) {
        val rights = nodes(tags).map { it.boundsInRoot.right.dp() }
        assertTrue("right edges of $tags differ by more than $tolerance dp: $rights", rights.max() - rights.min() <= tolerance)
    }

    /** Every node of [tags] is as wide as the first. */
    fun equalWidths(tags: List<String>) {
        val widths = nodes(tags).map { it.boundsInRoot.width.dp() }
        assertTrue("widths of $tags differ: $widths", widths.max() - widths.min() <= tolerance)
    }

    /** Every node of [tags] is as tall as the first (buttons of one row). */
    fun equalHeights(tags: List<String>) {
        val heights = nodes(tags).map { it.boundsInRoot.height.dp() }
        assertTrue("heights of $tags differ: $heights", heights.max() - heights.min() <= tolerance)
    }

    /** The gaps between neighbouring nodes of [tags], in the order given, left to right, are all equal. */
    fun equalGapsAcross(tags: List<String>) {
        val boxes = nodes(tags).map { it.boundsInRoot }
        val gaps = boxes.zipWithNext { a, b -> (b.left - a.right).dp() }
        assertTrue("gaps across $tags differ: $gaps", gaps.isEmpty() || gaps.max() - gaps.min() <= tolerance)
    }

    /** The same, top to bottom. */
    fun equalGapsDown(tags: List<String>) {
        val boxes = nodes(tags).map { it.boundsInRoot }
        val gaps = boxes.zipWithNext { a, b -> (b.top - a.bottom).dp() }
        assertTrue("gaps down $tags differ: $gaps", gaps.isEmpty() || gaps.max() - gaps.min() <= tolerance)
    }

    /** [tag]'s middle is [parent]'s middle, across (Play and Pause on the middle of its bar). */
    fun centredAcross(tag: String, parent: String) {
        assertEquals("$tag is not centred across $parent", node(parent).boundsInRoot.center.x.dp(), node(tag).boundsInRoot.center.x.dp(), tolerance)
    }

    /** [tag]'s middle is [parent]'s middle, down (a label level with its icon). */
    fun centredDown(tag: String, parent: String) {
        assertEquals("$tag is not centred down $parent", node(parent).boundsInRoot.center.y.dp(), node(tag).boundsInRoot.center.y.dp(), tolerance)
    }

    /** [tag] stands wholly inside [parent]: nothing clipped at an edge. */
    fun inside(tag: String, parent: String) {
        val child = node(tag).boundsInRoot
        val box = node(parent).boundsInRoot
        assertTrue(
            "$tag $child leaves $parent $box",
            child.left >= box.left - tolerance * density && child.right <= box.right + tolerance * density &&
                child.top >= box.top - tolerance * density && child.bottom <= box.bottom + tolerance * density,
        )
    }

    /** Every Text under the nodes of [tags] is set in one font size (the same role, one size). */
    fun equalTextSizes(tags: List<String>) {
        val sizes = tags.map { tag -> tag to textSizeOf(ui.onNodeWithTag(tag)) }
        val missing = sizes.filter { it.second == null }
        assertTrue("no text layout under ${missing.map { it.first }}", missing.isEmpty())
        val values = sizes.map { it.second!! }
        assertTrue("font sizes of $tags differ: $values", values.max() - values.min() <= 0.01f)
    }

    /** The font size, in sp, of the first text under [node] (merged), or null. */
    fun textSizeOf(node: SemanticsNodeInteraction): Float? {
        val root = node.fetchSemanticsNode()
        val found = mutableListOf<TextLayoutResult>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(found)
            if (found.isEmpty()) n.children.forEach(::walk)
        }
        walk(root)
        return found.firstOrNull()?.layoutInput?.style?.fontSize?.value
    }

    /** The whole of two measures matches within the tolerance: a helper for one-off pairs. */
    fun same(what: String, a: Float, b: Float) {
        assertTrue("$what: $a against $b", abs(a - b) <= tolerance)
    }
}
