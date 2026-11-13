package com.tmplayer.desktop.player

import com.tmplayer.player.PieceMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * A video cut into raw byte pieces ([com.tmplayer.data.SplitPieces]) as the one file it was, for
 * [TdMediaData]: the pieces' bytes end to end, a position mapped to a piece and an offset in it
 * ([PieceMap]). Each piece is its own [StreamBytes] (a TDLib stream, or a file on the disk),
 * opened only once a read reaches it, so only what mpv reads is fetched.
 *
 * @param sizes every piece's exact length, in order.
 * @param open opens piece [index]'s bytes, its download aimed at [from].
 * @param onLeft told when a seek moves the reading off piece [index] to one that does not follow
 *   it; the piece is closed by then, and opened again should a read come back to it.
 */
class JoinedStreamBytes(
    sizes: List<Long>,
    private val open: suspend (index: Int, from: Long) -> StreamBytes,
    private val onLeft: (index: Int) -> Unit = {},
) : StreamBytes {

    private val map = PieceMap(sizes)

    override val size: Long get() = map.total

    private val opened = arrayOfNulls<StreamBytes>(map.count)
    private val opening = Mutex()

    @Volatile
    private var lastIndex = -1

    @Volatile
    private var closed = false

    override fun cachedAvailable(position: Long): Long? {
        val spot = map.locate(position) ?: return null
        val piece = opened[spot.index] ?: return null
        val available = piece.cachedAvailable(spot.offset) ?: return null
        return minOf(available, map.sizes[spot.index] - spot.offset)
    }

    override suspend fun awaitBytesAt(position: Long): Long {
        val spot = map.locate(position) ?: throw IOException("Read past the end at $position")
        val piece = pieceAt(spot.index, spot.offset)
        return minOf(piece.awaitBytesAt(spot.offset), map.sizes[spot.index] - spot.offset)
    }

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val spot = map.locate(position) ?: return -1
        val piece = opened[spot.index] ?: throw IOException("Piece ${spot.index + 1} is not open")
        val wanted = minOf(length.toLong(), map.sizes[spot.index] - spot.offset).toInt()
        return piece.read(spot.offset, buffer, offset, wanted)
    }

    override suspend fun askAgain(position: Long) {
        val spot = map.locate(position) ?: return
        // Only a piece already open: one that is not has nothing stalled to ask again for.
        opened[spot.index]?.askAgain(spot.offset)
    }

    override fun close() {
        closed = true
        opened.forEachIndexed { index, piece ->
            runCatching { piece?.close() }
            opened[index] = null
        }
    }

    private suspend fun pieceAt(index: Int, from: Long, reading: Boolean = true): StreamBytes {
        opened[index]?.let { return if (reading) note(index, it) else it }
        return opening.withLock {
            if (closed) throw IOException("Media closed")
            val piece = opened[index] ?: open(index, from).also { opened[index] = it }
            if (reading) note(index, piece) else piece
        }
    }

    private fun note(index: Int, piece: StreamBytes): StreamBytes {
        val last = lastIndex
        lastIndex = index
        if (last >= 0 && last != index && last != index - 1) {
            // Left behind by a seek: closed, so its download can stop and its bytes can go.
            opened[last]?.let { left ->
                opened[last] = null
                runCatching { left.close() }
                runCatching { onLeft(last) }
            }
        }
        return piece
    }
}

/** A whole file on the disk as [StreamBytes]: a piece kept in Downloads, every byte there already. */
class LocalFileBytes(private val file: java.io.File) : StreamBytes {

    private val channel: java.nio.channels.FileChannel =
        java.nio.channels.FileChannel.open(file.toPath(), java.nio.file.StandardOpenOption.READ)

    override val size: Long = channel.size()

    override fun cachedAvailable(position: Long): Long? = (size - position).takeIf { it > 0 }

    override suspend fun awaitBytesAt(position: Long): Long =
        cachedAvailable(position) ?: throw IOException("Read past the end of ${file.name} at $position")

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val read = channel.read(java.nio.ByteBuffer.wrap(buffer, offset, length), position)
        if (read < 0) throw java.io.EOFException("Short read at $position")
        return read
    }

    override fun close() {
        runCatching { channel.close() }
    }
}

/**
 * A slice of [inner] shown as the whole file: a stored video inside a ZIP ([com.tmplayer.data.ZipArchive]),
 * whose bytes start [start] bytes into the archive and run [length] bytes, so mpv meets the video's
 * own bytes and seeks within them. Positions are the slice's; the archive's are [start] more.
 */
class WindowedBytes(
    private val inner: StreamBytes,
    private val start: Long,
    private val length: Long,
) : StreamBytes {

    init {
        require(start >= 0 && length >= 0 && start + length <= inner.size) { "Window outside the archive" }
    }

    override val size: Long get() = length

    override fun cachedAvailable(position: Long): Long? {
        if (position < 0 || position >= length) return null
        return inner.cachedAvailable(start + position)?.let { minOf(it, length - position) }
    }

    override suspend fun awaitBytesAt(position: Long): Long {
        if (position < 0 || position >= length) throw IOException("Read past the end at $position")
        return minOf(inner.awaitBytesAt(start + position), length - position)
    }

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position < 0 || position >= this.length) return -1
        return inner.read(start + position, buffer, offset, minOf(length.toLong(), this.length - position).toInt())
    }

    override suspend fun askAgain(position: Long) {
        if (position in 0 until length) inner.askAgain(start + position)
    }

    override fun close() = inner.close()
}

/**
 * [length] bytes at [offset] of these bytes, waiting for each to arrive, or null when they cannot
 * be had (a piece missing, the network gone); fewer only at the end. Reads a ZIP's directory
 * through [com.tmplayer.data.ZipArchive].
 */
suspend fun StreamBytes.readRange(offset: Long, length: Int): ByteArray? {
    if (offset < 0 || length < 0) return null
    if (offset >= size) return ByteArray(0)
    val want = minOf(length.toLong(), size - offset).toInt()
    val out = ByteArray(want)
    var got = 0
    try {
        while (got < want) {
            val here = awaitBytesAt(offset + got)
            val n = read(offset + got, out, got, minOf(here, (want - got).toLong()).toInt())
            if (n <= 0) break
            got += n
        }
    } catch (e: IOException) {
        return null
    }
    return if (got == want) out else out.copyOf(got)
}
