// What the page keeps of the worker's answers (posters.ts), and for how long. Pure, so it is tested
// without a browser (posters.test.ts).

const DAY = 24 * 60 * 60 * 1000
/** MetaKeep: an answer with a match is kept 60 days, "nothing matched" 7. */
export const FOUND_TTL = 60 * DAY
export const MISS_TTL = 7 * DAY

/** Whether the worker lets an answer be kept: not one it marks no-store (or no-cache, or private). */
export function storable(cacheControl: string | null): boolean {
    return !/(^|,)\s*(no-store|no-cache|private)\b/i.test(cacheControl ?? '')
}

/** Whether a kept answer still stands: within its time, and not from before a field the page needs. */
export function fresh(url: string, body: { match?: boolean; found?: boolean; seasonList?: unknown[]; groupsRead?: boolean }, saved: number): boolean {
    // A poster answer kept before the overview and the wide picture were added is asked again,
    // and an extras answer kept before the title logo.
    if (body.match && url.startsWith('/tmdb/poster') && !('overview' in body)) return false
    if (body.found && url.startsWith('/tmdb/extras') && !('logo' in body)) return false
    // A show TMDB lists as one season, kept before the season split (its episode groups were not read), is asked again: only
    // such a show can have a split. AniList's answer carries no groups.
    const lone = body.seasonList?.length === 1
    if (body.found && lone && url.startsWith('/tmdb/extras') && !/[?&]a=1(&|$)/.test(url) && !('groupsRead' in body)) return false
    return Date.now() - saved < (body.match || body.found ? FOUND_TTL : MISS_TTL)
}
