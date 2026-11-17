package android.media

/** Android's codec; none runs on iOS (the engine decodes), its flags are here for the player's code. */
class MediaCodec private constructor() {
    /** What a codec throws when it fails, Android's: none runs on iOS, so none is made. */
    open class CodecException private constructor() : IllegalStateException() {
        val isRecoverable: Boolean get() = false
        val isTransient: Boolean get() = false
        val errorCode: Int get() = 0
    }

    companion object {
        const val BUFFER_FLAG_KEY_FRAME = 1
        const val BUFFER_FLAG_CODEC_CONFIG = 2
        const val BUFFER_FLAG_END_OF_STREAM = 4
        const val BUFFER_FLAG_PARTIAL_FRAME = 8
        const val VIDEO_SCALING_MODE_SCALE_TO_FIT = 1
        const val VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING = 2
    }
}
