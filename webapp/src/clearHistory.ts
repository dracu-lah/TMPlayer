// Clear history in one step, as the apps do: Continue watching and Watched go together, Watch later
// is kept. It asks first (HX59) with how many videos leave and that nothing leaves Telegram, and
// the toast's Undo still puts it all back for 8 s.
import { t } from './i18n'
import { clearHistory, restoreHistory, resumes, watchedIds } from './store'
import { askConfirm } from './ui/Confirm'
import { showToast } from './ui/Toast'

let busy = false

/** How many different videos Continue watching and Watched hold between them. */
async function historyCount(): Promise<number> {
    const [left, done] = await Promise.all([resumes(), watchedIds()])
    return new Set([...left.map((r) => `${r.chatId}:${r.messageId}`), ...done]).size
}

export async function clearHistoryWithUndo() {
    if (busy) return
    busy = true
    try {
        // A failed read is not "nothing to lose": it asks, without a count.
        const count = await historyCount().catch(() => null)
        // Nothing to lose: no question, the same short answer as before.
        const yes =
            count === 0 ||
            (await askConfirm({
                title: t('browse.clear_history_title'),
                message: count === null ? t('browse.clear_history_detail') : t('browse.clear_history_message', { count }),
                detail: count === null ? undefined : t('browse.clear_history_detail'),
                confirm: t('browse.clear_history'),
            }))
        if (!yes) return
        const snap = await clearHistory()
        showToast({
            kind: 'success',
            text: t('browse.history_cleared'),
            action: snap ? { label: t('browse.undo'), run: () => restoreHistory(snap) } : undefined,
        })
    } finally {
        busy = false
    }
}
