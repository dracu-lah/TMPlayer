// One asked Refresh for Home and Chats (WP-40, the web half of core's AskedRefresh): the button, a pull
// on a phone, and the page coming back after a while all go through `ask`, so a refresh already running is
// not started twice. Pure (the clock and the timers are handed in) so `node --test` drives it; the status
// is the work status of ui/workStatus.ts, shown by ui/WorkStatus.tsx: nothing under 400 ms, "Updating…"
// after that, "Up to date" for 2 s when it ended after being shown, "Could not update" with Try again
// when it failed or ran past its time box.
import type { WorkStatus } from './workStatus'

export const SHOW_AFTER_MS = 400
export const UPDATED_FOR_MS = 2000
export const TIME_BOX_MS = 60_000
/** How long a tab or an installed app stays hidden before coming back reloads Home and the list. */
export const HIDDEN_FOR_MS = 5 * 60_000

export interface Timers {
    set: (fn: () => void, ms: number) => unknown
    clear: (id: unknown) => void
}

const real: Timers = { set: (fn, ms) => setTimeout(fn, ms), clear: (id) => clearTimeout(id as ReturnType<typeof setTimeout>) }

export interface AskedRefreshDeps {
    /** The page's own reload; a rejection is a failure. */
    run: () => Promise<unknown>
    /** Where the status goes. */
    set: (status: WorkStatus) => void
    now?: () => number
    timers?: Timers
}

export interface AskedRefresh {
    /** Starts the reload unless one runs; true when it started. `quiet` is for a reload nobody asked for (the page coming back): no words unless it fails. */
    ask: (quiet?: boolean) => boolean
    running: () => boolean
}

export function createAskedRefresh(deps: AskedRefreshDeps): AskedRefresh {
    const now = deps.now ?? Date.now
    const timers = deps.timers ?? real
    let running = false
    let round = 0
    const ask = (quiet = false) => {
        if (running) return false
        running = true
        const mine = ++round
        let shown = false
        let settled = false
        let boxTimer: unknown = null
        const showTimer = quiet
            ? null
            : timers.set(() => {
                  shown = true
                  deps.set({ kind: 'syncing', what: '' })
              }, SHOW_AFTER_MS)
        const end = (ok: boolean) => {
            if (settled) return
            settled = true
            running = false
            if (showTimer !== null) timers.clear(showTimer)
            timers.clear(boxTimer)
            if (!ok) {
                deps.set({ kind: 'failed', message: '', keptAt: now() })
            } else if (shown) {
                deps.set({ kind: 'updated', at: now() })
                timers.set(() => round === mine && deps.set({ kind: 'idle' }), UPDATED_FOR_MS)
            }
        }
        boxTimer = timers.set(() => end(false), TIME_BOX_MS)
        let work: Promise<unknown>
        try {
            work = deps.run()
        } catch {
            work = Promise.reject(new Error('run failed'))
        }
        work.then(
            () => end(true),
            () => end(false),
        )
        return true
    }
    return { ask, running: () => running }
}

/** Whether a page that was hidden since `hiddenAt` is due a quiet reload now it is visible again. */
export function dueOnReturn(hiddenAt: number | null, now: number, after = HIDDEN_FOR_MS): boolean {
    return hiddenAt !== null && now >= hiddenAt && now - hiddenAt >= after
}

/** The parts of `document` the return watcher reads, so a test can stand in for it. */
export interface ReturnDoc {
    visibilityState: string
    addEventListener: (type: 'visibilitychange', fn: () => void) => void
    removeEventListener: (type: 'visibilitychange', fn: () => void) => void
}

/**
 * Calls `fn` once each time the page becomes visible after being hidden for `after` ms (default 5
 * minutes), as Apple TV, Netflix and YouTube refresh on return. Returns the stop function.
 */
export function onReturnAfterHidden(fn: () => void, doc: ReturnDoc = document, now: () => number = Date.now, after = HIDDEN_FOR_MS): () => void {
    let hiddenAt: number | null = doc.visibilityState === 'hidden' ? now() : null
    const onChange = () => {
        if (doc.visibilityState === 'hidden') {
            hiddenAt ??= now()
            return
        }
        const due = dueOnReturn(hiddenAt, now(), after)
        hiddenAt = null
        if (due) fn()
    }
    doc.addEventListener('visibilitychange', onChange)
    return () => doc.removeEventListener('visibilitychange', onChange)
}

/** How far a pull must go to refresh, and the most it shows: a thumb's travel, not a screen's. */
export const PULL_TRIGGER_PX = 72
export const PULL_MAX_PX = 96

/** The pull's shown distance for a finger travel of `dy`: it follows at half, never past the cap, nothing for an upward move. */
export function pullShown(dy: number): number {
    if (!(dy > 0)) return 0
    return Math.min(PULL_MAX_PX, dy / 2)
}

/** Whether letting go after a travel of `dy` refreshes. */
export const pullReleases = (dy: number): boolean => dy >= PULL_TRIGGER_PX
