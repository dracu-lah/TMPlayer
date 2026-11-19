// "Can't reach Telegram" (the apps' ConnectAdvice.unreachable and AuthStall.watchWaiting, U2): the
// browser says it is online and the worker's connection to Telegram stays down. The banner comes
// after UNREACHABLE_MS of that, and once shown it stays through a blink of line and goes only after
// the line has held for CLEAR_MS, with no second 20 s wait when it drops again inside that.
// Pure with timers passed in, so a test can drive the clock; net.ts feeds it.

export const UNREACHABLE_MS = 20_000
export const CLEAR_MS = 3_000

export interface Timers {
    set(fn: () => void, ms: number): number
    clear(handle: number): void
}

const real: Timers = { set: (fn, ms) => window.setTimeout(fn, ms), clear: (h) => window.clearTimeout(h) }

export function createReachWatch(onChange: (shown: boolean) => void, timers: Timers = real) {
    let held = false
    let shown = false
    let timer: number | null = null
    const stop = () => {
        if (timer !== null) timers.clear(timer)
        timer = null
    }
    const show = (on: boolean) => {
        if (shown === on) return
        shown = on
        onChange(on)
    }
    const arm = () => {
        stop()
        if (held) {
            // On already, and the line was only up for a blink: it stays on.
            if (shown) return
            timer = timers.set(() => show(true), UNREACHABLE_MS)
        } else if (shown) timer = timers.set(() => show(false), CLEAR_MS)
    }
    return {
        /** Whether the line is held up now; a repeat of the last answer changes nothing. */
        update(next: boolean) {
            if (next === held) return
            held = next
            arm()
        },
        /** Retry: the banner goes and the count starts again if the line is still held up. */
        restart() {
            stop()
            show(false)
            if (held) timer = timers.set(() => show(true), UNREACHABLE_MS)
        },
        stop,
    }
}
