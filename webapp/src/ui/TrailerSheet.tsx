// A trailer in a sheet over the video's page (private/TRAILERS-PLAN.md): YouTube's own embedded player,
// youtube-nocookie.com, and nothing else. The iframe is not in the page until the Trailer button is
// pressed, so nothing from Google loads before it; the press is also what lets it start playing.
// It says so in a line under the player. The player's messages (ready, state, error) come as
// ui/trailer.ts reads them; an error from it, or no `ready` within 12 s, replaces the player with a
// plain message and the link to YouTube, as the page had before. Keyboard: the focus goes to Close
// when it opens, Escape or a press on the dimmed page closes it, and the focus returns to the
// Trailer button.
import { createEffect, createSignal, on, onCleanup, Show } from 'solid-js'
import { useLocation } from '@solidjs/router'
import { t } from '../i18n'
import { ServiceBadge } from './ServiceBadge'
import { embedUrl, fallsBack, HOST, LOADING, next, parseWidgetMessage, READY_TIMEOUT_MS, timedOut, watchUrl, type State } from './trailer'

interface Target {
    key: string
    /** The button that opened it: the focus goes back there. */
    opener: HTMLElement | null
}

const [target, setTarget] = createSignal<Target | null>(null)

/** Opens the sheet for a trailer. Nothing is requested from YouTube until this runs. */
export function openTrailer(key: string, opener: HTMLElement | null) {
    setTarget({ key, opener })
}

export function TrailerSheet() {
    let dialog!: HTMLDialogElement
    let frame: HTMLIFrameElement | undefined
    let closeButton: HTMLButtonElement | undefined
    const location = useLocation()
    const [state, setState] = createSignal<State>(LOADING)

    createEffect(() => {
        if (target() && !dialog.open) dialog.showModal()
        else if (!target() && dialog.open) dialog.close()
    })
    // The focus is in the sheet from the first moment: on Close, which is always there.
    createEffect(() => {
        if (target()) queueMicrotask(() => closeButton?.focus({ preventScroll: true }))
    })

    const close = () => {
        const was = target()
        if (!was) return
        setTarget(null)
        // After the dialog has let go of the page, so nothing takes the focus back from it.
        queueMicrotask(() => was.opener?.isConnected && was.opener.focus({ preventScroll: true }))
    }

    // A new page behind it (Back, a link) ends it.
    createEffect(on(() => location.pathname, close, { defer: true }))

    // One trailer's listening: YouTube's posts to this page, the handshake that makes the embed post
    // them, and the wait for `ready`. Each is only for this trailer and ends with it.
    createEffect(
        on(target, (now) => {
            setState(LOADING)
            if (!now) return
            const hear = (e: MessageEvent) => {
                if (e.origin !== HOST || !frame || e.source !== frame.contentWindow) return
                const message = parseWidgetMessage(e.data)
                if (message) setState((s) => next(s, message))
            }
            window.addEventListener('message', hear)
            const wait = setTimeout(() => setState((s) => timedOut(s)), READY_TIMEOUT_MS)
            // The embed answers only a page that says it is listening: said again until it has spoken.
            const greet = setInterval(() => {
                if (state().phase !== 'loading') return clearInterval(greet)
                frame?.contentWindow?.postMessage(JSON.stringify({ event: 'listening', id: 'tmplayer', channel: 'widget' }), HOST)
            }, 500)
            onCleanup(() => {
                window.removeEventListener('message', hear)
                clearTimeout(wait)
                clearInterval(greet)
            })
        }),
    )

    // The player is gone when it failed, so the link is the way on: the focus lands on it.
    let link: HTMLAnchorElement | undefined
    createEffect(() => {
        if (target() && fallsBack(state())) queueMicrotask(() => link?.focus({ preventScroll: true }))
    })

    return (
        <dialog class="confirm trailer-sheet" ref={dialog} aria-labelledby="trailer-title" onClose={close} onClick={(e) => e.target === dialog && close()}>
            <Show when={target()} keyed>
                {(now) => (
                    <div class="trailer-body">
                        <h2 id="trailer-title">{t('trailer.title')}</h2>
                        {/* Where it plays: YouTube's tile and name, drawn only (aria-hidden tile, one name). */}
                        <ServiceBadge service="youtube" name />
                        <Show
                            when={!fallsBack(state())}
                            fallback={
                                <div class="trailer-fallback" role="alert">
                                    <p>{t('metadata.trailer_unavailable')}</p>
                                    <a ref={link} class="button primary" href={watchUrl(now.key) ?? undefined} target="_blank" rel="noopener noreferrer">
                                        {t('metadata.trailer_on_youtube')}
                                    </a>
                                </div>
                            }
                        >
                            <div class="trailer-frame">
                                <iframe
                                    ref={frame}
                                    src={embedUrl(now.key, pageOrigin()) ?? undefined}
                                    title={t('trailer.title')}
                                    allow="autoplay; encrypted-media; picture-in-picture; fullscreen"
                                    allowfullscreen
                                    referrerpolicy="strict-origin-when-cross-origin"
                                />
                                <Show when={state().phase === 'loading'}>
                                    <p class="trailer-loading" role="status">{t('trailer.loading')}</p>
                                </Show>
                            </div>
                            <p class="muted trailer-note">{t('trailer.loads_from_youtube')}</p>
                        </Show>
                        <div class="confirm-buttons">
                            <button ref={closeButton} class="tonal trailer-close" type="button" onClick={close}>{t('trailer.close')}</button>
                        </div>
                    </div>
                )}
            </Show>
        </dialog>
    )
}

/** This page's origin, which YouTube's embed is told (`origin`), or the embed's own host where a page has none. */
function pageOrigin(): string {
    return window.location.origin.startsWith('http') ? window.location.origin : HOST
}
