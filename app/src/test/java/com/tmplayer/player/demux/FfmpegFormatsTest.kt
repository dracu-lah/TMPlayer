package com.tmplayer.player.demux

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * The Media3 formats of what libavformat reads ([FfmpegFormats]) and the checks on what the native
 * side hands over ([ContainerValues]), including garbage: arrays of any length and value, names
 * with any characters, indexes out of order. Nothing may throw, and every value stays in range.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FfmpegFormatsTest {

    private fun stream(type: Int, decoder: String?, codec: String?, extra: ByteArray? = null) =
        StreamInfo(0, type, decoder, codec, null, width = 320, height = 240, sampleRate = 44100, channels = 2,
            blockAlign = 743, bitsPerCodedSample = 16, codecTag = 0x33564D57, extraData = extra)

    @Test
    fun oldCodecsAreNamedByTheirFfmpegDecoderWithTheExtensionsLayout() {
        val wmv = FfmpegFormats.format(stream(StreamInfo.TYPE_VIDEO, "wmv3", "wmv3", byteArrayOf(1, 2, 3)), "asf")!!
        assertEquals("video/x-ffmpeg-wmv3", wmv.sampleMimeType)
        assertEquals("video/x-ffmpeg-container-asf", wmv.containerMimeType)
        assertArrayEquals(byteArrayOf(1, 2, 3), wmv.initializationData[0])
        val params = ByteBuffer.wrap(wmv.initializationData[1])
        assertEquals(16, params.int)
        assertEquals(0x33564D57, params.int)
        assertEquals(320, wmv.width)

        val cook = FfmpegFormats.format(stream(StreamInfo.TYPE_AUDIO, "cook", "cook"), "rm")!!
        assertEquals("audio/x-ffmpeg-cook", cook.sampleMimeType)
        assertEquals(0, cook.initializationData[0].size)
        val audio = ByteBuffer.wrap(cook.initializationData[1])
        assertEquals(743, audio.int)
        assertEquals(16, audio.int)
        assertEquals(44100, cook.sampleRate)
        assertEquals(2, cook.channelCount)
    }

    /**
     * The decoders of the real sample files (scripts/media3ext/bench/SAMPLES.md): each one's name,
     * as libavformat gives it for a stream, becomes the MIME type the extension's FfmpegLibrary
     * resolves back to that decoder, and is a name the native side may hand over.
     */
    @Test
    fun everyDecoderOfTheRealSamplesGetsItsOwnMimeType() {
        val video = listOf("wmv3", "vc1", "svq3", "vp3", "vp6", "vp6f", "rv10", "rv30", "rv40", "mjpegb", "mjpeg", "h263", "h263p", "mpeg1video")
        val audio = listOf(
            "wmapro", "wmalossless", "wmav2", "mp1", "cook", "real_288", "qcelp", "sipr", "atrac3", "atrac3plus", "ape",
            "nellymoser", "adpcm_ima_smjpeg", "aac", "g723_1", "siren",
        )
        for (name in video) {
            assertTrue(ContainerValues.isName(name))
            val mime = FfmpegFormats.sampleMimeType(stream(StreamInfo.TYPE_VIDEO, name, name))
            // MPEG-2 is the device's; every other one is named by its decoder.
            assertEquals("video/x-ffmpeg-$name", mime)
            assertEquals(name, FfmpegFormats.codecLabel(mime!!))
        }
        for (name in audio) {
            assertTrue(ContainerValues.isName(name))
            val mime = FfmpegFormats.sampleMimeType(stream(StreamInfo.TYPE_AUDIO, name, name))
            // MP1 is Media3's own layer 1 type; the rest are named by their decoder.
            assertEquals(if (name == "mp1") MimeTypes.AUDIO_MPEG_L1 else "audio/x-ffmpeg-$name", mime)
        }
        // Vivo's G.723.1 and Siren (decoders of the build since tm4) are named by their codec name.
        assertEquals("audio/x-ffmpeg-g723_1", FfmpegFormats.sampleMimeType(stream(StreamInfo.TYPE_AUDIO, null, "g723_1")))
    }

    @Test
    fun theDevicesMpeg2DecoderGetsTheSequenceHeaderAsCodecData() {
        val header = byteArrayOf(0, 0, 1, 0xB3.toByte(), 0x16, 0x01, 0x20, 0x13, 0xFF.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x18)
        val with = FfmpegFormats.format(stream(StreamInfo.TYPE_VIDEO, "mpeg2video", "mpeg2video", header), "mpeg")!!
        assertEquals(MimeTypes.VIDEO_MPEG2, with.sampleMimeType)
        assertEquals(1, with.initializationData.size)
        assertArrayEquals(header, with.initializationData[0])
        // No header found: none handed over, as before.
        assertTrue(FfmpegFormats.format(stream(StreamInfo.TYPE_VIDEO, "mpeg2video", "mpeg2video"), "mpeg")!!.initializationData.isEmpty())
    }

    @Test
    fun hardwareAndPassthroughCodecsKeepMedia3sMimeTypes() {
        assertEquals(MimeTypes.VIDEO_MPEG2, FfmpegFormats.sampleMimeType(stream(StreamInfo.TYPE_VIDEO, "mpeg2video", "mpeg2video")))
        assertEquals(MimeTypes.AUDIO_AC3, FfmpegFormats.sampleMimeType(stream(StreamInfo.TYPE_AUDIO, "ac3", "ac3")))
        assertEquals(MimeTypes.AUDIO_DTS, FfmpegFormats.sampleMimeType(stream(StreamInfo.TYPE_AUDIO, "dca", "dts")))
        assertEquals(MimeTypes.AUDIO_MPEG_L2, FfmpegFormats.sampleMimeType(stream(StreamInfo.TYPE_AUDIO, "mp2", "mp2")))
        // H.264 with its parameter sets in the packets goes to the device; an avcC one to FFmpeg.
        assertEquals(MimeTypes.VIDEO_H264, FfmpegFormats.sampleMimeType(stream(StreamInfo.TYPE_VIDEO, "h264", "h264")))
        assertEquals("video/x-ffmpeg-h264", FfmpegFormats.sampleMimeType(stream(StreamInfo.TYPE_VIDEO, "h264", "h264", byteArrayOf(1, 0x64, 0, 0x1f))))
        // Standard ones carry no FFmpeg layout.
        assertTrue(FfmpegFormats.format(stream(StreamInfo.TYPE_AUDIO, "ac3", "ac3"), "mpeg")!!.initializationData.isEmpty())
    }

    @Test
    fun aCodecWithoutADecoderIsListedAndPicturesAndDataAreNot() {
        assertEquals("video/x-ffmpeg-indeo5", FfmpegFormats.sampleMimeType(stream(StreamInfo.TYPE_VIDEO, null, "indeo5")))
        assertNull(FfmpegFormats.sampleMimeType(stream(StreamInfo.TYPE_OTHER, null, "bin_data")))
        assertNull(FfmpegFormats.sampleMimeType(stream(StreamInfo.TYPE_VIDEO, null, null)))
        val cover = StreamInfo(0, StreamInfo.TYPE_VIDEO, "mjpeg", "mjpeg", null, attachedPicture = true)
        assertNull(FfmpegFormats.format(cover, "ape"))
    }

    @Test
    fun theDetailsNameTheContainerAndTheDecoder() {
        assertEquals("asf", FfmpegFormats.demuxerOf("video/x-ffmpeg-container-asf"))
        assertNull(FfmpegFormats.demuxerOf("video/x-matroska"))
        assertNull(FfmpegFormats.demuxerOf("video/x-ffmpeg-container-../evil"))
        assertNull(FfmpegFormats.demuxerOf(null))
        assertEquals("ASF (Windows Media)", FfmpegFormats.containerLabel("asf"))
        assertEquals("MPEG-PS", FfmpegFormats.containerLabel("mpeg"))
        assertEquals("XYZ", FfmpegFormats.containerLabel("xyz"))
        assertEquals("wmv2", FfmpegFormats.codecLabel("video/x-ffmpeg-wmv2"))
        assertEquals("avc", FfmpegFormats.codecLabel(MimeTypes.VIDEO_H264))
    }

    @Test
    fun streamValuesAreBounded() {
        val values = longArrayOf(0, 1L shl 40, -5, 10_000_000, 1000, -1, 1 shl 30, 999, 0x7FFFFFFFFFL, 30000, 1001, -1, 1, 0)
        val s = ContainerValues.stream(3, values, "wmv2", "wmv2", "en-GB", ByteArray(4))!!
        assertEquals(0, s.width)
        assertEquals(0, s.height)
        assertEquals(0, s.sampleRate)
        assertEquals(0, s.channels)
        assertEquals(0, s.bitRate)
        assertEquals(0, s.bitsPerCodedSample)
        assertEquals(29.97f, s.frameRate, 0.01f)
        assertTrue(s.default)
        assertEquals("en-GB", s.language)
        assertNull(ContainerValues.stream(0, longArrayOf(0, 1, 2), "wmv2", null, null, null))
        assertNull(ContainerValues.stream(0, LongArray(14) { 9 }, "wmv2", null, null, null))
        assertNull(ContainerValues.stream(0, LongArray(14), "WMV2;rm -rf", null, null, null)!!.decoder)
    }

    @Test
    fun indexPairsOutOfOrderOrOutsideTheFileAreDropped() {
        val (times, positions) = ContainerValues.index(longArrayOf(0, 100, 2, 50, 1, 200, 5, -1, 6, 900, 7, 5000, 8), 1000)
        assertArrayEquals(longArrayOf(0, 2, 6), times)
        assertArrayEquals(longArrayOf(100, 50, 900), positions)
        assertEquals(0, ContainerValues.index(null, 1000).first.size)
    }

    @Test
    fun garbageNeverThrows() {
        val random = Random(20261115)
        repeat(5000) {
            val values = LongArray(random.nextInt(0, 20)) { random.nextLong() }
            val text = if (random.nextBoolean()) null else String(CharArray(random.nextInt(0, 60)) { random.nextInt(0, 0x3000).toChar() })
            val extra = if (random.nextBoolean()) null else ByteArray(random.nextInt(0, 64)) { random.nextInt().toByte() }
            val s = ContainerValues.stream(random.nextInt(0, 40), values.takeIf { random.nextInt(10) > 0 }, text, text, text, extra)
            if (s != null) {
                assertTrue(s.width in 0..16_384 && s.height in 0..16_384 && s.channels in 0..64 && s.sampleRate in 0..768_000)
                FfmpegFormats.format(s, text ?: "asf")
            }
            val info = LongArray(random.nextInt(0, 8)) { random.nextLong() }
            ContainerValues.streamCount(info)
            val size = random.nextLong(-1, 1L shl 40)
            val container = ContainerValues.container(text ?: "asf", text, info, listOfNotNull(s), LongArray(random.nextInt(0, 30)) { random.nextLong() }, size)
            if (container != null) {
                val map = ContainerSeekMap(container, size)
                if (map.isSeekable) {
                    val points = map.getSeekPoints(random.nextLong())
                    assertTrue(points.first.position >= 0)
                }
            }
        }
    }
}
