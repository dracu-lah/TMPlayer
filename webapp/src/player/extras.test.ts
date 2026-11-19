// Rotate picture (PictureRotation), speed per series and chat (SpeedMemory) and the boost levels, against the
// cases core's tests hold. `node --test "src/player/*.test.ts"` (pnpm test:player).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { NONE, transformOf } from './zoom.ts'
import { BOOSTS, moviVolume, nextTurn, pictureTransform, turnedPictureSize, normaliseTurn, resolveSpeed, sanitiseBoost, seriesKeyOf, sidewaysTurn, speedScopes, speedTarget, speedToKeep, turnFrame } from './extras.ts'

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

test('the turn and the zoom are one transform: the zoom in the stage, then the turn about the centre, and neither erases the other', () => {
    const zoom = { scale: 2, panX: 10, panY: -4 }
    assert.equal(pictureTransform(0, NONE), '')
    assert.equal(pictureTransform(90, NONE), 'rotate(90deg)')
    assert.equal(pictureTransform(0, zoom), 'translate(10px, -4px) scale(2)')
    assert.equal(pictureTransform(270, zoom), 'translate(10px, -4px) scale(2) rotate(270deg)')
    // The same answer as the pieces alone: the zoom part is zoom.ts's own, the turn is the normalised quarter.
    assert.equal(pictureTransform(-90, zoom), `${transformOf(zoom)} rotate(270deg)`)
    assert.equal(pictureTransform(NaN, zoom), transformOf(zoom))
    // Zoomed back to 100 per cent a turned picture keeps its turn.
    assert.equal(pictureTransform(180, NONE), 'rotate(180deg)')
})

test('the zoom reaches a turned picture by its size on the screen, not the file\'s', () => {
    // A 1920x1080 file on a 1280x720 stage: fitted it fills the stage.
    assert.deepEqual(turnedPictureSize(1280, 720, 1920, 1080, 'fit', 0), { width: 1280, height: 720 })
    assert.deepEqual(turnedPictureSize(1280, 720, 1920, 1080, 'fit', 180), { width: 1280, height: 720 })
    // Turned a quarter it is laid out in the traded 720x1280 frame (720x405 there), which is 405 wide and 720 tall on the screen.
    assert.deepEqual(turnedPictureSize(1280, 720, 1920, 1080, 'fit', 90), { width: 405, height: 720 })
    assert.deepEqual(turnedPictureSize(1280, 720, 1920, 1080, 'fit', 270), { width: 405, height: 720 })
    assert.equal(turnedPictureSize(0, 720, 1920, 1080, 'fit', 90), null)
    // A phone upright, 390x700: the turned picture fills its width (693x390 in the traded frame).
    assert.deepEqual(turnedPictureSize(390, 700, 1920, 1080, 'fit', 90), { width: 390, height: 693 })
    // A fixed ratio is a box in the traded frame too: 4:3 in 720x1280 is 720x540, 540 wide and 720 tall turned.
    assert.deepEqual(turnedPictureSize(1280, 720, 1920, 1080, '4:3', 90), { width: 540, height: 720 })
})

test('movi takes the volume times the boost, rising with the volume and capped at its own 2', () => {
    assert.equal(moviVolume(1, 1), 1)
    assert.equal(moviVolume(1, 1.5), 1.5)
    assert.equal(moviVolume(1, 2), 2)
    // Under full volume the boost still lifts, in step: no jump to 2x at 100 per cent.
    assert.equal(moviVolume(0.5, 1.5), 0.75)
    assert.ok(moviVolume(0.99, 2) < moviVolume(1, 2))
    assert.ok(moviVolume(0.9, 2) > moviVolume(0.9, 1))
    assert.equal(moviVolume(0, 2), 0)
    assert.equal(moviVolume(1, 9), 2)
    assert.equal(moviVolume(1, 0.2), 1)
}
)
