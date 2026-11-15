package com.tmplayer.cast

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import com.tmplayer.data.FormFactor
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodecList
import java.io.File

/** The phone's transcode parts that run without a codec: the ability read, the TV rule, the data source, the health. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class AndroidTranscodeTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun reset() {
        AndroidTranscodeAbility.forget()
        FormFactor.override(false)
        ShadowMediaCodecList.reset()
    }

    private fun codec(name: String, mime: String, encoder: Boolean, w: Int = 1920, h: Int = 1080) =
        MediaCodecInfoBuilder.newBuilder()
            .setName(name)
            .setIsEncoder(encoder)
            .setCapabilities(
                MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                    .setMediaFormat(if (mime.startsWith("video/")) MediaFormat.createVideoFormat(mime, w, h) else MediaFormat.createAudioFormat(mime, 48_000, 2))
                    .setIsEncoder(encoder)
                    .apply { if (mime.startsWith("video/")) setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) }
                    .build(),
            )
            .build()

    @Test
    fun `a tv never transcodes`() {
        ShadowMediaCodecList.addCodec(codec("c2.vendor.avc.encoder", "video/avc", true))
        FormFactor.override(true)
        assertNull(AndroidTranscodeAbility.probe(context))
        assertFalse(AndroidTranscodeAbility.noEncoder)
    }

    @Test
    fun `a phone without an h264 encoder offers no transcode and says why`() {
        FormFactor.override(false)
        ShadowMediaCodecList.addCodec(codec("c2.vendor.hevc.decoder", "video/hevc", false))
        assertNull(AndroidTranscodeAbility.probe(context))
        assertTrue(AndroidTranscodeAbility.noEncoder)
    }

    @Test
    fun `a phone's encoder and decoders make its ability`() {
        FormFactor.override(false)
        ShadowMediaCodecList.addCodec(codec("c2.vendor.avc.encoder", "video/avc", true))
        ShadowMediaCodecList.addCodec(codec("c2.vendor.hevc.decoder", "video/hevc", false))
        ShadowMediaCodecList.addCodec(codec("c2.vendor.mpeg2.decoder", "video/mpeg2", false))
        ShadowMediaCodecList.addCodec(codec("c2.android.aac.decoder", "audio/mp4a-latm", false))
        val a = AndroidTranscodeAbility.probe(context)
        assertNotNull(a)
        a!!
        assertEquals(setOf(Codecs.HEVC, Codecs.MPEG2), a.videoDecoders)
        assertTrue(Codecs.AAC in a.audioDecoders!!)
        assertTrue(Codecs.PCM in a.audioDecoders!!)
        assertFalse(a.ac3)
        assertEquals(setOf(TranscodeContainer.Fmp4), a.containers)
        // Media3's fragmented MP4 muxer carries AAC and Opus as they are, not AC-3.
        assertFalse(a.passesAudio(Codecs.AC3))
        assertTrue(a.passesAudio(Codecs.AAC))
        assertTrue(a.maxWidth <= 1920 && a.maxHeight <= 1080)
    }

    @Test
    fun `the data source reads the cast source from any byte`() {
        val bytes = ByteArray(10_000) { (it * 13).toByte() }
        val file = File.createTempFile("cast", ".bin").apply { writeBytes(bytes); deleteOnExit() }
        val ds = CastSourceDataSource(FileCastSource(file))
        val uri = Uri.parse("tmplayer-cast://video/v.mkv")
        assertEquals(9_000L, ds.open(DataSpec.Builder().setUri(uri).setPosition(1_000).build()))
        assertEquals(uri, ds.uri)
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(777)
        while (true) {
            val n = ds.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, n)
        }
        ds.close()
        assertArrayEquals(bytes.copyOfRange(1_000, 10_000), out.toByteArray())
        // A bounded read stops at its length.
        assertEquals(10L, ds.open(DataSpec.Builder().setUri(uri).setPosition(5).setLength(10).build()))
        val ten = ByteArray(64)
        assertEquals(10, ds.read(ten, 0, 64))
        assertEquals(C.RESULT_END_OF_INPUT, ds.read(ten, 0, 64))
        ds.close()
        assertNull(ds.uri)
    }

    @Test
    fun `a position past the end is refused, not read`() {
        val file = File.createTempFile("cast", ".bin").apply { writeBytes(ByteArray(10)); deleteOnExit() }
        val ds = CastSourceDataSource(FileCastSource(file))
        val failed = runCatching { ds.open(DataSpec.Builder().setUri(Uri.parse("tmplayer-cast://v")).setPosition(11).build()) }
        assertTrue(failed.isFailure)
    }

    @Test
    fun `the health reading never throws`() {
        val h = AndroidDeviceHealth(context).read()
        assertTrue(h.batteryPercent == null || h.batteryPercent in 0..100)
    }
}
