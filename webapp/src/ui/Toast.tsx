// A short message at the foot with one action (Undo), gone by itself after 8 s: 5 s was gone before
// a viewer reached it on the apps (5246be8). One at a time; a new one replaces the last. On a TV
// the remote reaches its Undo from the bar or the page's foot (focus.ts, HX22), and it stays up
// while the remote is in it, its 8 s starting again once the remote leaves.
//
// It stands above the floating bar, never behind it: its bottom edge is measured from the bar's top
// edge (and the status pill's) while it is up, plus 12 px, so no constant has to follow the bar's
// height (a larger text size, a phone held sideways, a chat's own bar). Four kinds (success, info,
// warning, error) give it a tinted card, a round chip and an icon that moves once (CSS); info is the
// default. Errors are announced at once (role alert), the rest politely (role status). A press on
// the text or a swipe away dismisses it; it never takes focus.
import { createEffect, createSignal, onCleanup, Show } from 'solid-js'
import { clearanceOf, TOAST_GAP } from './toastPlace'

export type ToastKind = 'success' | 'info' | 'warning' | 'error'

interface Toast {
    text: string
    action?: { label: string; run: () => void }
    /** What the message is: a done thing (success), a plain note (info, the default), a limit or a refusal (warning) or a failure (error). */
    kind?: ToastKind
}

const [toast, setToast] = createSignal<Toast | null>(null)
/** It is fading out (120 ms) before it goes; a new message ends that at once. */
const [leaving, setLeaving] = createSignal(false)
let timer = 0
let leave = 0
const LEAVE_MS = 120

const SHOWN_MS = 8000

export function showToast(next: Toast) {
    clearTimeout(leave)
    setLeaving(false)
    setToast(next)
    restart()
}

function restart() {
    clearTimeout(timer)
    timer = window.setTimeout(hide, SHOWN_MS)
}

/** Fades out, then goes. */
function hide() {
    clearTimeout(timer)
    if (!toast() || leaving()) return
    setLeaving(true)
    leave = window.setTimeout(() => (setToast(null), setLeaving(false)), LEAVE_MS)
}

const measure = (): number | null =>
    clearanceOf((selector) => {
        const el = document.querySelector<HTMLElement>(selector)
        const r = el?.getBoundingClientRect()
        return r && r.height > 0 ? r.top : null
    }, document.documentElement.clientHeight)

/** The icon of each kind, drawn inline (the chip colours it); the check and the cross draw themselves in CSS. */
function Glyph(props: { kind: ToastKind }) {
    return (
        <svg class="toast-icon" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            {props.kind === 'success' ? (
                <path class="toast-draw" d="M5 12.5l4.5 4.5L19 7.5" />
            ) : props.kind === 'error' ? (
                <>
                    <circle cx="12" cy="12" r="9" />
                    <path d="M9 9l6 6M15 9l-6 6" />
                </>
            ) : props.kind === 'warning' ? (
                <>
                    <path d="M12 4l9 16H3z" />
                    <path d="M12 10v4M12 17.2v.1" />
                </>
            ) : (
                <>
                    <circle cx="12" cy="12" r="9" />
                    <path d="M12 11v5M12 7.8v.1" />
                </>
            )}
        </svg>
    )
}

export function Toaster() {
    const [clear, setClear] = createSignal<number | null>(null)
    onCleanup(() => (clearTimeout(timer), clearTimeout(leave)))
    // While one is up: measure the bar at once, when the window changes and twice a second (a page change brings or takes away a bar).
    createEffect(() => {
        if (!toast()) return
        const now = () => {
            const c = measure()
            if (c !== clear()) setClear(c)
        }
        now()
        const every = window.setInterval(now, 500)
        addEventListener('resize', now)
        // The status pill coming or going is told by the page (data-status-pill), at once rather than at the next look.
        const pill = new MutationObserver(() => queueMicrotask(now))
        pill.observe(document.documentElement, { attributes: true, attributeFilter: ['data-status-pill'] })
        onCleanup(() => {
            clearInterval(every)
            removeEventListener('resize', now)
            pill.disconnect()
        })
    })
    // A swipe away (sideways or down) or a press on the words dismisses it.
    let from: { x: number; y: number } | null = null
    const dismiss = hide
    return (
        <Show when={toast()}>
            {(tt) => {
                const kind = () => tt().kind ?? 'info'
                return (
                    <div
                        class="toast"
                        classList={{ [`toast-${kind()}`]: true, leaving: leaving() }}
                        data-kind={kind()}
                        role={kind() === 'error' ? 'alert' : 'status'}
                        aria-live={kind() === 'error' ? 'assertive' : 'polite'}
                        data-tv-reach="bottom"
                        style={clear() === null ? undefined : { '--toast-bottom': `${clear()! + TOAST_GAP}px` }}
                        onFocusIn={() => clearTimeout(timer)}
                        onFocusOut={(e) => {
                            if (!e.currentTarget.contains(e.relatedTarget as Node | null)) restart()
                        }}
                        onPointerDown={(e) => {
                            from = { x: e.clientX, y: e.clientY }
                        }}
                        onPointerUp={(e) => {
                            const start = from
                            from = null
                            if (!start || (e.target as HTMLElement).closest('button')) return
                            const dx = e.clientX - start.x
                            const dy = e.clientY - start.y
                            if (Math.abs(dx) > 48 || dy > 32 || (Math.abs(dx) < 6 && Math.abs(dy) < 6)) dismiss()
                        }}
                        onPointerCancel={() => (from = null)}
                    >
                        <span class="toast-chip" aria-hidden="true">
                            <Glyph kind={kind()} />
                        </span>
                        <p>{tt().text}</p>
                        <Show when={tt().action}>
                            {(a) => (
                                <button
                                    class="link"
                                    type="button"
                                    onClick={() => {
                                        dismiss()
                                        a().run()
                                    }}
                                >
                                    {a().label}
                                </button>
                            )}
                        </Show>
                    </div>
                )
            }}
        </Show>
    )
}

/** The four kinds, for a test or a picture that draws each. */
export const KINDS: readonly ToastKind[] = ['success', 'info', 'warning', 'error']
