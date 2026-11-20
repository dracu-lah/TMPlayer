import assert from 'node:assert/strict'
import { test } from 'node:test'
import { gridOwns, gridTarget, installShellKeys, isMac, isTextField, rowOf, shellAction, type KeyLike, type Row } from './shellKeys.ts'

const press = (key: string, extra: Partial<KeyLike> = {}): KeyLike => ({ key, ctrlKey: false, metaKey: false, altKey: false, shiftKey: false, ...extra })
const idle = { mac: false, typing: false }

test('the window chords: Ctrl on a PC, Command on a Mac, never both', () => {
    const table: [KeyLike, boolean, string | null][] = [
        [press('k', { ctrlKey: true }), false, 'search'],
        [press('K', { ctrlKey: true }), false, 'search'],
        [press('k', { metaKey: true }), true, 'search'],
        [press('k', { ctrlKey: true }), true, null],
        [press('k', { metaKey: true }), false, null],
        [press('k', { ctrlKey: true, metaKey: true }), false, null],
        [press('f', { ctrlKey: true }), false, 'findInPage'],
        [press('j', { ctrlKey: true }), false, 'downloads'],
        [press('A', { ctrlKey: true, shiftKey: true }), false, 'chooseChats'],
        [press('a', { ctrlKey: true }), false, null],
        [press('k', { ctrlKey: true, altKey: true }), false, null],
        [press('/'), false, 'findInPage'],
        [press('?', { shiftKey: true }), false, null],
        [press('ContextMenu'), false, 'cardMenu'],
        [press('F10', { shiftKey: true }), false, 'cardMenu'],
        [press('F10'), false, null],
    ]
    for (const [e, mac, want] of table) assert.equal(shellAction(e, { mac, typing: false }), want, `${JSON.stringify(e)} mac=${mac}`)
})

test('Command or Ctrl and a digit are the browser tabs\' in a tab, Home and Chats only in an installed app', () => {
    const tab = { mac: false, typing: false }
    const app = { mac: false, typing: false, standalone: true }
    assert.equal(shellAction(press('1', { ctrlKey: true, code: 'Digit1' }), tab), null)
    assert.equal(shellAction(press('2', { ctrlKey: true, code: 'Digit2' }), tab), null)
    assert.equal(shellAction(press('1', { ctrlKey: true, code: 'Digit1' }), { ...tab, standalone: false }), null)
    assert.equal(shellAction(press('1', { ctrlKey: true, code: 'Digit1' }), app), 'home')
    assert.equal(shellAction(press('2', { ctrlKey: true, code: 'Digit2' }), app), 'chats')
    assert.equal(shellAction(press('!', { ctrlKey: true, code: 'Digit1' }), app), 'home')
    assert.equal(shellAction(press('3', { ctrlKey: true, code: 'Digit3' }), app), null)
})

test('letter chords follow the key place on a layout without Latin letters, and the letter on one with', () => {
    assert.equal(shellAction(press('\u043b', { ctrlKey: true, code: 'KeyK' }), idle), 'search')
    assert.equal(shellAction(press('\u03ba', { ctrlKey: true, code: 'KeyK' }), idle), 'search')
    assert.equal(shellAction(press('\u043e', { ctrlKey: true, code: 'KeyJ' }), idle), 'downloads')
    assert.equal(shellAction(press('\u0430', { ctrlKey: true, code: 'KeyF' }), idle), 'findInPage')
    assert.equal(shellAction(press('\u0444', { ctrlKey: true, shiftKey: true, code: 'KeyA' }), idle), 'chooseChats')
    // A Dvorak keyboard's K is on another key: the letter wins where it is Latin.
    assert.equal(shellAction(press('k', { ctrlKey: true, code: 'KeyV' }), idle), 'search')
    assert.equal(shellAction(press('\u043b', { ctrlKey: true, code: 'KeyW' }), idle), null)
})

test('/ is found where it needs Shift, and still not typed over in a field', () => {
    assert.equal(shellAction(press('/', { shiftKey: true, code: 'Digit7' }), idle), 'findInPage')
    assert.equal(shellAction(press('/', { code: 'Slash' }), idle), 'findInPage')
    assert.equal(shellAction(press('?', { shiftKey: true, code: 'Slash' }), idle), null)
    assert.equal(shellAction(press('/', { shiftKey: true, code: 'Digit7' }), { mac: false, typing: true }), null)
})

test('Home, End and the Page keys are the grid\'s even at an edge; arrows are not', () => {
    for (const key of ['Home', 'End', 'PageUp', 'PageDown']) assert.equal(gridOwns(key, {}), true, key)
    assert.equal(gridOwns('Home', { ctrl: true }), true)
    assert.equal(gridOwns('End', { ctrl: true }), true)
    assert.equal(gridOwns('PageDown', { ctrl: true }), false)
    for (const key of ['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Enter']) assert.equal(gridOwns(key, {}), false, key)
    assert.equal(gridOwns('Home', { shift: true }), false)
    assert.equal(gridOwns('End', { alt: true }), false)
    // The edge itself: nowhere to go, so the target is null while the key is still owned.
    assert.equal(gridTarget('Home', { ctrl: true }, 0, [[0, 3], [3, 6]], 1), null)
    assert.equal(gridOwns('Home', { ctrl: true }), true)
})

test('a held chord acts once: the repeats are spent without a second page change', () => {
    const listeners: ((e: unknown) => void)[] = []
    const g = globalThis as Record<string, unknown>
    const saved = { window: g.window, document: g.document }
    g.window = { addEventListener: (_: string, fn: (e: unknown) => void) => listeners.push(fn), removeEventListener: () => {}, matchMedia: () => ({ matches: false }) }
    g.document = { querySelector: () => null, activeElement: null }
    const gone: string[] = []
    let path = '/chats'
    const off = installShellKeys({ enabled: () => true, path: () => path, go: (p) => gone.push(`go ${(path = p)}`), back: () => gone.push(`back ${(path = '/chats')}`), searchLabel: () => 'Search', mac: false, listen: (fn) => (listeners.push(fn as (e: unknown) => void), () => {}) })
    const fire = (repeat: boolean) => {
        let prevented = false
        listeners[0]({ ...press('k', { ctrlKey: true, code: 'KeyK' }), repeat, defaultPrevented: false, target: { tagName: 'BODY' }, preventDefault: () => (prevented = true) })
        return prevented
    }
    try {
        assert.equal(fire(false), true)
        assert.equal(fire(true), true)
        assert.equal(fire(true), true)
        assert.deepEqual(gone, ['go /search'])
    } finally {
        off()
        Object.assign(g, saved)
    }
})

test('the keys the browser keeps are never taken', () => {
    for (const key of ['w', 't', 'n', 'q', 'r', 'l', 'p', 's', 'c', 'v', 'x', 'z', 'y', 'a', ',', 'h', 'd']) {
        assert.equal(shellAction(press(key, { ctrlKey: true }), idle), null, `Ctrl+${key}`)
        assert.equal(shellAction(press(key, { metaKey: true }), { mac: true, typing: false }), null, `Cmd+${key}`)
        if (key !== 'a') assert.equal(shellAction(press(key, { ctrlKey: true, shiftKey: true }), idle), null, `Ctrl+Shift+${key}`)
    }
    assert.equal(shellAction(press('F5'), idle), null)
    assert.equal(shellAction(press('F11'), idle), null)
})

test('a text field keeps its plain keys, and gives the chords', () => {
    const typing = { mac: false, typing: true }
    assert.equal(shellAction(press('/'), typing), null)
    assert.equal(shellAction(press('ContextMenu'), typing), null)
    assert.equal(shellAction(press('F10', { shiftKey: true }), typing), null)
    assert.equal(shellAction(press('k', { ctrlKey: true }), typing), 'search')
    assert.equal(shellAction(press('j', { ctrlKey: true }), typing), 'downloads')
})

test('an input method composing is left alone', () => {
    assert.equal(shellAction(press('k', { ctrlKey: true, isComposing: true }), idle), null)
    assert.equal(shellAction(press('Process'), idle), null)
})

test('which fields are text', () => {
    assert.equal(isTextField(null), false)
    assert.equal(isTextField({ tagName: 'INPUT', type: 'search' }), true)
    assert.equal(isTextField({ tagName: 'INPUT', type: 'text' }), true)
    assert.equal(isTextField({ tagName: 'INPUT' }), true)
    assert.equal(isTextField({ tagName: 'INPUT', type: 'checkbox' }), false)
    assert.equal(isTextField({ tagName: 'TEXTAREA' }), true)
    assert.equal(isTextField({ tagName: 'DIV', isContentEditable: true }), true)
    assert.equal(isTextField({ tagName: 'A' }), false)
    assert.equal(isTextField({ tagName: 'BUTTON' }), false)
})

test('Mac is read from the platform', () => {
    assert.equal(isMac('MacIntel'), true)
    assert.equal(isMac('iPhone'), true)
    assert.equal(isMac('Linux x86_64'), false)
    assert.equal(isMac('Win32'), false)
    assert.equal(isMac(''), false)
})

// 10 items, 3 columns: rows 0-3, 3-6, 6-9, 9-10 (a short last row).
const rows: Row[] = [[0, 3], [3, 6], [6, 9], [9, 10]]
const go = (key: string, at: number, mods = {}, page = 2) => gridTarget(key, mods, at, rows, page)

test('the arrows keep their column and never wrap', () => {
    assert.equal(go('ArrowRight', 0), 1)
    assert.equal(go('ArrowRight', 2), 3)
    assert.equal(go('ArrowRight', 9), null)
    assert.equal(go('ArrowLeft', 3), 2)
    assert.equal(go('ArrowLeft', 0), null)
    assert.equal(go('ArrowDown', 1), 4)
    assert.equal(go('ArrowDown', 7), 9)
    assert.equal(go('ArrowDown', 9), null)
    assert.equal(go('ArrowUp', 4), 1)
    assert.equal(go('ArrowUp', 9), 6)
    assert.equal(go('ArrowUp', 1), null)
})

test('Home and End go to the row ends, with Ctrl to the grid ends', () => {
    assert.equal(go('Home', 4), 3)
    assert.equal(go('End', 4), 5)
    assert.equal(go('Home', 3), null)
    assert.equal(go('End', 9), null)
    assert.equal(go('Home', 4, { ctrl: true }), 0)
    assert.equal(go('End', 4, { ctrl: true }), 9)
    assert.equal(go('Home', 0, { ctrl: true }), null)
})

test('Page keys jump rows and stop at the last and first', () => {
    assert.equal(go('PageDown', 1), 7)
    assert.equal(go('PageDown', 7), 9)
    assert.equal(go('PageDown', 9), null)
    assert.equal(go('PageUp', 9), 3)
    assert.equal(go('PageUp', 7), 1)
    assert.equal(go('PageUp', 1), null)
    // A page of zero or less rows still moves one.
    assert.equal(go('PageDown', 0, {}, 0), 3)
})

test("chords with Alt, Cmd or Shift, a Ctrl arrow and other keys are not the grid's", () => {
    for (const mods of [{ alt: true }, { meta: true }, { shift: true }]) assert.equal(go('ArrowRight', 0, mods), null)
    assert.equal(go('ArrowRight', 0, { ctrl: true }), null)
    assert.equal(go('PageDown', 0, { ctrl: true }), null)
    assert.equal(go('Enter', 0), null)
    assert.equal(go('a', 0), null)
})

test('a wide item has a row of its own: the column clamps to it', () => {
    const wide: Row[] = [[0, 3], [3, 4], [4, 7]]
    assert.equal(gridTarget('ArrowDown', {}, 2, wide, 1), 3)
    assert.equal(gridTarget('ArrowDown', {}, 3, wide, 1), 4)
    assert.equal(gridTarget('ArrowUp', {}, 6, wide, 1), 3)
    assert.equal(gridTarget('ArrowUp', {}, 2, wide, 1), null)
})

test('bad places and empty grids answer null', () => {
    assert.equal(gridTarget('ArrowRight', {}, -1, rows, 1), null)
    assert.equal(gridTarget('ArrowRight', {}, 99, rows, 1), null)
    assert.equal(gridTarget('ArrowRight', {}, 0, [], 1), null)
    assert.equal(gridTarget('ArrowDown', {}, Number.NaN, rows, 1), null)
    assert.equal(rowOf([], 0), -1)
    assert.equal(rowOf(rows, 10), -1)
})

test('a grid of 100,000 items answers at once', () => {
    const big: Row[] = Array.from({ length: 33334 }, (_, r) => [r * 3, Math.min(r * 3 + 3, 100_000)] as Row)
    const started = performance.now()
    for (let i = 0; i < 2000; i++) assert.equal(gridTarget('ArrowDown', {}, 3 * i + 1, big, 5), 3 * i + 4)
    assert.ok(performance.now() - started < 500)
    assert.equal(gridTarget('End', { ctrl: true }, 5, big, 5), 99_999)
})
