// WP-28: the player's keyboard table of core (PlayerKeyMap), as `:core:webRules` exports it, read from
// the web: the same table the Android tablets and Chromebooks use, so no page ports it by hand and
// no Kotlin and TypeScript parity test is needed. WP-61 moves playerKeys.ts onto it.
// `pnpm rules` first (:core:webRules), then `node --test src/player/playerKeyMap.test.ts`.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import * as core from '../rules/core/tmplayer-rules.mjs'

interface Ctx {
    mac?: boolean
    chapters?: boolean
    paused?: boolean
    speedNormal?: boolean
    fullscreen?: boolean
    skipShown?: boolean
}
interface Press {
    code: string
    key?: string
    shift?: boolean
    ctrl?: boolean
    alt?: boolean
    meta?: boolean
}

const act = (p: Press, c: Ctx = {}) => {
    const a = core.playerKeyAction(
        p.code,
        p.key ?? '',
        !!p.shift,
        !!p.ctrl,
        !!p.alt,
        !!p.meta,
        !!c.mac,
        !!c.chapters,
        !!c.paused,
        c.speedNormal ?? true,
        !!c.fullscreen,
        !!c.skipShown,
    )
    return a == null ? null : { kind: a.kind, value: a.value, flag: a.flag }
}

test('the seek ladder and the chapter keys come out of core as plain objects', () => {
    assert.deepEqual(act({ code: 'ArrowRight' }), { kind: 'seek', value: 5, flag: false })
    assert.deepEqual(act({ code: 'ArrowLeft', shift: true }), { kind: 'seek', value: -60, flag: false })
    assert.deepEqual(act({ code: 'KeyJ' }), { kind: 'seek', value: -10, flag: false })
    assert.deepEqual(act({ code: 'ArrowRight', ctrl: true }, { chapters: true }), { kind: 'chapter', value: 0, flag: true })
    assert.deepEqual(act({ code: 'ArrowLeft', meta: true }, { mac: true }), { kind: 'seek', value: -60, flag: false })
})

test('letters are read by physical key, digits and the question mark by what they typed', () => {
    assert.deepEqual(act({ code: 'KeyM', key: 'm' }), { kind: 'mute', value: 0, flag: false })
    assert.deepEqual(act({ code: 'BracketRight' }), { kind: 'speed', value: 0, flag: true })
    assert.deepEqual(act({ code: 'BracketLeft' }), { kind: 'speed', value: 0, flag: false })
    assert.deepEqual(act({ code: 'Digit7', key: '7' }), { kind: 'jump', value: 7, flag: false })
    assert.deepEqual(act({ code: 'Slash', key: '?', shift: true }), { kind: 'sheet', value: 0, flag: false })
    assert.equal(act({ code: 'Slash', key: '/' }), null)
})

test('the player state decides the ambiguous keys, and other chords are the browser\'s', () => {
    assert.equal(act({ code: 'Comma' }), null)
    assert.deepEqual(act({ code: 'Comma' }, { paused: true }), { kind: 'frame', value: 0, flag: false })
    assert.deepEqual(act({ code: 'Backspace' }), { kind: 'back', value: 0, flag: false })
    assert.deepEqual(act({ code: 'Backspace' }, { speedNormal: false }), { kind: 'speedReset', value: 0, flag: false })
    assert.deepEqual(act({ code: 'Escape' }, { fullscreen: true }), { kind: 'exitFullscreen', value: 0, flag: false })
    assert.equal(act({ code: 'KeyG' }), null)
    assert.deepEqual(act({ code: 'KeyG' }, { skipShown: true }), { kind: 'skip', value: 0, flag: false })
    for (const code of ['KeyC', 'KeyV', 'KeyX', 'KeyA', 'KeyF']) assert.equal(act({ code, ctrl: true }), null, code)
    assert.equal(act({ code: 'F5' }), null)
})
