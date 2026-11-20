import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createSlots } from './fetchSlots.ts'
import { createThumbLoader } from './thumbJobs.ts'

/** A loader whose downloads the test finishes by hand. */
function rig(limit = 1) {
    const slots = createSlots(limit)
    const downloads: { key: string; finish: () => void }[] = []
    const cacheHits = new Set<string>()
    let urls = 0
    const loader = createThumbLoader({
        slots,
        run: async (key, slot) => {
            if (cacheHits.has(key)) return `blob:cache-${key}`
            const release = await slot()
            try {
                await new Promise<void>((finish) => downloads.push({ key, finish }))
            } finally {
                release()
            }
            return `blob:${key}-${++urls}`
        },
    })
    return { slots, loader, downloads, cacheHits }
}
const tick = () => new Promise((r) => setTimeout(r, 0))

test('cards that want one picture share one download', async () => {
    const { loader, downloads } = rig(4)
    const a = new AbortController()
    const b = new AbortController()
    const pa = loader.load('x', a.signal)
    const pb = loader.load('x', b.signal)
    await tick()
    assert.equal(downloads.length, 1)
    downloads[0].finish()
    assert.equal(await pa, await pb)
})

test('a card that leaves takes only its own ask back while another still wants it', async () => {
    const { loader, downloads } = rig(1)
    const hold = loader.load('busy') // holds the one slot
    await tick()
    const a = new AbortController()
    const b = new AbortController()
    const pa = loader.load('x', a.signal)
    const pb = loader.load('x', b.signal)
    a.abort()
    await assert.rejects(pa, { name: 'AbortError' })
    downloads[0].finish()
    await hold
    await tick()
    assert.equal(downloads.length, 2, 'x still downloads for the card that stayed')
    downloads[1].finish()
    assert.match(await pb, /^blob:x/)
})

test('cancel with the last card: a queued picture is never downloaded and the next ask starts afresh', async () => {
    const { loader, downloads, slots } = rig(1)
    const hold = loader.load('busy')
    await tick()
    const a = new AbortController()
    const b = new AbortController()
    const pa = loader.load('x', a.signal)
    const pb = loader.load('x', b.signal)
    a.abort()
    b.abort()
    await assert.rejects(pa, { name: 'AbortError' })
    await assert.rejects(pb, { name: 'AbortError' })
    assert.equal(loader.jobs(), 1, 'only the busy one is left')
    assert.deepEqual(slots.state, { active: 1, waiting: 0 })
    downloads[0].finish()
    await hold
    await tick()
    assert.equal(downloads.length, 1, 'x was never downloaded')
    const again = loader.load('x')
    await tick()
    assert.equal(downloads.length, 2)
    downloads[1].finish()
    assert.match(await again, /^blob:x/)
})

test('a download already with Telegram is not withdrawn: the next card joins it instead of starting a second', async () => {
    const { loader, downloads, slots } = rig(2)
    const a = new AbortController()
    const pa = loader.load('x', a.signal)
    await tick()
    assert.equal(downloads.length, 1)
    a.abort()
    await assert.rejects(pa, { name: 'AbortError' })
    assert.deepEqual(slots.state, { active: 1, waiting: 0 }, 'the slot is held until it ends')
    const b = new AbortController()
    const pb = loader.load('x', b.signal)
    await tick()
    assert.equal(downloads.length, 1, 'one download, shared')
    downloads[0].finish()
    assert.match(await pb, /^blob:x-1$/)
    assert.deepEqual(slots.state, { active: 0, waiting: 0 })
})

test('an ask withdrawn in the moment its slot comes releases the slot and downloads nothing', async () => {
    const { loader, downloads, slots } = rig(1)
    const hold = loader.load('busy')
    await tick()
    const a = new AbortController()
    const pa = loader.load('x', a.signal)
    // The slot is handed on and the card leaves in the same breath.
    downloads[0].finish()
    a.abort()
    await hold
    await assert.rejects(pa, { name: 'AbortError' })
    await tick()
    assert.equal(downloads.length, 1)
    assert.deepEqual(slots.state, { active: 0, waiting: 0 })
})

test('a picture on this device needs no slot', async () => {
    const { loader, downloads, cacheHits, slots } = rig(1)
    const hold = loader.load('busy')
    await tick()
    cacheHits.add('c')
    assert.equal(await loader.load('c'), 'blob:cache-c')
    assert.deepEqual(slots.state, { active: 1, waiting: 0 })
    downloads[0].finish()
    await hold
})
