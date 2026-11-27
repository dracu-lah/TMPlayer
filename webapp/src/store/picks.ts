// "Choose the right poster" (the apps' corrections): the TMDB title picked for a video.
import { createSignal } from 'solid-js'
import { decodePick, encodePick, type Pick } from '../pick'
import { allEntries, id, remove, write } from './kv'

// One stored line per video as the apps keep a correction (`fix:<chat>:<message>` = `tmdb=film:123`),
// on this device only.

const [picks, setPicks] = createSignal<ReadonlyMap<string, Pick>>(new Map())
export { picks, setPicks }

/** The title picked for a video, or undefined for none. Reactive: a pick redraws what read it. */
export const pickOf = (chatId: number, messageId: number): Pick | undefined => picks().get(id(chatId, messageId))

/**
 * Picks `pick` for each of `videos` (the video, its copies, and the others of its title when the
 * viewer asked for them), or takes the picks off with null: back to the name's own match.
 */
export function setPick(videos: readonly { chatId: number; messageId: number }[], pick: Pick | null) {
    const next = new Map(picks())
    for (const v of videos) {
        const key = id(v.chatId, v.messageId)
        if (pick) {
            next.set(key, pick)
            write(`fix:${key}`, encodePick(pick))
            write(`fixat:${key}`, Date.now())
        } else if (next.delete(key)) {
            remove(`fixat:${key}`)
            remove(`fix:${key}`)
        }
    }
    setPicks(next)
}

export async function loadPicks() {
    const stored = await allEntries<unknown>('fix:')
    const found = new Map<string, Pick>()
    for (const [key, text] of stored) {
        const pick = decodePick(text)
        if (pick) found.set(key.slice('fix:'.length), pick)
    }
    setPicks(found)
}
