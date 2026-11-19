// The free zoom (PZ1): core's PictureZoomTest, case for case, plus the web's wheel and transform.
// `node --test "src/player/*.test.ts"` (pnpm test:player).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { clampedTo, NONE, panBy, percent, pinch, settled, stepped, transformOf, wheelSteps, zoomed, type Frame } from './zoom.ts'

/** A 16:9 picture filling a 1920 by 1080 screen. */
const wide: Frame = { viewWidth: 1920, viewHeight: 1080, pictureWidth: 1920, pictureHeight: 1080 }
/** The same picture on a phone held upright: a band across the middle of a tall screen. */
const upright: Frame = { viewWidth: 1080, viewHeight: 2340, pictureWidth: 1080, pictureHeight: 607.5 }
const near = (a: number, b: number, eps = 0.001) => assert.ok(Math.abs(a - b) <= eps, `${a} is not ${b}`)

test('a pinch around the centre grows the picture in place', () => {
    const z = pinch(NONE, 1.5, 960, 540, wide)
    near(z.scale, 1.5)
    near(z.panX, 0)
    near(z.panY, 0)
    assert.equal(percent(z), 150)
    assert.ok(zoomed(z))
})

test('the point under the fingers stays under them', () => {
    const z = pinch(NONE, 2, 480, 270, wide)
    near(z.panX, 480)
    near(z.panY, 270)
    near(z.panX + z.scale * -480, -480)
})

test('scale is held between 100 and 400 per cent', () => {
    near(pinch(NONE, 10, 960, 540, wide).scale, 4)
    near(pinch(NONE, 0.2, 960, 540, wide).scale, 1)
    assert.ok(!zoomed(pinch(NONE, 0.2, 960, 540, wide)))
})

test("the picture's edge never comes inside the screen's edge", () => {
    const z = panBy({ scale: 2, panX: 0, panY: 0 }, 5000, -5000, wide)
    near(z.panX, 960)
    near(z.panY, -540)
})

test('a side narrower than the screen stays centred', () => {
    const z = panBy(pinch(NONE, 1.5, 100, 300, upright), 0, 400, upright)
    near(z.panY, 0)
    assert.ok(z.panX > 0)
    assert.ok(z.panX <= (1.5 * 1080 - 1080) / 2 + 0.001)
})

test('a drag at 100 per cent moves nothing', () => {
    assert.deepEqual(panBy(NONE, 300, 300, wide), NONE)
})

test('just over 100 per cent settles on 100', () => {
    assert.deepEqual(settled({ scale: 1.04, panX: 10, panY: 5 }, wide), NONE)
    near(settled({ scale: 1.06, panX: 0, panY: 0 }, wide).scale, 1.06)
})

test('turning the phone keeps the scale and pulls the pan back inside', () => {
    const z = clampedTo({ scale: 2, panX: 900, panY: 500 }, wide)
    const turned = clampedTo(z, upright)
    near(turned.scale, 2)
    near(turned.panX, 540)
    near(turned.panY, 0)
})

test('nothing to measure is nothing to zoom', () => {
    const empty: Frame = { viewWidth: 0, viewHeight: 1080, pictureWidth: 1920, pictureHeight: 1080 }
    assert.deepEqual(clampedTo({ scale: 2, panX: 100, panY: 100 }, empty), NONE)
    assert.deepEqual(pinch(NONE, 2, 0, 0, empty), NONE)
    const nan: Frame = { viewWidth: NaN, viewHeight: 1080, pictureWidth: 1920, pictureHeight: 1080 }
    assert.deepEqual(clampedTo({ scale: 2, panX: 0, panY: 0 }, nan), NONE)
    assert.deepEqual(pinch(NONE, NaN, 960, 540, wide), NONE)
    assert.deepEqual(pinch(NONE, -1, 960, 540, wide), NONE)
    assert.deepEqual(clampedTo({ scale: NaN, panX: NaN, panY: Infinity }, wide), NONE)
    assert.deepEqual(stepped(NONE, NaN, 960, 540, wide), NONE)
})

test('steps double the picture in four and settle on the way back', () => {
    let z = NONE
    for (let i = 0; i < 4; i++) z = stepped(z, 1, 960, 540, wide)
    near(z.scale, 2)
    for (let i = 0; i < 4; i++) z = stepped(z, -1, 960, 540, wide)
    assert.deepEqual(z, NONE)
    near(stepped(NONE, 1000, 960, 540, wide).scale, 4)
})

test("a mouse wheel's notch is one step, a trackpad's pinch a fraction, up zooms in", () => {
    assert.equal(wheelSteps(-100, 0), 1)
    assert.equal(wheelSteps(100, 0), -1)
    assert.equal(wheelSteps(-3, 1), 1)
    assert.equal(wheelSteps(-1, 2), 1)
    near(wheelSteps(-4, 0), 0.04)
    assert.equal(wheelSteps(-100000, 0), 8)
    assert.equal(wheelSteps(0, 0), 0)
    assert.equal(wheelSteps(NaN, 0), 0)
})

test('the transform moves then scales, and is empty at 100 per cent', () => {
    assert.equal(transformOf(NONE), '')
    assert.equal(transformOf({ scale: 2, panX: 480.123, panY: -270 }), 'translate(480.12px, -270px) scale(2)')
})
