// Telegram links on the page (WB16, the apps' U9): Copy link on a card's menu, Copy link at this time
// in the player's, Open in Telegram. The link itself is built in the worker (tg/links.ts, core's
// tgPostLink); this is what the page does with it: who is offered it, the time added, the copy and
// what to do when the browser refuses the clipboard. Pure but for `ask` and the clipboard, which
// the tests replace.
import type { LinkAnswer } from '../tg/links'
import type { VideoItem } from '../tg/types'

/** A day and a half: past any real video, so a bad position cannot make a silly number (the apps' LinkAtTime). */
export const MAX_START_S = 129_600

const TME = 'https://t.me/'

/**
 * The menus offer a link for a video from a message that may be shared: not under "restrict saving
 * content" (the chat's or the message's, which stops the video and its link being passed on), and
 * not one played from pieces, which has no single message to point at (the apps' LinkAtTime.offered).
 */
export function linkOffered(item: Pick<VideoItem, 'canBeSaved' | 'joined'>): boolean {
    return item.canBeSaved === true && !item.joined
}

/** Whether `link` is a Telegram address the page may hand to the browser (an anchor's href, the clipboard). */
export function isTgLink(link: unknown): link is string {
    if (typeof link !== 'string' || link.length > 300 || !link.startsWith(TME)) return false
    try {
        return new URL(link).origin === 'https://t.me'
    } catch {
        return false
    }
}

/** The link with `?t=<seconds>` (TMPlayer opens a link at its time); an existing `t` is replaced, other parts kept, 0 or less takes it off. */
export function withStart(link: string, seconds: number): string {
    if (!isTgLink(link)) return link
    const at = Number.isFinite(seconds) ? Math.min(MAX_START_S, Math.max(0, Math.floor(seconds))) : 0
    const url = new URL(link)
    if (at > 0) url.searchParams.set('t', String(at))
    else url.searchParams.delete('t')
    return url.toString()
}

/** Copies text. False when the browser has no clipboard or refuses it (no permission, the page not in focus); the caller then shows the text. */
export async function copyText(text: string, clip: Pick<Clipboard, 'writeText'> | undefined = typeof navigator === 'undefined' ? undefined : navigator.clipboard): Promise<boolean> {
    try {
        if (clip?.writeText) {
            await clip.writeText(text)
            return true
        }
    } catch {
        // Refused: the old way next, then the caller's.
    }
    return legacyCopy(text)
}

/** `execCommand('copy')` from a hidden field, for a page on a browser with no async clipboard (a TV's, an old Safari). */
function legacyCopy(text: string): boolean {
    if (typeof document === 'undefined' || !document.body) return false
    const field = document.createElement('textarea')
    field.value = text
    field.setAttribute('readonly', '')
    field.style.cssText = 'position:fixed;top:0;left:0;opacity:0;pointer-events:none'
    const was = document.activeElement instanceof HTMLElement ? document.activeElement : null
    document.body.append(field)
    try {
        field.select()
        return document.execCommand('copy')
    } catch {
        return false
    } finally {
        field.remove()
        was?.focus({ preventScroll: true })
    }
}

export type Asker = (chatId: number, messageId: number) => Promise<LinkAnswer>

const KEEP = 40
const asked = new Map<string, Promise<LinkAnswer>>()

/**
 * The answer for a video's message, asked once for as long as a menu is open and kept for a few (a
 * second tap, the player's menu after the card's); not kept when it is "gone" or the asking failed,
 * so Try again asks again. The page asks as a menu opens, so the copy that follows runs in the tap.
 */
export function linkOf(item: Pick<VideoItem, 'chatId' | 'messageId'>, ask: Asker): Promise<LinkAnswer> {
    const key = `${item.chatId}:${item.messageId}`
    const kept = asked.get(key)
    if (kept) return kept
    const run = ask(item.chatId, item.messageId).then(
        (answer) => {
            const ok = isLinkAnswer(answer) ? answer : { none: 'nolink' as const }
            if ((!('link' in ok) || ok.partial) && asked.get(key) === run) asked.delete(key)
            return ok
        },
        () => {
            if (asked.get(key) === run) asked.delete(key)
            return { none: 'nolink' as const }
        },
    )
    asked.set(key, run)
    while (asked.size > KEEP) asked.delete(asked.keys().next().value!)
    return run
}

/** What the worker answered, checked: the page trusts nothing that crosses the worker's edge. */
function isLinkAnswer(a: unknown): a is LinkAnswer {
    if (typeof a !== 'object' || a === null) return false
    const o = a as Record<string, unknown>
    return isTgLink(o.link) || o.none === 'gone' || o.none === 'nolink' || o.none === 'protected'
}

/** Forgets what was asked (a test, or a signed out page). */
export function forgetLinks() {
    asked.clear()
}

export type CopyOutcome =
    /** On the clipboard. */
    | { done: 'copied'; link: string }
    /** The browser refused: the page shows the link to copy by hand. */
    | { done: 'shown'; link: string }
    /** No link: the message is gone, or the chat has none. */
    | { done: 'none'; why: 'gone' | 'nolink' | 'protected' }

/** Copy the link, at `startSec` when above 0. Never throws. */
export async function copyLink(item: Pick<VideoItem, 'chatId' | 'messageId'>, startSec: number, ask: Asker, write: (text: string) => Promise<boolean> = copyText): Promise<CopyOutcome> {
    const answer = await linkOf(item, ask)
    if (!('link' in answer)) return { done: 'none', why: answer.none }
    const link = startSec > 0 ? withStart(answer.link, startSec) : answer.link
    return (await write(link)) ? { done: 'copied', link } : { done: 'shown', link }
}
