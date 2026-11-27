// "Rename": the title a viewer gave a video.
import { createSignal } from 'solid-js'
import { admit, cleanTitle } from '../rename'
import { allEntries, id, remove, VIDEO_KEY, write } from './kv'

// The title a viewer gave a video (the apps' VideoCorrection.title), one stored line per
// file (`title:<chat>:<message>` = the cleaned title, `titleat:<chat>:<message>` = when). The sync
// file carries it as the `title=` line of that video's correction. What is stored is outside data:
// read back through `cleanTitle`, the keys checked, the count bounded.

const [titles, setTitles] = createSignal<ReadonlyMap<string, string>>(new Map())
export { titles, setTitles }

/** The most renames kept (a viewer renames a handful; anything past this is not theirs). */
export const TITLES_MAX = 5_000

/** The title the viewer gave a video, or undefined for none. Reactive: a rename redraws what read it. */
export const titleOf = (chatId: number, messageId: number): string | undefined => titles().get(id(chatId, messageId))

/**
 * Gives each of `videos` (the video and its copies) the title `title`, or takes the rename off with
 * null (or a title with nothing in it): the file name reads again. The same title again changes
 * nothing, not even the time the sync file sees.
 */
export function setTitle(videos: readonly { chatId: number; messageId: number }[], title: string | null) {
    const clean = cleanTitle(title)
    let next: ReadonlyMap<string, string> = titles()
    const now = Date.now()
    let changed = false
    for (const v of videos) {
        const key = id(v.chatId, v.messageId)
        if (clean) {
            if (next.get(key) === clean) continue
            const put = admit(next, key, clean, TITLES_MAX)
            next = put.next
            // Full: the oldest rename goes, here only (the sync file keeps its own newest 1,000).
            if (put.evicted) forgetTitle(put.evicted)
            write(`title:${key}`, clean)
            write(`titleat:${key}`, now)
        } else if (next.has(key)) {
            const left = new Map(next)
            left.delete(key)
            next = left
            remove(`titleat:${key}`, true)
            remove(`title:${key}`)
        } else continue
        changed = true
    }
    if (changed) setTitles(next)
}

/** A rename dropped to make room: no removal is noted, it is not the viewer's act. */
export function forgetTitle(key: string) {
    remove(`title:${key}`, true)
    remove(`titleat:${key}`, true)
}

export async function loadTitles() {
    const [stored, times] = await Promise.all([allEntries<unknown>('title:'), allEntries<number>('titleat:')])
    const found: [string, string, number][] = []
    for (const [k, v] of stored) {
        const key = k.slice('title:'.length)
        const title = cleanTitle(v)
        const at = times.get(`titleat:${key}`)
        if (title && VIDEO_KEY.test(key)) found.push([key, title, typeof at === 'number' && Number.isFinite(at) ? at : 0])
    }
    // Oldest first, so a full store drops the oldest; a stored pile past the cap keeps the newest.
    found.sort((x, y) => x[2] - y[2])
    setTitles(new Map(found.slice(-TITLES_MAX).map(([key, title]) => [key, title])))
}
