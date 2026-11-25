package com.tmplayer.ui.audit

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.browse.ACCOUNT_BUTTON_TAG
import com.tmplayer.ui.browse.BAR_PILL_TAG
import com.tmplayer.ui.components.PAGE_HEADER_TAG
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import java.io.File

/**
 * One screen of the audit: a name, whether the page sits under the status bar, and what draws it.
 * [fixed] is false for a screen with no fixed chrome of its own (a sheet, a dialog, a panel).
 */
internal class AuditScreen(
    val name: String,
    val systemBars: Boolean = true,
    val content: @Composable () -> Unit,
)

/**
 * Draws every audit screen at each font scale (1.0, 1.3, 2.0) in the window the subclass's `@Config`
 * sets, writes a PNG of each under `build/phone-audit/<window>/` and collects what [AuditChecks]
 * finds into `build/phone-audit/<window>/findings.txt`. One composition is kept and its screen,
 * font and state switched, which is what keeps a few hundred pictures cheap.
 */
@OptIn(ExperimentalTestApi::class)
internal abstract class PhoneAuditBase(private val window: String) {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    private var index by mutableIntStateOf(0)
    private var scale by mutableFloatStateOf(1f)
    private val density get() = rule.activity.resources.displayMetrics.density

    private fun bars() {
        val top = (28 * density).toInt()
        val bottom = (24 * density).toInt()
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, top, 0, 0))
            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars(), Insets.of(0, top, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bottom))
            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bottom))
            .setVisible(WindowInsetsCompat.Type.statusBars(), true)
            .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
            .build()
        fun walk(view: View) {
            if (view.javaClass.name.contains("AndroidComposeView")) ViewCompat.dispatchApplyWindowInsets(view, insets)
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(rule.activity.window.decorView)
        rule.waitForIdle()
    }

    /**
     * Draws every screen at every scale. With [failOn], the findings of those checks that are not in [known]
     * ("screen/check") fail the test once all have been drawn, each named with its screen and font.
     */
    protected fun runAll(
        screens: List<AuditScreen>,
        scales: List<Float> = listOf(1f, 1.3f, 2f),
        failOn: Set<String>? = null,
        known: Set<String> = emptySet(),
        pictures: Boolean = true,
    ) {
        FormFactor.override(false)
        Motion.reduced = true
        val broken = ArrayList<String>()
        val findings = StringBuilder()
        val ellipses = StringBuilder()
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) {
                TMPlayerTheme {
                    Box(Modifier.fillMaxSize().background(Tone.background)) {
                        key(index) { screens[index].content() }
                    }
                }
            }
        }
        rule.waitForIdle()
        for ((i, screen) in screens.withIndex()) {
            for (s in scales) {
                val label = "${screen.name}@${s}"
                try {
                    index = i
                    scale = s
                    rule.waitForIdle()
                    rule.mainClock.advanceTimeBy(600)
                    bars()
                    if (pictures) save("${screen.name}-f${s}.png", rule.onRoot().captureToImage().asAndroidBitmap())
                    val root = rule.onRoot().fetchSemanticsNode()
                    val nodes = AuditChecks.all(root)
                    val windowRect = root.boundsInRoot
                    val chrome = AuditChecks.Chrome(
                        statusBarPx = if (screen.systemBars) 28 * density else 0f,
                        header = tagRect(PAGE_HEADER_TAG),
                        avatar = tagRect(ACCOUNT_BUTTON_TAG),
                        bar = tagRect(BAR_PILL_TAG),
                    )
                    for (f in AuditChecks.everything(nodes, windowRect, density, chrome, s)) {
                        findings.appendLine("$label | $f")
                        if (failOn != null && f.check in failOn && "${screen.name}/${f.check}" !in known) broken += "$label | $f"
                    }
                    for (f in AuditChecks.ellipsised(nodes)) ellipses.appendLine("$label | $f")
                } catch (e: Throwable) {
                    findings.appendLine("$label | crash: ${e.javaClass.simpleName}: ${e.message?.take(200)}")
                    if (failOn != null) broken += "$label | crash: ${e.javaClass.simpleName}: ${e.message?.take(200)}"
                }
            }
        }
        File(dir(), "findings.txt").writeText(findings.toString())
        File(dir(), "ellipsis.txt").writeText(ellipses.toString())
        if (failOn != null) assertTrue("${broken.size} findings:\n" + broken.joinToString("\n"), broken.isEmpty())
    }

    /**
     * Scrolls each screen's biggest list to its end and asks that nothing readable is left under the bottom
     * bar: the last item must be reachable above it (what `LocalBottomChrome` pads the lists' ends by).
     * Screens without the bar are skipped. Returns what was found under the bar, "screen@font | text".
     */
    protected fun endOfLists(screens: List<AuditScreen>, scale: Float = 1f): List<String> {
        FormFactor.override(false)
        Motion.reduced = true
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) {
                TMPlayerTheme {
                    Box(Modifier.fillMaxSize().background(Tone.background)) {
                        key(index) { screens[index].content() }
                    }
                }
            }
        }
        val under = ArrayList<String>()
        for ((i, screen) in screens.withIndex()) {
            index = i
            rule.waitForIdle()
            rule.mainClock.advanceTimeBy(600)
            bars()
            val bar = tagRect(BAR_PILL_TAG) ?: continue
            // The biggest list that scrolls up and down (the pager beside it scrolls sideways), swiped until it stops.
            val vertical = rule.onAllNodes(androidx.compose.ui.test.hasScrollAction()).fetchSemanticsNodes()
                .filter { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange) != null }
            val biggest = vertical.maxByOrNull { it.boundsInRoot.height } ?: continue
            val list = rule.onNode(androidx.compose.ui.test.SemanticsMatcher("the biggest list") { it.id == biggest.id })
            repeat(40) {
                val range = list.fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)
                if (range != null && range.value() >= range.maxValue() - 1f) return@repeat
                list.performTouchInput { swipeUp() }
                rule.waitForIdle()
            }
            rule.mainClock.advanceTimeBy(400)
            val root = rule.onRoot().fetchSemanticsNode()
            val barNow = tagRect(BAR_PILL_TAG) ?: bar
            for (n in AuditChecks.all(root)) {
                if (!AuditChecks.hasText(n)) continue
                val b = n.boundsInRoot
                if (b.width <= 0f || b.height <= 0f) continue
                val insideBar = b.left >= barNow.left - 1f && b.right <= barNow.right + 1f && b.top >= barNow.top - 1f && b.bottom <= barNow.bottom + 1f
                if (!insideBar && b.top < barNow.bottom && b.bottom > barNow.top + 1f && b.right > barNow.left && b.left < barNow.right) {
                    under += "${screen.name}@$scale | ${with(AuditChecks) { n.label() }} bounds $b under the bar $barNow"
                }
            }
        }
        return under
    }

    private fun tagRect(tag: String): Rect? =
        rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().firstOrNull()?.boundsInRoot

    private fun dir(): File = File("../build/phone-audit/$window").absoluteFile.also { it.mkdirs() }

    private fun save(name: String, bitmap: Bitmap) {
        File(dir(), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
