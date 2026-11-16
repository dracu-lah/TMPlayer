// The web's port of the sync file against the apps' own: a file core wrote is read here, the file
// this port writes is the one core's WebSyncParityTest reads back, and a hundred seeded pairs of
// files get the merge and the "what this device changes" core made of them (fixtures/parity.json,
// made by core; regenerate with TMPLAYER_WRITE_WEB_FIXTURES=1 ./gradlew :core:jvmTest --tests '*WebSyncParityTest').
// Every reader of outside data gets truncated, wrong-typed, huge and random input.
import assert from 'node:assert/strict'
import { readFileSync, writeFileSync } from 'node:fs'
import { test } from 'node:test'
import {
    MAX_READ_BYTES,
    apply,
    decode,
    emptyDoc,
    encode,
    gzip,
    keyOf,
    merge,
    mergeCounting,
    sameLists,
    tdlibMessageId,
    toJson,
    unionLists,
    webMessageId,
    type SyncApply,
    type SyncDoc,
} from './format.ts'

const fixture = (name: string) => new URL(`./fixtures/${name}`, import.meta.url)
const bytes = (name: string) => new Uint8Array(readFileSync(fixture(name)))
const SHIFT = 2 ** 20
const CHAT = -1001234567890
const ACCOUNT = 777

async function read(b: Uint8Array, account = ACCOUNT): Promise<SyncDoc> {
    const r = await decode(b, account)
    assert.equal(r.kind, 'ok')
    return (r as { doc: SyncDoc }).doc
}

test('a file core wrote is read: every list, the show line, bookmarks, corrections, removals and the named lists untouched', async () => {
    const doc = await read(bytes('core-v3.json.gz'))
    assert.equal(doc.account, ACCOUNT)
    assert.equal(doc.appVersion, '1.99.0')
    assert.deepEqual(doc.resume.map((r) => [r.chat, webMessageId(r.msg), r.positionMs, r.title, r.state ?? null]), [
        [CHAT, 5, 754_000, 'Video 5', '1,ja,0,en,1.25'],
        [42, 77, 61_000, 'Video 77', null],
    ])
    assert.deepEqual(doc.watched.map((w) => [w.chat, webMessageId(w.msg), w.manual]), [
        [CHAT, 6, true],
        [CHAT, 7, false],
    ])
    assert.deepEqual(doc.later.map((l) => [l.chat, webMessageId(l.msg), l.series]), [
        [CHAT, 8, ''],
        [CHAT, 3, 'harbour notes'],
    ])
    assert.deepEqual([...doc.favourites.keys()].sort(), [CHAT, 42].sort())
    assert.deepEqual(doc.removed.map((r) => r.list).sort(), ['bookmark', 'correction', 'resume'])
    assert.equal(doc.bookmarks[0].note, 'the lantern scene')
    assert.equal(doc.corrections[0].text, 'tmdb=show:123\ntitle=Night Lantern (2022)')
    assert.deepEqual((doc.lists as { lists: { name: string }[] }).lists.map((l) => l.name), ['Weekend'])
})

test('what is read is written back as it was: the lists and bookmarks a web never edits are not lost', async () => {
    const doc = await read(bytes('core-v3.json.gz'))
    const again = await read((await encode(doc)).bytes)
    assert.deepEqual(toJson(again), toJson(doc))
    assert.deepEqual(again.lists, doc.lists)
})

test('merging with a web that has nothing keeps everything the file holds', async () => {
    const doc = await read(bytes('core-v3.json.gz'))
    const now = doc.updatedAt + 1_000
    const merged = merge(doc, { ...emptyDoc(ACCOUNT), updatedAt: now }, now)
    assert.deepEqual(toJson({ ...merged, updatedAt: doc.updatedAt, appVersion: doc.appVersion }), toJson(doc))
})

// ---- parity with core -----------------------------------------------------------------------

const b64 = (s: string) => new Uint8Array(Buffer.from(s, 'base64'))

function summary(a: SyncApply) {
    const at = (m: Map<string, number>) => [...m].map(([k, v]) => `${k}@${v}`).sort()
    return {
        resumeIn: a.resumeIn.map((r) => `${keyOf(r.chat, r.msg)}@${r.updatedAt}`).sort(),
        resumeOut: at(a.resumeOut),
        watchedIn: a.watchedIn.map((w) => `${keyOf(w.chat, w.msg)}@${w.watchedAt}@${w.manual}`).sort(),
        watchedOut: at(a.watchedOut),
        laterIn: a.laterIn.map((l) => `${l.series ? `${l.chat}_s:${l.series}` : keyOf(l.chat, l.msg)}@${l.addedAt}`).sort(),
        laterOut: at(a.laterOut),
        favouritesIn: [...a.favouritesIn].map(String).sort(),
        favouritesOut: [...a.favouritesOut].map(String).sort(),
        bookmarksIn: a.bookmarksIn.map((m) => `${m.chat}_${m.msg}_${m.id}@${m.at}`).sort(),
        bookmarksOut: at(a.bookmarksOut),
        correctionsIn: a.correctionsIn.map((c) => `${keyOf(c.chat, c.msg)}@${c.at}`).sort(),
        correctionsOut: at(a.correctionsOut),
    }
}

test('a hundred seeded pairs: the merge and what it changes are the apps\' (core/WebSyncParityTest)', async () => {
    const file = JSON.parse(readFileSync(fixture('parity.json'), 'utf8')) as { now: number; account: number; cases: { a: string; b: string; merged: string; apply: Record<string, string[]> }[] }
    assert.ok(file.cases.length >= 100)
    for (const [i, c] of file.cases.entries()) {
        const a = await read(b64(c.a), file.account)
        const b = await read(b64(c.b), file.account)
        const theirs = await read(b64(c.merged), file.account)
        const mine = merge(a, b, file.now)
        assert.deepEqual(toJson(mine), toJson(theirs), `case ${i} merged`)
        assert.deepEqual(summary(apply(a, mine)), c.apply, `case ${i} apply`)
        // The algebra the apps test: the order the files are read in does not matter, nor reading one twice.
        assert.deepEqual(toJson(merge(b, a, file.now)), toJson(mine), `case ${i} commutative`)
        assert.deepEqual(toJson(merge(mine, mine, file.now)), toJson(mine), `case ${i} idempotent`)
    }
})

// ---- what the web writes, for the apps to read ------------------------------------------------

/** The file the web wrote for core's WebSyncParityTest.coreReadsTheFileTheWebWrote. */
function webFile(): SyncDoc {
    const t = 1_799_999_990_000
    const doc = emptyDoc(ACCOUNT)
    doc.updatedAt = t + 5_000
    doc.appVersion = 'web'
    doc.resume.push({ chat: CHAT, msg: tdlibMessageId(5), file: 1, title: 'Night Lantern', chatTitle: '', size: 0, durationSec: 5400, positionMs: 754_000, durationMs: 5_400_000, updatedAt: t })
    doc.watched.push({ chat: CHAT, msg: tdlibMessageId(6), file: 0, title: 'Video', chatTitle: '', size: 0, durationSec: 0, watchedAt: t - 1_000, manual: false })
    doc.later.push({ chat: CHAT, msg: tdlibMessageId(8), file: 0, title: 'Video', fileName: '', chatTitle: '', size: 0, durationSec: 0, mime: 'video/mp4', addedAt: t - 2_000, series: '' })
    doc.favourites.set(CHAT, t - 3_000)
    doc.removed.push({ list: 'watched', key: keyOf(CHAT, tdlibMessageId(7)), at: t - 500 })
    doc.corrections.push({ chat: CHAT, msg: tdlibMessageId(6), text: 'tmdb=film:99', at: t - 4_000 })
    doc.bookmarks.push({ chat: CHAT, msg: tdlibMessageId(5), id: 1_799_999_000_000, positionMs: 120_000, note: 'a note the web kept untouched', at: t - 5_000 })
    doc.lists = { lists: [{ id: 'wkend1', name: 'Weekend', at: t - 6_000, home: true }], entries: [], removed: [] }
    return doc
}

test('the file the web writes reads back as written, and is the one core reads (fixtures/web-written.json.gz)', async () => {
    const doc = webFile()
    const written = (await encode(doc)).bytes
    if (process.env.TMPLAYER_WRITE_WEB_FIXTURES === '1') writeFileSync(fixture('web-written.json.gz'), written)
    assert.deepEqual(toJson(await read(written)), toJson(doc))
    // The checked in file is what the web writes now, so core's test reads what ships.
    assert.deepEqual(toJson(await read(bytes('web-written.json.gz'))), toJson(doc))
})

test('message ids cross between the web\'s and TDLib\'s', () => {
    assert.equal(tdlibMessageId(5), 5 * SHIFT)
    assert.equal(webMessageId(5 * SHIFT), 5)
    for (const odd of [0, -SHIFT, SHIFT + 1, 1.5, NaN, Infinity, 2 ** 60]) assert.equal(webMessageId(odd), null)
})

test('a file over its size is cut, the oldest places first, and the cut is counted', async () => {
    const doc = emptyDoc(ACCOUNT)
    for (let i = 1; i <= 200; i++) doc.resume.push({ chat: -100, msg: i * SHIFT, file: 1, title: `Video ${i} ${'x'.repeat(30)}`, chatTitle: '', size: 0, durationSec: 60, positionMs: i * 1_000, durationMs: 60_000, updatedAt: 1_000 + i })
    const full = await encode(doc)
    assert.equal(full.dropped.resume, 0)
    const small = await encode(doc, 1_500)
    assert.ok(small.bytes.length <= 1_500)
    assert.ok(small.dropped.resume > 0)
    assert.ok(small.doc.resume.every((r) => r.updatedAt > 1_000 + small.dropped.resume), 'the oldest go first')
})

test('the same lists written by two devices are the same file whoever wrote them and when', async () => {
    const doc = await read(bytes('core-v3.json.gz'))
    assert.ok(sameLists(doc, { ...doc, account: 1, updatedAt: 5, appVersion: 'web' }))
    assert.ok(!sameLists(doc, { ...doc, favourites: new Map() }))
})

test('a clock that runs fast cannot make its entries win for ever', () => {
    const now = 1_800_000_000_000
    const a = emptyDoc(1)
    a.resume.push({ chat: 1, msg: SHIFT, file: 1, title: 'A', chatTitle: '', size: 0, durationSec: 0, positionMs: 1_000, durationMs: 0, updatedAt: now + 400 * 24 * 3_600_000 })
    const b = emptyDoc(1)
    b.resume.push({ chat: 1, msg: SHIFT, file: 1, title: 'A', chatTitle: '', size: 0, durationSec: 0, positionMs: 9_000, durationMs: 0, updatedAt: now + 1_000 })
    const { doc } = mergeCounting(a, b, now)
    assert.equal(doc.resume.length, 1)
    assert.equal(doc.resume[0].updatedAt, now + 1_000, 'the far future reads as now, so the later real time wins')
})

// ---- garbage --------------------------------------------------------------------------------

const text = (v: unknown) => new TextEncoder().encode(typeof v === 'string' ? v : JSON.stringify(v))
const sync = (extra: Record<string, unknown>) => ({ format: 'tmplayer-sync', version: 3, account: ACCOUNT, updatedAt: 1, app: 'x', ...extra })

test('anything that is not a sync file is said so and never throws', async () => {
    const junk: Uint8Array[] = [
        new Uint8Array(),
        new Uint8Array([0x1f, 0x8b]),
        new Uint8Array([0x1f, 0x8b, 8, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3]),
        text(''),
        text('{'),
        text('null'),
        text('[]'),
        text('42'),
        text({}),
        text({ format: 'other', version: 1 }),
        text({ format: 'tmplayer-sync' }),
        text({ format: 'tmplayer-sync', version: 'x' }),
        text({ format: 'tmplayer-sync', version: 0 }),
        text({ format: 'tmplayer-sync', version: NaN }),
        text('{"a":'.repeat(50_000)),
        text(JSON.stringify([[[[[[]]]]]])),
        new Uint8Array(MAX_READ_BYTES + 1),
        new Uint8Array(Array.from({ length: 2_000 }, (_, i) => (i * 37) & 255)),
    ]
    for (const j of junk) assert.equal((await decode(j, ACCOUNT)).kind, 'notSync')
})

test('a file from a newer build is left alone, another account\'s is not read', async () => {
    assert.deepEqual(await decode(text(sync({ version: 4 })), ACCOUNT), { kind: 'tooNew', version: 4 })
    assert.deepEqual(await decode(text(sync({ version: 1e12 })), ACCOUNT), { kind: 'tooNew', version: 2 ** 31 - 1 })
    assert.deepEqual(await decode(text(sync({ account: 9 })), ACCOUNT), { kind: 'otherAccount', account: 9 })
    assert.equal((await decode(text(sync({ account: 0 })), ACCOUNT)).kind, 'ok')
})

test('entries with a missing, wrong-typed or absurd field are skipped on their own and the rest read', async () => {
    const r = await read(
        text(
            sync({
                resume: [null, 3, 'x', {}, { chat: 1 }, { chat: '1', message: 2, file: 1, title: 'a', positionMs: 1 }, { chat: 1, message: 2, file: 0, title: 'a', positionMs: 1 }, { chat: 1, message: 2, file: 3, title: '  ', positionMs: 1 }, { chat: 1, message: SHIFT, file: 3, title: 'ok', positionMs: 5, state: 'not,five' }],
                watched: [{ chat: 1, message: 2 }, { chat: 1, message: SHIFT, watchedAt: 7, manual: 'yes' }],
                watchLater: [{ chat: 1, message: 2, addedAt: 'x' }, { chat: 1, message: SHIFT, addedAt: 3, title: 't', series: 5 }],
                favourites: [{ chat: 'x' }, { chat: 5 }, { chat: 5, at: 9 }],
                removed: [{ list: 'nope', key: '1_2', at: 1 }, { list: 'resume', key: 'abc', at: 1 }, { list: 'resume', key: '1_2', at: 'x' }, { list: 'resume', key: '1_2', at: 4 }],
                bookmarks: [{ chat: 1, msg: 2, id: -1, pos: 3 }, { chat: 1, msg: 2, id: 5, pos: -3 }, { chat: 1, msg: 2, id: 5, pos: 3, note: 'n' }],
                corrections: [{ chat: 1, msg: 2, fix: 7 }, { chat: 1, msg: 2, fix: '   ' }, { chat: 1, msg: 2, fix: 'tmdb=film:5' }],
                lists: 'not an object',
            }),
        ),
    )
    assert.equal(r.resume.length, 1)
    assert.equal(r.resume[0].state, undefined)
    assert.deepEqual(r.watched.map((w) => w.manual), [false])
    assert.deepEqual(r.later.map((l) => l.series), [''])
    assert.deepEqual([...r.favourites], [[5, 9]])
    assert.deepEqual(r.removed, [{ list: 'resume', key: '1_2', at: 4 }])
    assert.equal(r.bookmarks.length, 1)
    assert.equal(r.corrections.length, 1)
    assert.equal(r.lists, undefined)
})

test('huge lists are cut to twice their cap on reading, a huge title to its bound', async () => {
    const many = Array.from({ length: 5_000 }, (_, i) => ({ chat: 1, message: (i + 1) * SHIFT, file: 3, title: 'y'.repeat(1_000), positionMs: 5, updatedAt: i }))
    const r = await read(await gzip(text(sync({ resume: many }))))
    assert.ok(r.resume.length <= 400)
    assert.ok(r.resume.every((x) => x.title.length <= 60))
})

test('two copies\' named lists are joined by id, the newest winning for each, nothing dropped for being absent', () => {
    const a = { lists: [{ id: 'aaa', name: 'Old', at: 1 }, { id: 'bbb', name: 'Only A', at: 5 }], entries: [{ list: 'aaa', chat: 1, message: 2, addedAt: 3, title: 'A' }], removed: [{ list: 'aaa', at: 9 }] }
    const b = { lists: [{ id: 'aaa', name: 'New', at: 7 }], entries: [{ list: 'aaa', chat: 1, message: 2, addedAt: 4, title: 'B' }, { list: 'aaa', chat: 1, message: 3, addedAt: 1, title: 'C' }], removed: [{ list: 'aaa', entry: '1_2', at: 2 }] }
    const u = unionLists(a, b) as { lists: { id: string; name: string }[]; entries: { title: string }[]; removed: unknown[] }
    assert.deepEqual(u.lists.map((l) => `${l.id}:${l.name}`), ['aaa:New', 'bbb:Only A'])
    assert.deepEqual(u.entries.map((e) => e.title).sort(), ['B', 'C'])
    assert.equal(u.removed.length, 2)
    assert.deepEqual(unionLists(b, a), unionLists(a, b), 'the order the copies are read in does not matter')
    for (const junk of [null, 'x', 7, [], { lists: 'no', entries: [null, 3] }]) assert.doesNotThrow(() => unionLists(junk, a))
    const merged = merge({ ...emptyDoc(1), lists: a, updatedAt: 1 }, { ...emptyDoc(1), lists: b, updatedAt: 2 }, 10)
    assert.equal((merged.lists as { lists: unknown[] }).lists.length, 2)
})

test('random shapes never throw in reading, merging or applying', async () => {
    let seed = 11
    const next = () => (seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff
    const leaf = () => [null, 0, -3, 1.5, 'x', '', true, '1_2', 2 ** 40, NaN][Math.floor(next() * 10)]
    const pick = (depth: number): unknown => {
        const r = next()
        if (depth > 3 || r < 0.35) return leaf()
        if (r < 0.6) return Array.from({ length: Math.floor(next() * 4) }, () => pick(depth + 1))
        const o: Record<string, unknown> = {}
        for (const k of ['chat', 'message', 'msg', 'file', 'title', 'positionMs', 'updatedAt', 'watchedAt', 'addedAt', 'at', 'manual', 'list', 'key', 'id', 'pos', 'note', 'fix', 'series', 'state']) if (next() < 0.4) o[k] = pick(depth + 1)
        return o
    }
    for (let i = 0; i < 400; i++) {
        const j = text(sync({ resume: pick(0), watched: pick(0), watchLater: pick(0), favourites: pick(0), removed: pick(0), bookmarks: pick(0), corrections: pick(0), lists: pick(0) }))
        const r = await decode(j, ACCOUNT)
        if (r.kind !== 'ok') continue
        assert.doesNotThrow(() => apply(r.doc, merge(r.doc, emptyDoc(ACCOUNT), 1_800_000_000_000)))
        await encode(r.doc)
    }
})
