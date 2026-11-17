package androidx.media3.common.util

/** Media3's Consumer. */
fun interface Consumer<T> {
    fun accept(t: T)
}
