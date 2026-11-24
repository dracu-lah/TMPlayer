// The picture: taps, clicks, the wheel and the phone's gestures (VideoGestures, TapZone, FineScrub, PlayerGestures).
// Double tap or click, a held finger (fast play), the sideways scrub, the side strips' brightness and volume, the
// pinch and its pan. What they do goes through `Deps`, so this file only reads the pointer.
import { createEffect, createSignal, onCleanup } from 'solid-js'
import { t } from '../../i18n'
import { fineRate } from '../extras'
import { panBy, pinch as pinchZoom, settled, wheelSteps, zoomed, type Zoom } from '../zoom'
import { DOUBLE_MS, FIXED_VOLUME, HOLD_MS, RUN_MS, SIDE, SLOP } from './parts'
import type { usePicture } from './usePicture'
import type { Ctl } from './types'

export interface GestureDeps {
    c: Ctl
    pic: ReturnType<typeof usePicture>
    tv: () => boolean
    phone: () => boolean
    /** Plain labels (the phone and the TV): a tap on the picture toggles the controls rather than playing. */
    plain: () => boolean
    locked: () => boolean
    sheet: () => unknown
    duration: () => number
    flash: (text: string, side?: 'left' | 'right' | 'centre') => void
    seekBy: (s: number) => void
    seekTo: (s: number, from?: number) => void
    setVol: (x: number) => void
    togglePlay: () => void
    toggleFullscreen: () => void | Promise<void>
    /** Raises or puts away the controls. */
    toggle: () => void
    show: () => void
}

export function useGestures(d: GestureDeps) {
    const { c, pic } = d
    const v = c.video
    const [hold, setHold] = createSignal(false)
    const [ripple, setRipple] = createSignal<{ side: 'left' | 'right'; total: number; x: number; y: number; n: number } | null>(null)
    const [level, setLevel] = createSignal<{ side: 'left' | 'right'; value: number; brightness: boolean } | null>(null)
    const [scrub, setScrub] = createSignal<{ from: number; to: number; rate?: number } | null>(null)
    const [brightness, setBrightness] = createSignal(1)
    createEffect(() => (v.style.filter = brightness() < 0.999 ? `brightness(${brightness()})` : ''))

    let down: { x: number; y: number; at: number; id: number; time: number; vol: number; bright: number } | null = null
    /** Fingers on the picture, in the stage's own pixels (two of them are a pinch, which zooms the picture). */
    const touches = new Map<number, { x: number; y: number }>()
    /** The pinch under way: the fingers' spread and the point between them at the last step. Null when none. */
    let pinch: { dist: number; x: number; y: number } | null = null
    const spread = () => {
        const [a, b] = [...touches.values()]
        return Math.hypot(a.x - b.x, a.y - b.y)
    }
    const middle = () => {
        const [a, b] = [...touches.values()]
        return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }
    }
    /** Where the drag that moves the enlarged picture was at its last step. */
    let panAt = { x: 0, y: 0 }
    /** The sideways scrub, slowed to a half and a quarter as the finger leaves its line (FineScrub.Tracker). */
    let fineScrub = { x: 0, last: 0 }
    let mode: 'tap' | 'hold' | 'scrub' | 'level' | 'pan' = 'tap'
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
    /**
     * Where a pointer is on the stage. The stage's place is read when a touch or press begins and
     * kept for its moves (`fresh` reads it again), so a drag that writes the zoom's transform never
     * makes the browser lay the page out again to answer a read.
     */
    let rootAt = { left: 0, top: 0 }
    const local = (e: { clientX: number; clientY: number }, fresh = false) => {
        if (fresh) {
            const r = c.root.getBoundingClientRect()
            rootAt = { left: r.left, top: r.top }
        }
        return { x: e.clientX - rootAt.left, y: e.clientY - rootAt.top }
    }
    /** Ten seconds either way, the running total shown in the ripple. */
    const jump = (side: 'left' | 'right', x: number, y: number) => {
        d.seekBy(side === 'left' ? -10 : 10)
        const prev = ripple()
        const total = prev && prev.side === side ? prev.total + 10 : 10
        setRipple({ side, total, x, y, n: (prev?.n ?? 0) + 1 })
        runUntil = performance.now() + RUN_MS
        clearTimeout(rippleTimer)
        rippleTimer = window.setTimeout(() => setRipple(null), RUN_MS + 300)
    }
    /** The same figure for a seek a key made through the stepper (seekStepper.ts): its own running total, from the matching side. */
    const rippleFor = (side: 'left' | 'right', total: number) => {
        const w = c.root.clientWidth
        setRipple({ side, total: Math.round(Math.abs(total)), x: w * (side === 'right' ? 0.85 : 0.15), y: c.root.clientHeight / 2, n: (ripple()?.n ?? 0) + 1 })
        runUntil = performance.now() + RUN_MS
        clearTimeout(rippleTimer)
        rippleTimer = window.setTimeout(() => setRipple(null), RUN_MS + 300)
    }
    /** The TV's Left and Right over the bare picture: the jump from the matching side. */
    const jumpRemote = (forward: boolean) => {
        const w = c.root.clientWidth
        jump(forward ? 'right' : 'left', w * (forward ? 0.85 : 0.15), c.root.clientHeight / 2)
    }
    const seekFlash = (up: boolean) => d.flash(`${up ? '+' : '-'}${t('player.seek_seconds', { seconds: 10 })}`, up ? 'right' : 'left')
    /**
     * The desktop's wheel over the picture: volume, or with Shift ten seconds a notch. Ctrl+wheel
     * zooms around the pointer, and so does a trackpad's pinch, which browsers send as Ctrl+wheel;
     * it is never the page's zoom over the picture (a tablet's trackpad included).
     */
    const onWheel = (e: WheelEvent) => {
        if (d.tv()) return
        if (e.ctrlKey) {
            e.preventDefault()
            if (d.locked() || c.blocked() || d.sheet()) return
            const p = local(e, true)
            pic.zoomBy(wheelSteps(e.deltaY, e.deltaMode), p.x, p.y)
            return
        }
        if (d.phone() || d.locked() || c.blocked() || d.sheet() || !e.deltaY) return
        e.preventDefault()
        if (e.shiftKey) {
            d.seekBy(e.deltaY < 0 ? 10 : -10)
            seekFlash(e.deltaY < 0)
        } else {
            d.setVol(v.volume + (e.deltaY < 0 ? 0.05 : -0.05))
            d.flash(`${Math.round(v.volume * 100)}%`)
        }
    }
    const onDown = (e: PointerEvent) => {
        if (d.locked() || e.button > 0) return
        // Any touch screen: a phone, an iPad, a touch laptop in the desktop form (the TV has no fingers).
        if (e.pointerType === 'touch' && !d.tv()) {
            touches.set(e.pointerId, local(e, true))
            if (touches.size === 2) {
                clearTimeout(holdTimer)
                clearTimeout(tapTimer)
                if (mode === 'hold') (v.playbackRate = heldRate, setHold(false))
                // A drag of the first finger that had begun (a scrub, a level) ends where it was.
                if (mode === 'scrub') scrubTimer = window.setTimeout(() => setScrub(null), 400)
                if (mode === 'level') levelTimer = window.setTimeout(() => setLevel(null), 1000)
                mode = 'tap'
                down = null
                pinch = { dist: spread(), ...middle() }
                return
            }
            if (touches.size > 2 || pinch) return
        }
        const p = local(e, true)
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
            if (touches.has(e.pointerId)) touches.set(e.pointerId, local(e))
            if (touches.size !== 2) return
            // One step of the pinch: the scale by the spread's change, around the point between the
            // fingers, and moved with that point (a two finger drag pans), as PlayerGestures' onZoom.
            const dist = spread()
            const m = middle()
            if (dist > 0 && pinch.dist > 0 && pic.zoomAllowed()) {
                const frame = pic.zoomFrame()
                const dx = m.x - pinch.x
                const dy = m.y - pinch.y
                pic.setZoom((z) => panBy(pinchZoom(z, dist / pinch!.dist, m.x, m.y, frame), dx, dy, frame))
                pic.showZoomChip(true)
            }
            pinch = { dist, ...m }
            return
        }
        const mouse = e.pointerType === 'mouse'
        if (!d.phone() && mouse) {
            d.show()
            // The mouse's drag moves an enlarged picture (the desktop app's pan); otherwise a move is only a move.
            if (!down || e.pointerId !== down.id || !(e.buttons & 1) || (mode !== 'pan' && !(mode === 'tap' && zoomed(pic.zoom())))) return
        }
        if (!down || e.pointerId !== down.id || mode === 'hold') return
        const p = local(e)
        const dx = p.x - down.x
        const dy = p.y - down.y
        const w = c.root.clientWidth
        const h = c.root.clientHeight
        if (mode === 'tap' && Math.hypot(dx, dy) > SLOP) {
            clearTimeout(holdTimer)
            // A pointer the browser no longer counts as down cannot be captured; the drag goes on without.
            try {
                ;(e.currentTarget as HTMLElement).setPointerCapture?.(e.pointerId)
            } catch {}
            if (zoomed(pic.zoom()) && pic.zoomAllowed()) {
                // Enlarged, a drag moves the picture and the scrub waits until it is back at 100 per
                // cent; an up or down drag from the side strips still sets brightness and volume.
                const strip = !mouse && (down.x < w * SIDE || down.x > w * (1 - SIDE))
                if (strip && Math.abs(dy) > Math.abs(dx) * 1.5) mode = 'level'
                else (mode = 'pan', (panAt = { x: down.x, y: down.y }))
            } else if (Math.abs(dx) > Math.abs(dy)) (mode = 'scrub', (fineScrub = { x: down.x, last: down.x }))
            else mode = 'level'
        }
        if (mode === 'pan') {
            const frame = pic.zoomFrame()
            pic.setZoom((z) => panBy(z, p.x - panAt.x, p.y - panAt.y, frame))
            panAt = p
            pic.showZoomChip(true)
            return
        }
        if (mode === 'scrub' && d.duration()) {
            // A full width's drag covers a ninetieth of an hour-long film per pixel, as the app's scrub reads.
            const rate = fineRate(dy)
            fineScrub.x += (p.x - fineScrub.last) * rate
            fineScrub.last = p.x
            const to = Math.max(0, Math.min(d.duration(), down.time + ((fineScrub.x - down.x) / w) * Math.min(d.duration(), 600)))
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
                d.setVol(down.vol + change)
                setLevel({ side: 'right', value: v.volume, brightness: false })
            }
            clearTimeout(levelTimer)
        }
    }
    /** The fingers of a pinch lift (or the browser takes them): it comes to rest, just over 100 per cent being 100. */
    const endPinch = (e: PointerEvent) => {
        const was = touches.size
        touches.delete(e.pointerId)
        if (was === 2 && touches.size < 2) {
            pic.setZoom((z) => settled(z, pic.zoomFrame()))
            pic.showZoomChip(false)
        }
        if (!touches.size) pinch = null
    }
    const onUp = (e: PointerEvent) => {
        if (pinch) return endPinch(e)
        touches.delete(e.pointerId)
        clearTimeout(holdTimer)
        if (!down || e.pointerId !== down.id) return
        const start = down
        down = null
        if (mode === 'pan') {
            pic.showZoomChip(false)
            return
        }
        if (mode === 'hold') {
            v.playbackRate = heldRate
            setHold(false)
            return
        }
        if (mode === 'scrub') {
            const s = scrub()
            if (s) d.seekTo(s.to, start.time)
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
                d.toggleFullscreen()
                return
            }
            lastTap = { at: now, zone, x: start.x }
            tapTimer = window.setTimeout(() => (d.plain() ? d.toggle() : d.togglePlay()), DOUBLE_MS)
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
            if (zone === 'centre') d.togglePlay()
            else jump(zone, start.x, start.y)
            return
        }
        lastTap = { at: now, zone, x: start.x }
        tapTimer = window.setTimeout(d.toggle, DOUBLE_MS)
    }
    const onCancel = (e: PointerEvent) => {
        if (pinch) return endPinch(e)
        touches.delete(e.pointerId)
        clearTimeout(holdTimer)
        if (mode === 'hold') {
            v.playbackRate = heldRate
            setHold(false)
        }
        if (mode === 'pan') pic.showZoomChip(false)
        down = null
    }
    onCleanup(() => [holdTimer, tapTimer, rippleTimer, levelTimer, scrubTimer].forEach(clearTimeout))
    // Safari's own pinch (gesture events, beside the pointer events above): on an iPhone the page
    // must not zoom under the fingers, so it is refused and the pointers do the work; on a Mac's
    // trackpad, where no pointers come, its scale is the zoom, around the pointer.
    type Gesture = UIEvent & { scale: number; clientX: number; clientY: number }
    let gestureScale = 1
    const onGesture = (e: Event) => {
        e.preventDefault()
        const g = e as Gesture
        if (e.type === 'gesturestart') return void (gestureScale = 1)
        if (e.type !== 'gesturechange' || touches.size || d.tv() || d.locked() || c.blocked() || d.sheet() || !(g.scale > 0)) return
        const r = c.root.getBoundingClientRect()
        const frame = pic.zoomFrame()
        if (!pic.zoomAllowed()) return
        pic.setZoom((z: Zoom) => pinchZoom(z, g.scale / gestureScale, g.clientX - r.left, g.clientY - r.top, frame))
        gestureScale = g.scale
        pic.showZoomChip(false)
    }
    const onGestureEnd = (e: Event) => {
        e.preventDefault()
        if (touches.size) return
        pic.setZoom((z) => settled(z, pic.zoomFrame()))
    }
    return { hold, ripple, level, scrub, jump, jumpRemote, rippleFor, seekFlash, onWheel, onDown, onMove, onUp, onCancel, onGesture, onGestureEnd }
}
