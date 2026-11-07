package com.tmplayer.ui.browse

import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.data.MediaItem
import com.tmplayer.data.Series
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.ShelfEntry
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class SeriesTvScrollTest {

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private var nextId = 1L

    private fun video(name: String): MediaItem {
        val id = nextId++
        return MediaItem(
            chatId = 7, messageId = id, fileId = id.toInt(), title = name, sizeBytes = 400_000_000,
            durationSec = 2600, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null,
            date = (1000 - id).toInt(), fileName = name,
        )
    }

    private fun bergretter(): Series {
        val names = buildList {
            for (season in 1..17) for (ep in 1..3) add("Die Bergretter - S%02d E%02d.mp4".format(season, ep))
            for (ep in 1..30) {
                if (ep == 6) {
                    add("Die Bergretter - S18 E06b - Teil 2.mp4")
                    add("Die Bergretter - S18 E06a - Teil 1.mp4")
                } else {
                    add("Die Bergretter - S18 E%02d.mp4".format(ep))
                }
            }
        }
        val entries = SeriesShelf.arrange(names.map(::video))
        return entries.filterIsInstance<ShelfEntry.Show>().single().series
    }

    /**
     * The Die Bergretter report (2026-11-07), by remote alone: 18 season tabs, then a season of 31
     * episodes whose sixth is posted in two halves (E06a, E06b, one season and episode number).
     * Right walks the tabs to the last, Down enters the episodes, and every one is focused in
     * turn, the halves apart, each inside the screen, to the last; Up comes back to the tabs.
     */
    @Test
    fun `the remote reaches the last season tab and every episode of a long season, halves apart`() {
        val activity = open(bergretter())
        focusPlay(activity)
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("Down from Play lands on the up-next episode", here(activity).contains("S1 E1"))
        press(activity, KeyEvent.KEYCODE_DPAD_UP)
        assertTrue("Up from the episodes lands on the season shown", here(activity).startsWith("Season 1/"))
        repeat(17) { press(activity, KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertTrue("Right x17 reaches Season 18", here(activity).startsWith("Season 18/"))
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("Right at the last tab stays on it", here(activity).startsWith("Season 18/"))

        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        val stops = mutableListOf(here(activity))
        repeat(30) {
            press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
            stops += here(activity)
        }
        val labels = stops.map { stop -> Regex("S18 E\\d+[ab]?").find(stop)?.value }
        assertEquals("every episode in turn, never stuck: $labels", 31, labels.distinct().size)
        assertEquals("S18 E1", labels.first())
        assertEquals("S18 E30", labels.last())
        assertTrue("the halves are told apart", labels.containsAll(listOf("S18 E6a", "S18 E6b")))
        stops.forEach { stop ->
            val bottom = Regex("@y(-?\\d+)-(\\d+)").find(stop)?.groupValues?.get(2)?.toInt() ?: Int.MAX_VALUE
            assertTrue("each focused card is on the 1080 px screen: $stop", bottom in 1..1080)
        }
        // One more Down leaves the list's last card for what is under it (the cast), not out of the page.
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("past the last episode the remote goes on", !here(activity).contains("S18 E30") && here(activity).isNotBlank())
        var ups = 0
        while (!here(activity).startsWith("Season") && ups++ < 80) press(activity, KeyEvent.KEYCODE_DPAD_UP)
        assertTrue("Up walks back to the season tabs: ${here(activity)}", here(activity).startsWith("Season 18/"))
    }

    /** The focused control's words, its box in the window with scrolling taken off, and the rows' scroll. */
    private fun here(activity: ComponentActivity): String {
        val all = nodes(activity)
        val focused = all.filter { it.config.getOrNull(SemanticsProperties.Focused) == true }
        val scrolls = all.mapNotNull { n ->
            n.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.let { "h" + it.value().toInt() + "/" + it.maxValue().toInt() + "w" + n.size.width }
        }
        return focused.joinToString(";") {
            words(it).take(3).joinToString("/") + "@y" + it.boundsInRoot.top.toInt() + "-" + it.boundsInRoot.bottom.toInt() + " x=" + it.positionInRoot.x.toInt() + "+" + it.size.width
        } + " " + scrolls
    }

    private fun focusPlay(activity: ComponentActivity) {
        nodes(activity).first { words(it).any { w -> w.startsWith("Play ") } }
            .config.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke()
        idle()
    }

    private fun open(series: Series): ComponentActivity {
        FormFactor.override(true)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.window.decorView.dispatchWindowFocusChanged(true)
        val watch = SeriesWatch(point = { null }, finished = { false })
        activity.setContent {
            TmMaterialTheme(dark = true) {
                SeriesPanel(
                    series = series,
                    watch = watch,
                    onPlay = {},
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 27.dp),
                )
            }
        }
        // The first paint's hold is a real-time delay: wait it out, then let the frames run.
        repeat(3) { Thread.sleep(250); idle() }
        return activity
    }

    private fun idle() = repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }

    private fun press(activity: ComponentActivity, code: Int) {
        val now = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        idle()
    }

    private fun nodes(activity: ComponentActivity): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(view: View) {
            if (view is ViewRootForTest) out += view.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(activity.window.decorView)
        return out
    }

    private fun words(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
}
