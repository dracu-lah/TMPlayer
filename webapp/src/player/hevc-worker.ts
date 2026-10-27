// hevc.js in a worker: HEVC fragments in, H.264 fragments (with the AAC sound passed through) out,
// for a browser without an HEVC decoder (player/hevc.ts). Off the page's thread, so the controls
// stay smooth while it decodes and encodes.
import { SegmentTranscoder } from '@hevcjs/core'

const glueUrl = new URL('../../node_modules/@hevcjs/core/dist/wasm/hevc-decode.js', import.meta.url).href
const wasmBinaryUrl = new URL('../../node_modules/@hevcjs/core/dist/wasm/hevc-decode.wasm', import.meta.url).href

export type ToWorker = { type: 'init'; gen: number; id: number; bytes: Uint8Array } | { type: 'media'; gen: number; id: number; bytes: Uint8Array }
export type FromWorker =
    | { type: 'out'; id: number; init: Uint8Array | null; codec: string; bytes: Uint8Array | null; speed: number }
    | { type: 'skip'; id: number }
    | { type: 'ready'; id: number }
    | { type: 'error'; id: number; message: string }

const debug = self.location.hostname === 'localhost'

let glue: Promise<void> | null = null
/** The decoder's glue is a classic script setting globalThis.HEVCDecoderModule, which the transcoder looks for first. */
function loadGlue() {
    glue ??= fetch(glueUrl)
        .then((r) => (r.ok ? r.text() : Promise.reject(new Error(`hevc-decode.js ${r.status}`))))
        .then((text) => {
            ;(0, eval)(text)
        })
    return glue
}

let transcoder: SegmentTranscoder | null = null
let gen = -1
/** The run's H.264 init segment goes out with its first fragment. */
let initSent = false
let queue: Promise<void> = Promise.resolve()

const post = (m: FromWorker, transfer: Transferable[] = []) => (self as unknown as Worker).postMessage(m, transfer)

async function handle(m: ToWorker) {
    if (m.type === 'init') {
        if (m.gen !== gen) return post({ type: 'skip', id: m.id })
        initSent = false
        transcoder?.destroy()
        transcoder = null
        try {
            await loadGlue()
            // A fresh transcoder per run: a seek starts at a key frame with a clean decoder.
            const t = new SegmentTranscoder({ wasmBinaryUrl })
            await t.init()
            await t.processInitSegment(m.bytes)
            if (m.gen === gen) transcoder = t
            post({ type: 'ready', id: m.id })
        } catch (e) {
            post({ type: 'error', id: m.id, message: String((e as Error)?.message ?? e) })
        }
        return
    }
    if (m.gen !== gen || !transcoder) return post({ type: 'skip', id: m.id })
    try {
        const out = await transcoder.processMediaSegment(m.bytes)
        if (m.gen !== gen) return post({ type: 'skip', id: m.id })
        const result = transcoder.initResult
        const init = !initSent && result ? result.initSegment.slice() : null
        if (init) initSent = true
        const bytes = out && out.byteLength ? out.slice() : null
        // Seconds of video made per second of work, for the player's "too slow here" notice.
        const perf = transcoder.lastPerfStats
        const busy = perf ? perf.demuxMs + perf.decodeMs + perf.encodeMs : 0
        const speed = perf && busy > 0 ? perf.segDurMs / busy : 0
        post({ type: 'out', id: m.id, init, codec: result?.codec ?? 'avc1.640033', bytes, speed }, [init?.buffer, bytes?.buffer].filter(Boolean) as Transferable[])
    } catch (e) {
        post({ type: 'error', id: m.id, message: String((e as Error)?.message ?? e) })
    }
}

self.onmessage = (e: MessageEvent<ToWorker>) => {
    // An init supersedes what is still queued from the run before it.
    if (e.data.type === 'init') gen = e.data.gen
    queue = queue.then(() => handle(e.data)).catch((err) => console.warn('[hevc worker]', err))
}
