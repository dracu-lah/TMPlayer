import assert from 'node:assert/strict'
import { test } from 'node:test'
import { decodePick, encodePick, isPick, sameTitle, withPick } from './pick.ts'

const video = (messageId: number, title: string, year: number | null, series = false, chatId = 7) => ({
    chatId,
    messageId,
    poster: { title, year, kind: series ? 'show' : 'film', tmdbId: null, series, languages: [], episode: null } as never,
})

test('the others of a title in the chat: a film by name and year, a show by name; not itself, not another chat', () => {
    const film = video(1, 'Idiot', 2022)
    const pool = [film, video(2, 'idiot', 2022), video(3, 'Idiot', 2019), video(4, 'Idiot', 2022, false, 8), video(5, 'Something', 2022), { chatId: 7, messageId: 6, poster: null }, video(2, 'Idiot', 2022)]
    assert.deepEqual(sameTitle(film, pool).map((v) => v.messageId), [2])
    const episode = video(10, 'Harbour Notes', null, true)
    const episodes = [video(11, 'Harbour Notes', 2021, true), video(12, 'harbour  notes', null, true), video(13, 'Harbour Notes', null, false)]
    assert.deepEqual(sameTitle(episode, [episode, ...episodes]).map((v) => v.messageId), [11, 12])
    assert.deepEqual(sameTitle({ chatId: 7, messageId: 1, poster: null }, pool), [])
    assert.deepEqual(sameTitle(film, []), [])
})

test('a pick reads back as it was written, in the text the apps store', () => {
    assert.equal(encodePick({ id: 123, kind: 'film' }), 'tmdb=film:123')
    assert.deepEqual(decodePick('tmdb=show:9'), { id: 9, kind: 'show' })
    assert.deepEqual(decodePick(encodePick({ id: 603, kind: 'film' })), { id: 603, kind: 'film' })
    // The apps write other lines beside it.
    assert.deepEqual(decodePick('separate\ntmdb=film:5\ntitle=Night Train'), { id: 5, kind: 'film' })
})

test('garbage is no pick', () => {
    for (const bad of [null, undefined, 5, {}, [], '', 'wrong', 'tmdb=', 'tmdb=film:', 'tmdb=film:0', 'tmdb=film:-4', 'tmdb=movie:5', 'tmdb=film:1e3', 'tmdb=film:99999999999', 'TMDB=film:5', 'title=tmdb=film:5']) {
        assert.equal(decodePick(bad), null, String(bad))
    }
    assert.equal(isPick({ id: 5, kind: 'film' }), true)
    for (const bad of [null, 5, {}, { id: 0, kind: 'film' }, { id: 1.5, kind: 'film' }, { id: '5', kind: 'film' }, { id: 5, kind: 'movie' }, { id: 1e12, kind: 'show' }]) {
        assert.equal(isPick(bad), false, JSON.stringify(bad))
    }
})

const ask = { title: 'Idiot', year: 2022, kind: 'film', tmdbId: null, series: false, languages: ['ta'], episode: null } as never

test('a pick forces the id and the kind, and says no year has to back it', () => {
    const q = withPick(ask, { id: 2, kind: 'film' }, 'Idiot.2022.mkv')
    assert.equal(q.tmdbId, 2)
    assert.equal(q.kind, 'film')
    assert.equal(q.picked, true)
    assert.equal(q.year, 2022)
    assert.deepEqual(q.languages, ['ta'])
})

test('a show pick keeps the episode, a film pick drops it, a name that asked nothing still gets a question', () => {
    const episode = { title: 'Harbour Notes', year: null, kind: 'show', tmdbId: null, series: true, languages: [], episode: 'S02E03' } as never
    assert.equal(withPick(episode, { id: 5, kind: 'show' }, 'x').episode, 'S02E03')
    assert.equal(withPick(episode, { id: 5, kind: 'show' }, 'x').series, true)
    const asFilm = withPick(episode, { id: 5, kind: 'film' }, 'x')
    assert.equal(asFilm.episode, null)
    assert.equal(asFilm.series, false)
    const none = withPick(null, { id: 7, kind: 'film' }, 'Some video')
    assert.equal(none.title, 'Some video')
    assert.equal(none.tmdbId, 7)
})
