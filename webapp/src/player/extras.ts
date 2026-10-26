// The player's smaller rules, ported from core's pureMain so the browser answers as the apps do:
// A-B repeat (AbLoop), the way back from a long jump (SeekUndo), when to ask "Still watching?"
// (StillWatching), the sleep timer's choices (SleepTimer) and the slowed scrub (FineScrub). Times are seconds here where
// the Kotlin keeps milliseconds. Nothing here touches the page.

// ---- A-B repeat ----------------------------------------------------------------------------

export interface Loop {
    start: number
    end: number | null
}

/** Shorter than this is a stutter, not a loop. */
const LOOP_MIN = 0.5
/** A position this far past the end still counts as having played into it; further is a seek past the loop. */
const LOOP_RUN_PAST = 2

/** AbLoop.press: the first press marks the start, the second the end (in time order), the third clears. */
export function pressLoop(current: Loop | null, position: number): Loop | null {
    if (!current) return { start: position, end: null }
    if (current.end !== null) return null
    if (Math.abs(position - current.start) < LOOP_MIN) return current
    return { start: Math.min(current.start, position), end: Math.max(current.start, position) }
}

/** AbLoop.wrap: where playback at `position` goes back to, or null to carry on. */
export function wrapLoop(loop: Loop | null, position: number): number | null {
    if (!loop || loop.end === null) return null
    return position >= loop.end && position < loop.end + LOOP_RUN_PAST ? loop.start : null
}

// ---- the way back from a long jump ---------------------------------------------------------

export interface UndoOffer {
    backTo: number
    until: number
}

/** SeekUndo: seeks close together are one journey; a long one offers the way back for a few seconds. */
export class SeekUndo {
    static readonly MIN_DISTANCE = 60
    static readonly BURST = 2000
    static readonly OFFER = 6000
    static readonly SLACK = 2
    private offer: UndoOffer | null = null
    private burstOrigin = -1
    private lastSeekAt = -Infinity
    private returningTo = -1

    current(now: number): UndoOffer | null {
        if (this.offer && now >= this.offer.until) this.offer = null
        return this.offer
    }

    /** A seek from `from` to `to` (seconds) happened at `now` (ms): the offer standing after it. */
    seeked(from: number, to: number, now: number): UndoOffer | null {
        if (from < 0 || to < 0) return this.current(now)
        if (this.returningTo >= 0) {
            const ours = Math.abs(to - this.returningTo) <= SeekUndo.SLACK
            this.returningTo = -1
            if (ours) {
                this.lastSeekAt = -Infinity
                return null
            }
        }
        const inBurst = this.lastSeekAt !== -Infinity && now - this.lastSeekAt <= SeekUndo.BURST
        this.lastSeekAt = now
        const standing = this.current(now)
        const origin = standing ? standing.backTo : inBurst && this.burstOrigin >= 0 ? this.burstOrigin : from
        this.burstOrigin = origin
        if (standing && Math.abs(to - origin) <= SeekUndo.SLACK) {
            this.offer = null
            return null
        }
        if (standing) this.offer = { ...standing, until: now + SeekUndo.OFFER }
        else this.offer = Math.abs(to - origin) >= SeekUndo.MIN_DISTANCE ? { backTo: origin, until: now + SeekUndo.OFFER } : null
        return this.offer
    }

    /** Takes the offer: the place to go back to, or null when none stands. */
    take(now: number): number | null {
        const standing = this.current(now)
        if (!standing) return null
        this.offer = null
        this.burstOrigin = -1
        this.returningTo = standing.backTo
        return standing.backTo
    }

    clear() {
        this.offer = null
        this.burstOrigin = -1
        this.lastSeekAt = -Infinity
        this.returningTo = -1
    }
}

// ---- still watching ------------------------------------------------------------------------

/** StillWatching: three episodes in a row, or two hours with nothing pressed. */
export const AUTOPLAY_LIMIT = 3
export const IDLE_LIMIT = 2 * 60 * 60 * 1000

/**
 * What outlives one player: how many episodes autoplay has started since anybody pressed anything,
 * and when anybody last did. The next episode is a new player, so this sits on the module.
 */
export const watching = { autoplayed: 0, lastInput: Date.now() }

/** Any input at all is somebody being there. */
export function noteInput() {
    watching.autoplayed = 0
    watching.lastInput = Date.now()
}

/** The sleep timer as it stands, kept here so it carries over to the next episode. */
export const sleepState: { at: number | null; end: boolean } = { at: null, end: false }

/** SleepTimer.CHOICES: minutes, then the end of the video (0). */
export const SLEEP_CHOICES = [15, 30, 45, 60, 90, 0]

// ---- the slowed scrub ----------------------------------------------------------------------

/**
 * FineScrub.rate: a finger further from the line it scrubs along slows the travel to a half and
 * then a quarter. The app measures in dp; a CSS pixel is one.
 */
export function fineRate(offset: number): number {
    const away = Math.abs(offset)
    return away >= 128 ? 0.25 : away >= 56 ? 0.5 : 1
}
