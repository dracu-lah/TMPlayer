// What a chat's filters keep per chat, checked: the small part of ui/videoFilter.ts that store.ts
// reads at first load, so the rest (chip words, notes, Play random's memory) loads with the chat.

/** The Show group, in the order the menu lists it. The apps' On this device and 1080p and up are not here: the web keeps no copies, and HD is a picture chip. */
export const FILTERS = ['All', 'Unwatched', 'InProgress', 'Watched', 'Shows', 'Singles'] as const
export type ChatFilter = (typeof FILTERS)[number]

/** What is kept for one chat: the Show filter and the line of the filters by what a video is. */
export interface ChatPref {
    filter: ChatFilter
    facets: string
}

export const NO_PREF: ChatPref = { filter: 'All', facets: '' }

/** Longest stored line: six parts of at most eleven characters each, with their separators. */
const LINE_MAX = 80
const PART = /^(?:(?:len|pic|add)=[a-z0-9]{1,6}|(?:hdr|sub)=1|lang=[a-z]{2,3})$/

export function cleanFilter(raw: unknown): ChatFilter {
    return FILTERS.find((f) => f === raw) ?? 'All'
}

/**
 * A stored line of facets kept only as far as it is well formed: parts of the shapes the rules
 * write, one of each kind, nothing else. What the rules cannot read they would drop anyway; this
 * keeps garbage (a megabyte, a script) from being stored or sent on.
 */
export function cleanFacets(raw: unknown): string {
    if (typeof raw !== 'string' || raw.length > 4 * LINE_MAX) return ''
    const seen = new Set<string>()
    const kept: string[] = []
    for (const part of raw.split(';')) {
        if (!PART.test(part)) continue
        const key = part.slice(0, part.indexOf('='))
        if (seen.has(key)) continue
        seen.add(key)
        kept.push(part)
    }
    return kept.join(';')
}

/** A chat's stored preference, or none where what was stored is not one. */
export function cleanPref(raw: unknown): ChatPref {
    if (!raw || typeof raw !== 'object') return NO_PREF
    const o = raw as { filter?: unknown; facets?: unknown }
    return { filter: cleanFilter(o.filter), facets: cleanFacets(o.facets) }
}

export const isDefault = (p: ChatPref) => p.filter === 'All' && p.facets === ''
