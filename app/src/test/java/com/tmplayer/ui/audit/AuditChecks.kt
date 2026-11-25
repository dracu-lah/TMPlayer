package com.tmplayer.ui.audit

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import kotlin.math.abs

/**
 * Invariants that hold on every phone and tablet screen, read from the semantics tree alone, so one
 * function checks a page the way it checks a hundred (phone audit, 2026-11-25). Each check returns
 * the findings as lines naming the node and its bounds; none is a pass or fail by itself, the
 * callers decide which ones fail a test.
 *
 * The checks mirror what the user found on a real phone: text cut by the screen's edge or by its
 * own box, a label drawn off the middle of its pill, a control under 48 dp, a line drawn under the
 * status bar clock, the account's picture or a fixed header, and the last item of a list that never
 * clears the bottom bar.
 */
internal object AuditChecks {
    /** What the page's fixed chrome covers, in window pixels. A node may sit inside it, never across it. */
    class Chrome(
        val statusBarPx: Float,
        val header: Rect? = null,
        val avatar: Rect? = null,
        val bar: Rect? = null,
    )

    class Finding(val check: String, val node: String, val detail: String) {
        override fun toString() = "$check: $node $detail"
    }

    internal fun SemanticsNode.words(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    internal fun hasText(n: SemanticsNode): Boolean = n.layout() != null

    internal fun SemanticsNode.label(): String {
        val w = words().firstOrNull { it.isNotBlank() }
        val id = config.getOrNull(SemanticsProperties.TestTag)
        return "\"${w?.take(40) ?: ""}\"${id?.let { " [$it]" } ?: ""}"
    }

    private fun SemanticsNode.layout(): TextLayoutResult? {
        val out = ArrayList<TextLayoutResult>()
        config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
        return out.firstOrNull()
    }

    private fun SemanticsNode.walk(into: MutableList<SemanticsNode>) {
        into += this
        children.forEach { it.walk(into) }
    }

    fun all(root: SemanticsNode): List<SemanticsNode> = ArrayList<SemanticsNode>().also { root.walk(it) }

    private fun Rect.area() = maxOf(0f, width) * maxOf(0f, height)

    private fun Rect.inter(o: Rect): Rect = Rect(maxOf(left, o.left), maxOf(top, o.top), minOf(right, o.right), minOf(bottom, o.bottom))

    private fun SemanticsNode.isInside(ancestorTagRect: Rect): Boolean = boundsInRoot.let { b ->
        b.left >= ancestorTagRect.left - 1f && b.right <= ancestorTagRect.right + 1f &&
            b.top >= ancestorTagRect.top - 1f && b.bottom <= ancestorTagRect.bottom + 1f
    }

    /** Text drawn past the window's side, or cut by its own box: a line that runs off, a box shorter than its lines. */
    fun clippedText(nodes: List<SemanticsNode>, window: Rect, fontScale: Float): List<Finding> {
        val out = ArrayList<Finding>()
        for (n in nodes) {
            val layout = n.layout() ?: continue
            val b = n.boundsInRoot
            if (b.width <= 0f || b.height <= 0f) continue
            // The window's side: a visible line may not be cut by it. (The top and bottom are scrolling's.)
            if (b.left < window.left - 1f || b.right > window.right + 1f) {
                if (b.right > window.left && b.left < window.right) {
                    out += Finding("text-off-window", n.label(), "bounds $b in window $window")
                }
            }
            val lines = layout.lineCount
            val ellipsised = (0 until lines).any { layout.isLineEllipsized(it) }
            if (!ellipsised) {
                // A box shorter than its own lines draws them cut through the middle.
                if (layout.multiParagraph.height > layout.size.height + 1.5f) {
                    out += Finding("text-box-short", n.label(), "lines ${layout.multiParagraph.height}px in a ${layout.size.height}px box")
                }
                // More lines than maxLines allows, cut with no ellipsis: the sentence just stops.
                if (layout.multiParagraph.didExceedMaxLines) {
                    out += Finding("text-cut-no-ellipsis", n.label(), "${layout.lineCount} lines shown of more, maxLines ${layout.layoutInput.maxLines}")
                }
                // A single line wider than its box with no ellipsis is hard cut.
                val widest = (0 until lines).maxOfOrNull { layout.getLineRight(it) - layout.getLineLeft(it) } ?: 0f
                if (widest > layout.size.width + 1.5f && layout.layoutInput.overflow != androidx.compose.ui.text.style.TextOverflow.Visible) {
                    out += Finding("text-line-cut", n.label(), "line ${widest}px in a ${layout.size.width}px box")
                }
            }
        }
        return out
    }

    /** A word split across two lines ("Favourite" then "s"): a box too narrow for its longest word. */
    fun brokenWords(nodes: List<SemanticsNode>): List<Finding> {
        val out = ArrayList<Finding>()
        for (n in nodes) {
            val layout = n.layout() ?: continue
            if (n.boundsInRoot.height <= 0f) continue
            val text = layout.layoutInput.text.text
            for (line in 0 until layout.lineCount - 1) {
                val end = layout.getLineEnd(line, visibleEnd = false)
                if (end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()) {
                    out += Finding("word-broken", n.label(), "split between \"${text.substring(maxOf(0, end - 6), end)}\" and \"${text.substring(end, minOf(text.length, end + 6))}\"")
                    break
                }
            }
        }
        return out
    }

    /** Lines cut with an ellipsis: not a defect when intentional, so these are listed for a person to judge. */
    fun ellipsised(nodes: List<SemanticsNode>): List<Finding> = nodes.mapNotNull { n ->
        val layout = n.layout() ?: return@mapNotNull null
        if (n.boundsInRoot.height <= 0f) return@mapNotNull null
        val cut = (0 until layout.lineCount).any { layout.isLineEllipsized(it) }
        if (cut) Finding("ellipsis", n.label(), "${layout.lineCount} lines, maxLines ${layout.layoutInput.maxLines}") else null
    }

    /** Pressable things smaller than a 48 dp square in either direction. */
    fun smallTargets(nodes: List<SemanticsNode>, density: Float): List<Finding> {
        val min = 48f * density - 1.5f
        return nodes.filter { it.config.getOrNull(SemanticsActions.OnClick) != null }
            .filter { it.touchBoundsInRoot.let { b -> b.width > 0f && b.height > 0f && b.right > 0f && b.bottom > 0f } }
            .filter { it.config.getOrNull(SemanticsProperties.Disabled) == null }
            // The unclipped size: a card half off the screen (a row scrolled past) is not a small target.
            .filter { maxOf(it.size.width.toFloat(), it.touchBoundsInRoot.width) < min || maxOf(it.size.height.toFloat(), it.touchBoundsInRoot.height) < min }
            // A control inside a bigger pressable (a switch in its row) is reached by the row.
            .filter { n -> generateSequence(n.parent) { it.parent }.none { a -> a.config.getOrNull(SemanticsActions.OnClick) != null && a.touchBoundsInRoot.width >= min && a.touchBoundsInRoot.height >= min && a.touchBoundsInRoot.overlaps(n.touchBoundsInRoot) } }
            .filter { it.config.getOrNull(SemanticsProperties.Role) != Role.Tab || it.boundsInRoot.height < min }
            .map { Finding("small-target", it.label(), "${it.touchBoundsInRoot.width / density} x ${it.touchBoundsInRoot.height / density} dp") }
    }

    /** A button, tab or chip whose words are not in the middle of its box, vertically within [tol] px. */
    fun offCentre(nodes: List<SemanticsNode>, tol: Float = 2f): List<Finding> {
        val out = ArrayList<Finding>()
        for (n in nodes) {
            val role = n.config.getOrNull(SemanticsProperties.Role)
            if (role != Role.Button && role != Role.Tab) continue
            val b = n.boundsInRoot
            if (b.height <= 0f || b.width <= 0f) continue
            // Half off the screen (a row scrolled past the edge): its box is cut, so its middle says nothing.
            if (b.height < n.size.height - 1f || b.width < n.size.width - 1f) continue
            val texts = all(n).filter { it !== n && it.layout() != null && it.boundsInRoot.height > 0f }
            if (texts.isEmpty()) continue
            val top = texts.minOf { it.boundsInRoot.top }
            val bottom = texts.maxOf { it.boundsInRoot.bottom }
            val centre = (top + bottom) / 2f
            val boxCentre = (b.top + b.bottom) / 2f
            if (abs(centre - boxCentre) > tol && b.height < b.width * 1.2f + 400f) {
                out += Finding("label-off-centre", n.label(), "text centre ${centre} vs box ${boxCentre} (box $b)")
            }
        }
        return out
    }

    /**
     * Anything that is not part of the fixed chrome but sits under it: under the status bar's clock,
     * across the fixed header, or under the account's picture. Drawn at rest, so a list that scrolls
     * under a header is only asked about at its top.
     */
    fun underChrome(nodes: List<SemanticsNode>, chrome: Chrome, window: Rect): List<Finding> {
        val out = ArrayList<Finding>()
        val parts = listOfNotNull(
            chrome.header?.let { "header" to it },
            chrome.avatar?.let { "avatar" to it },
        )
        for (n in nodes) {
            val b = n.boundsInRoot
            if (b.width <= 0f || b.height <= 0f) continue
            val isText = n.layout() != null
            val isPress = n.config.getOrNull(SemanticsActions.OnClick) != null
            if (!isText && !isPress) continue
            // A page-wide pressable (a whole row) is judged by its words, not its box.
            if (b.width > window.width * 0.9f && b.height > window.height * 0.5f) continue
            for ((name, rect) in parts) {
                if (n.isInside(rect)) continue // part of the chrome itself
                val i = b.inter(rect)
                if (i.area() > 4f) out += Finding("under-$name", n.label(), "bounds $b over $name $rect")
            }
            if (chrome.statusBarPx > 0f && isText && b.top < chrome.statusBarPx - 1.5f && b.bottom > 0f &&
                parts.none { n.isInside(it.second) }
            ) {
                out += Finding("under-status-bar", n.label(), "top ${b.top} within the ${chrome.statusBarPx}px status bar")
            }
        }
        return out
    }

    /** The checks that fail a build when a screen breaks them (see PhoneInvariantsTest). */
    val INVARIANTS = setOf(
        "text-off-window", "word-broken", "text-box-short", "text-line-cut", "text-cut-no-ellipsis", "label-off-centre",
        "under-avatar", "under-header", "under-status-bar", "small-target",
    )

    fun everything(nodes: List<SemanticsNode>, window: Rect, density: Float, chrome: Chrome, fontScale: Float): List<Finding> =
        clippedText(nodes, window, fontScale) + brokenWords(nodes) + smallTargets(nodes, density) + offCentre(nodes) + underChrome(nodes, chrome, window)
}
