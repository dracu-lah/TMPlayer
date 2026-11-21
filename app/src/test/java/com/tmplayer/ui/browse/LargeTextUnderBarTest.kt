package com.tmplayer.ui.browse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.data.SettingsStore
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.BigEmpty
import com.tmplayer.ui.components.BigError
import com.tmplayer.ui.components.LocalBottomChrome
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Large text under the phone's floating bar. Home's first-run card and the shared loading, empty
 * and failed pages scroll far enough to bring every line and button out from under the bar: with
 * iOS's large text an iPhone 14 showed the card's last line under the bar, on a page too short to
 * scroll (2026-11-15). An empty state inside a list row (Downloads) still draws, with no scroll of
 * its own to fail in a list. Pictures go to `build/device-test/review/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LargeTextUnderBarTest {

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private val settings get() = SettingsStore(RuntimeEnvironment.getApplication())

    // The page at rest: riseIn's lift and fade are an animation the test's clock does not always
    // play to its end. Stored as well as set: the app copies the stored choice into Motion.reduced
    // when it starts (App.kt), and that copy could land after a bare write and turn motion back on.
    @Before
    fun still() {
        runBlocking { settings.setReduceMotion(true) }
        Motion.reduced = true
        Snapshot.sendApplyNotifications()
    }

    @After
    fun tearDown() {
        runBlocking { settings.setReduceMotion(false) }
        Motion.reduced = false
        FormFactor.override(false)
    }

    /** About what the floating bar keeps on an iPhone: the bar, its gap and the home indicator. */
    private val bar = 110.dp

    private val long = "Nothing has been played here yet. Forward a video to Saved Messages in Telegram, " +
        "then open Saved Messages here to watch it, or star a chat to give it a row of its own."

    private fun open(fontScale: Float, page: @Composable () -> Unit): ComponentActivity {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, fontScale),
                LocalBottomChrome provides bar,
            ) {
                TMPlayerTheme {
                    Box(Modifier.fillMaxSize()) {
                        page()
                        // The floating bar's stand-in, where the real one draws.
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(bar)
                                .background(Color(0xCC2A4A6A))
                                .testTag(BAR),
                        )
                    }
                }
            }
        }
        idle()
        return activity
    }

    private fun idle() = repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }

    private fun nodes(activity: ComponentActivity): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(view: View) {
            if (view is ViewRootForTest) out += view.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(activity.window.decorView)
        return out
    }

    private fun words(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun draw(activity: ComponentActivity, out: String) {
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File("../build/device-test/review/$out").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    /**
     * Checks that the page can scroll far enough to bring every line and every button out from
     * under the bar: what still sits under it must be within the scroll left to do. (The scroll
     * action itself animates, which the test's clock does not play.)
     */
    private fun assertAllAboveTheBar(activity: ComponentActivity, picture: String) {
        val all = nodes(activity)
        val barTop = all.first { it.config.getOrNull(SemanticsProperties.TestTag) == BAR }.boundsInRoot.top
        val range = all.firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) }
        val left = range?.let { it.maxValue() - it.value() } ?: 0f
        val shown = all.filter { words(it).isNotEmpty() || it.config.getOrNull(SemanticsActions.OnClick) != null }
        assertTrue("the page has words", shown.isNotEmpty())
        // Drawn first, so a failure leaves its picture.
        draw(activity, picture)
        for (node in shown) {
            val bottom = node.boundsInRoot.bottom - left
            assertTrue("${words(node)} ends at $bottom at the most, under the bar at $barTop", bottom <= barTop + 1f)
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w390dp-h844dp-port-night-xhdpi")
    fun `Home's first-run card scrolls clear of the bar at double text`() {
        val activity = open(fontScale = 2f) { FirstVideoEmpty(onOpen = {}) }
        assertAllAboveTheBar(activity, "large-text-home-first-run.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w390dp-h844dp-port-night-xhdpi")
    fun `Home's first-run card at 1_3 text ends above the bar`() {
        val activity = open(fontScale = 1.3f) { FirstVideoEmpty(onOpen = {}) }
        assertAllAboveTheBar(activity, "large-text-home-first-run-13.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp-port-night-xhdpi")
    fun `an empty page with a button scrolls clear of the bar on a small phone at double text`() {
        val activity = open(fontScale = 2f) { BigEmpty(long, actionLabel = "Go to chats", onAction = {}) }
        assertAllAboveTheBar(activity, "large-text-empty-page.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp-port-night-xhdpi")
    fun `a failed page keeps Try again and Send logs clear of the bar on a small phone at double text`() {
        val activity = open(fontScale = 2f) { BigError(long, onRetry = {}) }
        assertAllAboveTheBar(activity, "large-text-failed-page.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w390dp-h844dp-port-night-xhdpi")
    fun `an empty state in a list row still draws, without a scroll of its own`() {
        val activity = open(fontScale = 1f) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    // As Downloads gives its empty sections a height of their own.
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 300.dp),
                        contentAlignment = Alignment.Center,
                        propagateMinConstraints = true,
                    ) { BigEmpty("Nothing downloading") }
                }
                item { Text("Recently played") }
            }
        }
        val all = nodes(activity)
        val empty = all.firstOrNull { "Nothing downloading" in words(it) }
        val next = all.firstOrNull { "Recently played" in words(it) }
        assertNotNull("the empty state drew", empty)
        assertNotNull("the row under it drew", next)
        val density = activity.resources.displayMetrics.density
        assertTrue("the row kept its height", next!!.boundsInRoot.top >= 300f * density - 1f)
        draw(activity, "large-text-empty-in-row.png")
    }

    private companion object {
        const val BAR = "floating-bar"
    }
}
