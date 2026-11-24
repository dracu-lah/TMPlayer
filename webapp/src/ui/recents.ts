// Recent searches (WP-59): the apps' RecentSearches (core pureMain) in TypeScript, over what the
// browser stored. Ten kept, newest first; the same words in another case or spacing are one search; a
// query typed a letter at a time is recorded as it settles. Anything stored that does not hold up
// (not JSON, not a list, an entry that is not text, ten thousand entries) reads as what is left.

export const LIMIT = 10
export const MAX_LENGTH = 80
/** Entries read from a stored list before it is cut to LIMIT: a bound on the work, not on the list. */
const READ_CAP = 1_000

/** One line, single spaces, cut to a chip's length: a chip is not the place for a pasted paragraph. */
export function normalise(query: string): string {
    return query.trim().split(/\s+/).filter(Boolean).join(' ').slice(0, MAX_LENGTH).trim()
}

/** `query` put at the front of `current`, by the apps' rules. */
export function add(current: readonly string[], query: string, limit = LIMIT): string[] {
    const clean = normalise(query)
    if (!clean) return current.slice(0, limit)
    const key = clean.toLowerCase()
    const newest = current[0]?.toLowerCase()
    // A shorter start of the newest entry (a backspace on the way to another word) is not recorded.
    if (newest !== undefined && newest !== key && newest.startsWith(key)) return current.slice(0, limit)
    const rest = current.filter((it, at) => {
        const other = it.toLowerCase()
        // The same words are one search; a query that only extends the newest entry replaces it.
        return other !== key && !(at === 0 && key.startsWith(other))
    })
    return [clean, ...rest].slice(0, limit)
}

/** `current` without `query`, matched as `add` matches: any case, any spacing. */
export function remove(current: readonly string[], query: string): string[] {
    const key = normalise(query).toLowerCase()
    return current.filter((it) => it.toLowerCase() !== key)
}

/** What the store read back, checked: a list of at most LIMIT distinct, tidy searches. */
export function parse(raw: unknown): string[] {
    let list: unknown = raw
    if (typeof raw === 'string') {
        try {
            list = JSON.parse(raw)
        } catch {
            return []
        }
    }
    if (!Array.isArray(list)) return []
    const seen = new Set<string>()
    const out: string[] = []
    for (const v of list.slice(0, READ_CAP)) {
        if (typeof v !== 'string') continue
        const clean = normalise(v)
        const key = clean.toLowerCase()
        if (!clean || seen.has(key)) continue
        seen.add(key)
        out.push(clean)
        if (out.length >= LIMIT) break
    }
    return out
}

/** A backup's searches joined into this device's: this device's first (they are newer), then the file's the device lacks. */
export function join(mine: readonly string[], theirs: readonly string[]): string[] {
    const have = new Set(mine.map((s) => s.toLowerCase()))
    return parse([...mine, ...theirs.filter((s) => !have.has(normalise(s).toLowerCase()))])
}
