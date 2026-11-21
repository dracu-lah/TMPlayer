package com.tmplayer.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.data.FormatFacts
import com.tmplayer.data.MediaItem
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.browse.MediaCard
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * Format badges (T8), composed for real on the phone upright, at a 2.0 font and on the
 * television: the details row's whole set of pills wraps and stays on the screen, a pill is text
 * and not a control (no press, no focus stop), nothing is drawn for no facts, and a poster tile
 * keeps its one resolution pill inside its picture at a 200% font. Pictures go to
 * `build/device-test/review/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FormatBadgeRenderTest {

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private val everything = FormatFacts.Probe(
        videoMime = "video/hevc", width = 3840, height = 2160, frameRate = 59.94f, transfer = FormatFacts.Transfer.Pq, bitDepth = 10,
        audio = listOf(FormatFacts.AudioTrack(mime = "audio/eac3-joc", channels = 6)),
    )

    private fun open(fontScale: Float, content: @Composable () -> Unit): ComponentActivity {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.window.decorView.dispatchWindowFocusChanged(true)
        activity.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                TMPlayerTheme { Box(Modifier.background(Tone.background)) { content() } }
            }
        }
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }
        return activity
    }

    private fun nodes(activity: ComponentActivity): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(view: View) {
            if (view is ViewRootForTest) out += view.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(activity.window.decorView)
        return out
    }

    private fun words(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    private fun withText(activity: ComponentActivity, text: String) = nodes(activity).filter { text in words(it) }

    private fun draw(activity: ComponentActivity, out: String) {
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File("../build/device-test/review/$out").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    private fun item(name: String, width: Int = 0, height: Int = 0, onDevice: Boolean = false) = MediaItem(
        chatId = 7, messageId = 1, fileId = 1, title = name, sizeBytes = 900_000_000, durationSec = 5_400,
        mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 1, fileName = name,
        width = width, height = height, onDevice = onDevice,
    )

    private fun detailsRow(tags: List<FormatFacts.Tag>) = @Composable {
        Column(Modifier.fillMaxWidth().padding(16.dp)) { FormatBadgeRow(tags) }
    }

    private val fullRow get() = FormatFacts.tags(everything)

    // The details row -------------------------------------------------------------------------

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h640dp-port-night-xhdpi")
    fun `the whole row sits on a small phone`() {
        val activity = open(1f, detailsRow(fullRow))
        for (text in listOf("4K", "HDR10", "HEVC", "60 fps", "Atmos", "5.1")) {
            val node = withText(activity, text).singleOrNull()
            assertNotNull("$text is drawn once", node)
            assertInside(activity, node!!.boundsInRoot)
        }
        draw(activity, "format-badges-row-phone.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h640dp-port-night-xhdpi")
    fun `at a 200 percent font the row wraps whole pills and none leaves the screen or covers another`() {
        val activity = open(2f, detailsRow(fullRow))
        val rects = listOf("4K", "HDR10", "HEVC", "60 fps", "Atmos", "5.1").map { text ->
            withText(activity, text).single().boundsInRoot.also { assertInside(activity, it) }
        }
        for (i in rects.indices) for (j in i + 1 until rects.size) {
            assertFalse("${rects[i]} overlaps ${rects[j]}", rects[i].overlaps(rects[j]))
        }
        // It wrapped: more than one line of pills.
        assertTrue("a second line of pills", rects.map { it.top }.distinct().size > 1)
        draw(activity, "format-badges-row-phone-font20.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-mdpi")
    fun `the television shows the row too`() {
        FormFactor.override(true)
        val activity = open(1f, detailsRow(fullRow))
        assertEquals(1, withText(activity, "Atmos").size)
        draw(activity, "format-badges-row-tv.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h640dp-port-night-xhdpi")
    fun `a pill is text, not a control`() {
        val activity = open(1f, detailsRow(fullRow))
        val all = nodes(activity)
        assertTrue("no pill takes a press", all.none { it.config.getOrNull(SemanticsActions.OnClick) != null })
        assertTrue("no pill is a focus stop", all.none { it.config.getOrNull(SemanticsProperties.Focused) != null })
        assertTrue("no pill is a focus target", all.none { it.config.getOrNull(SemanticsActions.RequestFocus) != null })
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h640dp-port-night-xhdpi")
    fun `no facts draw nothing at all`() {
        val activity = open(1f, detailsRow(emptyList()))
        assertTrue(nodes(activity).none { it.config.getOrNull(SemanticsProperties.Text) != null })
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h640dp-port-night-xhdpi")
    fun `a size read from the name still draws, hollow`() {
        val tags = listOf(FormatFacts.Tag(FormatFacts.Kind.Resolution, "1080p", fromName = true), FormatFacts.Tag(FormatFacts.Kind.Resolution, "4K"))
        val activity = open(1f, detailsRow(tags))
        assertEquals(1, withText(activity, "1080p").size)
        draw(activity, "format-badges-row-name.png")
    }

    // The poster tile -----------------------------------------------------------------------

    private fun tile(item: MediaItem) = @Composable {
        Box(Modifier.width(112.dp)) { MediaCard(item = item, watched = null, finished = false, onClick = {}, onFocused = {}, dense = true, poster = true) }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h640dp-port-night-xhdpi")
    fun `a poster tile wears one resolution pill inside its picture`() {
        val activity = open(1f, tile(item("Gladiator.II.2024.1080p.WEB-DL.x265.mkv", 1920, 1080)))
        val pill = withText(activity, "1080p").singleOrNull()
        assertNotNull("the 1080p pill", pill)
        val card = Rect(0f, 0f, 112f * activity.resources.displayMetrics.density, activity.window.decorView.height.toFloat())
        assertTrue("inside the tile: ${pill!!.boundsInRoot}", pill.boundsInRoot.right <= card.right && pill.boundsInRoot.left >= 0f)
        // The top end corner: right half of the tile, near its top.
        assertTrue("on the right", pill.boundsInRoot.center.x > card.width / 2f)
        assertTrue("near the top", pill.boundsInRoot.top < 40f * activity.resources.displayMetrics.density)
        draw(activity, "format-badges-tile-phone.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h640dp-port-night-xhdpi")
    fun `at a 200 percent font the poster pill stays inside the tile and under a third of its width`() {
        val activity = open(2f, tile(item("Gladiator.II.2024.2160p.mkv", 3840, 2160)))
        val pill = withText(activity, "4K").single()
        val tileWidth = 112f * activity.resources.displayMetrics.density
        assertTrue("inside: ${pill.boundsInRoot}", pill.boundsInRoot.right <= tileWidth && pill.boundsInRoot.left >= 0f)
        assertTrue("a pill, not a bar: ${pill.boundsInRoot.width} of $tileWidth", pill.boundsInRoot.width < tileWidth * 0.6f)
        draw(activity, "format-badges-tile-phone-font20.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h640dp-port-night-xhdpi")
    fun `a poster tile is silent below 720p and when nothing is known`() {
        val sd = open(1f, tile(item("Old.Show.480p.mkv", 854, 480)))
        assertTrue("no SD pill", withText(sd, "SD").isEmpty() && withText(sd, "480p").isEmpty())
        val none = open(1f, tile(item("Birthday highlights.mp4")))
        assertNull(withText(none, "1080p").firstOrNull())
        assertTrue("no pill text of ours", FormatFacts.tileTag(item("Birthday highlights.mp4")) == null)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-mdpi")
    fun `the television's poster tile wears the pill too`() {
        FormFactor.override(true)
        val activity = open(1f, tile(item("Paper.Moons.2018.1080p.BluRay.mkv", 1920, 1080)))
        assertEquals(1, withText(activity, "1080p").size)
        draw(activity, "format-badges-tile-tv.png")
    }

    private fun assertInside(activity: ComponentActivity, bounds: Rect) {
        val w = activity.window.decorView.width.toFloat()
        val h = activity.window.decorView.height.toFloat()
        assertTrue("$bounds left of the screen", bounds.left >= 0f)
        assertTrue("$bounds right of the screen", bounds.right <= w)
        assertTrue("$bounds top of the screen", bounds.top >= 0f)
        assertTrue("$bounds foot of the screen", bounds.bottom <= h)
    }
}
