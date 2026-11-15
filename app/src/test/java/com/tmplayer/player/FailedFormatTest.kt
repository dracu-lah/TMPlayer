package com.tmplayer.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import com.tmplayer.player.demux.FfmpegFormats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** EG-F37: the failed format read from Media3's formats into the one line. */
class FailedFormatTest {

    private fun video(
        mime: String? = MimeTypes.VIDEO_H265,
        codecs: String? = "hvc1.2.4.L150.B0",
        width: Int = 3840,
        height: Int = 2160,
        fps: Float = Format.NO_VALUE.toFloat(),
        transfer: Int = C.COLOR_TRANSFER_ST2084,
        container: String? = MimeTypes.VIDEO_MATROSKA,
    ): Format = Format.Builder()
        .setSampleMimeType(mime)
        .setCodecs(codecs)
        .setWidth(width)
        .setHeight(height)
        .setFrameRate(fps)
        .setContainerMimeType(container)
        .setColorInfo(ColorInfo.Builder().setColorTransfer(transfer).build())
        .build()

    private fun audio(mime: String? = MimeTypes.AUDIO_E_AC3, channels: Int = 6, container: String? = MimeTypes.VIDEO_MATROSKA): Format =
        Format.Builder().setSampleMimeType(mime).setChannelCount(channels).setContainerMimeType(container).build()

    private fun line(of: FailedFormat.Of) = FailedFormat.line(of, "read by FFmpeg", "software decoder")

    @Test
    fun theLineFromTheFormatsInHand() {
        assertEquals(
            "HEVC Main 10 L5.0, 3840 x 2160, HDR10, E-AC-3 5.1, Matroska",
            line(FailedFormat.Of(video(), audio(), softwareDecoder = false)),
        )
    }

    @Test
    fun theFfmpegReaderAndTheSoftwareDecoderAreSaid() {
        val container = FfmpegFormats.CONTAINER_PREFIX + "asf"
        val text = line(
            FailedFormat.Of(
                video(mime = "video/x-ffmpeg-vc1", codecs = null, width = 1920, height = 1080, transfer = C.COLOR_TRANSFER_SDR, container = container),
                audio(mime = "audio/x-ffmpeg-wmapro", channels = 2, container = container),
                softwareDecoder = true,
            ),
        )
        assertEquals("VC-1, 1920 x 1080, WMA stereo, ASF, read by FFmpeg, software decoder", text)
    }

    @Test
    fun noVideoTrackAndAnAudioOnlyFile() {
        assertEquals("E-AC-3 5.1, Matroska", line(FailedFormat.Of(null, audio(), false)))
        assertEquals("H.264, 1280 x 720", line(FailedFormat.Of(video(MimeTypes.VIDEO_H264, null, 1280, 720, transfer = C.COLOR_TRANSFER_SDR, container = null), null, false)))
    }

    @Test
    fun anErrorBeforeAnyFormatIsKnownHasNoLine() {
        assertEquals("", line(FailedFormat.Of(null, null, false)))
        assertEquals("", line(FailedFormat.Of(Format.Builder().build(), Format.Builder().build(), false)))
    }

    @Test
    fun aVeryLongCodecStringIsCut() {
        val text = line(FailedFormat.Of(video(codecs = "hvc1." + "9".repeat(2_048)), null, false))
        assertTrue(text, text.length < 120)
    }

    @Test
    fun onlyAFormatsFailureShowsTheLine() {
        fun error(code: Int) = PlaybackException("x", null, code)
        assertTrue(FailedFormat.relevant(error(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)))
        assertTrue(FailedFormat.relevant(error(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)))
        assertTrue(FailedFormat.relevant(error(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)))
        assertTrue(FailedFormat.relevant(error(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED)))
        assertFalse(FailedFormat.relevant(error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)))
        assertFalse(FailedFormat.relevant(error(PlaybackException.ERROR_CODE_UNSPECIFIED)))
        assertFalse(FailedFormat.relevant(null))
    }
}
