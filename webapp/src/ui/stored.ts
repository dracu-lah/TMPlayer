// What store.ts reads back from the browser (IndexedDB, localStorage) is outside data: a file cut
// short, an older build's, a hand edit. Each shape is written once here as a schema (zod's mini
// build, tree-shaken), and a value that does not hold up is dropped, so the default stands and one
// bad entry never takes the others with it. Pure, so it runs under `node --test`.
//
// Recently added once had its own "Chosen chats" (recentSource, recentChats). It follows the chats
// added in Add chats now (2026-11-01), so an older build's keys are not in the schema: they are
// dropped on the way in and the next write leaves them behind.
import * as z from 'zod/mini'

const bytes = z.number().check(z.gte(0))

/** Settings' stored fields, each checked alone: a bad one is left out and its default stands. */
const SETTINGS = {
    theme: z.enum(['system', 'dark', 'light', 'black']),
    sizeMin: bytes,
    sizeMax: bytes,
    sort: z.enum(['Newest', 'Oldest', 'Name', 'Year', 'Size', 'Longest', 'LastPlayed', 'Unwatched']),
    posters: z.boolean(),
    trailersInApp: z.boolean(),
    // Milliseconds taken off a saved position when a video resumes; null is the app's default.
    rewindMs: z.nullable(z.number().check(z.gte(0), z.lte(600_000))),
    carousel: z.boolean(),
    homeContinue: z.boolean(),
    homeRecent: z.boolean(),
    homeStarred: z.boolean(),
    // The apps' remember_searches: recent searches are kept to pick again (on by default).
    rememberSearches: z.boolean(),
    // Reduce motion and High contrast: Automatic follows the system, On and Off are the viewer's word.
    reduceMotion: z.enum(['auto', 'on', 'off']),
    contrast: z.enum(['auto', 'on', 'off']),
    // The order of Home's rows (HomeOrder.kt) is text; anything else, or an absurd length, is no order.
    homeOrder: z.string().check(z.maxLength(100_000)),
}
type SettingsFields = { [K in keyof typeof SETTINGS]: z.infer<(typeof SETTINGS)[K]> }

/** `saved` with the keys that do not hold up, and the ones no longer known, taken out. */
export function cleanSettings(saved: unknown): Partial<SettingsFields> {
    const out: Record<string, unknown> = {}
    if (typeof saved !== 'object' || saved === null || Array.isArray(saved)) return out
    for (const [key, schema] of Object.entries(SETTINGS)) {
        const v = (saved as Record<string, unknown>)[key]
        if (v === undefined) continue
        const ok = z.safeParse(schema, v)
        if (ok.success) out[key] = ok.data
    }
    return out as Partial<SettingsFields>
}

const HOME_CHAT = z.object({ chatId: z.number(), title: z.string() })

/** A starred chat's row on Home, as last drawn. */
export interface HomeChatRow {
    chatId: number
    title: string
}

/** Home's chat rows as stored: a list that does not hold up is no list, and a row that does not is skipped. */
export function cleanHomeChats(raw: unknown): HomeChatRow[] {
    if (!Array.isArray(raw)) return []
    return raw.slice(0, 5000).flatMap((r): HomeChatRow[] => {
        const ok = z.safeParse(HOME_CHAT, r)
        return ok.success ? [{ chatId: ok.data.chatId, title: ok.data.title.slice(0, 200) }] : []
    })
}

/** Anything but the two known words, and nothing stored, reads as never asked. */
export function readAddedMode(raw: unknown): 'unanswered' | 'all' | 'picked' {
    const ok = z.safeParse(z.enum(['all', 'picked']), raw)
    return ok.success ? ok.data : 'unanswered'
}

const RESUME = z.object({
    chatId: z.number(),
    messageId: z.number(),
    position: z.number(),
    length: z._default(z.number(), 0),
    time: z._default(z.number(), 0),
})
export type StoredResume = z.infer<typeof RESUME>

/** A saved place, or null when what is stored is not one. */
export function cleanResume(raw: unknown): StoredResume | null {
    const ok = z.safeParse(RESUME, raw)
    return ok.success ? ok.data : null
}

const LAST = z.object({
    key: z.string().check(z.minLength(1)),
    resume: z.optional(RESUME),
    watched: z.optional(z.number()),
})
export type StoredLast = z.infer<typeof LAST>

/** The last save kept in localStorage for a closing page, or null when it is missing or does not hold up. */
export function parseLast(text: string | null): StoredLast | null {
    if (!text) return null
    try {
        const ok = z.safeParse(LAST, JSON.parse(text))
        return ok.success ? ok.data : null
    } catch {
        return null
    }
}

/** One change noted in localStorage until IndexedDB has it: a value, or a removal. */
export type Note = { v: unknown } | { d: 1 }
const NOTE = z.union([z.object({ d: z.literal(1) }), z.object({ v: z.unknown() })])

/** The journal of changes noted at once in localStorage; an entry that is not a note is left out. */
export function parseJournal(text: string | null): Record<string, Note> {
    const out: Record<string, Note> = {}
    let raw: unknown
    try {
        raw = JSON.parse(text || '{}')
    } catch {
        return out
    }
    if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return out
    for (const [key, n] of Object.entries(raw)) {
        const ok = z.safeParse(NOTE, n)
        if (ok.success) out[key] = 'd' in ok.data ? { d: 1 } : { v: ok.data.v }
    }
    return out
}
