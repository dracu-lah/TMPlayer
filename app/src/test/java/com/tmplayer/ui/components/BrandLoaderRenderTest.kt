package com.tmplayer.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.toArgb
import com.tmplayer.data.FormFactor
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.TmSchemes
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

/**
 * The loader after the splash on a phone held upright and on a television at 1920 by 1080, in
 * both themes, with the light held at points through its sweep. The frames go to
 * `build/device-test/review/`, and to TMPLAYER_SHOTS=<dir> as well when that is set. Each one
 * checks that its corners are the window's own colour, which is what makes the hand-over from the
 * system splash seamless.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrandLoaderRenderTest {

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun phoneDark() = frames("phone", tv = false, dark = true)

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-notnight-xhdpi")
    fun phoneLight() = frames("phone", tv = false, dark = false)

    @Test
    @Config(sdk = [28], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun tvDark() = frames("tv", tv = true, dark = true)

    @Test
    @Config(sdk = [28], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun tvLight() = frames("tv", tv = true, dark = false)

    private fun frames(device: String, tv: Boolean, dark: Boolean) {
        val theme = if (dark) "dark" else "light"
        for (at in listOf(0.15f, 0.35f, 0.5f, 0.65f, 0.85f)) {
            val bitmap = render(tv, dark, at)
            val ground = TmSchemes.of(dark).background.toArgb()
            assertEquals(ground, bitmap.getPixel(2, 2))
            assertEquals(ground, bitmap.getPixel(bitmap.width - 3, bitmap.height - 3))
            save("loader-$device-$theme-${(at * 100).toInt()}.png", bitmap)
        }
    }

    private fun render(tv: Boolean, dark: Boolean, at: Float): Bitmap {
        FormFactor.override(tv)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            TmMaterialTheme(dark = dark) { BrandLoader(label = L.stateConnecting, sweepAt = at) }
        }
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
