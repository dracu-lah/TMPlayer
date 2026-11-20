// The page's side of a video posted more than once (HX38, decision D5): what this browser plays
// natively and the device it is, which the worker cannot know, with the viewer's own corrections
// ("Not the same video", the copy chosen, the quality usually played), handed to the Telegram
// worker whenever one of them changes, so its cards and copies lists answer with them. A change the
// viewer made bumps `copiesTick` once the worker has it, and the lists showing cards read again.
import { createEffect, createRoot, createSignal } from 'solid-js'
import { api } from './tg/rpc'
import { form } from './form'
import { apartIds, chooseCopy, chosenCopies, copyHabit, setApart, setCopyHabit, setTitle, titleOf, titles } from './store'
import { deviceMoved, slowKinds, type CopyContext, type CopyDevice, type PictureKind } from './tg/copyFit'
import type { VideoItem } from './tg/types'

/** Bumped once the worker holds a change the viewer made: the lists of cards read again. */
const [copiesTick, setCopiesTick] = createSignal(0)
export { copiesTick }

let kinds: PictureKind[] | null = null

/** The picture kinds this browser cannot decode itself (read once: the browser does not change under the page). */
function browserSlow(): PictureKind[] {
    if (kinds) return kinds
    const w = globalThis as { ManagedMediaSource?: typeof MediaSource; MediaSource?: typeof MediaSource }
    const ms = w.ManagedMediaSource ?? w.MediaSource
    let el: HTMLVideoElement | null = null
    try {
        el = document.createElement('video')
    } catch {}
    // webOS and Tizen decode HEVC in hardware whatever their browser says (player/hevcnames.ts tvDecodesHevc).
    const tvHevc = /Web0S|webOS|Tizen/.test(navigator.userAgent)
    kinds = slowKinds((m) => !!ms?.isTypeSupported?.(m) || (el?.canPlayType(m) ?? '') !== '', tvHevc)
    return kinds
}

const deviceOf = (): CopyDevice => (form() === 'tv' ? 'tv' : form() === 'phone' ? 'phone' : 'computer')

/** What the worker is told now. */
export function copyContextNow(): CopyContext {
    const named = [...titles()].map(([id, title]) => ({ id, title })).sort((a, b) => (a.id < b.id ? -1 : 1))
    return { device: deviceOf(), slow: browserSlow(), apart: [...apartIds()], chosen: [...chosenCopies()], habit: copyHabit(), titles: named }
}

let sent: Promise<void> = Promise.resolve()

// Sent at once (before the first list is asked for), and again on every change: a rotation into
// the phone form, the store's corrections loaded or changed.
// A change of the device kind (a window narrowed into the phone form, a rotation) arranges the
// cards again once the worker has it, as a correction does: a phone's card no longer leads with 4K.
let device: CopyDevice | null = null
// A rename (made here, by the sync file, or in another tab) renames the cards and groups again, as a correction does.
let renamed: string | null = null
createRoot(() =>
    createEffect(() => {
        const ctx = copyContextNow()
        sent = api.copyContext(ctx).catch((e) => console.warn('[copies]', e))
        const moved = deviceMoved(device, ctx.device)
        device = ctx.device
        const names = JSON.stringify(ctx.titles)
        const retitled = renamed !== null && renamed !== names
        renamed = names
        if (moved || retitled) refreshed()
    }),
)

/**
 * Gives `item` (and its other copies, `copies`) the title `title`, or takes the rename off with null:
 * the cards, the groups and the video's page read it at once. Cleaned and cut at the apps' limit.
 */
export function renameVideo(item: Pick<VideoItem, 'chatId' | 'messageId'>, title: string | null, copies: readonly Pick<VideoItem, 'chatId' | 'messageId'>[] = []) {
    setTitle([item, ...copies], title)
}

/** The title the viewer gave `item`, or undefined. Reactive. */
export const renamedTitle = (item: Pick<VideoItem, 'chatId' | 'messageId'>) => titleOf(item.chatId, item.messageId)

/** Once the worker has the change just made, the lists read again. */
function refreshed() {
    // The effect above sent the new context in the same tick as the store changed.
    queueMicrotask(() => void sent.then(() => setCopiesTick(copiesTick() + 1)))
}

/** "Not the same video": `item` stands apart from the copies it was grouped with (off: grouped again). */
export function keepApart(item: Pick<VideoItem, 'chatId' | 'messageId'>, on: boolean) {
    setApart(item.chatId, item.messageId, on)
    refreshed()
}

/** Whether `item` is a file the viewer kept apart. */
export const isApart = (item: Pick<VideoItem, 'chatId' | 'messageId'>) => apartIds().has(`${item.chatId}:${item.messageId}`)

/** The viewer chose `item` among `among` in its list of copies: its cards play it from now on. */
export function chooseCopyOf(item: Pick<VideoItem, 'chatId' | 'messageId'>, among: readonly Pick<VideoItem, 'chatId' | 'messageId'>[]) {
    chooseCopy(item, among)
    refreshed()
}

/** A play of a copy whose picture is `height`: noted in the quality the viewer usually plays. */
export function notePlayed(height: number | null | undefined) {
    if (typeof height !== 'number' || !(height > 0)) return
    api.habitNoted(copyHabit(), height).then(setCopyHabit, () => {})
}
