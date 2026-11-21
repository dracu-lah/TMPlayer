// Which profile this tab runs (HX88 phase B3). The book of profiles lives in localStorage, which every
// tab shares; the profile a tab chose, the PIN profile it unlocked and the work it carries over a
// reload live in its sessionStorage, so a reload stays on its profile, a new tab starts on the picker,
// and an unlock never outlives the tab. The profile is fixed for the page's life: a switch reloads the
// page into the next one, so nothing of one profile (the Telegram worker, the lists in memory, the
// caches) can outlive it. The rules are ui/accountBook.ts; what the buttons do is accountFlows.ts.
import { createSignal, untrack } from 'solid-js'
import * as B from './ui/accountBook'

const BOOK_KEY = 'tmplayer-accounts'
/** sessionStorage: the profile this tab chose (or switched to). */
const TAB_KEY = 'tmplayer-tab-profile'
/** sessionStorage: a line or a step a reload carries on with. */
const INTENT_KEY = 'tmplayer-accounts-intent'
/** sessionStorage: the PIN profile this tab entered the PIN of (only ever the open one; never in localStorage). */
const UNLOCK_KEY = 'tmplayer-tab-unlocked'
/** sessionStorage: work this tab carries on in a profile's page with no screen shown (a sign out of it, Remove all). */
const PENDING_KEY = 'tmplayer-accounts-pending'
/** sessionStorage: when the tab was hidden, for the relock after a minute away. */
const HIDDEN_KEY = 'tmplayer-tab-hidden-at'

const testMode = import.meta.env.DEV && import.meta.env.VITE_TG_TEST === '1'

function readStored(): B.Book {
    try {
        return B.readBook(localStorage.getItem(BOOK_KEY))
    } catch {
        return B.defaultBook()
    }
}

function session(key: string): string | null {
    try {
        return sessionStorage.getItem(key)
    } catch {
        return null
    }
}
function setSession(key: string, value: string | null) {
    try {
        if (value === null) sessionStorage.removeItem(key)
        else sessionStorage.setItem(key, value)
    } catch {}
}

function hiddenAt(): number | null {
    const text = session(HIDDEN_KEY)
    const n = Number(text)
    return text !== null && Number.isFinite(n) && n > 0 ? n : null
}

const first = readStored()
const tabChoice = session(TAB_KEY)
// A reload after a minute hidden starts locked, as the tab would have been.
if (B.relockDue(first, B.find(first, tabChoice)?.id ?? first.active, hiddenAt(), Date.now())) setSession(UNLOCK_KEY, null)
setSession(HIDDEN_KEY, null)
/** sessionStorage: the unlock's token, left by a page of this tab that starts the next one on its unlocked profile. */
const HANDOFF_KEY = 'tmplayer-unlock-handoff'

function navigationType(): string | null {
    try {
        return (performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming | undefined)?.type ?? null
    } catch {
        return null
    }
}

/**
 * The unlock is bound to the page instance that holds it: a token in memory, and a Web Lock of that
 * name for as long as the page lives. A reload of the tab, or a page the tab itself started on that
 * profile, takes it over; a duplicated tab or a restored session (whose sessionStorage is a copy)
 * does not, and asks the PIN.
 */
let token: string | null = null
{
    const taken = B.acceptUnlock(session(UNLOCK_KEY), Date.now(), navigationType(), session(HANDOFF_KEY))
    setSession(HANDOFF_KEY, null)
    // The unlock holds the tab's own profile only.
    if (taken && taken.id === tabChoice) token = taken.token
    else setSession(UNLOCK_KEY, null)
}

const plan = B.bootPlan(first, tabChoice, token ? tabChoice : null, B.readPending(session(PENDING_KEY), first))
if (!plan.pending) setSession(PENDING_KEY, null)

/** The profile this page runs, for its whole life. */
export const activeId = plan.id
/** Its storage names. */
export const names = B.namesOf(activeId, testMode)
export const namesFor = (id: string) => B.namesOf(id, testMode)
/** Work this page does in its profile with no screen: a sign out of it, or a step of Remove all. */
export const pendingWork: B.Pending | null = plan.pending
const [book, setBookSignal] = createSignal(first)
export { book }
export const profiles = () => book().profiles
export const activeProfile = () => B.find(book(), activeId)
export const many = () => book().profiles.length > 1

/** Writes the book for every tab. */
export function saveBook(next: B.Book) {
    setBookSignal(next)
    try {
        localStorage.setItem(BOOK_KEY, B.writeBook(next))
    } catch {}
}
/** The book as stored now (another tab may have changed it). */
export function freshBook(): B.Book {
    const b = readStored()
    // Only a change is told: a read inside a computation never sets off another round of it.
    if (B.writeBook(b) !== B.writeBook(untrack(book))) setBookSignal(b)
    return b
}

// ---- Generations: a write tagged with this page's profile lands only while it is still current ----

let gen = 0
export const pageStamp: B.Stamp = { id: activeId, gen: 0 }
/** False once this page began leaving its profile (a switch, a sign out of it), or the profile went in another tab. */
export const writesOpen = () => B.isCurrent(pageStamp, { id: activeId, gen }, book())
/** From here on, nothing this page writes for its profile lands. */
export function closeWrites() {
    gen++
}

// ---- Unlocking ----

/** The PIN profile this tab may open now, or null: only the unlock this very page holds the token of. */
export function unlockedId(): string | null {
    if (!token) return null
    const text = session(UNLOCK_KEY)
    const id = B.readUnlock(text, Date.now())
    try {
        return id && (JSON.parse(text!) as { token?: unknown }).token === token ? id : null
    } catch {
        return null
    }
}
const newToken = () => Array.from(crypto.getRandomValues(new Uint8Array(12)), (b) => (b % 36).toString(36)).join('')

/** Holds the token's Web Lock while this page lives; a page that finds it already held is a copy and locks. */
let releaseHold: (() => void) | null = null
function holdToken(t: string, onCopy: () => void) {
    const locks = typeof navigator !== 'undefined' ? navigator.locks : undefined
    if (!locks) return
    releaseHold?.()
    locks
        .request(`tmplayer-unlock:${t}`, { ifAvailable: true }, (lock) => {
            if (!lock) return onCopy()
            return new Promise<void>((resolve) => (releaseHold = resolve))
        })
        .catch(() => {})
}

/** The PIN was entered for `id` in this tab, which is about to open it: the one unlock the tab holds. */
export function unlock(id: string) {
    token = newToken()
    setSession(UNLOCK_KEY, B.writeUnlock(id, Date.now(), token))
    holdToken(token, () => {})
    freshening ??= setInterval(freshenUnlock, 20_000)
}
/** Locks again: a switch away, a sign out, a remove, an add, a minute hidden. */
export function relock() {
    token = null
    releaseHold?.()
    releaseHold = null
    setSession(UNLOCK_KEY, null)
}
/** This page starts the next one of the tab on `id`: it takes the unlock over when it is `id`'s. */
export function handOff(id: string | null) {
    if (token && id !== null && unlockedId() === id) setSession(HANDOFF_KEY, token)
}

/**
 * The unlock is stamped with a time and runs out after RELOCK_HIDDEN_MS unless the tab, shown, keeps
 * it fresh: a copy of it (a duplicated tab, a session restored after a crash) or a tab hidden too
 * long finds it run out. The timer stops by itself once nothing is unlocked.
 */
let freshening: ReturnType<typeof setInterval> | undefined
function freshenUnlock() {
    const id = unlockedId()
    if (!id) {
        clearInterval(freshening)
        freshening = undefined
        return
    }
    if (id === activeId && document.visibilityState === 'visible') setSession(UNLOCK_KEY, B.writeUnlock(id, Date.now(), token!))
}
if (typeof window !== 'undefined') {
    if (unlockedId()) {
        freshening = setInterval(freshenUnlock, 20_000)
        // Another live page still holds this token: this page is a copy of it (a duplicated tab), and locks.
        holdToken(token!, () => lockNow())
    }
    // A reload (or a closing tab) stamps it now: the next page of this tab finds it fresh, a copy restored later does not.
    window.addEventListener('pagehide', () => {
        const id = unlockedId()
        if (id === activeId) setSession(UNLOCK_KEY, B.writeUnlock(id, Date.now(), token!))
    })
}

// ---- Locking a running page ----

/** Every Media Session action a page can take, so a lock leaves none (unknown ones are skipped). */
const MEDIA_ACTIONS = ['play', 'pause', 'stop', 'seekbackward', 'seekforward', 'seekto', 'previoustrack', 'nexttrack', 'skipad', 'enterpictureinpicture', 'togglemicrophone', 'togglecamera', 'hangup', 'previousslide', 'nextslide', 'voiceactivity'] as const

/**
 * Nothing of the profile plays on under a lock: picture in picture and full screen end, the Media
 * Session (the OS's player, the hardware keys, Chrome's media hub) is emptied, and every video and
 * sound on the page is stopped and unloaded. The player itself is closed by the Shell (App.tsx), so its
 * streams, decoders and sound graph go with it; its place was saved first (runBeforeSwitch), and the
 * unlock starts again from there.
 */
export function stopMediaForLock() {
    if (typeof document === 'undefined') return
    try {
        if (document.pictureInPictureElement) void document.exitPictureInPicture().catch(() => {})
    } catch {}
    try {
        // Only the player's own full screen ends (its media-container.player, the element useFullscreen asks for): the TV build keeps its page in full screen (the document element).
        if (document.fullscreenElement?.matches('media-container.player')) void document.exitFullscreen().catch(() => {})
    } catch {}
    try {
        const session = navigator.mediaSession
        if (session) {
            session.metadata = null
            session.playbackState = 'none'
            for (const action of MEDIA_ACTIONS)
                try {
                    session.setActionHandler(action as MediaSessionAction, null)
                } catch {}
        }
    } catch {}
    for (const media of document.querySelectorAll<HTMLMediaElement>('video, audio')) {
        try {
            media.pause()
            media.removeAttribute('src')
            media.srcObject = null
            media.load()
        } catch {}
    }
}

/** Locks the open profile again (a minute away, or this page found to be a copy): its place kept, its media stopped, the picker over all. */
function lockNow() {
    relock()
    runBeforeSwitch()
    stopMediaForLock()
    closeTopLayer()
    setPicker((p) => (p === 'launch' ? p : 'lock'))
}

/**
 * While locked, no dialog of the profile opens, however late it was asked for (an answer coming
 * back after the lock): only the picker's own (inside it, or marked `data-while-locked`) and its cover.
 */
const mayOpenWhileLocked = (d: Element) => d.matches('.profiles, .lock-cover, [data-while-locked]') || !!d.closest('dialog.profiles')
if (typeof HTMLDialogElement !== 'undefined') {
    const proto = HTMLDialogElement.prototype
    const showModal = proto.showModal
    const show = proto.show
    if (showModal)
        proto.showModal = function (this: HTMLDialogElement) {
            if (untrack(locked) && !mayOpenWhileLocked(this)) return
            return showModal.call(this)
        }
    if (show)
        proto.show = function (this: HTMLDialogElement) {
            if (untrack(locked) && !mayOpenWhileLocked(this)) return
            return show.call(this)
        }
}

/**
 * While locked the rest of the page is inert and hidden from screen readers, beside the top layer:
 * every child of the body but the app root, and inside the root everything but the picker or its cover.
 */
const shielded = new Set<Element>()
let shieldWatch: MutationObserver | null = null
function shieldNow() {
    const app = document.getElementById('app')
    const keep = (el: Element) => el.matches('.profiles, .lock-cover, [data-while-locked], dialog.confirm-ask') || !!el.querySelector('dialog.profiles, dialog.lock-cover')
    const children = [...document.body.children].filter((el) => el !== app).concat(app ? [...app.children] : [])
    for (const el of children) {
        if (keep(el) || el.hasAttribute('inert') || el.tagName === 'SCRIPT') continue
        el.setAttribute('inert', '')
        el.setAttribute('aria-hidden', 'true')
        shielded.add(el)
    }
}
export function shieldPage(on: boolean) {
    if (typeof document === 'undefined') return
    shieldWatch?.disconnect()
    shieldWatch = null
    if (!on) {
        for (const el of shielded) (el.removeAttribute('inert'), el.removeAttribute('aria-hidden'))
        shielded.clear()
        return
    }
    shieldNow()
    // A Nav or TvBar mounted after the lock (leaving /watch) and a later toast get the shield too.
    if (typeof MutationObserver !== 'undefined') {
        shieldWatch = new MutationObserver(shieldNow)
        shieldWatch.observe(document.body, { childList: true })
        const app = document.getElementById('app')
        if (app) shieldWatch.observe(app, { childList: true })
    }
}

/**
 * Closes everything in the browser's top layer (modal dialogs, popovers: a card's menu, a question,
 * a sheet), which would stand above the picker and still show the profile. A dialog held open by
 * work in progress is hidden instead. `keep` is the picker's own.
 */
export function closeTopLayer(keep?: Element | null, hideHeld = true) {
    if (typeof document === 'undefined') return
    for (const d of document.querySelectorAll<HTMLDialogElement>('dialog[open]')) {
        if (d === keep || d.matches('.profiles, .lock-cover')) continue
        try {
            d.close()
        } catch {}
        if (hideHeld && d.hasAttribute('open')) {
            d.style.visibility = 'hidden'
            d.setAttribute('inert', '')
        }
    }
    try {
        for (const p of document.querySelectorAll<HTMLElement>(':popover-open')) if (p !== keep) p.hidePopover()
    } catch {}
}

// ---- The picker ----

/**
 * 'launch' while the tab has not chosen or unlocked (it runs no Telegram client yet), 'lock' when its
 * open PIN profile locked again while running (it must be chosen again, with the PIN), 'menu' when
 * opened from the profile menu.
 */
const [picker, setPicker] = createSignal<'launch' | 'lock' | 'menu' | null>(plan.picker)
export { picker }
export const openPicker = () => setPicker((p) => p ?? 'menu')
export const closePicker = () => setPicker(null)
/** The profile's screens are locked away: the picker at launch, or locked again (nothing of the page may answer input or play). */
export const locked = () => {
    const p = picker()
    return p === 'launch' || p === 'lock'
}

/** Records the tab's choice: a reload stays on it, a new tab asks again. */
export const chooseInTab = (id: string | null) => setSession(TAB_KEY, id)
/** This tab has chosen its profile (it was asked, or there was nothing to ask); never a PIN profile it has not unlocked. */
export function markChosen() {
    if (!pendingWork && B.mayOpen(untrack(book), activeId, unlockedId())) setSession(TAB_KEY, activeId)
}

/** Whether this tab has chosen the profile it runs. */
export const tabChose = () => session(TAB_KEY) === activeId

/** Leaves work for the next page, which runs in that profile without screens; null clears it. */
export const setPending = (next: B.Pending | null) => setSession(PENDING_KEY, next ? JSON.stringify(next) : null)

// ---- Intents carried over a reload ----

export type Intent = { kind: 'signinAgain' } | { kind: 'notice'; text: 'same' | 'removed' | 'cancelled' | 'failed' | 'deleted' }

export function takeIntent(): Intent | null {
    const text = session(INTENT_KEY)
    setSession(INTENT_KEY, null)
    if (!text || text.length > 200) return null
    try {
        const v = JSON.parse(text) as { kind?: unknown; text?: unknown }
        if (v.kind === 'signinAgain') return { kind: v.kind }
        if (v.kind === 'notice' && (v.text === 'same' || v.text === 'removed' || v.text === 'cancelled' || v.text === 'failed' || v.text === 'deleted')) return { kind: 'notice', text: v.text }
    } catch {}
    return null
}
export const leaveIntent = (intent: Intent | null) => setSession(INTENT_KEY, intent ? JSON.stringify(intent) : null)

/** The profile this tab was on before it began adding one: Cancel goes back to it (or to the picker, for a PIN profile). */
const FROM_KEY = 'tmplayer-add-from'
export const addingFrom = () => session(FROM_KEY)
export const setAddingFrom = (id: string | null) => setSession(FROM_KEY, id)

// ---- Before a switch: the player keeps its place ----

const before = new Set<() => void>()
/** Runs before the page leaves its profile (the player saves where it was), and when it locks again. */
export function beforeSwitch(fn: () => void): () => void {
    before.add(fn)
    return () => void before.delete(fn)
}
export function runBeforeSwitch() {
    for (const fn of [...before]) {
        try {
            fn()
        } catch (e) {
            console.warn('[accounts] before switch', e)
        }
    }
}
/** True while a video plays in this page: a switch asks first. */
export const playing = () => before.size > 0

// ---- A minute hidden locks the open PIN profile again ----

if (typeof document !== 'undefined')
    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'hidden') return setSession(HIDDEN_KEY, String(Date.now()))
        const away = hiddenAt()
        setSession(HIDDEN_KEY, null)
        if (pendingWork || !B.relockDue(untrack(book), activeId, away, Date.now())) return freshenUnlock()
        lockNow()
    })

// ---- Photos ----

export function photoOf(id: string): string | null {
    try {
        return B.cleanPhoto(localStorage.getItem(namesFor(id).photo))
    } catch {
        return null
    }
}
const [photos, setPhotos] = createSignal(0)
/** Changes when a photo was kept or dropped, so the picker draws it again. */
export const photosVersion = photos
export function keepPhoto(id: string, dataUrl: string | null) {
    try {
        const clean = B.cleanPhoto(dataUrl)
        if (clean) localStorage.setItem(namesFor(id).photo, clean)
        else localStorage.removeItem(namesFor(id).photo)
    } catch {}
    setPhotos((n) => n + 1)
}

// ---- Tabs ----

/** Whether another tab runs this profile's Telegram client now. Browsers without Web Locks say no. */
export async function heldElsewhere(id: string, evenThisOne = false): Promise<boolean> {
    // This page's own profile: its lock is this tab's, unless the page has not opened its client (`evenThisOne`).
    if (id === activeId && !evenThisOne) return false
    try {
        const state = await navigator.locks?.query()
        return !!state?.held?.some((l) => l.name === namesFor(id).lock)
    } catch {
        return false
    }
}

// Another tab changed the book: this one follows, and a tab whose profile was removed there starts again.
if (typeof window !== 'undefined')
    window.addEventListener('storage', (e) => {
        if (e.key !== BOOK_KEY && e.key !== null) return
        const next = readStored()
        setBookSignal(next)
        if (!B.find(next, activeId)) location.reload()
    })
