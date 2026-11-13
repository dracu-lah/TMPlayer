package com.tmplayer.data

/**
 * Bytes of an archive that can be read by range: a whole file, or the pieces of a split one
 * ([SplitPieces]) read as one stream. [read] answers fewer bytes only at the end, and null when
 * the bytes cannot be had (a piece missing, the network gone).
 */
interface ZipSource {
    val size: Long
    suspend fun read(offset: Long, length: Int): ByteArray?
}

/** One file inside a ZIP, with every offset already mapped into the joined stream. */
data class ZipEntry(
    val name: String,
    /** 0 is STORE (the bytes as they are); 8 is deflate, and so on: anything else cannot stream. */
    val method: Int,
    val compressedSize: Long,
    val uncompressedSize: Long,
    /** Where the entry's local header starts in the joined stream. */
    val headerOffset: Long,
    val encrypted: Boolean,
) {
    val isDirectory: Boolean get() = name.endsWith('/')

    /** Plays as a slice of the archive: stored as it is, not locked. */
    val streamable: Boolean get() = method == 0 && !encrypted && !isDirectory && compressedSize == uncompressedSize

    /** The words for why a video inside cannot play, or null when it can. */
    val blocker: ZipBlocker?
        get() = when {
            isDirectory -> null
            encrypted -> ZipBlocker.Encrypted
            method != 0 -> ZipBlocker.Compressed
            else -> null
        }
}

enum class ZipBlocker { Compressed, Encrypted }

/**
 * Reads a ZIP's directory from its tail and finds where a stored (uncompressed) video's bytes
 * sit, so the video can play straight out of the archive, split or not, with seeking. Nothing
 * here unpacks: a compressed entry is named as such and left alone.
 *
 * Handles ZIP64 (archives over 4 GB, which is the reason a video is zipped at all for Telegram's
 * limit), a comment after the directory, and the two ways an archive is split: raw byte pieces
 * (`.zip.001`, `.zip.002`, joined into one stream, headers say disk 0) and real multi-disk
 * volumes (`.z01`, `.z02`, `.zip`, whose headers count a disk and an offset in it: pass the start
 * of each disk in the joined stream as [diskStarts]).
 *
 * Reads outside data, so nothing here throws and nothing trusts a size: a bad archive answers
 * null or an empty list. The tail read is bounded by the largest comment ZIP allows, the
 * directory by [MAX_DIRECTORY] and [MAX_ENTRIES].
 */
object ZipArchive {

    const val MAX_ENTRIES = 20_000
    const val MAX_DIRECTORY = 16L * 1024 * 1024

    private const val SIG_EOCD = 0x06054b50L
    private const val SIG_EOCD64 = 0x06064b50L
    private const val SIG_LOCATOR64 = 0x07064b50L
    private const val SIG_CENTRAL = 0x02014b50L
    private const val SIG_LOCAL = 0x04034b50L
    private const val EOCD_MIN = 22
    private const val MAX_COMMENT = 65_535
    private const val LOCATOR64 = 20
    private const val EOCD64_MIN = 56
    private const val CENTRAL_MIN = 46
    private const val LOCAL_MIN = 30

    /** Whether the first bytes of a stream are a ZIP's: a local header, or an empty archive's end record. */
    fun looksLikeZip(head: ByteArray): Boolean {
        if (head.size < 4) return false
        val sig = u32(head, 0)
        return sig == SIG_LOCAL || sig == SIG_EOCD
    }

    /**
     * Every entry of the archive in [source], or null when it is not a ZIP, is cut short or its
     * directory is unreadable. [diskStarts] maps a multi-disk archive's disk numbers into the
     * joined stream; leave it empty for a single file or raw byte pieces.
     */
    suspend fun entries(source: ZipSource, diskStarts: List<Long> = emptyList()): List<ZipEntry>? {
        val total = source.size
        if (total < EOCD_MIN) return null
        val tailLength = minOf(total, (EOCD_MIN + MAX_COMMENT).toLong()).toInt()
        val tailStart = total - tailLength
        val tail = source.read(tailStart, tailLength) ?: return null
        if (tail.size < EOCD_MIN) return null

        val at = findEocd(tail) ?: return null
        var entryCount = u16(tail, at + 10).toLong()
        var directorySize = u32(tail, at + 12)
        var directoryOffset = u32(tail, at + 16)
        var directoryDisk = u16(tail, at + 6)

        val needsZip64 = entryCount == 0xFFFFL || directorySize == 0xFFFFFFFFL || directoryOffset == 0xFFFFFFFFL || directoryDisk == 0xFFFF
        if (needsZip64) {
            // The ZIP64 locator sits right before the end record, and points at the ZIP64 end record.
            val locator = tailStart + at - LOCATOR64
            if (locator < 0) return null
            val loc = source.read(locator, LOCATOR64) ?: return null
            if (loc.size < LOCATOR64 || u32(loc, 0) != SIG_LOCATOR64) return null
            val recordDisk = u32(loc, 4).toInt()
            val recordOffset = joined(recordDisk, u64(loc, 8), diskStarts, total) ?: return null
            val record = source.read(recordOffset, EOCD64_MIN) ?: return null
            if (record.size < EOCD64_MIN || u32(record, 0) != SIG_EOCD64) return null
            directoryDisk = u32(record, 20).toInt()
            entryCount = u64(record, 32)
            directorySize = u64(record, 40)
            directoryOffset = u64(record, 48)
        }
        if (entryCount < 0 || entryCount > MAX_ENTRIES || directorySize < 0 || directorySize > MAX_DIRECTORY) return null
        if (entryCount == 0L) return emptyList()
        val directoryAt = joined(directoryDisk, directoryOffset, diskStarts, total) ?: return null
        if (directoryAt + directorySize > total) return null
        val directory = source.read(directoryAt, directorySize.toInt()) ?: return null
        if (directory.size < directorySize) return null

        val found = ArrayList<ZipEntry>()
        var cursor = 0
        while (cursor + CENTRAL_MIN <= directory.size && found.size < entryCount) {
            if (u32(directory, cursor) != SIG_CENTRAL) break
            val flags = u16(directory, cursor + 8)
            val method = u16(directory, cursor + 10)
            var compressed = u32(directory, cursor + 20)
            var uncompressed = u32(directory, cursor + 24)
            val nameLength = u16(directory, cursor + 28)
            val extraLength = u16(directory, cursor + 30)
            val commentLength = u16(directory, cursor + 32)
            var disk = u16(directory, cursor + 34)
            var offset = u32(directory, cursor + 42)
            val next = cursor + CENTRAL_MIN + nameLength + extraLength + commentLength
            if (next > directory.size) break
            val nameStart = cursor + CENTRAL_MIN
            val name = directory.decodeToString(nameStart, nameStart + nameLength)

            // The ZIP64 extra holds, in this order, only the fields the base record set to all ones.
            var extra = nameStart + nameLength
            val extraEnd = extra + extraLength
            while (extra + 4 <= extraEnd) {
                val id = u16(directory, extra)
                val length = u16(directory, extra + 2)
                val body = extra + 4
                if (body + length > extraEnd) break
                if (id == 0x0001) {
                    var p = body
                    fun next64(): Long? = if (p + 8 <= body + length) u64(directory, p).also { p += 8 } else null
                    if (uncompressed == 0xFFFFFFFFL) uncompressed = next64() ?: -1
                    if (compressed == 0xFFFFFFFFL) compressed = next64() ?: -1
                    if (offset == 0xFFFFFFFFL) offset = next64() ?: -1
                    if (disk == 0xFFFF && p + 4 <= body + length) disk = u32(directory, p).toInt()
                }
                extra = body + length
            }

            val header = if (compressed < 0 || uncompressed < 0 || offset < 0) null else joined(disk, offset, diskStarts, total)
            if (header != null) {
                found += ZipEntry(
                    name = name,
                    method = method,
                    compressedSize = compressed,
                    uncompressedSize = uncompressed,
                    headerOffset = header,
                    encrypted = flags and 1 != 0,
                )
            }
            cursor = next
        }
        return found
    }

    /**
     * Where the bytes of [entry] begin in the joined stream, read from its local header (whose
     * name and extra field lengths can differ from the directory's), or null when the header is
     * missing, wrong or would put the bytes outside the archive.
     */
    suspend fun dataOffset(source: ZipSource, entry: ZipEntry): Long? {
        if (!entry.streamable) return null
        val header = source.read(entry.headerOffset, LOCAL_MIN) ?: return null
        if (header.size < LOCAL_MIN || u32(header, 0) != SIG_LOCAL) return null
        val start = entry.headerOffset + LOCAL_MIN + u16(header, 26) + u16(header, 28)
        if (start < 0 || start + entry.uncompressedSize > source.size) return null
        return start
    }

    /** The one video to play out of an archive: the largest stored entry that is a video. */
    fun bestVideo(entries: List<ZipEntry>): ZipEntry? =
        entries.filter { it.streamable && isVideoName(it.name) }
            .maxByOrNull { it.uncompressedSize }

    /** The largest entry that would be a video but cannot stream, to say why. */
    fun blockedVideo(entries: List<ZipEntry>): ZipEntry? =
        entries.filter { it.blocker != null && isVideoName(it.name) }
            .maxByOrNull { it.uncompressedSize }

    fun isVideoName(name: String): Boolean =
        name.substringAfterLast('/').substringAfterLast('.', "").lowercase() in MediaText.VIDEO_EXTENSIONS

    /** A disk's number and offset in it, as a position in the joined stream; null when it points nowhere. */
    private fun joined(disk: Int, offset: Long, diskStarts: List<Long>, total: Long): Long? {
        if (offset < 0) return null
        val base = when {
            disk == 0 -> diskStarts.firstOrNull() ?: 0L
            disk in diskStarts.indices -> diskStarts[disk]
            else -> return null
        }
        val at = base + offset
        return at.takeIf { it in 0 until total }
    }

    private fun findEocd(tail: ByteArray): Int? {
        var at = tail.size - EOCD_MIN
        while (at >= 0) {
            if (tail[at] == 0x50.toByte() && u32(tail, at) == SIG_EOCD) {
                val comment = u16(tail, at + 20)
                // The comment must end exactly at the end of the file, or this is bytes that only look like one.
                if (at + EOCD_MIN + comment == tail.size) return at
            }
            at--
        }
        return null
    }

    private fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, at: Int): Long =
        (u16(b, at).toLong()) or (u16(b, at + 2).toLong() shl 16)

    /** A 64-bit field, or -1 when it does not fit a Long (an archive that large is not one to trust). */
    private fun u64(b: ByteArray, at: Int): Long {
        val high = u32(b, at + 4)
        if (high > 0x7FFFFFFFL) return -1
        return u32(b, at) or (high shl 32)
    }
}
