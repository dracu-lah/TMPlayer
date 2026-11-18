package com.tmplayer.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The decoder policy (RL5): a decoder name the ladder left out is never offered to Media3 again,
 * the sound's decoders are never touched, the queueing mode follows the path and the device list,
 * and decoder fallback stays on in every policy.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DecoderPolicyTest {

    private fun codec(name: String, mime: String, hardware: Boolean = true): MediaCodecInfo =
        MediaCodecInfo.newInstance(name, mime, mime, null, hardware, !hardware, hardware, false, false)

    /** A fake device codec list: two HEVC decoders, one AVC, one AAC. Counts the questions asked. */
    private class FakeSelector(private val list: Map<String, List<MediaCodecInfo>>) : MediaCodecSelector {
        var asked = 0
        override fun getDecoderInfos(mimeType: String, requiresSecureDecoder: Boolean, requiresTunnelingDecoder: Boolean): List<MediaCodecInfo> {
            asked++
            return list[mimeType].orEmpty()
        }
    }

    private val device = FakeSelector(
        mapOf(
            MimeTypes.VIDEO_H265 to listOf(codec("OMX.amlogic.hevc.decoder.awesome2", MimeTypes.VIDEO_H265), codec("c2.android.hevc.decoder", MimeTypes.VIDEO_H265, hardware = false)),
            MimeTypes.VIDEO_H264 to listOf(codec("OMX.amlogic.avc.decoder.awesome2", MimeTypes.VIDEO_H264)),
            MimeTypes.AUDIO_AAC to listOf(codec("OMX.amlogic.hevc.decoder.awesome2", MimeTypes.AUDIO_AAC)),
        ),
    )

    @Test
    fun anExcludedNameIsNeverOffered() {
        val selector = DecoderPolicy(excluded = setOf("omx.AMLOGIC.hevc.decoder.awesome2")).selector(device)
        val hevc = selector.getDecoderInfos(MimeTypes.VIDEO_H265, false, false).map { it.name }
        assertEquals("the next listed decoder takes the picture", listOf("c2.android.hevc.decoder"), hevc)
        // Secure and tunneling asks go through the same filter.
        assertFalse(selector.getDecoderInfos(MimeTypes.VIDEO_H265, true, true).any { it.name.startsWith("OMX.amlogic.hevc") })
    }

    @Test
    fun theSoundAndOtherFormatsAreLeftAlone() {
        val selector = DecoderPolicy(excluded = setOf("OMX.amlogic.hevc.decoder.awesome2")).selector(device)
        assertEquals(1, selector.getDecoderInfos(MimeTypes.AUDIO_AAC, false, false).size)
        assertEquals(1, selector.getDecoderInfos(MimeTypes.VIDEO_H264, false, false).size)
    }

    @Test
    fun leavingEveryDecoderOutKeepsTheList() {
        val selector = DecoderPolicy(excluded = setOf("OMX.amlogic.avc.decoder.awesome2")).selector(device)
        assertEquals(
            "an exclusion alone never leaves a picture with no decoder",
            listOf("OMX.amlogic.avc.decoder.awesome2"),
            selector.getDecoderInfos(MimeTypes.VIDEO_H264, false, false).map { it.name },
        )
    }

    @Test
    fun noExclusionIsTheStockList() {
        assertSame(device, DecoderPolicy.Default.selector(device))
    }

    @Test
    fun queueingFollowsThePathThenTheList() {
        assertEquals(DecoderPolicy.Async.On, DecoderPolicy.of(RemedyPath(asyncFlipped = true), null, 28).async)
        assertEquals(DecoderPolicy.Async.On, DecoderPolicy.of(RemedyPath(), knownAsync = true, sdk = 28).async)
        assertEquals("Media3 already queues asynchronously from API 31", DecoderPolicy.Async.Default, DecoderPolicy.of(RemedyPath(), knownAsync = true, sdk = 33).async)
        assertEquals(DecoderPolicy.Async.Off, DecoderPolicy.of(RemedyPath(), knownAsync = false, sdk = 33).async)
        assertEquals(DecoderPolicy.Async.Default, DecoderPolicy.of(RemedyPath(), knownAsync = false, sdk = 28).async)
        assertEquals(DecoderPolicy.Async.Default, DecoderPolicy.of(RemedyPath(), null, 0).async)
    }

    @Test
    fun thePathCarriesOver() {
        val policy = DecoderPolicy.of(
            RemedyPath(software = true, excluded = setOf("c2.mtk.hevc.decoder"), tunnelingOff = true, audioSoftware = true),
            knownAsync = null,
            sdk = 30,
        )
        assertEquals(setOf("c2.mtk.hevc.decoder"), policy.excluded)
        assertTrue(policy.softwareVideo && policy.softwareAudio && policy.tunnelingOff)
        assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER, policy.extensionMode(preferSoftwareVideo = false))
        assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON, DecoderPolicy.Default.extensionMode(preferSoftwareVideo = false))
        assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER, DecoderPolicy.Default.extensionMode(preferSoftwareVideo = true))
        val line = policy.describe()
        assertTrue(line, line.contains("fallback on") && line.contains("left out c2.mtk.hevc.decoder") && line.contains("tunneling off"))
    }

    /** The stutter report's "why this decoder" class: fixed words, no decoder name, kept whole by the report's check. */
    @Test
    fun theShortClassSaysThePathInFixedWords() {
        assertEquals("async_default+pic_hw+snd_hw+tun_auto+out_0", DecoderPolicy.Default.shortClass())
        val changed = DecoderPolicy(
            excluded = setOf("c2.mtk.hevc.decoder", "OMX.MS.HEVC.Decoder"),
            async = DecoderPolicy.Async.On,
            softwareVideo = true,
            tunnelingOff = true,
        )
        assertEquals("async_on+pic_sw+snd_hw+tun_off+out_2", changed.shortClass())
        assertFalse(changed.shortClass().contains("mtk"))
        for (async in DecoderPolicy.Async.entries) {
            for (sw in listOf(true, false)) {
                val text = DecoderPolicy(async = async, softwareVideo = sw, softwareAudio = !sw, excluded = (1..20).map { "d$it" }.toSet()).shortClass()
                assertEquals(text, com.tmplayer.data.StutterReport.policyOf(text))
            }
        }
    }

    @Test
    fun theFactoryTakesThePolicyAndKeepsFallbackOn() {
        // Every policy builds; the renderers then ask the policy's codec list, not the stock one.
        for (async in DecoderPolicy.Async.entries) {
            val factory = DecoderPolicy(excluded = setOf("x"), async = async).applyTo(NextRenderersFactory(RuntimeEnvironment.getApplication()))
            val renderers = factory.createRenderers(
                android.os.Handler(android.os.Looper.getMainLooper()),
                object : androidx.media3.exoplayer.video.VideoRendererEventListener {},
                object : androidx.media3.exoplayer.audio.AudioRendererEventListener {},
                { },
                { },
            )
            assertTrue(renderers.any { it is androidx.media3.exoplayer.video.MediaCodecVideoRenderer })
        }
        val fallback = DefaultRenderersFactory::class.java.getDeclaredField("enableDecoderFallback").apply { isAccessible = true }
        val factory = DecoderPolicy.Default.applyTo(DefaultRenderersFactory(RuntimeEnvironment.getApplication()))
        assertTrue("decoder fallback stays on", fallback.getBoolean(factory))
        val selector = DefaultRenderersFactory::class.java.getDeclaredField("mediaCodecSelector").apply { isAccessible = true }
        val excluding = DecoderPolicy(excluded = setOf("x")).applyTo(DefaultRenderersFactory(RuntimeEnvironment.getApplication()))
        assertTrue(selector.get(excluding) is ExcludingCodecSelector)
    }
}
