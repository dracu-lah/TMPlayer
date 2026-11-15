import type * as LibAVTypes from '../../libav/libav.types'

/**
 * Starts TMPlayer's libav.js builds (webapp/libav/build.sh), served from public/libav/ and loaded
 * only when a file needs them, each in its own worker:
 *
 * - `tmplayer`: the player engine (libav.ts), containers mediabunny cannot open and SD pictures.
 * - `tmplayer-sound`: sound decoders alone (libavsound.ts), for a track mediabunny reads but this
 *   browser cannot decode.
 *
 * Engines without bulk memory (Chromium 74 and older: webOS 5, Tizen 2020) get the `.mvp.wasm`
 * lowered to the first WebAssembly.
 */
export type LibAV = LibAVTypes.LibAV
export type Variant = 'tmplayer' | 'tmplayer-sound'

/** Beside the page's own files: the TV build lives under /tv/ and ships its own copy (dist-tv/libav). */
// Under plain node (the unit tests) there is no Vite env: the page's root is the base then.
const BASE = `${(import.meta as { env?: { BASE_URL?: string } }).env?.BASE_URL ?? '/'}libav`
const VERSION = '6.10.9.0'

/** A module with one `memory.fill`: compiles only where bulk memory (Chromium 75) is there. */
const BULK_MEMORY = new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0, 1, 4, 1, 96, 0, 0, 3, 2, 1, 0, 5, 3, 1, 0, 1, 10, 13, 1, 11, 0, 65, 0, 65, 0, 65, 0, 252, 11, 0, 11])

/** Whether this engine's wasm has bulk memory, sign extension and saturating conversions. */
export function newerWasm(): boolean {
    try {
        return WebAssembly.validate(BULK_MEMORY)
    } catch {
        return false
    }
}

type Frontend = { LibAV: (opts: object) => Promise<LibAV> }
let frontend: Promise<Frontend> | null = null

/**
 * The classic frontend, a script that sets `LibAV` and starts classic workers, in every browser:
 * a module worker cannot be told apart beforehand (Chromium 79 reads the `type` it cannot honour),
 * and build.sh lowers these scripts to Chromium 68. The ES module build is for node's tests only.
 */
function loadFrontend(): Promise<Frontend> {
    return new Promise<Frontend>((resolve, reject) => {
        const w = window as unknown as { LibAV?: Frontend & { base?: string } }
        w.LibAV = { ...(w.LibAV ?? {}), base: BASE } as Frontend & { base: string }
        const s = document.createElement('script')
        s.src = `${BASE}/libav-${VERSION}-tmplayer.js`
        s.async = true
        s.onload = () => (w.LibAV?.LibAV ? resolve(w.LibAV) : reject(new Error('libav.js did not load')))
        s.onerror = () => reject(new Error('libav.js did not load'))
        document.head.appendChild(s)
    })
}

/** A new libav.js instance of `variant`, in its own worker. A failed load (offline) is tried again next time. */
export async function startLibav(variant: Variant = 'tmplayer'): Promise<LibAV> {
    frontend ??= loadFrontend().catch((e) => {
        frontend = null
        throw e
    })
    const f = await frontend
    return f.LibAV({ base: BASE, variant, ...(newerWasm() ? {} : { wasmurl: `${BASE}/libav-${VERSION}-${variant}.mvp.wasm` }) })
}
