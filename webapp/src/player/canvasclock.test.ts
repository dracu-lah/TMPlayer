// node --test: the canvas engine's clock, frame choice, ring, speed gauge and sound placement, with
// fake frames and a hand-moved timer (canvasclock.ts), and its picture and sound conversions
// (libavplanes.ts).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { canvasPixels, chooseFrame, DecodeGauge, FrameRing, MasterClock, refilled, ringFrames, SoundTimeline, starved } from './canvasclock.ts'
import type { PixelFormats } from './libavframes.ts'
import { downmix, soundPlanes, yuvPlanes } from './libavplanes.ts'

/** A source the test moves by hand. */
const timer = () => {
    let t = 100
    return { now: () => t, advance: (s: number) => (t += s) }
}
const ms = (s: number) => Math.round(s * 1000)

test('a held clock stays where it was set, a running one follows its source', () => {
    const s = timer()
    const c = new MasterClock(s.now)
    c.set(10)
    s.advance(5)
    assert.equal(c.position(), 10)
    c.go(false)
    s.advance(2)
    assert.equal(ms(c.position()), 12000)
    c.hold()
    s.advance(9)
    assert.equal(ms(c.position()), 12000)
})

test('a clock on a sound source that stood still while held keeps its anchor on go', () => {
    const s = timer()
    const c = new MasterClock(s.now)
    c.set(5)
    c.go(true)
    s.advance(1)
    assert.equal(ms(c.position()), 6000)
    c.hold()
    // The source was suspended: it does not move, and nothing is lost when it runs again.
    c.go(true)
    s.advance(0.5)
    assert.equal(ms(c.position()), 6500)
})

test('a clock on the page timer takes a new anchor on go, so held time is not counted', () => {
    const s = timer()
    const c = new MasterClock(s.now)
    c.set(5)
    s.advance(30)
    c.go(false)
    s.advance(1)
    assert.equal(ms(c.position()), 6000)
})

test('latency shifts the clock back so the picture follows what is heard', () => {
    const s = timer()
    const c = new MasterClock(s.now, 0.1)
    c.set(0)
    c.go(false)
    s.advance(1)
    assert.equal(ms(c.position()), 1000)
    assert.equal(ms(c.contextTimeOf(2) - c.contextTimeOf(1)), 1000)
    // A sound at media time m is heard 0.1 s after the source reaches it.
    assert.equal(ms(c.contextTimeOf(1.5) - c.sourceTimeOf(1.5)), 100)
})

test('a new speed carries on from where the picture is', () => {
    const s = timer()
    const c = new MasterClock(s.now)
    c.set(0)
    c.go(false)
    s.advance(2)
    c.setRate(2)
    assert.equal(ms(c.position()), 2000)
    s.advance(1)
    assert.equal(ms(c.position()), 4000)
    c.setRate(-1)
    assert.equal(c.rate, 2)
})

test('settle takes in what the sound played while its context was stopping', () => {
    const s = timer()
    const c = new MasterClock(s.now)
    c.set(3)
    c.go(false)
    s.advance(1)
    c.hold()
    assert.equal(ms(c.position()), 4000)
    // The context ran on for 20 ms before it stopped.
    s.advance(0.02)
    c.settle()
    assert.equal(ms(c.position()), 4020)
    c.go(true)
    s.advance(1)
    assert.equal(ms(c.position()), 5020)
    // Settling a running clock does nothing.
    c.settle()
    assert.equal(ms(c.position()), 5020)
})

test('chooseFrame shows the last frame that is due and drops the late ones before it', () => {
    const times = [0.0, 0.04, 0.08, 0.12]
    assert.deepEqual(chooseFrame(times, 0.1), { show: 2, dropped: 2 })
    assert.deepEqual(chooseFrame(times, 0.0), { show: 0, dropped: 0 })
    assert.deepEqual(chooseFrame(times, 0.05), { show: 1, dropped: 1 })
    // Nothing due yet: the picture on the screen is repeated.
    assert.deepEqual(chooseFrame([0.5, 0.54], 0.2), { show: -1, dropped: 0 })
    assert.deepEqual(chooseFrame([], 1), { show: -1, dropped: 0 })
    // A frame a few ms early is shown with this refresh, not the next.
    assert.equal(chooseFrame([0.505], 0.5).show, 0)
})

test('the picture stays within 80 ms of the sound over a run with display jitter and a late frame now and then', () => {
    const s = timer()
    const c = new MasterClock(s.now)
    c.set(0)
    c.go(true)
    const ring = new FrameRing<{ at: number; bytes: number }>(12, 1e9)
    let next = 0
    let worst = 0
    let drops = 0
    let shown = -1
    for (let step = 0; step < 60 * 20; step++) {
        s.advance(1 / 60 + (step % 5 === 0 ? 0.004 : 0))
        const now = c.position()
        // The decoder keeps up to 0.4 s ahead; every 7th frame turns up only 0.1 s before it is due, 0.3 s later than the others.
        // The engine drops a frame that arrives more than 80 ms behind the clock.
        while (!ring.full && next * 0.04 < now + 0.4 && !(next % 7 === 0 && next * 0.04 - 0.1 > now)) {
            const at = next++ * 0.04
            if (at < now - 0.08) drops++
            else ring.push({ at, bytes: 1 })
        }
        const choice = chooseFrame(ring.times(), now)
        if (choice.show >= 0) {
            shown = ring.take(choice.show)!.at
            drops += choice.dropped
        }
        if (shown >= 0 && now > 1) worst = Math.max(worst, Math.abs(now - shown))
    }
    // A 25 fps picture shown against a clock is at most one frame (40 ms) old plus a display tick.
    assert.ok(worst < 0.08, `worst offset ${worst}`)
    assert.ok(drops < 10, `dropped ${drops}`)
})

test('a slow decoder is followed by dropping frames, the offset stays bounded', () => {
    const s = timer()
    const c = new MasterClock(s.now)
    c.set(0)
    c.go(true)
    const ring = new FrameRing<{ at: number; bytes: number }>(8, 1e9)
    // The decoder makes 15 frames a second of a 25 fps video: it gets behind, frames come late.
    let next = 0
    let decoded = 0
    let worst = 0
    let dropped = 0
    let shown = 0
    for (let step = 0; step < 60 * 20; step++) {
        s.advance(1 / 60)
        const now = c.position()
        decoded += 15 / 60
        while (decoded >= 1 && !ring.full) {
            decoded -= 1
            // Frames it has not reached by the clock are skipped over, as the engine drops late ones on arrival.
            next = Math.max(next, Math.floor(now / 0.04) - 1)
            ring.push({ at: next++ * 0.04, bytes: 1 })
        }
        const ch = chooseFrame(ring.times(), now)
        if (ch.show >= 0) {
            shown = ring.take(ch.show)!.at
            dropped += ch.dropped
        }
        if (now > 2) worst = Math.max(worst, now - shown)
    }
    assert.ok(worst < 0.2, `worst lag ${worst}`)
    assert.ok(dropped >= 0)
})

test('the ring is bounded by count and by bytes, and a single frame always fits', () => {
    const r = new FrameRing<{ at: number; bytes: number }>(3, 100)
    r.push({ at: 0, bytes: 150 })
    assert.equal(r.full, true)
    r.take(0)
    assert.equal(r.length, 0)
    assert.equal(r.bytes, 0)
    assert.equal(r.full, false)
    r.push({ at: 0, bytes: 10 })
    r.push({ at: 1, bytes: 10 })
    r.push({ at: 2, bytes: 10 })
    assert.equal(r.full, true)
    assert.equal(r.last, 2)
    // Taking a later frame drops the ones before it and frees their bytes.
    assert.equal(r.take(1)?.at, 1)
    assert.equal(r.length, 1)
    assert.equal(r.bytes, 10)
    assert.equal(r.take(5), undefined)
    r.clear()
    assert.equal(r.last, null)
})

test('ringFrames fits 10 MB, between 4 and 24 frames', () => {
    assert.equal(ringFrames(720 * 576 * 1.5), 16)
    assert.equal(ringFrames(320 * 176 * 1.5), 24)
    assert.equal(ringFrames(1920 * 1080 * 1.5), 4)
    assert.equal(ringFrames(0), 8)
})

test('starved and refilled decide when the clock waits for the decoder', () => {
    assert.equal(starved(0, false, 5, 4.9), false)
    assert.equal(starved(0, false, 5.3, 4.9), true)
    assert.equal(starved(2, false, 9, 1), false)
    assert.equal(starved(0, true, 9, 1), false)
    assert.equal(starved(0, false, 0, null), true)
    assert.equal(refilled(2, 12, false), false)
    assert.equal(refilled(3, 12, false), true)
    assert.equal(refilled(0, 12, true), true)
    assert.equal(refilled(4, 4, false), true)
})

test('DecodeGauge calls a slow decoder heavy and a fast one fine, and keeps the first verdict', () => {
    const slow = new DecodeGauge(25)
    assert.equal(slow.verdict(), 'wait')
    slow.add(12, 1000)
    assert.equal(slow.verdict(), 'wait')
    slow.add(12, 1000)
    // 24 frames in 2 s of decoding: 12 a second, under 20.
    assert.equal(slow.verdict(), 'heavy')
    slow.add(500, 1000)
    assert.equal(slow.verdict(), 'heavy')

    const fast = new DecodeGauge(25)
    fast.add(30, 200)
    assert.equal(fast.verdict(), 'wait')
    fast.add(70, 600)
    assert.equal(fast.verdict(), 'ok')

    // A 12 fps clip only needs 12.
    const low = new DecodeGauge(12)
    low.add(30, 2000)
    assert.equal(low.verdict(), 'wait')
    low.add(70, 6000)
    assert.equal(low.verdict(), 'ok')

    // Only time spent decoding is counted.
    const idle = new DecodeGauge(25)
    idle.add(100, 1000)
    assert.equal(idle.fps, 100)
})

test('SoundTimeline places stretches gapless, skips what is late and trims the front', () => {
    const s = timer()
    const c = new MasterClock(s.now)
    c.set(0)
    const tl = new SoundTimeline(c)
    // Held, anchored at the source's 100 s: 0 s of the video is heard at 100 s.
    const a = tl.place(0.2, 0.2, s.now())!
    assert.equal(ms(a.when), 100200)
    assert.equal(a.offset, 0)
    // The next stretch starts within rounding of the end of the last: gapless.
    const b = tl.place(0.4000001, 0.2, s.now())!
    assert.equal(b.when, a.when + 0.2)
    // The video starts at 5 s and its first sound starts earlier: the front is cut.
    c.set(5)
    tl.reset()
    const first = tl.place(4.9, 0.2, s.now())!
    assert.ok(first.offset > 0.08 && first.offset < 0.12, `offset ${first.offset}`)
    assert.ok(Math.abs(first.when - (s.now() + 0.01)) < 1e-9)
    // All of it past: nothing to play.
    tl.reset()
    assert.equal(tl.place(4.0, 0.2, s.now()), null)
    // A stretch placed after the clock ran 1 s lines up with it.
    c.go(true)
    s.advance(1)
    tl.reset()
    const late = tl.place(6.5, 0.2, s.now())!
    assert.equal(ms(late.when), 101500)
})

test('canvasPixels allows a PAL DVD on a TV stick and more on a desktop', () => {
    assert.ok(canvasPixels(4, 1, false) >= 720 * 576)
    assert.ok(canvasPixels(8, 8, false) >= 1920 * 1080)
    assert.ok(canvasPixels(8, 4, true) < canvasPixels(8, 8, false))
})

const PF: PixelFormats = {
    AV_PIX_FMT_YUV420P: 0,
    AV_PIX_FMT_RGB24: 2,
    AV_PIX_FMT_BGR24: 3,
    AV_PIX_FMT_YUV422P: 4,
    AV_PIX_FMT_YUV444P: 5,
    AV_PIX_FMT_YUV410P: 6,
    AV_PIX_FMT_YUV411P: 7,
    AV_PIX_FMT_GRAY8: 8,
    AV_PIX_FMT_YUVJ420P: 12,
    AV_PIX_FMT_YUVJ422P: 13,
    AV_PIX_FMT_YUVJ444P: 14,
    AV_PIX_FMT_ARGB: 25,
    AV_PIX_FMT_RGBA: 26,
    AV_PIX_FMT_ABGR: 27,
    AV_PIX_FMT_BGRA: 28,
}

test('yuvPlanes hands 4:2:0 through without a copy and repacks padded rows', () => {
    // Tight 4 x 2: Y is 8 bytes, U and V 2 bytes each.
    const tight = new Uint8Array([...Array(8).fill(10), 1, 2, 3, 4])
    const a = yuvPlanes({ format: PF.AV_PIX_FMT_YUV420P, width: 4, height: 2, data: tight }, PF)!
    assert.equal(a.y.buffer, tight.buffer)
    assert.deepEqual([...a.u], [1, 2])
    assert.deepEqual([...a.v], [3, 4])
    assert.equal(a.cw, 2)
    assert.equal(a.ch, 1)
    assert.equal(a.full, false)
    // Padded to 6: the rows come out tight.
    const padded = new Uint8Array(6 * 2 + 3 + 3)
    padded.set([1, 2, 3, 4], 0)
    padded.set([5, 6, 7, 8], 6)
    padded.set([9, 9], 12)
    padded.set([8, 8], 15)
    const b = yuvPlanes(
        {
            format: PF.AV_PIX_FMT_YUVJ420P,
            width: 4,
            height: 2,
            data: padded,
            layout: [
                { offset: 0, stride: 6 },
                { offset: 12, stride: 3 },
                { offset: 15, stride: 3 },
            ],
        },
        PF,
    )!
    assert.deepEqual([...b.y], [1, 2, 3, 4, 5, 6, 7, 8])
    assert.deepEqual([...b.u], [9, 9])
    assert.equal(b.full, true)
    // 4:2:2 is brought to 4:2:0.
    const c = yuvPlanes({ format: PF.AV_PIX_FMT_YUV422P, width: 4, height: 2, data: new Uint8Array(4 * 2 + 2 * 2 * 2).fill(7) }, PF)!
    assert.equal(c.cw, 2)
    assert.equal(c.ch, 1)
    // A short buffer, an unknown layout and garbage are refused, not thrown.
    assert.equal(yuvPlanes({ format: PF.AV_PIX_FMT_YUV420P, width: 5, height: 3, data: new Uint8Array(10) }, PF), null)
    assert.equal(yuvPlanes({ format: 12345, width: 4, height: 2, data: tight }, PF), null)
    assert.equal(yuvPlanes({ format: 0, width: 0, height: 0, data: tight }, PF), null)
    assert.equal(yuvPlanes({ format: 0, width: 4, height: 2, data: [] as unknown as Uint8Array }, PF), null)
})

test('soundPlanes makes floats per channel from every sample type, and refuses garbage', () => {
    const s16 = soundPlanes(1, 2, 2, new Int16Array([16384, -16384, 0, 32767]))!
    assert.deepEqual([...s16[0]], [0.5, 0])
    assert.ok(Math.abs(s16[1][1] - 0.99997) < 1e-4)
    const u8p = soundPlanes(5, 1, 2, [new Uint8Array([128, 255])])!
    assert.deepEqual([...u8p[0]], [0, 127 / 128])
    const f32p = soundPlanes(8, 2, 1, [new Float32Array([0.25]), new Float32Array([-0.25])])!
    assert.equal(f32p[1][0], -0.25)
    const f64 = soundPlanes(4, 1, 2, new Float64Array([0.5, -0.5]))!
    assert.deepEqual([...f64[0]], [0.5, -0.5])
    assert.equal(soundPlanes(1, 2, 4, new Int16Array(3)), null)
    assert.equal(soundPlanes(8, 2, 1, [new Float32Array(1)]), null)
    assert.equal(soundPlanes(99, 1, 1, new Int16Array(1)), null)
    assert.equal(soundPlanes(1, 0, 1, new Int16Array(1)), null)
    assert.equal(soundPlanes(1, 1, 1, null), null)
    assert.equal(soundPlanes(6, 1, 1, new Int16Array(1)), null)
    // 5.1 folds down to two, a lone centre channel feeds both; the low channel is left out.
    const six = Array.from({ length: 6 }, (_, i) => new Float32Array([i === 2 ? 1 : 0]))
    const two = downmix(six)
    assert.equal(two.length, 2)
    assert.ok(two[0][0] > 0.2 && two[0][0] === two[1][0])
    const lfe = Array.from({ length: 6 }, (_, i) => new Float32Array([i === 3 ? 1 : 0]))
    assert.equal(downmix(lfe)[0][0], 0)
    assert.equal(downmix([new Float32Array(1)]).length, 1)
    assert.equal(downmix(Array.from({ length: 3 }, () => new Float32Array(2))).length, 2)
})
