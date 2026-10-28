// A short message at the foot with one action (Undo), gone by itself after 8 s: 5 s was gone before
// a viewer reached it on the apps (5246be8). One at a time; a new one replaces the last. On a TV
// the remote reaches its Undo from the bar or the page's foot (focus.ts, HX22), and it stays up
// while the remote is in it, its 8 s starting again once the remote leaves.
import { createSignal, onCleanup, Show } from 'solid-js'

interface Toast {
    text: string
    action?: { label: string; run: () => void }
}

const [toast, setToast] = createSignal<Toast | null>(null)
let timer = 0

const SHOWN_MS = 8000

export function showToast(next: Toast) {
    setToast(next)
    restart()
}

function restart() {
    clearTimeout(timer)
    timer = window.setTimeout(() => setToast(null), SHOWN_MS)
}

export function Toaster() {
    onCleanup(() => clearTimeout(timer))
    return (
        <Show when={toast()}>
            {(tt) => (
                <div
                    class="toast"
                    role="status"
                    data-tv-reach="bottom"
                    onFocusIn={() => clearTimeout(timer)}
                    onFocusOut={(e) => {
                        if (!e.currentTarget.contains(e.relatedTarget as Node | null)) restart()
                    }}
                >
                    <p>{tt().text}</p>
                    <Show when={tt().action}>
                        {(a) => (
                            <button
                                class="link"
                                type="button"
                                onClick={() => {
                                    clearTimeout(timer)
                                    setToast(null)
                                    a().run()
                                }}
                            >
                                {a().label}
                            </button>
                        )}
                    </Show>
                </div>
            )}
        </Show>
    )
}
