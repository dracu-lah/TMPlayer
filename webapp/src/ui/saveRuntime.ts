// Save to device in the page (WP-86): the glue over saveToDevice.ts's rules. A save registers the file
// under a key of its own for the service worker to read from (stream/reader.ts `streams`), follows the
// worker's messages, and lets go of the file when the save ends however it ends. Nothing is kept: the
// bytes pass through to the browser's download.
import { createSignal } from 'solid-js'
import { activeId } from '../accounts'
import { size } from '../screens/VideoList'
import { openFile, streams } from '../stream/reader'
import { t } from '../i18n'
import type { VideoItem } from '../tg/types'
import { showToast } from './Toast'
import { apply, fileNameOf, offered, readMessage, saveUrl, type Env, type Save } from './saveToDevice'

const [saves, setSaves] = createSignal<Save[]>([])
export { saves }

/** The save's stream key: its own, so a video that plays at the same time keeps its own file. */
const streamKeyOf = (id: string) => `save:${id}`
/** A save that has not begun to move in this long did not start (the browser refused the download). */
const START_MS = 20_000
const timers = new Map<string, number>()
const frames = new Map<string, HTMLIFrameElement>()
const dropFrame = (id: string) => {
    frames.get(id)?.remove()
    frames.delete(id)
}

const env = (): Env => ({
    worker: typeof navigator !== 'undefined' && !!navigator.serviceWorker?.controller,
    streams: typeof ReadableStream === 'function',
})

/** Whether Save to device is offered for this video here: hidden where it cannot work. */
export const canSaveToDevice = (item: VideoItem) => offered(item, env())

let listening = false
function listen() {
    if (listening || !navigator.serviceWorker) return
    listening = true
    navigator.serviceWorker.addEventListener('message', (ev: MessageEvent) => {
        const m = readMessage(ev.data)
        if (!m || !saves().some((s) => s.id === m.id)) return
        clearTimeout(timers.get(m.id))
        if (m.state === 'progress') timers.set(m.id, window.setTimeout(() => end(m.id, 'failed', 'read'), START_MS * 3))
        else timers.delete(m.id)
        if (m.state === 'progress') return void setSaves((s) => apply(s, m))
        end(m.id, m.state, m.why)
    })
}

/** A save is over: the file is let go, the page says how it ended, and the line goes after a moment. */
function end(id: string, state: 'done' | 'failed' | 'cancelled', why?: string) {
    const was = saves().find((s) => s.id === id)
    if (!was || was.state !== 'running') return
    clearTimeout(timers.get(id))
    timers.delete(id)
    streams.get(streamKeyOf(id))?.close?.()
    streams.delete(streamKeyOf(id))
    dropFrame(id)
    setSaves((all) => apply(all, { type: 'save', id, state, ...(why ? { why } : {}) }))
    showToast({ kind: state === 'done' ? 'success' : state === 'cancelled' || why === 'gone' ? 'warning' : 'error', text: state === 'done' ? t('web.save_done', { name: was.name }) : state === 'cancelled' ? t('web.save_cancelled', { name: was.name }) : why === 'gone' ? t('web.gone_from_chat') : t('web.save_failed', { name: was.name }) })
    window.setTimeout(() => setSaves((all) => all.filter((s) => s.id !== id)), 4000)
}

/**
 * Starts saving `item`: the size is said, then the browser's own download begins. No room check: a page cannot know
 * the free space of the disk its Downloads folder is on (the browser reports only the site's own quota, not the disk, SEC2-12),
 * so a full disk is the browser's own failure and the line ends as "Could not save" (`end` with `failed`).
 */
export function saveToDevice(item: VideoItem): void {
    if (!canSaveToDevice(item)) return
    listen()
    // Not guessable: the key is all that names this file to the service worker (SEC2-05).
    const id = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : `${Date.now().toString(36)}${Math.random().toString(36).slice(2)}${Math.random().toString(36).slice(2)}`
    const name = fileNameOf(item)
    const file = openFile({ key: item.key, size: item.size, duration: item.duration })
    // Nothing is read ahead of the worker's own asks.
    file.wantAhead = false
    streams.set(streamKeyOf(id), file)
    setSaves((all) => [...all, { id, name, size: item.size, loaded: 0, state: 'running' }])
    showToast({ text: t('web.save_size_to_downloads', { size: size(item.size) }) })
    timers.set(id, window.setTimeout(() => end(id, 'failed', 'read'), START_MS))
    // A hidden frame, not a link with `download`: the service worker answers a frame's navigation, and a
    // response with Content-Disposition: attachment is the browser's download (StreamSaver's way, which
    // every engine that has a service worker takes). The frame goes after the save ends or a minute.
    const frame = document.createElement('iframe')
    frame.hidden = true
    frame.setAttribute('aria-hidden', 'true')
    frame.src = saveUrl({ streamKey: streamKeyOf(id), size: item.size, mime: 'video/mp4', profile: activeId, name, id })
    document.body.append(frame)
    frames.set(id, frame)
    window.setTimeout(() => dropFrame(id), 60_000)
}

/** Stops a save: the worker ends its stream with an error and the browser drops the download. */
export function cancelSave(id: string) {
    navigator.serviceWorker?.controller?.postMessage({ type: 'save-cancel', id })
    // A worker that does not answer (gone, asleep) must not leave the line up for ever.
    window.setTimeout(() => end(id, 'cancelled'), 3000)
}
