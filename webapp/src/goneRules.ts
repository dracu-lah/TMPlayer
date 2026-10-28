// The rules for a kept video gone from Telegram (HX21), with no page code in them so node --test
// can run them: which failures mean gone for good, and which line says what left the lists.

/** Telegram's answer for a chat this account can no longer read: its videos are gone for it. */
const CHAT_GONE = /\b(CHANNEL_PRIVATE|CHAT_FORBIDDEN|USER_BANNED_IN_CHANNEL|PEER_ID_INVALID|CHANNEL_INVALID|CHAT_ID_INVALID)\b/

/**
 * Whether `e` (a call's failure) says the chat is closed to this account for good. A flood wait,
 * a timeout, a lost connection or anything else is not gone: the entry is kept for the next look.
 */
export function chatGone(e: unknown): boolean {
    if (e === null || e === undefined) return false
    const err = e as { text?: unknown; message?: unknown }
    const text = typeof err.text === 'string' ? err.text : typeof err.message === 'string' ? err.message : typeof e === 'string' ? e : ''
    return CHAT_GONE.test(text)
}

/** Which catalog line says what left the lists, or null when nothing did. */
export function goneKey(history: number, later: number, videos: number): 'continue.gone_left_lists' | 'continue.gone_left_watch_later' | 'continue.gone_left_history' | null {
    if (!(videos > 0)) return null
    if (history > 0 && later > 0) return 'continue.gone_left_lists'
    if (later > 0) return 'continue.gone_left_watch_later'
    return 'continue.gone_left_history'
}
