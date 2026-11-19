// Blue Box (tv 207347, read live 2026-11-19): one season of 37 on its TMDB page, and a "Seasons"
// episode group of 25 + 12, a "Cours (Path)" group of three and a "Blu-ray (ja-JP)" group of 25. The
// shape of the apps' SeasonSplitTest, as TMDB's JSON, for the worker's tests.

/** The airing day of TMDB's episode n: weekly from 2024-10-03 to 25, then weekly from 2026-10-02. */
export function air(n: number): string {
    const start = n <= 25 ? Date.UTC(2024, 9, 3) + (n - 1) * 7 * 86_400_000 : Date.UTC(2026, 9, 2) + (n - 26) * 7 * 86_400_000
    return new Date(start).toISOString().slice(0, 10)
}

/** TMDB names episodes up to 31; 32 on are announced as "Episode 32". */
export const name = (n: number) => (n <= 31 ? `The ${n}th Tide` : `Episode ${n}`)

export const GROUPS = [
    { id: 'bluray0001', name: 'Blu-ray (ja-JP)', type: 3, group_count: 3, episode_count: 25 },
    { id: 'cours00001', name: 'Cours (Path)', type: 7, group_count: 3, episode_count: 37 },
    { id: 'seasons001', name: 'Seasons', type: 6, group_count: 2, episode_count: 37 },
]

/** The show's own page: one season of 37 (and two specials), the next episode the 33rd, the groups appended. */
export function page(groups: unknown = { results: GROUPS }) {
    return {
        id: 207347,
        name: 'Blue Box',
        genres: [{ name: 'Animation' }],
        number_of_seasons: 1,
        status: 'Returning Series',
        seasons: [
            { season_number: 0, episode_count: 2 },
            { season_number: 1, episode_count: 37, air_date: '2024-10-03' },
        ],
        next_episode_to_air: { season_number: 1, episode_number: 33, air_date: air(33) },
        vote_average: 8.1,
        vote_count: 300,
        episode_groups: groups,
    }
}

const episode = (n: number, order: number) => ({
    season_number: 1,
    episode_number: n,
    order,
    name: name(n),
    overview: `About ${n}`,
    air_date: air(n),
    runtime: 24,
    still_path: `/still${String(n).padStart(4, '0')}abc.jpg`,
})

/** The "Seasons" group as TMDB answers it: the second season listed first, as TMDB sometimes does. */
export const SEASONS_GROUP = {
    id: 'seasons001',
    name: 'Seasons',
    groups: [
        { name: 'Season 2', order: 2, episodes: Array.from({ length: 12 }, (_, i) => episode(26 + i, i)) },
        { name: 'Season 1', order: 1, episodes: Array.from({ length: 25 }, (_, i) => episode(1 + i, i)) },
    ],
}

/** TMDB's own season 1: 37 episodes. */
export const OWN_SEASON = { episodes: Array.from({ length: 37 }, (_, i) => ({ episode_number: i + 1, name: name(i + 1), air_date: air(i + 1), runtime: 24 })) }
