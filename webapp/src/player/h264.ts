/**
 * Presentation order for H.264 kept in AVI, which stores decode order and decode times only: a file
 * with B-frames would show its frames out of order if copied with those times. Each frame's picture
 * order count (POC) is read from its slice header (sequence and picture parameter sets as the stream
 * carries them, Annex B), and a frame's place among the frames since the last IDR frame gives its
 * time. POC type 0 is read (what encoders write with B-frames); types 1 and 2 keep decode order.
 */

class Bits {
    private pos = 0
    constructor(private d: Uint8Array) {}
    u(n: number): number {
        let v = 0
        for (let i = 0; i < n; i++) {
            const byte = this.d[this.pos >> 3] ?? 0
            v = v * 2 + ((byte >> (7 - (this.pos & 7))) & 1)
            this.pos++
        }
        return v
    }
    ue(): number {
        let zeros = 0
        while (this.u(1) === 0 && zeros < 32) zeros++
        return 2 ** zeros - 1 + this.u(zeros)
    }
    se(): number {
        const k = this.ue()
        return k & 1 ? (k + 1) / 2 : -(k / 2)
    }
}

/** A NAL unit's payload without emulation prevention bytes (00 00 03). */
function rbsp(nal: Uint8Array): Uint8Array {
    const out = new Uint8Array(nal.length)
    let n = 0
    let zeros = 0
    for (const b of nal) {
        if (zeros >= 2 && b === 3) {
            zeros = 0
            continue
        }
        out[n++] = b
        zeros = b === 0 ? zeros + 1 : 0
    }
    return out.subarray(0, n)
}

/** The NAL units of an Annex B access unit. */
function* nals(data: Uint8Array): Generator<Uint8Array> {
    let start = -1
    for (let i = 0; i + 2 < data.length; i++) {
        if (data[i] === 0 && data[i + 1] === 0 && data[i + 2] === 1) {
            if (start >= 0) yield data.subarray(start, data[i - 1] === 0 ? i - 1 : i)
            start = i + 3
            i += 2
        }
    }
    if (start >= 0) yield data.subarray(start)
}

interface Sps {
    separateColour: boolean
    frameNumBits: number
    pocType: number
    pocLsbBits: number
    frameMbsOnly: boolean
}

const HIGH_PROFILES = new Set([100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135])

function parseSps(nal: Uint8Array): [number, Sps] {
    const b = new Bits(rbsp(nal.subarray(1)))
    const profile = b.u(8)
    b.u(16)
    const id = b.ue()
    let separateColour = false
    if (HIGH_PROFILES.has(profile)) {
        const chroma = b.ue()
        if (chroma === 3) separateColour = b.u(1) === 1
        b.ue()
        b.ue()
        b.u(1)
        if (b.u(1)) {
            for (let i = 0; i < (chroma === 3 ? 12 : 8); i++) {
                if (!b.u(1)) continue
                let last = 8
                let next = 8
                for (let j = 0; j < (i < 6 ? 16 : 64); j++) {
                    if (next !== 0) next = (last + b.se() + 256) % 256
                    last = next === 0 ? last : next
                }
            }
        }
    }
    const frameNumBits = b.ue() + 4
    const pocType = b.ue()
    let pocLsbBits = 0
    if (pocType === 0) pocLsbBits = b.ue() + 4
    else if (pocType === 1) {
        b.u(1)
        b.se()
        b.se()
        const n = b.ue()
        for (let i = 0; i < n; i++) b.se()
    }
    b.ue()
    b.u(1)
    b.ue()
    b.ue()
    const frameMbsOnly = b.u(1) === 1
    return [id, { separateColour, frameNumBits, pocType, pocLsbBits, frameMbsOnly }]
}

/**
 * Gives each copied frame, handed over in decode order, its presentation time. Frames are held
 * until `WINDOW` later ones are in (no stream reorders further), or until `flush`.
 */
export class H264Order {
    private sps = new Map<number, Sps>()
    private ppsSps = new Map<number, number>()
    private prevMsb = 0
    private prevLsb = 0
    /** POCs of the frames since the last IDR frame (already given a time). */
    private segment: number[] = []
    private segmentStart = 0
    private held: { data: Uint8Array; key: boolean; dts: number; poc: number | null; idr: boolean }[] = []
    private static readonly WINDOW = 16

    constructor(private frameLength: number) {}

    /** Adds a frame; returns the frames whose times are now known, as [data, key, pts]. */
    push(data: Uint8Array, key: boolean, dts: number): [Uint8Array, boolean, number][] {
        const [poc, idr] = this.poc(data)
        this.held.push({ data, key, dts, poc, idr })
        return this.release(false)
    }

    flush(): [Uint8Array, boolean, number][] {
        return this.release(true)
    }

    private release(all: boolean): [Uint8Array, boolean, number][] {
        const out: [Uint8Array, boolean, number][] = []
        while (this.held.length && (all || this.held.length > H264Order.WINDOW)) {
            const f = this.held.shift()!
            if (f.idr || !this.segment.length) {
                this.segment = []
                this.segmentStart = f.dts
            }
            if (f.poc === null) {
                out.push([f.data, f.key, f.dts])
                continue
            }
            // Its place in showing order: earlier frames of the same run, and later ones up to the next IDR, with a smaller POC.
            let rank = 0
            for (const p of this.segment) if (p < f.poc) rank++
            for (const g of this.held) {
                if (g.idr) break
                if (g.poc !== null && g.poc < f.poc) rank++
            }
            this.segment.push(f.poc)
            out.push([f.data, f.key, this.segmentStart + rank * this.frameLength])
        }
        return out
    }

    /** The frame's POC and whether it is an IDR frame; null POC when it cannot be read. */
    private poc(data: Uint8Array): [number | null, boolean] {
        for (const nal of nals(data)) {
            const type = nal[0] & 0x1f
            const refIdc = (nal[0] >> 5) & 3
            if (type === 7) {
                const [id, sps] = parseSps(nal)
                this.sps.set(id, sps)
            } else if (type === 8) {
                const b = new Bits(rbsp(nal.subarray(1)))
                const id = b.ue()
                this.ppsSps.set(id, b.ue())
            } else if (type === 1 || type === 5) {
                const b = new Bits(rbsp(nal.subarray(1)))
                b.ue()
                b.ue()
                const sps = this.sps.get(this.ppsSps.get(b.ue()) ?? -1)
                if (!sps) return [null, type === 5]
                if (sps.separateColour) b.u(2)
                const frameNum = b.u(sps.frameNumBits)
                if (!sps.frameMbsOnly && b.u(1)) b.u(1)
                if (type === 5) {
                    b.ue()
                    this.prevMsb = 0
                    this.prevLsb = 0
                }
                void frameNum
                // Type 2 shows frames in decode order; type 1 is left there too.
                if (sps.pocType !== 0) return [null, type === 5]
                const lsb = b.u(sps.pocLsbBits)
                const max = 2 ** sps.pocLsbBits
                let msb = this.prevMsb
                if (lsb < this.prevLsb && this.prevLsb - lsb >= max / 2) msb += max
                else if (lsb > this.prevLsb && lsb - this.prevLsb > max / 2) msb -= max
                if (refIdc !== 0) {
                    this.prevMsb = msb
                    this.prevLsb = lsb
                }
                return [msb + lsb, type === 5]
            }
        }
        return [null, false]
    }
}
