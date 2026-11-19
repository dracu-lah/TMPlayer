// The Add chats picker's rough video counts (HX27, WB22): the asking, with its budget. A chat's count is the
// two filters' totals Telegram's search already carries; three chats at a time, kept for ten minutes (bounded),
// never kept for a failure. A flood wait of up to thirty seconds is waited out once by the call that got it
// while the others go on; a second one, or a longer one, is Telegram refusing: the rest of that ask and every ask
// for a while after it answer REFUSED without a call, and the page stops counting for its visit. It takes
// Telegram's call as a plain function, so `node --test` runs the budget against a fake account. An abort signal (the picker closed) stops what is not yet asked.
import { floodSeconds } from './flood.ts'

/** "Could not be read" (a failed call): the page may ask again, once, when the viewer asks for more. */
export const UNKNOWN = -1
/** "Telegram refused" (a flood wait that would not go away): not asked again for a while, and the page stops. */
export const REFUSED = -2
/** Chats counted at once, so the picker never floods Telegram. */
export const COUNT_BATCH = 3
/** A count is kept this long, and at most this many are kept. */
export const COUNT_KEEP_MS = 10 * 60_000
export const COUNT_KEEP_MAX = 4000
/** The longest flood wait a count sits out, once. */
export const COUNT_FLOOD_MAX_S = 30
/** After a refusal nothing is asked for this long, or the wait Telegram named when that is longer (at most five minutes). */
export const REFUSED_PAUSE_MS = 60_000
const REFUSED_PAUSE_MAX_MS = 5 * 60_000

export interface CountClock {
    now(): number
    sleep(ms: number): Promise<void>
}

const real: CountClock = { now: () => Date.now(), sleep: (ms) => new Promise((r) => setTimeout(r, ms)) }

export function createVideoCounter(ask: (chatId: number) => Promise<number>, clock: CountClock = real) {
    const kept = new Map<number, { count: number; time: number }>()
    let pausedUntil = 0
    /** Raised by `forget` (sign out): an answer that was in flight when it was raised is not kept. */
    let generation = 0

    const keep = (id: number, count: number) => {
        // Oldest first: a Map iterates in the order of insertion, and a new answer for a chat is inserted anew.
        kept.delete(id)
        kept.set(id, { count, time: clock.now() })
        while (kept.size > COUNT_KEEP_MAX) kept.delete(kept.keys().next().value as number)
    }

    async function one(chatId: number, signal?: AbortSignal): Promise<number> {
        const mine = generation
        for (let attempt = 0; ; attempt++) {
            // The picker closed: nothing more is asked, not even the second try after a flood wait.
            if (signal?.aborted) return UNKNOWN
            if (clock.now() < pausedUntil) return REFUSED
            try {
                const count = await ask(chatId)
                if (!Number.isFinite(count) || count < 0) return UNKNOWN
                if (mine === generation && !signal?.aborted) keep(chatId, count)
                return count
            } catch (e) {
                const seconds = floodSeconds(e as { message?: string; text?: string; seconds?: number })
                if (!seconds) return UNKNOWN
                if (attempt > 0 || seconds > COUNT_FLOOD_MAX_S) {
                    pausedUntil = Math.max(pausedUntil, clock.now() + Math.min(Math.max(seconds * 1000, REFUSED_PAUSE_MS), REFUSED_PAUSE_MAX_MS))
                    return REFUSED
                }
                await clock.sleep(seconds * 1000 + 250)
            }
        }
    }

    return {
        /** `chatIds`' rough video counts, by id: UNKNOWN for one that could not be read, REFUSED once Telegram refused. */
        async counts(chatIds: readonly number[], signal?: AbortSignal): Promise<Record<number, number>> {
            const now = clock.now()
            const out: Record<number, number> = {}
            const need: number[] = []
            for (const id of chatIds) {
                const hit = kept.get(id)
                if (hit && now - hit.time < COUNT_KEEP_MS) out[id] = hit.count
                else need.push(id)
            }
            for (let i = 0; i < need.length && !signal?.aborted; i += COUNT_BATCH) {
                await Promise.all(need.slice(i, i + COUNT_BATCH).map(async (id) => void (out[id] = await one(id, signal))))
            }
            return out
        },
        forget() {
            generation++
            kept.clear()
            pausedUntil = 0
        },
        /** For tests: how many answers are kept. */
        size: () => kept.size,
    }
}
