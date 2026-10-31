// The Add chats picker (HX27): which of the viewer's chats TMPlayer draws from. Nothing changes in
// Telegram either way; Done always writes a choice, even an empty one, because an empty choice is
// a real answer and not a reason to fall back to every chat (store.ts setAddedChats).
import { createEffect, createMemo, createResource, createSignal, For, onCleanup, onMount, Show } from 'solid-js'
import { useNavigate } from '@solidjs/router'
import { Search01Icon, StarIcon } from '@hugeicons/core-free-icons'
import { api, call } from '../tg/rpc'
import { t } from '../i18n'
import { addedChats, setAddedChats, setStarred, starredIds, type AddedChat } from '../store'
import { back } from '../session'
import { Icon } from '../ui/Icon'
import { PageBar } from '../ui/PageBar'
import { VirtualGrid, type GridLayout } from '../ui/VirtualGrid'
import { ChatPicture } from './ChatPicture'
import { chatName } from './Chats'
import type { ChatItem, ChatsEvent } from '../tg/types'

/** One row at a time, as wide as the list: a tick, the picture, the name and its rough count. */
const ROW_LAYOUT: GridLayout = { cols: 1, estimate: 72 }

/** Counts are asked for three chats at a time (library.ts videoCounts); -1 is "could not be read". */
const UNKNOWN = -1
/** Past this the exact figure is not worth a longer phrase. */
const MANY = 1000

export function AddChats() {
    const navigate = useNavigate()

    // The full account's chats, unfiltered: picking from them is the one place the gate itself
    // does not apply (Chats.tsx is the gated browse list; this is where the gate is set).
    const [partial, setPartial] = createSignal<ChatItem[]>([])
    const [chatsRes] = createResource(() => call('chats', [false], { onEvent: (e) => void setPartial((e as ChatsEvent).chats) }))
    const list = () => chatsRes() ?? partial()
    const loaded = () => chatsRes() !== undefined

    const [stored] = createResource(addedChats)

    // What is ticked, seeded once from the stored choice (an empty choice from before stays empty, not "every chat").
    const [ticked, setTicked] = createSignal<ReadonlySet<number>>(new Set())
    let seeded = false
    createEffect(() => {
        const s = stored()
        if (s && !seeded) {
            seeded = true
            setTicked(new Set(s.map((a) => a.chatId)))
        }
    })

    const [text, setText] = createSignal('')
    const query = createMemo(() => text().trim().toLowerCase())
    const shown = createMemo(() => {
        const q = query()
        return q ? list().filter((c) => chatName(c).toLowerCase().includes(q)) : list()
    })

    // A chat once added, now left or deleted: still on the list to remove, under its own band.
    const goneRows = createMemo((): AddedChat[] => {
        if (!loaded()) return []
        const here = new Set(list().map((c) => c.id))
        return (stored() ?? []).filter((a) => !here.has(a.chatId))
    })

    // Each row's rough video count, asked for three chats at a time as it comes on screen.
    const [known, setKnown] = createSignal<ReadonlyMap<number, number>>(new Map())
    const requested = new Set<number>()
    let pendingIds: number[] = []
    let flush = 0
    async function resolveCounts(ids: number[]) {
        try {
            const counts = await api.videoCounts(ids)
            setKnown((m) => {
                const next = new Map(m)
                for (const [k, v] of Object.entries(counts)) next.set(Number(k), v as number)
                return next
            })
        } catch {
            ids.forEach((id) => requested.delete(id))
        }
    }
    const wantCount = (id: number) => {
        if (requested.has(id)) return
        requested.add(id)
        pendingIds.push(id)
        clearTimeout(flush)
        flush = window.setTimeout(() => {
            const ids = pendingIds
            pendingIds = []
            void resolveCounts(ids)
        }, 80)
    }
    const retryCount = (id: number) => {
        requested.delete(id)
        wantCount(id)
    }
    onCleanup(() => clearTimeout(flush))

    const toggle = (id: number) =>
        setTicked((t) => {
            const next = new Set(t)
            if (next.has(id)) next.delete(id)
            else next.add(id)
            return next
        })

    const removeGone = (id: number) =>
        setTicked((t) => {
            const next = new Set(t)
            next.delete(id)
            return next
        })

    const visibleIds = () => shown().map((c) => c.id)
    const allVisibleOn = () => {
        const ids = visibleIds()
        return ids.length > 0 && ids.every((id) => ticked().has(id))
    }
    const toggleVisible = () => {
        const ids = visibleIds()
        const on = !allVisibleOn()
        setTicked((t) => {
            const next = new Set(t)
            for (const id of ids) (on ? next.add(id) : next.delete(id))
            return next
        })
    }

    const countOf = (id: number) => known().get(id)
    const totalVideos = () => [...ticked()].reduce((sum, id) => sum + Math.max(0, countOf(id) ?? 0), 0)
    const stillCounting = () => [...ticked()].some((id) => countOf(id) === undefined)
    const footer = () => {
        const n = ticked().size
        if (!n) return t('picker.total_none')
        if (stillCounting()) return t('picker.total_counting', { chats: n, videos: totalVideos() })
        return t('picker.total_videos', { chats: n, videos: totalVideos() })
    }

    const [saving, setSaving] = createSignal(false)
    const done = () => {
        if (saving()) return
        setSaving(true)
        const byId = new Map(list().map((c) => [c.id, chatName(c)]))
        const oldNames = new Map((stored() ?? []).map((a) => [a.chatId, a.title]))
        const chosen = [...ticked()].map((chatId) => ({ chatId, title: byId.get(chatId) ?? oldNames.get(chatId) ?? '' }))
        setAddedChats(chosen)
        back(navigate)
    }

    return (
        <>
            <PageBar title={t('browse.add_chats')} />
            <h1 class="visually-hidden">{t('browse.add_chats')}</h1>
            <p class="muted picker-blurb">{t('picker.blurb')}</p>
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
            <Show when={loaded() && list().length > 0 && shown().length === 0}>
                <p class="muted">{t('chats.no_match', { query: text().trim() })}</p>
            </Show>
            <VirtualGrid items={shown()} layout={() => ROW_LAYOUT} rowClass="chats" label={t('browse.add_chats')}>
                {(c) => (
                    <PickRow chat={c} on={ticked().has(c.id)} onToggle={() => toggle(c.id)} count={countOf(c.id)} onWant={() => wantCount(c.id)} onRetry={() => retryCount(c.id)} />
                )}
            </VirtualGrid>
            <Show when={goneRows().length}>
                <h2 class="picker-gone-head">{t('picker.band_gone')}</h2>
                <ul class="chats">
                    <For each={goneRows()}>
                        {(a) => (
                            <li class="picker-gone-row">
                                <span class="chat-name">{a.title}</span>
                                <span class="muted small">{t('picker.gone_detail')}</span>
                                <button class="link" type="button" onClick={() => removeGone(a.chatId)}>
                                    {t('picker.remove')}
                                </button>
                            </li>
                        )}
                    </For>
                </ul>
            </Show>
            <div class="select-bar">
                <span class="select-count" aria-live="polite">{footer()}</span>
                <button class="primary" type="button" disabled={saving()} onClick={done}>{t('common.done')}</button>
            </div>
        </>
    )
}

function PickRow(props: { chat: ChatItem; on: boolean; onToggle: () => void; count: number | undefined; onWant: () => void; onRetry: () => void }) {
    let el!: HTMLDivElement
    onMount(() => {
        const io = new IntersectionObserver((entries) => {
            if (!entries.some((e) => e.isIntersecting)) return
            io.disconnect()
            props.onWant()
        })
        io.observe(el)
        onCleanup(() => io.disconnect())
    })
    return (
        <div ref={el} class="chat-row">
            <label class="pick-chat">
                <input type="checkbox" class="chat-tick" checked={props.on} onChange={props.onToggle} aria-label={chatName(props.chat)} />
                <ChatPicture photo={props.chat.photo} name={chatName(props.chat)} self={props.chat.self} />
                <span class="chat-text">
                    <span class="chat-name">{chatName(props.chat)}</span>
                    <CountLabel count={props.count} onRetry={props.onRetry} />
                </span>
            </label>
            <PickerStar chatId={props.chat.id} />
        </div>
    )
}

/** A chat's star, as Chats.tsx's own StarButton has it (kept here too: that one is this file's own screen, not a shared component). */
function PickerStar(props: { chatId: number }) {
    const on = () => starredIds().has(props.chatId)
    const label = () => (on() ? t('chats.unfavourite') : t('chats.favourite'))
    return (
        <button class="icon-button star" classList={{ on: on() }} type="button" aria-pressed={on()} aria-label={label()} title={label()} onClick={() => setStarred(props.chatId, !on())}>
            <Icon icon={StarIcon} size={22} selected={on()} />
        </button>
    )
}

function CountLabel(props: { count: number | undefined; onRetry: () => void }) {
    return (
        <span class="muted chat-count">
            <Show
                when={props.count !== UNKNOWN}
                fallback={
                    <button class="link" type="button" onClick={(e) => { e.preventDefault(); props.onRetry() }}>
                        {t('picker.count_again')}
                    </button>
                }
            >
                <Show when={props.count !== undefined} fallback=" ">
                    {(props.count ?? 0) >= MANY ? t('picker.count_many') : t('picker.count_about', { count: props.count ?? 0 })}
                </Show>
            </Show>
        </span>
    )
}
