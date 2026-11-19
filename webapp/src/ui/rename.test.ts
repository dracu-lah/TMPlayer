// "Rename" on the web: the title cleaned wherever it comes from (a field, storage, the sync file),
// read from and written to a correction's lines, and what the sheet's Save does with the field.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { admit, cleanTitle, countOf, groupStep, RENAME_MAX, titleLine, titleOf, saveOf, typed } from '../rename.ts'

test('a title is trimmed to one line; nothing left, or not a string, is no rename', () => {
    assert.equal(cleanTitle('  Night Train (2019)  '), 'Night Train (2019)')
    assert.equal(cleanTitle('Night\nTrain\r\n(2019)\t'), 'Night Train (2019)')
    for (const v of ['', '   ', '\n\t', undefined, null, 5, {}, [], ['a'], true]) assert.equal(cleanTitle(v), null, String(v))
})

test('a title is cut at the apps\' 120 characters, never through an emoji, and a huge value is read only as far as it matters', () => {
    assert.equal(RENAME_MAX, 120)
    assert.equal(cleanTitle('a'.repeat(500)), 'a'.repeat(120))
    // The limit counts whole characters (code points): an emoji is one, and is never cut in half.
    assert.equal(cleanTitle(`${'a'.repeat(119)}😀`), `${'a'.repeat(119)}😀`)
    assert.equal(cleanTitle(`${'a'.repeat(119)}😀 tail`), `${'a'.repeat(119)}😀`)
    assert.equal(cleanTitle(`${'a'.repeat(120)}😀`), 'a'.repeat(120))
    assert.equal(cleanTitle('😀'.repeat(200)), '😀'.repeat(120), 'a title of emoji keeps all 120, not 60')
    assert.equal(cleanTitle('b'.repeat(5_000_000))?.length, 120)
    assert.equal(cleanTitle('😀'.repeat(100), 60), '😀'.repeat(60))
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
    assert.equal(typed(`${'a'.repeat(119)}😀😀`), `${'a'.repeat(119)}😀`)
    // The count the sheet shows is the cap's: sixty emoji are 60 of 120 and typing goes on.
    assert.equal(countOf('😀'.repeat(60)), 60)
    assert.equal(typed('😀'.repeat(60) + 'abc'), '😀'.repeat(60) + 'abc')
    assert.equal(countOf(typed('😀'.repeat(500))), 120)
    assert.equal(countOf('a😀b'), 3)
    // Not renamed yet: the shown title is the start, so the same again or nothing is no save.
    assert.equal(saveOf('Night Train', 'Night Train', undefined), null)
    assert.equal(saveOf('  Night Train ', 'Night Train', undefined), null)
    assert.equal(saveOf('Night Train 2', 'Night Train', undefined), 'save')
    assert.equal(saveOf('', 'Night Train', undefined), null, 'nothing to take off')
    assert.equal(saveOf('   ', '', undefined), null)
    // Renamed: a new title saves, the same one does not, an emptied field is "use the file name".
    assert.equal(saveOf('Mine', 'Night Train', 'Mine'), null)
    assert.equal(saveOf('Night Train', 'Night Train', 'Mine'), 'save', 'the file name typed is a rename to it')
    assert.equal(saveOf('Mine 2', 'Night Train', 'Mine'), 'save')
    assert.equal(saveOf('  ', 'Night Train', 'Mine'), 'reset')
})

test('a full store drops the oldest rename to take the new one; the same key set again is the newest', () => {
    let titles: Map<string, string> = new Map([['1:1', 'one'], ['1:2', 'two'], ['1:3', 'three']])
    let put = admit(titles, '1:4', 'four', 3)
    assert.deepEqual([...put.next], [['1:2', 'two'], ['1:3', 'three'], ['1:4', 'four']])
    assert.equal(put.evicted, '1:1')
    // Not full, or the key is already there: nothing goes, and the key moves to the newest place.
    put = admit(titles, '1:2', 'two again', 3)
    assert.equal(put.evicted, null)
    assert.deepEqual([...put.next.keys()], ['1:1', '1:3', '1:2'])
    assert.equal(admit(titles, '1:9', 'nine', 4).evicted, null)
    // A long run of new renames never grows the store past its cap, and the newest 5,000 are what is left.
    titles = new Map()
    for (let i = 0; i < 5_050; i++) titles = admit(titles, `1:${i}`, `t${i}`, 5_000).next
    assert.equal(titles.size, 5_000)
    assert.equal(titles.has('1:49'), false)
    assert.equal(titles.has('1:50'), true)
    assert.equal(titles.get('1:5049'), 't5049')
})

test('Save with the other copies not found asks first and never saves part of a group without the viewer knowing', () => {
    assert.equal(groupStep([], false), 'write', 'a video with no other copies')
    assert.equal(groupStep([{}, {}], false), 'write')
    assert.equal(groupStep(null, false), 'ask', 'the lookup failed: say so, offer Try again')
    assert.equal(groupStep(null, true), 'write', 'the second Save is the yes to this copy only')
})
