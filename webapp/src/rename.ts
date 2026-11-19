// "Rename" on the web: the title a viewer gave one video, in place of what its file name reads as
// (the apps' VideoCorrection.title). Pure, so the page, the store, the Telegram worker and the sync
// file share one reading of it (rename.test.ts, sync/local.test.ts).
//
// It is outside data wherever it comes from (a text field, storage, the sync file), so it is read
// as `unknown` and cleaned: one line, no control or direction override characters, cut to the
// apps' limit at a whole character, nothing left meaning "no rename".

/** VideoCorrections.TITLE_MAX: the longest title a rename keeps. */
export const RENAME_MAX = 120

/** Line breaks and other control characters read as a space (VideoCorrection.encode does the same for a break). */
const CONTROL = new RegExp(`[\\x00-\\x1f\\x7f-\\x9f${String.fromCharCode(0x2028, 0x2029)}]+`, 'g')
/** Direction embeddings, overrides and isolates (U+202A to U+202E, U+2066 to U+2069): a title that reorders the text around it is not a title. */
const DIRECTION = new RegExp(`[${String.fromCharCode(0x202a, 0x202b, 0x202c, 0x202d, 0x202e, 0x2066, 0x2067, 0x2068, 0x2069)}]`, 'g')

/**
 * `raw` as a title, or null when nothing is left (not a string, blank): the form every store, sync
 * line and sheet field goes through. `max` is the longest (RENAME_MAX; the sync file's own is 60).
 */
export function cleanTitle(raw: unknown, max = RENAME_MAX): string | null {
    if (typeof raw !== 'string') return null
    // Read only as far as it can matter: a stored value may be absurdly long.
    const text = raw.slice(0, max * 8).replace(CONTROL, ' ').replace(DIRECTION, '').trim()
    if (!text) return null
    return atMost(text, max).trim() || null
}

/** `text` cut to `max` characters, never through a surrogate pair (an emoji at the limit goes whole or not at all). */
function atMost(text: string, max: number): string {
    if (text.length <= max) return text
    const last = text.charCodeAt(max - 1)
    return text.slice(0, last >= 0xd800 && last <= 0xdbff ? max - 1 : max)
}

const LINE = /\r\n|\r|\n/
const TITLE = 'title='

/** The rename in a correction's stored lines (`separate`, `wrong`, `tmdb=film:1`, `title=...`), or null. */
export function titleOf(text: unknown, max = RENAME_MAX): string | null {
    if (typeof text !== 'string') return null
    for (const line of text.slice(0, 4_000).split(LINE)) if (line.startsWith(TITLE)) return cleanTitle(line.slice(TITLE.length), max)
    return null
}

/** The line a rename is stored as. */
export const titleLine = (title: string): string => `${TITLE}${title}`

/**
 * What the sheet's field holds once the viewer types: cut at the limit (a paste of a whole caption
 * is cut, not refused), line breaks as spaces, but spaces at the ends kept so typing a space works.
 */
export function typed(raw: string): string {
    return atMost(raw.slice(0, RENAME_MAX * 8).replace(CONTROL, ' ').replace(DIRECTION, ''), RENAME_MAX)
}

/**
 * What Save does with the field: `save` a new title, `reset` (the field emptied on a video that is
 * renamed: the file name reads again), or null when nothing would change (so Save is not offered).
 * `shown` is the title on show now, `renamed` the viewer's own when there is one.
 */
export function saveOf(field: string, shown: string, renamed: string | undefined): 'save' | 'reset' | null {
    const next = cleanTitle(field)
    if (renamed) return next === null ? 'reset' : next === cleanTitle(renamed) ? null : 'save'
    return next === null || next === cleanTitle(shown) ? null : 'save'
}
