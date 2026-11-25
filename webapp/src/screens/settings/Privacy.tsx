import { Show } from 'solid-js'
import { CloudSyncIcon, Delete02Icon } from '@hugeicons/core-free-icons'
import { t } from '../../i18n'
import { askConfirm } from '../../ui/Confirm'
import { setSyncOn, statusLine, syncHasFile, syncNow, syncOn, syncRunning, syncStatus, turnOffAndDelete } from '../../sync'
import { showToast, type ToastKind } from '../../ui/Toast'
import { ActionRow, ToggleRow } from '../../ui/SettingsParts'

/**
 * Privacy and lock: resume on your other devices (the apps' row of the same name). Off until the
 * viewer turns it on, and turning it on asks first what goes where. While on: the line of the last
 * sync, "Sync now" (or "Replace the sync file" when the file could not be read), and "Turn off and
 * delete the sync file", which takes TMPlayer's file out of Saved Messages.
 */
export function Privacy() {
    let enable!: HTMLDialogElement
    const say = (text: string, kind: ToastKind = 'success') => showToast({ kind, text })
    const change = (on: boolean) => (on ? enable.showModal() : void setSyncOn(false))
    const turnOn = () => {
        enable.close()
        void setSyncOn(true)
        say(t('settings.resume_sync_started'))
    }
    // Turning off and deleting the file asks first (HX59), and holds its buttons while the file is taken out.
    const erased = async () => {
        let gone = false
        const yes = await askConfirm({
            title: t('settings.resume_sync_delete_title'),
            message: t('settings.resume_sync_delete_message'),
            confirm: t('settings.resume_sync_delete'),
            run: async () => (gone = await turnOffAndDelete()),
        })
        if (yes) say(gone ? t('settings.resume_sync_deleted') : t('settings.resume_sync_delete_failed'), gone ? 'success' : 'error')
    }
    const replaced = async () => {
        const yes = await askConfirm({
            title: t('settings.resume_sync_replace_title'),
            message: t('settings.resume_sync_replace_message'),
            confirm: t('settings.resume_sync_replace'),
        })
        if (yes) void syncNow({ replace: true })
    }
    const unreadable = () => syncStatus()?.kind === 'unreadable'
    // The last run failed: the same button says Try again beside the line that says why (LS-31).
    const failed = () => !syncRunning() && syncStatus()?.kind === 'failed'
    return (
        <>
            <div class="set-card">
                <ToggleRow id="resume_sync" icon={CloudSyncIcon} title={t('syncplace.switch')} subtitle={syncOn() ? t('settings.resume_sync_on') : t('settings.resume_sync_off')} on={syncOn()} onChange={change} />
                <Show when={syncOn()}>
                    <div class="set-block sync-status">
                        <p class="small" role="status" aria-live="polite" aria-busy={syncRunning()}>{statusLine()}</p>
                        <div class="sync-buttons">
                            <Show
                                when={unreadable()}
                                fallback={<button type="button" class="outline-button" disabled={syncRunning()} onClick={() => void syncNow()}>{failed() ? t('common.try_again') : t('settings.resume_sync_now')}</button>}
                            >
                                <button type="button" class="outline-button" disabled={syncRunning()} onClick={() => void replaced()}>{t('settings.resume_sync_replace')}</button>
                            </Show>
                        </div>
                    </div>
                </Show>
                <Show when={syncOn() || syncHasFile()}>
                    <ActionRow row="resume_sync_delete" icon={Delete02Icon} title={t('settings.resume_sync_delete')} subtitle={t('settings.resume_sync_delete_detail')} onClick={() => void erased()} />
                </Show>
                <div class="set-block">
                    <p class="muted small">{t('syncplace.privacy')}</p>
                </div>
            </div>
            <dialog class="confirm" ref={enable} aria-labelledby="sync-enable-title">
                <h2 id="sync-enable-title">{t('settings.resume_sync_enable_title')}</h2>
                <p class="muted">{t('settings.resume_sync_enable_message')}</p>
                <div class="confirm-buttons">
                    <button class="link" type="button" onClick={() => enable.close()}>{t('common.cancel')}</button>
                    <button class="primary" type="button" onClick={turnOn}>{t('syncplace.card_yes')}</button>
                </div>
            </dialog>
        </>
    )
}
