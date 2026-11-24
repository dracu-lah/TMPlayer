// Where a toast stands (ui/toastPlace.ts): the clearance is measured from the highest bar or pill.
// `node --test src/ui/toastPlace.test.ts`
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { clearanceOf, TOAST_GAP } from './toastPlace.ts'

const tops = (m: Record<string, number | null>) => (selector: string) => m[selector] ?? null

test('the toast stands over the bar: the clearance is the window height minus the bar top', () => {
    assert.equal(clearanceOf(tops({ '#main-nav .nav-cells': 750 }), 844), 94)
    assert.equal(TOAST_GAP, 12)
})

test('the highest of the bar and the pill wins', () => {
    assert.equal(clearanceOf(tops({ '#main-nav .nav-cells': 750, '.work-status.pill .work-status-line': 700 }), 844), 144)
    assert.equal(clearanceOf(tops({ 'nav.chat-bar .nav-cells': 760 }), 844), 84)
})

test('nothing drawn, garbage or a bar below the window leaves the CSS fallback', () => {
    assert.equal(clearanceOf(tops({}), 844), null)
    assert.equal(clearanceOf(tops({ '#main-nav .nav-cells': Number.NaN }), 844), null)
    assert.equal(clearanceOf(tops({ '#main-nav .nav-cells': 900 }), 844), null)
    assert.equal(clearanceOf(tops({ '#main-nav .nav-cells': 700 }), 0), null)
    assert.equal(clearanceOf(tops({ '#main-nav .nav-cells': 700 }), Number.NaN), null)
    assert.equal(clearanceOf(tops({ '#main-nav .nav-cells': -50 }), 844), null)
})
