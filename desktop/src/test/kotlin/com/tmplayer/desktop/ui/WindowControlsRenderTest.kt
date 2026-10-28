package com.tmplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.tmplayer.desktop.os.WindowChromeMode
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The window's own buttons at the end of a page header (Windows: three, and a tiling window
 * manager: two), on their own over a page with no header, and in the player's bar, in both
 * themes. With TMPLAYER_SHOTS=<dir> the frames are written there as PNGs.
 */
class WindowControlsRenderTest {

    private fun chrome(mode: WindowChromeMode, maximized: Boolean = false) = WindowChrome(
        mode = mode,
        window = null,
        onMinimize = if (mode == WindowChromeMode.Custom) ({}) else null,
        onToggleMaximize = {},
        onClose = {},
        maximized = { maximized },
    )

    @Test
    fun headers() {
        for (dark in listOf(true, false)) {
            val suffix = if (dark) "" else "-light"
            save("window-controls$suffix.png", render(dark) {
                Column(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalWindowChrome provides chrome(WindowChromeMode.Custom)) {
                        PageHeader("Home", "What you were watching, and what is new", actions = {
                            IconButton(onClick = {}) { Icon(TmIcons.Refresh, contentDescription = null) }
                        })
                    }
                    CompositionLocalProvider(LocalWindowChrome provides chrome(WindowChromeMode.Custom, maximized = true)) {
                        PageHeader("Settings", "Version 2.0.0")
                    }
                    CompositionLocalProvider(LocalWindowChrome provides chrome(WindowChromeMode.Tiling)) {
                        PageHeader("Downloads", "Tiling window manager: close and fullscreen")
                    }
                    CompositionLocalProvider(LocalWindowChrome provides chrome(WindowChromeMode.Custom)) {
                        Box(Modifier.fillMaxWidth().height(80.dp)) {
                            Text("A page with no header", Modifier.padding(24.dp))
                            WindowControlsFallback(Modifier.align(Alignment.TopEnd))
                        }
                        // The player's bar over a dark frame.
                        Row(
                            Modifier.fillMaxWidth().background(Color(0xFF1B2330)).padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Big Buck Bunny", color = Color.White, modifier = Modifier.weight(1f))
                            WindowControls(onArt = true)
                        }
                    }
                }
            })
        }
    }

    /** A loader over Home: the header is still composed under it, and the buttons show over the cover. */
    @Test
    fun coverOverHeader() {
        save("window-controls-cover.png", render(dark = true, height = 200) {
            CompositionLocalProvider(LocalWindowChrome provides chrome(WindowChromeMode.Custom)) {
                Box(Modifier.fillMaxSize()) {
                    PageHeader("Home", "Under the loader")
                    Box(Modifier.fillMaxSize().background(Color(0xFF10141B)), contentAlignment = Alignment.Center) {
                        CoverWindowControls()
                        Text("Loader", color = Color.White)
                    }
                    WindowControlsFallback(Modifier.align(Alignment.TopEnd))
                }
            }
        })
    }

    @Test
    fun glyphs() {
        save("window-glyphs.png", render(dark = true, width = 400, height = 120) {
            Row(Modifier.padding(16.dp)) {
                for (glyph in listOf(WindowGlyphs.Minimize, WindowGlyphs.Maximize, WindowGlyphs.Restore, WindowGlyphs.Close)) {
                    Icon(glyph, contentDescription = null, modifier = Modifier.padding(8.dp).size(72.dp))
                }
            }
        })
    }

    private fun render(dark: Boolean, width: Int = WIDTH, height: Int = HEIGHT, page: @Composable () -> Unit): ByteArray =
        ImageComposeScene(width, height, Density(1f)) {
            TmMaterialTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = Tone.background) { page() }
            }
        }.use { scene ->
            scene.render(0)
            scene.render(500_000_000)
            scene.render(1_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }

    private fun save(name: String, png: ByteArray) {
        assertTrue(png.size > 1000)
        val out = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        File(out).apply { mkdirs() }.resolve(name).writeBytes(png)
    }

    private companion object {
        const val WIDTH = 1100
        const val HEIGHT = 560
    }
}
