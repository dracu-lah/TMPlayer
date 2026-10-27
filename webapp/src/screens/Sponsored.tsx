import { createSignal, For, onCleanup, onMount, Show } from 'solid-js'
import { api, errorText } from '../tg/rpc'
import { t } from '../i18n'
import { loadThumb } from './thumbs'
import type { SponsoredItem, SponsoredReport } from '../tg/types'

/**
 * Telegram's spacing for sponsored messages (the apps' placeSponsored): one after every
 * `between` cards, each message once; zero means one after all of them.
 */
export function placeSponsored<T>(cards: T[], ads: SponsoredItem[], between: number): (T | SponsoredItem)[] {
    if (!ads.length) return cards
    if (between <= 0) return [...cards, ads[0]]
    const out: (T | SponsoredItem)[] = []
    let next = 0
    cards.forEach((card, i) => {
        out.push(card)
        if ((i + 1) % between === 0 && next < ads.length) out.push(ads[next++])
    })
    return out
}

/**
 * A sponsored message among a channel's cards, as the apps draw it: an amber edge so it is never
 * taken for a video, its picture, label, title, text and the sponsor's details, its button and
 * Report. Counted as viewed once its text is wholly on screen; a press is told to Telegram as the
 * link opens in a new tab. Never playable.
 */
export function SponsoredCard(props: { item: SponsoredItem }) {
    let text!: HTMLDivElement
    let dialog!: HTMLDialogElement
    const [src, setSrc] = createSignal<string | null>(null)
    const [status, setStatus] = createSignal<string | null>(null)
    const [choices, setChoices] = createSignal<Extract<SponsoredReport, { kind: 'options' }> | null>(null)
    const [busy, setBusy] = createSignal(false)

    onMount(() => {
        if (props.item.thumb) loadThumb(props.item.thumb).then(setSrc, () => {})
        const io = new IntersectionObserver(
            (entries) => {
                if (!entries.some((e) => e.intersectionRatio >= 1)) return
                io.disconnect()
                api.sponsoredViewed(props.item.id).catch(() => {})
            },
            { threshold: 1 },
        )
        io.observe(text)
        onCleanup(() => io.disconnect())
    })

    let statusTimer = 0
    onCleanup(() => clearTimeout(statusTimer))
    const say = (message: string) => {
        setStatus(message)
        clearTimeout(statusTimer)
        statusTimer = window.setTimeout(() => setStatus(null), 4000)
    }

    // The tab opens inside the press itself, or the browser blocks it as a pop-up.
    const open = (media: boolean) => {
        if (props.item.url) window.open(props.item.url, '_blank', 'noopener,noreferrer')
        api.sponsoredClicked(props.item.id, media).catch(() => {})
    }

    const report = async (option: number[] = []) => {
        setBusy(true)
        try {
            const res = await api.sponsoredReport(props.item.id, option)
            if (res.kind === 'options') {
                setChoices(res)
                if (!dialog.open) dialog.showModal()
                return
            }
            dialog.close()
            say(
                res.kind === 'reported' ? t('sponsored.reported')
                : res.kind === 'hidden' ? t('sponsored.hidden')
                : res.kind === 'premium' ? t('sponsored.premium_required')
                : t('sponsored.unavailable'),
            )
        } catch (e) {
            dialog.close()
            say(errorText(e))
        } finally {
            setBusy(false)
        }
    }

    return (
        <div class="sponsored" classList={{ 'no-art': !props.item.thumb }}>
            <Show when={props.item.thumb}>
                <button class="sponsored-art" type="button" tabIndex={-1} aria-hidden="true" onClick={() => open(true)}>
                    <Show when={src()}>
                        <img src={src()!} alt="" decoding="async" />
                    </Show>
                </button>
            </Show>
            <div class="sponsored-text" ref={text}>
                <p class="sponsored-label">{props.item.recommended ? t('sponsored.recommended') : t('sponsored.label')}</p>
                <Show when={props.item.title}>
                    <p class="sponsored-title">{props.item.title}</p>
                </Show>
                <Show when={props.item.text}>
                    <p class="sponsored-body">{props.item.text}</p>
                </Show>
                <Show when={props.item.additionalInfo}>
                    <p class="muted small">{props.item.additionalInfo}</p>
                </Show>
                <Show when={props.item.sponsorInfo}>
                    <p class="muted small">{props.item.sponsorInfo}</p>
                </Show>
                <Show when={status()}>
                    <p class="small" role="status">{status()}</p>
                </Show>
            </div>
            <div class="sponsored-buttons">
                <Show when={props.item.buttonText && props.item.url}>
                    <button class="tonal" type="button" onClick={() => open(false)}>{props.item.buttonText}</button>
                </Show>
                <Show when={props.item.canReport}>
                    <button class="link" type="button" disabled={busy()} onClick={() => report()}>{t('sponsored.report')}</button>
                </Show>
            </div>
            <dialog class="confirm" ref={dialog} aria-labelledby={`report-${props.item.id}`}>
                <h2 id={`report-${props.item.id}`}>{choices()?.title || t('sponsored.report_why')}</h2>
                <div class="report-options">
                    <For each={choices()?.options ?? []}>
                        {(o) => (
                            <button type="button" disabled={busy()} onClick={() => report(o.option)}>{o.text}</button>
                        )}
                    </For>
                </div>
                <div class="confirm-buttons">
                    <button class="link" type="button" onClick={() => dialog.close()}>{t('common.cancel')}</button>
                </div>
            </dialog>
        </div>
    )
}
