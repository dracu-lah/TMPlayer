package com.tmplayer.ios.media3

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.tmplayer.platform.Logger
import com.tmplayer.platform.PlainLock
import kotlinx.coroutines.CancellationException

/**
 * The player's own data sources, for the engine to read the video through, as Android's ExoPlayer
 * reads it: the factory Android's player gave its media source factory (JoinedDataSource over
 * TdDataSource, with the player's byte counter listening) and the item's URI, under a
 * `tmsource://<key>` address the engine opens. So a Telegram stream on iOS follows Android's own
 * rules (Low storage mode, Save mobile data, an index at the end of a file fetched on its own,
 * stalls asked again, Telegram's refusals put into words), a video joined from parts plays, and
 * the loader's speed and percentage count the bytes read.
 */
object SourceStreams {
    const val SCHEME = "tmsource"

    private class Entry(val factory: DataSource.Factory, val uri: Uri) {
        @kotlin.concurrent.Volatile
        var failure: Throwable? = null
    }

    private val lock = PlainLock()
    private val entries = HashMap<String, Entry>()
    private var next = 0

    /** An address for [uri] read through [factory]'s sources. */
    fun register(factory: DataSource.Factory, uri: Uri): String = lock.locked {
        val key = (++next).toString()
        entries[key] = Entry(factory, uri)
        key
    }

    fun forget(key: String) {
        lock.locked { entries.remove(key) }
    }

    /** What last went wrong reading [key], for the player's error: Telegram's words, put as Android's player puts them. */
    fun failure(key: String): Throwable? = lock.locked { entries[key]?.failure }

    internal fun failed(key: String, error: Throwable) {
        lock.locked { entries[key]?.failure = error }
    }

    /** A stream at [key]'s start, or null when there is no such address or its source cannot open. Blocks. */
    fun open(key: String): SourceStream? {
        val entry = lock.locked { entries[key] } ?: return null
        MovedParts.clear(entry.uri)
        val stream = SourceStream(key, entry.factory.createDataSource(), entry.uri)
        return stream.takeIf { it.start() }
    }
}

/**
 * One reading of a video through a player's data source, for the engine's reading thread: a read at
 * the place the source is at goes on from there, a read anywhere else opens the source again there,
 * as ExoPlayer opens a source again after a seek. Every call blocks, as the engine's demuxer expects.
 */
class SourceStream internal constructor(private val key: String, private val source: DataSource, private val uri: Uri) {
    /** The video's length in bytes, or -1 when the source does not say. */
    var size: Long = -1
        private set

    // Where the open source reads next; -1 while it is closed.
    private var at = -1L

    @kotlin.concurrent.Volatile
    private var cancelled = false

    internal fun start(): Boolean = openAt(0)

    private fun openAt(position: Long): Boolean {
        closeSource()
        return try {
            val remaining = source.open(DataSpec(uri, position, C.LENGTH_UNSET.toLong()))
            if (position == 0L && remaining != C.LENGTH_UNSET.toLong()) size = remaining
            at = position
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            SourceStreams.failed(key, e)
            Logger.w(TAG, "open of $uri at $position failed", e)
            false
        }
    }

    /** Up to [length] bytes at [position] into [buffer]: how many, 0 at the end, -1 on a failure. */
    fun read(position: Long, buffer: ByteArray, length: Int): Int {
        if (cancelled) return -1
        if (size in 1..position) return 0
        if (position != at && !openAt(position)) return -1
        return try {
            val read = source.read(buffer, 0, length)
            if (read == C.RESULT_END_OF_INPUT) {
                0
            } else {
                at += read
                read
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            SourceStreams.failed(key, e)
            Logger.w(TAG, "read of $uri at $position failed", e)
            closeSource()
            -1
        }
    }

    /** The engine stopped wanting bytes: every later read gives up at once. */
    fun cancel() {
        cancelled = true
    }

    fun close() {
        cancelled = true
        closeSource()
    }

    private fun closeSource() {
        if (at < 0) return
        at = -1
        runCatching { source.close() }
    }

    private companion object {
        const val TAG = "SourceStream"
    }
}
