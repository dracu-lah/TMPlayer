import { createSignal, onCleanup, type Accessor } from 'solid-js'

/**
 * A value that shows for `ms` and then goes by itself (a note, a chip, a card): setting it again restarts the
 * wait, `clear` ends it now, and the timer goes with the component.
 */
export function timed<T>(ms: number): [value: Accessor<T | null>, show: (value: T) => void, clear: () => void] {
    const [value, set] = createSignal<T | null>(null)
    let timer = 0
    const clear = () => {
        clearTimeout(timer)
        set(null)
    }
    onCleanup(() => clearTimeout(timer))
    return [
        value,
        (v) => {
            clearTimeout(timer)
            set(() => v)
            timer = window.setTimeout(() => set(null), ms)
        },
        clear,
    ]
}
