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
import com.tmplayer.data.SupportRegion
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.about.SupportActions
import com.tmplayer.ui.about.SupportDialog
import com.tmplayer.ui.about.SupportStep
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
 * The support window (D1) on a phone and a television, light and dark, in both region orders and on
 * the UPI and Buy Me a Coffee pages, drawn over the screen it covers. PNGs under
 * `build/device-test/review/support-*.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SupportCardsRenderTest {

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

    private val actions = SupportActions(openLink = { true }, copy = { true }, openUpiApp = { true })
    private val tvActions = SupportActions(openLink = { false }, copy = { true })
    private val elsewhere = SupportRegion.order("US", null)
    private val india = SupportRegion.order("IN", null)

    private fun window(order: SupportRegion.Order, step: SupportStep = SupportStep.Ways, tv: Boolean = false, asking: Boolean = true): @Composable () -> Unit = {
        SupportDialog(
            order = order,
            title = if (asking) "TMPlayer is part of your evenings" else "Support TMPlayer",
            text = if (asking) "You have watched 11 hours here. If it earns a spot on your screen, a coffee keeps it going. It is voluntary and unlocks nothing." else "Voluntary. It unlocks nothing: every feature is free for everyone.",
            actions = if (tv) tvActions else actions,
            onClose = {},
            onNever = if (asking) ({}) else null,
            start = step,
        )
    }

    private fun phone(name: String, content: @Composable () -> Unit) {
        val activity = activity(tv = false, content)
        settle()
        shot(activity, name)
    }

    private fun tv(name: String, content: @Composable () -> Unit) {
        val activity = activity(tv = true, content)
        settle()
        shot(activity, name)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-notnight-xhdpi")
    fun phoneElsewhereLight() = phone("support-phone-elsewhere-light.png", window(elsewhere))

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun phoneElsewhereDark() = phone("support-phone-elsewhere-dark.png", window(elsewhere))

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-notnight-xhdpi")
    fun phoneIndiaLight() = phone("support-phone-india-light.png", window(india, asking = false))

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun phoneIndiaDark() = phone("support-phone-india-dark.png", window(india, asking = false))

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun phoneUpiPage() = phone("support-phone-upi.png", window(india, SupportStep.Upi))

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun phoneLargestFont() {
        // The largest system font: the window scrolls and the cards wrap instead of cutting words.
        org.robolectric.RuntimeEnvironment.setFontScale(2.0f)
        phone("support-phone-largest-font.png", window(india))
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-notnight-mdpi")
    fun tvElsewhereLight() = tv("support-tv-elsewhere-light.png", window(elsewhere, tv = true))

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-night-mdpi")
    fun tvIndiaDark() = tv("support-tv-india-dark.png", window(india, tv = true, asking = false))

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-night-mdpi")
    fun tvCoffeePage() = tv("support-tv-coffee.png", window(elsewhere, SupportStep.Coffee, tv = true))

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-night-mdpi")
    fun tvUpiPage() = tv("support-tv-upi.png", window(india, SupportStep.Upi, tv = true))
}
