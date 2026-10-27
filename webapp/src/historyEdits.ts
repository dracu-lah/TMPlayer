// Remove from history, as the apps' HistoryEdits does it: the chosen videos leave Continue watching
// and Watched together and stay in their chats, done at once, with the toast's Undo putting back
// exactly what was taken out (the same positions and times, so the same order) for 8 s. A TV
// remote cannot reach the toast, so there the caller asks first and no Undo is offered.
import { t } from './i18n'
import { removeFromHistory, restoreHistory } from './store'
import { showToast } from './ui/Toast'

let busy = false

/** The id a video goes by in the history ("chatId:messageId"). */
export const historyId = (v: { chatId: number; messageId: number }) => `${v.chatId}:${v.messageId}`

/** Removes `ids` (the same video twice counts once); resolves with how many were really in the history. */
export async function removeWithUndo(ids: string[], undo = true): Promise<number> {
    if (busy || !ids.length) return 0
    busy = true
    try {
        const removed = await removeFromHistory([...new Set(ids)])
        if (!removed) return 0
        showToast({
            text: t('browse.removed_from_history', { count: removed.count }),
            action: undo ? { label: t('browse.undo'), run: () => restoreHistory(removed.snapshot) } : undefined,
        })
        return removed.count
    } finally {
        busy = false
    }
}
