package com.tmplayer.ui.browse

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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

/**
 * The same long show on a phone (the Die Bergretter report, 2026-11-07): 18 seasons, the last with
 * 31 episodes and a sixth in two halves. A finger scrolls the page to the end, the halves are two
 * cards with their own labels, and the season tabs row scrolls sideways.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "w411dp-h891dp-port-notnight-xhdpi")
class SeriesPhoneScrollTest {

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

    @Test
    fun `a finger reaches the last episode of a long season and the halves are two cards`() {
        FormFactor.override(false)
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
        val series = SeriesShelf.arrange(names.map(::video)).filterIsInstance<ShelfEntry.Show>().single().series
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val watch = SeriesWatch(point = { null }, finished = { false })
        activity.setContent {
            TmMaterialTheme(dark = true) {
                SeriesPanel(
                    series = series,
                    watch = watch,
                    onPlay = {},
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                )
            }
        }
        repeat(3) { Thread.sleep(250); idle() }

        // Down to the season tabs (the second item of the page's list).
        nodes(activity).first { it.config.getOrNull(SemanticsActions.ScrollToIndex) != null }
            .config[SemanticsActions.ScrollToIndex].action?.invoke(1)
        idle()
        // The tabs row scrolls sideways: 18 tabs are wider than the phone.
        val row = nodes(activity).first { it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null }
        assertTrue("the tab row has room to scroll", row.config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() > 0f)

        // Pick the long season, then a finger flick to the end of the list.
        nodes(activity).first { words(it).contains("Season 18") && it.config.getOrNull(SemanticsActions.OnClick) != null }
            .config[SemanticsActions.OnClick].action?.invoke()
        idle()
        val list = nodes(activity).first { it.config.getOrNull(SemanticsActions.ScrollToIndex) != null }
        list.config[SemanticsActions.ScrollToIndex].action?.invoke(7)
        idle()
        val middle = nodes(activity).flatMap(::words)
        assertTrue("the halves are two cards with their own labels: $middle", middle.contains("S18 E6a") && middle.contains("S18 E6b"))
        list.config[SemanticsActions.ScrollToIndex].action?.invoke(32)
        idle()
        val end = nodes(activity).flatMap(::words)
        assertTrue("the last episode is reachable: $end", end.contains("S18 E30"))
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

    private fun words(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
}
