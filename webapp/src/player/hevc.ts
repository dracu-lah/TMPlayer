// HEVC where the browser has no decoder (Firefox and Chromium on Linux, older Android): hevc.js
// decodes it and WebCodecs encodes it to H.264, in a worker (hevc-worker.ts), between the repack's
// fragmented MP4 and MediaSource. Loaded only for such a file.
import type { FromWorker, ToWorker } from './hevc-worker'

let able: Promise<boolean> | null = null

/** Whether this browser can stand an HEVC file in as H.264: a worker, WebAssembly, an H.264 encoder and MediaSource for it. */
export function canTranscodeHevc(): Promise<boolean> {
    able ??= (async () => {
        const MS = window.ManagedMediaSource ?? window.MediaSource
        if (!MS || typeof Worker !== 'function' || typeof WebAssembly !== 'object' || typeof VideoEncoder !== 'function') return false
        if (!MS.isTypeSupported('video/mp4; codecs="avc1.640033,mp4a.40.2"')) return false
        const h264 = await VideoEncoder.isConfigSupported({ codec: 'avc1.640033', width: 1920, height: 1080 }).catch(() => null)
        return !!h264?.supported
    })()
    return able
}

export interface Transcoded {
    /** The run's H.264 init segment, with its first fragment only. */
    init: Uint8Array | null
    codec: string
    bytes: Uint8Array | null
}

/** Workers that convert fragments side by side: two where the device has the cores, else one. */
const PARALLEL = (navigator.hardwareConcurrency ?? 4) >= 8 && !matchMedia('(pointer: coarse)').matches ? 2 : 1

class Lane {
    private worker = new Worker(new URL('./hevc-worker.ts', import.meta.url), { type: 'module' })
    private next = 0
    private waiting = new Map<number, (m: FromWorker) => void>()
    failed: Error | null = null

    constructor() {
        this.worker.onmessage = (e: MessageEvent<FromWorker>) => {
            const w = this.waiting.get(e.data.id)
            this.waiting.delete(e.data.id)
            w?.(e.data)
        }
        this.worker.onerror = (e) => {
            this.failed = new Error(`hevc worker: ${e.message || 'failed to start'}`)
            this.drop({ type: 'error', id: -1, message: this.failed.message })
        }
    }

    ask(m: Omit<ToWorker, 'id'>): Promise<FromWorker> {
        if (this.failed) return Promise.reject(this.failed)
        const id = ++this.next
        return new Promise((resolve) => {
            this.waiting.set(id, resolve)
            const msg = { ...m, id } as ToWorker
            this.worker.postMessage(msg, [msg.bytes.buffer])
        })
    }

    /** Answers everything still waiting with `m` (a skip on a seek, the error when the worker died). */
    drop(m: FromWorker) {
        for (const w of this.waiting.values()) w(m)
        this.waiting.clear()
    }

    /** Whether anything sent is still unanswered. */
    get busy() {
        return this.waiting.size > 0
    }

    terminate() {
        this.worker.terminate()
        this.drop({ type: 'skip', id: -1 })
    }
}

/** Every live transcoder, ended as the page goes: a worker torn down with the page holds the next one up. */
const live = new Set<HevcTranscoder>()
addEventListener('pagehide', () => {
    for (const t of live) t.destroy()
})

/**
 * The workers for one playing file. Each run (a start or a seek) begins with its init segment,
 * which every worker gets; fragments then go round the workers, each starting at a key frame
 * (mediabunny cuts them there), and come back in the order they were given.
 */
export class HevcTranscoder {
    readonly parallel = PARALLEL
    private lanes = Array.from({ length: PARALLEL }, () => new Lane())
    private turn = 0
    /** Recent fragments' speeds (video seconds per second of work, all workers together). */
    private speeds: number[] = []

    constructor() {
        live.add(this)
    }

    /** Starts run `gen` with its HEVC init segment; false when it was superseded. */
    async init(gen: number, bytes: Uint8Array): Promise<boolean> {
        this.turn = 0
        const answers = await Promise.all(this.lanes.map((l) => l.ask({ type: 'init', gen, bytes: bytes.slice() })))
        const error = answers.find((r) => r.type === 'error')
        if (error?.type === 'error') throw new Error(`hevc init: ${error.message}`)
        return answers.every((r) => r.type === 'ready')
    }

    /** One HEVC fragment (moof and mdat) as H.264; null when the run was superseded. */
    async media(gen: number, bytes: Uint8Array): Promise<Transcoded | null> {
        const lane = this.lanes[this.turn++ % this.lanes.length]
        const r = await lane.ask({ type: 'media', gen, bytes: bytes.slice() })
        if (r.type === 'error') throw new Error(`hevc: ${r.message}`)
        if (r.type !== 'out') return null
        if (r.speed > 0) this.speeds = [...this.speeds.slice(-5), r.speed * this.lanes.length]
        return { init: r.init, codec: r.codec, bytes: r.bytes }
    }

    /** Whether the last fragments were made more slowly than they play (with a margin), once a few are in. */
    slow(): boolean {
        if (this.speeds.length < 4) return false
        return this.speeds.reduce((a, b) => a + b, 0) / this.speeds.length < 0.95
    }

    /**
     * A seek: what is still being converted for the old place is answered as superseded at once, and
     * a worker busy with a fragment (up to 15 s on a heavy file) is replaced rather than waited for.
     */
    abandon() {
        this.lanes = this.lanes.map((l) => {
            if (!l.busy) return l
            l.terminate()
            return new Lane()
        })
    }

    destroy() {
        live.delete(this)
        for (const l of this.lanes) l.terminate()
    }
}
