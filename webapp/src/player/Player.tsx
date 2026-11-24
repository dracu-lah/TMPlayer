import { createEffect, createSignal, onCleanup, onMount, Show } from 'solid-js'
import '@videojs/html/video/player'
import '@videojs/html/ui/container'
import { Cancel01Icon } from '@hugeicons/core-free-icons'
import { openFile, openJoined, streams, warmFile, workerAsked, type MediaFile } from '../stream/reader'
import { mountZip, PiecesProblem, ZipProblem } from '../stream/joined'
import { bridgeAsked, tvServiceUrl } from '../stream/tvservice'
import { noteTvNative } from './tvnative'
import { codecName, keyFrames, nativeEngine, pictureForLibav, playableSound, probe, RemuxEngine, route, soundForLibav, Unplayable, type Engine, type Probe, type SoundTrack, type Why } from './engines'
import { LIBAV_FORMATS, LibavEngine } from './libav'
import { MoviEngine, moviSupported } from './movi'
import { storyboardFrames, type Frames } from './storyboard'
import { appleTouch, FrameGrabber, PICTURES_SHOWN } from './seekpreview'
import { Chrome, variant } from './Chrome'
import { OpeningLoader } from './loader'
import { copyDetails, formatLine, type FormatFacts } from './formatline'
import { readChapters, type Chapter } from './chapters'
import { setLook, setSubDelay } from './cues'
import { readLook } from './keep'
import { SUBTITLE_EXTENSIONS, subtitleFromDrop } from './subtitles'
import { warmRestore } from './subpick-use'
import { api, clearFlood, floodLeft, link } from '../tg/rpc'
import { online } from '../net'
import { StallWatch, stallWhy } from './openStall'
import { t, waitPhrase } from '../i18n'
import { watchStutter, type StutterWatch } from './stutter-watch'
import { form } from '../form'
import { Icon } from '../ui/Icon'
import { dropResume, getResume, isWatched, saveResume as storeResume, setWatched, settings } from '../store'
import { activeId, beforeSwitch } from '../accounts'
import type { VideoItem, WatchInfo } from '../tg/types'
import { BLANK_POSTER, codecSupport, isMediaFailure, NEXT_AHEAD, onTv, playableCopies, speedText, tabs, timing, whyText, type Source } from './playerParts'
import { StatusSheet } from './StatusSheet'
import { useCrashWatch } from './useCrashWatch'
import { useMediaSession } from './useMediaSession'
import { usePlayerLogs } from './usePlayerLogs'
import { useSubtitleTracks } from './useSubtitleTracks'

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
    /** A Play all run (WP-32): the end of the video goes on to the next one whatever Autoplay says. */
    playAll?: boolean
}) {
    let video!: HTMLVideoElement
    let container!: HTMLElement
    let subtitleInput!: HTMLInputElement
    const [ready, setReady] = createSignal(false)
    const item = props.item
    // HX25: the remembered online subtitle is looked for while the video opens, so it is in before the first frame.
    void warmRestore(item)
    const copies = playableCopies(item.copies)
    const mounted = performance.now()
    let loadStarted = 0
    let framed = false
    let seekStarted = 0

    let file: MediaFile | null = null
    let engine: Engine | null = null
    let probed: Probe | null = null
    /** Bumped on each load, so a slow probe of an abandoned source does nothing. */
    let generation = 0
    /** The posted file as a source: the pieces it is cut into, or the ZIP it is kept in, with it. */
    const original = (): Source => ({ key: item.key, size: item.size, duration: item.duration, height: 0, joined: item.joined, zip: item.zip })
    const [source, setSource] = createSignal<Source>(original())
    const [error, setError] = createSignal<string | null>(null)
    const [needsApp, setNeedsApp] = createSignal<Why | null>(null)
    /** What the file's header says of its picture and sound, for the error card's one plain line (formatline.ts). */
    const [facts, setFacts] = createSignal<FormatFacts | null>(null)
    /** The failure on the card is a codec or container one, the kind that line is about. */
    const [mediaFailed, setMediaFailed] = createSignal(false)
    const [detailsCopied, setDetailsCopied] = createSignal(false)
    /** The picture is converted here (HEVC with no decoder), which makes the opening slow: the loader says so. */
    const [converting, setConverting] = createSignal(false)
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
    /** Bytes a second of a line that keeps sending too slowly to start yet (the loader shows it); 0 when none is. */
    const [trickle, setTrickle] = createSignal(0)
    /** Seconds Telegram asked a file read to wait, kept up to date while the sheet is up. */
    const [flood, setFlood] = createSignal(0)
    /** Where the video was resumed from (seconds), for the Start over offer. */
    const [resumedAt, setResumedAt] = createSignal<number | null>(null)
    /** The opening loader's stage (loader.tsx); null once the first frame is in, which ends it for good. */
    const [opening, setOpening] = createSignal<number | null>(0)
    const advance = (stage: number) => setOpening((s) => (s === null ? null : Math.max(s, stage)))
    const [frames, setFrames] = createSignal<Frames | null>(null)
    /** The video's own frames for the seek bar's card, for a natively played file (see [FrameGrabber]). */
    const [grabber, setGrabber] = createSignal<FrameGrabber | null>(null)
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
        setMediaFailed(isMediaFailure(e))
        if (e instanceof Unplayable) setNeedsApp(e.why)
        // Offline: wait for the connection and carry on at the same second (onOnline).
        else if (!navigator.onLine) {
            retryOnline = true
            setStall('offline')
        }
        else if (e instanceof PiecesProblem) setError(t(e.number === 1 ? 'player.pieces_first_missing' : 'player.pieces_up_to', { number: e.number, total: e.total }))
        else if (e instanceof ZipProblem) setError(t(`player.zip_${e.kind}`))
        else if (/\bgone\b/.test(String((e as Error)?.message ?? e))) {
            dropResume(item.chatId, item.messageId)
            setError(t('web.gone_from_chat'))
        } else setError(t('player.error_other'))
    }

    /** The address the stream is served under: the file's own key (a joined one has its own, see JoinedFile.key). */
    const streamKey = () => file?.key ?? source().key

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

    /** The seek bar's own frames end with the source they read. */
    const dropGrabber = () => {
        grabber()?.destroy()
        setGrabber(null)
    }
    const playVideo = () => void video.play().catch(() => {})
    const unload = () => {
        dropGrabber()
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

    /**
     * The file `src` plays from: a Telegram file, or its pieces joined (a video cut up, a ZIP
     * split up), with a ZIP's stored video shown through a window as the whole file. Throws
     * PiecesProblem or ZipProblem with the reason when it cannot, which `fail` says; null when a
     * newer load took over meanwhile.
     */
    const openSource = async (src: Source, gen: number): Promise<MediaFile | null> => {
        if (!src.joined && !src.zip) return openFile(src)
        const set = src.joined
        // Nothing starts without the first piece; a ZIP's directory is in the last, so a gap anywhere ends it too.
        if (set && (!set.files.length || (src.zip && set.firstMissing))) throw new PiecesProblem(set.firstMissing || 1, set.total)
        const j = openJoined(src)
        try {
            const zipped = await mountZip(j, !!src.zip)
            if (gen !== generation) {
                j.close()
                return null
            }
            // Cut up with a piece missing later on: it plays up to the gap.
            if (set?.firstMissing && !zipped) setNotice(t('player.pieces_up_to', { number: set.firstMissing, total: set.total }))
        } catch (e) {
            j.close()
            throw e
        }
        return j
    }

    // Report stutter and the playback watch (WB18): read every 2 s while the video plays and never otherwise. Not on a TV:
    // nothing there can receive a mark (no clipboard to paste from, and the upload is not on yet), so it does not spend its small CPU on one.
    let stutterWatch: StutterWatch | null = null
    onMount(() => {
        if (form() !== 'tv') stutterWatch = watchStutter(video, () => engine, () => stall() !== null)
    })
    onCleanup(() => stutterWatch?.stop())
    const stutter = { supported: () => stutterWatch?.supported() ?? false, press: () => stutterWatch?.press() ?? 'kept', restart: () => stutterWatch?.restart() }
    // A stall that clears with no media event of its own (the bytes came, the clock moves): the watch follows it.
    createEffect(() => {
        stall()
        stutterWatch?.refresh()
    })
    // What the log may take out as written, and what it says of this video; the scrubbed logs for Copy details.
    const { logsSize, logsText, copyLogs } = usePlayerLogs(() => video, item, () => props.info, { facts, stall, error, needsApp })

    /** Opens `src` at `at` seconds; `play` starts it once it can. */
    const load = async (src: Source, at: number, play: boolean) => {
        const gen = ++generation
        openingAt = at
        moviTried = ''
        loadStarted = performance.now()
        watch.reset(loadStarted)
        stutter.restart()
        setTrickle(0)
        framed = false
        retryOnline = false
        // A new source resets the element's speed; the one chosen carries over.
        const rate = video.playbackRate
        unload()
        video.defaultPlaybackRate = rate
        setError(null)
        setMediaFailed(false)
        setFacts(null)
        setDetailsCopied(false)
        setStall(null)
        setSource(src)
        let f: MediaFile
        try {
            const opened = await openSource(src, gen)
            if (!opened) return
            f = opened
        } catch (e) {
            if (gen === generation) fail(e)
            return
        }
        file = f
        streams.set(f.key, f)
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
            setFacts(p.facts)
            setConverting(p.transcode)
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
                // On the webOS .ipk the loopback bridge wins over the Service Worker address (tvservice.ts).
                const bridged = tvServiceUrl(f.key, f.size, p.mime)
                const url = bridged ?? `/stream/${encodeURIComponent(f.key)}?size=${f.size}&mime=${encodeURIComponent(p.mime.split(';')[0])}&p=${encodeURIComponent(activeId)}`
                // Says which route fed the player, so a tester's screenshot tells R4 (bridge) from R3 (worker).
                const how = bridged ? 'Played by the TV player through the TMPlayer service' : form() === 'tv' ? 'Played by the TV player through the Service Worker' : 'Played by the browser'
                engine = nativeEngine(video, url, at, how)
                // A second element on the same stream for the seek bar's frames; not on an iPhone or
                // iPad (Safari's memory and range limits) nor on the TV layout.
                if (PICTURES_SHOWN && !appleTouch && form() !== 'tv') setGrabber(new FrameGrabber(url))
            } else if (soundForLibav(p) && !(await playableSound(p.audios)) && (await soundByLibav(f, p, at, play, gen, rate))) {
                return
            } else engine = await remux(p, at)
            if (gen !== generation) return
            video.playbackRate = rate
            setSwitchable(!!engine.setAudio)
            // Which HEVC name the TV took (hevcnames.ts), so a tester's screenshot says which step played it.
            const hevc = way === 'remux' && p.hevcAs ? (p.hevcTrusted ? `, ${p.videoCodec} sent to the TV decoder unasked (${p.tryNames.length} names to try)` : p.hevcAs.startsWith('av01.') ? `, AV1 as ${p.hevcAs}` : `, HEVC as ${p.hevcAs}`) : ''
            setDetails(`${p.container}, ${p.mime.replace(/^[^;]*;\s*/, '')}. ${engine.detail}${hevc}`)
            if (play) playVideo()
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
     * H.264 whose only sound is one mediabunny cannot name (TrueHD, WMA, RealAudio): libav.js reads
     * the file, copies the picture and decodes the sound. False when it cannot, and the repack then
     * plays the picture without sound as before.
     */
    const soundByLibav = async (f: MediaFile, p: Probe, at: number, play: boolean, gen: number, rate: number): Promise<boolean> => {
        try {
            await software(f, p.container, at, play, gen, rate)
        } catch (e) {
            console.warn('[player] libav.js could not play the sound', e)
            return false
        }
        if (gen !== generation) return true
        probed = null
        p.input.dispose()
        return true
    }

    /**
     * What the browser cannot play: movi (FFmpeg and WebCodecs), then libav.js for its containers,
     * then the reason the video needs the app. A loader stays up throughout; only the last step
     * shows anything else.
     */
    const fallback = async (why: unknown, f: MediaFile, at: number, play: boolean, gen: number, rate: number, p: Probe | null = null) => {
        const container = why instanceof Unplayable && why.why.kind === 'format' ? why.why.format : null
        // A picture mediabunny reads but cannot name (MPEG-4 Part 2, MPEG-2, VC-1, H.263 in MP4, 3GP,
        // Matroska or MPEG-TS), or does not find (MPEG-2 in MPEG-TS, Theora in Ogg), goes to libav.js too.
        const picture = !container && !!p && why instanceof Unplayable && pictureForLibav(p, why.why)
        // AVI, FLV, WMV, RealMedia, VOB: libav.js first, which plays through the element (picture in
        // picture, AirPlay); movi when it cannot.
        const libav = (!!container && LIBAV_FORMATS.has(container)) || picture
        if (libav) {
            try {
                await software(f, container ?? p!.container, at, play, gen, rate)
                p?.input.dispose()
                return
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
        // No encoder for the picture here (a TV): libav.js decodes it and a canvas shows it, with the sound as the clock.
        if (libav && why instanceof Unplayable && (why.why.kind === 'picture' || why.why.kind === 'no-mse' || why.why.kind === 'too-heavy') && (await canvasWanted())) {
            try {
                await software(f, container ?? p!.container, at, play, gen, rate, true)
                return
            } catch (e) {
                if (gen !== generation) return
                console.warn('[player] the canvas engine could not play it', e)
                // Too large for this device says so; the other failures keep the first reason.
                if (e instanceof Unplayable && e.why.kind === 'too-heavy') why = e
            }
        }
        throw why
    }

    /** The canvas engine's chunk is fetched only when a file reaches it, and only where it can run. */
    const canvasWanted = async () => (await import('./libavcanvas')).canvasSupported()

    /** movi on `f`; mediabunny's reading of the file (`p`), when there is one, finds its key frames and goes with the engine. */
    const viaMovi = async (f: MediaFile, at: number, play: boolean, gen: number, rate: number, p: Probe | null) => {
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
        dropGrabber()
        engine = e
        setAudios(e.sounds)
        setAudio(e.sounds[0] ?? null)
        video.playbackRate = rate
        setSwitchable(e.sounds.length > 1)
        setDetails(e.detail)
        if (play) playVideo()
        void findChapters(f, gen)
        void addStoryboard()
    }

    /**
     * The repack failed part way: movi at the same second when the browser turned the media down (a
     * codec, a broken stream); Telegram's and the network's failures are said as they are.
     */
    const remuxFailed = async (err: unknown) => {
        const f = file
        const media = isMediaFailure(err)
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
        // A TV whose media server fetched /stream/ itself, around the worker: counted, and after two the TV stays on the repack.
        if (onTv) {
            const reached = workerAsked(streamKey()) || bridgeAsked(streamKey())
            noteTvNative(reached)
            if (!reached) console.warn('[player] the TV player did not go through the stream worker')
        }
        dropGrabber()
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
            playVideo()
        } catch (e) {
            fail(e)
        }
    }

    /**
     * A file only libav.js opens (AVI): its own engine, which reads the header itself. With
     * `canvas` the picture is drawn on a canvas instead of re-encoded for the Media Source (a
     * browser with no WebCodecs encoder, the TVs): a lazy chunk, loaded only here.
     */
    const software = async (f: MediaFile, container: string, at: number, play: boolean, gen: number, rate: number, canvas = false) => {
        const events = {
            onError: fail,
            onDetail: (d: string) => setDetails(`${container}. ${d}`),
            onSlow: slowHere,
        }
        const e = canvas ? await (await import('./libavcanvas')).CanvasEngine.open(f, video, container, source().duration, at, events) : await LibavEngine.open(f, video, container, source().duration, at, events)
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
        if (play) playVideo()
    }

    /** The file's chapter marks, read from its head or tail, which the opening has fetched already. */
    const findChapters = async (f: MediaFile, gen: number) => {
        setChapters([])
        const list = await readChapters(f, navigator.language)
        if (gen === generation && list.length) setChapters(list)
    }

    /** The same source again at the same second: Retry after a stall, a lost connection or a quality pick. */
    const reload = (src = source()) => load(src, engine ? video.currentTime || 0 : openingAt, !video.paused || stall() !== null)

    /** The failed format in one plain line, for the card of a codec or container failure that knew its format; empty otherwise. */
    const failedLine = () => (needsApp() || (error() && mediaFailed()) ? formatLine(facts()) : '')

    /** Copy details: the line, what this browser says of the codecs, the datacenter and the browser; no title, chat or file name. */
    const copyFailure = async () => {
        const f = facts()
        const support = codecSupport(f)
        try {
            await navigator.clipboard.writeText(copyDetails(failedLine(), support, item.dc, navigator.userAgent))
            setDetailsCopied(true)
        } catch {
            setDetailsCopied(false)
        }
    }

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

    const { addSubtitle, loadSubtitle, onControls } = useSubtitleTracks(() => video, () => container, ready, item, () => closed, setNotice)

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
        const src: Source = copy ? { key: copy.key, size: copy.size, duration: item.duration, height: copy.height } : original()
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
    let wasHidden = false
    /** Counts the bytes that arrive while the video waits: 10 s with none is the stall sheet (U1), see openStall.ts. */
    const watch = new StallWatch()
    /** The page is back in view (also from a frozen tab, which ran no timer meanwhile): what arrived is not a speed and the wait counts afresh. */
    const countAfresh = () => {
        const now = performance.now()
        lastReceived = file?.received ?? 0
        stuckSince = now
        watch.reset(now, lastReceived)
    }
    const onShown = () => (document.hidden ? void (wasHidden = true) : countAfresh())
    const sample = () => {
        if (document.hidden) return void (wasHidden = true)
        if (!video.paused) refreshKept()
        const f = file
        setLoaded(f && f.loaded < 0.999 ? f.loaded : null)
        const now = performance.now()
        const received = f?.received ?? 0
        // Back from the background: what arrived meanwhile is not a speed, and the wait counts afresh.
        if (wasHidden) {
            wasHidden = false
            countAfresh()
        }
        const rate = received - lastReceived
        lastReceived = received
        const b = video.buffered
        let ahead = 0
        for (let i = 0; i < b.length; i++) if (video.currentTime >= b.start(i) - 0.5 && video.currentTime <= b.end(i)) ahead = b.end(i) - video.currentTime
        setSampled([ahead, rate])
        // Waiting to play with the clock not moving: say so after a while, as the app does.
        // Before the first frame the element is paused with nothing in it: that is waiting too.
        const waiting = (!everPlayed || !video.paused) && video.readyState < 3
        const moved = video.currentTime !== lastTime
        // A read ends a chunk at a time, so what is asked of Telegram stretches the wait; a flood wait or no line is the cause already, and is not.
        const known = floodLeft() > 0 || (navigator.onLine && link() !== null && link() !== 'connected' && link() !== 'updating')
        const phase = watch.tick(now, { waiting: waiting && !error() && !needsApp(), moved, received, local: !f || f.loaded >= 0.999, transcode: !!probed?.transcode, pending: known ? 0 : (f?.pending ?? 0) })
        // Bytes still arriving, only too slowly: the loader shows the speed, and Telegram is not blamed.
        setTrickle(phase === 'trickle' && !stall() ? Math.max(1, Math.round(watch.speed(now))) : 0)
        setFlood(floodLeft())
        if (moved || !waiting || error() || needsApp()) {
            lastTime = video.currentTime
            stuckSince = now
            if (stall()) setStall(null)
        } else if (!navigator.onLine && now - stuckSince > 2000) setStall('offline')
        else if (phase === 'stalled' && !stall()) setStall(everPlayed ? 'mid' : 'start')
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
    // A profile switch (HX88) asks first while this plays; then the place is kept and the video stops.
    onCleanup(
        beforeSwitch(() => {
            if (closed) return
            savePosition(video.currentTime)
            video.pause()
        }),
    )
    onMount(async () => {
        clearFlood()
        setSubDelay(0)
        readLook().then(setLook, () => {})
        isWatched(item.chatId, item.messageId).then(setWatchedNow, () => {})
        const left = (await getResume(item.chatId, item.messageId))?.position ?? 0
        savedMs = left > 0 ? left * 1000 : null
        const at = left > 0 ? (await api.resumeStart(left * 1000, rewindMs()).catch(() => left * 1000)) / 1000 : 0
        if (closed) return
        if (at > 0) setResumedAt(at)
        load(source(), at, true)
        startTicks()
        window.addEventListener('offline', onOffline)
        window.addEventListener('online', onOnline)
        document.addEventListener('visibilitychange', onHidden)
        document.addEventListener('visibilitychange', onShown)
        window.addEventListener('pageshow', onShown)
        window.addEventListener('pagehide', onLeave)
        tabs?.addEventListener('message', onOtherTab)
        container.focus({ preventScroll: true })
    })
    // The controls are drawn once the video element exists.
    onMount(() => setReady(true))

    // The lock screen and media keys: its name, the jumps, previous and next.
    useMediaSession(() => video, item, () => props.info, play, () => close())

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

    // A failure nobody caught while the video is open comes here, not to the page's card (HX19): useCrashWatch.
    useCrashWatch(() => video, { closed: () => closed, error, opened: () => opening() === null }, () => setError(t('player.error_other')))

    onCleanup(() => {
        closed = true
        savePosition(video.currentTime)
        clearInterval(saveTimer)
        clearInterval(tick)
        window.removeEventListener('offline', onOffline)
        window.removeEventListener('online', onOnline)
        document.removeEventListener('visibilitychange', onHidden)
        document.removeEventListener('visibilitychange', onShown)
        window.removeEventListener('pageshow', onShown)
        window.removeEventListener('pagehide', onLeave)
        tabs?.removeEventListener('message', onOtherTab)
        generation++
        unload()
        const f = frames()
        if (f) URL.revokeObjectURL(f.url)
        if (document.fullscreenElement === container) document.exitFullscreen().catch(() => {})
    })

    const close = () => {
        // Saved before leaving: Back may leave the site, and then no cleanup runs.
        savePosition(video.currentTime)
        if (document.fullscreenElement === container) document.exitFullscreen().catch(() => {})
        props.onClose()
    }

    /** The status sheet is up: a stall, a failure or a file this browser cannot play. */
    const blocked = () => !!(needsApp() || error() || (stall() && stall() !== 'offline'))
    const sheetTitle = () => (props.info?.episode ? `${props.info.title} ${props.info.episode.code}` : (props.info?.title ?? item.title))
    const lighter = () => (needsApp() ? copies[0] : copies[copies.length - 1])
    /** Why nothing arrives: Telegram asked to wait (and for how long), no line to Telegram, or it has not started sending. */
    const why = () => stallWhy(flood(), online(), link())
    // The wait in steps of 5 s past 10, so the sheet's alert is not read out every second.
    const floodWait = () => waitPhrase(flood() > 10 ? Math.ceil(flood() / 5) * 5 : flood())
    const stallBody = () => {
        const w = why()
        if (w === 'flood') return t('player.stalled_flood_wait', { wait: floodWait() })
        if (w === 'noline') return t('player.stalled_no_line_plain')
        return stall() === 'start' ? t('player.stalled_slow') : t('player.stalled_mid_slow')
    }

    return (
        <video-player class="player-root">
            <media-container
                ref={container}
                class="player"
                classList={{ blocked: blocked() }}
                attr:data-variant={variant()}
                tabIndex={-1}
                onDragOver={(e) => {
                    // Only a drop that carries files is ours; the drop below takes a subtitle among them.
                    if (e.dataTransfer?.types.includes('Files')) e.preventDefault()
                }}
                onDrop={(e) => {
                    e.preventDefault()
                    const dropped = subtitleFromDrop(e.dataTransfer?.files)
                    if (dropped) loadSubtitle(dropped)
                }}
            >
                <video
                    ref={video}
                    playsinline
                    preload="auto"
                    // Android's WebView draws a grey, blurred play button on a video with no poster; the opening loader covers the wait.
                    poster={BLANK_POSTER}
                    onLoadedMetadata={() => {
                        // The TV's own player read the file through the worker: the route works on this TV.
                        if (onTv && engine?.name === 'native' && (workerAsked(streamKey()) || bridgeAsked(streamKey()))) noteTvNative(true)
                        advance(1)
                    }}
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
                            playAll: () => !!props.playAll,
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
                            stutter: () => (stutter.supported() ? { press: stutter.press as () => 'kept' | 'already' } : null),
                            logsSize,
                            logsText,
                            copyLogs,
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
                    <OpeningLoader item={item} info={props.info} at={resumedAt() ?? 0} stage={opening()!} converting={converting()} rate={trickle() ? speedText(trickle()) : undefined} offline={stall() === 'offline'} onClose={close} />
                </Show>

                <Show when={blocked()}>
                    <StatusSheet
                        item={item}
                        title={sheetTitle()}
                        heading={needsApp() ? t('web.needs_app_title') : error() ? t('player.cant_play') : stall() === 'start' ? t('player.stalled_title') : t('player.stalled_mid_title')}
                        body={needsApp() ? whyText(needsApp()!) : (error() ?? stallBody())}
                        note={needsApp() ? t('web.app_body') : undefined}
                        warning={!!error() || !!needsApp()}
                        format={failedLine()}
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
                        {/* A lighter copy helps a slow Telegram, not a flood wait or a blocked line (the apps' StalledSheet). */}
                        <Show when={copies.length && source().height === 0 && (needsApp() || error() || why() === 'slow')}>
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
                        <Show when={failedLine()}>
                            <button type="button" class="pc-sheet-button" onClick={copyFailure}>
                                {detailsCopied() ? t('player.details_copied') : t('player.copy_details')}
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
