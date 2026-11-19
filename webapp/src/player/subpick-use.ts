// The player's side of the remembered online subtitle (HX25): the keys a video is filed under (core's
// rule, answered by the Telegram worker), what opening a video finds, and the four things a viewer
// does that the memory follows (an online subtitle picked, Off, an online track chosen again, any other
// subtitle chosen). Everything here is best effort: a failure means the pick is asked for again, never
// an error card. subpicks.ts holds the store and is plain; this file is page code.
import { call } from '../tg/rpc'
import { msoneOn } from '../msoneFlag'
import { download, fileKey, keptFile, knownHealth, type Hit } from './msone'
import { clearOff, forgetPick, rememberOff, rememberPick, restorePick, type Restored } from './subpicks'
import { onPicksWiped } from './subpicks-db'
import type { VideoItem } from '../tg/types'

type Ref = Pick<VideoItem, 'chatId' | 'messageId' | 'title' | 'fileName'>

const id = (item: Ref) => `${item.chatId}:${item.messageId}`
const keys = new Map<string, Promise<string[]>>()
const MAX_REMEMBERED_ASKS = 20

/** What the worker's rule says, else the post alone (core files a post under `msg:<chat>:<message>`). */
export function pickKeys(item: Ref): Promise<string[]> {
    const at = id(item)
    const known = keys.get(at)
    if (known) return known
    const post = item.messageId ? [`msg:${item.chatId}:${item.messageId}`] : []
    const asked = call('subtitlePickKeys', [item.chatId, item.messageId, item.title ?? '', item.fileName ?? '']).then(
        (list) => (Array.isArray(list) && list.every((k) => typeof k === 'string') && list.length ? list : post),
        () => post,
    )
    if (keys.size >= MAX_REMEMBERED_ASKS) keys.delete(keys.keys().next().value as string)
    keys.set(at, asked)
    return asked
}

/**
 * Whether the line allows a download of the remembered file again: MSone offered here and not
 * paused or stopped, the browser online, and no data saver or cellular line asked for (the web has
 * no kids lock; WB26 adds a seam here if it ever does).
 */
export function mayDownload(): boolean {
    if (!msoneOn()) return false
    if (typeof navigator !== 'undefined' && navigator.onLine === false) return false
    const connection = (navigator as Navigator & { connection?: { saveData?: boolean; type?: string } }).connection
    if (connection?.saveData || connection?.type === 'cellular') return false
    const state = knownHealth().state
    return state === 'on' || state === 'unknown'
}

/** A lookup nobody took this long after it started is stale (the player never mounted its controls). */
const STALE_MS = 30_000
interface Started {
    at: number
    run: Promise<Restored>
    /** What counted the viewer's choices and the wipes when it began: a change since means the answer is no longer wanted. */
    choices: number
    wipes: number
}
const started = new Map<string, Started>()
/** Per video: how many times the viewer has chosen a subtitle (any track, Off, an own file, a chat file, MSone's list) in this page. */
const choices = new Map<string, number>()
let wipes = 0
// A sign out, a session that ended or MSone stopping: what is held in memory (a restored file included) goes with the store.
onPicksWiped(() => {
    wipes++
    started.clear()
    keys.clear()
})

const chose = (item: Ref) => {
    const at = id(item)
    choices.set(at, (choices.get(at) ?? 0) + 1)
}

/**
 * Starts looking for the video's remembered subtitle, so the answer is there when the controls
 * mount; asked twice for the same video it is one lookup. Never rejects.
 */
function warm(item: Ref): Started {
    const at = id(item)
    const known = started.get(at)
    if (known && Date.now() - known.at < STALE_MS) return known
    const run = pickKeys(item)
        .then((list) =>
            restorePick(list, {
                stopped: () => knownHealth().state === 'stopped',
                cached: (hit) => keptFile(fileKey(hit)),
                mayDownload,
                download: async (hit) => {
                    const r = await download(hit, { season: null, episode: null })
                    return r.ok ? r.file : null
                },
            }),
        )
        .catch((): Restored => ({ kind: 'none' }))
    const entry = { at: Date.now(), run, choices: choices.get(at) ?? 0, wipes }
    if (started.size >= MAX_REMEMBERED_ASKS) started.delete(started.keys().next().value as string)
    started.set(at, entry)
    return entry
}

export function warmRestore(item: Ref): Promise<Restored> {
    return warm(item).run
}

/**
 * The answer once, then forgotten (the next opening of the video asks the store again), with `wanted()`:
 * false once the viewer has chosen a subtitle since the lookup began (Off while a download was pending,
 * say) or the picks were wiped. Then the file is neither laid nor kept.
 */
export async function takeRestore(item: Ref): Promise<{ found: Restored; wanted: () => boolean }> {
    const entry = warm(item)
    const found = await entry.run
    if (started.get(id(item)) === entry) started.delete(id(item))
    return { found, wanted: () => (choices.get(id(item)) ?? 0) === entry.choices && wipes === entry.wipes }
}

const quietly = (work: Promise<void>) => void work.catch(() => {})

/**
 * Looks the video's keys up, then runs `job` with them, unless a wipe (sign out, a session that ended, MSone
 * stopping) happened since the viewer acted: the lookup goes through the worker, so a wipe can land while it is
 * pending, and the store's own generation check only starts when the write is queued, after that wait.
 */
function afterKeys<T>(item: Ref, job: (keys: string[]) => Promise<T>): Promise<void> {
    const seen = wipes
    return pickKeys(item).then((k) => (seen === wipes ? job(k).then(() => {}) : undefined))
}

/** An online subtitle was picked for the video: kept with its file. */
export function picked(item: Ref, hit: Hit, file: File) {
    chose(item)
    quietly(afterKeys(item, (k) => rememberPick(k, hit, file)))
}

/** A restored subtitle that had to be downloaded again, laid and still wanted: kept again so the next opening finds its own copy. Not a choice. */
export function keepRestored(item: Ref, hit: Hit, file: File) {
    quietly(afterKeys(item, (k) => rememberPick(k, hit, file)))
}

/** The viewer turned subtitles off. */
export function choseOff(item: Ref) {
    chose(item)
    quietly(afterKeys(item, rememberOff))
}

/** The viewer chose the online track again: an Off kept for the video no longer stands. */
export function choseOnline(item: Ref) {
    chose(item)
    quietly(afterKeys(item, clearOff))
}

/** Any other explicit choice (an embedded track, a file of their own, one from the chat): the pick no longer stands. */
export function choseOther(item: Ref) {
    chose(item)
    quietly(afterKeys(item, forgetPick))
}
