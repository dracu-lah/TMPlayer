// The player's controls, drawn as the apps draw theirs: the phone's PlayerControls.kt
// (player_controls.xml, PlayerFeedback.kt, the pickers in PlayerSheets.kt) and the desktop's
// PlayerOverlay.kt with StoryboardPreview.kt and PlayerKeys.kt. Video.js is not drawn at all: the
// `<video>` underneath is fed by engines.ts, and everything on top of it is here.
//
// One overlay, three shapes chosen by the layout (form.ts): `phone` (top bar, the centre cluster,
// the times over the bar and the round-button pill with Copies and Episodes), `desktop` (the
// same three groups at the desktop's sizes, the hover preview, the volume slider and the menus
// that open from their buttons) and `tv` (the Android TV player: no top bar, the title over the
// bar, one play disc, a row of round buttons with More at its end, the time as a button, every
// control reached with the remote's arrows and OK, see `tvKey`).
import { createEffect, createMemo, createSignal, For, Match, on, onCleanup, onMount, Show, Switch, type JSX } from 'solid-js'
import {
    Add01Icon,
    Bookmark02Icon,
    CheckmarkCircle02Icon,
    ArrowExpandIcon,
    ArrowLeft02Icon,
    ArrowShrinkIcon,
    Cancel01Icon,
    CropIcon,
    DashboardSpeed02Icon,
    FitToScreenIcon,
    HeadphonesIcon,
    InformationCircleIcon,
    KeyboardIcon,
    ListVideoIcon,
    MaximizeScreenIcon,
    MinusSignIcon,
    Moon02Icon,
    MoreVerticalIcon,
    NextIcon,
    PictureInPictureOnIcon,
    PreviousIcon,
    RepeatIcon,
    ScreenRotationIcon,
    SquareLock02Icon,
    SquareUnlock02Icon,
    SubtitleIcon,
    Sun03Icon,
    VolumeHighIcon,
    VolumeLowIcon,
    VolumeMute02Icon,
} from '@hugeicons/core-free-icons'
import './player.css'
import { Icon, type IconData } from '../ui/Icon'
import { t } from '../i18n'
import { Choice, Entry, Menu, PhoneSheet, Picker, variant, walk } from './sheets'
import { remoteKey } from '../tv/remote'
import { frameAt, type Frames } from './storyboard'
import { chapterAt, chapterMarks, chapterNext, chapterPrevious, isGeneric, skipSegments, type Chapter, type SkipSegment } from './chapters'
import { applyDelay, look, setLook, setSubDelay, subDelay } from './cues'
import { BookmarksSheet, delayText, ShortcutsSheet, SIZES, TimingSheet } from './extra-sheets'
import { AUTOPLAY_LIMIT, fineRate, IDLE_LIMIT, noteInput, pressLoop, SeekUndo, SLEEP_CHOICES, sleepState, watching, wrapLoop, type Loop } from './extras'
import {
    addBookmark, deleteBookmark, loadBookmarks, readAutoplay, renameBookmark, writeAutoplay, writeLook, type Bookmark, type SubtitleLook,
} from './keep'
import type { SoundTrack } from './engines'
import type { VideoCopy, VideoItem, WatchInfo } from '../tg/types'

/** TmIcons.PlaySolid and PauseSolid: Hugeicons Play and Pause, filled. */
const PLAY_PATH =
    'M20.1406 12.846C19.7871 14.189 18.1167 15.138 14.7757 17.0361C11.546 18.8709 9.9312 19.7884 8.6298 19.4196C8.0918 19.2671 7.6016 18.9776 7.2062 18.5787C6.25 17.6139 6.25 15.7426 6.25 12C6.25 8.2574 6.25 6.3861 7.2062 5.42132C7.6016 5.02245 8.0918 4.73288 8.6298 4.58042C9.9312 4.21165 11.546 5.12907 14.7757 6.96393C18.1167 8.86197 19.7871 9.811 20.1406 11.154C20.2865 11.7084 20.2865 12.2916 20.1406 12.846Z'
const PlaySolid: IconData = [['path', { d: PLAY_PATH, fill: 'currentColor' }]]
const PauseSolid: IconData = [
    ['path', { d: 'M4 7C4 5.58579 4 4.87868 4.43934 4.43934C4.87868 4 5.58579 4 7 4C8.41421 4 9.12132 4 9.56066 4.43934C10 4.87868 10 5.58579 10 7V17C10 18.4142 10 19.1213 9.56066 19.5607C9.12132 20 8.41421 20 7 20C5.58579 20 4.87868 20 4.43934 19.5607C4 19.1213 4 18.4142 4 17V7Z', fill: 'currentColor' }],
    ['path', { d: 'M14 7C14 5.58579 14 4.87868 14.4393 4.43934C14.8787 4 15.5858 4 17 4C18.4142 4 19.1213 4 19.5607 4.43934C20 4.87868 20 5.58579 20 7V17C20 18.4142 20 19.1213 19.5607 19.5607C19.1213 20 18.4142 20 17 20C15.5858 20 14.8787 20 14.4393 19.5607C14 19.1213 14 18.4142 14 17V7Z', fill: 'currentColor' }],
]
/** The phone's copies button (ic_copies.xml): three stacked layers, drawn by hand in Hugeicons' stroke. */
const CopiesIcon: IconData = [
    ['path', { d: 'M12 3.5L21 8.25L12 13L3 8.25Z' }],
    ['path', { d: 'M3 12.25L12 17L21 12.25' }],
    ['path', { d: 'M3 16.25L12 21L21 16.25' }],
]

/** PlaybackSpeed.CHOICES, and the fine control's step and ends. */
const SPEEDS = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2]
const FINE_STEP = 0.05
const SPEED_MIN = 0.25
const SPEED_MAX = 4

/** PlayerControls' timings: in a touch slower than out, hidden after 3.5 s of playing untouched. */
const HIDE_MS = 3500
/** A second press inside this is a double tap or click (VideoGestures' DOUBLE_CLICK_MS). */
const DOUBLE_MS = 300
/** A finger held this long plays fast until it lifts. */
const HOLD_MS = 450
/** A side tap within this of the last jump adds another (the running total). */
const RUN_MS = 700
/** A finger that moved further than this is a drag, not a tap. */
const SLOP = 12
/** TapZone.SIDE_FRACTION: the left and right thirds-and-a-bit that jump. */
const SIDE = 0.35
/** PlayerControls.WIDE_ROW_DP: from this width the phone's row adds picture shape and the lock. */
const WIDE_ROW = 540
const NOTE_MS = 900
/** An iPhone or iPad: a page cannot set the volume there (the side buttons do), so the right side's drag does nothing. */
const FIXED_VOLUME = /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)
const UNLOCK_PILL_MS = 2500

export type Shape = 'fit' | 'crop' | 'stretch'
const SHAPES: Shape[] = ['fit', 'crop', 'stretch']
const SHAPE_ICON: Record<Shape, IconData> = { fit: FitToScreenIcon, crop: CropIcon, stretch: MaximizeScreenIcon }
const shapeName = (s: Shape) => t(`player.scale_${s}`)

/** SeekMath.clock: 4:05, 1:02:03. */
export function clock(seconds: number): string {
    const s = Math.max(0, Math.floor(isFinite(seconds) ? seconds : 0))
    const h = Math.floor(s / 3600)
    const m = Math.floor((s % 3600) / 60)
    const sec = String(s % 60).padStart(2, '0')
    return h ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`
}

/** PlaybackSpeed.label: 1x, 1.25x, 0.5x. */
export const speedLabel = (rate: number) => t('format.speed_factor', { speed: String(Math.round(rate * 100) / 100) })

/** What the overlay needs from the player: its video, the item, and the actions that load things. */
export interface Ctl {
    video: HTMLVideoElement
    root: HTMLElement
    item: VideoItem
    info: () => WatchInfo | null
    close: () => void
    play: (next: VideoItem | null) => void
    /** A status sheet is up (stall, failure, needs the app): the controls stand aside. */
    blocked: () => boolean
    audios: () => SoundTrack[]
    audio: () => SoundTrack | null
    canSwitchAudio: () => boolean
    pickAudio: (index: number) => void
    trackLabel: (track: SoundTrack, index: number) => string
    copies: VideoCopy[]
    sourceKey: () => string
    sourceHeight: () => number
    pickQuality: (key: string) => void
    qualityLabel: (height: number) => string
    readout: () => string
    details: () => string
    loadSubtitleFile: () => void
    frames: () => Frames | null
    /** Where the video was resumed from, for the Start over offer; null when it starts at the beginning. */
    resumedAt: () => number | null
    startOver: () => void
    /** The controls came up or went: subtitles move up out of the bar's way while they show. */
    onControls?: (up: boolean) => void
    /** The file's chapter marks (empty when it has none or they are not read). */
    chapters: () => Chapter[]
    /** How much of the file has arrived (0 to 1); null once all of it has. */
    loaded: () => number | null
    /** Whether the video is on the Watched list, and the menu's way to change it. */
    watched: () => boolean
    markWatched: (on: boolean) => void
}

type Sheet = 'subtitles' | 'audio' | 'speed' | 'copies' | 'episodes' | 'more' | 'shape' | 'details' | 'options' | 'sleep' | 'bookmarks' | 'timing' | 'shortcuts' | 'still' | null

export { variant }

export function Chrome(props: { ctl: Ctl; children?: JSX.Element }) {
    const c = props.ctl
    const v = c.video
    const phone = () => variant() === 'phone'
    const tv = () => variant() === 'tv'
    /** Plain labels (no key hints): the phone's and the TV's remote have no letters to press. */
    const plain = () => phone() || tv()
    // The TV's skin is fetched with the TV layout only (the controls start hidden, so nothing shows bare).
    createEffect(() => tv() && void import('./player-tv.css'))

    // ---- the video's state ----------------------------------------------------------------
    const [paused, setPaused] = createSignal(true)
    const [waiting, setWaiting] = createSignal(true)
    const [time, setTime] = createSignal(0)
    const [duration, setDuration] = createSignal(c.item.duration || 0)
    const [buffered, setBuffered] = createSignal(0)
    const [volume, setVolume] = createSignal(1)
    const [muted, setMuted] = createSignal(false)
    const [rate, setRate] = createSignal(1)
    const [fullscreen, setFullscreen] = createSignal(false)
    const [subs, setSubs] = createSignal<TextTrack[]>([])
    const [subOn, setSubOn] = createSignal<TextTrack | null>(null)
    const [width, setWidth] = createSignal(c.root.clientWidth || innerWidth)

    const readBuffered = () => {
        const b = v.buffered
        let end = 0
        for (let i = 0; i < b.length; i++) if (v.currentTime >= b.start(i) - 0.5) end = Math.max(end, b.end(i))
        setBuffered(end)
    }
    const readSubs = () => {
        const list = Array.from(v.textTracks).filter((tt) => tt.kind === 'subtitles' || tt.kind === 'captions')
        setSubs(list)
        setSubOn(list.find((tt) => tt.mode === 'showing') ?? null)
    }
    const listen = <K extends keyof HTMLMediaElementEventMap>(name: K, fn: () => void) => {
        v.addEventListener(name, fn)
        onCleanup(() => v.removeEventListener(name, fn))
    }
    listen('play', () => (setPaused(false), resumed()))
    listen('pause', () => (setPaused(true), show(), tv() && !sheet() && queueMicrotask(() => focusIt(discEl()))))
    listen('playing', () => setWaiting(false))
    listen('waiting', () => setWaiting(true))
    listen('canplay', () => setWaiting(false))
    // The TV's hidden controls do not redraw with the picture (nothing is drawn, so nothing is read);
    // they catch up the moment they come up.
    const drawn = () => variant() !== 'tv' || visible()
    listen('timeupdate', () => drawn() && (dragTime() === null && setTime(v.currentTime), readBuffered()))
    listen('seeked', () => setTime(v.currentTime))
    listen('progress', () => drawn() && readBuffered())
    listen('durationchange', () => isFinite(v.duration) && v.duration > 0 && setDuration(v.duration))
    listen('volumechange', () => (setVolume(v.volume), setMuted(v.muted)))
    listen('ratechange', () => setRate(v.playbackRate))
    v.textTracks.addEventListener('addtrack', readSubs)
    v.textTracks.addEventListener('change', readSubs)
    const onFullscreen = () => setFullscreen(!!document.fullscreenElement)
    document.addEventListener('fullscreenchange', onFullscreen)
    const resize = new ResizeObserver(() => setWidth(c.root.clientWidth))
    resize.observe(c.root)
    onCleanup(() => {
        v.textTracks.removeEventListener('addtrack', readSubs)
        v.textTracks.removeEventListener('change', readSubs)
        document.removeEventListener('fullscreenchange', onFullscreen)
        resize.disconnect()
    })

    // ---- showing and hiding ------------------------------------------------------------------
    const [visible, setVisible] = createSignal(variant() !== 'tv')
    const [sheet, setSheet] = createSignal<Sheet>(null)
    /** The pointer rests on the controls (desktop): they stay. */
    const [held, setHeld] = createSignal(false)
    const [locked, setLocked] = createSignal(false)
    let hideTimer = 0
    const poke = () => {
        clearTimeout(hideTimer)
        if (v.paused || held() || sheet() || dragTime() !== null || c.blocked()) return
        hideTimer = window.setTimeout(() => setVisible(false), HIDE_MS)
    }
    function show() {
        if (locked()) return
        setVisible(true)
        poke()
    }
    function resumed() {
        if (visible()) poke()
    }
    const hide = () => {
        clearTimeout(hideTimer)
        setVisible(false)
    }
    const toggle = () => (visible() ? hide() : show())
    createEffect(() => c.onControls?.(visible() && !locked() && !c.blocked()))
    createEffect(on([sheet, held], () => (visible() ? poke() : undefined), { defer: true }))
    onCleanup(() => clearTimeout(hideTimer))

    createEffect(on(visible, (up) => up && tv() && (setTime(v.currentTime), readBuffered()), { defer: true }))

    // ---- the TV: the clock, and where the remote is --------------------------------------
    // The wall clock in the corner (renderWallClock): read when the controls come up and then
    // every 20 s while they are up, so nothing ticks with the picture bare or paused and hidden.
    const timeOfDay = () => new Date().toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })
    const [wall, setWall] = createSignal(timeOfDay())
    createEffect(() => {
        if (!tv() || !visible()) return
        setWall(timeOfDay())
        const id = window.setInterval(() => setWall(timeOfDay()), 20_000)
        onCleanup(() => clearInterval(id))
    })
    const barEl = () => c.root.querySelector<HTMLElement>('.pc-bar')
    const discEl = () => c.root.querySelector<HTMLElement>('.pc-play')
    const rowItems = () => Array.from(c.root.querySelectorAll<HTMLElement>('.pc-row button'))
    const inLayer = (el: Element | null) => !!el && !!c.root.querySelector('.pc-layer')?.contains(el)
    const focusIt = (el: HTMLElement | null | undefined) => el?.focus({ preventScroll: true })
    // The row comes up with the remote on the bar (arrows seek at once), or on the disc while
    // paused (OK resumes); it goes with the remote back on the picture.
    createEffect(
        on(
            () => visible() && !locked(),
            (up) => {
                if (!tv()) return
                queueMicrotask(() => {
                    if (up) {
                        if (!sheet() && !inLayer(document.activeElement)) focusIt(v.paused ? discEl() : barEl())
                    } else if (inLayer(document.activeElement)) {
                        ;(document.activeElement as HTMLElement).blur()
                        focusIt(c.root)
                    }
                })
            },
            { defer: true },
        ),
    )
    // A menu or list closing hands the remote back to the button that opened it.
    let opener: HTMLElement | null = null
    createEffect(
        on(
            sheet,
            (now, before) => {
                if (!tv() || now !== null || !before) return
                queueMicrotask(() => visible() && focusIt(opener?.isConnected ? opener : barEl()))
            },
            { defer: true },
        ),
    )
    // A status sheet (stall, failure, needs the app) takes the remote on its first button.
    createEffect(
        on(c.blocked, (b) => {
            if (b && tv()) window.setTimeout(() => focusIt(c.root.querySelector<HTMLElement>('.pc-status button, .pc-status a')), 0)
        }),
    )

    // ---- actions -------------------------------------------------------------------------------
    const [note, setNote] = createSignal<{ text: string; side: 'left' | 'right' | 'centre' } | null>(null)
    let noteTimer = 0
    const flash = (text: string, side: 'left' | 'right' | 'centre' = 'centre') => {
        clearTimeout(noteTimer)
        setNote({ text, side })
        noteTimer = window.setTimeout(() => setNote(null), NOTE_MS)
    }
    onCleanup(() => clearTimeout(noteTimer))

    const togglePlay = () => (v.paused ? v.play().catch(() => {}) : v.pause())
    const seekTo = (s: number, from = v.currentTime) => {
        const d = duration()
        const to = Math.max(0, d ? Math.min(d - 0.1, s) : s)
        v.currentTime = to
        setTime(v.currentTime)
        noteSeek(from, to)
    }
    const seekBy = (s: number) => seekTo(v.currentTime + s)
    const setSpeed = (r: number) => {
        const next = Math.round(Math.min(SPEED_MAX, Math.max(SPEED_MIN, r)) * 100) / 100
        v.playbackRate = next
        flash(speedLabel(next))
    }
    const stepSpeed = (up: boolean) => {
        const r = v.playbackRate
        const next = up ? SPEEDS.find((s) => s > r + 0.001) : [...SPEEDS].reverse().find((s) => s < r - 0.001)
        setSpeed(next ?? (up ? Math.min(SPEED_MAX, r + 0.25) : Math.max(SPEED_MIN, r - 0.25)))
    }
    const setVol = (x: number) => {
        v.volume = Math.max(0, Math.min(1, x))
        v.muted = v.volume === 0
    }
    const pickSub = (track: TextTrack | null) => {
        for (const tt of subs()) tt.mode = tt === track ? 'showing' : 'disabled'
        readSubs()
        flash(track ? t('player.subtitles_track', { track: subLabel(track, subs().indexOf(track)) }) : t('player.subtitles_off'))
    }
    const subLabel = (tt: TextTrack, i: number) => tt.label || (tt.language ? tt.language.toUpperCase() : t('player.track_number', { number: i + 1 }))
    const cycleSub = (back = false) => {
        const list = [null, ...subs()]
        const at = list.indexOf(subOn())
        pickSub(list[(at + (back ? -1 : 1) + list.length) % list.length])
    }
    const cycleAudio = (back = false) => {
        const list = c.audios()
        if (list.length < 2 || !c.canSwitchAudio()) return
        const at = c.audio() ? list.indexOf(c.audio()!) : -1
        const next = (at + (back ? -1 : 1) + list.length) % list.length
        c.pickAudio(next)
        flash(c.trackLabel(list[next], next))
    }

    // Picture shape: Fit, Crop and Stretch, as object-fit on the video.
    const [shape, setShape] = createSignal<Shape>('fit')
    createEffect(() => (v.style.objectFit = shape() === 'fit' ? 'contain' : shape() === 'crop' ? 'cover' : 'fill'))
    const cycleShape = () => {
        const next = SHAPES[(SHAPES.indexOf(shape()) + 1) % SHAPES.length]
        setShape(next)
        flash(t('player.picture_shape_now', { shape: shapeName(next) }))
    }


    // ---- chapters (Chapters.kt) --------------------------------------------------------------
    const chapters = () => c.chapters()
    const chapterText = (i: number) => {
        const list = chapters()
        const count = t('player.chapter_of', { number: i + 1, total: list.length })
        const name = list[i]?.title?.trim()
        return name && !isGeneric(name) ? t('player.chapter_named', { chapter: count, name }) : count
    }
    const chapterTextAt = (s: number) => {
        const i = chapterAt(chapters(), s)
        return i < 0 ? null : chapterText(i)
    }
    const stepChapter = (forward: boolean) => {
        const list = chapters()
        const i = forward ? chapterNext(list, v.currentTime) : chapterPrevious(list, v.currentTime)
        if (i === null) return flash(forward ? t('player.last_chapter') : t('player.before_first_chapter'))
        seekTo(list[i].start)
        flash(chapterText(i))
    }

    // ---- the way back from a long jump (SeekUndo) --------------------------------------------
    const undo = new SeekUndo()
    const [backTo, setBackTo] = createSignal<number | null>(null)
    let backTimer = 0
    function noteSeek(from: number, to: number) {
        const offer = undo.seeked(from, to, performance.now())
        clearTimeout(backTimer)
        setBackTo(offer ? offer.backTo : null)
        if (offer) backTimer = window.setTimeout(() => setBackTo(null), Math.max(0, offer.until - performance.now()))
    }
    const takeBack = () => {
        const to = undo.take(performance.now())
        if (to === null) return false
        clearTimeout(backTimer)
        setBackTo(null)
        seekTo(to)
        return true
    }
    onCleanup(() => clearTimeout(backTimer))

    // ---- A-B repeat (AbLoop) -----------------------------------------------------------------
    const [loop, setLoop] = createSignal<Loop | null>(null)
    const pressRepeat = () => {
        const was = loop()
        const next = pressLoop(was, v.currentTime)
        if (!was) flash(plain() ? t('player.loop_start_set', { start: clock(next!.start) }) : t('player.loop_from', { start: clock(next!.start) }))
        else if (next === was) flash(plain() ? t('player.loop_too_short_menu') : t('player.loop_too_short'))
        else if (next) flash(t('player.repeating', { start: clock(next.start), end: clock(next.end!) }))
        else flash(t('player.repeat_off'))
        setLoop(next)
    }
    // Played into the end, back to the start; a seek past the loop is the viewer's, and is left alone.
    listen('timeupdate', () => {
        const to = wrapLoop(loop(), v.currentTime)
        if (to === null) return
        v.currentTime = to
        undo.clear()
    })

    // ---- the sleep timer (SleepTimer) and "Still watching?" ----------------------------------
    const [sleepAt, setSleepAt] = createSignal(sleepState.at)
    const [sleepEnd, setSleepEnd] = createSignal(sleepState.end)
    const [sleepPick, setSleepPick] = createSignal<number | null>(null)
    const [stillWhy, setStillWhy] = createSignal<'idle' | 'autoplay' | 'sleep' | 'sleep_end'>('idle')
    let sleepTimer = 0
    /** The next episode keeps the timer; leaving the player ends it. */
    let keepSleep = false
    const askStill = (why: 'idle' | 'autoplay' | 'sleep' | 'sleep_end') => {
        v.pause()
        setStillWhy(why)
        setSheet('still')
        show()
    }
    const clearSleep = () => {
        clearTimeout(sleepTimer)
        setSleepAt(null)
        setSleepEnd(false)
        sleepState.at = null
        sleepState.end = false
    }
    const armSleep = () => {
        clearTimeout(sleepTimer)
        const at = sleepAt()
        if (at !== null) sleepTimer = window.setTimeout(sleepCheck, Math.max(0, Math.min(at - Date.now(), 2 ** 30)))
    }
    function sleepCheck() {
        const at = sleepAt()
        if (at === null) return
        if (Date.now() < at) return armSleep()
        clearSleep()
        askStill('sleep')
    }
    const sleepLabel = (minutes: number) =>
        minutes === 0
            ? t('player.sleep_end_of_video')
            : minutes < 60
              ? t('unit.minutes', { count: minutes })
              : minutes % 60 === 0
                ? t('unit.hours', { count: minutes / 60 })
                : t('sleep.hours_minutes', { hours: Math.floor(minutes / 60), minutes: minutes % 60 })
    const setSleep = (minutes: number | null) => {
        clearSleep()
        setSleepPick(minutes)
        if (minutes === null) return flash(t('player.sleep_off'))
        if (minutes === 0) {
            setSleepEnd(true)
            sleepState.end = true
        } else {
            const at = Date.now() + minutes * 60_000
            setSleepAt(at)
            sleepState.at = at
            armSleep()
        }
        flash(t('player.sleep_in', { time: sleepLabel(minutes) }))
    }
    const sleepSummary = () => {
        const at = sleepAt()
        if (at !== null) return t('sleep.minutes_left', { count: Math.max(1, Math.ceil((at - Date.now()) / 60_000)) })
        return sleepEnd() ? t('player.sleep_end_of_video') : t('common.off')
    }
    const onVisible = () => document.visibilityState === 'visible' && sleepAt() !== null && sleepCheck()
    onMount(() => (armSleep(), document.addEventListener('visibilitychange', onVisible)))
    onCleanup(() => {
        clearTimeout(sleepTimer)
        document.removeEventListener('visibilitychange', onVisible)
        if (!keepSleep) (sleepState.at = null, (sleepState.end = false))
    })
    // Two hours of playing with nothing pressed (StillWatching.askAfterIdle): one timer, armed only while it plays.
    let idleTimer = 0
    const armIdle = () => {
        clearTimeout(idleTimer)
        if (v.paused) return
        const left = IDLE_LIMIT - (Date.now() - watching.lastInput)
        idleTimer = window.setTimeout(() => {
            if (v.paused) return
            if (Date.now() - watching.lastInput >= IDLE_LIMIT) askStill('idle')
            else armIdle()
        }, Math.max(1000, left))
    }
    listen('play', armIdle)
    listen('pause', () => clearTimeout(idleTimer))
    onMount(() => {
        for (const e of ['keydown', 'pointerdown'] as const) document.addEventListener(e, noteInput, { capture: true, passive: true })
        onCleanup(() => (['keydown', 'pointerdown'] as const).forEach((e) => document.removeEventListener(e, noteInput, { capture: true })))
    })
    onCleanup(() => clearTimeout(idleTimer))

    /** Another episode: autoplay's count and the sleep timer go with it. */
    const go = (next: VideoItem | null) => {
        keepSleep = true
        c.play(next)
    }

    // ---- bookmarks (Bookmarks.kt) --------------------------------------------------------------
    const [bookmarks, setBookmarks] = createSignal<Bookmark[]>([])
    onMount(() => loadBookmarks(c.item.chatId, c.item.messageId).then(setBookmarks, () => {}))
    const bookmarkHere = () => {
        const { list, saved } = addBookmark(c.item.chatId, c.item.messageId, bookmarks(), v.currentTime)
        setBookmarks(list)
        flash(t('player.bookmark_saved', { time: clock(saved.at) }))
    }

    // ---- picture in picture, by hand only ------------------------------------------------------
    type WebkitVideo = HTMLVideoElement & { webkitSupportsPresentationMode?: (m: string) => boolean; webkitSetPresentationMode?: (m: string) => void; webkitPresentationMode?: string }
    const wv = v as WebkitVideo
    const pipSupported = () =>
        (!!document.pictureInPictureEnabled && !v.disablePictureInPicture && !!v.requestPictureInPicture) ||
        (typeof wv.webkitSetPresentationMode === 'function' && !!wv.webkitSupportsPresentationMode?.('picture-in-picture'))
    const togglePip = async () => {
        try {
            if (document.pictureInPictureElement) await document.exitPictureInPicture()
            else if (document.pictureInPictureEnabled && v.requestPictureInPicture) await v.requestPictureInPicture()
            else wv.webkitSetPresentationMode?.(wv.webkitPresentationMode === 'picture-in-picture' ? 'inline' : 'picture-in-picture')
        } catch {}
    }

    // ---- skip offers from the file's own chapters -----------------------------------------------
    const segments = createMemo(() => (c.info()?.episode ? skipSegments(chapters(), duration()) : []))
    const [skipNow, setSkipNow] = createSignal<SkipSegment | null>(null)
    listen('timeupdate', () => {
        const list = segments()
        if (!list.length) return void (skipNow() && setSkipNow(null))
        const p = v.currentTime
        const hit = list.find((s) => p >= s.start && p < s.end - 1) ?? null
        if (hit !== skipNow()) setSkipNow(hit)
    })
    const skipLabel = (s: SkipSegment) => t(`player.skip_${s.kind}`)
    const skipIt = () => {
        const s = skipNow()
        if (s) (seekTo(s.end), setSkipNow(null))
    }

    // ---- subtitle timing and look ----------------------------------------------------------------
    const nudgeDelay = (d: number) => {
        const next = Math.round((subDelay() + d) * 10) / 10
        setSubDelay(next)
        applyDelay(v, next)
        flash(t('player.subtitle_delay', { delay: delayText(next) }))
    }
    const changeLook = (next: SubtitleLook) => {
        setLook(next)
        writeLook(next)
    }
    const setDelay = (d: number) => {
        setSubDelay(d)
        applyDelay(v, d)
    }
    const toggleWatched = () => {
        const on = !c.watched()
        c.markWatched(on)
        flash(on ? t('player.marked_watched') : t('player.marked_unwatched'))
    }
    const stepShape = (dir: 1 | -1) => {
        const next = SHAPES[Math.max(0, Math.min(SHAPES.length - 1, SHAPES.indexOf(shape()) + dir))]
        if (next === shape()) return
        setShape(next)
        flash(t('player.picture_shape_now', { shape: shapeName(next) }))
    }
    const repeatText = () => (!loop() ? t('player.loop_set_start') : loop()!.end === null ? t('player.loop_set_end') : t('player.loop_clear'))
    const stepSize = (dir: -1 | 1) => {
        const at = SIZES.indexOf(look().size)
        const next = SIZES[Math.max(0, Math.min(SIZES.length - 1, at + dir))]
        changeLook({ ...look(), size: next })
        flash(`${t('tracks.subtitle_size')}: ${t(`subtitles.size_${next}`)}`)
    }

    // ---- autoplay and the countdown on the next card --------------------------------------------
    const [autoplay, setAutoplay] = createSignal(true)
    onMount(() => readAutoplay().then(setAutoplay, () => {}))
    const changeAutoplay = (on: boolean) => {
        setAutoplay(on)
        writeAutoplay(on)
        if (!on) setCounting(null)
    }
    const [counting, setCounting] = createSignal<number | null>(null)
    let countTimer = 0
    const stopCount = () => {
        clearInterval(countTimer)
        countTimer = 0
        setCounting(null)
    }
    const startCount = (seconds: number) => {
        stopCount()
        setCounting(seconds)
        countTimer = window.setInterval(() => {
            const left = (counting() ?? 0) - 1
            if (left > 0) return setCounting(left)
            const next = c.info()?.next
            stopCount()
            if (!next) return
            watching.autoplayed++
            go(next)
        }, 1000)
    }
    onCleanup(() => clearInterval(countTimer))
    // The video ended: a sleep timer set for its end stops here; otherwise autoplay counts down, or asks "Still watching?" after a run.
    const ended = () => {
        const next = c.info()?.next
        if (sleepEnd()) {
            clearSleep()
            return askStill('sleep_end')
        }
        if (!next) return
        setNextUp(true)
        if (!autoplay() || nextHidden()) return
        if (watching.autoplayed >= AUTOPLAY_LIMIT) return askStill('autoplay')
        startCount(5)
    }
    // Anything the viewer does with the picture stops the countdown.
    listen('seeking', stopCount)
    listen('play', stopCount)

    // Full screen: the player's own box where the browser has it; the iPhone's video full screen otherwise.
    const canFullscreen = () => !!document.fullscreenEnabled || 'webkitEnterFullscreen' in v
    const toggleFullscreen = () => {
        if (document.fullscreenElement) return void document.exitFullscreen().catch(() => {})
        if (document.fullscreenEnabled && c.root.requestFullscreen) return void c.root.requestFullscreen({ navigationUI: 'hide' }).catch(() => {})
        ;(v as HTMLVideoElement & { webkitEnterFullscreen?: () => void }).webkitEnterFullscreen?.()
    }
    // Rotate: where the browser can lock the screen's turn (Chrome on Android, in full screen).
    const orientation = (screen as Screen & { orientation?: ScreenOrientation & { lock?: (o: string) => Promise<void> } }).orientation
    const canRotate = () => phone() && !!orientation?.lock && !!document.fullscreenEnabled
    const rotate = async () => {
        try {
            if (!document.fullscreenElement) await c.root.requestFullscreen({ navigationUI: 'hide' })
            const upright = orientation!.type.startsWith('portrait')
            await orientation!.lock!(upright ? 'landscape' : 'portrait')
            flash(upright ? t('player.rotation_landscape') : t('player.rotation_portrait'))
        } catch {}
    }

    const lock = () => {
        setSheet(null)
        hide()
        setLocked(true)
        showUnlock()
    }
    const [unlockShown, setUnlockShown] = createSignal(false)
    let unlockTimer = 0
    const showUnlock = () => {
        clearTimeout(unlockTimer)
        setUnlockShown(true)
        unlockTimer = window.setTimeout(() => setUnlockShown(false), UNLOCK_PILL_MS)
    }
    const unlock = () => {
        clearTimeout(unlockTimer)
        setLocked(false)
        setUnlockShown(false)
        show()
    }
    onCleanup(() => clearTimeout(unlockTimer))

    // ---- times -------------------------------------------------------------------------------
    const REMAINING = 'tmplayer-remaining'
    const [remaining, setRemaining] = createSignal((() => {
        try {
            return localStorage.getItem(REMAINING) === '1'
        } catch {
            return false
        }
    })())
    const flipRemaining = () => {
        const next = !remaining()
        setRemaining(next)
        try {
            if (next) localStorage.setItem(REMAINING, '1')
            else localStorage.removeItem(REMAINING)
        } catch {}
        poke()
    }
    const [dragTime, setDragTime] = createSignal<number | null>(null)
    /** Where the video was when a drag on the bar began: what a slip there is measured from. */
    let dragFrom = -1
    const shownTime = () => dragTime() ?? time()
    const endText = () => (remaining() && duration() ? `-${clock(duration() - shownTime())}` : clock(duration()))

    // ---- the TV's remote (PlayerActivity.dispatchKeyEvent and PlayerControls' D-pad rules) --
    // Over the bare picture: Left and Right jump ten seconds at once (held, they repeat), OK plays
    // or pauses (a pause raises the row with the remote on the disc), Up and Down raise the row,
    // Back leaves. With the row up: the remote is on the bar (Left and Right seek, OK plays or
    // pauses), Down walks to the buttons, Up back to the bar and the disc, Back puts the row away.
    // A list or menu keeps the remote inside it; Back closes it. The media keys work throughout.
    const jumpRemote = (forward: boolean) => {
        const w = c.root.clientWidth
        jump(forward ? 'right' : 'left', w * (forward ? 0.85 : 0.15), c.root.clientHeight / 2)
    }
    const tvKey = (e: KeyboardEvent): boolean | 'native' => {
        const key = remoteKey(e)
        if (!key) return false
        const active = document.activeElement as HTMLElement | null
        switch (key) {
            case 'play':
                if (v.paused) (togglePlay(), flash(t('player.play')))
                return true
            case 'pause':
            case 'stop':
                if (!v.paused) v.pause()
                return true
            case 'playpause':
                if (!e.repeat) togglePlay()
                return true
            case 'forward':
            case 'rewind':
                jumpRemote(key === 'forward')
                return true
            case 'next':
            case 'previous': {
                const to = key === 'next' ? info()?.next : info()?.previous
                if (!to) return false
                go(to)
                return true
            }
        }
        const arrow = key === 'up' || key === 'down' || key === 'left' || key === 'right'
        if (c.blocked()) {
            if (key === 'back') c.close()
            else if (arrow) walk(c.root.querySelector('.pc-status'), key, active)
            else return 'native'
            return true
        }
        if (sheet()) {
            if (key === 'back' || (sheet() === 'details' && key === 'ok')) setSheet(null)
            else if (key === 'ok') return 'native'
            else if (arrow && ['episodes', 'timing', 'bookmarks', 'still'].includes(sheet()!)) walk(c.root.querySelector('.pc-sheet, .pc-still'), key, active)
            return true
        }
        // The next episode's card holds Left, Right and OK once it has the remote; Back puts it away.
        if (counting() !== null && key === 'back') return (stopCount(), setNextHidden(true), true)
        const card = c.root.querySelector<HTMLElement>('.pc-next')
        if (card && active && card.contains(active)) {
            if (key === 'back') (stopCount(), setNextHidden(true), focusIt(c.root))
            else if (key === 'left' || key === 'right') walk(card, key, active)
            else if (key === 'ok') return 'native'
            if (key === 'back' || key === 'left' || key === 'right') return true
        }
        if (!(visible() && !locked())) {
            const playNow = card?.querySelector<HTMLElement>('button:last-child')
            if (key === 'left' || key === 'right') playNow ? focusIt(playNow) : jumpRemote(key === 'right')
            else if (key === 'ok' && skipNow() && !playNow) skipIt()
            else if (key === 'ok') playNow ? focusIt(playNow) : togglePlay()
            else if (key === 'up' && backTo() !== null) takeBack()
            else if (key === 'up' && offer() && c.resumedAt() !== null) (c.startOver(), setOffer(false))
            else if (key === 'up' || key === 'down') show()
            else if (key === 'back') c.close()
            return true
        }
        poke()
        const items = rowItems()
        const at = active ? items.indexOf(active) : -1
        const bar = barEl()
        const disc = discEl()
        if (key === 'back') hide()
        else if (key === 'ok') {
            if (inLayer(active) && active !== bar) return 'native'
            togglePlay()
        } else if (key === 'left' || key === 'right') {
            if (at >= 0) focusIt(items[at + (key === 'right' ? 1 : -1)])
            else if (active === bar) seekBy(key === 'right' ? 10 : -10)
            else if (active !== disc) jumpRemote(key === 'right')
        } else if (key === 'up') {
            if (at >= 0) focusIt(bar)
            else if (active === bar) focusIt(disc)
            else if (active !== disc) focusIt(bar)
        } else if (key === 'down') {
            if (at >= 0) return true
            if (active === disc) focusIt(bar)
            else if (active === bar) focusIt(items[0])
            else focusIt(bar)
        }
        return true
    }

    // ---- keys (PlayerKeys.actionFor, the parts a browser can do) --------------------------
    const onKey = (e: KeyboardEvent) => {
        if (e.defaultPrevented) return
        const target = e.target as HTMLElement | null
        if (target && (target.isContentEditable || (target instanceof HTMLInputElement && target.type !== 'range') || target instanceof HTMLTextAreaElement)) return
        if (tv()) {
            const handled = tvKey(e)
            if (handled) {
                if (handled === true) e.preventDefault()
                return
            }
        }
        if (c.blocked()) return
        if (locked()) {
            if (e.key === 'Enter') unlock()
            else showUnlock()
            e.preventDefault()
            return
        }
        // A sheet or menu up: Escape closes it, the rest is its own.
        if (sheet()) {
            if (e.key === 'Escape') {
                setSheet(null)
                e.preventDefault()
            }
            return
        }
        const k = e.key
        const shift = e.shiftKey
        const command = e.ctrlKey || e.metaKey
        const plain = !shift && !e.ctrlKey && !e.altKey && !e.metaKey
        const onButton = target instanceof HTMLButtonElement || target?.getAttribute('role') === 'slider'
        let done = true
        if (k === 'ArrowLeft' || k === 'ArrowRight') {
            if (target?.getAttribute('role') === 'slider') return
            const sign = k === 'ArrowRight' ? 1 : -1
            // The command key alone steps through chapters, where the file has them (else it is a minute).
            if (command && !shift && !e.altKey && chapters().length) stepChapter(sign > 0)
            else {
                const s = (e.ctrlKey && e.altKey) ? 300 : shift || e.ctrlKey ? 60 : e.altKey ? 10 : 5
                seekBy(sign * s)
                flash(`${sign > 0 ? '+' : '-'}${t('player.seek_seconds', { seconds: s })}`, sign > 0 ? 'right' : 'left')
            }
        } else if (k === 'ArrowUp' || k === 'ArrowDown') {
            setVol(v.volume + (k === 'ArrowUp' ? 0.05 : -0.05))
            flash(`${Math.round(v.volume * 100)}%`)
        } else if ((k === ' ' || k === 'Enter') && onButton) return
        else if (command || e.altKey) return
        else if ((k === ' ' || k === 'k' || k === 'K' || k === 'Enter') && plain) togglePlay()
        else if (k === 'p' || k === 'P') shift ? go(c.info()?.previous ?? null) : togglePlay()
        else if ((k === 'n' || k === 'N') && shift) go(c.info()?.next ?? null)
        else if (k === 'PageDown') go(c.info()?.next ?? null)
        else if (k === 'PageUp') go(c.info()?.previous ?? null)
        else if ((k === 'r' || k === 'R') && !shift) pressRepeat()
        else if ((k === 'b' || k === 'B') && !shift) bookmarkHere()
        else if ((k === 'g' || k === 'G') && !shift) skipNow() ? skipIt() : (done = false)
        else if (k === 'G' && shift) stepSize(1)
        else if (k === 'F' && shift) stepSize(-1)
        else if (k === 'z' || k === 'Z' || k === 'x' || k === 'X') nudgeDelay(k === 'z' ? -0.1 : 0.1)
        else if (k === '?') setSheet('shortcuts')
        else if (k === 'j' || k === 'J') seekBy(-10)
        else if (k === 'l' || k === 'L') seekBy(10)
        else if (k === 'm' || k === 'M') v.muted = !v.muted
        else if ((k === 'f' || k === 'F') && !shift) toggleFullscreen()
        else if (k === 'c' || k === 'C') cycleSub()
        else if (k === 'v' || k === 'V') cycleSub(shift)
        else if (k === 'a' || k === 'A') cycleAudio(shift)
        else if ((k === 'w' || k === 'W') && !shift) cycleShape()
        else if (k === ']' || k === '=' || k === '+' || k === '>') stepSpeed(true)
        else if (k === '[' || k === '-' || k === '<') stepSpeed(false)
        else if (k === ',' && v.paused) seekBy(-1 / 30)
        else if (k === '.' && v.paused) seekBy(1 / 30)
        else if (k === 'Home') seekTo(0)
        else if (k === 'End') seekTo(duration())
        else if (/^[0-9]$/.test(k) && !shift) seekTo((duration() * Number(k)) / 10)
        else if (k === 'i' || k === 'I') setSheet('details')
        else if (k === 'Backspace' && shift) takeBack()
        else if (k === 'Backspace') {
            if (Math.abs(v.playbackRate - 1) > 0.001) setSpeed(1)
            else c.close()
        } else if (k === 'Escape') {
            if (document.fullscreenElement) document.exitFullscreen().catch(() => {})
            else c.close()
        } else done = false
        if (done) {
            e.preventDefault()
            if (!['ArrowUp', 'ArrowDown', 'm', 'M'].includes(k)) show()
        }
    }
    onMount(() => document.addEventListener('keydown', onKey))
    onCleanup(() => document.removeEventListener('keydown', onKey))

    // ---- the picture: taps, clicks and the phone's gestures ---------------------------------
    const [hold, setHold] = createSignal(false)
    const [ripple, setRipple] = createSignal<{ side: 'left' | 'right'; total: number; x: number; y: number; n: number } | null>(null)
    const [level, setLevel] = createSignal<{ side: 'left' | 'right'; value: number; brightness: boolean } | null>(null)
    const [scrub, setScrub] = createSignal<{ from: number; to: number; rate?: number } | null>(null)
    const [brightness, setBrightness] = createSignal(1)
    createEffect(() => (v.style.filter = brightness() < 0.999 ? `brightness(${brightness()})` : ''))

    let down: { x: number; y: number; at: number; id: number; time: number; vol: number; bright: number } | null = null
    /** Fingers on the picture (the phone's pinch steps the picture shape, once per pinch). */
    const touches = new Map<number, { x: number; y: number }>()
    let pinch: { dist: number; done: boolean } | null = null
    const spread = () => {
        const [a, b] = [...touches.values()]
        return Math.hypot(a.x - b.x, a.y - b.y)
    }
    /** The sideways scrub, slowed to a half and a quarter as the finger leaves its line (FineScrub.Tracker). */
    let fineScrub = { x: 0, last: 0 }
    let mode: 'tap' | 'hold' | 'scrub' | 'level' = 'tap'
    let holdTimer = 0
    let heldRate = 1
    let lastTap = { at: 0, zone: '' as 'left' | 'right' | 'centre' | '', x: 0 }
    let tapTimer = 0
    let runUntil = 0
    let rippleTimer = 0
    let levelTimer = 0
    let scrubTimer = 0
    const zoneOf = (x: number): 'left' | 'right' | 'centre' => {
        const w = c.root.clientWidth
        return x < w * SIDE ? 'left' : x > w * (1 - SIDE) ? 'right' : 'centre'
    }
    const local = (e: PointerEvent) => {
        const r = c.root.getBoundingClientRect()
        return { x: e.clientX - r.left, y: e.clientY - r.top }
    }
    const jump = (side: 'left' | 'right', x: number, y: number) => {
        seekBy(side === 'left' ? -10 : 10)
        const prev = ripple()
        const total = prev && prev.side === side ? prev.total + 10 : 10
        setRipple({ side, total, x, y, n: (prev?.n ?? 0) + 1 })
        runUntil = performance.now() + RUN_MS
        clearTimeout(rippleTimer)
        rippleTimer = window.setTimeout(() => setRipple(null), RUN_MS + 300)
    }
    /** The desktop's wheel over the picture: volume, or with Shift ten seconds a notch. */
    const onWheel = (e: WheelEvent) => {
        if (phone() || tv() || locked() || c.blocked() || sheet() || !e.deltaY) return
        e.preventDefault()
        if (e.shiftKey) {
            seekBy(e.deltaY < 0 ? 10 : -10)
            flash(`${e.deltaY < 0 ? '+' : '-'}${t('player.seek_seconds', { seconds: 10 })}`, e.deltaY < 0 ? 'right' : 'left')
        } else {
            setVol(v.volume + (e.deltaY < 0 ? 0.05 : -0.05))
            flash(`${Math.round(v.volume * 100)}%`)
        }
    }
    const onDown = (e: PointerEvent) => {
        if (locked() || e.button > 0) return
        if (phone() && e.pointerType === 'touch') {
            touches.set(e.pointerId, { x: e.clientX, y: e.clientY })
            if (touches.size === 2) {
                clearTimeout(holdTimer)
                clearTimeout(tapTimer)
                if (mode === 'hold') (v.playbackRate = heldRate, setHold(false))
                mode = 'tap'
                down = null
                pinch = { dist: spread(), done: false }
                return
            }
            if (touches.size > 2 || pinch) return
        }
        const p = local(e)
        down = { ...p, at: performance.now(), id: e.pointerId, time: v.currentTime, vol: v.volume, bright: brightness() }
        mode = 'tap'
        clearTimeout(holdTimer)
        if (!v.paused) {
            holdTimer = window.setTimeout(() => {
                if (!down || mode !== 'tap') return
                mode = 'hold'
                heldRate = v.playbackRate
                v.playbackRate = 2
                setHold(true)
            }, e.pointerType === 'mouse' ? 500 : HOLD_MS)
        }
    }
    const onMove = (e: PointerEvent) => {
        if (pinch) {
            if (touches.has(e.pointerId)) touches.set(e.pointerId, { x: e.clientX, y: e.clientY })
            if (!pinch.done && touches.size === 2) {
                const ratio = spread() / pinch.dist
                if (ratio > 1.15 || ratio < 0.85) (pinch.done = true, stepShape(ratio > 1 ? 1 : -1))
            }
            return
        }
        if (variant() !== 'phone' && e.pointerType === 'mouse') {
            show()
            return
        }
        if (!down || e.pointerId !== down.id || mode === 'hold') return
        const p = local(e)
        const dx = p.x - down.x
        const dy = p.y - down.y
        if (mode === 'tap' && Math.hypot(dx, dy) > SLOP) {
            clearTimeout(holdTimer)
            ;(e.currentTarget as HTMLElement).setPointerCapture?.(e.pointerId)
            if (Math.abs(dx) > Math.abs(dy)) (mode = 'scrub', (fineScrub = { x: down.x, last: down.x }))
            else mode = 'level'
        }
        const w = c.root.clientWidth
        const h = c.root.clientHeight
        if (mode === 'scrub' && duration()) {
            // A full width's drag covers a ninetieth of an hour-long film per pixel, as the app's scrub reads.
            const rate = fineRate(dy)
            fineScrub.x += (p.x - fineScrub.last) * rate
            fineScrub.last = p.x
            const to = Math.max(0, Math.min(duration(), down.time + ((fineScrub.x - down.x) / w) * Math.min(duration(), 600)))
            setScrub({ from: down.time, to, rate })
            clearTimeout(scrubTimer)
        } else if (mode === 'level') {
            const left = down.x < w / 2
            const change = -dy / (h * 0.8)
            if (left) {
                const b = Math.max(0.15, Math.min(1, down.bright + change))
                setBrightness(b)
                setLevel({ side: 'left', value: (b - 0.15) / 0.85, brightness: true })
            } else if (!FIXED_VOLUME) {
                setVol(down.vol + change)
                setLevel({ side: 'right', value: v.volume, brightness: false })
            }
            clearTimeout(levelTimer)
        }
    }
    const onUp = (e: PointerEvent) => {
        touches.delete(e.pointerId)
        if (pinch) {
            if (!touches.size) pinch = null
            return
        }
        clearTimeout(holdTimer)
        if (!down || e.pointerId !== down.id) return
        const start = down
        down = null
        if (mode === 'hold') {
            v.playbackRate = heldRate
            setHold(false)
            return
        }
        if (mode === 'scrub') {
            const s = scrub()
            if (s) seekTo(s.to, start.time)
            scrubTimer = window.setTimeout(() => setScrub(null), 400)
            return
        }
        if (mode === 'level') {
            levelTimer = window.setTimeout(() => setLevel(null), 1000)
            return
        }
        const zone = zoneOf(start.x)
        const now = performance.now()
        if (e.pointerType === 'mouse') {
            // Desktop: a click plays or pauses, a double click goes full screen.
            if (now - lastTap.at < DOUBLE_MS) {
                clearTimeout(tapTimer)
                lastTap.at = 0
                toggleFullscreen()
                return
            }
            lastTap = { at: now, zone, x: start.x }
            tapTimer = window.setTimeout(() => (plain() ? toggle() : togglePlay()), DOUBLE_MS)
            return
        }
        // Touch: a side tap during a run adds a jump; a double tap on a side jumps, in the middle plays or pauses.
        if (zone !== 'centre' && now < runUntil && ripple()?.side === zone) {
            clearTimeout(tapTimer)
            jump(zone, start.x, start.y)
            return
        }
        if (now - lastTap.at < DOUBLE_MS && lastTap.zone === zone) {
            clearTimeout(tapTimer)
            lastTap.at = 0
            if (zone === 'centre') togglePlay()
            else jump(zone, start.x, start.y)
            return
        }
        lastTap = { at: now, zone, x: start.x }
        tapTimer = window.setTimeout(toggle, DOUBLE_MS)
    }
    const onCancel = (e: PointerEvent) => {
        touches.delete(e.pointerId)
        if (pinch) {
            if (!touches.size) pinch = null
            return
        }
        clearTimeout(holdTimer)
        if (mode === 'hold') {
            v.playbackRate = heldRate
            setHold(false)
        }
        down = null
    }
    onCleanup(() => [holdTimer, tapTimer, rippleTimer, levelTimer, scrubTimer].forEach(clearTimeout))

    // ---- what the row offers ---------------------------------------------------------------
    const info = () => c.info()
    const prevCode = () => info()?.previousCode ?? info()?.previous?.title ?? ''
    const nextCode = () => info()?.nextCode ?? info()?.next?.title ?? ''
    const hasEpisodes = () => (info()?.seasons.length ?? 0) > 0
    const copyLabel = () => (c.copies.length ? c.qualityLabel(c.sourceHeight()) : '')
    const wideRow = () => width() >= WIDE_ROW
    const volumeIcon = () => (muted() || volume() === 0 ? VolumeMute02Icon : volume() < 0.5 ? VolumeLowIcon : VolumeHighIcon)

    // The Start over offer, for a few seconds after resuming.
    const [offer, setOffer] = createSignal(false)
    createEffect(on(c.resumedAt, (at) => {
        if (at === null) return
        setOffer(true)
        const timer = window.setTimeout(() => setOffer(false), 7000)
        onCleanup(() => clearTimeout(timer))
    }))

    // The next episode's card: from 30 s before the end (the player says when), and at the end.
    const [nextUp, setNextUp] = createSignal(false)
    const [nextHidden, setNextHidden] = createSignal(false)
    listen('timeupdate', () => {
        const d = v.duration
        setNextUp(!!info()?.next && isFinite(d) && d > 60 && d - v.currentTime <= 30)
    })
    listen('ended', ended)
    createEffect(on(() => info()?.next?.key, () => setNextHidden(false), { defer: true }))

    /** "Keep watching": the run starts again, and the video goes on (or on to the next episode, if it had ended). */
    const keepWatching = () => {
        noteInput()
        setSheet(null)
        const next = c.info()?.next
        if (v.ended && next) go(next)
        else v.play().catch(() => {})
    }

    const menuAt = (name: Exclude<Sheet, null>) => (e: MouseEvent) => {
        e.stopPropagation()
        opener = e.currentTarget as HTMLElement
        setAnchor((e.currentTarget as HTMLElement).getBoundingClientRect())
        setSheet(sheet() === name ? null : name)
    }
    const [anchor, setAnchor] = createSignal<DOMRect | null>(null)

    const Btn = (p: { icon: IconData; label: string; onClick: (e: MouseEvent) => void; class?: string; size?: number; tipBelow?: boolean; shot?: string; pressed?: boolean }) => (
        <button
            type="button"
            class={`pc-btn ${p.class ?? ''}`}
            aria-label={p.label}
            data-tip={phone() ? undefined : p.label}
            data-tip-below={p.tipBelow ? '' : undefined}
            data-shot={p.shot}
            aria-expanded={p.pressed}
            onClick={(e) => (p.onClick(e), poke())}
        >
            <Icon icon={p.icon} size={p.size ?? 24} />
        </button>
    )

    return (
        <div
            class="pc"
            data-variant={variant()}
            classList={{ shown: visible() && !locked() && !c.blocked(), locked: locked(), sheeted: !!sheet() }}
            onPointerMove={(e) => e.pointerType === 'mouse' && !locked() && variant() !== 'phone' && show()}
        >
            <div
                class="pc-picture"
                onPointerDown={onDown}
                onPointerMove={onMove}
                onPointerUp={onUp}
                onPointerCancel={onCancel}
                onWheel={onWheel}
                onContextMenu={(e) => (phone() || hold()) && e.preventDefault()}
            />

            <div class="pc-layer" aria-hidden={!visible() || undefined}>
                <div class="pc-tint" />
                <div class="pc-scrim top" />
                <div class="pc-scrim bottom" />

                <div class="pc-top" onPointerEnter={() => setHeld(true)} onPointerLeave={() => setHeld(false)}>
                    <Btn icon={ArrowLeft02Icon} label={plain() ? t('common.back') : t('player.back_hint')} onClick={() => c.close()} tipBelow />
                    <div class="pc-titles">
                        <div class="pc-title">{info()?.title ?? c.item.title}</div>
                        <Show when={subtitleLine(info(), c.item)}>{(line) => <div class="pc-subtitle">{line()}</div>}</Show>
                    </div>
                    <Btn icon={MoreVerticalIcon} label={phone() ? t('player.more_options') : t('common.more')} onClick={menuAt('more')} tipBelow shot="more" pressed={sheet() === 'more'} />
                </div>

                <Show when={c.loaded() !== null && !c.blocked()}>
                    <span class="pc-chipline loading" role="status">{t('player.cached_chip', { percent: `${Math.round(c.loaded()! * 100)}%` })}</span>
                </Show>

                <div class="pc-center" onPointerEnter={() => setHeld(true)} onPointerLeave={() => setHeld(false)}>
                    <div class="pc-step" classList={{ gone: !info()?.previous }}>
                        <Show when={phone()}><span class="pc-caption-space" /></Show>
                        <Btn
                            icon={PreviousIcon}
                            class="tonal step"
                            size={phone() ? 28 : 34}
                            label={t('player.previous_episode_hint', { episode: t('player.previous_up', { title: prevCode() }) })}
                            onClick={() => go(info()?.previous ?? null)}
                        />
                        <Show when={phone()}><span class="pc-caption">{info()?.previousCode ?? ''}</span></Show>
                    </div>
                    <button
                        type="button"
                        class="pc-btn tonal pc-play"
                        aria-label={paused() ? t('player.play') : t('player.pause')}
                        aria-live="polite"
                        data-tip={phone() ? undefined : tv() ? (paused() ? t('player.play') : t('player.pause')) : `${paused() ? t('player.play') : t('player.pause')} (Space, K)`}
                        onClick={() => (togglePlay(), poke())}
                    >
                        <Show when={!waiting() || paused()} fallback={<span class="pc-spinner" />}>
                            <span class="pc-play-glyph" data-state={paused() ? 'play' : 'pause'}>
                                <Icon icon={paused() ? PlaySolid : PauseSolid} size={40} />
                            </span>
                        </Show>
                    </button>
                    <div class="pc-step" classList={{ gone: !info()?.next }}>
                        <Show when={phone()}><span class="pc-caption-space" /></Show>
                        <Btn
                            icon={NextIcon}
                            class="tonal step"
                            size={phone() ? 28 : 34}
                            label={t('player.next_episode_hint', { episode: t('player.next_up', { title: nextCode() }) })}
                            onClick={() => go(info()?.next ?? null)}
                        />
                        <Show when={phone()}><span class="pc-caption">{info()?.nextCode ?? ''}</span></Show>
                    </div>
                </div>

                <Show when={tv()}>
                    <div class="pc-clock" aria-hidden="true">{wall()}</div>
                </Show>

                <div class="pc-bottom" onPointerEnter={() => setHeld(true)} onPointerLeave={() => setHeld(false)}>
                    <Show when={tv()}>
                        <div class="pc-cluster-title">{info()?.title ?? c.item.title}</div>
                        <Show when={subtitleLine(info(), c.item)}>{(line) => <div class="pc-cluster-sub">{line()}</div>}</Show>
                    </Show>
                    <div class="pc-times">
                        <span>{clock(shownTime())}</span>
                        <button type="button" class="pc-total" onClick={flipRemaining} aria-label={remaining() ? t('player.show_total_length') : t('player.show_time_remaining')}>
                            {endText()}
                        </button>
                    </div>
                    <SeekBar
                        time={time}
                        duration={duration}
                        buffered={buffered}
                        frames={c.frames}
                        dragTime={dragTime}
                        marks={() => chapterMarks(chapters(), duration())}
                        chapterAt={chapterTextAt}
                        setDragTime={(s) => {
                            if (s !== null && dragTime() === null) dragFrom = v.currentTime
                            setDragTime(s)
                            if (s === null) poke()
                            else clearTimeout(hideTimer)
                        }}
                        onSeek={(s) => {
                            seekTo(s, dragFrom >= 0 ? dragFrom : undefined)
                            dragFrom = -1
                        }}
                        phone={phone()}
                    />
                    <div class="pc-row">
                        <div class="pc-pill">
                            <Btn icon={SubtitleIcon} label={plain() ? t('tracks.subtitles') : t('player.subtitles_hint')} onClick={menuAt('subtitles')} shot="subtitles" pressed={sheet() === 'subtitles'} />
                            <Btn icon={HeadphonesIcon} label={plain() ? t('player.audio') : t('player.audio_hint')} onClick={menuAt('audio')} shot="audio" pressed={sheet() === 'audio'} />
                            <Show
                                when={plain()}
                                fallback={
                                    <button type="button" class="pc-btn pc-speed-text" data-tip={t('player.speed_hint')} data-shot="speed" aria-label={t('player.playback_speed')} onClick={(e) => (menuAt('speed')(e), poke())}>
                                        {speedLabel(rate())}
                                    </button>
                                }
                            >
                                <Btn icon={DashboardSpeed02Icon} label={t('player.playback_speed')} onClick={menuAt('speed')} shot="speed" />
                            </Show>
                            <Show when={!phone() || wideRow()}>
                                <Btn icon={SHAPE_ICON[shape()]} label={plain() ? t('player.picture_shape_now', { shape: shapeName(shape()) }) : t('player.picture_shape_hint', { shape: shapeName(shape()) })} onClick={cycleShape} class="shape" />
                            </Show>
                            <Show when={canRotate()}>
                                <Btn icon={ScreenRotationIcon} label={t('player.screen_orientation')} onClick={rotate} />
                            </Show>
                            <Show when={phone() && wideRow()}>
                                <Btn icon={SquareLock02Icon} label={t('player.lock_screen')} onClick={lock} shot="lock" />
                            </Show>
                            <Show when={tv()}>
                                <Btn icon={MoreVerticalIcon} label={t('common.more')} onClick={menuAt('more')} shot="more" pressed={sheet() === 'more'} />
                            </Show>
                        </div>
                        <Show when={!phone() && !tv()}>
                            <span class="pc-spacer" />
                            <div class="pc-pill">
                                <VolumeControl volume={volume} muted={muted} icon={volumeIcon} onVolume={setVol} onMute={() => (v.muted = !v.muted)} />
                                <Show when={canFullscreen()}>
                                    <Btn
                                        icon={fullscreen() ? ArrowShrinkIcon : ArrowExpandIcon}
                                        label={fullscreen() ? t('player.exit_fullscreen_hint') : t('player.fullscreen_hint')}
                                        onClick={toggleFullscreen}
                                    />
                                </Show>
                            </div>
                        </Show>
                        <Show when={plain() && copyLabel()}>
                            <button type="button" class="pc-chip-button" data-shot="copies" onClick={menuAt('copies')} aria-label={t('player.copies')}>
                                <Icon icon={CopiesIcon} size={24} />
                                {copyLabel()}
                            </button>
                        </Show>
                        <Show when={hasEpisodes()}>
                            <button type="button" class="pc-chip-button" data-shot="episodes" onClick={menuAt('episodes')}>
                                <Icon icon={ListVideoIcon} size={24} />
                                {t('episodes.title')}
                            </button>
                        </Show>
                        <Show when={tv()}>
                            <span class="pc-spacer" />
                            <button type="button" class="pc-readout" onClick={flipRemaining} aria-label={remaining() ? t('player.show_total_length') : t('player.show_time_remaining')}>
                                {`${clock(shownTime())} / ${endText()}`}
                            </button>
                        </Show>
                        <Show when={phone() && canFullscreen() && !canRotate()}>
                            <span class="pc-spacer" />
                            <Btn icon={fullscreen() ? ArrowShrinkIcon : ArrowExpandIcon} label={fullscreen() ? t('player.exit_fullscreen_hint') : t('player.fullscreen_hint')} onClick={toggleFullscreen} class="bare" />
                        </Show>
                    </div>
                </div>
            </div>

            {/* What a gesture or a key puts on the picture while it happens (PlayerFeedback). */}
            <Show when={hold()}>
                <div class="pc-note hold" role="status">{speedLabel(2)}</div>
            </Show>
            <Show when={note()}>{(n) => <div class={`pc-note ${n().side}`} role="status">{n().text}</div>}</Show>
            <Show when={loop()}>
                {(l) => (
                    <button type="button" class="pc-chipline loop" onClick={() => (setLoop(null), flash(t('player.repeat_off')))} aria-label={t('player.loop_clear')}>
                        <b>{l().end === null ? t('player.loop_badge_start') : t('player.loop_badge')}</b>
                        <span>
                            {l().end === null
                                ? plain() ? t('player.loop_from_detail', { start: clock(l().start) }) : t('player.loop_start_chip', { start: clock(l().start) })
                                : plain() ? t('player.repeating', { start: clock(l().start), end: clock(l().end!) }) : t('player.loop_chip', { start: clock(l().start), end: clock(l().end!) })}
                        </span>
                    </button>
                )}
            </Show>
            <Show when={backTo() !== null && !c.blocked()}>
                <button type="button" class="pc-corner back" tabIndex={tv() ? -1 : 0} onClick={takeBack}>
                    <span>{t('player.back_to', { time: clock(backTo()!) })}</span>
                    <Show when={tv() && !visible()}><small>{t('player.back_to_up')}</small></Show>
                </button>
            </Show>
            <Show when={skipNow() && !c.blocked() && !(nextUp() && !nextHidden() && info()?.next)}>
                <button type="button" class="pc-corner skip" tabIndex={tv() ? -1 : 0} onClick={skipIt}>
                    <span>{skipLabel(skipNow()!)}</span>
                    <Show when={tv() && !visible()}><small>{t('player.skip_ok_hint')}</small></Show>
                </button>
            </Show>
            <Show when={ripple()}>
                {(r) => (
                    <div class={`pc-ripple ${r().side}`} aria-hidden="true">
                        <div class="pc-moon" />
                        <Show keyed when={r().n}>
                            <span class="pc-wave" style={{ left: `${r().side === 'left' ? r().x : r().x - c.root.clientWidth * (1 - SIDE)}px`, top: `${r().y}px` }} />
                        </Show>
                        <div class="pc-plate">
                            <span class="pc-chevrons">
                                <i /><i /><i />
                            </span>
                            <span>{t('player.seek_seconds', { seconds: r().total })}</span>
                        </div>
                    </div>
                )}
            </Show>
            <Show when={level()}>
                {(l) => (
                    <div class={`pc-level ${l().side}`} aria-hidden="true">
                        <span class="pc-level-figure">{Math.round(l().value * 100)}</span>
                        <span class="pc-level-bar"><span style={{ height: `${l().value * 100}%` }} /></span>
                        <Icon icon={l().brightness ? Sun03Icon : VolumeHighIcon} size={18} />
                    </div>
                )}
            </Show>
            <Show when={scrub()}>
                {(s) => (
                    <div class="pc-scrub" role="status">
                        <span class="pc-scrub-delta">{`${s().to >= s().from ? '+' : '-'}${clock(Math.abs(s().to - s().from))}`}</span>
                        <span class="pc-scrub-target">{`${clock(s().to)} / ${clock(duration())}`}</span>
                        <span class="pc-scrub-bar"><span style={{ width: `${duration() ? (s().to / duration()) * 100 : 0}%` }} /></span>
                        <Show when={(s().rate ?? 1) < 1}><span class="pc-scrub-hint">{t((s().rate ?? 1) <= 0.25 ? 'player.scrub_quarter' : 'player.scrub_half')}</span></Show>
                    </div>
                )}
            </Show>

            <Show when={offer() && !c.blocked() && c.resumedAt() !== null}>
                <div class="pc-offer" role="status">
                    <span>{t('player.resuming_from', { time: clock(c.resumedAt()!) })}</span>
                    <button type="button" onClick={() => (c.startOver(), setOffer(false))}>{t('player.start_over')}</button>
                </div>
            </Show>

            <Show when={nextUp() && !nextHidden() && info()?.next && !c.blocked()}>
                <div class="pc-next" role="group" aria-label={t('player.next_episode')}>
                    <p>{t('player.next_up', { title: nextCode() })}</p>
                    <Show when={counting() !== null}>
                        <p class="pc-count" role="status">{tv() ? t('player.autoplay_countdown', { seconds: counting()! }) : t('player.next_in', { seconds: counting()! })}</p>
                    </Show>
                    <div>
                        <button type="button" onClick={() => (stopCount(), setNextHidden(true))}>{counting() !== null ? t('common.cancel') : t('common.hide')}</button>
                        <button type="button" onClick={() => go(info()!.next)}>{t('player.play_now')}</button>
                    </div>
                </div>
            </Show>

            {/* The bare picture on a TV: a wait has no disc to show it on. */}
            <Show when={tv() && waiting() && !visible() && !c.blocked()}>
                <span class="pc-spinner pc-bare-spinner" role="status" aria-label={t('common.loading')} />
            </Show>

            {props.children}

            <Switch>
                <Match when={sheet() === 'more'}>
                    <Menu anchor={anchor()} root={c.root} below={!tv()} onClose={() => setSheet(null)}>
                        <Show when={!tv()}>
                            <Entry icon={SquareLock02Icon} text={t('player.lock_screen')} onClick={lock} />
                            <hr />
                        </Show>
                        <Show when={c.copies.length}>
                            <Entry text={t('player.copies')} trailing={copyLabel()} onClick={() => setSheet('copies')} />
                        </Show>
                        <Show when={pipSupported()}>
                            <Entry icon={PictureInPictureOnIcon} text={t('player.picture_in_picture')} onClick={() => (setSheet(null), togglePip())} />
                        </Show>
                        <Entry text={t('player.playback_options')} onClick={() => setSheet('options')} />
                        <Entry text={t('player.picture_shape')} trailing={shapeName(shape())} onClick={() => setSheet('shape')} />
                        <Entry text={t('player.subtitle_style_and_timing')} onClick={() => setSheet('timing')} />
                        <Entry text={t('player.load_subtitle_file')} onClick={() => (setSheet(null), c.loadSubtitleFile())} />
                        <hr />
                        <Entry icon={CheckmarkCircle02Icon} text={c.watched() ? t('player.mark_unwatched') : t('player.mark_watched')} onClick={() => (setSheet(null), toggleWatched())} />
                        <Entry icon={Bookmark02Icon} text={t('player.add_bookmark')} trailing={plain() ? undefined : 'B'} onClick={() => (setSheet(null), bookmarkHere())} />
                        <Entry text={t('player.bookmarks')} trailing={bookmarks().length ? String(bookmarks().length) : undefined} onClick={() => setSheet('bookmarks')} />
                        <hr />
                        <Show when={!plain()}>
                            <Entry icon={KeyboardIcon} text={t('player.shortcuts')} trailing="?" onClick={() => setSheet('shortcuts')} />
                        </Show>
                        <Entry icon={InformationCircleIcon} text={t('player.playback_details')} trailing={phone() ? undefined : 'I'} onClick={() => setSheet('details')} />
                    </Menu>
                </Match>
                <Match when={sheet() === 'options'}>
                    <Menu anchor={anchor()} root={c.root} below={!tv()} onClose={() => setSheet(null)}>
                        <Entry text={t('common.back')} onClick={() => setSheet('more')} />
                        <hr />
                        <Entry icon={Moon02Icon} text={t('player.sleep_timer')} trailing={sleepSummary()} onClick={() => setSheet('sleep')} />
                        <Entry icon={RepeatIcon} text={repeatText()} trailing={plain() ? undefined : 'R'} onClick={() => (setSheet(null), pressRepeat())} />
                        <Show when={chapters().length}>
                            <Entry text={t('player.chapter_next')} trailing={plain() ? undefined : `${t(/Mac/.test(navigator.platform) ? 'keys.cmd' : 'keys.ctrl')}+Right`} onClick={() => (setSheet(null), stepChapter(true))} />
                            <Entry text={t('player.chapter_previous')} trailing={plain() ? undefined : `${t(/Mac/.test(navigator.platform) ? 'keys.cmd' : 'keys.ctrl')}+Left`} onClick={() => (setSheet(null), stepChapter(false))} />
                        </Show>
                    </Menu>
                </Match>
                <Match when={sheet() === 'sleep'}>
                    <Picker title={t('player.sleep_timer')} phone={phone()} anchor={anchor()} root={c.root} onClose={() => setSheet(null)} onBack={phone() ? undefined : () => setSheet('options')}>
                        <Show when={sleepAt() !== null || sleepEnd()}>
                            <Choice icon={Cancel01Icon} text={t('player.sleep_turn_off')} phone={phone()} onClick={() => (setSleep(null), setSheet(null))} />
                        </Show>
                        <For each={SLEEP_CHOICES}>
                            {(m) => <Choice text={sleepLabel(m)} on={sleepPick() === m && (sleepAt() !== null || sleepEnd())} phone={phone()} onClick={() => (setSleep(m), setSheet(null))} />}
                        </For>
                    </Picker>
                </Match>
                <Match when={sheet() === 'bookmarks'}>
                    <BookmarksSheet
                        list={bookmarks}
                        clock={clock}
                        onHere={bookmarkHere}
                        onPlay={(b) => (setSheet(null), seekTo(b.at))}
                        onNote={(b, note) => setBookmarks(renameBookmark(c.item.chatId, c.item.messageId, bookmarks(), b.id, note))}
                        onDelete={(b) => (setBookmarks(deleteBookmark(c.item.chatId, c.item.messageId, bookmarks(), b.id)), flash(t('player.bookmark_deleted')))}
                        onClose={() => setSheet(null)}
                    />
                </Match>
                <Match when={sheet() === 'timing'}>
                    <TimingSheet delay={subDelay} onDelay={setDelay} look={look} onLook={changeLook} onClose={() => setSheet(null)} />
                </Match>
                <Match when={sheet() === 'shortcuts'}>
                    <ShortcutsSheet mac={/Mac/.test(navigator.platform)} onClose={() => setSheet(null)} />
                </Match>
                <Match when={sheet() === 'still'}>
                    <div class="pc-sheet-scrim still">
                        <div class="pc-still" role="alertdialog" aria-label={t('player.still_watching')} ref={(el) => queueMicrotask(() => el.querySelector<HTMLElement>('.primary')?.focus({ preventScroll: true }))}>
                            <h2>{t('player.still_watching')}</h2>
                            <p>
                                {stillWhy() === 'idle'
                                    ? t('player.still_watching_idle')
                                    : stillWhy() === 'autoplay'
                                      ? t('player.still_watching_autoplay', { count: AUTOPLAY_LIMIT })
                                      : stillWhy() === 'sleep'
                                        ? t('player.still_watching_sleep')
                                        : t('player.still_watching_sleep_end')}
                            </p>
                            <p class="muted">{t('player.still_watching_paused')}</p>
                            <div class="pc-still-actions">
                                <button type="button" class="pc-sheet-button" onClick={() => c.close()}>{t('player.go_back')}</button>
                                <button type="button" class="pc-sheet-button primary" onClick={keepWatching}>{t('player.keep_watching')}</button>
                            </div>
                        </div>
                    </div>
                </Match>
                <Match when={sheet() === 'shape'}>
                    <Picker title={t('player.picture_shape')} phone={phone()} anchor={anchor()} root={c.root} onClose={() => setSheet(null)} onBack={phone() ? undefined : () => setSheet('more')}>
                        <For each={SHAPES}>{(s) => <Choice text={shapeName(s)} on={shape() === s} phone={phone()} onClick={() => (setShape(s), setSheet(null))} />}</For>
                    </Picker>
                </Match>
                <Match when={sheet() === 'subtitles'}>
                    <Picker title={t('tracks.subtitles')} phone={phone()} anchor={anchor()} root={c.root} onClose={() => setSheet(null)}>
                        <Choice text={t('common.off')} on={!subOn()} phone={phone()} onClick={() => (pickSub(null), setSheet(null))} />
                        <For each={subs()}>{(tt, i) => <Choice text={subLabel(tt, i())} on={subOn() === tt} phone={phone()} onClick={() => (pickSub(tt), setSheet(null))} />}</For>
                        <Show when={!phone()}><hr /></Show>
                        <Choice icon={Add01Icon} text={t('player.load_subtitle_file')} phone={phone()} onClick={() => (setSheet(null), c.loadSubtitleFile())} />
                    </Picker>
                </Match>
                <Match when={sheet() === 'audio'}>
                    <Picker title={t('player.audio')} phone={phone()} anchor={anchor()} root={c.root} onClose={() => setSheet(null)}>
                        <Show when={c.audios().length} fallback={<Choice text={t('player.no_audio_tracks')} phone={phone()} onClick={() => setSheet(null)} />}>
                            <For each={c.audios()}>
                                {(tr, i) => (
                                    <Choice
                                        text={c.trackLabel(tr, i())}
                                        on={c.audio() === tr}
                                        phone={phone()}
                                        disabled={!c.canSwitchAudio() && c.audio() !== tr}
                                        onClick={() => (c.pickAudio(i()), setSheet(null))}
                                    />
                                )}
                            </For>
                        </Show>
                    </Picker>
                </Match>
                <Match when={sheet() === 'speed'}>
                    <Show
                        when={phone()}
                        fallback={
                            <Picker title={t('player.playback_speed')} phone={false} anchor={anchor()} root={c.root} onClose={() => setSheet(null)}>
                                <For each={SPEEDS}>{(s) => <Choice text={speedLabel(s)} on={Math.abs(rate() - s) < 0.001} phone={false} onClick={() => (setSpeed(s), setSheet(null))} />}</For>
                            </Picker>
                        }
                    >
                        <SpeedSheet rate={rate} landscape={width() > c.root.clientHeight} onPick={(s) => (setSpeed(s), setSheet(null))} onFine={setSpeed} onClose={() => setSheet(null)} />
                    </Show>
                </Match>
                <Match when={sheet() === 'copies'}>
                    <Picker title={t('player.copies')} note={t('player.copies_note')} phone={phone()} anchor={anchor()} root={c.root} onClose={() => setSheet(null)} onBack={phone() ? undefined : () => setSheet('more')}>
                        <For each={[{ key: c.item.key, height: 0 }, ...c.copies]}>
                            {(copy) => <Choice text={c.qualityLabel(copy.height)} on={c.sourceKey() === copy.key} phone={phone()} onClick={() => (c.pickQuality(copy.key), setSheet(null))} />}
                        </For>
                    </Picker>
                </Match>
                <Match when={sheet() === 'episodes'}>
                    <EpisodesPanel info={info()!} current={c.item} phone={phone()} autoplay={autoplay()} onAutoplay={changeAutoplay} onPlay={(item) => (setSheet(null), go(item))} onClose={() => setSheet(null)} />
                </Match>
                <Match when={sheet() === 'details'}>
                    <div class="pc-details" role="dialog" aria-label={t('player.playback_details')}>
                        <div class="pc-details-head">
                            <strong>{t('player.playback_details')}</strong>
                            <button type="button" class="pc-btn" aria-label={t('common.close')} onClick={() => setSheet(null)}>
                                <Icon icon={Cancel01Icon} size={20} />
                            </button>
                        </div>
                        <p>{c.details()}</p>
                        <p>{c.readout()}</p>
                        <p>{t('player.details_speed', { speed: speedLabel(rate()) })}</p>
                    </div>
                </Match>
            </Switch>

            <Show when={locked()}>
                <div class="pc-shield" onClick={showUnlock}>
                    <button type="button" class="pc-unlock" classList={{ shown: unlockShown() }} onClick={(e) => (e.stopPropagation(), unlock())} aria-label={t('player.unlock')}>
                        <Icon icon={SquareUnlock02Icon} size={22} />
                        {t('player.tap_to_unlock')}
                    </button>
                </div>
            </Show>
        </div>
    )
}

/** The top bar's second line: the episode's code and name, else the chat. */
function subtitleLine(info: WatchInfo | null, item: VideoItem): string {
    const ep = info?.episode
    if (ep) return ep.name ? `${ep.code}  ·  ${ep.name}` : ep.code
    return info?.chatTitle ?? item.chat
}

/**
 * The seek bar: the played run in the accent over the buffered run, a thumb that grows while
 * held, and over it the preview card, Telegram's storyboard frame for the time under the finger
 * or the pointer with the time in a chip (StoryboardPreview's SeekPreview; on a phone the chip
 * comes only with a picture, since the times line says it already). The seek lands on release.
 */
function SeekBar(props: {
    time: () => number
    duration: () => number
    buffered: () => number
    frames: () => Frames | null
    dragTime: () => number | null
    setDragTime: (s: number | null) => void
    onSeek: (s: number) => void
    phone: boolean
    /** The chapter starts, drawn as gaps in the bar. */
    marks: () => number[]
    /** The chapter's name for a time, for the preview card. */
    chapterAt: (s: number) => string | null
}) {
    let bar!: HTMLDivElement
    const [hoverX, setHoverX] = createSignal<number | null>(null)
    const [barWidth, setBarWidth] = createSignal(0)
    const fraction = (s: number) => (props.duration() ? Math.max(0, Math.min(1, s / props.duration())) : 0)
    const timeAt = (clientX: number) => {
        const r = bar.getBoundingClientRect()
        setBarWidth(r.width)
        return Math.max(0, Math.min(1, (clientX - r.left) / r.width)) * props.duration()
    }
    const xOf = (clientX: number) => clientX - bar.getBoundingClientRect().left
    let dragging = false
    // A finger moving off the bar slows the drag to a half and a quarter (FineScrub): the bar's position is its own, not the finger's.
    let fine = { x: 0, last: 0, y: 0 }
    const [slow, setSlow] = createSignal(1)
    const down = (e: PointerEvent) => {
        if (!props.duration() || e.button > 0) return
        dragging = true
        bar.setPointerCapture(e.pointerId)
        const r = bar.getBoundingClientRect()
        fine = { x: e.clientX, last: e.clientX, y: r.top + r.height / 2 }
        setSlow(1)
        props.setDragTime(timeAt(e.clientX))
        if (e.pointerType === 'mouse') setHoverX(xOf(e.clientX))
        e.stopPropagation()
    }
    const move = (e: PointerEvent) => {
        if (dragging) {
            if (e.pointerType === 'mouse') props.setDragTime(timeAt(e.clientX))
            else {
                const rate = fineRate(e.clientY - fine.y)
                const r = bar.getBoundingClientRect()
                fine.x = Math.max(r.left, Math.min(r.right, fine.x + (e.clientX - fine.last) * rate))
                fine.last = e.clientX
                setSlow(rate)
                props.setDragTime(timeAt(fine.x))
            }
        }
        if (e.pointerType === 'mouse') {
            setBarWidth(bar.getBoundingClientRect().width)
            setHoverX(xOf(e.clientX))
        }
    }
    const up = () => {
        if (!dragging) return
        dragging = false
        const s = props.dragTime()
        if (s !== null) props.onSeek(s)
        props.setDragTime(null)
        setSlow(1)
    }
    const played = () => fraction(props.dragTime() ?? props.time())
    const ahead = () => fraction(props.buffered())
    // Where the card points: the finger while dragging, else the pointer.
    const at = createMemo(() => {
        const d = props.dragTime()
        if (d !== null) return { time: d, x: fraction(d) * (barWidth() || bar?.clientWidth || 0) }
        const x = hoverX()
        if (x === null || !props.duration()) return null
        const w = barWidth() || bar.clientWidth
        return { time: (Math.max(0, Math.min(w, x)) / w) * props.duration(), x }
    })
    const card = createMemo(() => {
        const a = at()
        if (!a) return null
        const f = props.frames()
        if (props.phone && (!f || props.dragTime() === null)) return null
        return { ...a, frames: f }
    })
    const cardWidth = () => (props.phone ? 200 : 160)
    return (
        <div
            ref={bar}
            class="pc-bar"
            classList={{ thick: hoverX() !== null || props.dragTime() !== null }}
            role="slider"
            tabIndex={0}
            aria-label={t('web.seek_bar')}
            aria-valuemin={0}
            aria-valuemax={Math.round(props.duration())}
            aria-valuenow={Math.round(props.time())}
            aria-valuetext={`${clock(props.time())} / ${clock(props.duration())}`}
            onPointerDown={down}
            onPointerMove={move}
            onPointerUp={up}
            onPointerCancel={up}
            onPointerLeave={(e) => e.pointerType === 'mouse' && !dragging && setHoverX(null)}
            onKeyDown={(e) => {
                if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return
                e.preventDefault()
                e.stopPropagation()
                props.onSeek(props.time() + (e.key === 'ArrowRight' ? 10 : -10))
            }}
        >
            <div class="pc-track">
                <Show when={ahead() > played()}>
                    <div class="pc-buffered" style={{ width: `${ahead() * 100}%` }} />
                </Show>
                <div class="pc-played" style={{ width: `${played() * 100}%` }} />
                <For each={props.marks()}>{(m) => <span class="pc-mark" style={{ left: `${fraction(m) * 100}%` }} />}</For>
            </div>
            <div class="pc-thumb" style={{ left: `${played() * 100}%` }} />
            <Show when={card()}>
                {(k) => {
                    const frame = () => {
                        const f = k().frames
                        if (!f) return null
                        const w = cardWidth()
                        const scale = w / f.width
                        const p = frameAt(f, k().time)
                        return {
                            width: `${w}px`,
                            height: `${Math.round(f.height * scale)}px`,
                            'background-image': `url(${f.url})`,
                            'background-size': `${f.spriteWidth * scale}px ${f.spriteHeight * scale}px`,
                            'background-position': `${-p.x * scale}px ${-p.y * scale}px`,
                        }
                    }
                    const left = () => {
                        const w = Math.max(cardWidth(), 64)
                        const total = barWidth() || bar.clientWidth
                        return Math.max(props.phone ? -16 : 0, Math.min(total - w + (props.phone ? 16 : 0), k().x - w / 2))
                    }
                    return (
                        <div class="pc-preview" style={{ left: `${left()}px`, width: `${Math.max(cardWidth(), 64)}px` }} aria-hidden="true">
                            <Show when={frame()}>{(style) => <div class="pc-frame" style={style()} />}</Show>
                            <Show when={props.chapterAt(k().time)}>{(name) => <span class="pc-chapter">{name()}</span>}</Show>
                            <span class="pc-chip">{clock(k().time)}</span>
                            <Show when={slow() < 1}><span class="pc-slow">{t(slow() <= 0.25 ? 'player.scrub_quarter' : 'player.scrub_half')}</span></Show>
                        </div>
                    )
                }}
            </Show>
        </div>
    )
}

/** The desktop's volume: the speaker button, and on hover a slider beside it (VolumeControl). */
function VolumeControl(props: { volume: () => number; muted: () => boolean; icon: () => IconData; onVolume: (x: number) => void; onMute: () => void }) {
    return (
        <div class="pc-volume">
            <input
                type="range"
                min="0"
                max="100"
                step="1"
                aria-label={t('keys.row_volume')}
                value={props.muted() ? 0 : Math.round(props.volume() * 100)}
                style={{ '--v': `${props.muted() ? 0 : props.volume() * 100}%` }}
                onInput={(e) => props.onVolume(Number(e.currentTarget.value) / 100)}
            />
            <button type="button" class="pc-btn" aria-label={props.muted() ? t('player.unmute_hint') : t('player.mute_hint')} data-tip={props.muted() ? t('player.unmute_hint') : t('player.mute_hint')} onClick={props.onMute}>
                <Icon icon={props.icon()} size={24} />
            </button>
        </div>
    )
}


/**
 * The phone's speed sheet: the presets as tiles (four to a row upright, all seven sideways), then
 * the fine control, minus and plus either side of the figure, a press at a time.
 */
function SpeedSheet(props: { rate: () => number; landscape: boolean; onPick: (s: number) => void; onFine: (s: number) => void; onClose: () => void }) {
    const fine = (up: boolean) => props.onFine(Math.round((props.rate() + (up ? FINE_STEP : -FINE_STEP)) * 100) / 100)
    return (
        <PhoneSheet title={t('player.playback_speed')} onClose={props.onClose} class="speed">
            <div class="pc-tiles" classList={{ wide: props.landscape }}>
                <For each={SPEEDS}>
                    {(s) => (
                        <button type="button" class="pc-tile" role="radio" aria-checked={Math.abs(props.rate() - s) < 0.001} onClick={() => props.onPick(s)}>
                            {speedLabel(s)}
                        </button>
                    )}
                </For>
            </div>
            <div class="pc-fine">
                <div class="pc-fine-head">{t('player.speed_fine')}</div>
                <div class="pc-fine-detail">{t('player.speed_fine_detail', { step: speedLabel(FINE_STEP), min: speedLabel(SPEED_MIN), max: speedLabel(SPEED_MAX) })}</div>
                <div class="pc-fine-row">
                    <button type="button" class="pc-round" aria-label={t('player.speed_slower')} disabled={props.rate() <= SPEED_MIN + 0.001} onClick={() => fine(false)}>
                        <Icon icon={MinusSignIcon} size={24} />
                    </button>
                    <span class="pc-fine-value" aria-live="polite">{speedLabel(props.rate())}</span>
                    <button type="button" class="pc-round" aria-label={t('player.speed_faster')} disabled={props.rate() >= SPEED_MAX - 0.001} onClick={() => fine(true)}>
                        <Icon icon={Add01Icon} size={24} />
                    </button>
                </div>
            </div>
        </PhoneSheet>
    )
}

/**
 * The episode list (EpisodesPanel): the show and its count at the top, the previous and next
 * steps, the seasons as chips and the season's episodes, the one playing marked. On a phone the
 * whole screen; on the desktop a panel in the middle of the picture.
 */
function EpisodesPanel(props: { info: WatchInfo; current: VideoItem; phone: boolean; autoplay: boolean; onAutoplay: (on: boolean) => void; onPlay: (v: VideoItem) => void; onClose: () => void }) {
    const seasonOf = () => props.info.seasons.find((s) => s.episodes.some((e) => e.item.messageId === props.current.messageId))?.number ?? props.info.seasons[0]?.number
    const [picked, setPicked] = createSignal<number | null>(null)
    const season = createMemo(() => props.info.seasons.find((s) => s.number === (picked() ?? seasonOf())) ?? props.info.seasons[0])
    const count = () => props.info.seasons.reduce((n, s) => n + s.episodes.length, 0)
    const show = () => props.info.episode?.show ?? props.info.title
    let el!: HTMLDivElement
    onMount(() => el.querySelector<HTMLElement>('.pc-episode.playing, .pc-episode')?.focus({ preventScroll: true }))
    return (
        <div class="pc-sheet-scrim episodes" onClick={(e) => e.target === e.currentTarget && props.onClose()}>
            <div ref={el} class="pc-sheet pc-episodes" role="dialog" aria-label={t('episodes.title')}>
                <div class="pc-sheet-head">
                    <div>
                        <h2>{t('episodes.title')}</h2>
                        <p>{`${show()}  ·  ${t('series.summary', { seasons: props.info.seasons.length, episodes: count() })}`}</p>
                    </div>
                    <button type="button" class="pc-sheet-close" aria-label={t('common.close')} onClick={props.onClose}>
                        <Icon icon={Cancel01Icon} size={24} />
                    </button>
                </div>
                <div class="pc-episodes-body">
                    <div class="pc-episodes-side">
                        <button type="button" class="pc-step-line pc-switch" role="switch" aria-checked={props.autoplay} onClick={() => props.onAutoplay(!props.autoplay)}>
                            <span class="pc-sw" aria-hidden="true" />
                            {t('episodes.autoplay')}
                        </button>
                        <Show when={props.info.previous}>
                            <button type="button" class="pc-step-line" onClick={() => props.onPlay(props.info.previous!)}>
                                <Icon icon={PreviousIcon} size={18} />
                                {t('player.previous_up', { title: props.info.previousCode ?? props.info.previous!.title })}
                            </button>
                        </Show>
                        <Show when={props.info.next}>
                            <button type="button" class="pc-step-line" onClick={() => props.onPlay(props.info.next!)}>
                                <Icon icon={NextIcon} size={18} />
                                {t('player.next_up', { title: props.info.nextCode ?? props.info.next!.title })}
                            </button>
                        </Show>
                    </div>
                    <div class="pc-episodes-main">
                        <Show when={props.info.seasons.length > 1}>
                            <div class="pc-seasons" role="tablist">
                                <For each={props.info.seasons}>
                                    {(s) => (
                                        <button type="button" role="tab" class="pc-season" aria-selected={s.number === season()?.number} onClick={() => setPicked(s.number)}>
                                            <span>{t('series.season', { number: s.number })}</span>
                                            <small>{t('series.episodes_count', { count: s.episodes.length })}</small>
                                        </button>
                                    )}
                                </For>
                            </div>
                        </Show>
                        <ol class="pc-episode-list">
                            <For each={season()?.episodes ?? []}>
                                {(e) => {
                                    const playing = () => e.item.messageId === props.current.messageId && e.item.chatId === props.current.chatId
                                    return (
                                        <li>
                                            <button type="button" class="pc-episode" classList={{ playing: playing() }} aria-current={playing() ? 'true' : undefined} onClick={() => !playing() && props.onPlay(e.item)}>
                                                <span class="pc-episode-code">
                                                    {e.code}
                                                    <Show when={playing()}><span class="pc-badge">{t('episodes.playing')}</span></Show>
                                                </span>
                                                <span class="pc-episode-name">{e.name ?? e.item.title}</span>
                                                <span class="pc-episode-facts">{facts(e.item)}</span>
                                            </button>
                                        </li>
                                    )
                                }}
                            </For>
                        </ol>
                    </div>
                </div>
            </div>
        </div>
    )
}

function facts(v: VideoItem): string {
    const m = Math.round(v.duration / 60)
    const size = v.size >= 1e9 ? `${(v.size / 1e9).toFixed(1)} GB` : `${Math.max(1, Math.round(v.size / 1e6))} MB`
    return [m ? `${m}m` : '', size].filter(Boolean).join('  ·  ')
}
