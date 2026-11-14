package com.tmplayer.cast

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A source of any length (over 4 GB too) whose every byte is a function of its place, so a test
 * can check any range without holding the file. Counts its readers the way TDLib would see its
 * download windows: how many are open at once ([maxOpen]) and where each was opened ([opens]).
 */
class PatternSource(
    override val length: Long,
    private val readDelayMs: Long = 0,
    /** Called before every read with the reader's place: throw to simulate a cut. */
    private val beforeRead: (Long) -> Unit = {},
) : CastSource {

    val opens = CopyOnWriteArrayList<Long>()
    private val openNow = AtomicInteger()
    val maxOpen = AtomicInteger()

    override fun open(position: Long): CastReader {
        opens += position
        val now = openNow.incrementAndGet()
        maxOpen.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        return object : CastReader {
            var at = position
            var closed = false

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (closed) throw IOException("read after close")
                beforeRead(at)
                if (readDelayMs > 0) Thread.sleep(readDelayMs)
                if (at >= this@PatternSource.length) return -1
                val n = minOf(length.toLong(), this@PatternSource.length - at).toInt()
                for (i in 0 until n) buffer[offset + i] = byteAt(at + i)
                at += n
                return n
            }

            override fun close() {
                if (!closed) openNow.decrementAndGet()
                closed = true
            }
        }
    }

    companion object {
        fun byteAt(i: Long): Byte = ((i xor (i ushr 13)) * 31 + (i ushr 32)).toByte()

        fun expected(start: Long, count: Int): ByteArray = ByteArray(count) { byteAt(start + it) }
    }
}
