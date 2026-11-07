// The line of facts under a carousel page's name, as the apps' featuredFactsLine writes it
// ("Film  ·  2026  ·  Drama, Thriller  ·  2h 02m", "Series  ·  2024  ·  Crime  ·  3 seasons"):
// the kind, the year, up to two genres, and a film's length or a show's seasons. Pure, so it runs
// under `node --test`; the words come in as functions so it holds no strings of its own.

export interface FactsInput {
    isShow: boolean
    year?: number | null
    genres?: readonly string[] | null
    /** TMDB's runtime of a film in minutes, else the file's own length in seconds is used. */
    runtimeMin?: number | null
    durationSec?: number | null
    /** TMDB's season count of a show, else the number the chat holds (one season says nothing). */
    seasons?: number | null
    chatSeasons?: number | null
}

export interface FactsWords {
    film: string
    series: string
    length: (seconds: number) => string
    seasons: (count: number) => string
}

export const FACTS_SEPARATOR = '  ·  '

export function featuredFactsLine(f: FactsInput, w: FactsWords): string {
    const genres = (f.genres ?? [])
        .filter((g) => !!g?.trim())
        .slice(0, 2)
        .join(', ')
    let length = ''
    if (f.isShow) {
        const n = f.seasons && f.seasons > 0 ? f.seasons : f.chatSeasons && f.chatSeasons > 1 ? f.chatSeasons : 0
        if (n) length = w.seasons(n)
    } else {
        const sec = f.runtimeMin && f.runtimeMin > 0 ? f.runtimeMin * 60 : f.durationSec && f.durationSec > 0 ? f.durationSec : 0
        if (sec) length = w.length(sec)
    }
    return [f.isShow ? w.series : w.film, f.year && f.year > 0 ? String(f.year) : '', genres, length].filter(Boolean).join(FACTS_SEPARATOR)
}
