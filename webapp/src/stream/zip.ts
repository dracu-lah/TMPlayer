// A ZIP's directory, read from its tail, to find a stored (uncompressed) video inside it, so the
// video plays straight out of the archive, split into pieces or not, with seeking. A port of
// core's ZipArchive.kt (same limits, same checks); nothing here unpacks: a compressed entry is
// named as such and left alone. Pure, no imports, so a Node test can run it.
//
// Outside data: nothing here throws and nothing trusts a size. A bad archive answers null or an
// empty list. The tail read is bounded by the largest comment ZIP allows, the directory by
// MAX_DIRECTORY and MAX_ENTRIES.

/** Bytes of an archive read by range: a whole file, or its pieces joined. Answers fewer bytes only at the end, null when they cannot be had. */
export interface ZipSource {
    readonly size: number
    read(offset: number, length: number): Promise<Uint8Array | null>
}

export interface ZipEntry {
    name: string
    /** 0 is STORE (the bytes as they are); 8 is deflate, and so on: anything else cannot stream. */
    method: number
    compressedSize: number
    uncompressedSize: number
    /** Where the entry's local header starts in the joined stream. */
    headerOffset: number
    encrypted: boolean
}

export type ZipBlocker = 'compressed' | 'encrypted'

export const MAX_ENTRIES = 20_000
export const MAX_DIRECTORY = 16 * 1024 * 1024
/** The end record and the longest comment ZIP allows: what the tail read asks for. */
export const TAIL_READ = 22 + 65_535

/** The extensions of a video (MediaText.VIDEO_EXTENSIONS). */
const VIDEO_EXTENSIONS = new Set(['mkv', 'mp4', 'm4v', 'avi', 'mov', 'webm', 'ts', 'm2ts', 'mts', 'flv', 'wmv', 'mpg', 'mpeg', '3gp', 'ogv', 'divx', 'vob', 'asf', 'rmvb'])

const SIG_EOCD = 0x06054b50
const SIG_EOCD64 = 0x06064b50
const SIG_LOCATOR64 = 0x07064b50
const SIG_CENTRAL = 0x02014b50
const SIG_LOCAL = 0x04034b50
const EOCD_MIN = 22
const LOCATOR64 = 20
const EOCD64_MIN = 56
const CENTRAL_MIN = 46
const LOCAL_MIN = 30
const U32_MAX = 0xffffffff

/** Plain archives and joined pieces of one: a local header, or an empty archive's end record, opens the stream. */
export function looksLikeZip(head: Uint8Array): boolean {
    if (head.length < 4) return false
    const sig = u32(head, 0)
    return sig === SIG_LOCAL || sig === SIG_EOCD
}

export const isStreamable = (e: ZipEntry): boolean => e.method === 0 && !e.encrypted && !e.name.endsWith('/') && e.compressedSize === e.uncompressedSize

/** The words for why a video inside cannot play, or null when it can (a directory has none). */
export function blockerOf(e: ZipEntry): ZipBlocker | null {
    if (e.name.endsWith('/')) return null
    if (e.encrypted) return 'encrypted'
    return e.method !== 0 ? 'compressed' : null
}

export function isVideoName(name: string): boolean {
    const leaf = name.slice(name.lastIndexOf('/') + 1)
    const dot = leaf.lastIndexOf('.')
    return dot >= 0 && VIDEO_EXTENSIONS.has(leaf.slice(dot + 1).toLowerCase())
}

/** The one video to play out of an archive: the largest stored entry that is a video. */
export function bestVideo(entries: ZipEntry[]): ZipEntry | null {
    let best: ZipEntry | null = null
    for (const e of entries) if (isStreamable(e) && isVideoName(e.name) && (!best || e.uncompressedSize > best.uncompressedSize)) best = e
    return best
}

/** The largest entry that would be a video but cannot stream, to say why. */
export function blockedVideo(entries: ZipEntry[]): ZipEntry | null {
    let best: ZipEntry | null = null
    for (const e of entries) if (blockerOf(e) && isVideoName(e.name) && (!best || e.uncompressedSize > best.uncompressedSize)) best = e
    return best
}

/** A read that cannot fail the caller: null for a throw as for an answer that is not there. */
async function readOrNull(source: ZipSource, offset: number, length: number): Promise<Uint8Array | null> {
    if (!Number.isSafeInteger(offset) || !Number.isSafeInteger(length) || offset < 0 || length < 0) return null
    try {
        return await source.read(offset, length)
    } catch {
        return null
    }
}

/**
 * Every entry of the archive in `source`, or null when it is not a ZIP, is cut short or its
 * directory is unreadable.
 */
export async function zipEntries(source: ZipSource): Promise<ZipEntry[] | null> {
    try {
        return await readEntries(source)
    } catch {
        return null
    }
}

async function readEntries(source: ZipSource): Promise<ZipEntry[] | null> {
    const total = source.size
    if (!Number.isSafeInteger(total) || total < EOCD_MIN) return null
    const tailLength = Math.min(total, TAIL_READ)
    const tailStart = total - tailLength
    const tail = await readOrNull(source, tailStart, tailLength)
    if (!tail || tail.length < EOCD_MIN) return null

    const at = findEocd(tail)
    if (at === null) return null
    let entryCount = u16(tail, at + 10)
    let directorySize = u32(tail, at + 12)
    let directoryOffset = u32(tail, at + 16)
    let directoryDisk = u16(tail, at + 6)

    const needsZip64 = entryCount === 0xffff || directorySize === U32_MAX || directoryOffset === U32_MAX || directoryDisk === 0xffff
    if (needsZip64) {
        // The ZIP64 locator sits right before the end record, and points at the ZIP64 end record.
        const locator = tailStart + at - LOCATOR64
        if (locator < 0) return null
        const loc = await readOrNull(source, locator, LOCATOR64)
        if (!loc || loc.length < LOCATOR64 || u32(loc, 0) !== SIG_LOCATOR64) return null
        // Raw pieces joined into one stream say disk 0 throughout; a real multi-disk archive is not read.
        if (u32(loc, 4) !== 0) return null
        const recordAt = u64(loc, 8)
        if (recordAt < 0 || recordAt >= total) return null
        const record = await readOrNull(source, recordAt, EOCD64_MIN)
        if (!record || record.length < EOCD64_MIN || u32(record, 0) !== SIG_EOCD64) return null
        directoryDisk = u32(record, 20)
        entryCount = u64(record, 32)
        directorySize = u64(record, 40)
        directoryOffset = u64(record, 48)
    }
    if (entryCount < 0 || entryCount > MAX_ENTRIES || directorySize < 0 || directorySize > MAX_DIRECTORY) return null
    if (entryCount === 0) return []
    if (directoryDisk !== 0 || directoryOffset < 0 || directoryOffset >= total || directoryOffset + directorySize > total) return null
    const directory = await readOrNull(source, directoryOffset, directorySize)
    if (!directory || directory.length < directorySize) return null

    const text = new TextDecoder('utf-8')
    const found: ZipEntry[] = []
    let cursor = 0
    while (cursor + CENTRAL_MIN <= directory.length && found.length < entryCount) {
        if (u32(directory, cursor) !== SIG_CENTRAL) break
        const flags = u16(directory, cursor + 8)
        const method = u16(directory, cursor + 10)
        let compressed = u32(directory, cursor + 20)
        let uncompressed = u32(directory, cursor + 24)
        const nameLength = u16(directory, cursor + 28)
        const extraLength = u16(directory, cursor + 30)
        const commentLength = u16(directory, cursor + 32)
        let offset = u32(directory, cursor + 42)
        let disk = u16(directory, cursor + 34)
        const next = cursor + CENTRAL_MIN + nameLength + extraLength + commentLength
        if (next > directory.length) break
        const nameStart = cursor + CENTRAL_MIN
        const name = text.decode(directory.subarray(nameStart, nameStart + nameLength))

        // The ZIP64 extra holds, in this order, only the fields the base record set to all ones.
        let extra = nameStart + nameLength
        const extraEnd = extra + extraLength
        while (extra + 4 <= extraEnd) {
            const id = u16(directory, extra)
            const length = u16(directory, extra + 2)
            const body = extra + 4
            if (body + length > extraEnd) break
            if (id === 0x0001) {
                let p = body
                const next64 = (): number => {
                    if (p + 8 > body + length) return -1
                    const v = u64(directory, p)
                    p += 8
                    return v
                }
                if (uncompressed === U32_MAX) uncompressed = next64()
                if (compressed === U32_MAX) compressed = next64()
                if (offset === U32_MAX) offset = next64()
                if (disk === 0xffff && p + 4 <= body + length) disk = u32(directory, p)
            }
            extra = body + length
        }
        // An entry on another disk (a real multi-disk archive) is left out: its offset is not in this stream.
        if (compressed >= 0 && uncompressed >= 0 && offset >= 0 && offset < total && disk === 0) {
            found.push({ name, method, compressedSize: compressed, uncompressedSize: uncompressed, headerOffset: offset, encrypted: (flags & 1) !== 0 })
        }
        cursor = next
    }
    return found
}

/**
 * Where the bytes of `entry` begin in the joined stream, read from its local header (whose name
 * and extra field lengths can differ from the directory's), or null when the header is missing,
 * wrong or would put the bytes outside the archive.
 */
export async function dataOffset(source: ZipSource, entry: ZipEntry): Promise<number | null> {
    if (!isStreamable(entry)) return null
    const header = await readOrNull(source, entry.headerOffset, LOCAL_MIN)
    if (!header || header.length < LOCAL_MIN || u32(header, 0) !== SIG_LOCAL) return null
    const start = entry.headerOffset + LOCAL_MIN + u16(header, 26) + u16(header, 28)
    if (!Number.isSafeInteger(start) || start < 0 || start + entry.uncompressedSize > source.size) return null
    return start
}

/** The end record's place in the tail: the last one whose comment ends exactly at the end of the file. */
function findEocd(tail: Uint8Array): number | null {
    for (let at = tail.length - EOCD_MIN; at >= 0; at--) {
        if (tail[at] === 0x50 && u32(tail, at) === SIG_EOCD) {
            const comment = u16(tail, at + 20)
            // Bytes that only look like an end record do not end where the file does.
            if (at + EOCD_MIN + comment === tail.length) return at
        }
    }
    return null
}

const u16 = (b: Uint8Array, at: number): number => b[at] | (b[at + 1] << 8)
const u32 = (b: Uint8Array, at: number): number => (u16(b, at) | (u16(b, at + 2) << 16)) >>> 0

/** A 64-bit field, or -1 when it does not fit a safe integer (an archive that large is not one to trust). */
function u64(b: Uint8Array, at: number): number {
    const high = u32(b, at + 4)
    if (high >= 0x200000) return -1
    return u32(b, at) + high * 0x100000000
}
