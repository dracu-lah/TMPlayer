package androidx.media3.container

/**
 * Media3's NAL unit helpers. Android's player reads a Dolby Vision video's base layer codecs here
 * for its own decoder; on iOS the engine decodes it, so the answer is the one Media3 gives for
 * initialisation data it cannot parse: an exception the caller catches and goes on without.
 */
object NalUnitUtil {
    fun getH265BaseLayerCodecsString(initializationData: List<ByteArray>): String =
        throw IllegalArgumentException("No HEVC parameter sets to read on iOS")
}
