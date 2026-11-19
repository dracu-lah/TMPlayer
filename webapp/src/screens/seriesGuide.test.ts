// The series page's merge (seriesGuide.ts), the cases of the apps' SeriesGuideTableTest: two of six
// seasons, five of ten episodes, specials, a two part file, a wrong match, another numbering, no
// metadata, garbage and huge answers.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { agrees, counted, hasGaps, numbering, place, readSeason, rekey, rows, seasons, seasonsFor, type HeldSeason, type ListedSeason, type ProviderEpisode } from './seriesGuide.ts'

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

// ---- the season split and a chat counting straight through (the apps' EpisodeGuide.numbering, EpisodeNumbering) ----

const blue = (n: number) => ({ number: n, episodes: n === 1 ? 25 : 12, airDate: n === 1 ? '2024-10-03' : '2026-10-02' })
const facts = { seasonList: [blue(1), blue(2)], next: { season: 2, episode: 8, airDate: '2026-11-20' }, seasonGroup: 'seasons001', listedSeasons: [{ number: 1, episodes: 37, airDate: '2024-10-03' }], listedNext: { season: 1, episode: 33, airDate: '2026-11-20' } }

test('a chat numbered S02E01 follows the split, one numbered S01E26 follows TMDB\'s own, a show without a split has one numbering', () => {
    const split = numbering([held(1, range(1, 25)), held(2, range(1, 5))], facts)
    assert.ok(split.split)
    assert.deepEqual(split.seasons.map((s) => s.episodes), [25, 12])
    assert.deepEqual(split.next, facts.next)
    const through = numbering([held(1, range(1, 30))], facts)
    assert.ok(!through.split)
    assert.deepEqual(through.seasons.map((s) => s.episodes), [37])
    assert.deepEqual(through.next, facts.listedNext)
    // Disagrees with both: the split stands (nothing is called missing then).
    assert.ok(numbering([held(1, range(1, 60))], facts).split)
    const plain = numbering([held(1, [1])], { seasonList: [listed(1)], next: null })
    assert.deepEqual([plain.split, plain.seasons.length], [false, 1])
    assert.deepEqual(numbering([held(1, [1])], null), { seasons: [], next: null, split: false })
})

const stand = [listed(1, 12), listed(2, 12), listed(3, 12)]

test('a season counted straight through is placed by TMDB\'s season sizes', () => {
    const second = range(13, 24)
    assert.ok(counted(2, second, stand))
    assert.ok(!counted(1, range(1, 12), stand))
    assert.ok(!counted(2, range(1, 12), stand))
    assert.ok(!counted(0, [30], stand), 'specials never')
    assert.ok(!counted(9, [30], stand), 'a season TMDB does not list')
    assert.ok(!counted(2, [1, 2, 3, 4, 5, 6, 7, 13], stand), 'one stray number past its end')
    assert.deepEqual(place(2, 13, second, stand), [2, 1])
    assert.deepEqual(place(2, 24, second, stand), [2, 12])
    assert.deepEqual(place(2, 5, range(1, 5), stand), [2, 5], 'not counted: as written')
    assert.deepEqual(place(2, 40, second, stand), null)
    assert.deepEqual(place(2, 0, second, stand), null)
    assert.deepEqual(seasonsFor(2, second, stand), [2])
    assert.deepEqual(seasonsFor(1, range(1, 30), stand), [1, 2, 3])
    assert.deepEqual(seasonsFor(2, range(1, 5), stand), [2])
})

test('TMDB\'s lists are put under the chat\'s numbers, and an uncounted season answers its own list', () => {
    const ep = (number: number, name: string): ProviderEpisode => ({ number, name, overview: '', still: null, airDate: null, runtime: null })
    const lists = new Map([[2, [ep(1, 'One'), ep(2, 'Two')]], [1, [ep(11, 'Eleven'), ep(12, 'Twelve')]]])
    const out = rekey(2, [13, 14, 99], stand, lists)
    assert.deepEqual(out.map((e) => [e.number, e.name]), [[13, 'One'], [14, 'Two']])
    assert.deepEqual(rekey(2, [1, 2], stand, lists).map((e) => [e.number, e.name]), [[1, 'One'], [2, 'Two']])
    assert.deepEqual(rekey(5, [1], stand, lists), [])
})

test('the page asks at most twelve of TMDB\'s seasons for one chat season, and garbage layouts stay bounded', () => {
    const many = range(1, 200).map((n) => listed(n, 1))
    assert.ok(seasonsFor(150, range(1, 300), many).length <= 12)
    assert.ok(seasonsFor(1, [Number.MAX_SAFE_INTEGER, 1e9, -5, 0.5], many).length <= 12)
    const odd = [null, 'x', { number: 1, episodes: Number.MAX_SAFE_INTEGER, airDate: null }, { number: Number.NaN, episodes: 5, airDate: null }] as never
    assert.equal(counted(1, [1, 2], odd), false)
    assert.deepEqual(place(1, 5, [5], odd), [1, 5])
})

test('with TMDB\'s episode list the gaps follow it and its days: aired is missing, not yet aired is upcoming', () => {
    const ep = (number: number, airDate: string | null): ProviderEpisode => ({ number, name: `E${number}`, overview: '', still: null, airDate, runtime: 24 })
    const provider = [ep(1, '2026-10-01'), ep(2, '2026-10-08'), ep(3, '2026-12-01'), ep(4, null)]
    const r = rows(2, [held(2, [1])], listed(2, 4, '2026-10-01'), null, today, provider)
    assert.deepEqual(states(r), ['have', 'missing', 'upcoming', 'upcoming'])
    assert.equal((r[2] as { airDate: string | null }).airDate, '2026-12-01')
    // A list longer than the count wins; a chat past the list keeps its own numbering.
    assert.equal(rows(2, [held(2, [1])], listed(2, 2, '2026-10-01'), null, today, provider).length, 4)
    assert.deepEqual(states(rows(2, [held(2, [1, 9])], listed(2, 4), null, today, provider)), ['have', 'have'])
    // Empty or garbage lists fall back to the count.
    assert.equal(rows(1, [held(1, [1])], listed(1, 3), null, today, []).length, 3)
    assert.equal(rows(1, [held(1, [1])], listed(1, 3), null, today, [null, 7, { number: -1 }] as never).length, 3)
})

test('the worker\'s season answer is read field by field: garbage and huge answers keep only the sane episodes', () => {
    const good = { number: 3, name: ' Third ', overview: 'Words', still: '/still0003abc.jpg', airDate: '2026-10-02', runtime: 24 }
    const read = readSeason({ found: true, episodes: [good, null, 7, 'x', { number: 0 }, { number: 1.5 }, { number: 99999 }, { number: 4, name: 7, still: '/../x.jpg', airDate: 'soon', runtime: 1e9 }] })!
    assert.deepEqual(read.map((e) => e.number), [3, 4])
    assert.equal(read[0].name, 'Third')
    assert.deepEqual([read[1].name, read[1].still, read[1].airDate, read[1].runtime], ['', null, null, null])
    for (const bad of [null, 7, 'x', {}, { found: false }, { found: true }, { found: true, episodes: 'no' }]) assert.equal(readSeason(bad), null)
    const huge = readSeason({ found: true, episodes: Array.from({ length: 10_000 }, (_, i) => ({ number: i + 1, name: 'x'.repeat(5000) })) })!
    assert.ok(huge.length <= 3000)
    assert.ok(huge.every((e) => e.name.length <= 400))
})

test('a thousand-episode season with a thousand-episode list stays cheap', () => {
    const provider = range(1, 1000).map((n): ProviderEpisode => ({ number: n, name: `E${n}`, overview: '', still: null, airDate: '2000-01-01', runtime: 24 }))
    const started = performance.now()
    const r = rows(1, [held(1, [1, 2, 3])], listed(1, 1000, '2000-01-01'), null, today, provider)
    assert.equal(r.length, 1000)
    assert.ok(performance.now() - started < 500)
})

test('3,000 episodes counted straight through are placed in one pass, not one pass per number', () => {
    const layout = range(1, 20).map((n) => listed(n, 150))
    const numbers = range(1, 3000)
    const lists = new Map(range(1, 20).map((s) => [s, range(1, 150).map((n): ProviderEpisode => ({ number: n, name: `E${s}x${n}`, overview: '', still: null, airDate: null, runtime: null }))]))
    const started = performance.now()
    const seasons = seasonsFor(1, numbers, layout)
    const out = rekey(1, numbers, layout, lists)
    assert.ok(seasons.length <= 12)
    assert.equal(out.length, 3000)
    assert.equal(out[2999].name, 'E20x150')
    assert.ok(performance.now() - started < 300, `took ${performance.now() - started} ms`)
})
