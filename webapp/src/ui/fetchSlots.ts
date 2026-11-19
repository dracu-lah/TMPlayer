// A few slots for fetches that must not all run at once (thumbnails, HX54), where one waiting for
// a slot can be taken back: a card that left the screen asks for nothing, instead of the ask
// running to the end behind the ones the viewer is looking at. Pure, so a test drives it.

export interface Slots {
    /** Resolves with the slot's release once one is free; rejects with an AbortError when `signal` aborts first (nothing is then held). */
    acquire(signal?: AbortSignal): Promise<() => void>
    /** Slots in use and asks waiting, for tests and the idle check. */
    readonly state: { active: number; waiting: number }
}

const aborted = () => new DOMException('aborted', 'AbortError')

export function createSlots(limit: number): Slots {
    let active = 0
    const queue: { grant: () => void }[] = []
    const free = () => {
        // Hands the slot straight on to the next one waiting, else gives it back.
        const next = queue.shift()
        if (next) next.grant()
        else active--
    }
    return {
        acquire(signal) {
            if (signal?.aborted) return Promise.reject(aborted())
            return new Promise((resolve, reject) => {
                let done = false
                const release = () => {
                    if (done) return
                    done = true
                    free()
                }
                if (active < limit) {
                    active++
                    return resolve(release)
                }
                const entry = { grant: () => resolve(release) }
                queue.push(entry)
                signal?.addEventListener(
                    'abort',
                    () => {
                        const at = queue.indexOf(entry)
                        if (at < 0) return
                        queue.splice(at, 1)
                        reject(aborted())
                    },
                    { once: true },
                )
            })
        },
        get state() {
            return { active, waiting: queue.length }
        },
    }
}
