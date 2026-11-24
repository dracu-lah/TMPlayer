// How long one frame lasts, for the frame step keys (`,` and `.`): read from the video itself, from the
// gaps between the frames the browser shows (`requestVideoFrameCallback`'s mediaTime), instead of
// assuming thirty a second. A browser without that call (Safari before 15.4, some TV engines) keeps
// 1/30. Pure: the caller feeds it the times.

/** A frame at 30 frames a second: what is used until the video says otherwise. */
export const FRAME_FALLBACK = 1 / 30
/** Gaps outside 120 and 10 frames a second are a seek or a stall, not a frame. */
const MIN_GAP = 1 / 120
const MAX_GAP = 1 / 10
/** How many gaps are kept. */
const KEEP = 15
/** Fewer gaps than this say nothing yet. */
const NEED = 5

/** The usual frame gap among `gaps` (their median, so one dropped frame does not move it); the fallback with too few. */
export function frameFrom(gaps: readonly number[]): number {
    const ok = gaps.filter((g) => Number.isFinite(g) && g >= MIN_GAP && g <= MAX_GAP).slice(-KEEP)
    if (ok.length < NEED) return FRAME_FALLBACK
    const sorted = [...ok].sort((a, b) => a - b)
    return sorted[Math.floor(sorted.length / 2)]!
}

/** Feeds frame times, one at a time, and says the frame length so far. */
export class FrameClock {
    private last: number | null = null
    private gaps: number[] = []
    /** A frame was shown at `mediaTime` seconds, `rate` times normal speed. */
    frame(mediaTime: number, rate: number) {
        if (this.last !== null && rate > 0 && mediaTime > this.last) this.gaps.push((mediaTime - this.last) / 1)
        this.last = mediaTime
        if (this.gaps.length > KEEP * 2) this.gaps = this.gaps.slice(-KEEP)
    }
    /** A seek or a new file: the next frame is the first. */
    reset() {
        this.last = null
        this.gaps = []
    }
    /** Enough frames seen: the callback can stop asking for more (a TV stick has no frame to spare). */
    get settled(): boolean {
        return this.gaps.filter((g) => g >= MIN_GAP && g <= MAX_GAP).length >= KEEP
    }
    get duration(): number {
        return frameFrom(this.gaps)
    }
}
