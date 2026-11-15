import { AudioSample, CustomAudioDecoder, registerDecoder, type AudioCodec, type EncodedPacket } from 'mediabunny'
import { joinSound, SAMPLE_FORMATS } from './libavframes.ts'
import { startLibav, type LibAV } from './libavload.ts'

/**
 * Sound decoders from FFmpeg (the `tmplayer-sound` libav.js build) for a track mediabunny reads but
 * this browser cannot decode: the TVs have no WebCodecs at all, Safari before 26 no audio decoder,
 * and no browser takes MPEG audio Layer I or II as "MP3". Registered with mediabunny only after the
 * browser said no to a codec, so the browser's own decoder stays first; AC-3, E-AC-3 and DTS keep
 * their @mediabunny packages. The decoded sound is then encoded as the remux engine does.
 */
const FFMPEG: Partial<Record<AudioCodec, string>> = { aac: 'aac', mp3: 'mp3', opus: 'opus', vorbis: 'vorbis', flac: 'flac' }

/** The codecs this browser turned down, which these decoders take. */
const taken = new Set<AudioCodec>()

const debug = typeof location !== 'undefined' && (location.hostname === 'localhost' || location.hostname === '127.0.0.1')

/** How the shared instance starts; node tests hand in their own (libavsound.test.ts). */
let starter: () => Promise<LibAV> = () => startLibav('tmplayer-sound')
export function setLibavSoundStarter(start: () => Promise<LibAV>) {
    starter = start
}

/** One libav.js worker for every open decoder; it ends 30 s after the last one closes. */
let shared: Promise<LibAV> | null = null
let users = 0
let idle: ReturnType<typeof setTimeout> | null = null

async function take(): Promise<LibAV> {
    users++
    if (idle) clearTimeout(idle)
    idle = null
    shared ??= starter().catch((e) => {
        shared = null
        throw e
    })
    return shared
}

function give() {
    users = Math.max(0, users - 1)
    if (users || idle) return
    idle = setTimeout(() => {
        idle = null
        if (users) return
        const s = shared
        shared = null
        void s?.then((L) => L.terminate()).catch(() => {})
    }, 30_000)
}

class LibavAudioDecoder extends CustomAudioDecoder {
    private L: LibAV | null = null
    private ctx: { c: number; pkt: number; frame: number } | null = null
    /** Calls into the worker one after another (a decode may still run when the next arrives). */
    private queue: Promise<unknown> = Promise.resolve()
    private next = 0

    static supports(codec: AudioCodec): boolean {
        return taken.has(codec)
    }

    async init() {
        const L = await take()
        this.L = L
        const name = FFMPEG[this.codec]
        if (!name) throw new Error(`no FFmpeg decoder for ${this.codec}`)
        const desc = await L.avcodec_descriptor_get_by_name(name)
        const codecId = desc ? await L.AVCodecDescriptor_id(desc) : 0
        if (!codecId) throw new Error(`no FFmpeg decoder for ${this.codec}`)
        const d = this.config.description
        const extradata = !d ? undefined : ArrayBuffer.isView(d) ? new Uint8Array(d.buffer, d.byteOffset, d.byteLength) : new Uint8Array(d)
        const [, c, pkt, frame] = await L.ff_init_decoder(name, {
            codecpar: {
                codec_type: L.AVMEDIA_TYPE_AUDIO,
                codec_id: codecId,
                format: -1,
                sample_rate: this.config.sampleRate,
                channels: this.config.numberOfChannels,
                ...(extradata?.length ? { extradata: extradata.slice() } : {}),
            },
            time_base: [1, this.config.sampleRate],
        })
        this.ctx = { c, pkt, frame }
    }

    decode(packet: EncodedPacket) {
        const data = packet.data.slice()
        const pts = Math.round(packet.timestamp * this.config.sampleRate)
        return this.run([{ data, pts, ptshi: pts < 0 ? -1 : 0 }], false)
    }

    flush() {
        return this.run([], true)
    }

    async close() {
        const L = this.L
        const ctx = this.ctx
        this.ctx = null
        this.L = null
        if (L && ctx) await this.queue.then(() => L.ff_free_decoder(ctx.c, ctx.pkt, ctx.frame)).catch(() => {})
        if (L) give()
    }

    private run(packets: { data: Uint8Array; pts: number; ptshi: number }[], fin: boolean): Promise<void> {
        const step = this.queue.then(async () => {
            const L = this.L
            const ctx = this.ctx
            if (!L || !ctx) return
            const frames = await L.ff_decode_multi(ctx.c, ctx.pkt, ctx.frame, packets, { fin, ignoreErrors: true })
            for (const f of frames) {
                const format = SAMPLE_FORMATS[f.format as keyof typeof SAMPLE_FORMATS]
                const data = joinSound(f.data)
                const rate = f.sample_rate || this.config.sampleRate
                const channels = f.channels ?? 0
                if (!format || !data || !channels || !f.nb_samples) continue
                const at = f.pts != null && !(f.pts === L.AV_NOPTS_VALUE_LO && f.ptshi === L.AV_NOPTS_VALUE_HI) ? L.i64tof64(f.pts, f.ptshi ?? 0) / this.config.sampleRate : this.next
                this.next = at + f.nb_samples / rate
                this.onSample(new AudioSample({ data, format, numberOfChannels: channels, sampleRate: rate, timestamp: at }))
            }
        })
        this.queue = step.catch(() => {})
        return step
    }
}

let registered = false

/**
 * Takes `codec` into FFmpeg's decoders when the browser cannot decode it; false when this build has
 * no decoder for it. The decoders load on the first decode, not here.
 */
export function useLibavSound(codec: AudioCodec): boolean {
    if (!FFMPEG[codec]) return false
    taken.add(codec)
    if (!registered) {
        registered = true
        registerDecoder(LibavAudioDecoder)
    }
    if (debug) console.debug('[libav] sound decoded by FFmpeg:', codec)
    return true
}
