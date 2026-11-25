// Sync across my devices (the apps' ResumeSync) for the web: the switch, the schedule, the
// status line and the question Home asks once. The rules are in format.ts, local.ts and run.ts; this
// file joins them to the stores, the worker and the page.
//
// Off by default, and while it is off nothing is read from or written to Saved Messages. Turning
// it on is the viewer's own act: the switch in Settings, Privacy and lock, or "Yes, keep it" on
// the card Home shows once.
import { createRoot, createSignal } from 'solid-js'
import { api, errorText } from '../tg/rpc'
import { me } from '../session'
import { names, writesOpen } from '../accounts'
import { language, t } from '../i18n'
import { REMOVAL_DAYS } from './format'
import { freshState, parseState, runSync, type Deps, type Outcome, type State } from './run'
import type { Lists, Writer } from './local'
import type { VideoItem } from '../tg/types'
import {
    kvRead,
    kvWrite,
    laterList,
    namedListsDoc,
    onListsChanged,
    resumes,
    syncDropLater,
    syncDropResume,
    syncDropStar,
    syncDropWatched,
    syncMergeLists,
    syncPicks,
    syncPutLater,
    syncPutPick,
    syncPutTitle,
    syncPutResume,
    syncPutStar,
    syncPutWatched,
    syncStars,
    syncTitles,
    syncTitleTombs,
    syncTombs,
    syncWrote,
    watchedList,
} from '../store'

const STATE_KEY = 'sync:state'
const STATUS_KEY = 'sync:status'
const CARD_KEY = 'sync:card'

/** SyncScheduler's: the first run after the app opens, how long saves settle, the shortest gap between runs. */
export const START_DELAY_MS = 8_000
export const SETTLE_MS = 20_000
export const LEAVE_SETTLE_MS = 2_000
export const MIN_GAP_MS = 3 * 60 * 1000
export const LEAVE_GAP_MS = 30 * 1000

/** What the last run came to, for the Settings row (SyncStatus). */
export interface Status {
    kind: 'done' | 'noConnection' | 'waitingForFile' | 'twoCopies' | 'failed' | 'unreadable' | 'tooNew' | 'otherAccount'
    at: number
    received: number
    dropped: number
    detail: string
}

const [state, setState] = createRoot(() => createSignal<State>(freshState()))
const [status, setStatus] = createRoot(() => createSignal<Status | null>(null))
const [running, setRunning] = createRoot(() => createSignal(false))
const [cardSeen, setCardSeen] = createRoot(() => createSignal(true))
const [loaded, setLoaded] = createRoot(() => createSignal(false))

/** The switch: on only once the viewer turned it on. */
export const syncOn = () => state().on
export const syncStatus = status
export const syncRunning = running
/** A file was ever written or its account noted: "Turn off and delete" has something to delete. */
export const syncHasFile = () => state().file !== 0 || state().account !== 0

/** Reads what was kept: the switch, the last status, whether Home asked already. Called once at start. */
export async function loadSync(): Promise<void> {
    const saved = readShared() ?? parseState(await kvRead<State>(STATE_KEY))
    if (saved) setState(saved)
    const last = await kvRead<Status>(STATUS_KEY)
    if (last && typeof last === 'object' && typeof last.at === 'number') setStatus(last)
    setCardSeen((await kvRead<boolean>(CARD_KEY)) === true)
    setLoaded(true)
}

/**
 * The state is also kept in localStorage, which every tab of this site shares: the switch and the
 * file a tab last used are read from there before each run, and a change in another tab arrives as
 * a `storage` event, so a tab that was open when the switch was turned off does not sync on.
 */
// Per profile (HX88): the adopted profile's keeps its name.
const SHARED_KEY = names.syncShared
function readShared(): State | null {
    try {
        return parseState(JSON.parse(localStorage.getItem(SHARED_KEY) ?? 'null'))
    } catch {
        return null
    }
}

async function patch(p: Partial<State>) {
    // A run that ends after the page began leaving its profile (HX88) writes nothing of it.
    if (!writesOpen()) return
    const next = { ...(readShared() ?? state()), ...p }
    setState(next)
    kvWrite(STATE_KEY, next)
    try {
        localStorage.setItem(SHARED_KEY, JSON.stringify(next))
    } catch {}
}

/** The state as it is now, across tabs. */
function currentState(): State {
    const shared = readShared()
    if (shared) setState(shared)
    return state()
}

function note(s: Status | null) {
    setStatus(s)
    if (s) kvWrite(STATUS_KEY, s)
}

// ---- the stores, as the sync reads and writes them ---------------------------------------------

async function lists(): Promise<Lists> {
    const since = Date.now() - REMOVAL_DAYS * 24 * 60 * 60 * 1000
    const [left, done, later, stars, picks, titles, tombs, titleTombs] = await Promise.all([resumes(), watchedList(), laterList(), syncStars(), syncPicks(), syncTitles(), syncTombs(since), syncTitleTombs(since)])
    return {
        resumes: left.map((r) => ({ chatId: r.chatId, messageId: r.messageId, position: r.position, length: r.length, time: r.time })),
        watched: done.map((w) => ({ chatId: w.chatId, messageId: w.messageId, time: w.time })),
        // A whole show saved is the apps' own line (its key is theirs): only single videos travel.
        later: later.filter((l) => !l.show).map((l) => ({ chatId: l.item.chatId, messageId: l.item.messageId, time: l.time, title: l.item.title, size: l.item.size, duration: l.item.duration })),
        stars: stars.map((s) => ({ chatId: s.chatId, time: s.time })),
        named: namedListsDoc(),
        picks: picks.map((p) => ({ chatId: p.chatId, messageId: p.messageId, text: p.text, at: p.at })),
        titles,
        tombs,
        titleTombs,
    }
}

const writer: Writer = {
    putResume: (r) => syncPutResume(r),
    dropResume: (c, m) => syncDropResume(c, m),
    putWatched: (c, m, at) => syncPutWatched(c, m, at),
    dropWatched: (c, m) => syncDropWatched(c, m),
    video: (c, m) => api.video(c, m),
    putLater: (item, at) => syncPutLater(item as VideoItem, at),
    dropLater: (c, m) => syncDropLater(c, m),
    putStar: (c, at) => syncPutStar(c, at),
    dropStar: (c) => syncDropStar(c),
    putPick: (c, m, pick, at) => syncPutPick(c, m, pick, at),
    putTitle: (c, m, title, at) => syncPutTitle(c, m, title, at),
    mergeLists: (raw) => syncMergeLists(raw, (c, m) => api.video(c, m)),
}

/** What the backup reads and writes (backupFlow.ts): the same lists and writer a sync uses. */
export const localLists = lists
export const localWriter = writer

function deps(): Deps {
    return {
        now: () => Date.now(),
        account: me()?.id ?? 0,
        appVersion: 'web',
        caption: t('settings.resume_sync_caption'),
        state: { get: async () => currentState(), set: patch },
        transport: {
            files: (known) => api.syncFiles(known),
            write: (target, bytes, caption) => api.syncWrite(target, bytes, caption),
            remove: (ids) => api.syncDelete(ids),
        },
        lists,
        writer,
        offline: () => typeof navigator !== 'undefined' && navigator.onLine === false,
        humanise: (e) => errorText(e) || String((e as Error)?.message ?? e),
    }
}

const statusOf = (o: Outcome, at: number): Status | null => {
    switch (o.kind) {
        case 'off':
            return null
        case 'done':
            return { kind: 'done', at, received: o.received, dropped: o.dropped, detail: '' }
        case 'failed':
            return { kind: 'failed', at, received: 0, dropped: 0, detail: o.reason }
        case 'tooNew':
        case 'unreadable':
        case 'otherAccount':
        case 'noConnection':
        case 'waitingForFile':
        case 'twoCopies':
            return { kind: o.kind, at, received: 0, dropped: 0, detail: '' }
    }
}

let busy: Promise<Outcome> | null = null
let lastRunAt = 0

/** One sync now. [manual] is "Sync now"; [replace] writes this device's lists over a file that could not be read, which only the viewer asks for. */
export function syncNow(o: { push?: boolean; replace?: boolean } = {}): Promise<Outcome> {
    // A second ask while one runs waits for it, as the apps' lock does.
    busy ??= (async () => {
        setRunning(true)
        try {
            const outcome = await runSync(deps(), o)
            if (outcome.kind === 'done' && outcome.received > 0) syncWrote()
            lastRunAt = Date.now()
            const s = statusOf(outcome, lastRunAt)
            if (s) note(s)
            return outcome
        } finally {
            setRunning(false)
            busy = null
        }
    })()
    return busy
}

// ---- the schedule (SyncScheduler) ---------------------------------------------------------------

type Why = 'turnedOn' | 'start' | 'settled' | 'left'
let timer = 0
let due = 0

function ask(why: Why) {
    if (!state().on || !me()) return
    const delay = why === 'turnedOn' ? 0 : why === 'start' ? START_DELAY_MS : why === 'left' ? LEAVE_SETTLE_MS : SETTLE_MS
    const gap = why === 'turnedOn' ? 0 : why === 'left' ? LEAVE_GAP_MS : MIN_GAP_MS
    const at = Math.max(Date.now() + delay, lastRunAt + gap)
    // A sooner ask takes the timer; a later one waits behind it.
    if (timer && due <= at) return
    clearTimeout(timer)
    due = at
    timer = window.setTimeout(() => {
        timer = 0
        due = 0
        if (document.visibilityState === 'hidden' && why !== 'left') return
        void syncNow()
    }, Math.max(0, at - Date.now()))
}

/** The app opened signed in: a sync soon, if the viewer turned it on. */
export const syncSoon = () => ask('start')

let hooked = false
/** Once, when the app is signed in: a change to a list is pushed after it settles, leaving the page pushes at once. */
export function startSync() {
    if (!hooked) {
        hooked = true
        onListsChanged(() => {
            // What a sync writes itself is not a reason for another.
            if (!running()) ask('settled')
        })
        document.addEventListener('visibilitychange', () => {
            if (document.visibilityState === 'hidden') ask('left')
            else if (Date.now() - lastRunAt > MIN_GAP_MS) ask('start')
        })
        window.addEventListener('online', () => ask('start'))
        // The switch or the file changed in another tab.
        window.addEventListener('storage', (e) => {
            if (e.key !== SHARED_KEY) return
            const now = readShared()
            if (now) setState(now)
            if (!now?.on) (clearTimeout(timer), (timer = 0), (due = 0))
        })
    }
    syncSoon()
}

/** Signed in: what was kept is read (the switch, the last line, whether Home asked), then the first sync is scheduled if the viewer turned it on. */
export function openSync() {
    void loadSync().then(startSync, () => {})
}

/** The switch. Turning on is the viewer's act and syncs at once; turning off leaves the file where it is (Delete takes it away). */
export async function setSyncOn(on: boolean): Promise<void> {
    await patch({ on })
    if (on) {
        setCardSeen(true)
        kvWrite(CARD_KEY, true)
        ask('turnedOn')
    } else {
        clearTimeout(timer)
        timer = 0
        due = 0
    }
}

/** "Turn off and delete the sync file": off here, and the file (and an older TMPlayer's message) out of Saved Messages. True when it is gone. */
export async function turnOffAndDelete(): Promise<boolean> {
    // Off first, so a sync under way writes nothing more, then it is waited for: no edit of the file meets its deletion.
    await setSyncOn(false)
    if (busy) await busy.catch(() => {})
    const known = currentState().file
    try {
        const gone = await api.syncDeleteAll(known)
        if (gone) await patch({ file: 0, sentAt: 0 })
        return gone
    } catch {
        return false
    }
}

/** Sign out or a session ended: nothing in memory outlives the account (what is kept is cleared with the rest). */
export function stopSync() {
    clearTimeout(timer)
    timer = 0
    due = 0
    lastRunAt = 0
    try {
        localStorage.removeItem(SHARED_KEY)
    } catch {}
    setState(freshState())
    setStatus(null)
    setCardSeen(true)
    setLoaded(false)
}

// ---- Home's question ---------------------------------------------------------------------------

/** Whether Home asks now: never asked and sync not already on (a switch turned on in Settings, or by an earlier visit, never shows it). */
export const keepPlacePending = () => loaded() && !cardSeen() && !state().on

/** Counted as shown, for good: one showing, then never again. */
export function keepPlaceShown() {
    if (cardSeen()) return
    setCardSeen(true)
    kvWrite(CARD_KEY, true)
}

/** "Yes, keep it": the switch on and a first sync at once. */
export const keepPlaceYes = () => setSyncOn(true)

// ---- the Settings row's words (SyncStatus.text) -------------------------------------------------

const when = (at: number) => new Intl.DateTimeFormat(language(), { dateStyle: 'medium', timeStyle: 'short' }).format(at)

/** The status line under the switch. */
export function statusLine(): string {
    if (running()) return t('settings.resume_sync_syncing')
    const s = status()
    if (!s) return t('settings.resume_sync_never')
    switch (s.kind) {
        case 'done': {
            const done = t('settings.resume_sync_done', { time: when(s.at), count: s.received })
            return s.dropped > 0 ? `${done} ${t('settings.resume_sync_dropped', { count: s.dropped })}` : done
        }
        case 'noConnection':
            return t('settings.resume_sync_offline')
        case 'waitingForFile':
            return t('settings.resume_sync_pending')
        case 'twoCopies':
            return t('settings.resume_sync_two_copies')
        case 'failed':
            return t('settings.resume_sync_failed', { reason: s.detail || '?' })
        case 'unreadable':
            return t('settings.resume_sync_unreadable')
        case 'tooNew':
            return t('settings.resume_sync_too_new')
        case 'otherAccount':
            return t('settings.resume_sync_other_account')
    }
}
