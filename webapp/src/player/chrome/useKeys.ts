// The player's keys: the TV remote (PlayerActivity.dispatchKeyEvent and PlayerControls' D-pad rules) and the
// keyboard (PlayerKeys.actionFor, the parts a browser can do). They only decide what a key means; what it does is
// handed in as `Keys`, so the focus order and the shortcuts are in one place and the actions are not repeated.
import { onCleanup, onMount } from 'solid-js'
import { t } from '../../i18n'
import { oncePerPress, remoteKey } from '../../tv/remote'
import { confirmOpen } from '../../ui/Confirm'
import { onGlobal } from '../../ui/globalInput'
import { nextTurn } from '../extras'
import { actionFor, playerHearsKeys, SEEK_SHORT } from '../playerKeys'
import { walk } from '../sheets'
import type { SkipSegment } from '../chapters'
import { MAC } from './parts'
import type { Ctl, Sheet } from './types'

export interface Keys {
    c: Ctl
    tv: () => boolean
    // What is on screen.
    sheet: () => Sheet
    setSheet: (s: Sheet) => void
    visible: () => boolean
    locked: () => boolean
    counting: () => number | null
    skipNow: () => SkipSegment | null
    backTo: () => number | null
    offer: () => boolean
    setOffer: (on: boolean) => void
    // The controls' elements, for the remote's walk.
    barEl: () => HTMLElement | null
    discEl: () => HTMLElement | null
    noticeClose: () => HTMLElement | null
    rowItems: () => HTMLElement[]
    inLayer: (el: Element | null) => boolean
    focusIt: (el: HTMLElement | null | undefined) => void
    // Actions.
    show: () => void
    hide: () => void
    poke: () => void
    unlock: () => void
    showUnlock: () => void
    flash: (text: string, side?: 'left' | 'right' | 'centre') => void
    togglePlay: () => void
    seekBy: (s: number) => void
    seekTo: (s: number) => void
    peekAt: (s: number) => void
    jumpRemote: (forward: boolean) => void
    /** A held or tapped seek key through the one stepper (seekStepper.ts): 10 s a step on the TV layout, 5 s on a keyboard. */
    stepRemote: (forward: boolean, repeat: boolean) => void
    /** A key came up: the stepper's next press starts a hold of its own (PB2-12). */
    releaseStep: () => void
    /** One frame, in seconds, as this video shows it. */
    frameLength: () => number
    stopCount: () => void
    setNextHidden: (on: boolean) => void
    go: (next: import('../../tg/types').VideoItem | null) => void
    skipIt: () => void
    takeBack: () => boolean
    stepChapter: (forward: boolean) => void
    setVol: (x: number) => void
    stepSpeed: (up: boolean) => void
    setSpeed: (r: number) => void
    cycleSub: (back?: boolean) => void
    cycleAudio: (back?: boolean) => void
    nudgeDelay: (d: number) => void
    stepSize: (dir: -1 | 1) => void
    toggleFullscreen: () => void | Promise<void>
    cycleShape: () => void
    turn: () => number
    duration: () => number
    pickTurn: (deg: number) => void
    zoomBy: (steps: number) => void
    resetZoom: () => void
    screenshot: (withSubtitles: boolean) => void | Promise<void>
    pressRepeat: () => void
    bookmarkHere: () => void
    askTime: () => void
    pipSupported: () => boolean
    togglePip: () => void | Promise<void>
}

/** The remote's meaning of a key: true (handled and stopped), false (not ours), 'native' (the focused control takes it). */
function tvKey(k: Keys, e: KeyboardEvent): boolean | 'native' {
    const { c } = k
    const v = c.video
    const key = remoteKey(e)
    if (!key) return false
    // Stop, Info, CC, Next and Previous act once per press, as on Android (repeatCount == 0): a held
    // or bouncing Stop left two pages, a held CC spun through every track (PB2-10).
    if (e.repeat && oncePerPress(key)) return true
    const active = document.activeElement as HTMLElement | null
    switch (key) {
        case 'play':
            if (v.paused) (k.togglePlay(), k.flash(t('player.play')))
            return true
        case 'pause':
            if (!v.paused) v.pause()
            return true
        // Stop leaves the video, as the Android remote's Stop does (PlayerActivity).
        case 'stop':
            c.close()
            return true
        // webOS Info opens the playback details, CC the subtitles.
        case 'info':
            k.setSheet('details')
            return true
        case 'captions':
            k.cycleSub()
            return true
        case 'playpause':
            if (!e.repeat) k.togglePlay()
            return true
        case 'forward':
        case 'rewind':
            k.stepRemote(key === 'forward', e.repeat)
            return true
        case 'next':
        case 'previous': {
            const to = key === 'next' ? c.info()?.next : c.info()?.previous
            if (!to) return false
            k.go(to)
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
    const sheet = k.sheet()
    if (sheet) {
        // Playback details holds buttons now (Report stutter, Copy details): OK presses the one the remote is on, and closes the sheet from anywhere else.
        const onDetailsButton = sheet === 'details' && !!active?.closest('.pc-details-btn')
        if (key === 'back' || (sheet === 'details' && key === 'ok' && !onDetailsButton)) k.setSheet(null)
        else if (key === 'ok') return 'native'
        else if (arrow && sheet === 'details') walk(c.root.querySelector('.pc-details'), key, active)
        else if (arrow && ['episodes', 'timing', 'bookmarks', 'still', 'msone'].includes(sheet)) walk(c.root.querySelector('.pc-sheet, .pc-still'), key, active)
        return true
    }
    // The next episode's card holds Left, Right and OK once it has the remote; Back puts it away.
    if (k.counting() !== null && key === 'back') return (k.stopCount(), k.setNextHidden(true), true)
    const card = c.root.querySelector<HTMLElement>('.pc-next')
    if (card && active && card.contains(active)) {
        if (key === 'back') (k.stopCount(), k.setNextHidden(true), k.focusIt(c.root))
        else if (key === 'left' || key === 'right') walk(card, key, active)
        else if (key === 'ok') return 'native'
        if (key === 'back' || key === 'left' || key === 'right') return true
    }
    // A notice over the picture with its own close (no sound, a slow decoder): Up from the
    // row's top reaches it; Down, Back or closing it puts the remote back there, with the row
    // up again if it went meanwhile (HX22).
    const closing = k.noticeClose()
    if (closing && active === closing) {
        if (key === 'ok') closing.click()
        if (key === 'ok' || key === 'back' || key === 'down') (k.show(), k.focusIt(k.discEl() ?? k.barEl()))
        return true
    }
    if (!(k.visible() && !k.locked())) {
        const playNow = card?.querySelector<HTMLElement>('button:last-child')
        if (key === 'left' || key === 'right') playNow ? k.focusIt(playNow) : k.stepRemote(key === 'right', e.repeat)
        else if (key === 'ok' && k.skipNow() && !playNow) k.skipIt()
        else if (key === 'ok') playNow ? k.focusIt(playNow) : k.togglePlay()
        else if (key === 'up' && k.backTo() !== null) k.takeBack()
        else if (key === 'up' && k.offer() && c.resumedAt() !== null) (c.startOver(), k.setOffer(false))
        else if (key === 'up' || key === 'down') k.show()
        else if (key === 'back') c.close()
        return true
    }
    k.poke()
    const items = k.rowItems()
    const at = active ? items.indexOf(active) : -1
    const bar = k.barEl()
    const disc = k.discEl()
    const notice = k.noticeClose()
    const top = disc ?? bar
    if (key === 'back') k.hide()
    else if (key === 'ok') {
        if (k.inLayer(active) && active !== bar) return 'native'
        k.togglePlay()
    } else if (key === 'left' || key === 'right') {
        if (at >= 0) k.focusIt(items[at + (key === 'right' ? 1 : -1)])
        else if (active === bar || active !== disc) k.stepRemote(key === 'right', e.repeat)
    } else if (key === 'up') {
        if (at >= 0) k.focusIt(bar)
        else if (active === bar && disc) k.focusIt(disc)
        else if (active === top && notice) k.focusIt(notice)
        else if (active !== disc && active !== bar) k.focusIt(bar)
    } else if (key === 'down') {
        if (at >= 0) return true
        if (active === disc) k.focusIt(bar)
        else if (active === bar) k.focusIt(items[0])
        else k.focusIt(bar)
    }
    return true
}

/** The keyboard's meaning of a key, and the remote's on a TV; listens on the page through onGlobal (not while the profile is locked). */
export function useKeys(k: Keys) {
    const { c } = k
    const v = c.video
    const onKey = (e: KeyboardEvent) => {
        const target = e.target as HTMLElement | null
        const typing = !!target && (target.isContentEditable || (target instanceof HTMLInputElement && target.type !== 'range') || target instanceof HTMLTextAreaElement)
        // A confirm question (ui/Confirm.tsx) has every key, Escape first: the player behind it stays.
        if (!playerHearsKeys({ handled: e.defaultPrevented, typing, confirm: confirmOpen() })) return
        if (k.tv()) {
            const handled = tvKey(k, e)
            if (handled) {
                if (handled === true) e.preventDefault()
                return
            }
        }
        if (c.blocked()) return
        if (k.locked()) {
            if (e.key === 'Enter') k.unlock()
            else k.showUnlock()
            e.preventDefault()
            return
        }
        // A sheet or menu up: Escape closes it, the rest is its own.
        if (k.sheet()) {
            if (e.key === 'Escape') {
                k.setSheet(null)
                e.preventDefault()
            }
            return
        }
        const key = e.key
        // Not every target is an element: a key sent to the document itself has none.
        const onSlider = target instanceof Element && target.getAttribute('role') === 'slider'
        const onButton = target instanceof HTMLButtonElement || onSlider
        // The seek bar's own arrows, and a focused button's Space and Enter, are theirs.
        if ((key === 'ArrowLeft' || key === 'ArrowRight') && onSlider) return
        if ((key === ' ' || key === 'Enter') && onButton && !e.altKey) return
        const a = actionFor(
            { key, code: e.code, shift: e.shiftKey, ctrl: e.ctrlKey, alt: e.altKey, meta: e.metaKey },
            { mac: MAC, chapters: c.chapters().length > 0, paused: v.paused, speedNormal: Math.abs(v.playbackRate - 1) <= 0.001, fullscreen: !!document.fullscreenElement, skipShown: !!k.skipNow() },
        )
        if (!a) return
        let done = true
        let raise = true
        switch (a.do) {
            case 'togglePlay': k.togglePlay(); break
            case 'seek':
                // The plain arrows are the one stepper's: a held key neither storms nor stands still. A modified arrow is one jump a press.
                if (Math.abs(a.by) === SEEK_SHORT) {
                    k.stepRemote(a.by > 0, e.repeat)
                    break
                }
                if (e.repeat) break
                k.seekBy(a.by)
                k.flash(`${a.by > 0 ? '+' : '-'}${t('player.seek_seconds', { seconds: Math.abs(a.by) })}`, a.by > 0 ? 'right' : 'left')
                break
            case 'chapter': k.stepChapter(a.forward); break
            case 'volume':
                k.setVol(v.volume + a.by)
                k.flash(`${Math.round(v.volume * 100)}%`)
                raise = false
                break
            case 'mute': v.muted = !v.muted; raise = false; break
            case 'episode': k.go((a.next ? c.info()?.next : c.info()?.previous) ?? null); break
            case 'speed': k.stepSpeed(a.up); break
            case 'speedReset': k.setSpeed(1); break
            case 'frame': k.seekBy(a.forward ? k.frameLength() : -k.frameLength()); break
            case 'subtitles': k.cycleSub(a.back); break
            case 'subtitlesToggle': k.cycleSub(); break
            case 'audio': k.cycleAudio(a.back); break
            case 'subtitleDelay': k.nudgeDelay(a.by); break
            case 'subtitleSize': k.stepSize(a.by); break
            case 'fullscreen': void k.toggleFullscreen(); break
            case 'exitFullscreen': document.exitFullscreen().catch(() => {}); break
            case 'shape': k.cycleShape(); break
            case 'rotate': k.pickTurn(nextTurn(k.turn())); break
            case 'zoom': k.zoomBy(a.steps); raise = false; break
            case 'zoomReset': k.resetZoom(); raise = false; break
            case 'screenshot': void k.screenshot(a.subtitles); raise = false; break
            case 'repeat': k.pressRepeat(); break
            case 'bookmark': k.bookmarkHere(); break
            case 'skip': k.skipIt(); break
            case 'goToTime': k.askTime(); break
            case 'undoSeek': k.takeBack(); break
            // Where the browser has no small window, Ctrl+P stays its print.
            case 'pip': k.pipSupported() ? void k.togglePip() : (done = false); break
            case 'details': k.setSheet('details'); break
            case 'sheet': k.setSheet('shortcuts'); break
            case 'jump': k.seekTo((k.duration() * a.tenth) / 10); break
            case 'end': k.seekTo(k.duration()); break
            case 'back': c.close(); break
        }
        if (done) {
            e.preventDefault()
            if (raise) k.show()
        }
    }
    // Not while the profile is locked: no key of the page may play, skip or open the locked video.
    onMount(() => onCleanup(onGlobal(document, 'keydown', onKey)))
    // Any key up ends a hold: a repeat that comes without its first press then starts with the plain step (PB2-12).
    onMount(() => onCleanup(onGlobal(document, 'keyup', () => k.releaseStep())))
}
