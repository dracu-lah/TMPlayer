// The web player's profiles (HX88 phase B3), the rules core's Accounts and AccountSwitcher keep
// (docs/agents/multi-account.md), with nothing of the page in it so `node --test` runs it:
//
// - At most three profiles; one Telegram client at a time (the page runs one profile, a switch
//   reloads it into the next).
// - The login already in this browser is adopted in place as profile 1 (`main`): its storage keeps
//   the names it always had (mtcute's `tmplayer`, the keys of `tmplayer-app` as they are), so going
//   back to an older build still finds it. Any other profile has a folder name, and its storage is
//   named after it (`tmplayer-<folder>`, keys `@<folder>:<key>`).
// - With one profile nothing changes: no picker, the same names.
// - The book is outside data (localStorage, hand edits, an older build): it is read through a
//   schema, a profile that does not hold up is left out, and a book that does not is the adopted
//   profile alone, the state of the version before.
// - A write is tagged with the profile and the page's generation, and dropped once either changed.
import * as z from 'zod/mini'

export const MAX_PROFILES = 3
/** The adopted profile's id: the browser's own storage names. */
export const ADOPTED = 'main'
export const PIN_TRIES = 5
export const PIN_LOCK_MS = 30_000
/** A profile still being added that no tab finished after this long is cleaned away (killed part way). */
export const ADDING_STALE_MS = 30 * 60_000
export const NAME_MAX = 40
/** What a new profile needs free before it is made: its session and first lists. */
export const ADD_NEEDS_BYTES = 20 * 1024 * 1024
/** PBKDF2 rounds for a PIN: a local screen lock, so enough to slow a look at the stored hash, quick on a TV. */
export const PIN_ROUNDS = 100_000

const FOLDER = /^[a-z0-9]{6}$/

export interface Pin {
    /** base64 */
    salt: string
    /** base64 of PBKDF2-SHA-256, 32 bytes */
    hash: string
    rounds: number
}

export type ProfileState = 'ok' | 'adding' | 'attention'

export interface Profile {
    /** `main` for the adopted profile, else its folder name. */
    id: string
    /** Telegram's name for the account, or the viewer's own; '' until known. */
    name: string
    /** The Telegram user id once signed in; the same account twice is refused by it. */
    userId: number | null
    state: ProfileState
    pin: Pin | null
    /** Wrong PINs in a row; five lock the profile for 30 s. */
    fails: number
    lockedUntil: number
    added: number
}

export interface Book {
    profiles: Profile[]
    /** The profile last opened, which a new tab starts on when it shows no picker. */
    active: string
}

const PIN = z.object({
    salt: z.string().check(z.minLength(8), z.maxLength(64)),
    hash: z.string().check(z.minLength(16), z.maxLength(128)),
    rounds: z.number().check(z.int(), z.gte(1000), z.lte(10_000_000)),
})

const PROFILE = z.object({
    id: z.string().check(z.maxLength(12)),
    name: z._default(z.string().check(z.maxLength(400)), ''),
    userId: z._default(z.nullable(z.number().check(z.int(), z.gt(0))), null),
    state: z._default(z.enum(['ok', 'adding', 'attention']), 'ok'),
    pin: z._default(z.nullable(PIN), null),
    fails: z._default(z.number().check(z.int(), z.gte(0), z.lte(1000)), 0),
    lockedUntil: z._default(z.number().check(z.gte(0)), 0),
    added: z._default(z.number().check(z.gte(0)), 0),
})

export const validId = (id: string) => id === ADOPTED || FOLDER.test(id)

/** The book of a browser that never had a second profile: the adopted one alone. */
export function defaultBook(): Book {
    return { profiles: [blank(ADOPTED, 0)], active: ADOPTED }
}

function blank(id: string, now: number, state: ProfileState = 'ok'): Profile {
    return { id, name: '', userId: null, state, pin: null, fails: 0, lockedUntil: 0, added: now }
}

/** The stored book, or the adopted profile alone when nothing (or nothing usable) is stored. */
export function readBook(text: string | null | undefined): Book {
    if (!text || text.length > 64_000) return defaultBook()
    let raw: unknown
    try {
        raw = JSON.parse(text)
    } catch {
        return defaultBook()
    }
    if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return defaultBook()
    const list = (raw as { profiles?: unknown }).profiles
    if (!Array.isArray(list)) return defaultBook()
    const profiles: Profile[] = []
    const seen = new Set<string>()
    for (const entry of list.slice(0, 20)) {
        const ok = z.safeParse(PROFILE, entry)
        if (!ok.success || !validId(ok.data.id) || seen.has(ok.data.id)) continue
        seen.add(ok.data.id)
        profiles.push({ ...ok.data, name: cleanName(ok.data.name) })
        if (profiles.length === MAX_PROFILES) break
    }
    if (!profiles.length) return defaultBook()
    const active = (raw as { active?: unknown }).active
    return { profiles, active: typeof active === 'string' && seen.has(active) ? active : profiles[0].id }
}

export const writeBook = (book: Book): string => JSON.stringify({ v: 1, ...book })

/** A name as the picker shows it: one line, no control characters, at most NAME_MAX. */
export function cleanName(name: string): string {
    return [...name.replace(/[\p{Cc}\p{Cf}\u2028\u2029]/gu, ' ').replace(/\s+/g, ' ').trim()].slice(0, NAME_MAX).join('')
}

export const find = (book: Book, id: string | null | undefined): Profile | undefined => (id ? book.profiles.find((p) => p.id === id) : undefined)

/**
 * Whether this page answers a stream read the Service Worker passed on: one tagged with this page's
 * profile, or an untagged one (an older worker) only while the browser has a single profile, as before.
 */
export const streamReadAllowed = (tag: unknown, activeId: string, profileCount: number): boolean => (typeof tag === 'string' ? tag === activeId : profileCount <= 1)

/** A profile's place in the book, from 1, found by its id (a copy read from storage is another object); 0 when it is gone. */
export const profileNumber = (book: Book, id: string): number => book.profiles.findIndex((p) => p.id === id) + 1

const patch = (book: Book, id: string, change: Partial<Profile>): Book => ({
    ...book,
    profiles: book.profiles.map((p) => (p.id === id ? { ...p, ...change } : p)),
})

export const canAdd = (book: Book) => book.profiles.length < MAX_PROFILES

/** A new folder name: six letters and digits, none in use. */
export function newFolder(book: Book, random: () => number): string {
    const chars = 'abcdefghijklmnopqrstuvwxyz0123456789'
    for (;;) {
        let f = ''
        for (let i = 0; i < 6; i++) f += chars[Math.floor(random() * chars.length) % chars.length]
        if (!find(book, f)) return f
    }
}

export type AddResult = { full: true } | { noSpace: { needed: number; free: number } } | { book: Book; profile: Profile }

/** Records a profile being added (`adding`, never signed in); a fourth, or a full disk, is refused. */
export function addProfile(book: Book, folder: string, now: number, freeBytes: number | null): AddResult {
    if (!canAdd(book)) return { full: true }
    if (freeBytes !== null && freeBytes < ADD_NEEDS_BYTES) return { noSpace: { needed: ADD_NEEDS_BYTES, free: Math.max(0, freeBytes) } }
    const profile = blank(folder, now, 'adding')
    return { book: { ...book, profiles: [...book.profiles, profile] }, profile }
}

/**
 * Forgets a profile. The last one is never dropped: it keeps its id (its storage names) with its
 * identity emptied, which is the normal sign in.
 */
export function removeProfile(book: Book, id: string): Book {
    if (!find(book, id)) return book
    if (book.profiles.length === 1) return { profiles: [blank(id, book.profiles[0].added)], active: id }
    const profiles = book.profiles.filter((p) => p.id !== id)
    return { profiles, active: book.active === id ? profiles[0].id : book.active }
}

/** Profiles still being added, left by a tab that closed part way, older than ADDING_STALE_MS; `keep` is this tab's own. */
export function staleAdding(book: Book, now: number, keep: string | null): Profile[] {
    if (book.profiles.length < 2) return []
    return book.profiles.filter((p) => p.state === 'adding' && p.id !== keep && now - p.added > ADDING_STALE_MS)
}

export type AttachResult = { book: Book } | { same: Profile } | { stale: true }

/**
 * The profile signed in as `user`: its identity is recorded, `adding` and `attention` are over.
 * The same Telegram account on another profile is refused. An answer for a profile no longer in
 * the book is dropped.
 */
export function attach(book: Book, id: string, user: { id: number; name: string | null }): AttachResult {
    const self = find(book, id)
    if (!self) return { stale: true }
    // A profile that was this account already stays it (a name read again, a session checked at start).
    const twin = self.userId === user.id ? undefined : book.profiles.find((p) => p.id !== id && p.userId === user.id)
    if (twin) return { same: twin }
    const name = cleanName(user.name ?? '') || self.name
    return { book: patch(book, id, { userId: user.id, name, state: 'ok' }) }
}

/** The session of a signed in profile is missing or broken: the picker says Needs attention. */
export const markAttention = (book: Book, id: string): Book => (find(book, id) ? patch(book, id, { state: 'attention' }) : book)

export function rename(book: Book, id: string, name: string): Book {
    const clean = cleanName(name)
    return clean && find(book, id) ? patch(book, id, { name: clean }) : book
}

export const setActive = (book: Book, id: string): Book => (find(book, id) ? { ...book, active: id } : book)

/** Whether a tab that has not chosen yet starts on the picker: two or more profiles, or one with a PIN. */
export const pickerAtLaunch = (book: Book) => book.profiles.length >= 2 || book.profiles.some((p) => p.pin)

// ---- Unlocking: which profile a tab may open ------------------------------------------------
//
// A profile with a PIN opens only in a tab that entered its PIN. That unlock lives in the tab alone
// (sessionStorage, never localStorage), holds the open profile only, and goes on a switch away, a
// sign out, a remove, an add, the tab closing, or the tab hidden for more than a minute.

/** A tab hidden longer than this locks its PIN profile again. */
export const RELOCK_HIDDEN_MS = 60_000

/** Work a reload carries on in a profile's page without opening its screens: signing it out, or Remove all. */
export interface Pending {
    kind: 'signout' | 'removeAll'
    id: string
}

export interface BootPlan {
    /** The profile the page runs. */
    id: string
    /** 'launch' when the tab must choose (or unlock) before anything opens. */
    picker: 'launch' | null
    /** Work to finish in that profile with no screen shown. */
    pending: Pending | null
}

/** Whether `id` may open in a tab whose unlocked profile is `unlocked`: a profile with no PIN always may. */
export function mayOpen(book: Book, id: string, unlocked: string | null): boolean {
    const p = find(book, id)
    return !!p && (!p.pin || unlocked === id)
}

/**
 * What a page starts on. Pending work runs in its profile with no picker and no screens; otherwise the
 * tab's own choice (or the profile last open), and the picker when the tab has not chosen and there is
 * something to choose, or when the profile it would open has a PIN this tab has not entered.
 */
export function bootPlan(book: Book, tabChoice: string | null, unlocked: string | null, pending: Pending | null): BootPlan {
    if (pending && find(book, pending.id)) return { id: pending.id, picker: null, pending }
    const chosen = find(book, tabChoice)
    const id = chosen?.id ?? book.active
    const ask = (!chosen && pickerAtLaunch(book)) || !mayOpen(book, id, unlocked)
    return { id, picker: ask ? 'launch' : null, pending: null }
}

/** The profile Cancel (or a refused sign in) goes back to: only one this tab had open and that needs no PIN again; else the picker. */
export function backTarget(book: Book, from: string | null): string | null {
    const p = find(book, from)
    return p && !p.pin ? p.id : null
}

/** The profile Add account remembers to go back to: the open one, only when this tab had chosen it and (with a PIN) unlocked it. */
export function addingFromFor(book: Book, activeId: string, chosen: boolean, unlocked: string | null): string | null {
    return chosen && mayOpen(book, activeId, unlocked) ? activeId : null
}

/** A hidden tab locks its open PIN profile again once it was away for longer than RELOCK_HIDDEN_MS. */
export function relockDue(book: Book, activeId: string, hiddenAt: number | null, now: number): boolean {
    return hiddenAt !== null && now - hiddenAt > RELOCK_HIDDEN_MS && !!find(book, activeId)?.pin
}

/**
 * Whether signing `id` out (or removing it) needs its PIN first: a PIN profile always does, unless it
 * is the profile open and unlocked in this tab, or its PIN was entered for this just now (`verified`).
 */
export function needsPinFor(book: Book, id: string, activeId: string, unlocked: string | null, verified: ReadonlySet<string>): boolean {
    const p = find(book, id)
    if (!p?.pin) return false
    if (id === activeId && unlocked === id) return false
    return !verified.has(id)
}

/** The profiles whose PIN Remove all asks before anything is wiped (all of them right, or nothing goes). */
export const pinsForRemoveAll = (book: Book, activeId: string, unlocked: string | null): Profile[] =>
    book.profiles.filter((p) => p.pin && !(p.id === activeId && unlocked === p.id))

/** A pending step as stored in the tab, or null when it does not hold up or its profile is gone. */
export function readPending(text: string | null | undefined, book: Book): Pending | null {
    if (!text || text.length > 200) return null
    try {
        const v = JSON.parse(text) as { kind?: unknown; id?: unknown }
        if ((v.kind === 'signout' || v.kind === 'removeAll') && typeof v.id === 'string' && find(book, v.id)) return { kind: v.kind, id: v.id }
    } catch {}
    return null
}

// ---- Storage names -----------------------------------------------------------------------

export interface Names {
    /** mtcute's IndexedDB database. */
    mtcute: string
    /** The Web Lock one tab holds while it runs this profile's client. */
    lock: string
    /** The Telegram worker's name, which tells it its storage. */
    worker: string
    /** Put before an account key in `tmplayer-app`; '' for the adopted profile. */
    keyPrefix: string
    lastPosition: string
    syncShared: string
    subpicks: string
    photo: string
}

export function namesOf(id: string, test = false): Names {
    const folder = id === ADOPTED || !FOLDER.test(id) ? null : id
    const tg = test ? 'tmplayer-test' : 'tmplayer'
    return {
        mtcute: folder ? `${tg}-${folder}` : tg,
        lock: folder ? `tmplayer-telegram:${folder}` : 'tmplayer-telegram',
        worker: folder ? `telegram:${folder}` : 'telegram',
        keyPrefix: folder ? `@${folder}:` : '',
        lastPosition: folder ? `tmplayer-last-position@${folder}` : 'tmplayer-last-position',
        syncShared: folder ? `tmplayer-sync-state@${folder}` : 'tmplayer-sync-state',
        subpicks: folder ? `tmplayer-subpicks-${folder}` : 'tmplayer-subpicks',
        photo: `tmplayer-account-photo:${folder ?? ADOPTED}`,
    }
}

/** The worker's storage, from its name (`telegram` or `telegram:<folder>`). */
export function storageOfWorker(workerName: string, test = false): string {
    const m = /^telegram:([a-z0-9]{6})$/.exec(workerName)
    return namesOf(m ? m[1] : ADOPTED, test).mtcute
}

// ---- Which keys are the device's ------------------------------------------------------------

/**
 * Keys of `tmplayer-app` every profile shares (core's AccountKeys.DEVICE): Settings' object (theme,
 * sizes, sort, Home's rows, rewind), the player's switches, speeds by show, the copy habit and the
 * online subtitle setup. Every other key is the profile's own: a key nobody classed stays private.
 */
const DEVICE_KEYS = new Set(['settings', 'copyhabit'])
const DEVICE_PREFIXES = ['speed:', 'player:', 'secret:']

export const isDeviceKey = (key: string) => DEVICE_KEYS.has(key) || DEVICE_PREFIXES.some((p) => key.startsWith(p))

/** The key as stored for a profile whose prefix is `prefix`. */
export const scopedKey = (prefix: string, key: string) => (!prefix || isDeviceKey(key) ? key : prefix + key)

/** A stored key as this profile sees it, or null when it belongs to another profile. */
export function viewKey(prefix: string, stored: string): string | null {
    if (stored.startsWith('@')) {
        if (!prefix || !stored.startsWith(prefix)) return null
        return stored.slice(prefix.length)
    }
    return !prefix || isDeviceKey(stored) ? stored : null
}

/** Whether a stored key is one of this profile's own (what signing it out takes away); device keys never are. */
export function ownsKey(prefix: string, stored: string): boolean {
    if (prefix) return stored.startsWith(prefix)
    return !stored.startsWith('@') && !isDeviceKey(stored)
}

// ---- Generations ------------------------------------------------------------------------------

export interface Stamp {
    id: string
    gen: number
}

/** A write made under `stamp` lands only while the page is still that profile in that generation and the profile is still in the book. */
export const isCurrent = (stamp: Stamp, now: Stamp, book: Book) => stamp.id === now.id && stamp.gen === now.gen && !!find(book, stamp.id)

// ---- PIN ------------------------------------------------------------------------------------

export const validPin = (pin: string) => /^\d{4}$/.test(pin)

const b64 = (bytes: Uint8Array) => {
    let s = ''
    for (const b of bytes) s += String.fromCharCode(b)
    return btoa(s)
}
const unb64 = (text: string): Uint8Array | null => {
    try {
        return Uint8Array.from(atob(text), (c) => c.charCodeAt(0))
    } catch {
        return null
    }
}

async function derive(subtle: SubtleCrypto, pin: string, salt: Uint8Array, rounds: number): Promise<Uint8Array> {
    const key = await subtle.importKey('raw', new TextEncoder().encode(pin), 'PBKDF2', false, ['deriveBits'])
    const bits = await subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt: salt as BufferSource, iterations: rounds }, key, 256)
    return new Uint8Array(bits)
}

/** Every byte compared, whatever the first difference: the time says nothing about the PIN. */
export function sameBytes(a: Uint8Array, b: Uint8Array): boolean {
    let diff = a.length ^ b.length
    const n = Math.max(a.length, b.length)
    for (let i = 0; i < n; i++) diff |= (a[i] ?? 0) ^ (b[i] ?? 0)
    return diff === 0
}

export async function makePin(pin: string, crypto: Crypto, rounds = PIN_ROUNDS): Promise<Pin> {
    if (!validPin(pin)) throw new Error('A PIN is four digits')
    const salt = crypto.getRandomValues(new Uint8Array(16))
    return { salt: b64(salt), hash: b64(await derive(crypto.subtle, pin, salt, rounds)), rounds }
}

export const setPin = (book: Book, id: string, pin: Pin | null): Book => (find(book, id) ? patch(book, id, { pin, fails: 0, lockedUntil: 0 }) : book)

export type PinAnswer = { ok: true; book: Book } | { ok: false; left: number; book: Book } | { locked: number; book: Book }

/** Seconds of the lock still to run; a lock stamped further ahead than a whole lock (a clock moved back) runs from now. */
export function lockLeft(p: Profile, now: number): number {
    if (p.lockedUntil <= now) return 0
    return Math.min(p.lockedUntil - now, PIN_LOCK_MS)
}

/**
 * Checks a PIN. Five wrong in a row lock the profile for 30 s, kept in the book so a reload does
 * not reset it; a locked profile checks nothing. A profile without a PIN opens.
 */
export async function checkPin(book: Book, id: string, pin: string, now: number, crypto: Crypto): Promise<PinAnswer> {
    let current = book
    return checkPinIn(
        () => current,
        (next) => void (current = next),
        id,
        pin,
        now,
        crypto,
    )
}

/**
 * The same check against a book that other tabs write too: the PIN is derived first (the slow part),
 * then the try is counted on the book as it is THEN (`read` again), and written at once, so two tabs
 * trying at the same time both count and the five-try lock cannot be outrun.
 */
export async function checkPinIn(read: () => Book, write: (book: Book) => void, id: string, pin: string, now: number, crypto: Crypto): Promise<PinAnswer> {
    const before = find(read(), id)
    if (!before) return { ok: false, left: 0, book: read() }
    if (!before.pin) return { ok: true, book: read() }
    const wait = lockLeft(before, now)
    if (wait > 0) return { locked: now + wait, book: read() }
    const salt = unb64(before.pin.salt)
    const want = unb64(before.pin.hash)
    let good = false
    if (salt && want && validPin(pin)) good = sameBytes(await derive(crypto.subtle, pin, salt, before.pin.rounds), want)
    // Counted on the book as it is now: another tab's tries meanwhile are kept.
    const book = read()
    const p = find(book, id)
    if (!p || !p.pin) return { ok: false, left: 0, book }
    // The PIN changed meanwhile (another tab): this one was checked against the old one, so it opens nothing.
    if (p.pin.hash !== before.pin.hash) good = false
    const held = lockLeft(p, now)
    if (held > 0) return { locked: now + held, book }
    let answer: PinAnswer
    if (good) answer = { ok: true, book: patch(book, id, { fails: 0, lockedUntil: 0 }) }
    else {
        const fails = p.fails + 1
        if (fails >= PIN_TRIES) {
            const until = now + PIN_LOCK_MS
            answer = { locked: until, book: patch(book, id, { fails: 0, lockedUntil: until }) }
        } else answer = { ok: false, left: PIN_TRIES - fails, book: patch(book, id, { fails }) }
    }
    write(answer.book)
    return answer
}

/** An unlock as the tab keeps it: the profile, when it was last confirmed (refreshed while the tab is shown), and the page instance's token. */
export function writeUnlock(id: string, at: number, token = ''): string {
    return JSON.stringify({ id, at, token })
}

export interface Accepted {
    id: string
    token: string
}

/**
 * Whether a page may take over the unlock its sessionStorage holds. sessionStorage is copied by a
 * duplicated tab and by a session restore, so the unlock alone proves nothing: it is taken only by a
 * reload of the same tab (`navigation` 'reload') or a page this tab itself started on that profile
 * (`handoff` equal to the unlock's token), only while it is fresh, and only when no other live page
 * still holds its token (`heldElsewhere`, checked by the caller through Web Locks). Anything else asks
 * the PIN again.
 */
export function acceptUnlock(text: string | null | undefined, now: number, navigation: string | null, handoff: string | null): Accepted | null {
    const id = readUnlock(text, now)
    if (!id) return null
    let token = ''
    try {
        const v = JSON.parse(text!) as { token?: unknown }
        token = typeof v.token === 'string' && /^[a-z0-9]{8,40}$/.test(v.token) ? v.token : ''
    } catch {}
    if (!token) return null
    if (navigation === 'reload' || (handoff !== null && handoff === token)) return { id, token }
    return null
}

/**
 * The profile a stored unlock opens, or null: one that does not hold up, or older than RELOCK_HIDDEN_MS
 * (a duplicated tab or a session restored after a crash carries an old one), or stamped ahead of now.
 */
export function readUnlock(text: string | null | undefined, now: number): string | null {
    if (!text || text.length > 200) return null
    try {
        const v = JSON.parse(text) as { id?: unknown; at?: unknown }
        if (typeof v.id !== 'string' || !validId(v.id) || typeof v.at !== 'number' || !Number.isFinite(v.at)) return null
        const age = now - v.at
        return age >= 0 && age <= RELOCK_HIDDEN_MS ? v.id : null
    } catch {
        return null
    }
}

/** A cached small photo, as stored: a JPEG or PNG data address of a sensible size, else none. */
export function cleanPhoto(text: string | null | undefined): string | null {
    if (!text || text.length > 90_000) return null
    return /^data:image\/(jpeg|png|webp);base64,[A-Za-z0-9+/]+=*$/.test(text) ? text : null
}
