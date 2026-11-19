// Rotate picture (PictureRotation), speed per series and chat (SpeedMemory) and the boost levels, against the
// cases core's tests hold. `node --test "src/player/*.test.ts"` (pnpm test:player).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { BOOSTS, nextTurn, normaliseTurn, resolveSpeed, sanitiseBoost, seriesKeyOf, sidewaysTurn, speedScopes, speedTarget, speedToKeep, turnFrame } from './extras.ts'

test('a turn is one of the four quarters, whatever it is given', () => {
    assert.deepEqual([0, 90, 180, 270, 360, -90, 450, 44, 45, 46, -1].map(normaliseTurn), [0, 90, 180, 270, 0, 270, 90, 0, 90, 90, 0])
    for (const bad of [NaN, Infinity, -Infinity]) assert.equal(normaliseTurn(bad), 0)
    assert.deepEqual([0, 90, 180, 270].map(nextTurn), [90, 180, 270, 0])
    assert.deepEqual([0, 90, 180, 270].map(sidewaysTurn), [false, true, false, true])
})

test('a sideways turn trades the stage box and keeps it centred, so the turned box fills the stage', () => {
    assert.deepEqual(turnFrame(1280, 720, 0), { left: 0, top: 0, width: 1280, height: 720 })
    assert.deepEqual(turnFrame(1280, 720, 180), { left: 0, top: 0, width: 1280, height: 720 })
    const f = turnFrame(1280, 720, 90)
    assert.deepEqual(f, { left: 280, top: -280, width: 720, height: 1280 })
    // Turned about its centre, a 720 by 1280 box covers exactly the 1280 by 720 stage.
    assert.equal(f.left + f.width / 2, 640)
    assert.equal(f.top + f.height / 2, 360)
    assert.deepEqual(turnFrame(1280, 720, 270), f)
    // A stage not measured yet is not a reason to throw.
    assert.deepEqual(turnFrame(0, 0, 90), { left: 0, top: 0, width: 0, height: 0 })
})

test('the series key is the apps: lower case, letters and digits only, 48 at most', () => {
    assert.equal(seriesKeyOf('Kerala Crime Files'), 'keralacrimefiles')
    assert.equal(seriesKeyOf('Kerala_Crime_Files'), 'keralacrimefiles')
    assert.equal(seriesKeyOf('Ünï-Çödé 2'), 'ünïçödé2')
    assert.equal(seriesKeyOf('---'), '')
    assert.equal(seriesKeyOf('a'.repeat(100)).length, 48)
    // Emoji and right to left names are kept as the letters they are, never split into halves.
    assert.equal(seriesKeyOf('مسلسل 1 😀'), 'مسلسل1')
})

test('a speed belongs to the show, else the chat; a video outside a chat has neither', () => {
    assert.deepEqual(speedScopes(-100123, 'Harbour Notes'), { series: 'series:harbournotes', chat: 'chat:-100123' })
    assert.deepEqual(speedScopes(-100123, null), { series: null, chat: 'chat:-100123' })
    assert.deepEqual(speedScopes(0, 'Harbour Notes'), { series: 'series:harbournotes', chat: null })
    assert.deepEqual(speedScopes(NaN, '!!!'), { series: null, chat: null })
    assert.equal(speedTarget(speedScopes(5, 'Show')), 'series:show')
    assert.equal(speedTarget(speedScopes(5, null)), 'chat:5')
    assert.equal(speedTarget(speedScopes(0, null)), null)
})

test('the opening speed is the show first, then the chat, then 1x', () => {
    assert.equal(resolveSpeed(1.5, 2), 1.5)
    assert.equal(resolveSpeed(undefined, 1.25), 1.25)
    assert.equal(resolveSpeed(undefined, undefined), 1)
    // Damaged values pass as if they were not there.
    for (const bad of ['fast', NaN, 0, -1, null, {}, [], Infinity]) assert.equal(resolveSpeed(bad, 1.75), 1.75)
    assert.equal(resolveSpeed(99, undefined), 4)
    assert.equal(resolveSpeed(0.01, undefined), 0.25)
    assert.equal(resolveSpeed(1.2345, undefined), 1.23)
})

test('1x is written as nothing, so the next video of the kind opens at 1x too', () => {
    assert.equal(speedToKeep(1), null)
    assert.equal(speedToKeep(1.003), null)
    assert.equal(speedToKeep(1.5), 1.5)
    assert.equal(speedToKeep(8), 4)
    assert.equal(speedToKeep(NaN), null)
})

test('a boost is one of the offered levels, else off', () => {
    for (const level of BOOSTS) assert.equal(sanitiseBoost(level), level)
    for (const bad of [3, 0, -2, 1.3, NaN, Infinity, '2', null, undefined, {}]) assert.equal(sanitiseBoost(bad), 1)
    assert.equal(Math.max(...BOOSTS), 2)
})
