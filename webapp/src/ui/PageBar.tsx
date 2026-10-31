// A page's own top: the round Back button at the top start and a pill naming the page beside it,
// drawn as the app's floating chrome is (the menu fill, the floating hairline, a soft shadow).
import type { JSX } from 'solid-js'
import { useNavigate } from '@solidjs/router'
import { ArrowLeft02Icon } from '@hugeicons/core-free-icons'
import { Icon } from './Icon'
import { initialOf } from './initial'
import { nameArt } from './colour'
import { back } from '../session'

/** `actions`, round buttons, sit at the far end, as the chat's own bar has Star, Sort, Search and Refresh. */
export function PageBar(props: { title: string; picture?: JSX.Element; actions?: JSX.Element }) {
    const navigate = useNavigate()
    return (
        <div class="page-bar">
            <button class="round-button" type="button" aria-label="Back" title="Back" onClick={() => back(navigate)}>
                <Icon icon={ArrowLeft02Icon} size={22} />
            </button>
            <div class="title-pill" hidden={!props.title}>
                {props.picture}
                <span>{props.title}</span>
            </div>
            <div class="bar-actions">{props.actions}</div>
        </div>
    )
}

/**
 * A chat's picture where there is none: its first letter on the chat's own colour, the same
 * colour the apps draw for that name (HX30 a). A name with no letter at all keeps the colour.
 */
export function Initial(props: { name: string | null | undefined; class?: string }) {
    return (
        <span
            class={props.class ? `initial ${props.class}` : 'initial'}
            style={{ background: nameArt(props.name), color: '#fff' }}
            aria-hidden="true"
        >
            {initialOf(props.name)}
        </span>
    )
}
