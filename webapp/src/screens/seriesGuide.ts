// The series page's merge of what TMDB knows with what the chat holds (T9): every season and episode
// listed, each Have (a video, plays), Missing (known, not in the chats) or Upcoming (not aired). The
// port of core's EpisodeGuide (data/EpisodeGuide.kt), season and episode counts only: the web asks
// TMDB for no per-episode list. A change to the Kotlin rules is a change here; seriesGuide.test.ts
// holds the cases of the apps' SeriesGuideTableTest.

/** More seasons than any show has, the longest season listed from a count, and the largest number taken. */
export const MAX_SEASONS = 200
export const MAX_EPISODES = 3000

/** What the chat holds of one season: its number, the episode numbers, and the ones a longer file holds. */
export interface HeldSeason {
    number: number
    numbers: readonly number[]
    covered?: readonly number[]
    /** The chat holds a whole-season file of it (a pack), so it is held though it lists no episode. */
    packed?: boolean
}

/** One season as TMDB lists it (tmdb/extras.ts' SeasonStop). */
export interface ListedSeason {
    number: number
    episodes: number
    airDate: string | null
}

export interface Next {
    season: number
    episode: number
    airDate: string
}

export type State = 'have' | 'missing' | 'upcoming'

export interface SeasonStop {
    number: number
    /** Episodes of it the chat holds. */
    have: number
    /** Episodes TMDB counts, when it counts them. */
    total: number | null
    state: State
    airDate: string | null
    /** Season 0: the specials, last, and only when the chat holds one. */
    special: boolean
}

export type Row =
    | { kind: 'have'; number: number }
    | { kind: 'gap'; number: number; state: 'missing' | 'upcoming'; airDate: string | null }

const sane = (n: number, max: number) => Number.isFinite(n) && n >= 1 && n <= max

function provider(listed: readonly ListedSeason[] | undefined): Map<number, ListedSeason> {
    const out = new Map<number, ListedSeason>()
    for (const s of (listed ?? []).slice(0, MAX_SEASONS * 2)) {
        if (!s || !sane(s.number, MAX_SEASONS)) continue
        out.set(s.number, { number: s.number, episodes: sane(s.episodes, MAX_EPISODES) ? Math.trunc(s.episodes) : 0, airDate: typeof s.airDate === 'string' && s.airDate ? s.airDate : null })
    }
    return out
}

/**
 * Whether TMDB's seasons describe the show the chat holds (EpisodeGuide.agrees): a wrong match or
 * a release numbered another way (anime counted straight through) disagrees, and then nothing the
 * chat lacks is called missing. True when there is nothing to compare.
 */
export function agrees(held: readonly HeldSeason[], listed: readonly ListedSeason[] | undefined): boolean {
    const known = provider(listed)
    if (!known.size) return true
    let have = 0
    let off = 0
    for (const season of held) {
        if (season.number <= 0) continue
        const numbers = new Set(season.numbers)
        have += numbers.size
        const info = known.get(season.number)
        const top = Math.max(0, ...numbers)
        if (!info || (info.episodes > 0 && top > info.episodes)) off += numbers.size
    }
    return have === 0 || off * 2 <= have
}

/** Every season either side knows, in order, the specials last (EpisodeGuide.seasons). Dates are `yyyy-MM-dd`, compared as text. */
export function seasons(held: readonly HeldSeason[], listed: readonly ListedSeason[] | undefined, next: Next | null | undefined, today: string): SeasonStop[] {
    const have = new Map(held.map((s) => [s.number, new Set(s.numbers).size]))
    const packed = new Set(held.filter((s) => s.packed && sane(s.number, MAX_SEASONS)).map((s) => s.number))
    const known = provider(listed)
    const nextSeason = next && sane(next.season, MAX_SEASONS) ? next.season : null
    const numbers = [...new Set([...have.keys()].filter((n) => n > 0).concat([...known.keys()], nextSeason === null ? [] : [nextSeason]))].sort((a, b) => a - b)
    const out: SeasonStop[] = numbers.map((n) => {
        const info = known.get(n)
        const mine = have.get(n) ?? 0
        const airDate = info?.airDate ?? (next && next.season === n && next.episode === 1 ? next.airDate : null)
        const state: State = mine > 0 || packed.has(n) ? 'have' : airDate === null || airDate > today ? 'upcoming' : 'missing'
        return { number: n, have: mine, total: info && info.episodes > 0 ? info.episodes : null, state, airDate, special: false }
    })
    const specials = have.get(0) ?? 0
    if (specials > 0) out.push({ number: 0, have: specials, total: null, state: 'have', airDate: null, special: true })
    return out
}

/**
 * [season]'s lines: each episode number the chat holds as `have`, and between them a `gap` for each
 * one TMDB counts and the chat lacks (EpisodeGuide.rows). A season whose chat numbers run past
 * TMDB's count keeps the chat's own list: another numbering, nothing in it is missing. With
 * `provider`, the season's episodes as TMDB lists them one by one, the gaps follow that list (and its
 * days: one that has not aired is upcoming) rather than the count.
 */
export function rows(
    season: number,
    held: readonly HeldSeason[],
    info: ListedSeason | undefined,
    next: Next | null | undefined,
    today: string,
    provider?: readonly ProviderEpisode[] | null,
): Row[] {
    const mine = held.find((s) => s.number === season)
    const numbers = new Set(mine?.numbers ?? [])
    // A season held only as a whole-season file lists that file (the page adds it), not a gap for each episode.
    if (mine?.packed && !numbers.size) return []
    const covered = new Set(mine?.covered ?? [])
    const listedHere = new Map<number, ProviderEpisode>()
    for (const e of (provider ?? []).slice(0, MAX_EPISODES * 2)) if (e && sane(e.number, MAX_NUMBER)) listedHere.set(Math.trunc(e.number), e)
    const count = info && sane(info.episodes, MAX_EPISODES) ? Math.trunc(info.episodes) : 0
    const known = listedHere.size ? Math.max(...listedHere.keys()) : count
    const differs = known > 0 && Math.max(0, ...numbers) > known
    const counted = !listedHere.size && season > 0 && !differs ? Array.from({ length: count }, (_, i) => i + 1) : []
    const all = [...new Set([...numbers, ...(differs ? [] : listedHere.keys()), ...counted])].sort((a, b) => a - b)
    const begun = info?.airDate ? info.airDate <= today : true
    const out: Row[] = []
    for (const n of all) {
        if (numbers.has(n)) {
            out.push({ kind: 'have', number: n })
            continue
        }
        if (covered.has(n)) continue
        const meta = listedHere.get(n)
        const nextHere = next && next.season === season ? next : null
        const dated = meta?.airDate ?? (nextHere && nextHere.episode === n ? nextHere.airDate : null)
        const state: 'missing' | 'upcoming' = !begun
            ? 'upcoming'
            : meta
              ? !meta.airDate || meta.airDate > today
                  ? 'upcoming'
                  : 'missing'
              : nextHere && n >= nextHere.episode
                ? 'upcoming'
                : 'missing'
        out.push({ kind: 'gap', number: n, state, airDate: dated })
    }
    return out
}

// ---- which numbering the chat follows, and where its numbers sit in TMDB's seasons ----

/** The largest episode number placed; a bigger one is a garbage name, not an episode. */
export const MAX_NUMBER = 9999
/** Most provider seasons one chat season is read against (EpisodeNumbering.MAX_SEASONS). */
export const MAX_PLACED_SEASONS = 12

/** One episode of a season as the worker answers it (tmdb/extras.ts' split and /tmdb/season): its number in that season. */
export interface ProviderEpisode {
    number: number
    name: string
    overview: string
    still: string | null
    airDate: string | null
    runtime: number | null
}

/** What the series page reads of the worker's extras answer. */
export interface SeasonFacts {
    seasonList?: readonly ListedSeason[]
    next?: Next | null
    seasonGroup?: string | null
    listedSeasons?: readonly ListedSeason[]
    listedNext?: Next | null
}

export interface Numbering {
    seasons: readonly ListedSeason[]
    next: Next | null
    /** The seasons are the split's (episode group `group`), not TMDB's own. */
    split: boolean
}

/**
 * Which of the show's numberings the chat follows (EpisodeGuide.numbering). A show TMDB lists as one
 * season that an episode group splits has two: the split (S02E01, as most releases number it) and
 * TMDB's own (S01E26, as a release counting straight through does). The split is taken unless the
 * chat disagrees with it and agrees with TMDB's own; every other show has one numbering.
 */
export function numbering(held: readonly HeldSeason[], facts: SeasonFacts | null | undefined): Numbering {
    if (!facts) return { seasons: [], next: null, split: false }
    const split = !!facts.seasonGroup && !!facts.listedSeasons?.length
    const first: Numbering = { seasons: facts.seasonList ?? [], next: facts.next ?? null, split }
    if (!split || agrees(held, facts.seasonList)) return first
    return agrees(held, facts.listedSeasons) ? { seasons: facts.listedSeasons ?? [], next: facts.listedNext ?? null, split: false } : first
}

/** The numbered seasons of `layout` with an episode count, in order, one each (EpisodeNumbering.counts). */
function counts(layout: readonly ListedSeason[]): ListedSeason[] {
    const seen = new Set<number>()
    return layout
        .filter((s) => !!s && sane(s.number, MAX_SEASONS) && sane(s.episodes, MAX_EPISODES) && !seen.has(s.number) && !!seen.add(s.number))
        .sort((a, b) => a.number - b.number)
        .slice(0, MAX_SEASONS)
}

/**
 * What a chat season's numbers need once per call: whether they are counted straight through (S02E13
 * for TMDB's second season's first: at least half lie past TMDB's count for that season; a season TMDB
 * does not list, the specials, and one stray number past its end are not) and TMDB's layout in order.
 */
function placer(season: number, numbers: Iterable<number>, layout: readonly ListedSeason[]) {
    const list = [...numbers]
    const all = counts(layout)
    const own = all.find((s) => s.number === season)
    const past = own ? list.filter((n) => n > own.episodes).length : 0
    const through = season >= 1 && list.length > 0 && !!own && past > 0 && past * 2 >= list.length
    return {
        list,
        through,
        /** TMDB's season and episode for `number` (counted straight through), null past every season. */
        at(number: number): [number, number] | null {
            if (!Number.isInteger(number) || number < 1 || number > MAX_NUMBER) return null
            if (!through) return [season, number]
            if (own && number <= own.episodes) return [season, number]
            let before = 0
            for (const s of all) {
                if (number <= before + s.episodes) return [s.number, number - before]
                before += s.episodes
            }
            return null
        },
    }
}

/** Whether the chat's `numbers` of `season` are counted straight through rather than from one (see placer). */
export function counted(season: number, numbers: Iterable<number>, layout: readonly ListedSeason[]): boolean {
    return placer(season, numbers, layout).through
}

/** TMDB's season and episode for episode `number` of the chat's `season`; null past every season TMDB lists (EpisodeNumbering.place). */
export function place(season: number, number: number, numbers: Iterable<number>, layout: readonly ListedSeason[]): [number, number] | null {
    return placer(season, numbers, layout).at(number)
}

/** The TMDB seasons whose lists name the chat's `numbers` of `season`, in order, at most MAX_PLACED_SEASONS: just `season` unless it is counted straight through. */
export function seasonsFor(season: number, numbers: Iterable<number>, layout: readonly ListedSeason[]): number[] {
    const p = placer(season, numbers, layout)
    if (!p.through) return [season]
    const out: number[] = []
    for (const n of [...p.list].sort((a, b) => a - b)) {
        const at = p.at(n)
        if (at && !out.includes(at[0])) out.push(at[0])
        if (out.length >= MAX_PLACED_SEASONS) break
    }
    return out.length ? out : [season]
}

/**
 * TMDB's episodes from `lists` (its seasons' lists, by season) under the chat's own numbers for
 * `season`: each of `numbers` with the name, still and words of the episode it stands for. A season
 * that is not counted straight through answers its own list as it is (EpisodeNumbering.rekey).
 */
export function rekey(season: number, numbers: Iterable<number>, layout: readonly ListedSeason[], lists: ReadonlyMap<number, readonly ProviderEpisode[]>): ProviderEpisode[] {
    const p = placer(season, numbers, layout)
    if (!p.through) return [...(lists.get(season) ?? [])]
    const byPlace = new Map<string, ProviderEpisode>()
    for (const [s, eps] of lists) for (const e of eps) byPlace.set(`${s}:${e.number}`, e)
    const out: ProviderEpisode[] = []
    for (const n of [...new Set(p.list)].sort((a, b) => a - b)) {
        const at = p.at(n)
        const e = at && byPlace.get(`${at[0]}:${at[1]}`)
        if (e) out.push({ ...e, number: n })
    }
    return out
}

const DAY = /^\d{4}-\d{2}-\d{2}$/
const STILL = /^\/[A-Za-z0-9_-]{8,64}\.(jpg|png|webp)$/
const words = (v: unknown, max: number) => (typeof v === 'string' ? v.trim().slice(0, max) : '')

/** The worker's /tmdb/season answer, every field checked: null for anything that is not a list, the sane episodes of one otherwise. */
export function readSeason(json: unknown): ProviderEpisode[] | null {
    if (!json || typeof json !== 'object') return null
    const o = json as { found?: unknown; episodes?: unknown }
    if (o.found !== true || !Array.isArray(o.episodes)) return null
    const out: ProviderEpisode[] = []
    for (const raw of o.episodes.slice(0, MAX_EPISODES)) {
        if (!raw || typeof raw !== 'object') continue
        const e = raw as Record<string, unknown>
        if (typeof e.number !== 'number' || !Number.isInteger(e.number) || e.number < 1 || e.number > MAX_NUMBER) continue
        out.push({
            number: e.number,
            name: words(e.name, 400),
            overview: words(e.overview, 4000),
            still: typeof e.still === 'string' && STILL.test(e.still) ? e.still : null,
            airDate: typeof e.airDate === 'string' && DAY.test(e.airDate) ? e.airDate : null,
            runtime: typeof e.runtime === 'number' && Number.isInteger(e.runtime) && e.runtime >= 1 && e.runtime <= 1000 ? e.runtime : null,
        })
    }
    return out
}

/** Whether the full list shows anything the chat lacks: a season, or episodes of a season held in part. */
export function hasGaps(list: readonly SeasonStop[]): boolean {
    return list.some((s) => s.state !== 'have' || (s.total !== null && s.have < s.total))
}
