import { Show, type JSX } from 'solid-js'
import { RefreshIcon, Tick02Icon } from '@hugeicons/core-free-icons'
import { t } from '../../i18n'
import { Icon } from '../../ui/Icon'

/** What the QR step shows of the code's life; SignIn owns the timers and Telegram's events. */
export interface QrView {
    /** The code as SVG, or null while the next one is on its way. */
    svg: string | null
    scanned: boolean
    expired: boolean
    /** The last seconds of a code, said so a code that changes under the camera is expected. */
    soon: boolean
    /** Milliseconds the code has left. */
    left: number
    /** Share of the code's life left, 0 to 1. */
    fraction: number
    /** The refresh pill spins for a few seconds after a press. */
    refreshing: boolean
}

/** A veil over the code: it says one thing and, while shown, can be reached. */
function Veil(props: { on: boolean; children: JSX.Element }) {
    return (
        <div class="qr-veil" classList={{ on: props.on }} aria-hidden={!props.on} inert={!props.on}>
            {props.children}
        </div>
    )
}

/** The QR code, its life bar, the steps to scan it and the way over to the phone number. */
export function QrStep(props: { qr: QrView; onRefresh: () => void; onUsePhone: () => void }) {
    const q = () => props.qr
    const gone = () => q().expired && !q().scanned
    return (
        <div class="qr-layout">
            <div class="qr-side">
                <div class="qr" aria-live="polite">
                    <Show when={q().svg} fallback={<div class="loader on-white" role="progressbar" aria-label={t('login.qr_renewing')} />}>
                        <div class="qr-code" role="img" aria-label={t('login.qr_description')} innerHTML={q().svg!} />
                    </Show>
                    <Veil on={q().scanned}>
                        <span class="qr-tick"><Icon icon={Tick02Icon} size={32} /></span>
                        <b>{t('login.qr_scanned')}</b>
                    </Veil>
                    <Veil on={gone()}>
                        <b>{t('login.qr_expired')}</b>
                        <button type="button" class="qr-refresh on-white" onClick={props.onRefresh}>
                            <Icon icon={RefreshIcon} size={20} />
                            {t('login.qr_refresh')}
                        </button>
                    </Veil>
                </div>
                <div class="qr-life" classList={{ gone: q().scanned }}>
                    <div class="qr-bar"><i classList={{ soon: q().soon }} style={{ width: `${(q().soon && q().left <= 0 ? 1 : q().fraction) * 100}%` }} /></div>
                    <p class="muted small" aria-live="polite">
                        {q().expired ? t('login.qr_renewing') : q().soon ? (q().left <= 0 ? t('login.qr_renewing') : t('login.qr_expires_soon')) : ' '}
                    </p>
                </div>
            </div>
            <div class="qr-text">
                <ol class="steps numbered">
                    <li>{t('login.qr_step_open')}</li>
                    <li>{t('login.qr_step_devices')}</li>
                    <li>{t('login.qr_step_link')}</li>
                </ol>
                <p class="muted small">{t('login.qr_note')}</p>
                <div class="qr-actions">
                    <button type="button" class="link" onClick={props.onUsePhone}>{t('login.use_phone')}</button>
                    <button type="button" class="qr-refresh" onClick={props.onRefresh} disabled={q().refreshing}>
                        <Show when={q().refreshing} fallback={<Icon icon={RefreshIcon} size={20} />}><span class="loader tiny" /></Show>
                        {t('login.qr_refresh')}
                    </button>
                </div>
            </div>
        </div>
    )
}
