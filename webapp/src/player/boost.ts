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
/** The limiter: a compressor that starts just under full scale and squeezes hard, so a lifted peak lands near it. */
const LIMITER = { threshold: -3, knee: 0, ratio: 20, attack: 0.003, release: 0.12 }

type AudioCtor = typeof AudioContext

const Ctor = (typeof window === 'undefined' ? undefined : (window.AudioContext ?? (window as unknown as { webkitAudioContext?: AudioCtor }).webkitAudioContext)) as AudioCtor | undefined

/** An iPhone or iPad (the same test the controls use for the volume they cannot set). */
const IOS = typeof navigator !== 'undefined' && (/iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1))

/** Whether this browser can lift the sound: Web Audio, and not an iPhone or iPad. */
export const boostSupported = () => !IOS && !!Ctor

/** The level in force (1 is off), kept for engines that start after it was set. */
let level = 1
export const boostLevel = () => level

/** What a canvas engine defines on the element to take the level on its own gain. */
export interface BoostHook {
    tmBoost?: (level: number) => void
}

interface Graph {
    ctx: AudioContext
    gain: GainNode
    limiter: DynamicsCompressorNode
}
const graphs = new WeakMap<HTMLMediaElement, Graph>()

/**
 * The limiter in front of `to`: on above 100%, bypassed at 100% so an unboosted sound passes as the
 * file has it. Returns the switch; `from` is wired to whichever it points at.
 */
export function limiterBetween(ctx: AudioContext, from: AudioNode, to: AudioNode): { set: (on: boolean) => void; node: DynamicsCompressorNode } {
    const node = ctx.createDynamicsCompressor()
    node.threshold.value = LIMITER.threshold
    node.knee.value = LIMITER.knee
    node.ratio.value = LIMITER.ratio
    node.attack.value = LIMITER.attack
    node.release.value = LIMITER.release
    node.connect(to)
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

/** Same origin or a blob: a cross origin element source without CORS would play silent, so it is not routed. */
function routable(video: HTMLMediaElement): boolean {
    try {
        const src = video.currentSrc || video.src
        if (!src || src.startsWith('blob:')) return true
        return new URL(src, location.href).origin === location.origin
    } catch {
        return false
    }
}

const limiters = new WeakMap<HTMLMediaElement, ReturnType<typeof limiterBetween>>()

async function graphFor(video: HTMLMediaElement): Promise<Graph | null> {
    const have = graphs.get(video)
    if (have) return have
    if (!Ctor || !routable(video)) return null
    let ctx: AudioContext
    try {
        ctx = new Ctor()
    } catch {
        return null
    }
    // Routed while suspended the element would play silent: only go ahead once the context runs (a tap or key allowed it).
    if (ctx.state !== 'running') await ctx.resume().catch(() => {})
    if (ctx.state !== 'running') {
        void ctx.close().catch(() => {})
        return null
    }
    if (graphs.has(video)) {
        void ctx.close().catch(() => {})
        return graphs.get(video) ?? null
    }
    try {
        const source = ctx.createMediaElementSource(video)
        const gain = ctx.createGain()
        source.connect(gain)
        const limit = limiterBetween(ctx, gain, ctx.destination)
        limit.set(false)
        const graph: Graph = { ctx, gain, limiter: limit.node }
        limiters.set(video, limit)
        graphs.set(video, graph)
        // The system may suspend the context (a phone call, a tab in the background): a routed element is silent until it runs again.
        const wake = () => void ctx.resume().catch(() => {})
        video.addEventListener('play', wake)
        ctx.onstatechange = () => ctx.state === 'suspended' && !video.paused && wake()
        return graph
    } catch {
        // The element was already routed or cannot be: the sound stays as it was.
        void ctx.close().catch(() => {})
        return null
    }
}

/**
 * Sets the boost on `video` (1 to 2). Resolves true when it took effect: false where nothing could
 * carry it (no Web Audio, a suspended context with no tap yet, a cross origin source), so the
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
    limiters.get(video)?.set(level > 1)
    graph.gain.gain.setTargetAtTime(level, graph.ctx.currentTime, 0.02)
    return true
}

/** Frees the context when the player goes: a browser allows only a handful at once. */
export function releaseBoost(video: HTMLMediaElement) {
    const graph = graphs.get(video)
    if (!graph) return
    graphs.delete(video)
    void graph.ctx.close().catch(() => {})
}
