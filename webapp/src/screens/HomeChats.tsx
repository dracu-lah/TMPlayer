// Settings > Home > Choose chats (the apps' HomeSettingsSheets, 8f55cf2): the chats Recently added,
// and the carousel drawn from it, read when Home's source is Chosen chats. A search, Select all
// over what is shown (Ctrl+A on a desktop too), and the count. Done writes the choice; Back leaves
// it as it was. A chosen chat the list does not carry (archived, or reached through Add chats) is
// kept, not dropped for being out of sight.
import { createEffect, createMemo, createResource, createSignal, onCleanup, onMount, Show } from 'solid-js'
import { useNavigate } from '@solidjs/router'
import { Search01Icon } from '@hugeicons/core-free-icons'
import { call } from '../tg/rpc'
import { t } from '../i18n'
import { form } from '../form'
import { settings, setSetting, RECENT_CHATS_MAX } from '../store'
import { back } from '../session'
import { Icon } from '../ui/Icon'
import { PageBar } from '../ui/PageBar'
import { VirtualGrid, type GridLayout } from '../ui/VirtualGrid'
import { ChatPicture } from './ChatPicture'
import { chatName } from './Chats'
import type { ChatItem, ChatsEvent } from '../tg/types'

const ROW_LAYOUT: GridLayout = { cols: 1, estimate: 72 }

export function HomeChats() {
    const navigate = useNavigate()
    const [partial, setPartial] = createSignal<ChatItem[]>([])
    const [chatsRes] = createResource(() => call('chats', [false], { onEvent: (e) => void setPartial((e as ChatsEvent).chats) }).catch(() => [] as ChatItem[]))
    const list = () => chatsRes() ?? partial()
    const loaded = () => chatsRes() !== undefined

    // Seeded once from the setting; the picker's own until Done.
    const [ticked, setTicked] = createSignal<ReadonlySet<number>>(new Set(settings().recentChats))
    let touched = false
    createEffect(() => {
        const saved = settings().recentChats
        if (!touched) setTicked(new Set(saved))
    })

    const [text, setText] = createSignal('')
    const shown = createMemo(() => {
        const q = text().trim().toLowerCase()
        return q ? list().filter((c) => chatName(c).toLowerCase().includes(q)) : list()
    })
    const change = (next: Set<number>) => {
        touched = true
        setTicked(next)
    }
    const toggle = (id: number) => {
        const next = new Set(ticked())
        if (next.has(id)) next.delete(id)
        else next.add(id)
        change(next)
    }
    const visibleIds = () => shown().map((c) => c.id)
    const allVisibleOn = () => visibleIds().length > 0 && visibleIds().every((id) => ticked().has(id))
    const toggleVisible = () => {
        const on = !allVisibleOn()
        const next = new Set(ticked())
        for (const id of visibleIds()) (on ? next.add(id) : next.delete(id))
        change(next)
    }

    // Ctrl+A (Cmd+A) selects every chat shown, as the desktop app's picker does; not while typing in the search.
    const onKey = (e: KeyboardEvent) => {
        if (form() !== 'desktop' || !(e.ctrlKey || e.metaKey) || e.key.toLowerCase() !== 'a') return
        if ((e.target as HTMLElement | null)?.closest('input[type=search]')) return
        e.preventDefault()
        if (!allVisibleOn()) toggleVisible()
    }
    onMount(() => document.addEventListener('keydown', onKey))
    onCleanup(() => document.removeEventListener('keydown', onKey))

    const done = () => {
        setSetting('recentChats', [...ticked()].slice(0, RECENT_CHATS_MAX))
        back(navigate)
    }

    return (
        <>
            <PageBar title={t('settings.home_chats_title')} />
            <h1 class="visually-hidden">{t('settings.home_chats_title')}</h1>
            <p class="muted picker-blurb">{t('settings.home_chats_note')}</p>
            <label class="search-field">
                <Icon icon={Search01Icon} size={20} />
                <input type="search" placeholder={t('chats.search')} aria-label={t('chats.search')} value={text()} onInput={(e) => setText(e.currentTarget.value)} />
            </label>
            <div class="picker-actions">
                <button class="link" type="button" onClick={toggleVisible} disabled={!visibleIds().length}>
                    {allVisibleOn() ? t('browse.select_none') : t('browse.select_all')}
                </button>
            </div>
            <Show when={!loaded() && !list().length}>
                <p class="muted">{t('picker.loading')}</p>
            </Show>
            <Show when={loaded() && !list().length}>
                <p class="muted">{t('picker.empty')}</p>
            </Show>
            <Show when={list().length > 0 && shown().length === 0}>
                <p class="muted">{t('settings.home_chats_no_match')}</p>
            </Show>
            <VirtualGrid items={shown()} layout={() => ROW_LAYOUT} rowClass="chats" label={t('settings.home_chats_title')}>
                {(c) => (
                    <div class="chat-row">
                        <label class="pick-chat">
                            <input type="checkbox" class="chat-tick" checked={ticked().has(c.id)} onChange={() => toggle(c.id)} aria-label={chatName(c)} />
                            <ChatPicture photo={c.photo} name={chatName(c)} self={c.self} />
                            <span class="chat-text">
                                <span class="chat-name">{chatName(c)}</span>
                            </span>
                        </label>
                    </div>
                )}
            </VirtualGrid>
            <div class="select-bar">
                <span class="select-count" aria-live="polite">{t('settings.home_source_chosen_detail', { count: ticked().size })}</span>
                <button class="primary" type="button" onClick={done}>{t('common.done')}</button>
            </div>
        </>
    )
}
