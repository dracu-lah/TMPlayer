package com.tmplayer.player

import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextRenderer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PB2-11: a subtitle track running behind the picture still holds its last lines when the picture
 * and the sound end. Media3 waits for every renderer, so the video ended up to the delay later over
 * a black picture. With the picture and the sound ended, the delayed text counts as ended too.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DelayedTextRendererTest {

    private fun text() = TextRenderer({ }, Looper.getMainLooper())

    /** A renderer in a given state: enabled or not, ended or not. */
    private fun other(enabled: Boolean, ended: Boolean): Renderer = object : ForwardingRenderer(text()) {
        override fun getState(): Int = if (enabled) Renderer.STATE_STARTED else Renderer.STATE_DISABLED
        override fun isEnded(): Boolean = ended
    }

    @Test
    fun `a delayed text track with a line still pending ends with the picture and the sound`() {
        val inner = text()
        assertFalse("a cue still to come: Media3's own renderer is not ended", inner.isEnded)
        val delayed = DelayedTextRenderer(inner, { 30_000_000L }) { true }
        assertTrue(delayed.isEnded)
        // Not before the picture and sound have ended.
        assertFalse(DelayedTextRenderer(text(), { 30_000_000L }) { false }.isEnded)
        // No delay, or an early one: Media3's own answer, as before.
        assertFalse(DelayedTextRenderer(text(), { 0L }) { true }.isEnded)
        assertFalse(DelayedTextRenderer(text(), { -5_000_000L }) { true }.isEnded)
    }

    @Test
    fun `the picture and the sound have ended when every enabled one has, and at least one is enabled`() {
        assertTrue(DelayedTextRenderer.ended(listOf(other(enabled = true, ended = true), other(enabled = false, ended = false))))
        assertFalse(DelayedTextRenderer.ended(listOf(other(enabled = true, ended = true), other(enabled = true, ended = false))))
        assertFalse("subtitles alone are never ended for it", DelayedTextRenderer.ended(listOf(other(enabled = false, ended = true))))
        assertFalse(DelayedTextRenderer.ended(emptyList()))
    }
}
