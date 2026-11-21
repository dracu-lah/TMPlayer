// One of the detail page's everyday actions.
import { Show, type JSX } from 'solid-js'
import '../series.css'
import { Icon, type IconData } from '../../ui/Icon'
import '../versions.css'


/** One of the everyday actions: a round outlined button with its name under it; a switch fills when on. A link opens elsewhere. */
export function Round(props: { icon: IconData; label: string; on?: boolean; onClick?: (e: MouseEvent & { currentTarget: HTMLButtonElement }) => void; href?: string; popup?: boolean }): JSX.Element {
    const inner = () => (
        <>
            <span class="detail-round-disc">
                <Icon icon={props.icon} size={22} />
            </span>
            <span class="detail-round-label">{props.label}</span>
        </>
    )
    return (
        <Show
            when={props.href}
            fallback={
                <button type="button" class="detail-round" aria-pressed={props.on === undefined ? undefined : props.on} aria-haspopup={props.popup ? 'dialog' : undefined} onClick={props.onClick}>
                    {inner()}
                </button>
            }
        >
            <a class="detail-round" href={props.href} target="_blank" rel="noopener noreferrer">
                {inner()}
            </a>
        </Show>
    )
}
