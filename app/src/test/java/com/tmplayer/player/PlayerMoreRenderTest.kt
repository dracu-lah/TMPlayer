package com.tmplayer.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import com.tmplayer.R
import com.tmplayer.data.FormFactor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * More options (HX16): the panel at the end edge on a phone held sideways and on a television,
 * the bottom sheet on a phone held upright, each on its list and on the shapes' page, dark and
 * light. Drawn to `build/device-test/review/more-*.png` for a look.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerMoreRenderTest {

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private fun idle(ms: Long = 1000) {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        var left = ms
        while (left > 0) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            left -= 20
        }
    }

    private fun panel(tv: Boolean, shapes: Boolean, name: String, sideways: Boolean = true): FakeHost {
        FormFactor.override(tv)
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        activity.setTheme(R.style.Theme_TMPlayer_Player)
        // Opened before the layout is attached, so the panel is in its first composition: in
        // one test process only the first test's window gets recomposition frames, which left
        // every later panel opened after attaching blank.
        val root = android.view.LayoutInflater.from(activity).inflate(R.layout.activity_player, null) as FrameLayout
        // A stand-in for the picture, so the panel's shade and edge show against something.
        root.setBackgroundColor(0xFF2F4F6F.toInt())
        root.findViewById<View>(R.id.status_overlay).visibility = View.GONE
        val host = FakeHost(tv, sideways)
        val panel = PlayerMorePanel(activity, root, host)
        if (shapes) panel.openOnShapes() else panel.open()
        activity.setContentView(root)
        idle(1000)
        assertTrue(panel.isOpen)
        val decor = activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        decor.draw(canvas)
        // The decor's software draw here skips a ComposeView laid over views, so the panel's own
        // view is drawn on top where it sits.
        val composed = (0 until root.childCount).map { root.getChildAt(it) }
            .last { it is androidx.compose.ui.platform.ComposeView && it.visibility == View.VISIBLE }
        val at = IntArray(2).also(composed::getLocationInWindow)
        canvas.save()
        canvas.translate(at[0].toFloat(), at[1].toFloat())
        composed.draw(canvas)
        canvas.restore()
        if (!sideways && !tv) {
            // Upright, the phone's sheet: a window of its own, the newest one up.
            val sheet = windowRoots().last { it !== decor }
            canvas.drawColor(0x66000000)
            sheet.draw(canvas)
        }
        val file = File("../build/device-test/review/$name").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
        panel.closeNow()
        assertFalse(panel.isOpen)
        assertEquals(1, host.closings)
        // Shut, nothing of it is laid out over the player.
        assertTrue((0 until root.childCount).map { root.getChildAt(it) }.none { it is androidx.compose.ui.platform.ComposeView && it.visibility == View.VISIBLE && it.width == root.width })
        return host
    }

    /** Every window's root view, oldest first, as the window manager holds them. */
    @Suppress("UNCHECKED_CAST")
    private fun windowRoots(): List<View> {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        val field = global.getDeclaredField("mViews").apply { isAccessible = true }
        return (field.get(instance) as List<View>).toList()
    }

    /**
     * Every shot in one test: the Compose frame clock follows only the first test's looper in a
     * test process, so a panel's slide in a second test never ran and drew nothing.
     */
    @Test
    @Config(sdk = [35])
    fun everyShape() {
        val phoneSideways = "w891dp-h411dp-land"
        val phoneUpright = "w411dp-h891dp-port"
        val television = "w960dp-h540dp-land"
        for (theme in listOf("night" to "dark", "notnight" to "light")) {
            for (shapes in listOf(false, true)) {
                val page = if (shapes) "-shape" else ""
                RuntimeEnvironment.setQualifiers("$phoneSideways-${theme.first}-xhdpi")
                panel(tv = false, shapes = shapes, name = "more-phone-land$page-${theme.second}.png")
                RuntimeEnvironment.setQualifiers("$phoneUpright-${theme.first}-xhdpi")
                panel(tv = false, shapes = shapes, name = "more-phone-port$page-${theme.second}.png", sideways = false)
                RuntimeEnvironment.setQualifiers("$television-${theme.first}-xhdpi")
                panel(tv = true, shapes = shapes, name = "more-tv$page-${theme.second}.png")
            }
        }
    }

    /** A paused video from a message, a sleep timer running and Volume boost on. */
    class FakeHost(override val tv: Boolean, private val sideways: Boolean) : PlayerMoreHost {
        var closings = 0
        override fun title() = "Night Train S01E04"
        override fun sideways() = sideways
        override fun phoneEntries() = PlayerMenu.phoneEntries(
            pictureInPicture = true,
            openInAnotherApp = true,
            saveToDownloads = true,
            markWatched = true,
            saveFrame = true,
            pictureShape = true,
            stereo = true,
        )
        override fun tvEntries() = PlayerMenu.tvEntries(
            pictureInPicture = true,
            saveToDownloads = true,
            markWatched = true,
            stereo = true,
            pictureShape = true,
        )
        override fun shape() = VideoScale.entries.getOrElse(3) { VideoScale.Fit }
        override fun speed() = 1.25f
        override fun skipSilence() = false
        override fun stereo() = Stereo3d.OFF
        override fun stereoTagged(): Stereo3d.Layout? = null
        override fun stereoDetail(): String? = null
        override fun volumeBoost() = true
        override fun nightMode() = false
        override fun evenVolume() = true
        override fun sleepDetail() = "23 minutes left"
        override fun sleepChoice() = 30
        override fun loop(): AbLoop? = null
        override fun chapter(): String? = null
        override fun watched() = false
        override fun bookmarks(): BookmarkSheetActions? = null
        override fun playbackDetails(): Pair<String, List<String>>? = null
        override fun onPhoneEntry(entry: PhoneMenuEntry) = Unit
        override fun onTvEntry(entry: PlayerMenuEntry) = Unit
        override fun setShape(scale: VideoScale) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun setSkipSilence(on: Boolean) = Unit
        override fun setStereo(next: Stereo3d) = null to next
        override fun setSleep(minutes: Int?) = Unit
        override fun closed() {
            closings++
        }
    }
}
