package com.tmplayer.desktop.player

import com.tmplayer.data.valueOrNull
import com.tmplayer.player.TdByteWindow
import dev.g000sha256.tdl.TdlClient
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * [StreamBytes] over TDLib, through the same [TdByteWindow] Android's `TdDataSource` wraps.
 *
 * Opened once per playback with [start], which subscribes before the first `getFile` (so no update
 * is missed while it is in flight), learns the size, and asks TDLib for the file from the first
 * byte the player will want.
 */
class TdStreamBytes(private val td: TdlClient, val fileId: Int) : StreamBytes {

    private val window = TdByteWindow(td)

    override var size: Long = 0L
        private set

    /** Whether the whole file was already on disk when last looked at. */
    val completed: Boolean get() = window.completed

    suspend fun start(from: Long = 0L) {
        window.watch(fileId)
        size = window.lookUp()
        // A finished file needs nothing from TDLib, not even a download request, which would only
        // re-enter its scheduler for bytes that are already sitting on disk.
        if (!window.completed) window.requestDownloadFrom(from)
    }

    override fun cachedAvailable(position: Long): Long? = window.cachedAvailable(position)

    override suspend fun awaitBytesAt(position: Long): Long = window.awaitBytesAt(position)

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        window.read(position, buffer, offset, length)

    override suspend fun fetch(offset: Long, length: Int): ByteArray? {
        // Synchronous: TDLib answers once the range is in, or once another request moves the
        // download, which is why the caller keeps the other reads waiting meanwhile.
        val file = td.downloadFile(
            fileId = fileId,
            priority = PLAYBACK_PRIORITY,
            offset = offset,
            limit = length.toLong(),
            synchronous = true,
        ).valueOrNull ?: return null
        val local = file.local
        val landed = local.isDownloadingCompleted ||
            (local.downloadOffset <= offset && local.downloadOffset + local.downloadedPrefixSize >= offset + length)
        if (!landed) return null
        val path = local.path.takeIf { it.isNotBlank() } ?: return null
        // NIO, for the delete sharing on Windows that lets TDLib rename the file meanwhile.
        return FileChannel.open(Paths.get(path), StandardOpenOption.READ).use { channel ->
            val bytes = ByteBuffer.allocate(length)
            while (bytes.hasRemaining()) {
                if (channel.read(bytes, offset + bytes.position()) < 0) throw EOFException("Short read at $offset")
            }
            bytes.array()
        }
    }

    override fun close() = window.close()

    private companion object {
        /** The same top priority the window asks with, so thumbnails never starve playback. */
        const val PLAYBACK_PRIORITY = 32
    }
}
