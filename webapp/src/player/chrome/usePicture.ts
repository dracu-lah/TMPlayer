// The picture's own state: its shape (VideoScale), its quarter turns (PictureRotation) and the free zoom (PZ1,
// PictureZoom). The three share the one transform and the one stage measure, so they live together.
import { createEffect, createSignal, on, onCleanup, untrack } from 'solid-js'
import { t } from '../../i18n'
import { cueOverlay, type CueOverlay } from '../cueoverlay'
import { pictureTransform, sidewaysTurn, turnedPictureSize, turnFrame } from '../extras'
import { nextShape, objectFit, ratioBox, shapeRatio, type Shape } from '../shape'
import { clampedTo, NONE as NO_ZOOM, percent, stepped, zoomed, type Frame, type Zoom } from '../zoom'
import { shapeName, ZOOM_MS } from './parts'
import type { Ctl } from './types'

type WebkitShown = HTMLVideoElement & { webkitDisplayingFullscreen?: boolean }

export interface PictureDeps {
    listen: <K extends keyof HTMLMediaElementEventMap>(name: K, fn: () => void) => void
    flash: (text: string) => void
    tv: () => boolean
    /** The controls are locked (the screen lock). */
    locked: () => boolean
}

export function usePicture(c: Ctl, d: PictureDeps) {
    const v = c.video
    // Picture shape (VideoScale): Original, Fill and Stretch as object-fit on the video; a fixed
    // ratio as the video's box sized to it inside the stage, the picture filling the box. For this
    // player only: a new one starts on Original. The box follows the stage's size, so a resize,
    // fullscreen and a turned phone keep the ratio.
    const [shape, setShape] = createSignal<Shape>('fit')
    const [stage, setStage] = createSignal({ width: 0, height: 0 })
    {
        const box = v.parentElement
        if (box && typeof ResizeObserver !== 'undefined') {
            const watch = new ResizeObserver(() => setStage({ width: box.clientWidth, height: box.clientHeight }))
            watch.observe(box)
            onCleanup(() => watch.disconnect())
        }
    }
    // Rotate picture (PictureRotation): the picture turned a quarter at a time as it is drawn, the box resized so a
    // sideways picture still fits the stage; for this video only (a new player starts upright).
    const [turn, setTurn] = createSignal(0)
    createEffect(() => {
        const s = shape()
        const deg = turn()
        const sw = stage().width || v.parentElement?.clientWidth || 0
        const sh = stage().height || v.parentElement?.clientHeight || 0
        v.style.objectFit = objectFit(s)
        // The canvas engines' pictures follow this (movi.ts by custom properties, libavcanvas.ts by mirroring the style).
        c.root.style.setProperty('--stage-w', `${sw}px`)
        c.root.style.setProperty('--stage-h', `${sh}px`)
        c.root.style.setProperty('--turn', `${deg}deg`)
        if (deg) c.root.setAttribute('data-turn', String(deg))
        else c.root.removeAttribute('data-turn')
        const ratio = shapeRatio(s)
        const frame = turnFrame(sw, sh, deg)
        const inner = ratio === null ? null : ratioBox(frame.width, frame.height, ratio)
        const at = inner ? { left: frame.left + inner.left, top: frame.top + inner.top, width: inner.width, height: inner.height } : sidewaysTurn(deg) && sw > 0 && sh > 0 ? frame : null
        if (!at) {
            for (const p of ['position', 'left', 'top', 'width', 'height'] as const) v.style.removeProperty(p)
            return
        }
        Object.assign(v.style, { position: 'absolute', left: `${at.left}px`, top: `${at.top}px`, width: `${at.width}px`, height: `${at.height}px` })
    })
    onCleanup(() => {
        for (const p of ['position', 'left', 'top', 'width', 'height', 'object-fit'] as const) v.style.removeProperty(p)
        for (const p of ['--stage-w', '--stage-h', '--turn']) c.root.style.removeProperty(p)
        c.root.removeAttribute('data-turn')
    })

    // ---- free zoom: 100 to 400 per cent over the shape, around the fingers -----
    // Drawn as a transform on the picture (the <video>, or the canvas an engine draws on in its
    // place), centred on the stage as every shape's box is, so the zoom's pan is the transform's
    // move. The subtitles stay outside it: the <video>'s own cue box would grow with the picture,
    // so while zoomed it is hidden (zoom.css) and the cues are drawn in the layer over the picture
    // the canvas engines already use. A new video is a new Chrome, so it starts at 100 per cent.
    const [zoom, setZoom] = createSignal<Zoom>(NO_ZOOM)
    const [zoomChip, setZoomChip] = createSignal(false)
    let zoomChipTimer = 0
    /** Not on the TV (no fingers, no zoom keys), not while locked or a status sheet is up, not in the iPhone's own full screen. */
    const zoomAllowed = () => !d.tv() && !d.locked() && !c.blocked() && !(v as WebkitShown).webkitDisplayingFullscreen
    const engineCanvas = () => c.root.querySelector<HTMLCanvasElement>('canvas.sw-canvas, canvas.movi-canvas')
    /**
     * The stage and the picture's size in it, measured once and kept: a pinch's moves only write
     * the transform, never read the layout back. Measured again after a resize, another shape,
     * a new frame size or an engine's canvas coming or going.
     */
    let frameKept: Frame | null = null
    const zoomFrame = (): Frame => {
        if (frameKept) return frameKept
        const w = c.root.clientWidth
        const h = c.root.clientHeight
        const cv = engineCanvas()
        const pic = turnedPictureSize(w, h, v.videoWidth || cv?.width || w, v.videoHeight || cv?.height || h, shape(), turn())
        const frame = { viewWidth: w, viewHeight: h, pictureWidth: pic?.width ?? w, pictureHeight: pic?.height ?? h }
        // A stage not laid out yet is measured again next time.
        if (w > 0 && h > 0) frameKept = frame
        return frame
    }
    d.listen('resize', () => (frameKept = null))
    const showZoomChip = (live: boolean) => {
        clearTimeout(zoomChipTimer)
        setZoomChip(true)
        if (!live) zoomChipTimer = window.setTimeout(() => setZoomChip(false), ZOOM_MS)
    }
    /** Back to 100 per cent: with the figure saying so, or `quiet` (a new shape says it with its own note). */
    function resetZoom(quiet = false) {
        if (quiet) (clearTimeout(zoomChipTimer), setZoomChip(false))
        if (!zoomed(zoom())) return
        setZoom(NO_ZOOM)
        if (!quiet) showZoomChip(false)
    }
    /** A key's or a wheel's steps, around a point of the stage (its centre for a key). */
    const zoomBy = (steps: number, x = c.root.clientWidth / 2, y = c.root.clientHeight / 2) => {
        if (!zoomAllowed() || !steps) return
        setZoom((z) => stepped(z, steps, x, y, zoomFrame()))
        showZoomChip(false)
    }
    /** The transform on every picture there is now: the <video>, and an engine's canvas, however late it came. The turn and the zoom share it (extras.ts pictureTransform). */
    const applyZoom = (z: Zoom) => {
        const tf = pictureTransform(untrack(turn), z)
        for (const el of [v, engineCanvas()]) {
            if (!el) continue
            if (el.style.transform !== tf) el.style.transform = tf
            el.style.transformOrigin = tf ? 'center center' : ''
        }
        c.root.classList.toggle('pc-zoomed', zoomed(z))
    }
    // The cues while zoomed: a layer of our own over the native picture, unless an engine draws its
    // own (movi.ts, libavcanvas.ts), which wins whenever it comes, so the words are never drawn twice.
    let zoomCues: CueOverlay | null = null
    let zoomCuesEl: Element | null = null
    const engineCues = () => Array.from(c.root.querySelectorAll('.movi-cues')).some((el) => el !== zoomCuesEl)
    const placeCues = (z: Zoom) => {
        // One layer for both: the zoomed picture and the turned one each take the element's own cue box with them.
        const want = (zoomed(z) || untrack(turn) !== 0) && !engineCues()
        if (want && !zoomCues) {
            const before = new Set(c.root.querySelectorAll('.movi-cues'))
            zoomCues = cueOverlay(v, () => v.currentTime)
            zoomCuesEl = Array.from(c.root.querySelectorAll('.movi-cues')).find((el) => !before.has(el)) ?? null
            zoomCues.draw()
        } else if (!want && zoomCues) {
            zoomCues.remove()
            zoomCues = null
            zoomCuesEl = null
        }
    }
    createEffect(() => {
        const z = zoom()
        turn()
        applyZoom(z)
        placeCues(z)
    })
    // An engine's canvas or cue layer added (or taken away) after the zoom was set: the transform
    // and the cues follow it, and the picture is measured again.
    if (typeof MutationObserver !== 'undefined') {
        const engines = new MutationObserver(() => {
            frameKept = null
            const z = untrack(zoom)
            applyZoom(z)
            placeCues(z)
        })
        engines.observe(c.root, { childList: true })
        onCleanup(() => engines.disconnect())
    }
    // A resized stage (a turned phone, a window, full screen) keeps the scale and pulls the pan back inside.
    createEffect(on(stage, () => ((frameKept = null), setZoom((z) => (zoomed(z) ? clampedTo(z, zoomFrame()) : z))), { defer: true }))
    const redrawCues = () => zoomCues?.draw()
    d.listen('timeupdate', redrawCues)
    v.textTracks.addEventListener('change', redrawCues)
    // The iPhone's own full screen is the system's player: the page's transform does not reach it.
    const quietReset = () => resetZoom(true)
    v.addEventListener('webkitbeginfullscreen', quietReset)
    onCleanup(() => {
        clearTimeout(zoomChipTimer)
        zoomCues?.remove()
        v.textTracks.removeEventListener('change', redrawCues)
        v.removeEventListener('webkitbeginfullscreen', quietReset)
        c.root.classList.remove('pc-zoomed')
        for (const el of [v, engineCanvas()]) el?.style.removeProperty('transform')
        v.style.removeProperty('transform-origin')
    })
    const zoomText = () => t('player.zoom_percent', { percent: `${percent(zoom())}%` })

    const pickTurn = (deg: number) => {
        // The turn changes the picture the zoom enlarges, as another shape does: the zoom starts again from 100 per cent.
        resetZoom(true)
        setTurn(deg)
        frameKept = null
        d.flash(deg ? t('player.rotate_picture_now', { degrees: deg }) : t('player.rotate_picture_off'))
    }
    const pickShape = (next: Shape) => {
        // Another shape starts the zoom again from 100 per cent (PictureZoom: the shape sizes the picture
        // it enlarges), quietly: the shape's own note says what happened.
        resetZoom(true)
        setShape(next)
        frameKept = null
        d.flash(t('player.picture_shape_now', { shape: shapeName(next) }))
    }
    const cycleShape = () => pickShape(nextShape(shape()))

    return { shape, turn, stage, zoom, setZoom, zoomChip, zoomText, zoomAllowed, zoomFrame, zoomBy, showZoomChip, resetZoom, engineCanvas, pickTurn, pickShape, cycleShape }
}
