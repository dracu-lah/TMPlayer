package com.tmplayer.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.tmplayer.data.AccountSession
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
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
 * The Devices page (CP83) on a phone held upright and on a television. Frames go to
 * `build/device-test/review/` and to TMPLAYER_SHOTS=<dir> when that is set.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DevicesRenderTest {

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
        save("devices-phone.png", render(tv = false) { DevicesScreen(onClose = {}, preview = SESSIONS) })
    }

    @Test
    @Config(sdk = [28], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun tv() {
        save("devices-tv.png", render(tv = true) { DevicesScreen(onClose = {}, preview = SESSIONS) })
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

    private companion object {
        val NOW = (System.currentTimeMillis() / 1000).toInt()

        val SESSIONS = listOf(
            AccountSession(
                1, current = true, kind = AccountSession.Kind.Phone, device = "POCO X6", platform = "Android",
                systemVersion = "14", appName = "TMPlayer", appVersion = "1.27.0", official = false,
                loginDate = NOW - 86_400 * 40, lastActiveDate = NOW, ip = "203.0.113.5", location = "Lisbon, Portugal",
            ),
            AccountSession(
                2, current = false, kind = AccountSession.Kind.Computer, device = "Desktop", platform = "Windows",
                systemVersion = "11", appName = "Telegram Desktop", appVersion = "5.6", official = true,
                loginDate = NOW - 86_400 * 200, lastActiveDate = NOW - 3_600 * 3, ip = "198.51.100.7", location = "Lisbon, Portugal",
            ),
            AccountSession(
                3, current = false, kind = AccountSession.Kind.Browser, device = "Firefox 131", platform = "Linux",
                systemVersion = "", appName = "Telegram Web A", appVersion = "10.9", official = true,
                loginDate = NOW - 86_400 * 3, lastActiveDate = NOW - 86_400 * 2, ip = "192.0.2.44", location = "Madrid, Spain",
                unconfirmed = true,
            ),
        )
    }
}
