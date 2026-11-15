// What libav.js hands back, made into what the browser's encoders and Media Source take. No
// browser or libav.js here, so `pnpm test:player` runs it in node (libavframes.test.ts).

/** FFmpeg's pixel formats this file converts, by their names in libav.js (`AV_PIX_FMT_*`). */
export interface PixelFormats {
    AV_PIX_FMT_YUV420P: number
    AV_PIX_FMT_YUVJ420P: number
    AV_PIX_FMT_YUV422P: number
    AV_PIX_FMT_YUVJ422P: number
    AV_PIX_FMT_YUV444P: number
    AV_PIX_FMT_YUVJ444P: number
    AV_PIX_FMT_YUV411P: number
    AV_PIX_FMT_YUV410P: number
    AV_PIX_FMT_GRAY8: number
    AV_PIX_FMT_RGB24: number
    AV_PIX_FMT_BGR24: number
    AV_PIX_FMT_RGBA: number
    AV_PIX_FMT_BGRA: number
    AV_PIX_FMT_ARGB: number
    AV_PIX_FMT_ABGR: number
}

/** A decoded picture as libav.js copies it out (`copyoutFrame: 'video_packed'`). */
export interface PackedFrame {
    format: number
    width: number
    height: number
    data: Uint8Array
    layout?: { offset: number; stride: number }[]
}

/** A picture in I420 (the one layout every WebCodecs encoder takes), at an even size. */
export interface I420 {
    data: Uint8Array
    width: number
    height: number
}

/**
 * The picture as I420, cropped to an even width and height (encoders refuse odd ones). Chroma is
 * averaged down (4:2:2, 4:4:4) or repeated up (4:1:1, 4:1:0); RGB is turned into BT.601 YUV at
 * studio range. Null for a layout it does not know (a palette, 10 bit), so the caller says so.
 */
export function toI420(f: PackedFrame, pf: PixelFormats): I420 | null {
    const w = f.width & ~1
    const h = f.height & ~1
    if (w <= 0 || h <= 0 || !(f.data instanceof Uint8Array)) return null
    const cw = w >> 1
    const ch = h >> 1
    const out = new Uint8Array(w * h + 2 * cw * ch)
    const U = w * h
    const V = U + cw * ch
    const fmt = f.format

    /** Each plane's offset and stride: libav.js's own, else packed tight with the given chroma size. */
    const planes = (n: number, cwIn: number, chIn: number) => {
        if (f.layout && f.layout.length >= n) return f.layout
        const sizes = [{ s: f.width, rows: f.height }, ...Array.from({ length: n - 1 }, () => ({ s: cwIn, rows: chIn }))]
        let at = 0
        return sizes.map(({ s, rows }) => {
            const p = { offset: at, stride: s }
            at += s * rows
            return p
        })
    }
    const fits = (p: { offset: number; stride: number }[], rows: number[]) => p.every((q, i) => q.offset >= 0 && q.stride >= 0 && q.offset + q.stride * rows[i] <= f.data.length)
    const d = f.data

    const yuv = (xs: number, ys: number) => {
        // Chroma at 1/xs across and 1/ys down.
        const cwIn = Math.ceil(f.width / xs)
        const chIn = Math.ceil(f.height / ys)
        const p = planes(3, cwIn, chIn)
        if (!fits(p, [h, chIn, chIn])) return null
        for (let y = 0; y < h; y++) out.set(d.subarray(p[0].offset + y * p[0].stride, p[0].offset + y * p[0].stride + w), y * w)
        for (let c = 1; c <= 2; c++) {
            const to = c === 1 ? U : V
            const { offset, stride } = p[c]
            for (let y = 0; y < ch; y++) {
                for (let x = 0; x < cw; x++) {
                    // The source chroma sample(s) under this 2 x 2 block of the picture.
                    const x0 = Math.floor((2 * x) / xs)
                    const x1 = Math.min(cwIn - 1, Math.floor((2 * x + 1) / xs))
                    const y0 = Math.floor((2 * y) / ys)
                    const y1 = Math.min(chIn - 1, Math.floor((2 * y + 1) / ys))
                    const a = d[offset + y0 * stride + x0] + d[offset + y0 * stride + x1] + d[offset + y1 * stride + x0] + d[offset + y1 * stride + x1]
                    out[to + y * cw + x] = (a + 2) >> 2
                }
            }
        }
        return out
    }

    if (fmt === pf.AV_PIX_FMT_YUV420P || fmt === pf.AV_PIX_FMT_YUVJ420P) {
        if (!yuv(2, 2)) return null
    } else if (fmt === pf.AV_PIX_FMT_YUV422P || fmt === pf.AV_PIX_FMT_YUVJ422P) {
        if (!yuv(2, 1)) return null
    } else if (fmt === pf.AV_PIX_FMT_YUV444P || fmt === pf.AV_PIX_FMT_YUVJ444P) {
        if (!yuv(1, 1)) return null
    } else if (fmt === pf.AV_PIX_FMT_YUV411P) {
        if (!yuv(4, 1)) return null
    } else if (fmt === pf.AV_PIX_FMT_YUV410P) {
        if (!yuv(4, 4)) return null
    } else if (fmt === pf.AV_PIX_FMT_GRAY8) {
        const p = planes(1, 0, 0)
        if (!fits(p, [h])) return null
        for (let y = 0; y < h; y++) out.set(d.subarray(p[0].offset + y * p[0].stride, p[0].offset + y * p[0].stride + w), y * w)
        out.fill(128, U)
    } else {
        // Packed RGB: where red, green and blue sit in each pixel, and its size.
        const rgb: Record<number, [number, number, number, number]> = {
            [pf.AV_PIX_FMT_RGB24]: [0, 1, 2, 3],
            [pf.AV_PIX_FMT_BGR24]: [2, 1, 0, 3],
            [pf.AV_PIX_FMT_RGBA]: [0, 1, 2, 4],
            [pf.AV_PIX_FMT_BGRA]: [2, 1, 0, 4],
            [pf.AV_PIX_FMT_ARGB]: [1, 2, 3, 4],
            [pf.AV_PIX_FMT_ABGR]: [3, 2, 1, 4],
        }
        const at = rgb[fmt]
        if (!at) return null
        const [r, g, b, size] = at
        const p = f.layout?.[0] ?? { offset: 0, stride: f.width * size }
        if (!fits([p], [h])) return null
        const lum = (i: number) => (66 * d[i + r] + 129 * d[i + g] + 25 * d[i + b] + 128) / 256 + 16
        for (let y = 0; y < h; y++) {
            for (let x = 0; x < w; x++) out[y * w + x] = lum(p.offset + y * p.stride + x * size)
        }
        for (let y = 0; y < ch; y++) {
            for (let x = 0; x < cw; x++) {
                let R = 0
                let G = 0
                let B = 0
                for (const [dx, dy] of [[0, 0], [1, 0], [0, 1], [1, 1]]) {
                    const i = p.offset + (2 * y + dy) * p.stride + (2 * x + dx) * size
                    R += d[i + r]
                    G += d[i + g]
                    B += d[i + b]
                }
                R /= 4
                G /= 4
                B /= 4
                out[U + y * cw + x] = (-38 * R - 74 * G + 112 * B + 128) / 256 + 128
                out[V + y * cw + x] = (112 * R - 94 * G - 18 * B + 128) / 256 + 128
            }
        }
    }
    return { data: out, width: w, height: h }
}

/** libav's AVSampleFormat numbers as mediabunny's formats (doubles are turned into floats). */
export const SAMPLE_FORMATS = {
    0: 'u8',
    1: 's16',
    2: 's32',
    3: 'f32',
    4: 'f32',
    5: 'u8-planar',
    6: 's16-planar',
    7: 's32-planar',
    8: 'f32-planar',
    9: 'f32-planar',
} as const

/** A decoded sound frame's samples in one buffer, planes one after the other, doubles as floats. */
export function joinSound(data: unknown): Float32Array | Int16Array | Int32Array | Uint8Array | null {
    if (Array.isArray(data)) {
        const planes = data as ArrayLike<number>[]
        if (!planes.length || !planes[0]) return null
        const n = planes[0].length
        if (planes.some((p) => !p || p.length !== n)) return null
        const first = planes[0]
        const Ctor = first instanceof Float64Array || first instanceof Float32Array ? Float32Array : first instanceof Int16Array ? Int16Array : first instanceof Int32Array ? Int32Array : first instanceof Uint8Array ? Uint8Array : null
        if (!Ctor) return null
        const joined = new Ctor(n * planes.length)
        planes.forEach((p, i) => joined.set(p, i * n))
        return joined
    }
    if (data instanceof Float64Array) return Float32Array.from(data)
    if (data instanceof Float32Array || data instanceof Int16Array || data instanceof Int32Array || data instanceof Uint8Array) return data
    return null
}

/**
 * The MPEG audio layer of a frame (1, 2 or 3) from its header, or null. mediabunny names every
 * MPEG audio track `mp3`, but a broadcast recording's Layer II is not MP3 to a browser's decoder.
 */
export function mpegAudioLayer(data: Uint8Array): 1 | 2 | 3 | null {
    for (let i = 0; i + 1 < Math.min(data.length, 4096); i++) {
        if (data[i] !== 0xff || (data[i + 1] & 0xe0) !== 0xe0) continue
        const bits = (data[i + 1] >> 1) & 3
        if (bits === 0) continue
        return (4 - bits) as 1 | 2 | 3
    }
    return null
}

/**
 * FFmpeg's decoder for a sound track mediabunny reads but cannot name (`codec` null), from its
 * Matroska codec id or MP4 sample entry; null when this build has none.
 */
export function soundDecoderFor(internalCodecId: unknown): string | null {
    if (typeof internalCodecId !== 'string') return null
    const id = internalCodecId
    const MATROSKA: [string, string][] = [
        ['A_TRUEHD', 'truehd'],
        ['A_MLP', 'mlp'],
        ['A_ALAC', 'alac'],
        ['A_MPEG/L2', 'mp2'],
        ['A_MPEG/L1', 'mp1'],
        ['A_REAL/COOK', 'cook'],
        ['A_REAL/14_4', 'ra_144'],
        ['A_REAL/28_8', 'ra_288'],
        ['A_REAL/SIPR', 'sipr'],
        ['A_REAL/ATRC', 'atrac3'],
        ['A_WAVPACK4', 'wavpack'],
        ['A_TTA1', 'tta'],
        ['A_QUICKTIME/QDM2', 'qdm2'],
        ['A_MS/ACM', 'acm'],
    ]
    for (const [prefix, name] of MATROSKA) if (id.startsWith(prefix)) return name
    // MP4 and QuickTime sample entries mediabunny does not know.
    const MP4: Record<string, string> = { alac: 'alac', samr: 'amrnb', sawb: 'amrwb', '.mp2': 'mp2', ima4: 'adpcm_ima_qt', Qclp: 'qcelp', sqcp: 'qcelp', mlpa: 'truehd', QDM2: 'qdm2', 'ms\0\x11': 'adpcm_ima_wav' }
    return MP4[id] ?? null
}

/**
 * The largest picture this device decodes in software and encodes again in real time, in pixels
 * (width times height). Measured on the AVI engine (session 16): 640 x 352 Xvid encodes at about 5
 * times real time on a laptop, a phone has about a third of that; TV boxes and 2 GB phones keep
 * to 480p. Larger pictures say they need the app instead of stuttering.
 */
export function softwarePixels(cores: number, memoryGb: number | undefined, phone: boolean): number {
    const low = cores <= 4 || (memoryGb !== undefined && memoryGb <= 2)
    if (low) return 854 * 480
    if (phone) return 1280 * 720
    return 1920 * 1088
}
