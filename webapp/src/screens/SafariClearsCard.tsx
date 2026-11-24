import { createSignal, Show } from 'solid-js'
import { Download04Icon } from '@hugeicons/core-free-icons'
import { t } from '../i18n'
import { Icon } from '../ui/Icon'
import { me } from '../session'
import { inAppIos, iosBrowser } from '../install'
import { HomeScreenSteps } from '../ui/HomeScreenSteps'
import { setSyncOn, syncOn } from '../sync'
import { showToast } from '../ui/Toast'
import { readDismissed, saveDismissed, showSafariCard } from '../ui/safariCard'

const storage = () => {
    try {
        return localStorage
    } catch {
        return null
    }
}

/**
 * Signed in, in a Safari tab: Safari clears a site's storage after 7 days without a visit, and the
 * sign in and the lists go with it (WP-56). One card, closed for good by "Not now" or once resume
 * sync is on; Settings, Stored in this browser says the same for as long as the page is a tab. The
 * button turns on resume across devices, the viewer's own act, which keeps the places in their
 * Saved Messages even if the browser forgets the rest.
 */
export function SafariClearsCard() {
    const [closed, setClosed] = createSignal(readDismissed(storage()))
    const close = () => {
        saveDismissed(storage())
        setClosed(true)
    }
    const keep = () => {
        close()
        void setSyncOn(true)
        showToast({ kind: 'success', text: t('syncplace.started') })
    }
    return (
        <Show when={showSafariCard({ inTab: iosBrowser && !inAppIos, signedIn: !!me(), dismissed: closed() })}>
            <section class="keep-place safari-clears" aria-labelledby="safari-clears-title">
                <Icon icon={Download04Icon} size={28} class="keep-place-icon" />
                <div class="keep-place-text">
                    <h2 id="safari-clears-title">{t('web.safari_clears_title')}</h2>
                    <p>{t('web.safari_clears_body')}</p>
                    <HomeScreenSteps />
                </div>
                <div class="keep-place-buttons">
                    <button type="button" class="link" onClick={close}>{t('web.install_later')}</button>
                    <Show when={!syncOn()}>
                        <button type="button" class="primary" onClick={keep}>{t('web.turn_on_resume_sync')}</button>
                    </Show>
                </div>
            </section>
        </Show>
    )
}

/** The same warning as a row in Settings, Stored in this browser: it stays while the page is a Safari tab. */
export function SafariClearsRow() {
    return (
        <Show when={iosBrowser}>
            <div class="set-block" id="safari-clears">
                <h3 class="set-block-title">{t('web.safari_clears_title')}</h3>
                <p class="muted small">{t('web.safari_clears_body')}</p>
                <HomeScreenSteps />
            </div>
        </Show>
    )
}
