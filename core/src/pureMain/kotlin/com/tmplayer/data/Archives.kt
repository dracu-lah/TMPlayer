package com.tmplayer.data

import com.tmplayer.i18n.L

/**
 * A compressed file posted in a chat: a ZIP, a RAR, a 7z or one of their volumes. Listed under a
 * chat's videos as something that cannot be played here, with the reason, so a chat that posts its
 * videos packed in archives does not read as a chat with nothing in it.
 */
@androidx.compose.runtime.Immutable
data class ArchiveFile(
    val chatId: Long,
    val messageId: Long,
    val fileId: Int,
    val fileName: String,
    val sizeBytes: Long,
    val date: Int,
    val kind: ArchiveKind,
    /** One volume of several (`name.part2.rar`, `name.7z.003`), which is never a whole archive. */
    val volume: Boolean = false,
    /** The name or the caption says a password is needed ("password: 1234"). */
    val locked: Boolean = false,
) {
    /** Why it is not played, in a sentence. */
    val reason: String get() = Archives.reason(this)

    /** The same reason as a value a test or a badge can switch on. */
    val why: ArchiveWhy get() = Archives.why(this)
}

/** What makes an archive a dead end for a player, in the order the row names it. */
enum class ArchiveWhy { Volume, Password, Huge, Compressed }

/** Which kind of archive, as far as the name says. */
enum class ArchiveKind(val label: String) {
    Zip("ZIP"),
    Rar("RAR"),
    SevenZip("7z"),
    /** A tarball or a single compressed stream: `.tar`, `.gz`, `.tgz`, `.bz2`, `.xz`, `.zst`. */
    Other("archive"),
}

object Archives {

    /** The kind [fileName] (or, without a telling name, [mimeType]) is, or null for no archive. */
    fun kindOf(fileName: String, mimeType: String = ""): ArchiveKind? {
        val name = fileName.trim().lowercase()
        return when {
            ZIP.containsMatchIn(name) -> ArchiveKind.Zip
            RAR.containsMatchIn(name) -> ArchiveKind.Rar
            SEVEN_ZIP.containsMatchIn(name) -> ArchiveKind.SevenZip
            OTHER.containsMatchIn(name) -> ArchiveKind.Other
            mimeType.lowercase() in ZIP_TYPES -> ArchiveKind.Zip
            mimeType.lowercase() in RAR_TYPES -> ArchiveKind.Rar
            mimeType.lowercase() == "application/x-7z-compressed" -> ArchiveKind.SevenZip
            else -> null
        }
    }

    /** One numbered volume of a split archive rather than a whole one. */
    fun isVolume(fileName: String): Boolean = VOLUME.containsMatchIn(fileName.trim().lowercase())

    /**
     * The sentence a row carries. A volume needs all of its fellows, and every archive needs
     * unpacking before what is inside it can be played: TMPlayer streams a video straight from
     * Telegram, and a compressed file has to be read whole and unpacked first.
     */
    fun reason(file: ArchiveFile): String = when (why(file)) {
        ArchiveWhy.Volume -> L.gridArchiveVolumeReason(file.kind.label)
        ArchiveWhy.Password -> L.gridArchivePasswordReason(file.kind.label)
        ArchiveWhy.Huge -> L.gridArchiveHugeReason(file.kind.label)
        ArchiveWhy.Compressed -> L.gridArchiveReason(file.kind.label)
    }

    /** A volume is named first (it is never whole), then a password, then a size nobody opens in place. */
    fun why(file: ArchiveFile): ArchiveWhy = when {
        file.volume -> ArchiveWhy.Volume
        file.locked -> ArchiveWhy.Password
        file.sizeBytes >= HUGE_BYTES -> ArchiveWhy.Huge
        else -> ArchiveWhy.Compressed
    }

    /** From here an archive is named as very large: TMPlayer never opens it, whatever kind it is. */
    const val HUGE_BYTES = 2L * 1024 * 1024 * 1024

    /** Without a release name, an archive this large is still taken for a video's box (a backup is smaller). */
    const val UNNAMED_VIDEO_BYTES = 500L * 1024 * 1024

    /**
     * Whether [fileName] is worth a row under a chat's videos: an archive named like a video
     * release (a resolution, an episode mark, a video extension inside) or as large as a video
     * could be. A phone backup, a mod pack or a photo archive stays out, and so does a ZIP the
     * chat already lists as a video ([MediaText.looksLikeVideoArchive]), which plays or says why
     * when opened.
     */
    fun worthListing(fileName: String, sizeBytes: Long, mimeType: String = ""): Boolean {
        val kind = kindOf(fileName, mimeType) ?: return false
        if (kind == ArchiveKind.Zip && MediaText.looksLikeVideoArchive(fileName, sizeBytes)) return false
        if (sizeBytes >= UNNAMED_VIDEO_BYTES) return true
        val stem = fileName.trim().replace(SUFFIX, "")
        if (stem.isEmpty()) return false
        return stem.substringAfterLast('.', "").lowercase() in MediaText.VIDEO_EXTENSIONS ||
            MediaText.qualityTags(stem).isNotEmpty() || EpisodeCopies.markers(stem).isNotEmpty() ||
            MediaName.parse(stem).let { it.episode != null || it.season != null }
    }

    /**
     * Why a file handed to the player (a link, Continue watching, another app) is an archive it
     * cannot play, in the same sentence the listing carries ([reason]), or null when there is
     * nothing to say: not an archive, or a ZIP the player opens and inspects itself
     * ([ZipArchive.inspect]). A RAR, a 7z, a tarball and a ZIP volume (`.z01`) are told apart
     * from a video by name, so the player says what the file is rather than failing to decode it.
     */
    fun blockedOnOpen(fileName: String, mimeType: String = "", sizeBytes: Long = 0, caption: String? = null): String? {
        val kind = kindOf(fileName, mimeType) ?: return null
        if (kind == ArchiveKind.Zip && SplitPieces.baseOf(fileName.trim()).endsWith(".zip", ignoreCase = true)) return null
        return reason(ArchiveFile(0, 0, 0, fileName, sizeBytes, 0, kind, volume = isVolume(fileName), locked = looksLocked(fileName, caption)))
    }

    /** Whether a name or a caption says a password is needed. */
    fun looksLocked(vararg texts: String?): Boolean = texts.any { it != null && PASSWORD.containsMatchIn(it) }

    /** Newest first and each message once, as a listing gathers pages of them. */
    fun merge(listed: List<ArchiveFile>, more: List<ArchiveFile>): List<ArchiveFile> =
        if (more.isEmpty()) listed else (listed + more).distinctBy { it.messageId }.sortedByDescending { it.messageId }

    /** The archive's own suffixes, volumes included, taken off to read the name of what is inside. */
    private val SUFFIX = Regex(
        """(\.(7z|zip|rar|tar|gz|tgz|bz2|xz|zst)(\.\d{1,4})?|\.part\d{1,4}\.rar|\.[rz]\d{2})$""",
        RegexOption.IGNORE_CASE,
    )
    private val PASSWORD = Regex("""\b(password|passwd|pwd|pass\s*[:=])""", RegexOption.IGNORE_CASE)

    private val ZIP = Regex("""\.zip$|\.zip\.\d{1,4}$|\.z\d{2}$""")
    private val RAR = Regex("""\.rar$|\.rar\.\d{1,4}$|\.r\d{2}$""")
    private val SEVEN_ZIP = Regex("""\.7z$|\.7z\.\d{1,4}$""")
    private val OTHER = Regex("""\.(tar|gz|tgz|bz2|xz|zst)$|\.(tar|gz|tgz|bz2|xz|zst)\.\d{1,4}$""")
    private val VOLUME = Regex("""\.(7z|zip|rar|tar|gz|tgz|bz2|xz|zst)\.\d{1,4}$|\.part\d{1,4}\.rar$|\.[rz]\d{2}$""")
    private val ZIP_TYPES = setOf("application/zip", "application/x-zip-compressed")
    private val RAR_TYPES = setOf("application/x-rar-compressed", "application/vnd.rar", "application/x-rar")
}
