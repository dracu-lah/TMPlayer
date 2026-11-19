// The Last played order's times, held in the worker: the page sends them once (and again only when
// they change), and each chat view names them by version, so a 20,000 entry list is not posted with
// every arrangement of the grid (and the watch page's Next arranges by the same times).

export interface Touches {
    version: number
    ids: string[]
    times: number[]
}

/** Most entries kept: a chat's order reads what has loaded of it. */
export const TOUCHES_MAX = 20_000

let held: Touches | null = null

/** Takes a version of the lists; anything that is not a version of equal, well typed lists is refused and the old ones stay. */
export function holdTouches(version: unknown, ids: unknown, times: unknown): boolean {
    if (typeof version !== 'number' || !Number.isSafeInteger(version) || version <= 0) return false
    if (!Array.isArray(ids) || !Array.isArray(times) || ids.length !== times.length) return false
    const n = Math.min(ids.length, TOUCHES_MAX)
    const keep: Touches = { version, ids: [], times: [] }
    for (let i = 0; i < n; i++) {
        if (typeof ids[i] !== 'string' || typeof times[i] !== 'number' || !Number.isFinite(times[i])) continue
        keep.ids.push(ids[i])
        keep.times.push(times[i])
    }
    held = keep
    return true
}

/** The lists a view names, or none where the worker does not hold that version (it restarted, or the page has not sent it). */
export function touchesFor(version: number | undefined): { ids: string[]; times: number[] } {
    return held && version !== undefined && held.version === version ? held : { ids: [], times: [] }
}

export const forgetTouches = () => {
    held = null
}
