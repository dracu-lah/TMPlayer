// The series page's merge (seriesGuide.ts), the cases of the apps' SeriesGuideTableTest: two of six
// seasons, five of ten episodes, specials, a two part file, a wrong match, another numbering, no
// metadata, garbage and huge answers.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { agrees, hasGaps, rows, seasons, type HeldSeason, type ListedSeason } from './seriesGuide.ts'

const today = '2026-11-16'
const range = (a: number, b: number) => Array.from({ length: b - a + 1 }, (_, i) => a + i)
const held = (number: number, numbers: number[], covered: number[] = []): HeldSeason => ({ number, numbers, covered })
const listed = (n: number, episodes = 10, airDate: string | null = `20${20 + n}-01-01`): ListedSeason => ({ number: n, episodes, airDate })
const states = (r: ReturnType<typeof rows>) => r.map((x) => (x.kind === 'have' ? 'have' : x.state))

test('two of six seasons present list all six, the unaired one by its date', () => {
    const all = [1, 2, 3, 4, 5].map((n) => listed(n)).concat([listed(6, 10, '2027-03-01')])
    const list = seasons([held(1, range(1, 3)), held(4, [1, 2])], all, null, today)
    assert.deepEqual(list.map((s) => s.number), [1, 2, 3, 4, 5, 6])
    assert.deepEqual(list.map((s) => s.state), ['have', 'missing', 'missing', 'have', 'missing', 'upcoming'])
    assert.equal(list[5].airDate, '2027-03-01')
    assert.ok(agrees([held(1, range(1, 3)), held(4, [1, 2])], all))
    assert.ok(hasGaps(list))
})

test('five of ten episodes present show five gaps by number', () => {
    const r = rows(1, [held(1, [1, 3, 5, 7, 9])], listed(1), null, today)
    assert.deepEqual(states(r), ['have', 'missing', 'have', 'missing', 'have', 'missing', 'have', 'missing', 'have', 'missing'])
    assert.deepEqual(r.map((x) => x.number), range(1, 10))
})

test('an unaired season lists its episodes as upcoming; the next episode dates the rest of a running one', () => {
    assert.deepEqual(states(rows(2, [held(1, [1])], listed(2, 3, '2027-02-01'), null, today)), ['upcoming', 'upcoming', 'upcoming'])
    const next = { season: 3, episode: 6, airDate: '2026-12-01' }
    const r = rows(3, [held(3, [1, 2, 3])], listed(3, 8, '2026-09-01'), next, today)
    assert.deepEqual(states(r), ['have', 'have', 'have', 'missing', 'missing', 'upcoming', 'upcoming', 'upcoming'])
    assert.equal((r[5] as { airDate: string | null }).airDate, '2026-12-01')
})

test('specials come last and only when the chat holds one', () => {
    const list = seasons([held(1, [1, 2]), held(0, [1])], [listed(1, 2)], null, today)
    assert.deepEqual(list.map((s) => s.number), [1, 0])
    assert.ok(list[1].special)
    assert.deepEqual(seasons([held(1, [1, 2])], [listed(1, 2)], null, today).map((s) => s.number), [1])
    assert.deepEqual(states(rows(0, [held(0, [1])], undefined, null, today)), ['have'])
})

test('a file holding two episodes is not missing its second', () => {
    assert.deepEqual(states(rows(1, [held(1, [1, 2, 3], [4])], listed(1, 4), null, today)), ['have', 'have', 'have'])
})

test('a wrong match or another numbering does not agree', () => {
    assert.ok(!agrees([held(1, range(1, 10)), held(2, range(1, 10)), held(3, range(1, 10))], [listed(1, 6), listed(2, 6)]))
    assert.ok(!agrees([held(1, range(1, 30))], [listed(1, 12), listed(2, 12), listed(3, 12)]))
    // One season past its count still agrees overall, and keeps its own list.
    const chat = [held(1, range(1, 40)), held(2, range(1, 12))]
    const known = [listed(1, 45), listed(2, 10)]
    assert.ok(agrees(chat, known))
    assert.deepEqual(states(rows(2, chat, known[1], null, today)), Array(12).fill('have'))
    assert.equal(states(rows(1, chat, known[0], null, today)).filter((x) => x === 'missing').length, 5)
})

test('with no metadata the page is as it was', () => {
    assert.ok(agrees([held(1, [1, 2])], undefined))
    assert.deepEqual(seasons([held(1, [1, 2])], undefined, null, today).map((s) => s.number), [1])
    assert.deepEqual(states(rows(1, [held(1, [1, 2])], undefined, null, today)), ['have', 'have'])
    assert.deepEqual(rows(2, [held(1, [1, 2])], undefined, null, today), [])
})

test('garbage and huge answers stay bounded and never throw', () => {
    const odd = [{ number: -3, episodes: 5, airDate: null }, { number: 0, episodes: 99, airDate: null }, { number: 1, episodes: Number.MAX_SAFE_INTEGER, airDate: null }, { number: Number.NaN, episodes: 1, airDate: null }, null, 'x', { number: 2, episodes: -9, airDate: 7 }] as never
    const big = Array.from({ length: 5000 }, (_, i) => listed(i + 1, 10, '2020-01-01'))
    const list = seasons([held(1, [1, 2])], [...odd, ...big], { season: Number.MAX_SAFE_INTEGER, episode: -1, airDate: '' }, today)
    assert.ok(list.length <= 201)
    assert.ok(list.every((s) => s.number >= 0))
    assert.ok(rows(1, [held(1, [1, 2])], { number: 1, episodes: Number.MAX_SAFE_INTEGER, airDate: null }, null, today).length <= 3000)
    agrees([held(1, [1, 2])], [...odd, ...big])
})

test('twenty seasons and a thousand episodes are cheap, from counts alone', () => {
    const chat = range(1, 20).map((n) => held(n, [1, 2, 3]))
    const known = range(1, 20).map((n) => listed(n, 50))
    const started = performance.now()
    assert.equal(seasons(chat, known, null, today).length, 20)
    const long = rows(1, chat, { number: 1, episodes: 1000, airDate: '2000-01-01' }, null, today)
    assert.equal(long.length, 1000)
    assert.equal(long.filter((x) => x.kind === 'gap').length, 997)
    assert.ok(performance.now() - started < 500)
})

test('a season held only as a whole-season file is held, and lists no gap', () => {
    const packOnly: HeldSeason = { number: 2, numbers: [], packed: true }
    const list = seasons([held(1, [1, 2]), packOnly], [listed(1), listed(2), listed(3)], null, today)
    assert.deepEqual(list.map((s) => [s.number, s.state]), [[1, 'have'], [2, 'have'], [3, 'missing']])
    assert.equal(list[1].have, 0)
    assert.deepEqual(rows(2, [held(1, [1, 2]), packOnly], listed(2), null, today), [])
    // A season with episodes and a pack keeps its own gaps.
    assert.deepEqual(states(rows(1, [{ number: 1, numbers: [1], packed: true }], listed(1, 3), null, today)), ['have', 'missing', 'missing'])
})
