// The providers an anime's episode guide reads, one reader each, over what they answer: core's
// AniListApi.episodes, TmdbApi.showGuide / episodeGroup / seasons, KitsuApi and JikanApi
// (core/.../online/MetaApis.kt), ported for the poster worker. Nothing a provider sends is trusted:
// a wrong type, a missing field or an absurd number skips that entry and keeps the rest, and no
// reader throws (anime-sources.test.ts feeds each truncated, wrong-typed, huge and random input).
import type { SeasonStop } from './extras.ts'
import { MAX_EPISODES, MAX_NUMBER, MAX_SEASONS, dateOf, streamingPiece, type GroupEpisode, type ListedEpisode, type Next, type Piece } from '../src/animeGuide.ts'

/** KitsuApi.BASE, PAGE and JikanApi.BASE: how the worker reaches them, and how many episodes a page holds. */
export const KITSU = 'https://kitsu.io/api/edge'
export const KITSU_PAGE = 20
export const JIKAN = 'https://api.jikan.moe/v4'
export const JIKAN_PAGE = 100
/** KitsuApi.MAX_PAGES and JikanApi.MAX_PAGES: 3,000 episodes. */
export const KITSU_MAX_PAGES = MAX_EPISODES / KITSU_PAGE
export const JIKAN_MAX_PAGES = MAX_EPISODES / JIKAN_PAGE
/** TmdbApi.APPEND_LIMIT: seasons asked for in one call. */
export const APPEND_LIMIT = 20

/** AniListApi.EPISODES_QUERY, with the show's names beside (the apps take them from the match they hold). */
export const EPISODES_QUERY =
    'query ($id: Int) { Media(id: $id, type: ANIME) { idMal episodes nextAiringEpisode { episode airingAt } ' +
    'streamingEpisodes { title } title { romaji english native } synonyms } }'

type Json = Record<string, any>
const object = (v: unknown): Json | null => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Json) : null)
const objects = (v: unknown): Json[] => (Array.isArray(v) ? v.map(object).filter((o): o is Json => o !== null) : [])
const text = (v: unknown, max = 600): string => (typeof v === 'string' && v !== 'null' ? v.trim().slice(0, max) : '')
const whole = (v: unknown, lo: number, hi: number): number | null => {
    const n = typeof v === 'string' && v.trim() !== '' ? Number(v) : v
    return typeof n === 'number' && Number.isInteger(n) && n >= lo && n <= hi ? n : null
}
/** A date `2026-10-14` from a longer stamp ("2026-10-14T00:00:00Z"), else null. */
const day = (v: unknown): string | null => {
    const s = typeof v === 'string' ? v.slice(0, 10) : ''
    return /^\d{4}-\d{2}-\d{2}$/.test(s) ? s : null
}
const STILL = /^\/[A-Za-z0-9_-]{8,64}\.(jpg|png|webp)$/
const still = (v: unknown): string | null => (typeof v === 'string' && STILL.test(v) ? v : null)

/** What AniList knows of one run's episodes (AniListRun), and the names the show is found elsewhere by. */
export interface Run {
    episodes: number | null
    malId: string | null
    next: Next | null
    streaming: Piece[]
    names: string[]
}

/** AniListApi.episodes' reading. Null for an answer with no media. */
export function runOf(json: unknown): Run | null {
    const media = object(object(object(json)?.data)?.Media)
    if (!media) return null
    const n = object(media.nextAiringEpisode)
    const episode = whole(n?.episode, 1, MAX_NUMBER)
    const airDate = typeof n?.airingAt === 'number' ? dateOf(n.airingAt) : null
    const title = object(media.title) ?? {}
    const names = [title.english, title.romaji, title.native, ...(Array.isArray(media.synonyms) ? media.synonyms.slice(0, 24) : [])]
        .map((x) => text(x, 200))
        .filter(Boolean)
    const mal = whole(media.idMal, 1, 999_999_999)
    return {
        episodes: whole(media.episodes, 1, MAX_EPISODES),
        malId: mal === null ? null : String(mal),
        next: episode !== null && airDate ? { season: 1, episode, airDate } : null,
        streaming: objects(media.streamingEpisodes).slice(0, MAX_EPISODES).map((e) => streamingPiece(text(e.title, 700))).filter((p): p is Piece => p !== null),
        names: [...new Set(names)],
    }
}

/** A TMDB show's seasons with their counts (specials left in; the guide counts only the numbered ones) and its episode groups. */
export interface ShowGuide {
    seasons: SeasonStop[]
    groups: { id: string; type: number; episodes: number }[]
}

/** TmdbApi.showGuide's reading. */
export function showGuideOf(json: unknown): ShowGuide {
    const o = object(json)
    const seasons: SeasonStop[] = []
    for (const s of objects(o?.seasons).slice(0, MAX_SEASONS * 2)) {
        const number = whole(s.season_number, 0, MAX_SEASONS)
        if (number === null) continue
        seasons.push({ number, episodes: whole(s.episode_count, 0, MAX_EPISODES) ?? 0, airDate: day(s.air_date) })
    }
    const groups = objects(object(o?.episode_groups)?.results)
        .slice(0, 50)
        .map((g) => ({ id: text(g.id, 40), type: whole(g.type, 0, 20) ?? 0, episodes: whole(g.episode_count, 0, MAX_EPISODES) ?? 0 }))
        .filter((g) => /^[A-Za-z0-9]{6,40}$/.test(g.id))
    return { seasons, groups }
}

/** TmdbApi.seasonEpisodes: one season's episodes in order, each with its name, picture file, date and length. */
export function seasonOf(json: unknown): ListedEpisode[] {
    return objects(object(json)?.episodes)
        .slice(0, MAX_EPISODES)
        .flatMap((e) => {
            const number = whole(e.episode_number, 1, MAX_NUMBER)
            if (number === null) return []
            return [{ number, name: text(e.name, 400), overview: text(e.overview, 4000), still: still(e.still_path), airDate: day(e.air_date), runtime: whole(e.runtime, 1, 1000) }]
        })
        .sort((a, b) => a.number - b.number)
}

/** TmdbApi.seasons' reading: the `season/N` pages appended to the show's own. */
export function seasonsOf(json: unknown, numbers: readonly number[]): Map<number, ListedEpisode[]> {
    const o = object(json)
    return new Map(numbers.map((n) => [n, seasonOf(o?.[`season/${n}`])]))
}

/** TmdbApi.episodeGroup's reading: each episode with the season and number TMDB files it under. */
export function groupOf(json: unknown): GroupEpisode[] {
    const out: GroupEpisode[] = []
    objects(object(json)?.groups).slice(0, 400).forEach((group, gi) => {
        const groupOrder = whole(group.order, -1_000_000, 1_000_000) ?? gi
        objects(group.episodes).slice(0, MAX_EPISODES).forEach((e, ei) => {
            const season = whole(e.season_number, 0, MAX_SEASONS)
            const episode = whole(e.episode_number, 1, MAX_NUMBER)
            if (season === null || episode === null || out.length >= MAX_EPISODES * 2) return
            out.push({
                groupOrder,
                order: whole(e.order, -1_000_000, 1_000_000) ?? ei,
                season,
                episode,
                piece: { absolute: 0, name: text(e.name, 400), overview: text(e.overview, 4000), still: still(e.still_path), airDate: day(e.air_date), runtime: whole(e.runtime, 1, 1000) },
            })
        })
    })
    return out
}

/** KitsuApi.idFor's reading: Kitsu's own id for the anime the mapping names, or null. */
export function kitsuIdOf(json: unknown): string | null {
    for (const mapping of objects(object(json)?.data)) {
        const item = object(object(object(mapping.relationships)?.item)?.data)
        const id = item?.type === 'anime' ? String(item.id ?? '') : ''
        if (/^\d{1,12}$/.test(id)) return id
    }
    return null
}

/** One page of a list, and whether another follows. */
export interface Page {
    list: ListedEpisode[]
    more: boolean
}

/** KitsuApi.episodes' reading. A picture is dropped (Kitsu's host is not one the page draws from). */
export function kitsuPageOf(json: unknown): Page {
    const o = object(json)
    const list = objects(o?.data).flatMap((e): ListedEpisode[] => {
        const a = object(e.attributes)
        const number = whole(a?.number, 1, MAX_NUMBER)
        if (!a || number === null) return []
        const titles = object(a.titles)
        return [
            {
                number,
                name: text(titles?.en_us, 400) || text(titles?.en, 400) || text(a.canonicalTitle, 400),
                overview: text(a.synopsis, 4000),
                still: null,
                airDate: day(a.airdate),
                runtime: whole(a.length, 1, 1000),
            },
        ]
    })
    return { list, more: typeof object(o?.links)?.next === 'string' && (object(o?.links)!.next as string).trim() !== '' && list.length > 0 }
}

/** JikanApi.episodes' reading. */
export function jikanPageOf(json: unknown): Page {
    const o = object(json)
    const list = objects(o?.data).flatMap((e): ListedEpisode[] => {
        const number = whole(e.mal_id, 1, MAX_NUMBER)
        if (number === null) return []
        return [{ number, name: text(e.title, 400), overview: '', still: null, airDate: day(e.aired), runtime: null }]
    })
    return { list, more: object(o?.pagination)?.has_next_page === true && list.length > 0 }
}
