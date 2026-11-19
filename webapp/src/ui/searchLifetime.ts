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

/**
 * Whether the words in the address (`?q=`) are an old search to forget as the page opens. A page
 * load (a reload, a pasted address, a restored or launched tab) never brings an old search back,
 * but the first visit of a load that came from inside the app does keep them: "Find in other chats"
 * on a video's page opens Search with its words (WB21). Inside the app means the entry the page was
 * opened on is not this one (`entry`) and the load itself was a plain navigation, not a reload or
 * a Back or Forward from another session (`loadType`, the navigation entry's type, null where the
 * browser does not say).
 */
export function dropsAddressSearch(o: { shownThisLoad: boolean; words: boolean; entry: boolean; loadType: string | null }): boolean {
    if (o.shownThisLoad || !o.words) return false
    return o.entry || (o.loadType !== null && o.loadType !== 'navigate')
}

/** How this page load began ('navigate', 'reload', 'back_forward', 'prerender'), or null when the browser does not say. */
export function loadType(): string | null {
    try {
        const type = (performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming | undefined)?.type
        return typeof type === 'string' ? type : null
    } catch {
        return null
    }
}
