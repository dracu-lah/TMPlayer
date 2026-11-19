// The player's side of the stutter watch (WB18): reads the video every 2 s while it plays, and only
// then. Paused, buffering, seeking, ended, a hidden page, a stalled line: no timer runs, so an idle
// player costs nothing (a row that kept working on its own once held a 1 GB TV at 150% CPU, AGENTS.md).
// Once the watch has given its one answer the readings go on at a third of the wakeups, because
// Report stutter marks the last minute of them and a stutter after a clean start is the common one.
// The rules and the readings are stutter.ts; what it writes to the log is fixed words and buckets.
import { log } from '../logs.ts'
import type { Engine } from './engines.ts'
import { bucketOf, engineTag, noteEngine, paceWord, qualityOf, SAMPLE_MS, SAMPLE_SLOW_MS, StutterMonitor, type MarkResult, type Quality } from './stutter.ts'

export interface StutterWatch {
    /** Whether this browser (or the engine drawing the picture) counts frames: Report stutter is offered only then. */
    supported: () => boolean
    /** Report stutter: marks this moment. `already` for a second press within five seconds. */
    press: () => MarkResult
    /** A new video starts: the watch and the readings begin again. */
    restart: () => void
    /** Something outside the video's own events changed (a stall cleared or began): the timer follows. */
    refresh: () => void
    /** Whether the readings run now (for the tests). */
    running: () => boolean
    stop: () => void
}

export function watchStutter(video: HTMLVideoElement, engine: () => Engine | null, stalled: () => boolean, clock: () => number = () => performance.now()): StutterWatch {
    const monitor = new StutterMonitor()
    let timer: ReturnType<typeof setInterval> | null = null
    let period = SAMPLE_MS
    const quality = (): Quality | null => {
        try {
            return engine()?.quality?.() ?? qualityOf(video)
        } catch {
            return null
        }
    }
    const playing = () => !video.paused && !video.ended && !video.seeking && video.readyState >= 3 && !stalled() && !document.hidden
    const read = () => {
        noteEngine(engine()?.name)
        monitor.tick({ now: clock(), position: video.currentTime, playing: playing(), rate: video.playbackRate, engine: engineTag(engine()?.name), quality: quality() })
        // The answer is in: the same readings, a third as often.
        if (monitor.watch.done && period === SAMPLE_MS && timer !== null) {
            clearInterval(timer)
            period = SAMPLE_SLOW_MS
            timer = setInterval(read, period)
        }
    }
    const start = () => {
        if (timer !== null || !playing()) return
        read()
        if (timer === null) timer = setInterval(read, period)
    }
    const halt = () => {
        if (timer !== null) clearInterval(timer)
        timer = null
        monitor.interrupted()
    }
    const jump = () => {
        halt()
        monitor.seeked()
    }
    monitor.onAnswer = (answer, f) =>
        log('I', 'watch', `${answer === 'kept-up' ? 'kept up' : 'could not keep up'}, pace ${paceWord(f.speedPercent)}, dropped ${bucketOf(f.dropped)} of ${bucketOf(f.frames)}, sound gaps ${bucketOf(f.underruns)}, symptom ${f.symptom ?? 'none'}, engine ${engineTag(engine()?.name)}`)
    const onVisible = () => (document.hidden ? halt() : start())
    const stopping = ['pause', 'waiting', 'ended', 'emptied', 'stalled', 'error'] as const
    const starting = ['playing', 'canplay', 'seeked'] as const
    for (const e of stopping) video.addEventListener(e, halt)
    for (const e of starting) video.addEventListener(e, start)
    video.addEventListener('seeking', jump)
    document.addEventListener('visibilitychange', onVisible)
    const stop = () => {
        halt()
        for (const e of stopping) video.removeEventListener(e, halt)
        for (const e of starting) video.removeEventListener(e, start)
        video.removeEventListener('seeking', jump)
        document.removeEventListener('visibilitychange', onVisible)
    }
    return {
        supported: () => !!engine()?.quality || typeof video.getVideoPlaybackQuality === 'function',
        press: () => {
            if (playing()) read()
            const result = monitor.press(clock())
            log('I', 'watch', result === 'kept' ? 'the viewer marked a stutter' : 'the viewer marked a stutter again within five seconds (same mark)')
            return result
        },
        restart: () => {
            halt()
            period = SAMPLE_MS
            monitor.restart()
        },
        refresh: () => (stalled() ? halt() : start()),
        running: () => timer !== null,
        stop,
    }
}
