package com.tmplayer.ui.browse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SeriesEpisode
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.ShelfEntry
import com.tmplayer.data.WatchPoint
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
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
import kotlin.math.abs

/**
 * The episode card at each width (W15, 2026-11-25): its 16:9 picture is about 40 percent of a phone
 * card (136 to 220 dp), 264 dp on a television and 256 dp in a large window; every card of a list
 * has the same picture width and the same left edge for its words; the file's size is not on it;
 * and a season of 500 episodes composes only the rows near the screen and shows its first rows well
 * inside three seconds, scrolled to its end as much as at its start.
 *
 * Frames are written to `build/device-test/episodes/` for the review page.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EpisodeCardsLayoutTest {

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private var nextId = 1L

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private fun video(name: String): MediaItem {
        val id = nextId++
        return MediaItem(
            chatId = 7, messageId = id, fileId = id.toInt(), title = name, sizeBytes = 2_700_000_000,
            durationSec = 2_580, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null,
            date = (1_000_000 - id).toInt(), fileName = name,
        )
    }

    private fun season(count: Int): List<SeriesEpisode> =
        SeriesShelf.arrange((1..count).map { video("Blue.Box.S01E%03d.1080p.mkv".format(it)) })
            .filterIsInstance<ShelfEntry.Show>().single().series.episodes

    private fun content(activity: ComponentActivity, episodes: List<SeriesEpisode>, large: Boolean, tv: Boolean, scrollTo: Int? = null) {
        activity.setContent {
            TmMaterialTheme(dark = true) {
                val list = rememberLazyListState()
                val scope = rememberCoroutineScope()
                if (scrollTo != null) scope.launch { list.scrollToItem(scrollTo) }
                LazyColumn(
                    Modifier.fillMaxSize().background(Tone.background).padding(horizontal = if (large) 48.dp else 12.dp),
                    state = list,
                    verticalArrangement = Arrangement.spacedBy(if (tv) 8.dp else 6.dp),
                ) {
                    items(episodes, key = { it.item.id }) { episode ->
                        EpisodeRow(
                            episode = episode,
                            point = if (episode.episode == 2) WatchPoint(900_000, 2_580_000) else null,
                            finished = episode.episode == 1,
                            upNext = episode.episode == 2,
                            onClick = {},
                            name = "Episode name ${episode.episode}",
                            large = large,
                        )
                    }
                }
            }
        }
    }

    private fun idle() = repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }

    /** The unmerged tree: a card's picture is tagged inside a clickable that merges its words. */
    private fun nodes(activity: ComponentActivity): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(view: View) {
            if (view is ViewRootForTest) out += view.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(activity.window.decorView)
        return out
    }

    private fun arts(activity: ComponentActivity) =
        nodes(activity).filter { it.config.getOrNull(SemanticsProperties.TestTag) == EPISODE_ART_TAG }

    private fun titles(activity: ComponentActivity) = nodes(activity).filter { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("Episode name") } == true
    }

    private fun save(activity: ComponentActivity, name: String) {
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File("../build/device-test/episodes/$name").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Every card of the list: 16:9 within a pixel, one width, one left edge for the picture and for the words. */
    private fun check(tv: Boolean, large: Boolean, minDp: Float, maxDp: Float, out: String) {
        FormFactor.override(tv)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        content(activity, season(8), large, tv)
        idle()
        val density = activity.resources.displayMetrics.density
        val art = arts(activity)
        assertTrue("pictures drawn", art.size >= 3)
        art.forEach { node ->
            // The layout size, not the bounds in the root: the last card is cut by the screen's edge.
            val w = node.size.width.toFloat()
            val h = node.size.height.toFloat()
            assertTrue("16:9, got $w x $h", abs(w * 9f / 16f - h) <= 1.5f)
            assertTrue("width ${w / density} dp within $minDp..$maxDp", w / density >= minDp - 0.5f && w / density <= maxDp + 0.5f)
        }
        assertEquals("one picture width", 1, art.map { it.size.width }.toSet().size)
        assertEquals("one left edge for the pictures", 1, art.map { Math.round(it.positionInRoot.x) }.toSet().size)
        val words = titles(activity)
        assertTrue("titles drawn", words.size >= 3)
        assertEquals("one left edge for the words", 1, words.map { Math.round(it.positionInRoot.x) }.toSet().size)
        assertEquals("one title size", 1, words.map { it.size.height }.toSet().size)
        // The words start after the picture, and the pictures are not squeezed to a sliver of a phone's card.
        assertTrue(words.first().positionInRoot.x > art.first().positionInRoot.x + art.first().size.width)
        val texts = nodes(activity).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }
        assertTrue(texts.toString(), texts.none { it.endsWith("GB") || it.contains(".mkv") })
        save(activity, out)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h780dp-port-night-xhdpi")
    fun phone360() = check(tv = false, large = false, minDp = 136f, maxDp = 220f, out = "cards-phone-360.png")

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun phone411() = check(tv = false, large = false, minDp = 150f, maxDp = 220f, out = "cards-phone-411.png")

    @Test
    @Config(sdk = [35], qualifiers = "w800dp-h1280dp-port-night-xhdpi")
    fun tablet800() = check(tv = false, large = false, minDp = 136f, maxDp = 220f, out = "cards-tablet-800.png")

    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-night-xhdpi")
    fun desktopWide() = check(tv = false, large = true, minDp = 256f, maxDp = 256f, out = "cards-large-1280.png")

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-mdpi")
    fun television960() = check(tv = true, large = false, minDp = 264f, maxDp = 264f, out = "cards-tv-960.png")

    @Test
    @Config(sdk = [35], qualifiers = "w1920dp-h1080dp-land-television-night-mdpi")
    fun television1920() = check(tv = true, large = false, minDp = 264f, maxDp = 264f, out = "cards-tv-1920.png")

    /**
     * A season of 500 episodes: the first cards stand within three seconds, only the rows near the
     * screen are composed (not 500 pictures), and scrolled to the end it is the same handful.
     */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun fiveHundredEpisodesComposeOnlyWhatIsNear() {
        val episodes = season(500)
        assertEquals(500, episodes.size)
        FormFactor.override(false)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val started = System.nanoTime()
        content(activity, episodes, large = false, tv = false)
        idle()
        val firstMs = (System.nanoTime() - started) / 1_000_000
        val atStart = arts(activity).size
        assertTrue("first cards drawn", atStart >= 3)
        assertTrue("first content in $firstMs ms, under 3000", firstMs < 3_000)
        assertTrue("composed $atStart pictures for 500 episodes", atStart <= 16)

        val far = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        content(far, episodes, large = false, tv = false, scrollTo = 499)
        idle()
        val atEnd = arts(far).size
        assertTrue("the end of the season is drawn", atEnd >= 3)
        assertTrue("composed $atEnd pictures at the end", atEnd <= 16)
    }
}
