// "Report stutter" and the playback watch on the web (WB18, reliability plan L1 to L3): the twin of
// core's PlaybackWatch (the one definition of a real stutter), of its PlaybackMarks (the viewer marks
// a moment and the last minute of readings is kept for the logs) and of StutterReport's buckets.
//
// Pure: a clock and a reading are passed in, so the lines are tested on a driven clock; player/Player.tsx
// owns the timer, which runs only while the video plays on a visible page, so nothing runs by itself.
// The thresholds are the core's: stutter.test.ts checks this file against `stutterSymptom`,
// `stutterSettleMs` and `stutterWindowMs` as WebRules exports them, so a change there fails here.
//
// What a reading holds is fixed words, an engine tag and bucketed counts, never a number the viewer
// could be told by, and never a title, chat or file name: a [Reading] has no field that could carry
// one. A browser has no sound underrun counter and no frame offsets, so those read as none.
import { onClear, supply } from '../logs.ts'

/** How long after a start, seek or interruption playback is not read (the decoder warms up). */
export const SETTLE_MS = 2_000
/** Playing time watched before the answer is given, once. */
export const WINDOW_MS = 12_000
/** A gap between two samples longer than this (the page was away) is not counted. */
const MAX_GAP_MS = 5_000
/** The position must keep at least this pace against the speed asked for. */
const MIN_SPEED = 0.9
const MAX_DROPPED_SHARE = 0.1
const MIN_DROPPED = 10
const MAX_UNDERRUNS = 3

/** How often the player reads the video while it plays. */
export const SAMPLE_MS = 2_000
/** Once the watch has answered the readings go on (a mark needs the last minute) at a third of the wakeups. */
export const SAMPLE_SLOW_MS = 6_000
/** A gap between two readings longer than this has no pace (the page was away). */
const MAX_READING_GAP_MS = 8_000
/** The marks keep at most this long a window of readings, this many readings, and this many marks. */
export const MARK_WINDOW_MS = 60_000
export const MAX_READINGS = 40
export const MAX_MARKS = 5
/** A second press this soon after the first is the same mark (a double press, a press while a seek settles). */
export const DEBOUNCE_MS = 5_000

export type Symptom = 'slow' | 'dropped' | 'underruns' | 'late'

/**
 * Core's `PlaybackWatch.symptomOf`: what a viewer would have seen, or null. [speed] is the pace of the
 * position against the speed asked for (1 is on time). Garbage in (not a number, negative, absurdly
 * large) reads as no evidence, never as a stutter.
 */
export function symptomOf(speed: number, dropped: number, frames: number, underruns: number, late: boolean): Symptom | null {
    const whole = (n: number) => (Number.isFinite(n) ? Math.min(2_000_000_000, Math.max(0, Math.trunc(n))) : 0)
    const lost = whole(dropped)
    const total = Math.max(whole(frames), lost)
    if (Number.isFinite(speed) && speed >= 0 && speed < MIN_SPEED) return 'slow'
    if (lost >= MIN_DROPPED && lost / total > MAX_DROPPED_SHARE) return 'dropped'
    if (whole(underruns) >= MAX_UNDERRUNS) return 'underruns'
    if (late && lost >= MIN_DROPPED && lost / total > 0.02) return 'late'
    return null
}

export type Answer = 'kept-up' | 'cannot-keep-up'

export interface Figures {
    /** Video seconds moved per second asked for, in per cent. */
    speedPercent: number
    playedSeconds: number
    dropped: number
    frames: number
    underruns: number
    symptom: Symptom | null
}

/** Core's PlaybackWatch: the first seconds of a video, read the same way for every file. */
export class PlaybackWatch {
    private lastWall = -1
    private lastPosition = 0
    private lastSpeed = 1
    private lastRendered = -1
    private lastDropped = 0
    private lastUnderruns = 0
    private settled = 0
    private played = 0
    private expected = 0
    private moved = 0
    private dropped = 0
    private rendered = 0
    private underruns = 0
    done = false

    /** A new video: the count and the answer start again. */
    restart() {
        this.done = false
        this.reset()
    }

    /** Starts the count again (a seek): the answer is still to come. */
    reset() {
        this.interrupted()
        this.played = 0
        this.expected = 0
        this.moved = 0
        this.dropped = 0
        this.rendered = 0
        this.underruns = 0
    }

    /** The video stopped or jumped with no sample to say so: the stretch in hand is dropped and the next [SETTLE_MS] of playing is not read. */
    interrupted() {
        this.lastWall = -1
        this.lastRendered = -1
        this.settled = 0
    }

    /**
     * One sample: the wall clock and the position (milliseconds), whether the video plays now (not
     * paused, not waiting), the running totals of frames shown and dropped, and the speed the viewer asked for.
     */
    sample(wallMs: number, positionMs: number, playing: boolean, renderedTotal: number, droppedTotal: number, underrunTotal = 0, speed = 1): Answer | null {
        if (this.done) return null
        if (![wallMs, positionMs, renderedTotal, droppedTotal, underrunTotal].every(Number.isFinite)) {
            this.interrupted()
            return null
        }
        if (!playing) {
            this.interrupted()
            return null
        }
        const pace = Number.isFinite(speed) && speed > 0 ? speed : 1
        if (this.lastWall >= 0 && pace === this.lastSpeed) {
            const wall = wallMs - this.lastWall
            const moved = positionMs - this.lastPosition
            const expected = wall * pace
            if (wall >= 1 && wall <= MAX_GAP_MS && moved >= 0 && moved <= expected * 3) {
                if (this.settled < SETTLE_MS) {
                    this.settled += wall
                } else {
                    this.played += wall
                    this.expected += expected
                    this.moved += moved
                    const fresh = renderedTotal - this.lastRendered
                    this.rendered += this.lastRendered < 0 ? 0 : fresh < 0 ? renderedTotal : fresh
                    this.dropped += Math.max(0, droppedTotal - this.lastDropped)
                    this.underruns += Math.max(0, underrunTotal - this.lastUnderruns)
                }
            } else {
                this.settled = 0
            }
        } else if (this.lastWall >= 0) {
            // The speed changed since the last sample: the sound and the clock re-time themselves.
            this.settled = 0
        }
        this.lastWall = wallMs
        this.lastPosition = positionMs
        this.lastSpeed = pace
        this.lastRendered = renderedTotal
        this.lastDropped = droppedTotal
        this.lastUnderruns = underrunTotal
        if (this.played < WINDOW_MS) return null
        this.done = true
        return this.symptom() === null ? 'kept-up' : 'cannot-keep-up'
    }

    figures(): Figures {
        return {
            speedPercent: Math.trunc(this.speed() * 100),
            playedSeconds: Math.trunc(this.played / 1000),
            dropped: this.dropped,
            frames: this.dropped + this.rendered,
            underruns: this.underruns,
            symptom: this.symptom(),
        }
    }

    private speed(): number {
        return this.expected > 0 ? this.moved / this.expected : 0
    }

    private symptom(): Symptom | null {
        return symptomOf(this.speed(), this.dropped, this.dropped + this.rendered, this.underruns, false)
    }
}

// ---- Buckets and words ---------------------------------------------------------------------------------------

/** A count as a coarse bucket, never the number itself (StutterReport.bucketOf). */
export function bucketOf(count: number): string {
    if (!Number.isFinite(count) || count < 0) return 'unknown'
    if (count === 0) return '0'
    if (count === 1) return '1'
    if (count < 5) return '2-4'
    if (count < 10) return '5-9'
    if (count < 20) return '10-19'
    if (count < 50) return '20-49'
    return '50+'
}

/** The pace of the position against the speed asked for, as one of four words. */
export function paceWord(percent: number): string {
    if (!Number.isFinite(percent) || percent < 0) return 'unknown'
    if (percent < 50) return 'under-50'
    if (percent < 90) return '50-89'
    if (percent <= 110) return 'on-time'
    return 'over-110'
}

const ENGINES = ['native', 'remux', 'libav', 'movi', 'canvas']
/** The engine as one of the fixed tags, `unknown` for anything else. */
export const engineTag = (name: unknown): string => (typeof name === 'string' && ENGINES.includes(name) ? name : 'unknown')

const RATES = [0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2, 2.5, 3, 4]
/** The speed asked for as `0.75x`, `1x`, or `other`: only the speeds the player offers are written. */
export function rateWord(rate: number): string {
    return RATES.includes(rate) ? `${rate}x` : 'other'
}

// ---- Readings and marks -----------------------------------------------------------------------------------------

/** One look at the video while it played. No field holds a name. */
export interface Reading {
    /** The monotonic clock, milliseconds. */
    at: number
    /** Frames dropped and frames in all since the last reading; -1 when this browser does not say. */
    dropped: number
    frames: number
    /** The position's pace against the speed asked for, in per cent since the last reading; -1 for the first. */
    pacePercent: number
    engine: string
    rate: number
}

const ordered = (r: Reading) => ({
    at: Number.isFinite(r.at) ? r.at : 0,
    dropped: Number.isFinite(r.dropped) ? Math.max(0, Math.trunc(r.dropped)) : -1,
    frames: Number.isFinite(r.frames) ? Math.max(0, Math.trunc(r.frames)) : -1,
    pacePercent: Number.isFinite(r.pacePercent) ? Math.trunc(r.pacePercent) : -1,
    engine: engineTag(r.engine),
    rate: r.rate,
})

/** A reading as one line of fixed words and buckets; seconds count from the window's first reading, never a clock time. */
export function readingLine(r: Reading, firstAt: number): string {
    const c = ordered(r)
    const at = Math.max(0, Math.trunc((c.at - firstAt) / 1000))
    return `  +${at}s dropped=${c.dropped < 0 ? 'unknown' : bucketOf(c.dropped)} of=${c.frames < 0 ? 'unknown' : bucketOf(c.frames)} pace=${c.pacePercent < 0 ? 'unknown' : paceWord(c.pacePercent)} engine=${c.engine} speed=${rateWord(c.rate)}`
}

export type MarkResult = 'kept' | 'already'

interface Mark {
    /** Readings of the last minute, oldest first (possibly none: the moment is the news). */
    readings: Reading[]
    symptom: Symptom | null
}

/**
 * The viewer's marks: a double press within [DEBOUNCE_MS] is the same mark, the last [MAX_MARKS] are
 * kept, each with at most the last [MAX_READINGS] readings of the last [MARK_WINDOW_MS]. In memory:
 * they go with the tab, and into the logs only when the viewer copies them.
 */
export class StutterMarks {
    private marks: Mark[] = []
    private lastAt = Number.NEGATIVE_INFINITY

    mark(readings: readonly Reading[], symptom: Symptom | null, now: number): MarkResult {
        if (Number.isFinite(now) && now - this.lastAt >= 0 && now - this.lastAt < DEBOUNCE_MS) return 'already'
        const clean = readings.map(ordered)
        const newest = clean.reduce((m, r) => Math.max(m, r.at), Number.NEGATIVE_INFINITY)
        // A trace's clock only runs forward: a mark keeps the readings after the last mark's own.
        const prior = this.marks.length ? Math.max(...this.marks.flatMap((m) => m.readings.map((r) => r.at)), Number.NEGATIVE_INFINITY) : Number.NEGATIVE_INFINITY
        const fresh = clean.filter((r) => r.at > newest - MARK_WINDOW_MS && r.at > prior).slice(-MAX_READINGS)
        this.marks.push({ readings: fresh, symptom })
        if (this.marks.length > MAX_MARKS) this.marks.shift()
        this.lastAt = Number.isFinite(now) ? now : this.lastAt
        return 'kept'
    }

    get count() {
        return this.marks.length
    }

    /** The marks as lines for the logs: how many, then each one's verdict word and its readings. Empty when nothing was marked. */
    reportLines(): string[] {
        if (this.marks.length === 0) return []
        const lines = [`Playback trace: ${this.marks.length} moment(s) the viewer marked as stuttering, oldest first.`]
        for (const m of this.marks) {
            lines.push(`Marked, ${m.readings.length} reading(s), watch said ${m.symptom ?? 'nothing'}`)
            const first = m.readings[0]?.at ?? 0
            for (const r of m.readings) lines.push(readingLine(r, first))
        }
        return lines
    }

    clear() {
        this.marks = []
        this.lastAt = Number.NEGATIVE_INFINITY
    }
}

/** The marks of this tab, across videos. */
export const sharedMarks = new StutterMarks()
supply('marks', () => sharedMarks.reportLines())
onClear(() => sharedMarks.clear())

let engineNow = 'unknown'
/** The engine playing now, for the device lines of Copy details. */
export const noteEngine = (name: unknown) => {
    engineNow = engineTag(name)
}

/** The device's facts in fixed words and buckets: nothing that names the viewer, a video or a chat. */
export function deviceLines(env: { video?: object; nav?: { hardwareConcurrency?: unknown; deviceMemory?: unknown }; win?: object } = {}): string[] {
    const nav = env.nav ?? (typeof navigator === 'undefined' ? {} : (navigator as { hardwareConcurrency?: unknown; deviceMemory?: unknown }))
    const win = env.win ?? (typeof globalThis === 'undefined' ? {} : globalThis)
    const cores = typeof nav.hardwareConcurrency === 'number' ? bucketOf(nav.hardwareConcurrency) : 'unknown'
    const memory = typeof nav.deviceMemory === 'number' && [0.25, 0.5, 1, 2, 4, 8].includes(nav.deviceMemory) ? `${nav.deviceMemory} GB or so` : 'unknown'
    const has = (k: string) => (k in win ? 'yes' : 'no')
    return [
        `Engine in use: ${engineNow}`,
        `Frame counters: ${env.video && 'getVideoPlaybackQuality' in env.video ? 'yes' : 'no'}`,
        `Media Source: ${has('MediaSource')}, managed: ${has('ManagedMediaSource')}, WebCodecs: ${has('VideoDecoder')}`,
        `Processor threads: ${cores}, memory class: ${memory}`,
    ]
}

// ---- The monitor the player owns -------------------------------------------------------------------------------------------------

/** What the player reads off the video: frames shown and dropped in all, or null when the browser does not say. */
export interface Quality {
    dropped: number
    total: number
    /** Sound underruns so far, for an engine that counts them (the canvas engine). */
    underruns?: number
}

/** The standard counters of a video element, or null: an old WebKit and some TV engines have none. */
export function qualityOf(video: { getVideoPlaybackQuality?: () => { droppedVideoFrames?: unknown; totalVideoFrames?: unknown } }): Quality | null {
    try {
        const q = video.getVideoPlaybackQuality?.()
        if (!q) return null
        const dropped = typeof q.droppedVideoFrames === 'number' ? q.droppedVideoFrames : NaN
        const total = typeof q.totalVideoFrames === 'number' ? q.totalVideoFrames : NaN
        if (!Number.isFinite(dropped) || !Number.isFinite(total) || dropped < 0 || total < 0) return null
        return { dropped, total: Math.max(total, dropped) }
    } catch {
        return null
    }
}

export interface TickInput {
    /** The monotonic clock (milliseconds) and the position (seconds). */
    now: number
    position: number
    playing: boolean
    rate: number
    engine: string
    quality: Quality | null
}

/**
 * The watch, the readings and the marks of one video. One call per sample while it plays; [onAnswer]
 * gets the watch's one answer (the player writes it to the log). [seeked] and [interrupted] start
 * the settling again, as core's player does on a seek or a stall.
 */
export class StutterMonitor {
    readonly watch = new PlaybackWatch()
    /** The marks outlive a video (they are the viewer's, kept for Copy details) and go with the logs. */
    readonly marks = sharedMarks
    private readings: Reading[] = []
    private last: { at: number; position: number; dropped: number; total: number } | null = null
    private symptom: Symptom | null = null
    onAnswer: (answer: Answer, figures: Figures) => void = () => {}

    /** A new video: the watch and the readings start again. */
    restart() {
        this.watch.restart()
        this.readings = []
        this.last = null
        this.symptom = null
    }

    seeked() {
        this.watch.reset()
        this.last = null
    }

    interrupted() {
        this.watch.interrupted()
        this.last = null
    }

    tick(i: TickInput) {
        if (!i.playing) return this.interrupted()
        const q = i.quality
        const positionMs = i.position * 1000
        const answer = this.watch.sample(i.now, positionMs, true, q ? q.total - q.dropped : 0, q ? q.dropped : 0, q?.underruns ?? 0, i.rate)
        const prev = this.last
        let pace = -1
        let dropped = -1
        let frames = -1
        if (prev) {
            const wall = i.now - prev.at
            if (wall > 0 && wall <= MAX_READING_GAP_MS && i.rate > 0) pace = Math.round(((positionMs - prev.position) / (wall * i.rate)) * 100)
            if (q) {
                // A decoder made again starts its counters from nought: carry on from there.
                dropped = q.dropped >= prev.dropped ? q.dropped - prev.dropped : q.dropped
                frames = q.total >= prev.total ? q.total - prev.total : q.total
            }
        }
        this.last = { at: i.now, position: positionMs, dropped: q?.dropped ?? 0, total: q?.total ?? 0 }
        this.readings.push({ at: i.now, dropped, frames, pacePercent: pace, engine: engineTag(i.engine), rate: i.rate })
        if (this.readings.length > MAX_READINGS) this.readings.shift()
        if (answer) {
            const figures = this.watch.figures()
            this.symptom = figures.symptom
            this.onAnswer(answer, figures)
        }
    }

    /** Report stutter: marks this moment with the last minute of readings. A second press this soon is `already`. */
    press(now: number): MarkResult {
        return this.marks.mark(this.readings, this.symptom, now)
    }
}
