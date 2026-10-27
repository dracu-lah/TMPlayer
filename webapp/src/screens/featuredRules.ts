// Which Recently added tiles may stand across the top of Home, by the apps' rules (core
// HomeFeatured, 2026-10-27): only films and shows, a file of at least 400 MB, a film at least 20
// minutes when its length is known and a real title, one page per title (a lone episode and its
// show are one). HomeFeatured itself is Kotlin over the shelf's types, and the web holds the
// worker's tiles, so the same rules are applied here to the data the web has. Pure, so it runs
// under `node --test`.

export const FEATURED_LIMIT = 5
export const CANDIDATES = FEATURED_LIMIT * 3
export const MIN_BYTES = 400 * 1024 * 1024
export const FILM_MIN_SEC = 20 * 60

const GENERIC = [
    /(video|vid|movie|clip|file|untitled|new video|new movie|telegram video)([ _-]*\(?\d+\)?)?/,
    /(vid|img|mov|pxl|mvimg|video|movie|clip|file|document|telegram|tg|record|recording|screen ?record(ing)?|wa)[ _-]*\d[\d _.:-]*(wa\d+)?/,
    /\d{4}[ _.-]\d{1,2}[ _.-]\d{1,2}([ _.:-]+\d[\d _.:-]*)?/,
    /\d{1,2}[ _.-]\d{1,2}[ _.-]\d{4}([ _.:-]+\d[\d _.:-]*)?/,
    /\d{8}([ _-]?\d{4,6})?/,
].map((r) => new RegExp(`^(?:${r.source})$`))

/** A title that names nothing: blank, "Video", a camera's "VID_20241013", a date. A bare number stays a title. */
export function isGenericTitle(title: string): boolean {
    const plain = title.trim().toLowerCase()
    if (!/[\p{L}\p{N}]/u.test(plain)) return true
    return GENERIC.some((r) => r.test(plain))
}

/** The parts of a tile the rules read. */
export interface Candidate {
    key: string
    kind: 'show' | 'file'
    title: string
    extra: string | null
    size: number
    duration: number
    /** Whether the poster lookup has a question for it at all. */
    asked: boolean
}

/** Whether a tile may be a page at all, before anything is looked up. */
export function eligible(c: Candidate): boolean {
    if (c.extra || !c.asked) return false
    if (c.size < MIN_BYTES) return false
    if (c.kind === 'file') {
        if (c.duration > 0 && c.duration < FILM_MIN_SEC) return false
        if (isGenericTitle(c.title)) return false
    }
    return true
}

/** The eligible ones in order, one page per title, at most CANDIDATES. */
export function candidatesOf<T extends Candidate>(tiles: T[]): T[] {
    const seen = new Set<string>()
    const out: T[] = []
    for (const tile of tiles) {
        if (!eligible(tile)) continue
        const key = tile.title.trim().toLowerCase()
        if (seen.has(key)) continue
        seen.add(key)
        out.push(tile)
        if (out.length >= CANDIDATES) break
    }
    return out
}

/** Whether an online answer makes a complete page: a match with a wide picture and an overview. */
export function isComplete(a: { match: boolean; backdrop?: string | null; overview?: string }): boolean {
    return a.match && !!a.backdrop && !!a.overview?.trim()
}
