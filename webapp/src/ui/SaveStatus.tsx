import { createMemo, For, Show } from 'solid-js'
import { t } from '../i18n'
import { size } from '../screens/VideoList'
import { cancelSave, saves } from './saveRuntime'

/**
 * The saves to the device that are running or just ended (WP-86): the name, how far it has got and a Cancel
 * that stops it, over the page's foot like the update notice. A screen reader hears the state, not the
 * ticking figure; what ended says so in its own line, and the toast says it once.
 */
export function SaveStatus() {
    // The lines are kept by their id, so a tick of progress moves the bar and does not draw the line (and its Cancel) again.
    const ids = createMemo(() => saves().map((s) => s.id))
    return (
        <Show when={ids().length}>
            <div class="save-status" role="region" aria-label={t('web.save_to_device')} data-tv-reach="bottom">
                <For each={ids()}>
                    {(id) => {
                        const s = () => saves().find((x) => x.id === id)
                        return (
                            <Show when={s()}>
                                {(line) => (
                                    <div class="save-line" classList={{ over: line().state !== 'running' }}>
                                        <p class="save-name">
                                            {s()!.state === 'running'
                                                ? t('web.save_saving', { name: s()!.name })
                                                : s()!.state === 'done'
                                                  ? t('web.save_done', { name: s()!.name })
                                                  : s()!.state === 'cancelled'
                                                    ? t('web.save_cancelled', { name: s()!.name })
                                                    : t('web.save_failed', { name: s()!.name })}
                                        </p>
                                        <Show when={s()!.state === 'running'}>
                                            <progress max={s()!.size} value={s()!.loaded} aria-label={t('web.save_saving', { name: s()!.name })} />
                                            <span class="save-figure" aria-hidden="true">{t('downloads.save_to_folder_bytes', { done: size(s()!.loaded), total: size(s()!.size) })}</span>
                                            <button class="link" type="button" onClick={() => cancelSave(id)}>{t('common.cancel')}</button>
                                        </Show>
                                    </div>
                                )}
                            </Show>
                        )
                    }}
                </For>
            </div>
        </Show>
    )
}
