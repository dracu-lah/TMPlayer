package androidx.media3.exoplayer.mediacodec

import androidx.media3.common.Format
import androidx.media3.common.util.ThrowingRunnable
import java.nio.ByteBuffer

/**
 * Media3's wrapper around a MediaCodec, which Android's player wraps again to mask HDR10+. No
 * MediaCodec runs on iOS, the engine decoding instead, so nothing makes one; the types are here
 * for the player's code as upstream wrote it.
 */
interface MediaCodecAdapter {
    class Configuration(val codecInfo: MediaCodecInfo, val format: Format)

    fun interface Factory {
        fun createAdapter(configuration: Configuration): MediaCodecAdapter
    }

    fun interface OnBufferAvailableListener {
        fun onInputBufferAvailable()
    }

    fun getInputBuffer(index: Int): ByteBuffer?

    fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int)

    fun <E : Exception> useBuffer(runnable: ThrowingRunnable<E>) = runnable.run()

    fun registerOnBufferAvailableListener(listener: OnBufferAvailableListener): Boolean = false

    fun flush()

    fun release()
}
