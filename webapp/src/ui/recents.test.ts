import assert from 'node:assert/strict'
import { test } from 'node:test'
import { LIMIT, MAX_LENGTH, add, join, normalise, parse, remove } from './recents.ts'

test('a search goes to the front, ten kept', () => {
    let list: string[] = []
    for (let i = 0; i < 14; i++) list = add(list, `video ${i}`)
    assert.equal(list.length, LIMIT)
    assert.equal(list[0], 'video 13')
    assert.equal(list.at(-1), 'video 4')
})

test('the same words in another case or spacing are one search, moved to the front', () => {
    assert.deepEqual(add(['harbour', 'night'], '  NIGHT '), ['NIGHT', 'harbour'])
    assert.deepEqual(add(['a b', 'c'], 'A   B'), ['A B', 'c'])
})

test('a query typed a letter at a time is recorded as it settles', () => {
    assert.deepEqual(add(['sev'], 'seve'), ['seve'])
    assert.deepEqual(add(['severance'], 'sev'), ['severance'])
    assert.deepEqual(add(['harbour', 'severance'], 'sev'), ['sev', 'harbour', 'severance'])
})

test('blank queries are ignored and a pasted paragraph is one tidy line', () => {
    assert.deepEqual(add(['a'], '   '), ['a'])
    assert.equal(normalise('  one \n two\t three  '), 'one two three')
    assert.equal(normalise('x'.repeat(500)).length, MAX_LENGTH)
})

test('remove takes a search out by its words, any case', () => {
    assert.deepEqual(remove(['Harbour', 'night'], '  harbour'), ['night'])
    assert.deepEqual(remove(['a'], 'b'), ['a'])
})

test('garbage never throws: not JSON, not a list, not text, nothing', () => {
    for (const bad of [undefined, null, 5, true, {}, 'not json', '{"a":1}', '[', '"x"', [null, 1, {}, [], true]]) assert.deepEqual(parse(bad), [], String(bad))
    assert.deepEqual(parse('["ok", 5, "OK", "  ", "two  words"]'), ['ok', 'two words'])
})

test('ten thousand stored entries read as ten, quickly', () => {
    const huge = Array.from({ length: 10_000 }, (_, i) => `search ${i}`)
    const t0 = Date.now()
    assert.equal(parse(huge).length, LIMIT)
    assert.equal(parse(JSON.stringify(huge)).length, LIMIT)
    assert.ok(Date.now() - t0 < 500)
    assert.equal(parse(['y'.repeat(100_000)])[0]!.length, MAX_LENGTH)
})

test('a backup joins behind this device’s own searches', () => {
    assert.deepEqual(join(['new', 'shared'], ['old', 'SHARED', 'older']), ['new', 'shared', 'old', 'older'])
    assert.equal(join([], Array.from({ length: 50 }, (_, i) => `s${i}`)).length, LIMIT)
})
