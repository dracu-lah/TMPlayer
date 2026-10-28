// The page's last stop for an error nobody caught (HX10): an exception thrown outside a handler
// that deals with it, or a promise rejected with nobody waiting. Before this, a failed render left
// a blank or frozen page with no way out. Now it is logged and a small card says so, with Reload
// (and Close, since a stray failure in the background need not end what is on screen). Plain DOM,
// so it shows even when the app's own rendering is what failed.
import { t } from './i18n'

/** Failures that are not the app breaking: a cancelled call, autoplay refused, the browser's own noise. */
function harmless(reason: unknown, message: string): boolean {
    const name = (reason as { name?: string } | null)?.name
    if (name === 'AbortError' || name === 'NotAllowedError') return true
    // Telegram's answers (a call's error carries its code) and a lost connection are the screens'
    // to show; a forgotten await on one is not the page breaking.
    if (typeof (reason as { code?: unknown } | null)?.code === 'number') return true
    if (/failed to fetch|network|timeout|timed out|offline|FLOOD_WAIT/i.test(message)) return true
    // Chrome reports a ResizeObserver that settled in the next frame as a window error.
    if (/ResizeObserver loop/.test(message)) return true
    // A cross-origin script's error carries nothing to act on.
    if (message === 'Script error.' && reason == null) return true
    return false
}

let card: HTMLElement | null = null

/**
 * The card's keys while it is up, or null. The remote's keys come to the card first (HX19): the
 * screens' own key handling listens on the window too and kept the focus, so on a TV neither button
 * could be reached. Left and Right move between the two, OK presses one, Back closes the card, and
 * nothing reaches the screen behind. Read by a listener [watchCrashes] puts on the window before
 * any screen's, so it runs first.
 */
let cardKeys: ((ev: KeyboardEvent) => void) | null = null

/**
 * Who takes a failure instead of the card: the player while a video is open, which says so in its
 * own status sheet with the remote already in it, rather than a card over the picture (HX19).
 */
let sink: (() => void) | null = null

/** The player's way in: returns the way out, for its cleanup. */
export function routeCrashes(to: () => void): () => void {
    sink = to
    return () => {
        if (sink === to) sink = null
    }
}

/** Shows the card, once: later failures while it is up add nothing. Exported for the tests. */
export function showCrashCard() {
    if (sink) return sink()
    if (card?.isConnected) return
    const box = document.createElement('div')
    box.className = 'crash-card'
    box.setAttribute('role', 'alertdialog')
    box.setAttribute('aria-live', 'assertive')
    const text = document.createElement('p')
    text.textContent = label('errors.screen_failed_title', 'Something went wrong')
    const reload = document.createElement('button')
    reload.className = 'primary'
    reload.textContent = label('common.reload', 'Reload')
    reload.onclick = () => location.reload()
    const close = document.createElement('button')
    close.className = 'link'
    close.textContent = label('common.close', 'Close')
    const row = document.createElement('div')
    row.append(reload, close)
    box.append(text, row)
    cardKeys = (ev: KeyboardEvent) => {
        const buttons = [reload, close]
        const at = buttons.indexOf(document.activeElement as HTMLButtonElement)
        const typing = /^(INPUT|TEXTAREA)$/.test((document.activeElement as HTMLElement | null)?.tagName ?? '')
        // Back as the TV layout reads it: Backspace (outside a text field) and webOS's 461 too.
        if (ev.key === 'Escape' || ev.key === 'GoBack' || ev.key === 'BrowserBack' || ev.keyCode === 461 || (ev.key === 'Backspace' && !typing)) {
            dismiss()
        } else if (ev.key === 'ArrowLeft' || ev.key === 'ArrowRight') {
            const step = (ev.key === 'ArrowRight') === (document.dir !== 'rtl') ? 1 : -1
            buttons[at < 0 ? 0 : (at + step + buttons.length) % buttons.length].focus()
        } else if (ev.key === 'Enter' || ev.key === ' ') {
            ;(at < 0 ? reload : buttons[at]).click()
        } else if (ev.key === 'ArrowUp' || ev.key === 'ArrowDown') {
            if (at < 0) reload.focus()
        } else {
            return
        }
        ev.preventDefault()
        ev.stopImmediatePropagation()
    }
    const dismiss = () => {
        cardKeys = null
        box.remove()
    }
    close.onclick = dismiss
    document.body.append(box)
    card = box
    reload.focus()
}

/** The catalog's words, or English when the catalog itself is what failed. */
function label(key: Parameters<typeof t>[0], fallback: string): string {
    try {
        return t(key) || fallback
    } catch {
        return fallback
    }
}

/** Installs the two listeners; called first thing in main.tsx. */
export function watchCrashes() {
    window.addEventListener('keydown', (ev) => cardKeys?.(ev), true)
    window.addEventListener('error', (ev) => {
        const message = ev.message ?? ''
        if (harmless(ev.error, message)) return
        console.error('[crash] uncaught', ev.error ?? message)
        showCrashCard()
    })
    window.addEventListener('unhandledrejection', (ev) => {
        const reason = ev.reason
        const message = String((reason as { message?: string } | null)?.message ?? reason ?? '')
        if (harmless(reason, message)) return
        console.error('[crash] unhandled rejection', reason)
        showCrashCard()
    })
}
