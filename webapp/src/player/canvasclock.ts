// The clock and the frame choices of the canvas engine (libavcanvas.ts), as plain functions and
// classes with no browser in them, so `pnpm test:player` runs them in node with fake frames
// (canvasclock.test.ts). The sound is the master: the picture is dropped or repeated to follow it.

/**
 * The master clock. `source` is the sound's clock (the audio context's `currentTime`, which stops
 * while the context is suspended) or the page's own timer when the file has no sound that plays;
 * `latency` is how long a sound takes from the context to the speaker, so the picture follows what
 * is heard and not what was handed over. Held until `go()`: a clock that waits for the first
 * pictures, a paused video and a seek in flight are all held.
 */
export class MasterClock {
    private anchorMedia = 0
    private anchorSource = 0
    private held = true
    private speed = 1

    private readonly source: () => number
    private readonly latency: number

    constructor(source: () => number, latency = 0) {
        this.source = source
        this.latency = latency
    }

    /** Seconds into the video the clock stands at. */
    position(): number {
        return this.held ? this.anchorMedia : this.anchorMedia + (this.source() - this.latency - this.anchorSource) * this.speed
    }

    get running(): boolean {
        return !this.held
    }

    get rate(): number {
        return this.speed
    }

    /** Stops here, where it is. */
    hold() {
        if (this.held) return
        this.anchorMedia = this.position()
        this.anchorSource = this.source() - this.latency
        this.held = true
    }

    /**
     * After the source itself stopped (a suspended audio context takes a moment to stop and the
     * sound played on meanwhile): the held position moves on by what the source ran since `hold`,
     * so a sound already scheduled still lines up with it.
     */
    settle() {
        if (!this.held) return
        const at = this.source() - this.latency
        this.anchorMedia += (at - this.anchorSource) * this.speed
        this.anchorSource = at
    }

    /** Sets where the clock stands, held or not. */
    set(media: number) {
        this.anchorMedia = media
        this.anchorSource = this.source() - this.latency
    }

    /**
     * Runs on from the held position. A clock on the sound's source keeps its anchor (the source
     * stood still while held); one on the page's timer takes a new one.
     */
    go(frozenWhileHeld: boolean) {
        if (!this.held) return
        if (!frozenWhileHeld) this.anchorSource = this.source() - this.latency
        this.held = false
    }

    /** Another speed from here on, without a jump in the picture. */
    setRate(rate: number) {
        if (!(rate > 0)) return
        this.anchorMedia = this.position()
        this.anchorSource = this.source() - this.latency
        this.speed = rate
    }

    /** The source's time at which the sound at `media` seconds is to be heard (not a speaker time: add the latency for the context). */
    sourceTimeOf(media: number): number {
        return this.anchorSource + (media - this.anchorMedia) / this.speed
    }

    /** The context time to start the sound at `media` at: the source's time plus the latency. */
    contextTimeOf(media: number): number {
        return this.sourceTimeOf(media) + this.latency
    }
}

/** A picture waiting to be shown. */
export interface Timed {
    /** Seconds into the video it is shown at. */
    at: number
    /** Its size in memory, for the ring's budget. */
    bytes: number
}

/**
 * The pictures decoded ahead of the clock: a small queue bounded by count and by bytes (the TV
 * stick has 1 GB), oldest first. `full` tells the decoder to wait.
 */
export class FrameRing<T extends Timed> {
    private items: T[] = []
    private used = 0

    readonly maxFrames: number
    readonly maxBytes: number

    constructor(maxFrames: number, maxBytes: number) {
        this.maxFrames = maxFrames
        this.maxBytes = maxBytes
    }

    get length(): number {
        return this.items.length
    }

    get bytes(): number {
        return this.used
    }

    /** No room for another: by count or by bytes (a single frame always fits an empty ring). */
    get full(): boolean {
        return this.items.length >= this.maxFrames || (this.items.length > 0 && this.used >= this.maxBytes)
    }

    push(f: T) {
        this.items.push(f)
        this.used += f.bytes
    }

    peek(i = 0): T | undefined {
        return this.items[i]
    }

    times(): number[] {
        return this.items.map((f) => f.at)
    }

    /** Takes the frame at `index` and drops the ones before it; returns it. */
    take(index: number): T | undefined {
        if (index < 0 || index >= this.items.length) return undefined
        const gone = this.items.splice(0, index + 1)
        for (const g of gone) this.used -= g.bytes
        return gone[gone.length - 1]
    }

    clear() {
        this.items = []
        this.used = 0
    }

    /** The last frame's time, or null when empty. */
    get last(): number | null {
        return this.items.length ? this.items[this.items.length - 1].at : null
    }
}

/** How many frames fit: 4 at least, 24 at most, and within `budget` bytes. */
export function ringFrames(bytesPerFrame: number, budget = 10 * 2 ** 20): number {
    if (!(bytesPerFrame > 0)) return 8
    return Math.max(4, Math.min(24, Math.floor(budget / bytesPerFrame)))
}

/** What to show at `now` from frames at `times` (ascending). */
export interface Choice {
    /** Index of the frame to draw, or -1 to leave the picture as it is. */
    show: number
    /** How many frames before it were never shown (they were late). */
    dropped: number
}

/**
 * The frame to draw now: the last one whose time has come (a frame `early` seconds ahead counts,
 * so a frame is not shown a whole refresh late). Every frame before it was too late to show and
 * is dropped; nothing due means the picture on the screen is repeated.
 */
export function chooseFrame(times: readonly number[], now: number, early = 0.012): Choice {
    let k = -1
    for (let i = 0; i < times.length; i++) {
        if (times[i] <= now + early) k = i
        else break
    }
    return { show: k, dropped: k > 0 ? k : 0 }
}

/**
 * Whether the clock should wait for the decoder: the ring ran dry (nothing queued, the decoder
 * not finished) and the clock is more than `slack` seconds past the last frame shown.
 */
export function starved(ringLength: number, finished: boolean, now: number, lastShown: number | null, slack = 0.25): boolean {
    if (finished || ringLength > 0) return false
    return lastShown === null || now > lastShown + slack
}

/** Whether a held clock may run again: enough pictures queued, or the decoder has no more. */
export function refilled(ringLength: number, maxFrames: number, finished: boolean): boolean {
    return finished || ringLength >= Math.min(Math.max(3, maxFrames >> 2), maxFrames)
}

/** What decoding the first seconds says about this device. */
export type Verdict = 'wait' | 'ok' | 'heavy'

/** Decoded frames counted over the time spent inside the decoder (reads and waits are not counted). */
export class DecodeGauge {
    private frames = 0
    private busy = 0
    private settled: Verdict = 'wait'

    private readonly sourceFps: number
    private readonly need: number

    /** `need` is the speed this device must keep at the cap, frames a second. */
    constructor(sourceFps: number, need = 20) {
        this.sourceFps = sourceFps
        this.need = need
    }

    add(frames: number, busyMs: number) {
        if (this.settled !== 'wait') return
        this.frames += frames
        this.busy += busyMs
    }

    get fps(): number {
        return this.busy > 0 ? (this.frames * 1000) / this.busy : 0
    }

    /**
     * 'heavy' once 24 frames and 1.5 s of decoding show less than `need` a second (or less than
     * the source's own speed when that is lower); 'ok' once 90 frames hold it. The first verdict
     * stays.
     */
    verdict(): Verdict {
        if (this.settled !== 'wait') return this.settled
        const need = Math.min(this.need, this.sourceFps > 0 ? this.sourceFps : this.need) * 0.95
        if (this.frames >= 24 && this.busy >= 1500 && this.fps < need) return (this.settled = 'heavy')
        if (this.frames >= 90 && this.fps >= need) return (this.settled = 'ok')
        return 'wait'
    }
}

/** Where to start one decoded stretch of sound, and from which sample. */
export interface Slot {
    /** The audio context's time to start at. */
    when: number
    /** Seconds into the stretch to start from (the front is already past, or before the target). */
    offset: number
}

/**
 * Places decoded sound on the audio context's timeline from the master clock, one stretch after
 * the other with no gap (the context resamples each stretch, so adding the times up beats using
 * each one's own time), and skipping what is already late. Pure: the context's time is passed in.
 */
export class SoundTimeline {
    private next: number | null = null

    private readonly clock: MasterClock

    constructor(clock: MasterClock) {
        this.clock = clock
    }

    reset() {
        this.next = null
    }

    /** The end of what is scheduled, in context time; null before any. */
    get end(): number | null {
        return this.next
    }

    /**
     * `media` is the stretch's time in the video and `length` its seconds at normal speed;
     * `now` is the context's `currentTime`. Null when all of it is past already.
     */
    place(media: number, length: number, now: number): Slot | null {
        const rate = this.clock.rate
        let when = this.clock.contextTimeOf(media)
        // Within a few ms of the end of the last stretch: gapless (rounding and resampling).
        if (this.next !== null && Math.abs(when - this.next) < 0.03) when = this.next
        let offset = 0
        const soonest = now + 0.01
        if (when < soonest) {
            offset = (soonest - when) * rate
            when = soonest
            if (offset >= length) return null
        }
        this.next = when + (length - offset) / rate
        return { when, offset }
    }
}

/**
 * The largest picture the canvas engine decodes at a speed it holds, in pixels. There is no
 * encoder here, so it is above the Media Source route's cap (a PAL DVD is 720 x 576); the first
 * seconds' measured speed (DecodeGauge) is the real judge.
 */
export function canvasPixels(cores: number, memoryGb: number | undefined, phone: boolean): number {
    const low = cores <= 4 || (memoryGb !== undefined && memoryGb <= 2)
    if (low) return 1024 * 576
    if (phone) return 1280 * 720
    return 1920 * 1088
}
