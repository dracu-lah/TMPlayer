
import { createEffect, createSignal, For, Match, on, onCleanup, onMount, Show, Switch, type JSX } from 'solid-js'
import { Add01Icon, Bookmark02Icon, CheckmarkCircle02Icon, ArrowExpandIcon, ArrowLeft02Icon, ArrowShrinkIcon, Cancel01Icon, DashboardSpeed02Icon, HeadphonesIcon, InformationCircleIcon, KeyboardIcon, ListVideoIcon, Moon02Icon, MoreVerticalIcon, NextIcon, PictureInPictureOnIcon, PreviousIcon, RepeatIcon, ScreenRotationIcon, SquareLock02Icon, SquareUnlock02Icon, SubtitleIcon, Sun03Icon, TranslateIcon, VolumeHighIcon, VolumeLowIcon, VolumeMute02Icon } from '@hugeicons/core-free-icons'
import './player.css'
import './subfonts.css'
import './rotate.css'
import { Icon, type IconData } from '../ui/Icon'
import { t } from '../i18n'
import { Choice, Entry, Panel, PhoneSheet, Picker, ShortcutsSheet, TimePrompt, variant } from './sheets'
import { SHAPES } from './shape'
import { percent, zoomed } from './zoom'
import './zoom.css'
import { chapterAt, chapterInside, chapterListable, chapterMarks, chapterNext, chapterPrevious, chapterRows, isGeneric } from './chapters'
import { applyDelay, look, setLook, setSubDelay, subDelay } from './cues'
import { BookmarksSheet, delayText, LinkSheet, SIZES, TimingSheet } from './extra-sheets'
import { MsoneSheet } from './msone-sheet'
import { SubmakerJobLine, SubmakerSheet } from './submaker-sheet'
import { submakerBase, submakerSet } from '../submakerBase'
import * as subpick from './subpick-use'
import { openingSpeed, rememberSpeed } from '../store'
import { msoneOn } from '../msoneFlag'
import { AUTOPLAY_LIMIT, BOOSTS, noteInput, pressLoop, repeats, repeatState, SeekUndo, SLEEP_CHOICES, speedScopes, TURNS, watching, wrapLoop, type Loop } from './extras'
import {
    addBookmark, deleteBookmark, loadBookmarks, readAutoplay, readChatOrder, readKeepRepeat, renameBookmark, writeAutoplay, writeChatOrder, writeKeepRepeat, writeLook,
    type Bookmark, type SubtitleLook,
} from './keep'
import type { VideoItem } from '../tg/types'
import { PlaySolid, PauseSolid, CopiesIcon, SPEEDS, SPEED_MIN, SPEED_MAX, HIDE_MS, HIDE_TV_MS, SIDE, WIDE_ROW, NOTE_MS, PEEK_MS, UNLOCK_PILL_MS, MAC, shapeIcon, shapeName, clock, speedLabel } from './chrome/parts'
import type { Ctl, Sheet } from './chrome/types'
import { SeekBar } from './chrome/SeekBar'
import { DetailsSheet, StillSheet } from './chrome/Dialogs'
import { createScreenshot } from './chrome/screenshot'
import { useBoost } from './chrome/useBoost'
import { useCopyLink } from './chrome/useCopyLink'
import { useFullscreen } from './chrome/useFullscreen'
import { useGestures } from './chrome/useGestures'
import { useSkips } from './chrome/useSkips'
import { useKeys } from './chrome/useKeys'
import { usePicture } from './chrome/usePicture'
import { useSleep } from './chrome/useSleep'
import { useSubtitles } from './chrome/useSubtitles'
import { timed } from '../ui/timed'
import { SeekStepper } from './seekStepper'
import { FrameClock } from './frameStep'
import { chatDetail, EpisodesPanel, msoneTarget, SpeedSheet, subtitleLine, VolumeControl } from './chrome/menus'

export { variant }

export function Chrome(props: { ctl: Ctl; children?: JSX.Element }) {
    const c = props.ctl
    const v = c.video
    const info = () => c.info()
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
    const resize = new ResizeObserver(() => setWidth(c.root.clientWidth))
    resize.observe(c.root)
    onCleanup(() => {
        v.textTracks.removeEventListener('addtrack', readSubs)
        v.textTracks.removeEventListener('change', readSubs)
        resize.disconnect()
    })

    // ---- showing and hiding ------------------------------------------------------------------
    const [visible, setVisible] = createSignal(variant() !== 'tv')
    const [sheet, setSheet] = createSignal<Sheet>(null)
    /** The pointer rests on the controls (desktop): they stay. */
    const [held, setHeld] = createSignal(false)
    const [locked, setLocked] = createSignal(false)
    const resting = { onPointerEnter: () => setHeld(true), onPointerLeave: () => setHeld(false) }
    let hideTimer = 0
    const poke = () => {
        clearTimeout(hideTimer)
        if (v.paused || held() || sheet() || dragTime() !== null || c.blocked()) return
        hideTimer = window.setTimeout(() => (keyboardOnRow() ? poke() : setVisible(false)), tv() ? HIDE_TV_MS : HIDE_MS)
    }
    /** A keyboard user's ring must not vanish under them: the row stays up while one of its controls has the keyboard. */
    const keyboardOnRow = () => {
        const a = document.activeElement
        return !tv() && a instanceof HTMLElement && !!c.root.querySelector('.pc-layer')?.contains(a) && a.matches(':focus-visible')
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
    const noticeClose = () => c.root.querySelector<HTMLElement>('.pc-notice button')
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
                // The TV after any menu; the desktop after More's panel, which took the keyboard.
                if (now !== null || !before || !(tv() || (inPanel() && !phone()))) return
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
    const [note, showNote] = timed<{ text: string; side: 'left' | 'right' | 'centre'; quiet?: boolean }>(NOTE_MS)
    const flash = (text: string, side: 'left' | 'right' | 'centre' = 'centre') => showNote({ text, side })
    const link = useCopyLink(c, sheet, setSheet, flash)

    const togglePlay = () => (v.paused ? v.play().catch(() => {}) : v.pause())
    const seekTo = (s: number, from = v.currentTime) => {
        const d = duration()
        const to = Math.max(0, d ? Math.min(d - 0.1, s) : s)
        v.currentTime = to
        setTime(v.currentTime)
        noteSeek(from, to)
    }
    const seekBy = (s: number) => seekTo(v.currentTime + s)
    // One stepper for a held seek key (seekStepper.ts): the TV's Left and Right, the arrows over the picture and on the bar.
    // One write of currentTime per step, the picture shows the running total, and a screen reader is told once in a while.
    const stepper = new SeekStepper()
    // The TV shows the jump's figure (set once the gestures exist, below); a keyboard shows the note.
    let showRipple: (side: 'left' | 'right', total: number) => void = () => {}
    const [said, setSaid] = createSignal('')
    const stepRemote = (forward: boolean, repeat: boolean) => {
        const r = stepper.press({ forward, repeat, now: performance.now(), baseSec: tv() ? 10 : 5, durationSec: duration() })
        if (!r) return
        seekBy(r.by)
        peekAt(v.currentTime)
        const text = `${r.total > 0 ? '+' : '-'}${t('player.seek_seconds', { seconds: Math.round(Math.abs(r.total)) })}`
        if (tv()) showRipple(r.by > 0 ? 'right' : 'left', r.total)
        else showNote({ text, side: r.by > 0 ? 'right' : 'left', quiet: true })
        if (r.announce) setSaid(`${text}, ${clock(v.currentTime)}`)
    }
    // How long a frame lasts in this video, from the frames the browser shows; 1/30 where it cannot say (Safari before 15.4).
    const frames = new FrameClock()
    {
        const vfc = v as HTMLVideoElement & { requestVideoFrameCallback?: (cb: (now: number, meta: { mediaTime: number }) => void) => number; cancelVideoFrameCallback?: (id: number) => void }
        if (typeof vfc.requestVideoFrameCallback === 'function') {
            let id = 0
            const ask = () => {
                id = vfc.requestVideoFrameCallback!((_now, meta) => {
                    frames.frame(meta.mediaTime, 1)
                    // Enough frames seen: no more callbacks, so nothing runs per frame on a weak TV.
                    if (!frames.settled) ask()
                })
            }
            const start = () => (frames.reset(), vfc.cancelVideoFrameCallback?.(id), ask())
            start()
            listen('loadeddata', start)
            listen('seeked', () => !frames.settled && frames.reset())
            onCleanup(() => vfc.cancelVideoFrameCallback?.(id))
        }
    }
    const frameLength = () => frames.duration
    // The speed a video opens at: the one kept for its show (known once the show is read), else for its chat.
    let speedPicked = false
    {
        let last = ''
        createEffect(() => {
            const scopes = speedScopes(c.item.chatId, c.info()?.episode?.show ?? null)
            const key = `${scopes.series}|${scopes.chat}`
            if (key === last) return
            last = key
            let alive = true
            onCleanup(() => (alive = false))
            openingSpeed(scopes).then(
                (r) => {
                    if (!alive || speedPicked || Math.abs(v.playbackRate - r) < 0.001) return
                    v.defaultPlaybackRate = r
                    v.playbackRate = r
                },
                () => {},
            )
        })
    }
    const setSpeed = (r: number) => {
        const next = Math.round(Math.min(SPEED_MAX, Math.max(SPEED_MIN, r)) * 100) / 100
        v.playbackRate = next
        // Remembered for this show, or this chat (SpeedMemory); 1x forgets it, so the next one opens at 1x too.
        speedPicked = true
        rememberSpeed(speedScopes(c.item.chatId, c.info()?.episode?.show ?? null), next)
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
    const subtitles = useSubtitles(c, { subs, subOn, readSubs, sheet, setSheet, flash, duration, info })
    const { chatSubs, chatNote, setChatNote, chatBusy, pickChat, layOnline, layMaker, jobs, submakerTarget, subLabel, pickSub, cycleSub } = subtitles
    const cycleAudio = (back = false) => {
        const list = c.audios()
        if (list.length < 2 || !c.canSwitchAudio()) return
        const at = c.audio() ? list.indexOf(c.audio()!) : -1
        const next = (at + (back ? -1 : 1) + list.length) % list.length
        c.pickAudio(next)
        flash(c.trackLabel(list[next], next))
    }

    const pic = usePicture(c, { listen, flash, tv, locked })
    const { shape, turn, zoom, zoomChip, zoomText, zoomBy, resetZoom, pickTurn, pickShape, cycleShape } = pic
    const boost = useBoost(v, listen, flash)

    // ---- screenshots (S with the subtitles, Shift+S without) -----------------------------------
    const screenshot = createScreenshot({ video: v, canvas: pic.engineCanvas, turn, subtitles: subOn, title: () => info()?.title ?? c.item.title, flash })


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
        // Only the marks inside the video: one past the end of a malformed file would end it.
        const list = chapterInside(chapters(), duration())
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

    // ---- Repeat this video, and the chat's order after a video that is not an episode --------------
    // The state outlives one player (extras.repeatState): off again when another video starts, unless "Keep repeat on".
    const itemKey = `${c.item.chatId}:${c.item.messageId}`
    const [keepRepeat, setKeepRepeat] = createSignal(false)
    const [chatOrder, setChatOrder] = createSignal(false)
    const [repeatOn, setRepeatOn] = createSignal(repeats(itemKey, false))
    onMount(() => {
        readKeepRepeat().then((keep) => (setKeepRepeat(keep), setRepeatOn(repeats(itemKey, keep))), () => {})
        readChatOrder().then(setChatOrder, () => {})
    })
    const changeKeepRepeat = (on: boolean) => {
        setKeepRepeat(on)
        writeKeepRepeat(on)
        setRepeatOn(repeats(itemKey, on))
    }
    // Takes effect from the next video: the watch page asks the chat for its next one when it opens.
    const changeChatOrder = (on: boolean) => {
        setChatOrder(on)
        writeChatOrder(on)
    }

    // ---- A-B repeat (AbLoop) -----------------------------------------------------------------
    const [loop, setLoop] = createSignal<Loop | null>(null)
    const pressRepeat = () => {
        // The two repeats exclude each other: marking a stretch turns Repeat this video off.
        if (repeatOn()) (repeatState.of = null, setRepeatOn(false))
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
    const sleep = useSleep(v, listen, { flash, ask: () => (setSheet('still'), show()) })
    const { sleepAt, sleepEnd, sleepPick, stillWhy, askStill, clearSleep, sleepLabel, setSleep, sleepSummary } = sleep

    /** Another episode: autoplay's count and the sleep timer go with it. */
    const go = (next: VideoItem | null) => {
        sleep.keep()
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
        // The small window shows the frame as it is, unturned: a turned picture goes back upright as it opens (the note says so).
        if (turn() !== 0 && !document.pictureInPictureElement) pickTurn(0)
        try {
            if (document.pictureInPictureElement) await document.exitPictureInPicture()
            else if (document.pictureInPictureEnabled && v.requestPictureInPicture) await v.requestPictureInPicture()
            else wv.webkitSetPresentationMode?.(wv.webkitPresentationMode === 'picture-in-picture' ? 'inline' : 'picture-in-picture')
        } catch {}
    }

    // ---- skip offers from the file's own chapters -----------------------------------------------
    const { skipNow, skipLabel, skipIt } = useSkips(c, listen, duration, seekTo)

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
        // Repeat this video: from the start again, no card and no count towards "Still watching?";
        // a sleep timer for this video's end wins, and Repeat is off for what comes next.
        if (repeatOn() && !sleepEnd()) {
            v.currentTime = 0
            v.play().catch(() => {})
            return
        }
        if (sleepEnd()) {
            if (repeatOn()) (repeatState.of = null, setRepeatOn(false))
            clearSleep()
            return askStill('sleep_end')
        }
        if (!next) return
        setNextUp(true)
        if (!(autoplay() || c.playAll?.()) || nextHidden()) return
        if (watching.autoplayed >= AUTOPLAY_LIMIT) return askStill('autoplay')
        startCount(5)
    }
    const toggleRepeatVideo = () => {
        const on = !repeatOn()
        repeatState.of = on ? itemKey : null
        setRepeatOn(on)
        if (on) {
            // Excludes the A-B repeat, and a card for the next video has nothing to announce.
            setLoop(null)
            stopCount()
            setNextUp(false)
        }
        flash(on ? t('player.repeat_video_on') : t('player.repeat_off'))
    }
    // Anything the viewer does with the picture stops the countdown.
    listen('seeking', stopCount)
    listen('play', stopCount)

    const { fullscreen, canFullscreen, canTurn, toggle: toggleFullscreen, rotate } = useFullscreen(c, phone, flash)

    const lock = () => {
        setSheet(null)
        hide()
        setLocked(true)
        showUnlock()
    }
    const [unlockShown, pillUp, hideUnlock] = timed<true>(UNLOCK_PILL_MS)
    const showUnlock = () => pillUp(true)
    const unlock = () => {
        hideUnlock()
        setLocked(false)
        show()
    }

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
    // The TV's arrows seek at once: the card shows the new place for a moment after each press.
    const [peek, peekAt] = timed<number>(PEEK_MS)
    /** Where the video was when a drag on the bar began: what a slip there is measured from. */
    let dragFrom = -1
    const shownTime = () => dragTime() ?? time()
    const endText = () => (remaining() && duration() ? `-${clock(duration() - shownTime())}` : clock(duration()))

    /** Go to a time: the prompt, with the keyboard handed back to where it was when it closes. */
    const askTime = (from: Element | null = document.activeElement) => {
        opener = from instanceof HTMLElement && from !== document.body ? from : null
        setSheet('goto')
    }
    const closeTime = () => {
        setSheet(null)
        queueMicrotask(() => focusIt(opener?.isConnected ? opener : c.root))
    }
    const gestures = useGestures({ c, pic, tv, phone, plain, locked, sheet, duration, flash, seekBy, seekTo, setVol, togglePlay, toggleFullscreen, toggle, show })
    showRipple = gestures.rippleFor
    const { hold, ripple, level, scrub, jumpRemote, onGesture, onGestureEnd, onDown, onMove, onUp, onCancel, onWheel } = gestures

    // ---- what the row offers ---------------------------------------------------------------
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
        setNextUp(!repeatOn() && !!info()?.next && isFinite(d) && d > 60 && d - v.currentTime <= 30)
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
        // More's pages follow in its panel; the bar's own buttons keep their menus.
        setInPanel(name === 'more')
        setSheet(sheet() === name ? null : name)
    }
    /** The sheet up was reached from More options, so it is drawn in More's panel with a Back line. */
    const [inPanel, setInPanel] = createSignal(false)
    const panelBack = (to: Exclude<Sheet, null>) => (inPanel() ? () => setSheet(to) : undefined)
    /** One page of More options: the side panel on the desktop and the TV, the phone's sheet on a phone. */
    const MorePage = (p: { title: string; back?: Exclude<Sheet, null>; children: JSX.Element }) => (
        <Show when={phone()} fallback={<Panel title={p.title} page={p.title} onClose={() => setSheet(null)} onBack={p.back ? () => setSheet(p.back!) : undefined}>{p.children}</Panel>}>
            <PhoneSheet title={p.title} onClose={() => setSheet(null)} onBack={p.back ? () => setSheet(p.back!) : undefined}>{p.children}</PhoneSheet>
        </Show>
    )
    const [anchor, setAnchor] = createSignal<DOMRect | null>(null)
    /** A picker list beside its button (the desktop and the TV) or in the phone's sheet, closing back into the controls. */
    const Pick = (p: { title: string; note?: string; phone?: boolean; panel: boolean; onBack?: () => void; children: JSX.Element }) => (
        <Picker title={p.title} note={p.note} phone={p.phone ?? phone()} panel={p.panel} anchor={anchor()} root={c.root} onClose={() => setSheet(null)} onBack={p.onBack}>{p.children}</Picker>
    )

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

    useKeys({
        c, tv, sheet, setSheet, visible, locked, counting, skipNow, backTo, offer, setOffer, barEl, discEl, noticeClose, rowItems, inLayer, focusIt, show, hide, poke, unlock, showUnlock,
        flash, togglePlay, seekBy, seekTo, peekAt, jumpRemote, stepRemote, releaseStep: () => stepper.release(), frameLength, stopCount, setNextHidden, go, skipIt, takeBack, stepChapter, setVol, stepSpeed, setSpeed, cycleSub, cycleAudio, nudgeDelay, stepSize,
        toggleFullscreen, cycleShape, turn, duration, pickTurn, zoomBy, resetZoom, screenshot, pressRepeat, bookmarkHere, askTime, pipSupported, togglePip,
    })

    return (
        <div
            class="pc"
            data-variant={variant()}
            classList={{ shown: visible() && !locked() && !c.blocked(), locked: locked(), sheeted: !!sheet() }}
            onPointerMove={(e) => e.pointerType === 'mouse' && !locked() && variant() !== 'phone' && show()}
        >
            <div
                class="pc-picture"
                ref={(el) => {
                    el.addEventListener('gesturestart', onGesture)
                    el.addEventListener('gesturechange', onGesture)
                    el.addEventListener('gestureend', onGestureEnd)
                }}
                onPointerDown={onDown}
                onPointerMove={onMove}
                onPointerUp={onUp}
                onPointerCancel={onCancel}
                onWheel={onWheel}
                onContextMenu={(e) => {
                    if (phone() || hold()) return void e.preventDefault()
                    // The desktop app's right click: More options' panel, or closed again.
                    if (tv() || c.blocked() || locked()) return
                    e.preventDefault()
                    opener = c.root.querySelector<HTMLElement>('[data-shot="more"]')
                    setInPanel(true)
                    setSheet(sheet() === 'more' ? null : 'more')
                }}
            />

            {/* Tab into a row that has gone brings it back, so the ring is never on a control nobody sees. */}
            <div class="pc-layer" aria-hidden={!visible() || undefined} inert={!tv() && sheet() !== null} on:focusin={() => !tv() && !visible() && show()}>
                <div class="pc-tint" />
                <div class="pc-scrim top" />
                <div class="pc-scrim bottom" />

                <div class="pc-top" {...resting}>
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

                <div class="pc-center" {...resting}>
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

                <div class="pc-bottom" {...resting}>
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
                        grabber={() => (tv() ? null : c.grabber())}
                        item={c.item}
                        peek={peek}
                        tv={tv()}
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
                        onStep={stepRemote}
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
                                <Btn icon={shapeIcon(shape())} label={plain() ? t('player.picture_shape_now', { shape: shapeName(shape()) }) : t('player.picture_shape_hint', { shape: shapeName(shape()) })} onClick={cycleShape} class="shape" />
                            </Show>
                            {/* Where the app has its rotate button: the app's player already fills the screen, a page does not. */}
                            <Show when={phone() && canFullscreen()}>
                                <Btn
                                    icon={fullscreen() ? ArrowShrinkIcon : ArrowExpandIcon}
                                    label={fullscreen() ? t('player.exit_fullscreen') : t('player.fullscreen')}
                                    onClick={() => void toggleFullscreen()}
                                    shot="fullscreen"
                                />
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
                    </div>
                </div>
            </div>

            {/* What a gesture or a key puts on the picture while it happens (PlayerFeedback). */}
            <Show when={hold()}>
                <div class="pc-note hold" role="status">{speedLabel(2)}</div>
            </Show>
            <Show when={note()}>{(n) => <div class={`pc-note ${n().side}`} role={n().quiet ? undefined : 'status'} aria-hidden={n().quiet ? 'true' : undefined}>{n().text}</div>}</Show>
            {/* Where a held seek key has got to, said to a screen reader at most every 800 ms (seekStepper.ANNOUNCE_MS). */}
            <div class="visually-hidden" role="status" aria-live="polite">{said()}</div>
            {/* The zoom's figure (PlayerFeedback's zoom chip): up while the picture moves and a moment after; a tap puts it back at 100 per cent. */}
            <Show when={zoomChip() && !locked() && !c.blocked() && !sheet()}>
                <button type="button" class="pc-zoom-chip" data-shot="zoom" aria-live="polite" aria-label={`${zoomText()}. ${t('player.reset_zoom')}`} onClick={() => resetZoom()}>
                    {zoomText()}
                </button>
            </Show>
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
            <SubmakerJobLine jobs={jobs} />

            <Switch>
                <Match when={sheet() === 'more'}>
                    <MorePage title={t('player.more_options')}>
                        {/* As the app's overflow: what the phone's row shows is left out. */}
                        <Show when={!tv() && !(phone() && wideRow())}>
                            <Entry icon={SquareLock02Icon} text={t('player.lock_screen')} onClick={lock} />
                        </Show>
                        <Show when={phone() && canTurn()}>
                            <Entry icon={ScreenRotationIcon} text={t('player.screen_orientation')} onClick={() => (setSheet(null), rotate())} />
                        </Show>
                        <Show when={!tv() && !(phone() && wideRow() && !canTurn())}>
                            <hr />
                        </Show>
                        <Show when={c.copies.length}>
                            <Entry text={t('player.copies')} trailing={copyLabel()} onClick={() => setSheet('copies')} />
                        </Show>
                        <Show when={pipSupported()}>
                            <Entry icon={PictureInPictureOnIcon} text={t('player.picture_in_picture')} onClick={() => (setSheet(null), togglePip())} />
                        </Show>
                        {/* The bar's three, each with what it is set to, as the desktop app's panel has them. */}
                        <Show when={!phone()}>
                            <Entry text={t('player.subtitles')} trailing={subOn() ? subLabel(subOn()!, subs().indexOf(subOn()!)) : t('common.off')} onClick={() => setSheet('subtitles')} />
                            <Entry text={t('player.audio')} trailing={c.audio() ? c.trackLabel(c.audio()!, c.audios().indexOf(c.audio()!)) : undefined} onClick={() => setSheet('audio')} />
                            <Entry text={t('player.playback_speed')} trailing={speedLabel(rate())} onClick={() => setSheet('speed')} />
                        </Show>
                        <Entry text={t('player.playback_options')} trailing={sleepAt() !== null || sleepEnd() ? sleepSummary() : undefined} onClick={() => setSheet('options')} />
                        <Entry text={t('player.picture_shape')} trailing={shapeName(shape())} onClick={() => setSheet('shape')} />
                        <Entry text={t('player.rotate_picture')} trailing={turn() ? t('player.rotate_picture_degrees', { degrees: turn() }) : undefined} onClick={() => setSheet('rotate')} />
                        {/* On an iPhone the entry stays and says why it does nothing there. */}
                        <Show when={boost.ok || boost.ios}>
                            <Entry text={t('player.volume_boost')} trailing={boost.level() > 1 ? t('player.volume_boost_level', { percent: Math.round(boost.level() * 100) }) : undefined} onClick={() => setSheet('boost')} />
                        </Show>
                        {/* The apps' Night mode and Even out volume, in the same Web Audio graph as the boost (boost.ts). */}
                        <Show when={boost.extras()}>
                            <Entry text={t('player.night_mode')} trailing={boost.night() ? t('common.on') : t('common.off')} onClick={boost.toggleNight} />
                            <Entry text={t('player.even_volume')} trailing={boost.even() ? t('common.on') : t('common.off')} onClick={boost.toggleEven} />
                        </Show>
                        {/* PhoneMenuEntry.ResetZoom: the pinch's undo without a gesture, so a screen reader reaches it too. */}
                        <Show when={zoomed(zoom())}>
                            <Entry text={t('player.reset_zoom')} trailing={`${percent(zoom())}%`} onClick={() => (setSheet(null), resetZoom())} />
                        </Show>
                        <Entry text={t('player.subtitle_style_and_timing')} onClick={() => setSheet('timing')} />
                        <Show when={link.offered()}>
                            <Entry text={t('player.copy_link_at_time')} onClick={link.copyAtTime} />
                        </Show>
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
                    </MorePage>
                </Match>
                <Match when={sheet() === 'options'}>
                    <MorePage title={t('player.playback_options')} back="more">
                        <Entry icon={Moon02Icon} text={t('player.sleep_timer')} trailing={sleepSummary()} onClick={() => setSheet('sleep')} />
                        <Entry icon={RepeatIcon} text={repeatText()} trailing={plain() ? undefined : 'R'} onClick={() => (setSheet(null), pressRepeat())} />
                        {/* Switches: the page stays up with the new value, as the volume lines do in the apps. */}
                        <Entry icon={RepeatIcon} text={t('player.repeat_video')} trailing={repeatOn() ? t('common.on') : t('common.off')} onClick={toggleRepeatVideo} />
                        <Entry text={t('settings.keep_repeat')} trailing={keepRepeat() ? t('common.on') : t('common.off')} onClick={() => changeKeepRepeat(!keepRepeat())} />
                        <Entry text={t('settings.play_chat_order')} trailing={chatOrder() ? t('common.on') : t('common.off')} onClick={() => changeChatOrder(!chatOrder())} />
                        {/* The chapters as a page (WP-26), from two marks; the steps stay beside it. */}
                        <Show when={chapterListable(chapters(), duration())}>
                            <Entry text={t('player.chapters')} trailing={chapterTextAt(v.currentTime) ?? undefined} onClick={() => setSheet('chapters')} />
                        </Show>
                        <Show when={chapters().length}>
                            <Entry text={t('player.chapter_next')} trailing={plain() ? undefined : `${t(MAC ? 'keys.cmd' : 'keys.ctrl')}+Right`} onClick={() => (setSheet(null), stepChapter(true))} />
                            <Entry text={t('player.chapter_previous')} trailing={plain() ? undefined : `${t(MAC ? 'keys.cmd' : 'keys.ctrl')}+Left`} onClick={() => (setSheet(null), stepChapter(false))} />
                        </Show>
                        {/* Typing a time on a TV's remote is no quicker than the bar's arrows. */}
                        <Show when={!tv()}>
                            <Entry
                                text={t('player.go_to_time')}
                                trailing={plain() ? undefined : `${t(MAC ? 'keys.cmd' : 'keys.ctrl')}+G`}
                                onClick={() => askTime(c.root.querySelector('[data-shot="more"]'))}
                            />
                        </Show>
                    </MorePage>
                </Match>
                <Match when={sheet() === 'sleep'}>
                    <Pick title={t('player.sleep_timer')} panel onBack={() => setSheet('options')}>
                        <Show when={sleepAt() !== null || sleepEnd()}>
                            <Choice icon={Cancel01Icon} text={t('player.sleep_turn_off')} phone={phone()} onClick={() => (setSleep(null), setSheet(null))} />
                        </Show>
                        <For each={SLEEP_CHOICES}>
                            {(m) => <Choice text={sleepLabel(m)} on={sleepPick() === m && (sleepAt() !== null || sleepEnd())} phone={phone()} onClick={() => (setSleep(m), setSheet(null))} />}
                        </For>
                    </Pick>
                </Match>
                <Match when={sheet() === 'chapters'}>
                    <Pick title={t('player.chapters')} panel onBack={() => setSheet('options')}>
                        <For each={chapterRows(chapters(), v.currentTime, (number) => t('player.chapter_untitled', { number }), duration())}>
                            {(row) => (
                                <Choice
                                    text={row.title}
                                    detail={`\u2066${clock(row.start)}\u2069`}
                                    on={row.current}
                                    phone={phone()}
                                    onClick={() => (setSheet(null), seekTo(row.start), flash(chapterTextAt(row.start) ?? row.title))}
                                />
                            )}
                        </For>
                    </Pick>
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
                <Match when={sheet() === 'link'}>
                    <LinkSheet link={link.shown()} onClose={() => setSheet(null)} />
                </Match>
                <Match when={sheet() === 'shortcuts'}>
                    <ShortcutsSheet mac={MAC} onClose={() => setSheet(null)} />
                </Match>
                <Match when={sheet() === 'goto'}>
                    <TimePrompt
                        position={v.currentTime}
                        duration={duration()}
                        clock={clock}
                        onClose={closeTime}
                        onGo={(s) => {
                            closeTime()
                            seekTo(s)
                            flash(clock(s))
                        }}
                    />
                </Match>
                <Match when={sheet() === 'still'}>
                    <StillSheet why={stillWhy()} onBack={() => c.close()} onKeep={keepWatching} />
                </Match>
                <Match when={sheet() === 'rotate'}>
                    {/* The picture beside it is the answer, as for the shapes: the panel stays open. */}
                    <Pick title={t('player.rotate_picture')} note={t('player.rotate_picture_note')} panel onBack={() => setSheet('more')}>
                        <For each={TURNS}>
                            {(deg) => (
                                <Choice
                                    text={deg === 0 ? t('player.rotate_picture_none') : deg === 90 ? t('player.rotate_picture_right') : deg === 180 ? t('player.rotate_picture_half') : t('player.rotate_picture_left')}
                                    detail={deg ? t('player.rotate_picture_degrees', { degrees: deg }) : undefined}
                                    on={turn() === deg}
                                    phone={phone()}
                                    onClick={() => (pickTurn(deg), phone() && setSheet(null))}
                                />
                            )}
                        </For>
                    </Pick>
                </Match>
                <Match when={sheet() === 'boost'}>
                    <Pick title={t('player.volume_boost')} note={boost.ok ? t('player.volume_boost_note') : t('web.volume_boost_not_here')} panel onBack={() => setSheet('more')}>
                        <For each={boost.ok ? BOOSTS : []}>
                            {(level) => (
                                <Choice
                                    text={level === 1 ? t('player.volume_boost_off') : t('player.volume_boost_level', { percent: Math.round(level * 100) })}
                                    on={Math.abs(boost.level() - level) < 0.001}
                                    phone={phone()}
                                    onClick={() => (boost.pick(level), phone() && setSheet(null))}
                                />
                            )}
                        </For>
                    </Pick>
                </Match>
                <Match when={sheet() === 'shape'}>
                    {/* In the panel a pick leaves it open: the picture beside it is the answer. */}
                    <Pick title={t('player.picture_shape')} panel onBack={() => setSheet('more')}>
                        <For each={SHAPES}>
                            {(s) => (
                                <>
                                    <Show when={s === '16:9' && !phone()}><hr /></Show>
                                    <Choice text={shapeName(s)} on={shape() === s} phone={phone()} onClick={() => (pickShape(s), phone() && setSheet(null))} />
                                </>
                            )}
                        </For>
                    </Pick>
                </Match>
                <Match when={sheet() === 'subtitles'}>
                    <Pick title={t('tracks.subtitles')} panel={inPanel()} onBack={panelBack('more')}>
                        <Choice text={t('common.off')} on={!subOn()} phone={phone()} onClick={() => (pickSub(null), setSheet(null))} />
                        <For each={subs()}>{(tt, i) => <Choice text={subLabel(tt, i())} on={subOn() === tt} phone={phone()} onClick={() => (pickSub(tt), setSheet(null))} />}</For>
                        <Show when={!phone()}><hr /></Show>
                        <Show when={chatSubs().length}>
                            <Choice icon={Add01Icon} text={t('tracks.in_chat')} detail={t('tracks.in_chat_detail')} phone={phone()} onClick={() => (setChatNote(''), setSheet('chatsubs'))} />
                        </Show>
                        <Choice icon={Add01Icon} text={t('player.load_subtitle_file')} phone={phone()} onClick={() => (setSheet(null), c.loadSubtitleFile())} />
                        <Show when={msoneOn()}>
                            <Choice icon={TranslateIcon} service="msone" text={t('msone.search')} detail={t('msone.search_detail')} phone={phone()} onClick={() => setSheet('msone')} />
                        </Show>
                        <Show when={submakerSet()}>
                            <Choice icon={TranslateIcon} service="submaker" text={t('submaker.search')} detail={t('submaker.search_detail')} phone={phone()} onClick={() => setSheet('submaker')} />
                        </Show>
                    </Pick>
                </Match>
                <Match when={sheet() === 'chatsubs'}>
                    <Pick title={t('tracks.in_chat')} note={chatNote() || t('tracks.in_chat_detail')} panel={inPanel()} onBack={() => setSheet('subtitles')}>
                        <For each={chatSubs()}>
                            {(sub) => <Choice text={sub.name} detail={chatDetail(sub, chatBusy() === sub.key)} phone={phone()} disabled={chatBusy() !== null && chatBusy() !== sub.key} onClick={() => void pickChat(sub)} />}
                        </For>
                    </Pick>
                </Match>
                <Match when={sheet() === 'msone'}>
                    <MsoneSheet target={msoneTarget(c.item, info())} onLoad={async (file, as, hit) => (await layOnline(file, as), subpick.picked(c.item, hit, file))} onPickFile={() => (setSheet(null), c.loadSubtitleFile())} onClose={() => setSheet(null)} />
                </Match>
                <Match when={sheet() === 'submaker' && submakerBase()}>
                    {(base) => (
                        <SubmakerSheet
                            base={base()}
                            resolve={submakerTarget}
                            jobs={jobs}
                            onLoad={async (file, as) => (await layMaker(file, as), subpick.choseOther(c.item))}
                            phone={phone()}
                            panel={inPanel()}
                            anchor={anchor()}
                            root={c.root}
                            onClose={() => setSheet(null)}
                            onBack={panelBack('subtitles')}
                        />
                    )}
                </Match>
                <Match when={sheet() === 'audio'}>
                    <Pick title={t('player.audio')} panel={inPanel()} onBack={panelBack('more')}>
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
                    </Pick>
                </Match>
                <Match when={sheet() === 'speed'}>
                    <Show
                        when={phone()}
                        fallback={
                            <Pick title={t('player.playback_speed')} phone={false} panel={inPanel()} onBack={panelBack('more')}>
                                <For each={SPEEDS}>{(s) => <Choice text={speedLabel(s)} on={Math.abs(rate() - s) < 0.001} phone={false} onClick={() => (setSpeed(s), setSheet(null))} />}</For>
                            </Pick>
                        }
                    >
                        <SpeedSheet rate={rate} landscape={width() > c.root.clientHeight} onPick={(s) => (setSpeed(s), setSheet(null))} onFine={setSpeed} onClose={() => setSheet(null)} />
                    </Show>
                </Match>
                <Match when={sheet() === 'copies'}>
                    <Pick title={t('player.copies')} note={t('player.copies_note')} panel={inPanel()} onBack={panelBack('more')}>
                        <For each={[{ key: c.item.key, height: 0 }, ...c.copies]}>
                            {(copy) => <Choice text={c.qualityLabel(copy.height)} on={c.sourceKey() === copy.key} phone={phone()} onClick={() => (c.pickQuality(copy.key), setSheet(null))} />}
                        </For>
                    </Pick>
                </Match>
                <Match when={sheet() === 'episodes'}>
                    <EpisodesPanel info={info()!} current={c.item} phone={phone()} autoplay={autoplay()} onAutoplay={changeAutoplay} onPlay={(item) => (setSheet(null), go(item))} onClose={() => setSheet(null)} />
                </Match>
                <Match when={sheet() === 'details'}>
                    <DetailsSheet c={c} tv={tv()} rate={rate()} onClose={() => setSheet(null)} />
                </Match>
            </Switch>

            <Show when={locked()}>
                <div class="pc-shield" onClick={showUnlock}>
                    <button type="button" class="pc-unlock" classList={{ shown: unlockShown() !== null }} onClick={(e) => (e.stopPropagation(), unlock())} aria-label={t('player.unlock')}>
                        <Icon icon={SquareUnlock02Icon} size={22} />
                        {t('player.tap_to_unlock')}
                    </button>
                </div>
            </Show>
        </div>
    )
}
