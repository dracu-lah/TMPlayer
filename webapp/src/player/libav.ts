import {
    AudioSample,
    AudioSampleSource,
    canEncodeVideo,
    EncodedPacket,
    EncodedVideoPacketSource,
    Mp4OutputFormat,
    Output,
    QUALITY_HIGH,
    VideoSample,
    VideoSampleSource,
    type AudioCodec,
} from 'mediabunny'
import type { TgFile } from '../stream/reader'

type AudioSampleFormat = Extract<ConstructorParameters<typeof AudioSample>[0], { numberOfChannels: number; data: unknown }>['format']
import type * as LibAVTypes from '../../libav/libav.types'
import { H264Order } from './h264'
import { codecName, encoderFor, FeedEngine, Unplayable, type EngineEvents, type SoundTrack } from './engines'

type LibAV = LibAVTypes.LibAV
type Frame = LibAVTypes.Frame
type Packet = LibAVTypes.Packet

/**
 * TMPlayer's own libav.js build (webapp/libav/build.sh), served from public/libav/ and loaded only
 * for a file mediabunny cannot open. It runs in its own worker.
 */
const BASE = '/libav'
const VARIANT = 'tmplayer'
const VERSION = '6.10.9.0'

/** The containers (as `sniff` names them) this engine opens. */
export const LIBAV_FORMATS = new Set(['AVI'])

const debug = location.hostname === 'localhost'
const phone = matchMedia('(pointer: coarse)').matches

/** FFmpeg's picture codec names in plain words, for "needs the app" and the readout. */
const VIDEO_NAMES: Record<string, string> = {
    mpeg4: 'MPEG-4 Part 2 (DivX, Xvid)',
    msmpeg4v1: 'MS MPEG-4 v1',
    msmpeg4v2: 'MS MPEG-4 v2',
    msmpeg4v3: 'MS MPEG-4 v3 (DivX 3)',
    h263: 'H.263',
    h264: 'H.264',
    hevc: 'HEVC (H.265)',
    mpeg1video: 'MPEG-1',
    mpeg2video: 'MPEG-2',
    mjpeg: 'Motion JPEG',
    wmv1: 'WMV 7',
    wmv2: 'WMV 8',
    wmv3: 'WMV 9',
    vc1: 'VC-1',
    vp6f: 'VP6',
    cinepak: 'Cinepak',
    indeo5: 'Indeo 5',
    rawvideo: 'uncompressed',
}

/** FFmpeg's sound codec names that mediabunny also knows, so the menu names them the same way. */
const SOUND_CODECS: Record<string, AudioCodec> = { mp3: 'mp3', ac3: 'ac3', eac3: 'eac3', aac: 'aac', dts: 'dts', flac: 'flac', opus: 'opus', vorbis: 'vorbis' }
const SOUND_NAMES: Record<string, string> = {
    mp2: 'MPEG Layer 2',
    pcm_s16le: 'PCM',
    pcm_u8: 'PCM',
    adpcm_ms: 'MS ADPCM',
    adpcm_ima_wav: 'IMA ADPCM',
    wmav2: 'WMA',
}

/** libav's AVSampleFormat numbers as mediabunny's formats (doubles are turned into floats). */
const SAMPLE_FORMATS: Record<number, AudioSampleFormat> = {
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
}
const YUV420P = 0
const YUVJ420P = 12
const AV_PKT_FLAG_KEY = 1

/** A sound track libav.js reads, in the shape the player's menu shows. */
export interface LibavSound extends SoundTrack {
    readonly index: number
    readonly decodable: boolean
}

interface Picture {
    index: number
    codecId: number
    codecpar: number
    name: string
    tb: [number, number]
    width: number
    height: number
    /** H.264 is handed over as it is; everything else is decoded here and encoded again by the browser. */
    copy: boolean
}

let loading: Promise<{ LibAV: (opts: object) => Promise<LibAV> }> | null = null

async function startLibav(): Promise<LibAV> {
    // A failed load (offline) is tried again next time.
    loading ??= import(/* @vite-ignore */ `${BASE}/libav-${VERSION}-${VARIANT}.mjs`).then(
        (m) => m.default ?? m,
        (e) => {
            loading = null
            throw e
        },
    )
    const ns = await loading
    return ns.LibAV({ base: BASE, variant: VARIANT })
}

const seconds = (lo: number, hi: number, tb: [number, number], L: LibAV): number | null => {
    if (hi === L.AV_NOPTS_VALUE_HI && lo === L.AV_NOPTS_VALUE_LO) return null
    return (L.i64tof64(lo, hi) * tb[0]) / tb[1]
}

/** The H.264 codec string from the stream's first sequence parameter set (Annex B, as AVI keeps it). */
function avcCodec(data: Uint8Array): string | null {
    for (let i = 0; i + 7 < data.length; i++) {
        if (data[i] !== 0 || data[i + 1] !== 0 || data[i + 2] !== 1) continue
        if ((data[i + 3] & 0x1f) !== 7) continue
        const hex = (b: number) => b.toString(16).padStart(2, '0')
        return `avc1.${hex(data[i + 4])}${hex(data[i + 5])}${hex(data[i + 6])}`
    }
    return null
}

/** The browser's encoder for decoded pictures: H.264 everywhere it is offered, else VP9 in MP4. */
async function pictureEncoder(width: number, height: number): Promise<'avc' | 'vp9' | null> {
    const MS = window.ManagedMediaSource ?? window.MediaSource
    if (!MS) return null
    if (MS.isTypeSupported('video/mp4; codecs="avc1.4d401f"') && (await canEncodeVideo('avc', { width, height, bitrate: QUALITY_HIGH }))) return 'avc'
    if (MS.isTypeSupported('video/mp4; codecs="vp09.00.31.08"') && (await canEncodeVideo('vp9', { width, height, bitrate: QUALITY_HIGH }))) return 'vp9'
    return null
}

/**
 * Plays what mediabunny cannot open (AVI) with libav.js: the file is read in a worker by FFmpeg's
 * demuxer; an H.264 picture is copied, any other picture is decoded there (MPEG-4 Part 2, DivX 3)
 * and encoded again by the browser's own encoder (WebCodecs, H.264 at the same size); the sound is
 * decoded there and encoded as in the remux engine. The result is fragmented MP4 in Media Source,
 * so the browser's own player, full screen, picture in picture and lock screen keys stay.
 *
 * Built with `LibavEngine.open`, which reads the header first and says `Unplayable` when the
 * picture cannot be played (no decoder in the build, no encoder in the browser).
 */
export class LibavEngine extends FeedEngine {
    readonly name = 'libav'
    detail = ''
    private output: Output | null = null
    private pkt = 0
    private vdec: { c: number; pkt: number; frame: number } | null = null
    private adec: { c: number; pkt: number; frame: number; index: number } | null = null
    private encoder: 'avc' | 'vp9' | null = null
    /** Each sound stream's decoder inputs, by stream index. */
    private streams = new Map<number, { codecId: number; codecpar: number; tb: [number, number] }>()

    private constructor(
        video: HTMLVideoElement,
        private L: LibAV,
        private fmt: number,
        private pic: Picture,
        readonly sounds: LibavSound[],
        private sound: LibavSound | null,
        readonly duration: number,
        readonly container: string,
        ev: EngineEvents,
    ) {
        super(video, duration, ev)
    }

    /** The picture codec in plain words. */
    get videoCodec() {
        return VIDEO_NAMES[this.pic.name] ?? this.pic.name
    }

    /**
     * Reads the header of `file` and plays it from `start` with the first sound it decodes; with
     * `start` null it only reads the header (the survey), and the caller destroys it.
     */
    static async open(file: TgFile, video: HTMLVideoElement, container: string, durationHint: number, start: number | null, ev: EngineEvents): Promise<LibavEngine> {
        const t0 = performance.now()
        const L = await startLibav()
        /** A read that failed (a lost connection), so the failure is not taken for a damaged file. */
        let readError: unknown = null
        try {
            const name = `in-${file.key.replace(/[^\w.-]/g, '_')}`
            L.onblockread = async (n: string, pos: number, len: number) => {
                const end = Math.min(file.size, pos + len)
                let data: Uint8Array = new Uint8Array(0)
                if (pos < file.size) {
                    try {
                        data = await file.read(pos, end)
                    } catch (e) {
                        // Passed through libav's read, so the run fails with it (the player waits for the connection).
                        readError = e
                        throw e
                    }
                }
                await L.ff_block_reader_dev_send(n, pos, data)
            }
            await L.mkblockreaderdev(name, file.size)
            const [fmt, streams] = await L.ff_init_demuxer_file(name)
            const v = streams.find((s) => s.codec_type === L.AVMEDIA_TYPE_VIDEO)
            if (!v) throw new Unplayable({ kind: 'no-picture' })
            const vname = await L.avcodec_get_name(v.codec_id)
            const vpar = await L.ff_copyout_codecpar(v.codecpar)
            const pic: Picture = {
                index: v.index,
                codecId: v.codec_id,
                codecpar: v.codecpar,
                name: vname,
                tb: [v.time_base_num, v.time_base_den],
                width: vpar.width ?? 0,
                height: vpar.height ?? 0,
                copy: vname === 'h264',
            }
            const codecWords = VIDEO_NAMES[vname] ?? vname
            if (pic.copy) {
                const MS = window.ManagedMediaSource ?? window.MediaSource
                if (!MS?.isTypeSupported('video/mp4; codecs="avc1.4d401f"')) throw new Unplayable({ kind: 'picture', codec: codecWords })
            } else if (!(await L.avcodec_find_decoder(v.codec_id))) throw new Unplayable({ kind: 'picture', codec: codecWords })

            const sounds: LibavSound[] = []
            const inputs = new Map<number, { codecId: number; codecpar: number; tb: [number, number] }>()
            for (const s of streams) {
                if (s.codec_type !== L.AVMEDIA_TYPE_AUDIO) continue
                const n = await L.avcodec_get_name(s.codec_id)
                const par = await L.ff_copyout_codecpar(s.codecpar)
                sounds.push({
                    index: s.index,
                    codec: SOUND_CODECS[n] ?? null,
                    internalCodecId: SOUND_NAMES[n] ?? n,
                    languageCode: 'und',
                    name: null,
                    numberOfChannels: par.channels ?? 0,
                    decodable: !!(await L.avcodec_find_decoder(s.codec_id)),
                })
                inputs.set(s.index, { codecId: s.codec_id, codecpar: s.codecpar, tb: [s.time_base_num, s.time_base_den] })
            }
            const lengths = streams.map((s) => s.duration).filter((d) => d > 0 && isFinite(d))
            const duration = lengths.length ? Math.max(...lengths) : durationHint
            const engine = new LibavEngine(video, L, fmt, pic, sounds, null, duration, container, ev)
            engine.streams = inputs
            engine.sound = sounds.find((t) => t.decodable) ?? null
            if (!pic.copy) {
                engine.encoder = await pictureEncoder(pic.width || 640, pic.height || 360)
                if (!engine.encoder) throw new Unplayable({ kind: 'picture', codec: codecWords })
            }
            engine.pkt = await L.av_packet_alloc()
            if (debug) console.debug('[libav] opened', container, vname, sounds.map((s) => s.internalCodecId), Math.round(performance.now() - t0), 'ms')
            if (start !== null) engine.begin(start)
            return engine
        } catch (e) {
            L.terminate()
            if (readError || e instanceof Unplayable) throw readError ?? e
            // FFmpeg could not make sense of it: a damaged or unusual file.
            if (debug) console.debug('[libav] could not open', e)
            throw new Unplayable({ kind: 'format', format: container })
        }
    }

    async setAudio(track: SoundTrack): Promise<boolean> {
        const sound = this.sounds.find((s) => s === track)
        if (!sound?.decodable || this.destroyed) return false
        if (sound === this.sound) return true
        this.sound = sound
        this.again()
        return true
    }

    destroy() {
        super.destroy()
        // Whatever libav.js was doing is dropped with its worker.
        this.L.terminate()
    }

    /** The old output is let go without waiting (its encoder's flush took up to a second); its run sees `gen` and ends. */
    protected async stop() {
        const output = this.output
        this.output = null
        if (output && output.state !== 'finalized' && output.state !== 'canceled') output.cancel().catch(() => {})
    }

    /** The picture's decoder once, and the chosen sound's (another track frees the last one's). */
    private async openDecoders() {
        const L = this.L
        if (!this.pic.copy && !this.vdec) {
            const [, c, pkt, frame] = await L.ff_init_decoder(this.pic.codecId, { codecpar: this.pic.codecpar, time_base: this.pic.tb })
            this.vdec = { c, pkt, frame }
        }
        const s = this.sound
        if (this.adec && this.adec.index !== s?.index) {
            await L.ff_free_decoder(this.adec.c, this.adec.pkt, this.adec.frame)
            this.adec = null
        }
        const st = s ? this.streams.get(s.index) : null
        if (s && st && !this.adec) {
            const [, c, pkt, frame] = await L.ff_init_decoder(st.codecId, { codecpar: st.codecpar, time_base: st.tb })
            this.adec = { c, pkt, frame, index: s.index }
            this.audioTb = st.tb
        }
    }

    protected async produce(gen: number, t: number) {
        try {
            await this.run(gen, t)
        } catch (e) {
            // Some encoders take a 5.1 setup and then fail on it (WebKit's AAC): once more in stereo.
            if (gen !== this.gen || this.stereo || (e as Error)?.name !== 'EncodingError') throw e
            if (debug) console.debug('[libav] encoding failed, sound again in stereo', e)
            this.stereo = true
            await this.stop()
            await this.run(gen, t)
        }
    }

    private stereo = false

    private async run(gen: number, t: number) {
        const L = this.L
        const pic = this.pic
        await this.openDecoders()
        if (gen !== this.gen) return
        const target = Math.max(0, Math.floor((t * pic.tb[1]) / pic.tb[0]))
        await L.avformat_seek_file_max(this.fmt, pic.index, target, 0, 0)
        if (this.vdec) await L.avcodec_flush_buffers(this.vdec.c)
        if (this.adec) await L.avcodec_flush_buffers(this.adec.c)
        if (gen !== this.gen) return

        const sound = this.sound
        const audioTb = this.audioTb
        const enc = sound && this.adec ? await encoderFor(Math.min(phone || this.stereo ? 2 : 6, sound.numberOfChannels || 2)) : null
        const picture = pic.copy ? 'picture copied (H.264)' : `picture decoded (${this.videoCodec}) and encoded to ${this.encoder === 'vp9' ? 'VP9' : 'H.264'}`
        const said = !sound ? 'no sound' : enc ? `sound converted from ${codecName(sound)} to ${enc.to.toUpperCase()}` : `sound not playable (${codecName(sound)})`
        this.detail = `Played with libav.js, ${picture}, ${said}`
        this.ev.onDetail?.(this.detail)
        /** The first key frame's time: output times count from it, and the buffer is placed there. */
        let from: number | null = null
        const frameLength = pic.tb[0] / pic.tb[1] <= 0.1 ? pic.tb[0] / pic.tb[1] : 1 / 25
        let output = null as Output | null
        let pictures = null as VideoSampleSource | EncodedVideoPacketSource | null
        let soundSource = null as AudioSampleSource | null
        let nextSound = 0
        let keyed = false
        const order = new H264Order(frameLength)
        /** Sound decoded before the picture's first kept frame, which may still belong after it. */
        let early: Frame[] = []

        const startOutput = async (first: { width: number; height: number; sar?: [number, number] }) => {
            output = new Output({
                format: new Mp4OutputFormat({ fastStart: 'fragmented', minimumFragmentDuration: 1 }),
                target: this.sink(gen, () => output!, () => from ?? 0, this.videoCodec),
            })
            if (pic.copy) pictures = new EncodedVideoPacketSource('avc')
            else {
                const [n, d] = first.sar ?? [0, 0]
                // Pixels that are not square (a DVD's 720 x 576) are drawn at their shown width.
                const width = n > 0 && d > 0 && n !== d ? Math.round((first.width * n) / d / 2) * 2 : undefined
                pictures = new VideoSampleSource({
                    codec: this.encoder!,
                    bitrate: QUALITY_HIGH,
                    keyFrameInterval: 2,
                    latencyMode: 'realtime',
                    sizeChangeBehavior: width ? 'fill' : 'contain',
                    ...(width ? { transform: { width, height: first.height, fit: 'fill' as const } } : {}),
                })
            }
            output.addVideoTrack(pictures, { frameRate: Math.round(1 / frameLength) })
            if (enc) {
                soundSource = new AudioSampleSource({
                    codec: enc.to,
                    bitrate: QUALITY_HIGH,
                    transform: { numberOfChannels: enc.channels, sampleRate: 48000 },
                })
                output.addAudioTrack(soundSource)
            }
            this.output = output
            await output.start()
        }

        const addSound = async (frames: Frame[]) => {
            for (const f of frames) {
                const at = (f.pts != null ? seconds(f.pts, f.ptshi ?? 0, audioTb, L) : null) ?? nextSound
                const rate = f.sample_rate ?? 48000
                nextSound = at + (f.nb_samples ?? 0) / rate
                if (from === null || at < from) continue
                const sample = audioSample(f, at - from)
                if (!sample || !soundSource) continue
                await soundSource.add(sample).finally(() => sample.close())
                if (gen !== this.gen) return
            }
        }

        for (;;) {
            if (gen !== this.gen) return
            const [res, packets] = await L.ff_read_frame_multi(this.fmt, this.pkt, { limit: 256 * 1024 })
            if (gen !== this.gen) return
            const end = res === L.AVERROR_EOF
            if (res < 0 && !end && res !== -L.EAGAIN) throw new Error(`libav read ${res}`)
            const vp: Packet[] = packets[pic.index] ?? []
            const ap: Packet[] = sound ? (packets[sound.index] ?? []) : []

            if (pic.copy) {
                const ready: [Uint8Array, boolean, number][] = []
                for (const p of vp) {
                    const dts = (p.dts != null ? seconds(p.dts, p.dtshi ?? 0, pic.tb, L) : null) ?? (p.pts != null ? seconds(p.pts, p.ptshi ?? 0, pic.tb, L) : null) ?? 0
                    const key = ((p.flags ?? 0) & AV_PKT_FLAG_KEY) !== 0
                    if (!keyed && !key) continue
                    keyed = true
                    ready.push(...order.push(p.data, key, dts))
                }
                if (end) ready.push(...order.flush())
                for (const [data, key, at] of ready) {
                    if (from === null) {
                        from = at
                        const codec = avcCodec(data)
                        if (!codec) throw new Unplayable({ kind: 'picture', codec: this.videoCodec })
                        await startOutput({ width: pic.width, height: pic.height })
                        if (gen !== this.gen) return
                        await (pictures as unknown as EncodedVideoPacketSource).add(new EncodedPacket(data, 'key', 0, frameLength), {
                            decoderConfig: { codec, codedWidth: pic.width, codedHeight: pic.height },
                        })
                        await addSound(early)
                        early = []
                        continue
                    }
                    if (at < from) continue
                    await (pictures as unknown as EncodedVideoPacketSource).add(new EncodedPacket(data, key ? 'key' : 'delta', at - from, frameLength))
                    if (gen !== this.gen) return
                }
            } else if (this.vdec) {
                let input = vp
                if (!keyed) {
                    // Decoding starts at a key frame, where the seek landed.
                    const k = input.findIndex((p) => ((p.flags ?? 0) & AV_PKT_FLAG_KEY) !== 0)
                    input = k < 0 ? [] : input.slice(k)
                    keyed = k >= 0
                }
                const frames: Frame[] = input.length || end ? await L.ff_decode_multi(this.vdec.c, this.vdec.pkt, this.vdec.frame, input, { fin: end, ignoreErrors: true, copyoutFrame: 'video_packed' }) : []
                if (gen !== this.gen) return
                for (const f of frames) {
                    if (f.format !== YUV420P && f.format !== YUVJ420P) throw new Unplayable({ kind: 'picture', codec: this.videoCodec })
                    const at =
                        (f.pts != null ? seconds(f.pts, f.ptshi ?? 0, pic.tb, L) : null) ??
                        (f.best_effort_timestamp != null ? seconds(f.best_effort_timestamp, f.best_effort_timestamphi ?? 0, pic.tb, L) : null)
                    if (at === null) continue
                    if (from === null) {
                        // Frames before the second asked for are decoded (they lead to it) but not
                        // encoded again: the encoder starts its own key frame on the first one kept.
                        if (at < t - frameLength / 2) continue
                        from = at
                        await startOutput({ width: f.width!, height: f.height!, sar: f.sample_aspect_ratio })
                        if (gen !== this.gen) return
                        await addSound(early)
                        early = []
                    }
                    if (at < from) continue
                    const sample = new VideoSample(f.data as Uint8Array, {
                        format: 'I420',
                        codedWidth: f.width!,
                        codedHeight: f.height!,
                        timestamp: at - from,
                        duration: frameLength,
                    })
                    await (pictures as unknown as VideoSampleSource).add(sample).finally(() => sample.close())
                    if (gen !== this.gen) return
                }
            }

            if (this.adec && enc && (ap.length || end)) {
                const frames: Frame[] = await L.ff_decode_multi(this.adec.c, this.adec.pkt, this.adec.frame, ap, { fin: end, ignoreErrors: true })
                if (gen !== this.gen) return
                if (from === null) early = [...early, ...frames].slice(-200)
                else await addSound(frames)
            }
            if (end) break
        }
        if (gen !== this.gen || !output) return
        await (output as Output).finalize()
    }

    private audioTb: [number, number] = [1, 1]
}

/** A decoded sound frame as mediabunny's sample, planes joined into one buffer. */
function audioSample(f: Frame, timestamp: number): AudioSample | null {
    const format = SAMPLE_FORMATS[f.format]
    const channels = f.channels ?? 0
    if (!format || !channels || !f.nb_samples) return null
    let data: Float32Array | Int16Array | Int32Array | Uint8Array
    if (Array.isArray(f.data)) {
        const planes = f.data as (Float32Array | Float64Array | Int16Array | Int32Array | Uint8Array)[]
        const n = planes[0].length
        const Ctor = planes[0] instanceof Float64Array ? Float32Array : (planes[0].constructor as typeof Float32Array)
        const joined = new Ctor(n * planes.length)
        planes.forEach((p, i) => joined.set(p as unknown as ArrayLike<number>, i * n))
        data = joined
    } else data = f.data instanceof Float64Array ? Float32Array.from(f.data) : f.data
    return new AudioSample({ data, format, numberOfChannels: channels, sampleRate: f.sample_rate ?? 48000, timestamp })
}
