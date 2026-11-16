package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.tmplayer.data.ConnectHint
import com.tmplayer.data.ConnectStep
import com.tmplayer.data.NetworkFacts
import com.tmplayer.data.NetworkKind
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Translator
import com.tmplayer.ui.components.BrandLoader
import com.tmplayer.ui.connect.ConnectBannerView
import com.tmplayer.ui.connect.ConnectingHelpView
import com.tmplayer.ui.connect.ProxyScreen
import com.tmplayer.ui.connect.connectHintLabel
import com.tmplayer.ui.connect.connectStepLabel
import com.tmplayer.ui.connect.networkLine
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.TmSchemes
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The connecting help, its banner and the proxy screen, drawn in both themes: each frame has
 * something on it besides the ground. With TMPLAYER_SHOTS=<dir> the frames are written there.
 */
class ConnectHelpRenderTest {

    private val s get() = Translator.messages

    @Test
    fun `the connecting help draws, waiting and stalled`() {
        for (dark in listOf(true, false)) {
            // Waiting is the spinner alone, a few dozen pixels at this sampling.
            shot("connect-waiting-${theme(dark)}", 1366, 768, dark, least = 5) { Loader(stalled = false) }
            shot("connect-stalled-${theme(dark)}", 1366, 768, dark) { Loader(stalled = true) }
            shot("connect-stalled-small-${theme(dark)}", 960, 600, dark) { Loader(stalled = true, hint = ConnectHint.Vpn) }
        }
    }

    @Test
    fun `the banner and the proxy screen draw`() {
        for (dark in listOf(true, false)) {
            shot("connect-banner-${theme(dark)}", 960, 200, dark) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
                    ConnectBannerView(connectHintLabel(ConnectHint.MaybeBlocked, s), onRetry = {}, onOpenProxies = {})
                }
            }
            shot("proxy-${theme(dark)}", 1366, 900, dark) { ProxyScreen(onClose = {}) }
        }
    }

    @Composable
    private fun Loader(stalled: Boolean, hint: ConnectHint = ConnectHint.MaybeBlocked) {
        BrandLoader(label = L.signinConnecting, sweepAt = 0.5f) {
            ConnectingHelpView(
                status = connectStepLabel(ConnectStep.Connecting, s),
                stalled = stalled,
                hint = connectHintLabel(hint, s),
                network = networkLine(NetworkFacts(known = true, kind = NetworkKind.WiFi, vpn = hint == ConnectHint.Vpn), s),
                proxyOn = false,
                onRetry = {},
                onOpenProxies = {},
                sendLogs = { SendLogsActions() },
            )
        }
    }

    private fun shot(name: String, w: Int, h: Int, dark: Boolean, least: Int = 50, content: @Composable () -> Unit) {
        val png = ImageComposeScene(w, h, Density(1f)) {
            TmMaterialTheme(dark = dark) { content() }
        }.use { scene ->
            scene.render(0)
            scene.render(600_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }
        val bitmap = Bitmap.makeFromImage(Image.makeFromEncoded(png))
        val ground = TmSchemes.of(dark).background.toArgb()
        var drawn = 0
        for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) if (bitmap.getColor(x, y) != ground) drawn++
        assertTrue("$name has something on it", drawn > least)
        val out = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        File(out).apply { mkdirs() }.resolve("$name.png").writeBytes(png)
    }

    private fun theme(dark: Boolean) = if (dark) "dark" else "light"
}
