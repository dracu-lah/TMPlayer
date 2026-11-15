// The order of Home's rows as Settings lists it (the apps' HomeOrder.kt, EG-F7 and EG-F23). The
// stored order is text, row keys separated by commas ("continue", "recent", "chat-<id>"); Home's own
// build applies it in the worker through WebRules (HomeLayout.order), this file only lists and moves
// the rows on the Settings page. Pure, so it runs under `node --test`. Keep it in step with HomeOrder.kt.

export const CONTINUE = 'continue'
export const RECENT = 'recent'

/** Keys kept from the stored text, and the longest a key can be: the apps' HomeOrder.MAX_KEYS. */
const MAX_KEYS = 2000
const MAX_KEY_LENGTH = 32
const KEY = /^[a-z0-9-]+$/

export const chatKey = (chatId: number) => `chat-${chatId}`

/** The keys in the stored text, in order, each once; whatever is not a key (or not text) is dropped. */
export function parseOrder(stored: unknown): string[] {
    if (typeof stored !== 'string' || stored === '') return []
    const out = new Set<string>()
    let from = 0
    // Walked by hand: a huge garbage value must not become a huge list first.
    while (from <= stored.length && out.size < MAX_KEYS) {
        let to = stored.indexOf(',', from)
        if (to < 0) to = stored.length
        const word = stored.slice(from, to).trim()
        if (word.length > 0 && word.length <= MAX_KEY_LENGTH && KEY.test(word)) out.add(word)
        from = to + 1
    }
    return [...out]
}

export function encodeOrder(order: readonly string[]): string {
    return [...new Set(order.filter((k) => k.length > 0 && k.length <= MAX_KEY_LENGTH && KEY.test(k)))].slice(0, MAX_KEYS).join(',')
}

/**
 * `defaults`, the keys in Home's own order, arranged by `stored`: the stored keys that are among them
 * first, in their stored order, then every other key right after the key that stands before it in
 * `defaults` (at the top when nothing before it is placed). A key nothing has is ignored.
 */
export function arrangeOrder(defaults: readonly string[], stored: readonly string[]): string[] {
    const every = [...new Set(defaults)]
    if (stored.length === 0 || every.length < 2) return every
    const wanted = new Set(every)
    const placed = new Set<string>()
    for (const k of stored) if (wanted.has(k)) placed.add(k)
    if (placed.size === every.length) return [...placed]
    const out = [...placed]
    every.forEach((key, at) => {
        if (placed.has(key)) return
        let spot = 0
        for (let before = at - 1; before >= 0; before--) {
            const found = out.indexOf(every[before])
            if (found >= 0) {
                spot = found + 1
                break
            }
        }
        out.splice(spot, 0, key)
    })
    return out
}

/** `order` with `key` moved `by` places (negative is up), stopping at either end; the same list when nothing moves. */
export function moveKey(order: readonly string[], key: string, by: number): string[] {
    const from = order.indexOf(key)
    if (from < 0) return [...order]
    const to = Math.min(Math.max(from + by, 0), order.length - 1)
    if (to === from) return [...order]
    const next = [...order]
    next.splice(from, 1)
    next.splice(to, 0, key)
    return next
}

/** One row in Settings' list: a starred chat's row has its name. */
export interface OrderEntry {
    key: string
    chatTitle: string | null
    /** A named list's row (HX43): the list's name. */
    listName?: string
}

/** What Settings lists: Continue watching, Recently added, a row for each starred chat, in the stored order. */
export function editorRows(chats: readonly { chatId: number; title: string }[], stored: readonly string[], lists: readonly { id: string; name: string }[] = []): OrderEntry[] {
    const defaults: OrderEntry[] = [
        { key: CONTINUE, chatTitle: null },
        { key: RECENT, chatTitle: null },
        ...chats.map((c) => ({ key: chatKey(c.chatId), chatTitle: c.title })),
        // The named lists on Home (HX43), after the chats, as Home builds them.
        ...lists.map((l) => ({ key: `list-${l.id}`, chatTitle: null, listName: l.name })),
    ]
    const byKey = new Map(defaults.map((e) => [e.key, e]))
    return arrangeOrder(defaults.map((e) => e.key), stored).flatMap((k) => byKey.get(k) ?? [])
}
