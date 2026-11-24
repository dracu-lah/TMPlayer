import assert from 'node:assert/strict'
import { test } from 'node:test'
import { clipName, OPTIONS_NAME_MAX } from './optionsName.ts'

test('a short name is left alone, and one of exactly the limit too', () => {
    assert.equal(clipName('Clip 20'), 'Clip 20')
    assert.equal(clipName('a'.repeat(OPTIONS_NAME_MAX)), 'a'.repeat(OPTIONS_NAME_MAX))
    assert.equal(clipName(''), '')
})

test('a long name is cut at 24 characters with an ellipsis', () => {
    const out = clipName('Averylongvideonamethatgoesonandon')
    assert.equal([...out].length, OPTIONS_NAME_MAX)
    assert.ok(out.endsWith('\u2026'))
})

test('a cut never leaves a space before the ellipsis, and never splits a character made of two code units', () => {
    assert.equal(clipName('x'.repeat(22) + ' abcdef'), 'x'.repeat(22) + '\u2026')
    const emoji = clipName('\u{1F3AC}'.repeat(40))
    assert.equal([...emoji].length, OPTIONS_NAME_MAX)
    assert.ok(!/[\uD800-\uDBFF]$/.test(emoji.slice(0, -1)))
})
