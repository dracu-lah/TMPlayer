// Sign out: everything the page keeps goes.
import { forgetLinks } from '../ui/tgLinks'
import { clearLogs } from '../logs'
import { clearIndex, THUMBS } from '../cacheBounds'
import * as Lists from '../ui/lists'
import { clearDatabase, forgetMemory } from './kv'
import { applyLook, applyTheme, DEFAULTS, setSettings } from './settings'
import { LAST_KEY } from './history'
import { setLaterIds } from './later'
import { setAddedIdsSignal, setAddedModeSignal, setStarredIds } from './chats'
import { setListsDoc } from './lists'
import { setRecentSearches } from './recents'
import { setPicks } from './picks'
import { setTitles } from './titles'
import { setApartIds, setChosenCopies, setCopyHabitSignal } from './copies'
import { POSTERS } from './thumbs'

/** Sign out: settings, positions, watched, stars, thumbnails and posters go; the theme falls back to the system's. */
export async function clearAll() {
    forgetMemory()
    // The message links asked for are the account's (U9): none stays for the next one.
    forgetLinks()
    // The page's log, the names it remembered to take out of it and the crash note are the account's too (WB18).
    clearLogs()
    try {
        localStorage.removeItem(LAST_KEY)
    } catch {}
    await clearDatabase()
    await globalThis.caches?.delete(THUMBS).catch(() => {})
    await globalThis.caches?.delete(POSTERS).catch(() => {})
    await clearIndex()
    setSettings({ ...DEFAULTS })
    setStarredIds(new Set<number>())
    setLaterIds(new Set<string>())
    setListsDoc(Lists.emptyLists())
    setRecentSearches([])
    setPicks(new Map())
    setTitles(new Map())
    setApartIds(new Set<string>())
    setChosenCopies([])
    setCopyHabitSignal(null)
    // The added chats are the account's, not the device's, so signing out leaves none of them
    // behind and the next account is asked for itself (as the apps' clearEverything does).
    setAddedIdsSignal(new Set<number>())
    setAddedModeSignal('unanswered')
    applyTheme(DEFAULTS.theme)
    applyLook(DEFAULTS)
}
