import { createEffect, createSignal, Show } from 'solid-js'
import { CloudSyncIcon } from '@hugeicons/core-free-icons'
import { t } from '../i18n'
import { Icon } from '../ui/Icon'
import { showToast } from '../ui/Toast'
import { keepPlacePending, keepPlaceShown, keepPlaceYes } from '../sync'

/**
 * Home's one question about resume across devices (the apps' KeepPlaceCard): "Keep my place on
 * all my devices?", at the end of a Home that has rows, never over a first run or an empty page.
 * Asked once: it counts as shown the moment it is on screen, and either answer ends it for good.
 * "Yes" turns the sync on (the viewer's own act: until then nothing is read from or written to
 * Saved Messages) and syncs at once; "Not now" only closes the card. A sync that is already on
 * (turned on in Settings, or by an earlier visit) never shows it.
 *
 * `eligible` is false until Home has something to show. The card stays for as long as this Home is
 * on screen after it was counted as shown, as the apps' does.
 */
export function KeepPlaceCard(props: { eligible: boolean }) {
    const [keep, setKeep] = createSignal(false)
    createEffect(() => {
        if (keepPlacePending() && props.eligible) setKeep(true)
    })
    // Counted as shown once it is in the page, not when the viewer has scrolled to it: one showing, then never again.
    createEffect(() => {
        if (keep()) keepPlaceShown()
    })
    /** The card's buttons are gone: the keyboard goes to the first video of Home, else the heading, never to the top of the page. */
    const settle = () => {
        const next = document.querySelector<HTMLElement>('.home-row a.card') ?? document.querySelector<HTMLElement>('.home-title')
        if (next) {
            if (!next.matches('a, button')) next.tabIndex = -1
            next.focus({ preventScroll: true })
        }
    }
    const yes = () => {
        setKeep(false)
        settle()
        void keepPlaceYes()
        showToast({ kind: 'success', text: t('syncplace.started') })
    }
    const notNow = () => {
        setKeep(false)
        settle()
    }
    return (
        <Show when={keep()}>
            <section class="keep-place" aria-labelledby="keep-place-title">
                <Icon icon={CloudSyncIcon} size={28} class="keep-place-icon" />
                <div class="keep-place-text">
                    <h2 id="keep-place-title">{t('syncplace.card_title')}</h2>
                    <p>{t('syncplace.card_body')}</p>
                    <p class="muted small">{t('syncplace.card_hint')}</p>
                </div>
                <div class="keep-place-buttons">
                    <button type="button" class="link" onClick={notNow}>{t('syncplace.card_no')}</button>
                    <button type="button" class="primary" onClick={yes}>{t('syncplace.card_yes')}</button>
                </div>
            </section>
        </Show>
    )
}
