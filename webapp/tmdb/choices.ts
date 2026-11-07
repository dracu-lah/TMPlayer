// "Choose the right poster": the titles TMDB has that a video's name could mean, merged and ranked.
// core's PosterChoiceRules.merge, ported line for line (core/.../online/PosterChoices.kt); the
// cases are in choices.test.ts. The worker's /tmdb/search asks TMDB (handler.ts) and hands the lists here.
import { score, type Ask, type Candidate } from './match.ts'

/** PosterChoiceRules.LIMIT and OVERVIEW_MAX. */
export const LIMIT = 10
export const OVERVIEW_MAX = 140

/** One title offered, as the page draws it. */
export interface Choice {
    id: number
    kind: 'film' | 'show'
    title: string
    year: number | null
    /** TMDB's `original_language`, ISO 639-1; empty when unknown. */
    language: string
    overview: string
    /** The poster's file ("/abc.jpg"), or null. */
    poster: string | null
}

/**
 * `lists` (each the answer of one search, films or shows, with the year or without it) merged into
 * one: a title once however many searches found it, the closest to `ask` first (score: the name,
 * then the year, then a language the file's name lists), a tie broken by the kind the file looks
 * like, then by TMDB's own order. At most `LIMIT`.
 */
export function mergeChoices(ask: Ask, lists: { film: boolean; candidates: Candidate[] }[]): Choice[] {
    interface Entry {
        film: boolean
        c: Candidate
        order: number
    }
    const seen = new Set<string>()
    const entries: Entry[] = []
    let order = 0
    for (const { film, candidates } of lists) {
        for (const c of candidates) {
            const id = Number(c.id)
            if (!Number.isInteger(id) || id <= 0 || c.names.every((n) => !n.trim())) continue
            if (seen.has(`${film ? 'f' : 's'}${id}`)) continue
            seen.add(`${film ? 'f' : 's'}${id}`)
            entries.push({ film, c, order: order++ })
        }
    }
    const prefersFilm = ask.kind === 'film'
    entries.sort((a, b) => score(ask, b.c) - score(ask, a.c) || Number(b.film === prefersFilm) - Number(a.film === prefersFilm) || a.order - b.order)
    return entries.slice(0, LIMIT).map(({ film, c }) => ({
        id: Number(c.id),
        kind: film ? 'film' : 'show',
        title: c.names.find((n) => n.trim()) ?? '',
        year: c.year,
        language: c.originalLanguage.toLowerCase().slice(0, 3),
        overview: (c.overview ?? '').trim().replace(/\s+/g, ' ').slice(0, OVERVIEW_MAX),
        poster: c.poster,
    }))
}
