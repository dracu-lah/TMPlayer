// A video cut into raw byte pieces (Name.mkv.001, Name.part001), or kept uncompressed in a ZIP
// whole or split the same way (Name.1080p.zip.001), as one file for the player: the Android app's
// JoinedDataSource and the desktop's JoinedStreamBytes, for the browser. The pieces are Telegram
// files read one at a time; the player reads this as if it were a single file, whatever it does
// (an index at the end, a seek across a seam, the Service Worker's ranges).
//
// No imports of reader.ts but its types, so a Node test can run it against in-memory pieces.
import type { MediaFile } from './reader'
import { bestVideo, blockedVideo, blockerOf, dataOffset, looksLikeZip, zipEntries, type ZipSource } from './zip.ts'

const KiB = 1024
const MiB = 1024 * KiB

/** Pieces held open at once. A piece's own head and tail chunks are never evicted from memory (reader.ts), so this keeps that to a few MiB however many pieces there are. */
export const MAX_OPEN = 3
/** Within this much of a piece's end the next piece is opened ahead, so playing across the seam does not stall. */
const SEAM_AHEAD = 8 * MiB

export interface Piece {
    key: string
    size: number
}

interface Slot {
    file: MediaFile
    /** Reads on their way: such a piece is not closed to make room. */
    busy: number
    /** When it was last used, for the oldest to go first. */
    used: number
}

/** A name that may be one piece of a cut video ("Name.mkv.001", "Name.part3", "Name.part001.mkv"), the cheap look before asking the chat for the rest. */
export function looksSplit(fileName: string): boolean {
    return /(?:\.\d{1,4}|[._ -]part\s?\d+)$|part\s?\d+\.\w{2,4}$/i.test(fileName.trim())
}

/**
 * The pieces read as one file, in order, opened lazily: a piece is a Telegram file (`open`) made
 * when a read first needs it, and the least recently used one goes when more than MAX_OPEN would
 * be open, so a seek far away leaves its old place behind. Optionally a window (see `window`)
 * shows a part of the joined bytes, the video stored inside a ZIP, as the whole file.
 */
export class JoinedFile implements MediaFile {
    readonly chunk = 512 * KiB
    private readonly sizes: number[]
    /** Where each piece starts in the joined bytes. */
    private readonly starts: number[]
    private readonly total: number
    private base = 0
    private length: number
    private slots = new Map<number, Slot>()
    private tick = 0
    private closed = false
    private ahead = true
    private search = false
    /** Bytes received and how much each piece had arrived by when it was closed: what a closed piece still counts for. */
    private receivedClosed = 0
    private loadedClosed: number[]
    readonly pieces: readonly Piece[]
    private readonly open: (piece: Piece, index: number) => MediaFile

    constructor(pieces: readonly Piece[], open: (piece: Piece, index: number) => MediaFile) {
        this.pieces = pieces
        this.open = open
        this.sizes = pieces.map((p) => (Number.isFinite(p.size) && p.size > 0 ? Math.floor(p.size) : 0))
        this.starts = []
        let at = 0
        for (const size of this.sizes) {
            this.starts.push(at)
            at += size
        }
        this.total = at
        this.length = at
        this.loadedClosed = this.sizes.map(() => 0)
    }

    /** Every piece's bytes added up, whatever the window shows. */
    get joinedSize(): number {
        return this.total
    }

    /** What the player sees: the window's length, or every piece's bytes added up. */
    get size(): number {
        return this.length
    }

    /** The same for the same pieces and window, and different for any other, so the Service Worker's address names this stream alone. */
    get key(): string {
        return `join:${this.pieces.map((p) => p.key).join('~')}${this.base || this.length !== this.total ? `@${this.base}-${this.length}` : ''}`
    }

    /**
     * Shows `length` bytes from `start` of the joined bytes as the whole file, before anything is
     * served from it. A window outside the pieces is refused.
     */
    window(start: number, length: number) {
        if (!Number.isSafeInteger(start) || !Number.isSafeInteger(length) || start < 0 || length <= 0 || start + length > this.total) {
            throw new RangeError('window outside the pieces')
        }
        this.base = start
        this.length = length
    }

    get searched(): boolean {
        return this.search
    }
    set searched(v: boolean) {
        this.search = v
        for (const s of this.slots.values()) s.file.searched = v
    }

    get wantAhead(): boolean {
        return this.ahead
    }
    set wantAhead(v: boolean) {
        this.ahead = v
        for (const s of this.slots.values()) s.file.wantAhead = v
    }

    get received(): number {
        let n = this.receivedClosed
        for (const s of this.slots.values()) n += s.file.received
        return n
    }

    /** How much of the window has arrived, 0 to 1 (a closed piece counts for what it had). */
    get loaded(): number {
        if (!this.total) return 1
        let got = 0
        for (let i = 0; i < this.sizes.length; i++) got += this.sizes[i] * (this.slots.get(i)?.file.loaded ?? this.loadedClosed[i])
        return Math.min(1, got / this.total)
    }

    get waiting(): boolean {
        for (const s of this.slots.values()) if (s.file.waiting) return true
        return false
    }

    /** Pieces open now, for tests and the details panel. */
    get openPieces(): number[] {
        return [...this.slots.keys()]
    }

    /** The first piece the window shows and the last: their heads and tails are what an index asks for first. */
    warmUp() {
        if (this.closed || !this.length) return
        const first = this.pieceAt(this.base)
        const last = this.pieceAt(this.base + this.length - 1)
        this.slot(first).file.warmUp()
        if (last !== first) this.slot(last).file.warmUp()
    }

    /** Bytes [start, end) of the window. */
    async read(start: number, end: number): Promise<Uint8Array> {
        if (this.closed) throw new DOMException('closed', 'AbortError')
        end = Math.min(end, this.length)
        start = Math.max(0, start)
        if (!(start < end)) return new Uint8Array(0)
        const a = this.base + start
        const b = this.base + end
        const first = this.pieceAt(a)
        const last = this.pieceAt(b - 1)
        const jobs: Promise<Uint8Array>[] = []
        for (let i = first; i <= last; i++) {
            const from = Math.max(a, this.starts[i]) - this.starts[i]
            const to = Math.min(b, this.starts[i] + this.sizes[i]) - this.starts[i]
            if (to > from) jobs.push(this.fromPiece(i, from, to))
        }
        const parts = await Promise.all(jobs)
        if (this.closed) throw new DOMException('closed', 'AbortError')
        this.seam(last, b)
        if (parts.length === 1) return parts[0]
        const out = new Uint8Array(end - start)
        let at = 0
        for (const part of parts) {
            out.set(part, at)
            at += part.length
        }
        return out
    }

    close() {
        this.closed = true
        for (const index of [...this.slots.keys()]) this.drop(index)
    }

    private async fromPiece(index: number, from: number, to: number): Promise<Uint8Array> {
        const slot = this.slot(index)
        slot.busy++
        try {
            const bytes = await slot.file.read(from, to)
            // A piece shorter than Telegram said it was would shift every byte after it: fail instead of playing garbage.
            if (bytes.length !== to - from) throw new Error(`piece ${index + 1} came short`)
            return bytes
        } finally {
            slot.busy--
        }
    }

    /** The piece's file, opened now when it is not: the least recently used idle one makes room. */
    private slot(index: number): Slot {
        const have = this.slots.get(index)
        if (have) {
            have.used = ++this.tick
            return have
        }
        while (this.slots.size >= MAX_OPEN) {
            let oldest = -1
            for (const [i, s] of this.slots) if (!s.busy && (oldest < 0 || s.used < this.slots.get(oldest)!.used)) oldest = i
            // Everything open is being read: a read spanning pieces goes over the limit for its length rather than wait on itself.
            if (oldest < 0) break
            this.drop(oldest)
        }
        const file = this.open(this.pieces[index], index)
        file.wantAhead = this.ahead
        file.searched = this.search
        const slot: Slot = { file, busy: 0, used: ++this.tick }
        this.slots.set(index, slot)
        return slot
    }

    private drop(index: number) {
        const slot = this.slots.get(index)
        if (!slot) return
        this.slots.delete(index)
        this.receivedClosed += slot.file.received
        this.loadedClosed[index] = slot.file.loaded
        slot.file.close()
    }

    /** Near a piece's end, the next piece starts arriving, once, while the player plays on. */
    private seam(last: number, end: number) {
        const next = last + 1
        if (!this.ahead || this.closed || next >= this.pieces.length || this.slots.has(next)) return
        if (this.starts[next] >= this.base + this.length || this.starts[last] + this.sizes[last] - end > SEAM_AHEAD) return
        const slot = this.slot(next)
        slot.busy++
        // One byte: the first chunk, and the piece's own read-ahead goes on from there.
        slot.file.read(0, 1).then(
            () => slot.busy--,
            () => slot.busy--,
        )
    }

    /** The piece holding byte `at` of the joined bytes. */
    private pieceAt(at: number): number {
        let lo = 0
        let hi = this.starts.length - 1
        while (lo < hi) {
            const mid = (lo + hi + 1) >> 1
            if (this.starts[mid] <= at) lo = mid
            else hi = mid - 1
        }
        // Empty pieces share a start with the next: step over them.
        while (lo < this.sizes.length - 1 && this.sizes[lo] === 0) lo++
        return lo
    }
}

/** Why a video kept in a ZIP cannot play, as the player says it (core's player.zip_*). */
export type ZipFailure = 'compressed' | 'encrypted' | 'no_video' | 'unreadable'

export class ZipProblem extends Error {
    readonly kind: ZipFailure
    constructor(kind: ZipFailure, cause?: unknown) {
        super(`zip: ${kind}`, { cause })
        this.kind = kind
        this.name = 'ZipProblem'
    }
}

/** Pieces of a video missing from the chat: with the first gone nothing starts, a later gap plays up to it (core's player.pieces_*). */
export class PiecesProblem extends Error {
    /** The first piece missing, counted from 1. */
    readonly number: number
    readonly total: number
    constructor(number: number, total: number) {
        super(`pieces: ${number} of ${total} missing`)
        this.number = number
        this.total = total
        this.name = 'PiecesProblem'
    }
}

/** The joined bytes as a ZIP source: a read that fails or runs off the end is null, never a throw. */
function sourceOf(file: JoinedFile): ZipSource {
    return {
        size: file.joinedSize,
        read: async (offset, length) => {
            if (offset < 0 || offset > file.joinedSize) return null
            try {
                // Read through the whole joined bytes, whatever window is set.
                return await file.read(offset, Math.min(file.joinedSize, offset + length))
            } catch {
                return null
            }
        },
    }
}

/**
 * If the joined bytes are a ZIP (their first bytes say so), finds the stored video inside, reading
 * the archive's directory from the end, and shows just that video through the file's window, so the
 * player sees an ordinary video file. Returns whether it did. `expectZip`: the name said ZIP, so
 * anything else is an unreadable archive and not a video to try as it is. Throws ZipProblem with the
 * reason when the archive cannot be read, holds no video, or its video is compressed or locked.
 */
export async function mountZip(file: JoinedFile, expectZip: boolean): Promise<boolean> {
    let head: Uint8Array | null = null
    try {
        head = await file.read(0, 4)
    } catch (e) {
        if (expectZip) throw new ZipProblem('unreadable', e)
        return false
    }
    if (!looksLikeZip(head)) {
        if (expectZip) throw new ZipProblem('unreadable')
        return false
    }
    const source = sourceOf(file)
    const entries = await zipEntries(source)
    if (!entries) throw new ZipProblem('unreadable')
    const video = bestVideo(entries)
    if (!video || video.uncompressedSize <= 0) {
        const blocked = blockedVideo(entries)
        const why = blocked ? blockerOf(blocked) : null
        throw new ZipProblem(why ?? 'no_video')
    }
    const start = await dataOffset(source, video)
    if (start === null) throw new ZipProblem('unreadable')
    try {
        file.window(start, video.uncompressedSize)
    } catch (e) {
        throw new ZipProblem('unreadable', e)
    }
    return true
}
