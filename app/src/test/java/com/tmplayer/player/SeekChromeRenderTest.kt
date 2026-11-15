package com.tmplayer.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
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
 * The row with a chaptered file's cuts in the bar and the chapter named after the time, an A-B
 * repeat's amber marks beside them, and the "Back to" chip in its corner, on a phone held sideways
 * and on a television. Drawn to `build/device-test/review/seek-*.png` for a look.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SeekChromeRenderTest {

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

    private val chapters = listOf(
        Chapter(0, "Opening"),
        Chapter(95_000, "Cold open"),
        Chapter(610_000, "The Station"),
        Chapter(1_180_000, "Night Train"),
        Chapter(2_480_000, "Ending"),
    )

    private fun render(out: String, tv: Boolean): View {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val root = FrameLayout(activity).apply {
            id = R.id.player_root
            setBackgroundColor(Color.rgb(0x20, 0x30, 0x40))
        }
        LayoutInflater.from(activity).inflate(R.layout.player_controls, root, true)
        val overlay = View(activity).apply { id = R.id.overlay_container }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
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
        controls.chapters = chapters
        controls.loop = AbLoop(startMs = 1_900_000, endMs = 2_100_000)
        controls.show()
        val chip = UndoSeekChip(root, overlay, tv) {}
        chip.show(backToMs = 312_000)
        idle()
        // Above the row, as the activity lifts it: the row's top, plus a gap.
        val cluster = root.findViewById<View>(R.id.controls_cluster)
        chip.view.translationY = -(root.height - cluster.top - NextUpCard.bottomPx(root) + 8 * activity.resources.displayMetrics.density).coerceAtLeast(0f)
        idle()
        val decor = activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        val file = File("../build/device-test/review/$out").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
        return root
    }

    private fun idle() = repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }

    @Test
    @Config(sdk = [35], qualifiers = "w891dp-h411dp-land-night-xhdpi")
    fun phoneSideways() {
        val root = render("seek-phone.png", tv = false)
        val time = root.findViewById<TextView>(R.id.times_position).text.toString()
        assertTrue(time, "Chapter 4 of 5: Night Train" in time)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-night-xhdpi")
    fun television() {
        val root = render("seek-tv.png", tv = true)
        val time = root.findViewById<TextView>(R.id.controls_time).text.toString()
        assertTrue(time, "Night Train" in time)
    }

    // Large screens (U17): the phone's controls on a tablet lying down and an unfolded foldable.
    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-night-xhdpi")
    fun tabletLyingDown() {
        render("seek-tablet-land.png", tv = false)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w841dp-h673dp-land-night-xhdpi")
    fun foldableSideways() {
        render("seek-foldable-land.png", tv = false)
    }
}
