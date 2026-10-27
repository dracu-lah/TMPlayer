// A page's own top: the round Back button at the top start and a pill naming the page beside it,
// drawn as the app's floating chrome is (the menu fill, the floating hairline, a soft shadow).
import type { JSX } from 'solid-js'
import { useNavigate } from '@solidjs/router'
import { ArrowLeft02Icon } from '@hugeicons/core-free-icons'
import { Icon } from './Icon'
import { initialOf } from './initial'
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

/** A chat's picture where there is none yet: its first letter on the raised surface. */
export function Initial(props: { name: string; class?: string }) {
    return (
        <span class={props.class ? `initial ${props.class}` : 'initial'} aria-hidden="true">
            {initialOf(props.name)}
        </span>
    )
}
