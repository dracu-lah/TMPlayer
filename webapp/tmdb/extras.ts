// The detail page's second helping (MetaExtras), read from TMDB's own page for a title with its credits,
// videos and age ratings appended: the facts line (genres, length, seasons, rating, age rating, director
// or creators), the cast and a trailer. core's MetaApis.tmdbExtras and MetaRules, ported for the
// worker (it runs on Cloudflare and cannot load the Kotlin module); extras.test.ts holds the cases of
// the apps' MetaExtrasTest. A change to the Kotlin rules is a change here. Only fields MetaExtras has.
// Also SeasonSplit (a show TMDB lists as one season that an episode group splits, Blue Box: 37 as
// 25 + 12), the part of the apps' season guide the worker has to do: extras.test.ts holds the cases of
// the apps' SeasonSplitTest and the garbage of GarbageInputTest.
import { MAX_EPISODES, MAX_NUMBER, MAX_SEASONS, placeholder, type GroupEpisode, type ListedEpisode } from '../src/animeGuide.ts'

/** MetaRules.MIN_VOTES, CAST_LIMIT. */
const MIN_VOTES = 25
const CAST_LIMIT = 10

export interface Person {
    name: string
    /** Who they play. */
    role: string
    /** TMDB's profile file, "/abc.jpg", or null. */
    photo: string | null
}

/** One season as TMDB lists it (MetaSeason): its number, how many episodes, when it began. */
export interface SeasonStop {
    number: number
    episodes: number
    airDate: string | null
}

export interface Extras {
    found: true
    genres: string[]
    /** A film's length, or a show's usual episode length, in minutes. */
    runtime: number | null
    seasons: number | null
    /** Out of ten, and only once enough people have voted. */
    rating: number | null
    certification: string | null
    directors: string[]
    creators: string[]
    cast: Person[]
    /** YouTube's video id. */
    trailer: string | null
    ended: boolean
    /** A show's seasons, specials left out, so the series page can list the ones the chats lack (MetaExtras.seasonList). */
    seasonList: SeasonStop[]
    /** The next episode to air, where TMDB knows one (MetaExtras.nextEpisode). */
    next: { season: number; episode: number; airDate: string } | null
    /** IMDb's id ("tt0903747"), for a later look at its scores. */
    imdb: string | null
    /** The title logo's file (MetaRules.logo): PNG, wide, English or wordless; null for none. */
    logo: string | null
    /** The episode's own name, words and picture, when asked for (a show's episode). */
    episode?: { name: string; overview: string; still: string | null }
    /** The TMDB episode group whose split `seasonList` and `next` follow (SeasonSplit); null for every other title. */
    seasonGroup?: string | null
    /** TMDB's own seasons and next episode, kept beside a split for a chat numbered TMDB's way. */
    listedSeasons?: SeasonStop[]
    listedNext?: { season: number; episode: number; airDate: string } | null
    /** The answer was made knowing the show's episode groups: absent in one kept before (the page asks again). */
    groupsRead?: true
}

type Json = Record<string, any>
const objects = (list: unknown): Json[] => (Array.isArray(list) ? list.filter((o) => o && typeof o === 'object') : [])
const strings = (list: Json[], field: string) => list.map((o) => String(o[field] ?? '').trim()).filter(Boolean)

/** MetaRules.rating: the average to one decimal, or null when too few voted. */
export function rating(average: number, votes: number): number | null {
    if (votes < MIN_VOTES || !(average > 0)) return null
    return Math.round(Math.min(10, average) * 10) / 10
}

/** MetaRules.certification: the viewer's region's, else the US one, else none. */
export function certification(byRegion: Record<string, string>, region: string): string | null {
    const clean = Object.fromEntries(Object.entries(byRegion).filter(([, v]) => v.trim()).map(([k, v]) => [k.toUpperCase(), v]))
    return clean[region.toUpperCase()] ?? clean.US ?? null
}

/** MetaRules.RELEASE_ORDER: theatrical, then digital, physical, television, limited, premiere. */
const RELEASE_ORDER = [3, 4, 5, 6, 2, 1]

/** MetaRules.filmCertifications: a film's age rating per region from TMDB's release dates. */
export function filmCertifications(results: Json[]): Record<string, string> {
    const out: Record<string, string> = {}
    for (const r of results) {
        const releases = objects(r.release_dates)
            .map((x) => [String(x.certification ?? '').trim(), Number(x.type)] as const)
            .filter(([c]) => c)
        if (!releases.length) continue
        const at = (type: number) => {
            const i = RELEASE_ORDER.indexOf(type)
            return i < 0 ? RELEASE_ORDER.length : i
        }
        out[String(r.iso_3166_1 ?? '')] = releases.reduce((a, b) => (at(b[1]) < at(a[1]) ? b : a))[0]
    }
    return out
}

/**
 * MetaRules.trailer: YouTube only; a trailer before a teaser; an official one first; then English
 * (the worker asks in English), then any; the newest of what is left.
 */
export function trailer(videos: Json[]): string | null {
    const ok = videos.filter((v) => String(v.site ?? '').toLowerCase() === 'youtube' && v.key && /^(trailer|teaser)$/i.test(String(v.type ?? '')))
    const rank = (v: Json) => [/^trailer$/i.test(String(v.type)) ? 0 : 1, v.official ? 0 : 1, String(v.iso_639_1 ?? '').toLowerCase() === 'en' ? 1 : 2] as const
    ok.sort((a, b) => {
        const x = rank(a)
        const y = rank(b)
        for (let i = 0; i < 3; i++) if (x[i] !== y[i]) return x[i] - y[i]
        return String(b.published_at ?? '').localeCompare(String(a.published_at ?? ''))
    })
    return ok[0] ? String(ok[0].key) : null
}

/** A profile or still file as the page asks it by: "/abc.jpg" only. */
const file = (v: unknown): string | null => (typeof v === 'string' && /^\/[A-Za-z0-9_-]{8,64}\.(jpg|png|webp)$/.test(v) ? v : null)

/** MetaRules.LOGO_MIN_ASPECT and LOGO_MIN_WIDTH: a narrower logo is a stacked badge, a smaller one too soft. */
const LOGO_MIN_ASPECT = 1.2
const LOGO_MIN_WIDTH = 200

/**
 * MetaRules.logo: the title logo to draw from TMDB's `images.logos`, English before one with no
 * words (the web is asked in English), PNG only, wide rather than tall, then by votes and width.
 */
export function logo(logos: Json[]): string | null {
    const tier = (l: Json) => {
        const lang = typeof l.iso_639_1 === 'string' && l.iso_639_1 && l.iso_639_1 !== 'xx' ? l.iso_639_1.toLowerCase() : null
        return lang === 'en' ? 0 : lang === null ? 1 : null
    }
    const aspect = (l: Json) => Number(l.aspect_ratio) || (Number(l.height) > 0 ? Number(l.width) / Number(l.height) : 0)
    const picked = logos
        .filter((l) => !!l && typeof l === 'object' && typeof l.file_path === 'string' && /^\/[A-Za-z0-9_-]{8,64}\.png$/.test(l.file_path) && aspect(l) >= LOGO_MIN_ASPECT && Number(l.width) >= LOGO_MIN_WIDTH)
        .map((l) => ({ l, tier: tier(l) }))
        .filter((x): x is { l: Json; tier: number } => x.tier !== null)
        .sort((a, b) => a.tier - b.tier || Number(b.l.vote_average ?? 0) - Number(a.l.vote_average ?? 0) || Number(b.l.vote_count ?? 0) - Number(a.l.vote_count ?? 0) || Number(b.l.width ?? 0) - Number(a.l.width ?? 0))
    return picked[0]?.l.file_path ?? null
}

/** MetaApis.tmdbExtras. */
/** MetaApis.tmdbExtras' season list: season 0 (TMDB's specials) left out, in order; garbage skipped. */
export function seasonList(list: unknown): SeasonStop[] {
    return objects(list)
        .map((o) => ({ number: Math.trunc(Number(o.season_number)), episodes: Math.trunc(Number(o.episode_count)) || 0, airDate: String(o.air_date ?? '').trim() || null }))
        .filter((o) => Number.isFinite(o.number) && o.number > 0 && o.number <= 200)
        .sort((a, b) => a.number - b.number)
}

/** The next episode to air, only when it has a day and is in a numbered season. */
export function nextEpisode(o: unknown): Extras['next'] {
    const j = o && typeof o === 'object' ? (o as Json) : null
    if (!j) return null
    const season = Math.trunc(Number(j.season_number))
    const episode = Math.trunc(Number(j.episode_number))
    const airDate = String(j.air_date ?? '').trim()
    return season > 0 && episode > 0 && airDate ? { season, episode, airDate } : null
}

export function extras(json: Json, film: boolean, region: string): Extras {
    const genres = strings(objects(json.genres), 'name')
    const usual = (Array.isArray(json.episode_run_time) ? json.episode_run_time : []).map(Number).find((n: number) => n > 0)
    const runtime = film ? Number(json.runtime) || null : (usual ?? (Number(json.last_episode_to_air?.runtime) || null))
    const cert = film
        ? certification(filmCertifications(objects(json.release_dates?.results)), region)
        : certification(Object.fromEntries(objects(json.content_ratings?.results).map((r) => [String(r.iso_3166_1 ?? ''), String(r.rating ?? '').trim()])), region)
    const credits = film ? json.credits : json.aggregate_credits
    const cast = objects(credits?.cast)
        .sort((a, b) => (film ? Number(a.order ?? 1e9) - Number(b.order ?? 1e9) : Number(b.total_episode_count ?? 0) - Number(a.total_episode_count ?? 0)))
        .slice(0, CAST_LIMIT)
        .map((p): Person => {
            const roles = objects(p.roles)
            const role = film ? String(p.character ?? '') : String(roles.reduce<Json | null>((a, b) => (!a || Number(b.episode_count ?? 0) > Number(a.episode_count ?? 0) ? b : a), null)?.character ?? '')
            return { name: String(p.name ?? '').trim(), role: role.trim(), photo: file(p.profile_path) }
        })
        .filter((p) => p.name)
    const directors = film ? [...new Set(strings(objects(credits?.crew).filter((c) => c.job === 'Director'), 'name'))].slice(0, 3) : []
    const creators = film ? [] : strings(objects(json.created_by), 'name').slice(0, 3)
    const imdb = String(json.imdb_id ?? json.external_ids?.imdb_id ?? '').trim()
    return {
        found: true,
        genres,
        runtime,
        seasons: film ? null : Number(json.number_of_seasons) || null,
        rating: rating(Number(json.vote_average) || 0, Number(json.vote_count) || 0),
        certification: cert,
        directors,
        creators,
        cast,
        trailer: trailer(objects(json.videos?.results)),
        ended: !film && ['Ended', 'Canceled'].includes(String(json.status ?? '')),
        seasonList: film ? [] : seasonList(json.seasons),
        next: film ? null : nextEpisode(json.next_episode_to_air),
        imdb: /^tt\d+$/.test(imdb) ? imdb : null,
        logo: logo(objects(json.images?.logos)),
        seasonGroup: null,
        listedSeasons: [],
        listedNext: null,
        groupsRead: true,
    }
}

// ---- the season split (SeasonSplit) ----------------------------------------------------------------

/** TmdbApi.MAX_GROUPS and MAX_GROUP_NAME: more groups than any show has, and the longest name kept. */
const MAX_GROUPS = 100
const MAX_GROUP_NAME = 200
/** A group's id as TMDB writes it (24 hex), and as the worker may pass it on. */
export const GROUP_ID = /^[A-Za-z0-9_-]{4,64}$/
/** The share of a show's episodes (tenths) a group must hold to stand for the whole show. */
const SHARE_TENTHS = 9

/** One TMDB episode group: its id, its type (2 is "Absolute order"), how many episodes it holds, its name and how many groups (seasons, cours, discs) it splits them into. */
export interface EpisodeGroup {
    id: string
    type: number
    episodes: number
    name: string
    groups: number
}

const whole = (v: unknown, lo: number, hi: number): number | null => {
    const n = typeof v === 'number' ? v : typeof v === 'string' && v.trim() ? Number(v) : NaN
    return Number.isFinite(n) && Math.trunc(n) >= lo && Math.trunc(n) <= hi ? Math.trunc(n) : null
}

/** TmdbApi.episodeGroups: the `episode_groups.results` of a show's page, the ones with a sane id; garbage skipped, the rest capped. */
export function episodeGroups(json: Json): EpisodeGroup[] {
    const results = json?.episode_groups && typeof json.episode_groups === 'object' ? json.episode_groups.results : null
    return objects(results)
        .slice(0, MAX_GROUPS * 2)
        .flatMap((g): EpisodeGroup[] => {
            const id = typeof g.id === 'string' ? g.id.trim() : ''
            if (!GROUP_ID.test(id)) return []
            return [
                {
                    id,
                    type: whole(g.type, 0, 100) ?? 0,
                    episodes: whole(g.episode_count, 0, 1e7) ?? 0,
                    name: typeof g.name === 'string' ? g.name.trim().slice(0, MAX_GROUP_NAME) : '',
                    groups: whole(g.group_count, 0, 1e7) ?? 0,
                },
            ]
        })
        .slice(0, MAX_GROUPS)
}

/**
 * SeasonSplit.choose: the group that splits `seasons` (the show's page) into seasons, or null. Only for a
 * show TMDB lists as exactly one numbered season; a group stands for it when it holds at least 90% of that
 * season's episodes (and not more than twice as many) in more groups than one. "Absolute order" groups
 * are left out. The fewest groups wins, then a name starting with "Season", then the most episodes.
 */
export function chooseGroup(seasons: readonly SeasonStop[], groups: readonly EpisodeGroup[]): EpisodeGroup | null {
    const numbered = [...new Map(seasons.filter((s) => s.number > 0).map((s) => [s.number, s])).values()]
    if (numbered.length !== 1) return null
    const total = numbered.reduce((n, s) => n + Math.min(Math.max(s.episodes, 0), MAX_EPISODES), 0)
    if (total < 2) return null
    const least = Math.ceil((total * SHARE_TENTHS) / 10)
    const seasonFirst = (g: EpisodeGroup) => (g.name.trim().toLowerCase().startsWith('season') ? 0 : 1)
    return (
        groups
            .slice(0, MAX_GROUPS)
            .filter((g) => g.type !== 2 && g.id && g.episodes >= least && g.episodes <= total * 2 && g.groups >= numbered.length + 1 && g.groups <= MAX_SEASONS)
            .sort((a, b) => a.groups - b.groups || seasonFirst(a) - seasonFirst(b) || b.episodes - a.episodes || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0))[0] ?? null
    )
}

/** A show's seasons as buildSplit makes them from one TMDB episode group. */
export interface Split {
    group: string
    seasons: SeasonStop[]
    /** Each season's episodes, numbered from one. */
    episodes: Map<number, ListedEpisode[]>
    /** Where each of TMDB's own episodes ("season:episode") landed: the split's season and number. */
    places: Map<string, [number, number]>
}

const placeKey = (season: number, episode: number) => `${season}:${episode}`

/**
 * SeasonSplit.build: the group's seasons, each with its episodes numbered from one in the group's order.
 * Specials (TMDB's season 0) are dropped, an episode the group files twice is taken once, and an
 * untitled one TMDB calls "Episode 32" is no name in season 2 where it is episode 7. Null when fewer
 * than two seasons come out, or when the group holds less than 90% of the `listed` episodes.
 */
export function buildSplit(group: string, episodes: readonly GroupEpisode[], listed: readonly SeasonStop[]): Split | null {
    const total = listed.filter((s) => s.number > 0).reduce((n, s) => n + Math.min(Math.max(s.episodes, 0), MAX_EPISODES), 0)
    const sorted = episodes
        .slice(0, MAX_EPISODES * 2)
        .filter((e) => e && e.piece && e.season > 0 && e.episode >= 1 && e.episode <= MAX_NUMBER)
        .sort((a, b) => a.groupOrder - b.groupOrder || a.order - b.order)
    const seen = new Set<string>()
    const byGroup = new Map<number, GroupEpisode[]>()
    for (const e of sorted) {
        if (seen.has(placeKey(e.season, e.episode))) continue
        seen.add(placeKey(e.season, e.episode))
        const list = byGroup.get(e.groupOrder)
        if (list) list.push(e)
        else byGroup.set(e.groupOrder, [e])
    }
    const groups = [...byGroup.values()].map((l) => l.slice(0, MAX_EPISODES)).filter((l) => l.length).slice(0, MAX_SEASONS)
    if (groups.length < 2) return null
    if (groups.reduce((n, l) => n + l.length, 0) * 10 < total * SHARE_TENTHS) return null
    const places = new Map<string, [number, number]>()
    const bySeason = new Map<number, ListedEpisode[]>()
    const seasons = groups.map((list, gi): SeasonStop => {
        const season = gi + 1
        bySeason.set(
            season,
            list.map((e, i) => {
                places.set(placeKey(e.season, e.episode), [season, i + 1])
                const name = (e.piece.name ?? '').trim()
                return {
                    number: i + 1,
                    // TMDB's "Episode 32" for an untitled one numbers it in the show's own count, not the season's.
                    name: placeholder(name, e.episode) ? '' : name,
                    overview: e.piece.overview ?? '',
                    still: e.piece.still ?? null,
                    airDate: e.piece.airDate ?? null,
                    runtime: e.piece.runtime ?? null,
                }
            }),
        )
        const first = list.map((e) => e.piece.airDate).filter((d): d is string => !!d).sort()[0] ?? null
        return { number: season, episodes: list.length, airDate: first }
    })
    return { group, seasons, episodes: bySeason, places }
}

/**
 * Split.next: TMDB's next episode (numbered its way) in the split's numbering. One the group has not
 * filed yet but that follows the last episode it has is counted on in the last season; any other the
 * split cannot place is null rather than labelled wrong.
 */
export function splitNext(split: Split, airing: Extras['next']): Extras['next'] {
    if (!airing) return null
    const at = split.places.get(placeKey(airing.season, airing.episode))
    if (at) return { season: at[0], episode: at[1], airDate: airing.airDate }
    const last = split.seasons[split.seasons.length - 1]
    if (!last) return null
    let lastListed = 0
    for (const key of split.places.keys()) {
        const [s, e] = key.split(':').map(Number)
        if (s === airing.season && e > lastListed) lastListed = e
    }
    const home = split.places.get(placeKey(airing.season, lastListed))
    if (!lastListed || airing.episode <= lastListed || home?.[0] !== last.number) return null
    const number = last.episodes + (airing.episode - lastListed)
    return number >= 1 && number <= MAX_NUMBER ? { season: last.number, episode: number, airDate: airing.airDate } : null
}

/** Split.applyTo: `found` (TMDB's, of the show this split is of) with the split's seasons, TMDB's own kept beside. */
export function applySplit(found: Extras, split: Split): Extras {
    return { ...found, seasons: split.seasons.length, seasonList: split.seasons, next: splitNext(split, found.next), seasonGroup: split.group, listedSeasons: found.seasonList, listedNext: found.next }
}

/** A split season's episode `number`, as the page about it asks (the episode's own name, words and picture). */
export function splitEpisode(split: Split, season: number, number: number): Extras['episode'] | undefined {
    const e = split.episodes.get(season)?.find((x) => x.number === number)
    return e ? { name: e.name, overview: e.overview, still: e.still } : undefined
}
