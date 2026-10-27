// Clear history in one step, as the apps do: Continue watching and Watched go together, Watch later
// is kept, it is done at once, and the toast's Undo puts it all back for 8 s.
import { t } from './i18n'
import { clearHistory, restoreHistory } from './store'
import { showToast } from './ui/Toast'

let busy = false

export async function clearHistoryWithUndo() {
    if (busy) return
    busy = true
    try {
        const snap = await clearHistory()
        showToast({
            text: t('browse.history_cleared'),
            action: snap ? { label: t('browse.undo'), run: () => restoreHistory(snap) } : undefined,
        })
    } finally {
        busy = false
    }
}
