package com.tmplayer.desktop.player

import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.concurrent.thread

/**
 * TDLib's partial download, played by a thread: copies [source] into [target] at [bytesPerSecond]
 * from wherever it was last aimed, keeping the set of ranges that have landed.
 *
 * For the tests and for the dev harness's `--growing` mode, so the streaming path (blocking
 * reads, re-aiming on a far seek, the tail prefetch) can be watched without a Telegram account.
 * Like TDLib, a read that wants bytes far from where the download is going moves the download
 * there, after [reaimLatencyMs].
 */
class GrowingFileBytes(
    private val source: File,
    private val target: File,
    private val bytesPerSecond: Long,
    private val reaimLatencyMs: Long = 300,
) : StreamBytes {

    override val size: Long = source.length()

    private val lock = Object()
    private val ranges = ArrayList<LongArray>()

    @Volatile
    private var cursor = 0L

    @Volatile
    private var aimAt = -1L

    @Volatile
    private var aimDueAt = 0L

    @Volatile
    private var stopped = false

    /** How many times the download was moved, for the tests. */
    @Volatile
    var reaims = 0
        private set

    private val handle: RandomAccessFile

    init {
        target.parentFile?.mkdirs()
        RandomAccessFile(target, "rw").use { it.setLength(0) }
        handle = RandomAccessFile(target, "r")
        thread(name = "growing-file", isDaemon = true) { pump() }
    }

    private fun pump() {
        RandomAccessFile(source, "r").use { input ->
            RandomAccessFile(target, "rw").use { out ->
                val chunk = (bytesPerSecond * TICK_MS / 1000).toInt().coerceAtLeast(1)
                val buf = ByteArray(chunk)
                while (!stopped) {
                    val pending = aimAt
                    if (pending >= 0) {
                        if (System.currentTimeMillis() < aimDueAt) {
                            Thread.sleep(TICK_MS)
                            continue
                        }
                        aimAt = -1
                        cursor = pending
                        reaims++
                    }
                    cursor = contiguousEnd(cursor)
                    if (cursor >= size) {
                        val gap = firstGap() ?: break
                        cursor = gap
                    }
                    val n = minOf(chunk.toLong(), size - cursor, nextRangeStart(cursor) - cursor).toInt()
                    input.seek(cursor)
                    input.readFully(buf, 0, n)
                    out.seek(cursor)
                    out.write(buf, 0, n)
                    addRange(cursor, cursor + n)
                    cursor += n
                    Thread.sleep(TICK_MS)
                }
            }
        }
    }

    override fun cachedAvailable(position: Long): Long? {
        val end = contiguousEnd(position)
        return if (end > position) end - position else null
    }

    override suspend fun awaitBytesAt(position: Long): Long {
        aimIfFar(position)
        while (true) {
            cachedAvailable(position)?.let { return it }
            if (stopped) throw IOException("Stopped")
            delay(TICK_MS)
        }
    }

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = synchronized(handle) {
        handle.seek(position)
        handle.read(buffer, offset, length)
    }

    override suspend fun fetch(offset: Long, length: Int): ByteArray? {
        aim(offset)
        while (contiguousEnd(offset) < offset + length) {
            if (stopped) return null
            delay(TICK_MS)
        }
        val bytes = ByteArray(length)
        synchronized(handle) {
            handle.seek(offset)
            handle.readFully(bytes)
        }
        return bytes
    }

    override fun close() {
        stopped = true
        runCatching { handle.close() }
    }

    fun downloadedBytes(): Long = synchronized(lock) { ranges.sumOf { it[1] - it[0] } }

    private fun aimIfFar(position: Long) {
        if (cachedAvailable(position) != null) return
        if (position < cursor || position > cursor + NEAR_BYTES) aim(position)
    }

    private fun aim(position: Long) {
        aimDueAt = System.currentTimeMillis() + reaimLatencyMs
        aimAt = position
    }

    private fun addRange(start: Long, end: Long) = synchronized(lock) {
        ranges.add(longArrayOf(start, end))
        ranges.sortBy { it[0] }
        val merged = ArrayList<LongArray>()
        for (r in ranges) {
            val last = merged.lastOrNull()
            if (last != null && r[0] <= last[1]) last[1] = maxOf(last[1], r[1]) else merged.add(r.copyOf())
        }
        ranges.clear()
        ranges.addAll(merged)
    }

    private fun contiguousEnd(position: Long): Long = synchronized(lock) {
        ranges.firstOrNull { position >= it[0] && position < it[1] }?.get(1) ?: position
    }

    private fun nextRangeStart(position: Long): Long = synchronized(lock) {
        ranges.firstOrNull { it[0] > position }?.get(0) ?: size
    }

    private fun firstGap(): Long? {
        synchronized(lock) {
            var p = 0L
            for (r in ranges) {
                if (r[0] > p) return p
                p = r[1]
            }
            return if (p < size) p else null
        }
    }

    private companion object {
        const val TICK_MS = 20L
        const val NEAR_BYTES = 4L * 1024 * 1024
    }
}
