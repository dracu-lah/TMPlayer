// A row asks for its content only once it has stayed on screen (HX54, HomeViewModel.rowShown after
// ROW_DWELL_MS): a hard scroll flies past dozens of rows and each asked at the moment it appeared
// was a burst of reads on Telegram's flood bucket. Keyed by what the row is, not by the component
// that draws it, because a row drawn again a moment after it was drawn is the same row: it keeps
// the time it had stayed and, once it has stayed, its place. Pure, timers and clock injected.

export interface DwellTimers {
    set(fn: () => void, ms: number): unknown
    clear(handle: unknown): void
}

export interface DwellOptions {
    /** How long a row stays on screen before it counts (250 ms in the apps). */
    ms: number
    /** How long a row that left is still remembered, so a row drawn again at once does not start over. */
    grace: number
    /** The row has stayed: ask for it. */
    confirm(key: string): void
    /** The row has left for good: drop what it asked that has not begun. */
    release(key: string): void
    timers?: DwellTimers
    now?: () => number
}

export interface Dwell {
    /** The row is on screen; `onConfirm` runs once it has stayed (at once for a row already confirmed). */
    enter(key: string, onConfirm: () => void): void
    /** The row left the screen. */
    leave(key: string): void
    /** Whether the row has stayed and not yet been released. */
    confirmed(key: string): boolean
    /**
     * What the other side knew is gone (the Telegram worker restarted): every row on screen starts
     * counting again and is asked afresh once it has stayed.
     */
    reset(): void
}

const real: DwellTimers = { set: (fn, ms) => setTimeout(fn, ms), clear: (h) => clearTimeout(h as ReturnType<typeof setTimeout>) }

interface Waiting {
    onConfirm: () => void
    /** Running countdown, or undefined while the row is away. */
    handle?: unknown
    startedAt: number
    /** Time still to stay. */
    remaining: number
    /** Forgets the row if it stays away. */
    grace?: unknown
}

export function createDwell(o: DwellOptions): Dwell {
    const timers = o.timers ?? real
    const now = o.now ?? Date.now
    const waiting = new Map<string, Waiting>()
    const done = new Set<string>()
    const leaving = new Map<string, unknown>()
    /** Rows on screen now, with whom to tell. */
    const present = new Map<string, () => void>()
    const cancelLeave = (key: string) => {
        const h = leaving.get(key)
        if (h === undefined) return
        timers.clear(h)
        leaving.delete(key)
    }
    const count = (key: string, w: Waiting) => {
        w.startedAt = now()
        w.handle = timers.set(() => {
            waiting.delete(key)
            done.add(key)
            o.confirm(key)
            w.onConfirm()
        }, w.remaining)
    }
    const api: Dwell = {
        enter(key, onConfirm) {
            present.set(key, onConfirm)
            cancelLeave(key)
            if (done.has(key)) return onConfirm()
            const held = waiting.get(key)
            if (!held) {
                const w: Waiting = { onConfirm, startedAt: 0, remaining: o.ms }
                waiting.set(key, w)
                return count(key, w)
            }
            // Counting down, or away a moment: the newest drawing is told, and the time it had stayed counts.
            held.onConfirm = onConfirm
            if (held.grace !== undefined) {
                timers.clear(held.grace)
                held.grace = undefined
                count(key, held)
            }
        },
        leave(key) {
            present.delete(key)
            const held = waiting.get(key)
            if (held) {
                if (held.handle === undefined) return
                // Away before it stayed: the time stays, for a moment, in case it is drawn again.
                timers.clear(held.handle)
                held.handle = undefined
                held.remaining = Math.max(0, held.remaining - (now() - held.startedAt))
                held.grace = timers.set(() => waiting.delete(key), o.grace)
                return
            }
            if (!done.has(key) || leaving.has(key)) return
            leaving.set(
                key,
                timers.set(() => {
                    leaving.delete(key)
                    done.delete(key)
                    o.release(key)
                }, o.grace),
            )
        },
        confirmed: (key) => done.has(key),
        reset() {
            for (const w of waiting.values()) {
                if (w.handle !== undefined) timers.clear(w.handle)
                if (w.grace !== undefined) timers.clear(w.grace)
            }
            for (const h of leaving.values()) timers.clear(h)
            waiting.clear()
            leaving.clear()
            done.clear()
            for (const [key, onConfirm] of [...present]) api.enter(key, onConfirm)
        },
    }
    return api
}
