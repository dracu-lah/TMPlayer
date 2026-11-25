package com.tmplayer.tvux

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.TextUnitType
import com.tmplayer.ui.theme.FocusContrast
import com.tmplayer.ui.theme.FocusStyle
import com.tmplayer.ui.theme.contrastRatio
import org.robolectric.Shadows.shadowOf
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * The tv-ux-review checklist as rules a machine reads (WP-16): helpers over the semantics tree and
 * the captured frame, each one adding a [Finding] that names the rule and the node. The suite
 * ([TvUxChecklistTest], [TvUxPlayerRowTest]) runs them on every TV screen and fails on any finding
 * that no `tvux-allow/WP-xx.txt` line allows; [TvUxSelfTest] holds each rule to failing on the
 * case it exists for. Run with `./gradlew :app:testDebugUnitTest -Ptvux=true --tests 'com.tmplayer.tvux.*'`.
 */

/** One broken rule on one node of one screen. [node] is the node's words, or its role and place. */
data class Finding(val screen: String, val rule: String, val node: String, val detail: String) {
    /** As an allow list line, so a new known failure can be pasted into its package's file. */
    fun allowLine(reason: String = "TODO"): String = "$screen | $rule | $node | $reason"

    override fun toString(): String = "$rule on '$node' ($screen): $detail"
}

/**
 * The known failures, one file per package under `app/src/test/resources/tvux-allow/` (WP-16 step
 * 6), so two packages in one batch never write the same list. A line is
 * `screen | rule | node | reason`; `*` in the first three matches anything, `#` starts a comment.
 * Each TV package deletes its own lines as it fixes them; every file is empty by device checkpoint 4.
 */
object TvUxAllow {
    data class Line(val screen: String, val rule: String, val node: String, val reason: String, val file: String) {
        fun allows(f: Finding) = glob(screen, f.screen) && glob(rule, f.rule) && glob(node, f.node)
    }

    /** Every line of every file in [dir], or of the directory the test resources hold. */
    fun load(dir: File? = null): List<Line> {
        val folder = dir ?: resourceDir() ?: return emptyList()
        return folder.listFiles { f -> f.isFile && f.name.endsWith(".txt") }.orEmpty().sortedBy { it.name }.flatMap { file ->
            file.readLines().mapNotNull { parse(it, file.name) }
        }
    }

    /** One line, or null for a blank line, a comment or a line without its four fields. */
    fun parse(raw: String, file: String): Line? {
        val text = raw.substringBefore('#').trim()
        if (text.isEmpty()) return null
        val parts = text.split('|').map { it.trim() }
        if (parts.size < 4 || parts.take(3).any { it.isEmpty() }) return null
        return Line(parts[0], parts[1], parts[2], parts.drop(3).joinToString(" | "), file)
    }

    private fun resourceDir(): File? {
        val url = TvUxAllow::class.java.classLoader?.getResource("tvux-allow/README.txt")
        val fromClasspath = url?.takeIf { it.protocol == "file" }?.let { File(it.toURI()).parentFile }
        return fromClasspath ?: listOf(File("src/test/resources/tvux-allow"), File("app/src/test/resources/tvux-allow")).firstOrNull { it.isDirectory }
    }

    /** [pattern] against [text], with `*` for any run of characters. */
    fun glob(pattern: String, text: String): Boolean {
        if (pattern == "*") return true
        val regex = pattern.split('*').joinToString(".*") { Regex.escape(it) }
        return Regex(regex, RegexOption.DOT_MATCHES_ALL).matches(text)
    }
}

/**
 * Fails with every finding [allow] does not cover, one per line, and prints the lines that could
 * be pasted into an allow list. A line that allowed nothing on a screen it names is printed as
 * stale, not failed, so a fix in another package never breaks this one.
 */
fun assertNoFindings(screen: String, findings: List<Finding>, allow: List<TvUxAllow.Line> = TvUxAllow.load()) {
    val open = findings.distinct().filter { f -> allow.none { it.allows(f) } }
    val used = allow.filter { line -> findings.any { line.allows(it) } }
    val stale = allow.filter { it.screen != "*" && TvUxAllow.glob(it.screen, screen) && it !in used }
    if (stale.isNotEmpty()) println("tvux $screen: allow lines that matched nothing (delete them): " + stale.joinToString { "${it.file}: ${it.rule} ${it.node}" })
    if (findings.isNotEmpty()) {
        val out = File("build/tvux").apply { mkdirs() }.resolve("$screen.txt")
        out.writeText(findings.distinct().joinToString("\n") { it.allowLine() } + "\n")
    }
    if (open.isNotEmpty()) {
        throw AssertionError(
            "tvux $screen: ${open.size} finding(s) no tvux-allow line covers:\n" +
                open.joinToString("\n") { "  $it" } +
                "\nAs allow lines (only for a known failure another package fixes):\n" +
                open.joinToString("\n") { "  " + it.allowLine() },
        )
    }
}

/** A captured frame of the whole window, in physical pixels. */
class Frame(val width: Int, val height: Int, private val px: IntArray) {
    fun at(x: Int, y: Int): Int = px[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]

    fun color(x: Int, y: Int): Color = Color(at(x, y))

    companion object {
        fun of(bitmap: Bitmap): Frame {
            val px = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return Frame(bitmap.width, bitmap.height, px)
        }
    }
}

/** Two pixels differ enough to be a drawn change, not antialiasing noise. */
private fun differs(a: Int, b: Int): Boolean {
    val dr = abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF))
    val dg = abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF))
    val db = abs((a and 0xFF) - (b and 0xFF))
    return max(dr, max(dg, db)) > 24
}

/**
 * The bounding box of what changed between [rest] and [focused] inside [region], leaving out
 * [skip] (the node the focus came from, which changes too). Null when nothing changed.
 */
fun drawnChange(rest: Frame, focused: Frame, region: Rect, skip: Rect?, extraSkip: List<Rect> = emptyList()): Rect? {
    var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = Int.MIN_VALUE; var b = Int.MIN_VALUE
    val x0 = region.left.toInt().coerceAtLeast(0); val x1 = region.right.toInt().coerceAtMost(rest.width - 1)
    val y0 = region.top.toInt().coerceAtLeast(0); val y1 = region.bottom.toInt().coerceAtMost(rest.height - 1)
    for (y in y0..y1) for (x in x0..x1) {
        val at = Offset(x.toFloat(), y.toFloat())
        if (skip != null && skip.contains(at)) continue
        if (extraSkip.any { it.contains(at) }) continue
        if (differs(rest.at(x, y), focused.at(x, y))) {
            l = min(l, x); t = min(t, y); r = max(r, x); b = max(b, y)
        }
    }
    return if (l == Int.MAX_VALUE) null else Rect(l.toFloat(), t.toFloat(), r + 1f, b + 1f)
}

/**
 * How far the focused look stands out from the rest look at the node's edge: for points every
 * couple of dp round [bounds], the best WCAG ratio between the two frames along the edge's normal
 * (from [outside] px outside to [inside] px inside), and the median of those. A ring or a fill
 * change gives the whole edge a high ratio; a yellow button that stays yellow gives about 1.
 */
fun edgeContrast(rest: Frame, focused: Frame, bounds: Rect, step: Float, outside: Float, inside: Float): Float {
    val ratios = mutableListOf<Float>()
    fun probe(x: Float, y: Float, nx: Float, ny: Float) {
        var best = 1f
        var d = -outside
        while (d <= inside) {
            val px = (x + nx * d).toInt(); val py = (y + ny * d).toInt()
            if (px in 0 until rest.width && py in 0 until rest.height) {
                best = max(best, contrastRatio(rest.color(px, py), focused.color(px, py)))
            }
            d += 1f
        }
        ratios += best
    }
    var x = bounds.left + step
    while (x < bounds.right - step) { probe(x, bounds.top, 0f, 1f); probe(x, bounds.bottom - 1, 0f, -1f); x += step }
    var y = bounds.top + step
    while (y < bounds.bottom - step) { probe(bounds.left, y, 1f, 0f); probe(bounds.right - 1, y, -1f, 0f); y += step }
    if (ratios.isEmpty()) return 1f
    ratios.sort()
    return ratios[ratios.size / 2]
}

/** What one interface size and font scale pass is called in a finding, such as `textFloor@Smaller,1.3`. */
data class SizePass(val name: String, val uiFactor: Float, val fontScale: Float)

/**
 * The checklist over one screen held by a compose [rule] at TV qualifiers (960 x 540 dp). Each
 * check adds to [findings]; nothing here asserts, so a screen reports everything at once.
 */
class TvUxChecklist(
    private val rule: AndroidComposeTestRule<*, out ComponentActivity>,
    val screen: String,
) {
    val findings = mutableListOf<Finding>()

    /** The nodes a focus check could not read, and why: printed with the findings, never failed. */
    val skipped = mutableListOf<String>()

    private val activity get() = rule.activity

    /** The screen's own density, before any interface size scales it: the floors are in these dp. */
    val density: Float get() = activity.resources.displayMetrics.density

    private fun dp(v: Float) = v * density

    fun add(rule: String, node: String, detail: String) {
        findings += Finding(screen, rule, node, detail)
    }

    // ----- time and keys -------------------------------------------------------------------------

    /** Frames and the main looper for [ms] of the test clock, with a little real time for disk reads. */
    fun settle(ms: Long = 600) {
        var left = ms
        while (left > 0) {
            val step = min(left, 100L)
            rule.mainClock.advanceTimeBy(step)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(step))
            left -= step
        }
        rule.waitForIdle()
    }

    /** A window the remote works in: out of touch mode and holding focus, as on a television. */
    fun windowForRemote() {
        // The activity's window and every dialog over it (a screen drawn in a Dialog has a window of its own).
        val roots = runCatching {
            val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
            @Suppress("UNCHECKED_CAST")
            (org.robolectric.util.ReflectionHelpers.getField<Any>(global, "mViews") as List<View>).toList()
        }.getOrNull().orEmpty().plus(activity.window.decorView).distinct().mapNotNull { it.parent }
        for (viewRoot in roots) {
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
                viewRoot, "ensureTouchMode",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
            )
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
                viewRoot, "windowFocusChanged",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
            )
        }
        settle(200)
    }

    /** One press and release of [code] through the window, the path a remote's key takes. */
    fun key(code: Int, settleMs: Long = 300) {
        val now = SystemClock.uptimeMillis()
        send(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        send(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        settle(settleMs)
    }

    /**
     * [code] held: the first press, then [repeats] repeats that waited [lateMs] in the queue (a
     * stick whose frames take 40 ms), then the release. A list that drops stale repeats moves once.
     */
    fun held(code: Int, repeats: Int, lateMs: Long = 400, after: () -> Unit = {}) {
        val down = SystemClock.uptimeMillis()
        send(KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0))
        settle(50)
        after()
        repeat(repeats) { n ->
            val now = SystemClock.uptimeMillis()
            send(KeyEvent(down, now - lateMs, KeyEvent.ACTION_DOWN, code, n + 1))
            settle(20)
            after()
        }
        val up = SystemClock.uptimeMillis()
        send(KeyEvent(down, up, KeyEvent.ACTION_UP, code, 0))
        settle(300)
    }

    // ----- the tree ------------------------------------------------------------------------------

    /** Read only the window on top (a prompt over its page), so the page under it is not read again. */
    var topWindowOnly = false

    private fun roots(merged: Boolean = true): List<SemanticsNode> =
        rule.onAllNodes(isRoot(), useUnmergedTree = !merged).fetchSemanticsNodes(atLeastOneRootRequired = false)
            .let { if (topWindowOnly && it.size > 1) listOf(it.last()) else it }

    private fun walk(node: SemanticsNode, out: MutableList<SemanticsNode>) {
        out += node
        node.children.forEach { walk(it, out) }
    }

    /** Every node of every window (dialogs and popups included), merged as TalkBack reads them. */
    fun nodes(merged: Boolean = true): List<SemanticsNode> = mutableListOf<SemanticsNode>().also { all -> roots(merged).forEach { walk(it, all) } }

    /** Whether the node is shown at all: on screen and not empty. */
    private fun shown(n: SemanticsNode): Boolean {
        val b = n.boundsInRoot
        return b.width > 0.5f && b.height > 0.5f && !n.config.contains(SemanticsProperties.InvisibleToUser)
    }

    /** Cut by a parent or the screen's edge: a peek of the next item, which the size rules skip. */
    fun clipped(n: SemanticsNode): Boolean {
        val b = n.boundsInRoot
        return b.width + 1f < n.size.width || b.height + 1f < n.size.height
    }

    /**
     * The node's laid out rect in the window, before any clip or drawn scale: its layout size round
     * its drawn centre (a graphics layer's scale moves the corner [SemanticsNode.positionInRoot]
     * reports, never the centre).
     */
    fun layoutRect(n: SemanticsNode): Rect {
        val w = n.size.width.toFloat(); val h = n.size.height.toFloat()
        val c = if (clipped(n)) n.positionInRoot + Offset(w / 2, h / 2) else n.boundsInRoot.center
        return Rect(c.x - w / 2, c.y - h / 2, c.x + w / 2, c.y + h / 2)
    }

    /** Every node the remote can land on, in reading order. */
    fun focusables(): List<SemanticsNode> = nodes()
        .filter { it.config.contains(SemanticsProperties.Focused) && shown(it) }
        .sortedWith(compareBy({ it.boundsInRoot.top.toInt() / 8 }, { it.boundsInRoot.left }))

    fun focused(): List<SemanticsNode> = nodes().filter { it.config.getOrNull(SemanticsProperties.Focused) == true }

    private val unnamed = mutableMapOf<Pair<Int, Int>, String>()

    /** What a finding calls the node: its words, its description, its tag, or its role and place. */
    fun nameOf(n: SemanticsNode): String {
        val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }?.trim()
        val desc = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")?.trim()
        val tag = n.config.getOrNull(SemanticsProperties.TestTag)
        val words = listOf(desc, text, tag).firstOrNull { !it.isNullOrBlank() }
        if (words != null) return words.replace('|', '/').replace('\n', ' ').take(60).trim()
        val role = n.config.getOrNull(SemanticsProperties.Role)?.toString() ?: "node"
        val b = n.boundsInRoot
        return unnamed.getOrPut(b.left.toInt() / 16 to b.top.toInt() / 16) { "unnamed $role at ${(b.left / density).toInt()},${(b.top / density).toInt()} dp" }
    }

    private fun interaction(n: SemanticsNode) =
        rule.onAllNodes(SemanticsMatcher("node ${n.id}") { it.id == n.id && it.boundsInRoot == n.boundsInRoot }).onFirst()

    /** Puts the focus on [n] the way a screen's own request would; false when it refused. */
    fun focus(n: SemanticsNode, settleMs: Long = 400): Boolean {
        runCatching { interaction(n).requestFocus() }
        settle(settleMs)
        return current(n)?.config?.getOrNull(SemanticsProperties.Focused) == true
    }

    /** [n] as the tree has it now (its bounds and state may have changed). */
    fun current(n: SemanticsNode): SemanticsNode? = nodes().firstOrNull { it.id == n.id }

    /**
     * The windows on screen, bottom first: the activity's, then any dialog or popup over it. Read
     * from WindowManagerGlobal's list (hidden API, so by reflection), with the activity's alone
     * when that cannot be read.
     */
    private fun windows(): List<View> {
        val all = runCatching {
            val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
            @Suppress("UNCHECKED_CAST")
            (org.robolectric.util.ReflectionHelpers.getField<Any>(global, "mViews") as List<View>).toList()
        }.getOrNull().orEmpty().filter { it.isShown && it.width > 0 }
        return all.ifEmpty { listOf(activity.window.decorView) }
    }

    /** A key to the window on top, as the system sends it to the focused window: a dialog's keys go to the dialog. */
    private fun send(event: KeyEvent) {
        // A tip (FocusTip, a one-time hint) is a popup that cannot take the focus: the system sends the key
        // past it to the window under, so the top window here is the topmost one that can.
        val all = windows()
        val top = all.lastOrNull { ((it.layoutParams as? android.view.WindowManager.LayoutParams)?.flags ?: 0) and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0 } ?: all.last()
        if (top === activity.window.decorView) activity.dispatchKeyEvent(event) else top.dispatchKeyEvent(event)
    }

    /** Every window as drawn now, each at its place on the screen. */
    fun frame(): Frame {
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val origin = IntArray(2).also { root.getLocationOnScreen(it) }
        for (w in windows()) {
            val at = IntArray(2).also { w.getLocationOnScreen(it) }
            canvas.save()
            canvas.translate((at[0] - origin[0]).toFloat(), (at[1] - origin[1]).toFloat())
            w.draw(canvas)
            canvas.restore()
        }
        return Frame.of(bitmap)
    }

    // ----- the rules -----------------------------------------------------------------------------

    /** entryFocus: exactly one node holds the remote once the screen is up. */
    fun entryFocus() {
        val on = focused()
        if (on.size != 1) add("entryFocus", if (on.isEmpty()) "screen" else on.joinToString(" + ") { nameOf(it) }, "${on.size} focused nodes on arrival, want exactly 1")
    }

    /** safeArea: every node fully drawn (not a peek) sits inside 48 dp across and 27 dp down. */
    fun safeArea(h: Float = 48f, v: Float = 27f) {
        val w = activity.window.decorView.width.toFloat(); val hgt = activity.window.decorView.height.toFloat()
        val inside = Rect(dp(h) - 1f, dp(v) - 1f, w - dp(h) + 1f, hgt - dp(v) + 1f)
        val seen = mutableSetOf<String>()
        for (n in nodes()) {
            if (!shown(n) || clipped(n)) continue
            val interesting = n.config.contains(SemanticsProperties.Focused) || n.config.contains(SemanticsProperties.Text)
            if (!interesting) continue
            val b = n.boundsInRoot
            if (b.width >= w - 2f && b.height >= hgt - 2f) continue
            if (b.left < inside.left || b.top < inside.top || b.right > inside.right || b.bottom > inside.bottom) {
                val name = nameOf(n)
                if (seen.add(name)) add("safeArea", name, "at ${rectDp(b)} dp, outside the ${h.toInt()} x ${v.toInt()} dp safe area")
            }
        }
    }

    /** targets: every focusable's focus rect is at least [min] dp tall. */
    fun targets(minDp: Float = 48f) {
        for (n in focusables()) {
            if (clipped(n)) continue
            val hDp = n.size.height / density
            if (hDp + 0.5f < minDp) add("targets", nameOf(n), "focus rect ${"%.1f".format(hDp)} dp tall, want $minDp")
        }
    }

    /**
     * labelCentre: in every button, pill, chip or tab (a focusable with a role, at most [maxDp] tall) the words sit on
     * the control's own middle line, within [tolPx] pixels (TV, 2026-11-25: Find a setting drew its label 7 px above
     * the middle of a 96 px pill). The words are the unmerged text nodes inside the control, taken as one block.
     */
    fun labelsCentred(tolPx: Float = 2f, maxDp: Float = 72f) {
        val texts = nodes(merged = false).filter { shown(it) && it.config.contains(SemanticsProperties.Text) }
        val seen = mutableSetOf<String>()
        for (n in focusables()) {
            if (clipped(n)) continue
            val role = n.config.getOrNull(SemanticsProperties.Role) ?: continue
            if (role != Role.Button && role != Role.Tab && role != Role.RadioButton) continue
            val box = n.boundsInRoot
            if (box.height / density > maxDp) continue
            val inside = texts.filter { t ->
                val b = t.boundsInRoot
                b.left >= box.left - 1f && b.right <= box.right + 1f && b.top >= box.top - 1f && b.bottom <= box.bottom + 1f && b.height > 0f
            }
            if (inside.isEmpty()) continue
            val top = inside.minOf { it.boundsInRoot.top }
            val bottom = inside.maxOf { it.boundsInRoot.bottom }
            val off = (top + bottom) / 2f - box.center.y
            if (abs(off) > tolPx) {
                val name = nameOf(n)
                if (seen.add(name)) add("labelCentre", name, "words centre ${"%.1f".format(off)} px off the middle of a ${"%.0f".format(box.height)} px control (${"%.1f".format(off / density)} dp)")
            }
        }
    }

    /** labelled: every focusable has words or a description, and a role; toggles carry their state. */
    fun labelled() {
        for (n in focusables()) {
            val c = n.config
            val words = !c.getOrNull(SemanticsProperties.Text).isNullOrEmpty() || !c.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty() ||
                !c.getOrNull(SemanticsProperties.EditableText)?.text.isNullOrEmpty()
            val role = c.getOrNull(SemanticsProperties.Role)
            val editable = c.contains(SemanticsProperties.EditableText)
            if (!words) add("labelled", nameOf(n), "no text or contentDescription")
            if (role == null && !editable) add("labelled", nameOf(n), "no role")
            val toggle = role == Role.Switch || role == Role.Checkbox
            if (toggle && !c.contains(SemanticsProperties.ToggleableState)) add("labelled", nameOf(n), "a $role without ToggleableState")
            if (role == Role.RadioButton && !c.contains(SemanticsProperties.Selected) && !c.contains(SemanticsProperties.ToggleableState)) {
                add("labelled", nameOf(n), "a RadioButton without Selected")
            }
        }
    }

    /**
     * textFloor: every text laid out at [floorSp] or more, in the screen's own sp (an interface
     * size below Default draws it smaller), for [pass]'s size and font scale. One finding per text.
     */
    fun textFloor(pass: SizePass, floorSp: Float = 14f) {
        val seen = mutableSetOf<String>()
        for (n in nodes(merged = false)) {
            if (!shown(n)) continue
            val action = n.config.getOrNull(SemanticsActions.GetTextLayoutResult) ?: continue
            val results = mutableListOf<TextLayoutResult>()
            runCatching { action.action?.invoke(results) }
            for (r in results) {
                val style = r.layoutInput.style
                val d = r.layoutInput.density
                val size = style.fontSize
                if (size.type != TextUnitType.Sp) continue
                val px = size.value * d.fontScale * d.density
                // The floor is in the screen's own sp at font 1.0: a larger font grows the text and
                // leaves the floor, so a screen that undoes the viewer's font scale is caught.
                val floorPx = floorSp * density
                if (px + 0.05f < floorPx) {
                    val name = nameOf(n)
                    if (seen.add(name)) add("textFloor@${pass.name}", name, "laid out at ${"%.1f".format(px / density)} sp of the screen, want $floorSp")
                }
            }
        }
    }

    /**
     * The checks that need the focus moved: for up to [limit] focusables, the rest frame (the remote
     * on its nearest neighbour) against the focused frame. [focusSizeStable], [drawnInsideClip],
     * [focusContrast] and [selectedNotFocused] read the pair.
     */
    fun focusWalk(limit: Int = 12) {
        val all = focusables().filterNot { clipped(it) }
        if (all.size < 2) return
        val screenW = activity.window.decorView.width.toFloat(); val screenH = activity.window.decorView.height.toFloat()
        for (start in all.take(limit)) {
            // A pixel read that fails is read once more, and only what fails both times counts.
            val mark = findings.size
            measure(start, screenW, screenH)
            if (findings.size == mark) continue
            val first = findings.subList(mark, findings.size).toList()
            findings.subList(mark, findings.size).clear()
            measure(start, screenW, screenH)
            val second = findings.subList(mark, findings.size).map { it.rule to it.node }.toSet()
            findings.subList(mark, findings.size).clear()
            findings += first.filter { (it.rule to it.node) in second }
        }
    }

    /** One rest and focused pair of frames for [start], and the rules that read them. */
    private fun measure(start: SemanticsNode, screenW: Float, screenH: Float) {
        run {
            val node = current(start) ?: run { skipped += "${nameOf(start)}: gone"; null } ?: return
            val other = (current(start)?.let { n -> focusables().filter { it.id != n.id && !it.boundsInRoot.overlaps(n.boundsInRoot) } } ?: emptyList())
                .minByOrNull { (it.boundsInRoot.center - node.boundsInRoot.center).getDistance() } ?: return
            // Once on the node first, so a list scrolls it into its place before anything is read.
            if (!focus(node)) { skipped += "${nameOf(node)}: refused the focus"; return }
            if (!focus(other)) { skipped += "${nameOf(node)}: its neighbour ${nameOf(other)} refused the focus"; return }
            val before = focusables().associate { it.id to layoutRect(it) }
            val restNode = current(node) ?: return
            val rest = frame()
            if (!focus(restNode)) { skipped += "${nameOf(restNode)}: refused the focus back"; return }
            val after = focusables().associate { it.id to layoutRect(it) }
            val focusedNode = current(node) ?: return
            val focusedFrame = frame()
            val name = nameOf(focusedNode)
            val otherBefore = before[other.id]; val otherAfter = after[other.id]
            if (otherBefore == null || otherAfter == null) { skipped += "$name: its neighbour left the tree"; return }
            // The list scrolled under the move: the frames are of different places, read nothing.
            // Centres, since a scale drawn in a graphics layer moves a node's corner but not its centre.
            if ((otherBefore.center - otherAfter.center).getDistance() > 1.5f) {
                skipped += "$name: the page scrolled under the move (${nameOf(other)} from ${rectDp(otherBefore)} to ${rectDp(otherAfter)} dp)"
                return
            }

            val restRect = layoutRect(restNode); val nowRect = layoutRect(focusedNode)
            val tol = dp(0.5f) + 1f
            if (abs(restRect.width - nowRect.width) > tol || abs(restRect.height - nowRect.height) > tol) {
                add("focusSizeStable", name, "layout ${sizeDp(restRect)} at rest, ${sizeDp(nowRect)} focused")
            }
            val moved = after.filter { (id, r) -> id != node.id && id != other.id && before[id]?.let { b -> (b.center - r.center).getDistance() > tol || abs(b.width - r.width) > tol || abs(b.height - r.height) > tol } == true }
                .filterKeys { id -> before[id]?.let { !it.overlaps(restRect) } ?: false }
            if (moved.isNotEmpty()) add("focusSizeStable", name, "focus moved ${moved.size} other node(s), first at ${rectDp(before.getValue(moved.keys.first()))} dp")

            val margin = max(dp(16f), 0.25f * max(restRect.width, restRect.height))
            val region = restRect.inflate(margin).intersect(Rect(0f, 0f, screenW, screenH))
            // The neighbour changes too (it gave up the focus). Its rect is left out with a margin for
            // a neighbour that grows. Where that margin would reach the node itself (two full width
            // rows or two pills side by side) nothing is left out: the frame is read against a
            // neutral one instead, with the remote on a node far from the region (WP-03), so the
            // neighbour's fill swap is no change and a growth into its rect still is one.
            val otherRect = layoutRect(other)
            // The neighbour's own container too, up to the first one that comes near the node under
            // test: a card that fills when a child has the remote (the accent colours, WP-06) changes
            // its whole look between the two frames, which is the neighbour's change and not growth of
            // the node. A container that holds the node itself, or a list, is never skipped.
            var otherArea = otherRect
            var up = other.parent
            while (up != null && up.parent != null) {
                val r = layoutRect(up)
                if (r.overlaps(restRect.inflate(dp(4f)))) break
                otherArea = r
                up = up.parent
            }
            val skip = otherArea.inflate(max(dp(6f), 0.1f * max(other.size.width, other.size.height).toFloat())).takeUnless { it.overlaps(restRect) }
            var baseline = rest
            var skipNow = skip
            // The frame the focused one is read against, with the remote on a node far from the region (WP-03).
            fun takeNeutral() {
                // A node that is not the neighbour and not beside the node: only its own fill swap
                // differs between the two frames, and its rect is left out.
                // One that is not beside the node when there is one; else the nearest that is: its own
                // rect is left out, so a growth into it is read on the node's other sides alone.
                val spare = focusables().filter { it.id != node.id && it.id != other.id }
                val far = (spare.filter { !layoutRect(it).overlaps(restRect.inflate(dp(16f))) }.ifEmpty { spare.filter { !layoutRect(it).overlaps(restRect.inflate(dp(1f))) } })
                    .minByOrNull { (it.boundsInRoot.center - node.boundsInRoot.center).getDistance() }
                if (far != null && focus(far)) {
                    val still = current(node)?.let { layoutRect(it) }
                    if (still != null && (still.center - restRect.center).getDistance() <= tol) {
                        baseline = frame()
                        skipNow = layoutRect(current(far) ?: far).inflate(dp(1f))
                    }
                }
                if (baseline === rest) {
                    // No neutral frame (a pair alone on the screen, or a list that moved): leave out the
                    // neighbour's own rect and 1 dp round it. A growth is symmetric, so what it adds on
                    // the node's other sides still shows; one only towards the neighbour would not.
                    skipNow = otherArea.inflate(dp(1f)).takeUnless { it.overlaps(restRect) }
                }
                // Put the remote back where the next check expects it.
                current(node)?.let { if (!focus(it)) skipped += "${nameOf(it)}: refused the focus back after the neutral frame" }
            }
            if (skip == null) takeNeutral()
            // A pinned header (Settings' breadcrumb) changes with the section the focus is in, and the detail strip
            // under the pane (WP-74) says the focused row's sentence: neither is growth of the row.
            val pinnedTags = setOf(com.tmplayer.ui.settings.SETTINGS_HEADER_TAG, com.tmplayer.ui.settings.SETTINGS_DETAIL_TAG)
            val pinned = nodes(merged = false).filter { it.config.getOrNull(SemanticsProperties.TestTag) in pinnedTags }.map { layoutRect(it) }
            fun growthChecks() {
                val drawn = drawnChange(baseline, focusedFrame, region, skipNow, pinned)
                if (drawn != null) {
                    val full = restRect.width >= screenW * 0.9f
                    val scale = if (full) 1f else FocusStyle.TvScale
                    val cx = restRect.center.x; val cy = restRect.center.y
                    val allowed = Rect(cx - restRect.width * scale / 2, cy - restRect.height * scale / 2, cx + restRect.width * scale / 2, cy + restRect.height * scale / 2).inflate(tol)
                    if (drawn.left < allowed.left || drawn.top < allowed.top || drawn.right > allowed.right || drawn.bottom > allowed.bottom) {
                        add("focusSizeStable", name, "drawn ${sizeDp(drawn)} focused over a ${sizeDp(restRect)} layout, more than ${if (full) "none (full width)" else "the design scale $scale"}")
                    }
                    drawnInsideClip(name, restRect, drawn, screenW, screenH)
                }
            }
            val mark = findings.size
            growthChecks()
            if (skip != null && findings.size > mark) {
                // The neighbour's own focus can draw beyond its rect (a pill names itself in a label
                // under it while the remote is on it, FocusTip), and that vanishing is no growth of this
                // node: read the frames again against one with the remote far away before reporting.
                while (findings.size > mark) findings.removeAt(findings.size - 1)
                takeNeutral()
                growthChecks()
            }
            if (focusedNode.config.getOrNull(SemanticsProperties.Selected) == true) {
                selectedNotFocused(name, rest, focusedFrame, restRect)
            } else {
                focusContrast(name, rest, focusedFrame, restRect)
            }
        }
    }

    /**
     * drawnInsideClip: a grown item's drawn rect is whole (a container cut one side of its growth
     * off) and inside the safe area when its layout is.
     */
    private fun drawnInsideClip(name: String, layout: Rect, drawn: Rect, screenW: Float, screenH: Float) {
        val gl = layout.left - drawn.left; val gr = drawn.right - layout.right
        val gt = layout.top - drawn.top; val gb = drawn.bottom - layout.bottom
        val grew = dp(2f)
        fun cut(a: Float, b: Float) = max(a, b) > grew && min(a, b) < max(a, b) / 3
        if (cut(gl, gr) || cut(gt, gb)) {
            add("drawnInsideClip", name, "drawn growth left ${dpOf(gl)} right ${dpOf(gr)} top ${dpOf(gt)} bottom ${dpOf(gb)} dp: a container cut it")
        }
        val safe = Rect(dp(48f), dp(27f), screenW - dp(48f), screenH - dp(27f)).inflate(1f)
        val layoutInside = layout.left >= safe.left && layout.top >= safe.top && layout.right <= safe.right && layout.bottom <= safe.bottom
        val drawnInside = drawn.left >= safe.left && drawn.top >= safe.top && drawn.right <= safe.right && drawn.bottom <= safe.bottom
        if (layoutInside && !drawnInside) add("drawnInsideClip", name, "drawn ${rectDp(drawn)} dp leaves the safe area its layout is in")
    }

    /**
     * How strongly focus marks the node: the edge's ratio ([edgeContrast]), or, for a node whose
     * mark sits inside its bounds (a ring round a round picture in a larger tile), the median
     * ratio of the pixels that changed, when they are at least a one pixel ring's worth.
     */
    private fun focusMark(rest: Frame, focused: Frame, bounds: Rect): Float {
        val edge = edgeContrast(rest, focused, bounds, step = dp(2f), outside = dp(3f), inside = dp(4f))
        if (edge >= FocusContrast.Minimum) return edge
        val area = bounds.inflate(dp(3f))
        val ratios = mutableListOf<Float>()
        for (y in max(0, area.top.toInt()) until min(rest.height, area.bottom.toInt())) {
            for (x in max(0, area.left.toInt()) until min(rest.width, area.right.toInt())) {
                if (differs(rest.at(x, y), focused.at(x, y))) ratios += contrastRatio(rest.color(x, y), focused.color(x, y))
            }
        }
        if (ratios.size < 2 * (bounds.width + bounds.height)) return edge
        ratios.sort()
        return max(edge, ratios[ratios.size / 2])
    }

    /** focusContrast: the focused frame differs from the rest frame by 3:1 or more at the focus edge. */
    private fun focusContrast(name: String, rest: Frame, focused: Frame, bounds: Rect) {
        val ratio = focusMark(rest, focused, bounds)
        if (ratio < FocusContrast.Minimum) add("focusContrast", name, "focused against rest ${"%.2f".format(ratio)}:1 at the edge, want ${FocusContrast.Minimum}")
    }

    /** selectedNotFocused: a selected tab or rail item focused looks different from it selected alone. */
    private fun selectedNotFocused(name: String, rest: Frame, focused: Frame, bounds: Rect) {
        val ratio = focusMark(rest, focused, bounds)
        if (ratio < FocusContrast.Minimum) add("selectedNotFocused", name, "selected and focused against selected ${"%.2f".format(ratio)}:1, want ${FocusContrast.Minimum}")
    }

    /** The theme's ring against the worst artwork (white or black) under it: FocusContrast's own floor. */
    fun ringAgainstArt(ring: Color, dark: Boolean) {
        val worst = FocusContrast.worstAgainstArt(ring, dark)
        if (worst < FocusContrast.Minimum) add("focusContrast", "theme ring", "ring against the worst artwork ${"%.2f".format(worst)}:1")
    }

    /**
     * The arrows' graph from the entry focus: every focusable reached, and from each, where Up,
     * Down, Left and Right go, breadth first for up to [limit] nodes. Names, not ids, since a list
     * recomposes its items.
     */
    fun arrowGraph(limit: Int = 14): Map<String, Int> {
        val start = focused().firstOrNull() ?: return emptyMap()
        val distance = linkedMapOf(nameOf(start) to 0)
        val queue = ArrayDeque(listOf(start))
        val dirs = listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
        val stuck = mutableListOf<String>()
        var visited = 0
        while (queue.isNotEmpty() && visited < limit) {
            val from = queue.removeFirst()
            val fromName = nameOf(from)
            visited++
            var moves = 0
            var tried = 0
            for (d in dirs) {
                val live = nodes().firstOrNull { nameOf(it) == fromName && it.config.contains(SemanticsProperties.Focused) } ?: break
                if (!focus(live, 250)) break
                tried++
                key(d, 250)
                val now = focused().firstOrNull() ?: continue
                val nowName = nameOf(now)
                if (nowName != fromName) {
                    moves++
                    if (nowName !in distance) {
                        distance[nowName] = (distance[fromName] ?: 0) + 1
                        queue += now
                    }
                }
            }
            // Only a node every arrow was tried on counts: one that would not take the focus back
            // (a tab that turns the page under it) says nothing about traps.
            if (moves == 0 && tried == dirs.size) stuck += fromName
        }
        if (focusables().size > 1) stuck.forEach { add("noTrap", it, "no arrow moves the remote off it") }
        return distance
    }

    /** reach: [action] (its words or description) is at most [presses] arrow moves from the entry focus. */
    fun reach(graph: Map<String, Int>, action: String, presses: Int = 3) {
        val hit = graph.entries.firstOrNull { it.key.contains(action, ignoreCase = true) }
        when {
            hit == null -> add("reach", action, "not reached by the arrows from the entry focus (walked ${graph.size} nodes)")
            hit.value > presses -> add("reach", action, "${hit.value} presses from the entry focus, want $presses or fewer")
        }
    }

    /** backReturns: OK on [opener] opens something; Back closes it and the remote is on [opener] again. */
    fun backReturns(opener: String) {
        fun find() = nodes().firstOrNull { nameOf(it).contains(opener, ignoreCase = true) && it.config.contains(SemanticsProperties.Focused) }
        // A long page keeps its lower rows uncomposed: the remote walks Down to the opener, as a viewer's does.
        var downs = 0
        while (find() == null && downs++ < 20) key(KeyEvent.KEYCODE_DPAD_DOWN, 250)
        val node = find()
        if (node == null) { add("backReturns", opener, "no focusable opener with these words"); return }
        // A wrapper may hand the focus to the same control under another node: the words decide.
        fun on() = focused().any { nameOf(it).contains(opener, ignoreCase = true) }
        var took = focus(node) || on()
        // A row that hands a request to the item it last had (a chip row's restorer) is walked
        // with the arrows instead, as the remote reaches it.
        for (d in listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT)) {
            var n = 0
            while (!took && n++ < 6) { key(d, 250); took = on() }
        }
        if (!took) { add("backReturns", opener, "the opener refused the focus, which is on '${focused().firstOrNull()?.let { nameOf(it) } ?: "nothing"}'"); return }
        key(KeyEvent.KEYCODE_DPAD_CENTER, 600)
        key(KeyEvent.KEYCODE_BACK, 600)
        val on = focused().firstOrNull()?.let { nameOf(it) }
        if (on == null || !on.contains(opener, ignoreCase = true)) add("backReturns", opener, "after OK and Back the remote is on '${on ?: "nothing"}'")
    }

    /** heldDown: a held Down whose repeats are stale moves the remote once, not once per repeat. */
    fun heldDown(repeats: Int = 6) {
        val start = focused().firstOrNull() ?: return
        val from = nameOf(start)
        var last = start.id
        var moves = 0
        // Counted press by press, by the node itself: a list that scrolls moves the focused item
        // under the remote, which changes an unnamed node's name (it carries its place) and is no move.
        held(KeyEvent.KEYCODE_DPAD_DOWN, repeats) {
            val now = focused().firstOrNull()?.id
            if (now != null && now != last) { moves++; last = now }
        }
        if (moves > 1) add("heldDown", "screen", "a held Down with $repeats stale repeats moved the remote $moves times from '$from', want once")
    }

    /**
     * keyOwnership: each key that `docs/agents/tv-keys.md` says does nothing in [column] leaves the
     * remote where it was. Skipped when the table is not there yet (WP-69 writes it).
     */
    fun keyOwnership(column: String, table: TvKeyTable? = TvKeyTable.load()) {
        table ?: return
        val start = focused().firstOrNull() ?: return
        val startName = nameOf(start)
        for ((key, codes) in TvKeyTable.codes) {
            val cell = table.cell(key, column) ?: continue
            if (!cell.trim().equals("nothing", ignoreCase = true)) continue
            for (code in codes) {
                val live = nodes().firstOrNull { nameOf(it) == startName && it.config.contains(SemanticsProperties.Focused) } ?: return
                focus(live, 250)
                key(code, 300)
                val now = focused().firstOrNull()?.let { nameOf(it) }
                if (now != startName) add("keyOwnership", key, "the table says nothing in '$column', but it moved the remote to '${now ?: "nothing"}'")
            }
        }
    }

    private fun dpOf(px: Float) = "%.1f".format(px / density)
    private fun sizeDp(r: Rect) = "${dpOf(r.width)} x ${dpOf(r.height)} dp"
    private fun rectDp(r: Rect) = "${(r.left / density).toInt()},${(r.top / density).toInt()} ${(r.width / density).toInt()}x${(r.height / density).toInt()}"
}

/**
 * `docs/agents/tv-keys.md`'s key ownership table (WP-69): rows by key, columns by context. Null
 * when the file is not there or has no such table.
 */
class TvKeyTable(private val header: List<String>, private val rows: Map<String, List<String>>) {
    fun cell(key: String, column: String): String? {
        val col = header.indexOfFirst { it.equals(column, ignoreCase = true) }.takeIf { it >= 0 } ?: return null
        return rows[key]?.getOrNull(col)
    }

    companion object {
        /** The keys a row of the table names and the key codes a remote sends for them. */
        val codes: Map<String, List<Int>> = mapOf(
            "Menu" to listOf(KeyEvent.KEYCODE_MENU),
            "Info" to listOf(KeyEvent.KEYCODE_INFO),
            "Search" to listOf(KeyEvent.KEYCODE_SEARCH),
            "CC, Audio" to listOf(KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK),
            "Page, Channel" to listOf(KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN),
            "Play/Pause, Stop" to listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP),
        )

        fun load(): TvKeyTable? {
            val file = listOf(File("../docs/agents/tv-keys.md"), File("docs/agents/tv-keys.md")).firstOrNull { it.isFile } ?: return null
            return parse(file.readText())
        }

        /** The first Markdown table whose first header cell is "Key". */
        fun parse(markdown: String): TvKeyTable? {
            val lines = markdown.lines().map { it.trim() }.filter { it.startsWith("|") }
            val headerAt = lines.indexOfFirst { cells(it).firstOrNull().equals("Key", ignoreCase = true) }.takeIf { it >= 0 } ?: return null
            val header = cells(lines[headerAt])
            val rows = lines.drop(headerAt + 1).takeWhile { it.startsWith("|") }
                .map { cells(it) }
                .filterNot { row -> row.all { c -> c.all { it == '-' || it == ':' || it == ' ' } } }
                .associateBy({ it.first() }, { it })
            return TvKeyTable(header, rows)
        }

        private fun cells(line: String) = line.trim().trim('|').split('|').map { it.trim() }
    }
}
