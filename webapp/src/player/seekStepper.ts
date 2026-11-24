// One stepper for a held seek key (WP-61): the TV remote's Left and Right, a keyboard's arrows, and the
// seek bar's. A tap moves the base step; a key held down repeats about thirty times a second, and a
// repeat is let through only every 150 ms, so the video's `currentTime` is written at most about
// seven times a second whatever the repeat rate of the browser or the remote, and the step grows with
// the hold as the apps' does: the base, three times it after two seconds, six times after five, held to
// a 200th of the video's length (10 s to 120 s).
//
// The ramp is core's `RemoteQuirks.holdStepMs` (FireOs.kt), the rule the Android TV player runs. Core's
// `:core:webRules` does not export it yet, so it is written here, number for number, and
// seekStepper.test.ts reads FireOs.kt itself and fails when a constant or a test value there moves.
// Pure: no DOM, no timers; the caller hands in the clock.

/** A repeat sooner than this after the last one that moved is dropped. */
export const GATE_MS = 150
/** Repeats of a held key arrive about every 50 ms in core's count (RemoteQuirks.HOLD_EVERY's note). */
export const REPEAT_MS = 50
export const HOLD_EVERY = 4
export const HOLD_MEDIUM_FROM = 40
export const HOLD_LARGE_FROM = 100
/** The most a held step moves on a video this long: a 200th of it, from 10 s to 120 s. */
const CAP_SHARE = 200
const CAP_MIN_MS = 10_000
const CAP_MAX_MS = 120_000
/** A press within this of the last one that moved adds to one running total. */
export const RUN_MS = 700
/** The most often a screen reader is told where a held key has got to. */
export const ANNOUNCE_MS = 800

export const holdCapMs = (durationMs: number): number => Math.min(CAP_MAX_MS, Math.max(CAP_MIN_MS, Math.trunc(durationMs / CAP_SHARE)))

/** RemoteQuirks.holdStepMs: how far a held key moves on this repeat, 0 to let it pass. */
export function holdStepMs(repeatCount: number, baseMs: number, durationMs = 0): number {
    if (repeatCount <= 0) return baseMs
    if (repeatCount % HOLD_EVERY !== 0) return 0
    const step = repeatCount >= HOLD_LARGE_FROM ? baseMs * 6 : repeatCount >= HOLD_MEDIUM_FROM ? baseMs * 3 : baseMs
    if (!(durationMs > 0) || step <= baseMs) return step
    return Math.min(step, Math.max(holdCapMs(durationMs), baseMs))
}

export interface Press {
    forward: boolean
    /** KeyboardEvent.repeat */
    repeat: boolean
    /** Milliseconds, any clock that only goes forward. */
    now: number
    /** The base step in seconds: 10 on the TV layout, 5 on a desktop keyboard. */
    baseSec: number
    /** Seconds; 0 or NaN when not known. */
    durationSec: number
}

export interface Step {
    /** Seconds to move, signed. */
    by: number
    /** Where this run of presses has got to, signed, for the note. */
    total: number
    /** Say it to a screen reader now (not more than once in ANNOUNCE_MS). */
    announce: boolean
}

export class SeekStepper {
    /** When the hold of the key down now started; null when no key is down (none yet, or it came up). */
    private heldFrom: number | null = null
    private lastMoved = -Infinity
    private lastDir = 0
    private total = 0
    private announcedAt = -Infinity

    /** What one key press moves; null for a repeat the gate drops. */
    press(p: Press): Step | null {
        const dir = p.forward ? 1 : -1
        const base = Math.round(p.baseSec * 1000)
        const durationMs = Number.isFinite(p.durationSec) && p.durationSec > 0 ? Math.round(p.durationSec * 1000) : 0
        let stepMs: number
        if (!p.repeat || this.heldFrom === null) {
            // A new press: the plain step, and the hold starts here. So does a repeat whose first
            // press never came (the key held as the player opened, a remote that drops it), which
            // otherwise counted from 0 and took the 6x step at once (PB2-12).
            this.heldFrom = p.now
            stepMs = base
        } else {
            if (p.now - this.lastMoved < GATE_MS) return null
            const count = Math.floor((p.now - (this.heldFrom ?? p.now)) / REPEAT_MS)
            stepMs = holdStepMs(count - (count % HOLD_EVERY), base, durationMs)
            // A hold the first press did not start (focus came to the key already down): the plain step.
            if (stepMs <= 0) stepMs = base
        }
        if (p.now - this.lastMoved > RUN_MS || dir !== this.lastDir) this.total = 0
        this.lastMoved = p.now
        this.lastDir = dir
        const by = (dir * stepMs) / 1000
        this.total += by
        const announce = p.now - this.announcedAt >= ANNOUNCE_MS
        if (announce) this.announcedAt = p.now
        return { by, total: this.total, announce }
    }

    /** The key came up: the next press starts a run of its own. */
    release() {
        this.heldFrom = null
    }
}
