// Free zoom over the picture (PZ1), the arithmetic of core's PictureZoom.kt for the web player:
// a pinch that follows the fingers from 100 to 400 per cent around the point between them, a
// drag that moves the enlarged picture, and a pinch back under SNAP that settles on the fitted
// picture again. The page runs it on every pointer move, so it cannot wait on the rules worker:
// this is a line by line copy, and zoom.test.ts holds PictureZoomTest's cases.
//
// It sits on top of the picture shape (shape.ts): the shape decides the picture's size at 100 per
// cent, the zoom enlarges that. All in the pixels of the stage the picture is drawn in; panX and
// panY are how far the picture's centre has moved from the stage's centre, held so the picture's
// edge never comes inside the stage's edge. Never fed outside data, but a size of zero or not a
// number reads as "nothing to zoom" rather than a crash. Pure, so node --test can reach it.

export interface Zoom {
    readonly scale: number
    readonly panX: number
    readonly panY: number
}

/** The stage the picture is drawn in, and the picture's own size there at 100 per cent. */
export interface Frame {
    readonly viewWidth: number
    readonly viewHeight: number
    readonly pictureWidth: number
    readonly pictureHeight: number
}

export const MIN = 1
export const MAX = 4
/** A pinch let go under this settles on 100 per cent, so "almost back" is back. */
export const SNAP = 1.05
/** One key press or wheel notch: four of them double the picture. */
export const STEP = 2 ** 0.25
/** A wheel that reports a huge turn in one event still moves a sane amount. */
const MAX_STEPS = 8

export const NONE: Zoom = Object.freeze({ scale: MIN, panX: 0, panY: 0 })

const clamp = (x: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, x))
const valid = (f: Frame) => [f.viewWidth, f.viewHeight, f.pictureWidth, f.pictureHeight].every((n) => Number.isFinite(n) && n > 0)

/** True once the picture is larger than the shape draws it. */
export const zoomed = (z: Zoom) => z.scale > MIN

/** The zoom as the chip shows it: 135 for 1.35. */
export const percent = (z: Zoom) => Math.round(z.scale * 100)

/**
 * Held to MIN..MAX, with the picture's edge never inside the stage's edge. Called again whenever
 * the frame changes size: a phone turned, a window resized, another shape.
 */
export function clampedTo(z: Zoom, frame: Frame): Zoom {
    if (!valid(frame)) return NONE
    const held = Number.isFinite(z.scale) ? clamp(z.scale, MIN, MAX) : MIN
    const reachX = Math.max(0, (held * frame.pictureWidth - frame.viewWidth) / 2)
    const reachY = Math.max(0, (held * frame.pictureHeight - frame.viewHeight) / 2)
    const x = Number.isFinite(z.panX) ? clamp(z.panX, -reachX, reachX) : 0
    const y = Number.isFinite(z.panY) ? clamp(z.panY, -reachY, reachY) : 0
    // -0 and 0 are the same place; the tests (and a style string) want the plain one.
    return { scale: held, panX: x + 0, panY: y + 0 }
}

/**
 * One step of a pinch: the scale times `factor`, around the point (focusX, focusY) of the stage
 * the fingers are centred on, which stays under them. A factor that is not a number or not above
 * zero changes nothing.
 */
export function pinch(z: Zoom, factor: number, focusX: number, focusY: number, frame: Frame): Zoom {
    if (!valid(frame) || !Number.isFinite(factor) || factor <= 0 || !Number.isFinite(focusX) || !Number.isFinite(focusY)) return z
    const from = clamp(z.scale, MIN, MAX)
    const next = clamp(from * factor, MIN, MAX)
    const grow = next / from
    const fx = focusX - frame.viewWidth / 2
    const fy = focusY - frame.viewHeight / 2
    // The picture point under the focus is (f - pan) / scale; it stays under f at the new scale.
    return clampedTo({ scale: next, panX: fx - (fx - z.panX) * grow, panY: fy - (fy - z.panY) * grow }, frame)
}

/** A drag of (dx, dy) pixels on the enlarged picture; nothing at 100 per cent. */
export function panBy(z: Zoom, dx: number, dy: number, frame: Frame): Zoom {
    if (!zoomed(z) || !valid(frame) || !Number.isFinite(dx) || !Number.isFinite(dy)) return z
    return clampedTo({ scale: z.scale, panX: z.panX + dx, panY: z.panY + dy }, frame)
}

/** Where a pinch comes to rest as the fingers lift: just over 100 per cent is 100 per cent. */
export const settled = (z: Zoom, frame: Frame): Zoom => (z.scale < SNAP ? NONE : clampedTo(z, frame))

/**
 * A key's or a wheel notch's step, `steps` of STEP each (negative to zoom out), around
 * (focusX, focusY); settled at once, since there is no lift to wait for.
 */
export function stepped(z: Zoom, steps: number, focusX: number, focusY: number, frame: Frame): Zoom {
    if (!Number.isFinite(steps)) return z
    const next = pinch(z, STEP ** clamp(steps, -MAX_STEPS, MAX_STEPS), focusX, focusY, frame)
    return steps < 0 ? settled(next, frame) : next
}

/**
 * The steps a Ctrl+wheel event is worth: a mouse's notch (100 pixels, 3 lines or a page) is one,
 * a trackpad's pinch (which browsers send as a Ctrl+wheel of a few pixels at a time) a fraction.
 * Up, away from the viewer, zooms in, as in every map and image viewer.
 */
export function wheelSteps(deltaY: number, deltaMode: number): number {
    if (!Number.isFinite(deltaY) || deltaY === 0) return 0
    const per = deltaMode === 1 ? 3 : deltaMode === 2 ? 1 : 100
    return clamp(-deltaY / per, -MAX_STEPS, MAX_STEPS)
}

/** The CSS transform that draws the zoom on an element centred on the stage; empty at 100 per cent. */
export function transformOf(z: Zoom): string {
    if (!zoomed(z)) return ''
    const n = (v: number) => String(Math.round(v * 100) / 100)
    return `translate(${n(z.panX)}px, ${n(z.panY)}px) scale(${Math.round(z.scale * 10000) / 10000})`
}
