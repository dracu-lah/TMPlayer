// The remembered online subtitle (HX25): recency, forgetting, Off, a garbage record, a store that
// refuses, 5,000 picks, and what opening a video finds. `node --test "src/player/*.test.ts"` (pnpm test:player).
import assert from 'node:assert/strict'
import { test, beforeEach } from 'node:test'
import { forgetKept, setKeepStore, type Hit, type KeepStore } from './msone.ts'
import { boundCopies, clearOff, forgetPick, MAX_COPY_BYTES, MAX_PICKS, pickCount, readState, recallPick, rememberOff, rememberPick, restorePick, setPickClock, setPickStore, wipePicks, type RestoreDeps } from './subpicks.ts'
import { wipeAllPicks } from './subpicks-db.ts'

const hit = (id: string): Hit => ({
    id, title: `Harbour Notes ${id}`, year: 2020, season: null, episode: null, translator: { name: 'Translator name', url: null },
    pageUrl: null, downloadUrl: 'https://malayalamsubtitles.org/download/x',
})
const srt = (name = 'a.srt', size = 40) => new File([new Uint8Array(size).fill(65)], name)

function mapStore() {
    const map = new Map<string, unknown>()
    const store: KeepStore = {
        get: async (k) => map.get(k),
        set: async (k, v) => void map.set(k, v),
        del: async (ks) => void ks.forEach((k) => map.delete(k)),
        keys: async () => [...map.keys()],
    }
    return { map, store }
}

let t = 1_800_000_000_000
let fake = mapStore()
beforeEach(() => {
    fake = mapStore()
    setPickStore(fake.store)
    setPickClock(() => (t += 1000))
})

const KEYS = ['msg:5:77', 'video:harbour notes|2020']
const copies = () => [...fake.map.keys()].filter((k) => k.startsWith('copy|'))

test('a pick comes back with its own copy, by the post or by the video itself', async () => {
    await rememberPick(KEYS, hit('1'), srt('Harbour.srt'))
    const same = await recallPick(['msg:5:77'])
    assert.ok(same && !same.off)
    assert.equal(same.hit.id, '1')
    assert.equal(same.file?.name, 'Harbour.srt')
    assert.equal(same.file?.size, 40)
    // A re-post of the video (another message) finds it by the video's key.
    const repost = await recallPick(['msg:5:99', 'video:harbour notes|2020'])
    assert.ok(repost && !repost.off && repost.hit.id === '1')
    assert.equal(await recallPick(['msg:5:99']), null)
    assert.equal(await recallPick([]), null)
})

test('Off is remembered, replaces a pick, and a track chosen again ends only an Off', async () => {
    await rememberPick(KEYS, hit('1'), srt())
    await rememberOff(KEYS)
    assert.deepEqual(await recallPick(KEYS), { off: true })
    // The copy of the replaced pick is gone with it.
    assert.deepEqual(copies(), [])
    await clearOff(KEYS)
    assert.equal(await recallPick(KEYS), null)
    // clearOff never drops a real pick.
    await rememberPick(KEYS, hit('2'), srt())
    await clearOff(KEYS)
    const kept = await recallPick(KEYS)
    assert.ok(kept && !kept.off && kept.hit.id === '2')
})

test('any other explicit choice forgets the pick and its copy', async () => {
    await rememberPick(KEYS, hit('1'), srt())
    await forgetPick(['msg:5:77'])
    // Only the keys named: the video's own key still has it, as in core.
    assert.ok(await recallPick(['video:harbour notes|2020']))
    await forgetPick(KEYS)
    assert.equal(await recallPick(KEYS), null)
    assert.deepEqual(copies(), [])
})

test('a copy shared by two videos stays while one of them still has the pick', async () => {
    await rememberPick(['msg:1:1'], hit('9'), srt())
    await rememberPick(['msg:1:2'], hit('9'), srt())
    await forgetPick(['msg:1:1'])
    const left = await recallPick(['msg:1:2'])
    assert.ok(left && !left.off && left.file)
})

test('recalling refreshes recency: the pick looked at survives a full list', async () => {
    for (let n = 1; n <= MAX_PICKS; n++) await rememberOff([`msg:1:${n}`])
    assert.ok(await recallPick(['msg:1:1']))
    await rememberOff(['msg:2:1'])
    assert.ok(await recallPick(['msg:1:1']), 'the one used now is the last to go')
    assert.equal(await recallPick(['msg:1:2']), null, 'the least recently used went')
    assert.equal(await pickCount(), MAX_PICKS)
})

test('5,000 picks stay bounded to 400, newest kept', async () => {
    for (let n = 1; n <= 5000; n++) await rememberOff([`msg:1:${n}`, `video:film ${n}|2020`])
    assert.equal(await pickCount(), MAX_PICKS)
    assert.deepEqual(await recallPick(['msg:1:5000']), { off: true })
    assert.equal(await recallPick(['msg:1:1']), null)
})

test('a file of the wrong size is not copied, the pick still stands', async () => {
    await rememberPick(KEYS, hit('1'), srt('big.srt', 4 * 1024 * 1024 + 1))
    const got = await recallPick(KEYS)
    assert.ok(got && !got.off && got.file === null)
    await rememberPick(['msg:3:3'], hit('1'), new File([], 'empty.srt'))
    const empty = await recallPick(['msg:3:3'])
    assert.ok(empty && !empty.off && empty.file === null)
})

test('a copy that is gone or garbage reads as no copy, not an error', async () => {
    await rememberPick(KEYS, hit('1'), srt())
    fake.map.delete('copy|1')
    let got = await recallPick(KEYS)
    assert.ok(got && !got.off && got.file === null)
    fake.map.set('copy|1', { name: 5, bytes: 'x' })
    got = await recallPick(KEYS)
    assert.ok(got && !got.off && got.file === null)
})

test('garbage state reads as no picks, and bad entries leave the good ones', async () => {
    for (const bad of [null, undefined, 7, 'x', [], {}, { picks: 'x' }, { picks: [null, 1, 'x', [], {}] }]) assert.deepEqual(readState(bad), [])
    const state = {
        v: 1,
        picks: [
            { key: '', at: 1, off: true },
            { key: 'k'.repeat(401), at: 1, off: true },
            { key: 'a', at: 'x', hit: hit('1') },
            { key: 'b', at: 5, hit: { id: 7 } },
            { key: 'c', at: 6, hit: { ...hit('2'), downloadUrl: 'http://x' } },
            { key: 'good', at: 7, hit: hit('3'), copy: 'other-id' },
            { key: 'off', at: 8, off: true },
        ],
    }
    assert.deepEqual(readState(state).map((e) => [e.key, e.copy]), [['a', null], ['good', null], ['off', null]])
    fake.map.set('state', state)
    assert.deepEqual(await recallPick(['good']), { off: false, hit: hit('3'), file: null })
    fake.map.set('state', { picks: new Array(100_000).fill({ key: 'x', at: 1, off: true }) })
    assert.ok((await pickCount()) <= 1)
    // Keys are strings, bounded, and few.
    fake.map.clear()
    assert.equal(await recallPick([5 as unknown as string, '', 'x'.repeat(401)]), null)
    await rememberOff([5 as unknown as string])
    assert.equal(await pickCount(), 0)
})

test('sign out wipes the picks and the copies, and nothing else in the store', async () => {
    await rememberPick(KEYS, hit('1'), srt())
    await rememberOff(['msg:2:2'])
    fake.map.set('unrelated', 1)
    await wipePicks()
    assert.deepEqual([...fake.map.keys()], ['unrelated'])
    assert.equal(await recallPick(KEYS), null)
})

test('MSone stopping for good wipes the picks too', async () => {
    setKeepStore(mapStore().store)
    await rememberPick(KEYS, hit('1'), srt())
    await forgetKept()
    await new Promise((r) => setTimeout(r, 20))
    assert.equal(await recallPick(KEYS), null)
})

test('a store that fails or hangs (a private window) is no picks and no error', async () => {
    const failing: KeepStore = {
        get: async () => { throw new Error('denied') },
        set: async () => { throw new Error('full') },
        del: async () => { throw new Error('x') },
        keys: async () => { throw new Error('x') },
    }
    setPickStore(failing)
    await rememberPick(KEYS, hit('1'), srt())
    await rememberOff(KEYS)
    await forgetPick(KEYS)
    await wipePicks()
    assert.equal(await recallPick(KEYS), null)
    assert.equal(await pickCount(), 0)
    const sync: KeepStore = {
        get: () => { throw new Error('no indexedDB') },
        set: () => { throw new Error('x') },
        del: () => { throw new Error('x') },
        keys: () => { throw new Error('x') },
    }
    setPickStore(sync)
    assert.equal(await recallPick(KEYS), null)
})

test('a store that hangs is given up on', { timeout: 10_000 }, async () => {
    const hanging: KeepStore = { get: () => new Promise(() => {}), set: () => new Promise(() => {}), del: () => new Promise(() => {}), keys: () => new Promise(() => {}) }
    setPickStore(hanging)
    assert.equal(await recallPick(KEYS), null)
    await rememberOff(KEYS)
})

test('a full disk for the copy keeps the pick without it', async () => {
    const real = fake.store.set
    fake.store.set = async (k, v) => {
        if (k.startsWith('copy|')) throw new Error('QuotaExceededError')
        return real(k, v)
    }
    await rememberPick(KEYS, hit('1'), srt())
    const got = await recallPick(KEYS)
    assert.ok(got && !got.off && got.file === null && got.hit.id === '1')
})

test('two tabs: each change reads the state fresh, the newest write wins', async () => {
    // Another tab wrote between this tab's two calls: nothing is held in memory to overwrite it.
    await rememberOff(['msg:1:1'])
    fake.map.set('state', { v: 1, picks: [{ key: 'msg:1:1', at: 1, off: true }, { key: 'msg:9:9', at: 2, hit: hit('7') }] })
    await rememberOff(['msg:1:2'])
    assert.equal(await pickCount(), 3)
    const other = await recallPick(['msg:9:9'])
    assert.ok(other && !other.off && other.hit.id === '7')
    // Concurrent writes in one tab never lose each other.
    await Promise.all(Array.from({ length: 20 }, (_, n) => rememberOff([`msg:7:${n}`])))
    assert.equal(await pickCount(), 23)
})

// ---- restorePick: the order that spends least ----

const deps = (over: Partial<RestoreDeps> = {}): RestoreDeps & { downloads: string[] } => {
    const downloads: string[] = []
    return {
        stopped: () => false,
        cached: async () => null,
        mayDownload: () => true,
        download: async (h) => (downloads.push(h.id), srt('fresh.srt')),
        ...over,
        downloads,
    }
}

test('opening a video: nothing, Off, the cache first, then the copy, then one download', async () => {
    assert.deepEqual(await restorePick(KEYS, deps()), { kind: 'none' })
    await rememberOff(KEYS)
    assert.deepEqual(await restorePick(KEYS, deps()), { kind: 'off' })
    await rememberPick(KEYS, hit('1'), srt('copy.srt'))
    const cache = deps({ cached: async () => srt('cache.srt') })
    const a = await restorePick(KEYS, cache)
    assert.ok(a.kind === 'ready' && a.file.name === 'cache.srt' && a.fromDevice)
    const d = deps()
    const b = await restorePick(KEYS, d)
    assert.ok(b.kind === 'ready' && b.file.name === 'copy.srt' && b.fromDevice)
    assert.deepEqual(d.downloads, [])
})

test('a copy that is gone downloads again once; the caller keeps it, and blocked it asks again with no error', async () => {
    await rememberPick(KEYS, hit('1'), null)
    const blocked = await restorePick(KEYS, deps({ mayDownload: () => false }))
    assert.equal(blocked.kind, 'missing')
    const failed = await restorePick(KEYS, deps({ download: async () => null }))
    assert.equal(failed.kind, 'missing')
    const throws = await restorePick(KEYS, deps({ download: async () => { throw new Error('offline') } }))
    assert.equal(throws.kind, 'missing')
    const d = deps()
    const ok = await restorePick(KEYS, d)
    assert.ok(ok.kind === 'ready' && !ok.fromDevice && ok.file.name === 'fresh.srt')
    assert.deepEqual(d.downloads, ['1'])
    // Restoring never files anything by itself: a viewer who chose Off meanwhile is not overruled (the caller decides).
    assert.deepEqual(copies(), [])
    await rememberPick(KEYS, ok.hit, ok.file)
    const again = deps()
    const second = await restorePick(KEYS, again)
    assert.ok(second.kind === 'ready' && second.fromDevice)
    assert.deepEqual(again.downloads, [])
})

test('MSone stopped: nothing of it is laid, not from the copy and not from the cache', async () => {
    await rememberPick(KEYS, hit('1'), srt())
    const stopped = deps({ stopped: () => true, cached: async () => srt('cache.srt') })
    assert.deepEqual(await restorePick(KEYS, stopped), { kind: 'none' })
    assert.deepEqual(stopped.downloads, [])
})

// ---- the review's findings ----

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

test('MSone stopping wipes the picks through the small module alone (no player chunk needed)', async () => {
    setKeepStore(mapStore().store)
    await rememberPick(KEYS, hit('1'), srt())
    await forgetKept()
    await sleep(30)
    assert.deepEqual([...fake.map.keys()], [])
})

test('a recency that cannot be saved still gives the pick', async () => {
    await rememberPick(KEYS, hit('1'), srt())
    await rememberOff(['msg:9:9'])
    fake.store.set = async () => {
        throw new Error('QuotaExceededError')
    }
    const got = await recallPick(KEYS)
    assert.ok(got && !got.off && got.hit.id === '1' && got.file)
    assert.deepEqual(await recallPick(['msg:9:9']), { off: true })
})

test('a wipe drops the writes queued or running before it: the next account finds nothing', async () => {
    const slow: KeepStore = {
        ...fake.store,
        set: async (k, v) => {
            await sleep(40)
            fake.map.set(k, v)
        },
    }
    setPickStore(slow)
    const writes = [rememberPick(KEYS, hit('1'), srt()), rememberOff(['msg:2:2']), rememberPick(['msg:3:3'], hit('2'), srt())]
    await sleep(10)
    await wipeAllPicks()
    await Promise.all(writes)
    await sleep(200)
    assert.deepEqual([...fake.map.keys()], [])
    // A write asked for after the wipe is the new account's own.
    await rememberOff(['msg:4:4'])
    assert.equal(await pickCount(), 1)
})

test('a job that timed out stops before its next write', { timeout: 15_000 }, async () => {
    let first = true
    const hangs: KeepStore = {
        ...fake.store,
        get: async (k) => {
            if (first) {
                first = false
                await sleep(2600)
            }
            return fake.map.get(k)
        },
    }
    setPickStore(hangs)
    await rememberOff(KEYS)
    await sleep(2800)
    assert.equal(fake.map.has('state'), false, 'the late job wrote nothing after its time was up')
    // The next change works.
    await rememberOff(KEYS)
    assert.equal(await pickCount(), 2)
})

test('the copies are held to 64 MB, the least recently used going first and their picks staying', async () => {
    const MB4 = 4 * 1024 * 1024
    for (let n = 1; n <= 17; n++) await rememberPick([`msg:1:${n}`], hit(String(n)), srt(`${n}.srt`, MB4))
    assert.equal(MAX_COPY_BYTES, 64 * 1024 * 1024)
    assert.equal(copies().length, 16)
    const oldest = await recallPick(['msg:1:1'])
    assert.ok(oldest && !oldest.off && oldest.hit.id === '1' && oldest.file === null, 'the oldest pick kept its hit, not its copy')
    const newest = await recallPick(['msg:1:17'])
    assert.ok(newest && !newest.off && newest.file?.size === MB4)
})

test('boundCopies counts a shared copy once and drops the longest unused', () => {
    const e = (key: string, at: number, copy: string | null, size: number) => ({ key, at, hit: hit(copy ?? 'x'), copy, size })
    const list = [e('a', 1, 'c1', 10), e('b', 2, 'c2', 10), e('c', 3, 'c1', 10)]
    assert.equal(boundCopies(list, 20), list, 'two distinct copies of 10 fit in 20')
    const out = boundCopies(list, 10)
    // c2 was last used at 2, c1 at 3 (through key c): c2 goes.
    assert.deepEqual(out.map((x) => x.copy), ['c1', null, 'c1'])
})
