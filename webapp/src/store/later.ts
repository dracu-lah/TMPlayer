// Watch later: the video itself is kept, so the list draws without asking Telegram.
import { createSignal } from 'solid-js'
import type { VideoItem } from '../tg/types'
import { all, id, remove, write } from './kv'

export interface Later {
    item: VideoItem
    time: number
    /**
     * Set for a whole show saved (HX20), as the apps keep it: the show's name, lower-cased, which
     * with the chat is the line's key whatever episode comes next. [item] is one of its episodes
     * under the show's name, whose page lists the show's seasons.
     */
    show?: string
}
const [laterIds, setLaterIds] = createSignal<ReadonlySet<string>>(new Set())
export { laterIds, setLaterIds }

/** A show's Watch later key: its chat and its name, lower-cased. */
export const showLaterId = (chatId: number, show: string) => `show:${chatId}:${show.trim().toLowerCase()}`

/** The key a Watch later line is kept and looked up by: the video's, or the show's. */
export const laterId = (l: Later) => (l.show ? showLaterId(l.item.chatId, l.show) : id(l.item.chatId, l.item.messageId))

/**
 * Puts a whole show on Watch later, or takes it off (HX20). [episode] is the episode the page is
 * about, kept under the show's name: its card opens that page, which lists the show's seasons.
 */
export function setShowLater(episode: VideoItem, show: string, on: boolean) {
    const key = showLaterId(episode.chatId, show)
    const next = new Set(laterIds())
    if (on) {
        next.add(key)
        const item: VideoItem = { ...episode, title: show, name: show }
        write(`later:${key}`, { item, time: Date.now(), show: show.trim().toLowerCase() } satisfies Later)
    } else {
        next.delete(key)
        remove(`later:${key}`)
    }
    setLaterIds(next)
}

export function setLater(item: VideoItem, on: boolean) {
    const key = id(item.chatId, item.messageId)
    const next = new Set(laterIds())
    if (on) {
        next.add(key)
        write(`later:${key}`, { item, time: Date.now() } satisfies Later)
    } else {
        next.delete(key)
        remove(`later:${key}`)
    }
    setLaterIds(next)
}

/** Takes one video off Watch later by its ids alone (a video gone from its chat, HX21); true when it was there. */
export function dropLater(chatId: number, messageId: number): boolean {
    const key = id(chatId, messageId)
    if (!laterIds().has(key)) return false
    setLater({ chatId, messageId } as VideoItem, false)
    return true
}

/** Watch later, the latest first. */
export async function laterList(): Promise<Later[]> {
    return (await all<Later>('later:')).sort((a, b) => b.time - a.time)
}

/** The key a Watch later line goes by, for History's ticks and `removeLater`. */
export const laterKey = laterId

/**
 * Takes the Watch later lines with these keys off, or every line when `keys` is null (Clear Watch
 * later), and returns exactly what went, times included, so an Undo puts back the same order.
 */
export async function removeLater(keys: readonly string[] | null): Promise<Later[]> {
    const wanted = keys ? new Set(keys) : null
    const gone = (await laterList()).filter((l) => !wanted || wanted.has(laterId(l)))
    if (!gone.length) return gone
    const next = new Set(laterIds())
    for (const l of gone) {
        const key = laterId(l)
        next.delete(key)
        remove(`later:${key}`)
    }
    setLaterIds(next)
    return gone
}

/** Undo for `removeLater`: the same lines back, with the times they had. */
export function restoreLater(lines: readonly Later[]) {
    const next = new Set(laterIds())
    for (const l of lines) {
        const key = laterId(l)
        next.add(key)
        write(`later:${key}`, l)
    }
    setLaterIds(next)
}
