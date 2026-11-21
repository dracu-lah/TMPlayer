package com.tmplayer.desktop.profiles

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.tmplayer.data.ProfileUnlock
import com.tmplayer.i18n.L
import com.tmplayer.ui.lock.PinPad
import com.tmplayer.ui.lock.PinPurpose
import com.tmplayer.ui.lock.ProfileFlowScreen
import com.tmplayer.ui.lock.ProfilePinChecker
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.TmSchemes
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The profile screens as the desktop draws them (HX88, B2): Who's watching?, a profile's PIN pad
 * and its options, in both themes, at the default size and with the text twice as large. Each
 * frame has something on it besides the ground, and nothing throws at a 960 by 600 window with
 * large text. With TMPLAYER_SHOTS=<dir> the frames are written there.
 */
class DesktopProfilesRenderTest {
    private val kit = DesktopProfileKit("Asha", "Ben", "Chandra").also { it.pin(1, "2580") }

    @After
    fun tearDown() {
        kit.close()
        ProfileUnlock.lock()
    }

    private val sizes = listOf("1x" to Density(1f), "2x-font" to Density(1f, fontScale = 2f))

    @Test
    fun `who is watching draws in both themes and at twice the text size`() {
        for (dark in listOf(true, false)) for ((size, density) in sizes) {
            val flow = kit.flow()
            shot("profiles-picker-${theme(dark)}-$size", 1366, 768, density, dark) {
                ProfileFlowScreen(flow, kit.switcher.profiles.value, signInStep = false)
            }
            shot("profiles-picker-small-${theme(dark)}-$size", 960, 600, density, dark) {
                ProfileFlowScreen(flow, kit.switcher.profiles.value, signInStep = false)
            }
        }
    }

    @Test
    fun `a profile's PIN pad draws in both themes and at twice the text size`() {
        for (dark in listOf(true, false)) for ((size, density) in sizes) {
            shot("profiles-pin-${theme(dark)}-$size", 1366, 768, density, dark) {
                PinPad(
                    checker = ProfilePinChecker(kit.switcher, kit.ids[1]),
                    purpose = PinPurpose.Check,
                    title = L.profilesPinFor(name = "Ben"),
                    onDone = {},
                    onCancel = {},
                )
            }
        }
    }

    @Test
    fun `a profile's options draw in both themes and at twice the text size`() {
        for (dark in listOf(true, false)) for ((size, density) in sizes) {
            val flow = kit.flow()
            val tile = flow.model(kit.switcher.profiles.value, false).tiles[1]
            flow.options(tile)
            shot("profiles-options-${theme(dark)}-$size", 1366, 768, density, dark) {
                ProfileFlowScreen(flow, kit.switcher.profiles.value, signInStep = false)
            }
        }
    }

    private fun shot(name: String, w: Int, h: Int, density: Density, dark: Boolean, content: @Composable () -> Unit) {
        val png = ImageComposeScene(w, h, density) {
            TmMaterialTheme(dark = dark) { content() }
        }.use { scene ->
            scene.render(0)
            scene.render(600_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }
        val bitmap = Bitmap.makeFromImage(Image.makeFromEncoded(png))
        val ground = TmSchemes.of(dark).background.toArgb()
        var drawn = 0
        for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) if (bitmap.getColor(x, y) != ground) drawn++
        assertTrue("$name has something on it", drawn > 50)
        val out = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        File(out).apply { mkdirs() }.resolve("$name.png").writeBytes(png)
    }

    private fun theme(dark: Boolean) = if (dark) "dark" else "light"
}
