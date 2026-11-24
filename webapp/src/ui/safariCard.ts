// Safari clears what a site keeps in a browser tab after 7 days without a visit (WebKit's storage
// cap), the sign in and the lists with it; a Home Screen app is left alone. The card that says so,
// and when it is shown: signed in, in a Safari tab (not the installed app), until it is closed.

export const SAFARI_CARD_KEY = 'tmplayer-safari-card'

export interface CardState {
    /** An iPhone or iPad in a browser tab: not standalone. */
    inTab: boolean
    signedIn: boolean
    dismissed: boolean
}

export const showSafariCard = (s: CardState) => s.inTab && s.signedIn && !s.dismissed

/** Whether the card was closed: only an explicit "1" counts, and a browser that refuses storage shows it again. */
export function readDismissed(storage: Pick<Storage, 'getItem'> | null): boolean {
    try {
        return storage?.getItem(SAFARI_CARD_KEY) === '1'
    } catch {
        return false
    }
}

export function saveDismissed(storage: Pick<Storage, 'setItem'> | null) {
    try {
        storage?.setItem(SAFARI_CARD_KEY, '1')
    } catch {}
}
