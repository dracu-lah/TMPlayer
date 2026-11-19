// A row asks for its content only once it has stayed on screen (HX54, HomeViewModel.rowShown after
// ROW_DWELL_MS): a hard scroll flies past dozens of rows and each asked at the moment it appeared
// was a burst of reads on Telegram's flood bucket. Keyed by what the row is, not by the component
// that draws it, because Home draws its rows again whenever a page of answers lands: a row that is
// drawn anew a moment after it was drawn is the same row, and keeps its place. Pure, timers injected.

export interface DwellTimers {
    set(fn: () => void, ms: number): unknown
    clear(handle: unknown): void
}

export interface DwellOptions {
    /** How long a row stays on screen before it counts (250 ms in the apps). */
    ms: number
    /** How long a row that left is still counted, so a row drawn again at once does not start over. */
    grace: number
    /** The row has stayed: ask for it. */
    confirm(key: string): void
    /** The row has left for good: drop what it asked that has not begun. */
    release(key: string): void
    timers?: DwellTimers
}

export interface Dwell {
    /** The row is on screen; `onConfirm` runs once it has stayed (at once for a row already confirmed). */
    enter(key: string, onConfirm: () => void): void
    /** The row left the screen. */
    leave(key: string): void
    /** Whether the row has stayed and not yet been released. */
    confirmed(key: string): boolean
}

const real: DwellTimers = { set: (fn, ms) => setTimeout(fn, ms), clear: (h) => clearTimeout(h as ReturnType<typeof setTimeout>) }

export function createDwell(o: DwellOptions): Dwell {
    const timers = o.timers ?? real
    const waiting = new Map<string, { handle: unknown; onConfirm: () => void }>()
    const done = new Set<string>()
    const leaving = new Map<string, unknown>()
    const cancelLeave = (key: string) => {
        const h = leaving.get(key)
        if (h === undefined) return
        timers.clear(h)
        leaving.delete(key)
    }
    return {
        enter(key, onConfirm) {
            cancelLeave(key)
            if (done.has(key)) return onConfirm()
            const held = waiting.get(key)
            // Already counting down (two drawings of one row): the newest one is told.
            if (held) return void (held.onConfirm = onConfirm)
            const entry = {
                onConfirm,
                handle: timers.set(() => {
                    waiting.delete(key)
                    done.add(key)
                    o.confirm(key)
                    entry.onConfirm()
                }, o.ms),
            }
            waiting.set(key, entry)
        },
        leave(key) {
            const held = waiting.get(key)
            if (held) {
                // Gone before it stayed: never asked.
                timers.clear(held.handle)
                waiting.delete(key)
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
    }
}
