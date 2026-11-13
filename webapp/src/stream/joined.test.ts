// The joined file against in-memory pieces: reading across seams, seeks, the window, a ZIP stored
// inside split pieces, and every way it can fail. Sized like real chats (a 71 GB set of 18 pieces)
// by generating bytes on demand instead of holding them. `node --test "src/stream/*.test.ts"`.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { JoinedFile, looksSplit, MAX_OPEN, mountZip, ZipProblem, type Piece } from './joined.ts'
import type { MediaFile } from './reader'

const KiB = 1024
const MiB = 1024 * KiB

/** A byte of an imaginary file at an offset: anywhere in 71 GB, no memory held. */
const byteAt = (at: number): number => (Math.imul(at % 0x7fffffff, 2654435761) >>> 24) & 0xff

/** What the pieces and the player see: a file's bytes in the range [a, b) of a source that is not held whole. */
interface Source {
    size: number
    slice(a: number, b: number): Uint8Array
}

const plain = (size: number, base = 0): Source => ({
    size,
    slice: (a, b) => Uint8Array.from({ length: b - a }, (_, i) => byteAt(base + a + i)),
})

const heldBytes = (data: Uint8Array): Source => ({ size: data.length, slice: (a, b) => data.slice(a, b) })

/** Bytes that exist only where patched, zeros between: a 9 GB archive of which only the headers are real. */
function sparse(size: number, patches: { at: number; bytes: Uint8Array }[]): Source {
    return {
        size,
        slice: (a, b) => {
            const out = new Uint8Array(b - a)
            for (const p of patches) {
                const from = Math.max(a, p.at)
                const to = Math.min(b, p.at + p.bytes.length)
                if (from < to) out.set(p.bytes.subarray(from - p.at, to - p.at), from - a)
            }
            return out
        },
    }
}

class FakePiece implements MediaFile {
    readonly chunk = 512 * KiB
    searched = false
    wantAhead = true
    received = 0
    loaded = 0
    waiting = false
    closed = false
    warmed = false
    reads: [number, number][] = []
    readonly index: number
    readonly key: string
    readonly size: number
    private readonly source: Source
    private readonly base: number
    private readonly world: World
    constructor(index: number, key: string, size: number, source: Source, base: number, world: World) {
        this.index = index
        this.key = key
        this.size = size
        this.source = source
        this.base = base
        this.world = world
    }
    warmUp() {
        this.warmed = true
    }
    async read(start: number, end: number): Promise<Uint8Array> {
        if (this.closed) throw new Error(`read from closed piece ${this.index}`)
        end = Math.min(end, this.size)
        this.reads.push([start, end])
        if (this.world.failing.has(this.index)) throw new Error('piece unavailable')
        await Promise.resolve()
        const out = this.source.slice(this.base + start, this.base + end)
        this.received += out.length
        this.loaded = Math.min(1, this.loaded + out.length / Math.max(1, this.size))
        return this.world.short.has(this.index) ? out.subarray(0, Math.max(0, out.length - 1)) : out
    }
    close() {
        this.closed = true
        this.world.open.delete(this)
    }
}

/** The pieces of a whole `source` cut at `sizes`, opened through a recorder. */
class World {
    open = new Set<FakePiece>()
    made: FakePiece[] = []
    failing = new Set<number>()
    short = new Set<number>()
    peak = 0
    readonly pieces: Piece[]
    readonly source: Source
    readonly sizes: number[]
    constructor(source: Source, sizes: number[]) {
        this.source = source
        this.sizes = sizes
        assert.equal(
            sizes.reduce((a, b) => a + b, 0),
            source.size,
        )
        this.pieces = sizes.map((size, i) => ({ key: `p${i}`, size }))
    }
    opener = (piece: Piece, index: number): MediaFile => {
        let base = 0
        for (let i = 0; i < index; i++) base += this.sizes[i]
        const file = new FakePiece(index, piece.key, piece.size, this.source, base, this)
        this.open.add(file)
        this.made.push(file)
        this.peak = Math.max(this.peak, this.open.size)
        return file
    }
    joined() {
        return new JoinedFile(this.pieces, this.opener)
    }
}

const sameBytes = (got: Uint8Array, source: Source, a: number, b: number) => {
    assert.equal(got.length, b - a, `length of [${a}, ${b})`)
    assert.ok(Buffer.compare(got, source.slice(a, b)) === 0, `bytes of [${a}, ${b})`)
}

/** A small deterministic generator, so a failure repeats. */
function rng(seed: number) {
    let x = seed * 2654435761 || 1
    return () => {
        x ^= x << 13
        x ^= x >>> 17
        x ^= x << 5
        return (x >>> 0) / 0x100000000
    }
}

test('reads across piece seams are the whole file, for ranges that start, end and straddle anywhere', async () => {
    const world = new World(plain(10_000), [4000, 3000, 2500, 500])
    const file = world.joined()
    assert.equal(file.size, 10_000)
    const next = rng(7)
    for (let i = 0; i < 400; i++) {
        const a = Math.floor(next() * 10_000)
        const b = Math.min(10_000, a + 1 + Math.floor(next() * 6000))
        sameBytes(await file.read(a, b), world.source, a, b)
    }
    // Exactly on every seam, and a read of one byte either side.
    for (const seam of [4000, 7000, 9500]) {
        sameBytes(await file.read(seam - 1, seam + 1), world.source, seam - 1, seam + 1)
        sameBytes(await file.read(seam, seam + 1), world.source, seam, seam + 1)
        sameBytes(await file.read(seam - 1, seam), world.source, seam - 1, seam)
    }
    // Past the end answers what is there, nothing beyond it, and an empty or backwards range is empty.
    sameBytes(await file.read(9990, 50_000), world.source, 9990, 10_000)
    assert.equal((await file.read(10_000, 10_001)).length, 0)
    assert.equal((await file.read(5, 5)).length, 0)
    assert.equal((await file.read(9, 3)).length, 0)
    file.close()
})

test('a real chat: 18 pieces of 4 GB, past 32 bits, read exactly at the seams', async () => {
    const gb4 = 4_194_304_000
    const sizes = [...Array(17).fill(gb4), 3_769_561_462]
    const world = new World(plain(sizes.reduce((a, b) => a + b, 0)), sizes)
    const file = world.joined()
    assert.equal(file.size, 17 * gb4 + 3_769_561_462)
    for (const seam of [gb4, 5 * gb4, 17 * gb4]) {
        sameBytes(await file.read(seam - 3, seam + 3), world.source, seam - 3, seam + 3)
    }
    sameBytes(await file.read(file.size - 100, file.size + 100), world.source, file.size - 100, file.size)
    // A sweep through every piece never holds more than the limit open.
    for (let i = 0; i < 18; i++) sameBytes(await file.read(i * gb4 + 10, i * gb4 + 40), world.source, i * gb4 + 10, i * gb4 + 40)
    assert.ok(world.peak <= MAX_OPEN, `peak ${world.peak}`)
    assert.ok(file.openPieces.length <= MAX_OPEN)
    // The ones left behind were closed, not kept: every piece opened but the ones still open is closed.
    assert.equal(world.made.filter((p) => p.closed).length, world.made.length - file.openPieces.length)
    file.close()
    assert.equal(world.open.size, 0)
})

test('a seek leaves its old piece, which is closed once others need the room, and a piece is opened only when read', async () => {
    const piece = 20 * MiB
    const world = new World(plain(10 * piece), Array(10).fill(piece))
    const file = world.joined()
    assert.equal(world.made.length, 0)
    await file.read(0, 100)
    assert.deepEqual(file.openPieces, [0])
    await file.read(9 * piece + MiB, 9 * piece + MiB + 100)
    await file.read(5 * piece + MiB, 5 * piece + MiB + 100)
    await file.read(2 * piece + MiB, 2 * piece + MiB + 100)
    assert.ok(!file.openPieces.includes(0))
    assert.equal(world.made[0].closed, true)
    assert.deepEqual([...file.openPieces].sort(), [2, 5, 9])
    // Back to the first: opened again from the start, nothing stale.
    sameBytes(await file.read(0, 100), world.source, 0, 100)
    assert.equal(world.made.filter((p) => p.index === 0).length, 2)
    assert.ok(world.peak <= MAX_OPEN)
    file.close()
})

test('a piece being read is never closed to make room, and the read is whole', async () => {
    const world = new World(plain(100_000), Array(10).fill(10_000))
    const file = world.joined()
    // One read across six pieces: more than the limit, so it goes over for its length and settles after.
    sameBytes(await file.read(5_000, 65_000), world.source, 5_000, 65_000)
    assert.ok(world.made.every((p) => !p.closed || p.reads.length > 0))
    await file.read(90_000, 90_010)
    assert.ok(file.openPieces.length <= MAX_OPEN + 1)
    file.close()
})

test('the window shows one stretch of the joined bytes as the whole file, bounds and all', async () => {
    const world = new World(plain(30_000), [10_000, 10_000, 10_000])
    const file = world.joined()
    file.window(9_000, 12_000)
    assert.equal(file.size, 12_000)
    assert.equal(file.joinedSize, 30_000)
    sameBytes(await file.read(0, 12_000), world.source, 9_000, 21_000)
    sameBytes(await file.read(900, 1_200), world.source, 9_900, 10_200)
    sameBytes(await file.read(11_990, 20_000), world.source, 20_990, 21_000)
    assert.equal((await file.read(12_000, 12_100)).length, 0)
    // The window starts mid-piece and ends mid-piece: the first and last pieces are read only inside it.
    assert.ok(world.made.every((p) => p.reads.every(([a, b]) => (p.index === 0 ? a >= 9_000 : true) && (p.index === 2 ? b <= 1_000 : true))))
    // A window outside the pieces, empty or not a number is refused, and leaves the file as it was.
    for (const [start, length] of [[-1, 10], [0, 0], [0, -4], [29_000, 2_000], [30_001, 1], [Number.NaN, 5], [5, Number.POSITIVE_INFINITY], [0.5, 3]]) {
        assert.throws(() => world.joined().window(start, length), RangeError)
    }
    assert.equal(file.size, 12_000)
    file.close()
})

test('the stream key is the same for the same pieces and window and different for any other', () => {
    const a = new World(plain(30_000), [10_000, 10_000, 10_000])
    const b = new World(plain(30_000), [10_000, 10_000, 10_000])
    const whole = a.joined()
    assert.equal(whole.key, b.joined().key)
    const x = a.joined()
    x.window(100, 5_000)
    const y = b.joined()
    y.window(100, 5_001)
    assert.notEqual(x.key, whole.key)
    assert.notEqual(x.key, y.key)
    assert.match(x.key, /^join:p0~p1~p2@100-5000$/)
    // Safe in a /stream/ address once encoded.
    assert.equal(decodeURIComponent(encodeURIComponent(x.key)), x.key)
})

test('closing closes every open piece, reads after it fail and nothing is left running', async () => {
    const world = new World(plain(30_000), [10_000, 10_000, 10_000])
    const file = world.joined()
    await file.read(0, 25_000)
    file.close()
    assert.equal(world.open.size, 0)
    await assert.rejects(file.read(0, 10), { name: 'AbortError' })
    file.warmUp()
    assert.equal(world.open.size, 0)
    // No timer is left behind by any of it.
    const timers = (process.getActiveResourcesInfo() as string[]).filter((r) => r === 'Timeout')
    assert.equal(timers.length, 0)
})

test('a piece that cannot be read, or comes back short, fails the read instead of shifting the bytes', async () => {
    const world = new World(plain(30_000), [10_000, 10_000, 10_000])
    world.failing.add(1)
    const file = world.joined()
    sameBytes(await file.read(0, 100), world.source, 0, 100)
    await assert.rejects(file.read(9_900, 10_100), /unavailable/)
    sameBytes(await file.read(20_000, 20_100), world.source, 20_000, 20_100)
    file.close()
    const short = new World(plain(30_000), [10_000, 10_000, 10_000])
    short.short.add(2)
    const second = short.joined()
    await assert.rejects(second.read(20_000, 20_100), /came short/)
    second.close()
})

test('size, received and loaded add up over every piece, the closed ones still counting', async () => {
    const world = new World(plain(40_000), [10_000, 10_000, 10_000, 10_000])
    const file = world.joined()
    assert.equal(file.received, 0)
    assert.equal(file.loaded, 0)
    await file.read(0, 10_000)
    await file.read(10_000, 20_000)
    await file.read(20_000, 30_000)
    await file.read(30_000, 40_000)
    assert.ok(!file.openPieces.includes(0), 'the first is closed by now')
    // The bytes asked, and the one byte a piece near its end asks of the next to have it ready.
    assert.ok(file.received >= 40_000 && file.received <= 40_003, `received ${file.received}`)
    assert.ok(Math.abs(file.loaded - 1) < 1e-9)
    // Flags set on the joined file reach the pieces open now and the ones opened later.
    file.wantAhead = false
    file.searched = true
    await file.read(0, 10)
    assert.ok([...world.open].every((p) => p.wantAhead === false && p.searched === true))
    file.close()
})

test('near the end of a piece the next one starts arriving, once, and only while the player plays', async () => {
    const world = new World(plain(60 * MiB), [20 * MiB, 20 * MiB, 20 * MiB])
    const file = world.joined()
    await file.read(2 * MiB, 2 * MiB + 10)
    assert.equal(world.made.length, 1)
    // 4 MiB from the end of piece 0: the next piece's first byte is asked.
    await file.read(16 * MiB, 16 * MiB + 10)
    assert.deepEqual(file.openPieces.sort(), [0, 1])
    const next = world.made.find((p) => p.index === 1)!
    assert.deepEqual(next.reads, [[0, 1]])
    await file.read(17 * MiB, 17 * MiB + 10)
    assert.equal(next.reads.length, 1)
    // Paused (read-ahead off): no piece is opened ahead.
    file.wantAhead = false
    await file.read(36 * MiB, 36 * MiB + 10)
    const third = world.made.filter((p) => p.index === 2)
    assert.ok(third.every((p) => p.reads.every(([a, b]) => !(a === 0 && b === 1))))
    // A window that ends before the next piece does not reach for it.
    const w = new World(plain(60 * MiB), [20 * MiB, 20 * MiB, 20 * MiB]).joined()
    w.window(1 * MiB, 17 * MiB)
    await w.read(15 * MiB, 15 * MiB + 10)
    assert.deepEqual(w.openPieces, [0])
    file.close()
    w.close()
})

test('warming opens the first and the last piece of what is shown', () => {
    const world = new World(plain(30_000), [10_000, 10_000, 10_000])
    const file = world.joined()
    file.warmUp()
    assert.deepEqual(world.made.map((p) => [p.index, p.warmed]), [[0, true], [2, true]])
    const w = world.joined()
    w.window(10_500, 5_000)
    w.warmUp()
    assert.equal(world.made.filter((p) => p.index === 1).length, 1)
    file.close()
    w.close()
})

test('a set of one piece, and a set with an empty piece in it, read as they are', async () => {
    const one = new World(plain(500), [500]).joined()
    sameBytes(await one.read(0, 500), plain(500), 0, 500)
    const holes = new World(plain(300), [100, 0, 100, 0, 100]).joined()
    sameBytes(await holes.read(0, 300), plain(300), 0, 300)
    sameBytes(await holes.read(99, 101), plain(300), 99, 101)
    // Sizes Telegram never sends are read as nothing.
    const odd = new JoinedFile([{ key: 'a', size: Number.NaN }, { key: 'b', size: -5 }, { key: 'c', size: 10 }], new World(plain(10), [10]).opener)
    assert.equal(odd.size, 10)
    one.close()
    holes.close()
})

// ---- a video stored inside a ZIP --------------------------------------------------------------

const enc = new TextEncoder()

class Bytes {
    parts: number[] = []
    get length() {
        return this.parts.length
    }
    u16(v: number) {
        this.parts.push(v & 0xff, (v >> 8) & 0xff)
    }
    u32(v: number) {
        this.u16(v % 0x10000)
        this.u16(Math.floor(v / 0x10000) % 0x10000)
    }
    u64(v: number) {
        this.u32(v % 0x100000000)
        this.u32(Math.floor(v / 0x100000000))
    }
    put(b: Uint8Array) {
        for (const x of b) this.parts.push(x)
    }
    done() {
        return Uint8Array.from(this.parts)
    }
}

/** A stored ZIP of one video, as small as it gets: local header, bytes, directory, end record. */
function zipOf(name: string, video: Uint8Array, opts: { method?: number; flags?: number; extra?: { name: string; data: Uint8Array }[] } = {}): Uint8Array {
    const out = new Bytes()
    const entries = [{ name, data: video }, ...(opts.extra ?? [])]
    const at: number[] = []
    for (const [i, e] of entries.entries()) {
        at.push(out.length)
        const n = enc.encode(e.name)
        out.u32(0x04034b50)
        out.u16(20)
        out.u16(i === 0 ? (opts.flags ?? 0) : 0)
        out.u16(i === 0 ? (opts.method ?? 0) : 0)
        out.u32(0)
        out.u32(0)
        out.u32(e.data.length)
        out.u32(e.data.length)
        out.u16(n.length)
        out.u16(0)
        out.put(n)
        out.put(e.data)
    }
    const dir = out.length
    for (const [i, e] of entries.entries()) {
        const n = enc.encode(e.name)
        out.u32(0x02014b50)
        out.u16(20)
        out.u16(20)
        out.u16(i === 0 ? (opts.flags ?? 0) : 0)
        out.u16(i === 0 ? (opts.method ?? 0) : 0)
        out.u32(0)
        out.u32(0)
        out.u32(e.data.length)
        out.u32(e.data.length)
        out.u16(n.length)
        out.u16(0)
        out.u16(0)
        out.u16(0)
        out.u16(0)
        out.u32(0)
        out.u32(at[i])
        out.put(n)
    }
    const size = out.length - dir
    out.u32(0x06054b50)
    out.u16(0)
    out.u16(0)
    out.u16(entries.length)
    out.u16(entries.length)
    out.u32(size)
    out.u32(dir)
    out.u16(0)
    return out.done()
}

const cut = (data: Uint8Array, sizes: number[]) => {
    assert.equal(sizes.reduce((a, b) => a + b, 0), data.length)
    return new World(heldBytes(data), sizes)
}

const video = (size: number, seed = 3): Uint8Array => {
    const next = rng(seed)
    return Uint8Array.from({ length: size }, () => Math.floor(next() * 256))
}

test('a stored video in a ZIP cut into three pieces plays as the video itself, seeks and all', async () => {
    const film = video(50_000)
    const archive = zipOf('Film.2026.1080p.mkv', film, { extra: [{ name: 'readme.txt', data: video(80, 9) }] })
    const world = cut(archive, [20_000, 20_000, archive.length - 40_000])
    const file = world.joined()
    assert.equal(await mountZip(file, true), true)
    assert.equal(file.size, film.length)
    sameBytes(await file.read(0, film.length), heldBytes(film), 0, film.length)
    const next = rng(11)
    for (let i = 0; i < 200; i++) {
        const a = Math.floor(next() * film.length)
        const b = Math.min(film.length, a + 1 + Math.floor(next() * 12_000))
        sameBytes(await file.read(a, b), heldBytes(film), a, b)
    }
    // The video's own first bytes are what the container sniffer sees, not the archive's.
    assert.deepEqual([...(await file.read(0, 4))], [...film.subarray(0, 4)])
    assert.equal((await file.read(film.length, film.length + 50)).length, 0)
    assert.ok(file.key.includes('@'), 'the window is part of the stream key')
    file.close()
})

test('a whole ZIP (one piece) plays the same, and a name that said zip over bytes that are not is unreadable', async () => {
    const film = video(5_000)
    const world = cut(zipOf('a.mp4', film), [zipOf('a.mp4', film).length])
    const file = world.joined()
    assert.equal(await mountZip(file, true), true)
    sameBytes(await file.read(0, 5_000), heldBytes(film), 0, 5_000)
    file.close()
    const notZip = new World(plain(9_000), [9_000]).joined()
    await assert.rejects(mountZip(notZip, true), (e: unknown) => e instanceof ZipProblem && e.kind === 'unreadable')
    assert.equal(notZip.size, 9_000)
    notZip.close()
})

test('pieces that are not a ZIP are played as the video they are: no window, nothing changed', async () => {
    const world = new World(plain(30_000), [10_000, 10_000, 10_000])
    const file = world.joined()
    assert.equal(await mountZip(file, false), false)
    assert.equal(file.size, 30_000)
    sameBytes(await file.read(9_990, 10_010), world.source, 9_990, 10_010)
    file.close()
})

test('a compressed, locked or empty ZIP says which, and a missing last piece makes it unreadable', async () => {
    const film = video(4_000)
    const why = async (archive: Uint8Array, sizes?: number[], break_?: (w: World) => void) => {
        const world = cut(archive, sizes ?? [archive.length])
        break_?.(world)
        const file = world.joined()
        try {
            await mountZip(file, true)
            return 'mounted'
        } catch (e) {
            return e instanceof ZipProblem ? e.kind : `other: ${String(e)}`
        } finally {
            file.close()
        }
    }
    assert.equal(await why(zipOf('a.mkv', film, { method: 8 })), 'compressed')
    assert.equal(await why(zipOf('a.mkv', film, { flags: 1 })), 'encrypted')
    assert.equal(await why(zipOf('notes.txt', film)), 'no_video')
    assert.equal(await why(zipOf('empty.mkv', new Uint8Array(0))), 'no_video')
    const ok = zipOf('a.mkv', film)
    const thirds = [Math.floor(ok.length / 3), Math.floor(ok.length / 3), ok.length - 2 * Math.floor(ok.length / 3)]
    assert.equal(await why(ok, thirds), 'mounted')
    // The directory is in the last piece: when it cannot be read, nothing says "no video".
    assert.equal(await why(ok, thirds, (w) => w.failing.add(2)), 'unreadable')
    // The first piece gone: the archive does not start.
    assert.equal(await why(ok, thirds, (w) => w.failing.add(0)), 'unreadable')
    // A truncated archive, a corrupt directory and noise.
    assert.equal(await why(ok.subarray(0, ok.length - 30)), 'unreadable')
    assert.equal(await why(Uint8Array.from([0x50, 0x4b, 3, 4, ...video(200, 5)])), 'unreadable')
})

test('a 9.6 GB ZIP of three pieces (a real chat\'s sizes) is read from its tail without reading the rest', async () => {
    const sizes = [4_194_304_000, 4_194_304_000, 1_233_811_809]
    const total = sizes.reduce((a, b) => a + b, 0)
    const name = enc.encode('Avengers.Endgame.Encore.2026.IMAX.1080p.mkv')
    const header = new Bytes()
    header.u32(0x04034b50)
    header.u16(45)
    header.u16(0)
    header.u16(0)
    header.u32(0)
    header.u32(0)
    header.u32(0xffffffff)
    header.u32(0xffffffff)
    header.u16(name.length)
    header.u16(0)
    header.put(name)
    const dataAt = header.length
    // The directory, ZIP64 end record, locator and end record, for a video of this many bytes at the head.
    const tailFor = (videoLength: number, directoryAt: number) => {
        const cd = new Bytes()
        cd.u32(0x02014b50)
        cd.u16(45)
        cd.u16(45)
        cd.u16(0)
        cd.u16(0)
        cd.u32(0)
        cd.u32(0)
        cd.u32(0xffffffff)
        cd.u32(0xffffffff)
        cd.u16(name.length)
        cd.u16(4 + 24)
        cd.u16(0)
        cd.u16(0)
        cd.u16(0)
        cd.u32(0)
        cd.u32(0xffffffff)
        cd.put(name)
        cd.u16(1)
        cd.u16(24)
        cd.u64(videoLength)
        cd.u64(videoLength)
        cd.u64(0)
        const end = new Bytes()
        end.u32(0x06064b50)
        end.u64(44)
        end.u16(45)
        end.u16(45)
        end.u32(0)
        end.u32(0)
        end.u64(1)
        end.u64(1)
        end.u64(cd.length)
        end.u64(directoryAt)
        end.u32(0x07064b50)
        end.u32(0)
        end.u64(directoryAt + cd.length)
        end.u32(1)
        end.u32(0x06054b50)
        end.u16(0)
        end.u16(0)
        end.u16(0xffff)
        end.u16(0xffff)
        end.u32(0xffffffff)
        end.u32(0xffffffff)
        end.u16(0)
        return Uint8Array.from([...cd.done(), ...end.done()])
    }
    // The tail's length does not depend on the numbers in it: measure it, then place the video before it.
    const videoLength = total - dataAt - tailFor(0, 0).length
    const directoryAt = dataAt + videoLength
    const tail = tailFor(videoLength, directoryAt)
    assert.equal(directoryAt + tail.length, total)
    const source = sparse(total, [{ at: 0, bytes: header.done() }, { at: directoryAt, bytes: tail }])
    const world = new World(source, sizes)
    const file = world.joined()
    assert.equal(await mountZip(file, true), true)
    assert.equal(file.size, videoLength)
    assert.equal(file.key.endsWith(`@${dataAt}-${videoLength}`), true)
    // Only the archive's head and tail were read: a few KB, not 9.6 GB.
    assert.ok(file.received < 200_000, `received ${file.received}`)
    // And a read across the first seam, in the window, is the right bytes (zeros, the video's stand-in).
    const across = await file.read(sizes[0] - dataAt - 5, sizes[0] - dataAt + 5)
    assert.equal(across.length, 10)
    file.close()
})

test('every name that is, and is not, a piece of a cut video the chat can be asked about', () => {
    for (const name of ['Film.2026.mkv.001', 'Film.mkv.part3', 'Film.part001', 'Film.part001.mkv', 'Colony (2026) UHD 2160p BluRay SDR HEVC [(Hindi + Tamil .zip.017', 'Avengers.Endgame.Encore.2026.IMAX.1080p.DSNP.WEB-DL..zip.001']) {
        assert.equal(looksSplit(name), true, name)
    }
    for (const name of ['Film.mkv', 'Night.Train.2019.1080p.BluRay.mkv.zip', 'IvyWalletBackup_20260930-1830.zip', 'signed-ota_update.zip', 'Harbour.Notes.S01E02', '']) {
        assert.equal(looksSplit(name), false, name)
    }
})
