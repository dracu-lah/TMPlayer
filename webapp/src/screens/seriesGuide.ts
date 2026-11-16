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
 * TMDB's count keeps the chat's own list: another numbering, nothing in it is missing.
 */
export function rows(season: number, held: readonly HeldSeason[], info: ListedSeason | undefined, next: Next | null | undefined, today: string): Row[] {
    const mine = held.find((s) => s.number === season)
    const numbers = new Set(mine?.numbers ?? [])
    // A season held only as a whole-season file lists that file (the page adds it), not a gap for each episode.
    if (mine?.packed && !numbers.size) return []
    const covered = new Set(mine?.covered ?? [])
    const count = info && sane(info.episodes, MAX_EPISODES) ? Math.trunc(info.episodes) : 0
    const differs = count > 0 && Math.max(0, ...numbers) > count
    const counted = season > 0 && !differs ? Array.from({ length: count }, (_, i) => i + 1) : []
    const all = [...new Set([...numbers, ...counted])].sort((a, b) => a - b)
    const begun = info?.airDate ? info.airDate <= today : true
    const out: Row[] = []
    for (const n of all) {
        if (numbers.has(n)) {
            out.push({ kind: 'have', number: n })
            continue
        }
        if (covered.has(n)) continue
        const dated = next && next.season === season && next.episode === n ? next.airDate : null
        const state: 'missing' | 'upcoming' = !begun || (next && next.season === season && n >= next.episode) ? 'upcoming' : 'missing'
        out.push({ kind: 'gap', number: n, state, airDate: dated })
    }
    return out
}

/** Whether the full list shows anything the chat lacks: a season, or episodes of a season held in part. */
export function hasGaps(list: readonly SeasonStop[]): boolean {
    return list.some((s) => s.state !== 'have' || (s.total !== null && s.have < s.total))
}
