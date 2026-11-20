// `node --test src/player/openStall.test.ts` (pnpm test:player): why a video is not starting, on a clock the test drives.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { DECODE_STALL_MS, MAX_EXTRA_MS, STALL_MS, StallWatch, stallWhy, stallWindow, TRICKLE_MS, type Sample } from './openStall.ts'

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

const CHUNK = 512 * 1024

/**
 * A line of `rate` bytes a second shared by `inFlight` chunks: nothing arrives until they all end
 * together (the worst case for a watch), and then the next ones are asked. Returns whether the
 * sheet ever came in `seconds`.
 */
function stalledOn(rate: number, inFlight: number, seconds: number): boolean {
    const w = new StallWatch()
    w.reset(0)
    const every = (inFlight * CHUNK) / rate
    let received = 0
    let next = every
    for (let t = 1; t <= seconds; t++) {
        if (t >= next) {
            received += inFlight * CHUNK
            next += every
        }
        if (w.tick(t * 1000, waiting({ received, pending: inFlight * CHUNK })) === 'stalled') return true
    }
    return false
}

test('a slow line that ends a chunk every so often is never called stalled: 30 and 80 KB/s, head and tail asked together, then four', () => {
    for (const rate of [30_000, 80_000]) {
        assert.equal(stalledOn(rate, 2, 400), false, `${rate} B/s, 2 in flight`)
        assert.equal(stalledOn(rate, 4, 400), false, `${rate} B/s, 4 in flight`)
    }
})

test('a line that sends nothing is called stalled once the chunks asked for would have come at 25 KB/s', () => {
    const w = new StallWatch()
    w.reset(0)
    const window = stallWindow(2 * CHUNK)
    assert.ok(window > STALL_MS && window <= STALL_MS + MAX_EXTRA_MS)
    assert.equal(w.tick(window - 1000, waiting({ pending: 2 * CHUNK })), 'ok')
    assert.equal(w.tick(window, waiting({ pending: 2 * CHUNK })), 'stalled')
})

test('with nothing asked, or the cause known (pending 0), it is still 10 s', () => {
    assert.equal(stallWindow(0), STALL_MS)
    assert.equal(stallWindow(-5), STALL_MS)
    const w = new StallWatch()
    w.reset(0)
    assert.equal(w.tick(STALL_MS, waiting({ pending: 0 })), 'stalled')
})

test('what is asked stretches the wait by no more than the cap', () => {
    assert.equal(stallWindow(1e12), STALL_MS + MAX_EXTRA_MS)
    assert.equal(stallWindow(NaN) >= STALL_MS || Number.isNaN(stallWindow(NaN)), true)
})
