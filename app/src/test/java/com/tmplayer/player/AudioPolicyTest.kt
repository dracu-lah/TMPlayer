package com.tmplayer.player

import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegVideoRenderer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.nio.ByteBuffer

/** A Dolby Digital Plus 5.1 soundtrack, as it goes to a receiver untouched. */
@UnstableApi
internal val EAC3: Format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3).setChannelCount(6).setSampleRate(48_000).build()

/** Plain PCM, what any decoder hands the output. */
@UnstableApi
internal val PCM: Format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT)
    .setChannelCount(2).setSampleRate(48_000).build()

/**
 * A fake sound output whose AudioTrack never starts: it takes [configure][AudioSink.configure],
 * then the first buffer throws [AudioSink.InitializationException] for the format it was configured
 * with, the way Media3's sink does when the device refuses the output. Every other call answers
 * the type's default.
 */
@UnstableApi
internal fun refusingSink(): AudioSink {
    var configured: Format? = null
    return Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
        when (method.name) {
            "configure" -> {
                configured = args?.firstOrNull() as? Format
                null
            }
            "handleBuffer" -> throw AudioSink.InitializationException(
                AudioTrack.STATE_UNINITIALIZED, 48_000, 0, 0, 0, configured ?: PCM, false, null,
            )
            "supportsFormat" -> true
            "getFormatSupport" -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                else -> null
            }
        }
    } as AudioSink
}

/**
 * Plays [format] into the refusing sink and returns the player error that comes of it, built the
 * way Media3's audio renderer builds it from the sink's exception.
 */
@UnstableApi
internal fun refusedOutput(format: Format): ExoPlaybackException {
    val sink = refusingSink()
    sink.configure(format, 0, null)
    val refused = try {
        sink.handleBuffer(ByteBuffer.allocate(16), 0, 1)
        null
    } catch (e: AudioSink.InitializationException) {
        e
    }
    requireNotNull(refused) { "the fake sink always refuses" }
    return ExoPlaybackException.createForRenderer(
        refused, "MediaCodecAudioRenderer", 1, refused.format, C.FORMAT_HANDLED, refused.isRecoverable,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    )
}

/**
 * The sound's ladder (RL8): an output that refuses a bitstream teaches the class to start in PCM,
 * one that refuses PCM teaches nothing, and the app's decoder first moves only the sound.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioPolicyTest {

    @Test
    fun aRefusedBitstreamTeachesPcm() {
        val error = refusedOutput(EAC3)
        assertEquals(MimeTypes.AUDIO_E_AC3, AudioPolicy.refusedFormat(error)?.sampleMimeType)
        assertTrue(AudioPolicy.bitstream(AudioPolicy.refusedFormat(error)))
        assertTrue(AudioPolicy.teachesSoftware(error, takes = true))
        assertTrue(AudioPolicy.softwareHelps(error, takes = true))
        assertFalse("without FFmpeg for the format nothing is learned", AudioPolicy.teachesSoftware(error, takes = false))
    }

    @Test
    fun aRefusedPcmOutputTeachesNothing() {
        val error = refusedOutput(PCM)
        assertFalse(AudioPolicy.bitstream(AudioPolicy.refusedFormat(error)))
        assertFalse(AudioPolicy.teachesSoftware(error, takes = true))
        assertFalse("FFmpeg's PCM would be refused the same", AudioPolicy.softwareHelps(error, takes = true))
    }

    @Test
    fun otherFailuresAreNotTheOutputs() {
        val decoding = ExoPlaybackException.createForRenderer(
            IllegalStateException("fake codec"), "MediaCodecAudioRenderer", 1, EAC3, C.FORMAT_HANDLED, false,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
        )
        assertNull(AudioPolicy.refusedFormat(decoding))
        assertFalse(AudioPolicy.teachesSoftware(decoding, takes = true))
        assertTrue("a failing decoder still may go to FFmpeg's", AudioPolicy.softwareHelps(decoding, takes = true))
        assertFalse(AudioPolicy.bitstream(null))
        assertFalse(AudioPolicy.softwareTakes("video/hevc"))
        assertFalse(AudioPolicy.softwareTakes(null))
    }

    @Test
    fun rendererModes() {
        assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER, AudioPolicy.rendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON, softwareFirst = true))
        assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON, AudioPolicy.rendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER, softwareFirst = false))
        assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON, AudioPolicy.rendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON, softwareFirst = false))
        assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF, AudioPolicy.rendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF, softwareFirst = true))
    }

    /** The factory as the player builds it: only the sound's renderers take [AudioPolicy.rendererMode]. */
    private fun renderers(softwareAudio: Boolean): List<Renderer> {
        val factory = object : NextRenderersFactory(RuntimeEnvironment.getApplication()) {
            override fun buildAudioRenderers(
                context: android.content.Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: AudioSink,
                eventHandler: Handler,
                eventListener: AudioRendererEventListener,
                out: ArrayList<Renderer>,
            ) = super.buildAudioRenderers(
                context, AudioPolicy.rendererMode(extensionRendererMode, softwareAudio), mediaCodecSelector,
                enableDecoderFallback, audioSink, eventHandler, eventListener, out,
            )
        }
        factory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        return factory.createRenderers(
            Handler(Looper.getMainLooper()),
            object : androidx.media3.exoplayer.video.VideoRendererEventListener {},
            object : AudioRendererEventListener {},
            { },
            { },
        ).toList()
    }

    @Test
    fun theSoundGoesToFfmpegFirstAndThePictureStays() {
        val pcm = renderers(softwareAudio = true)
        assertTrue("FFmpeg's sound first", pcm.indexOfFirst { it is FfmpegAudioRenderer } < pcm.indexOfFirst { it is MediaCodecAudioRenderer })
        assertTrue("the device's picture first", pcm.indexOfFirst { it is MediaCodecVideoRenderer } < pcm.indexOfFirst { it is FfmpegVideoRenderer })
        val stock = renderers(softwareAudio = false)
        assertTrue(stock.indexOfFirst { it is MediaCodecAudioRenderer } < stock.indexOfFirst { it is FfmpegAudioRenderer })
    }

    /** WP-24: the rule behind "Not applied": only a bitstream opened on the output is beyond Volume boost and Night mode. */
    @Test
    fun volumeBoostAndNightModeReachPcmAndNotABitstream() {
        for (pcm in listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT)) {
            assertFalse("PCM $pcm is shaped", AudioPolicy.bitstreamEncoding(pcm))
            assertTrue(AudioPolicy.boostReaches(AudioPolicy.bitstreamEncoding(pcm)))
        }
        for (bits in listOf(C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC, C.ENCODING_DTS, C.ENCODING_DTS_HD, C.ENCODING_DOLBY_TRUEHD)) {
            assertTrue("bitstream $bits goes to the speakers untouched", AudioPolicy.bitstreamEncoding(bits))
            assertFalse(AudioPolicy.boostReaches(AudioPolicy.bitstreamEncoding(bits)))
        }
        // An output that did not say what it opened with is not called a bitstream.
        assertFalse(AudioPolicy.bitstreamEncoding(C.ENCODING_INVALID))
    }

    @Test
    fun theDetailLinesSayNotAppliedOnlyForABoostThatIsOnAndBypassed() {
        val line = { on: Boolean, bypassed: Boolean -> soundDetail(on, "On", bypassed, "Not applied", "Off") }
        assertEquals("Off", line(false, false))
        assertEquals("Off", line(false, true))
        assertEquals("On", line(true, false))
        assertEquals("Not applied", line(true, true))
    }
}
