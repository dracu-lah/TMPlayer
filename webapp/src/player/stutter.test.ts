// `node --test src/player/stutter.test.ts` (pnpm test:player): the playback watch against core's own rules, the marks and their buckets.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import * as core from '../rules/core/tmplayer-rules.mjs'
import { clearLogs, suppliedParts } from '../logs.ts'
import {
    DEBOUNCE_MS,
    MAX_MARKS,
    MAX_READINGS,
    PlaybackWatch,
    SETTLE_MS,
    StutterMarks,
    StutterMonitor,
    WINDOW_MS,
    bucketOf,
    deviceLines,
    noteEngine,
    engineTag,
    paceWord,
    qualityOf,
    rateWord,
    readingLine,
    symptomOf,
    type Reading,
} from './stutter.ts'

test('the thresholds are core\'s: the settle and the window, and the symptom over a grid of readings', () => {
    assert.equal(SETTLE_MS, core.stutterSettleMs())
    assert.equal(WINDOW_MS, core.stutterWindowMs())
    const speeds = [0, 0.3, 0.89, 0.9, 1, 1.4, 3, -1, NaN, Infinity]
    const counts = [0, 1, 9, 10, 11, 100, 1000, -5, NaN, 3e9]
    let checked = 0
    for (const speed of speeds)
        for (const dropped of counts)
            for (const frames of counts)
                for (const underruns of [0, 2, 3, 99, -1, NaN])
                    for (const late of [false, true]) {
                        assert.equal(symptomOf(speed, dropped, frames, underruns, late), core.stutterSymptom(speed, dropped, frames, underruns, late), `${speed} ${dropped} ${frames} ${underruns} ${late}`)
                        checked++
                    }
    assert.ok(checked > 10_000)
})

/** Drives a watch with one sample every [step] ms of wall time for [seconds], the position moving at [pace] of the speed asked for. */
function run(watch: PlaybackWatch, seconds: number, o: { pace?: number; dropShare?: number; fps?: number; step?: number; speed?: number; start?: number } = {}) {
    const step = o.step ?? 2000
    const fps = o.fps ?? 24
    let wall = o.start ?? 0
    let position = 0
    let total = 0
    let dropped = 0
    let answer: ReturnType<PlaybackWatch['sample']> = null
    for (let t = 0; t <= seconds * 1000; t += step) {
        answer = watch.sample(wall, position, true, total - dropped, dropped, 0, o.speed ?? 1) ?? answer
        wall += step
        position += step * (o.pace ?? 1) * (o.speed ?? 1)
        const frames = Math.round((fps * step) / 1000)
        total += frames
        dropped += Math.round(frames * (o.dropShare ?? 0))
    }
    return answer
}

test('a clean 12 s of playing after the 2 s settle keeps up, and says so once', () => {
    const w = new PlaybackWatch()
    assert.equal(run(w, 13), null, 'still inside settle plus window')
    const done = run(new PlaybackWatch(), 20)
    assert.equal(done, 'kept-up')
    assert.equal(w.sample(99_999, 0, true, 0, 0), null)
})

test('a clock that runs slow, or a tenth of the frames dropped, cannot keep up', () => {
    assert.equal(run(new PlaybackWatch(), 20, { pace: 0.6 }), 'cannot-keep-up')
    const w = new PlaybackWatch()
    assert.equal(run(w, 20, { dropShare: 0.25 }), 'cannot-keep-up')
    assert.equal(w.figures().symptom, 'dropped')
    assert.ok(w.figures().frames > 0)
})

test('0.75x with the position moving at 0.75 is on time, not slow', () => {
    assert.equal(run(new PlaybackWatch(), 20, { speed: 0.75 }), 'kept-up')
})

test('a seek or a pause starts the settle again and what is not playing is not counted', () => {
    const w = new PlaybackWatch()
    for (let t = 0; t < 8000; t += 2000) w.sample(t, t, true, t / 40, 0)
    w.interrupted()
    assert.equal(w.sample(10_000, 0, false, 0, 0), null)
    // The jump after a seek is not slow playback.
    assert.equal(w.sample(12_000, 300_000, true, 300, 0), null)
    assert.equal(w.figures().symptom, null)
    w.reset()
    assert.equal(w.figures().playedSeconds, 0)
})

test('a gap longer than five seconds (the page was away) is not counted as slow', () => {
    const w = new PlaybackWatch()
    for (let t = 0; t <= 6000; t += 2000) w.sample(t, t, true, t / 40, 0)
    w.sample(60_000, 8000, true, 400, 0)
    assert.equal(w.figures().symptom, null)
})

test('garbage samples read as no evidence and never throw', () => {
    const w = new PlaybackWatch()
    for (const bad of [NaN, Infinity, -1, 1e300]) {
        assert.doesNotThrow(() => w.sample(bad, bad, true, bad, bad, bad, bad))
        assert.doesNotThrow(() => w.sample(1000, 1000, true, 1, 1, 1, bad))
    }
    assert.equal(w.done, false, 'no answer from nothing')
})

test('qualityOf reads the standard counters and nothing else', () => {
    assert.deepEqual(qualityOf({ getVideoPlaybackQuality: () => ({ droppedVideoFrames: 3, totalVideoFrames: 100 }) }), { dropped: 3, total: 100 })
    assert.equal(qualityOf({}), null, 'an old WebKit and some TV engines have no counters')
    assert.equal(qualityOf({ getVideoPlaybackQuality: () => ({ droppedVideoFrames: '3', totalVideoFrames: 100 }) }), null)
    assert.equal(qualityOf({ getVideoPlaybackQuality: () => ({ droppedVideoFrames: -1, totalVideoFrames: 100 }) }), null)
    assert.equal(qualityOf({ getVideoPlaybackQuality: () => ({ droppedVideoFrames: NaN, totalVideoFrames: 1 }) }), null)
    assert.equal(qualityOf({ getVideoPlaybackQuality: () => { throw new Error('no') } }), null)
    assert.equal(qualityOf({ getVideoPlaybackQuality: () => null as never }), null)
    assert.deepEqual(qualityOf({ getVideoPlaybackQuality: () => ({ droppedVideoFrames: 9, totalVideoFrames: 2 }) }), { dropped: 9, total: 9 })
})

test('buckets are the apps: 0, 1, 2-4, 5-9, 10-19, 20-49, 50+, unknown', () => {
    const got = [-1, NaN, 0, 1, 2, 4, 5, 9, 10, 19, 20, 49, 50, 1e9].map(bucketOf)
    assert.deepEqual(got, ['unknown', 'unknown', '0', '1', '2-4', '2-4', '5-9', '5-9', '10-19', '10-19', '20-49', '20-49', '50+', '50+'])
    for (let n = 0; n < 200; n++) assert.ok(['0', '1', '2-4', '5-9', '10-19', '20-49', '50+'].includes(bucketOf(n)))
})

test('words: pace, speed and engine are from fixed lists, anything else reads as other or unknown', () => {
    assert.deepEqual([0, 49, 50, 89, 90, 110, 111, -4, NaN].map(paceWord), ['under-50', 'under-50', '50-89', '50-89', 'on-time', 'on-time', 'over-110', 'unknown', 'unknown'])
    assert.equal(rateWord(0.75), '0.75x')
    assert.equal(rateWord(1.2345), 'other')
    assert.equal(engineTag('libav'), 'libav')
    assert.equal(engineTag('Kerala.Crime.Files.mkv'), 'unknown')
    assert.equal(engineTag(42), 'unknown')
})

const reading = (at: number, o: Partial<Reading> = {}): Reading => ({ at, dropped: 2, frames: 48, pacePercent: 100, engine: 'native', rate: 1, ...o })

test('a reading line holds words and buckets, and seconds from the first, never a clock or a raw count', () => {
    const line = readingLine(reading(1_234_567, { dropped: 7, frames: 137 }), 1_230_000)
    assert.equal(line, '  +4s dropped=5-9 of=50+ pace=on-time engine=native speed=1x')
    // A hostile engine name, rate or number cannot put text in a line.
    const odd = readingLine({ at: NaN, dropped: NaN, frames: 1e99, pacePercent: Infinity, engine: 'My Film (2019).mkv', rate: 7 }, 0)
    assert.equal(odd, '  +0s dropped=unknown of=50+ pace=unknown engine=unknown speed=other')
    assert.doesNotMatch(odd, /Film|2019|mkv/u)
})

test('a second press within five seconds is the same mark; after that a new one', () => {
    const m = new StutterMarks()
    assert.equal(m.mark([reading(1000)], null, 10_000), 'kept')
    assert.equal(m.mark([reading(1500)], null, 10_000 + DEBOUNCE_MS - 1), 'already')
    assert.equal(m.count, 1)
    assert.equal(m.mark([reading(9000)], 'slow', 10_000 + DEBOUNCE_MS), 'kept')
    assert.equal(m.count, 2)
})

test('marks keep the last five, a minute and 40 readings each, and a later mark adds only what is new', () => {
    const m = new StutterMarks()
    const trace = Array.from({ length: 100 }, (_, i) => reading(i * 2000))
    m.mark(trace, null, 1_000_000)
    const first = m.reportLines()
    assert.equal(first.filter((l) => l.startsWith('  +')).length, 30, 'a minute at 2 s is 30 readings')
    const more = [...trace, ...Array.from({ length: 5 }, (_, i) => reading(200_000 + i * 2000))]
    m.mark(more, null, 1_010_000)
    const second = m.reportLines().filter((l) => l.startsWith('Marked'))
    assert.equal(second.length, 2)
    assert.ok(m.reportLines().filter((l) => l.startsWith('  +')).length <= 30 + 5)
    for (let i = 0; i < 10; i++) m.mark([reading(300_000 + i * 100_000)], null, 2_000_000 + i * 10_000)
    assert.equal(m.count, MAX_MARKS)
    const big = new StutterMarks()
    big.mark(Array.from({ length: 500 }, (_, i) => reading(i * 100)), null, 0)
    assert.ok(big.reportLines().filter((l) => l.startsWith('  +')).length <= MAX_READINGS)
})

test('a mark with no readings is still kept: the moment is the news', () => {
    const m = new StutterMarks()
    assert.equal(m.mark([], null, 5), 'kept')
    assert.deepEqual(m.reportLines(), ['Playback trace: 1 moment(s) the viewer marked as stuttering, oldest first.', 'Marked, 0 reading(s), watch said nothing'])
})

test('garbage marks: wrong typed readings, a NaN clock and a huge trace never throw and never leak a word', () => {
    const m = new StutterMarks()
    const junk = [{}, null, 'x', { at: 'a', dropped: {}, engine: ['Show S01E02'], rate: 'fast' }, reading(NaN)] as unknown as Reading[]
    assert.doesNotThrow(() => m.mark(junk.filter((j) => j !== null && typeof j === 'object'), null, NaN))
    const text = m.reportLines().join('\n')
    assert.doesNotMatch(text, /Show|S01E02|fast/u)
    assert.equal(new StutterMarks().reportLines().length, 0)
})

test('the monitor: a clean video gives kept-up once, a slow one cannot keep up, and Report stutter marks the minute', () => {
    const answers: string[] = []
    const mon = new StutterMonitor()
    mon.onAnswer = (a) => answers.push(a)
    let total = 0
    let dropped = 0
    for (let i = 0; i < 12; i++) {
        total += 48
        dropped += 14
        mon.tick({ now: i * 2000, position: i * 2 * 0.95, playing: true, rate: 1, engine: 'native', quality: { dropped, total } })
    }
    assert.deepEqual(answers, ['cannot-keep-up'])
    assert.equal(mon.press(30_000), 'kept')
    assert.equal(mon.press(31_000), 'already')
    const lines = mon.marks.reportLines()
    assert.match(lines[1], /watch said dropped/u)
    assert.ok(lines.some((l) => /dropped=10-19 of=20-49 pace=on-time engine=native speed=1x/u.test(l)), lines.join('\n'))
    mon.restart()
    assert.equal(mon.marks.count, 1, 'marks outlive a video: they are the viewer\'s, for Copy details')
    assert.match(suppliedParts().marks!.join('\n'), /1 moment\(s\) the viewer marked/u)
    clearLogs()
    assert.equal(mon.marks.count, 0, 'signing out clears them')
    assert.deepEqual(suppliedParts().marks, [])
})

test('the device lines are fixed words and buckets, and a hostile navigator reads as unknown', () => {
    noteEngine('libav')
    const lines = deviceLines({ video: { getVideoPlaybackQuality() {} }, nav: { hardwareConcurrency: 8, deviceMemory: 4 }, win: { MediaSource: 1 } })
    assert.deepEqual(lines, ['Engine in use: libav', 'Frame counters: yes', 'Media Source: yes, managed: no, WebCodecs: no', 'Processor threads: 5-9, memory class: 4 GB or so'])
    noteEngine('Some Show.mkv')
    const odd = deviceLines({ video: {}, nav: { hardwareConcurrency: 'many', deviceMemory: 3.7 }, win: {} })
    assert.deepEqual(odd, ['Engine in use: unknown', 'Frame counters: no', 'Media Source: no, managed: no, WebCodecs: no', 'Processor threads: unknown, memory class: unknown'])
    assert.doesNotThrow(() => deviceLines())
})

test('the monitor without frame counters still measures the pace, and pausing starts the settle again', () => {
    const answers: string[] = []
    const mon = new StutterMonitor()
    mon.onAnswer = (a) => answers.push(a)
    for (let i = 0; i < 12; i++) mon.tick({ now: i * 2000, position: i * 2 * 0.5, playing: true, rate: 1, engine: 'remux', quality: null })
    assert.deepEqual(answers, ['cannot-keep-up'])
    const second = new StutterMonitor()
    second.onAnswer = (a) => answers.push(a)
    for (let i = 0; i < 4; i++) second.tick({ now: i * 2000, position: i * 2, playing: true, rate: 1, engine: 'native', quality: null })
    second.tick({ now: 8000, position: 6, playing: false, rate: 1, engine: 'native', quality: null })
    second.seeked()
    assert.deepEqual(answers, ['cannot-keep-up'])
})
