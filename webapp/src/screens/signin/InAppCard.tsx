import { onMount } from 'solid-js'
import { t } from '../../i18n'
import { log } from '../../logs'
import { showToast } from '../../ui/Toast'
import { inAppName } from '../../ui/inAppBrowser'
import { iosVersion } from '../../install'

/** The link a person opens in Safari: this site's own address, with nothing of the page they are on. */
const address = () => `${location.origin}/`

/** Copies `text`; false where the browser refuses (a web view often does). */
async function copy(text: string): Promise<boolean> {
    try {
        await navigator.clipboard.writeText(text)
        return true
    } catch {}
    try {
        const field = document.createElement('textarea')
        field.value = text
        field.setAttribute('readonly', '')
        field.style.position = 'fixed'
        field.style.opacity = '0'
        document.body.append(field)
        field.select()
        const ok = document.execCommand('copy')
        field.remove()
        return ok
    } catch {
        return false
    }
}

/**
 * Before sign in, inside another app's web view (a link from Telegram, Instagram, Mail; WP-58): a
 * web view keeps its own storage, so the sign in would be lost, and it cannot be added to the Home
 * Screen. It says so, and offers the link to open in Safari. "Continue here anyway" is the way out of a
 * wrong guess.
 */
export function InAppCard(props: { onContinue: () => void }) {
    // In Send logs: which kind of view met this page, never the page's address or who is signing in.
    onMount(() => log('W', 'install', `in-app browser, ${inAppName(navigator.userAgent)}, ios ${iosVersion}`))
    const copyLink = async () => {
        const ok = await copy(address())
        showToast({ kind: ok ? 'success' : 'info', text: ok ? t('common.link_copied') : address() })
    }
    return (
        <section class="in-app-card" aria-labelledby="in-app-title">
            <img class="boot-mark" src="/icon.svg" alt="" width="64" height="64" />
            <h1 id="in-app-title">{t('web.open_in_safari_title')}</h1>
            <p>{t('web.open_in_safari_body')}</p>
            <p class="muted small">{t('web.open_in_safari_steps')}</p>
            <button type="button" class="primary" onClick={() => void copyLink()}>{t('web.copy_link')}</button>
            <button type="button" class="link" onClick={props.onContinue}>{t('web.open_in_safari_continue')}</button>
        </section>
    )
}
