// The software player for browsers with no WebCodecs encoder (the LG webOS and Samsung Tizen
// TVs, Chromium 68 to 81): a file whose picture the TV cannot play (MPEG-4 Part 2, WMV, VC-1,
// MPEG-1 and 2, H.263, Sorenson, RealVideo) is read and decoded by libav.js, in its own worker,
// and the pictures are drawn on a canvas (canvasdraw.ts). The sound is decoded there too and
// played by Web Audio, whose clock is the master (canvasclock.ts): the picture is dropped or
// repeated to follow it. The `<video>` element stays the one thing the controls, the remote, the
// resume point and the keys read: its properties and events are taken over while this engine runs
// (as movi.ts does) and given back on destroy.
import type * as LibAVTypes from '../../libav/libav.types'
import type { MediaFile } from '../stream/reader'
import { canvasPixels, chooseFrame, DecodeGauge, FrameRing, MasterClock, refilled, ringFrames, SoundTimeline, starved } from './canvasclock'
import { startDrawing, type Drawing } from './canvasdraw'
import { cueOverlay } from './cueoverlay'
import { boostLevel, limiterBetween } from './boost'
import { codecName, Unplayable, type Engine, type EngineEvents, type SoundTrack } from './engines'
import { AV_PKT_FLAG_KEY, debug, phone, readHeader, toSeconds, type Header, type LibavSound, type Picture } from './libav'
import { downmix, soundPlanes, yuvPlanes, type Planes } from './libavplanes'
import { startLibav, type LibAV } from './libavload'

type Frame = LibAVTypes.Frame
type Packet = LibAVTypes.Packet

/** A picture decoded ahead of the clock. */
interface Shown {
    at: number
    bytes: number
    planes: Planes
    /** The width it is shown at (pixels that are not square). */
    shownWidth: number
}

/** A stretch of sound gathered from decoded frames, to hand to the audio context as one buffer. */
interface Gathered {
    at: number
    rate: number
    channels: Float32Array[][]
    samples: number
    end: number
}

/** Local testing hooks (the stats, ?canvas=2d, ?canvascap=): localhost and the end to end tests' address. */
const LOCAL = location.hostname === 'localhost' || location.hostname === '127.0.0.1'
const PICTURES_PER_CALL = 12
const SOUND_PACKETS_PER_CALL = 64
/** How far ahead of the clock sound is scheduled, seconds. */
const SOUND_AHEAD = 3
const TIME_EVENT_MS = 250
/** A refresh later than this after the last one is a slow display (a TV, WebKit with no GPU under load): see [kick]. */
const SLOW_REFRESH_MS = 34
/** How often the timer looks at the screen while the display is slow, so a late refresh does not make the picture late. */
const TIMER_STEP_MS = 10
const SEEK_SETTLE_MS = 120

/**
 * Whether this browser can run the canvas engine: WebAssembly and Workers for libav.js, and a
 * canvas to draw on. Web Audio is not needed (a file without it plays silent).
 */
export function canvasSupported(): boolean {
    return typeof WebAssembly === 'object' && typeof Worker === 'function' && !!document.createElement('canvas').getContext
}

type AudioContextCtor = typeof AudioContext

/** Whether sound can play here at all. */
function audioContext(): AudioContext | null {
    const C = (window.AudioContext ?? (window as unknown as { webkitAudioContext?: AudioContextCtor }).webkitAudioContext) as AudioContextCtor | undefined
    if (!C) return null
    try {
        return new C()
    } catch {
        return null
    }
}

export class CanvasEngine implements Engine {
    readonly name = 'canvas'
    detail = ''
    readonly sounds: LibavSound[]

    // What the file is.
    private readonly L: LibAV
    private readonly fmt: number
    private readonly pic: Picture
    private readonly streams: Header['inputs']
    private readonly duration: number
    private readonly base: number
    private readonly size: number
    private readonly codecWords: string
    private sound: LibavSound | null

    // What shows and plays it.
    private readonly canvas: HTMLCanvasElement
    private readonly drawing: Drawing
    private readonly ctx: AudioContext | null
    private readonly gain: GainNode | null
    private readonly ring: FrameRing<Shown>
    private readonly clock: MasterClock
    private readonly timeline: SoundTimeline
    private readonly gauge: DecodeGauge
    private readonly sources = new Set<AudioBufferSourceNode>()
    private overlay: ReturnType<typeof cueOverlay> | null = null
    private restoreVideo: () => void = () => {}
    private styleWatch: MutationObserver | null = null

    // The run (reading, decoding) and its state.
    private gen = 0
    private running: Promise<void> = Promise.resolve()
    private destroyed = false
    private pkt = 0
    private vdec: { c: number; pkt: number; frame: number } | null = null
    private adec: { c: number; pkt: number; frame: number; index: number } | null = null
    private audioTb: [number, number] = [1, 1]
    private fresh = true
    private eof = false
    private frameLength = 1 / 25
    private lastAt = -1
    private soundEnd = 0
    private waker: (() => void) | null = null
    private waitTimer: ReturnType<typeof setTimeout> | undefined
    private seekTimer: ReturnType<typeof setTimeout> | undefined

    // What the element reports.
    private paused = true
    private ended = false
    private seekingTo: number | null = null
    /** A seek was asked for and the run has not started over yet: nothing in the ring is the answer. */
    private restartPending = false
    private startAt = 0
    /** Play was asked for and the clock waits for pictures (the start, a seek, a decoder that fell behind). */
    private buffering = true
    private shownOnce = false
    private lastShown: number | null = null
    private shownSize: { width: number; height: number } = { width: 0, height: 0 }
    private useContextClock: boolean
    private silent: boolean
    private raf = 0
    private loopTimer: ReturnType<typeof setTimeout> | undefined
    private lastRefresh = 0
    private slowDisplay = false
    private lastTimeEvent = 0
    private underruns: number[] = []
    private saidSlow = false
    private dropped = 0
    /** Pictures drawn and sound underruns, in all: what the stutter watch reads (stutter.ts). */
    private drawn = 0
    private underrunTotal = 0
    private heavyCodec: string
    private volume = 1
    /** Volume boost, 1 (off) to 2: a multiplier on the gain, set through the element's `tmBoost`. */
    private boost = 1
    private limiter: ReturnType<typeof limiterBetween> | null = null
    private muted = false
    private rate = 1

    private constructor(
        private readonly video: HTMLVideoElement,
        h: Header,
        L: LibAV,
        size: number,
        canvas: HTMLCanvasElement,
        drawing: Drawing,
        ctx: AudioContext | null,
        private readonly ev: EngineEvents,
    ) {
        this.L = L
        this.fmt = h.fmt
        this.pic = h.pic
        this.streams = h.inputs
        this.duration = h.duration
        this.base = h.base
        this.size = size
        this.codecWords = h.codecWords
        this.heavyCodec = `${h.codecWords}, ${h.pic.width} x ${h.pic.height}`
        this.sounds = h.sounds
        this.canvas = canvas
        this.drawing = drawing
        this.ctx = ctx
        this.sound = h.sounds.find((s) => s.decodable) ?? null
        this.silent = !ctx || !this.sound
        this.useContextClock = !this.silent
        this.gain = ctx ? ctx.createGain() : null
        // Volume boost (boost.ts) lifts this gain above 1; the limiter behind it is only in the path then.
        if (ctx && this.gain) {
            this.limiter = limiterBetween(ctx, this.gain, ctx.destination)
            this.limiter.set(false)
        }
        // 4:2:0 frames of this size: one picture's bytes, for the ring's budget.
        const frameBytes = Math.max(1, Math.round(h.pic.width * h.pic.height * 1.5))
        this.ring = new FrameRing<Shown>(ringFrames(frameBytes), 10 * 2 ** 20)
        // What is heard comes later than what the context was handed: the picture follows what is heard.
        const latency = ctx ? Math.min(0.3, Math.max(0, (ctx.baseLatency || 0) + ((ctx as unknown as { outputLatency?: number }).outputLatency || 0))) : 0
        this.clock = new MasterClock(() => (this.useContextClock && this.ctx ? this.ctx.currentTime : performance.now() / 1000), latency)
        this.timeline = new SoundTimeline(this.clock)
        this.gauge = new DecodeGauge(1 / this.frameLength)
    }

    /** The picture codec in plain words. */
    get videoCodec() {
        return this.codecWords
    }

    /** The frame counters the stutter watch reads in place of the element's own (the canvas has none): drawn and dropped in all, and the sound's underruns. */
    quality() {
        return { dropped: this.dropped, total: this.drawn + this.dropped, underruns: this.underrunTotal }
    }

    /** What the tests (and a tester's console, `__canvas.stats()` on localhost) read: how the picture follows the clock. */
    stats() {
        return {
            drawing: this.drawing.kind,
            clock: this.clock.position(),
            shown: this.lastShown,
            /** The shown picture's time against the clock: negative is late. */
            offset: this.lastShown === null ? null : this.lastShown - this.clock.position(),
            running: this.clock.running,
            queued: this.ring.length,
            queuedBytes: this.ring.bytes,
            maxFrames: this.ring.maxFrames,
            dropped: this.dropped,
            underruns: this.underruns.length,
            decodeFps: this.gauge.fps,
            sources: this.sources.size,
            audio: this.ctx?.state ?? 'none',
            contextClock: this.useContextClock,
        }
    }

    /**
     * Reads the header of `file` and plays it from `start`. Throws Unplayable when this device
     * cannot (no decoder in the build, a picture larger than it decodes in time, nothing to draw
     * on), with everything let go.
     */
    static async open(file: MediaFile, video: HTMLVideoElement, container: string, durationHint: number, start: number, ev: EngineEvents): Promise<CanvasEngine> {
        const t0 = performance.now()
        if (!canvasSupported()) throw new Unplayable({ kind: 'picture', codec: container })
        const L = await startLibav()
        const failure: { error: unknown } = { error: null }
        let canvas: HTMLCanvasElement | null = null
        let ctx: AudioContext | null = null
        try {
            const h = await readHeader(L, file, container, durationHint, failure)
            const words = h.codecWords
            if (!(await L.avcodec_find_decoder(h.pic.codecId))) throw new Unplayable({ kind: 'picture', codec: words })
            const cores = navigator.hardwareConcurrency || 2
            // Local testing: ?canvascap=<pixels> sets the largest picture, to try the "too heavy" page.
            const asked = LOCAL ? Number(new URLSearchParams(location.search).get('canvascap')) : 0
            const cap = asked > 0 ? asked : canvasPixels(cores, (navigator as { deviceMemory?: number }).deviceMemory, phone)
            if (h.pic.width * h.pic.height > cap) {
                throw new Unplayable({ kind: 'too-heavy', codec: `${words}, ${h.pic.width} x ${h.pic.height}` })
            }
            const made = startDrawing(
                () => {
                    const c = document.createElement('canvas')
                    c.className = 'sw-canvas'
                    return c
                },
                LOCAL && new URLSearchParams(location.search).get('canvas') === '2d',
            )
            if (!made) throw new Unplayable({ kind: 'picture', codec: words })
            canvas = made.canvas
            ctx = h.sounds.some((s) => s.decodable) ? audioContext() : null
            const engine = new CanvasEngine(video, h, L, file.size, canvas, made.drawing, ctx, ev)
            engine.pkt = await L.av_packet_alloc()
            engine.attach(start)
            if (LOCAL) {
                console.debug('[canvas] opened', container, h.vname, made.drawing.kind, Math.round(performance.now() - t0), 'ms')
                ;(window as unknown as { __canvas?: CanvasEngine }).__canvas = engine
            }
            return engine
        } catch (e) {
            ctx?.close().catch(() => {})
            L.terminate()
            if (failure.error || e instanceof Unplayable) throw failure.error ?? e
            if (debug) console.debug('[canvas] could not open', e)
            throw new Unplayable({ kind: 'format', format: container })
        }
    }

    // ---- the element -----------------------------------------------------------------------

    /** Puts the canvas over the video, takes the element over and starts at `start`. */
    private attach(start: number) {
        const video = this.video
        this.volume = video.volume
        this.muted = video.muted
        this.boost = boostLevel()
        this.paused = true
        this.startAt = start
        this.applyVolume()
        // The audio context starts held: the clock runs it.
        this.ctx?.suspend().catch(() => {})
        video.after(this.canvas)
        this.mirrorStyle()
        this.styleWatch = new MutationObserver(() => this.mirrorStyle())
        this.styleWatch.observe(video, { attributes: true, attributeFilter: ['style'] })
        this.restoreVideo = this.takeOver()
        this.overlay = cueOverlay(video, () => this.position())
        this.clock.set(start)
        this.restart(start)
    }

    /** The shape the controls chose (Original, Fill, a ratio) is on the video's style: the canvas wears it too. */
    private mirrorStyle() {
        const from = this.video.style
        const to = this.canvas.style
        for (const p of ['position', 'left', 'top', 'width', 'height', 'objectFit', 'filter', 'transform'] as const) {
            const v = from[p]
            if (to[p] !== v) to[p] = v
        }
    }

    private position(): number {
        const t = this.seekingTo ?? this.clock.position()
        return this.duration > 0 ? Math.min(t, this.duration) : t
    }

    private fire(type: string) {
        this.video.dispatchEvent(new Event(type))
    }

    private takeOver(): () => void {
        const video = this.video
        let defaultRate = video.defaultPlaybackRate || 1
        const self = this
        const own: PropertyDescriptorMap = {
            currentTime: {
                configurable: true,
                get: () => self.position(),
                set: (t: number) => self.seek(Number(t)),
            },
            duration: { configurable: true, get: () => (self.duration > 0 && isFinite(self.duration) ? self.duration : NaN) },
            paused: { configurable: true, get: () => self.paused },
            ended: { configurable: true, get: () => self.ended },
            seeking: { configurable: true, get: () => self.seekingTo !== null },
            readyState: { configurable: true, get: () => (self.seekingTo !== null || !self.shownOnce || self.buffering ? 1 : 4) },
            videoWidth: { configurable: true, get: () => self.shownSize.width },
            videoHeight: { configurable: true, get: () => self.shownSize.height },
            playbackRate: {
                configurable: true,
                get: () => self.rate,
                set: (r: number) => self.setSpeed(r),
            },
            defaultPlaybackRate: {
                configurable: true,
                get: () => defaultRate,
                set: (r: number) => {
                    if (r > 0) defaultRate = r
                },
            },
            tmBoost: {
                configurable: true,
                writable: true,
                value: (level: number) => {
                    self.boost = Math.max(1, Math.min(2, Number(level) || 1))
                    self.applyVolume()
                },
            },
            volume: {
                configurable: true,
                get: () => self.volume,
                set: (v: number) => {
                    self.volume = Math.max(0, Math.min(1, Number(v) || 0))
                    self.applyVolume()
                    self.fire('volumechange')
                },
            },
            muted: {
                configurable: true,
                get: () => self.muted,
                set: (m: boolean) => {
                    self.muted = !!m
                    self.applyVolume()
                    self.fire('volumechange')
                },
            },
            buffered: {
                configurable: true,
                get: () => {
                    const start = self.lastShown ?? self.clock.position()
                    const end = self.eof && self.duration > 0 ? self.duration : Math.max(start, self.ring.last ?? start)
                    const has = isFinite(start) && isFinite(end) && end > start
                    return { length: has ? 1 : 0, start: () => start, end: () => end } as unknown as TimeRanges
                },
            },
            play: { configurable: true, value: () => self.play() },
            pause: { configurable: true, value: () => self.pause() },
            // The picture is a canvas: no picture in picture, no iPhone video full screen.
            disablePictureInPicture: { configurable: true, get: () => true },
            requestPictureInPicture: { configurable: true, value: () => Promise.reject(new DOMException('Not with this video', 'NotSupportedError')) },
            webkitSetPresentationMode: { configurable: true, value: undefined },
            webkitEnterFullscreen: { configurable: true, value: undefined },
        }
        video.pause()
        video.removeAttribute('src')
        video.classList.add('sw-active')
        Object.defineProperties(video, own)
        return () => {
            for (const key of Object.keys(own)) delete (video as unknown as Record<string, unknown>)[key]
            video.classList.remove('sw-active')
        }
    }

    private applyVolume() {
        if (this.gain) this.gain.gain.value = this.muted ? 0 : this.volume * this.boost
        this.limiter?.set(this.boost > 1)
    }

    async setAudio(track: SoundTrack): Promise<boolean> {
        const sound = this.sounds.find((s) => s === track)
        if (!sound?.decodable || this.destroyed || !this.ctx) return false
        if (sound === this.sound) return true
        this.sound = sound
        this.silent = false
        this.restart(this.clock.position())
        return true
    }

    private async play(): Promise<void> {
        if (this.destroyed || !this.paused) return
        this.paused = false
        this.fire('play')
        if (this.ended) {
            this.ended = false
            this.seek(0)
            return
        }
        this.buffering = true
        if (!this.shownOnce || this.seekingTo !== null) this.fire('waiting')
        this.wake()
        this.kick()
    }

    private pause() {
        if (this.destroyed || this.paused) return
        this.paused = true
        this.buffering = false
        this.holdClock()
        this.fire('pause')
        this.fire('timeupdate')
    }

    private setSpeed(r: number) {
        if (!(r > 0) || r === this.rate || this.destroyed) return
        this.rate = r
        this.fire('ratechange')
        // Sound already scheduled plays at the old speed: start the run again from here.
        if (this.shownOnce) this.restart(this.clock.position())
        else this.clock.setRate(r)
    }

    private seek(t: number) {
        if (this.destroyed || !isFinite(t)) return
        const to = Math.max(0, this.duration > 0 ? Math.min(t, this.duration) : t)
        // The player asks for the second the run starts at: the run is already on its way.
        if (!this.shownOnce && this.seekingTo === null && Math.abs(to - this.startAt) < 0.05) return
        this.ended = false
        this.seekingTo = to
        this.restartPending = true
        // The picture and sound stand still while the place is chosen.
        this.holdClock()
        this.fire('seeking')
        this.fire('timeupdate')
        // A drag over the seek bar sets this dozens of times a second: only where it settles is decoded.
        clearTimeout(this.seekTimer)
        this.seekTimer = setTimeout(() => this.seekingTo !== null && this.restart(this.seekingTo), SEEK_SETTLE_MS)
    }

    // ---- the clock and the screen ----------------------------------------------------------

    /** Stops the clock and the sound where they are. */
    private holdClock() {
        if (!this.clock.running) return
        this.clock.hold()
        if (this.useContextClock && this.ctx) this.ctx.suspend().then(() => this.clock.settle(), () => {})
    }

    private startClock() {
        this.buffering = false
        if (this.useContextClock && this.ctx) {
            this.ctx.resume().catch(() => {})
            this.clock.go(true)
            // A browser that wants a tap before it plays sound: the picture goes on without it.
            const gen = this.gen
            setTimeout(() => {
                if (gen !== this.gen || this.destroyed || this.paused || !this.useContextClock || !this.ctx || this.ctx.state === 'running') return
                const at = this.clock.position()
                this.useContextClock = false
                this.silent = true
                this.stopSources()
                this.clock.set(at)
                if (debug) console.debug('[canvas] no sound: the audio context would not start')
            }, 1500)
        } else this.clock.go(false)
        this.fire('playing')
        this.kick()
    }

    private wake() {
        const w = this.waker
        this.waker = null
        w?.()
    }

    /** Waits while `blocked()` holds: woken by the screen taking a picture, a pause, a seek, and while playing every 250 ms (a sound's time passing). */
    private async until(gen: number, blocked: () => boolean) {
        while (gen === this.gen && !this.destroyed && blocked()) {
            await new Promise<void>((resolve) => {
                this.waker = resolve
                clearTimeout(this.waitTimer)
                // Nothing runs by itself while paused (a TV's idle CPU).
                if (!this.paused) this.waitTimer = setTimeout(resolve, 250)
            })
        }
        clearTimeout(this.waitTimer)
    }

    /**
     * Asks for a look at the screen: the next refresh, and a timer beside it. The refresh is the
     * right moment to draw while it comes on time; a display that is slow to refresh (WebKit with
     * no GPU on a busy machine ran its refreshes 120 ms apart while its timers were on time, a TV
     * is the same) would leave the picture a refresh behind the sound, so after a late refresh the
     * timer looks every few milliseconds and draws what is due. Either one that comes first looks;
     * the other one stays set for the next look.
     */
    private kick() {
        if (this.destroyed) return
        if (document.hidden) {
            if (!this.loopTimer) this.loopTimer = setTimeout(this.onTimer, 500)
            return
        }
        if (!this.raf) this.raf = requestAnimationFrame(this.onRefresh)
        if (!this.loopTimer) this.loopTimer = setTimeout(this.onTimer, this.slowDisplay ? TIMER_STEP_MS : SLOW_REFRESH_MS)
    }

    private readonly onRefresh = () => {
        this.raf = 0
        const now = performance.now()
        this.slowDisplay = this.lastRefresh > 0 && now - this.lastRefresh > SLOW_REFRESH_MS
        this.lastRefresh = now
        // The refresh looks now; the timer is set again by [kick] when the clock still runs.
        clearTimeout(this.loopTimer)
        this.loopTimer = undefined
        this.look()
    }

    /** The timer came first: the refresh is late. */
    private readonly onTimer = () => {
        this.loopTimer = undefined
        if (!document.hidden) this.slowDisplay = true
        this.look()
    }

    private look() {
        if (this.destroyed) return
        this.step()
        // Stays on the refresh only while the clock runs; every other state is woken by a picture arriving.
        if (this.clock.running) this.kick()
    }

    /** One look at the screen: seek done, clock started or held, the due picture drawn, the end found. */
    private step() {
        const ring = this.ring
        if (this.restartPending) return
        // The first picture goes up as soon as there is one, played or not.
        if (!this.shownOnce && this.seekingTo === null && ring.length) this.draw(ring.take(0)!)
        if (this.seekingTo !== null) {
            const first = ring.peek(0)
            if (!first) {
                if (this.eof) this.finishSeek(null)
                return
            }
            this.finishSeek(ring.take(0)!)
        }
        if (this.paused) return
        const hidden = document.hidden
        if (this.buffering) {
            if (!this.shownOnce || !refilled(ring.length, ring.maxFrames, this.eof)) return
            this.startClock()
        }
        const now = this.clock.position()
        // The clock ran on while the page was hidden (no drawing then): what is late is dropped, not drawn.
        const choice = chooseFrame(ring.times(), now)
        if (choice.show >= 0) {
            const f = ring.take(choice.show)!
            this.dropped += choice.dropped
            if (!hidden) this.draw(f)
            else this.lastShown = f.at
            this.wake()
        } else if (!hidden && starved(ring.length, this.eof, now, this.lastShown)) {
            this.underrun()
            return
        }
        if (this.eof && ring.length === 0 && now >= Math.max((this.lastShown ?? 0) + this.frameLength, this.soundEnd) - 0.02) return this.finish()
        const t = performance.now()
        if (t - this.lastTimeEvent >= TIME_EVENT_MS) {
            this.lastTimeEvent = t
            this.fire('timeupdate')
            this.fire('progress')
            this.overlay?.draw()
            this.mirrorStyle()
        }
    }

    private draw(f: Shown) {
        this.drawing.draw(f.planes, f.shownWidth)
        this.drawn++
        this.lastShown = f.at
        this.shownSize = { width: f.shownWidth, height: f.planes.height }
        if (!this.shownOnce) {
            this.shownOnce = true
            this.fire('loadedmetadata')
            this.fire('durationchange')
            this.fire('canplay')
        }
    }

    /** The first picture at or after the seek target is up: the element is at rest, and plays on when it was asked to. */
    private finishSeek(f: Shown | null) {
        const to = this.seekingTo
        if (to === null) return
        if (f) this.draw(f)
        this.seekingTo = null
        this.fire('seeked')
        this.fire('timeupdate')
        this.overlay?.draw()
        this.wake()
        this.buffering = !this.paused
        if (this.paused) this.fire('canplay')
        if (!f) this.finish()
    }

    /** The decoder fell behind the clock: the clock waits for it (sound too), and says so. */
    private underrun() {
        this.holdClock()
        this.buffering = true
        this.fire('waiting')
        const now = performance.now()
        this.underruns = this.underruns.filter((t) => now - t < 30_000)
        this.underruns.push(now)
        this.underrunTotal++
        if (this.underruns.length >= 3 && !this.saidSlow) {
            this.saidSlow = true
            this.ev.onSlow?.()
        }
        if (debug) console.debug('[canvas] waiting for the decoder', this.underruns.length)
    }

    private finish() {
        if (this.ended) return
        this.ended = true
        this.paused = true
        this.holdClock()
        this.fire('timeupdate')
        this.fire('pause')
        this.fire('ended')
    }

    // ---- the run ---------------------------------------------------------------------------

    /** Starts over at `t`: what was decoded or scheduled is dropped, the clock waits for the first pictures. */
    private restart(t: number) {
        const gen = ++this.gen
        this.wake()
        clearTimeout(this.seekTimer)
        this.stopSources()
        this.ring.clear()
        this.eof = false
        this.lastAt = -1
        this.lastShown = null
        this.soundEnd = 0
        this.holdClock()
        this.clock.set(t)
        this.clock.setRate(this.rate)
        this.timeline.reset()
        this.restartPending = false
        this.buffering = !this.paused
        if (this.seekingTo === null && this.shownOnce) this.seekingTo = t
        // The run before sees the new generation at its next step; the new one starts after it.
        const before = this.running
        this.running = (async () => {
            await before.catch(() => {})
            if (gen !== this.gen || this.destroyed) return
            await this.run(gen, t)
        })().catch((e) => {
            if (gen !== this.gen || this.destroyed) return
            // A failed run leaves nothing playing or showing under the page that says why.
            this.gen++
            this.wake()
            this.stopSources()
            this.holdClock()
            this.canvas.style.display = 'none'
            this.ev.onError?.(e)
        })
    }

    private stopSources() {
        for (const s of this.sources) {
            s.onended = null
            try {
                s.stop()
            } catch {
                /* not started */
            }
            s.disconnect()
        }
        this.sources.clear()
    }

    /** The picture's decoder once, and the chosen sound's (another track frees the last one's). */
    private async openDecoders() {
        const L = this.L
        if (!this.vdec) {
            const [, c, pkt, frame] = await L.ff_init_decoder(this.pic.codecId, { codecpar: this.pic.codecpar, time_base: this.pic.tb })
            this.vdec = { c, pkt, frame }
        }
        const s = this.silent ? null : this.sound
        if (this.adec && this.adec.index !== s?.index) {
            await L.ff_free_decoder(this.adec.c, this.adec.pkt, this.adec.frame)
            this.adec = null
        }
        const st = s ? this.streams.get(s.index) : null
        if (s && st && !this.adec) {
            const [, c, pkt, frame] = await L.ff_init_decoder(st.codecId, { codecpar: st.codecpar, time_base: st.tb })
            this.adec = { c, pkt, frame, index: s.index }
            this.audioTb = st.tb
        }
    }

    private async run(gen: number, t: number) {
        const L = this.L
        const pic = this.pic
        await this.openDecoders()
        if (gen !== this.gen) return
        const base = this.base
        const seconds = (lo: number, hi: number, tb: [number, number]) => {
            const s = toSeconds(lo, hi, tb, L)
            return s === null ? null : s - base
        }
        // A demuxer just opened is at the start already: seeking there would drop what it read
        // while finding the streams (FLV, which keeps no index, then starts at its second key frame).
        if (!(this.fresh && t === 0)) {
            const target = Math.max(0, Math.floor(((t + base) * pic.tb[1]) / pic.tb[0]))
            const lo = target % 2 ** 32
            const hi = Math.floor(target / 2 ** 32)
            if ((await L.avformat_seek_file_max(this.fmt, pic.index, lo, hi, 0)) < 0) {
                // No index to seek by (FLV, some MPEG-PS): to the same share of the file in bytes;
                // decoding then starts at the next key frame from there.
                const at = this.duration > 0 ? Math.floor(this.size * Math.min(1, t / this.duration) * 0.98) : 0
                await L.avformat_seek_file(this.fmt, -1, 0, 0, at % 2 ** 32, Math.floor(at / 2 ** 32), at % 2 ** 32, Math.floor(at / 2 ** 32), L.AVSEEK_FLAG_BYTE)
            }
        }
        this.fresh = false
        if (this.vdec) await L.avcodec_flush_buffers(this.vdec.c)
        if (this.adec) await L.avcodec_flush_buffers(this.adec.c)
        if (gen !== this.gen) return

        const sound = this.silent ? null : this.sound
        const how = this.drawing.kind === 'webgl' ? 'WebGL' : '2D canvas'
        this.detail = `Played with libav.js on a canvas (${how}), picture decoded (${this.videoCodec}) in a worker${sound && this.adec ? `, sound ${codecName(sound)} through Web Audio` : ', no sound'}`
        this.ev.onDetail?.(this.detail)

        let keyed = false
        let nextSound = 0
        let gathered: Gathered | null = null
        const frameLength = pic.tb[0] / pic.tb[1] <= 0.1 ? pic.tb[0] / pic.tb[1] : 1 / 25
        this.frameLength = frameLength

        /** Hands the gathered sound to the audio context as one buffer. */
        const flush = () => {
            const g = gathered
            gathered = null
            const ctx = this.ctx
            if (!g || !ctx || this.silent || g.samples <= 0) return
            const chans = g.channels[0].length
            const buffer = ctx.createBuffer(chans, g.samples, g.rate)
            for (let c = 0; c < chans; c++) {
                const out = buffer.getChannelData(c)
                let at = 0
                for (const part of g.channels) {
                    out.set(part[c], at)
                    at += part[c].length
                }
            }
            const slot = this.timeline.place(g.at, g.samples / g.rate, ctx.currentTime)
            if (!slot) return
            const src = ctx.createBufferSource()
            src.buffer = buffer
            src.playbackRate.value = this.clock.rate
            src.connect(this.gain!)
            src.onended = () => {
                this.sources.delete(src)
                src.disconnect()
            }
            src.start(slot.when, slot.offset)
            this.sources.add(src)
            this.soundEnd = Math.max(this.soundEnd, g.end)
        }
        const addSound = (frames: Frame[]) => {
            for (const f of frames) {
                const planes = soundPlanes(f.format as number, f.channels ?? 0, f.nb_samples ?? 0, f.data)
                if (!planes) continue
                const rate = f.sample_rate ?? 48000
                const at = (f.pts != null ? seconds(f.pts, f.ptshi ?? 0, this.audioTb) : null) ?? nextSound
                const length = (f.nb_samples ?? 0) / rate
                nextSound = at + length
                // Before the second asked for: it plays nothing (the timeline would cut it anyway).
                if (at + length < t - 0.05) continue
                const mixed = downmix(planes)
                if (gathered && (Math.abs(gathered.end - at) > 0.03 || gathered.rate !== rate || gathered.channels[0].length !== mixed.length)) flush()
                if (!gathered) gathered = { at, rate, channels: [], samples: 0, end: at }
                gathered.channels.push(mixed)
                gathered.samples += mixed[0].length
                gathered.end = at + length
                if (gathered.samples / rate >= 0.2) flush()
            }
        }

        for (;;) {
            if (gen !== this.gen) return
            // Room for pictures (the ring) and not too much sound scheduled ahead of the clock.
            await this.until(gen, () => this.ring.full || (this.clock.running && !!this.ctx && !this.silent && (this.timeline.end ?? 0) - this.ctx.currentTime > SOUND_AHEAD))
            if (gen !== this.gen) return
            const [res, packets] = await L.ff_read_frame_multi(this.fmt, this.pkt, { limit: 128 * 1024 })
            if (gen !== this.gen) return
            const end = res === L.AVERROR_EOF
            if (res < 0 && !end && res !== -L.EAGAIN) throw new Error(`libav read ${res}`)
            let vp: Packet[] = packets[pic.index] ?? []
            const ap: Packet[] = sound && this.adec ? (packets[sound.index] ?? []) : []

            // Sound first: it is the clock, and the picture may wait for room.
            if (this.adec) {
                for (let i = 0; i < ap.length || (end && i === 0); i += SOUND_PACKETS_PER_CALL) {
                    const chunk = ap.slice(i, i + SOUND_PACKETS_PER_CALL)
                    const last = end && i + SOUND_PACKETS_PER_CALL >= ap.length
                    const frames: Frame[] = await L.ff_decode_multi(this.adec.c, this.adec.pkt, this.adec.frame, chunk, { fin: last, ignoreErrors: true })
                    if (gen !== this.gen) return
                    addSound(frames)
                }
                if (end) flush()
            }

            if (!keyed) {
                // Decoding starts at a key frame, where the seek landed.
                const k = vp.findIndex((p) => ((p.flags ?? 0) & AV_PKT_FLAG_KEY) !== 0)
                vp = k < 0 ? [] : vp.slice(k)
                keyed = k >= 0
            }
            for (let i = 0; i < vp.length || (end && i === 0); i += PICTURES_PER_CALL) {
                const chunk = vp.slice(i, i + PICTURES_PER_CALL)
                const last = end && i + PICTURES_PER_CALL >= vp.length
                const t0 = performance.now()
                const frames: Frame[] = await L.ff_decode_multi(this.vdec!.c, this.vdec!.pkt, this.vdec!.frame, chunk, { fin: last, ignoreErrors: true, copyoutFrame: 'video_packed' })
                this.gauge.add(frames.length, performance.now() - t0)
                if (gen !== this.gen) return
                if (this.gauge.verdict() === 'heavy') {
                    if (debug) console.debug('[canvas] too heavy', this.gauge.fps.toFixed(1), 'frames a second')
                    throw new Unplayable({ kind: 'too-heavy', codec: this.heavyCodec })
                }
                for (const f of frames) {
                    if (!(await this.take(gen, f, t, seconds))) {
                        if (gen !== this.gen) return
                    }
                }
                // A picture or two for the screen to start on.
                this.kick()
            }
            if (end) break
        }
        if (gen !== this.gen) return
        this.eof = true
        this.kick()
        // Whatever started waiting on more of the file is told there is none.
        this.wake()
    }

    /** One decoded picture into the ring: dropped when it is before the target or already late, else queued (waits for room). */
    private async take(gen: number, f: Frame, target: number, seconds: (lo: number, hi: number, tb: [number, number]) => number | null): Promise<boolean> {
        const pic = this.pic
        let at =
            (f.pts != null ? seconds(f.pts, f.ptshi ?? 0, pic.tb) : null) ??
            (f.best_effort_timestamp != null ? seconds(f.best_effort_timestamp, f.best_effort_timestamphi ?? 0, pic.tb) : null)
        if (at === null) at = this.lastAt >= 0 ? this.lastAt + this.frameLength : target
        if (this.lastAt >= 0 && at <= this.lastAt) at = this.lastAt + 0.001
        if (this.lastAt >= 0 && at - this.lastAt > 0.005 && at - this.lastAt < 0.25) this.frameLength = at - this.lastAt
        this.lastAt = at
        // Before the second asked for: decoded (it leads to it) but not shown.
        if (this.seekingTo !== null || !this.shownOnce || this.lastShown === null) {
            if (at < target - this.frameLength / 2) return false
        }
        // Already past the clock by more than a few frames: not worth drawing (a slow decoder, a hidden page).
        if (this.clock.running && at < this.clock.position() - 0.08) {
            this.dropped++
            return false
        }
        const planes = yuvPlanes({ format: f.format as number, width: f.width ?? 0, height: f.height ?? 0, data: f.data as Uint8Array, layout: f.layout }, this.L)
        // A layout it cannot draw (a palette, 10 bit): the app plays it.
        if (!planes) throw new Unplayable({ kind: 'picture', codec: this.videoCodec })
        const [n, d] = f.sample_aspect_ratio ?? [0, 0]
        const shownWidth = n > 0 && d > 0 && n !== d ? Math.min(2048, Math.round((planes.width * n) / d / 2) * 2) : planes.width
        await this.until(gen, () => this.ring.full)
        if (gen !== this.gen) return false
        this.ring.push({ at, bytes: planes.y.buffer.byteLength, planes, shownWidth })
        // The screen looks (a seek to finish, the clock to start) even when the ring fills before this batch ends.
        this.kick()
        return true
    }

    destroy() {
        if (this.destroyed) return
        this.destroyed = true
        this.gen++
        this.wake()
        clearTimeout(this.waitTimer)
        clearTimeout(this.seekTimer)
        clearTimeout(this.loopTimer)
        if (this.raf) cancelAnimationFrame(this.raf)
        this.styleWatch?.disconnect()
        this.restoreVideo()
        this.overlay?.remove()
        this.stopSources()
        this.ring.clear()
        this.drawing.destroy()
        this.canvas.remove()
        this.ctx?.close().catch(() => {})
        // Whatever libav.js was doing is dropped with its worker.
        this.L.terminate()
    }
}
