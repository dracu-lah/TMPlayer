// A short message at the foot with one action (Undo), gone by itself after 8 s: 5 s was gone before
// a viewer reached it on the apps (5246be8). One at a time; a new one replaces the last.
import { createSignal, onCleanup, Show } from 'solid-js'

interface Toast {
    text: string
    action?: { label: string; run: () => void }
}

const [toast, setToast] = createSignal<Toast | null>(null)
let timer = 0

export function showToast(next: Toast) {
    clearTimeout(timer)
    setToast(next)
    timer = window.setTimeout(() => setToast(null), 8000)
}

export function Toaster() {
    onCleanup(() => clearTimeout(timer))
    return (
        <Show when={toast()}>
            {(tt) => (
                <div class="toast" role="status">
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
