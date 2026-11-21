package com.tmplayer.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tmplayer.data.FormFactor
import com.tmplayer.data.SupportReminder
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.BigError
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.Duration

/**
 * The Buy Me a Coffee window on a phone and a television, light and dark, drawn over the screen
 * it covers, and an error state with Send logs beside Try again. PNGs under
 * `build/device-test/review/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoffeeDialogRenderTest {

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private val counters = SupportReminder.Counters(completedWatches = 7, watchTimeMs = 11L * 60 * 60 * 1000)

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private fun activity(tv: Boolean, content: @Composable () -> Unit): ComponentActivity {
        FormFactor.override(tv)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(Tone.background)) { content() }
            }
        }
        return activity
    }

    /** The codes are drawn off the main thread: a moment of real time for them, then the frames. */
    private fun settle() {
        idle(1200)
        Thread.sleep(800)
        idle(300)
    }

    private fun idle(ms: Long) {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        var left = ms
        while (left > 0) {
            val step = minOf(left, 20L)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            left -= step
        }
    }

    /** The activity, then the newest dialog's window over it, as the screen shows them. */
    private fun shot(activity: ComponentActivity, name: String, dialog: Boolean = true) {
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        root.draw(canvas)
        if (dialog) {
            val window = requireNotNull(ShadowDialog.getLatestDialog()?.window) { "no dialog up" }
            canvas.drawColor(0x99000000.toInt())
            window.decorView.draw(canvas)
        }
        val file = File("../build/device-test/review/$name").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-notnight-xhdpi")
    fun phoneLight() {
        val activity = activity(tv = false) { CoffeeDialog(counters, asking = true, onLater = {}, onNever = {}) }
        settle()
        shot(activity, "coffee-phone-light.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun phoneDark() {
        val activity = activity(tv = false) { CoffeeDialog(counters, asking = true, onLater = {}, onNever = {}) }
        settle()
        shot(activity, "coffee-phone-dark.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-notnight-mdpi")
    fun tvLight() {
        val activity = activity(tv = true) { CoffeeDialog(counters, asking = true, onLater = {}, onNever = {}) }
        settle()
        shot(activity, "coffee-tv-light.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-night-mdpi")
    fun tvDark() {
        val activity = activity(tv = true) { CoffeeDialog(counters, asking = true, onLater = {}, onNever = {}) }
        settle()
        shot(activity, "coffee-tv-dark.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun phoneErrorWithSendLogs() {
        val activity = activity(tv = false) { BigError("Telegram did not answer. Check the connection and try again.", onRetry = {}) }
        idle(1500)
        shot(activity, "error-send-logs-phone.png", dialog = false)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-notnight-mdpi")
    fun tvErrorWithSendLogs() {
        val activity = activity(tv = true) { BigError("Telegram did not answer. Check the connection and try again.", onRetry = {}) }
        idle(1500)
        shot(activity, "error-send-logs-tv.png", dialog = false)
    }
}
