// Whether the browser has a connection, for the offline line and the lists that try again by
// themselves once it is back; and whether the browser is online while Telegram is not answering
// (the "Can't reach Telegram" banner, U2).
import { createEffect, createSignal, onCleanup } from 'solid-js'
import { api, link } from './tg/rpc'
import { createLinkLine, createReachWatch, singleFlight, type LinkLine } from './ui/reach'

const [online, setOnline] = createSignal(navigator.onLine)
window.addEventListener('online', () => setOnline(true))
window.addEventListener('offline', () => setOnline(false))
export { online }

/** Runs `fn` each time the connection comes back, for as long as the calling component lives. */
export function onBackOnline(fn: () => void) {
    window.addEventListener('online', fn)
    onCleanup(() => window.removeEventListener('online', fn))
}

const [unreachable, setUnreachable] = createSignal(false)
/** The browser is online and the worker's line to Telegram has stayed down for 20 s (held through a blink for 3 s). */
export { unreachable }

const [linkLine, setLinkLine] = createSignal<LinkLine>(null)
/** "Connecting…" after 3 s of that line and "Updating…" after 2 s of catching up, while online and watched (LS-29, reach.ts). */
export { linkLine }

let watch: ReturnType<typeof createReachWatch> | null = null
let line: ReturnType<typeof createLinkLine> | null = null

/**
 * Starts the banner's watch, once, while `active` (signed in and holding the session). Not counted
 * while the page is in the background: a phone that was in a pocket comes back with its line down,
 * which is a reconnect and not a blocked network, and the count starts again on its return.
 */
export function watchReach(active: () => boolean) {
    watch ??= createReachWatch(setUnreachable)
    line ??= createLinkLine(setLinkLine)
    const [seen, setSeen] = createSignal(!document.hidden)
    const onSeen = () => setSeen(!document.hidden)
    document.addEventListener('visibilitychange', onSeen)
    createEffect(() => {
        const state = link()
        // Not told yet (the worker is starting) is not down; 'updating' is connected and catching up.
        const down = state !== null && state !== 'connected' && state !== 'updating'
        watch!.update(active() && online() && seen() && down)
        // Offline is the offline line's to say, and a page in the background says nothing.
        line!.update(active() && online() && seen() ? state : null)
    })
    onCleanup(() => {
        document.removeEventListener('visibilitychange', onSeen)
        watch?.stop()
        watch = null
        line?.stop()
        line = null
        setUnreachable(false)
    })
}

/** Retry on the banner: the connections open again and the 20 s count starts again. */
const reconnect = singleFlight(() => api.reconnect())
export function retryReach() {
    watch?.restart()
    reconnect()
}
