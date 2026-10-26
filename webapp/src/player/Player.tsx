import { createEffect, createSignal, For, on, onCleanup, onMount, Show } from 'solid-js'
import '@videojs/html/video/player'
import '@videojs/html/ui/container'
import '@videojs/html/ui/controls'
import '@videojs/html/ui/controls-backdrop'
import '@videojs/html/ui/controls-content'
import '@videojs/html/ui/controls-group'
import '@videojs/html/ui/gesture'
import '@videojs/html/ui/hotkey'
import '@videojs/html/ui/buffering-indicator'
import '@videojs/html/ui/seek-indicator'
import '@videojs/html/ui/seek-indicator-value'
import '@videojs/html/ui/play-button'
import '@videojs/html/ui/seek-button'
import '@videojs/html/ui/mute-button'
import '@videojs/html/ui/fullscreen-button'
import '@videojs/html/ui/time'
import '@videojs/html/ui/time-group'
import '@videojs/html/ui/time-separator'
import '@videojs/html/ui/time-slider'
import '@videojs/html/ui/slider-track'
import '@videojs/html/ui/slider-buffer'
import '@videojs/html/ui/slider-fill'
import '@videojs/html/ui/slider-thumb'
import '@videojs/html/ui/slider-preview'
import '@videojs/html/ui/slider-thumbnail'
import '@videojs/html/ui/slider-value'
import '@videojs/html/ui/menu'
import '@videojs/html/ui/menu-content'
import '@videojs/html/ui/menu-item'
import '@videojs/html/ui/menu-radio-group'
import '@videojs/html/ui/menu-radio-item'
import '@videojs/html/ui/menu-item-indicator'
import '@videojs/html/ui/playback-rate-radio-group'
import '@videojs/html/ui/captions-radio-group'
import {
    ArrowLeft02Icon,
    ArrowRight01Icon,
    Cancel01Icon,
    FullScreenIcon,
    GoBackward10SecIcon,
    GoForward10SecIcon,
    MinimizeScreenIcon,
    NextIcon,
    PauseIcon,
    PlayIcon,
    PreviousIcon,
    RepeatIcon,
    Settings02Icon,
    VolumeHighIcon,
    VolumeMute02Icon,
} from '@hugeicons/core-free-icons'
import { openFile, streams, warmFile, type TgFile } from '../stream/reader'
import { codecName, nativeEngine, playableSound, probe, RemuxEngine, route, Unplayable, type Engine, type Probe, type SoundTrack, type Why } from './engines'
import { LIBAV_FORMATS, LibavEngine } from './libav'
import { storyboardTrack } from './storyboard'
import { readSubtitle, SUBTITLE_EXTENSIONS, SubtitleError } from './subtitles'
import { api } from '../tg/rpc'
import { t } from '../i18n'
import { Icon } from '../ui/Icon'
import { dropResume, getResume, saveResume as storeResume, settings } from '../store'
import type { VideoCopy, VideoItem, WatchInfo } from '../tg/types'

/** What plays: the posted file, or one of Telegram's lighter copies of it. */
interface Source {
    key: string
    size: number
    duration: number
    /** A copy's picture height; 0 for the posted file. */
    height: number
}

/** Seconds before the end at which the next episode is fetched ahead and its card comes up. */
const NEXT_AHEAD = 30
/** Waiting this long with nothing arriving says so, as the app's OpeningStall. */
const STALL_MS = 15_000
/** How long a press is held before the video plays at double speed. */
const HOLD_MS = 450

/** Local testing only: start and seek times, in the console and in `window.playerTimes`. */
const timing =
    location.hostname === 'localhost'
        ? (label: string, since: number) => {
              const ms = Math.round(performance.now() - since)
              console.debug('[player]', label, ms, 'ms')
              ;((window as unknown as { playerTimes?: { label: string; ms: number }[] }).playerTimes ??= []).push({ label, ms })
          }
        : () => {}

/**
 * The item Video.js's own option groups (speed, subtitles) copy for each choice. Set as HTML: it is
 * cloned by Video.js, never drawn by Solid. The tick is drawn by CSS (.menu-check).
 */
const RADIO_TEMPLATE =
    '<template><media-menu-radio-item class="menu-row"><span data-part="label"></span><media-menu-item-indicator force-mount class="menu-check"></media-menu-item-indicator></media-menu-radio-item></template>'

/** The speed last chosen in this tab: the next episode (or any video opened after) plays at it too. */
let chosenRate = 1

/** Only one tab plays at a time: a tab that starts playing tells the others, which pause. */
const tabs = typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel('tmplayer-player') : null

/** Telegram's copies this browser can play: H.264 always, AV1 where it decodes it. */
function playableCopies(copies: VideoCopy[]): VideoCopy[] {
    const av1 = typeof MediaSource !== 'undefined' && MediaSource.isTypeSupported('video/mp4; codecs="av01.0.08M.08"')
    return copies.filter((c) => c.codec !== 'av01' || av1)
}

function speedText(bytesPerSecond: number): string {
    const mb = bytesPerSecond / 1e6
    return mb >= 1 ? t('format.speed_mb', { speed: mb.toFixed(1) }) : t('format.speed_kb', { speed: Math.round(bytesPerSecond / 1e3) })
}

/**
 * The player: Video.js 10's elements (state, gestures, hotkeys, seek bar with Telegram's
 * storyboard, menus) around our own `<video>`, which the engines in engines.ts feed. Quality
 * switches between the posted file and Telegram's copies at the same second; a subtitle file
 * from the device becomes a text track; the next episode is fetched ahead near the end.
 */
export function Player(props: {
    item: VideoItem
    info: WatchInfo | null
    onClose: () => void
    /** Plays another video of the chat (previous or next episode, a card). */
    onPlay: (item: VideoItem) => void
}) {
    let video!: HTMLVideoElement
    let container!: HTMLElement
    let subtitleInput!: HTMLInputElement
    const item = props.item
    const copies = playableCopies(item.copies)
    const mounted = performance.now()
    let loadStarted = 0
    let framed = false
    let seekStarted = 0

    let file: TgFile | null = null
    let engine: Engine | null = null
    let probed: Probe | null = null
    /** Bumped on each load, so a slow probe of an abandoned source does nothing. */
    let generation = 0
    const [source, setSource] = createSignal<Source>({ key: item.key, size: item.size, duration: item.duration, height: 0 })
    const [error, setError] = createSignal<string | null>(null)
    const [needsApp, setNeedsApp] = createSignal<Why | null>(null)
    /** A line over the picture: the sound, a subtitle file, a quality switch. */
    const [notice, setNotice] = createSignal('')
    const [audios, setAudios] = createSignal<SoundTrack[]>([])
    const [audio, setAudio] = createSignal<SoundTrack | null>(null)
    const [switchable, setSwitchable] = createSignal(false)
    const [details, setDetails] = createSignal('')
    const [readout, setReadout] = createSignal('')
    const [stall, setStall] = createSignal<'start' | 'mid' | 'offline' | null>(null)
    const [startOver, setStartOver] = createSignal(false)
    const [nextCard, setNextCard] = createSignal(false)
    const [nextDismissed, setNextDismissed] = createSignal(false)
    const [holding, setHolding] = createSignal(false)
    const [fullscreen, setFullscreen] = createSignal(false)
    const subtitleUrls: string[] = []
    let storyboardUrls: string[] = []

    // Resume: where the video was left (seconds), the app's rewind taken off on opening.
    let savedMs: number | null = null
    const rewindMs = () => settings().rewindMs
    // What to keep for where it is now (the app's ResumeRewind.kept, in the worker), asked once a
    // second, so a save on closing is written at once: an answer awaited then would be lost with the page.
    let kept: { at: number; value: number } | null = null
    const refreshKept = () => {
        const at = video.currentTime
        if (!(at > 0)) return
        api.resumeKept(savedMs, at * 1000, rewindMs()).then((ms) => (kept = { at, value: ms / 1000 }), () => {})
    }
    const savePosition = (position: number) => {
        if (!(position > 0)) return
        const length = video.duration && isFinite(video.duration) ? video.duration : source().duration
        const value = kept && Math.abs(kept.at - position) < 1.5 ? kept.value : position
        storeResume({ chatId: item.chatId, messageId: item.messageId, position: value, length })
    }

    const fail = (e: unknown) => {
        console.error('[player]', e)
        if (e instanceof Unplayable) setNeedsApp(e.why)
        // Offline: wait for the connection and carry on at the same second (onOnline).
        else if (!navigator.onLine) {
            retryOnline = true
            setStall('offline')
        }
        else if (/\bgone\b/.test(String((e as Error)?.message ?? e))) {
            dropResume(item.chatId, item.messageId)
            setError(t('web.gone_from_chat'))
        } else setError(t('player.error_other'))
    }

    /** The remux engine with the first sound track this browser plays; without sound when none does. */
    const remux = async (p: Probe, at: number): Promise<Engine> => {
        const sound = await playableSound(p.audios)
        if (!sound && p.audios.length) setNotice(t('web.no_sound', { codec: codecName(p.audios[0]) }))
        setAudio(sound)
        const e = new RemuxEngine(video, p, sound, at, { onError: fail, onDetail: (d) => setDetails(`${p.container}. ${d}`) })
        if (at) video.currentTime = at
        return e
    }

    const unload = () => {
        engine?.destroy()
        engine = null
        probed?.input.dispose()
        probed = null
        if (file) {
            streams.delete(file.key)
            file.close()
            file = null
        }
    }

    /** Where the source being opened starts, until it has opened: a retry starts there too. */
    let openingAt = 0
    /** A load failed for want of a connection: it runs again once the browser is back online. */
    let retryOnline = false

    /** Opens `src` at `at` seconds; `play` starts it once it can. */
    const load = async (src: Source, at: number, play: boolean) => {
        const gen = ++generation
        openingAt = at
        loadStarted = performance.now()
        framed = false
        retryOnline = false
        // A new source resets the element's speed; the one chosen carries over.
        const rate = video.playbackRate
        unload()
        video.defaultPlaybackRate = rate
        setError(null)
        setStall(null)
        setSource(src)
        const f = openFile(src)
        file = f
        streams.set(src.key, f)
        f.warmUp()
        try {
            const p = await probe(f, src.duration).catch((e) => {
                if (e instanceof Unplayable && e.why.kind === 'format' && LIBAV_FORMATS.has(e.why.format)) return e.why.format
                throw e
            })
            if (typeof p === 'string') return await software(f, p, at, play, gen, rate)
            if (gen !== generation) return p.input.dispose()
            probed = p
            setAudios(p.audios)
            setAudio(p.audios[0] ?? null)
            if (route(p) === 'native') {
                const url = `/stream/${encodeURIComponent(src.key)}?size=${src.size}&mime=${encodeURIComponent(p.mime.split(';')[0])}`
                engine = nativeEngine(video, url, at)
            } else engine = await remux(p, at)
            if (gen !== generation) return
            video.playbackRate = rate
            setSwitchable(!!engine.setAudio)
            setDetails(`${p.container}, ${p.mime.replace(/^[^;]*;\s*/, '')}. ${engine.detail}`)
            if (play) video.play().catch(() => {})
        } catch (e) {
            if (gen === generation) fail(e)
        }
    }

    // A native element that refuses the file hands over to the remux engine at the same place.
    const onVideoError = async () => {
        if (engine?.name !== 'native' || !probed) return
        const p = probed
        const at = video.currentTime
        engine.destroy()
        engine = null
        try {
            if (!p.remuxable) throw new Unplayable({ kind: 'picture', codec: p.videoCodec })
            engine = await remux(p, at)
            setSwitchable(true)
            setDetails(`${p.container}. ${engine.detail} (the browser refused it as it is)`)
            video.play().catch(() => {})
        } catch (e) {
            fail(e)
        }
    }

    /** A file only libav.js opens (AVI): its own engine, which reads the header itself. */
    const software = async (f: TgFile, container: string, at: number, play: boolean, gen: number, rate: number) => {
        const e = await LibavEngine.open(f, video, container, source().duration, at, {
            onError: fail,
            onDetail: (d) => setDetails(`${container}. ${d}`),
        })
        if (gen !== generation) return e.destroy()
        engine = e
        const sound = e.sounds.find((s) => s.decodable) ?? null
        if (!sound && e.sounds.length) setNotice(t('web.no_sound', { codec: codecName(e.sounds[0]) }))
        setAudios(e.sounds)
        setAudio(sound)
        if (at) video.currentTime = at
        video.playbackRate = rate
        setSwitchable(true)
        setDetails(`${container}. ${e.detail}`)
        if (play) video.play().catch(() => {})
    }

    /** The same source again at the same second: Retry after a stall, a lost connection or a quality pick. */
    const reload = (src = source()) => load(src, engine ? video.currentTime || 0 : openingAt, !video.paused || stall() !== null)

    const whyText = (why: Why) =>
        why.kind === 'format'
            ? t('web.needs_app_format', { format: why.format === 'unknown' ? '?' : why.format })
            : why.kind === 'picture'
              ? t('web.needs_app_picture', { codec: why.codec })
              : why.kind === 'no-mse'
                ? t('web.needs_app_no_mse')
                : t('web.needs_app_codec')

    // Telegram's storyboard as the seek bar's preview, fetched once the picture is up.
    let storyboardAsked = false
    const addStoryboard = async () => {
        if (storyboardAsked || !item.storyboard) return
        storyboardAsked = true
        try {
            const sb = await storyboardTrack(item.storyboard, source().duration || video.duration)
            storyboardUrls = sb.urls
            const track = document.createElement('track')
            track.kind = 'metadata'
            track.label = 'thumbnails'
            track.src = sb.vtt
            track.default = true
            video.append(track)
        } catch (e) {
            console.warn('[player] storyboard', e)
        }
    }

    const loadSubtitle = async (picked: File) => {
        try {
            const sub = await readSubtitle(picked)
            subtitleUrls.push(sub.url)
            const track = document.createElement('track')
            track.kind = 'subtitles'
            track.label = sub.label
            if (sub.language) track.srclang = sub.language
            track.src = sub.url
            track.addEventListener('load', () => placeCues(controlsUp))
            video.append(track)
            // Shown at once: the one just loaded is the one wanted.
            for (const tt of Array.from(video.textTracks)) if (tt.kind === 'subtitles' || tt.kind === 'captions') tt.mode = tt === track.track ? 'showing' : 'disabled'
            setNotice('')
        } catch (e) {
            setNotice(e instanceof SubtitleError && e.why === 'vobsub' ? t('web.needs_app_picture_subtitles') : t('player.subtitle_load_failed', { file: picked.name }))
        }
    }

    // Subtitles move up while the controls are showing, so the seek bar never covers them.
    let controlsUp = true
    const placeCues = (up: boolean) => {
        controlsUp = up
        for (const tt of Array.from(video.textTracks)) {
            if (tt.kind !== 'subtitles' && tt.kind !== 'captions') continue
            for (const cue of Array.from(tt.cues ?? [])) if (cue instanceof VTTCue) cue.line = up ? -4 : 'auto'
        }
    }

    const pickAudio = async (index: number) => {
        const track = audios()[index]
        if (!track || track === audio() || !engine?.setAudio) return
        if (!(await engine.setAudio(track))) {
            setNotice(t('web.sound_unplayable', { codec: codecName(track) }))
            return
        }
        setNotice('')
        setAudio(track)
    }

    const pickQuality = (key: string) => {
        if (key === source().key) return
        const copy = copies.find((c) => c.key === key)
        const src: Source = copy ? { key: copy.key, size: copy.size, duration: item.duration, height: copy.height } : { key: item.key, size: item.size, duration: item.duration, height: 0 }
        setNotice(t('player.quality_switched', { quality: qualityLabel(src.height) }))
        reload(src)
    }
    const qualityLabel = (height: number) => (height ? t('detail.telegram_quality', { quality: `${height}p` }) : t('web.quality_original'))

    const trackLabel = (tr: SoundTrack, i: number) => {
        const lang = tr.languageCode && tr.languageCode !== 'und' ? tr.languageCode.toUpperCase() : ''
        const parts = [tr.name, lang, codecName(tr), tr.numberOfChannels ? t('player.channels_count', { count: tr.numberOfChannels }) : ''].filter(Boolean)
        return parts.length ? parts.join(', ') : t('player.track_number', { number: i + 1 })
    }

    const play = (next: VideoItem | null) => {
        if (next) props.onPlay(next)
    }

    // Hold on the picture: double speed while held, back on release.
    let holdTimer = 0
    let heldRate = 1
    const holdStart = (e: PointerEvent) => {
        if (e.button !== 0 || video.paused) return
        clearTimeout(holdTimer)
        holdTimer = window.setTimeout(() => {
            heldRate = video.playbackRate
            video.playbackRate = 2
            setHolding(true)
        }, HOLD_MS)
    }
    const holdEnd = () => {
        clearTimeout(holdTimer)
        if (!holding()) return
        video.playbackRate = heldRate
        setHolding(false)
    }

    // Loaded ahead and the arrival speed, once a second while the page is seen; also the stall watch.
    let tick = 0
    let lastReceived = 0
    let lastTime = -1
    let stuckSince = 0
    let everPlayed = false
    const sample = () => {
        if (document.hidden) return
        if (!video.paused) refreshKept()
        const f = file
        const received = f?.received ?? 0
        const rate = received - lastReceived
        lastReceived = received
        const b = video.buffered
        let ahead = 0
        for (let i = 0; i < b.length; i++) if (video.currentTime >= b.start(i) - 0.5 && video.currentTime <= b.end(i)) ahead = b.end(i) - video.currentTime
        setReadout(`${t('player.details_buffered')}: ${t('player.seek_seconds', { seconds: Math.round(ahead) })}  ·  ${speedText(Math.max(0, rate))}`)
        // Waiting to play with the clock not moving: say so after a while, as the app does.
        // Before the first frame the element is paused with nothing in it: that is waiting too.
        const waiting = (!everPlayed || !video.paused) && video.readyState < 3
        if (video.currentTime !== lastTime || !waiting || error() || needsApp()) {
            lastTime = video.currentTime
            stuckSince = performance.now()
            if (stall()) setStall(null)
        } else if (!navigator.onLine && performance.now() - stuckSince > 2000) setStall('offline')
        else if (performance.now() - stuckSince > STALL_MS && !stall()) setStall(everPlayed ? 'mid' : 'start')
    }

    // Hidden (another app, a closed tab, a reload): the place is saved now, not at the next 10 s.
    const onHidden = () => document.visibilityState === 'hidden' && savePosition(video.currentTime)
    const onLeave = () => savePosition(video.currentTime)
    const onOtherTab = (e: MessageEvent<{ playing?: boolean }>) => e.data?.playing && !video.paused && video.pause()
    // Offline: a line while what is buffered plays on; the overlay only once playback is stuck.
    let wentOffline = false
    const onOffline = () => {
        wentOffline = true
        setNotice(t('player.offline_waiting'))
    }
    const onOnline = () => {
        if (!wentOffline) return
        wentOffline = false
        const back = t('player.back_online')
        setNotice(back)
        window.setTimeout(() => notice() === back && setNotice(''), 4000)
        if (retryOnline || stall() || error()) reload()
    }

    let saveTimer = 0
    /** Closed while the saved place was still being read: nothing may start after that. */
    let closed = false
    const watchControls = new MutationObserver(() => placeCues(!!container.querySelector('.controls-content[data-visible]')))
    onMount(async () => {
        const left = (await getResume(item.chatId, item.messageId))?.position ?? 0
        savedMs = left > 0 ? left * 1000 : null
        const at = left > 0 ? (await api.resumeStart(left * 1000, rewindMs()).catch(() => left * 1000)) / 1000 : 0
        if (closed) return
        video.playbackRate = chosenRate
        if (at > 0) {
            setStartOver(true)
            window.setTimeout(() => setStartOver(false), 10_000)
        }
        load(source(), at, true)
        saveTimer = window.setInterval(() => savePosition(video.currentTime), 10_000)
        tick = window.setInterval(sample, 1000)
        window.addEventListener('offline', onOffline)
        window.addEventListener('online', onOnline)
        document.addEventListener('visibilitychange', onHidden)
        window.addEventListener('pagehide', onLeave)
        tabs?.addEventListener('message', onOtherTab)
        document.addEventListener('keydown', onKey)
        document.addEventListener('fullscreenchange', onFullscreen)
        const controls = container.querySelector('.controls-content')!
        watchControls.observe(controls, { attributes: true, attributeFilter: ['data-visible'] })
        container.focus({ preventScroll: true })
    })

    // The lock screen and media keys: its name, the jumps, previous and next.
    createEffect(() => {
        if (!('mediaSession' in navigator)) return
        const info = props.info
        const ep = info?.episode
        navigator.mediaSession.metadata = new MediaMetadata({ title: ep ? `${ep.code}${ep.name ? ` ${ep.name}` : ''}` : (info?.title ?? item.title), artist: ep?.show ?? item.chat })
        navigator.mediaSession.setActionHandler('seekbackward', () => (video.currentTime = Math.max(0, video.currentTime - 10)))
        navigator.mediaSession.setActionHandler('seekforward', () => (video.currentTime += 10))
        navigator.mediaSession.setActionHandler('seekto', (d) => d.seekTime != null && (video.currentTime = d.seekTime))
        navigator.mediaSession.setActionHandler('previoustrack', info?.previous ? () => play(info.previous) : null)
        navigator.mediaSession.setActionHandler('nexttrack', info?.next ? () => play(info.next) : null)
    })

    // Near the end: the next episode starts arriving, and its card comes up.
    let nextWarmed = false
    const onTime = () => {
        const next = props.info?.next
        const d = video.duration
        if (!next || !isFinite(d) || d < NEXT_AHEAD * 2) return
        const near = d - video.currentTime <= NEXT_AHEAD
        if (near && !nextWarmed) {
            nextWarmed = true
            warmFile(next)
        }
        setNextCard(near)
    }
    createEffect(on(() => props.info?.next?.key, () => setNextDismissed(false), { defer: true }))

    onCleanup(() => {
        closed = true
        watchControls.disconnect()
        savePosition(video.currentTime)
        clearInterval(saveTimer)
        clearInterval(tick)
        clearTimeout(holdTimer)
        window.removeEventListener('offline', onOffline)
        window.removeEventListener('online', onOnline)
        document.removeEventListener('visibilitychange', onHidden)
        window.removeEventListener('pagehide', onLeave)
        tabs?.removeEventListener('message', onOtherTab)
        document.removeEventListener('keydown', onKey)
        document.removeEventListener('fullscreenchange', onFullscreen)
        generation++
        unload()
        for (const u of [...subtitleUrls, ...storyboardUrls]) URL.revokeObjectURL(u)
        if (document.fullscreenElement) document.exitFullscreen().catch(() => {})
        if ('mediaSession' in navigator) {
            navigator.mediaSession.metadata = null
            for (const a of ['previoustrack', 'nexttrack', 'seekbackward', 'seekforward', 'seekto'] as const) navigator.mediaSession.setActionHandler(a, null)
        }
    })

    function onFullscreen() {
        setFullscreen(!!document.fullscreenElement)
    }

    const close = () => {
        // Saved before leaving: Back may leave the site, and then no cleanup runs.
        savePosition(video.currentTime)
        if (document.fullscreenElement) document.exitFullscreen().catch(() => {})
        props.onClose()
    }

    // The keys Video.js has no action for: Escape closes, Shift+N and Shift+P step episodes.
    function onKey(e: KeyboardEvent) {
        if (e.target instanceof HTMLInputElement && e.target.type !== 'range') return
        if (e.key === 'Escape') {
            if (document.fullscreenElement || document.querySelector('media-menu[data-open]')) return
            close()
        } else if (e.shiftKey && e.key.toLowerCase() === 'n') play(props.info?.next ?? null)
        else if (e.shiftKey && e.key.toLowerCase() === 'p') play(props.info?.previous ?? null)
        else return
        e.preventDefault()
    }

    const showSettings = () => !needsApp()
    const episodeLine = () => {
        const ep = props.info?.episode
        return ep ? `${ep.code}${ep.name ? `  ·  ${ep.name}` : ''}` : ''
    }

    return (
        <video-player class="player-root">
            <media-container
                ref={container}
                class="player"
                classList={{ blocked: !!needsApp(), holding: holding() }}
                tabIndex={-1}
                onPointerDown={(e: PointerEvent) => (e.target === video ? holdStart(e) : undefined)}
                onPointerUp={holdEnd}
                onPointerCancel={holdEnd}
                onPointerLeave={holdEnd}
            >
                <video
                    ref={video}
                    playsinline
                    preload="auto"
                    onPlaying={() => {
                        if (!framed) {
                            framed = true
                            timing('first frame, from the page opening', mounted)
                            timing('first frame, from the load', loadStarted)
                        } else if (seekStarted) timing('seek, until playing', seekStarted)
                        seekStarted = 0
                        everPlayed = true
                        setStall(null)
                        addStoryboard()
                        if (probed && engine) setDetails(`${probed.container}, ${probed.mime.replace(/^[^;]*;\s*/, '')}. ${engine.detail}`)
                    }}
                    onPlay={() => {
                        if (file) file.wantAhead = true
                        tabs?.postMessage({ playing: true })
                    }}
                    onPause={() => {
                        savePosition(video.currentTime)
                        // Paused for a minute: stop reading ahead.
                        const f = file
                        window.setTimeout(() => {
                            if (video.paused && f) f.wantAhead = false
                        }, 60_000)
                    }}
                    onTimeUpdate={onTime}
                    onRateChange={() => !holding() && (chosenRate = video.playbackRate)}
                    onSeeking={() => framed && (seekStarted = performance.now())}
                    onSeeked={() => {
                        if (seekStarted) timing('seek, until the frame', seekStarted)
                        refreshKept()
                    }}
                    onError={onVideoError}
                    onEnded={() => {
                        savePosition(video.duration)
                        if (props.info?.next) setNextCard(true)
                    }}
                />

                <media-gesture type="tap" action="togglePaused" pointer="mouse" />
                <media-gesture type="doubletap" action="toggleFullscreen" pointer="mouse" />
                <media-gesture type="doubletap" action="seekStep" attr:value="-10" region="left" pointer="touch" />
                <media-gesture type="doubletap" action="seekStep" attr:value="10" region="right" pointer="touch" />
                <media-hotkey target="document" keys="Space" action="togglePaused" />
                <media-hotkey target="document" keys="k" action="togglePaused" />
                <media-hotkey target="document" keys="m" action="toggleMuted" />
                <media-hotkey target="document" keys="f" action="toggleFullscreen" />
                <media-hotkey target="document" keys="c" action="toggleSubtitles" />
                <media-hotkey target="document" keys="ArrowLeft" action="seekStep" attr:value="-5" />
                <media-hotkey target="document" keys="ArrowRight" action="seekStep" attr:value="10" />
                <media-hotkey target="document" keys="j" action="seekStep" attr:value="-10" />
                <media-hotkey target="document" keys="l" action="seekStep" attr:value="10" />
                <media-hotkey target="document" keys="ArrowUp" action="volumeStep" attr:value="0.05" />
                <media-hotkey target="document" keys="ArrowDown" action="volumeStep" attr:value="-0.05" />
                <media-hotkey target="document" keys="]" action="speedUp" />
                <media-hotkey target="document" keys="[" action="speedDown" />
                <media-hotkey target="document" keys="0-9" action="seekToPercent" />

                <media-buffering-indicator class="buffering">
                    <div class="spinner" aria-label={t('player.buffering')} />
                </media-buffering-indicator>
                <media-seek-indicator class="seek-indicator">
                    <media-seek-indicator-value />
                </media-seek-indicator>
                <Show when={holding()}>
                    <div class="hold-badge" role="status">{t('format.speed_factor', { speed: 2 })}</div>
                </Show>

                <Show when={error()}>
                    <div class="player-error" role="alert">
                        <p>{error()}</p>
                        <div class="actions">
                            <button class="primary" onClick={() => reload()}>{t('player.reload')}</button>
                            <button onClick={close}>{t('common.back')}</button>
                        </div>
                    </div>
                </Show>
                <Show when={stall() && !error() && !needsApp()}>
                    <div class="player-error stall" role="alert">
                        <Show
                            when={stall() !== 'offline'}
                            fallback={<p>{t('player.offline_waiting')}</p>}
                        >
                            <h2>{stall() === 'start' ? t('player.stalled_title') : t('player.stalled_mid_title')}</h2>
                            <p>{stall() === 'start' ? t('player.stalled_slow') : t('player.stalled_mid_slow')}</p>
                            <div class="actions">
                                <button class="primary" onClick={() => reload()}>{t('player.reload')}</button>
                                <Show when={copies.length && source().height === 0}>
                                    <button onClick={() => pickQuality(copies[copies.length - 1].key)}>
                                        {t('player.lighter', { quality: `${copies[copies.length - 1].height}p` })}
                                    </button>
                                </Show>
                            </div>
                        </Show>
                    </div>
                </Show>
                <Show when={needsApp()}>
                    {(why) => (
                        <div class="player-error needs-app" role="alert">
                            <h2>{t('web.needs_app_title')}</h2>
                            <p>{whyText(why())}</p>
                            <p class="muted small">{t('web.app_body')}</p>
                            <div class="actions">
                                <a class="button primary" href="https://tmplayer.org/#download" target="_blank" rel="noopener">
                                    {t('web.needs_app_get')}
                                </a>
                                <Show when={copies.length && source().height === 0}>
                                    <button onClick={() => {
                                        setNeedsApp(null)
                                        pickQuality(copies[0].key)
                                    }}>
                                        {t('player.lighter', { quality: `${copies[0].height}p` })}
                                    </button>
                                </Show>
                                <button onClick={close}>{t('common.back')}</button>
                            </div>
                        </div>
                    )}
                </Show>
                <Show when={notice() && !needsApp() && !error()}>
                    <div class="player-notice" role="status">
                        <p>{notice()}</p>
                        <button class="icon" onClick={() => setNotice('')} aria-label={t('common.close')}>
                            <Icon icon={Cancel01Icon} size={20} />
                        </button>
                    </div>
                </Show>
                <Show when={startOver() && !needsApp()}>
                    <button
                        class="float-chip start-over"
                        onClick={() => {
                            video.currentTime = 0
                            setStartOver(false)
                        }}
                    >
                        <Icon icon={RepeatIcon} size={18} />
                        {t('player.start_over')}
                    </button>
                </Show>
                <Show when={props.info?.next && nextCard() && !nextDismissed()}>
                    <div class="next-card" role="group" aria-label={t('player.next_episode')}>
                        <div class="next-text">
                            <div class="muted small">{t('player.next_episode')}</div>
                            <div class="next-name">{props.info!.nextCode ?? props.info!.next!.title}</div>
                        </div>
                        <button class="primary" onClick={() => play(props.info!.next)}>
                            <Icon icon={PlayIcon} size={18} />
                            {t('player.play_now')}
                        </button>
                        <button class="icon" onClick={() => setNextDismissed(true)} aria-label={t('common.close')}>
                            <Icon icon={Cancel01Icon} size={20} />
                        </button>
                    </div>
                </Show>

                <media-controls class="controls-layer">
                    <media-controls-backdrop class="controls-backdrop" />
                    <media-controls-content class="controls-content">
                        <media-controls-group class="top-bar">
                            <button class="icon" onClick={close} aria-label={t('common.back')} title={t('player.back_hint')}>
                                <Icon icon={ArrowLeft02Icon} />
                            </button>
                            <div class="title">
                                <div class="title-name">{props.info?.title ?? item.title}</div>
                                <div class="muted small">{episodeLine() || item.chat}</div>
                            </div>
                        </media-controls-group>
                        <media-controls-group class="bottom-bar">
                            <media-time-slider class="time-slider">
                                <media-slider-track class="slider-track">
                                    <media-slider-buffer class="slider-buffer" />
                                    <media-slider-fill class="slider-fill" />
                                </media-slider-track>
                                <media-slider-thumb class="slider-thumb" />
                                <media-slider-preview class="slider-preview">
                                    <media-slider-thumbnail class="slider-thumbnail" />
                                    <media-slider-value type="pointer" class="slider-value" />
                                </media-slider-preview>
                            </media-time-slider>
                            <div class="row">
                                <Show when={props.info?.previous}>
                                    <button class="icon" onClick={() => play(props.info!.previous)} aria-label={t('player.previous_episode')} title={t('player.previous_episode_hint', { episode: props.info!.previousCode ?? '' })}>
                                        <Icon icon={PreviousIcon} />
                                    </button>
                                </Show>
                                <media-seek-button class="icon" seconds={-10}>
                                    <Icon icon={GoBackward10SecIcon} />
                                </media-seek-button>
                                <media-play-button class="icon play">
                                    <span class="when-paused"><Icon icon={PlayIcon} size={28} /></span>
                                    <span class="when-playing"><Icon icon={PauseIcon} size={28} /></span>
                                </media-play-button>
                                <media-seek-button class="icon" seconds={10}>
                                    <Icon icon={GoForward10SecIcon} />
                                </media-seek-button>
                                <Show when={props.info?.next}>
                                    <button class="icon" onClick={() => play(props.info!.next)} aria-label={t('player.next_episode')} title={t('player.next_episode_hint', { episode: props.info!.nextCode ?? '' })}>
                                        <Icon icon={NextIcon} />
                                    </button>
                                </Show>
                                <media-time-group class="time">
                                    <media-time type="current" />
                                    <media-time-separator />
                                    <media-time type="duration" class="muted" />
                                </media-time-group>
                                <div class="spacer" />
                                <media-mute-button class="icon">
                                    <span class="when-sound"><Icon icon={VolumeHighIcon} /></span>
                                    <span class="when-muted"><Icon icon={VolumeMute02Icon} /></span>
                                </media-mute-button>
                                <Show when={showSettings()}>
                                    <button type="button" class="icon" commandfor="player-menu" aria-label={t('player.playback_options')} title={t('player.playback_options')}>
                                        <Icon icon={Settings02Icon} />
                                    </button>
                                </Show>
                                <media-fullscreen-button class="icon">
                                    <Show when={fullscreen()} fallback={<Icon icon={FullScreenIcon} />}>
                                        <Icon icon={MinimizeScreenIcon} />
                                    </Show>
                                </media-fullscreen-button>
                            </div>
                        </media-controls-group>
                    </media-controls-content>
                </media-controls>

                <media-menu id="player-menu" class="player-menu" side="top" align="end">
                    <media-menu-content class="menu-page">
                        <media-menu-item commandfor="speed-menu" class="menu-row">
                            <span>{t('player.speed')}</span>
                            <span class="menu-value"><span data-part="hint" /><Icon icon={ArrowRight01Icon} size={18} /></span>
                        </media-menu-item>
                        <media-menu-item commandfor="subtitles-menu" class="menu-row">
                            <span>{t('player.subtitles')}</span>
                            <span class="menu-value"><span data-part="hint" /><Icon icon={ArrowRight01Icon} size={18} /></span>
                        </media-menu-item>
                        {/* Its own row: the Subtitles page above hides itself while the video has no subtitles. */}
                        <media-menu-item class="menu-row" on:click={() => subtitleInput.click()}>
                            <span>{t('player.load_subtitle_file')}</span>
                        </media-menu-item>
                        <Show when={audios().length > 1 && switchable()}>
                            <media-menu-item commandfor="sound-menu" class="menu-row">
                                <span>{t('player.audio')}</span>
                                <span class="menu-value"><span>{audio() ? trackLabel(audio()!, audios().indexOf(audio()!)) : ''}</span><Icon icon={ArrowRight01Icon} size={18} /></span>
                            </media-menu-item>
                        </Show>
                        <Show when={copies.length}>
                            <media-menu-item commandfor="quality-menu" class="menu-row">
                                <span>{t('series.quality')}</span>
                                <span class="menu-value"><span>{qualityLabel(source().height)}</span><Icon icon={ArrowRight01Icon} size={18} /></span>
                            </media-menu-item>
                        </Show>
                        <div class="menu-readout muted small">
                            <div>{readout()}</div>
                            <div>{details()}</div>
                        </div>
                    </media-menu-content>

                    <media-menu-content id="speed-menu" class="menu-page">
                        <media-menu-item class="menu-back"><Icon icon={ArrowLeft02Icon} size={18} />{t('player.playback_speed')}</media-menu-item>
                        <media-playback-rate-radio-group innerHTML={RADIO_TEMPLATE} />
                    </media-menu-content>

                    <media-menu-content id="subtitles-menu" class="menu-page">
                        <media-menu-item class="menu-back"><Icon icon={ArrowLeft02Icon} size={18} />{t('player.subtitles')}</media-menu-item>
                        <media-captions-radio-group innerHTML={RADIO_TEMPLATE} />
                    </media-menu-content>

                    <Show when={audios().length > 1 && switchable()}>
                        <media-menu-content id="sound-menu" class="menu-page">
                            <media-menu-item class="menu-back"><Icon icon={ArrowLeft02Icon} size={18} />{t('player.audio')}</media-menu-item>
                            <media-menu-radio-group attr:value={String(audios().indexOf(audio()!))} on:value-change={(e: CustomEvent<{ value: string }>) => pickAudio(Number(e.detail.value))}>
                                <For each={audios()}>
                                    {(tr, i) => (
                                        <media-menu-radio-item class="menu-row" attr:value={String(i())}>
                                            <span>{trackLabel(tr, i())}</span>
                                            <media-menu-item-indicator attr:force-mount="" class="menu-check" />
                                        </media-menu-radio-item>
                                    )}
                                </For>
                            </media-menu-radio-group>
                        </media-menu-content>
                    </Show>

                    <Show when={copies.length}>
                        <media-menu-content id="quality-menu" class="menu-page">
                            <media-menu-item class="menu-back"><Icon icon={ArrowLeft02Icon} size={18} />{t('series.quality')}</media-menu-item>
                            <media-menu-radio-group attr:value={source().key} on:value-change={(e: CustomEvent<{ value: string }>) => pickQuality(e.detail.value)}>
                                <For each={[{ key: item.key, height: 0 }, ...copies]}>
                                    {(c) => (
                                        <media-menu-radio-item class="menu-row" attr:value={c.key}>
                                            <span>{qualityLabel(c.height)}</span>
                                            <media-menu-item-indicator attr:force-mount="" class="menu-check" />
                                        </media-menu-radio-item>
                                    )}
                                </For>
                            </media-menu-radio-group>
                            <div class="menu-readout muted small">{t('player.copies_note')}</div>
                        </media-menu-content>
                    </Show>
                </media-menu>

                <input
                    ref={subtitleInput}
                    type="file"
                    hidden
                    accept={SUBTITLE_EXTENSIONS.map((e) => `.${e}`).join(',')}
                    onChange={(e) => {
                        const picked = e.currentTarget.files?.[0]
                        e.currentTarget.value = ''
                        if (picked) loadSubtitle(picked)
                    }}
                />
            </media-container>
        </video-player>
    )
}
