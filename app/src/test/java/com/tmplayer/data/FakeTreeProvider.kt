package com.tmplayer.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.FileNotFoundException

/**
 * A stand-in for the device's own storage provider (`com.android.externalstorage.documents`),
 * serving a plain folder as "primary:" so a picked-folder download can be written, read and deleted
 * in a test with no phone. A plain [ContentProvider] answering the document calls, because
 * Robolectric reaches providers through the old query signature that `DocumentsProvider` refuses.
 * [revoked] answers every call with the SecurityException a taken-back grant gives; [full] hands
 * out `/dev/full` for writing, which fails every write with ENOSPC the way a full drive does.
 */
class FakeTreeProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private val columns = arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_LAST_MODIFIED,
    )

    private fun check() {
        if (revoked) throw SecurityException("grant revoked")
    }

    private fun fileOf(id: String): File = File(base, id.removePrefix("primary:"))

    private fun idOf(file: File): String =
        "primary:" + file.relativeTo(base).path.let { if (it == ".") "" else it }

    private fun row(cursor: MatrixCursor, file: File) {
        if (!file.exists()) throw FileNotFoundException(file.path)
        val directory = file.isDirectory
        val values = mapOf<String, Any?>(
            Document.COLUMN_DOCUMENT_ID to idOf(file),
            Document.COLUMN_DISPLAY_NAME to file.name.ifEmpty { "primary" },
            Document.COLUMN_SIZE to if (directory) null else file.length(),
            Document.COLUMN_MIME_TYPE to if (directory) Document.MIME_TYPE_DIR else "video/x-matroska",
            Document.COLUMN_FLAGS to if (directory) Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE
            else Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE,
            Document.COLUMN_LAST_MODIFIED to file.lastModified(),
        )
        cursor.addRow(cursor.columnNames.map { values[it] }.toTypedArray())
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        check()
        val file = fileOf(DocumentsContract.getDocumentId(uri))
        val cursor = MatrixCursor(projection?.map { it }?.toTypedArray() ?: columns)
        if (uri.pathSegments.lastOrNull() == "children") {
            file.listFiles().orEmpty().forEach { row(cursor, it) }
        } else {
            row(cursor, file)
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        check()
        val file = if (full && mode.contains('w')) File("/dev/full") else fileOf(DocumentsContract.getDocumentId(uri))
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        check()
        val target = extras?.getParcelable<Uri>("uri") ?: return null
        return when (method) {
            "android:createDocument" -> {
                val parent = fileOf(DocumentsContract.getDocumentId(target))
                val file = File(parent, extras.getString(Document.COLUMN_DISPLAY_NAME).orEmpty())
                if (!file.createNewFile()) throw FileNotFoundException(file.path)
                Bundle().apply { putParcelable("uri", DocumentsContract.buildDocumentUri(StoredLocation.LOCAL_PROVIDER, idOf(file))) }
            }
            "android:deleteDocument" -> {
                fileOf(DocumentsContract.getDocumentId(target)).delete()
                Bundle()
            }
            else -> null
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        lateinit var base: File
        @Volatile var revoked = false
        @Volatile var full = false
    }
}
