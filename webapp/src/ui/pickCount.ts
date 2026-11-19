// What the Add chats picker counts, and how much of it (WB22). The counts only rank the Recommended band, so a
// whole account of 3,000 chats is never counted behind the list: a visit has a budget of chats, spent first on
// the head start (Saved Messages and the head of the list), then on rows the viewer stayed on (the dwell of
// ui/dwell.ts, as Home's rows), and the viewer can ask to look in more chats, which counts the next ones in
// scanOrder. Three at a time, nothing at all once Telegram refused, nothing once the picker closed. Pure: the
// asking lives in the screen, the cache in tg/counts.ts.

/** Chats counted in one visit before the viewer asks for more: the head start (60) and some rows they stayed on. */
export const VISIT_BUDGET = 150
/** Chats added to the budget each time the viewer asks to look in more. */
export const MORE_STEP = 100
/** A row has to stay on screen this long before it is counted (Home's rows wait 250 ms; the picker is slower to scroll through). */
export const COUNT_DWELL_MS = 400
/** Chats counted at once (tg/counts.ts COUNT_BATCH). */
export const AT_ONCE = 3

export interface CountPlan {
    /** Chats are counted in this order once the viewer has asked for more. */
    setScanOrder(ids: readonly number[]): void
    /** A chat the head start counts: it is spent from the budget and never asked for twice. */
    reserve(ids: readonly number[]): void
    /** A row the viewer stayed on. Counted next if the budget allows; ignored when it is counted already. */
    offer(id: number): void
    /** The viewer asked to look in more chats: the budget grows and the scan begins. */
    more(): void
    /** Telegram refused: nothing is asked for the rest of the visit. */
    refuse(): void
    /** The picker closed. */
    close(): void
    /** The next chats to count, at most `n`, and each is spent. Empty when there is nothing to ask for, or the budget is spent. */
    take(n?: number): number[]
    /** A chat that could not be read is asked once more when the viewer asks for more; a second failure is final. */
    retryLater(ids: readonly number[]): void
    readonly spent: number
    readonly allowance: number
    readonly refused: boolean
    /** Whether the budget is spent while chats of the list were never asked for, which is when the viewer is offered more. */
    wantsMore(total: number): boolean
}

export function createCountPlan(budget = VISIT_BUDGET, step = MORE_STEP): CountPlan {
    const asked = new Set<number>()
    const queued: number[] = []
    const queuedSet = new Set<number>()
    let scan: number[] = []
    let scanAt = 0
    let scanning = false
    let retry: number[] = []
    /** Chats that failed once: asked once more at most, whatever happens, so a chat that always fails cannot loop. */
    const failedOnce = new Set<number>()
    let allowance = budget
    let refused = false
    let closed = false
    const live = () => !refused && !closed

    return {
        setScanOrder(ids) {
            scan = [...ids]
            scanAt = 0
        },
        reserve(ids) {
            for (const id of ids) asked.add(id)
        },
        offer(id) {
            // A spent budget drops the offer: the row may be far off screen by the time more is asked for, and the scan reaches it.
            if (!live() || asked.has(id) || queuedSet.has(id) || failedOnce.has(id) || asked.size >= allowance) return
            queuedSet.add(id)
            queued.push(id)
        },
        more() {
            if (!live()) return
            allowance = Math.max(allowance, asked.size) + step
            scanning = true
            // Offers made before are for rows that may be gone from the screen: the scan order serves what is next.
            queued.length = 0
            queuedSet.clear()
        },
        refuse() {
            refused = true
            queued.length = 0
            queuedSet.clear()
        },
        close() {
            closed = true
            queued.length = 0
            queuedSet.clear()
        },
        take(n = AT_ONCE) {
            const out: number[] = []
            if (!live()) return out
            const room = () => out.length < n && asked.size < allowance
            while (room() && queued.length) {
                const id = queued.shift()!
                queuedSet.delete(id)
                if (asked.has(id) || failedOnce.has(id)) continue
                asked.add(id)
                out.push(id)
            }
            if (scanning) {
                while (room() && retry.length) {
                    const id = retry.shift()!
                    if (asked.has(id)) continue
                    asked.add(id)
                    out.push(id)
                }
                while (room() && scanAt < scan.length) {
                    const id = scan[scanAt++]
                    if (asked.has(id)) continue
                    asked.add(id)
                    out.push(id)
                }
            }
            return out
        },
        retryLater(ids) {
            // One more try per chat per visit; a second failure leaves it counted as asked, for good.
            const again = ids.filter((id) => !failedOnce.has(id))
            for (const id of again) failedOnce.add(id)
            retry = [...new Set([...retry, ...again])].slice(0, 100)
            for (const id of again) asked.delete(id)
        },
        get spent() {
            return asked.size
        },
        get allowance() {
            return allowance
        },
        get refused() {
            return refused
        },
        wantsMore(total) {
            return live() && asked.size >= allowance && (asked.size < total || retry.length > 0)
        },
    }
}
