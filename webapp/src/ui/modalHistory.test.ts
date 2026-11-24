import assert from 'node:assert/strict'
import { test } from 'node:test'
import { exempt, FLAG, LEAVING_MS, leavingPage, ModalHistory, type DialogHost } from './modalHistory.ts'

/** A session history the way a browser keeps it: entries, an index, and traversals that end in popstate. */
function browser() {
    const entries: { state: unknown; path: string }[] = [{ state: { _depth: 0 }, path: '/a' }]
    let i = 0
    const pops: (() => void)[] = []
    const real = {
        pushState(state: unknown, _t: string, url?: string | null) {
            entries.splice(i + 1)
            entries.push({ state, path: url ?? entries[i].path })
            i++
        },
        replaceState(state: unknown, _t: string, url?: string | null) {
            entries[i] = { state, path: url ?? entries[i].path }
        },
        back() {
            pops.push(() => (i = Math.max(0, i - 1)))
        },
        forward() {
            pops.push(() => (i = Math.min(entries.length - 1, i + 1)))
        },
    }
    return { entries, real, now: () => ({ state: entries[i].state, path: entries[i].path }), index: () => i, pops, pending: () => pops.length }
}

function dialog(): DialogHost & { events: (() => void)[]; shut(): void } {
    const events: (() => void)[] = []
    const d = {
        open: true,
        events,
        close() {
            if (!d.open) return
            d.open = false
            queueMicrotask(() => events.forEach((f) => f()))
        },
        shut() {
            d.close()
        },
        addEventListener(_t: 'close', fn: () => void) {
            events.push(fn)
        },
        contains: () => false,
    }
    return d
}

const focusHost = () => {
    const log: unknown[] = []
    let active: { isConnected: boolean } | null = null
    return { log, set: (a: typeof active) => (active = a), host: { active: () => active, isBody: (el: unknown) => el === 'body', focus: (el: unknown) => log.push(el) } }
}

/** Runs the traversals the fake browser queued, each followed by the page's popstate handler as the window would. */
function traverse(b: ReturnType<typeof browser>, mh: ModalHistory) {
    const swallowed: boolean[] = []
    while (b.pops.length) {
        b.pops.shift()!()
        swallowed.push(mh.popstate())
    }
    return swallowed
}

const tick = () => new Promise((r) => setTimeout(r, 0))

function setup() {
    const b = browser()
    const f = focusHost()
    const mh = new ModalHistory(b.real, b.now, f.host)
    return { b, mh, f }
}

test('opening pushes one entry that carries the flag', () => {
    const { b, mh } = setup()
    mh.opened(dialog(), null)
    assert.equal(b.entries.length, 2)
    assert.equal(typeof (b.entries[1].state as Record<string, unknown>)[FLAG], 'number')
    assert.equal((b.entries[1].state as Record<string, unknown>)._depth, 0)
})

test('Back closes the dialog and the page stays', () => {
    const { b, mh } = setup()
    const d = dialog()
    mh.opened(d, null)
    b.real.back()
    const swallowed = traverse(b, mh)
    assert.equal(d.open, false)
    assert.deepEqual(swallowed, [true])
    assert.equal(b.index(), 0)
})

test('a button close takes the entry off again, once', async () => {
    const { b, mh } = setup()
    const d = dialog()
    mh.opened(d, null)
    d.shut()
    await tick()
    assert.equal(b.pending(), 1)
    assert.deepEqual(traverse(b, mh), [true])
    assert.equal(b.index(), 0)
    assert.equal(mh.open, 0)
})

test('Back closing a dialog does not back out a second time from the close event', async () => {
    const { b, mh } = setup()
    const d = dialog()
    mh.opened(d, null)
    b.real.back()
    traverse(b, mh)
    await tick()
    assert.equal(b.pending(), 0)
    assert.equal(b.index(), 0)
})

test('two stacked dialogs each own an entry and Back closes the top one only', async () => {
    const { b, mh } = setup()
    const one = dialog()
    const two = dialog()
    mh.opened(one, null)
    mh.opened(two, null)
    assert.equal(b.entries.length, 3)
    b.real.back()
    traverse(b, mh)
    await tick()
    assert.equal(two.open, false)
    assert.equal(one.open, true)
    b.real.back()
    traverse(b, mh)
    await tick()
    assert.equal(one.open, false)
    assert.equal(b.index(), 0)
    assert.equal(b.pending(), 0)
})

test('a route change from a dialog replaces its entry: no ghost, the dialog closes', async () => {
    const { b, mh } = setup()
    const d = dialog()
    mh.opened(d, null)
    const w = mh.write('push', { _depth: 1 }, true)
    assert.equal(w.kind, 'replace')
    b.real.replaceState(w.state, '', '/b')
    mh.routeChanged()
    await tick()
    assert.equal(d.open, false)
    assert.equal(b.entries.length, 2)
    assert.equal(b.pending(), 0)
    assert.equal((b.entries[1].state as Record<string, unknown>)[FLAG], undefined)
})

test('a replace on a modal entry keeps its flag while the route stays', () => {
    const { b, mh } = setup()
    mh.opened(dialog(), null)
    const id = (b.entries[1].state as Record<string, unknown>)[FLAG]
    const w = mh.write('replace', { _depth: 0 }, false)
    assert.equal((w.state as Record<string, unknown>)[FLAG], id)
})

test('a push with no dialog is left alone', () => {
    const { mh } = setup()
    assert.deepEqual(mh.write('push', { x: 1 }, true), { kind: 'push', state: { x: 1 } })
})

test('a reload with a modal open steps over the left entry once', () => {
    const b = browser()
    b.real.pushState({ [FLAG]: 4 }, '')
    const mh = new ModalHistory(b.real, b.now, focusHost().host)
    mh.boot()
    assert.equal(b.pending(), 1)
    assert.deepEqual(traverse(b, mh), [true])
    assert.equal(b.index(), 0)
})

test('a start with no flag does nothing', () => {
    const { b, mh } = setup()
    mh.boot()
    assert.equal(b.pending(), 0)
})

test('a close that is held (work running) puts its entry back', () => {
    const { b, mh } = setup()
    const d = dialog()
    d.close = () => {}
    mh.opened(d, null)
    b.real.back()
    b.pops.shift()!()
    mh.popstate()
    assert.equal(d.open, true)
    assert.equal(b.entries.length, 2)
    assert.equal(b.index(), 1)
})

test('an entry no open dialog owns is stepped over, in the direction the person was going', () => {
    const { b, mh } = setup()
    // [a, ghost, b]: a dialog closed while a page was already on top of it.
    b.real.pushState({ _depth: 0, [FLAG]: 9 }, '', '/a')
    b.real.pushState({ _depth: 1 }, '', '/b')
    mh.sync()
    b.real.back()
    b.pops.shift()!()
    // landed on the ghost going back: it is swallowed and a second back follows
    assert.equal(mh.popstate(), true)
    assert.equal(b.pending(), 1)
    b.pops.shift()!()
    assert.equal(b.index(), 0)
})

test('forward onto the ghost goes on forward', () => {
    const { b, mh } = setup()
    b.real.pushState({ _depth: 0, [FLAG]: 9 }, '', '/a')
    b.real.pushState({ _depth: 1 }, '', '/b')
    mh.sync()
    b.real.back()
    b.pops.shift()!()
    mh.sync()
    b.real.back()
    b.pops.shift()!()
    assert.equal(b.index(), 0)
    b.real.forward()
    b.pops.shift()!()
    assert.equal(mh.popstate(), true)
    assert.equal(b.pending(), 1)
    b.pops.shift()!()
    assert.equal(b.index(), 2)
})

test('focus goes back to the opener when the browser left it on the body', async () => {
    const { mh, f } = setup()
    const opener = { isConnected: true }
    const d = dialog()
    mh.opened(d, opener)
    f.set(null)
    d.shut()
    await tick()
    assert.deepEqual(f.log, [opener])
})

test('focus is left alone when the page put it somewhere on purpose, or the opener is gone', async () => {
    const { mh, f } = setup()
    const d = dialog()
    mh.opened(d, { isConnected: true })
    f.set({ isConnected: true })
    d.shut()
    await tick()
    assert.deepEqual(f.log, [])
    const e = dialog()
    mh.opened(e, { isConnected: false })
    f.set(null)
    e.shut()
    await tick()
    assert.deepEqual(f.log, [])
})

test('opening the same dialog twice owns one entry', () => {
    const { b, mh } = setup()
    const d = dialog()
    mh.opened(d, null)
    mh.opened(d, null)
    assert.equal(b.entries.length, 2)
})

test('a push that throws leaves the dialog working without an entry', () => {
    const b = browser()
    const real = {
        ...b.real,
        pushState() {
            throw new Error('SecurityError')
        },
    }
    const mh = new ModalHistory(real, b.now, focusHost().host)
    const d = dialog()
    mh.opened(d, null)
    d.shut()
    assert.equal(b.pending(), 0)
})

test('the profile picker and its cover take no entry', () => {
    const el = (sel: string) => ({ matches: (s: string) => s.split(', ').includes(sel), closest: () => null })
    assert.equal(exempt(el('.profiles')), true)
    assert.equal(exempt(el('.lock-cover')), true)
    assert.equal(exempt(el('[data-while-locked]')), true)
    assert.equal(exempt(el('.card-menu')), false)
})

test('a dialog opened as another closes takes its entry over: one entry, one Back', async () => {
    const { b, mh } = setup()
    const menu = dialog()
    mh.opened(menu, null)
    menu.shut()
    const picker = dialog()
    mh.opened(picker, null)
    await tick()
    assert.equal(b.entries.length, 2)
    assert.equal(b.pending(), 0)
    picker.shut()
    await tick()
    traverse(b, mh)
    assert.equal(b.index(), 0)
})

test('two dialogs open at once, the lower one closing first: Back from the top steps over the entry it left', async () => {
    const { b, mh } = setup()
    const menu = dialog()
    const picker = dialog()
    mh.opened(menu, null)
    mh.opened(picker, null)
    menu.shut()
    await tick()
    picker.shut()
    await tick()
    traverse(b, mh)
    assert.equal(b.index(), 0)
    assert.equal(mh.open, 0)
})

test('a dialog closing while the page is leaving takes no step back over that navigation', async () => {
    const { b, mh } = setup()
    const d = dialog()
    mh.opened(d, null)
    mh.setLeaving(true)
    d.shut()
    await tick()
    assert.equal(b.pending(), 0)
    // The unload did not come: the next close steps back as before.
    mh.setLeaving(false)
    const e = dialog()
    mh.opened(e, null)
    e.shut()
    await tick()
    assert.equal(b.pending(), 1)
})

test('a question closed after leavingPage() takes no step back, where beforeunload comes too late (Safari)', async () => {
    const { b, mh } = setup()
    const d = dialog()
    mh.opened(d, null)
    // Sign out's work ends with location.replace; its question closes after, with no beforeunload yet.
    leavingPage()
    d.shut()
    await tick()
    assert.equal(b.pending(), 0)
    // A page change that never came: a later close steps back as before.
    leavingPage(Date.now() - LEAVING_MS - 1)
    const e = dialog()
    mh.opened(e, null)
    e.shut()
    await tick()
    assert.equal(b.pending(), 1)
})
