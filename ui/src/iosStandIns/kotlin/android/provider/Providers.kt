package android.provider

import android.content.ContentResolver
import android.net.Uri

/** The Storage Access Framework has no iOS counterpart: no tree or document id is ever valid. */
object DocumentsContract {
    const val EXTRA_INITIAL_URI = "android.provider.extra.INITIAL_URI"
    fun getTreeDocumentId(uri: Uri): String = throw IllegalArgumentException("Not a document tree: $uri")
    fun getDocumentId(uri: Uri): String = throw IllegalArgumentException("Not a document: $uri")
    fun buildDocumentUriUsingTree(tree: Uri, documentId: String): Uri = Uri.parse("$tree/document/$documentId")
    fun buildChildDocumentsUriUsingTree(tree: Uri, parentDocumentId: String): Uri = Uri.parse("$tree/children/$parentDocumentId")
    fun buildTreeDocumentUri(authority: String, documentId: String): Uri = Uri.parse("content://$authority/tree/$documentId")
    fun isTreeUri(uri: Uri): Boolean = false
    fun isDocumentUri(context: android.content.Context?, uri: Uri?): Boolean = false
    fun createDocument(resolver: ContentResolver, parent: Uri, mime: String, name: String): Uri? = null
    fun deleteDocument(resolver: ContentResolver, document: Uri): Boolean = false
    fun renameDocument(resolver: ContentResolver, document: Uri, name: String): Uri? = null

    object Document {
        const val COLUMN_DOCUMENT_ID = "document_id"
        const val COLUMN_DISPLAY_NAME = "_display_name"
        const val COLUMN_MIME_TYPE = "mime_type"
        const val COLUMN_SIZE = "_size"
        const val COLUMN_LAST_MODIFIED = "last_modified"
        const val MIME_TYPE_DIR = "vnd.android.document/directory"
    }
}

object MediaStore {
    /** The photo gallery, Android's; on iOS an insert here goes to Photos (ContentResolver.insert, PhotosInbox). */
    object Images {
        object Media {
            val EXTERNAL_CONTENT_URI: android.net.Uri = android.net.Uri.parse("content://media/external/images/media")
            const val DISPLAY_NAME = "_display_name"
            const val MIME_TYPE = "mime_type"
            const val RELATIVE_PATH = "relative_path"
            const val IS_PENDING = "is_pending"
            const val DATE_ADDED = "date_added"
        }
    }

    const val VOLUME_EXTERNAL_PRIMARY = "external_primary"
    const val MEDIA_IGNORE_FILENAME = ".nomedia"

    object MediaColumns {
        const val _ID = "_id"
        const val DATE_ADDED = "date_added"
        const val DISPLAY_NAME = "_display_name"
        const val MIME_TYPE = "mime_type"
        const val RELATIVE_PATH = "relative_path"
        const val IS_PENDING = "is_pending"
        const val SIZE = "_size"
        const val DATA = "_data"
    }

    object Video {
        object Media {
            val EXTERNAL_CONTENT_URI: Uri = Uri.parse("content://media/external/video/media")
            fun getContentUri(volume: String): Uri = EXTERNAL_CONTENT_URI
        }
    }

    object Downloads {
        val EXTERNAL_CONTENT_URI: Uri = Uri.parse("content://media/external/downloads")
        fun getContentUri(volume: String): Uri = EXTERNAL_CONTENT_URI
    }
}

object OpenableColumns {
    const val DISPLAY_NAME = "_display_name"
    const val SIZE = "_size"
}

object Settings {
    const val ACTION_APPLICATION_DETAILS_SETTINGS = "android.settings.APPLICATION_DETAILS_SETTINGS"
    const val ACTION_MANAGE_UNKNOWN_APP_SOURCES = "android.settings.MANAGE_UNKNOWN_APP_SOURCES"
    const val ACTION_APP_NOTIFICATION_SETTINGS = "android.settings.APP_NOTIFICATION_SETTINGS"
    const val ACTION_APP_LOCALE_SETTINGS = "android.settings.APP_LOCALE_SETTINGS"
    const val EXTRA_APP_PACKAGE = "android.provider.extra.APP_PACKAGE"

    /** Android's exception for a setting that is not there. */
    class SettingNotFoundException(message: String) : Exception(message)

    /** System settings Android's apps read; on iOS, the ones iOS has an answer for. */
    object System {
        const val SCREEN_BRIGHTNESS = "screen_brightness"
        const val SCREEN_BRIGHTNESS_MODE = "screen_brightness_mode"
        const val SCREEN_BRIGHTNESS_MODE_MANUAL = 0
        const val SCREEN_BRIGHTNESS_MODE_AUTOMATIC = 1

        fun getInt(resolver: android.content.ContentResolver?, name: String): Int = when (name) {
            SCREEN_BRIGHTNESS -> (platform.UIKit.UIScreen.mainScreen.brightness * 255).toInt().coerceIn(0, 255)
            SCREEN_BRIGHTNESS_MODE -> SCREEN_BRIGHTNESS_MODE_MANUAL
            else -> throw SettingNotFoundException(name)
        }

        fun getInt(resolver: android.content.ContentResolver?, name: String, def: Int): Int =
            runCatching { getInt(resolver, name) }.getOrDefault(def)
    }
}

object BaseColumns {
    const val _ID = "_id"
    const val _COUNT = "_count"
}
