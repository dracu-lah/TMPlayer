import {
    ALL_FORMATS,
    canEncodeAudio,
    Conversion,
    CustomSource,
    EncodedPacketSink,
    Input,
    Mp4InputFormat,
    MpegTsInputFormat,
    Mp4OutputFormat,
    Output,
    QUALITY_HIGH,
    QuickTimeInputFormat,
    StreamTarget,
    UnsupportedInputFormatError,
    WebMInputFormat,
    type InputAudioTrack,
    type InputVideoTrack,
    type StreamTargetChunk,
} from 'mediabunny'
import type { TgFile } from '../stream/reader'
import type { HevcTranscoder, Transcoded } from './hevc'

export interface Probe {
    input: Input
    container: string
    video: InputVideoTrack | null
    audios: InputAudioTrack[]
    /** Length in the player's seconds (from `base`). */
    duration: number
    /**
     * The file's first timestamp, which the player counts as 0. An MPEG-TS file starts where its
     * broadcast clock stood (often 1.4 s or hours in); MP4 and MKV start at 0.
     */
    base: number
    mime: string
    /** The browser plays the file as it is, through the Service Worker. */
    native: boolean
    /** The picture can be remuxed to MP4 and decoded by this browser. */
    remuxable: boolean
    /** The picture codec in plain words, for messages. */
    videoCodec: string
    /** HEVC this browser cannot decode, turned into H.264 by hevc.js on the way (player/hevc.ts). */
    transcode: boolean
}

/** Why a file cannot play here: the page says "needs the app" with the reason. */
export type Why =
    | { kind: 'format'; format: string }
    | { kind: 'picture'; codec: string }
    | { kind: 'no-picture' }
    | { kind: 'no-mse' }

export class Unplayable extends Error {
    constructor(readonly why: Why) {
        super(`unplayable: ${JSON.stringify(why)}`)
    }
}

const debug = location.hostname === 'localhost'
const mark = (label: string, since: number) => debug && console.debug('[remux]', label, Math.round(performance.now() - since), 'ms')

/** How long a seek waits for the one before it to wind down, how long a drag must rest, and how long a seek may stall. */
const PREVIOUS_RUN_WAIT_MS = 1500
const SEEK_SETTLE_MS = 120
const STALL_MS = 12_000

const MS: typeof MediaSource | undefined = window.ManagedMediaSource ?? window.MediaSource
const phone = matchMedia('(pointer: coarse)').matches

const CODEC_NAMES: Record<string, string> = {
    avc: 'H.264',
    hevc: 'HEVC (H.265)',
    av1: 'AV1',
    vp9: 'VP9',
    vp8: 'VP8',
    prores: 'ProRes',
    aac: 'AAC',
    opus: 'Opus',
    mp3: 'MP3',
    vorbis: 'Vorbis',
    flac: 'FLAC',
    ac3: 'Dolby Digital (AC-3)',
    eac3: 'Dolby Digital Plus (E-AC-3)',
    dts: 'DTS',
}

/** Matroska's codec ids for what mediabunny does not read, so a message can name it. */
const MATROSKA_NAMES: Record<string, string> = {
    A_TRUEHD: 'Dolby TrueHD',
    A_MLP: 'MLP',
    A_ALAC: 'ALAC',
    A_REAL: 'RealAudio',
    'V_MPEG4/ISO/ASP': 'MPEG-4 Part 2 (DivX, Xvid)',
    'V_MPEG4/ISO/SP': 'MPEG-4 Part 2',
    'V_MPEG4/MS/V3': 'MS MPEG-4',
    V_MPEG2: 'MPEG-2',
    V_MPEG1: 'MPEG-1',
    V_THEORA: 'Theora',
    V_REAL: 'RealVideo',
    'V_MS/VFW/FOURCC': 'Video for Windows',
}

/** A track's codec in plain words, also when mediabunny does not know it. */
export function codecName(track: { codec: string | null; internalCodecId: unknown } | null): string {
    if (!track) return 'unknown'
    if (track.codec) return CODEC_NAMES[track.codec] ?? track.codec
    const id = track.internalCodecId
    if (typeof id === 'string') {
        for (const [prefix, name] of Object.entries(MATROSKA_NAMES)) if (id.startsWith(prefix)) return name
        return id.replace(/^[AV]_/, '')
    }
    return 'unknown'
}

/** Names the containers mediabunny does not open, from their first bytes. */
async function sniff(file: TgFile): Promise<string> {
    const h = await file.read(0, 12).catch(() => new Uint8Array(0))
    const text = (from: number, to: number) => String.fromCharCode(...h.subarray(from, to))
    if (text(0, 4) === 'RIFF' && text(8, 11) === 'AVI') return 'AVI'
    if (h[0] === 0x30 && h[1] === 0x26 && h[2] === 0xb2 && h[3] === 0x75) return 'WMV'
    if (text(0, 3) === 'FLV') return 'FLV'
    if (text(0, 4) === '.RMF') return 'RealMedia'
    if (h[0] === 0 && h[1] === 0 && h[2] === 1 && h[3] === 0xba) return 'MPEG-PS (VOB)'
    return 'unknown'
}

/** Reads the file's header (not its name) to learn the container and codecs. */
export async function probe(file: TgFile, durationHint: number): Promise<Probe> {
    const input = new Input({
        formats: ALL_FORMATS,
        source: new CustomSource({
            getSize: () => file.size,
            read: (start, end) => file.read(start, end),
            maxCacheSize: 4 * 1024 * 1024,
            // TgFile does the network read-ahead and keeps the chunks; this only joins small reads
            // (an MPEG-TS file is read 188 bytes at a time, 50,000 calls a seek without it).
            prefetchProfile: 'fileSystem',
            // A read dropped by a seek or a close, with nothing waiting on it.
            handleUnhandledError: () => {},
        }),
    })
    let format
    try {
        format = await input.getFormat()
    } catch (e) {
        input.dispose()
        if (e instanceof UnsupportedInputFormatError) throw new Unplayable({ kind: 'format', format: await sniff(file) })
        throw e
    }
    file.searched = format instanceof MpegTsInputFormat
    const video = await input.getPrimaryVideoTrack()
    const audios = await input.getAudioTracks()
    const first = await input.getFirstTimestamp().catch(() => 0)
    const base = isFinite(first) && first > 0 ? first : 0
    const end = await input.getDurationFromMetadata().catch(() => null)
    const duration = end != null && end - base > 0 ? end - base : durationHint
    const mime = await input.getMimeType().catch(() => 'video/mp4')
    const isoOrWebm = format instanceof Mp4InputFormat || format instanceof QuickTimeInputFormat || format instanceof WebMInputFormat
    // QuickTime files are MP4 to every browser that plays them.
    const asMp4 = mime.replace('video/quicktime', 'video/mp4')
    const probeEl = document.createElement('video')
    const native = isoOrWebm && probeEl.canPlayType(asMp4) !== '' && !!navigator.serviceWorker?.controller
    const vcodec = (await video?.getCodecParameterString().catch(() => null)) ?? null
    let remuxable = !!MS && !!vcodec && MS.isTypeSupported(`video/mp4; codecs="${vcodec.replace(/^hev1/, 'hvc1')}"`)
    // No HEVC decoder here: hevc.js can stand it in as H.264 where the browser encodes that.
    let transcode = false
    if (!remuxable && !native && vcodec && /^(hev1|hvc1)/.test(vcodec) && (await (await import('./hevc')).canTranscodeHevc())) {
        transcode = remuxable = true
    }
    return { input, container: format.name, video, audios, duration, base, mime: asMp4, native, remuxable, videoCodec: codecName(video), transcode }
}

/**
 * How this browser plays a probed file: as it is, remuxed, or not at all (the page then says it
 * needs the app). Software decoding of the picture is left out on purpose (plan 2b).
 */
export function route(p: Probe): 'native' | 'remux' {
    if (!p.video) throw new Unplayable({ kind: 'no-picture' })
    if (p.native) return 'native'
    if (!MS) {
        // Old iPhones (before iOS 17.1) have no Media Source at all.
        throw new Unplayable(p.container.includes('MP4') || p.container.includes('QuickTime') ? { kind: 'picture', codec: p.videoCodec } : { kind: 'no-mse' })
    }
    if (!p.remuxable) throw new Unplayable({ kind: 'picture', codec: p.videoCodec })
    return 'remux'
}

/** The sound as the player hands it to the browser: copied, converted, or none it can play. */
type Sound = { kind: 'copy' } | { kind: 'convert'; to: 'aac' | 'opus'; channels: number } | { kind: 'none' }

let aacRegistered = false

/** AC-3, E-AC-3 and DTS decoders, loaded only when a file has that sound. */
async function registerDecoders(codec: string | null) {
    if (codec === 'ac3' || codec === 'eac3') {
        const { registerAc3Decoder } = await import('@mediabunny/ac3')
        registerAc3Decoder()
    } else if (codec === 'dts') {
        const { registerDtsDecoder } = await import('@mediabunny/dts')
        registerDtsDecoder()
    }
}

/**
 * The encoder for converted sound. AAC keeps everything the native player does with the sound on
 * an iPhone (lock screen, AirPlay, background); the browser's own encoder first (Safari 26), else
 * Opus where MP4 Opus plays and the browser encodes it (Chromium on Linux has no AAC encoder),
 * else mediabunny's AAC encoder in WebAssembly.
 */
export async function encoderFor(channels: number, sampleRate = 48000): Promise<{ to: 'aac' | 'opus'; channels: number } | null> {
    const counts = channels > 2 ? [channels, 2] : [channels]
    for (const n of counts) {
        const config = { numberOfChannels: n, sampleRate }
        if (await canEncodeAudio('aac', config)) return { to: 'aac', channels: n }
        if (MS!.isTypeSupported('audio/mp4; codecs="opus"') && (await canEncodeAudio('opus', config))) return { to: 'opus', channels: n }
        if (!aacRegistered) {
            const { registerAacEncoder } = await import('@mediabunny/aac-encoder')
            registerAacEncoder()
            aacRegistered = true
        }
        if (await canEncodeAudio('aac', config)) return { to: 'aac', channels: n }
    }
    return null
}

const sounds = new WeakMap<InputAudioTrack, Promise<Sound>>()

/** Whether this browser plays a sound track, and how. Asked once per track. */
export function soundOf(track: InputAudioTrack): Promise<Sound> {
    let known = sounds.get(track)
    if (!known) {
        known = (async (): Promise<Sound> => {
            const codec = await track.getCodecParameterString().catch(() => null)
            if (codec && MS?.isTypeSupported(`audio/mp4; codecs="${codec}"`)) return { kind: 'copy' }
            if (!track.codec) return { kind: 'none' }
            await registerDecoders(track.codec)
            if (!(await track.canDecode().catch(() => false))) return { kind: 'none' }
            const enc = await encoderFor(Math.min(phone ? 2 : 6, track.numberOfChannels || 2))
            return enc ? { kind: 'convert', ...enc } : { kind: 'none' }
        })()
        sounds.set(track, known)
    }
    return known
}

/** The first track (in the file's order) this browser can play the sound of, or null. */
export async function playableSound(tracks: InputAudioTrack[]): Promise<InputAudioTrack | null> {
    for (const t of tracks) if ((await soundOf(t)).kind !== 'none') return t
    return null
}

/** A sound track as the menu shows it: mediabunny's, or one libav.js reads (libav.ts). */
export type SoundTrack = Pick<InputAudioTrack, 'codec' | 'internalCodecId' | 'languageCode' | 'name' | 'numberOfChannels'>

export interface EngineEvents {
    onError?: (e: unknown) => void
    /** The engine's `detail` changed (another sound track, converted or copied). */
    onDetail?: (detail: string) => void
    /** The picture is made more slowly than it plays (a software decode on a slow device): said once. */
    onSlow?: () => void
}

export interface Engine {
    readonly name: 'native' | 'remux' | 'libav' | 'movi'
    readonly detail: string
    /** Plays another sound track; false when this browser cannot play that one. */
    setAudio?(track: SoundTrack): Promise<boolean>
    destroy(): void
}

/** The browser decodes the file itself, read through /stream/ by the Service Worker. */
export function nativeEngine(video: HTMLVideoElement, url: string, start: number): Engine {
    video.src = url
    if (start > 0) video.currentTime = start
    return {
        name: 'native',
        detail: 'Played by the browser',
        destroy() {
            video.removeAttribute('src')
            video.load()
        },
    }
}

/**
 * Feeds fragmented MP4 to ManagedMediaSource (iPhone, Safari) or MediaSource (elsewhere), from a
 * key frame at or before the second asked for; `produce` writes the MP4 from there, through `sink`.
 * Keeps 30 s (phone) to 60 s ahead, starts again on a seek outside what is buffered.
 */
export abstract class FeedEngine implements Engine {
    abstract readonly name: 'remux' | 'libav'
    abstract detail: string
    private ms!: MediaSource
    private managed = false
    private sb: SourceBuffer | null = null
    private mime = ''
    private url = ''
    protected gen = 0
    private running: Promise<void> = Promise.resolve()
    protected destroyed = false
    protected t0 = 0
    private wake: (() => void) | null = null
    private seekTimer: ReturnType<typeof setTimeout> | undefined
    private watchdog: ReturnType<typeof setTimeout> | undefined
    private retries = 0
    /** Set by a repack of HEVC this browser cannot decode: each fragment goes through it first. */
    protected transcoder: HevcTranscoder | null = null
    /** Whether the transcoded fragments carry the (AAC) sound too. */
    protected transcodedSound = false
    // A drag over the seek bar fires `seeking` dozens of times a second: only where it settles is
    // fed, not every place it passed.
    private readonly onSeeking = () => {
        clearTimeout(this.seekTimer)
        this.seekTimer = setTimeout(() => this.seekTo(this.video.currentTime), SEEK_SETTLE_MS)
    }
    private readonly onTick = () => this.wake?.()

    constructor(
        protected video: HTMLVideoElement,
        private length: number,
        protected ev: EngineEvents,
    ) {
        if (!MS) throw new Unplayable({ kind: 'no-mse' })
    }

    /** Starts feeding at `start` seconds; called once by the subclass when it is ready. */
    protected begin(start: number) {
        this.video.addEventListener('seeking', this.onSeeking)
        this.video.addEventListener('timeupdate', this.onTick)
        this.video.addEventListener('play', this.onTick)
        ;[this.ms, this.url] = this.open(start)
        this.managed = !!window.ManagedMediaSource && this.ms instanceof window.ManagedMediaSource
        // Safari offers ManagedMediaSource only with AirPlay off, or with an AirPlay source beside it.
        if (this.managed) this.video.disableRemotePlayback = true
    }

    /** Writes the MP4 for run `gen` from `t` seconds (the element's), until the end or a newer run. */
    protected abstract produce(gen: number, t: number): Promise<void>
    /** Stops what `produce` is doing; the next run waits for it. */
    protected abstract stop(): Promise<void>

    /** A new Media Source on the element, filled from `start` once it opens. */
    private open(start: number): [MediaSource, string] {
        const ms = new MS!()
        const url = URL.createObjectURL(ms)
        this.video.src = url
        // ManagedMediaSource says when it wants data (it may hold off on battery or a slow link).
        ms.addEventListener('startstreaming', this.onTick)
        ms.addEventListener(
            'sourceopen',
            () => {
                if (this.destroyed || ms !== this.ms) return
                if (this.length > 0) ms.duration = this.length
                this.restart(start)
            },
            { once: true },
        )
        return [ms, url]
    }

    /**
     * Starts over on a fresh Media Source at `t`: for another sound codec where SourceBuffer has no
     * `changeType` (WebKitGTK; Safari has it from 14.1).
     */
    private reopen(t: number) {
        const playing = !this.video.paused
        this.gen++
        this.wake?.()
        this.stop().catch(() => {})
        this.ms.removeEventListener('startstreaming', this.onTick)
        URL.revokeObjectURL(this.url)
        this.sb = null
        this.mime = ''
        ;[this.ms, this.url] = this.open(t)
        this.ms.addEventListener(
            'sourceopen',
            () => {
                if (t > 0) this.video.currentTime = t
                if (playing) this.video.play().catch(() => {})
            },
            { once: true },
        )
    }

    /** Plays on from where the element is, with whatever changed (another sound track). */
    protected again() {
        if (this.sb && !('changeType' in SourceBuffer.prototype)) this.reopen(this.video.currentTime)
        else this.restart(this.video.currentTime)
    }

    destroy() {
        this.destroyed = true
        this.transcoder?.destroy()
        this.gen++
        this.wake?.()
        this.stop().catch(() => {})
        clearTimeout(this.seekTimer)
        clearTimeout(this.watchdog)
        this.video.removeEventListener('seeking', this.onSeeking)
        this.video.removeEventListener('timeupdate', this.onTick)
        this.video.removeEventListener('play', this.onTick)
        this.ms?.removeEventListener('startstreaming', this.onTick)
        this.video.removeAttribute('src')
        this.video.load()
        URL.revokeObjectURL(this.url)
    }

    private seekTo(t: number) {
        if (this.isBuffered(t)) return
        this.restart(t)
    }

    private isBuffered(t: number): boolean {
        const b = this.sb?.buffered
        if (!b) return false
        for (let i = 0; i < b.length; i++) if (t >= b.start(i) - 0.1 && t < b.end(i) - 0.3) return true
        return false
    }

    private restart(t: number) {
        const gen = ++this.gen
        this.wake?.()
        this.watch(gen)
        const stopped = this.stop()
        const before = this.running
        const t0 = performance.now()
        this.running = (async () => {
            await stopped.catch(() => {})
            mark(`seek ${t.toFixed(1)}: cancelled`, t0)
            // The run before is told to stop, but its conversion may sit in a read or a write for a
            // while (a seek storm piled up behind one that never came back, and the video stayed
            // "seeking" for good). Its output is dropped by the generation check, so waiting for it
            // is only a courtesy, and a short one.
            await Promise.race([before.catch(() => {}), new Promise((resolve) => setTimeout(resolve, PREVIOUS_RUN_WAIT_MS))])
            if (gen !== this.gen || this.destroyed) return
            await this.clearBuffer()
            if (gen !== this.gen) return
            this.t0 = t0
            await this.produce(gen, t)
            if (gen === this.gen && this.ms.readyState === 'open' && this.sb) {
                await this.idle(this.sb)
                if (gen === this.gen && this.ms.readyState === 'open') this.ms.endOfStream()
            }
        })().catch((e) => {
            if (gen === this.gen && !this.destroyed) this.ev.onError?.(e)
        })
    }

    /**
     * A run that has put nothing in the buffer for a while while the element waits for it is
     * started again from where the element is, twice at most, rather than left spinning.
     */
    private watch(gen: number) {
        clearTimeout(this.watchdog)
        this.watchdog = setTimeout(() => {
            if (gen !== this.gen || this.destroyed) return
            const v = this.video
            if (v.readyState >= 3 || (!v.seeking && !v.paused)) {
                this.retries = 0
                return
            }
            if (this.retries >= 2) return
            this.retries++
            mark(`stalled, run again ${this.retries}`, this.t0 || performance.now())
            this.restart(v.currentTime)
        }, this.transcoder ? STALL_MS * 2.5 : STALL_MS)
    }

    private async clearBuffer() {
        const sb = this.sb
        if (!sb) return
        await this.idle(sb)
        if (this.ms.readyState === 'open') sb.abort()
        if (sb.buffered.length) {
            sb.remove(0, Infinity)
            await this.idle(sb)
        }
    }

    /**
     * Where run `gen` writes its MP4: into the SourceBuffer, its times placed `offset()` seconds on
     * (the output starts at 0 from a key frame). A superseded run's chunks are dropped, not refused:
     * an errored stream makes mediabunny's own cancel throw.
     */
    protected sink(gen: number, output: () => Output, offset: () => number, codec: string): StreamTarget {
        let written = 0
        const tx = this.transcoder
        const boxes = tx ? new Boxes() : null
        /** Converted fragments in the order they went in, while the workers work on several at once. */
        const inflight: { result: Promise<Transcoded | null>; times: number[] }[] = []
        /** The run's first fragment's times, in and out, which the others are placed from (see [placeTimes]). */
        let first: { in: number[]; out: number[] } | null = null
        const put = async (data: Uint8Array, mime?: string) => {
            const sb = await this.sourceBuffer(output(), offset(), codec, mime)
            await this.roomAhead(gen)
            if (gen !== this.gen) return false
            await this.append(sb, data)
            this.retries = 0
            if (this.t0) {
                mark(`first append ${data.length} B`, this.t0)
                this.t0 = 0
            }
            return true
        }
        const putConverted = async () => {
            const next = inflight.shift()!
            const r = await next.result
            if (debug && !r) console.debug('[hevc] skipped', gen, 'current', this.gen)
            if (!r || gen !== this.gen) return false
            if (tx?.slow()) this.ev.onSlow?.()
            if (r.bytes) first = placeTimes(r.bytes, next.times, first)
            if (debug && r.init) console.debug('[hevc] first fragment', r.codec)
            // hevc.js names both tracks itself when the sound is in (avc1...,mp4a.40.2).
            const mime = `video/mp4; codecs="${r.codec.includes(',') || !this.transcodedSound ? r.codec : `${r.codec},mp4a.40.2`}"`
            for (const data of [r.init, r.bytes]) if (data && !(await put(data, mime))) return false
            return true
        }
        return new StreamTarget(
            new WritableStream<StreamTargetChunk>({
                write: async (chunk) => {
                    if (gen !== this.gen) return
                    // Fragmented output is written in order; anything that rewrites earlier bytes is skipped.
                    if (chunk.position < written) return
                    written = chunk.position + chunk.data.length
                    if (chunk.position === 0) relabelHevc(chunk.data)
                    if (!tx || !boxes) return void (await put(chunk.data))
                    // HEVC in, H.264 out: the init segment starts the run in the workers, each fragment comes back converted.
                    for (const piece of boxes.push(chunk.data)) {
                        if (Boxes.isInit(piece)) {
                            const ok = await tx.init(gen, piece)
                            if (debug) console.debug('[hevc] init', gen, ok, 'current', this.gen)
                            if (!ok) return
                            continue
                        }
                        const times = tfdts(piece)
                        inflight.push({ result: tx.media(gen, piece), times })
                        while (inflight.length >= tx.parallel) if (!(await putConverted())) return
                    }
                },
                close: async () => {
                    while (inflight.length && gen === this.gen) if (!(await putConverted())) return
                },
            }),
        )
    }

    private async sourceBuffer(output: Output, offset: number, codec: string, as?: string): Promise<SourceBuffer> {
        const mime = as ?? (await output.getMimeType()).replace(/hev1\./g, 'hvc1.')
        if (!this.sb) {
            if (!MS!.isTypeSupported(mime)) throw new Unplayable({ kind: 'picture', codec })
            this.sb = this.ms.addSourceBuffer(mime)
            this.sb.mode = 'segments'
            this.mime = mime
        }
        await this.idle(this.sb)
        // Another sound track can mean another codec (AC-3 converted to Opus, then AAC copied).
        if (mime !== this.mime) {
            this.sb.changeType(mime)
            this.mime = mime
        }
        // Output timestamps start at zero from the key frame; place them where they belong.
        if (this.sb.timestampOffset !== offset) this.sb.timestampOffset = offset
        return this.sb
    }

    /**
     * Keeps 30 s (phone) to 60 s ahead of the playhead, and lets the buffer behind go. A
     * ManagedMediaSource that stopped streaming gets nothing more until it asks again, once 10 s
     * are in hand (so the first frames always go in).
     */
    private async roomAhead(gen: number) {
        const limit = phone ? 30 : 60
        for (;;) {
            if (gen !== this.gen) return
            const sb = this.sb!
            const t = this.video.currentTime
            let end = t
            for (let i = 0; i < sb.buffered.length; i++) if (t >= sb.buffered.start(i) - 1 && t <= sb.buffered.end(i)) end = sb.buffered.end(i)
            const held = this.managed && (this.ms as MediaSource & { streaming?: boolean }).streaming === false && end - t >= 10
            if (end - t < limit && !held) return
            await new Promise<void>((resolve) => {
                this.wake = resolve
                setTimeout(resolve, 1000)
            })
            this.wake = null
        }
    }

    private async append(sb: SourceBuffer, data: Uint8Array) {
        for (let attempt = 0; ; attempt++) {
            try {
                await this.idle(sb)
                sb.appendBuffer(data as BufferSource)
                await this.idle(sb)
                return
            } catch (e) {
                if ((e as DOMException)?.name !== 'QuotaExceededError' || attempt > 2) throw e
                const behind = this.video.currentTime - 10
                if (behind > 0) {
                    sb.remove(0, behind)
                    await this.idle(sb)
                }
                await new Promise((r) => setTimeout(r, 1000))
            }
        }
    }

    private idle(sb: SourceBuffer): Promise<void> {
        if (!sb.updating) return Promise.resolve()
        return new Promise((resolve, reject) => {
            const done = () => {
                sb.removeEventListener('updateend', done)
                sb.removeEventListener('error', fail)
                resolve()
            }
            const fail = () => {
                sb.removeEventListener('updateend', done)
                sb.removeEventListener('error', fail)
                reject(new Error('The browser could not take this part of the video.'))
            }
            sb.addEventListener('updateend', done)
            sb.addEventListener('error', fail)
        })
    }
}

/**
 * Remux to fragmented MP4 in the browser: the picture is copied untouched and decoded by the
 * device's hardware, sound the browser cannot play (AC-3, E-AC-3, DTS) is converted to AAC.
 *
 * Times: the `<video>` element counts from 0 at the file's first timestamp (`Probe.base`); the
 * conversion is cut at a key frame (`trim.start`, its output starts at 0) and placed with
 * `timestampOffset`.
 */
export class RemuxEngine extends FeedEngine {
    readonly name = 'remux'
    detail = 'Remuxed to MP4'
    private conversion: Conversion | null = null
    private keySink: EncodedPacketSink

    /** `audio` is a track `soundOf` said plays, or null to play without sound. */
    constructor(
        video: HTMLVideoElement,
        private p: Probe,
        private audio: InputAudioTrack | null,
        start: number,
        ev: EngineEvents,
    ) {
        super(video, p.duration, ev)
        if (!p.video) throw new Unplayable({ kind: 'no-picture' })
        this.keySink = new EncodedPacketSink(p.video)
        this.begin(start)
    }

    async setAudio(track: InputAudioTrack): Promise<boolean> {
        if (track === this.audio) return true
        if ((await soundOf(track)).kind === 'none') return false
        if (this.destroyed) return false
        this.audio = track
        this.again()
        return true
    }

    protected async stop() {
        // What the workers still convert for the old place is not waited for.
        this.transcoder?.abandon()
        const previous = this.conversion
        this.conversion = null
        await previous?.cancel()
    }

    protected async produce(gen: number, t: number) {
        if (this.p.transcode && !this.transcoder) {
            const { HevcTranscoder } = await import('./hevc')
            if (this.destroyed) return
            this.transcoder = new HevcTranscoder()
        }
        const key = await this.keySink.getKeyPacket(t + this.p.base, { metadataOnly: true }).catch(() => null)
        // Before the first key frame (or none found): from the start.
        const from = Math.max(key?.timestamp ?? this.p.base, this.p.base)
        mark(`key frame ${(from - this.p.base).toFixed(2)}`, this.t0)
        if (gen !== this.gen) return
        const audioOptions = await this.audioPlan()
        const output: Output = new Output({
            format: new Mp4OutputFormat({ fastStart: 'fragmented', minimumFragmentDuration: 1 }),
            target: this.sink(gen, () => output, () => from - this.p.base, this.p.videoCodec),
        })
        const conversion = await Conversion.init({
            input: this.p.input,
            output,
            video: (track) => (track === this.p.video ? {} : { discard: true }),
            audio: (track) => (track === this.audio && audioOptions ? audioOptions : { discard: true }),
            trim: { start: from },
            copy: { shiftTolerance: 0.25 },
            tags: {},
            showWarnings: false,
        })
        mark('conversion ready', this.t0)
        if (gen !== this.gen) return
        if (!conversion.isValid || !conversion.utilizedTracks.some((t) => t === this.p.video)) {
            throw new Unplayable({ kind: 'picture', codec: this.p.videoCodec })
        }
        this.conversion = conversion
        await conversion.execute()
    }

    /** The chosen track copied when the browser plays it in MP4, else converted; null for no sound. */
    private async audioPlan() {
        const plan = await this.soundPlan()
        this.ev.onDetail?.(this.detail)
        return plan
    }

    private async soundPlan() {
        const track = this.audio
        this.transcodedSound = false
        if (!track) {
            this.detail = 'Remuxed to MP4, no sound'
            return null
        }
        const sound = await soundOf(track)
        if (sound.kind === 'none') return null
        this.transcodedSound = true
        if (this.p.transcode) {
            // hevc.js passes AAC through and nothing else: anything but AAC becomes AAC.
            if (sound.kind === 'copy' && track.codec === 'aac') {
                this.detail = `HEVC converted to H.264 here, sound copied (AAC)`
                return {}
            }
            const channels = await aacEncoder(Math.min(2, track.numberOfChannels || 2))
            if (!channels) {
                this.transcodedSound = false
                return null
            }
            this.detail = `HEVC converted to H.264 here, sound converted from ${codecName(track)} to AAC`
            return { codec: 'aac', numberOfChannels: channels, sampleRate: 48000, quality: QUALITY_HIGH, forceTranscode: true } as const
        }
        if (sound.kind === 'copy') {
            this.detail = `Remuxed to MP4, sound copied (${codecName(track)})`
            return {}
        }
        this.detail = `Remuxed to MP4, sound converted from ${codecName(track)} to ${sound.to.toUpperCase()}`
        return {
            codec: sound.to,
            numberOfChannels: sound.channels,
            sampleRate: 48000,
            quality: QUALITY_HIGH,
            forceTranscode: true,
        } as const
    }
}

const HEV1 = [0x68, 0x65, 0x76, 0x31]

/**
 * Names HEVC `hvc1` instead of `hev1` in the init segment: Safari refuses `hev1`. The two differ only
 * in whether parameter sets may also travel in the stream; a Matroska file always carries them in
 * its header (CodecPrivate), which is what `hvc1` promises.
 */
function relabelHevc(data: Uint8Array) {
    for (let i = 0; i + 4 <= data.length; i++) {
        if (data[i] === HEV1[0] && data[i + 1] === HEV1[1] && data[i + 2] === HEV1[2] && data[i + 3] === HEV1[3]) data[i + 2] = 0x63
    }
}

if (debug) (window as unknown as { probe: typeof probe }).probe = probe

/**
 * The key frame at or before `t` (the player's seconds), read from the file's index or clusters
 * by mediabunny; null when it cannot say within `budgetMs`. movi lands a software-decoded seek
 * there instead of decoding its way to the exact second.
 */
export function keyFrames(p: Probe, budgetMs = 2000): ((t: number) => Promise<number | null>) | null {
    if (!p.video) return null
    const sink = new EncodedPacketSink(p.video)
    return async (t) => {
        const key = sink.getKeyPacket(t + p.base, { metadataOnly: true }).then((k) => (k ? Math.max(0, k.timestamp - p.base) : null), () => null)
        return Promise.race([key, new Promise<null>((r) => setTimeout(() => r(null), budgetMs))])
    }
}

/** An AAC encoder for `channels`: the browser's, else mediabunny's in WebAssembly; the channels it takes, or null. */
async function aacEncoder(channels: number): Promise<number | null> {
    const config = { numberOfChannels: channels, sampleRate: 48000 }
    if (await canEncodeAudio('aac', config)) return channels
    if (!aacRegistered) {
        const { registerAacEncoder } = await import('@mediabunny/aac-encoder')
        registerAacEncoder()
        aacRegistered = true
    }
    return (await canEncodeAudio('aac', config)) ? channels : null
}

/**
 * Joins the MP4 writer's pieces into whole top-level boxes, as hevc.js takes them: ftyp with moov
 * as one, then each moof with its mdat.
 */
class Boxes {
    private pending = new Uint8Array(0)

    static isInit(data: Uint8Array): boolean {
        if (data.length < 8) return false
        const type = String.fromCharCode(data[4], data[5], data[6], data[7])
        return type === 'ftyp' || type === 'moov'
    }

    push(data: Uint8Array): Uint8Array[] {
        const joined = new Uint8Array(this.pending.length + data.length)
        joined.set(this.pending)
        joined.set(data, this.pending.length)
        const view = new DataView(joined.buffer)
        const out: Uint8Array[] = []
        let start = 0
        let at = 0
        while (at + 8 <= joined.length) {
            let size = view.getUint32(at)
            if (size === 1) {
                if (at + 16 > joined.length) break
                size = Number(view.getBigUint64(at + 8))
            }
            if (size < 8 || at + size > joined.length) break
            const type = String.fromCharCode(joined[at + 4], joined[at + 5], joined[at + 6], joined[at + 7])
            at += size
            if (type === 'moov' || type === 'mdat') {
                out.push(joined.slice(start, at))
                start = at
            }
        }
        this.pending = joined.slice(start)
        return out
    }
}

/** Where each track fragment's decode time (tfdt) sits in a moof, in track order. */
function tfdtAt(data: Uint8Array): number[] {
    const at: number[] = []
    const v = new DataView(data.buffer, data.byteOffset, data.byteLength)
    const walk = (from: number, to: number) => {
        for (let i = from; i + 8 <= to; ) {
            const size = v.getUint32(i)
            if (size < 8 || i + size > to) return
            const type = String.fromCharCode(data[i + 4], data[i + 5], data[i + 6], data[i + 7])
            if (type === 'moof' || type === 'traf') walk(i + 8, i + size)
            else if (type === 'tfdt') at.push(i)
            i += size
        }
    }
    walk(0, data.length)
    return at
}

function tfdts(data: Uint8Array): number[] {
    const v = new DataView(data.buffer, data.byteOffset, data.byteLength)
    return tfdtAt(data).map((i) => (data[i + 8] === 1 ? Number(v.getBigUint64(i + 12)) : v.getUint32(i + 12)))
}

/**
 * hevc.js numbers a worker's fragments by counting them, not from their own decode times, so with
 * several workers each would start where the run did. The run's first fragment is taken as it
 * comes; every later one is placed as far after it as its input was after the first input.
 */
function placeTimes(out: Uint8Array, input: number[], first: { in: number[]; out: number[] } | null) {
    const at = tfdtAt(out)
    const v = new DataView(out.buffer, out.byteOffset, out.byteLength)
    const read = (i: number) => (out[i + 8] === 1 ? Number(v.getBigUint64(i + 12)) : v.getUint32(i + 12))
    if (!first) return at.length === input.length ? { in: input, out: at.map(read) } : null
    if (at.length !== input.length || first.in.length !== input.length) return first
    at.forEach((i, k) => {
        const t = Math.max(0, first.out[k] + (input[k] - first.in[k]))
        if (out[i + 8] === 1) v.setBigUint64(i + 12, BigInt(t))
        else v.setUint32(i + 12, t)
    })
    return first
}

