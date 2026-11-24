import { createSignal } from 'solid-js'
import { t } from '../../i18n'
import { askConfirm } from '../../ui/Confirm'
import { showToast } from '../../ui/Toast'
import { rowDomId } from '../SettingsFind'
import { backupNow, planRestore, restore, saveFile, type RestorePlan } from '../../sync/backupFlow'

/** The counts a restore would change, in the apps' own sentences. */
export function restoreLines(plan: RestorePlan): string[] {
    const c = plan.change
    const laterIn = c.laterIn.filter((l) => !l.series).length
    const out = [
        c.resumeIn.length ? t('backup.restore_resume', { count: c.resumeIn.length }) : '',
        c.watchedIn.length ? t('backup.restore_watched', { count: c.watchedIn.length }) : '',
        laterIn ? t('backup.restore_watch_later', { count: laterIn }) : '',
        c.favouritesIn.size ? t('backup.restore_favourites', { count: c.favouritesIn.size }) : '',
        plan.lines ? t('backup.restore_lists', { count: plan.lines }) : '',
        plan.searches ? t('backup.restore_searches', { count: plan.searches }) : '',
    ]
    return out.filter(Boolean)
}

/** Back up and Restore (WP-56): a file the person keeps, in the apps' format; their sign in is never in it. */
export function BackupBlock() {
    const [busy, setBusy] = createSignal(false)
    let input!: HTMLInputElement

    const save = async () => {
        if (busy()) return
        setBusy(true)
        try {
            const { name, text } = await backupNow()
            saveFile(name, text)
            showToast({ kind: 'success', text: t('backup.saved') })
        } catch {
            showToast({ kind: 'error', text: t('backup.failed') })
        } finally {
            setBusy(false)
        }
    }

    const picked = async () => {
        const file = input.files?.[0]
        input.value = ''
        if (!file || busy()) return
        setBusy(true)
        try {
            // A file far past any backup is refused before it is read into memory.
            if (file.size > 16 * 1024 * 1024) return void showToast({ kind: 'error', text: t('backup.not_backup') })
            const text = await file.text().catch(() => null)
            if (text === null) return void showToast({ kind: 'error', text: t('backup.read_failed') })
            const plan = await planRestore(text)
            if ('kind' in plan) {
                showToast({ kind: 'error', text: plan.kind === 'tooNew' ? t('backup.too_new') : t('backup.not_backup') })
                return
            }
            if (plan.nothing) return void showToast({ kind: 'info', text: t('backup.restore_nothing') })
            if (plan.otherAccount && !(await askConfirm({ title: t('backup.restore_title'), message: t('web.backup_other_account'), confirm: t('backup.restore_confirm'), danger: false }))) return
            const from = plan.read.createdAt > 0 ? t('backup.restore_from', { date: new Date(plan.read.createdAt).toLocaleDateString(), version: plan.read.app || '?' }) : ''
            const yes = await askConfirm({
                title: t('backup.restore_title'),
                message: [from, ...restoreLines(plan)].filter(Boolean).join(' '),
                detail: t('backup.restore_untouched'),
                confirm: t('backup.restore_confirm'),
                danger: false,
            })
            if (!yes) return
            await restore(plan)
            showToast({ kind: 'success', text: t('backup.restored') })
        } catch {
            showToast({ kind: 'error', text: t('backup.read_failed') })
        } finally {
            setBusy(false)
        }
    }

    return (
        <div class="set-block" id={rowDomId('back_up')}>
            <h3 class="set-block-title">{t('web.backup_title')}</h3>
            <p class="muted small">{t('backup.back_up_detail')}</p>
            <div class="set-buttons">
                <button class="outline-button" type="button" disabled={busy()} onClick={save}>{t('backup.back_up')}</button>
                <button class="outline-button" type="button" id={rowDomId('restore')} disabled={busy()} onClick={() => input.click()}>{t('backup.restore')}</button>
            </div>
            <p class="muted small">{t('web.backup_file_note')}</p>
            <input ref={input} type="file" accept=".json,application/json" hidden onChange={() => void picked()} />
        </div>
    )
}
