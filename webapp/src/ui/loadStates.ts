// What Home, a chat and a profile switch say when they load, fail or are asked twice (WP-99, LS-25 to
// LS-30). Pure, so `node --test src/ui/loadStates.test.ts` (pnpm test:ui) drives it without a page;
// the screens draw the result with ui/WorkStatus.tsx (the one status line, WP-98).
import { idle, type WorkStatus } from './workStatus.ts'

/**
 * Home's own load failed while rows are on screen (LS-25): they stay, and the line says since when
 * (`keptAt`, the last answer that landed) with Try again. Offline is the offline line's to say, and a
 * Home with no rows has its own card, so both are Idle here.
 */
export function keptFailure(o: { failed: boolean; online: boolean; rows: number; keptAt: number | null }): WorkStatus {
    if (!o.failed || !o.online || o.rows <= 0) return idle
    return { kind: 'failed', message: '', keptAt: o.keptAt }
}

/** The foot of Home when some chats' rows could not be read (LS-26): `say` is the sentence with its own count. */
export function rowsFailure(failed: number, ok: number, say: (count: number) => string): WorkStatus {
    if (!Number.isFinite(failed) || failed <= 0) return idle
    const count = Math.floor(failed)
    return { kind: 'partial', okCount: Math.max(0, ok), failedCount: count, what: say(count) }
}

export interface Latest {
    /** Starts a read: the one before it is aborted, and `current()` is false for it from now on. */
    begin: () => { signal: AbortSignal; current: () => boolean }
    /** The subject changed (another chat, topic): the read in flight is aborted and none is current. */
    cancel: () => void
}

/**
 * One read at a time for a screen (LS-28): a new read, or a change of subject, aborts the one still
 * running, so the worker is not left reading a chat nobody is looking at, and its answer is dropped.
 */
export function createLatest(): Latest {
    let seq = 0
    let running: AbortController | null = null
    return {
        begin() {
            running?.abort()
            const mine = ++seq
            const own = (running = new AbortController())
            return { signal: own.signal, current: () => mine === seq && !own.signal.aborted }
        },
        cancel() {
            running?.abort()
            running = null
            seq++
        },
    }
}

/** An abort is not a failure: what a cancelled read rejects with. */
export const isAbort = (e: unknown): boolean => (e as { name?: string } | null)?.name === 'AbortError'

export interface SwitchGuard {
    /** True when this switch may start; false while another runs (a double click, a second tile). */
    start: (id: string) => boolean
    /** The switch did not go through (a PIN, "stay here", a failure): the next one may start. */
    release: () => void
    /** The profile being opened, or null. */
    current: () => string | null
    /**
     * The page should go now (a reload into the profile): if it is still here after `ms` (the reload
     * never came, or a back/forward cache brought it back), the guard lets go, so the next press is
     * not ignored for good (SEC-10). Only the switch it was asked for is released.
     */
    releaseAfter: (ms: number, schedule?: (run: () => void, ms: number) => unknown) => void
}

/** One profile switch at a time (LS-30); `onChange` tells the tile which profile is opening. */
export function createSwitchGuard(onChange: (id: string | null) => void = () => {}): SwitchGuard {
    let id: string | null = null
    return {
        start(next) {
            if (id !== null) return false
            id = next
            onChange(id)
            return true
        },
        release() {
            if (id === null) return
            id = null
            onChange(null)
        },
        current: () => id,
        releaseAfter(ms, schedule = (run, wait) => setTimeout(run, wait)) {
            const held = id
            if (held === null) return
            schedule(() => {
                if (id === held) this.release()
            }, ms)
        },
    }
}
