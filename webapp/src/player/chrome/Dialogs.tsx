// The two dialogs the controls draw themselves: Playback details (WB18) and "Still watching?".
import { createSignal, onMount, Show, untrack } from 'solid-js'
import { Cancel01Icon } from '@hugeicons/core-free-icons'
import { Icon } from '../../ui/Icon'
import { t } from '../../i18n'
import { AUTOPLAY_LIMIT } from '../extras'
import { speedLabel } from './parts'
import type { Ctl } from './types'
import type { StillWhy } from './useSleep'

/** Playback details: what the player knows of this video, Report stutter and Copy details with the logs. */
export function DetailsSheet(props: { c: Ctl; tv: boolean; rate: number; onClose: () => void }) {
    const c = props.c
    // What Report stutter and Copy details just did, and what the logs weigh.
    const [note, setNote] = createSignal('')
    const [logsSize, setLogsSize] = createSignal<string | null>(null)
    /** The text a browser that would not copy gets to select and copy itself. */
    const [fallback, setFallback] = createSignal<string | null>(null)
    try {
        setLogsSize(untrack(() => c.logsSize()))
    } catch {
        setLogsSize(null)
    }
    // The remote and the keyboard come into the sheet on its close button, so OK still closes it as before.
    onMount(() => queueMicrotask(() => c.root.querySelector<HTMLElement>('.pc-details .pc-btn')?.focus({ preventScroll: true })))
    const copy = () =>
        void c.copyLogs().then((ok) => {
            setFallback(ok ? null : c.logsText())
            setNote(ok ? t('player.details_copied') : t('web.copy_failed'))
        })
    return (
        <div class="pc-details" role="dialog" aria-label={t('player.playback_details')}>
            <div class="pc-details-head">
                <strong>{t('player.playback_details')}</strong>
                <button type="button" class="pc-btn" aria-label={t('common.close')} onClick={() => props.onClose()}>
                    <Icon icon={Cancel01Icon} size={20} />
                </button>
            </div>
            <p>{c.details()}</p>
            <p>{c.readout()}</p>
            <p>{t('player.details_speed', { speed: speedLabel(props.rate) })}</p>
            <Show when={c.item.dc}>
                <p>{t('player.details_datacenter', { number: c.item.dc! })}</p>
            </Show>
            <div class="pc-details-actions">
                <Show when={c.stutter()}>
                    <button type="button" class="pc-details-btn" onClick={() => setNote(c.stutter()!.press() === 'already' ? t('player.stutter_marked_already') : t('web.stutter_marked'))}>
                        {t('player.report_stutter')}
                    </button>
                </Show>
                <Show when={!props.tv}>
                    <button type="button" class="pc-details-btn" onClick={copy}>
                        {t('player.copy_details')}
                    </button>
                </Show>
            </div>
            <Show when={c.stutter()}>
                <p class="pc-details-hint">{t('web.report_stutter_detail')}</p>
            </Show>
            <Show when={logsSize() && !props.tv}>
                <p class="pc-details-hint">{t('feedback.logs_size', { size: logsSize()! })}</p>
            </Show>
            <p class="pc-details-note" role="status" aria-live="polite">{note()}</p>
            <Show when={fallback()}>
                <textarea
                    class="pc-details-copy"
                    readonly
                    rows="6"
                    aria-label={t('player.copy_details')}
                    value={fallback()!}
                    ref={(el) => queueMicrotask(() => (el.focus({ preventScroll: true }), el.select()))}
                />
            </Show>
        </div>
    )
}

/** "Still watching?": the video is paused, the viewer goes back or keeps watching. */
export function StillSheet(props: { why: StillWhy; onBack: () => void; onKeep: () => void }) {
    const text = () =>
        props.why === 'idle'
            ? t('player.still_watching_idle')
            : props.why === 'autoplay'
              ? t('player.still_watching_autoplay', { count: AUTOPLAY_LIMIT })
              : props.why === 'sleep'
                ? t('player.still_watching_sleep')
                : t('player.still_watching_sleep_end')
    return (
        <div class="pc-sheet-scrim still">
            <div class="pc-still" role="alertdialog" aria-label={t('player.still_watching')} ref={(el) => queueMicrotask(() => el.querySelector<HTMLElement>('.primary')?.focus({ preventScroll: true }))}>
                <h2>{t('player.still_watching')}</h2>
                <p>{text()}</p>
                <p class="muted">{t('player.still_watching_paused')}</p>
                <div class="pc-still-actions">
                    <button type="button" class="pc-sheet-button" onClick={props.onBack}>{t('player.go_back')}</button>
                    <button type="button" class="pc-sheet-button primary" onClick={props.onKeep}>{t('player.keep_watching')}</button>
                </div>
            </div>
        </div>
    )
}
