// Which of TMDB's answers is the title a video's name asks for: core's MetaMatch, MetaPick and the
// year half of MetaConfidence, ported line for line (core/.../online/MetaModels.kt, MetaConfidence.kt),
// because the poster worker runs on Cloudflare and cannot load the Kotlin module the page's
// Telegram worker does. match.test.ts holds the cases of the apps' MetaPickTest and
// MetaConfidenceTest; a change to the Kotlin rules is a change here.

/** A title as TMDB's search lists it. */
export interface Candidate {
    id: string
    /** The title and the original title. */
    names: string[]
    year: number | null
    poster: string | null
    /** TMDB's `original_language`, ISO 639-1. */
    originalLanguage: string
    /** The overview in English (MetaInfo.overview); empty when TMDB has none. */
    overview?: string
    /** The wide picture's file (MetaInfo.backdropUrl), or null. */
    backdrop?: string | null
}

/** What a video's name asks (MetaQuery, as the page's rules read it: WebRules' PosterAsk). */
export interface Ask {
    title: string
    year: number | null
    kind: 'film' | 'show'
    tmdbId: number | null
    /** The name says an episode (S01E02, an air date). */
    series: boolean
    languages: string[]
    /**
     * The languages the chat's own title names (ChatLanguageHints, HX24), ISO 639-1, at most 4: a
     * weaker hint than `languages`, scored only between titles equal on words, year and language,
     * never a reason to accept one. Absent or empty for most chats.
     */
    hints?: string[]
    /** An IMDb id (`tt0111161`) the post's own words give (MetaLinks), resolved through TMDB's `find`; null for none. */
    imdbId?: string | null
    /**
     * The viewer picked this title for the video ("Choose the right poster"): its id is asked for,
     * and no year or episode has to back it, the viewer's word being the backing.
     */
    picked?: boolean
    /** The name reads like fansub anime (MetaQuery.anime): AniList is asked first for a show, after TMDB for a film. */
    anime?: boolean
}

/** Lower case, accents and punctuation gone, "&" as "and", a leading article dropped. */
export function normalise(title: string): string {
    const plain = title.toLowerCase().normalize('NFD').replace(/\p{M}+/gu, '')
    return plain
        .replaceAll('&', ' and ')
        .replace(/[^\p{L}\p{N}]+/gu, ' ')
        .trim()
        .replace(/^(the|a|an) /, '')
}

const words = (title: string) => new Set(normalise(title).split(' ').filter(Boolean))

/** Share of words the two titles have in common, 0 to 1. */
export function overlap(a: string, b: string): number {
    const x = words(a)
    const y = words(b)
    if (!x.size || !y.size) return 0
    let common = 0
    for (const w of x) if (y.has(w)) common++
    return common / (x.size + y.size - common)
}

/**
 * True when one of `names` is `wanted`: the same words, or most of them with the year agreeing
 * (within a year). For a show, a file's later year does not rule out the same words (a season's
 * year, not the premiere's).
 */
export function accepts(wanted: string, wantedYear: number | null, names: string[], year: number | null, show: boolean): boolean {
    const target = normalise(wanted)
    if (!target) return false
    const yearAgrees = wantedYear !== null && year !== null && Math.abs(wantedYear - year) <= 1
    const laterSeason = show && wantedYear !== null && year !== null && wantedYear > year
    if (wantedYear !== null && year !== null && !yearAgrees && !laterSeason) return false
    return names.filter((n) => n.trim()).some((name) => normalise(name) === target || (yearAgrees && overlap(wanted, name) >= 0.6))
}

// MetaPick's weights: the hint is 1 and every other step is 2 or more, so it decides only between
// titles equal on words, year and language.
const EXACT_WORDS = 16
const SAME_YEAR = 8
const NEAR_YEAR = 2
const LANGUAGE = 4
const HINT = 1

/** Higher is closer: the same words, then the year, then the language the file lists, then the chat's. */
export function score(ask: Ask, c: Candidate): number {
    const target = normalise(ask.title)
    let s = 0
    if (c.names.some((n) => n.trim() && normalise(n) === target)) s += EXACT_WORDS
    if (ask.year !== null && c.year !== null) {
        if (ask.year === c.year) s += SAME_YEAR
        else if (Math.abs(ask.year - c.year) <= 1) s += NEAR_YEAR
    }
    const original = c.originalLanguage.toLowerCase()
    if (original) {
        if (ask.languages.includes(original)) s += LANGUAGE
        if (ask.hints?.includes(original)) s += HINT
    }
    return s
}

/** The best of `candidates` that passes `accepts`; the provider's own order breaks a tie. */
export function best(ask: Ask, candidates: Candidate[], show: boolean): Candidate | null {
    let pick: Candidate | null = null
    let top = -1
    for (const c of candidates) {
        if (!accepts(ask.title, ask.year, c.names, c.year, show)) continue
        const s = score(ask, c)
        if (s > top) {
            top = s
            pick = c
        }
    }
    return pick
}

/**
 * Whether a match may stand for the video, on top of `accepts` (MetaConfidence.basis without the
 * runtime check): the year agrees, a later season's year on a show, or a show for a file that
 * names its episode. Otherwise the card keeps the video's own picture.
 */
export function trusted(ask: Ask, found: { kind: 'film' | 'show'; year: number | null }): boolean {
    if (ask.picked) return true
    if (ask.year !== null && found.year !== null) {
        const agrees = Math.abs(ask.year - found.year) <= 1
        const laterSeason = found.kind === 'show' && ask.series && ask.year > found.year
        if (agrees || laterSeason) return true
    }
    return found.kind === 'show' && ask.series
}

const MAX_HINTS = 4
const HINTS_RAW = 32

/**
 * The chat's language hints from the `h=` of a poster ask: ISO 639-1 codes (two lower case letters),
 * the first four distinct ones. Anything else (garbage, three letter codes, a huge string) is dropped,
 * and a string longer than a few codes is cut before it is read.
 */
export function parseHints(raw: string | null | undefined): string[] {
    if (!raw) return []
    const out: string[] = []
    for (const part of raw.slice(0, HINTS_RAW).split(',')) {
        const code = part.toLowerCase()
        if (/^[a-z]{2}$/.test(code) && !out.includes(code)) out.push(code)
        if (out.length === MAX_HINTS) break
    }
    return out
}
