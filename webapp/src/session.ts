// Who is signed in, for every page; and leaving a page the way the browser's Back would.
import { createSignal } from 'solid-js'
import type { Navigator } from '@solidjs/router'
import { api, whenUnauthorized } from './tg/rpc'
import { clearAll } from './store'
import { clearThumbs } from './cacheBounds'
import type { Me } from './tg/types'

/** undefined while the session is checked, null when signed out. */
const [me, setMe] = createSignal<Me | null | undefined>(undefined)
export { me, setMe }

/**
 * Telegram ended the session (HX23): the sign in page says so (errors.signed_out) until the next
 * sign in. Never set by the viewer's own Sign out.
 */
const [sessionEnded, setSessionEnded] = createSignal(false)
export { sessionEnded }

export async function signOut() {
    await api.logOut().catch(() => {})
    await clearAll()
    setMe(null)
}

/**
 * The session ended elsewhere, and the sign in page says why. Unlike Sign out the viewer chose
 * nothing, so their watched list, places, Watch later, stars, chosen chats, bookmarks and settings
 * stay for when they sign in again, as the apps keep them (169fff9); only the old session's
 * thumbnails go, with the worker's copy of the account.
 */
export async function sessionEndedElsewhere() {
    setSessionEnded(true)
    await api.logOut().catch(() => {})
    await clearThumbs().catch(() => {})
    setMe(null)
}

/** Signed in again: the line about the old session goes. */
export function signedIn(user: Me) {
    setSessionEnded(false)
    setMe(user)
}

// Signed out elsewhere: back to sign in, the viewer's own lists kept.
whenUnauthorized(() => {
    if (me()) sessionEndedElsewhere()
})

// The history entry the app was opened on is marked, so a page closed there goes Home instead of
// leaving the site.
const ENTRY = { tmplayerEntry: true }

export function markEntry() {
    history.replaceState({ ...history.state, ...ENTRY }, '')
}

/** A redirect that takes the place of the entry page keeps its mark. */
export const entryState = () => ((history.state as typeof ENTRY | null)?.tmplayerEntry ? ENTRY : undefined)

export function back(navigate: Navigator) {
    if ((history.state as typeof ENTRY | null)?.tmplayerEntry) navigate('/', { replace: true, state: ENTRY })
    else navigate(-1)
}
