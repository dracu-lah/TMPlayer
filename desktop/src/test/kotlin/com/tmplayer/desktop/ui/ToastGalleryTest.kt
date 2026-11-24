package com.tmplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.tmplayer.ui.components.ToastActionButton
import com.tmplayer.ui.components.ToastCard
import com.tmplayer.ui.components.ToastDismiss
import com.tmplayer.ui.components.ToastKind
import com.tmplayer.ui.components.ToastLayout
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The four toast kinds on the phone (411 x 891), the desktop (1280 x 800) and the television
 * (1920 x 1080), dark and light, each over a stand-in for the floating bar. The phone's and the
 * television's cards are the shared [ToastCard] with the same width rules their hosts give it
 * (the hosts themselves are Android's; their position is pinned by ToastPositionTest). With
 * TMPLAYER_SHOTS=<dir> the frames are written there as PNGs.
 */
class ToastGalleryTest {

    private val words = mapOf(
        ToastKind.Success to "Up to date. You have version 1.28.7.",
        ToastKind.Info to "Link copied. Paste it in a chat to share this video.",
        ToastKind.Warning to "Only 2 GB are free: this may not fit.",
        ToastKind.Error to "Could not save the logs. Check the folder and try again.",
    )

    private enum class Form { Phone, Desktop, Tv }

    @Test
    fun everyKindOnEveryForm() {
        for (dark in listOf(true, false)) {
            val theme = if (dark) "dark" else "light"
            for (kind in ToastKind.entries) {
                val name = kind.name.lowercase()
                save("phone-$name-$theme.png", render(411, 891, 2.625f, dark) { Stage(Form.Phone, kind, false) })
                save("desktop-$name-$theme.png", render(1280, 800, 1f, dark) { Stage(Form.Desktop, kind, false) })
                save("tv-$name-$theme.png", render(1920, 1080, 1f, dark) { Stage(Form.Tv, kind, false) })
            }
            save("phone-action-$theme.png", render(411, 891, 2.625f, dark) { Stage(Form.Phone, ToastKind.Info, true) })
            save("desktop-action-$theme.png", render(1280, 800, 1f, dark) { Stage(Form.Desktop, ToastKind.Info, true) })
        }
    }

    @Composable
    private fun Stage(form: Form, kind: ToastKind, action: Boolean) {
        val message = if (action) "Chat hidden from Chats" else words.getValue(kind)
        val actionSlot: (@Composable () -> Unit)? = if (action) {
            { ToastActionButton("Undo", kind) {} }
        } else {
            null
        }
        Box(Modifier.fillMaxSize()) {
            when (form) {
                Form.Phone -> {
                    // The phone's floating bar: 56 dp, 12 dp off the foot.
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).height(56.dp).widthIn(min = 300.dp).clip(CircleShape).background(FloatingTone.menu))
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp + 56.dp + ToastLayout.Gap)) {
                        ToastCard(
                            kind, message,
                            Modifier.padding(horizontal = ToastLayout.PhoneMargin).widthIn(max = ToastLayout.PhoneMax).fillMaxWidth(),
                            fillWidth = true, dismiss = ToastDismiss.Swipe, action = actionSlot,
                        )
                    }
                }
                Form.Desktop -> {
                    // The desktop's bar: 62 dp tall, 420 dp wide, 16 dp off the foot.
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).height(62.dp).size(420.dp, 62.dp).clip(CircleShape).background(FloatingTone.menu))
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp + 62.dp + ToastLayout.Gap)) {
                        ToastCard(
                            kind, message,
                            Modifier.widthIn(min = ToastLayout.DesktopMin, max = ToastLayout.DesktopMax),
                            dismiss = ToastDismiss.Click, action = actionSlot,
                        )
                    }
                }
                Form.Tv -> {
                    Row(Modifier.align(Alignment.TopCenter).padding(top = 27.dp)) {}
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 54.dp + ToastLayout.Gap)) {
                        ToastCard(kind, message, Modifier.padding(horizontal = 48.dp).widthIn(max = ToastLayout.TvMax), large = true)
                    }
                }
            }
        }
    }

    private fun render(width: Int, height: Int, scale: Float, dark: Boolean, page: @Composable () -> Unit): ByteArray =
        ImageComposeScene((width * scale).toInt(), (height * scale).toInt(), Density(scale)) {
            TmMaterialTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = Tone.background) { page() }
            }
        }.use { scene ->
            scene.render(0)
            Thread.sleep(300)
            scene.render(500_000_000)
            scene.render(1_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }

    private fun save(name: String, png: ByteArray) {
        assertTrue(png.size > 1000)
        val out = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        File(out).apply { mkdirs() }.resolve(name).writeBytes(png)
    }
}
