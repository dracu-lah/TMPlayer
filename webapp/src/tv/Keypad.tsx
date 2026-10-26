// The TV's number pad (plan 8.5, session 21): sign in by phone with the remote's arrows and OK,
// no TV keyboard. Three columns of digits with Delete and 0 under them. It only types digits for
// the form that holds it; the remote's own number keys type through the same functions there.
import { For, onMount } from 'solid-js'
import { Icon, type IconData } from '../ui/Icon'
import { t } from '../i18n'

/** Backspace: the key's outline and a cross, drawn in Hugeicons' stroke. */
const BackspaceIcon: IconData = [
    ['path', { d: 'M20 5H9L3 12l6 7h11a1 1 0 0 0 1-1V6a1 1 0 0 0-1-1Z' }],
    ['path', { d: 'M12.5 9.5l5 5M17.5 9.5l-5 5' }],
]

export default function Keypad(props: { onDigit: (digit: string) => void; onDelete: () => void; label: string }) {
    let el!: HTMLDivElement
    // The remote lands on the pad: the first digit under it.
    onMount(() => el.querySelector<HTMLElement>('button')?.focus({ preventScroll: true }))
    return (
        <div ref={el} class="keypad" role="group" aria-label={props.label}>
            <For each={['1', '2', '3', '4', '5', '6', '7', '8', '9']}>
                {(d) => (
                    <button type="button" class="key" onClick={() => props.onDigit(d)}>{d}</button>
                )}
            </For>
            <button type="button" class="key" aria-label={t('common.delete')} onClick={props.onDelete}>
                <Icon icon={BackspaceIcon} size={28} />
            </button>
            <button type="button" class="key" onClick={() => props.onDigit('0')}>0</button>
        </div>
    )
}
