import { createResource, createSignal, onCleanup, Show } from 'solid-js'
import { useLocation, useParams } from '@solidjs/router'
import { Share08Icon } from '@hugeicons/core-free-icons'
import { api } from '../tg/rpc'
import { t } from '../i18n'
import { Icon } from '../ui/Icon'
import { PageBar } from '../ui/PageBar'
import { ChatPicture } from './ChatPicture'
import type { ChatInfo as Info } from '../tg/types'

/**
 * A chat's info (the apps' ChatInfoSheet, here a page of its own behind the chat's Info button): its
 * large picture, its name, what kind of chat it is and how many members or subscribers it has, the
 * public @username (a press copies it), the description, and for a public chat Share and Copy link
 * for its t.me address. The name and the small picture the chat already had draw at once while the
 * rest is asked for; a failure says so and offers Try again, so it does not pass for a wait.
 */
export function ChatInfo() {
    const params = useParams<{ chatId: string }>()
    const location = useLocation<{ chat?: { title: string; photo: string | null; self: boolean } }>()
    const chatId = () => Number(params.chatId)
    const [info, { refetch }] = createResource(chatId, (id) => api.chatInfo(id))
    const known = () => location.state?.chat
    const title = () => info()?.title ?? known()?.title ?? ''
    const photo = () => info()?.photo ?? known()?.photo ?? null
    const self = () => info()?.kind === 'saved' || !!known()?.self
    const [said, setSaid] = createSignal('')
    let timer = 0
    onCleanup(() => clearTimeout(timer))
    const say = (text: string) => {
        setSaid(text)
        clearTimeout(timer)
        timer = window.setTimeout(() => setSaid(''), 2500)
    }
    /** Copies to the clipboard where the browser lets the page, else leaves the text selected for the viewer to copy. */
    const copy = async (text: string, done: string) => {
        try {
            await navigator.clipboard.writeText(text)
            say(done)
        } catch {
            say(text)
        }
    }
    const share = (link: string) => {
        if (navigator.share) navigator.share({ url: link, title: title() }).catch(() => {})
        else copy(link, t('common.link_copied'))
    }
    return (
        <>
            <PageBar title={t('chats.info_about')} />
            <article class="chat-info">
                <ChatPicture photo={photo()} name={title()} self={self()} class="avatar huge" />
                <h1 class="chat-info-name">{title()}</h1>
                <Show when={info() && facts(info()!)}>
                    <p class="muted">{facts(info()!)}</p>
                </Show>
                <Show when={info()?.username}>
                    <button type="button" class="chat-info-username" title={t('chats.info_copy_username')} onClick={() => copy(`@${info()!.username}`, t('chats.info_username_copied'))}>
                        @{info()!.username}
                    </button>
                </Show>
                <Show when={info()?.about}>
                    <p class="chat-info-about">{info()!.about}</p>
                </Show>
                <Show when={info()?.link}>
                    <div class="chat-info-links">
                        <button type="button" class="outline-button" onClick={() => share(info()!.link!)}>
                            <Icon icon={Share08Icon} size={18} />
                            {t('common.share')}
                        </button>
                        <button type="button" class="outline-button" onClick={() => copy(info()!.link!, t('common.link_copied'))}>{t('common.copy_link')}</button>
                    </div>
                    <p class="muted small" dir="ltr">{info()!.link!.replace('https://', '')}</p>
                </Show>
                <Show when={info.error || info()?.failed}>
                    <p class="muted">{t('chats.info_failed')}</p>
                    <button type="button" class="outline-button" onClick={() => refetch()}>{t('common.try_again')}</button>
                </Show>
                <p class="chat-info-said muted small" role="status" aria-live="polite">{said()}</p>
            </article>
        </>
    )
}

/** "Channels  ·  12,400 subscribers", "Groups  ·  31 members", or the kind alone: the apps' wording. */
function facts(info: Info): string {
    const kind = info.kind === 'saved' ? '' : info.kind === 'channel' ? t('chats.kind_channels') : info.kind === 'group' ? t('chats.kind_groups') : t('chats.kind_people')
    const count = info.members === null ? '' : info.kind === 'channel' ? t('chats.info_subscribers', { count: info.members }) : t('chats.info_members', { count: info.members })
    return [kind, count].filter(Boolean).join('  ·  ')
}
