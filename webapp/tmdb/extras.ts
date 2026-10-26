// The detail page's second helping (MetaExtras), read from TMDB's own page for a title with its credits,
// videos and age ratings appended: the facts line (genres, length, seasons, rating, age rating, director
// or creators), the cast and a trailer. core's MetaApis.tmdbExtras and MetaRules, ported for the
// worker (it runs on Cloudflare and cannot load the Kotlin module); extras.test.ts holds the cases of
// the apps' MetaExtrasTest. A change to the Kotlin rules is a change here. Only fields MetaExtras has.

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
    /** IMDb's id ("tt0903747"), for a later look at its scores. */
    imdb: string | null
    /** The episode's own name, words and picture, when asked for (a show's episode). */
    episode?: { name: string; overview: string; still: string | null }
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

/** MetaApis.tmdbExtras. */
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
        imdb: /^tt\d+$/.test(imdb) ? imdb : null,
    }
}
