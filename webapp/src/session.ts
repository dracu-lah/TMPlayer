// Who is signed in, for every page; and leaving a page the way the browser's Back would.
import { createSignal } from 'solid-js'
import type { Navigator } from '@solidjs/router'
import { api, whenUnauthorized } from './tg/rpc'
import { clearAll } from './store'
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

/** The session ended elsewhere: as Sign out, and the sign in page says why. */
export async function sessionEndedElsewhere() {
    setSessionEnded(true)
    await signOut()
}

/** Signed in again: the line about the old session goes. */
export function signedIn(user: Me) {
    setSessionEnded(false)
    setMe(user)
}

// Signed out elsewhere: back to sign in, with nothing of the old account left behind.
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
