import { createEffect, createResource, createSignal, on, onCleanup, Show } from 'solid-js'
import { A, useSearchParams } from '@solidjs/router'
import { PinIcon, Search01Icon, StarIcon } from '@hugeicons/core-free-icons'
import { api, call, errorText } from '../tg/rpc'
import { onBackOnline } from '../net'
import { t } from '../i18n'
import { setStarred, starredIds } from '../store'
import { Icon } from '../ui/Icon'
import { tvDp, VirtualGrid, type GridLayout } from '../ui/VirtualGrid'
import { form } from '../form'
import { ChatPicture } from './ChatPicture'
import type { ChatItem, ChatsEvent } from '../tg/types'

/** The app's list rows: one column on a phone, as many 320 px columns as fit from 840. */
function chatLayout(width: number): GridLayout {
    // The TV layout: 280 dp columns 12 dp apart, rows of a 44 dp picture (tv.css).
    if (form() === 'tv') {
        const dp = tvDp()
        return { cols: Math.max(1, Math.floor((width + 12 * dp) / (292 * dp))), estimate: Math.round(56 * dp) }
    }
    const cols = window.innerWidth < 840 ? 1 : Math.max(1, Math.floor((width + 24) / (320 + 24)))
    return { cols, estimate: 72 }
}

export const chatName = (c: { title: string; self: boolean }) => (c.self ? t('chats.saved_messages') : c.title)

/**
 * Every chat outside the archive, pinned first, as Telegram orders them; pages of a hundred show
 * as they arrive. The filter's words are in the address (`?q=`), and the app's fuzzy search ranks
 * the names. Each row has its star.
 */
export function Chats() {
    const [params, setParams] = useSearchParams<{ q?: string }>()
    const [partial, setPartial] = createSignal<ChatItem[]>([])
    const [chats, { refetch }] = createResource(() => call('chats', [false], { onEvent: (e) => void setPartial((e as ChatsEvent).chats) }))
    onBackOnline(() => chats.error && refetch())
    const list = () => (chats.error ? partial() : (chats() ?? partial()))
    const [text, setText] = createSignal(params.q ?? '')
    const query = () => params.q?.trim() ?? ''
    // The ranking reads the whole list, so it waits for it.
    const [ranked] = createResource(
        () => (query() && chats() ? query() : false),
        (q) => api.rankChats(q),
    )
    const shown = () => {
        if (!query()) return list()
        const ids = ranked.latest
        if (!ids) return []
        const byId = new Map(list().map((c) => [c.id, c]))
        return ids.map((id) => byId.get(id)).filter((c): c is ChatItem => !!c)
    }
    let debounce = 0
    let typing = false
    onCleanup(() => clearTimeout(debounce))
    // Typing replaces the entry instead of adding one per word.
    const onType = (v: string) => {
        setText(v)
        typing = true
        clearTimeout(debounce)
        debounce = window.setTimeout(() => {
            typing = false
            setParams({ q: v || undefined }, { replace: true })
        }, 200)
    }
    createEffect(on(() => params.q, (q) => !typing && setText(q ?? ''), { defer: true }))
    return (
        <>
            <h1 class="page-title">{t('nav.chats')}</h1>
            <label class="search-field">
                <Icon icon={Search01Icon} size={20} />
                <input type="search" placeholder={t('chats.search')} aria-label={t('chats.search')} value={text()} onInput={(e) => onType(e.currentTarget.value)} />
            </label>
            <Show when={chats.error}>
                <div class="notice error-notice">
                    <p class="error">{errorText(chats.error)}</p>
                    <button class="link" type="button" onClick={refetch}>{t('common.try_again')}</button>
                </div>
            </Show>
            <Show when={chats.loading && !list().length}>
                <p class="muted">{t('browse.loading_chats')}</p>
            </Show>
            <Show when={!chats.loading && !chats.error && !list().length}>
                <p class="muted">{t('browse.no_chats')}</p>
            </Show>
            <Show when={query() && ranked() && !shown().length}>
                <p class="muted">{t('chats.no_match', { query: query() })}</p>
            </Show>
            <VirtualGrid items={shown()} layout={chatLayout} rowClass="chats" label={t('nav.chats')}>
                {(c) => <ChatRow chat={c} />}
            </VirtualGrid>
        </>
    )
}

export function ChatRow(props: { chat: ChatItem }) {
    return (
        <div class="chat-row">
            <A class="chat-link" href={`/chat/${props.chat.id}`}>
                <ChatPicture photo={props.chat.photo} name={chatName(props.chat)} self={props.chat.self} />
                <span class="chat-name">{chatName(props.chat)}</span>
                <Show when={props.chat.pinned}>
                    <span class="chat-pin" title={t('chats.pinned_label')}>
                        <Icon icon={PinIcon} size={16} />
                    </span>
                </Show>
            </A>
            <StarButton chatId={props.chat.id} />
        </div>
    )
}

/** A chat's star, filled when on, as the app's favourites. */
export function StarButton(props: { chatId: number; round?: boolean }) {
    const on = () => starredIds().has(props.chatId)
    const label = () => (on() ? t('chats.unfavourite') : t('chats.favourite'))
    return (
        <button
            class={props.round ? 'round-button star' : 'icon-button star'}
            classList={{ on: on() }}
            type="button"
            aria-pressed={on()}
            aria-label={label()}
            title={label()}
            onClick={() => setStarred(props.chatId, !on())}
        >
            <Icon icon={StarIcon} size={22} selected={on()} />
        </button>
    )
}
