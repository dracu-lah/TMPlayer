import { createEffect, createSignal, onCleanup, onMount, Show, type JSX } from 'solid-js'
import '@videojs/html/video/player'
import '@videojs/html/ui/container'
import { Alert02Icon, Cancel01Icon } from '@hugeicons/core-free-icons'
import { openFile, streams, warmFile, type TgFile } from '../stream/reader'
import { codecName, keyFrames, nativeEngine, playableSound, probe, RemuxEngine, route, Unplayable, type Engine, type Probe, type SoundTrack, type Why } from './engines'
import { LIBAV_FORMATS, LibavEngine } from './libav'
import { MoviEngine, moviSupported } from './movi'
import { storyboardFrames, type Frames } from './storyboard'
import { appleTouch, FrameGrabber, PICTURES_SHOWN } from './seekpreview'
import { Chrome, variant } from './Chrome'
import { OpeningLoader } from './loader'
import { readChapters, type Chapter } from './chapters'
import { applyDelay, look, placeCues, lookVars, setLook, setSubDelay } from './cues'
import { readLook } from './keep'
import { loadThumb } from '../screens/thumbs'
import { readSubtitle, SUBTITLE_EXTENSIONS, SubtitleError } from './subtitles'
import { api } from '../tg/rpc'
import { t } from '../i18n'
import { routeCrashes } from '../crash'
import { form } from '../form'
import { Icon } from '../ui/Icon'
import { dropResume, getResume, isWatched, saveResume as storeResume, setWatched, settings } from '../store'
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
/** How long the picture has to move on after a failure nobody caught, before the player says it broke. */
const CRASH_GRACE_MS = 8_000

/** Local testing only: start and seek times, in the console and in `window.playerTimes`. */
const timing =
    location.hostname === 'localhost'
        ? (label: string, since: number) => {
              const ms = Math.round(performance.now() - since)
              console.debug('[player]', label, ms, 'ms')
              ;((window as unknown as { playerTimes?: { label: string; ms: number }[] }).playerTimes ??= []).push({ label, ms })
          }
        : () => {}

/** The speed last chosen in this tab: the next episode (or any video opened after) plays at it too. */
let chosenRate = 1

/** Only one tab plays at a time: a tab that starts playing tells the others, which pause. */
const tabs = typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel('tmplayer-player') : null

/** Telegram's copies this browser can play: H.264 always, AV1 where it decodes it. */
function playableCopies(copies: VideoCopy[]): VideoCopy[] {
    const av1 = typeof MediaSource !== 'undefined' && MediaSource.isTypeSupported('video/mp4; codecs="av01.0.08M.08"')
    return copies.filter((c) => c.codec !== 'av01' || av1)
}

/** A transparent pixel: a poster of nothing. */
const BLANK_POSTER = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7'

function speedText(bytesPerSecond: number): string {
    const mb = bytesPerSecond / 1e6
    return mb >= 1 ? t('format.speed_mb', { speed: mb.toFixed(1) }) : t('format.speed_kb', { speed: Math.round(bytesPerSecond / 1e3) })
}

/**
 * The player: our own `<video>`, which the engines in engines.ts feed, inside Video.js 10's player
 * element, under the apps' own controls (Chrome.tsx). Quality switches between the posted file and
 * Telegram's copies at the same second; a subtitle file from the device becomes a text track; the
 * next episode is fetched ahead near the end. A start that stalls, a failure and a file the browser
 * cannot play get the app's status sheet instead of the controls.
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
    const [ready, setReady] = createSignal(false)
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
    // The numbers behind the Details sheet's buffer line; the words are made only while that sheet reads them.
    const [sampled, setSampled] = createSignal<[number, number]>([0, 0])
    const readout = () => `${t('player.details_buffered')}: ${t('player.seek_seconds', { seconds: Math.round(sampled()[0]) })}  ·  ${speedText(Math.max(0, sampled()[1]))}`
    const [stall, setStall] = createSignal<'start' | 'mid' | 'offline' | null>(null)
    /** Where the video was resumed from (seconds), for the Start over offer. */
    const [resumedAt, setResumedAt] = createSignal<number | null>(null)
    /** The opening loader's stage (loader.tsx); null once the first frame is in, which ends it for good. */
    const [opening, setOpening] = createSignal<number | null>(0)
    const advance = (stage: number) => setOpening((s) => (s === null ? null : Math.max(s, stage)))
    const [frames, setFrames] = createSignal<Frames | null>(null)
    /** The video's own frames for the seek bar's card, for a natively played file (see [FrameGrabber]). */
    const [grabber, setGrabber] = createSignal<FrameGrabber | null>(null)
    const subtitleUrls: string[] = []
    const [chapters, setChapters] = createSignal<Chapter[]>([])
    /** How much of the file has arrived (0 to 1); null once all of it has. */
    const [loaded, setLoaded] = createSignal<number | null>(null)
    const [watched, setWatchedNow] = createSignal(false)
    /** Marked watched from the menu: the place is not saved again on leaving, or the video would be back in Continue. */
    let markedWatched = false

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
        if (!(position > 0) || markedWatched) return
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

    /** Said once per video: the picture is made here more slowly than it plays. */
    let saidSlow = ''
    const slowHere = () => {
        if (saidSlow === source().key) return
        saidSlow = source().key
        setNotice(t('web.slow_decode'))
    }

    /** The remux engine with the first sound track this browser plays; without sound when none does. */
    const remux = async (p: Probe, at: number): Promise<Engine> => {
        const sound = await playableSound(p.audios)
        if (!sound && p.audios.length) setNotice(t('web.no_sound', { codec: codecName(p.audios[0]) }))
        setAudio(sound)
        const e = new RemuxEngine(video, p, sound, at, { onError: (err) => void remuxFailed(err), onDetail: (d) => setDetails(`${p.container}. ${d}`), onSlow: slowHere })
        if (at) video.currentTime = at
        return e
    }

    const unload = () => {
        grabber()?.destroy()
        setGrabber(null)
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
        moviTried = ''
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
            // Local testing: ?engine=movi plays the file through movi whatever the browser could do.
            if (location.hostname === 'localhost' && new URLSearchParams(location.search).get('engine') === 'movi') {
                return await fallback(new Error('movi asked for'), f, at, play, gen, rate)
            }
            const p = await probe(f, src.duration).catch((e) => {
                if (e instanceof Unplayable && e.why.kind === 'format' && LIBAV_FORMATS.has(e.why.format)) return e.why.format
                throw e
            })
            if (typeof p === 'string') return await fallback(new Unplayable({ kind: 'format', format: p }), f, at, play, gen, rate)
            if (gen !== generation) return p.input.dispose()
            probed = p
            setAudios(p.audios)
            setAudio(p.audios[0] ?? null)
            let way: 'native' | 'remux'
            try {
                way = route(p)
            } catch (e) {
                // The browser plays neither the file nor a repack of it (HEVC without a decoder, say): movi next.
                probed = null
                return await fallback(e, f, at, play, gen, rate, p)
            }
            if (way === 'native') {
                const url = `/stream/${encodeURIComponent(src.key)}?size=${src.size}&mime=${encodeURIComponent(p.mime.split(';')[0])}`
                engine = nativeEngine(video, url, at)
                // A second element on the same stream for the seek bar's frames; not on an iPhone or
                // iPad (Safari's memory and range limits) nor on the TV layout.
                if (PICTURES_SHOWN && !appleTouch && form() !== 'tv') setGrabber(new FrameGrabber(url))
            } else engine = await remux(p, at)
            if (gen !== generation) return
            video.playbackRate = rate
            setSwitchable(!!engine.setAudio)
            setDetails(`${p.container}, ${p.mime.replace(/^[^;]*;\s*/, '')}. ${engine.detail}`)
            if (play) video.play().catch(() => {})
            void findChapters(f, gen)
            // The storyboard as soon as the video is open, so it is in by the time the bar is touched.
            void addStoryboard()
        } catch (e) {
            if (gen !== generation) return
            // A container mediabunny does not open at all goes to movi too.
            if (e instanceof Unplayable && e.why.kind === 'format' && file === f) return void fallback(e, f, at, play, gen, rate).catch(fail)
            fail(e)
        }
    }

    /** The source movi was tried on, so a failure there is not handed to movi again. */
    let moviTried = ''

    /**
     * What the browser cannot play: movi (FFmpeg and WebCodecs), then libav.js for its containers,
     * then the reason the video needs the app. A loader stays up throughout; only the last step
     * shows anything else.
     */
    const fallback = async (why: unknown, f: TgFile, at: number, play: boolean, gen: number, rate: number, p: Probe | null = null) => {
        const container = why instanceof Unplayable && why.why.kind === 'format' ? why.why.format : null
        // AVI: libav.js first, which plays through the element (picture in picture, AirPlay); movi when it cannot.
        if (container && LIBAV_FORMATS.has(container)) {
            try {
                return await software(f, container, at, play, gen, rate)
            } catch (e) {
                if (gen !== generation) return
                console.warn('[player] libav.js could not play it', e)
                why = e
            }
        }
        if (moviSupported() && moviTried !== f.key) {
            moviTried = f.key
            try {
                return await viaMovi(f, at, play, gen, rate, p)
            } catch (e) {
                if (gen !== generation) return
                console.warn('[player] movi could not play it', e)
            }
        } else p?.input.dispose()
        throw why
    }

    /** movi on `f`; mediabunny's reading of the file (`p`), when there is one, finds its key frames and goes with the engine. */
    const viaMovi = async (f: TgFile, at: number, play: boolean, gen: number, rate: number, p: Probe | null) => {
        const e = await MoviEngine.open(
            f,
            video,
            at,
            {
                onError: (err) => {
                    if (engine !== e) return
                    console.warn('[player] movi stopped', err)
                    fail(err)
                },
                onDetail: (d) => setDetails(d),
                onSlow: slowHere,
            },
            p ? keyFrames(p) : null,
            () => p?.input.dispose(),
        ).catch((err) => {
            p?.input.dispose()
            throw err
        })
        if (gen !== generation) return e.destroy()
        grabber()?.destroy()
        setGrabber(null)
        engine = e
        setAudios(e.sounds)
        setAudio(e.sounds[0] ?? null)
        video.playbackRate = rate
        setSwitchable(e.sounds.length > 1)
        setDetails(e.detail)
        if (play) video.play().catch(() => {})
        void findChapters(f, gen)
        void addStoryboard()
    }

    /**
     * The repack failed part way: movi at the same second when the browser turned the media down (a
     * codec, a broken stream); Telegram's and the network's failures are said as they are.
     */
    const remuxFailed = async (err: unknown) => {
        const f = file
        const media = err instanceof Unplayable || err instanceof DOMException || /could not take this part|MediaSource|SourceBuffer|decod|codec|hevc/i.test(String((err as Error)?.message ?? err))
        if (!f || engine?.name !== 'remux' || !media) return fail(err)
        const gen = generation
        const at = video.currentTime || openingAt
        const rate = video.playbackRate
        const play = !video.paused
        console.warn('[player] repack failed, trying movi', err)
        engine.destroy()
        engine = null
        const p = probed
        probed = null
        try {
            await fallback(err, f, at, play, gen, rate, p)
        } catch (e) {
            if (gen === generation) fail(e)
        }
    }

    // A native element that refuses the file hands over to the remux engine at the same place.
    const onVideoError = async () => {
        if (engine?.name !== 'native' || !probed) return
        const p = probed
        const at = video.currentTime
        grabber()?.destroy()
        setGrabber(null)
        engine.destroy()
        engine = null
        try {
            if (!p.remuxable) {
                probed = null
                if (file) return await fallback(new Unplayable({ kind: 'picture', codec: p.videoCodec }), file, at, true, generation, video.playbackRate, p)
                p.input.dispose()
                throw new Unplayable({ kind: 'picture', codec: p.videoCodec })
            }
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

    /** The file's chapter marks, read from its head or tail, which the opening has fetched already. */
    const findChapters = async (f: TgFile, gen: number) => {
        setChapters([])
        const list = await readChapters(f, navigator.language)
        if (gen === generation && list.length) setChapters(list)
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

    // Telegram's storyboard for the seek bar's preview card, fetched once the picture is up.
    let storyboardAsked = false
    const addStoryboard = async () => {
        if (!PICTURES_SHOWN || storyboardAsked || !item.storyboard) return
        storyboardAsked = true
        try {
            const f = await storyboardFrames(item.storyboard)
            if (closed) return URL.revokeObjectURL(f.url)
            setFrames(f)
        } catch (e) {
            console.warn('[player] storyboard', e)
        }
    }

    /** A subtitle file laid over the video and shown at once; throws when it cannot be read (MSone's sheet says so itself). */
    const addSubtitle = async (picked: File, as?: { label: string; language: string }) => {
        const sub = await readSubtitle(picked)
        if (closed) return URL.revokeObjectURL(sub.url)
        subtitleUrls.push(sub.url)
        const track = document.createElement('track')
        track.kind = 'subtitles'
        track.label = as?.label ?? sub.label
        const language = as?.language ?? sub.language
        if (language) track.srclang = language
        track.src = sub.url
        track.addEventListener('load', () => (placeCues(video, controlsUp), applyDelay(video)))
        video.append(track)
        // Shown at once: the one just loaded is the one wanted.
        for (const tt of Array.from(video.textTracks)) if (tt.kind === 'subtitles' || tt.kind === 'captions') tt.mode = tt === track.track ? 'showing' : 'disabled'
        setNotice('')
    }
    const loadSubtitle = async (picked: File) => {
        try {
            await addSubtitle(picked)
        } catch (e) {
            setNotice(e instanceof SubtitleError && e.why === 'vobsub' ? t('web.needs_app_picture_subtitles') : t('player.subtitle_load_failed', { file: picked.name }))
        }
    }

    // Subtitles move up while the controls are showing, so the seek bar never covers them; the look's position holds otherwise.
    let controlsUp = true
    const onControls = (up: boolean) => {
        controlsUp = up
        placeCues(video, up)
    }
    // The look: size, colour, edge and box as custom properties on the video, which its cues read.
    let pictureHeight = 0
    const paintLook = () => {
        for (const [name, value] of Object.entries(lookVars(look(), pictureHeight || container.clientHeight || 720))) video.style.setProperty(name, value)
        placeCues(video, controlsUp)
    }
    createEffect(() => (look(), ready() && paintLook()))
    onMount(() => {
        const watch = new ResizeObserver(() => {
            const h = container.clientHeight
            if (Math.abs(h - pictureHeight) > 8) (pictureHeight = h, paintLook())
        })
        watch.observe(container)
        onCleanup(() => watch.disconnect())
    })

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
        setLoaded(f && f.loaded < 0.999 ? f.loaded : null)
        const received = f?.received ?? 0
        const rate = received - lastReceived
        lastReceived = received
        const b = video.buffered
        let ahead = 0
        for (let i = 0; i < b.length; i++) if (video.currentTime >= b.start(i) - 0.5 && video.currentTime <= b.end(i)) ahead = b.end(i) - video.currentTime
        setSampled([ahead, rate])
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
    /** The saved place every 10 s and the stall check every second: running while the video plays. */
    const startTicks = () => {
        if (!saveTimer) saveTimer = window.setInterval(() => savePosition(video.currentTime), 10_000)
        if (!tick) tick = window.setInterval(sample, 1000)
    }
    /** A TV that is paused does nothing by itself (AGENTS.md's low-end rule): the pause saved the place and read the buffer once. */
    const stopTicks = () => {
        clearInterval(saveTimer)
        clearInterval(tick)
        saveTimer = 0
        tick = 0
    }
    /** Closed while the saved place was still being read: nothing may start after that. */
    let closed = false
    onMount(async () => {
        setSubDelay(0)
        readLook().then(setLook, () => {})
        isWatched(item.chatId, item.messageId).then(setWatchedNow, () => {})
        const left = (await getResume(item.chatId, item.messageId))?.position ?? 0
        savedMs = left > 0 ? left * 1000 : null
        const at = left > 0 ? (await api.resumeStart(left * 1000, rewindMs()).catch(() => left * 1000)) / 1000 : 0
        if (closed) return
        video.playbackRate = chosenRate
        if (at > 0) setResumedAt(at)
        load(source(), at, true)
        startTicks()
        window.addEventListener('offline', onOffline)
        window.addEventListener('online', onOnline)
        document.addEventListener('visibilitychange', onHidden)
        window.addEventListener('pagehide', onLeave)
        tabs?.addEventListener('message', onOtherTab)
        container.focus({ preventScroll: true })
    })
    // The controls are drawn once the video element exists.
    onMount(() => setReady(true))

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

    // Near the end: the next episode starts arriving (its card is the controls').
    let nextWarmed = false
    const onTime = () => {
        const next = props.info?.next
        const d = video.duration
        if (!next || !isFinite(d) || d < NEXT_AHEAD * 2 || nextWarmed) return
        if (d - video.currentTime <= NEXT_AHEAD) {
            nextWarmed = true
            warmFile(next)
        }
    }

    // A failure nobody caught while the video is open comes here, not to the page's card (HX19). On the
    // Mi TV one came while a 3D video opened and the video then played on fine, so the player waits:
    // if the picture moves on within a few seconds it was not the video's, and only the log keeps it;
    // if not, the status sheet says so, with Reload (this video again, where it was) and Go back.
    let crashCheck: ReturnType<typeof setTimeout> | undefined
    const unroute = routeCrashes(() => {
        if (crashCheck !== undefined || closed) return
        // Moved on: the picture's clock ticked forward (a looping or seeking video can come back below
        // where it was, so the ticks are counted rather than the times compared).
        let last = video.currentTime
        let moved = 0
        const tick = () => {
            if (video.currentTime !== last && !video.paused) moved++
            last = video.currentTime
        }
        video.addEventListener('timeupdate', tick)
        crashCheck = setTimeout(() => {
            crashCheck = undefined
            video.removeEventListener('timeupdate', tick)
            // Paused by the viewer after it played: still is not stuck.
            if (closed || error() || moved >= 2 || (video.paused && opening() === null)) return
            setError(t('player.error_other'))
        }, CRASH_GRACE_MS)
    })

    onCleanup(() => {
        unroute()
        clearTimeout(crashCheck)
        closed = true
        savePosition(video.currentTime)
        clearInterval(saveTimer)
        clearInterval(tick)
        window.removeEventListener('offline', onOffline)
        window.removeEventListener('online', onOnline)
        document.removeEventListener('visibilitychange', onHidden)
        window.removeEventListener('pagehide', onLeave)
        tabs?.removeEventListener('message', onOtherTab)
        generation++
        unload()
        for (const u of subtitleUrls) URL.revokeObjectURL(u)
        const f = frames()
        if (f) URL.revokeObjectURL(f.url)
        if (document.fullscreenElement) document.exitFullscreen().catch(() => {})
        if ('mediaSession' in navigator) {
            navigator.mediaSession.metadata = null
            for (const a of ['previoustrack', 'nexttrack', 'seekbackward', 'seekforward', 'seekto'] as const) navigator.mediaSession.setActionHandler(a, null)
        }
    })

    const close = () => {
        // Saved before leaving: Back may leave the site, and then no cleanup runs.
        savePosition(video.currentTime)
        if (document.fullscreenElement) document.exitFullscreen().catch(() => {})
        props.onClose()
    }

    /** The status sheet is up: a stall, a failure or a file this browser cannot play. */
    const blocked = () => !!(needsApp() || error() || (stall() && stall() !== 'offline'))
    const sheetTitle = () => (props.info?.episode ? `${props.info.title} ${props.info.episode.code}` : (props.info?.title ?? item.title))
    const lighter = () => (needsApp() ? copies[0] : copies[copies.length - 1])

    return (
        <video-player class="player-root">
            <media-container ref={container} class="player" classList={{ blocked: blocked() }} attr:data-variant={variant()} tabIndex={-1}>
                <video
                    ref={video}
                    playsinline
                    preload="auto"
                    // Android's WebView draws a grey, blurred play button on a video with no poster; the opening loader covers the wait.
                    poster={BLANK_POSTER}
                    onLoadedMetadata={() => advance(1)}
                    onCanPlay={() => {
                        advance(2)
                        // A play the browser held back (no tap to allow it): the controls' Play button, not a loader that never ends.
                        window.setTimeout(() => video.paused && !framed && setOpening(null), 800)
                    }}
                    onPlaying={() => {
                        setOpening(null)
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
                        if (form() === 'tv' && !closed) startTicks()
                        if (file) file.wantAhead = true
                        tabs?.postMessage({ playing: true })
                    }}
                    onPause={() => {
                        savePosition(video.currentTime)
                        if (form() === 'tv' && everPlayed && !closed) (sample(), stopTicks())
                        // Paused for a minute: stop reading ahead.
                        const f = file
                        window.setTimeout(() => {
                            if (video.paused && f) f.wantAhead = false
                        }, 60_000)
                    }}
                    onTimeUpdate={onTime}
                    // A held finger plays at double speed for a moment: that is not a speed chosen.
                    onRateChange={() => video.playbackRate !== 2 && (chosenRate = video.playbackRate)}
                    onSeeking={() => framed && (seekStarted = performance.now())}
                    onSeeked={() => {
                        if (seekStarted) timing('seek, until the frame', seekStarted)
                        refreshKept()
                    }}
                    onError={onVideoError}
                    onEnded={() => savePosition(video.duration)}
                />

                <Show when={ready()}>
                    <Chrome
                        ctl={{
                            video,
                            root: container,
                            item,
                            info: () => props.info,
                            close,
                            play,
                            blocked,
                            audios,
                            audio,
                            canSwitchAudio: () => switchable() && audios().length > 1,
                            pickAudio,
                            trackLabel,
                            copies,
                            sourceKey: () => source().key,
                            sourceHeight: () => source().height,
                            pickQuality,
                            qualityLabel,
                            readout,
                            details,
                            loadSubtitleFile: () => subtitleInput.click(),
                            addSubtitle,
                            frames,
                            grabber,
                            resumedAt,
                            startOver: () => {
                                video.currentTime = 0
                                setResumedAt(null)
                            },
                            onControls,
                            chapters,
                            loaded,
                            watched,
                            markWatched: (on) => {
                                markedWatched = on
                                setWatched(item.chatId, item.messageId, on)
                                setWatchedNow(on)
                            },
                        }}
                    >
                        <Show when={notice() && !blocked()}>
                            <div class="pc-notice" role="status">
                                <p>{notice()}</p>
                                <button type="button" onClick={() => setNotice('')} aria-label={t('common.close')}>
                                    <Icon icon={Cancel01Icon} size={20} />
                                </button>
                            </div>
                        </Show>
                    </Chrome>
                </Show>

                <Show when={opening() !== null && !blocked()}>
                    <OpeningLoader item={item} info={props.info} at={resumedAt() ?? 0} stage={opening()!} onClose={close} />
                </Show>

                <Show when={blocked()}>
                    <StatusSheet
                        item={item}
                        title={sheetTitle()}
                        heading={needsApp() ? t('web.needs_app_title') : error() ? t('player.cant_play') : stall() === 'start' ? t('player.stalled_title') : t('player.stalled_mid_title')}
                        body={needsApp() ? whyText(needsApp()!) : (error() ?? (stall() === 'start' ? t('player.stalled_slow') : t('player.stalled_mid_slow')))}
                        note={needsApp() ? t('web.app_body') : undefined}
                        warning={!!error() || !!needsApp()}
                    >
                        <Show
                            when={needsApp()}
                            fallback={
                                <button type="button" class="pc-sheet-button primary" onClick={() => reload()}>
                                    {error() ? t('player.reload') : t('common.try_again')}
                                </button>
                            }
                        >
                            <a class="pc-sheet-button primary" href="https://tmplayer.org/#download" target="_blank" rel="noopener">
                                {t('web.needs_app_get')}
                            </a>
                        </Show>
                        <Show when={copies.length && source().height === 0}>
                            <button
                                type="button"
                                class="pc-sheet-button"
                                onClick={() => {
                                    const pick = lighter()
                                    setNeedsApp(null)
                                    pickQuality(pick.key)
                                }}
                            >
                                {t('player.lighter', { quality: `${lighter().height}p` })}
                            </button>
                        </Show>
                        <button type="button" class="pc-sheet-button" onClick={close}>{t('player.go_back')}</button>
                    </StatusSheet>
                </Show>

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

/**
 * The player's loading and failure sheet (the app's status_sheet): the video's picture, its name,
 * what happened in a heading and a line, and the ways on as pills in a row that wraps. Stacked on
 * a phone held upright, the picture beside the words where the height is short.
 */
function StatusSheet(props: { item: VideoItem; title: string; heading: string; body: string; note?: string; warning: boolean; children: JSX.Element }) {
    const [art, setArt] = createSignal<string | null>(null)
    onMount(() => {
        if (props.item.thumb && !props.warning) loadThumb(props.item.thumb).then(setArt, () => {})
    })
    return (
        <div class="pc-status" role="alert">
            <div class="pc-status-inner" classList={{ pictured: !!art() }}>
                <Show when={art()}>
                    <img class="pc-status-art" src={art()!} alt="" />
                </Show>
                <div class="pc-status-text">
                    <p class="pc-status-name">{props.title}</p>
                    <Show when={props.warning}>
                        <span class="pc-status-warning">
                            <Icon icon={Alert02Icon} size={40} />
                        </span>
                    </Show>
                    <h2>{props.heading}</h2>
                    <p class="pc-status-body">{props.body}</p>
                    <Show when={props.note}>
                        <p class="pc-status-body">{props.note}</p>
                    </Show>
                    <div class="pc-status-actions">{props.children}</div>
                </div>
            </div>
        </div>
    )
}
