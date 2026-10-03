package com.tmplayer.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSourceException
import dev.g000sha256.tdl.TdlClient
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InterruptedIOException

/** `tdfile://<fileId>`, the only URI scheme [TdDataSource] understands. */
fun tdFileUri(fileId: Int): Uri = Uri.parse("tdfile://$fileId")

/**
 * Streams a Telegram file straight into the player without downloading it first.
 *
 * The Media3 shell around [TdByteWindow], which does the streaming: this turns Media3's open, read
 * and close into calls on it, blocks Media3's loading thread while a read waits for its bytes,
 * and reports the end of input the way Media3 expects.
 */
@UnstableApi
class TdDataSource(td: TdlClient) : BaseDataSource(true) {

    private val bytes = TdByteWindow(td)

    private var uri: Uri? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false

    override fun getUri(): Uri? = uri

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val fileId = dataSpec.uri.authority?.toIntOrNull()
            ?: dataSpec.uri.lastPathSegment?.toIntOrNull()
            ?: throw IOException("Not a Telegram file URI: ${dataSpec.uri}")
        position = dataSpec.position

        // Subscribed before the first getFile, so nothing arriving while it is in flight is
        // missed: an update dropped there is a second of stall for no reason.
        bytes.watch(fileId)

        val size = blocking(OPEN_TIMEOUT_MS) { bytes.lookUp() }
        if (position > size) throw DataSourceException(C.RESULT_END_OF_INPUT)

        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            size - position
        } else {
            minOf(dataSpec.length, size - position)
        }

        // A finished file needs nothing from TDLib, not even a download request, which would
        // only re-enter its scheduler for bytes that are already sitting on disk.
        if (!bytes.completed) blocking(OPEN_TIMEOUT_MS) { bytes.requestDownloadFrom(position) }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        // Fast path: no coroutine and no TDLib round-trip, which is all but a handful of reads.
        val available = bytes.cachedAvailable(position)
            ?: blocking(READ_TIMEOUT_MS) { bytes.awaitBytesAt(position) }
        val wanted = minOf(length.toLong(), bytesRemaining, available).toInt()

        val read = bytes.read(position, buffer, offset, wanted)

        position += read
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun close() {
        // Media3 may reopen this instance at a different offset; the window forgets the old one.
        bytes.close()
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    /**
     * Media3 calls [open] and [read] on its own loading thread and cancels a load by
     * interrupting it, so blocking here is expected; it just has to stay interruptible.
     */
    private fun <T> blocking(timeoutMs: Long, block: suspend () -> T): T = try {
        runBlocking { withTimeout(timeoutMs) { block() } }
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw InterruptedIOException("Cancelled")
    } catch (e: TimeoutCancellationException) {
        throw IOException("Telegram did not respond in time", e)
    }

    class Factory(private val client: TdlClient) : DataSource.Factory {
        override fun createDataSource(): DataSource = TdDataSource(client)
    }

    private companion object {
        const val OPEN_TIMEOUT_MS = 120_000L

        /**
         * The read's own ceiling, a little above the stall timeout it wraps.
         *
         * The inner wait is the one that decides when a stalled read fails; this is only the
         * backstop for a wait that never returns at all. Keep it above
         * [TdByteWindow.STALL_TIMEOUT_MS].
         */
        const val READ_TIMEOUT_MS = TdByteWindow.STALL_TIMEOUT_MS + 10_000L
    }
}
