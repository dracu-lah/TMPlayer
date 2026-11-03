package com.tmplayer.data

/**
 * Reads the two stored strings that name where a download lives (HX57): the chosen downloads
 * location ([SettingsStore.downloadRoot]) and a record's [ResumeRecord.localPath]. Both were
 * always a plain path; since "Other folder" they can also be a `content://` string from Android's
 * folder picker, told apart by the prefix alone, so no existing value changes meaning and an older
 * build reading a newer value just finds a path that does not exist.
 *
 * Pure text, so every line is tested without a phone. Nothing here throws: the value comes off the
 * disk, where it can be cut short, hand edited or from a build that is gone.
 */
object StoredLocation {

    /** Where a stored downloads location points. */
    enum class Kind {
        /** Blank: the app's own folder on internal storage, as it always was. */
        Internal,

        /** A path to an SD card or USB drive's app folder (HX26). */
        Volume,

        /** A folder the viewer picked through the system picker, on this device or a card. */
        Tree,

        /** A `content://` value that is not a local folder (a cloud drive, or garbage): never used. */
        Unusable,
    }

    /** The only provider accepted: the device's own storage. A cloud folder cannot seek, so it cannot play. */
    const val LOCAL_PROVIDER = "com.android.externalstorage.documents"

    private const val CONTENT = "content://"
    private const val TREE_PREFIX = "$CONTENT$LOCAL_PROVIDER/tree/"
    private const val DOCUMENT = "/document/"

    fun kindOf(value: String?): Kind = when {
        value.isNullOrBlank() -> Kind.Internal
        value.startsWith(TREE_PREFIX) && value.length > TREE_PREFIX.length -> Kind.Tree
        value.startsWith(CONTENT) -> Kind.Unusable
        else -> Kind.Volume
    }

    /** Whether a record's path is a document in a picked folder rather than a file on the disk. */
    fun isDocument(localPath: String?): Boolean = localPath != null && localPath.startsWith(CONTENT)

    /** Whether [document] (a record's path) lies inside the folder [tree] names. */
    fun documentIsIn(tree: String, document: String): Boolean {
        if (kindOf(tree) != Kind.Tree) return false
        val base = tree.substringBefore(DOCUMENT)
        return document.startsWith(base + DOCUMENT) && document.length > base.length + DOCUMENT.length
    }
}
