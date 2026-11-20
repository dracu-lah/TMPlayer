import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
    ADD_NEEDS_BYTES,
    ADDING_STALE_MS,
    ADOPTED,
    addProfile,
    addingFromFor,
    attach,
    backTarget,
    bootPlan,
    checkPin,
    checkPinIn,
    acceptUnlock,
    profileNumber,
    streamReadAllowed,
    readUnlock,
    writeUnlock,
    cleanName,
    cleanPhoto,
    defaultBook,
    find,
    isCurrent,
    isDeviceKey,
    lockLeft,
    makePin,
    markAttention,
    MAX_PROFILES,
    mayOpen,
    namesOf,
    needsPinFor,
    newFolder,
    ownsKey,
    PIN_LOCK_MS,
    pickerAtLaunch,
    pinsForRemoveAll,
    readBook,
    readPending,
    RELOCK_HIDDEN_MS,
    relockDue,
    removeProfile,
    rename,
    sameBytes,
    scopedKey,
    setPin,
    staleAdding,
    storageOfWorker,
    viewKey,
    writeBook,
    type Book,
} from './accountBook.ts'

const crypto = globalThis.crypto
let seed = 1
const random = () => ((seed = (seed * 16807) % 2147483647) / 2147483647)

function added(book: Book, now = 1000): Book {
    const r = addProfile(book, newFolder(book, random), now, null)
    assert.ok('book' in r)
    return r.book
}

test('a browser with no book adopts its login in place: the old storage names, no picker', () => {
    const book = readBook(null)
    assert.deepEqual(book.profiles.map((p) => p.id), [ADOPTED])
    assert.equal(book.active, ADOPTED)
    assert.equal(pickerAtLaunch(book), false)
    const n = namesOf(ADOPTED)
    assert.equal(n.mtcute, 'tmplayer')
    assert.equal(n.lock, 'tmplayer-telegram')
    assert.equal(n.worker, 'telegram')
    assert.equal(n.keyPrefix, '')
    assert.equal(n.lastPosition, 'tmplayer-last-position')
    assert.equal(n.syncShared, 'tmplayer-sync-state')
    assert.equal(n.subpicks, 'tmplayer-subpicks')
    assert.equal(namesOf(ADOPTED, true).mtcute, 'tmplayer-test')
    // Every key keeps its name for the adopted profile.
    for (const key of ['settings', 'resume:1:2', 'watched:1:2', 'addedMode', 'lists', 'speed:x', 'sync:state'])
        assert.equal(scopedKey(n.keyPrefix, key), key)
})

test('another profile gets storage of its own, named after its folder', () => {
    const book = added(defaultBook())
    const p = book.profiles[1]
    assert.match(p.id, /^[a-z0-9]{6}$/)
    assert.equal(p.state, 'adding')
    const n = namesOf(p.id)
    assert.equal(n.mtcute, `tmplayer-${p.id}`)
    assert.equal(n.worker, `telegram:${p.id}`)
    assert.equal(storageOfWorker(n.worker), `tmplayer-${p.id}`)
    assert.equal(storageOfWorker('telegram'), 'tmplayer')
    assert.equal(storageOfWorker('telegram:../x'), 'tmplayer')
    assert.equal(n.lock, `tmplayer-telegram:${p.id}`)
    assert.equal(scopedKey(n.keyPrefix, 'resume:1:2'), `@${p.id}:resume:1:2`)
    // The device's keys are shared.
    assert.equal(scopedKey(n.keyPrefix, 'settings'), 'settings')
    assert.equal(scopedKey(n.keyPrefix, 'player:boost'), 'player:boost')
    assert.equal(pickerAtLaunch(book), true)
})

test('each profile sees its own keys and the device keys only', () => {
    const a = '@abc123:'
    const b = '@xyz789:'
    assert.equal(viewKey('', 'resume:1:2'), 'resume:1:2')
    assert.equal(viewKey('', '@abc123:resume:1:2'), null)
    assert.equal(viewKey(a, '@abc123:resume:1:2'), 'resume:1:2')
    assert.equal(viewKey(a, '@xyz789:resume:1:2'), null)
    assert.equal(viewKey(a, 'resume:1:2'), null)
    assert.equal(viewKey(a, 'settings'), 'settings')
    assert.equal(viewKey(b, 'speed:Show'), 'speed:Show')
    assert.ok(ownsKey('', 'watched:1:2'))
    assert.ok(!ownsKey('', 'settings'))
    assert.ok(!ownsKey('', '@abc123:watched:1:2'))
    assert.ok(ownsKey(a, '@abc123:watched:1:2'))
    assert.ok(!ownsKey(a, 'watched:1:2'))
    assert.ok(!ownsKey(a, 'settings'))
    // An unclassed key stays the profile's own.
    assert.ok(!isDeviceKey('something-new'))
    for (const key of ['settings', 'copyhabit', 'speed:a', 'player:autoplay', 'secret:submaker']) assert.ok(isDeviceKey(key), key)
    for (const key of ['resume:1:2', 'watched:1:2', 'later:1:2', 'star:1', 'view:1', 'added:1', 'addedMode', 'home-chats', 'fix:1:2', 'fixat:1:2', 'title:1:2', 'titleat:1:2', 'apart:1:2', 'chose:1:2', 'lists', 'xw:1_2', 'xl:1_2', 'xfix:1_2', 'xtitle:1_2', 'sync:state', 'sync:status', 'sync:card', 'b:1:2'])
        assert.ok(!isDeviceKey(key), key)
})

test('three profiles at most: a fourth is refused', () => {
    let book = defaultBook()
    for (let i = 1; i < MAX_PROFILES; i++) book = added(book)
    assert.equal(book.profiles.length, 3)
    assert.deepEqual(addProfile(book, 'zzzzzz', 1, null), { full: true })
})

test('a profile is not made without room for it', () => {
    assert.deepEqual(addProfile(defaultBook(), 'aaaaaa', 1, 1024), { noSpace: { needed: ADD_NEEDS_BYTES, free: 1024 } })
    assert.ok('book' in addProfile(defaultBook(), 'aaaaaa', 1, ADD_NEEDS_BYTES))
    // A browser that cannot say how much is free is not held back.
    assert.ok('book' in addProfile(defaultBook(), 'aaaaaa', 1, null))
})

test('removing a profile keeps the others; the last one stays, emptied', () => {
    let book = added(added(defaultBook()))
    const [, second, third] = book.profiles
    book = { ...book, active: second.id }
    book = removeProfile(book, second.id)
    assert.deepEqual(book.profiles.map((p) => p.id), [ADOPTED, third.id])
    assert.equal(book.active, ADOPTED)
    book = removeProfile(book, ADOPTED)
    assert.deepEqual(book.profiles.map((p) => p.id), [third.id])
    const named = attach(book, third.id, { id: 9, name: 'Sam' })
    assert.ok('book' in named)
    const last = removeProfile(named.book, third.id)
    assert.equal(last.profiles.length, 1)
    assert.equal(last.profiles[0].id, third.id)
    assert.equal(last.profiles[0].userId, null)
    assert.equal(last.profiles[0].name, '')
    assert.equal(removeProfile(last, 'nobody'), last)
})

test('the same Telegram account on two profiles is refused', () => {
    let book = defaultBook()
    const one = attach(book, ADOPTED, { id: 1, name: 'Dana' })
    assert.ok('book' in one)
    book = added(one.book)
    const second = book.profiles[1].id
    const twice = attach(book, second, { id: 1, name: 'Dana' })
    assert.ok('same' in twice)
    assert.equal(twice.same.id, ADOPTED)
    const other = attach(book, second, { id: 2, name: 'Robin' })
    assert.ok('book' in other)
    assert.equal(find(other.book, second)?.state, 'ok')
    assert.equal(find(other.book, second)?.name, 'Robin')
    // The same account signing in again on its own profile is fine.
    assert.ok('book' in attach(other.book, ADOPTED, { id: 1, name: 'Dana' }))
    // An answer for a profile that went meanwhile lands nowhere.
    assert.deepEqual(attach(other.book, 'gone00', { id: 3, name: 'X' }), { stale: true })
})

test('a stored book that does not hold up is the adopted profile alone', () => {
    const GARBAGE = ['', 'x', '{', 'null', '1', '[]', '"s"', 'true', '{}', '{"profiles":1}', '{"profiles":[]}', '{"profiles":[null,1,"a",{}]}', `{"profiles":[{"id":"../../x"}]}`, 'x'.repeat(100_000)]
    for (const g of GARBAGE) assert.deepEqual(readBook(g), defaultBook(), g.slice(0, 40))
    assert.deepEqual(readBook(undefined), defaultBook())
})

test('one bad profile in the book is left out, the rest stand', () => {
    const good = added(added(defaultBook()))
    const raw = JSON.parse(writeBook(good))
    raw.profiles.splice(1, 0, { id: 'BAD!', name: 1 }, { id: good.profiles[1].id }, { id: 'qqqqqq', userId: -5 }, { id: 'pppppp', pin: { salt: 1 } })
    raw.active = 'nope'
    const back = readBook(JSON.stringify(raw))
    assert.deepEqual(back.profiles.map((p) => p.id), good.profiles.map((p) => p.id))
    assert.equal(back.active, ADOPTED)
    // More than three stored: the first three.
    raw.profiles = [...good.profiles, { id: 'rrrrrr' }, { id: 'ssssss' }]
    assert.equal(readBook(JSON.stringify(raw)).profiles.length, 3)
})

test('the book survives a write and a read', () => {
    let book = added(defaultBook())
    const r = attach(book, book.profiles[1].id, { id: 5, name: 'Robin\u0000 Test' })
    assert.ok('book' in r)
    book = rename(r.book, ADOPTED, '  Living   room  ')
    assert.equal(find(book, ADOPTED)?.name, 'Living room')
    assert.deepEqual(readBook(writeBook(book)), book)
    assert.equal(find(book, book.profiles[1].id)?.name, 'Robin Test')
    // An empty name changes nothing.
    assert.equal(rename(book, ADOPTED, '   '), book)
    assert.equal(cleanName('x'.repeat(200)).length, 40)
})

test('a profile left part way through adding is cleaned once stale, never this tab own', () => {
    const book = added(defaultBook(), 0)
    const adding = book.profiles[1].id
    assert.deepEqual(staleAdding(book, ADDING_STALE_MS - 1, null), [])
    assert.deepEqual(staleAdding(book, ADDING_STALE_MS + 1, null).map((p) => p.id), [adding])
    assert.deepEqual(staleAdding(book, ADDING_STALE_MS + 1, adding), [])
    // The only profile is never cleaned away.
    assert.deepEqual(staleAdding({ profiles: [{ ...book.profiles[1] }], active: adding }, ADDING_STALE_MS * 9, null), [])
})

test('a write from an older generation, or another profile, is dropped', () => {
    const book = added(defaultBook())
    const second = book.profiles[1].id
    const now = { id: ADOPTED, gen: 0 }
    assert.ok(isCurrent({ id: ADOPTED, gen: 0 }, now, book))
    // The page began a switch: what was tagged before lands nowhere.
    assert.ok(!isCurrent({ id: ADOPTED, gen: 0 }, { id: ADOPTED, gen: 1 }, book))
    assert.ok(!isCurrent({ id: second, gen: 0 }, now, book))
    // The profile was removed in another tab.
    assert.ok(!isCurrent({ id: second, gen: 0 }, { id: second, gen: 0 }, removeProfile(book, second)))
})

test('a profile marked for attention says so and keeps its data', () => {
    const r = attach(defaultBook(), ADOPTED, { id: 1, name: 'Dana' })
    assert.ok('book' in r)
    const book = markAttention(r.book, ADOPTED)
    assert.equal(find(book, ADOPTED)?.state, 'attention')
    assert.equal(find(book, ADOPTED)?.userId, 1)
    assert.equal(markAttention(book, 'nobody'), book)
    // Signed in again: the mark goes.
    const again = attach(book, ADOPTED, { id: 1, name: 'Dana' })
    assert.ok('book' in again && find(again.book, ADOPTED)?.state === 'ok')
})

test('a PIN opens its profile; five wrong lock it for 30 s, across a reload', async () => {
    const pin = await makePin('4821', crypto, 2000)
    assert.notEqual(pin.hash, '4821')
    let book = setPin(defaultBook(), ADOPTED, pin)
    assert.equal(pickerAtLaunch(book), true)
    const right = await checkPin(book, ADOPTED, '4821', 0, crypto)
    assert.ok('ok' in right && right.ok)
    let now = 1000
    for (let i = 1; i <= 4; i++) {
        const wrong = await checkPin(book, ADOPTED, '0000', now, crypto)
        assert.ok('ok' in wrong && !wrong.ok)
        assert.equal(wrong.left, 5 - i)
        // Kept in the book: a reload goes on counting.
        book = readBook(writeBook(wrong.book))
    }
    const fifth = await checkPin(book, ADOPTED, '0000', now, crypto)
    assert.ok('locked' in fifth)
    assert.equal(fifth.locked, now + PIN_LOCK_MS)
    book = readBook(writeBook(fifth.book))
    // Locked: even the right PIN is not checked.
    now += 10_000
    const held = await checkPin(book, ADOPTED, '4821', now, crypto)
    assert.ok('locked' in held)
    now += PIN_LOCK_MS
    const after = await checkPin(book, ADOPTED, '4821', now, crypto)
    assert.ok('ok' in after && after.ok)
    assert.equal(find(after.book, ADOPTED)?.fails, 0)
})

test('a lock stamped far ahead (the clock moved back) runs from now, never longer', () => {
    const p = { ...defaultBook().profiles[0], lockedUntil: 10_000_000 }
    assert.equal(lockLeft(p, 0), PIN_LOCK_MS)
    assert.equal(lockLeft({ ...p, lockedUntil: 5 }, 10), 0)
})

test('a PIN that is not four digits never opens, and a broken stored PIN refuses', async () => {
    const book = setPin(defaultBook(), ADOPTED, await makePin('1234', crypto, 2000))
    for (const bad of ['', '123', '12345', 'abcd', '١٢٣٤']) {
        const r = await checkPin(book, ADOPTED, bad, 0, crypto)
        assert.ok('ok' in r && !r.ok, bad)
    }
    const broken = setPin(defaultBook(), ADOPTED, { salt: '!!!!!!!!!!', hash: '################', rounds: 2000 })
    const r = await checkPin(broken, ADOPTED, '1234', 0, crypto)
    assert.ok('ok' in r && !r.ok)
    await assert.rejects(makePin('12', crypto))
})

test('bytes are compared whole', () => {
    assert.ok(sameBytes(new Uint8Array([1, 2, 3]), new Uint8Array([1, 2, 3])))
    assert.ok(!sameBytes(new Uint8Array([1, 2, 3]), new Uint8Array([1, 2, 4])))
    assert.ok(!sameBytes(new Uint8Array([1, 2]), new Uint8Array([1, 2, 0])))
})

test('a cached photo is a small picture address or nothing', () => {
    assert.equal(cleanPhoto('data:image/jpeg;base64,AAAA'), 'data:image/jpeg;base64,AAAA')
    for (const bad of [null, '', 'javascript:alert(1)', 'data:text/html;base64,AAAA', 'data:image/jpeg;base64,<script>', `data:image/jpeg;base64,${'A'.repeat(100_000)}`]) assert.equal(cleanPhoto(bad), null)
})

// ---- Unlocking (security review: a PIN profile never opens, nor is signed out, without its PIN) ----

async function lockedPair(): Promise<{ book: Book; locked: string }> {
    const two = added(defaultBook())
    const locked = two.profiles[1].id
    const r = attach(two, locked, { id: 2, name: 'Robin' })
    assert.ok('book' in r)
    return { book: setPin(r.book, locked, await makePin('4821', crypto, 2000)), locked }
}

test('a PIN profile the tab has not unlocked starts on the picker, even when the tab chose it', async () => {
    const { book, locked } = await lockedPair()
    assert.ok(!mayOpen(book, locked, null))
    assert.ok(mayOpen(book, locked, locked))
    assert.ok(mayOpen(book, ADOPTED, null))
    assert.deepEqual(bootPlan(book, locked, null, null), { id: locked, picker: 'launch', pending: null })
    // Unlocked in this tab: it opens straight away.
    assert.deepEqual(bootPlan(book, locked, locked, null), { id: locked, picker: null, pending: null })
    // An unlock of another profile opens nothing.
    assert.equal(bootPlan(book, locked, ADOPTED, null).picker, 'launch')
    // A new tab asks; a tab that chose the profile without a PIN does not.
    assert.equal(bootPlan(book, null, null, null).picker, 'launch')
    assert.equal(bootPlan(book, ADOPTED, null, null).picker, null)
    // The profile last open in another tab, with a PIN, is never opened by a tab that has not chosen.
    assert.equal(bootPlan({ ...book, active: locked }, null, null, null).picker, 'launch')
})

test('a sign out pending in a profile runs there with no picker and never counts as chosen', async () => {
    const { book, locked } = await lockedPair()
    const pending = readPending(JSON.stringify({ kind: 'signout', id: locked }), book)
    assert.deepEqual(pending, { kind: 'signout', id: locked })
    assert.deepEqual(bootPlan(book, ADOPTED, null, pending), { id: locked, picker: null, pending })
    // Garbage, or a profile gone, is no pending work: the tab boots as it chose.
    for (const bad of [null, '', '{', '{"kind":"signout"}', '{"kind":"x","id":"main"}', JSON.stringify({ kind: 'signout', id: 'gone00' }), 'x'.repeat(500)])
        assert.equal(readPending(bad, book), null, String(bad))
    assert.deepEqual(bootPlan(book, ADOPTED, null, null), { id: ADOPTED, picker: null, pending: null })
})

test('signing out or removing a PIN profile asks its PIN, unless it is open and unlocked here', async () => {
    const { book, locked } = await lockedPair()
    const none = new Set<string>()
    assert.ok(needsPinFor(book, locked, ADOPTED, null, none))
    // Unlocked for another profile, or open but not unlocked: still asks.
    assert.ok(needsPinFor(book, locked, ADOPTED, ADOPTED, none))
    assert.ok(needsPinFor(book, locked, locked, null, none))
    assert.ok(!needsPinFor(book, locked, locked, locked, none))
    assert.ok(!needsPinFor(book, locked, ADOPTED, null, new Set([locked])))
    // No PIN, nothing to ask.
    assert.ok(!needsPinFor(book, ADOPTED, locked, null, none))
})

test('Remove all asks every locked profile PIN but the one open and unlocked here', async () => {
    const { book, locked } = await lockedPair()
    const both = setPin(book, ADOPTED, await makePin('1111', crypto, 2000))
    assert.deepEqual(pinsForRemoveAll(both, ADOPTED, null).map((p) => p.id), [ADOPTED, locked])
    assert.deepEqual(pinsForRemoveAll(both, ADOPTED, ADOPTED).map((p) => p.id), [locked])
    assert.deepEqual(pinsForRemoveAll(defaultBook(), ADOPTED, null), [])
})

test('Cancel on a new account goes back only to a profile that needs no PIN again', async () => {
    const { book, locked } = await lockedPair()
    assert.equal(backTarget(book, ADOPTED), ADOPTED)
    assert.equal(backTarget(book, locked), null)
    assert.equal(backTarget(book, null), null)
    assert.equal(backTarget(book, 'gone00'), null)
    // Add account remembers the profile only when the tab had chosen and unlocked it.
    assert.equal(addingFromFor(book, ADOPTED, true, null), ADOPTED)
    assert.equal(addingFromFor(book, ADOPTED, false, null), null)
    assert.equal(addingFromFor(book, locked, true, null), null)
    assert.equal(addingFromFor(book, locked, true, locked), locked)
})

test('wrong PINs tried at the same time in two tabs both count, and five lock', async () => {
    const { book, locked } = await lockedPair()
    // One book every tab reads and writes (localStorage), stored as text.
    let stored = writeBook(book)
    const read = () => readBook(stored)
    const write = (b: Book) => void (stored = writeBook(b))
    const two = await Promise.all([checkPinIn(read, write, locked, '0000', 1000, crypto), checkPinIn(read, write, locked, '0001', 1000, crypto)])
    assert.equal(find(read(), locked)?.fails, 2)
    assert.deepEqual(two.map((a) => ('left' in a ? a.left : -1)).sort(), [3, 4])
    const three = await Promise.all([1, 2, 3].map((n) => checkPinIn(read, write, locked, `000${n + 1}`, 1000, crypto)))
    assert.ok(three.some((a) => 'locked' in a))
    assert.ok(find(read(), locked)!.lockedUntil > 1000)
    // While locked, even the right one does nothing.
    const held = await checkPinIn(read, write, locked, '4821', 2000, crypto)
    assert.ok('locked' in held)
})

test('a PIN changed in another tab while one was checked opens nothing', async () => {
    const { book, locked } = await lockedPair()
    let stored = writeBook(book)
    const read = () => readBook(stored)
    let first = true
    const write = (b: Book) => void (stored = writeBook(b))
    const changed = setPin(book, locked, await makePin('9999', crypto, 2000))
    const reading = () => {
        // The second read (after deriving) sees the new PIN.
        if (first) return ((first = false), read())
        stored = writeBook(changed)
        return read()
    }
    const answer = await checkPinIn(reading, write, locked, '4821', 0, crypto)
    assert.ok('ok' in answer && !answer.ok)
})

test('an unlock runs out: a copy restored later, or one stamped ahead, opens nothing', () => {
    const at = 5_000_000
    const text = writeUnlock('abc123', at)
    assert.equal(readUnlock(text, at), 'abc123')
    assert.equal(readUnlock(text, at + RELOCK_HIDDEN_MS), 'abc123')
    assert.equal(readUnlock(text, at + RELOCK_HIDDEN_MS + 1), null)
    assert.equal(readUnlock(text, at - 1), null)
    // An unlock without a time (an older build's, a hand edit) is none.
    for (const bad of [null, '', 'abc123', '{"id":"abc123"}', '{"id":"../x","at":5000000}', '{"id":"abc123","at":"5"}', 'x'.repeat(300)]) assert.equal(readUnlock(bad, at), null, String(bad))
})

test('only a reload of the tab, or a page it started itself, takes its unlock over', () => {
    const at = 9_000_000
    const text = writeUnlock('abc123', at, 'tok0123456789')
    assert.deepEqual(acceptUnlock(text, at + 1000, 'reload', null), { id: 'abc123', token: 'tok0123456789' })
    assert.deepEqual(acceptUnlock(text, at + 1000, 'navigate', 'tok0123456789'), { id: 'abc123', token: 'tok0123456789' })
    // A duplicated tab or a restored session: a copy of sessionStorage, but no reload of this tab and no handoff.
    for (const nav of ['navigate', 'back_forward', 'prerender', null]) assert.equal(acceptUnlock(text, at + 1000, nav, null), null, String(nav))
    assert.equal(acceptUnlock(text, at + 1000, 'navigate', 'other0123456'), null)
    // Run out, or without a token (an older build's): asks again.
    assert.equal(acceptUnlock(text, at + RELOCK_HIDDEN_MS + 1, 'reload', null), null)
    assert.equal(acceptUnlock(writeUnlock('abc123', at), at, 'reload', null), null)
    assert.equal(acceptUnlock(JSON.stringify({ id: 'abc123', at, token: '<script>' }), at, 'reload', null), null)
})

test('an unnamed profile is numbered by its place, found by id even in a copy of the book', () => {
    const book = added(added(defaultBook()))
    const copy = readBook(writeBook(book))
    assert.equal(profileNumber(book, copy.profiles[2].id), 3)
    assert.equal(profileNumber(book, ADOPTED), 1)
    assert.equal(profileNumber(book, 'gone00'), 0)
})

test('a page answers only its own profile stream reads; an untagged one only with a single profile', () => {
    assert.ok(streamReadAllowed('abc123', 'abc123', 2))
    assert.ok(!streamReadAllowed('main', 'abc123', 2))
    assert.ok(!streamReadAllowed(undefined, 'abc123', 2))
    assert.ok(!streamReadAllowed(null, 'main', 3))
    assert.ok(streamReadAllowed(undefined, 'main', 1))
    assert.ok(!streamReadAllowed(42, 'main', 2))
})

test('a tab hidden for more than a minute locks its PIN profile again', async () => {
    const { book, locked } = await lockedPair()
    assert.ok(!relockDue(book, locked, null, 1_000_000))
    assert.ok(!relockDue(book, locked, 1_000_000, 1_000_000 + RELOCK_HIDDEN_MS))
    assert.ok(relockDue(book, locked, 1_000_000, 1_000_000 + RELOCK_HIDDEN_MS + 1))
    // A profile with no PIN has nothing to lock.
    assert.ok(!relockDue(book, ADOPTED, 0, 10 * RELOCK_HIDDEN_MS))
})
