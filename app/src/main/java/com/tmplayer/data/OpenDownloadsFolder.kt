package com.tmplayer.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Opens the folder the viewer picked for downloads ("Other folder", HX57) in a file manager, for the
 * phone's Downloads page (Raju pass 2, 7.5: the desktop has Open folder, the phone had no way to
 * find its files). Only a picked folder can be opened this way: the default folder is inside the
 * app's own storage, which no file manager may browse, and there Keep > Save to folder and Save to
 * the gallery are the ways to a file manager.
 */
object OpenDownloadsFolder {

    /** The address of the folder itself, from the tree the picker gave; null for a malformed one. */
    fun folderUri(tree: Uri): Uri? = runCatching {
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    }.getOrNull()

    /** The intent that shows [tree] in a file manager, or null when the tree is not an openable folder. */
    fun intent(tree: Uri): Intent? {
        val folder = folderUri(tree) ?: return null
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** The picked folder when downloads are in one this app still holds the grant for, else null. */
    fun target(context: Context): Uri? =
        AndroidStorage.currentTree()?.takeIf { DownloadTree.hasGrant(context, it) }

    /** Opens [tree]; false when no app could (none installed, or the grant was taken back). Never throws. */
    fun open(context: Context, tree: Uri): Boolean {
        val intent = intent(tree) ?: return false
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }
}
