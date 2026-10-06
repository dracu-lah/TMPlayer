package com.tmplayer.online

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OpenSubtitles' file hash: the size plus every little endian 64 bit word of the first and the
 * last 64 KiB, as 16 hex digits. Two copies of the same release have the same hash whatever they
 * are called, which makes it the one match that cannot pick the wrong cut of a film.
 *
 * Only the two ends are read, so a Telegram video can be hashed while it is still streaming, once
 * its first and last 64 KiB are on the disk.
 */
object MovieHash {

    const val CHUNK: Int = 64 * 1024

    /** Whether a file of [size] bytes can be hashed: one shorter than a chunk cannot. */
    fun hashable(size: Long): Boolean = size >= CHUNK

    /** Where the tail chunk starts in a file of [size] bytes. */
    fun tailOffset(size: Long): Long = size - CHUNK

    /** The hash from the file's size and its first and last [CHUNK] bytes. */
    fun of(size: Long, head: ByteArray, tail: ByteArray): String {
        require(head.size >= CHUNK && tail.size >= CHUNK) { "need $CHUNK bytes from each end" }
        var sum = size
        sum += words(head)
        sum += words(tail)
        return java.lang.Long.toHexString(sum).padStart(16, '0')
    }

    /** The hash of a whole file on the disk, or null when it is too short or cannot be read. */
    fun of(file: File): String? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val size = raf.length()
            if (!hashable(size)) return null
            val head = ByteArray(CHUNK).also { raf.seek(0); raf.readFully(it) }
            val tail = ByteArray(CHUNK).also { raf.seek(tailOffset(size)); raf.readFully(it) }
            of(size, head, tail)
        }
    }.getOrNull()

    private fun words(bytes: ByteArray): Long {
        val buffer = ByteBuffer.wrap(bytes, 0, CHUNK).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0L
        repeat(CHUNK / 8) { sum += buffer.getLong() }
        return sum
    }
}
