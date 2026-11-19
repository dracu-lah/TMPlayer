import { call } from '../tg/rpc'
import { cachedThumb } from '../store'
import { createSlots } from '../ui/fetchSlots'

// Thumbnails: a few at a time, only for cards on screen, kept in the Cache API and for the session.
// A card that leaves the screen takes its ask back (HX54): one still waiting for a slot never runs,
// one already with Telegram finishes into the cache, and a hard scroll over hundreds of cards asks for the few
// the viewer stops on. Many cards may want one picture (a title in two rows): the fetch is shared and
// is taken back only when the last of them has.

/** Fetches with Telegram at once. */
const THUMB_SLOTS = 4
const slots = createSlots(THUMB_SLOTS)

interface Job {
    promise: Promise<string>
    ctl: AbortController
    /** Askers still waiting. A caller with no signal never takes its ask back, so counts for good. */
    users: number
    settled: boolean
}
const jobs = new Map<string, Job>()

const aborted = () => new DOMException('aborted', 'AbortError')

function start(key: string): Job {
    const ctl = new AbortController()
    const job: Job = {
        ctl,
        users: 0,
        settled: false,
        promise: cachedThumb(key, async () => {
            // The slot guards the ask to Telegram only: a picture already on this device needs none. Taking the
            // ask back counts while it waits; one with Telegram runs to its end and keeps its slot (so the
            // cap holds) and its picture goes to the cache for the next card that wants it.
            const release = await slots.acquire(ctl.signal)
            try {
                return await call('thumb', [key])
            } finally {
                release()
            }
        }).then((blob) => {
            if (ctl.signal.aborted) throw aborted()
            return URL.createObjectURL(blob)
        }),
    }
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

/** The thumbnail's object URL. With `signal`, aborting it takes this ask back (rejects with an AbortError). */
export function loadThumb(key: string, signal?: AbortSignal): Promise<string> {
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
            if (--mine.users <= 0) {
                // Nobody left wants it: the next ask starts afresh rather than joining one being withdrawn.
                if (jobs.get(key) === mine) jobs.delete(key)
                mine.ctl.abort()
            }
        }
        signal.addEventListener('abort', onAbort, { once: true })
        mine.promise.then(
            (url) => (signal.removeEventListener('abort', onAbort), resolve(url)),
            (e) => (signal.removeEventListener('abort', onAbort), reject(e)),
        )
    })
}
