// What the profiles' buttons do (HX88 phase B3): switch, add, sign one out, remove one, remove all,
// the PIN and the name. A switch never opens a second Telegram client: the page saves what is
// playing, lets nothing else of its profile land, and reloads into the next profile, whose own
// session opens there. Work that has to run in another profile's page (signing a sleeping profile
// out, Remove all) is left as pending work the next page does with no screen shown (accounts.ts).
//
// A profile with a PIN never opens without it: the picker asks it, a switch refuses a PIN profile
// this tab has not unlocked, signing out or removing one asks it first (unless it is the profile open
// and unlocked here), and Remove all asks every locked profile's PIN before anything is wiped.
import { createSignal } from 'solid-js'
import * as B from './ui/accountBook'
import { createSwitchGuard } from './ui/loadStates'
import {
    activeId,
    activeProfile,
    addingFrom,
    book,
    chooseInTab,
    closeTopLayer,
    handOff,
    closePicker,
    closeWrites,
    freshBook,
    heldElsewhere,
    keepPhoto,
    leaveIntent,
    markChosen,
    names,
    namesFor,
    openPicker,
    pendingWork,
    photoOf,
    playing,
    relock,
    runBeforeSwitch,
    saveBook,
    setAddingFrom,
    setPending,
    tabChose,
    takeIntent,
    unlock,
    unlockedId,
    type Intent,
} from './accounts'
import { api, openSession, sessionOpen, stopSession } from './tg/rpc'
import { clearAll, clearProfileData, flushWrites } from './store'
import { wipeAllPicks } from './player/subpicks-db'
import { clearThumbs } from './cacheBounds'
import { clearLogs, sizeText } from './logs'
import { forgetLinks } from './ui/tgLinks'
import { forgetSubmaker } from './submakerBase'
import { askConfirm } from './ui/Confirm'
import { leavingPage } from './ui/modalHistory'
import { showToast } from './ui/Toast'
import { t } from './i18n'
import type { Me } from './tg/types'

/**
 * The page starts again at its root. The modal history is told first, so the question whose button
 * did this closes without a step back, which in Safari would cancel the page change (leavingPage).
 */
function goHome() {
    leavingPage()
    location.replace('/')
}

/**
 * The page starts again on the profile `id` (null: the picker, or the only one), at Home: an address
 * of one account (a chat, a video) means nothing to another. Leaving the unlocked profile locks it.
 */
const reloadInto = (id: string | null) => {
    // The next page of this tab takes the unlock over only when it is that profile's.
    handOff(id)
    if (unlockedId() !== id) relock()
    closeTopLayer(null, false)
    chooseInTab(id)
    goHome()
}

/** Back to the picker, which asks every PIN again: where pending work could not finish (and says why). */
function toPicker() {
    setPending(null)
    relock()
    leaveIntent({ kind: 'notice', text: 'failed' })
    chooseInTab(null)
    goHome()
}

/** The page lets go of its profile: the player keeps its place, the writes in flight land, nothing after does. */
async function leave() {
    // A menu, a sheet or a question of this profile goes with it (a busy question stays until its work ends).
    closeTopLayer(null, false)
    runBeforeSwitch()
    await flushWrites().catch(() => {})
    closeWrites()
}

/** Asks before leaving a video that plays; true to go on. */
async function okToLeave(): Promise<boolean> {
    if (!playing()) return true
    return askConfirm({
        title: t('accounts.switch_playing_title'),
        message: t('accounts.switch_playing_message'),
        confirm: t('accounts.switch_confirm'),
        danger: false,
    })
}

function deleteDatabase(name: string): Promise<void> {
    return new Promise((resolve) => {
        try {
            const ask = indexedDB.deleteDatabase(name)
            ask.onsuccess = ask.onerror = () => resolve()
            // A connection still open somewhere: the delete goes through once it closes; nothing waits for it.
            ask.onblocked = () => setTimeout(resolve, 1500)
        } catch {
            resolve()
        }
    })
}

/** Everything a profile keeps in this browser, for a profile whose client is not running here (or no longer). */
async function wipeLocal(id: string) {
    const n = namesFor(id)
    await clearProfileData(n.keyPrefix)
    // The page's own picks store is open: emptied, and its database deleted once the page has gone.
    if (id === activeId) await wipeAllPicks().catch(() => {})
    await deleteDatabase(n.subpicks)
    await deleteDatabase(n.mtcute)
    try {
        localStorage.removeItem(n.lastPosition)
        localStorage.removeItem(n.syncShared)
        localStorage.removeItem(n.photo)
    } catch {}
    // A cache the others fill again: none of this profile's pictures stays.
    await clearThumbs().catch(() => {})
}

// ---- PINs entered for one step (sign out, remove, Remove all, options): in memory, used once ----

const verified = new Set<string>()
/** The picker's pad took `id`'s PIN for the step the viewer is about to take on it. */
export const pinVerified = (id: string) => void verified.add(id)
/** The picker closed: a PIN entered for options counts no longer. */
export const forgetVerified = () => verified.clear()
const needsPin = (id: string) => B.needsPinFor(freshBook(), id, activeId, unlockedId(), verified)

/** The PIN was right for opening `id`: this tab may open it now, and only it. */
export function pinOpened(id: string) {
    unlock(id)
}

// ---- Switching ----

/** The viewer picked the profile this page already runs (the picker at launch, or the one in use). */
function stayHere() {
    if (!B.mayOpen(freshBook(), activeId, unlockedId())) return
    markChosen()
    closePicker()
    openSession()
}

const [switching, setSwitching] = createSignal<string | null>(null)
/** The profile a switch is opening now (the tile says "Opening {name}"), or null. */
export const switchingId = switching
const switchGuard = createSwitchGuard(setSwitching)

/**
 * Opens a profile: this one stays, another reloads the page into it. A PIN profile opens only once this tab unlocked it.
 * One switch at a time (LS-30): a second press while one runs does nothing, so two reloads never start.
 */
export async function switchTo(id: string) {
    const book = freshBook()
    if (!B.find(book, id) || !B.mayOpen(book, id, unlockedId())) return
    if (id === activeId) return stayHere()
    if (!switchGuard.start(id)) return
    try {
        if (!(await okToLeave())) return switchGuard.release()
        await leave()
        saveBook(B.setActive(freshBook(), id))
        // The page reloads: the guard stays held until it has gone, so a late press cannot start a second reload.
        reloadInto(id)
        // A reload that never came lets go after a while (SEC-10), and so does a page the back/forward cache brought back.
        switchGuard.releaseAfter(10_000)
    } catch (e) {
        switchGuard.release()
        throw e
    }
}

if (typeof window !== 'undefined') {
    window.addEventListener('pageshow', (event) => {
        if (event.persisted) switchGuard.release()
    })
}

/** Free bytes for this site, or null when the browser does not say. */
async function freeBytes(): Promise<number | null> {
    try {
        const e = await navigator.storage?.estimate?.()
        if (!e || typeof e.quota !== 'number' || typeof e.usage !== 'number') return null
        return e.quota - e.usage
    } catch {
        return null
    }
}

/** Add account: a profile of its own, then the normal sign in in it; Cancel there comes back here (or to the picker). */
export async function addAccount() {
    const book = freshBook()
    if (!B.canAdd(book)) return showToast({ kind: 'warning', text: t('accounts.limit', { count: B.MAX_PROFILES }) })
    const made = B.addProfile(book, B.newFolder(book, Math.random), Date.now(), await freeBytes())
    if ('full' in made) return showToast({ kind: 'warning', text: t('accounts.limit', { count: B.MAX_PROFILES }) })
    if ('noSpace' in made) return showToast({ kind: 'error', text: t('accounts.no_space', { needed: sizeText(made.noSpace.needed), free: sizeText(made.noSpace.free) }) })
    if (!(await okToLeave())) return
    // Only a profile this tab had open (and unlocked) is somewhere to come back to.
    const from = B.addingFromFor(book, activeId, tabChose(), unlockedId())
    await leave()
    saveBook(made.book)
    setAddingFrom(from)
    relock()
    reloadInto(made.profile.id)
}

/** On the sign in of a profile being added: forget it and go back where the viewer came from; a PIN profile asks again. */
export async function cancelAdding() {
    const from = addingFrom()
    closeWrites()
    stopSession()
    await wipeLocal(activeId)
    saveBook(B.removeProfile(freshBook(), activeId))
    setAddingFrom(null)
    reloadInto(B.backTarget(freshBook(), from))
}

/** Whether this page's profile is still being added (its sign in has Cancel). */
export const addingHere = () => activeProfile()?.state === 'adding' && book().profiles.length > 1

// ---- Signing out ----

/**
 * Sign out of this profile while others stay: Telegram is told (at most 5 s), then its session,
 * its lists, places, picks and caches go from this browser, and the page opens the picker (or the
 * one profile left, when it has no PIN). The device's settings and every other profile stay.
 */
export async function signOutThisProfile(then: Intent | null = { kind: 'notice', text: 'removed' }) {
    await leave()
    await api.logOut().catch(() => {})
    stopSession()
    await wipeLocal(activeId)
    clearLogs()
    forgetLinks()
    const next = B.removeProfile(freshBook(), activeId)
    saveBook(next)
    leaveIntent(then)
    // Done: the work is no longer pending, and nothing stays unlocked.
    setPending(null)
    relock()
    reloadInto(next.profiles.length === 1 && !next.profiles[0].pin ? next.profiles[0].id : null)
}

/**
 * The last profile signed out (Settings' Sign out with one profile): as before profiles, everything
 * this browser kept goes. Its record stays with the identity emptied: the normal sign in.
 */
export async function signOutLast() {
    await api.logOut().catch(() => {})
    await clearAll()
    // The remembered online subtitles and their copies are the account's: none stays for the next one (HX25).
    await wipeAllPicks()
    forgetSubmaker()
    keepPhoto(activeId, null)
    relock()
    saveBook(B.removeProfile(freshBook(), activeId))
}

/**
 * Signs a profile out from the picker: this one at once; another by starting the page in it with the
 * sign out pending (no screen of it shows, and the tab is never marked as having chosen it). A PIN
 * profile asks its PIN first. False when it was refused.
 */
export async function signOutProfile(id: string): Promise<boolean> {
    if (needsPin(id)) return false
    verified.delete(id)
    // The profile this page runs, with its client open: here and now.
    if (id === activeId && sessionOpen()) {
        await signOutThisProfile()
        return true
    }
    return pendingSignOut(id)
}

/**
 * A sign out done in the profile's own page, with no screen of it shown: for a sleeping profile, and
 * for the one this page would run but has not opened (the picker at launch, where no client runs).
 */
async function pendingSignOut(id: string): Promise<boolean> {
    if (await heldElsewhere(id, true)) {
        showToast({ kind: 'warning', text: t('accounts.open_elsewhere') })
        return false
    }
    if (!(await okToLeave())) return false
    await leave()
    setPending({ kind: 'signout', id })
    relock()
    goHome()
    return true
}

/** Forgets a profile without reaching Telegram: one whose session is missing or broken, or one never signed in. A PIN profile asks its PIN first. */
export async function removeProfile(id: string): Promise<boolean> {
    if (needsPin(id)) return false
    verified.delete(id)
    if (id === activeId && sessionOpen()) {
        await signOutThisProfile()
        return true
    }
    // The profile this page would run, its client never opened (the picker at launch; its session may
    // not even be readable): removed here, from this browser only, and said so.
    if (id === activeId) {
        if (await heldElsewhere(id, true)) {
            showToast({ kind: 'warning', text: t('accounts.open_elsewhere') })
            return false
        }
        closeWrites()
        await wipeLocal(id)
        const next = B.removeProfile(freshBook(), id)
        saveBook(next)
        leaveIntent({ kind: 'notice', text: 'deleted' })
        relock()
        reloadInto(next.profiles.length === 1 && !next.profiles[0].pin ? next.profiles[0].id : null)
        return true
    }
    if (await heldElsewhere(id)) {
        showToast({ kind: 'warning', text: t('accounts.open_elsewhere') })
        return false
    }
    await wipeLocal(id)
    saveBook(B.removeProfile(freshBook(), id))
    showToast({ kind: 'success', text: t('accounts.removed_toast') })
    return true
}

/** Sign in again on a profile that needs attention: its broken session goes, its lists stay, and its sign in opens. */
export async function signInAgain(id: string) {
    if (needsPin(id)) return
    verified.delete(id)
    if (id === activeId && sessionOpen()) {
        closePicker()
        return
    }
    if (await heldElsewhere(id, true)) return showToast({ kind: 'warning', text: t('accounts.open_elsewhere') })
    if (B.find(freshBook(), id)?.pin) unlock(id)
    if (id === activeId) {
        // Not opened yet (the picker at launch): its session alone goes (it may be unreadable), its lists
        // stay, and the client opens on the normal sign in.
        await deleteDatabase(names.mtcute)
        leaveIntent({ kind: 'signinAgain' })
        markChosen()
        closePicker()
        openSession()
        return
    }
    await deleteDatabase(namesFor(id).mtcute)
    leaveIntent({ kind: 'signinAgain' })
    await switchTo(id)
}

/** The profiles whose PIN Remove all still needs (each asked by the pad before anything is wiped). */
export const removeAllNeeds = (): B.Profile[] => B.pinsForRemoveAll(freshBook(), activeId, unlockedId()).filter((p) => !verified.has(p.id))

/** Remove all accounts: every profile signed out in turn (each in its own page, no screen shown), then one empty profile on the browser's own storage. */
export async function removeAll(): Promise<boolean> {
    // Every locked profile's PIN first: one missing and nothing goes.
    if (removeAllNeeds().length) return false
    if (!(await removeAllClear())) return false
    const ok = await askConfirm({
        title: t('accounts.remove_all_title'),
        message: t('accounts.remove_all_message'),
        confirm: t('accounts.remove_all_confirm'),
        busy: t('settings.signing_out'),
        // Checked again right before anything is wiped: a tab opened, a profile added or a PIN set while the question was up stops it.
        run: async () => {
            if (await removeAllClear()) await continueRemoveAll()
        },
    })
    verified.clear()
    return ok
}

/**
 * Whether Remove all may wipe now: every locked profile's PIN entered (this picker's), and none of the
 * other profiles open in another tab (it would stop half way on the other-tab card). Says why not.
 */
async function removeAllClear(): Promise<boolean> {
    if (removeAllNeeds().length) return false
    for (const p of freshBook().profiles) {
        if (p.id !== activeId && (await heldElsewhere(p.id))) {
            showToast({ kind: 'warning', text: t('accounts.remove_all_elsewhere') })
            return false
        }
    }
    return true
}

async function continueRemoveAll() {
    const book = freshBook()
    if (book.profiles.length <= 1) {
        await leave()
        await signOutLast()
        stopSession()
        // A profile with a folder of its own goes with its storage: the browser's own names take over again.
        if (names.keyPrefix) await wipeLocal(activeId)
        saveBook(B.defaultBook())
        leaveIntent(null)
        setAddingFrom(null)
        setPending(null)
        relock()
        reloadInto(null)
        return
    }
    const rest = book.profiles.filter((p) => p.id !== activeId)
    await leave()
    await api.logOut().catch(() => {})
    stopSession()
    await wipeLocal(activeId)
    clearLogs()
    forgetLinks()
    saveBook(B.setActive(B.removeProfile(freshBook(), activeId), rest[0].id))
    setPending({ kind: 'removeAll', id: rest[0].id })
    relock()
    goHome()
}

// ---- What the page does once it runs its profile ----

let noticeShown = false
/** A line a reload carried over (the same account twice, a profile signed out): shown once the page is up. */
export function showCarriedNotice() {
    if (noticeShown) return
    noticeShown = true
    const intent = peekIntent()
    if (intent?.kind !== 'notice') return
    takeIntent()
    const text = { same: t('accounts.same_account'), removed: t('accounts.signed_out_toast'), cancelled: t('accounts.add_cancelled'), failed: t('accounts.signout_failed'), deleted: t('accounts.removed_toast') }[intent.text]
    // A line a reload carried over: the removals are done things, a failure is an error, the rest are notes.
    showToast({ kind: intent.text === 'failed' ? 'error' : intent.text === 'removed' || intent.text === 'deleted' ? 'success' : 'info', text })
}

function peekIntent(): Intent | null {
    const intent = takeIntent()
    leaveIntent(intent)
    return intent
}

/**
 * The page's profile answered who it is (or that it has no session). Does the work pending for it (a
 * sign out, a step of Remove all), with no screen of it shown; anything that fails there goes back
 * to the picker, which asks every PIN again. Returns 'leaving' when the page is about to go.
 */
export async function afterSessionRead(user: Me | null): Promise<'ok' | 'leaving' | 'signedOut'> {
    if (pendingWork) {
        try {
            if (pendingWork.kind === 'removeAll') await continueRemoveAll()
            else if (freshBook().profiles.length > 1) await signOutThisProfile()
            else {
                await signOutLast()
                setPending(null)
                reloadInto(activeId)
            }
        } catch (e) {
            console.warn('[accounts] pending work failed', e)
            toPicker()
        }
        return 'leaving'
    }
    const intent = peekIntent()
    if (intent?.kind === 'signinAgain') takeIntent()
    if (!user) {
        const p = activeProfile()
        // A profile that was signed in has lost its session: the picker says so, the others are untouched.
        if (p && p.userId !== null && p.state !== 'adding' && freshBook().profiles.length > 1 && intent?.kind !== 'signinAgain') {
            saveBook(B.markAttention(freshBook(), activeId))
            openPicker()
        }
        return 'ok'
    }
    markChosen()
    return 'ok'
}

/** Profiles a tab left half added (closed during the sign in) go once they are stale, unless a tab still has them open. */
export async function cleanStaleProfiles() {
    if (pendingWork) return
    for (const p of B.staleAdding(freshBook(), Date.now(), activeId)) {
        if (await heldElsewhere(p.id)) continue
        await wipeLocal(p.id)
        saveBook(B.removeProfile(freshBook(), p.id))
    }
}

/** The session could not be read at all (a broken store): with other profiles, this one needs attention. */
export function sessionUnreadable(): boolean {
    if (pendingWork) {
        toPicker()
        return true
    }
    if (freshBook().profiles.length < 2) return false
    saveBook(B.markAttention(freshBook(), activeId))
    openPicker()
    return true
}

/**
 * The page's profile is signed in as `user` (a sign in just now, or the session checked at start).
 * The same Telegram account on another profile is refused: this sign in is undone and the page goes
 * back. Returns false then.
 */
export async function attachSignedIn(user: Me): Promise<boolean> {
    if (pendingWork) return false
    const result = B.attach(freshBook(), activeId, { id: user.id, name: user.name ?? null })
    if ('stale' in result) return true
    if ('same' in result) {
        await refuseSame()
        return false
    }
    saveBook(B.setActive(result.book, activeId))
    markChosen()
    setAddingFrom(null)
    if (!photoOf(activeId) && freshBook().profiles.length > 1) void fetchPhoto()
    return true
}

async function refuseSame() {
    const from = addingFrom()
    const wasNew = activeProfile()?.userId == null
    closeWrites()
    await api.logOut().catch(() => {})
    stopSession()
    if (activeProfile()?.state === 'adding') {
        await wipeLocal(activeId)
        saveBook(B.removeProfile(freshBook(), activeId))
    } else if (wasNew) {
        // Signed in again as an account another profile already is: the profile keeps its lists and asks again.
        await deleteDatabase(names.mtcute)
    }
    setAddingFrom(null)
    leaveIntent({ kind: 'notice', text: 'same' })
    // A PIN profile is never gone back to without its PIN: the picker asks.
    reloadInto(B.backTarget(freshBook(), from))
}

/** The account's small Telegram photo, kept for the picker. */
export async function fetchPhoto() {
    try {
        const bytes = await api.myPhoto()
        if (!bytes) return
        const url = await new Promise<string | null>((resolve) => {
            const reader = new FileReader()
            reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : null)
            reader.onerror = () => resolve(null)
            reader.readAsDataURL(new Blob([bytes], { type: 'image/jpeg' }))
        })
        if (url) keepPhoto(activeId, url)
    } catch {}
}

// ---- PIN and name ----

export const pinsWork = () => typeof crypto !== 'undefined' && !!crypto.subtle

export async function tryPin(id: string, pin: string): Promise<B.PinAnswer> {
    // Counted on the book as it is once the PIN is derived: tries in another tab meanwhile still count.
    return B.checkPinIn(freshBook, saveBook, id, pin, Date.now(), crypto)
}

/** Sets or clears a PIN: only on the profile open and unlocked here, or one whose PIN was just entered. */
export async function changePin(id: string, pin: string | null): Promise<boolean> {
    if (needsPin(id)) return false
    saveBook(B.setPin(freshBook(), id, pin ? await B.makePin(pin, crypto) : null))
    // A PIN just set on the open profile: this tab keeps it open.
    if (pin && id === activeId) unlock(id)
    return true
}

/** Renames a profile; a PIN profile only once its PIN was entered (or it is open and unlocked here). */
export function renameProfile(id: string, name: string) {
    if (needsPin(id)) return
    saveBook(B.rename(freshBook(), id, name))
}
