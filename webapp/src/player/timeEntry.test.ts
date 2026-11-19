// Go to a time: core's TimeEntryTest, case for case, plus garbage. `pnpm test:player`.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { parseTime } from './timeEntry.ts'

const hourAndHalf = 90 * 60_000
const parse = (text: string, { duration = hourAndHalf, position = 0 } = {}) => parseTime(text, duration, position)

test('a clock', () => {
    assert.equal(parse('1:23:45'), ((1 * 60 + 23) * 60 + 45) * 1000)
    assert.equal(parse('83:10'), (83 * 60 + 10) * 1000)
    assert.equal(parse('0:45'), 45_000)
    assert.equal(parse(' 0 : 45 '), 45_000)
    assert.equal(parse('1:75'), null)
    assert.equal(parse('1:61:00'), null)
    assert.equal(parse('1:2:3:4'), null)
    assert.equal(parse('1:'), null)
})

test('a bare number is minutes', () => {
    assert.equal(parse('45'), 45 * 60_000)
    assert.equal(parse('0'), 0)
})

test('units', () => {
    assert.equal(parse('1h20m'), 80 * 60_000)
    assert.equal(parse('1h 20m 5s'), 80 * 60_000 + 5_000)
    assert.equal(parse('90s'), 90_000)
    assert.equal(parse('45 min'), 45 * 60_000)
    assert.equal(parse('1H'), 60 * 60_000)
    assert.equal(parse('1h1h'), null)
    assert.equal(parse('5ms'), null)
    assert.equal(parse('ten'), null)
})

test('a percentage of the length', () => {
    assert.equal(parse('50%'), 45 * 60_000)
    assert.equal(parse('12.5%'), hourAndHalf / 8)
    assert.equal(parse('12,5 %'), hourAndHalf / 8)
    assert.equal(parse('120%'), null)
    assert.equal(parse('50%', { duration: 0 }), null)
})

test('steps from where the video is, kept inside it', () => {
    assert.equal(parse('+5m', { position: 10 * 60_000 }), 15 * 60_000)
    assert.equal(parse('-30s', { position: 10 * 60_000 }), 9 * 60_000 + 30_000)
    assert.equal(parse('-20', { position: 10 * 60_000 }), 0)
    assert.equal(parse('+2h', { position: 10 * 60_000 }), hourAndHalf)
})

test('past the end is refused', () => {
    assert.equal(parse('91'), null)
    assert.equal(parse('2:00:00'), null)
    assert.equal(parse('2:00:00', { duration: 0 }), 2 * 3_600_000)
})

test('digits and signs from other keyboards', () => {
    assert.equal(parse('١٢:٣٠'), (12 * 60 + 30) * 1000)
    assert.equal(parse('۱۲:۳۰'), (12 * 60 + 30) * 1000)
    assert.equal(parse('०४५'), 45 * 60_000)
    assert.equal(parse('１２：３０'), (12 * 60 + 30) * 1000)
    assert.equal(parse('٥٠٪'), 45 * 60_000)
})

test('nothing reads as nothing', () => {
    assert.equal(parse(''), null)
    assert.equal(parse('   '), null)
    assert.equal(parse('+'), null)
    assert.equal(parse('12:3x'), null)
})

test('garbage never throws and never points outside the video', () => {
    const junk = ['NaN', 'Infinity', '1e9', '99999999999999999999999', '9'.repeat(400), '%', ':', '::', '-%', '+-5', '5..5%', '\u0000', '🙂', '1h2m3s4h', 'h', '0x10', '-1:00']
    for (const j of junk) {
        const got = parse(j, { position: 60_000 })
        assert.ok(got === null || (got >= 0 && got <= hourAndHalf), `${JSON.stringify(j)} gave ${got}`)
    }
    for (let i = 0; i < 2000; i++) {
        const text = Array.from({ length: 1 + Math.floor(Math.random() * 12) }, () => String.fromCharCode(Math.floor(Math.random() * 0x700))).join('')
        const got = parse(text)
        assert.ok(got === null || (Number.isFinite(got) && got >= 0 && got <= hourAndHalf), `${JSON.stringify(text)} gave ${got}`)
    }
    assert.equal(parseTime(undefined as unknown as string, hourAndHalf), null)
    // A huge hour field with no length to refuse it: null, never an unsafe number of milliseconds.
    assert.equal(parseTime('9007199254740:00:00', 0), null)
    assert.equal(parseTime('NaN%', hourAndHalf), null)
    assert.equal(parseTime('1e2%', hourAndHalf), null)
})
