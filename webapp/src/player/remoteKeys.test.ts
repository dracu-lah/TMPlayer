import assert from 'node:assert/strict'
import { test } from 'node:test'
import { oncePerPress, remoteKey } from '../tv/remote.ts'
import { readFileSync } from 'node:fs'

test('Stop, Info, CC, Next and Previous act once per press, their repeats dropped (PB2-10)', () => {
    for (const k of ['stop', 'info', 'captions', 'next', 'previous'] as const) assert.equal(oncePerPress(k), true, k)
    // The seek keys ramp on their repeats; play and pause have their own rule.
    for (const k of ['left', 'right', 'forward', 'rewind', 'up', 'down', 'ok', 'back', 'playpause'] as const) assert.equal(oncePerPress(k), false, k)
    const src = readFileSync(new URL('./chrome/useKeys.ts', import.meta.url), 'utf8')
    const tv = src.slice(src.indexOf('function tvKey('), src.indexOf("case 'play':"))
    assert.match(tv, /if \(e\.repeat && oncePerPress\(key\)\) return true/)
})

const key = (init: { key?: string; keyCode?: number }) => remoteKey({ key: init.key ?? '', keyCode: init.keyCode ?? 0, which: init.keyCode ?? 0 } as KeyboardEvent)

test('webOS keys by code: Info 457 and CC 460, as Stop 413 leaves', () => {
    assert.equal(key({ key: 'Unidentified', keyCode: 457 }), 'info')
    assert.equal(key({ key: 'Unidentified', keyCode: 460 }), 'captions')
    assert.equal(key({ key: 'Unidentified', keyCode: 413 }), 'stop')
    assert.equal(key({ key: 'MediaStop' }), 'stop')
})

test('the arrows, OK and Back keep their meaning', () => {
    assert.equal(key({ key: 'ArrowLeft' }), 'left')
    assert.equal(key({ key: 'Unidentified', keyCode: 37 }), 'left')
    assert.equal(key({ key: 'Enter' }), 'ok')
    assert.equal(key({ key: 'Unidentified', keyCode: 461 }), 'back')
    assert.equal(key({ key: 'a' }), null)
})

test('the TV layout keeps the controls up longer than a phone or a desktop', () => {
    // parts.ts draws icons (a page's code), so its two numbers are read from the source.
    const parts = readFileSync(new URL('./chrome/parts.ts', import.meta.url), 'utf8')
    const ms = (name: string) => Number(new RegExp(`export const ${name} = (\\d+)`).exec(parts)?.[1])
    assert.equal(ms('HIDE_TV_MS'), 5000)
    assert.ok(ms('HIDE_TV_MS') > ms('HIDE_MS'))
    const chrome = readFileSync(new URL('./Chrome.tsx', import.meta.url), 'utf8')
    assert.match(chrome, /tv\(\) \? HIDE_TV_MS : HIDE_MS/)
})
