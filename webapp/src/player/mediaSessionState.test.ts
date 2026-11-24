import assert from 'node:assert/strict'
import { test } from 'node:test'
import { artworkFor, jumpedTo, positionState, sendPositionState } from './mediaSessionState.ts'

test('a loaded video gives its length, position and speed', () => {
    assert.deepEqual(positionState({ duration: 600, currentTime: 12.5, playbackRate: 1.5 }), { duration: 600, playbackRate: 1.5, position: 12.5 })
})

test('no length, an infinite one (a live stream) or a zero one gives no state, which the API would throw on', () => {
    for (const duration of [NaN, Infinity, 0, -3]) assert.equal(positionState({ duration, currentTime: 1, playbackRate: 1 }), null)
})

test('a position past the end or before the start is held inside, and a rate of zero or garbage is 1', () => {
    assert.equal(positionState({ duration: 100, currentTime: 140, playbackRate: 1 })!.position, 100)
    assert.equal(positionState({ duration: 100, currentTime: -4, playbackRate: 1 })!.position, 0)
    assert.equal(positionState({ duration: 100, currentTime: NaN, playbackRate: 1 })!.position, 0)
    assert.equal(positionState({ duration: 100, currentTime: 1, playbackRate: 0 })!.playbackRate, 1)
    assert.equal(positionState({ duration: 100, currentTime: 1, playbackRate: NaN })!.playbackRate, 1)
})

test('setPositionState is called with the state, cleared when there is none, and a browser that throws or lacks it is left alone', () => {
    const seen: unknown[] = []
    const stub = { setPositionState: (s?: unknown) => void seen.push(s) }
    sendPositionState(stub, { duration: 60, currentTime: 5, playbackRate: 2 })
    sendPositionState(stub, { duration: NaN, currentTime: 0, playbackRate: 1 })
    assert.deepEqual(seen, [{ duration: 60, playbackRate: 2, position: 5 }, undefined])
    assert.doesNotThrow(() =>
        sendPositionState(
            {
                setPositionState: () => {
                    throw new TypeError('refused')
                },
            },
            { duration: 60, currentTime: 5, playbackRate: 2 },
        ),
    )
    assert.doesNotThrow(() => sendPositionState({} as never, { duration: 60, currentTime: 5, playbackRate: 2 }))
    assert.doesNotThrow(() => sendPositionState(undefined, { duration: 60, currentTime: 5, playbackRate: 2 }))
})

test('the artwork is the thumbnail, and nothing for a chat that restricts saving or no thumbnail', () => {
    assert.deepEqual(artworkFor('blob:x', true), [{ src: 'blob:x' }])
    assert.deepEqual(artworkFor('blob:x', false), [])
    assert.deepEqual(artworkFor(null, true), [])
    assert.deepEqual(artworkFor('', true), [])
})

test('the jumps stay inside the video, whatever step the OS asks for', () => {
    assert.equal(jumpedTo(5, -10, 100), 0)
    assert.equal(jumpedTo(95, 10, 100), 100)
    assert.equal(jumpedTo(50, 30, 100), 80)
    assert.equal(jumpedTo(50, 30, NaN), 80)
    assert.equal(jumpedTo(NaN, NaN, 100), 0)
})
