// The picture shapes (HX16): the box a fixed ratio gets inside the stage, and the order W, the
// shape button and a pinch step through. `node --test "src/player/*.test.ts"` (pnpm test:player).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { nextShape, objectFit, pictureSize, pinchShape, ratioBox, ratioPath, shapeRatio, SHAPES, type Shape } from './shape.ts'

test('the eight shapes in the apps order, W going round all of them', () => {
    assert.deepEqual(SHAPES, ['fit', 'crop', 'stretch', '16:9', '4:3', '21:9', '2.35:1', '1:1'])
    let s = SHAPES[0]
    const seen = [s]
    for (let i = 1; i < SHAPES.length; i++) seen.push((s = nextShape(s)))
    assert.deepEqual(seen, SHAPES)
    assert.equal(nextShape('1:1'), 'fit')
})

test('a pinch steps the first three only, a fixed shape counting as Original', () => {
    assert.equal(pinchShape('fit', true), 'crop')
    assert.equal(pinchShape('crop', true), 'stretch')
    assert.equal(pinchShape('stretch', true), 'stretch')
    assert.equal(pinchShape('fit', false), 'fit')
    assert.equal(pinchShape('4:3', true), 'crop')
    assert.equal(pinchShape('21:9', false), 'fit')
})

test('object-fit: contain, cover, and fill for Stretch and every fixed ratio', () => {
    assert.equal(objectFit('fit'), 'contain')
    assert.equal(objectFit('crop'), 'cover')
    assert.equal(objectFit('stretch'), 'fill')
    for (const s of ['16:9', '4:3', '21:9', '2.35:1', '1:1'] as const) assert.equal(objectFit(s), 'fill')
    assert.equal(shapeRatio('fit'), null)
    assert.equal(shapeRatio('2.35:1'), 2.35)
})

test('a wide ratio on a tall stage fills the width, centred top to bottom', () => {
    // A phone held upright: 390 by 844.
    assert.deepEqual(ratioBox(390, 844, 16 / 9), { width: 390, height: 219, left: 0, top: 313 })
})

test('a narrow ratio on a wide stage fills the height, centred side to side', () => {
    assert.deepEqual(ratioBox(1280, 720, 4 / 3), { width: 960, height: 720, left: 160, top: 0 })
    assert.deepEqual(ratioBox(1920, 1080, 1), { width: 1080, height: 1080, left: 420, top: 0 })
})

test('the ratio of the stage itself fills it, and 2.35:1 letterboxes a 16:9 stage', () => {
    assert.deepEqual(ratioBox(1920, 1080, 16 / 9), { width: 1920, height: 1080, left: 0, top: 0 })
    assert.deepEqual(ratioBox(1920, 1080, 2.35), { width: 1920, height: 817, left: 0, top: 132 })
})

test('a stage with no size yet, or a ratio that is no number, gives no box', () => {
    assert.equal(ratioBox(0, 720, 16 / 9), null)
    assert.equal(ratioBox(1280, 0, 16 / 9), null)
    assert.equal(ratioBox(1280, 720, 0), null)
    assert.equal(ratioBox(1280, 720, NaN), null)
    assert.equal(ratioBox(1280, 720, Infinity), null)
})

test('the ratio glyph stays inside the 24 unit box', () => {
    for (const s of SHAPES) {
        const r = shapeRatio(s)
        if (r === null) continue
        const numbers = ratioPath(r).match(/-?\d+(\.\d+)?/g)!.map(Number)
        assert.ok(numbers.every((n) => n >= 1.9 && n <= 22.1), `${s}: ${numbers}`)
    }
})

// A side by side 3D file watched flat (the web has no 3D): a squeezed one (1920x1080, two eyes at
// half width) and a full one (3840x1080, a 32:9 looking frame), on a 1280x720 stage.
const SBS: [string, number, number, Record<Shape, [number, number]>][] = [
    ['squeezed', 1920, 1080, { fit: [1280, 720], crop: [1280, 720], stretch: [1280, 720], '16:9': [1280, 720], '4:3': [960, 720], '21:9': [1280, 549], '2.35:1': [1280, 545], '1:1': [720, 720] }],
    ['full', 3840, 1080, { fit: [1280, 360], crop: [2560, 720], stretch: [1280, 720], '16:9': [1280, 720], '4:3': [960, 720], '21:9': [1280, 549], '2.35:1': [1280, 545], '1:1': [720, 720] }],
]
for (const [name, fw, fh, want] of SBS) {
    test(`a ${name} side by side frame gets the box of each of the eight shapes`, () => {
        for (const s of SHAPES) assert.deepEqual(pictureSize(1280, 720, fw, fh, s), { width: want[s][0], height: want[s][1] }, `${name} ${s}`)
    })
}

test('16:9 forced on the 32:9 looking frame draws it at 16:9, its eyes squeezed back to their shape', () => {
    const p = pictureSize(1920, 1080, 3840, 1080, '16:9')!
    assert.ok(Math.abs(p.width / p.height - 16 / 9) < 0.01)
    assert.equal(pictureSize(0, 1080, 3840, 1080, '16:9'), null)
})
