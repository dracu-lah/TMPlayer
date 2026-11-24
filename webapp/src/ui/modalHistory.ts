// One history rule for every modal `<dialog>` (WP-54): opening pushes a history entry, the browser's
// Back (the iPhone's edge swipe has no CloseWatcher) closes the dialog instead of the page, and a
// dialog closed by a button, Escape or the TV's Back takes its entry off again. A route change closes
// every dialog that is open. Focus goes back to the control that opened the dialog on every close.
//
// The rules live here without a page so `node --test` can run them over a fake history; `install()`
// at the bottom wires them to `HTMLDialogElement.showModal`, `history` and `popstate`.

/** The flag a modal's history entry carries in `history.state`. */
export const FLAG = 'tmModal'

export interface HistoryLike {
    readonly state: unknown
    pushState(state: unknown, title: string, url?: string | null): void
    replaceState(state: unknown, title: string, url?: string | null): void
    back(): void
    forward(): void
}

export interface DialogHost {
    open: boolean
    close(): void
    addEventListener(type: 'close', fn: () => void): void
    contains(node: unknown): boolean
}

export interface FocusHost {
    /** The element holding focus; null, or the body, when nothing does. */
    active(): { isConnected: boolean } | null
    isBody(el: unknown): boolean
    focus(el: unknown): void
}

interface Entry {
    dialog: DialogHost
    /** The history entry this dialog owns; null when it has none (the push failed, or it was popped or replaced). */
    id: number | null
    opener: { isConnected: boolean } | null
}

const flagOf = (state: unknown): number | null => {
    if (!state || typeof state !== 'object') return null
    const v = (state as Record<string, unknown>)[FLAG]
    return typeof v === 'number' ? v : null
}

const depthOf = (state: unknown): number | null => {
    if (!state || typeof state !== 'object') return null
    const v = (state as Record<string, unknown>)._depth
    return typeof v === 'number' ? v : null
}

const withFlag = (state: unknown, id: number) => ({ ...(state && typeof state === 'object' ? (state as object) : {}), [FLAG]: id })

const withoutFlag = (state: unknown) => {
    if (!state || typeof state !== 'object') return state
    const { [FLAG]: _gone, ...rest } = state as Record<string, unknown>
    return rest
}

/** When the page last said it is going (leavingPage); -Infinity when it has not. */
let leavingAt = -Infinity
/** How long a leavingPage() holds: a page change that has not come by then was not going to. */
export const LEAVING_MS = 3000
const pageLeaving = () => Date.now() - leavingAt < LEAVING_MS

/**
 * Says the page is about to go (call it right before `location.replace` or `reload`): a dialog that
 * closes now takes no step back. `beforeunload` cannot say it in time in Safari: there it comes after
 * the close event of a question its button closed, so the step back ran first and cancelled the page
 * change, and Sign out and Remove all did their work but stayed on Settings (WebKit e2e, 2026-11-25).
 */
export function leavingPage(now = Date.now()) {
    leavingAt = now
}

export class ModalHistory {
    private entries: Entry[] = []
    private nextId = 1
    /** Backs this class asked for itself: their popstate is not the person's. */
    private ownBacks = 0
    private last: unknown
    /** A navigation away from the page has started (`beforeunload`): a closing dialog must not step history back over it. */
    private leaving = false

    private real: Pick<HistoryLike, 'pushState' | 'replaceState' | 'back' | 'forward'>
    private now: () => { state: unknown; path: string }
    private focus: FocusHost

    constructor(real: Pick<HistoryLike, 'pushState' | 'replaceState' | 'back' | 'forward'>, now: () => { state: unknown; path: string }, focus: FocusHost) {
        this.real = real
        this.now = now
        this.focus = focus
        this.last = now().state
    }

    /** Run once at start: a reload with a modal open leaves its entry behind (the dialog is gone), and Back steps over it. */
    boot() {
        if (flagOf(this.now().state) !== null) {
            this.ownBacks++
            this.real.back()
        }
    }

    /** A modal dialog just opened. */
    opened(dialog: DialogHost, opener: { isConnected: boolean } | null) {
        if (this.entries.some((e) => e.dialog === dialog)) return
        const entry: Entry = { dialog, id: null, opener }
        this.entries.push(entry)
        // A dialog opened as another one closes (a menu row that opens a picker) takes over its entry.
        const closing = this.entries.find((e) => e !== entry && !e.dialog.open && e.id !== null && e.id === this.currentId())
        if (closing) {
            entry.id = closing.id
            closing.id = null
        } else {
            try {
                const id = this.nextId++
                this.real.pushState(withFlag(this.now().state, id), '')
                entry.id = id
            } catch {
                // Safari throws past 100 pushes in 30 seconds: the dialog still works, Back just leaves the page.
            }
        }
        this.last = this.now().state
        dialog.addEventListener('close', () => this.closed(entry))
    }

    private currentId() {
        return flagOf(this.now().state)
    }

    private closed(entry: Entry) {
        // Closed and opened again before this event came: it belongs to the second opening.
        if (entry.dialog.open) return
        const i = this.entries.indexOf(entry)
        if (i < 0) return
        this.entries.splice(i, 1)
        if (entry.id !== null && this.currentId() === entry.id && !this.leaving && !pageLeaving()) {
            entry.id = null
            this.ownBacks++
            this.real.back()
        }
        entry.id = null
        this.restoreFocus(entry)
    }

    private restoreFocus(entry: Entry) {
        const o = entry.opener
        if (!o || !o.isConnected) return
        const a = this.focus.active()
        // The browser already put it back, or the page put it somewhere on purpose: leave that.
        if (a && !this.focus.isBody(a) && !entry.dialog.contains(a)) return
        try {
            this.focus.focus(o)
        } catch {}
    }

    /** `popstate`: true when the event was this class's and must not reach the router. */
    popstate(): boolean {
        const before = this.last
        this.last = this.now().state
        const here = this.currentId()
        if (this.ownBacks > 0) {
            this.ownBacks--
            // Landed on an entry no open dialog owns (one that closed under another): on, the way this back was going.
            if (here !== null && !this.entries.some((e) => e.id === here)) {
                this.ownBacks++
                this.real.back()
            }
            return true
        }
        let swallowed = false
        // Every dialog above the entry we landed on goes.
        const keep = here === null ? -1 : this.entries.findIndex((e) => e.id === here)
        const doomed = this.entries.slice(keep + 1).filter((e) => e.id !== null)
        for (const e of doomed) {
            e.id = null
            swallowed = true
            e.dialog.close()
            // A close that is held (a question whose work is running) leaves the dialog up: it takes an entry again.
            if (e.dialog.open) {
                try {
                    const id = this.nextId++
                    this.real.pushState(withFlag(this.now().state, id), '')
                    e.id = id
                    this.last = this.now().state
                } catch {}
            }
        }
        if (here !== null && keep < 0 && !swallowed) {
            // An entry no open dialog owns (one closed while not on top): step over it, the way the person was going.
            const from = depthOf(before)
            const to = depthOf(this.now().state)
            this.ownBacks++
            if (from !== null && to !== null && from <= to) this.real.forward()
            else this.real.back()
            return true
        }
        return swallowed
    }

    /**
     * The router writing history: on a modal's entry, a push replaces it (so no entry is left behind) and
     * a replace keeps its flag. Returns what to hand to the real call.
     */
    write(kind: 'push' | 'replace', state: unknown, pathChanged: boolean): { kind: 'push' | 'replace'; state: unknown } {
        const here = this.currentId()
        if (here === null) return { kind, state }
        if (kind === 'push') {
            // Entries from this one up are gone with it; their dialogs close with the route.
            for (const e of this.entries) if (e.id !== null && e.id >= here) e.id = null
            return { kind: 'replace', state: withoutFlag(state) }
        }
        if (!pathChanged) return { kind, state: withFlag(state, here) }
        for (const e of this.entries) if (e.id === here) e.id = null
        return { kind, state: withoutFlag(state) }
    }

    /** After any write to history: remembers where the person is, to know the direction of the next traversal. */
    sync() {
        this.last = this.now().state
    }

    /** After a write: the route changed, so every open dialog closes. */
    routeChanged() {
        this.sync()
        for (const e of [...this.entries]) {
            e.id = null
            if (e.dialog.open) e.dialog.close()
        }
    }

    /**
     * A page change has started (a reload or `location.replace` right after a button closed a question, as
     * Remove all and Sign out do): the dialog's `close` event comes after it, and a history step then would
     * cancel that navigation. Set again to false once the unload was cancelled or has not come.
     */
    setLeaving(on: boolean) {
        this.leaving = on
    }

    /** Test and debug view. */
    get open() {
        return this.entries.length
    }
}

/** Dialogs that take no history entry: the profile picker and its cover are closed by their own work, never by Back. */
export const exempt = (el: { matches?: (s: string) => boolean; closest?: (s: string) => unknown }) =>
    !!(el.matches?.('.profiles, .lock-cover, [data-while-locked], [data-no-history]') || el.closest?.('dialog.profiles'))

/** Browser wiring. Call once, before the router starts, so this class's popstate listener runs first. */
export function installModalHistory() {
    if (typeof window === 'undefined' || typeof HTMLDialogElement === 'undefined' || (window as unknown as Record<string, unknown>).__tmModalHistory) return
    ;(window as unknown as Record<string, unknown>).__tmModalHistory = true
    const h = window.history
    const real = {
        pushState: h.pushState.bind(h),
        replaceState: h.replaceState.bind(h),
        back: h.back.bind(h),
        forward: h.forward.bind(h),
    }
    const pathNow = () => location.pathname + location.search
    const mh = new ModalHistory(real, () => ({ state: h.state, path: pathNow() }), {
        active: () => document.activeElement,
        isBody: (el) => el === document.body,
        focus: (el) => (el as HTMLElement).focus?.({ preventScroll: true }),
    })
    mh.boot()

    const showModal = HTMLDialogElement.prototype.showModal
    if (showModal)
        HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
            const opener = document.activeElement
            const was = this.open
            showModal.call(this)
            if (!was && this.open && !exempt(this)) mh.opened(this, opener && opener !== document.body ? opener : null)
        }

    const guarded = (kind: 'push' | 'replace') =>
        function (state: unknown, title: string, url?: string | null) {
            const before = pathNow()
            let after = before
            if (url != null) {
                try {
                    const u = new URL(url, location.href)
                    after = u.pathname + u.search
                } catch {}
            }
            const w = mh.write(kind, state, after !== before)
            if (w.kind === 'push') real.pushState(w.state, title, url)
            else real.replaceState(w.state, title, url)
            mh.sync()
            if (after !== before) queueMicrotask(() => mh.routeChanged())
        }
    // A navigation started in the same turn a dialog was closed in must not meet this class's own history step.
    window.addEventListener('beforeunload', () => {
        mh.setLeaving(true)
        setTimeout(() => mh.setLeaving(false), 3000)
    })
    h.pushState = guarded('push')
    h.replaceState = guarded('replace')

    window.addEventListener('popstate', (e) => {
        if (mh.popstate()) e.stopImmediatePropagation()
    })
}
