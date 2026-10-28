// Kept videos gone from Telegram for good (HX21), as the apps' GoneVideos: one deleted from its
// chat, one whose chat is closed to this account, one edited into something else. They leave
// Continue watching, Watched and Watch later, and the page says so once for the lot. Only a plain
// answer counts: a lookup that failed (offline, a flood wait, anything unknown) keeps the entry.
import { t } from './i18n'
import { dropLater, removeFromHistory } from './store'
import { showToast } from './ui/Toast'
import { chatGone, goneKey } from './goneRules'

export { chatGone }

/** The line for what left the lists, or null when nothing did. */
export function goneLine(history: number, later: number, videos: number): string | null {
    const key = goneKey(history, later, videos)
    return key && t(key, { count: videos })
}

const seen = new Set<string>()

/**
 * Takes `gone` out of the history and Watch later. `say` false when the page already says the
 * video is gone (the watch page), so it is not said twice. Each video is handled once a session.
 */
export async function forgetGone(gone: { chatId: number; messageId: number }[], say = true): Promise<number> {
    const fresh = gone.filter((g) => {
        const key = `${g.chatId}:${g.messageId}`
        if (seen.has(key)) return false
        seen.add(key)
        return true
    })
    if (!fresh.length) return 0
    const ids = fresh.map((g) => `${g.chatId}:${g.messageId}`)
    const removed = await removeFromHistory(ids).catch(() => null)
    const inHistory = new Set(removed ? [...removed.snapshot.resumes, ...removed.snapshot.watched].map(([k]) => k.slice(k.indexOf(':') + 1)) : [])
    let later = 0
    for (const g of fresh) if (dropLater(g.chatId, g.messageId)) (later++, inHistory.add(`${g.chatId}:${g.messageId}`))
    const line = goneLine(removed?.count ?? 0, later, inHistory.size)
    if (say && line) showToast({ text: line })
    return inHistory.size
}
