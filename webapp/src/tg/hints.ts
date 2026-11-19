import { rules, type Rules } from '../rules'

// The language words in a chat's title ("Malayalam Shows" gives "ml"), noted as the chat list is
// read so a video's poster question carries them as `hints` (HX24, ChatLanguageHints through the
// rules' noteChat). The rules load beside the connection, so a title that arrives first waits here
// and is noted the moment they are in. Bounded like the registry itself (64 shards of 128): a
// chat past that is simply not noted and its videos ask without hints.

const MAX_WAITING = 8192
const MAX_TITLE = 256

let ready: Rules | null = null
let epoch = 0
const waiting = new Map<number, string>()

function flush(r: Rules) {
    for (const [id, title] of waiting) r.noteChat(id, title)
    waiting.clear()
}

/** Notes `title` as chat `id`'s name; a rename replaces the earlier note. A title that is not text is ignored. */
export function noteChatTitle(id: number, title: unknown): void {
    if (typeof title !== 'string' || !Number.isSafeInteger(id)) return
    const text = title.slice(0, MAX_TITLE)
    if (ready) {
        ready.noteChat(id, text)
        return
    }
    if (!waiting.has(id) && waiting.size >= MAX_WAITING) return
    const first = waiting.size === 0
    waiting.set(id, text)
    if (!first) return
    const mine = epoch
    void rules().then(
        (r) => {
            ready = r
            if (mine === epoch) flush(r)
        },
        () => {},
    )
}

/** Forgets every chat's title (signing out). */
export function forgetChatTitles(): void {
    epoch++
    waiting.clear()
    ready?.forgetChats()
}
