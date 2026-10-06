package com.tmplayer.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.net.Uri
import com.tmplayer.MainActivity
import com.tmplayer.R

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
 */
object WatchNextPublisher {

    /** The launch intent's action: play the video the extras describe. */
    const val ACTION_PLAY = "com.tmplayer.action.PLAY_FROM_HOME"

    private const val EXTRA_CHAT = "wn_chat"
    private const val EXTRA_MESSAGE = "wn_message"
    private const val EXTRA_FILE = "wn_file"
    private const val EXTRA_TITLE = "wn_title"
    private const val EXTRA_CHAT_TITLE = "wn_chat_title"
    private const val EXTRA_SIZE = "wn_size"
    private const val EXTRA_DURATION = "wn_duration"

    fun apply(context: Context, changes: List<WatchNext.Change>) {
        if (changes.isEmpty()) return
        val existing = ownRows(context)
        for (change in changes) {
            runCatching {
                when (change) {
                    is WatchNext.Change.Remove -> existing[change.internalId]?.let { delete(context, it.id) }
                    is WatchNext.Change.Upsert -> upsert(context, change.entry, existing[change.entry.internalId])
                }
            }
        }
    }

    /** Takes every entry this app put in the row back out: the setting was turned off. */
    fun clearAll(context: Context) {
        ownRows(context).values.forEach { runCatching { delete(context, it.id) } }
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

    private fun intentFor(context: Context, video: WatchNext.Video): Intent =
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

    private class Row(val id: Long, val browsable: Boolean)

    /** This app's entries, by internal id. The provider only ever shows an app its own rows. */
    private fun ownRows(context: Context): Map<String, Row> = runCatching {
        val projection = arrayOf(
            TvContract.WatchNextPrograms._ID,
            TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
            TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
        )
        context.contentResolver.query(TvContract.WatchNextPrograms.CONTENT_URI, projection, null, null, null)
            ?.use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        val internal = cursor.getString(1) ?: continue
                        put(internal, Row(cursor.getLong(0), cursor.getInt(2) != 0))
                    }
                }
            }.orEmpty()
    }.getOrDefault(emptyMap())

    private fun upsert(context: Context, entry: WatchNext.Entry, row: Row?) {
        // Taken out of the row by the viewer on the home screen: that is an answer, and putting
        // it back on the next stop would be arguing with it.
        if (row != null && !row.browsable) return
        val values = values(context, entry)
        if (row == null) {
            context.contentResolver.insert(TvContract.WatchNextPrograms.CONTENT_URI, values)
        } else {
            context.contentResolver.update(
                ContentUris.withAppendedId(TvContract.WatchNextPrograms.CONTENT_URI, row.id),
                values,
                null,
                null,
            )
        }
    }

    private fun delete(context: Context, id: Long) {
        context.contentResolver.delete(ContentUris.withAppendedId(TvContract.WatchNextPrograms.CONTENT_URI, id), null, null)
    }

    private fun values(context: Context, entry: WatchNext.Entry): ContentValues = ContentValues().apply {
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
        // The app's own mark: a video's own picture would need a file the launcher may read,
        // which nothing in this app hands out.
        put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, poster(context).toString())
        put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, TvContract.WatchNextPrograms.ASPECT_RATIO_16_9)
        put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, intentFor(context, video).toUri(Intent.URI_INTENT_SCHEME))
    }

    private fun poster(context: Context): Uri = Uri.Builder()
        .scheme(android.content.ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.packageName)
        .appendPath(context.resources.getResourceTypeName(R.drawable.watch_next_poster))
        .appendPath(context.resources.getResourceEntryName(R.drawable.watch_next_poster))
        .build()
}
