package com.tmplayer.ui.browse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.tmplayer.data.EpisodeGuide
import com.tmplayer.data.FormFactor
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SeriesEpisode
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.ShelfEntry
import com.tmplayer.data.WatchPoint
import com.tmplayer.online.MetaEpisode
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The series page's episode list as a streaming app draws it: held episodes with the provider's
 * title, air date, running time and overview, the file size last, a watched tick and a progress
 * line; episodes the chat lacks as dimmed cards with a tag, never a control. Drawn to PNGs under
 * `build/device-test/review/` for the phone (both ways, a larger font), the television, a tablet
 * and an open foldable, and walked with a remote: Down and Up pass over the gaps.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SeriesEpisodeRowsRenderTest {

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private var nextId = 1L

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private fun video(name: String, sizeBytes: Long = 2_700_000_000): MediaItem {
        val id = nextId++
        return MediaItem(
            chatId = 7, messageId = id, fileId = id.toInt(), title = name, sizeBytes = sizeBytes,
            durationSec = 2_580, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null,
            date = (1000 - id).toInt(), fileName = name,
        )
    }

    private fun held(): List<SeriesEpisode> =
        SeriesShelf.arrange(listOf(1, 2, 5, 6).map { video("Blue.Box.S02E%02d.1080p.mkv".format(it)) })
            .filterIsInstance<ShelfEntry.Show>().single().series.episodes

    private fun guide(number: Int, name: String, airDate: String, overview: String = "") =
        MetaEpisode(season = 2, number = number, name = name, overview = overview, airDate = airDate, runtimeMin = 24)

    private fun gap(number: Int, name: String, airDate: String, state: EpisodeGuide.State, overview: String = "") =
        EpisodeGuide.Row.Gap(EpisodeGuide.Episode(2, number, null, guide(number, name, airDate, overview), state))

    /** E1 watched, E2 half way, E3 and E4 not in the chat (E4 not aired), E5 and E6 held. */
    private fun rows(): List<EpisodeGuide.Row> {
        val have = held()
        val words = "Taiki and Chinatsu wait for the bus, and the team heads to the regional meet."
        return listOf(
            EpisodeGuide.Row.Have(have[0], guide(1, "The Long Way Round", "2024-10-03", words)),
            EpisodeGuide.Row.Have(have[1], guide(2, "Second Wind", "2024-10-10", words)),
            gap(3, "A Rival Appears", "2024-10-17", EpisodeGuide.State.Missing, words),
            gap(4, "The Night Before", "2099-10-24", EpisodeGuide.State.Upcoming),
            EpisodeGuide.Row.Have(have[2], guide(5, "Open Court", "2024-10-31")),
            EpisodeGuide.Row.Have(have[3], null),
        )
    }

    private fun content(activity: ComponentActivity, large: Boolean) {
        val rows = rows()
        activity.setContent {
            TmMaterialTheme(dark = true) {
                LazyColumn(
                    Modifier.fillMaxSize().background(Tone.background).padding(horizontal = if (large) 48.dp else 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is EpisodeGuide.Row.Have -> EpisodeRow(
                                episode = row.episode,
                                point = if (row.episode.episode == 2) WatchPoint(900_000, 2_580_000) else null,
                                finished = row.episode.episode == 1,
                                upNext = row.episode.episode == 2,
                                onClick = {},
                                guide = row.meta,
                                name = row.meta?.name,
                                showOverview = true,
                                large = large,
                            )
                            is EpisodeGuide.Row.Gap -> GapRow(row.episode, large = large)
                            is EpisodeGuide.Row.Pack -> Unit
                        }
                    }
                }
            }
        }
    }

    private fun idle() = repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }

    private fun render(tv: Boolean, large: Boolean, out: String) {
        FormFactor.override(tv)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        content(activity, large)
        idle()
        val all = nodes(activity)
        // The gaps are read as one line each, and nothing in them takes the focus.
        val spoken = all.mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() }
        assertTrue(spoken.toString(), "Episode 3, not in this chat" in spoken)
        // The cards are taller now (a bigger picture): a short screen (a television, a phone on its side) shows
        // the first rows, the second gap only where the screen is tall enough.
        val tall = activity.resources.configuration.screenHeightDp >= 700
        if (tall) assertTrue(spoken.toString(), spoken.any { it.startsWith("Episode 4, airs ") })
        val gaps = all.filter { n -> n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("Episode ") } == true }
        assertEquals(if (tall) 2 else 1, gaps.size)
        gaps.forEach { assertNull("a gap is not focusable", it.config.getOrNull(SemanticsProperties.Focused)) }
        // The held episodes carry the provider's title and the running time first in the line of facts, the day
        // only where there is room for it (a television, a large window), and never the file's size.
        val texts = all.flatMap { words(it) }
        assertTrue(texts.toString(), texts.any { it.startsWith("43m") && it.contains("Oct 3, 2024") == (tv || large) })
        assertTrue(texts.toString(), texts.none { it.endsWith("GB") })
        // The code stands on the picture, once per held episode.
        assertTrue(texts.toString(), "S2 E1" in texts && "S2 E2" in texts)
        assertTrue(texts.toString(), "Not in this chat" in texts)

        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File("../build/device-test/review/$out").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun phone() = render(tv = false, large = false, out = "episodes-phone.png")

    @Test
    @Config(sdk = [35], qualifiers = "w891dp-h411dp-land-night-xhdpi")
    fun phoneLandscape() = render(tv = false, large = false, out = "episodes-phone-land.png")

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi", fontScale = 1.3f)
    fun phoneFontScale13() = render(tv = false, large = false, out = "episodes-phone-font13.png")

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-mdpi")
    fun television() = render(tv = true, large = false, out = "episodes-tv.png")

    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-night-xhdpi")
    fun tablet() = render(tv = false, large = true, out = "episodes-tablet.png")

    @Test
    @Config(sdk = [35], qualifiers = "w673dp-h841dp-port-night-xhdpi")
    fun foldableOpen() = render(tv = false, large = true, out = "episodes-foldable.png")

    /**
     * By remote alone: from the first episode, Down passes over the missing one and the unaired one
     * to the fifth, the sixth is next, and Up walks back the same way. A gap never holds the focus.
     */
    @Test
    @Config(sdk = [28], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun theRemotePassesOverTheGaps() {
        FormFactor.override(true)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.window.decorView.dispatchWindowFocusChanged(true)
        content(activity, large = false)
        idle()
        nodes(activity).first { "S2 E1" in words(it) }.config.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke()
        idle()
        assertTrue(here(activity), here(activity).contains("S2 E1"))
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("Down from E1 reaches the second episode: ${here(activity)}", here(activity).contains("S2 E2"))
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("Down passes over both gaps: ${here(activity)}", here(activity).contains("S2 E5"))
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(here(activity), here(activity).contains("S2 E6"))
        press(activity, KeyEvent.KEYCODE_DPAD_UP)
        assertTrue(here(activity), here(activity).contains("S2 E5"))
        press(activity, KeyEvent.KEYCODE_DPAD_UP)
        assertTrue("Up passes over both gaps: ${here(activity)}", here(activity).contains("S2 E2"))
    }

    private fun here(activity: ComponentActivity): String =
        nodes(activity).filter { it.config.getOrNull(SemanticsProperties.Focused) == true }.joinToString(";") { words(it).joinToString("/") }

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
