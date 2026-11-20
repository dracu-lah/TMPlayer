// Who is signed in, for every page; and leaving a page the way the browser's Back would.
import { createSignal } from 'solid-js'
import type { Navigator } from '@solidjs/router'
import { api, whenUnauthorized } from './tg/rpc'
import { clearLogs } from './logs'
import { clearThumbs } from './cacheBounds'
import { forgetLinks } from './ui/tgLinks'
import { wipeAllPicks } from './player/subpicks-db'
import type { Me } from './tg/types'
import { activeId, freshBook, saveBook } from './accounts'
import { attachSignedIn, signOutLast, signOutThisProfile } from './accountFlows'
import { markAttention } from './ui/accountBook'

/** undefined while the session is checked, null when signed out. */
const [me, setMe] = createSignal<Me | null | undefined>(undefined)
export { me, setMe }

/**
 * Telegram ended the session (HX23): the sign in page says so (errors.signed_out) until the next
 * sign in. Never set by the viewer's own Sign out.
 */
const [sessionEnded, setSessionEnded] = createSignal(false)
export { sessionEnded }

/**
 * Sign out of this profile (HX88): with other profiles it alone goes, and the page opens the picker;
 * the last one leaves this browser as before profiles, on the sign in.
 */
export async function signOut() {
    if (freshBook().profiles.length > 1) return signOutThisProfile()
    await signOutLast()
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
    // With other profiles, the picker says this one needs signing in again; the others are untouched.
    if (freshBook().profiles.length > 1) saveBook(markAttention(freshBook(), activeId))
    forgetLinks()
    await api.logOut().catch(() => {})
    // Its names are in the page's log: they go with the old session, whoever signs in next (WB18).
    clearLogs()
    await clearThumbs().catch(() => {})
    // The remembered online subtitles belong to the account that ended, whoever signs in next (HX25).
    await wipeAllPicks()
    setMe(null)
}

/**
 * Signed in: the line about the old session goes, and the profile records who it is. The same
 * Telegram account as another profile is refused (the page goes back to that one) and nothing is set.
 */
export async function signedIn(user: Me) {
    if (!(await attachSignedIn(user))) return
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
