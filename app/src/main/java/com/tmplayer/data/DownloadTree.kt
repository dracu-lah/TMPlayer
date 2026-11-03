package com.tmplayer.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A folder the viewer picked for downloads ("Other folder", HX57), through Android's folder
 * picker: the grant that keeps it reachable, whether it still is, and writing a video into it or
 * reading one back out. A picked folder is kept as its `content://` string in the same
 * [SettingsStore.downloadRoot] as a volume's path ([StoredLocation] tells them apart).
 *
 * The copy goes through [FolderExport]'s SAF folder and [FolderCopyIo.pipe], the code Save to
 * folder already trusts: a copy is written under a free name, its size is read back and compared,
 * and any copy that failed, was cancelled or came out the wrong size is deleted, so no partial
 * video is ever left in the viewer's folder. The original is only removed by the caller after this
 * returns.
 */
object DownloadTree {

    /** The folder in [value], or null for a blank, malformed or non-local value. Never throws. */
    fun parse(value: String?): Uri? {
        if (StoredLocation.kindOf(value) != StoredLocation.Kind.Tree) return null
        return runCatching { Uri.parse(value) }.getOrNull()?.takeIf { it.authority == StoredLocation.LOCAL_PROVIDER }
    }

    /** Whether this app still holds the read and write grant the picker gave for [tree]. */
    fun hasGrant(context: Context, tree: Uri): Boolean = runCatching {
        context.contentResolver.persistedUriPermissions.any {
            it.uri == tree && it.isReadPermission && it.isWritePermission
        }
    }.getOrDefault(false)

    /** Keeps the grant across restarts and reboots; false when Android would not (the picker lost it). */
    fun take(context: Context, tree: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        true
    }.getOrDefault(false)

    /** Lets go of the grant; the folder and what is in it are untouched. */
    fun release(context: Context, tree: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    /**
     * Whether downloads can be written into [tree] right now: the grant is still held, the folder
     * still exists (not deleted, its card not out) and the provider lets files be created in it.
     * Asked before every use, since Android gives no notice when any of those goes.
     */
    fun usable(context: Context, tree: Uri): Boolean {
        if (!hasGrant(context, tree)) return false
        val folder = FolderExport.folderFor(context, tree) ?: return false
        return runCatching { folder.writable() }.getOrDefault(false)
    }

    /** The folder's own name, as the system shows it. */
    fun label(context: Context, tree: Uri): String =
        runCatching { FolderExport.folderFor(context, tree)?.label }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: tree.lastPathSegment?.substringAfter(':')?.ifBlank { null }
            ?: tree.toString()

    /** Room on the folder's drive, or null when the provider gives no path to ask (said plainly, never guessed). */
    fun freeBytes(tree: Uri): Long? = FolderExport.freeBytes(tree)

    /** [created] as a document of [tree], the form every read and delete here uses. */
    fun documentIn(tree: Uri, created: Uri): Uri =
        runCatching { DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getDocumentId(created)) }
            .getOrDefault(created)

    /**
     * Releases every grant TMPlayer holds for a folder that is no longer the downloads folder and
     * has no download in it, so the system's limit on grants is never used up by folders given up.
     */
    suspend fun releaseUnused(context: Context, settings: SettingsStore) {
        val keep = runCatching { settings.downloadRootNow() }.getOrDefault("")
        val records = runCatching { settings.downloadsNow() }.getOrDefault(emptyList())
        val held = runCatching { context.contentResolver.persistedUriPermissions.toList() }.getOrDefault(emptyList())
        for (grant in held) {
            val tree = grant.uri.toString()
            if (StoredLocation.kindOf(tree) != StoredLocation.Kind.Tree) continue
            if (tree == keep) continue
            if (records.any { StoredLocation.documentIsIn(tree, it.localPath.orEmpty()) }) continue
            release(context, grant.uri)
        }
    }

    /**
     * Writes one video into [tree] as [name] (or "name (2)" when taken) from the stream [open]
     * makes, and returns its document. The copy's size is read back and must equal [length]; a
     * failure, a cancel or a wrong size deletes the copy before the exception leaves.
     *
     * @param onProgress bytes written so far, and [length].
     * @throws IOException the folder is not writable, the drive is full ("No space left"), the
     *   grant was taken back, or the copy came out the wrong size.
     * @throws CancellationException when the caller's job is cancelled mid copy.
     */
    suspend fun put(
        context: Context,
        tree: Uri,
        name: String,
        length: Long,
        open: () -> java.io.InputStream,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): Uri = withContext(Dispatchers.IO) {
        val folder = FolderExport.folderFor(context, tree) ?: throw IOException("Folder not reachable")
        val handle: Uri
        try {
            if (!folder.writable()) throw IOException("Folder not writable")
            val taken = folder.existing().mapTo(HashSet()) { it.lowercase() }
            handle = folder.create(FolderCopy.freeName(name) { it.lowercase() in taken }, "video/*")
        } catch (e: SecurityException) {
            throw IOException("Folder permission lost", e)
        }
        val job = currentCoroutineContext()
        try {
            val whole = folder.open(handle).use { out ->
                open().use { input ->
                    FolderCopyIo.pipe(input, out, { !job.isActive }) { onProgress(it, length) }
                }
            }
            if (!whole) throw CancellationException("Copy cancelled")
            val written = runCatching { folder.size(handle) }.getOrNull()
                ?: runCatching { LocalVideo.InTree(handle, context.contentResolver).length() }.getOrNull()
            // A provider that will not say what it wrote cannot be checked, so the original is not
            // let go of on its word: treated as a failed copy.
            if (written != length) throw IOException("Copied ${written ?: "an unknown number of"} of $length bytes")
            documentIn(tree, handle)
        } catch (failure: Throwable) {
            withContext(NonCancellable) { runCatching { folder.discard(handle) } }
            throw if (failure is SecurityException) IOException("Folder permission lost", failure) else failure
        }
    }

    /** [put] for a file on the disk. */
    suspend fun putFile(
        context: Context,
        tree: Uri,
        file: File,
        name: String,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): Uri {
        if (!file.isFile) throw FileNotFoundException("Nothing to move at ${file.absolutePath}")
        return put(context, tree, name, file.length(), { file.inputStream() }, onProgress)
    }

    /**
     * Copies a video out of a picked folder into [dir] as [name] (or a free variant), through a
     * `.part` file renamed when whole and size checked, so a crash or cancel leaves no half file
     * that looks finished. The document itself is left for the caller to delete.
     */
    suspend fun pull(
        video: LocalVideo.InTree,
        dir: File,
        name: String,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val total = video.length() ?: throw FileNotFoundException("Nothing to move at ${video.document}")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Could not create ${dir.absolutePath}")
        val target = DownloadFiles.freeName(dir, name)
        val part = File(dir, target.name + DownloadFiles.PART_SUFFIX)
        val job = currentCoroutineContext()
        try {
            val whole = video.open().use { input ->
                FileOutputStream(part).use { out ->
                    FolderCopyIo.pipe(input, out, { !job.isActive }) { onProgress(it, total) }.also { out.fd.sync() }
                }
            }
            if (!whole) throw CancellationException("Copy cancelled")
            if (part.length() != total) throw IOException("Copied ${part.length()} of $total bytes of $name")
            try {
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(part.toPath(), target.toPath())
            }
            target
        } catch (failure: Throwable) {
            runCatching { Files.deleteIfExists(part.toPath()) }
            throw if (failure is SecurityException) IOException("Folder permission lost", failure) else failure
        }
    }
}
