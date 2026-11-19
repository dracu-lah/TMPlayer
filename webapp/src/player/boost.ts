// Volume boost (CP48, GitHub #33): the sound above the file's own level, up to 200%, through Web
// Audio with a limiter behind the gain so a lifted peak is held back instead of clipping.
//
// Which path carries the sound decides where the gain goes:
//  - the page's `<video>` (native, MSE repack, libav.js re-encode): the element is routed into a
//    graph once, gain then limiter then the speakers (applyBoost below);
//  - the canvas engines (movi.ts, libavcanvas.ts), whose sound is their own Web Audio: they define
//    `tmBoost` on the element and apply the level on their own gain (the level is kept here, so an
//    engine that starts later opens at it).
// iPhone and iPad are left out: Safari's `<video>` volume is read only there, and a media element
// source can break AirPlay and background audio, so the entry is hidden (boostSupported).

/**
 * The limiter: a compressor that starts just under full scale and squeezes hard, so a lifted peak
 * lands near it, then a hard clip at full scale for what its 3 ms attack lets through.
 */
const LIMITER = { threshold: -3, knee: 0, ratio: 20, attack: 0.003, release: 0.12 }

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
    gain: GainNode
    limiter: ReturnType<typeof limiterBetween>
}
// One graph per element for its whole life: an element can be routed once only (a second
// createMediaElementSource on it throws, whatever context asks), so a remount reuses it.
const graphs = new WeakMap<HTMLMediaElement, Graph>()
// One start at a time per element, however many `playing` events come while it waits.
const starting = new WeakMap<HTMLMediaElement, Promise<Graph | null>>()

/**
 * The limiter in front of `to`: on above 100%, bypassed at 100% so an unboosted sound passes as the
 * file has it. `from` is wired to whichever it points at. The chain is the compressor, the makeup it
 * adds taken back out, and a hard clip at full scale (a WaveShaper whose two point curve is the
 * identity inside +-1 and flat outside it), so no sample leaves above 1.0.
 */
export function limiterBetween(ctx: BaseAudioContext, from: AudioNode, to: AudioNode): { set: (on: boolean) => void; node: DynamicsCompressorNode } {
    const node = ctx.createDynamicsCompressor()
    node.threshold.value = LIMITER.threshold
    node.knee.value = LIMITER.knee
    node.ratio.value = LIMITER.ratio
    node.attack.value = LIMITER.attack
    node.release.value = LIMITER.release
    const trim = ctx.createGain()
    trim.gain.value = 1 / limiterMakeup()
    const clip = ctx.createWaveShaper()
    clip.curve = new Float32Array([-1, 1])
    clip.oversample = 'none'
    node.connect(trim)
    trim.connect(clip)
    clip.connect(to)
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
            const gain = ctx.createGain()
            source.connect(gain)
            const limiter = limiterBetween(ctx, gain, ctx.destination)
            limiter.set(false)
            const graph: Graph = { ctx, source, gain, limiter }
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

/**
 * Sets the boost on `video` (1 to 2). Resolves true when it took effect: false where nothing could
 * carry it (no Web Audio, a context no gesture has allowed yet, a cross origin source), so the
 * caller can ask again after the next tap.
 */
export async function applyBoost(video: HTMLVideoElement, next: number): Promise<boolean> {
    level = next
    video.dataset.boost = String(next)
    const hook = (video as HTMLVideoElement & BoostHook).tmBoost
    if (typeof hook === 'function') {
        hook(next)
        const graph = graphs.get(video)
        if (graph) graph.gain.gain.value = 1
        return true
    }
    if (next <= 1 && !graphs.has(video)) return true
    const graph = await graphFor(video)
    if (!graph) return next <= 1
    // The engine may have changed while the context was waking.
    const now = (video as HTMLVideoElement & BoostHook).tmBoost
    if (typeof now === 'function') return applyBoost(video, level)
    // Which path carries it, for the end to end tests and the playback details.
    video.dataset.boostPath = 'element'
    graph.limiter.set(level > 1)
    graph.gain.gain.setTargetAtTime(level, graph.ctx.currentTime, 0.02)
    return true
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
    graph.limiter.set(false)
    graph.gain.gain.value = 1
    setTimeout(() => {
        if (video.isConnected || graphs.get(video) !== graph) return
        graphs.delete(video)
        void graph.ctx.close().catch(() => {})
    }, 1000)
}
