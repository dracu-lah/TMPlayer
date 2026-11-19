// `node --test src/player/openStall.test.ts` (pnpm test:player): why a video is not starting, on a clock the test drives.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { DECODE_STALL_MS, STALL_MS, StallWatch, stallWhy, TRICKLE_MS, type Sample } from './openStall.ts'

const waiting = (over: Partial<Sample> = {}): Sample => ({ waiting: true, moved: false, received: 0, local: false, transcode: false, ...over })

test('no bytes for 10 s says Telegram has not started sending', () => {
    const w = new StallWatch()
    w.reset(0)
    assert.equal(w.tick(STALL_MS - 1000, waiting()), 'ok')
    assert.equal(w.tick(STALL_MS, waiting()), 'stalled')
})

test('a line that keeps sending never gets the sheet: every byte starts the count again', () => {
    const w = new StallWatch()
    w.reset(0)
    let received = 0
    let last = 'ok'
    for (let t = 1000; t <= 120_000; t += 1000) {
        received += 800
        last = w.tick(t, waiting({ received }))
    }
    assert.equal(last, 'trickle')
})

test('a trickle shows its speed only after 5 s, not at the first second', () => {
    const w = new StallWatch()
    w.reset(0)
    assert.equal(w.tick(1000, waiting({ received: 100 })), 'ok')
    assert.equal(w.tick(TRICKLE_MS, waiting({ received: 200 })), 'trickle')
})

test('bytes that stop after a trickle count 10 s from the last one', () => {
    const w = new StallWatch()
    w.reset(0)
    assert.equal(w.tick(6000, waiting({ received: 500 })), 'trickle')
    assert.equal(w.tick(15_999, waiting({ received: 500 })), 'trickle')
    assert.equal(w.tick(16_000, waiting({ received: 500 })), 'stalled')
})

test('a trickle holds through the quiet between two chunks, so its line does not flicker', () => {
    const w = new StallWatch()
    w.reset(0)
    assert.equal(w.tick(1000, waiting({ received: 500 })), 'ok')
    for (let t = 5000; t <= 9000; t += 1000) assert.equal(w.tick(t, waiting({ received: 500 })), 'trickle')
})

test('the speed is the average over the whole wait', () => {
    const w = new StallWatch()
    w.reset(10_000, 1000)
    w.tick(14_000, waiting({ received: 1000 + 400_000 }))
    assert.equal(w.speed(14_000), 100_000)
    assert.equal(new StallWatch().speed(5), 0)
})

test('playing again resets the count', () => {
    const w = new StallWatch()
    w.reset(0)
    assert.equal(w.tick(9000, waiting()), 'ok')
    assert.equal(w.tick(9500, waiting({ moved: true })), 'ok')
    assert.equal(w.tick(19_000, waiting()), 'ok')
    assert.equal(w.tick(19_500, waiting()), 'stalled')
})

test('a paused video that is not waiting is never stalled', () => {
    const w = new StallWatch()
    w.reset(0)
    assert.equal(w.tick(60_000, waiting({ waiting: false })), 'ok')
})

test('a file that is all here is not Telegram: the old 15 s watch of the clock', () => {
    const w = new StallWatch()
    w.reset(0)
    assert.equal(w.tick(STALL_MS + 1000, waiting({ local: true })), 'ok')
    assert.equal(w.tick(DECODE_STALL_MS, waiting({ local: true })), 'stalled')
})

test('a conversion on this device waits three times as long', () => {
    const w = new StallWatch()
    w.reset(0)
    assert.equal(w.tick(29_000, waiting({ transcode: true })), 'ok')
    assert.equal(w.tick(30_000, waiting({ transcode: true })), 'stalled')
})

test('a page back from the background starts the count afresh', () => {
    const w = new StallWatch()
    w.reset(0)
    w.reset(300_000)
    assert.equal(w.tick(301_000, waiting()), 'ok')
})

test('the reason: a flood wait first, then no line, else Telegram is slow', () => {
    assert.equal(stallWhy(12, true, 'offline'), 'flood')
    assert.equal(stallWhy(0, true, 'connecting'), 'noline')
    assert.equal(stallWhy(0, true, 'offline'), 'noline')
    assert.equal(stallWhy(0, true, 'connected'), 'slow')
    assert.equal(stallWhy(0, true, 'updating'), 'slow')
    assert.equal(stallWhy(0, true, null), 'slow')
    // The device offline is the offline line's, not a blocked network.
    assert.equal(stallWhy(0, false, 'offline'), 'slow')
})
