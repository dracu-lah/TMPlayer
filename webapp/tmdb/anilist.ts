// AniList, the anime provider of the poster worker: core's AniListApi (search and extras) and
// MetaRules reading, ported for the worker (it runs on Cloudflare and cannot load the Kotlin
// module). The apps ask AniList first for a name that reads like fansub anime and TMDB after it
// (OnlineMetadata.resolve); the web does the same through handler.ts. AniList wants no key. Only
// the title leaves for AniList: no chat, file or account. A change to the Kotlin rules is a change
// here; anilist.test.ts holds the cases and feeds every reader garbage.
//
// AniList's pictures are full addresses; the page only ever names a file by a short path, so each
// is cut to "/al/<tail>" (cover, banner or character) and served by the worker's own image route
// from AniList's image host, the way TMDB's are.
import type { Candidate } from './match.ts'
import type { Extras, Person } from './extras.ts'
import { rating } from './extras.ts'
import { plain } from '../src/plain.ts'

export const BASE = 'https://graphql.anilist.co'
/** AniListApi.DEFAULT_WAIT_MS: how long asking stops after a throttle that names no time. */
export const DEFAULT_WAIT_MS = 60_000
const IMAGES = 'https://s4.anilist.co/file/anilistcdn'

/** AniListApi.QUERY: five titles by name, adult ones left out. */
export const SEARCH_QUERY =
    'query ($search: String) { Page(perPage: 5) { media(search: $search, type: ANIME, isAdult: false) { ' +
    'id format seasonYear startDate { year } title { romaji english native } synonyms ' +
    'description(asHtml: false) coverImage { large } bannerImage } } }'

/** AniListApi.EXTRAS_QUERY: genres, score, episode length, trailer and the main characters with their voices. */
export const EXTRAS_QUERY =
    'query ($id: Int) { Media(id: $id, type: ANIME) { idMal genres averageScore duration trailer { id site } ' +
    'characters(sort: [ROLE, RELEVANCE], perPage: 10) { edges { node { name { full } image { medium } } ' +
    'voiceActors(language: JAPANESE) { name { full } } } } } }'

/** MetaRules.CAST_LIMIT. */
const CAST_LIMIT = 10
/** At most this many names a title keeps (its own three and its synonyms), each cut short. */
const NAMES_MAX = 24
const NAME_MAX = 200
const OVERVIEW_MAX = 2_000

type Json = Record<string, any>
const object = (v: unknown): Json | null => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Json) : null)
const objects = (v: unknown): Json[] => (Array.isArray(v) ? v.map(object).filter((o): o is Json => o !== null) : [])
const text = (v: unknown, max = NAME_MAX): string => (typeof v === 'string' && v !== 'null' ? v.trim().slice(0, max) : '')

export { plain }

/** The tail of an AniList picture's address the worker serves ("cover/large/bx1-abc.jpg"), or null for any other address. */
export function imageTail(url: unknown): string | null {
    if (typeof url !== 'string') return null
    const m = /^https:\/\/s\d{1,2}\.anilist\.co\/file\/anilistcdn\/(?:media\/anime\/)?((?:cover|character)\/[a-zA-Z]{3,12}\/|banner\/)([A-Za-z0-9_-]{2,80}\.(?:jpg|jpeg|png|webp))$/.exec(url)
    return m ? m[1] + m[2] : null
}

/** What the page is given for a picture: "/al/<tail>", or null. */
export const imagePath = (url: unknown): string | null => {
    const tail = imageTail(url)
    return tail ? `/al/${tail}` : null
}

/** The address a page's "/al/<tail>" stands for on AniList's image host, or null for a tail that is not one of ours. */
export function imageAddress(tail: string): string | null {
    const m = /^(cover\/[a-zA-Z]{3,12}\/|character\/[a-zA-Z]{3,12}\/|banner\/)([A-Za-z0-9_-]{2,80}\.(?:jpg|jpeg|png|webp))$/.exec(tail)
    if (!m) return null
    return `${IMAGES}/${m[1].startsWith('banner') || m[1].startsWith('cover') ? 'media/anime/' : ''}${tail}`
}

/** A title as AniList's search lists it; `format` is "MOVIE", "TV", "OVA"... */
export interface AniCandidate extends Candidate {
    format: string
}

/**
 * AniListApi.search's reading: the titles of `Page.media`. An entry with no whole-number id is
 * skipped; anything else missing or of the wrong type reads as empty. Never throws.
 */
export function candidates(json: unknown): AniCandidate[] {
    const media = objects(object(object(object(json)?.data)?.Page)?.media)
    const out: AniCandidate[] = []
    for (const m of media) {
        if (!Number.isInteger(m.id) || m.id <= 0) continue
        const title = object(m.title) ?? {}
        const synonyms = Array.isArray(m.synonyms) ? m.synonyms.slice(0, NAMES_MAX) : []
        const names = [title.english, title.romaji, title.native, ...synonyms].map((n) => text(n)).filter(Boolean).slice(0, NAMES_MAX)
        const year = [m.seasonYear, object(m.startDate)?.year].map(Number).find((y) => Number.isInteger(y) && y > 1800 && y < 2200) ?? null
        out.push({
            id: String(m.id),
            names,
            year,
            poster: imagePath(object(m.coverImage)?.large),
            originalLanguage: 'ja',
            overview: plain(text(m.description, OVERVIEW_MAX * 2)).slice(0, OVERVIEW_MAX),
            backdrop: imagePath(m.bannerImage),
            format: text(m.format, 20),
        })
    }
    return out
}

/**
 * AniListApi.extras' reading: genres, episode length, the one score, the main characters with
 * their Japanese voice, the trailer. AniList leaves the average out until enough people scored a
 * title (MetaRules.rating); a trailer counts only on YouTube. Null for an answer with no media.
 */
export function extrasOf(json: unknown): Extras | null {
    const media = object(object(object(json)?.data)?.Media)
    if (!media) return null
    const genres = (Array.isArray(media.genres) ? media.genres : []).map((g) => text(g)).filter(Boolean).slice(0, 12)
    const cast = objects(object(media.characters)?.edges)
        .slice(0, CAST_LIMIT)
        .map((edge): Person | null => {
            const node = object(edge.node)
            const name = text(object(node?.name)?.full)
            if (!name) return null
            const voice = text(object(object(objects(edge.voiceActors)[0])?.name)?.full)
            return { name, role: voice, photo: imagePath(object(node?.image)?.medium) }
        })
        .filter((p): p is Person => p !== null)
    const trailerOf = object(media.trailer)
    const clip = text(trailerOf?.id, 40)
    const score = Number(media.averageScore)
    const minutes = Number(media.duration)
    return {
        found: true,
        genres,
        runtime: Number.isInteger(minutes) && minutes > 0 && minutes < 100_000 ? minutes : null,
        seasons: null,
        rating: Number.isFinite(score) && score > 0 ? rating(Math.min(100, score) / 10, 25) : null,
        certification: null,
        directors: [],
        creators: [],
        cast,
        trailer: text(trailerOf?.site, 20).toLowerCase() === 'youtube' && /^[A-Za-z0-9_-]{6,20}$/.test(clip) ? clip : null,
        ended: false,
        seasonList: [],
        next: null,
        imdb: null,
        logo: null,
    }
}
