// Save to device (WP-86): a video's file out of Telegram into the browser's own download, streamed by
// the service worker (public/sw.js `saveStream`) a piece at a time, so no copy is held and no partial
// file is left: the worker ends the stream with an error on a cancel or a failed read, which the
// browser answers by dropping the download. The rules here are pure: what may be saved, what the file is
// called, and how the worker's progress messages add up. `node --test` runs them.

export interface Savable {
    key: string
    size: number
    title: string
    fileName?: string
    mime?: string
    /** False under "restrict saving content" (the chat's or the message's): it streams, nothing is kept (Telegram's terms). */
    canBeSaved: boolean
    /** A video cut into pieces: not one file to hand out. */
    joined?: unknown
    /** A ZIP: the file inside it is not a file of its own. */
    zip?: boolean
}

export interface Env {
    /** The page has a service worker answering for it. */
    worker: boolean
    /** The browser has ReadableStream, which the worker's answer is. */
    streams: boolean
}

/** Why a video cannot be saved here, or null when it can. */
export type Why = 'restricted' | 'pieces' | 'archive' | 'noSize' | 'noWorker' | 'noStreams'

export function whyNot(item: Savable, env: Env): Why | null {
    if (!item.canBeSaved) return 'restricted'
    if (item.joined) return 'pieces'
    if (item.zip) return 'archive'
    if (!(item.size > 0)) return 'noSize'
    if (!env.worker) return 'noWorker'
    if (!env.streams) return 'noStreams'
    return null
}

/** Whether the row is offered at all: hidden, never greyed, where the video or the browser cannot. */
export const offered = (item: Savable, env: Env) => whyNot(item, env) === null

/** A name the browser will keep: no path, no control characters, no leading dots, 120 characters. */
export function fileNameOf(item: Pick<Savable, 'fileName' | 'title' | 'mime'>): string {
    const raw = (item.fileName || item.title || '').normalize('NFC')
    const clean = raw
        .replace(/[\u0000-\u001f\u007f<>:"/\\|?*]/g, ' ')
        // A name that is only dots (what "../.." leaves) is nothing.
        .replace(/(^|\s)\.+(?=\s|$)/g, '$1')
        .replace(/\s+/g, ' ')
        .trim()
        .replace(/^\.+/, '')
        .slice(0, 120)
        .trim()
    const base = clean || 'video'
    // A name with no extension takes the video's from its type, so the saved file opens.
    if (/\.[a-z0-9]{2,5}$/i.test(base)) return base
    const ext = { 'video/mp4': 'mp4', 'video/x-matroska': 'mkv', 'video/webm': 'webm', 'video/quicktime': 'mov' }[(item.mime ?? '').split(';')[0]!.trim()] ?? 'mp4'
    return `${base}.${ext}`
}

/** The address the worker answers with the file as a download. */
export function saveUrl(o: { streamKey: string; size: number; mime: string; profile: string; name: string; id: string }): string {
    return `/stream/${encodeURIComponent(o.streamKey)}?size=${o.size}&mime=${encodeURIComponent(o.mime.split(';')[0]!)}&p=${encodeURIComponent(o.profile)}&save=${encodeURIComponent(o.name)}&id=${encodeURIComponent(o.id)}`
}

/** What the worker tells the page about one save. */
export interface SaveMessage {
    type: 'save'
    id: string
    state: 'progress' | 'done' | 'failed' | 'cancelled'
    loaded?: number
    why?: string
}

export interface Save {
    id: string
    name: string
    size: number
    loaded: number
    state: 'running' | 'done' | 'failed' | 'cancelled'
    why?: string
}

/** A message from the worker, read as what it is: anything that is not a save message of ours is ignored (null). */
export function readMessage(data: unknown): SaveMessage | null {
    if (!data || typeof data !== 'object') return null
    const m = data as Record<string, unknown>
    if (m.type !== 'save' || typeof m.id !== 'string') return null
    const state = m.state
    if (state !== 'progress' && state !== 'done' && state !== 'failed' && state !== 'cancelled') return null
    return { type: 'save', id: m.id, state, ...(typeof m.loaded === 'number' && Number.isFinite(m.loaded) ? { loaded: m.loaded } : {}), ...(typeof m.why === 'string' ? { why: m.why.slice(0, 40) } : {}) }
}

/** The saves after a message: the one it names moves on, one that is over stays as it ended (a summary), others are untouched. */
export function apply(saves: readonly Save[], msg: SaveMessage): Save[] {
    return saves.map((s) => {
        if (s.id !== msg.id || s.state !== 'running') return s
        switch (msg.state) {
            case 'progress':
                return { ...s, loaded: Math.min(s.size, Math.max(s.loaded, msg.loaded ?? s.loaded)) }
            case 'done':
                return { ...s, loaded: s.size, state: 'done' }
            case 'failed':
                return { ...s, state: 'failed', ...(msg.why ? { why: msg.why } : {}) }
            case 'cancelled':
                return { ...s, state: 'cancelled' }
        }
    })
}

/** A summary line key for a finished save, and the reason keys the worker's words map to. */
export const reasonOf = (why: string | undefined): 'gone' | 'other' | 'read' => (why === 'gone' ? 'gone' : why === 'other' ? 'other' : 'read')
