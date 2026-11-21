package com.tmplayer.ui.connect

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.tmplayer.data.ConnectHint
import com.tmplayer.data.ConnectStep
import com.tmplayer.data.FormFactor
import com.tmplayer.data.NetworkFacts
import com.tmplayer.data.NetworkKind
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Translator
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.BrandLoader
import com.tmplayer.ui.theme.TmMaterialTheme
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
import java.io.File

/**
 * The connecting help once the wait has gone on too long, on a phone held upright and on a
 * television, and the proxy screen on both. Frames go to `build/device-test/review/` and to
 * TMPLAYER_SHOTS=<dir> when that is set.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectHelpRenderTest {

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun phone() {
        save("connect-stalled-phone.png", render(tv = false) { Stalled() })
        save("proxy-phone.png", render(tv = false) { ProxyScreen(onClose = {}) })
    }

    @Test
    @Config(sdk = [28], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun tv() {
        save("connect-stalled-tv.png", render(tv = true) { Stalled() })
        save("proxy-tv.png", render(tv = true) { ProxyScreen(onClose = {}) })
    }

    @androidx.compose.runtime.Composable
    private fun Stalled() {
        val s = Translator.messages
        BrandLoader(label = L.stateConnecting, sweepAt = 0.5f) {
            ConnectingHelpView(
                status = connectStepLabel(ConnectStep.Connecting, s),
                stalled = true,
                hint = connectHintLabel(ConnectHint.MaybeBlocked, s),
                network = networkLine(NetworkFacts(known = true, kind = NetworkKind.Mobile, validated = false), s),
                proxyOn = false,
                onRetry = {},
                onOpenProxies = {},
                sendLogs = { com.tmplayer.ui.settings.SendLogsButton() },
            )
        }
    }

    private fun render(tv: Boolean, content: @androidx.compose.runtime.Composable () -> Unit): Bitmap {
        FormFactor.override(tv)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { TmMaterialTheme(dark = true) { content() } }
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        return bitmap
    }

    private fun save(name: String, bitmap: Bitmap) {
        val dirs = listOfNotNull(
            File("../build/device-test/review").absoluteFile,
            System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() }?.let(::File),
        )
        for (dir in dirs) {
            val file = dir.apply { mkdirs() }.resolve(name)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(file.length() > 0)
        }
    }
}
