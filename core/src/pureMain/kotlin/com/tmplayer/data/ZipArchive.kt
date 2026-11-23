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
 * The answer [ZipArchive.inspect] gives: [entry] is the video to play for [Kind.Playable], and the
 * entry that would have been (the reason) for a compressed or locked archive.
 */
data class ZipVerdict(val kind: Kind, val entry: ZipEntry? = null) {
    enum class Kind {
        /** A stored video: it plays straight out of the archive. */
        Playable,

        /** The video is deflated or otherwise packed: it needs unpacking first. */
        Compressed,

        /** A password protects it. */
        Encrypted,

        /** A directory of more files, or a larger one, than is worth reading: named, never opened. */
        TooLarge,

        /** A ZIP with no video in it. */
        NoVideo,

        /** Not a ZIP, cut short, a piece missing or the bytes unreachable. */
        Unreadable,
    }

    /** The sentence the player shows for this verdict, or null for [Kind.Playable]. */
    val reason: String?
        get() = when (kind) {
            Kind.Playable -> null
            Kind.Compressed -> com.tmplayer.i18n.L.playerZipCompressed
            Kind.Encrypted -> com.tmplayer.i18n.L.playerZipEncrypted
            Kind.TooLarge -> com.tmplayer.i18n.L.playerZipTooLarge
            Kind.NoVideo -> com.tmplayer.i18n.L.playerZipNoVideo
            Kind.Unreadable -> com.tmplayer.i18n.L.playerZipUnreadable
        }

    /** Whether trying again could change the answer: only a directory that could not be read. */
    val retryable: Boolean get() = kind == Kind.Unreadable
}

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
    suspend fun entries(source: ZipSource, diskStarts: List<Long> = emptyList()): List<ZipEntry>? =
        load(source, diskStarts).entries

    /**
     * What the player says about [source]: the one video it can stream, or the reason it cannot,
     * in a word each ([ZipVerdict]). Reads only the directory (a bounded tail and the directory
     * itself), never the archive's contents: a password protected archive, a compressed one, one
     * listing more files than [MAX_ENTRIES] or a directory larger than [MAX_DIRECTORY] are named
     * and left unopened.
     */
    suspend fun inspect(source: ZipSource, diskStarts: List<Long> = emptyList()): ZipVerdict {
        val loaded = try {
            load(source, diskStarts)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return ZipVerdict(ZipVerdict.Kind.Unreadable)
        }
        if (loaded.huge) return ZipVerdict(ZipVerdict.Kind.TooLarge)
        val entries = loaded.entries ?: return ZipVerdict(ZipVerdict.Kind.Unreadable)
        bestVideo(entries)?.let { return ZipVerdict(ZipVerdict.Kind.Playable, it) }
        val blocked = blockedVideo(entries)
        return when (blocked?.blocker) {
            ZipBlocker.Encrypted -> ZipVerdict(ZipVerdict.Kind.Encrypted, blocked)
            ZipBlocker.Compressed -> ZipVerdict(ZipVerdict.Kind.Compressed, blocked)
            null -> ZipVerdict(ZipVerdict.Kind.NoVideo)
        }
    }

    /** The directory read, or why it was not: [huge] is an archive named and left alone, not a broken one. */
    private class Loaded(val entries: List<ZipEntry>?, val huge: Boolean = false)

    private suspend fun load(source: ZipSource, diskStarts: List<Long>): Loaded {
        val total = source.size
        if (total < EOCD_MIN) return Loaded(null)
        val tailLength = minOf(total, (EOCD_MIN + MAX_COMMENT).toLong()).toInt()
        val tailStart = total - tailLength
        val tail = source.read(tailStart, tailLength) ?: return Loaded(null)
        if (tail.size < EOCD_MIN) return Loaded(null)

        val at = findEocd(tail) ?: return Loaded(null)
        var entryCount = u16(tail, at + 10).toLong()
        var directorySize = u32(tail, at + 12)
        var directoryOffset = u32(tail, at + 16)
        var directoryDisk = u16(tail, at + 6)

        val needsZip64 = entryCount == 0xFFFFL || directorySize == 0xFFFFFFFFL || directoryOffset == 0xFFFFFFFFL || directoryDisk == 0xFFFF
        if (needsZip64) {
            // The ZIP64 locator sits right before the end record, and points at the ZIP64 end record.
            val locator = tailStart + at - LOCATOR64
            if (locator < 0) return Loaded(null)
            val loc = source.read(locator, LOCATOR64) ?: return Loaded(null)
            if (loc.size < LOCATOR64 || u32(loc, 0) != SIG_LOCATOR64) return Loaded(null)
            val recordDisk = u32(loc, 4).toInt()
            val recordOffset = joined(recordDisk, u64(loc, 8), diskStarts, total) ?: return Loaded(null)
            val record = source.read(recordOffset, EOCD64_MIN) ?: return Loaded(null)
            if (record.size < EOCD64_MIN || u32(record, 0) != SIG_EOCD64) return Loaded(null)
            directoryDisk = u32(record, 20).toInt()
            entryCount = u64(record, 32)
            directorySize = u64(record, 40)
            directoryOffset = u64(record, 48)
        }
        if (entryCount < 0 || directorySize < 0) return Loaded(null)
        if (entryCount > MAX_ENTRIES || directorySize > MAX_DIRECTORY) return Loaded(null, huge = true)
        if (entryCount == 0L) return Loaded(emptyList())
        val directoryAt = joined(directoryDisk, directoryOffset, diskStarts, total) ?: return Loaded(null)
        if (directoryAt + directorySize > total) return Loaded(null)
        val directory = source.read(directoryAt, directorySize.toInt()) ?: return Loaded(null)
        if (directory.size < directorySize) return Loaded(null)

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
        return Loaded(found)
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
        if (start < 0 || entry.uncompressedSize > source.size - start) return null
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
