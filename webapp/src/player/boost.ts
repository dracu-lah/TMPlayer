// Volume boost (CP48, GitHub #33): the sound above the file's own level, up to 200%, through Web
// Audio with a limiter behind the gain so a lifted peak is held back instead of clipping.
//
// Which path carries the sound decides where the gain goes:
//  - the page's `<video>` (native, MSE repack, libav.js re-encode): the element is routed into a
//    graph once, gain then limiter then the speakers (applyBoost below);
//  - the canvas engines (movi.ts, libavcanvas.ts), whose sound is their own Web Audio: they define
//    `tmBoost` on the element and apply the level on their own gain (the level is kept here, so an
//    engine that starts later opens at it).
// Night mode and Even out volume (WP-33, the apps' two switches) ride in the same graph: the element's
// sound goes through a lift and a compressor for Night mode, then a slow gain that follows the track's
// own average level for Even out volume, then the boost and its limiter. Only the element's own path
// has them: a canvas engine's sound is its own Web Audio and has the boost only. Audio delay stays out
// of the page (the browser's <video> cannot move its sound; see playerKeys.ts, web.audio_delay_unavailable).
// iPhone and iPad are left out: Safari's `<video>` volume is read only there, and a media element
// source can break AirPlay and background audio, so the entry is hidden (boostSupported).

/**
 * The limiter: a compressor that starts just under full scale and squeezes hard, so a lifted peak
 * lands near it, then a hard clip at full scale for what its 3 ms attack lets through.
 */
const LIMITER = { threshold: -3, knee: 0, ratio: 20, attack: 0.003, release: 0.12 }

/** A compressor's settings (dB, ratio, seconds). */
export interface Squeeze {
    threshold: number
    knee: number
    ratio: number
    attack: number
    release: number
}

/**
 * Night mode, as the apps have it (VolumeBoostProcessor): a lift of about 6 dB, then a compressor
 * that starts far lower (about -15 dBFS) at 6 to 1, so a full scale peak lands near -11 dBFS while
 * dialogue comes up: the loud scenes end quieter than they were.
 */
export const NIGHT: Squeeze = { threshold: -15, knee: 0, ratio: 6, attack: 0.005, release: 0.25 }
export const NIGHT_LIFT = 2

/** Even out volume's aim: about -18 dBFS of average level (an amplitude of 0.126), as the apps have it. */
const LEVEL_TARGET = 0.126
/** Its largest lift, about +12 dB. */
export const LEVEL_MAX = 4
/** About -60 dBFS: a block quieter than this is silence and is not measured, so a pause does not count as quiet. */
const LEVEL_GATE_SQUARE = 1e-6
/** How long the average level is taken over, and how fast the lift rises and falls (seconds): slow up, quick down, so it never pumps. */
export const LEVEL_SECONDS = 2
export const LEVEL_RISE_SECONDS = 1.5
export const LEVEL_FALL_SECONDS = 0.3
/** How often the level is looked at (ms): a few times a second is enough for a 2 s average and costs a TV stick next to nothing. */
export const LEVEL_POLL_MS = 250

/**
 * The lift Even out volume wants for a track whose average level is `meanSquare`: towards the target,
 * never below 1 (a loud track is left as it is) and never above 4.
 */
export function levelFor(meanSquare: number): number {
    if (!Number.isFinite(meanSquare) || meanSquare <= 0) return LEVEL_MAX
    return Math.min(LEVEL_MAX, Math.max(1, Math.sqrt((LEVEL_TARGET * LEVEL_TARGET) / meanSquare)))
}

/**
 * The running average (a mean square) after one more look: a one pole follower over about two
 * seconds, with a silent block left out (the average stays as it was). `dt` is the seconds since
 * the last look; garbage in (NaN, negative) leaves the average alone.
 */
export function followLoudness(prev: number, meanSquare: number, dt: number): number {
    if (!Number.isFinite(meanSquare) || !(meanSquare > LEVEL_GATE_SQUARE) || !Number.isFinite(dt) || !(dt > 0)) return prev
    const a = 1 - Math.exp(-dt / LEVEL_SECONDS)
    return prev + (meanSquare - prev) * a
}

/** The mean square of one block of samples. */
export function meanSquareOf(samples: ArrayLike<number>): number {
    let sum = 0
    for (let i = 0; i < samples.length; i++) sum += samples[i] * samples[i]
    return samples.length ? sum / samples.length : 0
}

/** The time constant (seconds) the lift walks to its target with: slow when rising, quick when falling. */
export const levelConstant = (from: number, to: number) => (to > from ? LEVEL_RISE_SECONDS : LEVEL_FALL_SECONDS)

/** The starting average of a track: the target itself, so the lift begins at 1 and walks from what is heard. */
export const LEVEL_START = LEVEL_TARGET * LEVEL_TARGET

/**
 * The makeup gain a DynamicsCompressorNode adds by itself (Web Audio's spec: the full range gain,
 * the curve's answer to 0 dBFS, raised to 0.6 and inverted). It cannot be switched off, so the
 * chain takes it back out: an unboosted level passes the limiter at exactly the gain it was given.
 */
export function limiterMakeup(thresholdDb = LIMITER.threshold, ratio = LIMITER.ratio): number {
    // Knee 0: above the threshold the output grows by 1/ratio of the input's excess; at 0 dBFS in:
    const outDb = thresholdDb + (0 - thresholdDb) / ratio
    const full = 10 ** (outDb / 20)
    return (1 / full) ** 0.6
}

type AudioCtor = typeof AudioContext

const Ctor = (typeof window === 'undefined' ? undefined : (window.AudioContext ?? (window as unknown as { webkitAudioContext?: AudioCtor }).webkitAudioContext)) as AudioCtor | undefined

/** An iPhone or iPad (the same test the controls use for the volume they cannot set). */
const IOS = typeof navigator !== 'undefined' && (/iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1))

/** Whether this browser can lift the sound: Web Audio, and not an iPhone or iPad. */
export const boostSupported = () => !IOS && !!Ctor
/** An iPhone or iPad, where the entry stays to say why there is no boost. */
export const boostIsIos = () => IOS

/** The level in force (1 is off), kept for engines that start after it was set. */
let level = 1
export const boostLevel = () => level
/** Night mode and Even out volume as asked (off until a viewer turns them on; the page keeps them). */
let night = false
let even = false

/** What a canvas engine defines on the element to take the level on its own gain. */
export interface BoostHook {
    tmBoost?: (level: number) => void
}

/** Same origin or a blob: a cross origin element source without CORS would play silent, so it is not routed. */
export function routableSource(src: string, origin: string, base = origin): boolean {
    try {
        if (!src || src.startsWith('blob:')) return true
        return new URL(src, base).origin === origin
    } catch {
        return false
    }
}

/**
 * Whether a context may be asked to run now: a browser that tracks user activation says no until
 * the page has had a tap or a key, and a context made before that stays suspended, so asking would
 * only make one more. A browser that does not say is asked.
 */
export function mayStart(activation: { hasBeenActive: boolean } | undefined): boolean {
    return !activation || activation.hasBeenActive
}

/** How long a resume that no gesture allowed is waited for: it can stay pending for good. */
const RESUME_WAIT_MS = 300

interface Graph {
    ctx: AudioContext
    source: MediaElementAudioSourceNode
    /** Night mode's lift, in front of its compressor. */
    lift: GainNode
    squeeze: ReturnType<typeof squeezeBetween>
    /** Even out volume's lift, walked by the poll. */
    leveller: GainNode
    analyser: AnalyserNode
    gain: GainNode
    limiter: ReturnType<typeof limiterBetween>
    /** The poll of the track's level, running only while Even out volume is on and the video plays. */
    poll: ReturnType<typeof setInterval> | null
    loudness: number
    polledAt: number
}
// One graph per element for its whole life: an element can be routed once only (a second
// createMediaElementSource on it throws, whatever context asks), so a remount reuses it.
const graphs = new WeakMap<HTMLMediaElement, Graph>()
// One start at a time per element, however many `playing` events come while it waits.
const starting = new WeakMap<HTMLMediaElement, Promise<Graph | null>>()

/**
 * A compressor in front of `to`, on or bypassed: `from` is wired to whichever it points at. The
 * chain is the compressor, the makeup it adds by itself taken back out, and (with `clip`) a hard
 * clip at full scale, a WaveShaper whose two point curve is the identity inside +-1 and flat
 * outside it, so no sample leaves above 1.0.
 */
export function squeezeBetween(ctx: BaseAudioContext, from: AudioNode, to: AudioNode, spec: Squeeze, clip: boolean): { set: (on: boolean) => void; node: DynamicsCompressorNode } {
    const node = ctx.createDynamicsCompressor()
    node.threshold.value = spec.threshold
    node.knee.value = spec.knee
    node.ratio.value = spec.ratio
    node.attack.value = spec.attack
    node.release.value = spec.release
    const trim = ctx.createGain()
    trim.gain.value = 1 / limiterMakeup(spec.threshold, spec.ratio)
    node.connect(trim)
    if (clip) {
        const shaper = ctx.createWaveShaper()
        shaper.curve = new Float32Array([-1, 1])
        shaper.oversample = 'none'
        trim.connect(shaper)
        shaper.connect(to)
    } else {
        trim.connect(to)
    }
    let on: boolean | null = null
    return {
        node,
        set(next) {
            if (next === on) return
            on = next
            try {
                from.disconnect()
            } catch {}
            from.connect(next ? node : to)
        },
    }
}

/**
 * The limiter in front of `to`: on above 100%, bypassed at 100% so an unboosted sound passes as the
 * file has it (see [squeezeBetween]).
 */
export function limiterBetween(ctx: BaseAudioContext, from: AudioNode, to: AudioNode) {
    return squeezeBetween(ctx, from, to, LIMITER, true)
}

async function graphFor(video: HTMLMediaElement): Promise<Graph | null> {
    const have = graphs.get(video)
    if (have) return have
    const going = starting.get(video)
    if (going) return going
    if (!Ctor || !mayStart(typeof navigator === 'undefined' ? undefined : (navigator as Navigator & { userActivation?: { hasBeenActive: boolean } }).userActivation)) return null
    if (!routableSource(video.currentSrc || video.src, location.origin, location.href)) return null
    const run = (async (): Promise<Graph | null> => {
        let ctx: AudioContext
        try {
            ctx = new Ctor()
        } catch {
            return null
        }
        const give = () => {
            void ctx.close().catch(() => {})
            return null
        }
        // A context made outside a gesture can stay suspended and its resume stay pending for good: it is waited for a moment,
        // and a context that does not run is closed, never routed (a routed element on a suspended context plays silent).
        if (ctx.state !== 'running') {
            await Promise.race([ctx.resume().catch(() => {}), new Promise((done) => setTimeout(done, RESUME_WAIT_MS))])
        }
        if (ctx.state !== 'running') return give()
        try {
            const source = ctx.createMediaElementSource(video)
            // source, Night mode (its lift, then its compressor, or straight past it), Even out volume's
            // gain, the boost's gain, the limiter (or straight past it), the speakers. The analyser only
            // listens to the source, for the level Even out volume follows.
            const lift = ctx.createGain()
            const afterNight = ctx.createGain()
            const leveller = ctx.createGain()
            const gain = ctx.createGain()
            const analyser = ctx.createAnalyser()
            analyser.fftSize = 2048
            source.connect(lift)
            source.connect(analyser)
            const squeeze = squeezeBetween(ctx, lift, afterNight, NIGHT, false)
            squeeze.set(false)
            afterNight.connect(leveller)
            leveller.connect(gain)
            const limiter = limiterBetween(ctx, gain, ctx.destination)
            limiter.set(false)
            const graph: Graph = { ctx, source, lift, squeeze, leveller, analyser, gain, limiter, poll: null, loudness: LEVEL_START, polledAt: 0 }
            graphs.set(video, graph)
            // The system may suspend the context (a phone call, a tab in the background): a routed element is silent until it runs again.
            const wake = () => void ctx.resume().catch(() => {})
            video.addEventListener('play', wake)
            ctx.onstatechange = () => ctx.state === 'suspended' && !video.paused && wake()
            return graph
        } catch {
            // The element was already routed (by a graph that was let go) or cannot be: the sound stays as it was.
            return give()
        }
    })()
    starting.set(video, run)
    try {
        return await run
    } finally {
        starting.delete(video)
    }
}

/** What a page asks of the sound of one element; a missing field keeps what it was. */
export interface AudioAsk {
    level?: number
    night?: boolean
    even?: boolean
}

/** Whether this element's sound is the page's own (not a canvas engine's): only there Night mode and Even out volume reach it. */
export const extrasHere = (video: HTMLMediaElement) => typeof (video as HTMLMediaElement & BoostHook).tmBoost !== 'function'

/** Stops the poll of the track's level. */
function stopPoll(graph: Graph) {
    if (graph.poll !== null) clearInterval(graph.poll)
    graph.poll = null
}

/** Starts or stops the poll that walks Even out volume's lift, as the switch and the video's playing say. */
function pollLevel(video: HTMLMediaElement, graph: Graph) {
    if (!(even && !video.paused && !video.ended)) return stopPoll(graph)
    if (graph.poll !== null) return
    const block = new Float32Array(graph.analyser.fftSize)
    graph.polledAt = graph.ctx.currentTime
    graph.poll = setInterval(() => {
        // Nothing runs for a stopped video or a suspended context: the poll stops itself and the next `play` starts it again.
        if (!even || video.paused || video.ended || graph.ctx.state !== 'running') return stopPoll(graph)
        const now = graph.ctx.currentTime
        graph.analyser.getFloatTimeDomainData(block)
        graph.loudness = followLoudness(graph.loudness, meanSquareOf(block), now - graph.polledAt)
        graph.polledAt = now
        const target = levelFor(graph.loudness)
        graph.leveller.gain.setTargetAtTime(target, now, levelConstant(graph.leveller.gain.value, target))
    }, LEVEL_POLL_MS)
}

/** Sets the whole chain from what was asked (level, night, even), on the element's own path. */
function settle(video: HTMLMediaElement, graph: Graph) {
    const now = graph.ctx.currentTime
    graph.squeeze.set(night)
    graph.lift.gain.setTargetAtTime(night ? NIGHT_LIFT : 1, now, 0.02)
    if (!even) graph.leveller.gain.setTargetAtTime(1, now, 0.08)
    // A lift and a squeeze can push a peak over full scale: the limiter is in whenever anything lifts.
    graph.limiter.set(level > 1 || night || even)
    graph.gain.gain.setTargetAtTime(level, now, 0.02)
    pollLevel(video, graph)
    video.dataset.night = String(night)
    video.dataset.even = String(even)
}

/** A canvas engine took the sound over: whatever this page's graph did to it is put to unity. */
function neutral(graph: Graph) {
    stopPoll(graph)
    graph.squeeze.set(false)
    graph.lift.gain.value = 1
    graph.leveller.gain.value = 1
    graph.limiter.set(false)
    graph.gain.gain.value = 1
}

/**
 * Sets the sound of `video`: the boost (1 to 2), Night mode and Even out volume. Resolves true when it
 * took effect: false where nothing could carry it (no Web Audio, a context no gesture has allowed
 * yet, a cross origin source), so the caller can ask again after the next tap. A canvas engine takes
 * the boost only (Night mode and Even out volume stay off there, see [extrasHere]).
 */
export async function applyAudio(video: HTMLVideoElement, ask: AudioAsk): Promise<boolean> {
    if (ask.level !== undefined) level = ask.level
    if (ask.night !== undefined) night = ask.night
    if (ask.even !== undefined) even = ask.even
    video.dataset.boost = String(level)
    const hook = (video as HTMLVideoElement & BoostHook).tmBoost
    if (typeof hook === 'function') {
        hook(level)
        const graph = graphs.get(video)
        if (graph) neutral(graph)
        return true
    }
    const wanted = level > 1 || night || even
    if (!wanted && !graphs.has(video)) return true
    const graph = await graphFor(video)
    if (!graph) return !wanted
    // The engine may have changed while the context was waking.
    const now = (video as HTMLVideoElement & BoostHook).tmBoost
    if (typeof now === 'function') return applyAudio(video, {})
    // Which path carries it, for the end to end tests and the playback details.
    video.dataset.boostPath = 'element'
    settle(video, graph)
    return true
}

/** Sets the boost on `video` (1 to 2); see [applyAudio]. */
export const applyBoost = (video: HTMLVideoElement, next: number) => applyAudio(video, { level: next })

/** The video started or stopped: Even out volume's poll follows (the poll stops itself when paused, so a play starts it). */
export function audioPlaying(video: HTMLMediaElement) {
    const graph = graphs.get(video)
    if (graph && extrasHere(video)) pollLevel(video, graph)
}

/**
 * The player went. The element stays routed (it can be only once, and a remount of the same element
 * takes the graph up again), so the graph is put to unity and out of the limiter rather than
 * closed; a context whose element has left the page is closed a moment later, since a browser
 * allows only a handful at once.
 */
export function releaseBoost(video: HTMLMediaElement) {
    const graph = graphs.get(video)
    if (!graph) return
    neutral(graph)
    setTimeout(() => {
        if (video.isConnected || graphs.get(video) !== graph) return
        graphs.delete(video)
        void graph.ctx.close().catch(() => {})
    }, 1000)
}
