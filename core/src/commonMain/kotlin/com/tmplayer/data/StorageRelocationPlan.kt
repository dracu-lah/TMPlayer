package com.tmplayer.data

import com.tmplayer.i18n.L
import java.io.File

/**
 * The parts of moving TMPlayer's files to a new storage location that are decisions rather than
 * work: whether a chosen folder will do, what the confirm dialog says, which downloads still have
 * to move, and how an unfinished move is written down so the next launch can finish it.
 *
 * Kept free of TDLib and of the file system's side effects (every fact about the disk is passed
 * in), so each rule can be tested on its own. The desktop's `StorageRelocation` does the work.
 */
object StorageRelocationPlan {

    /** The folder TMPlayer makes inside whatever the viewer chose, and keeps everything in. */
    const val APP_FOLDER = "TMPlayer"

    /** The downloads folder's name inside [APP_FOLDER] on a chosen location. */
    const val DOWNLOADS_FOLDER = "downloads"

    /** Room kept for a file's name in the path length check (names are cut to 200 bytes, most are far shorter). */
    const val LONGEST_NAME = 120

    /** The file that marks a folder as TMPlayer's own, so a later choice of it can be recognised. */
    const val MARKER = ".tmplayer"

    /** Room left over on the new drive beyond the downloads themselves, before a move is allowed. */
    const val SPARE_BYTES = 1024L * 1024 * 1024

    /** File system types that live on another machine, and are slow and can vanish mid stream. */
    val NETWORK_STORE_TYPES = setOf("cifs", "smbfs", "smb", "smb2", "nfs", "nfs4", "fuse.sshfs", "sshfs", "afpfs", "webdav", "davfs")

    /** What the disk says about a folder the viewer picked. Gathered by the caller. */
    data class Candidate(
        /** The folder picked; TMPlayer's own folder goes inside it. */
        val root: File,
        /** Whether a test file could be written inside `<root>/TMPlayer`. */
        val writable: Boolean,
        /** Free bytes on the drive [root] is on. */
        val usableBytes: Long,
        /** The file system type of that drive, as `FileStore.type()` reports it, or blank. */
        val storeType: String = "",
        /** Whether `<root>/TMPlayer/.tmplayer` is there already, from an earlier install or choice. */
        val markerExists: Boolean = false,
    )

    /** The answer to a chosen folder. */
    sealed interface Verdict {
        /** Not usable, for the reason given in a sentence the viewer reads. */
        data class Refused(val reason: String) : Verdict

        /**
         * Usable. [adopt] is true when TMPlayer has been there before and its downloads folder can
         * be taken on as it is; [warning] is a caution to show beside the confirm button.
         */
        data class Allowed(val adopt: Boolean, val warning: String?) : Verdict
    }

    val CANNOT_WRITE: String get() = L.storageCannotWrite
    val NESTED: String get() = L.storageNested
    val SAME_PLACE: String get() = L.storageSamePlace
    val NETWORK_WARNING: String get() = L.storageNetworkWarning

    /**
     * Whether [candidate] will do as the new storage location, checked in the order the viewer
     * would want to hear about it.
     *
     * @param currentFolders the folders TMPlayer uses now (cache and downloads); the new
     *   `TMPlayer` folder may be neither inside one of them nor hold one.
     * @param currentRoot the storage location now chosen, or null for the default folders.
     * @param downloadsBytes what the downloads that would move take altogether; zero when the
     *   downloads are left where they are, so only the folder itself has to fit.
     * @param pathLimit the longest path the system keeps files at (259 on Windows), or 0 for none:
     *   the new downloads folder must leave [LONGEST_NAME] characters for a file's name.
     */
    fun validate(
        candidate: Candidate,
        currentFolders: List<File>,
        currentRoot: File?,
        downloadsBytes: Long,
        pathLimit: Int = 0,
    ): Verdict {
        val base = appFolder(candidate.root)
        if (currentRoot != null && same(candidate.root, currentRoot)) return Verdict.Refused(SAME_PLACE)
        if (!candidate.writable) return Verdict.Refused(CANNOT_WRITE)
        if (pathLimit > 0 && File(base, DOWNLOADS_FOLDER).path.length + 1 + LONGEST_NAME > pathLimit) {
            return Verdict.Refused(L.storagePathTooLong)
        }
        if (currentFolders.any { inside(base, it) || inside(it, base) }) return Verdict.Refused(NESTED)
        val needed = downloadsBytes + SPARE_BYTES
        if (candidate.usableBytes < needed) {
            return Verdict.Refused(
                L.storageDriveTooSmall(size(candidate.usableBytes), size(needed)),
            )
        }
        val network = isNetworkPath(candidate.root.path) || candidate.storeType.lowercase() in NETWORK_STORE_TYPES
        return Verdict.Allowed(adopt = candidate.markerExists, warning = if (network) NETWORK_WARNING else null)
    }

    /** A size as the rest of the app writes it, with nothing at all written as "0 MB". */
    fun size(bytes: Long): String = if (bytes <= 0) L.formatSizeZero else MediaMapper.formatSize(bytes)

    /** `<root>/TMPlayer`. */
    fun appFolder(root: File): File = File(root, APP_FOLDER)

    /** A UNC path (`\\server\share`), which Windows reports as an ordinary file store. */
    fun isNetworkPath(path: String): Boolean = path.startsWith("\\\\") || path.startsWith("//")

    /** Whether [child] is [parent] or somewhere below it, by absolute normalised path. */
    fun inside(child: File, parent: File): Boolean = resolved(child).startsWith(resolved(parent))

    private fun same(a: File, b: File): Boolean = resolved(a) == resolved(b)

    /**
     * [file] with every symbolic link in it followed as far as the path exists, then the rest
     * (not made yet) added on: a folder reached through a link to the current one is that folder.
     * Falls back to the plain absolute path when the disk cannot be asked.
     */
    fun resolved(file: File): java.nio.file.Path {
        val plain = file.absoluteFile.normalize().toPath()
        return runCatching {
            var existing: java.nio.file.Path? = plain
            while (existing != null && !java.nio.file.Files.exists(existing)) existing = existing.parent
            if (existing == null) plain else existing.toRealPath().resolve(existing.relativize(plain).toString()).normalize()
        }.getOrDefault(plain)
    }

    /** Whether the downloads, [bytes] of them, fit in [freeBytes] with [SPARE_BYTES] to spare. Nothing to move always fits. */
    fun fits(bytes: Long, freeBytes: Long): Boolean = bytes <= 0 || freeBytes >= bytes + SPARE_BYTES

    /** Whether [e] is the file system saying the disk is full, in the words the JDK, Linux, macOS and Windows use. */
    fun isNoSpace(e: Throwable): Boolean {
        val text = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" ")
        return NO_SPACE_WORDS.any { text.contains(it, ignoreCase = true) }
    }

    private val NO_SPACE_WORDS = listOf("No space left", "ENOSPC", "disk full", "not enough space on the disk", "not enough disk space", "quota exceeded")

    /**
     * The confirm dialog's title and body, B2.3's wording.
     *
     * @param where the new location as the viewer would recognise it (the folder picked).
     */
    fun confirmText(
        where: String,
        newDownloads: String,
        newCache: String,
        downloadCount: Int,
        downloadsBytes: Long,
        cacheBytes: Long,
        adopt: Boolean,
        moveDownloads: Boolean = true,
    ): Pair<String, String> {
        val title = L.storageMoveTitle(where)
        val downloads = when {
            downloadCount == 0 -> L.storageMoveNoDownloads(newDownloads)
            moveDownloads -> L.storageMoveDownloads(downloadCount, size(downloadsBytes), newDownloads)
            else -> L.storageMoveStay(downloadCount, size(downloadsBytes), newDownloads)
        }
        val cache = if (cacheBytes > 0) L.storageMoveCacheSized(size(cacheBytes), newCache) else L.storageMoveCache(newCache)
        val parts = listOf(downloads, cache, L.storageMoveBody) + if (adopt) listOf(L.storageMoveAdopted) else emptyList()
        val body = parts.joinToString(" ")
        return title to body
    }

    /**
     * The downloads that still have to go to [downloadsDir]: every record with a file of its own
     * that is not there already. A record whose file has gone is left for the Downloads page to
     * show as missing; moving nothing is not something to report as a failure.
     */
    fun toMove(records: List<ResumeRecord>, downloadsDir: File, exists: (File) -> Boolean = File::isFile): List<ResumeRecord> {
        val target = resolved(downloadsDir)
        // Judged by the folder the file sits in, so a link lying in the new folder counts as there.
        val parents = HashMap<File?, Boolean>()
        return records.filter { record ->
            val path = record.localPath ?: return@filter false
            val file = File(path)
            if (!exists(file)) return@filter false
            val parent = file.absoluteFile.parentFile
            !parents.getOrPut(parent) { parent != null && resolved(parent).startsWith(target) }
        }
    }

    /**
     * Records whose file is gone from the path they name but is there, under the same name, in
     * [downloadsDir]: a move that landed the file and was cut off before the record was written
     * (a kill, a failed settings write). Each comes with the file to point the record at.
     */
    fun adoptable(records: List<ResumeRecord>, downloadsDir: File, exists: (File) -> Boolean = File::isFile): List<Pair<ResumeRecord, File>> =
        records.mapNotNull { record ->
            val old = File(record.localPath ?: return@mapNotNull null)
            if (exists(old)) return@mapNotNull null
            val found = File(downloadsDir, old.name)
            if (exists(found)) record to found else null
        }

    /** Records that name a file of their own that is no longer on the disk, so a move cannot carry them. */
    fun missing(records: List<ResumeRecord>, exists: (File) -> Boolean = File::exists): List<ResumeRecord> =
        records.filter { record -> record.localPath?.let { !exists(File(it)) } == true }

    /**
     * A move that has begun and not finished, as written to the desktop's settings before the
     * first file goes, so a crash or a kill can be finished on the next launch.
     *
     * @param root the new storage location, or blank for the default folders.
     * @param oldCache the cache folder that was in use before, deleted once the new one is running.
     * @param oldDownloads the downloads folder before, removed at the end if TMPlayer made it and
     *   it is empty.
     * @param moveDownloads whether the downloads were to move too (the viewer's tick); a move
     *   written down by an older version had no such choice and moved them.
     */
    data class Pending(val root: String, val oldCache: String, val oldDownloads: String, val moveDownloads: Boolean = true) {
        fun encode(): String =
            listOf(root, oldCache, oldDownloads, if (moveDownloads) "1" else "0").joinToString(SEP.toString()) { it.replace(SEP, ' ') }

        companion object {
            private const val SEP = '\u001F'

            /** Null for blank or unreadable text: no move is pending. */
            fun decode(text: String?): Pending? {
                if (text.isNullOrBlank()) return null
                val parts = text.split(SEP)
                if (parts.size != 3 && parts.size != 4) return null
                return Pending(parts[0], parts[1], parts[2], parts.getOrNull(3) != "0")
            }
        }
    }
}
