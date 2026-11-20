// The viewer's SubMaker install address (player/submaker.ts), the one secret this page keeps. It lives
// in the page's own IndexedDB store beside the settings (so Sign out clears it with them), never in
// localStorage, never in the address bar, a toast, a title or a log: the page reads only whether it is
// set, and logs.ts is told the address so that no line can carry it.
import { createSignal } from 'solid-js'
import { kvRead, kvRemove, kvWrite } from './store'
import { hideAddon } from './logs'
import { normalizeBase } from './player/submaker'

const KEY = 'secret:submaker'

const [base, setBase] = createSignal<string | null>(null)

/** The stored address, for the player's calls only. Never put it in text. */
export const submakerBase = base
/** Whether an address is set: the only thing a screen says about it. */
export const submakerSet = () => base() !== null

function take(next: string | null) {
    setBase(next)
    hideAddon(next)
}

let loading: Promise<void> | null = null

/** Reads the stored address once. Anything stored that is not a base is dropped. */
export function loadSubmaker(): Promise<void> {
    loading ??= kvRead<unknown>(KEY).then(
        (v) => take(normalizeBase(v)),
        () => {},
    )
    return loading
}

/** Stores what the viewer pasted; false (nothing changed) when it is not an install address. */
export function setSubmakerBase(pasted: string): boolean {
    const next = normalizeBase(pasted)
    if (!next) return false
    kvWrite(KEY, next)
    take(next)
    return true
}

export function removeSubmaker() {
    kvRemove(KEY, true)
    take(null)
}

/** Sign out: the store is cleared already, this page forgets it too. */
export function forgetSubmaker() {
    loading = null
    take(null)
}
