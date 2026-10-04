package com.tmplayer.data

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

    const val CANNOT_WRITE = "TMPlayer cannot write there."
    const val NESTED = "That folder is inside the one TMPlayer uses now, or holds it. Choose another."
    const val SAME_PLACE = "TMPlayer keeps its files there already."
    const val NETWORK_WARNING = "Network drives are slow to stream from and may disconnect."

    /**
     * Whether [candidate] will do as the new storage location, checked in the order the viewer
     * would want to hear about it.
     *
     * @param currentFolders the folders TMPlayer uses now (cache and downloads); the new
     *   `TMPlayer` folder may be neither inside one of them nor hold one.
     * @param currentRoot the storage location now chosen, or null for the default folders.
     * @param downloadsBytes what the downloads that would move take altogether.
     */
    fun validate(
        candidate: Candidate,
        currentFolders: List<File>,
        currentRoot: File?,
        downloadsBytes: Long,
    ): Verdict {
        val base = appFolder(candidate.root)
        if (currentRoot != null && same(candidate.root, currentRoot)) return Verdict.Refused(SAME_PLACE)
        if (!candidate.writable) return Verdict.Refused(CANNOT_WRITE)
        if (currentFolders.any { inside(base, it) || inside(it, base) }) return Verdict.Refused(NESTED)
        val needed = downloadsBytes + SPARE_BYTES
        if (candidate.usableBytes < needed) {
            return Verdict.Refused(
                "That drive has ${size(candidate.usableBytes)} free. " +
                    "The downloads alone need ${size(needed)}.",
            )
        }
        val network = isNetworkPath(candidate.root.path) || candidate.storeType.lowercase() in NETWORK_STORE_TYPES
        return Verdict.Allowed(adopt = candidate.markerExists, warning = if (network) NETWORK_WARNING else null)
    }

    /** A size as the rest of the app writes it, with nothing at all written as "0 MB". */
    fun size(bytes: Long): String = if (bytes <= 0) "0 MB" else MediaMapper.formatSize(bytes)

    /** `<root>/TMPlayer`. */
    fun appFolder(root: File): File = File(root, APP_FOLDER)

    /** A UNC path (`\\server\share`), which Windows reports as an ordinary file store. */
    fun isNetworkPath(path: String): Boolean = path.startsWith("\\\\") || path.startsWith("//")

    /** Whether [child] is [parent] or somewhere below it, by absolute normalised path. */
    fun inside(child: File, parent: File): Boolean {
        val c = child.absoluteFile.normalize().toPath()
        val p = parent.absoluteFile.normalize().toPath()
        return c.startsWith(p)
    }

    private fun same(a: File, b: File): Boolean =
        a.absoluteFile.normalize().toPath() == b.absoluteFile.normalize().toPath()

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
    ): Pair<String, String> {
        val title = "Move TMPlayer's files to $where?"
        val videos = if (downloadCount == 1) "1 video" else "$downloadCount videos"
        val downloads = if (downloadCount == 0) {
            "There are no downloads to move; new ones go to $newDownloads."
        } else {
            "Downloads ($videos, ${size(downloadsBytes)}) move to $newDownloads."
        }
        val cache = if (cacheBytes > 0) "The cache (${size(cacheBytes)})" else "The cache"
        val adopted = if (adopt) " TMPlayer has used this folder before, and the downloads already in it are kept." else ""
        val body = "$downloads $cache is cleared and starts again at $newCache. Nothing is removed from Telegram. " +
            "Playback and downloads pause while this happens.$adopted"
        return title to body
    }

    /**
     * The downloads that still have to go to [downloadsDir]: every record with a file of its own
     * that is not there already. A record whose file has gone is left for the Downloads page to
     * show as missing; moving nothing is not something to report as a failure.
     */
    fun toMove(records: List<ResumeRecord>, downloadsDir: File, exists: (File) -> Boolean = File::isFile): List<ResumeRecord> =
        records.filter { record ->
            val path = record.localPath ?: return@filter false
            val file = File(path)
            exists(file) && !inside(file, downloadsDir)
        }

    /**
     * A move that has begun and not finished, as written to the desktop's settings before the
     * first file goes, so a crash or a kill can be finished on the next launch.
     *
     * @param root the new storage location, or blank for the default folders.
     * @param oldCache the cache folder that was in use before, deleted once the new one is running.
     * @param oldDownloads the downloads folder before, removed at the end if TMPlayer made it and
     *   it is empty.
     */
    data class Pending(val root: String, val oldCache: String, val oldDownloads: String) {
        fun encode(): String = listOf(root, oldCache, oldDownloads).joinToString(SEP.toString()) { it.replace(SEP, ' ') }

        companion object {
            private const val SEP = '\u001F'

            /** Null for blank or unreadable text: no move is pending. */
            fun decode(text: String?): Pending? {
                if (text.isNullOrBlank()) return null
                val parts = text.split(SEP)
                if (parts.size != 3) return null
                return Pending(parts[0], parts[1], parts[2])
            }
        }
    }
}
