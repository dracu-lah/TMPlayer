// One season's episodes from the worker (/tmdb/season: names, words, stills, days, lengths), for the
// series page. Asked once per season and numbering for this page's life; a failure of any kind is
// "no list" (the page lists what the chats hold and the counts, as before) and is asked again the
// next time the season is shown, never remembered.
import { readSeason, type ProviderEpisode } from './seriesGuide'

const asked = new Map<string, Promise<ProviderEpisode[] | null>>()

/** `season` of the TMDB show `id`, or of the split episode group `group` makes of it; null when it could not be had. */
export function loadSeason(id: string, season: number, group: string | null): Promise<ProviderEpisode[] | null> {
    if (!/^\d{1,9}$/.test(id) || !Number.isInteger(season) || season < 0 || season > 200) return Promise.resolve(null)
    const q = new URLSearchParams({ id, s: String(season) })
    if (group) q.set('g', group)
    const key = q.toString()
    let p = asked.get(key)
    if (!p) {
        p = fetch(`/tmdb/season?${key}`).then(
            async (r) => {
                const read = r.ok ? readSeason(await r.json().catch(() => null)) : null
                // "Not found" is an answer (an empty list, kept); a 503 or garbage is not.
                if (read === null && !r.ok) asked.delete(key)
                return read ?? (r.ok ? [] : null)
            },
            () => (asked.delete(key), null),
        )
        asked.set(key, p)
    }
    return p
}
