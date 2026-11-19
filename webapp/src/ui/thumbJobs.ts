// Thumbnail asks shared between cards and taken back when the last card wants none (HX54). Pure, so a
// test drives it; screens/thumbs.ts wires in Telegram, the cache and object URLs.
//
// One job per picture. Cards that want the same one share it. While the job waits for a slot (or for
// the cache to answer) the last card to leave withdraws it; once it is with Telegram it is let run to
// its end and stays joinable, so the next card that wants the picture shares the download in flight
// instead of starting a second one beside it.

import type { Slots } from './fetchSlots'

interface Job {
    promise: Promise<string>
    ctl: AbortController
    /** Cards still waiting for it. A caller with no signal never takes its ask back, so counts for good. */
    users: number
    /** Past the slot wait: with Telegram, not to be withdrawn. */
    started: boolean
    settled: boolean
}

export interface ThumbLoaderOptions {
    slots: Slots
    /** The picture's URL: from the cache, else from Telegram once `slot` is held (release it when the download ends). */
    run(key: string, slot: () => Promise<() => void>): Promise<string>
}

const aborted = () => new DOMException('aborted', 'AbortError')

export function createThumbLoader(o: ThumbLoaderOptions) {
    const jobs = new Map<string, Job>()

    function start(key: string): Job {
        const ctl = new AbortController()
        const job: Job = { ctl, users: 0, started: false, settled: false, promise: undefined as never }
        const slot = async () => {
            const release = await o.slots.acquire(ctl.signal)
            // Taken back in the moment the slot came: it is not downloaded, and the slot goes on.
            if (ctl.signal.aborted) {
                release()
                throw aborted()
            }
            job.started = true
            return release
        }
        job.promise = o.run(key, slot).then((url) => {
            if (ctl.signal.aborted) throw aborted()
            return url
        })
        job.promise.then(
            () => (job.settled = true),
            () => {
                job.settled = true
                // A failed or withdrawn ask is not remembered: the next card asks again.
                if (jobs.get(key) === job) jobs.delete(key)
            },
        )
        return job
    }

    /** The picture's URL. With `signal`, aborting it takes this ask back (rejects with an AbortError). */
    function load(key: string, signal?: AbortSignal): Promise<string> {
        if (signal?.aborted) return Promise.reject(aborted())
        let job = jobs.get(key)
        if (!job) jobs.set(key, (job = start(key)))
        const mine = job
        mine.users++
        if (!signal) return mine.promise
        return new Promise<string>((resolve, reject) => {
            const onAbort = () => {
                if (mine.settled) return
                reject(aborted())
                if (--mine.users > 0 || mine.started) return
                // Nobody left wants it and it has not begun: withdrawn, and the next ask starts afresh.
                if (jobs.get(key) === mine) jobs.delete(key)
                mine.ctl.abort()
            }
            signal.addEventListener('abort', onAbort, { once: true })
            mine.promise.then(
                (url) => (signal.removeEventListener('abort', onAbort), resolve(url)),
                (e) => (signal.removeEventListener('abort', onAbort), reject(e)),
            )
        })
    }

    return { load, jobs: () => jobs.size }
}
