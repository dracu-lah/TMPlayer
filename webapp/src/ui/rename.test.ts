// "Rename" on the web: the title cleaned wherever it comes from (a field, storage, the sync file),
// read from and written to a correction's lines, and what the sheet's Save does with the field.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { changed, cleanTitle, RENAME_MAX, titleLine, titleOf, typed } from '../rename.ts'

test('a title is trimmed to one line; nothing left, or not a string, is no rename', () => {
    assert.equal(cleanTitle('  Night Train (2019)  '), 'Night Train (2019)')
    assert.equal(cleanTitle('Night\nTrain\r\n(2019)\t'), 'Night Train (2019)')
    for (const v of ['', '   ', '\n\t', undefined, null, 5, {}, [], ['a'], true]) assert.equal(cleanTitle(v), null, String(v))
})

test('a title is cut at the apps\' 120 characters, never through an emoji, and a huge value is read only as far as it matters', () => {
    assert.equal(RENAME_MAX, 120)
    assert.equal(cleanTitle('a'.repeat(500)), 'a'.repeat(120))
    // The emoji is two code units: at the limit it goes whole or not at all.
    assert.equal(cleanTitle(`${'a'.repeat(119)}😀`), 'a'.repeat(119))
    assert.equal(cleanTitle(`${'a'.repeat(118)}😀`), `${'a'.repeat(118)}😀`)
    assert.equal(cleanTitle(`${'a'.repeat(119)} tail`), 'a'.repeat(119))
    assert.equal(cleanTitle('b'.repeat(5_000_000))?.length, 120)
    assert.equal(cleanTitle('x'.repeat(60), 60), 'x'.repeat(60))
    assert.equal(cleanTitle('x'.repeat(61), 60)?.length, 60)
})

test('emoji and right to left text are kept; direction overrides and controls are not', () => {
    assert.equal(cleanTitle('فيلم الليل (2019)'), 'فيلم الليل (2019)')
    assert.equal(cleanTitle('夜行列車 🚆 2019'), '夜行列車 🚆 2019')
    assert.equal(cleanTitle('a‮b⁦c⁩d'), 'abcd')
    assert.equal(cleanTitle('a\u0000b\u007fc\u0085d e'), 'a b c d e')
    // Marks that shape a word (zero width joiners in an emoji sequence) stay.
    assert.equal(cleanTitle('👨‍👩‍👧'), '👨‍👩‍👧')
})

test('a rename is read from a correction\'s lines, whatever else is on them, and garbage reads as none', () => {
    assert.equal(titleOf('title=Night Train (2019)'), 'Night Train (2019)')
    assert.equal(titleOf('separate\nwrong\ntmdb=film:1\ntitle=Night Train'), 'Night Train')
    assert.equal(titleOf('separate\r\ntitle=Night Train\r\n'), 'Night Train')
    assert.equal(titleOf('title='), null)
    assert.equal(titleOf('title=   '), null)
    assert.equal(titleOf('separate'), null)
    assert.equal(titleOf('Title=Night Train'), null)
    assert.equal(titleOf(`title=${'z'.repeat(1_000)}`), 'z'.repeat(120))
    for (const v of [undefined, null, 7, {}, ['title=a']]) assert.equal(titleOf(v), null)
    assert.equal(titleOf(`${'x\n'.repeat(100_000)}title=late`), null, 'a value that long is not a correction')
    assert.equal(titleLine('A'), 'title=A')
})

test('the field keeps typing natural: cut at the limit, breaks as spaces, the ends untouched; Save needs a real change', () => {
    assert.equal(typed('abc '), 'abc ')
    assert.equal(typed('a\nb'), 'a b')
    assert.equal(typed('a'.repeat(300)).length, 120)
    assert.equal(typed(`${'a'.repeat(119)}😀`), 'a'.repeat(119))
    assert.equal(changed('Night Train', 'Night Train'), false)
    assert.equal(changed('  Night Train ', 'Night Train'), false)
    assert.equal(changed('Night Train 2', 'Night Train'), true)
    assert.equal(changed('', 'Night Train'), true, 'an empty field is "use the file name"')
    assert.equal(changed('   ', ''), false)
})
