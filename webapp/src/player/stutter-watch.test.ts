// `node --test src/player/stutter-watch.test.ts` (pnpm test:player): when the stutter watch's timer runs. It runs only while the
// video plays on a visible page with no stall, slows down once the watch has answered, and follows a stall that clears without a media event.
import assert from 'node:assert/strict'
import { afterEach, beforeEach, mock, test } from 'node:test'
import { SAMPLE_MS, SAMPLE_SLOW_MS } from './stutter.ts'
import { watchStutter, type StutterWatch } from './stutter-watch.ts'

/** A video element as far as the watch reads one: state, events and the frame counters. */
class FakeVideo extends EventTarget {
    paused = false
    ended = false
    seeking = false
    readyState = 4
    currentTime = 0
    playbackRate = 1
    total = 0
    getVideoPlaybackQuality() {
        return { droppedVideoFrames: 0, totalVideoFrames: this.total }
    }
}

const doc = new EventTarget() as EventTarget & { hidden: boolean }
doc.hidden = false
Object.defineProperty(globalThis, 'document', { configurable: true, value: doc })

let now = 0
let stalled = false
let video: FakeVideo
let watch: StutterWatch | null = null
const make = () => {
    video = new FakeVideo()
    watch = watchStutter(video as unknown as HTMLVideoElement, () => null, () => stalled, () => now)
    return watch
}
/** Lets [ms] of the page's time pass, playing along: the position moves with the clock while the video plays. */
const advance = (ms: number) => {
    for (let t = 0; t < ms; t += 1000) {
        now += 1000
        if (!video.paused && !stalled) {
            video.currentTime += 1
            video.total += 24
        }
        mock.timers.tick(1000)
    }
}

beforeEach(() => {
    mock.timers.enable({ apis: ['setInterval'] })
    now = 1000
    stalled = false
    doc.hidden = false
})
afterEach(() => {
    watch?.stop()
    watch = null
    mock.timers.reset()
})

test('no timer before the video plays, one while it plays, none when it pauses, waits or ends', () => {
    const w = make()
    assert.equal(w.running(), false)
    video.dispatchEvent(new Event('playing'))
    assert.equal(w.running(), true)
    for (const stop of ['pause', 'waiting', 'ended', 'emptied', 'stalled', 'error']) {
        video.dispatchEvent(new Event('playing'))
        assert.equal(w.running(), true, `playing before ${stop}`)
        if (stop === 'pause') video.paused = true
        if (stop === 'ended') video.ended = true
        video.dispatchEvent(new Event(stop))
        assert.equal(w.running(), false, stop)
        video.paused = false
        video.ended = false
    }
})

test('a hidden page stops it, and coming back starts it again', () => {
    const w = make()
    video.dispatchEvent(new Event('playing'))
    doc.hidden = true
    doc.dispatchEvent(new Event('visibilitychange'))
    assert.equal(w.running(), false)
    doc.hidden = false
    doc.dispatchEvent(new Event('visibilitychange'))
    assert.equal(w.running(), true)
})

test('a seek stops it until the video plays again; a seek on a paused video starts nothing', () => {
    const w = make()
    video.dispatchEvent(new Event('playing'))
    video.seeking = true
    video.dispatchEvent(new Event('seeking'))
    assert.equal(w.running(), false)
    video.seeking = false
    video.dispatchEvent(new Event('seeked'))
    assert.equal(w.running(), true)
    video.paused = true
    video.dispatchEvent(new Event('pause'))
    video.dispatchEvent(new Event('seeked'))
    assert.equal(w.running(), false)
})

test('while a stall holds nothing runs, and when it clears with no media event the watch starts by itself', () => {
    const w = make()
    video.dispatchEvent(new Event('playing'))
    assert.equal(w.running(), true)
    stalled = true
    w.refresh()
    assert.equal(w.running(), false, 'stalled')
    // A start that never fires: nothing starts on its own while the stall holds, even with media events.
    video.dispatchEvent(new Event('canplay'))
    assert.equal(w.running(), false)
    stalled = false
    w.refresh()
    assert.equal(w.running(), true, 'the stall cleared and no playing or seeked event came')
    // A refresh while it is paused starts nothing.
    video.paused = true
    video.dispatchEvent(new Event('pause'))
    w.refresh()
    assert.equal(w.running(), false)
})

test('after the answer the readings go on at a third of the wakeups, and a new video starts at the fast pace again', () => {
    const w = make()
    const ticks: number[] = []
    const real = video.getVideoPlaybackQuality.bind(video)
    video.getVideoPlaybackQuality = () => (ticks.push(now), real())
    video.dispatchEvent(new Event('playing'))
    advance(20_000)
    const early = ticks.length
    // Two seconds apart while the watch is still reading, then six apart.
    const gaps = ticks.slice(1).map((t, i) => t - ticks[i])
    assert.ok(gaps.slice(0, 5).every((g) => g === SAMPLE_MS), `fast at first: ${gaps}`)
    assert.ok(gaps.at(-1) === SAMPLE_SLOW_MS, `slow after the answer: ${gaps}`)
    ticks.length = 0
    advance(60_000)
    assert.ok(ticks.length <= 60_000 / SAMPLE_SLOW_MS + 1, `${ticks.length} readings in a minute after the answer, ${early} in the first 20 s`)
    // Another video: the watch is new, the pace is fast again.
    w.restart()
    assert.equal(w.running(), false)
    ticks.length = 0
    video.dispatchEvent(new Event('playing'))
    advance(5_000)
    assert.ok(ticks.length >= 3, `fast again: ${ticks.length}`)
})

test('Report stutter marks with a reading of its own, a second press says already, and a stopped watch cannot be pressed into running', () => {
    const w = make()
    video.dispatchEvent(new Event('playing'))
    advance(4_000)
    assert.equal(w.press(), 'kept')
    now += 1000
    assert.equal(w.press(), 'already')
    w.stop()
    video.dispatchEvent(new Event('playing'))
    assert.equal(w.running(), false, 'listeners are gone')
})

test('without frame counters nothing is offered, with an engine that counts it is', () => {
    const w = make()
    assert.equal(w.supported(), true)
    const bare = watchStutter({ addEventListener() {}, removeEventListener() {} } as unknown as HTMLVideoElement, () => null, () => false)
    assert.equal(bare.supported(), false)
    bare.stop()
    const canvas = watchStutter({ addEventListener() {}, removeEventListener() {} } as unknown as HTMLVideoElement, () => ({ name: 'canvas', detail: '', destroy() {}, quality: () => ({ dropped: 0, total: 0 }) }), () => false)
    assert.equal(canvas.supported(), true)
    canvas.stop()
})
