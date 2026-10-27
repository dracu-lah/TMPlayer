// The avatar letter rule, the web twin of core's NameInitialTest. `node --test src/ui/initial.test.ts`
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { initialOf } from './initial.ts'

test('the first letter or digit, past punctuation, symbols and emoji', () => {
    assert.equal(initialOf('(YEDHU)'), 'Y')
    assert.equal(initialOf('  movies hub'), 'M')
    assert.equal(initialOf('★ Films'), 'F')
    assert.equal(initialOf('🎬🍿 cinema'), 'C')
    assert.equal(initialOf('[4K] Club'), '4')
    assert.equal(initialOf('മോഹൻ'), 'മോ')
    assert.equal(initialOf('école'), 'É')
})

test('no letter at all gives none', () => {
    assert.equal(initialOf('🎬🍿'), '')
    assert.equal(initialOf('***'), '')
    assert.equal(initialOf(''), '')
    assert.equal(initialOf(null), '')
    assert.equal(initialOf(undefined), '')
})
