package com.tmplayer.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/** The scheme of a video joined from byte pieces: see [joinedUri]. */
const val JOINED_SCHEME = "tdjoin"

/**
 * One URI for a video cut into raw byte pieces ([com.tmplayer.data.SplitPieces]): each piece's
 * own URI (`tdfile://<id>` for a stream, `file://` for a download) with its exact length, in order.
 */
fun joinedUri(pieces: List<Pair<Uri, Long>>, window: Pair<Long, Long>? = null): Uri {
    val builder = Uri.Builder().scheme(JOINED_SCHEME).authority("pieces")
    pieces.forEach { (uri, size) ->
        builder.appendQueryParameter("p", uri.toString())
        builder.appendQueryParameter("s", size.toString())
    }
    // A stored video inside a ZIP: the stream seen through a window of its start and length, so the
    // player meets the video's own bytes as if it were the whole file ([ZipArchive]).
    window?.let { (start, length) ->
        builder.appendQueryParameter("zo", start.toString())
        builder.appendQueryParameter("zl", length.toString())
    }
    return builder.build()
}

/**
 * Plays a video cut into byte pieces as the one file it was: the pieces' bytes end to end, its
 * length their sum, a position mapped to a piece and an offset in it ([PieceMap]). Only the piece
 * being read is open, so only the ranges the player asks for are fetched, and each piece streams
 * through [TdDataSource] with its own Low storage and "Save mobile data" bounds, as any file does.
 *
 * Any other URI goes straight to [direct], so this sits where [TdDataSource] did.
 *
 * @param pieces makes the reader of one piece: [TdDataSource] for `tdfile://`, the disk for `file://`.
 * @param onLeftPiece told the URI of a piece a seek has moved off, so its download can stop.
 */
@UnstableApi
class JoinedDataSource(
    private val direct: DataSource,
    private val pieces: DataSource.Factory,
    private val onLeftPiece: (Uri) -> Unit = {},
) : DataSource {

    private val listeners = ArrayList<TransferListener>()

    private var uri: Uri? = null
    private var joined: Joined? = null

    /** The reader of the piece being read, for a joined video. */
    private var current: DataSource? = null
    private var currentIndex = -1

    /** The piece read last, kept across a close and a reopen, which is how Media3 seeks. */
    private var lastIndex = -1

    private var position = 0L
    private var bytesRemaining = 0L

    /** [windowStart] and [windowLength] are the slice of the joined bytes shown as the whole file, or 0 and the total. */
    private class Joined(val uris: List<Uri>, val map: PieceMap, val windowStart: Long, val windowLength: Long)

    override fun addTransferListener(transferListener: TransferListener) {
        direct.addTransferListener(transferListener)
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        if (dataSpec.uri.scheme != JOINED_SCHEME) {
            joined = null
            return direct.open(dataSpec)
        }
        val video = parse(dataSpec.uri)
        joined = video
        // Positions asked for are in the window; the pieces are read at the window's start plus that.
        val total = video.windowLength
        if (dataSpec.position > total) throw DataSourceException(androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        position = video.windowStart + dataSpec.position
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) total - dataSpec.position else minOf(dataSpec.length, total - dataSpec.position)
        if (bytesRemaining > 0) openPieceAt(position)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (joined == null) return direct.read(buffer, offset, length)
        if (length == 0) return 0
        while (true) {
            if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
            val source = current ?: openPieceAt(position) ?: return C.RESULT_END_OF_INPUT
            val wanted = minOf(length.toLong(), bytesRemaining).toInt()
            val read = source.read(buffer, offset, wanted)
            if (read == C.RESULT_END_OF_INPUT) {
                // The end of this piece: the next one carries on from its first byte.
                closePiece()
                val map = joined!!.map
                val next = currentIndexAfter(position, map) ?: return C.RESULT_END_OF_INPUT
                if (map.startOf(next) != position) throw IOException("Piece ${next + 1} does not start where the last ended")
                continue
            }
            position += read
            bytesRemaining -= read
            return read
        }
    }

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (joined == null) direct.responseHeaders else emptyMap()

    override fun close() {
        if (joined == null) {
            direct.close()
        } else {
            closePiece()
        }
        joined = null
        uri = null
    }

    /** Opens the piece holding [at], from the byte in it, or returns null at the end of the video. */
    private fun openPieceAt(at: Long): DataSource? {
        val video = joined ?: return null
        val spot = video.map.locate(at) ?: return null
        // A seek that left one piece for another: the one left needs fetching no further.
        if (lastIndex >= 0 && lastIndex != spot.index && lastIndex != spot.index - 1) {
            runCatching { onLeftPiece(video.uris[lastIndex]) }
        }
        val source = pieces.createDataSource()
        listeners.forEach(source::addTransferListener)
        val left = video.map.sizes[spot.index] - spot.offset
        source.open(
            DataSpec.Builder()
                .setUri(video.uris[spot.index])
                .setPosition(spot.offset)
                .setLength(left)
                .build(),
        )
        current = source
        currentIndex = spot.index
        lastIndex = spot.index
        return source
    }

    private fun closePiece() {
        val source = current ?: return
        current = null
        currentIndex = -1
        source.close()
    }

    private fun currentIndexAfter(at: Long, map: PieceMap): Int? = map.locate(at)?.index

    private fun parse(uri: Uri): Joined {
        val uris = uri.getQueryParameters("p").map(Uri::parse)
        val sizes = uri.getQueryParameters("s").map { it.toLongOrNull() ?: throw IOException("Bad piece size in $uri") }
        if (uris.isEmpty() || uris.size != sizes.size) throw IOException("Not a joined video: $uri")
        val map = PieceMap(sizes)
        val start = uri.getQueryParameter("zo")?.let { it.toLongOrNull() ?: throw IOException("Bad window in $uri") } ?: 0L
        val length = uri.getQueryParameter("zl")?.let { it.toLongOrNull() ?: throw IOException("Bad window in $uri") } ?: map.total
        // A window that leaves the pieces is a bad archive, not something to read past the end of.
        if (start < 0 || length < 0 || start + length > map.total) throw IOException("Window outside the pieces in $uri")
        return Joined(uris, map, start, length)
    }

    class Factory(
        private val direct: DataSource.Factory,
        private val pieces: DataSource.Factory,
        private val onLeftPiece: (Uri) -> Unit = {},
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = JoinedDataSource(direct.createDataSource(), pieces, onLeftPiece)
    }
}
