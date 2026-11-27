// Recent searches (WP-59).
import { createSignal } from 'solid-js'
import * as Recents from '../ui/recents'
import { read, remove, write } from './kv'
import { settings } from './settings'

// Up to ten, newest first, under `recents`; kept only while "Remember searches" is on, and gone with
// Clear and with sign out.

const RECENTS = 'recents'
const [recentSearches, setRecentSearches] = createSignal<string[]>([])
export { recentSearches, setRecentSearches }

export async function loadRecents() {
    setRecentSearches(Recents.parse(await read<unknown>(RECENTS)))
}

/** A search the viewer settled on, remembered unless they turned remembering off. */
export function noteSearch(query: string) {
    if (!settings().rememberSearches) return
    const next = Recents.add(recentSearches(), query)
    if (next.length === recentSearches().length && next.every((s, i) => s === recentSearches()[i])) return
    setRecentSearches(next)
    write(RECENTS, next)
}

export function forgetSearch(query: string) {
    const next = Recents.remove(recentSearches(), query)
    setRecentSearches(next)
    write(RECENTS, next)
}

export function clearSearches() {
    setRecentSearches([])
    remove(RECENTS, true)
}

/** A backup's searches joined behind this device's own; nothing is added while remembering is off. Returns how many came in. */
export function restoreSearches(theirs: readonly string[]): number {
    if (!settings().rememberSearches) return 0
    const next = Recents.join(recentSearches(), theirs)
    const added = next.length - recentSearches().length
    if (added <= 0) return 0
    setRecentSearches(next)
    write(RECENTS, next)
    return added
}
