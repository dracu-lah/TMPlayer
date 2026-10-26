package com.tmplayer.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.net.Uri
import android.util.Log
import com.tmplayer.MainActivity
import com.tmplayer.R
import com.tmplayer.ui.browse.TdHomeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Writes [WatchNext]'s decisions into the TV provider, where the Android TV home screen reads its
 * "Play next" row from.
 *
 * The platform's own `TvContract` rather than the androidx tvprovider library: the Watch Next table
 * has been in the platform since Android 8, which is this app's floor, and the library would only
 * wrap the same columns. Every call is best effort. A launcher without the row, a provider that
 * refuses the write, or a viewer who removed the entry by hand all end in nothing happening.
 *
 * Runs on a background thread: every call is a content provider round trip.
 *
 * **Pictures.** An entry shows the video's own picture, as a `content://` URI of [TvArtProvider]
 * that the launcher can open: the film's or show's wide picture when the online lookups know one,
 * else Telegram's thumbnail of the video (downloaded first when it is not on the device yet), and
 * the app's mark only when neither could be had. The home screen is read by another process, so
 * the picture is a copy in the app's own directory ([TvArt]) and not a file inside TDLib's. An
 * entry that wears the mark (nothing was ready when it was written, or the copy has since gone
 * from the disk) is given its picture by [fillMissingArt] the next time Home opens.
 */
object WatchNextPublisher {

    private const val TAG = "WatchNext"

    /** The launch intent's action: play the video the extras describe. */
    const val ACTION_PLAY = "com.tmplayer.action.PLAY_FROM_HOME"

    private const val EXTRA_CHAT = "wn_chat"
    private const val EXTRA_MESSAGE = "wn_message"
    private const val EXTRA_FILE = "wn_file"
    private const val EXTRA_TITLE = "wn_title"
    private const val EXTRA_CHAT_TITLE = "wn_chat_title"
    private const val EXTRA_SIZE = "wn_size"
    private const val EXTRA_DURATION = "wn_duration"

    /** How long finding one entry's picture may take, the online one and Telegram's thumbnail each. */
    private const val ART_TIMEOUT_MS = 20_000L

    /** Online lookups and downloads one entry's picture may make: the title's lookup, then its picture. */
    private const val ONLINE_PER_ENTRY = 2

    /** Entries [fillMissingArt] repairs in one go: it stops by itself, a few pictures a Home visit. */
    private const val REPAIRS_PER_RUN = 6

    /** One writer at a time: a stop in the player and the repair when Home opens both read, then write, the rows. */
    private val lock = Mutex()

    /**
     * The video as Telegram describes it, for its thumbnail and its name. A seam so a test can say
     * Telegram answers late, or not at all.
     */
    @Volatile
    internal var mediaLookup: suspend (chatId: Long, messageId: Long) -> com.tmplayer.data.MediaItem? =
        { chat, message -> TdHomeSource.mediaItem(chat, message) }

    suspend fun apply(context: Context, changes: List<WatchNext.Change>) {
        // The Play next row is Android 8's TV provider; an older television has none to fill.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        if (changes.isEmpty()) return
        withContext(Dispatchers.IO) {
            lock.withLock {
                val existing = ownRows(context)
                for (change in changes) {
                    runCatching {
                        when (change) {
                            is WatchNext.Change.Remove -> existing[change.internalId]?.let { delete(context, it.id) }
                            is WatchNext.Change.Upsert -> upsert(context, change.entry, existing[change.entry.internalId])
                        }
                    }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else Log.w(TAG, "Play next row not written", it) }
                }
                pruneArt(context)
            }
        }
    }

    /** Takes every entry this app put in the row back out: the setting was turned off. */
    suspend fun clearAll(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        withContext(Dispatchers.IO) {
            lock.withLock {
                ownRows(context).values.forEach { runCatching { delete(context, it.id) } }
                pruneArt(context)
            }
        }
    }

    /**
     * Gives the entries that wear the app's mark, or whose picture has gone from the disk, their
     * video's own picture: the thumbnail was not on the device, or Telegram did not answer, when
     * the entry was written (the viewer left the video before it arrived). Called when Home opens;
     * at most [REPAIRS_PER_RUN] entries a call, and nothing again until Home is next opened, so it
     * never keeps the device busy by itself. An entry the viewer took off the home screen is left alone.
     */
    suspend fun fillMissingArt(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        withContext(Dispatchers.IO) {
            lock.withLock {
                val wanting = ownRows(context).filterValues { it.browsable && needsArt(context, it) }.entries.take(REPAIRS_PER_RUN)
                for ((internalId, row) in wanting) {
                    runCatching {
                        val (chat, message) = idsOf(internalId) ?: return@runCatching
                        val poster = posterFor(context, chat, message, fallback = false) ?: return@runCatching
                        val values = ContentValues().apply { putPoster(this, poster) }
                        context.contentResolver.update(ContentUris.withAppendedId(TvContract.WatchNextPrograms.CONTENT_URI, row.id), values, null, null)
                        Log.i(TAG, "Play next: picture added to row ${row.id}")
                    }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else Log.w(TAG, "Play next picture not added", it) }
                }
                pruneArt(context)
            }
        }
    }

    /** The video a launch from the home screen asks for, or null for any other launch. */
    fun videoFrom(intent: Intent?): WatchNext.Video? {
        if (intent?.action != ACTION_PLAY) return null
        val chat = intent.getLongExtra(EXTRA_CHAT, 0)
        val message = intent.getLongExtra(EXTRA_MESSAGE, 0)
        val file = intent.getIntExtra(EXTRA_FILE, 0)
        if (chat == 0L || message == 0L || file <= 0) return null
        return WatchNext.Video(
            chatId = chat,
            messageId = message,
            fileId = file,
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            chatTitle = intent.getStringExtra(EXTRA_CHAT_TITLE).orEmpty(),
            sizeBytes = intent.getLongExtra(EXTRA_SIZE, 0),
            durationSec = intent.getIntExtra(EXTRA_DURATION, 0),
        )
    }

    internal fun intentFor(context: Context, video: WatchNext.Video): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_PLAY
            putExtra(EXTRA_CHAT, video.chatId)
            putExtra(EXTRA_MESSAGE, video.messageId)
            putExtra(EXTRA_FILE, video.fileId)
            putExtra(EXTRA_TITLE, video.title)
            putExtra(EXTRA_CHAT_TITLE, video.chatTitle)
            putExtra(EXTRA_SIZE, video.sizeBytes)
            putExtra(EXTRA_DURATION, video.durationSec)
        }

    private class Row(val id: Long, val browsable: Boolean, val poster: String?)

    /** The chat and message an entry's internal id ([WatchNext.Video.internalId]) names. */
    private fun idsOf(internalId: String): Pair<Long, Long>? {
        val at = internalId.lastIndexOf('_')
        if (at <= 0) return null
        val chat = internalId.substring(0, at).toLongOrNull() ?: return null
        val message = internalId.substring(at + 1).toLongOrNull() ?: return null
        return if (chat == 0L || message == 0L) null else chat to message
    }

    /** Whether the entry wears the app's mark, or a picture of ours that is gone from the disk. */
    private fun needsArt(context: Context, row: Row): Boolean =
        row.poster.isNullOrEmpty() || !row.poster.startsWith("content://") || TvArt.copyMissing(context, row.poster)

    /** This app's entries, by internal id. The provider only ever shows an app its own rows. */
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.O)
    private fun ownRows(context: Context): Map<String, Row> = runCatching {
        val projection = arrayOf(
            TvContract.WatchNextPrograms._ID,
            TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
            TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
        )
        context.contentResolver.query(TvContract.WatchNextPrograms.CONTENT_URI, projection, null, null, null)
            ?.use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        val internal = cursor.getString(1) ?: continue
                        put(internal, Row(cursor.getLong(0), cursor.getInt(2) != 0, cursor.getString(3)))
                    }
                }
            }.orEmpty()
    }.getOrDefault(emptyMap())

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.O)
    private suspend fun upsert(context: Context, entry: WatchNext.Entry, row: Row?) {
        // Taken out of the row by the viewer on the home screen: that is an answer, and putting
        // it back on the next stop would be arguing with it.
        if (row != null && !row.browsable) {
            Log.i(TAG, "Play next: row ${row.id} was removed on the home screen, left out")
            return
        }
        // The picture is looked for when the entry is new or has none of its own; an entry with its
        // picture keeps it, so a stop in the player asks neither Telegram nor the network again.
        val poster = if (row == null || needsArt(context, row)) {
            posterFor(context, entry.video.chatId, entry.video.messageId, fallback = true)
        } else {
            null
        }
        val values = values(context, entry, poster)
        if (row == null) {
            // The only trace the row leaves on this side: the provider shows each app its own
            // entries alone, so neither adb nor the launcher's settings can say whether one went in.
            val uri = context.contentResolver.insert(TvContract.WatchNextPrograms.CONTENT_URI, values)
            Log.i(TAG, "Play next ${entry.kind}: ${uri ?: "refused"}")
        } else {
            val updated = context.contentResolver.update(
                ContentUris.withAppendedId(TvContract.WatchNextPrograms.CONTENT_URI, row.id),
                values,
                null,
                null,
            )
            Log.i(TAG, "Play next ${entry.kind}: row ${row.id} updated ($updated)")
        }
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.O)
    private fun delete(context: Context, id: Long) {
        context.contentResolver.delete(ContentUris.withAppendedId(TvContract.WatchNextPrograms.CONTENT_URI, id), null, null)
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.O)
    private fun values(context: Context, entry: WatchNext.Entry, poster: Poster?): ContentValues = ContentValues().apply {
        val video = entry.video
        put(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, entry.internalId)
        put(TvContract.WatchNextPrograms.COLUMN_TYPE, TvContract.WatchNextPrograms.TYPE_MOVIE)
        put(
            TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
            when (entry.kind) {
                WatchNext.Kind.Continue -> TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
                WatchNext.Kind.Next -> TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT
            },
        )
        put(TvContract.WatchNextPrograms.COLUMN_TITLE, video.title)
        put(TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION, video.chatTitle)
        put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, entry.lastEngagementMs)
        if (entry.durationMs > 0) {
            put(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, entry.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
        put(
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            entry.positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
        // Null on an update of an entry that already has its picture: left as it is.
        if (poster != null) putPoster(this, poster)
        put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, intentFor(context, video).toUri(Intent.URI_INTENT_SCHEME))
    }

    private fun putPoster(values: ContentValues, poster: Poster) {
        values.put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, poster.uri.toString())
        values.put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, TvContract.WatchNextPrograms.ASPECT_RATIO_16_9)
    }

    // ---- pictures -------------------------------------------------------------------------------

    /** A picture for an entry: the video's own ([real]) or the app's mark, which stands in until it can be had. */
    internal class Poster(val uri: Uri, val real: Boolean)

    /**
     * The picture for the video of [chat] and [message]: its wide online picture, else Telegram's
     * thumbnail, each copied into [TvArtProvider]'s directory. With [fallback] the app's mark when
     * neither could be had; without it, null then, for the caller that only wants a real picture.
     */
    internal suspend fun posterFor(context: Context, chat: Long, message: Long, fallback: Boolean): Poster? {
        val name = runCatching { realArt(context, chat, message) }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            Log.w(TAG, "Play next picture not found", it)
            null
        }
        if (name != null) return Poster(TvArtProvider.uriFor(context, name), real = true)
        return if (fallback) Poster(mark(context), real = false) else null
    }

    private suspend fun realArt(context: Context, chat: Long, message: Long): String? {
        // The thumbnail's file and the names the lookups go by are Telegram's to say: the video a
        // Continue entry was written from often came from a saved record, which keeps neither.
        val item = withTimeoutOrNull(ART_TIMEOUT_MS) { mediaLookup(chat, message) }
        val art = TvChannels.Art(
            thumbnailFileId = item?.thumbnailFileId ?: 0,
            titled = false,
            name = item?.nameForParsing.orEmpty(),
            caption = item?.caption.orEmpty(),
        )
        if (item != null && art.name.isNotBlank()) {
            val wide = withTimeoutOrNull(ART_TIMEOUT_MS) { TvArt.wide(context, art, TvArt.OnlineBudget(ONLINE_PER_ENTRY), TvArt.PLAY_NEXT) }
            if (wide != null) return wide
        }
        if (art.thumbnailFileId <= 0) return null
        return withTimeoutOrNull(ART_TIMEOUT_MS) { TvArt.thumbnail(context, art.thumbnailFileId, "t|$chat|$message", TvArt.PLAY_NEXT) }
    }

    /** Takes the copies no entry names any more out of the directory; the channels' own are not touched. */
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.O)
    private fun pruneArt(context: Context) {
        val kept = ownRows(context).values.mapNotNullTo(HashSet()) { TvArt.artName(context, it.poster) }
        TvArt.prune(context, setOf(TvArt.PLAY_NEXT), kept)
    }

    /** The app's mark, for an entry whose own picture could not be had yet. */
    internal fun mark(context: Context): Uri = poster(context)

    internal fun poster(context: Context): Uri = Uri.Builder()
        .scheme(android.content.ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.packageName)
        .appendPath(context.resources.getResourceTypeName(R.drawable.watch_next_poster))
        .appendPath(context.resources.getResourceEntryName(R.drawable.watch_next_poster))
        .build()
}
