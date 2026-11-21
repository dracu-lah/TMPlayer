package com.tmplayer.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.tmplayer.data.ChannelOption
import com.tmplayer.data.FormFactor
import com.tmplayer.data.SyncChannelBlock
import com.tmplayer.data.SyncChannelChoice
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
 * The sync channel page (HX87) on a phone held upright, with a large font, and on a television.
 * Frames go to `build/device-test/review/` and to TMPLAYER_SHOTS=<dir> when that is set.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SyncChannelRenderTest {

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private val channel = SyncChannelChoice(-1001L, "TMPlayer sync", 42L)

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun phone() {
        save("sync-channel-phone.png", render(tv = false) { SyncChannelContent(SyncChannelUiState(), SyncChannelActions()) })
        save(
            "sync-channel-phone-blocked.png",
            render(tv = false) {
                SyncChannelContent(
                    SyncChannelUiState(choice = channel, block = SyncChannelBlock.MoreMembers(2), oldCopy = "Saved Messages", options = listOf(ChannelOption(1, "TMPlayer sync"))),
                    SyncChannelActions(),
                )
            },
        )
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi", fontScale = 1.6f)
    fun phoneWithLargeFont() {
        save("sync-channel-phone-large-font.png", render(tv = false) { SyncChannelContent(SyncChannelUiState(choice = channel, asking = true), SyncChannelActions()) })
    }

    @Test
    @Config(sdk = [28], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun tv() {
        save("sync-channel-tv.png", render(tv = true) { SyncChannelContent(SyncChannelUiState(), SyncChannelActions()) })
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
