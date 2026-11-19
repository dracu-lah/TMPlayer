// One sync end to end over a fake Saved Messages and fake devices: nothing before the switch is
// on, one file edited in place, two devices meeting through it, a removal reaching the other, and
// every way the file can be wrong (newer, unreadable, another account's, deleted, doubled).
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { decode, emptyDoc, encode, keyOf, tdlibMessageId, type SyncDoc } from './format.ts'
import type { Lists, Writer } from './local.ts'
import { RESEND_GUARD_MS, freshState, parseState, runSync, type Deps, type State, type SyncFile } from './run.ts'

const CHAT = -1001234567890
const T0 = 1_800_000_000_000

/** The account's Saved Messages: the messages that hold a file, and a count of every call made. */
function cloud() {
    const messages: { id: number; bytes: Uint8Array; date: number }[] = []
    let next = 100
    const calls = { files: 0, write: 0, remove: 0 }
    return {
        messages,
        calls,
        transport: {
            async files(): Promise<SyncFile[]> {
                calls.files++
                return messages.map((m) => ({ id: m.id, date: m.date, bytes: m.bytes.slice().buffer as ArrayBuffer }))
            },
            async write(target: number | null, bytes: Uint8Array) {
                calls.write++
                if (target !== null) {
                    const m = messages.find((x) => x.id === target)
                    if (!m) return { gone: true as const }
                    m.bytes = bytes.slice()
                    return { id: m.id }
                }
                messages.push({ id: next++, bytes: bytes.slice(), date: 1 })
                return { id: messages[messages.length - 1].id }
            },
            async remove(ids: number[]) {
                calls.remove++
                for (const id of ids) messages.splice(messages.findIndex((m) => m.id === id), 1)
            },
        },
    }
}

/** A device: its lists, what the sync wrote into them, and its state. */
function device(c: ReturnType<typeof cloud>, clock: { t: number }, over: { account?: number; on?: boolean } = {}) {
    const lists: Lists = { resumes: [], watched: [], later: [], stars: [], picks: [], tombs: [] }
    let state: State = { ...freshState(), on: over.on ?? true }
    const writer: Writer = {
        putResume: (r) => void (lists.resumes = [...lists.resumes.filter((x) => x.chatId !== r.chatId || x.messageId !== r.messageId), r]),
        dropResume: (c2, m) => void (lists.resumes = lists.resumes.filter((x) => x.chatId !== c2 || x.messageId !== m)),
        putWatched: (c2, m, t) => void (lists.watched = [...lists.watched.filter((x) => x.chatId !== c2 || x.messageId !== m), { chatId: c2, messageId: m, time: t }]),
        dropWatched: (c2, m) => void (lists.watched = lists.watched.filter((x) => x.chatId !== c2 || x.messageId !== m)),
        video: async (c2, m) => ({ chatId: c2, messageId: m, title: `V${m}`, size: 1, duration: 2 }) as never,
        putLater: (i, t) => void lists.later.push({ chatId: i.chatId, messageId: i.messageId, time: t, title: 'x', size: 0, duration: 0 }),
        dropLater: (c2, m) => void (lists.later = lists.later.filter((x) => x.chatId !== c2 || x.messageId !== m)),
        putStar: (c2, t) => void lists.stars.push({ chatId: c2, time: t }),
        dropStar: (c2) => void (lists.stars = lists.stars.filter((x) => x.chatId !== c2)),
        putPick: () => {},
        putTitle: () => {},
    }
    const deps = (): Deps => ({
        now: () => clock.t,
        account: over.account ?? 777,
        appVersion: 'web',
        caption: 'what this file is',
        state: { get: async () => state, set: async (p) => void (state = { ...state, ...p }) },
        transport: c.transport,
        lists: async () => lists,
        writer,
        offline: () => false,
        humanise: (e) => String((e as Error).message ?? e),
    })
    return { lists, deps, get state() { return state }, set state(s: State) { state = s } }
}

const place = (messageId: number, position: number, time: number) => ({ chatId: CHAT, messageId, position, length: 600, time })
async function fileDoc(c: ReturnType<typeof cloud>): Promise<SyncDoc> {
    const r = await decode(c.messages[0].bytes, 777)
    assert.equal(r.kind, 'ok')
    return (r as { doc: SyncDoc }).doc
}

test('with the switch off a sync touches nothing: no read, no write, nothing sent to Saved Messages', async () => {
    const c = cloud()
    const clock = { t: T0 }
    const a = device(c, clock, { on: false })
    a.lists.resumes.push(place(5, 300, T0))
    assert.deepEqual(await runSync(a.deps()), { kind: 'off' })
    assert.deepEqual(c.calls, { files: 0, write: 0, remove: 0 })
    assert.equal(c.messages.length, 0)
})

test('turned on, the first sync sends one file with this device\'s places; the next with nothing changed writes nothing', async () => {
    const c = cloud()
    const clock = { t: T0 }
    const a = device(c, clock)
    a.lists.resumes.push(place(5, 300, T0 - 10))
    const out = await runSync(a.deps())
    assert.deepEqual(out, { kind: 'done', received: 0, wrote: true, dropped: 0 })
    assert.equal(c.messages.length, 1)
    const doc = await fileDoc(c)
    assert.deepEqual(doc.resume.map((r) => [r.chat, r.msg, r.positionMs, r.title]), [[CHAT, tdlibMessageId(5), 300_000, 'Video']])
    assert.equal(doc.account, 777)
    assert.equal(a.state.file, c.messages[0].id)
    clock.t += 5 * 60_000
    const again = await runSync(a.deps())
    assert.deepEqual(again, { kind: 'done', received: 0, wrote: false, dropped: 0 })
    assert.equal(c.calls.write, 1)
})

test('two devices meet through the one file, edited in place, never a second message', async () => {
    const c = cloud()
    const clock = { t: T0 }
    const a = device(c, clock)
    const b = device(c, clock)
    a.lists.resumes.push(place(5, 300, T0 - 10))
    await runSync(a.deps())
    clock.t += 60_000
    const onB = await runSync(b.deps())
    assert.equal(onB.kind, 'done')
    assert.deepEqual(b.lists.resumes.map((r) => [r.chatId, r.messageId, r.position, r.time]), [[CHAT, 5, 300, T0 - 10]])
    // B moves on; A takes it in.
    clock.t += 60_000
    b.lists.resumes[0] = place(5, 480, clock.t - 5)
    b.lists.resumes.push(place(9, 60, clock.t - 4))
    await runSync(b.deps())
    clock.t += 60_000
    const onA = await runSync(a.deps())
    assert.equal((onA as { received: number }).received, 2)
    assert.deepEqual(a.lists.resumes.map((r) => [r.messageId, r.position]).sort(), [[5, 480], [9, 60]])
    assert.equal(c.messages.length, 1, 'the same message, replaced in place')
})

test('a mark taken off on one device is taken off on the other, not brought back by the older file', async () => {
    const c = cloud()
    const clock = { t: T0 }
    const a = device(c, clock)
    const b = device(c, clock)
    a.lists.watched.push({ chatId: CHAT, messageId: 6, time: T0 - 1_000 })
    await runSync(a.deps())
    clock.t += 1_000
    await runSync(b.deps())
    assert.equal(b.lists.watched.length, 1)
    // A takes the mark off (the store notes it), B syncs, then A again.
    clock.t += 1_000
    a.lists.watched = []
    a.lists.tombs.push({ list: 'watched', key: keyOf(CHAT, tdlibMessageId(6)), at: clock.t })
    clock.t += 1_000
    await runSync(a.deps())
    clock.t += 1_000
    await runSync(b.deps())
    assert.equal(b.lists.watched.length, 0)
    clock.t += 1_000
    await runSync(a.deps())
    assert.equal(a.lists.watched.length, 0)
    assert.ok((await fileDoc(c)).removed.some((r) => r.list === 'watched'))
})

test('a place removed here since the last sync is told to the file, so another device drops it', async () => {
    const c = cloud()
    const clock = { t: T0 }
    const a = device(c, clock)
    const b = device(c, clock)
    a.lists.resumes.push(place(5, 300, T0 - 10_000))
    await runSync(a.deps())
    clock.t += 1_000
    await runSync(b.deps())
    clock.t += 1_000
    a.lists.resumes = []
    await runSync(a.deps())
    clock.t += 1_000
    await runSync(b.deps())
    assert.deepEqual(b.lists.resumes, [])
})

test('a file from a newer build is left as it is and this device\'s lists are kept', async () => {
    const c = cloud()
    c.messages.push({ id: 1, date: 1, bytes: new TextEncoder().encode(JSON.stringify({ format: 'tmplayer-sync', version: 9, account: 777 })) })
    const before = c.messages[0].bytes.slice()
    const a = device(c, { t: T0 })
    a.lists.resumes.push(place(5, 300, T0 - 10))
    assert.deepEqual(await runSync(a.deps()), { kind: 'tooNew', version: 9 })
    assert.equal(c.calls.write, 0)
    assert.deepEqual(c.messages[0].bytes, before)
    assert.equal(a.lists.resumes.length, 1)
})

test('a file that cannot be read is not written over unless the viewer asks, and then it is replaced', async () => {
    const c = cloud()
    c.messages.push({ id: 1, date: 1, bytes: new TextEncoder().encode('not a sync file') })
    const a = device(c, { t: T0 })
    a.lists.resumes.push(place(5, 300, T0 - 10))
    assert.deepEqual(await runSync(a.deps()), { kind: 'unreadable' })
    assert.equal(c.calls.write, 0)
    const out = await runSync(a.deps(), { replace: true })
    assert.equal((out as { wrote: boolean }).wrote, true)
    assert.equal((await fileDoc(c)).resume.length, 1)
    assert.equal(c.messages.length, 1)
})

test('another account\'s file is never merged: the sync turns itself off', async () => {
    const c = cloud()
    const a = device(c, { t: T0 })
    a.state = { ...a.state, account: 5 }
    assert.deepEqual(await runSync(a.deps()), { kind: 'otherAccount' })
    assert.equal(a.state.on, false)
    assert.equal(c.calls.files, 0)
    const other = cloud()
    other.messages.push({ id: 1, date: 1, bytes: (await encode({ ...emptyDoc(999), updatedAt: 1 })).bytes })
    const b = device(other, { t: T0 })
    assert.deepEqual(await runSync(b.deps()), { kind: 'unreadable' })
    assert.equal(other.calls.write, 0)
})

test('a file deleted since it was read is sent anew once, and not again before Telegram has shown it', async () => {
    const c = cloud()
    const clock = { t: T0 }
    const a = device(c, clock)
    a.lists.resumes.push(place(5, 300, T0 - 10))
    await runSync(a.deps())
    c.messages.length = 0
    a.state = { ...a.state, file: 100 }
    clock.t += 60_000
    // The viewer deleted the message: no copy is found, a new one is wanted, but one was sent a minute ago.
    assert.deepEqual(await runSync(a.deps()), { kind: 'waitingForFile' })
    assert.equal(c.messages.length, 0)
    clock.t += RESEND_GUARD_MS
    assert.equal((await runSync(a.deps())).kind, 'done')
    assert.equal(c.messages.length, 1)
})

test('two copies are both read and merged in, and left for the apps to put right: the web writes nothing and deletes nothing', async () => {
    const c = cloud()
    const one = emptyDoc(777)
    one.updatedAt = T0 - 5_000
    one.resume.push({ chat: CHAT, msg: tdlibMessageId(5), file: 3, title: 'One', chatTitle: '', size: 0, durationSec: 0, positionMs: 1_000, durationMs: 0, updatedAt: T0 - 5_000 })
    const two = emptyDoc(777)
    two.updatedAt = T0 - 4_000
    two.resume.push({ chat: CHAT, msg: tdlibMessageId(8), file: 3, title: 'Two', chatTitle: '', size: 0, durationSec: 0, positionMs: 2_000, durationMs: 0, updatedAt: T0 - 4_000 })
    c.messages.push({ id: 1, date: 1, bytes: (await encode(one)).bytes }, { id: 2, date: 2, bytes: (await encode(two)).bytes })
    const a = device(c, { t: T0 })
    const out = await runSync(a.deps())
    // Said so ("two copies"), not "done": the viewer is told nothing was written here.
    assert.deepEqual(out, { kind: 'twoCopies' })
    assert.deepEqual(a.lists.resumes.map((r) => r.messageId).sort(), [5, 8])
    assert.deepEqual(c.calls, { files: 1, write: 0, remove: 0 })
})

test('a file the apps wrote keeps everything the web does not edit when the web writes it back', async () => {
    const c = cloud()
    c.messages.push({ id: 1, date: 1, bytes: new Uint8Array(readFileSync(new URL('./fixtures/core-v3.json.gz', import.meta.url))) })
    const clock = { t: 1_800_000_000_000 }
    const a = device(c, clock)
    a.lists.resumes.push(place(11, 120, clock.t - 5))
    const out = await runSync(a.deps())
    assert.equal(out.kind, 'done')
    assert.equal((out as { wrote: boolean }).wrote, true)
    const doc = await fileDoc(c)
    assert.equal(doc.bookmarks.length, 1)
    assert.ok(doc.lists)
    assert.equal(doc.later.filter((l) => l.series).length, 1)
    assert.ok(doc.resume.some((r) => r.msg === tdlibMessageId(11)))
    assert.ok(doc.resume.some((r) => r.msg === tdlibMessageId(5)))
    assert.equal(c.messages.length, 1)
})

test('a connection that fails is a failure in words, not a thrown error, and nothing is half written', async () => {
    const c = cloud()
    const a = device(c, { t: T0 })
    a.lists.resumes.push(place(5, 300, T0 - 10))
    const deps = a.deps()
    deps.transport = { ...deps.transport, files: async () => { throw new Error('FLOOD_WAIT_30') } }
    assert.deepEqual(await runSync(deps), { kind: 'failed', reason: 'FLOOD_WAIT_30' })
    const down = a.deps()
    down.offline = () => true
    assert.deepEqual(await runSync(down), { kind: 'noConnection' })
    assert.equal(c.messages.length, 0)
})

test('a switch turned off while a sync runs (another tab, or Turn off and delete) stops it before any write', async () => {
    const c = cloud()
    const clock = { t: T0 }
    const a = device(c, clock)
    a.lists.resumes.push(place(5, 300, T0 - 10))
    const deps = a.deps()
    const files = deps.transport.files
    deps.transport = { ...deps.transport, files: async (k) => { const r = await files(k); a.state = { ...a.state, on: false }; return r } }
    assert.deepEqual(await runSync(deps), { kind: 'off' })
    assert.equal(c.calls.write, 0)
    assert.equal(c.messages.length, 0)
})

test('a file deleted under a sync that was turned off is not sent anew', async () => {
    const c = cloud()
    const clock = { t: T0 }
    const a = device(c, clock)
    a.lists.resumes.push(place(5, 300, T0 - 10))
    await runSync(a.deps())
    a.lists.resumes.push(place(9, 60, T0 - 5))
    clock.t += RESEND_GUARD_MS + 1
    const deps = a.deps()
    deps.transport = { ...deps.transport, write: async () => { a.state = { ...a.state, on: false }; c.messages.length = 0; return { gone: true as const } } }
    assert.deepEqual(await runSync(deps), { kind: 'off' })
    assert.equal(c.messages.length, 0)
})

test('the state kept is read strictly: the switch is on only when it is true', () => {
    for (const bad of [null, 'x', 7, [], { on: 'true' }, { on: 1 }, { on: {} }, {}]) assert.equal(parseState(bad)?.on ?? false, false)
    assert.equal(parseState({ on: true, file: 'x', account: null })?.on, true)
    assert.deepEqual(parseState({ on: true, base: { resumeKeys: [1, 'a'], favourites: ['x', 2] } })?.base, { resumeKeys: ['a'], favourites: [2], at: 0 })
})
