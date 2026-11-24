// The account picture's chain on the web bar (WP-103). `node --test src/ui/accountPicture.test.ts`
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { accountName, accountPicture, firstName } from './accountPicture.ts'

test('a saved photo wins over the letter', () => {
    assert.deepEqual(accountPicture('data:image/jpeg;base64,AAAA', 'Asha Rao'), { kind: 'photo', src: 'data:image/jpeg;base64,AAAA' })
})

test('no photo: the first letter of the name', () => {
    assert.deepEqual(accountPicture(null, 'Asha Rao'), { kind: 'initial', letter: 'A' })
    assert.deepEqual(accountPicture('', '  (bob)'), { kind: 'initial', letter: 'B' })
})

test('nothing known: the glyph, never an empty disc', () => {
    assert.deepEqual(accountPicture(null, ''), { kind: 'glyph' })
    assert.deepEqual(accountPicture(undefined, undefined), { kind: 'glyph' })
    // A name with no letter or digit has nothing to draw either.
    assert.deepEqual(accountPicture(null, '★★'), { kind: 'glyph' })
})

test("Telegram's name first, then the profile's, trimmed", () => {
    assert.equal(accountName(' Asha ', 'Old'), 'Asha')
    assert.equal(accountName('', 'Profile name'), 'Profile name')
    assert.equal(accountName(null, '  '), '')
    assert.equal(accountName(undefined, undefined), '')
})

test('the caption is the first name, whole when it has no space', () => {
    assert.equal(firstName('Asha Rao'), 'Asha')
    assert.equal(firstName('  Asha\tRao '), 'Asha')
    assert.equal(firstName('Madonna'), 'Madonna')
    assert.equal(firstName('x'.repeat(64)), 'x'.repeat(64))
    assert.equal(firstName(''), '')
})
