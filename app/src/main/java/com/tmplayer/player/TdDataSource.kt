package com.tmplayer.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSourceException
import com.tmplayer.data.Failures
import com.tmplayer.data.Td
import com.tmplayer.data.errorMessage
import com.tmplayer.data.valueOrNull
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.dto.File as TdFile
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/** `tdfile://<fileId>`, the only URI scheme [TdDataSource] understands. */
fun tdFileUri(fileId: Int): Uri = Uri.parse("tdfile://$fileId")

/**
 * Streams a Telegram file straight into the player without downloading it first.
 *
 * TDLib is asked to fill the file starting at whatever byte the player wants; reads then come
 * off the partial file on disk as soon as the bytes land. Seeking works because Media3 re-opens
 * the source at a new offset and this class simply points TDLib at that offset instead:
 * no full download, no waiting for the end of a 12 GB remux to watch minute 90.
 *
 * Written against TDLib's public file API only.
 */
@UnstableApi
class TdDataSource(private val td: TdlClient) : BaseDataSource(true) {

    private var fileId = -1
    private var uri: Uri? = null
    private var handle: RandomAccessFile? = null
    private var localPath: String? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false
    private var size = 0L

    /**
     * Everything below [windowEnd] has already been confirmed on disk, so reads under it need no
     * call to TDLib at all.
     *
     * This is the difference between smooth playback and a stutter every few seconds: Media3's
     * extractors issue a great many small reads, and asking TDLib about the file on each one puts
     * a request round-trip in front of every byte.
     */
    @Volatile
    private var windowEnd = 0L

    @Volatile
    private var completed = false

    override fun getUri(): Uri? = uri

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        fileId = dataSpec.uri.authority?.toIntOrNull()
            ?: dataSpec.uri.lastPathSegment?.toIntOrNull()
            ?: throw IOException("Not a Telegram file URI: ${dataSpec.uri}")
        position = dataSpec.position

        val file = blocking { td.getFile(fileId).valueOrNull }
            ?: throw IOException("Telegram lost track of file $fileId")
        size = if (file.size > 0) file.size else file.expectedSize
        if (size <= 0) throw IOException("Unknown size for file $fileId")
        if (position > size) throw DataSourceException(C.RESULT_END_OF_INPUT)

        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            size - position
        } else {
            minOf(dataSpec.length, size - position)
        }

        absorb(file)
        // A finished file needs nothing from TDLib, not even a download request, which would
        // only re-enter its scheduler for bytes that are already sitting on disk.
        if (!completed) blocking { requestDownloadFrom(position) }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        // Fast path: no coroutine and no TDLib round-trip, which is all but a handful of reads.
        val available = cachedAvailable(position) ?: blocking { awaitBytesAt(position) }
        val wanted = minOf(length.toLong(), bytesRemaining, available).toInt()

        val read = synchronized(this) {
            val file = openHandle()
            file.seek(position)
            file.read(buffer, offset, wanted)
        }
        if (read <= 0) {
            // TDLib reported the bytes but the file on disk is shorter: it was trimmed or
            // re-created underneath us. Drop the cached window too, so the next read goes back
            // and asks TDLib what is really there instead of trusting a stale boundary.
            closeHandle()
            windowEnd = 0
            completed = false
            throw EOFException("Short read at $position of file $fileId")
        }

        position += read
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun close() {
        closeHandle()
        // Media3 may reopen this instance at a different offset; nothing learned about the old
        // window is safe to carry across.
        windowEnd = 0
        completed = false
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    /**
     * How many bytes at [target] are already known-good, or `null` if TDLib has to be asked.
     *
     * Never optimistic: it only answers from a boundary that a real [TdFile] confirmed earlier,
     * and any doubt returns `null` so the slow path re-checks.
     */
    private fun cachedAvailable(target: Long): Long? {
        if (localPath == null) return null
        if (completed) return (size - target).coerceAtLeast(0).takeIf { it > 0 }
        val ahead = windowEnd - target
        return if (ahead > 0) ahead else null
    }

    /** Records what a fresh [TdFile] tells us, so later reads can skip the round-trip. */
    private fun absorb(file: TdFile) {
        val path = file.local.path
        if (!path.isNullOrEmpty()) {
            // TDLib moves a file when a download finishes (the partial name is renamed away), so
            // the handle is dropped on any path change rather than reading a stale inode.
            synchronized(this) {
                if (path != localPath) {
                    runCatching { handle?.close() }
                    handle = null
                    localPath = path
                }
            }
        }
        if (file.local.isDownloadingCompleted) {
            completed = true
            windowEnd = size
        } else {
            completed = false
            windowEnd = file.local.downloadOffset + file.local.downloadedPrefixSize
        }
    }

    /**
     * Blocks until at least one byte at [target] is on disk, restarting the download whenever
     * TDLib's window has drifted away from where the player is reading.
     */
    private suspend fun awaitBytesAt(target: Long): Long {
        var file = td.getFile(fileId).valueOrNull ?: throw IOException("File $fileId disappeared")
        var available = available(file, target)
        if (available > 0) return available

        var waited = 0L
        while (available == 0L) {
            if (DownloadWindow.needsRestart(
                    position = target,
                    downloadOffset = file.local.downloadOffset,
                    downloadedPrefixSize = file.local.downloadedPrefixSize,
                    active = file.local.isDownloadingActive,
                    completed = file.local.isDownloadingCompleted,
                )
            ) {
                requestDownloadFrom(target)
            }

            // updateFile arrives on every few hundred KB, so this normally returns immediately;
            // the timeout is only there so a silent connection still gets re-poked.
            val update = withTimeoutOrNull(POLL_INTERVAL_MS) {
                td.fileUpdates.filter { it.file.id == fileId }.first()
            }
            file = update?.file
                ?: td.getFile(fileId).valueOrNull
                ?: throw IOException("File $fileId disappeared")

            available = available(file, target)
            if (available == 0L) {
                waited += POLL_INTERVAL_MS
                if (waited >= STALL_TIMEOUT_MS) {
                    throw IOException("Telegram stopped sending file $fileId at byte $target")
                }
            }
        }
        return available
    }

    private fun available(file: TdFile, target: Long): Long {
        absorb(file)
        if (localPath == null) return 0
        return DownloadWindow.availableAt(
            position = target,
            size = size,
            downloadOffset = file.local.downloadOffset,
            downloadedPrefixSize = file.local.downloadedPrefixSize,
            completed = file.local.isDownloadingCompleted,
        )
    }

    /**
     * `limit = 0` means "keep going to the end of the file", exactly what playback wants.
     *
     * The result is checked rather than dropped. A refused request looks exactly like a slow
     * one from here (no bytes arrive), and swallowing it turns a flood wait or an expired file
     * reference into a minute of blank screen followed by "Telegram stopped sending", which
     * says nothing a viewer can act on.
     */
    private suspend fun requestDownloadFrom(offset: Long) {
        val result = td.downloadFile(
            fileId = fileId,
            priority = PLAYBACK_PRIORITY,
            offset = offset,
            limit = 0,
            synchronous = false,
        )
        val error = result.errorMessage ?: return
        throw IOException(Failures.humanise(error))
    }

    /** Call under the instance lock. [awaitBytesAt] has already recorded a usable path. */
    private fun openHandle(): RandomAccessFile = handle ?: run {
        val path = localPath ?: throw IOException("No local file for $fileId yet")
        RandomAccessFile(path, "r").also { handle = it }
    }

    private fun closeHandle() = synchronized(this) {
        runCatching { handle?.close() }
        handle = null
        localPath = null
    }

    /**
     * Media3 calls [open] and [read] on its own loading thread and cancels a load by
     * interrupting it, so blocking here is expected; it just has to stay interruptible.
     */
    private fun <T> blocking(block: suspend () -> T): T = try {
        runBlocking { withTimeout(OPEN_TIMEOUT_MS) { block() } }
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw InterruptedIOException("Cancelled")
    } catch (e: TimeoutCancellationException) {
        throw IOException("Telegram did not respond in time", e)
    }

    class Factory(private val client: TdlClient? = null) : DataSource.Factory {
        override fun createDataSource(): DataSource = TdDataSource(client ?: Td.client)
    }

    private companion object {
        /** 1..32; playback gets the top of the range so thumbnails never starve it. */
        const val PLAYBACK_PRIORITY = 32
        const val POLL_INTERVAL_MS = 1_000L
        const val STALL_TIMEOUT_MS = 60_000L
        const val OPEN_TIMEOUT_MS = 120_000L
    }
}
