// The translation SubMaker makes on request, as the player lives with it: one job at a time for the
// video, running in the background while playback goes on, its subtitle laid over the video and
// replaced in place as it grows (at most once in 20 seconds until it is final, so a long file never
// stutters the picture), and its words for the progress line. The polling itself is submaker.ts;
// nothing here knows the address it fetches (the entry's url is passed through and never shown).
import { createSignal, type Accessor } from 'solid-js'
import { fetchTextWith, giveConsent, pageSleep, startJob, type Entry, type FetchText, type Job, type Why } from './submaker'

/** The most often a growing subtitle replaces the track on screen. */
export const LAY_EVERY_MS = 20_000

export type JobView =
    | { phase: 'running'; language: string; lines: number }
    | { phase: 'done'; language: string; lines: number }
    | { phase: 'failed'; language: string; lines: number; why: Why }

export interface JobsOptions {
    /** Which video: a job is one per video and language. */
    videoKey: () => string
    /** Lays the subtitle over the video; `replace` swaps the track laid before for it. Throws when it cannot be read. */
    lay: (srt: string, language: string, replace: boolean) => Promise<void>
    /** The video's length in seconds, when known: a translation reaching it is done. */
    duration: () => number
    foreground?: () => boolean
    /** A translation began or was cancelled: a word for the player's own flash (not the progress line). */
    said?: (what: 'started' | 'cancelled', language: string) => void
    fetchText?: FetchText
}

export interface Jobs {
    view: Accessor<JobView | null>
    start: (entry: Entry, language: string) => void
    cancel: () => void
    /** Closes the finished or failed line. */
    dismiss: () => void
    retry: () => void
    /** The player closed or the video changed: nothing keeps running. */
    dispose: () => void
}

const visible = () => typeof document === 'undefined' || document.visibilityState !== 'hidden'

export function createJobs(o: JobsOptions): Jobs {
    const [view, setView] = createSignal<JobView | null>(null)
    let job: Job | null = null
    let current: { entry: Entry; language: string } | null = null
    let timer: ReturnType<typeof setTimeout> | undefined
    let pending: { srt: string; final: boolean } | null = null
    let lastLaid = 0
    let laying = false
    let laidOnce = false
    let generation = 0

    const clear = () => {
        clearTimeout(timer)
        timer = undefined
        pending = null
    }

    const flush = async (mine: number) => {
        timer = undefined
        if (laying || !pending || mine !== generation || !current) return
        const next = pending
        pending = null
        laying = true
        try {
            await o.lay(next.srt, current.language, laidOnce)
            laidOnce = true
            lastLaid = Date.now()
        } catch {
            // A file that does not read now may read when it has grown: the next one is tried.
        } finally {
            laying = false
        }
        if (pending && mine === generation) schedule(mine, false)
    }

    const schedule = (mine: number, final: boolean) => {
        if (final) {
            clearTimeout(timer)
            return void flush(mine)
        }
        if (timer !== undefined) return
        timer = setTimeout(() => void flush(mine), laidOnce ? Math.max(0, lastLaid + LAY_EVERY_MS - Date.now()) : 0)
    }

    const stop = () => {
        generation++
        job?.cancel()
        job = null
        clear()
    }

    const begin = (entry: Entry, language: string) => {
        stop()
        const mine = generation
        current = { entry, language }
        laidOnce = false
        lastLaid = 0
        setView({ phase: 'running', language, lines: 0 })
        const length = o.duration()
        const options = {
            url: entry.url,
            fetchText: o.fetchText ?? fetchTextWith(),
            sleep: pageSleep,
            now: () => Date.now(),
            foreground: o.foreground ?? visible,
            // The last cue ends near the video's end: the translation is whole. A little short of it counts.
            sourceEndMs: length > 600 ? Math.round(length * 1000) - 120_000 : undefined,
            onUpdate: (u: { lines: number; srt: string; final: boolean }) => {
                if (mine !== generation) return
                pending = { srt: u.srt, final: u.final }
                setView({ phase: u.final ? 'done' : 'running', language, lines: u.lines })
                schedule(mine, u.final)
            },
        }
        // The same video and language asked twice joins the job running; its own updates keep going to this one's lay.
        job = startJob(o.videoKey(), language, options)
        void job.done.then((r) => {
            if (mine !== generation) return
            if (r.state === 'failed') setView({ phase: 'failed', language, lines: r.lines, why: r.why })
            else if (r.state === 'done') setView({ phase: 'done', language, lines: r.lines })
            // Cancelled: the view was changed by whoever cancelled it.
        })
    }

    return {
        view,
        start(entry, language) {
            giveConsent(language)
            begin(entry, language)
            o.said?.('started', language)
        },
        cancel() {
            const cur = current
            stop()
            // The subtitle laid so far stays; the line is simply gone.
            if (cur) {
                setView(null)
                o.said?.('cancelled', cur.language)
            }
        },
        dismiss: () => setView(null),
        retry() {
            if (current) begin(current.entry, current.language)
        },
        dispose() {
            stop()
            current = null
            setView(null)
        },
    }
}
