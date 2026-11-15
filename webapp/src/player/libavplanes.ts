// What the canvas engine (libavcanvas.ts) takes from libav.js frames: a picture as three planes a
// texture takes, and sound as one float array per channel. No browser or libav.js here, so
// `pnpm test:player` runs it in node (libavplanes.test.ts).
import { toI420, type PackedFrame, type PixelFormats } from './libavframes.ts'

/** A picture as three planes a texture takes: each tight (stride equals width), chroma at half size. */
export interface Planes {
    y: Uint8Array
    u: Uint8Array
    v: Uint8Array
    width: number
    height: number
    cw: number
    ch: number
    /** Full range (JPEG) levels, not studio range. */
    full: boolean
}

/**
 * The picture as Y, U and V planes. 4:2:0 comes through as views of libav.js's own buffer when its
 * rows are tight, and row by row when they are padded; any other layout goes through `toI420`
 * (cropped to an even size). Null for what that does not know either.
 */
export function yuvPlanes(f: PackedFrame, pf: PixelFormats): Planes | null {
    const w = f.width
    const h = f.height
    if (w > 0 && h > 0 && f.data instanceof Uint8Array && (f.format === pf.AV_PIX_FMT_YUV420P || f.format === pf.AV_PIX_FMT_YUVJ420P)) {
        const cw = (w + 1) >> 1
        const ch = (h + 1) >> 1
        const sizes = [
            { s: w, rows: h },
            { s: cw, rows: ch },
            { s: cw, rows: ch },
        ]
        let at = 0
        const layout = f.layout && f.layout.length >= 3 ? f.layout : sizes.map(({ s, rows }) => ({ offset: (at += s * rows) - s * rows, stride: s }))
        const out: Uint8Array[] = []
        for (let i = 0; i < 3; i++) {
            const { offset, stride } = layout[i]
            const { s, rows } = sizes[i]
            if (offset < 0 || stride < s || offset + stride * (rows - 1) + s > f.data.length) return null
            if (stride === s) out.push(f.data.subarray(offset, offset + s * rows))
            else {
                const tight = new Uint8Array(s * rows)
                for (let y = 0; y < rows; y++) tight.set(f.data.subarray(offset + y * stride, offset + y * stride + s), y * s)
                out.push(tight)
            }
        }
        return { y: out[0], u: out[1], v: out[2], width: w, height: h, cw, ch, full: f.format === pf.AV_PIX_FMT_YUVJ420P }
    }
    const i = toI420(f, pf)
    if (!i) return null
    const cw = i.width >> 1
    const ch = i.height >> 1
    const U = i.width * i.height
    return {
        y: i.data.subarray(0, U),
        u: i.data.subarray(U, U + cw * ch),
        v: i.data.subarray(U + cw * ch),
        width: i.width,
        height: i.height,
        cw,
        ch,
        full: f.format === pf.AV_PIX_FMT_YUVJ422P || f.format === pf.AV_PIX_FMT_YUVJ444P,
    }
}

/** One channel's samples as floats from -1 to 1, from any of libav's sample types. */
function asFloat(a: ArrayLike<number>, format: number): Float32Array {
    const out = new Float32Array(a.length)
    // u8 (0 and 5) centres on 128, s16 and s32 are signed; f32 and f64 are as they are.
    if (format === 0 || format === 5) for (let i = 0; i < a.length; i++) out[i] = (a[i] - 128) / 128
    else if (format === 1 || format === 6) for (let i = 0; i < a.length; i++) out[i] = a[i] / 32768
    else if (format === 2 || format === 7) for (let i = 0; i < a.length; i++) out[i] = a[i] / 2147483648
    else for (let i = 0; i < a.length; i++) out[i] = a[i]
    return out
}

/**
 * A decoded sound frame as one Float32Array per channel (what an AudioBuffer takes). `data` is
 * a typed array for interleaved samples and an array of them, one per channel, for planar ones.
 * Null for a format or shape it does not know.
 */
export function soundPlanes(format: number, channels: number, samples: number, data: unknown): Float32Array[] | null {
    if (!(channels > 0) || !(samples > 0) || channels > 32 || !(format >= 0 && format <= 9)) return null
    if (format >= 5) {
        if (!Array.isArray(data) || data.length < channels) return null
        const out: Float32Array[] = []
        for (let c = 0; c < channels; c++) {
            const p = data[c] as ArrayLike<number> | undefined
            if (!p || p.length < samples) return null
            out.push(asFloat(p.length === samples ? p : Array.prototype.slice.call(p, 0, samples), format))
        }
        return out
    }
    const d = data as ArrayLike<number> | null
    if (!d || Array.isArray(data) || typeof d.length !== 'number' || d.length < samples * channels) return null
    const all = asFloat(d, format)
    const out = Array.from({ length: channels }, () => new Float32Array(samples))
    for (let i = 0; i < samples; i++) for (let c = 0; c < channels; c++) out[c][i] = all[i * channels + c]
    return out
}

/**
 * At most two channels, as the TV's output takes them: 5.1 and 7.1 are folded down (centre and
 * surrounds at -3 dB, the low channel left out), others keep their first two.
 */
export function downmix(planes: Float32Array[]): Float32Array[] {
    const n = planes.length
    if (n <= 2) return planes
    const len = planes[0].length
    const L = new Float32Array(len)
    const R = new Float32Array(len)
    const k = Math.SQRT1_2
    if (n === 6 || n === 8) {
        // FFmpeg's order: L R C LFE BL BR (SL SR).
        const sl = n === 6 ? 4 : 6
        for (let i = 0; i < len; i++) {
            const c = planes[2][i] * k
            L[i] = (planes[0][i] + c + planes[sl][i] * k) / 2.4
            R[i] = (planes[1][i] + c + planes[sl + 1][i] * k) / 2.4
        }
        return [L, R]
    }
    return [planes[0], planes[1]]
}
