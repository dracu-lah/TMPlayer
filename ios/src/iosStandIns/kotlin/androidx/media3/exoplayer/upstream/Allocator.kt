package androidx.media3.exoplayer.upstream

/** The pool of buffers ExoPlayer loads into, Media3's; the engine keeps its own on iOS, so none is held. */
interface Allocator {
    val totalBytesAllocated: Int

    /** The one on iOS: nothing is allocated. */
    object None : Allocator {
        override val totalBytesAllocated: Int get() = 0
    }
}
