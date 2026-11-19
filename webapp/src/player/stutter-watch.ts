// The player's side of the stutter watch (WB18): reads the video every 2 s while it plays, and only
// then. Paused, buffering, seeking, ended, a hidden page, a stalled line: no timer runs, so an idle
// player costs nothing (a row that kept working on its own once held a 1 GB TV at 150% CPU, AGENTS.md).
// The rules and the readings are stutter.ts; what it writes to the log is fixed words and buckets.
import { log } from '../logs'
import type { Engine } from './engines'
import { bucketOf, engineTag, noteEngine, paceWord, qualityOf, SAMPLE_MS, StutterMonitor, type MarkResult, type Quality } from './stutter'

export interface StutterWatch {
    /** Whether this browser (or the engine drawing the picture) counts frames: Report stutter is offered only then. */
    supported: () => boolean
    /** Report stutter: marks this moment. `already` for a second press within five seconds. */
    press: () => MarkResult
    /** A new video starts: the watch and the readings begin again. */
    restart: () => void
    stop: () => void
}

export function watchStutter(video: HTMLVideoElement, engine: () => Engine | null, stalled: () => boolean): StutterWatch {
    const monitor = new StutterMonitor()
    let timer: ReturnType<typeof setInterval> | null = null
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
        monitor.tick({ now: performance.now(), position: video.currentTime, playing: playing(), rate: video.playbackRate, engine: engineTag(engine()?.name), quality: quality() })
    }
    const start = () => {
        if (timer !== null || !playing()) return
        read()
        timer = setInterval(read, SAMPLE_MS)
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
            const result = monitor.press(performance.now())
            log('I', 'watch', result === 'kept' ? 'the viewer marked a stutter' : 'the viewer marked a stutter again within five seconds (same mark)')
            return result
        },
        restart: () => {
            halt()
            monitor.restart()
        },
        stop,
    }
}
