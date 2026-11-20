import { test } from 'node:test'
import assert from 'node:assert/strict'
import { cleanHomeChats, cleanResume, cleanSettings, parseJournal, parseLast, readAddedMode } from './stored.ts'

/** What storage can hand back that is not what was written: cut short, the wrong type, absurd. */
const GARBAGE: unknown[] = [undefined, null, 0, -1, 1e999, NaN, '', 'x', 'x'.repeat(200_000), true, false, [], [1, 'a', null], {}, { a: 1 }, () => 1]

test('good settings stand', () => {
    const good = { theme: 'dark', sizeMin: 0, sizeMax: 1024, sort: 'Name', posters: false, trailersInApp: true, rewindMs: 5000, carousel: false, homeContinue: true, homeRecent: false, homeStarred: true, homeOrder: 'recent,continue' }
    assert.deepEqual(cleanSettings(good), good)
    assert.deepEqual(cleanSettings({ rewindMs: null }), { rewindMs: null })
})

test('garbage: a settings value of the wrong type is left out, so the default stands', () => {
    for (const key of ['theme', 'sizeMin', 'sizeMax', 'sort', 'posters', 'trailersInApp', 'rewindMs', 'carousel', 'homeContinue', 'homeRecent', 'homeStarred', 'homeOrder']) {
        for (const bad of [5, 'yes', {}, ['recent'], 'x'.repeat(200_000), NaN, -3]) {
            const out = cleanSettings({ [key]: bad }) as Record<string, unknown>
            // A string is a fine homeOrder, a number a fine size: only the others must be gone.
            if (key === 'homeOrder' && bad === 'yes') continue
            if ((key === 'sizeMin' || key === 'sizeMax') && bad === 5) continue
            if (key === 'rewindMs' && bad === 5) continue
            assert.equal(key in out, false, `${key}=${String(bad).slice(0, 12)}`)
        }
    }
})

test('garbage: a settings blob that is not an object is no settings', () => {
    for (const bad of GARBAGE) if (typeof bad !== 'object' || bad === null || Array.isArray(bad)) assert.deepEqual(cleanSettings(bad), {})
    assert.deepEqual(cleanSettings(() => 1), {})
})

test('an older build\'s keys and chosen chats are dropped', () => {
    const old = cleanSettings({ carousel: true, recentSource: 'chosen', recentChats: [-1001, 5], rows: 'big' })
    assert.deepEqual(old, { carousel: true })
})

test('the order of rows is text and nothing else', () => {
    assert.deepEqual(cleanSettings({ homeOrder: 'recent,continue' }), { homeOrder: 'recent,continue' })
    for (const bad of [5, null, {}, ['recent'], true, 'x'.repeat(200_000)]) assert.equal('homeOrder' in cleanSettings({ homeOrder: bad }), false)
})

test('Home chat rows: bad rows are skipped, the rest kept, titles cut', () => {
    const rows = cleanHomeChats([{ chatId: 1, title: 'A' }, null, 'x', { chatId: '2', title: 'B' }, { chatId: 3 }, { chatId: NaN, title: 'C' }, { chatId: 4, title: 'x'.repeat(500), extra: 1 }])
    assert.deepEqual(rows.map((r) => r.chatId), [1, 4])
    assert.equal(rows[1].title.length, 200)
    for (const bad of GARBAGE) if (!Array.isArray(bad) || bad.length === 0) assert.deepEqual(cleanHomeChats(bad), [])
    assert.equal(cleanHomeChats(Array.from({ length: 9000 }, (_, i) => ({ chatId: i, title: 't' }))).length, 5000)
})

test('the added mode is one of two words, else never asked', () => {
    assert.equal(readAddedMode('all'), 'all')
    assert.equal(readAddedMode('picked'), 'picked')
    for (const bad of [...GARBAGE, 'unanswered', 'ALL']) assert.equal(readAddedMode(bad), 'unanswered')
})

test('a saved place needs its ids and position; length and time default to 0', () => {
    assert.deepEqual(cleanResume({ chatId: 1, messageId: 2, position: 30, length: 100, time: 5 }), { chatId: 1, messageId: 2, position: 30, length: 100, time: 5 })
    assert.deepEqual(cleanResume({ chatId: 1, messageId: 2, position: 30 }), { chatId: 1, messageId: 2, position: 30, length: 0, time: 0 })
    for (const bad of [...GARBAGE, { chatId: 1 }, { chatId: '1', messageId: 2, position: 3 }]) assert.equal(cleanResume(bad), null)
})

test('the last save kept for a closing page', () => {
    assert.equal(parseLast(null), null)
    assert.equal(parseLast(''), null)
    assert.equal(parseLast('{"key":'), null)
    for (const bad of ['5', '"x"', '[]', 'null', '{}', '{"key":""}', '{"key":5}', '{"key":"1:2","resume":"x"}', '{"key":"1:2","watched":"no"}']) assert.equal(parseLast(bad), null, bad)
    assert.deepEqual(parseLast('{"key":"1:2","watched":9}'), { key: '1:2', watched: 9 })
    assert.equal(parseLast('{"key":"1:2","resume":{"chatId":1,"messageId":2,"position":40,"length":90,"time":7}}')?.resume?.position, 40)
})

test('the journal of noted writes keeps only notes, and never throws', () => {
    for (const bad of [null, '', '{', 'null', '5', '"x"', '[]', '[1]', 'true']) assert.deepEqual(parseJournal(bad), {}, String(bad))
    const kept = parseJournal(JSON.stringify({ 'resume:1:2': { v: { position: 5 } }, 'watched:1:2': { d: 1 }, bad: 5, worse: null, 'also bad': 'x', odd: { d: 2 } }))
    assert.deepEqual(Object.keys(kept).sort(), ['resume:1:2', 'watched:1:2'])
    assert.deepEqual(kept['watched:1:2'], { d: 1 })
    assert.deepEqual(kept['resume:1:2'], { v: { position: 5 } })
})
