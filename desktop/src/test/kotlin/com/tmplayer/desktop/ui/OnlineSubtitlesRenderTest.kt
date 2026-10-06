package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.tmplayer.desktop.player.OnlineSubtitlesPanel
import com.tmplayer.online.OnlineSubtitles
import com.tmplayer.online.SubtitleTarget
import com.tmplayer.ui.about.About
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Online subtitles on the desktop, over a canned OpenSubtitles ([OnlineFixture]): the Settings
 * group in each state, the sign in prompt, and the player's results panel in each state. With
 * TMPLAYER_SHOTS=<dir> the frames are written there as PNGs.
 */
class OnlineSubtitlesRenderTest {

    @After
    fun reset() {
        OnlineSubtitles.current = null
    }

    @Test
    fun settingsGroupInEachState() {
        for (state in listOf("signed_out", "signed_in", "quota", "expired", "unavailable", "subdl")) {
            OnlineFixture.install(state)
            save("desktop-settings-$state.png", render(height = 420) { OnlineSubtitlesGroup() })
        }
        OnlineFixture.install("signed_out")
        save("desktop-settings-signin-dialog.png", render(height = 900) { SignInDialog(onClose = {}) })
    }

    @Test
    fun aBuildWithoutTheKeyDrawsNothingAndCreditsNobody() {
        OnlineFixture.install("none")
        assertFalse(OnlineSubtitles.available)
        assertTrue(About.groups("1.0.0").none { group -> group.links.any { it.url == About.OPENSUBTITLES } })
        save("desktop-settings-none.png", render(height = 200) { OnlineSubtitlesGroup() })

        OnlineFixture.install("signed_out")
        assertTrue("About credits OpenSubtitles", About.groups("1.0.0").any { group -> group.links.any { it.url == About.OPENSUBTITLES } })
        save("desktop-about-credit.png", render(height = 1500) { AboutPage("1.0.0", onBack = {}) })
    }

    @Test
    fun playerPanelInEachState() {
        val target = SubtitleTarget("The.Coast.S01E04.1080p.WEB.H264.mkv", 1_400_000_000, hash = "8e245d9679d31e12")
        for (state in listOf("signed_in", "signed_out", "quota", "expired", "unavailable", "offline", "empty", "subdl")) {
            val online = OnlineFixture.install(state)
            val result = runBlocking { online.search(target) }
            save(
                "desktop-player-online-$state.png",
                render(height = 720, background = Color(0xFF203040)) {
                    Box(Modifier.fillMaxSize()) {
                        OnlineSubtitlesPanel(target = { target }, onLoaded = { _, _ -> }, onClose = {}, preset = result)
                    }
                },
            )
        }
    }

    private fun render(height: Int, background: Color? = null, page: @Composable () -> Unit): ByteArray =
        ImageComposeScene(WIDTH, height, Density(1.5f)) {
            TmMaterialTheme(dark = true) {
                Surface(Modifier.fillMaxSize(), color = background ?: Tone.background) {
                    Column(Modifier.padding(horizontal = if (background == null) 32.dp else 0.dp)) { page() }
                }
            }
        }.use { scene ->
            scene.render(0)
            Thread.sleep(300)
            scene.render(500_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }

    private fun save(name: String, png: ByteArray) {
        assertTrue("$name drew nothing", png.size > 1_000)
        val out = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        File(out).apply { mkdirs() }.resolve(name).writeBytes(png)
    }

    private companion object {
        const val WIDTH = 1280
    }
}
