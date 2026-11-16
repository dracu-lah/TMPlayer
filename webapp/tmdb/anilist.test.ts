// AniList's readers (anilist.ts) over the shapes its GraphQL answers have, and over garbage: every
// reader of outside data gets truncated, wrong-typed, huge and random input and must never throw.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { candidates, extrasOf, imageAddress, imagePath, imageTail, plain } from './anilist.ts'

const COVER = 'https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx21-ELSYx3yMPcKM.jpg'
const BANNER = 'https://s4.anilist.co/file/anilistcdn/media/anime/banner/21-wf37VakJmZqs.jpg'
const FACE = 'https://s4.anilist.co/file/anilistcdn/character/medium/b40-vnFl2Hcl44qt.png'

const search = (media: unknown) => ({ data: { Page: { media } } })
const naruto = {
    id: 20,
    format: 'TV',
    seasonYear: 2002,
    startDate: { year: 2002 },
    title: { romaji: 'Naruto', english: 'Naruto', native: 'ナルト' },
    synonyms: ['NARUTO', 'Naruto TV'],
    description: 'Moments before Naruto&#39;s birth,<br><br>a huge demon &amp; more.',
    coverImage: { large: COVER },
    bannerImage: BANNER,
}

test('a title is read as the app reads it: names, year, cleaned overview, pictures by short path', () => {
    const [c] = candidates(search([naruto]))
    assert.equal(c.id, '20')
    assert.deepEqual(c.names.slice(0, 3), ['Naruto', 'Naruto', 'ナルト'])
    assert.equal(c.year, 2002)
    assert.equal(c.format, 'TV')
    assert.equal(c.overview, "Moments before Naruto's birth,\n\na huge demon & more.")
    assert.equal(c.poster, '/al/cover/large/bx21-ELSYx3yMPcKM.jpg')
    assert.equal(c.backdrop, '/al/banner/21-wf37VakJmZqs.jpg')
    assert.equal(c.originalLanguage, 'ja')
})

test('the year falls back to the start date, and a missing year is null', () => {
    assert.equal(candidates(search([{ ...naruto, seasonYear: null }]))[0].year, 2002)
    assert.equal(candidates(search([{ id: 5, title: { romaji: 'X' } }]))[0].year, null)
})

test('an address that is not AniList\'s image host is no picture', () => {
    for (const bad of ['http://s4.anilist.co/file/anilistcdn/media/anime/cover/large/a1.jpg', 'https://evil.example/file/anilistcdn/media/anime/cover/large/a1.jpg', 'https://s4.anilist.co/other/a1.jpg', 'https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/../../a.jpg', 'https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/a1.svg', 42, null, {}]) {
        assert.equal(imageTail(bad), null, String(bad))
        assert.equal(imagePath(bad), null)
    }
})

test('a picture path goes back to AniList\'s image host, and only those', () => {
    assert.equal(imageAddress(imageTail(COVER)!), COVER)
    assert.equal(imageAddress(imageTail(BANNER)!), BANNER)
    assert.equal(imageAddress(imageTail(FACE)!), FACE)
    for (const bad of ['', '../x.jpg', 'cover/large/../../x.jpg', 'cover/large/a b.jpg', 'media/anime/cover/large/a1.jpg', 'banner/x.exe', 'cover//a1.jpg', 'cover/large/a1.jpg?x=1']) assert.equal(imageAddress(bad), null, bad)
})

test('the description is plain text', () => {
    assert.equal(plain('<i>One</i><br/>two&nbsp;&nbsp;three &lt;4&gt;'), 'One\ntwo three <4>')
    assert.equal(plain(''), '')
})

test('extras: genres, length, score out of ten, cast with voices, a YouTube trailer', () => {
    const x = extrasOf({
        data: {
            Media: {
                idMal: 20,
                genres: ['Action', 'Adventure', ''],
                averageScore: 79,
                duration: 23,
                trailer: { id: 'j2hiC9BmJlQ', site: 'youtube' },
                characters: { edges: [{ node: { name: { full: 'Naruto Uzumaki' }, image: { medium: FACE } }, voiceActors: [{ name: { full: 'Junko Takeuchi' } }] }, { node: { name: { full: '' } } }] },
            },
        },
    })!
    assert.equal(x.found, true)
    assert.deepEqual(x.genres, ['Action', 'Adventure'])
    assert.equal(x.runtime, 23)
    assert.equal(x.rating, 7.9)
    assert.deepEqual(x.cast, [{ name: 'Naruto Uzumaki', role: 'Junko Takeuchi', photo: '/al/character/medium/b40-vnFl2Hcl44qt.png' }])
    assert.equal(x.trailer, 'j2hiC9BmJlQ')
    assert.equal(x.logo, null)
    assert.ok('logo' in x)
})

test('no score until AniList has one, no trailer off YouTube, no media is no extras', () => {
    const x = extrasOf({ data: { Media: { averageScore: 0, duration: 0, trailer: { id: 'abc123', site: 'dailymotion' } } } })!
    assert.equal(x.rating, null)
    assert.equal(x.runtime, null)
    assert.equal(x.trailer, null)
    assert.equal(extrasOf({ data: { Media: null } }), null)
    assert.equal(extrasOf({ errors: [{ message: 'Not Found.' }] }), null)
})

// ---- garbage --------------------------------------------------------------------------------

const junk: unknown[] = [
    null,
    undefined,
    0,
    -1,
    1e308,
    NaN,
    'x',
    '{"data":',
    [],
    [1, 2, 3],
    {},
    { data: null },
    { data: [] },
    { data: { Page: 'no' } },
    { data: { Page: { media: 'no' } } },
    { data: { Page: { media: [null, 1, 'a', [], { id: 'x' }, { id: -4 }, { id: 1.5 }, { id: 7, title: 'no', synonyms: 'no', description: 9, coverImage: 'no', startDate: 'no' }] } } },
    { data: { Media: { genres: 'no', characters: 'no', trailer: 'no', averageScore: 'high', duration: {} } } },
    { data: { Media: { characters: { edges: [null, 3, { node: 'no' }, { node: { name: 5 } }, { node: { name: { full: 'A' } }, voiceActors: 'no' }] } } } },
]

test('garbage never throws and never invents a title', () => {
    for (const j of junk) {
        assert.doesNotThrow(() => candidates(j))
        assert.doesNotThrow(() => extrasOf(j))
    }
    // The one entry that has a whole number id is a title with nothing else: no names, no year, no pictures.
    const [only] = candidates(junk[15])
    assert.deepEqual({ id: only.id, names: only.names, year: only.year, poster: only.poster, backdrop: only.backdrop }, { id: '7', names: [], year: null, poster: null, backdrop: null })
    assert.deepEqual(candidates(junk[0]), [])
})

test('huge input is cut: names, synonyms, overview and genres have a bound', () => {
    const big = 'x'.repeat(100_000)
    const [c] = candidates(search([{ id: 1, title: { english: big }, synonyms: Array.from({ length: 5_000 }, () => big), description: big }]))
    assert.ok(c.names.length <= 24)
    assert.ok(c.names.every((n) => n.length <= 200))
    assert.ok(c.overview!.length <= 2_000)
    const x = extrasOf({ data: { Media: { genres: Array.from({ length: 1_000 }, () => 'g'), characters: { edges: Array.from({ length: 1_000 }, () => ({ node: { name: { full: 'N' } } })) } } } })!
    assert.ok(x.genres.length <= 12)
    assert.ok(x.cast.length <= 10)
})

test('random shapes never throw', () => {
    let seed = 7
    const next = () => (seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff
    const pick = (depth: number): unknown => {
        const r = next()
        if (depth > 4 || r < 0.3) return [null, 0, -3, 1.5, 'x', '', true, 'https://s4.anilist.co/x'][Math.floor(next() * 8)]
        if (r < 0.55) return Array.from({ length: Math.floor(next() * 4) }, () => pick(depth + 1))
        const o: Record<string, unknown> = {}
        for (const k of ['data', 'Page', 'media', 'Media', 'id', 'title', 'english', 'genres', 'characters', 'edges', 'node', 'name', 'full', 'trailer', 'site', 'synonyms']) if (next() < 0.35) o[k] = pick(depth + 1)
        return o
    }
    for (let i = 0; i < 2_000; i++) {
        const j = pick(0)
        assert.doesNotThrow(() => candidates(j))
        assert.doesNotThrow(() => extrasOf(j))
    }
})
