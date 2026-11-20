// Which profile this tab runs (HX88 phase B3). The book of profiles lives in localStorage, which every
// tab shares; the profile a tab chose lives in its sessionStorage, so a reload stays on it and a new
// tab starts on the picker. The profile is fixed for the page's life: a switch reloads the page into
// the next one, so nothing of one profile (the Telegram worker, the lists in memory, the caches) can
// outlive it. The rules are ui/accountBook.ts; what a switch, an add or a sign out does is accountFlows.ts.
import { createSignal, untrack } from 'solid-js'
import * as B from './ui/accountBook'

const BOOK_KEY = 'tmplayer-accounts'
/** sessionStorage: the profile this tab chose (or switched to). */
const TAB_KEY = 'tmplayer-tab-profile'
/** sessionStorage: what a reload carries on with (a sign out to finish, Remove all, a line to show). */
const INTENT_KEY = 'tmplayer-accounts-intent'

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

const first = readStored()
const chosen = B.find(first, session(TAB_KEY))

/** The profile this page runs, for its whole life. */
export const activeId = chosen?.id ?? first.active
/** Its storage names. */
export const names = B.namesOf(activeId, testMode)
export const namesFor = (id: string) => B.namesOf(id, testMode)

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

// ---- The picker ----

/** 'launch' while the tab has not chosen (it runs no Telegram client yet), 'menu' when opened from the profile menu. */
const [picker, setPicker] = createSignal<'launch' | 'menu' | null>(!chosen && B.pickerAtLaunch(first) ? 'launch' : null)
export { picker }
export const openPicker = () => setPicker((p) => p ?? 'menu')
export const closePicker = () => setPicker(null)

/** Records the tab's choice: a reload stays on it, a new tab asks again. */
export const chooseInTab = (id: string | null) => setSession(TAB_KEY, id)
/** This tab has chosen its profile (it was asked, or there was nothing to ask). */
export function markChosen() {
    setSession(TAB_KEY, activeId)
}

// ---- Intents carried over a reload ----

export type Intent =
    | { kind: 'signout' }
    | { kind: 'removeAll' }
    | { kind: 'signinAgain' }
    | { kind: 'notice'; text: 'same' | 'removed' | 'cancelled' }

export function takeIntent(): Intent | null {
    const text = session(INTENT_KEY)
    setSession(INTENT_KEY, null)
    if (!text || text.length > 200) return null
    try {
        const v = JSON.parse(text) as { kind?: unknown; text?: unknown }
        if (v.kind === 'signout' || v.kind === 'removeAll' || v.kind === 'signinAgain') return { kind: v.kind }
        if (v.kind === 'notice' && (v.text === 'same' || v.text === 'removed' || v.text === 'cancelled')) return { kind: 'notice', text: v.text }
    } catch {}
    return null
}
export const leaveIntent = (intent: Intent | null) => setSession(INTENT_KEY, intent ? JSON.stringify(intent) : null)

/** The profile this tab was on before it began adding one: Cancel goes back to it. */
const FROM_KEY = 'tmplayer-add-from'
export const addingFrom = () => session(FROM_KEY)
export const setAddingFrom = (id: string | null) => setSession(FROM_KEY, id)

// ---- Before a switch: the player keeps its place ----

const before = new Set<() => void>()
/** Runs before the page leaves its profile (the player saves where it was). */
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
export async function heldElsewhere(id: string): Promise<boolean> {
    if (id === activeId) return false
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
