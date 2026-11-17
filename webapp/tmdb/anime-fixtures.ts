// Canned provider answers shaped like a long anime's (the apps' AnimeFixtures: about 500 episodes
// named by absolute number, 21 TMDB seasons, one AniList entry), for the guide's tests. Nothing
// here reaches the internet; every answer is what a provider could send.
import type { SeasonStop } from './extras.ts'

export const TOTAL = 500
export const ANILIST = 1735
export const MAL = 1735
export const TMDB = 31910
export const KITSU = 1555
export const GROUP = 'abcdef0123456789abcdef01'

/** TMDB's 21 seasons: 20 of ordinary length and a last one that makes the total 500. */
export const seasonSizes: number[] = (() => {
    const sizes = [32, 21, 18, 32, 20, 20, 28, 18, 25, 22, 22, 24, 23, 25, 24, 25, 24, 21, 25, 19]
    sizes.push(TOTAL - sizes.reduce((a, b) => a + b, 0))
    return sizes
})()

const FIRST = ['Homecoming', 'Rescue', 'Silent', 'Broken', 'Hidden', 'Final', 'Distant', 'Burning', 'Frozen', 'Golden', 'Crimson', 'Lonely', 'Wandering', 'Secret', 'Rising', 'Fallen', 'Gentle', 'Fierce', 'Ancient', 'Hollow']
const SECOND = ['Bridge', 'Promise', 'Storm', 'Village', 'Mask', 'Shadow', 'Blade', 'River', 'Tower', 'Garden', 'Memory', 'Contract', 'Ember', 'Harbor', 'Winter', 'Gate', 'Oath', 'Lantern', 'Border', 'Echo', 'Pilgrim', 'Orchard', 'Ledger', 'Beacon', 'Canyon', 'Cipher', 'Tide', 'Summit', 'Relic', 'Verdict']

/** A name no placeholder check mistakes for "Episode N", unique to the absolute number. */
export const title = (absolute: number) => `${FIRST[absolute % FIRST.length]} ${SECOND[Math.floor(absolute / FIRST.length) % SECOND.length]}`
export const kitsuTitle = (absolute: number) => `K: ${title(absolute)}`
export const jikanTitle = (absolute: number) => `J: ${title(absolute)}`

/** TMDB's place (season, episode) of an absolute number. */
export function place(n: number): [number, number] {
    let left = n
    for (const [i, size] of seasonSizes.entries()) {
        if (left <= size) return [i + 1, left]
        left -= size
    }
    throw new Error('past the end')
}

/** One week apart from the first airing. */
export function date(n: number): string {
    return new Date(Date.UTC(2007, 1, 15) + (n - 1) * 7 * 86_400_000).toISOString().slice(0, 10)
}

export const stillOf = (n: number) => `/still${String(n).padStart(4, '0')}abcd.jpg`

export const counts = (sizes: number[] = seasonSizes): SeasonStop[] => sizes.map((episodes, i) => ({ number: i + 1, episodes, airDate: null }))

/** AniList's answer to the episodes query. */
export function aniRun(over: Record<string, unknown> = {}) {
    return {
        data: {
            Media: {
                idMal: MAL,
                episodes: TOTAL,
                nextAiringEpisode: null,
                streamingEpisodes: [],
                title: { romaji: 'Naruto: Shippuuden', english: null, native: 'ナルト 疾風伝' },
                synonyms: ['Naruto Shippuden'],
                ...over,
            },
        },
    }
}

/** TMDB's tv page with the groups appended. */
export function tmdbShow(sizes: number[] = seasonSizes, absoluteGroup = false) {
    return {
        id: TMDB,
        seasons: [{ season_number: 0, episode_count: 5, air_date: null }, ...sizes.map((n, i) => ({ season_number: i + 1, episode_count: n, air_date: '2007-02-15' }))],
        episode_groups: { results: absoluteGroup ? [{ id: GROUP, type: 2, episode_count: sizes.reduce((a, b) => a + b, 0) }, { id: 'ffffffffffffffffffffff01', type: 1, episode_count: 9 }] : [{ id: 'ffffffffffffffffffffff01', type: 1, episode_count: 9 }] },
    }
}

/** One season's page as TMDB gives it, named by absolute number. */
export function tmdbSeason(season: number, sizes: number[] = seasonSizes) {
    const start = sizes.slice(0, season - 1).reduce((a, b) => a + b, 0)
    return {
        episodes: Array.from({ length: sizes[season - 1] ?? 0 }, (_, i) => ({
            episode_number: i + 1,
            name: title(start + i + 1),
            overview: `Words ${start + i + 1}`.replace(/\d/g, 'x'),
            still_path: stillOf(start + i + 1),
            air_date: date(start + i + 1),
            runtime: 24,
        })),
    }
}

/** The "Absolute order" group: one group, every episode with its place. */
export function tmdbGroup(sizes: number[] = seasonSizes) {
    const total = sizes.reduce((a, b) => a + b, 0)
    return {
        groups: [
            {
                order: 1,
                episodes: Array.from({ length: total }, (_, i) => {
                    const [season, episode] = place(i + 1)
                    return { order: i, season_number: season, episode_number: episode, name: title(i + 1), overview: '', still_path: stillOf(i + 1), air_date: date(i + 1), runtime: 24 }
                }),
            },
        ],
    }
}

/** Kitsu's mapping answer and one page of its episodes. */
export const kitsuMapping = () => ({ data: [{ relationships: { item: { data: { type: 'anime', id: String(KITSU) } } } }] })
export function kitsuPage(offset: number, total = TOTAL) {
    const from = offset + 1
    const to = Math.min(total, offset + 20)
    return {
        data: Array.from({ length: Math.max(0, to - from + 1) }, (_, i) => ({ attributes: { number: from + i, titles: { en_us: kitsuTitle(from + i) }, synopsis: '', airdate: date(from + i), length: 24, thumbnail: { original: `https://media.kitsu.app/episodes/${from + i}.jpg` } } })),
        links: to < total ? { next: 'https://kitsu.io/api/edge/anime/1555/episodes?page%5Boffset%5D=' + to } : {},
    }
}

/** One page of Jikan's episodes, a hundred to a page. */
export function jikanPage(page: number, total = TOTAL) {
    const from = (page - 1) * 100 + 1
    const to = Math.min(total, page * 100)
    return {
        data: Array.from({ length: Math.max(0, to - from + 1) }, (_, i) => ({ mal_id: from + i, title: jikanTitle(from + i), aired: `${date(from + i)}T00:00:00+00:00` })),
        pagination: { has_next_page: to < total },
    }
}
