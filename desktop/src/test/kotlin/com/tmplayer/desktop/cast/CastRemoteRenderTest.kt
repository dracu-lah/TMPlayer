package com.tmplayer.desktop.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.tmplayer.desktop.player.CastingPanel
import com.tmplayer.desktop.player.PlaybackStatus
import com.tmplayer.desktop.player.PlayerTheme
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.tmplayer.cast.CastPhase
import com.tmplayer.ui.player.CastRemote
import com.tmplayer.ui.player.CastRemoteActions
import com.tmplayer.ui.player.CastRemoteState
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The phone's cast remote (CP73) drawn at a phone held upright, a phone on its side and a tablet,
 * in both themes, playing and in trouble, into build/cast-shots for a look: in landscape every
 * control has to be on screen at once.
 */
class CastRemoteRenderTest {

    private val actions = object : CastRemoteActions {
        override fun back() = Unit
        override fun devices() = Unit
        override fun togglePlay() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(deltaMs: Long) = Unit
        override fun previous() = Unit
        override fun next() = Unit
        override fun audio() = Unit
        override fun subtitles() = Unit
        override fun speed() = Unit
        override fun setVolume(level: Float) = Unit
        override fun toggleMute() = Unit
        override fun stop() = Unit
        override fun reconnect() = Unit
        override fun playHere() = Unit
        override fun retry() = Unit
    }

    private val playing = CastRemoteState(
        title = "Episode 4: The Lighthouse",
        subtitle = "Season 1",
        device = "Living Room TV",
        phase = CastPhase.Playing,
        positionMs = 754_000,
        durationMs = 1_442_000,
        showsPause = true,
        volume = 0.42f,
        hasAudioChoice = true,
        hasSubtitles = true,
        subtitlesOn = true,
        speed = 1f,
        hasPrevious = true,
        hasNext = true,
    )

    private val trouble = playing.copy(phase = CastPhase.Failed, error = "The TV couldn't play this video.", showsPause = false, note = null)
    private val buffering = playing.copy(phase = CastPhase.Buffering, note = "Your TV can't play this video's sound, so the phone converts it.", speed = 1.5f, subtitlesOn = false, volumeFixed = true)

    private class Form(val name: String, val widthDp: Int, val heightDp: Int, val density: Float)

    private val forms = listOf(
        Form("phone-portrait", 393, 851, 2.75f),
        Form("phone-landscape", 851, 393, 2.75f),
        Form("tablet", 800, 1280, 2f),
    )

    @Test
    fun `the desktop casting panel is one compact card, for a Cast TV and a DLNA one`() {
        val loopback = java.net.InetAddress.getLoopbackAddress()
        val dlna = com.tmplayer.cast.dlna.DlnaDevice(
            com.tmplayer.cast.dlna.DeviceDescription(
                "uuid:t", "Bedroom TV", "Acme", "T1000", null,
                com.tmplayer.cast.dlna.DlnaService("urn:schemas-upnp-org:service:AVTransport:1", "http://127.0.0.1/av"), null, null,
            ),
            loopback, "http://127.0.0.1/d.xml",
        )
        val targets = listOf(
            "cast" to CastTarget.of(CastDevice("id", "Living Room TV", "Chromecast", loopback)),
            "dlna" to CastTarget.of(dlna),
        )
        val out = File("build/cast-shots").apply { mkdirs() }
        for ((kind, target) in targets) {
            val receiver = Receiver.of(target)
            val engine = CastPlaybackEngine(receiver) { CastTitle("Episode 4") }
            for ((label, status) in listOf("starting" to PlaybackStatus(), "failed" to PlaybackStatus(error = "The TV couldn't play this video."))) {
                val png = ImageComposeScene(1280, 720, Density(1f)) {
                    PlayerTheme {
                        Box(Modifier.fillMaxSize().background(Color(0xFF0B0D10))) {
                            CastingPanel(engine, status, onPlayHere = {})
                        }
                    }
                }.use { scene ->
                    scene.render(0)
                    scene.render(1_000_000_000).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes
                }
                assertTrue(png.size > 2_000)
                out.resolve("desktop-panel-$kind-$label.png").writeBytes(png)
            }
            engine.abandon()
            receiver.close()
        }
    }

    @Test
    fun `the cast remote draws on every form in both themes`() {
        val out = File("build/cast-shots").apply { mkdirs() }
        for (form in forms) for (dark in listOf(false, true)) for ((label, state) in listOf("playing" to playing, "buffering" to buffering, "failed" to trouble)) {
            val w = (form.widthDp * form.density).toInt()
            val h = (form.heightDp * form.density).toInt()
            val png = ImageComposeScene(w, h, Density(form.density)) {
                TmMaterialTheme(dark = dark) { CastRemote(state, actions) }
            }.use { scene ->
                scene.render(0)
                scene.render(2_000_000_000).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes
            }
            assertTrue(png.size > 5_000)
            out.resolve("cast-${form.name}-${if (dark) "dark" else "light"}-$label.png").writeBytes(png)
        }
    }
}
