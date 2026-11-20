import { createSignal } from 'solid-js'
import { FavouriteIcon } from '@hugeicons/core-free-icons'
import { renderSVG } from 'uqr'
import { t } from '../../i18n'
import { Chevron, LinkRow } from '../../ui/SettingsParts'

// The same UPI details the apps and the site carry (ui About.kt, site/src/lib/support.ts).
const UPI_ID = 'nevilnicks4321@okaxis'
const UPI_LINK = `upi://pay?pa=${UPI_ID}&pn=TMPlayer&cu=INR&tn=${encodeURIComponent('TMPlayer support')}`

/**
 * Chipping in (user, 2026-11-01; HX75, 2026-11-02). Buy Me a Coffee takes cards and PayPal, which
 * most people here do not use: UPI stands beside it, with Google Pay's own mark, since that is the
 * app most of them pay with. The UPI row always opens a window with the code, the ID and Copy. On
 * a phone it also follows the real `upi://` link, which GPay, PhonePe or Paytm answers; the window
 * is there for when the wrong app opened or none did. A computer has nothing to answer the link,
 * so the window is all it gets. Nothing here is checked afterwards and nothing is unlocked.
 */
export function Support() {
    const touch = typeof matchMedia === 'function' && matchMedia('(pointer: coarse)').matches
    let dialog!: HTMLDialogElement
    const [copied, setCopied] = createSignal(false)
    const qr = renderSVG(UPI_LINK, { pixelSize: 6, border: 2, whiteColor: '#fff', blackColor: '#0e0f13' })
    const open = () => {
        setCopied(false)
        if (!dialog.open) dialog.showModal()
        // A phone hands the link to its UPI app; a refused or unanswered link leaves the window up.
        if (touch) {
            try { location.href = UPI_LINK } catch { /* the window shows the code and the ID */ }
        }
    }
    const copy = async () => {
        // A refused or missing clipboard leaves the ID on screen to copy by hand.
        try {
            await navigator.clipboard.writeText(UPI_ID)
            setCopied(true)
        } catch {
            setCopied(false)
        }
    }
    return (
        <>
            <div class="set-card">
                <LinkRow row="chip_in" href="https://buymeacoffee.com/nevil.dev" icon={FavouriteIcon} title={t('support.buy_coffee')} />
                <button class="set-row" type="button" onClick={open}>
                    <span class="set-icon set-icon-plain">
                        <img src="/gpay-mark.png" alt="" width="24" height="24" />
                    </span>
                    <span class="set-text">
                        <span class="set-title">{t('support.pay_upi')}</span>
                        <span class="set-sub">{UPI_ID}</span>
                    </span>
                    <Chevron />
                </button>
                <div class="set-block">
                    <p class="muted small">{t('support.any_amount')}</p>
                </div>
            </div>
            <dialog
                class="confirm upi-dialog"
                ref={dialog}
                aria-labelledby="upi-title"
                onClick={(e) => { if (e.target === dialog) dialog.close() }}
            >
                <h2 id="upi-title">{t('support.pay_upi')}</h2>
                <p class="muted">{t('support.upi_note')}</p>
                <div class="qr"><div class="qr-code" role="img" aria-label={t('support.scan_upi')} innerHTML={qr} /></div>
                <p class="muted small">{t('support.scan_upi')}</p>
                <p class="upi-id"><code>{UPI_ID}</code></p>
                <div class="confirm-buttons">
                    <button class="link" type="button" onClick={copy}>{copied() ? t('support.upi_copied') : t('support.upi_copy')}</button>
                    <button class="primary" type="button" onClick={() => dialog.close()}>{t('common.close')}</button>
                </div>
            </dialog>
        </>
    )
}
