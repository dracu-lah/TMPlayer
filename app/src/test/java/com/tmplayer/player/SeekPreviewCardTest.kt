package com.tmplayer.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.Player
import com.tmplayer.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.lang.reflect.Proxy
import java.time.Duration

/**
 * The scrub card over the bar (HX3): the picture with its time under it, or the time alone, at
 * both ends of the bar and in the middle, on a phone upright and sideways and on a television. It
 * must stay whole on the screen and clear of the times (phone) or the title (television). Drawn to
 * `build/device-test/review/seek-card-*.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SeekPreviewCardTest {

    private val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
        when (method.name) {
            "getDuration" -> 2_634_000L
            "getCurrentPosition" -> 1_260_000L
            "getBufferedPosition" -> 1_700_000L
            "isPlaying", "getPlayWhenReady" -> true
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                else -> null
            }
        }
    } as Player

    private val frame: Bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).also {
        Canvas(it).apply {
            drawColor(Color.rgb(0x2E, 0x6F, 0x95))
            drawLine(0f, 180f, 320f, 0f, android.graphics.Paint().apply { color = Color.rgb(0xF2, 0xD1, 0x6B); strokeWidth = 14f })
        }
    }

    private val pictures = object : ScrubThumbnails {
        override fun request(positionMs: Long, onFrame: (Bitmap?) -> Unit) = onFrame(frame)
        override fun release() {}
    }

    private val none = object : ScrubThumbnails {
        override fun request(positionMs: Long, onFrame: (Bitmap?) -> Unit) = onFrame(null)
        override fun release() {}
    }

    private fun render(out: String, tv: Boolean, at: Long, source: ScrubThumbnails?): Pair<View, View> {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val root = FrameLayout(activity).apply {
            id = R.id.player_root
            setBackgroundColor(Color.rgb(0x20, 0x30, 0x40))
        }
        LayoutInflater.from(activity).inflate(R.layout.player_controls, root, true)
        root.addView(View(activity).apply { id = R.id.overlay_container }, FrameLayout.LayoutParams(-1, -1))
        activity.setContentView(root)
        val controls = PlayerControls(
            root = root,
            isTv = tv,
            player = { player },
            onVisibility = {},
            onTogglePlay = {},
            onPickSubtitles = {},
            onPickAudio = {},
            onPickSpeed = {},
            onCycleOrientation = {},
            onPlayEpisode = {},
        )
        controls.setTitle("The Coast", "S01E04 · Nature Channel")
        controls.thumbnails = source
        // The card itself, as it shows once Trickplay.PICTURES_SHOWN is back on.
        controls.previewsOff = false
        controls.previewPinned = true
        controls.show()
        idle()
        controls.preview(at)
        idle()
        val decor = activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        val file = File("../build/device-test/review/$out").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
        return root to controls.previewCard
    }

    private fun idle() = repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }

    /** The card is shown, inside the screen on both sides, and above [clear]'s top. */
    private fun check(root: View, card: View, clear: View) {
        assertTrue("card shown", card.visibility == View.VISIBLE)
        val left = card.translationX
        val right = left + card.width
        assertTrue("left edge $left", left >= 0f)
        assertTrue("right edge $right of ${root.width}", right <= root.width)
        val bottom = card.translationY + card.height
        val clearTop = IntArray(2).also { clear.getLocationInWindow(it) }[1]
        assertTrue("card bottom $bottom clears $clearTop", bottom <= clearTop)
    }

    private fun suite(prefix: String, tv: Boolean, source: ScrubThumbnails?, clearId: Int) {
        for ((label, at) in listOf("start" to 0L, "mid" to 1_300_000L, "end" to 2_630_000L)) {
            val (root, card) = render("seek-card-$prefix-$label.png", tv, at, source)
            check(root, card, root.findViewById(clearId))
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xxhdpi")
    fun phoneUpright() = suite("phone-port", tv = false, pictures, R.id.controls_times)

    @Test
    @Config(sdk = [35], qualifiers = "w891dp-h411dp-land-night-xhdpi")
    fun phoneSideways() = suite("phone-land", tv = false, pictures, R.id.controls_times)

    /** No picture for the moment: the still fallback with the time across it, never nothing. */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xxhdpi")
    fun phoneFallback() = suite("phone-fallback", tv = false, none, R.id.controls_times)

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-night-xhdpi")
    fun televisionWithPictures() = suite("tv-frames", tv = true, pictures, R.id.controls_title)

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-night-xhdpi")
    fun televisionTimeOnly() = suite("tv-time", tv = true, none, R.id.controls_title)

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-night-xhdpi")
    fun televisionPicturesOff() = suite("tv-off", tv = true, null, R.id.controls_title)
}
