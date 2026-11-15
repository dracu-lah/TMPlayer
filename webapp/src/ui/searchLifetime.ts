// How long a search outlives the page being hidden: the web twin of ui's SearchLifetime (Kotlin), the same
// two minutes. A tab left in the background for longer comes back with an empty search box. The words are
// never kept in localStorage; the address (`?q=`) carries them only while the page lives.
export const AWAY_LIMIT_MS = 120_000

/** Whether the search stays: `hiddenAt` is when the page was hidden (null while it was not), both in epoch milliseconds. */
export function keepsSearch(hiddenAt: number | null, now: number): boolean {
    if (hiddenAt === null) return true
    const away = now - hiddenAt
    // A clock set back is not "a moment ago": start fresh.
    return away >= 0 && away <= AWAY_LIMIT_MS
}
