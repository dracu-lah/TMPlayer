// The picture shapes of core's VideoScale (HX16), for the web player: Original, Fill and Stretch
// as object-fit, and the fixed ratios as the video's box sized to the ratio inside the stage with
// object-fit: fill, as the apps' aspect override draws them. Pure, so node --test can reach it.

export type Shape = 'fit' | 'crop' | 'stretch' | '16:9' | '4:3' | '21:9' | '2.35:1' | '1:1'

/** VideoScale.entries, in the order W and the shape button step through. */
export const SHAPES: Shape[] = ['fit', 'crop', 'stretch', '16:9', '4:3', '21:9', '2.35:1', '1:1']

/** Width over height of a fixed shape; null for the three that fill or fit the stage. */
export function shapeRatio(s: Shape): number | null {
    switch (s) {
        case '16:9': return 16 / 9
        case '4:3': return 4 / 3
        case '21:9': return 21 / 9
        case '2.35:1': return 2.35
        case '1:1': return 1
        default: return null
    }
}

/** VideoScale.next: every shape in turn, back to Original after 1:1. */
export const nextShape = (s: Shape): Shape => SHAPES[(SHAPES.indexOf(s) + 1) % SHAPES.length]

/** VideoScale.pinch: the first three only, a fixed shape counting as Original. */
export function pinchShape(s: Shape, wider: boolean): Shape {
    const at = shapeRatio(s) === null ? SHAPES.indexOf(s) : 0
    return SHAPES[wider ? Math.min(at + 1, 2) : Math.max(at - 1, 0)]
}

/** The video's object-fit for a shape: a fixed ratio fills the box [ratioBox] gives it. */
export const objectFit = (s: Shape) => (s === 'fit' ? 'contain' : s === 'crop' ? 'cover' : 'fill')

/** The glyph of a fixed shape: a rounded frame at its ratio in the 24 unit box, as the desktop's. */
export function ratioPath(ratio: number): string {
    const w = Math.min(20, 15 * ratio)
    const h = w / ratio
    const x = 12 - w / 2
    const y = 12 - h / 2
    const r = 2
    const n = (v: number) => String(Math.round(v * 100) / 100)
    return `M${n(x + r)} ${n(y)}H${n(x + w - r)}Q${n(x + w)} ${n(y)} ${n(x + w)} ${n(y + r)}V${n(y + h - r)}Q${n(x + w)} ${n(y + h)} ${n(x + w - r)} ${n(y + h)}H${n(x + r)}Q${n(x)} ${n(y + h)} ${n(x)} ${n(y + h - r)}V${n(y + r)}Q${n(x)} ${n(y)} ${n(x + r)} ${n(y)}Z`
}

/**
 * The largest box of [ratio] that fits a [width] by [height] stage, centred in it, in whole
 * pixels; null for a stage with no size yet (the next resize sizes it).
 */
export function ratioBox(width: number, height: number, ratio: number): { width: number; height: number; left: number; top: number } | null {
    if (!(width > 0) || !(height > 0) || !(ratio > 0) || !isFinite(ratio)) return null
    const w = Math.min(width, height * ratio)
    const h = w / ratio
    const bw = Math.round(w)
    const bh = Math.round(h)
    return { width: bw, height: bh, left: Math.round((width - bw) / 2), top: Math.round((height - bh) / 2) }
}
