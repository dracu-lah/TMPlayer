// Subtitle files posted in the chat as the player takes them (EG-F48): a plain file, a zip of a
// season stored or compressed, and garbage (AGENTS.md: parsers of outside data get garbage tests).
// `node --test "src/player/*.test.ts"` (pnpm test:player).
import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { test } from 'node:test'
import { crc32, deflateRawSync } from 'node:zlib'
import { ChatSubtitleError, chatSubtitleFile, MAX_BYTES, MAX_ZIP_BYTES } from './chatsubs.ts'

const SRT = '1\n00:00:01,000 --> 00:00:02,000\nHello\n\n'

/** A zip of [entries], each stored or deflated, with a central directory. */
function zip(entries: { name: string; text: string; deflate?: boolean }[]): Uint8Array {
    const parts: Buffer[] = []
    const central: Buffer[] = []
    let offset = 0
    for (const e of entries) {
        const raw = Buffer.from(e.text)
        const data = e.deflate ? deflateRawSync(raw) : raw
        const name = Buffer.from(e.name)
        const local = Buffer.alloc(30)
        local.writeUInt32LE(0x04034b50, 0)
        local.writeUInt16LE(20, 4)
        local.writeUInt16LE(e.deflate ? 8 : 0, 8)
        local.writeUInt32LE(crc32(raw), 14)
        local.writeUInt32LE(data.length, 18)
        local.writeUInt32LE(raw.length, 22)
        local.writeUInt16LE(name.length, 26)
        parts.push(local, name, data)
        const dir = Buffer.alloc(46)
        dir.writeUInt32LE(0x02014b50, 0)
        dir.writeUInt16LE(20, 4)
        dir.writeUInt16LE(20, 6)
        dir.writeUInt16LE(e.deflate ? 8 : 0, 10)
        dir.writeUInt32LE(crc32(raw), 16)
        dir.writeUInt32LE(data.length, 20)
        dir.writeUInt32LE(raw.length, 24)
        dir.writeUInt16LE(name.length, 28)
        dir.writeUInt32LE(offset, 42)
        central.push(dir, name)
        offset += 30 + name.length + data.length
    }
    const directory = Buffer.concat(central)
    const end = Buffer.alloc(22)
    end.writeUInt32LE(0x06054b50, 0)
    end.writeUInt16LE(entries.length, 8)
    end.writeUInt16LE(entries.length, 10)
    end.writeUInt32LE(directory.length, 12)
    end.writeUInt32LE(offset, 16)
    return new Uint8Array(Buffer.concat([...parts, directory, end]))
}

const text = (f: File) => f.text()

test('a plain subtitle file goes through under its own name', async () => {
    const f = await chatSubtitleFile('Show.S01E02.en.srt', false, new TextEncoder().encode(SRT), null)
    assert.equal(f.name, 'Show.S01E02.en.srt')
    assert.equal(await text(f), SRT)
})

test('empty, too big and not-a-subtitle files are refused with their reason', async () => {
    await assert.rejects(chatSubtitleFile('a.srt', false, new Uint8Array(0), null), (e: ChatSubtitleError) => e.why === 'unreadable')
    await assert.rejects(chatSubtitleFile('a.srt', false, new Uint8Array(MAX_BYTES + 1), null), (e: ChatSubtitleError) => e.why === 'too-big')
    await assert.rejects(chatSubtitleFile('a.zip', true, new Uint8Array(MAX_ZIP_BYTES + 1), null), (e: ChatSubtitleError) => e.why === 'too-big')
    await assert.rejects(chatSubtitleFile('notes.txt', false, new TextEncoder().encode(SRT), null), (e: ChatSubtitleError) => e.why === 'unreadable')
    // Named as an archive, but not one.
    await assert.rejects(chatSubtitleFile('subs.zip', true, new TextEncoder().encode(SRT), null), (e: ChatSubtitleError) => e.why === 'unreadable')
})

test('a season zip gives the entry for this episode, stored or compressed', async () => {
    for (const deflate of [false, true]) {
        const bytes = zip([
            { name: 'Show.S01E01.srt', text: SRT.replace('Hello', 'One'), deflate },
            { name: 'sub/Show.S01E02.srt', text: SRT.replace('Hello', 'Two'), deflate },
        ])
        const f = await chatSubtitleFile('Show.S01.subs.zip', true, bytes, { season: 1, episode: 2 })
        assert.equal(f.name, 'Show.S01E02.srt')
        assert.match(await text(f), /Two/)
    }
})

test('a season pack without this episode, a zip of no subtitle and a broken zip give nothing', async () => {
    const pack = zip([{ name: 'Show.S01E01.srt', text: SRT }, { name: 'Show.S01E03.srt', text: SRT }])
    await assert.rejects(chatSubtitleFile('p.zip', true, pack, { season: 1, episode: 2 }), (e: ChatSubtitleError) => e.why === 'unreadable')
    await assert.rejects(chatSubtitleFile('p.zip', true, zip([{ name: 'readme.txt', text: 'hi' }]), null), (e: ChatSubtitleError) => e.why === 'unreadable')
    await assert.rejects(chatSubtitleFile('p.zip', true, pack.subarray(0, pack.length >> 1), null), (e: ChatSubtitleError) => e.why === 'unreadable')
})

test('garbage never throws anything but a ChatSubtitleError', async () => {
    for (let n = 0; n < 60; n++) {
        const bytes = randomBytes(1 + Math.floor(Math.random() * 4000))
        if (n % 3 === 0) {
            bytes[0] = 0x50
            bytes[1] = 0x4b
        }
        for (const [name, archive] of [['x.srt', false], ['x.zip', true], ['x.ass', false]] as const) {
            try {
                await chatSubtitleFile(name, archive, new Uint8Array(bytes), { season: 1, episode: 2 })
            } catch (e) {
                assert.ok(e instanceof ChatSubtitleError, String(e))
            }
        }
    }
})
