// The registry of chat titles behind hints.ts, apart from the rules module so node can test it.

/** What the rules offer for chat titles (WebRules.noteChat and forgetChats). */
export interface TitleSink {
    noteChat(id: number, title: string): void
    forgetChats(): void
}

const MAX_WAITING = 8192
const MAX_TITLE = 256

/**
 * Notes chat titles in the rules once `load` has them. A title that arrives first waits (bounded).
 * If `load` fails the waiting titles stay, and the next noteChatTitle call tries again: no timer,
 * so a page whose rules never load asks once per new title, not in a loop.
 */
export function createTitleNotes(load: () => Promise<TitleSink>) {
    let ready: TitleSink | null = null
    let loading = false
    const waiting = new Map<number, string>()

    function start() {
        if (loading) return
        loading = true
        void load().then(
            (sink) => {
                loading = false
                ready = sink
                // What waits now came after any sign out (it empties the map), so it is all this account's.
                for (const [id, title] of waiting) sink.noteChat(id, title)
                waiting.clear()
            },
            () => {
                loading = false
            },
        )
    }

    return {
        /** Notes `title` as chat `id`'s name; a rename replaces the earlier note. A title that is not text is ignored. */
        noteChatTitle(id: number, title: unknown): void {
            if (typeof title !== 'string' || !Number.isSafeInteger(id)) return
            const text = title.slice(0, MAX_TITLE)
            if (ready) {
                ready.noteChat(id, text)
                return
            }
            if (!waiting.has(id) && waiting.size >= MAX_WAITING) return
            waiting.set(id, text)
            start()
        },
        /** Forgets every chat's title (signing out). */
        forgetChatTitles(): void {
            waiting.clear()
            ready?.forgetChats()
        },
    }
}
