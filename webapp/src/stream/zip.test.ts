// The ZIP reader against archives built here byte by byte (core's ZipArchiveTest has the same layouts).
// `node --test "src/stream/*.test.ts"` (pnpm test:stream).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { bestVideo, blockedVideo, blockerOf, dataOffset, looksLikeZip, MAX_ENTRIES, TAIL_READ, zipEntries, type ZipEntry, type ZipSource } from './zip.ts'

interface Item {
    name: string
    data: Uint8Array
    method?: number
    flags?: number
    /** Declared compressed size when it is not the data's length. */
    declared?: number
}

const enc = new TextEncoder()

class Out {
    parts: number[] = []
    get length() {
        return this.parts.length
    }
    u8(...v: number[]) {
        this.parts.push(...v)
    }
    u16(v: number) {
        this.parts.push(v & 0xff, (v >> 8) & 0xff)
    }
    u32(v: number) {
        this.u16(v & 0xffff)
        this.u16(Math.floor(v / 65536) & 0xffff)
    }
    u64(v: number) {
        this.u32(v % 0x100000000)
        this.u32(Math.floor(v / 0x100000000))
    }
    bytes(b: Uint8Array) {
        for (let i = 0; i < b.length; i++) this.parts.push(b[i])
    }
}

/** A ZIP as the spec lays it out: local headers and data, the central directory, the end record (with ZIP64 records on request). */
function build(items: Item[], opts: { comment?: string; zip64?: boolean; localExtra?: number } = {}): Uint8Array {
    const out = new Out()
    const placed: { item: Item; offset: number }[] = []
    for (const item of items) {
        placed.push({ item, offset: out.length })
        const name = enc.encode(item.name)
        out.u32(0x04034b50)
        out.u16(20)
        out.u16(item.flags ?? 0)
        out.u16(item.method ?? 0)
        out.u32(0)
        out.u32(0)
        out.u32(item.declared ?? item.data.length)
        out.u32(item.data.length)
        out.u16(name.length)
        out.u16(opts.localExtra ?? 0)
        out.bytes(name)
        for (let i = 0; i < (opts.localExtra ?? 0); i++) out.u8(0xee)
        out.bytes(item.data)
    }
    const directory = out.length
    for (const { item, offset } of placed) {
        const name = enc.encode(item.name)
        const compressed = item.declared ?? item.data.length
        out.u32(0x02014b50)
        out.u16(45)
        out.u16(20)
        out.u16(item.flags ?? 0)
        out.u16(item.method ?? 0)
        out.u32(0)
        out.u32(0)
        if (opts.zip64) {
            out.u32(0xffffffff)
            out.u32(0xffffffff)
        } else {
            out.u32(compressed)
            out.u32(item.data.length)
        }
        out.u16(name.length)
        out.u16(opts.zip64 ? 4 + 24 : 0)
        out.u16(0)
        out.u16(0)
        out.u16(0)
        out.u32(0)
        out.u32(opts.zip64 ? 0xffffffff : offset)
        out.bytes(name)
        if (opts.zip64) {
            out.u16(0x0001)
            out.u16(24)
            // Uncompressed, compressed, offset: the order the spec gives.
            out.u64(item.data.length)
            out.u64(compressed)
            out.u64(offset)
        }
    }
    const directorySize = out.length - directory
    const comment = enc.encode(opts.comment ?? '')
    if (opts.zip64) {
        const record = out.length
        out.u32(0x06064b50)
        out.u64(44)
        out.u16(45)
        out.u16(45)
        out.u32(0)
        out.u32(0)
        out.u64(items.length)
        out.u64(items.length)
        out.u64(directorySize)
        out.u64(directory)
        out.u32(0x07064b50)
        out.u32(0)
        out.u64(record)
        out.u32(1)
    }
    out.u32(0x06054b50)
    out.u16(0)
    out.u16(0)
    out.u16(opts.zip64 ? 0xffff : items.length)
    out.u16(opts.zip64 ? 0xffff : items.length)
    out.u32(opts.zip64 ? 0xffffffff : directorySize)
    out.u32(opts.zip64 ? 0xffffffff : directory)
    out.u16(comment.length)
    out.bytes(comment)
    return Uint8Array.from(out.parts)
}

const payload = (size: number, seed = 1): Uint8Array => {
    // A small deterministic generator, so a failure repeats.
    let x = seed * 2654435761 || 1
    const b = new Uint8Array(size)
    for (let i = 0; i < size; i++) {
        x ^= x << 13
        x ^= x >>> 17
        x ^= x << 5
        b[i] = x & 0xff
    }
    return b
}

const over = (data: Uint8Array): ZipSource => ({
    size: data.length,
    read: async (offset, length) => (offset < 0 || offset > data.length ? null : data.subarray(offset, Math.min(data.length, offset + length))),
})

const find = async (zip: Uint8Array) => {
    const source = over(zip)
    const entries = await zipEntries(source)
    assert.ok(entries)
    const best = bestVideo(entries)
    return { source, entries, best }
}

test('a stored video is found and its bytes are where the header says, behind a header with an extra field', async () => {
    const video = payload(300_000)
    const zip = build([{ name: 'Movie.2160p.mkv', data: video }, { name: 'info.txt', data: payload(40, 2) }], { localExtra: 12 })
    const { source, best } = await find(zip)
    assert.equal(best?.name, 'Movie.2160p.mkv')
    const start = await dataOffset(source, best!)
    assert.ok(start !== null)
    assert.deepEqual(zip.subarray(start, start + video.length), video)
    assert.equal(start + video.length <= zip.length, true)
})

test('the largest stored video wins, and a folder, a sample name or a subtitle is not one', async () => {
    const zip = build([
        { name: 'Pack/', data: new Uint8Array(0) },
        { name: 'Pack/Sample.mkv', data: payload(1000, 3) },
        { name: 'Pack/Film.MP4', data: payload(5000, 4) },
        { name: 'Pack/Film.srt', data: payload(9000, 5) },
    ])
    const { best } = await find(zip)
    assert.equal(best?.name, 'Pack/Film.MP4')
})

test('a comment after the directory, even one that holds an end record signature, is stepped over', async () => {
    const fake = 'PK\x05\x06' + '\0'.repeat(18)
    const zip = build([{ name: 'a.mkv', data: payload(2000) }], { comment: `trailing ${fake} words` })
    const { best, entries } = await find(zip)
    assert.equal(entries.length, 1)
    assert.equal(best?.name, 'a.mkv')
})

test('ZIP64 (sizes and offset in the extra field, a ZIP64 end record) reads the same', async () => {
    const video = payload(70_000, 6)
    const zip = build([{ name: 'big.1080p.mkv', data: video }, { name: 'readme.txt', data: payload(10, 7) }], { zip64: true, comment: 'x' })
    const { source, best, entries } = await find(zip)
    assert.equal(entries.length, 2)
    assert.equal(best?.uncompressedSize, 70_000)
    const start = await dataOffset(source, best!)
    assert.deepEqual(zip.subarray(start!, start! + video.length), video)
})

test('a deflated video is named as compressed, a locked one as encrypted, and neither has a start', async () => {
    const deflated = build([{ name: 'a.mkv', data: payload(900), method: 8 }])
    const { source, entries, best } = await find(deflated)
    assert.equal(best, null)
    const blocked = blockedVideo(entries)
    assert.equal(blockerOf(blocked!), 'compressed')
    assert.equal(await dataOffset(source, blocked!), null)

    const locked = build([{ name: 'a.mkv', data: payload(900), flags: 1 }])
    const second = await find(locked)
    assert.equal(second.best, null)
    assert.equal(blockerOf(blockedVideo(second.entries)!), 'encrypted')

    // Encrypted wins over compressed: that is what stops it first.
    const both = await find(build([{ name: 'a.mkv', data: payload(50), method: 8, flags: 1 }]))
    assert.equal(blockerOf(blockedVideo(both.entries)!), 'encrypted')
})

test('an archive with no video says so: no best, no blocked', async () => {
    const { best, entries } = await find(build([{ name: 'notes.txt', data: payload(30) }, { name: 'cover.jpg', data: payload(50) }]))
    assert.equal(best, null)
    assert.equal(blockedVideo(entries), null)
    const empty = await zipEntries(over(build([])))
    assert.deepEqual(empty, [])
})

test('a stored entry whose two sizes disagree does not stream', async () => {
    const { best, entries } = await find(build([{ name: 'a.mkv', data: payload(900), declared: 400 }]))
    assert.equal(best, null)
    assert.equal(entries.length, 1)
})

test('a local header that points outside the archive gives no start', async () => {
    const zip = build([{ name: 'a.mkv', data: payload(900) }])
    const { source, best } = await find(zip)
    const lying: ZipEntry = { ...best!, uncompressedSize: 10_000_000, compressedSize: 10_000_000 }
    assert.equal(await dataOffset(source, lying), null)
    assert.equal(await dataOffset(source, { ...best!, headerOffset: 5 }), null)
    assert.equal(await dataOffset(source, { ...best!, headerOffset: zip.length - 3 }), null)
})

test('a failing or lying source gives null, never a throw', async () => {
    const zip = build([{ name: 'a.mkv', data: payload(900) }])
    assert.equal(await zipEntries({ size: zip.length, read: async () => null }), null)
    assert.equal(await zipEntries({ size: zip.length, read: async () => { throw new Error('network') } }), null)
    assert.equal(await zipEntries({ size: zip.length, read: async () => new Uint8Array(3) }), null)
    assert.equal(await zipEntries({ size: Number.NaN, read: async () => zip }), null)
    assert.equal(await zipEntries({ size: -5, read: async () => zip }), null)
    assert.equal(await zipEntries({ size: 10, read: async () => zip }), null)
    assert.equal(await dataOffset({ size: zip.length, read: async () => { throw new Error('x') } }, { name: 'a.mkv', method: 0, compressedSize: 1, uncompressedSize: 1, headerOffset: 0, encrypted: false }), null)
})

test('the tail read is bounded by the longest comment, and a directory past the limits is refused', async () => {
    const zip = build([{ name: 'a.mkv', data: payload(900) }], { comment: 'c'.repeat(60_000) })
    let asked = 0
    const source: ZipSource = {
        size: zip.length,
        read: async (offset, length) => {
            asked = Math.max(asked, length)
            return zip.subarray(offset, offset + length)
        },
    }
    assert.ok(await zipEntries(source))
    assert.ok(asked <= TAIL_READ)
    assert.equal(TAIL_READ, 65557)

    // An end record claiming a directory of 20 MiB, or more entries than the limit.
    const big = build([{ name: 'a.mkv', data: payload(100) }])
    const eocd = big.length - 22
    const sized = big.slice()
    new DataView(sized.buffer).setUint32(eocd + 12, 20 * 1024 * 1024, true)
    assert.equal(await zipEntries(over(sized)), null)
    const many = big.slice()
    assert.ok(0xfffe > MAX_ENTRIES)
    new DataView(many.buffer).setUint16(eocd + 10, 0xfffe, true)
    assert.equal(await zipEntries(over(many)), null)
})

test('a multi-disk end record (a real split archive) is not read as one stream', async () => {
    const zip = build([{ name: 'a.mkv', data: payload(900) }])
    const split = zip.slice()
    new DataView(split.buffer).setUint16(split.length - 22 + 6, 3, true)
    assert.equal(await zipEntries(over(split)), null)
})

test('the first bytes tell a ZIP from a video', () => {
    assert.equal(looksLikeZip(Uint8Array.of(0x50, 0x4b, 3, 4)), true)
    assert.equal(looksLikeZip(Uint8Array.of(0x50, 0x4b, 5, 6)), true)
    assert.equal(looksLikeZip(Uint8Array.of(0x1a, 0x45, 0xdf, 0xa3)), false)
    assert.equal(looksLikeZip(new Uint8Array(2)), false)
})

test('truncated at every length, a flipped byte anywhere and random bytes never throw', async () => {
    const zip = build([{ name: 'a.1080p.mkv', data: payload(400) }, { name: 'b.txt', data: payload(30, 2) }], { comment: 'hello' })
    for (let cut = 0; cut <= zip.length; cut++) {
        const prefix = zip.subarray(0, cut)
        const entries = await zipEntries(over(prefix))
        assert.ok(entries === null || Array.isArray(entries))
        if (entries) for (const e of entries) await dataOffset(over(prefix), e)
    }
    // Every byte of the directory and end record set to a few hostile values in turn.
    const tailFrom = zip.length - 22 - 2 * 80
    for (let at = Math.max(0, tailFrom); at < zip.length; at++) {
        for (const value of [0x00, 0xff, 0x7f, 0x80]) {
            const hit = zip.slice()
            hit[at] = value
            const entries = await zipEntries(over(hit))
            assert.ok(entries === null || Array.isArray(entries))
            if (entries) for (const e of entries) await dataOffset(over(hit), e)
        }
    }
    for (let seed = 1; seed <= 60; seed++) {
        const noise = payload(1 + (seed * 997) % 70_000, seed)
        const entries = await zipEntries(over(noise))
        assert.ok(entries === null || Array.isArray(entries))
    }
    // Noise ending in an end record signature that claims a huge directory.
    const nasty = new Uint8Array(400).fill(0xff)
    nasty.set([0x50, 0x4b, 5, 6], 378)
    nasty.fill(0, 382, 400)
    nasty.fill(0xff, 388, 396)
    assert.equal(await zipEntries(over(nasty)), null)
})
