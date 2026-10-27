// movi-player (FFmpeg in WebAssembly and WebCodecs) for what the browser cannot play or repack:
// HEVC where it has no decoder, AVI and other containers, files the remux path gave up on. It
// draws on a canvas laid over our `<video>`, which stays the one element the controls, resume,
// the stall watch and the keys read: its properties and events are taken over for as long as
// this engine runs (see [takeOver]) and given back on destroy.
import type { MoviPlayer, PlayerState, SourceAdapter, AudioTrack } from 'movi-player/player'
import type { TgFile } from '../stream/reader'
import { Unplayable, type Engine, type EngineEvents, type SoundTrack } from './engines'

const debug = location.hostname === 'localhost' || location.hostname === '127.0.0.1'

/** Whether this browser can run movi at all: WebAssembly, WebCodecs' video decoder and Web Audio. */
export function moviSupported(): boolean {
    return typeof WebAssembly === 'object' && typeof VideoDecoder === 'function' && typeof AudioContext === 'function'
}

/** Telegram's bytes for movi, through the same reader (cache, read-ahead, flood waits) as the other engines. */
class TgSource implements SourceAdapter {
    private position = 0
    private failure: Error | null = null

    constructor(private readonly file: TgFile) {}

    getSize() {
        return Promise.resolve(this.file.size)
    }

    async read(offset: number, length: number): Promise<ArrayBuffer> {
        const end = Math.min(this.file.size, offset + length)
        if (offset >= end) return new ArrayBuffer(0)
        const slow = debug ? setTimeout(() => console.debug('[movi] read still waiting', offset, end - offset), 5000) : 0
        try {
            const bytes = await this.file.read(offset, end).finally(() => clearTimeout(slow))
            this.position = offset + bytes.length
            return bytes.byteOffset === 0 && bytes.byteLength === bytes.buffer.byteLength ? (bytes.buffer as ArrayBuffer) : (bytes.slice().buffer as ArrayBuffer)
        } catch (e) {
            // A file gone from the chat will not come back; anything else is retried by movi.
            if (/\bgone\b/.test(String((e as Error)?.message ?? e))) this.failure = e as Error
            throw e
        }
    }

    seek(offset: number) {
        this.position = Math.max(0, Math.min(offset, this.file.size))
        return this.position
    }

    getPosition() {
        return this.position
    }

    close() {}

    getKey() {
        return `tg:${this.file.key}`
    }

    getFatalError() {
        return this.failure
    }
}

/** A movi sound track in the shape the player's sound menu shows. */
export interface MoviSound extends SoundTrack {
    readonly id: number
}

const MOVI_CODECS: Record<string, string> = { aac: 'aac', mp3: 'mp3', opus: 'opus', vorbis: 'vorbis', flac: 'flac', ac3: 'ac3', eac3: 'eac3' }

function sound(t: AudioTrack): MoviSound {
    const codec = (t.codec ?? '').toLowerCase()
    return {
        id: t.id,
        codec: (MOVI_CODECS[codec] ?? null) as SoundTrack['codec'],
        internalCodecId: codec.toUpperCase(),
        languageCode: t.language ?? 'und',
        name: t.label ?? null,
        numberOfChannels: t.channels,
    }
}

const WAITING: PlayerState[] = ['idle', 'loading', 'seeking', 'buffering']

export class MoviEngine implements Engine {
    readonly name = 'movi'
    detail = 'Played by movi-player (FFmpeg)'
    sounds: MoviSound[] = []
    private canvas: HTMLCanvasElement
    private resize: ResizeObserver
    private restore: () => void = () => {}
    private destroyed = false
    private onDestroy: () => void = () => {}

    private constructor(
        private readonly player: MoviPlayer,
        private readonly video: HTMLVideoElement,
        canvas: HTMLCanvasElement,
    ) {
        this.canvas = canvas
        this.resize = new ResizeObserver(() => this.fit())
        this.resize.observe(canvas)
    }

    /**
     * Opens `file` with movi at `at` seconds. Throws when movi cannot open it (the caller falls
     * back or says the video needs the app); nothing is left behind then.
     */
    static async open(
        file: TgFile,
        video: HTMLVideoElement,
        at: number,
        events: EngineEvents,
        keyAt: ((t: number) => Promise<number | null>) | null = null,
        onDestroy: () => void = () => {},
    ): Promise<MoviEngine> {
        const t0 = performance.now()
        const mark = (what: string) => debug && console.debug('[movi]', what, Math.round(performance.now() - t0), 'ms')
        const { MoviPlayer, Logger, LogLevel } = await import('movi-player/player')
        mark('module')
        Logger.setLevel(debug ? LogLevel.INFO : LogLevel.ERROR)
        const canvas = document.createElement('canvas')
        canvas.className = 'movi-canvas'
        video.after(canvas)
        const player = new MoviPlayer({ sourceAdapter: new TgSource(file), canvas, renderer: 'canvas', decoder: 'auto', cache: { type: 'lru', maxSizeMB: 64 } })
        // Sound and picture go on in a hidden tab, as the browser's own player does.
        player.setBackgroundPlay(true)
        const engine = new MoviEngine(player, video, canvas)
        engine.onDestroy = onDestroy
        if (debug) (window as unknown as { movi: MoviPlayer }).movi = player
        try {
            await player.load()
            mark('opened')
            if (engine.destroyed) throw new Error('closed')
            engine.sounds = player.getAudioTracks().map(sound)
            const v = player.getVideoTracks()[0]
            if (!v) throw new Error('no picture')
            engine.detail = `Played by movi-player: ${v.codec} ${v.width}x${v.height}${engine.sounds[0] ? `, ${engine.sounds[0].internalCodecId}` : ''}`
            // Decoded in software, a seek to the exact second decodes every frame from the key
            // frame before it (seconds at 1080p): land on that key frame instead, as the repack does.
            const software = String(player.getStats()['Video Decoder'] ?? '').startsWith('Software')
            const land = software && keyAt ? async (t: number) => (await keyAt(t)) ?? t : async (t: number) => t
            engine.restore = takeOver(video, player, events, land)
            engine.fit()
            if (at > 0) {
                await player.seek(await land(at), { internal: true })
                // The seek's promise can settle while movi is still seeking, and a play asked for
                // then is lost: wait for it to come to rest first (20 s at most).
                await settled(player, 20_000)
            }
            mark('at the start')
            engine.watchStart(file, events, v.codec)
            video.dispatchEvent(new Event('loadedmetadata'))
            video.dispatchEvent(new Event('durationchange'))
            video.dispatchEvent(new Event('canplay'))
            return engine
        } catch (e) {
            engine.destroy()
            throw e
        }
    }

    private startWatch: ReturnType<typeof setInterval> | undefined

    /**
     * movi can open a file and then decode nothing (a codec its build lacks): no frame and no
     * movement for 10 s while no read is outstanding is said as "needs the app", never a spinner.
     */
    watchStart(file: TgFile, events: EngineEvents, codec: string) {
        let still = 0
        let last = -1
        this.startWatch = setInterval(() => {
            const shown = Number(this.player.getStats()['Frames Rendered'] ?? 0)
            const t = this.player.getCurrentTime()
            if (shown > 0 || (last >= 0 && t !== last)) return clearInterval(this.startWatch)
            last = t
            if (this.video.paused || file.waiting) return void (still = 0)
            if ((still += 1) >= 10) {
                clearInterval(this.startWatch)
                events.onError?.(new Unplayable({ kind: 'picture', codec: codec.toUpperCase() }))
            }
        }, 1000)
    }

    private fit() {
        const r = this.canvas.getBoundingClientRect()
        const dpr = Math.min(2, window.devicePixelRatio || 1)
        if (r.width > 0 && r.height > 0) this.player.resizeCanvas(Math.round(r.width * dpr), Math.round(r.height * dpr))
    }

    async setAudio(track: SoundTrack): Promise<boolean> {
        const id = (track as MoviSound).id
        if (id === undefined) return false
        return this.player.selectAudioTrack(id)
    }

    destroy() {
        if (this.destroyed) return
        this.destroyed = true
        clearInterval(this.startWatch)
        this.resize.disconnect()
        this.restore()
        try {
            this.player.destroy()
        } catch (e) {
            console.warn('[movi] destroy', e)
        }
        this.canvas.remove()
        this.onDestroy()
    }
}

/**
 * Makes `video` answer for `player`: time, length, paused, speed, volume, what is buffered and how
 * ready it is, play and pause, with the media events the controls listen to. Returns the undo.
 */
function takeOver(video: HTMLVideoElement, player: MoviPlayer, events: EngineEvents, land: (t: number) => Promise<number>): () => void {
    const fire = (type: string) => video.dispatchEvent(new Event(type))
    let paused = true
    let ended = false
    let seekingTo: number | null = null
    let rate = 1
    let defaultRate = video.defaultPlaybackRate || 1
    let state: PlayerState = player.getState()

    const own: PropertyDescriptorMap = {
        currentTime: {
            configurable: true,
            get: () => seekingTo ?? player.getCurrentTime(),
            set: (t: number) => {
                const to = Math.max(0, Math.min(Number(t) || 0, player.getDuration() || Number(t) || 0))
                seekingTo = to
                ended = false
                fire('seeking')
                fire('timeupdate')
                land(to)
                    .then((at) => {
                        if (seekingTo !== to) return
                        return player.seek(at, { preservePlaying: true })
                    })
                    .then(
                    () => {
                        if (seekingTo !== to) return
                        seekingTo = null
                        fire('seeked')
                        fire('timeupdate')
                    },
                    (e) => {
                        if (seekingTo === to) seekingTo = null
                        console.warn('[movi] seek', e)
                    },
                )
            },
        },
        duration: { configurable: true, get: () => player.getDuration() || NaN },
        paused: { configurable: true, get: () => paused },
        ended: { configurable: true, get: () => ended },
        seeking: { configurable: true, get: () => seekingTo !== null },
        readyState: { configurable: true, get: () => (seekingTo !== null || WAITING.includes(state) ? 1 : 4) },
        videoWidth: { configurable: true, get: () => player.getVideoTracks()[0]?.width ?? 0 },
        videoHeight: { configurable: true, get: () => player.getVideoTracks()[0]?.height ?? 0 },
        playbackRate: {
            configurable: true,
            get: () => rate,
            set: (r: number) => {
                if (!(r > 0) || r === rate) return
                rate = r
                player.setPlaybackRate(r)
                fire('ratechange')
            },
        },
        defaultPlaybackRate: {
            configurable: true,
            get: () => defaultRate,
            set: (r: number) => {
                if (r > 0) defaultRate = r
            },
        },
        volume: {
            configurable: true,
            get: () => player.getVolume(),
            set: (v: number) => {
                player.setVolume(Math.max(0, Math.min(1, v)))
                fire('volumechange')
            },
        },
        muted: {
            configurable: true,
            get: () => player.getMuted(),
            set: (m: boolean) => {
                player.setMuted(!!m)
                fire('volumechange')
            },
        },
        buffered: {
            configurable: true,
            get: () => {
                const start = player.getBufferedRangeStart()
                const end = player.getBufferEndTime()
                const has = isFinite(start) && isFinite(end) && end > start
                return {
                    length: has ? 1 : 0,
                    start: () => start,
                    end: () => end,
                } as unknown as TimeRanges
            },
        },
        play: {
            configurable: true,
            value: async () => {
                if (debug) console.debug('[movi] play asked', paused, state)
                if (!paused) return
                paused = false
                if (ended) {
                    ended = false
                    await player.seek(0, { internal: true }).catch(() => {})
                }
                fire('play')
                try {
                    await player.play()
                } catch (e) {
                    paused = true
                    fire('pause')
                    throw e
                }
            },
        },
        pause: {
            configurable: true,
            value: () => {
                if (paused) return
                paused = true
                player.pause()
                fire('pause')
            },
        },
        // The picture is a canvas: no picture in picture for it (the menu leaves it out).
        disablePictureInPicture: { configurable: true, get: () => true },
        requestPictureInPicture: { configurable: true, value: () => Promise.reject(new DOMException('Not with this video', 'NotSupportedError')) },
        webkitSetPresentationMode: { configurable: true, value: undefined },
    }
    video.pause()
    video.removeAttribute('src')
    video.classList.add('movi-active')
    Object.defineProperties(video, own)

    // Subtitles from a file: the element's own cue box does not run (its clock stays at 0), so the
    // showing track's cues are drawn here, in the look the custom properties give `video::cue`.
    const cues = document.createElement('div')
    cues.className = 'movi-cues'
    video.parentElement?.append(cues)
    let shownCues = ''
    const drawCues = () => {
        const t = seekingTo ?? player.getCurrentTime()
        const track = Array.from(video.textTracks).find((tt) => tt.mode === 'showing' && (tt.kind === 'subtitles' || tt.kind === 'captions'))
        const active = track ? Array.from(track.cues ?? []).filter((c) => c.startTime <= t && t < c.endTime) : []
        const key = active.map((c) => `${c.startTime}:${c.endTime}`).join('|')
        if (key === shownCues) return
        shownCues = key
        // The look (size, colour, box, edge) is set on the element by Player.tsx.
        for (const name of ['--cue-size', '--cue-colour', '--cue-box', '--cue-edge']) cues.style.setProperty(name, video.style.getPropertyValue(name))
        cues.replaceChildren(
            ...active.map((c) => {
                const line = document.createElement('span')
                line.className = 'movi-cue'
                if (c instanceof VTTCue) line.append(c.getCueAsHTML())
                return line
            }),
        )
        const first = active[0]
        const at = first instanceof VTTCue && typeof first.line === 'number' && !first.snapToLines ? first.line : 90
        cues.style.paddingBottom = `${Math.max(0, 100 - at)}%`
    }
    const onTime = () => {
        if (seekingTo === null) fire('timeupdate')
        drawCues()
    }
    const onState = (s: PlayerState) => {
        const was = state
        state = s
        // movi paused by itself (an interruption, a lost audio device): the controls are told.
        if (s === 'paused' && was === 'playing' && !paused && seekingTo === null && !player.isPlaybackIntended()) {
            paused = true
            fire('pause')
        }
        // A play asked for while movi was still opening or seeking can be dropped: asked again once it settles.
        else if ((s === 'paused' || s === 'ready') && was !== 'playing' && !paused && seekingTo === null && !player.isPlaybackIntended()) {
            player.play().catch((e) => console.warn('[movi] play', e))
        }
        if (s === 'playing') fire('playing')
        else if ((s === 'buffering' || s === 'seeking') && !paused) fire('waiting')
        else if (s === 'ended') {
            ended = true
            paused = true
            fire('pause')
            fire('ended')
        } else if (s === 'error') events.onError?.(player.getSourceFailure() ?? new Error('movi stopped'))
        if (was !== 'playing' && (s === 'paused' || s === 'ready')) fire('canplay')
        if (debug) console.debug('[movi] state', s)
    }
    const onDuration = () => fire('durationchange')
    const onBuffer = () => fire('progress')
    const onError = (e: Error) => {
        console.warn('[movi] error', e)
        events.onError?.(e)
    }
    // Decoded in software more slowly than it plays: frames shown against the source's rate, every 5 s of playing.
    let lastFrames = -1
    const slowWatch = setInterval(() => {
        if (paused || seekingTo !== null || state !== 'playing') return void (lastFrames = -1)
        const shown = Number(player.getStats()['Frames Rendered'] ?? 0)
        const fps = player.getRenderHealth()?.sourceFps || 0
        if (lastFrames >= 0 && fps > 0 && (shown - lastFrames) / 5 < fps * 0.8) events.onSlow?.()
        lastFrames = shown
    }, 5000)
    player.on('timeUpdate', onTime)
    player.on('stateChange', onState)
    player.on('durationChange', onDuration)
    player.on('bufferUpdate', onBuffer)
    player.on('error', onError)

    return () => {
        clearInterval(slowWatch)
        cues.remove()
        player.off('timeUpdate', onTime)
        player.off('stateChange', onState)
        player.off('durationChange', onDuration)
        player.off('bufferUpdate', onBuffer)
        player.off('error', onError)
        for (const key of Object.keys(own)) delete (video as unknown as Record<string, unknown>)[key]
        video.classList.remove('movi-active')
    }
}

/** Resolves once movi is no longer opening, seeking or buffering, or after `ms`. */
function settled(player: MoviPlayer, ms: number): Promise<void> {
    if (!WAITING.includes(player.getState())) return Promise.resolve()
    return new Promise((resolve) => {
        const done = () => {
            clearTimeout(timer)
            off()
            resolve()
        }
        const timer = setTimeout(done, ms)
        const off = player.on('stateChange', (s) => !WAITING.includes(s) && done())
    })
}
