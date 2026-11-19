// A chat's filters on the web (U6) and Play random (U16): what is remembered per chat and what the
// page says about it. The rules themselves (which video passes, how the line is read and written,
// which video is picked) are core's, run in the worker; this file only keeps what the page stores
// clean, since a stored value is outside data, and decides which of the grid's notes to show.

import type { ChatFilter, ChatPref } from './filterPref.ts'

// What is stored per chat (ChatPref, cleanPref, FILTERS) is in filterPref.ts, which the store reads at first load.
export * from './filterPref.ts'

/** The catalog key naming each Show filter. */
export const FILTER_WORDS = {
    All: 'grid.filter_all',
    Unwatched: 'grid.filter_unwatched',
    InProgress: 'grid.filter_in_progress',
    Watched: 'grid.filter_watched',
    Shows: 'grid.filter_shows',
    Singles: 'grid.filter_singles',
} as const satisfies Record<ChatFilter, string>

/** How many filters are on, for the Sort button's label: the Show filter counts as one. */
export const filtersOn = (p: ChatPref, facetCount: number) => (p.filter === 'All' ? 0 : 1) + facetCount

/**
 * The grid's note about the filters, from what the worker counted.
 * - `off`: nothing is filtered.
 * - `paused`: words are being looked for, which lists what Telegram found whatever the filters say.
 * - `shown-all`: "Show them" was pressed, so this visit lists every video; Filter again puts them back.
 * - `hides-all`: videos are loaded and every one is screened out.
 * - `hides-some`: some are screened out, "N videos hidden by your filter".
 * - `on`: filters are on and nothing is screened out (yet).
 */
export type FilterState = 'off' | 'paused' | 'shown-all' | 'hides-all' | 'hides-some' | 'on'

export function filterState(o: { on: number; query: boolean; showAll: boolean; shown: number; total: number; hidden: number }): FilterState {
    if (o.on === 0) return 'off'
    if (o.query) return 'paused'
    if (o.showAll) return 'shown-all'
    if (o.total > 0 && o.shown === 0 && o.hidden > 0) return 'hides-all'
    if (o.hidden > 0) return 'hides-some'
    return 'on'
}

/**
 * Whether the grid should read another page by itself: filters are on, more of the chat is there,
 * and the cards on screen are too few to scroll. It stops after `AUTO_PAGES` pages asked for that
 * way, so a filter that matches nothing in a 20,000 video chat reads a few pages and then offers
 * Keep looking, never the whole chat. Changing a filter starts the three again but never past `AUTO_TOTAL` for the visit.
 */
export function wantsMore(o: { state: FilterState; shown: number; done: boolean; busy: boolean; failed: boolean; auto: number; total: number }): boolean {
    if (o.state !== 'hides-all' && o.state !== 'hides-some' && o.state !== 'on') return false
    if (o.done || o.busy || o.failed || o.auto >= AUTO_PAGES || o.total >= AUTO_TOTAL) return false
    return o.shown < ENOUGH
}

/** VideoFaceting.AUTO_PAGES: pages a short filtered grid asks for by itself. */
export const AUTO_PAGES = 3

/** Pages a chat visit reads by itself for its filters in all, however often they change: past it only Keep looking reads on. */
export const AUTO_TOTAL = 12

/** Cards that fill a page and its scroll: below this a filter asks for more by itself. */
export const ENOUGH = 24

/** The words of each chip, by the filter line's own codes (VideoFacets.Length, Picture, Added). */
export const LENGTH_WORDS = { any: 'grid.filter_any', short: 'grid.filter_length_short', long: 'grid.filter_length_long' } as const
export const PICTURE_WORDS = { any: 'grid.filter_any', '4k': 'grid.filter_picture_4k', '1080': 'grid.filter_hd', '720': 'grid.filter_picture_720' } as const
export const ADDED_WORDS = { any: 'grid.filter_any', week: 'grid.filter_added_week', month: 'grid.filter_added_month', year: 'grid.filter_added_year' } as const

/** What the worker's rules read from a chat's facets line (facetsRead). */
export interface Facets {
    length: string
    picture: string
    hdr: boolean
    subtitles: boolean
    language: string | null
    added: string
    count: number
}

export const NO_FACETS: Facets = { length: 'any', picture: 'any', hdr: false, subtitles: false, language: null, added: 'any', count: 0 }

const has = <T extends object>(o: T, k: string): k is Extract<keyof T, string> => Object.hasOwn(o, k)

/**
 * The filters in force as one phrase ("Unwatched, 1080p and up, HDR"), for "Unwatched hides all 40
 * videos loaded so far" (the apps' videoFiltersLabel); null with none on.
 */
export function filterLabel(pref: ChatPref, f: Facets, word: (key: string) => string): string | null {
    const out: string[] = []
    if (pref.filter !== 'All') out.push(word(FILTER_WORDS[pref.filter]))
    if (f.length !== 'any' && has(LENGTH_WORDS, f.length)) out.push(word(LENGTH_WORDS[f.length]))
    if (f.picture !== 'any' && has(PICTURE_WORDS, f.picture)) out.push(word(PICTURE_WORDS[f.picture]))
    if (f.hdr) out.push(word('grid.filter_hdr'))
    if (f.subtitles) out.push(word('grid.filter_subtitles'))
    if (f.language) out.push(languageName(f.language) || f.language)
    if (f.added !== 'any' && has(ADDED_WORDS, f.added)) out.push(word(ADDED_WORDS[f.added]))
    return out.length ? out.join(', ') : null
}

/** The line a Language chip says: the language's own name in English, the code where the browser knows none. */
export function languageName(code: string): string {
    if (!/^[a-z]{2,3}$/.test(code)) return ''
    try {
        return new Intl.DisplayNames(['en'], { type: 'language' }).of(code) ?? code.toUpperCase()
    } catch {
        return code.toUpperCase()
    }
}

/**
 * What each list played last, for this visit only, so Play random never plays the same video twice
 * in a row from one list. Oldest forgotten first; at most thirty-two lists.
 */
const lastPlayed = new Map<string, string>()
const KEPT_LISTS = 32

export const lastOf = (scope: string): string | null => lastPlayed.get(scope) ?? null

export function rememberPlayed(scope: string, id: string) {
    lastPlayed.delete(scope)
    lastPlayed.set(scope, id)
    while (lastPlayed.size > KEPT_LISTS) lastPlayed.delete(lastPlayed.keys().next().value!)
}

export const forgetPlayed = () => lastPlayed.clear()

/** A chat's, or one list's, key in `lastOf`. */
export const randomScope = (kind: 'chat' | 'later' | 'list', id: string | number) => `${kind}:${id}`
