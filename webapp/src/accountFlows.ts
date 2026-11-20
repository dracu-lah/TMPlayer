// What the profiles' buttons do (HX88 phase B3): switch, add, sign one out, remove one, remove all,
// the PIN and the name. A switch never opens a second Telegram client: the page saves what is
// playing, lets nothing else of its profile land, and reloads into the next profile, whose own
// session opens there. Work that has to run in another profile's page (signing a sleeping profile
// out, Remove all) is left as an intent the next page carries on (accounts.ts).
import * as B from './ui/accountBook'
import {
    activeId,
    activeProfile,
    addingFrom,
    book,
    chooseInTab,
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
    photoOf,
    playing,
    runBeforeSwitch,
    saveBook,
    setAddingFrom,
    takeIntent,
    type Intent,
} from './accounts'
import { api, openSession, stopSession } from './tg/rpc'
import { clearAll, clearProfileData, flushWrites } from './store'
import { wipeAllPicks } from './player/subpicks-db'
import { clearThumbs } from './cacheBounds'
import { clearLogs, sizeText } from './logs'
import { forgetLinks } from './ui/tgLinks'
import { forgetSubmaker } from './submakerBase'
import { askConfirm } from './ui/Confirm'
import { showToast } from './ui/Toast'
import { t } from './i18n'
import type { Me } from './tg/types'

/** The page starts again on the profile `id` (null: the picker, or the only one), at Home: an address of one account (a chat, a video) means nothing to another. */
const reloadInto = (id: string | null) => {
    chooseInTab(id)
    location.replace('/')
}

/** The page lets go of its profile: the player keeps its place, the writes in flight land, nothing after does. */
async function leave() {
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

/** The viewer picked the profile this page already runs (the picker at launch, or the one in use). */
function stayHere() {
    markChosen()
    closePicker()
    openSession()
}

/** Opens a profile: this one stays, another reloads the page into it. A PIN was asked by the picker. */
export async function switchTo(id: string) {
    if (id === activeId) return stayHere()
    const book = freshBook()
    if (!B.find(book, id)) return
    if (!(await okToLeave())) return
    await leave()
    saveBook(B.setActive(freshBook(), id))
    reloadInto(id)
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

/** Add account: a profile of its own, then the normal sign in in it; Cancel there comes back here. */
export async function addAccount() {
    const book = freshBook()
    if (!B.canAdd(book)) return showToast({ text: t('accounts.limit', { count: B.MAX_PROFILES }) })
    const made = B.addProfile(book, B.newFolder(book, Math.random), Date.now(), await freeBytes())
    if ('full' in made) return showToast({ text: t('accounts.limit', { count: B.MAX_PROFILES }) })
    if ('noSpace' in made) return showToast({ text: t('accounts.no_space', { needed: sizeText(made.noSpace.needed), free: sizeText(made.noSpace.free) }) })
    if (!(await okToLeave())) return
    await leave()
    saveBook(made.book)
    setAddingFrom(activeId)
    reloadInto(made.profile.id)
}

/** On the sign in of a profile being added: forget it and go back to the profile the viewer came from. */
export async function cancelAdding() {
    const from = addingFrom()
    closeWrites()
    stopSession()
    await wipeLocal(activeId)
    saveBook(B.removeProfile(freshBook(), activeId))
    setAddingFrom(null)
    const back = B.find(freshBook(), from)?.id ?? null
    reloadInto(back)
}

/** Whether this page's profile is still being added (its sign in has Cancel). */
export const addingHere = () => activeProfile()?.state === 'adding' && book().profiles.length > 1

/**
 * Sign out of this profile while others stay: Telegram is told (at most 5 s), then its session,
 * its lists, places, picks and caches go from this browser, and the page opens the picker (or the
 * one profile left). The device's settings and every other profile stay.
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
    saveBook(B.removeProfile(freshBook(), activeId))
}

/** Signs out a profile from the picker: this one at once, a sleeping one by opening it first (its client signs it out). */
export async function signOutProfile(id: string) {
    if (id === activeId) return signOutThisProfile()
    if (await heldElsewhere(id)) return showToast({ text: t('accounts.open_elsewhere') })
    leaveIntent({ kind: 'signout' })
    await switchTo(id)
}

/** Forgets a profile without reaching Telegram: one whose session is missing or broken, or one never signed in. */
export async function removeProfile(id: string) {
    if (id === activeId) return signOutThisProfile()
    if (await heldElsewhere(id)) return showToast({ text: t('accounts.open_elsewhere') })
    await wipeLocal(id)
    saveBook(B.removeProfile(freshBook(), id))
    showToast({ text: t('accounts.removed_toast') })
}

/** Sign in again on a profile that needs attention: its broken session goes, its lists stay, and its sign in opens. */
export async function signInAgain(id: string) {
    if (id === activeId) {
        closePicker()
        openSession()
        return
    }
    if (await heldElsewhere(id)) return showToast({ text: t('accounts.open_elsewhere') })
    await deleteDatabase(namesFor(id).mtcute)
    leaveIntent({ kind: 'signinAgain' })
    await switchTo(id)
}

/** Remove all accounts: every profile signed out in turn (each in its own page), then one empty profile on the browser's own storage. */
export async function removeAll() {
    const ok = await askConfirm({
        title: t('accounts.remove_all_title'),
        message: t('accounts.remove_all_message'),
        confirm: t('accounts.remove_all_confirm'),
        busy: t('settings.signing_out'),
        run: () => continueRemoveAll(),
    })
    return ok
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
    leaveIntent({ kind: 'removeAll' })
    reloadInto(rest[0].id)
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
    const text = { same: t('accounts.same_account'), removed: t('accounts.signed_out_toast'), cancelled: t('accounts.add_cancelled') }[intent.text]
    showToast({ text })
}

function peekIntent(): Intent | null {
    const intent = takeIntent()
    leaveIntent(intent)
    return intent
}

/** Whether this page was opened to sign its profile in again (no picker over the sign in). */
export const signingInAgain = () => peekIntent()?.kind === 'signinAgain'

/**
 * The page's profile answered who it is (or that it has no session). Carries on what a reload left:
 * a sign out of this profile, Remove all. Returns false when the page is about to leave.
 */
export async function afterSessionRead(user: Me | null): Promise<'ok' | 'leaving' | 'signedOut'> {
    const intent = peekIntent()
    if (intent?.kind === 'signout' || intent?.kind === 'removeAll') {
        takeIntent()
        if (intent.kind === 'removeAll') {
            await continueRemoveAll()
            return 'leaving'
        }
        if (freshBook().profiles.length > 1) {
            await signOutThisProfile()
            return 'leaving'
        }
        await signOutLast()
        return 'signedOut'
    }
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
    for (const p of B.staleAdding(freshBook(), Date.now(), activeId)) {
        if (await heldElsewhere(p.id)) continue
        await wipeLocal(p.id)
        saveBook(B.removeProfile(freshBook(), p.id))
    }
}

/** The session could not be read at all (a broken store): with other profiles, this one needs attention. */
export function sessionUnreadable(): boolean {
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
    reloadInto(B.find(freshBook(), from)?.id ?? null)
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
    const answer = await B.checkPin(freshBook(), id, pin, Date.now(), crypto)
    saveBook(answer.book)
    return answer
}

export async function changePin(id: string, pin: string | null) {
    saveBook(B.setPin(freshBook(), id, pin ? await B.makePin(pin, crypto) : null))
}

export function renameProfile(id: string, name: string) {
    saveBook(B.rename(freshBook(), id, name))
}
