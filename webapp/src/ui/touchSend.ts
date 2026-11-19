// Sends the Last played times to the worker once per change (see tg/touchCache.ts) and hands back
// the version a chat view names. Pure over its two ends so it is tested without a worker.

export interface TouchEnds {
    read: () => Promise<{ ids: string[]; times: number[] }>
    send: (version: number, ids: string[], times: number[]) => Promise<unknown>
}

/** What tells one list from the next without comparing 20,000 entries. */
export function signature(t: { ids: string[]; times: number[] }): string {
    let sum = 0
    for (const x of t.times) sum += x
    return `${t.ids.length}|${sum}|${t.ids[0] ?? ''}|${t.ids[t.ids.length - 1] ?? ''}`
}

export function touchSender(ends: TouchEnds) {
    let sent = ''
    let version = 0
    // One at a time: two callers at once share the send, never race it.
    let chain: Promise<number> = Promise.resolve(0)
    return {
        /** The version the worker holds for the lists as they are now; sends them first when they changed. */
        ensure(): Promise<number> {
            chain = chain
                .catch(() => 0)
                .then(async () => {
                    const now = await ends.read()
                    const sig = signature(now)
                    if (sig === sent) return version
                    const next = version + 1
                    await ends.send(next, now.ids, now.times)
                    version = next
                    sent = sig
                    return version
                })
            return chain
        },
        /** The worker was replaced and holds nothing: the next ensure sends again. */
        reset() {
            sent = ''
        },
    }
}
