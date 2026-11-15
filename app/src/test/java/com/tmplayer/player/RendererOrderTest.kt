package com.tmplayer.player

import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegVideoRenderer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The renderer order the automatic software fallback stands on (C1): the device's decoder first and
 * the FFmpeg extension's right behind it, so a format the device has no decoder for, refuses or
 * fails to start with is taken by the extension with no setting ([DecoderSwitch] turns the failed
 * side off when a decoder breaks after the track was chosen), and with "Prefer software video"
 * the extension's video renderer is ahead of the device's. Same factory class PlayerActivity builds
 * on; its own overrides (sink, level-lenient wrapper) leave the order as Media3 gives it.
 *
 * What stays a device check: whether a given format really reaches the extension depends on the
 * device's codec list, which a JVM test has no real one of.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RendererOrderTest {

    private fun build(mode: Int): List<Renderer> {
        val context = RuntimeEnvironment.getApplication()
        val handler = Handler(Looper.getMainLooper())
        val factory = NextRenderersFactory(context)
        factory.setExtensionRendererMode(mode)
        factory.setEnableDecoderFallback(true)
        return factory.createRenderers(
            handler,
            object : VideoRendererEventListener {},
            object : AudioRendererEventListener {},
            TextOutput { },
            MetadataOutput { },
        ).toList()
    }

    private inline fun <reified T : Renderer> List<Renderer>.indexOfType(): Int = indexOfFirst { it is T }

    @Test
    fun deviceDecoderIsAheadOfTheExtensionByDefault() {
        val renderers = build(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val video = renderers.indexOfType<MediaCodecVideoRenderer>()
        val ffmpegVideo = renderers.indexOfType<FfmpegVideoRenderer>()
        val audio = renderers.indexOfType<MediaCodecAudioRenderer>()
        val ffmpegAudio = renderers.indexOfType<FfmpegAudioRenderer>()
        assertTrue("both video renderers are built", video >= 0 && ffmpegVideo >= 0)
        assertTrue("both audio renderers are built", audio >= 0 && ffmpegAudio >= 0)
        assertTrue("device video before FFmpeg video", video < ffmpegVideo)
        assertTrue("device audio before FFmpeg audio", audio < ffmpegAudio)
    }

    @Test
    fun preferSoftwareVideoPutsTheExtensionFirst() {
        val renderers = build(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        val video = renderers.indexOfType<MediaCodecVideoRenderer>()
        val ffmpegVideo = renderers.indexOfType<FfmpegVideoRenderer>()
        assertTrue("both video renderers are built", video >= 0 && ffmpegVideo >= 0)
        assertTrue("FFmpeg video before device video", ffmpegVideo < video)
    }

    @Test
    fun theExtensionIsAlwaysBuiltWhenNotTurnedOff() {
        for (mode in listOf(
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
        )) {
            val renderers = build(mode)
            assertTrue(renderers.any { it is FfmpegVideoRenderer })
            assertTrue(renderers.any { it is FfmpegAudioRenderer })
        }
    }
}
