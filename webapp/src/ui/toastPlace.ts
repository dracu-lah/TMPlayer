// Where a toast stands: above the floating bar (and the status pill), 12 px clear of it. Pure, so
// `node --test src/ui/toastPlace.test.ts` reads it; ui/Toast.tsx measures the page and feeds it.

/** The gap between the toast and what it stands over, in px. */
export const TOAST_GAP = 12

/** The things a toast must stand above: the floating bar (a page's or a chat's) and the status pill. */
export const OVER = ['#main-nav .nav-cells', 'nav.chat-bar .nav-cells', '.work-status.pill .work-status-line'] as const

/**
 * px from the foot of a window [height] px tall to the top of the highest thing the toast stands over
 * (its top edge from [top], null when it is not drawn), or null when there is nothing (the CSS
 * fallback stands). A top that is not a number, or is below the window, counts as not drawn.
 */
export function clearanceOf(top: (selector: string) => number | null, height: number): number | null {
    if (!Number.isFinite(height) || height <= 0) return null
    let highest: number | null = null
    for (const selector of OVER) {
        const t = top(selector)
        if (typeof t === 'number' && Number.isFinite(t) && t > 0 && t < height && (highest === null || t < highest)) highest = t
    }
    return highest === null ? null : Math.max(0, Math.round(height - highest))
}
