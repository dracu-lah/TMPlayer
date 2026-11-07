// The one-tap way into Choose chats (/added-chats), a person with a plus. It sits beside the other
// header buttons on Home and Chats (phone and desktop in the page's head, the TV in its bar), and
// goes where the Settings row goes. While the chats gate is up it is not drawn: the gate is the chooser.
import { Show } from 'solid-js'
import { A } from '@solidjs/router'
import { UserAdd01Icon } from '@hugeicons/core-free-icons'
import { Icon } from './Icon'
import { t } from '../i18n'
import { chatsGated } from '../store'

export function ChooseChatsButton(props: { class?: string; size?: number }) {
    return (
        <Show when={!chatsGated()}>
            <A
                class={props.class ? `choose-chats ${props.class}` : 'round-button choose-chats'}
                href="/added-chats"
                aria-label={t('picker.choose_chats')}
                title={t('picker.choose_chats')}
                data-tv-back
            >
                <Icon icon={UserAdd01Icon} size={props.size ?? 22} />
            </A>
        </Show>
    )
}
