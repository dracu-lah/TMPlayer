package com.tmplayer.player

import android.content.Context
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
import androidx.test.core.app.ApplicationProvider
import com.tmplayer.R
import com.tmplayer.i18n.L
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.lang.reflect.Proxy
import java.time.Duration

/**
 * R5 (Raju steps 6.2 and 6.3): the words under the six icons for the first three openings of the
 * player, back and forward ten beside play, on a phone held upright and sideways and on a television,
 * at the system font and at twice it. The pictures go to `build/device-test/review/labels-*.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ControlLabelsTest {

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

    private var seeks = mutableListOf<Boolean>()

    private class Shown(val controls: PlayerControls, val root: FrameLayout, val activity: FragmentActivity)

    private fun idle() = repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }

    private fun render(out: String, tv: Boolean, labels: Boolean, fontScale: Float = 1f): Shown {
        RuntimeEnvironment.setFontScale(fontScale)
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val root = FrameLayout(activity).apply {
            id = R.id.player_root
            setBackgroundColor(Color.rgb(0x20, 0x30, 0x40))
        }
        LayoutInflater.from(activity).inflate(R.layout.player_controls, root, true)
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
            onSeekButton = { seeks += it },
            showLabels = labels,
        )
        controls.show()
        idle()
        val decor = activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        val file = File("../build/device-test/review/$out").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
        return Shown(controls, root, activity)
    }

    private fun text(shown: Shown, id: Int) = shown.root.findViewById<TextView>(id)

    /** Each visible word sits under its icon, whole (never cut to an ellipsis) and inside its slot. */
    private fun assertWordsUnderTheirIcons(shown: Shown, ids: List<Pair<Int, Int>>) {
        for ((button, label) in ids) {
            val icon = shown.root.findViewById<View>(button)
            val word = text(shown, label)
            assertEquals("the word shows", View.VISIBLE, word.visibility)
            assertTrue("${word.text} sits under its icon", word.top >= icon.bottom)
            assertTrue("${word.text} is not cut short", word.layout.getEllipsisCount(0) == 0)
            val slot = word.parent as View
            assertTrue("${word.text} fits its slot", word.left >= 0 && word.right <= slot.width)
        }
    }

    private val phoneRow = listOf(
        R.id.control_subtitles to R.id.label_subtitles,
        R.id.control_audio to R.id.label_audio,
        R.id.control_speed to R.id.label_speed,
        R.id.control_rotate to R.id.label_rotate,
    )

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `phone held upright at the system font`() {
        val shown = render("labels-phone-upright-1x.png", tv = false, labels = true)
        assertWordsUnderTheirIcons(shown, phoneRow)
        assertEquals("Subtitles", text(shown, R.id.label_subtitles).text.toString())
        assertEquals("Sound", text(shown, R.id.label_audio).text.toString())
        assertEquals("Speed", text(shown, R.id.label_speed).text.toString())
        assertEquals("Rotate", text(shown, R.id.label_rotate).text.toString())
        assertCentreFits(shown)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `phone held upright at twice the system font`() {
        val shown = render("labels-phone-upright-2x.png", tv = false, labels = true, fontScale = 2f)
        assertWordsUnderTheirIcons(shown, phoneRow)
        // The words stop growing at the cap, so a word under six icons never outgrows the row.
        val density = shown.activity.resources.displayMetrics.density
        assertEquals(ControlLabels.textPx(density, 2f), text(shown, R.id.label_speed).textSize, 0.5f)
        assertEquals(ControlLabels.TEXT_SP * density * ControlLabels.MAX_FONT_SCALE, text(shown, R.id.label_speed).textSize, 0.5f)
        assertCentreFits(shown)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h740dp-port-night-xhdpi")
    fun `the narrowest phone keeps back, play and forward on the screen at twice the font`() {
        val shown = render("labels-phone-narrow-2x.png", tv = false, labels = true, fontScale = 2f)
        assertCentreFits(shown)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w891dp-h411dp-land-night-xhdpi")
    fun `phone held sideways carries the shape and the lock with their words`() {
        val shown = render("labels-phone-sideways-1x.png", tv = false, labels = true)
        assertWordsUnderTheirIcons(shown, phoneRow + listOf(R.id.control_shape to R.id.label_shape, R.id.control_lock to R.id.label_lock))
        // The word under the shape button is the shape in force.
        assertEquals(VideoScaleLabels.fit, text(shown, R.id.label_shape).text.toString())
        shown.controls.setShape(VideoScale.Crop)
        assertEquals(VideoScale.Crop.label, text(shown, R.id.label_shape).text.toString())
    }

    @Test
    @Config(sdk = [35], qualifiers = "w891dp-h411dp-land-night-xhdpi")
    fun `phone held sideways at twice the font`() {
        val shown = render("labels-phone-sideways-2x.png", tv = false, labels = true, fontScale = 2f)
        assertWordsUnderTheirIcons(shown, phoneRow + listOf(R.id.control_shape to R.id.label_shape, R.id.control_lock to R.id.label_lock))
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-night-xhdpi")
    fun `television shows the same words, without the rotate and the lock it does not have`() {
        val shown = render("labels-tv-1x.png", tv = true, labels = true)
        assertWordsUnderTheirIcons(
            shown,
            listOf(
                R.id.control_subtitles to R.id.label_subtitles,
                R.id.control_audio to R.id.label_audio,
                R.id.control_speed to R.id.label_speed,
                R.id.control_shape to R.id.label_shape,
            ),
        )
        assertEquals(View.GONE, (shown.root.findViewById<View>(R.id.control_rotate).parent as View).visibility)
        assertCentreFits(shown)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-night-xhdpi")
    fun `television at twice the font`() {
        val shown = render("labels-tv-2x.png", tv = true, labels = true, fontScale = 2f)
        assertWordsUnderTheirIcons(shown, listOf(R.id.control_subtitles to R.id.label_subtitles, R.id.control_speed to R.id.label_speed))
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `after the third opening the icons are alone again, and the names stay for TalkBack and a long press`() {
        val shown = render("labels-phone-upright-off.png", tv = false, labels = false)
        for (id in listOf(R.id.label_subtitles, R.id.label_audio, R.id.label_speed, R.id.label_rotate, R.id.label_shape, R.id.label_lock)) {
            assertEquals(View.GONE, text(shown, id).visibility)
        }
        assertFalse(shown.controls.labelsShown)
        assertEquals(L.playerAudio, shown.root.findViewById<View>(R.id.control_audio).contentDescription.toString())
        shown.controls.setLabelsShown(true)
        assertEquals(View.VISIBLE, text(shown, R.id.label_subtitles).visibility)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `back and forward ten sit either side of play and each jumps once`() {
        val shown = render("labels-phone-upright-skip.png", tv = false, labels = false)
        val back = shown.root.findViewById<View>(R.id.center_seek_back)
        val play = shown.root.findViewById<View>(R.id.center_play_pause)
        val forward = shown.root.findViewById<View>(R.id.center_seek_forward)
        assertTrue(back.isShown && forward.isShown)
        assertTrue("back is left of play", leftOf(back, play))
        assertTrue("forward is right of play", leftOf(play, forward))
        assertEquals("10", text(shown, R.id.center_seek_back_text).text.toString())
        assertEquals("10", text(shown, R.id.center_seek_forward_text).text.toString())
        assertEquals("Back 10 seconds", back.contentDescription.toString())
        assertEquals("Forward 10 seconds", forward.contentDescription.toString())
        assertTrue("a thumb reaches it", back.width >= (48 * shown.activity.resources.displayMetrics.density).toInt())
        back.performClick()
        forward.performClick()
        assertEquals(listOf(false, true), seeks)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-night-xhdpi")
    fun `on the television the remote walks from play to the skip buttons and back to the bar`() {
        val shown = render("labels-tv-skip.png", tv = true, labels = false)
        val back = shown.root.findViewById<View>(R.id.center_seek_back)
        val play = shown.root.findViewById<View>(R.id.center_play_pause)
        val forward = shown.root.findViewById<View>(R.id.center_seek_forward)
        assertTrue(back.isShown && forward.isShown && back.isFocusable && forward.isFocusable)
        // The remote's first key takes the window out of touch mode, as on a television.
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            shown.activity.window.decorView.parent, "ensureTouchMode",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        assertEquals(forward, play.focusSearch(View.FOCUS_RIGHT))
        assertEquals(back, play.focusSearch(View.FOCUS_LEFT))
        assertEquals(R.id.controls_timebar, back.nextFocusDownId)
        assertEquals(R.id.controls_timebar, forward.nextFocusDownId)
        forward.performClick()
        assertEquals(listOf(true), seeks)
    }

    @Test
    fun `the words are counted per opening of the player, three times, and remembered`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("player_control_labels", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(listOf(true, true, true, false, false), List(5) { ControlLabels.takeSession(context) })
        assertEquals(3, context.getSharedPreferences("player_control_labels", Context.MODE_PRIVATE).getInt("sessions", -1))
        // A damaged count (negative, huge) reads as something sane and never throws.
        context.getSharedPreferences("player_control_labels", Context.MODE_PRIVATE).edit().putInt("sessions", -9).commit()
        assertTrue(ControlLabels.takeSession(context))
        context.getSharedPreferences("player_control_labels", Context.MODE_PRIVATE).edit().putInt("sessions", 9_999).commit()
        assertFalse(ControlLabels.takeSession(context))
        assertFalse("no store, no words", ControlLabels.takeSession(null as android.content.SharedPreferences?))
        assertTrue(ControlLabels.shows(0) && ControlLabels.shows(2) && !ControlLabels.shows(3))
        assertEquals(3, ControlLabels.after(3))
    }

    @Test
    fun `the words follow the system font up to a cap and never shrink below the base`() {
        assertEquals(11f * 3f, ControlLabels.textPx(3f, 1f), 0.001f)
        assertEquals(11f * 3f * 1.3f, ControlLabels.textPx(3f, 2f), 0.001f)
        assertEquals(11f * 3f, ControlLabels.textPx(3f, 0.8f), 0.001f)
    }

    private fun leftOf(a: View, b: View): Boolean {
        val pa = IntArray(2)
        val pb = IntArray(2)
        a.getLocationOnScreen(pa)
        b.getLocationOnScreen(pb)
        return pa[0] + a.width <= pb[0]
    }

    /** The centre cluster, with episode steps and the two skips, is whole on the screen. */
    private fun assertCentreFits(shown: Shown) {
        val width = shown.activity.resources.displayMetrics.widthPixels
        for (id in listOf(R.id.center_seek_back, R.id.center_play_pause, R.id.center_seek_forward)) {
            val at = IntArray(2)
            val view = shown.root.findViewById<View>(id)
            view.getLocationOnScreen(at)
            assertTrue("view $id starts on the screen", at[0] >= 0)
            assertTrue("view $id ends on the screen", at[0] + view.width <= width)
        }
    }

    /** The default word for Fit, to compare against without reaching into the catalog twice. */
    private object VideoScaleLabels {
        val fit: String get() = VideoScale.Fit.label
    }
}
