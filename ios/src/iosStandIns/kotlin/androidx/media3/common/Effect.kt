package androidx.media3.common

/** An effect on a video's frames, Media3's. */
interface Effect

/** An error in Media3's video frame pipeline. */
class VideoFrameProcessingException(message: String?, cause: Throwable? = null) : Exception(message, cause) {
    constructor(cause: Throwable) : this(cause.message, cause)

    /** The error with the frame it happened on, Media3's. */
    constructor(cause: Throwable, presentationTimeUs: Long) : this(cause.message, cause) {
        this.presentationTimeUs = presentationTimeUs
    }

    var presentationTimeUs: Long = C_TIME_UNSET
        private set

    private companion object {
        const val C_TIME_UNSET = Long.MIN_VALUE + 1
    }
}
