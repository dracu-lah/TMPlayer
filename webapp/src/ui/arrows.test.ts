// Arrow keys in a tab list or list box. `node --test src/ui/arrows.test.ts`
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { stepTo } from './arrows.ts'

test('Right and Left walk a row and wrap at the ends', () => {
    assert.equal(stepTo('ArrowRight', 0, 3), 1)
    assert.equal(stepTo('ArrowRight', 2, 3), 0)
    assert.equal(stepTo('ArrowLeft', 0, 3), 2)
    assert.equal(stepTo('ArrowLeft', 2, 3), 1)
})

test('Right and Left swap in a right to left language', () => {
    assert.equal(stepTo('ArrowRight', 1, 3, { rtl: true }), 0)
    assert.equal(stepTo('ArrowLeft', 1, 3, { rtl: true }), 2)
})

test('a vertical group walks on Up and Down and ignores Left and Right', () => {
    assert.equal(stepTo('ArrowDown', 0, 3, { vertical: true }), 1)
    assert.equal(stepTo('ArrowUp', 0, 3, { vertical: true }), 2)
    assert.equal(stepTo('ArrowRight', 0, 3, { vertical: true }), null)
})

test('Home and End go to the ends, other keys and an empty group are left alone', () => {
    assert.equal(stepTo('Home', 2, 5), 0)
    assert.equal(stepTo('End', 0, 5), 4)
    assert.equal(stepTo('a', 0, 5), null)
    assert.equal(stepTo('ArrowRight', 0, 0), null)
    assert.equal(stepTo('Home', 0, 0), null)
})

test('a single entry stays where it is', () => {
    assert.equal(stepTo('ArrowRight', 0, 1), 0)
    assert.equal(stepTo('ArrowLeft', 0, 1), 0)
})
