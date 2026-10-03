package com.tmplayer.desktop.player

/**
 * The bytes of one file that is still arriving, as the desktop's media source sees them.
 *
 * In the app this is TDLib's partial file behind [com.tmplayer.player.TdByteWindow]
 * ([TdStreamBytes]); in the tests and the dev harness it is a file being copied at a set rate
 * ([GrowingFileBytes]). [StreamInput] blocks on it and never needs to know which.
 */
interface StreamBytes {

    /** The whole file's length, known before the first read. */
    val size: Long

    /** How many bytes at [position] are on disk right now, or null if that takes a wait. */
    fun cachedAvailable(position: Long): Long?

    /**
     * Suspends until at least one byte at [position] is on disk, aiming the download there if it
     * is somewhere else, and returns how many are. Throws an IOException when it gives up.
     */
    suspend fun awaitBytesAt(position: Long): Long

    /** Reads bytes that [cachedAvailable] or [awaitBytesAt] has already vouched for. */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int

    /**
     * Brings [length] bytes at [offset] down now and hands them back, or null when that did not
     * work out. The download is pointed at that range while it runs, so the caller holds every
     * other waiting read back until this returns.
     */
    suspend fun fetch(offset: Long, length: Int): ByteArray?

    /** Stops listening. Reads after this are not expected. */
    fun close()
}
