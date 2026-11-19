// The search by topic name (WB21, U14) against a fake account: its budget (twelve groups, three at a
// time, twenty topics each, nothing without words, nothing after an abort), a flood wait waited out by
// the call that got it, the groups that refuse, and what Telegram's answers may look like.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import * as core from '../rules/core/tmplayer-rules.mjs'
import type { Rules } from '../rules'
import { COUNTED_HITS, findTopics, keepTopics, keepWhole, keptTopics, forgetTopics, telegramTopics, topicWords, type ForumChat, type RawTopic, type TopicAsk } from './topics.ts'

core.provideCatalog('en', readFileSync(new URL('../../../core/src/commonMain/resources/i18n/en.json', import.meta.url), 'utf8'))
const rules = core as unknown as Rules

const forum = (id: number, title = `Group ${id}`, archived = false): ForumChat => ({ id, title, archived })
const topic = (id: number, title: string, o: Partial<RawTopic> = {}): RawTopic => ({ id, title, pinned: false, closed: false, ...o })

/** A fake account: topics per chat, the calls made, and how many were in flight at once. */
function account(topics: Record<number, RawTopic[]>, o: { empty?: Set<string>; fail?: (chatId: number, n: number) => unknown; delay?: number; countFails?: unknown } = {}) {
    const calls: string[] = []
    const asks = new Map<number, number>()
    let inFlight = 0
    let peak = 0
    const ask: TopicAsk = {
        async topicsOf(chatId, query, limit) {
            calls.push(`topics ${chatId} ${query} ${limit}`)
            const n = (asks.get(chatId) ?? 0) + 1
            asks.set(chatId, n)
            inFlight++
            peak = Math.max(peak, inFlight)
            try {
                await new Promise((r) => setTimeout(r, o.delay ?? 1))
                const err = o.fail?.(chatId, n)
                if (err) throw err
                return (topics[chatId] ?? []).slice(0, limit)
            } finally {
                inFlight--
            }
        },
        async hasVideos(chatId, topicId) {
            calls.push(`count ${chatId} ${topicId}`)
            if (o.countFails) throw o.countFails
            return !o.empty?.has(`${chatId}:${topicId}`)
        },
    }
    return { ask, calls, peak: () => peak }
}

const search = (a: ReturnType<typeof account>, o: { forums: ForumChat[]; query: string; added?: number[] | null; signal?: AbortSignal; sleep?: (ms: number) => Promise<void> }) =>
    findTopics({
        rules,
        ask: a.ask,
        forums: o.forums,
        order: o.forums.map((f) => f.id),
        added: o.added ?? null,
        includeArchived: true,
        query: o.query,
        signal: o.signal ?? new AbortController().signal,
        sleep: o.sleep ? (ms) => o.sleep!(ms) : async () => {},
    })

const floodError = (n: number) => Object.assign(new Error(`FLOOD_WAIT_${n}`), { text: `FLOOD_WAIT_${n}`, seconds: n })

test('nothing is asked without words that a name could answer', async () => {
    const a = account({ 1: [topic(5, 'Lessons')] })
    for (const q of ['', '   ', '🙂', '...', '\n\t']) assert.deepEqual((await search(a, { forums: [forum(1)], query: q })).hits, [], JSON.stringify(q))
    assert.equal(topicWords(42), '')
    assert.equal(topicWords(null), '')
    assert.deepEqual(a.calls, [])
})

test('a hit is "Chat > Topic": accents and emoji fold, equal matches go by the order of the chat list, a topic with no videos last', async () => {
    const a = account({
        1: [topic(10, '☕ Café talks'), topic(11, 'Cafe archive', { closed: true })],
        2: [topic(20, 'Cafe')],
    }, { empty: new Set(['1:11']) })
    const found = await search(a, { forums: [forum(1, 'Music'), forum(2, 'Food')], query: 'cafe' })
    assert.deepEqual(found.hits.map((h) => `${h.chatTitle} > ${h.name}`), ['Music > ☕ Café talks', 'Food > Cafe', 'Music > Cafe archive'])
    assert.equal(found.hits[2].hasVideos, false, 'a topic with no videos is listed, dimmed')
    assert.equal(found.hits[2].closed, true)
    assert.equal(found.searching, false)
    assert.equal(found.refused, 0)
})

test('a pinned topic goes before an unpinned one of the same match, an untitled one is kept as Telegram matched it', async () => {
    const a = account({ 1: [topic(1, 'Lessons'), topic(2, 'Lessons 2', { pinned: true }), topic(3, '')] })
    const found = await search(a, { forums: [forum(1)], query: 'lessons' })
    assert.deepEqual(found.hits.map((h) => h.topicId), [2, 1, 3])
    assert.equal(found.hits[2].name, '')
})

test('3,000 forum chats: twelve are asked, three at a time, twenty topics each, and the page is told there are more', async () => {
    const forums = Array.from({ length: 3000 }, (_, i) => forum(i + 1))
    const a = account(Object.fromEntries(forums.map((f) => [f.id, Array.from({ length: 50 }, (_, i) => topic(i + 1, `Lessons ${i}`))])), { delay: 5 })
    const found = await search(a, { forums, query: 'lessons' })
    const asked = a.calls.filter((c) => c.startsWith('topics '))
    assert.equal(asked.length, 12)
    assert.ok(asked.every((c) => c.endsWith(' 20')), 'twenty topics a group')
    assert.ok(a.peak() <= 3, `at most three at once, saw ${a.peak()}`)
    assert.equal(found.partial, true)
    assert.equal(found.asked, 12)
    assert.equal(found.hits.length, 30, 'a heading of topics, not a second results list')
    assert.ok(a.calls.filter((c) => c.startsWith('count ')).length <= COUNTED_HITS, 'the video counts are for the best few only')
})

test('only the added groups are asked; archived ones go with the search switch', async () => {
    const a = account({ 1: [topic(1, 'Lessons')], 2: [topic(1, 'Lessons')], 3: [topic(1, 'Lessons')] })
    const forums = [forum(1), forum(2), forum(3, 'Old', true)]
    const found = await search(a, { forums, query: 'lessons', added: [2, 3] })
    assert.deepEqual(found.hits.map((h) => h.chatId).sort(), [2, 3])
    assert.equal(found.partial, false)
    assert.deepEqual((await search(account({}), { forums, query: 'lessons', added: [] })).hits, [])
    const none = account({})
    assert.equal((await search(none, { forums, query: 'lessons', added: [] })).asked, 0)
    assert.deepEqual(none.calls, [], 'no added group with topics, no call')
})

test('a new letter aborts the search: no group is asked after it', async () => {
    const forums = Array.from({ length: 12 }, (_, i) => forum(i + 1))
    const a = account(Object.fromEntries(forums.map((f) => [f.id, [topic(1, 'Lessons')]])), { delay: 20 })
    const controller = new AbortController()
    let partialsAfter = 0
    const run = findTopics({
        rules, ask: a.ask, forums, order: forums.map((f) => f.id), added: null, includeArchived: true, query: 'lessons', signal: controller.signal, sleep: async () => {},
        onPartial: () => controller.signal.aborted && partialsAfter++,
    })
    // The first three groups are in flight (20 ms each): abort while they are.
    await new Promise((r) => setTimeout(r, 10))
    assert.equal(a.calls.length, 3)
    controller.abort()
    await assert.rejects(run, (e: Error) => e.name === 'AbortError' || /abort/i.test(String(e)))
    await new Promise((r) => setTimeout(r, 120))
    assert.equal(a.calls.length, 3, 'the three in flight finished, no other group and no count was asked after the abort')
    assert.equal(partialsAfter, 0, 'an answer that lands after the abort is not sent on')
})

test('a flood wait is waited out by the call that got it, once, while the other groups go on', async () => {
    const forums = [1, 2, 3, 4].map((id) => forum(id))
    const a = account(Object.fromEntries(forums.map((f) => [f.id, [topic(1, `Lessons of ${f.id}`)]])), { fail: (chat, n) => (chat === 1 && n === 1 ? floodError(3) : null) })
    const slept: number[] = []
    const order: string[] = []
    const found = await findTopics({
        rules, ask: a.ask, forums, order: [1, 2, 3, 4], added: null, includeArchived: true, query: 'lessons', signal: new AbortController().signal,
        sleep: async (ms) => {
            slept.push(ms)
            await new Promise((r) => setTimeout(r, 30))
        },
        onPartial: (f) => order.push(f.hits.map((h) => h.chatId).join()),
    })
    assert.deepEqual(slept, [3250])
    assert.equal(found.hits.length, 4, 'the retried group is listed too')
    assert.equal(a.calls.filter((c) => c.startsWith('topics 1 ')).length, 2, 'asked again once')
    assert.ok(!order[0].includes('1'), 'the others answered while it waited')
    assert.equal(found.refused, 0)
})

test('a second flood wait, or one past thirty seconds, skips the group and lists the rest', async () => {
    const forums = [1, 2, 3].map((id) => forum(id))
    const a = account(Object.fromEntries(forums.map((f) => [f.id, [topic(1, 'Lessons')]])), {
        fail: (chat, n) => (chat === 1 ? floodError(2) : chat === 2 && n === 1 ? floodError(31) : null),
    })
    const slept: number[] = []
    const found = await search(a, { forums, query: 'lessons', sleep: async (ms) => void slept.push(ms) })
    assert.deepEqual(slept, [2250], 'group 1 waits once; 31 s is too long to wait')
    assert.deepEqual(found.hits.map((h) => h.chatId), [3])
    assert.equal(found.refused, 2)
})

test('every group refusing is a failure with the last reason; some refusing is not', async () => {
    const forums = [1, 2].map((id) => forum(id))
    const refuse = (n: string) => Object.assign(new Error(n), { text: n, code: 400 })
    await assert.rejects(search(account({}, { fail: () => refuse('CHANNEL_PRIVATE') }), { forums, query: 'lessons' }), /CHANNEL_PRIVATE/)
    const some = await search(account({ 2: [topic(1, 'Lessons')] }, { fail: (chat) => (chat === 1 ? refuse('CHAT_ADMIN_REQUIRED') : null) }), { forums, query: 'lessons' })
    assert.equal(some.hits.length, 1)
    assert.equal(some.refused, 1)
})

test('a count that fails leaves the topic listed, not dimmed', async () => {
    const a = account({ 1: [topic(1, 'Lessons')] })
    a.ask.hasVideos = async () => {
        throw new Error('boom')
    }
    const found = await search(a, { forums: [forum(1)], query: 'lessons' })
    assert.equal(found.hits.length, 1)
    assert.equal(found.hits[0].hasVideos, true)
})

test('the adapter reads Telegram\'s topics safely: no id, a repeat or a wrong type is skipped', async () => {
    const tg = {
        async getForumTopics() {
            return [
                { id: 5, title: 'Good', isPinned: true, isClosed: false },
                { id: 5, title: 'Repeat' },
                { id: 'x', title: 'Wrong type' },
                { id: -2, title: 'Negative' },
                { id: 6, title: 42, titleMissing: false },
                { id: 7, title: 'Hidden', titleMissing: true },
                null,
            ].filter(Boolean)
        },
        async searchMessages(p: { filter: { _: string }; threadId: number }) {
            return Object.assign([], { total: p.threadId === 5 && p.filter._ === 'inputMessagesFilterDocument' ? 3 : 0 })
        },
    }
    const real = telegramTopics(tg as never)
    const found = await real.topicsOf(1, 'x', 20, new AbortController().signal)
    assert.deepEqual(found.map((t) => [t.id, t.title, t.pinned]), [[5, 'Good', true], [6, '42', false], [7, '', false]])
    assert.equal(await real.hasVideos(1, 5, new AbortController().signal), true, 'files count when there is no video')
    assert.equal(await real.hasVideos(1, 6, new AbortController().signal), false)
})

test('answers are kept two minutes, for the same words and chats', () => {
    forgetTopics()
    const found = { hits: [], asked: 1, refused: 0, partial: false, searching: false }
    keepTopics('a', found, 1000)
    assert.equal(keptTopics('a', 1000 + 119_000), found)
    assert.equal(keptTopics('a', 1000 + 121_000), null)
    assert.equal(keptTopics('b', 1000), null)
    for (let i = 0; i < 100; i++) keepTopics(`k${i}`, found, 5)
    assert.equal(keptTopics('k0', 5), null, 'bounded')
    assert.equal(keptTopics('k99', 5), found)
})

test('counting stops for the rest of the search once Telegram refuses one count, and never waits a flood out', async () => {
    forgetTopics()
    const forums = [forum(1)]
    const a = account({ 1: Array.from({ length: 20 }, (_, i) => topic(i + 1, `Lessons ${i}`)) }, { countFails: floodError(9) })
    const slept: number[] = []
    const found = await search(a, { forums, query: 'lessons', sleep: async (ms) => void slept.push(ms) })
    // One count was refused by each lane at most (three side by side), then none more.
    assert.ok(a.calls.filter((c) => c.startsWith('count ')).length <= 3, `counts asked: ${a.calls.filter((c) => c.startsWith('count ')).length}`)
    assert.deepEqual(slept, [], 'a count is not waited out')
    assert.equal(found.hits.length, 20)
    assert.ok(found.hits.every((h) => h.hasVideos), 'unread topics are not dimmed')
})

test('a topic counted by one search is not counted by the next', async () => {
    forgetTopics()
    const forums = [forum(1)]
    const first = account({ 1: [topic(1, 'Lessons a'), topic(2, 'Lessons b')] }, { empty: new Set(['1:2']) })
    await search(first, { forums, query: 'lessons' })
    assert.equal(first.calls.filter((c) => c.startsWith('count ')).length, 2, 'one count a topic')
    const second = account({ 1: [topic(1, 'Lessons a'), topic(2, 'Lessons b')] }, { empty: new Set(['1:2']) })
    const found = await search(second, { forums, query: 'lessons' })
    assert.equal(second.calls.filter((c) => c.startsWith('count ')).length, 0)
    assert.equal(found.hits.find((h) => h.topicId === 2)!.hasVideos, false, 'the remembered answer still dims it')
})

test('an answer with a skipped group is not kept', () => {
    forgetTopics()
    const whole = { hits: [], asked: 3, refused: 0, partial: false, searching: false }
    assert.equal(keepWhole('whole', whole), true)
    assert.equal(keptTopics('whole'), whole)
    assert.equal(keepWhole('skipped', { ...whole, refused: 1 }), false)
    assert.equal(keptTopics('skipped'), null)
})
