package androidx.media3.common.util

/** Media3's runnable that may throw [E]. */
fun interface ThrowingRunnable<E : Exception> {
    fun run()
}
