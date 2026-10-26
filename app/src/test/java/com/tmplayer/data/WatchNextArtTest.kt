package com.tmplayer.data

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.media.tv.TvContract
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileNotFoundException

/**
 * The pictures on the home screen's "Play next" row (HX4: Continue watching showed no image).
 *
 * The launcher is another process, so these go through the content resolver the way it does:
 * the entry's poster URI is opened by [TvArtProvider], and the TV provider itself is a small
 * in-memory stand-in that keeps the rows the publisher writes.
 */
@RunWith(RobolectricTestRunner::class)
class WatchNextArtTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var tv: FakeTvProvider
    private lateinit var telegramThumbnail: File
    private val bytes = ByteArray(2_048) { (it % 251).toByte() }

    private val video = WatchNext.Video(
        chatId = -100L, messageId = 4L, fileId = 40, title = "Coast S01E04", chatTitle = "Nature",
        sizeBytes = 1_000_000, durationSec = 2_640,
    )

    private val entry = WatchNext.Entry(WatchNext.Kind.Continue, video, 1_000_000, 2_640_000, 1_700_000_000_000L)

    @Before
    fun setUp() {
        tv = Robolectric.setupContentProvider(FakeTvProvider::class.java, TvContract.AUTHORITY)
        Robolectric.setupContentProvider(TvArtProvider::class.java, TvArtProvider.authority(context))
        telegramThumbnail = File(context.cacheDir, "tdlib_thumb.jpg").apply { writeBytes(bytes) }
        TvArtProvider.dir(context).deleteRecursively()
        // Telegram knows the video and its thumbnail (file 7), which is not on the device yet.
        WatchNextPublisher.mediaLookup = { _, _ -> video.toMediaItem().copy(thumbnailFileId = 7) }
        TvArt.thumbnailPath = { null }
        // Hermetic: no lookup of the title ever leaves the test.
        TvArt.onlinePictures = false
    }

    @After
    fun tearDown() {
        WatchNextPublisher.mediaLookup = { chat, message -> com.tmplayer.ui.browse.TdHomeSource.mediaItem(chat, message) }
        TvArt.thumbnailPath = { fileId -> Thumbnails.path(fileId) }
        TvArt.onlinePictures = true
    }

    private fun poster(): String? = tv.rows.values.single().getAsString(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI)

    private fun read(uri: String): ByteArray =
        context.contentResolver.openInputStream(Uri.parse(uri))!!.use { it.readBytes() }

    private fun apply(vararg changes: WatchNext.Change) = runBlocking { WatchNextPublisher.apply(context, changes.toList()) }

    private fun fill() = runBlocking { WatchNextPublisher.fillMissingArt(context) }

    @Test
    fun `a video whose thumbnail is on the device gets it as the entry's picture, readable by another caller`() {
        TvArt.thumbnailPath = { fileId -> telegramThumbnail.path.takeIf { fileId == 7 } }
        apply(WatchNext.Change.Upsert(entry))
        val uri = poster()!!
        assertTrue(uri, uri.startsWith("content://${TvArtProvider.authority(context)}/n_"))
        // Opened through the resolver, as the launcher opens it: the copy, not the app's mark.
        assertEquals(bytes.size, read(uri).size)
        assertEquals(TvContract.WatchNextPrograms.ASPECT_RATIO_16_9, tv.rows.values.single().getAsInteger(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO))
    }

    @Test
    fun `an entry written before the thumbnail exists wears the mark, then gets the picture once it does`() {
        apply(WatchNext.Change.Upsert(entry))
        assertTrue(poster().toString(), poster()!!.startsWith("android.resource://"))

        // Nothing has arrived: asked again, still the mark, and no error.
        fill()
        assertTrue(poster().toString(), poster()!!.startsWith("android.resource://"))

        TvArt.thumbnailPath = { fileId -> telegramThumbnail.path.takeIf { fileId == 7 } }
        fill()
        val uri = poster()!!
        assertTrue(uri, uri.startsWith("content://"))
        assertEquals(bytes.size, read(uri).size)
    }

    @Test
    fun `Telegram not answering leaves the mark, and the entry is still written`() {
        WatchNextPublisher.mediaLookup = { _, _ -> null }
        apply(WatchNext.Change.Upsert(entry))
        assertEquals(1, tv.rows.size)
        assertTrue(poster().toString(), poster()!!.startsWith("android.resource://"))
        assertEquals(
            WatchNextPublisher.mark(context).toString(),
            poster(),
        )
    }

    @Test
    fun `a copy evicted from the disk is made again and served again`() {
        TvArt.thumbnailPath = { fileId -> telegramThumbnail.path.takeIf { fileId == 7 } }
        apply(WatchNext.Change.Upsert(entry))
        val uri = poster()!!
        val copy = File(TvArtProvider.dir(context), Uri.parse(uri).lastPathSegment!!)
        assertTrue(copy.delete())
        // The launcher can no longer open it.
        var gone = false
        try {
            read(uri)
        } catch (_: FileNotFoundException) {
            gone = true
        }
        assertTrue(gone)

        fill()
        assertEquals(uri, poster())
        assertEquals(bytes.size, read(uri).size)
    }

    @Test
    fun `a stop that moves the position keeps the picture and asks Telegram nothing`() {
        TvArt.thumbnailPath = { fileId -> telegramThumbnail.path.takeIf { fileId == 7 } }
        apply(WatchNext.Change.Upsert(entry))
        val first = poster()
        var asked = 0
        WatchNextPublisher.mediaLookup = { _, _ -> asked++; null }
        apply(WatchNext.Change.Upsert(entry.copy(positionMs = 2_000_000, lastEngagementMs = entry.lastEngagementMs + 1)))
        assertEquals(0, asked)
        assertEquals(first, poster())
        assertEquals(2_000_000, tv.rows.values.single().getAsInteger(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS))
    }

    @Test
    fun `an entry taken off the home screen by the viewer is not given a picture`() {
        apply(WatchNext.Change.Upsert(entry))
        tv.rows.values.single().put(TvContract.WatchNextPrograms.COLUMN_BROWSABLE, 0)
        TvArt.thumbnailPath = { telegramThumbnail.path }
        fill()
        assertTrue(poster().toString(), poster()!!.startsWith("android.resource://"))
    }

    @Test
    fun `removing the entry removes its copy, and the channels' clean up leaves it alone`() {
        TvArt.thumbnailPath = { fileId -> telegramThumbnail.path.takeIf { fileId == 7 } }
        apply(WatchNext.Change.Upsert(entry))
        val copy = File(TvArtProvider.dir(context), Uri.parse(poster()!!).lastPathSegment!!)
        assertTrue(copy.isFile)

        // The channels' own prune: no kept names at all, and "Play next"'s copy is still there.
        TvArt.prune(context, TvArt.CHANNEL_KINDS, emptySet())
        assertTrue(copy.isFile)

        apply(WatchNext.Change.Remove(entry.internalId))
        assertTrue(tv.rows.isEmpty())
        assertFalse(copy.exists())
    }

    @Test
    fun `turning the row off takes the entries and their copies away`() {
        TvArt.thumbnailPath = { fileId -> telegramThumbnail.path.takeIf { fileId == 7 } }
        apply(WatchNext.Change.Upsert(entry))
        runBlocking { WatchNextPublisher.clearAll(context) }
        assertTrue(tv.rows.isEmpty())
        assertEquals(0, TvArtProvider.dir(context).listFiles()?.size ?: 0)
    }

    @Test
    fun `the provider is open to the launcher but only reads one picture by its bare name`() {
        val info = context.packageManager.resolveContentProvider(TvArtProvider.authority(context), 0)
        assertNotNull(info)
        assertTrue("another app must be able to open it", info!!.exported)

        val dir = TvArtProvider.dir(context).apply { mkdirs() }
        File(dir, "n_ab12.jpg").writeBytes(bytes)
        File(context.noBackupFilesDir, "secret.jpg").writeBytes(bytes)
        val base = "content://${TvArtProvider.authority(context)}"
        assertEquals(bytes.size, read("$base/n_ab12.jpg").size)

        // Nothing but a bare name of the fixed shape: no path, no other directory, no writing.
        listOf("$base/..%2Fsecret.jpg", "$base/n_ab12.jpg/x", "$base/secret.jpg", "$base/n_zz.jpg").forEach { bad ->
            var refused = false
            try {
                read(bad)
            } catch (_: FileNotFoundException) {
                refused = true
            }
            assertTrue(bad, refused)
        }
        var writeRefused = false
        try {
            context.contentResolver.openOutputStream(Uri.parse("$base/n_ab12.jpg"))
        } catch (_: SecurityException) {
            writeRefused = true
        } catch (_: FileNotFoundException) {
            writeRefused = true
        }
        assertTrue(writeRefused)
        assertNull(context.contentResolver.query(Uri.parse("$base/n_ab12.jpg"), null, null, null, null))
    }

    @Test
    fun `a card's copy that has gone from the disk is spotted, one that is there is not`() {
        val dir = TvArtProvider.dir(context).apply { mkdirs() }
        val here = "content://${TvArtProvider.authority(context)}/w_a1.jpg"
        assertTrue(TvArt.copyMissing(context, here))
        File(dir, "w_a1.jpg").writeBytes(bytes)
        assertFalse(TvArt.copyMissing(context, here))
        // Not one of this app's copies: nothing to check.
        assertFalse(TvArt.copyMissing(context, "android.resource://x/drawable/y"))
        assertFalse(TvArt.copyMissing(context, null))
    }

    /** The TV provider's watch next table, in memory: what the publisher inserts, updates and deletes. */
    class FakeTvProvider : ContentProvider() {
        val rows = linkedMapOf<Long, ContentValues>()
        private var next = 1L

        override fun onCreate(): Boolean = true

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val id = next++
            rows[id] = ContentValues(values)
            return ContentUris.withAppendedId(uri, id)
        }

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = projection ?: arrayOf("_id")
            val cursor = MatrixCursor(columns)
            rows.forEach { (id, values) ->
                cursor.addRow(columns.map { column ->
                    when (column) {
                        "_id" -> id
                        TvContract.WatchNextPrograms.COLUMN_BROWSABLE -> values.getAsInteger(column) ?: 1
                        else -> values.get(column)
                    }
                })
            }
            return cursor
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            val row = rows[ContentUris.parseId(uri)] ?: return 0
            row.putAll(values!!)
            return 1
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            if (rows.remove(ContentUris.parseId(uri)) != null) 1 else 0

        override fun getType(uri: Uri): String? = null
    }
}
