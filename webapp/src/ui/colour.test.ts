// The avatar colour rule, the web twin of core's NameColourTest. `node --test src/ui/colour.test.ts`
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { nameArt, pairIndexOf, pairOf, PAIRS } from './colour.ts'

test('the same name always wears the same pair, whatever its case or spacing', () => {
    const one = pairIndexOf('Mallu Movies')
    assert.equal(pairIndexOf('mallu movies'), one)
    assert.equal(pairIndexOf('MALLU MOVIES'), one)
    assert.equal(pairIndexOf('  Mallu   Movies  '), one)
    assert.equal(pairIndexOf('Mallu\tMovies'), one)
})

test('every name gets a pair in range, a missing one included', () => {
    for (const name of [null, undefined, '', '   ', '🎬🍿', 'A', 'മലയാളം സിനിമ', 'فیلم‌ها', '日本語のチャット']) {
        const index = pairIndexOf(name)
        assert.ok(index >= 0 && index < PAIRS.length, `${name} gave ${index}`)
        assert.deepEqual(pairOf(name), PAIRS[index])
    }
})

// The same table core's NameColourTest asserts: the two hashes have to agree name for name, or a
// chat is one colour in the apps and another in the browser.
test("the apps' hash agrees name for name", () => {
    const expected: Record<string, number> = {
        '': 1,
        'Mallu Movies': 1,
        'Add-on Database: MSB': 6,
        DiccoBot: 7,
        'Saved Messages': 5,
        'മലയാളം സിനിമ': 4,
        'فیلم‌ها': 0,
        '10bitclub': 5,
        'Zeyrox Movies Official': 7,
        Nevil: 7,
        '🎬🎬': 3,
        A: 0,
        B: 3,
        C: 7,
        'Films & Shows': 0,
        TMPlayer: 1,
        日本語のチャット: 0,
    }
    for (const [name, index] of Object.entries(expected)) assert.equal(pairIndexOf(name), index, name)
})

test('a large account fills the palette', () => {
    const counts = new Array(PAIRS.length).fill(0)
    for (let i = 0; i < 4000; i++) counts[pairIndexOf(`Chat ${i} name`)]++
    for (const [index, count] of counts.entries()) {
        assert.ok(count >= 333 && count <= 666, `pair ${index} got ${count} of 4000`)
    }
})

test('the background carries both layers and the name pair', () => {
    const art = nameArt('Mallu Movies')
    const [light, deep] = pairOf('Mallu Movies')
    assert.ok(art.includes(light) && art.includes(deep), art)
    assert.ok(art.includes('radial-gradient') && art.includes('linear-gradient'), art)
})
