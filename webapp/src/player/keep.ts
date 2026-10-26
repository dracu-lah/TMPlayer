// What the player keeps on this device: bookmarks (BookmarkCodec's limits), the autoplay switch and
// the subtitle look, through the page's own storage, so signing out clears them too.
import { kvRead, kvRemove, kvWrite } from '../store'

// ---- bookmarks -----------------------------------------------------------------------------

export interface Bookmark {
    id: number
    /** Seconds. */
    at: number
    note: string
}

const MAX_BOOKMARKS = 200
export const MAX_NOTE = 120
const SAME_MOMENT = 1

/** BookmarkCodec.cleanNote: one line, trimmed, cut to 120. */
export const cleanNote = (note: string) => note.replace(/[\u0000-\u001f\u007f]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, MAX_NOTE).trim()

const bookmarkKey = (chatId: number, messageId: number) => `b:${chatId}:${messageId}`

export async function loadBookmarks(chatId: number, messageId: number): Promise<Bookmark[]> {
    const list = await kvRead<Bookmark[]>(bookmarkKey(chatId, messageId))
    return Array.isArray(list) ? [...list].sort((a, b) => a.at - b.at || a.id - b.id) : []
}

function keep(chatId: number, messageId: number, list: Bookmark[]): Bookmark[] {
    const sorted = [...list].sort((a, b) => a.at - b.at || a.id - b.id)
    if (sorted.length) kvWrite(bookmarkKey(chatId, messageId), sorted)
    else kvRemove(bookmarkKey(chatId, messageId))
    return sorted
}

/** BookmarkBook.add: one bookmark per moment, 200 a video; a second press at a moment only adds its note. */
export function addBookmark(chatId: number, messageId: number, list: Bookmark[], at: number, note = ''): { list: Bookmark[]; saved: Bookmark } {
    const clean = cleanNote(note)
    const next = [...list]
    const near = next.findIndex((b) => Math.abs(b.at - at) < SAME_MOMENT)
    let saved: Bookmark
    if (near >= 0) {
        if (clean) next[near] = { ...next[near], note: clean }
        saved = next[near]
    } else {
        saved = { id: Math.max(Date.now(), Math.max(0, ...next.map((b) => b.id)) + 1), at: Math.max(0, at), note: clean }
        next.push(saved)
        if (next.length > MAX_BOOKMARKS) next.splice(next.indexOf(next.reduce((a, b) => (b.id < a.id ? b : a))), 1)
    }
    return { list: keep(chatId, messageId, next), saved }
}

export const renameBookmark = (chatId: number, messageId: number, list: Bookmark[], id: number, note: string) =>
    keep(chatId, messageId, list.map((b) => (b.id === id ? { ...b, note: cleanNote(note) } : b)))

export const deleteBookmark = (chatId: number, messageId: number, list: Bookmark[], id: number) => keep(chatId, messageId, list.filter((b) => b.id !== id))

/** Puts one back where it was (the Undo after a delete). */
export const restoreBookmark = (chatId: number, messageId: number, list: Bookmark[], b: Bookmark) => keep(chatId, messageId, [...list, b])

// ---- the switches this device keeps ---------------------------------------------------------

/** Autoplay the next episode: on unless turned off (Settings: "Play the next episode automatically"). */
export const AUTOPLAY_KEY = 'player:autoplay'
export async function readAutoplay(): Promise<boolean> {
    return (await kvRead<boolean>(AUTOPLAY_KEY)) !== false
}
export function writeAutoplay(on: boolean) {
    if (on) kvRemove(AUTOPLAY_KEY)
    else kvWrite(AUTOPLAY_KEY, false)
}

/** How subtitles look (the app's SubtitleSize, SubtitleColour, SubtitleEdge, SubtitleBox, SubtitlePosition). */
export interface SubtitleLook {
    size: 'small' | 'medium' | 'large' | 'extra_large'
    colour: 'white' | 'yellow' | 'cyan' | 'green' | 'pink'
    edge: 'outline' | 'shadow' | 'none'
    /** The box behind the text, as an opacity in percent. */
    box: 0 | 35 | 60 | 80 | 100
    position: 'bottom' | 'raised' | 'high'
}
export const DEFAULT_LOOK: SubtitleLook = { size: 'medium', colour: 'white', edge: 'outline', box: 0, position: 'bottom' }
export const SIZE_FRACTION = { small: 0.052, medium: 0.065, large: 0.08, extra_large: 0.095 }
export const COLOUR_HEX = { white: '#FFFFFF', yellow: '#FFE14D', cyan: '#5CE6FF', green: '#7CF08A', pink: '#FF9AE0' }
export const BOTTOM_FRACTION = { bottom: 0.08, raised: 0.16, high: 0.26 }
const LOOK_KEY = 'player:subtitle-look'
export async function readLook(): Promise<SubtitleLook> {
    const saved = await kvRead<Partial<SubtitleLook>>(LOOK_KEY)
    return { ...DEFAULT_LOOK, ...(saved ?? {}) }
}
export function writeLook(look: SubtitleLook) {
    if (JSON.stringify(look) === JSON.stringify(DEFAULT_LOOK)) kvRemove(LOOK_KEY)
    else kvWrite(LOOK_KEY, look)
}
