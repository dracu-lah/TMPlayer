package com.tmplayer.desktop.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** EG-F37 on the desktop: mpv's track list in one plain line, the sheet that shows it, Copy details. */
class MpvFormatsTest {

    /** mpv's properties as a map: "track-list/0/codec" and so on. */
    private class Fake(private val values: Map<String, Any>) : MpvFormats.Props {
        override fun string(name: String) = values[name] as? String
        override fun int(name: String) = values[name] as? Int
        override fun double(name: String) = values[name] as? Double
        override fun bool(name: String) = values[name] as? Boolean
    }

    private val hevcFile = mapOf<String, Any>(
        "track-list/count" to 3,
        "track-list/0/type" to "video", "track-list/0/codec" to "hevc", "track-list/0/codec-profile" to "Main 10",
        "track-list/0/demux-w" to 3840, "track-list/0/demux-h" to 2160, "track-list/0/demux-fps" to 23.976,
        "track-list/1/type" to "audio", "track-list/1/codec" to "eac3", "track-list/1/demux-channel-count" to 6, "track-list/1/selected" to true,
        "track-list/2/type" to "sub", "track-list/2/codec" to "subrip",
        "file-format" to "matroska,webm",
        "hwdec-current" to "no",
        "video-params/gamma" to "pq",
        "decoder-list/count" to 3,
        "decoder-list/0/codec" to "hevc", "decoder-list/0/driver" to "hevc",
        "decoder-list/1/codec" to "hevc", "decoder-list/1/driver" to "hevc_qsv",
        "decoder-list/2/codec" to "eac3", "decoder-list/2/driver" to "eac3",
    )

    @Test
    fun theLineFromMpvsTrackList() {
        val failure = MpvFormats.describe(Fake(hevcFile), "software decoder")
        assertEquals("HEVC Main 10, 3840 x 2160, 23.976 fps, HDR10, E-AC-3 5.1, Matroska, software decoder", failure.line)
        assertTrue(failure.block, failure.block.contains("Decoders video/hevc: hevc, hevc_qsv"))
        assertTrue(failure.block, failure.block.contains("Decoders audio/eac3: eac3"))
        assertTrue(failure.block, failure.block.startsWith("Format: HEVC Main 10"))
    }

    @Test
    fun noVideoTrackAnAudioOnlyFileAndCoverArt() {
        val audioOnly = mapOf<String, Any>(
            "track-list/count" to 2,
            "track-list/0/type" to "audio", "track-list/0/codec" to "flac", "track-list/0/demux-channel-count" to 2,
            "track-list/1/type" to "video", "track-list/1/codec" to "mjpeg", "track-list/1/albumart" to true,
            "file-format" to "flac",
        )
        assertEquals("FLAC stereo, flac", MpvFormats.describe(Fake(audioOnly)).line)
    }

    @Test
    fun anErrorBeforeAnyFormatIsKnownIsEmpty() {
        assertEquals(MpvFormats.Failure.NONE, MpvFormats.describe(Fake(emptyMap())))
        assertEquals("", MpvFormats.describe(Fake(mapOf("track-list/count" to 0))).line)
    }

    @Test
    fun garbageFromMpvNeverThrows() {
        val long = "x".repeat(2_048)
        val values = mapOf<String, Any>(
            "track-list/count" to 100_000,
            "track-list/0/type" to "video", "track-list/0/codec" to long, "track-list/0/codec-profile" to long,
            "track-list/0/demux-w" to -5, "track-list/0/demux-h" to Int.MAX_VALUE, "track-list/0/demux-fps" to Double.NaN,
            "file-format" to long, "decoder-list/count" to 5_000_000,
        )
        val failure = MpvFormats.describe(Fake(values))
        assertFalse(failure.line.contains('\n'))
        assertTrue(failure.line.length < 400)
        // Properties that throw give nothing rather than an exception.
        val throwing = object : MpvFormats.Props {
            override fun string(name: String): String? = error("mpv is gone")
            override fun int(name: String): Int? = error("mpv is gone")
            override fun double(name: String): Double? = error("mpv is gone")
            override fun bool(name: String): Boolean? = error("mpv is gone")
        }
        assertEquals(MpvFormats.Failure.NONE, MpvFormats.describe(throwing))
    }

    @Test
    fun containersAreNamedFromMpvsList() {
        assertEquals("video/mp4", MpvFormats.containerOf("mov,mp4,m4a,3gp,3g2,mj2"))
        assertEquals("video/x-matroska", MpvFormats.containerOf("matroska,webm"))
        assertEquals("video/avi", MpvFormats.containerOf("avi"))
        assertEquals(null, MpvFormats.containerOf(null))
        assertEquals(null, MpvFormats.containerOf(",,"))
    }

    @Test
    fun copyDetailsCarriesTheFormatAndNoTitle() {
        val failure = MpvFormats.describe(Fake(hevcFile), "software decoder")
        val text = failureDetails(Phase.Failed("This video wouldn't play.", format = failure.line, details = failure.block))
        assertTrue(text, text.contains("This video wouldn't play."))
        assertTrue(text, text.contains("Format: HEVC Main 10, 3840 x 2160"))
        assertTrue(text, text.contains("Decoders video/hevc: hevc, hevc_qsv"))
        // The title and subtitle are not part of the signature: nothing of them can leak in.
        assertFalse(text, text.contains("Night Train"))
        assertEquals("a failure with no format still says what happened", true, failureDetails(Phase.Failed("x")).contains("x"))
    }

    @Test
    fun theErrorSheetShowsTheLine() {
        val failure = MpvFormats.describe(Fake(hevcFile), "software decoder")
        val png = ImageComposeScene(1280, 720, Density(1f)) {
            PlayerTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF203040))) {
                    StatusSheet(
                        phase = Phase.Failed("This device can't play this video's format. A different copy may work.", format = failure.line, details = failure.block),
                        title = "Night Train",
                        subtitle = "S01E04",
                        onRetry = {},
                        onBack = {},
                        onWatchAgain = {},
                        onPlayNext = {},
                        onCancelNext = {},
                    )
                }
            }
        }.use { scene ->
            scene.render(0)
            Thread.sleep(300)
            scene.render(500_000_000)
            Thread.sleep(200)
            scene.render(1_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }
        assertTrue(png.size > 1000)
        File("build/seek-shots").apply { mkdirs() }.resolve("error-format.png").writeBytes(png)
    }
}
