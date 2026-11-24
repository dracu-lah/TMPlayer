// WP-61: the web player's key table (playerKeys.ts, in the page's bundle) and core's PlayerKeyMap
// (the table the tablets and Chromebooks read, built by `:core:webRules`) give the same answer for
// every key of a US keyboard, with every modifier and every state of the player. The page does not
// load core's 1.2 MB rules module for a key press, so the two are written twice and this test, over
// about 20,000 presses, is what keeps them one table.
// `pnpm rules` first (:core:webRules), then `node --test src/player/playerKeyParity.test.ts`.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import * as core from '../rules/core/tmplayer-rules.mjs'
import { actionFor, type Action } from './playerKeys.ts'

interface Plain {
    kind: string
    value: number
    flag: boolean
}

/** The web's Action in core's three fields. */
function plain(a: Action | null): Plain | null {
    if (!a) return null
    const none = { value: 0, flag: false }
    switch (a.do) {
        case 'seek': return { kind: 'seek', value: a.by, flag: false }
        case 'chapter': return { kind: 'chapter', value: 0, flag: a.forward }
        case 'volume': return { kind: 'volume', value: Math.round(a.by * 100), flag: false }
        case 'episode': return { kind: 'episode', value: 0, flag: a.next }
        case 'speed': return { kind: 'speed', value: 0, flag: a.up }
        case 'frame': return { kind: 'frame', value: 0, flag: a.forward }
        case 'subtitles': return { kind: 'subtitles', value: 0, flag: a.back }
        case 'audio': return { kind: 'audio', value: 0, flag: a.back }
        case 'subtitleDelay': return { kind: 'subtitleDelay', value: Math.round(a.by * 10), flag: false }
        case 'subtitleSize': return { kind: 'subtitleSize', value: a.by, flag: false }
        case 'zoom': return { kind: 'zoom', value: a.steps, flag: false }
        case 'screenshot': return { kind: 'screenshot', value: 0, flag: a.subtitles }
        case 'jump': return { kind: 'jump', value: a.tenth, flag: false }
        default: return { kind: a.do, ...none }
    }
}

// A US keyboard: the code, and what it types plain and with Shift.
const US: [code: string, plain: string, shifted: string][] = [
    ...'abcdefghijklmnopqrstuvwxyz'.split('').map((c): [string, string, string] => [`Key${c.toUpperCase()}`, c, c.toUpperCase()]),
    ...'0123456789'.split('').map((c): [string, string, string] => [`Digit${c}`, c, ')!@#$%^&*('[Number(c)]!]),
    ['Minus', '-', '_'], ['Equal', '=', '+'], ['BracketLeft', '[', '{'], ['BracketRight', ']', '}'], ['Comma', ',', '<'], ['Period', '.', '>'], ['Slash', '/', '?'],
    ['Backquote', '`', '~'], ['Semicolon', ';', ':'], ['Quote', "'", '"'], ['Backslash', '\\', '|'],
    ['Space', ' ', ' '], ['Enter', 'Enter', 'Enter'], ['Escape', 'Escape', 'Escape'], ['Backspace', 'Backspace', 'Backspace'], ['Tab', 'Tab', 'Tab'],
    ['ArrowLeft', 'ArrowLeft', 'ArrowLeft'], ['ArrowRight', 'ArrowRight', 'ArrowRight'], ['ArrowUp', 'ArrowUp', 'ArrowUp'], ['ArrowDown', 'ArrowDown', 'ArrowDown'],
    ['PageUp', 'PageUp', 'PageUp'], ['PageDown', 'PageDown', 'PageDown'], ['Home', 'Home', 'Home'], ['End', 'End', 'End'],
    ['Numpad0', '0', '0'], ['Numpad5', '5', '5'], ['NumpadAdd', '+', '+'], ['NumpadSubtract', '-', '-'],
    ['F1', 'F1', 'F1'], ['F5', 'F5', 'F5'], ['F11', 'F11', 'F11'],
]

const ask = (code: string, key: string, m: { shift: boolean; ctrl: boolean; alt: boolean; meta: boolean }, c: { mac: boolean; chapters: boolean; paused: boolean; speedNormal: boolean; fullscreen: boolean; skipShown: boolean }) => {
    const web = plain(actionFor({ key, code, shift: m.shift, ctrl: m.ctrl, alt: m.alt, meta: m.meta }, c))
    const a = core.playerKeyAction(code, key, m.shift, m.ctrl, m.alt, m.meta, c.mac, c.chapters, c.paused, c.speedNormal, c.fullscreen, c.skipShown)
    return { web, core: a == null ? null : { kind: a.kind, value: a.value, flag: a.flag } }
}

test('every US key, with every modifier and player state, means the same in both tables', () => {
    const bad: string[] = []
    let n = 0
    for (const [code, lower, upper] of US) {
        for (let mods = 0; mods < 16; mods++) {
            const m = { shift: !!(mods & 1), ctrl: !!(mods & 2), alt: !!(mods & 4), meta: !!(mods & 8) }
            // What the key types: Shift gives the shifted character, and Alt on a Mac types another one, which the table must not read.
            const key = m.shift ? upper : lower
            for (let state = 0; state < 32; state++) {
                const c = { mac: !!(state & 1), chapters: !!(state & 2), paused: !!(state & 4), speedNormal: !!(state & 8), fullscreen: !!(state & 16), skipShown: false }
                n++
                const r = ask(code, key, m, c)
                if (JSON.stringify(r.web) !== JSON.stringify(r.core)) bad.push(`${code} ${JSON.stringify(m)} ${JSON.stringify(c)}: web ${JSON.stringify(r.web)}, core ${JSON.stringify(r.core)}`)
            }
        }
    }
    assert.ok(n > 15_000, `${n} presses`)
    assert.deepEqual([...new Set(bad.map((b) => b.split(' {')[0] + ' ' + b.split('}')[0].split(' {')[1]))].slice(0, 40), [], `${bad.length} of ${n} differ`)
})

test('the skip key reads the skip offer the same way', () => {
    for (const skipShown of [false, true]) {
        const c = { mac: false, chapters: false, paused: false, speedNormal: true, fullscreen: false, skipShown }
        const r = ask('KeyG', 'g', { shift: false, ctrl: false, alt: false, meta: false }, c)
        assert.deepEqual(r.web, r.core)
        assert.equal(r.web?.kind ?? null, skipShown ? 'skip' : null)
    }
})
