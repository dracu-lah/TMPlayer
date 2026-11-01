// The Add chats picker (HX27): which of the viewer's chats TMPlayer draws from. Nothing changes in
// Telegram either way; Done always writes a choice, even an empty one, because an empty choice is
// a real answer and not a reason to fall back to every chat (store.ts setAddedChats). The rows,
// their bands and what Select all means are core's ChatPicking rules (ui/picking.ts); the archive
// is read whole beside the list (HX32), and a list that failed part way says so.
import { createEffect, createMemo, createResource, createSignal, For, onCleanup, onMount, Show } from 'solid-js'
import { useBeforeLeave, useNavigate, useSearchParams } from '@solidjs/router'
import { Search01Icon, StarIcon } from '@hugeicons/core-free-icons'
import { api, call } from '../tg/rpc'
import { t } from '../i18n'
import { addedChats, setAddedChats, setStarred, starredIds, type AddedChat } from '../store'
import { back } from '../session'
import { form } from '../form'
import { Icon } from '../ui/Icon'
import { PageBar } from '../ui/PageBar'
import { VirtualGrid, type GridLayout } from '../ui/VirtualGrid'
import { adding, allTicked, changed, clearVisible, needsConfirm, pickRows, SCOPES, selectAll, visibleIds, type Band, type PickRow, type Scope } from '../ui/picking'
import { ChatPicture } from './ChatPicture'
import { chatName } from './Chats'
import type { ChatItem, ChatsEvent } from '../tg/types'

/** One row at a time, as wide as the list: a tick, the picture, the name and its rough count. */
const ROW_LAYOUT: GridLayout = { cols: 1, estimate: 72 }

/** Counts are asked for three chats at a time (library.ts videoCounts); -1 is "could not be read". */
const UNKNOWN = -1
/** Past this the exact figure is not worth a longer phrase. */
const MANY = 1000

type Key = Parameters<typeof t>[0]

const SCOPE_LABEL: Record<Scope, Key> = {
    all: 'browse.filter_all',
    archived: 'browse.filter_archived',
    channels: 'browse.filter_channels',
    groups: 'browse.filter_groups',
    people: 'browse.filter_people',
}
const BAND_LABEL: Partial<Record<Band, Key>> = {
    starred: 'picker.band_starred',
    chats: 'browse.filter_all_chats',
    archived: 'browse.filter_archived',
    gone: 'picker.band_gone',
}

type Item = { head: string } | { row: PickRow<ChatItem> }

/** A chat list read from the worker, with its pages as they come and whether it failed part way. */
function useChatList(name: 'chats' | 'archivedChats') {
    const [partial, setPartial] = createSignal<ChatItem[]>([])
    const [res, { refetch }] = createResource(() => call(name, name === 'chats' ? [false] : [], { onEvent: (e) => void setPartial((e as ChatsEvent).chats) }) as Promise<ChatItem[]>)
    return {
        list: () => (res.error ? partial() : (res() ?? partial())),
        done: () => !res.error && res() !== undefined,
        failed: () => !!res.error,
        retry: () => void refetch(),
    }
}

export function AddChats() {
    const navigate = useNavigate()
    // Opened from the first run's "Your chats" step: Done goes on to where the visitor was headed.
    const [params] = useSearchParams<{ setup?: string; next?: string }>()
    const setupNext = () => (params.setup ? (params.next?.startsWith('/') ? params.next : '/') : null)

    // The full account's chats, unfiltered, archive included: picking from them is the one place
    // the gate itself does not apply (Chats.tsx is the gated browse list; this is where it is set).
    const main = useChatList('chats')
    const archive = useChatList('archivedChats')
    const list = createMemo(() => {
        const here = new Set(main.list().map((c) => c.id))
        return [...main.list(), ...archive.list().filter((c) => !here.has(c.id))]
    })
    const loaded = () => main.done() && archive.done()
    const failed = () => main.failed() || archive.failed()
    const retry = () => {
        if (main.failed()) main.retry()
        if (archive.failed()) archive.retry()
    }

    const [stored] = createResource(addedChats)

    // What is ticked, seeded once from the stored choice (an empty choice from before stays empty, not "every chat").
    const [ticked, setTicked] = createSignal<ReadonlySet<number>>(new Set())
    let start: ReadonlySet<number> = new Set()
    let seeded = false
    createEffect(() => {
        const s = stored()
        if (s && !seeded) {
            seeded = true
            start = new Set(s.map((a) => a.chatId))
            setTicked(start)
        }
    })

    const [text, setText] = createSignal('')
    const [scope, setScope] = createSignal<Scope>('all')
    const rows = createMemo(() =>
        pickRows({
            chats: list(),
            name: chatName,
            added: new Set((stored() ?? []).map((a) => a.chatId)),
            addedNames: new Map((stored() ?? []).map((a) => [a.chatId, a.title])),
            starred: starredIds(),
            query: text(),
            scope: scope(),
            listComplete: loaded(),
        }),
    )
    const visible = createMemo(() => visibleIds(rows()))
    // A heading over each band once there is more than one; Saved Messages has none.
    const items = createMemo((): Item[] => {
        const bands = new Set(rows().map((r) => r.band))
        const out: Item[] = []
        let last: Band | null = null
        for (const row of rows()) {
            const label = BAND_LABEL[row.band]
            if (row.band !== last && bands.size > 1 && label) out.push({ head: t(label) })
            last = row.band
            out.push({ row })
        }
        return out
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

    // Select all over the rows shown, with a word first when it is a lot; Clear once all are ticked. Ctrl+A runs the same.
    let confirmAll!: HTMLDialogElement
    const [asking, setAsking] = createSignal(0)
    const selectAllNow = () => {
        const n = adding(ticked(), visible())
        if (!n) return
        if (needsConfirm(n)) {
            setAsking(n)
            confirmAll.showModal()
        } else setTicked(selectAll(ticked(), visible()))
    }
    const allOn = () => allTicked(ticked(), visible())
    onMount(() => {
        const keys = (e: KeyboardEvent) => {
            if (form() !== 'desktop' || !(e.ctrlKey || e.metaKey) || e.key.toLowerCase() !== 'a') return
            if (e.target instanceof HTMLInputElement && e.target.type !== 'checkbox') return
            if (document.querySelector('dialog[open]')) return
            e.preventDefault()
            selectAllNow()
        }
        addEventListener('keydown', keys)
        onCleanup(() => removeEventListener('keydown', keys))
    })

    const countOf = (id: number) => known().get(id)
    const totalVideos = () => [...ticked()].reduce((sum, id) => sum + Math.max(0, countOf(id) ?? 0), 0)
    const stillCounting = () => [...ticked()].some((id) => countOf(id) === undefined)
    const footer = () => {
        const n = ticked().size
        if (!n) return t('picker.total_none')
        if (stillCounting()) return t('picker.total_counting', { chats: n, videos: totalVideos() })
        return t('picker.total_videos', { chats: n, videos: totalVideos() })
    }

    // Leaving with ticks not saved asks first, whichever way it leaves (Back, the browser, the remote).
    let leaving = false
    let discard!: HTMLDialogElement
    let retryLeave: (() => void) | null = null
    useBeforeLeave((e) => {
        if (leaving || e.defaultPrevented || !changed(start, ticked())) return
        e.preventDefault()
        retryLeave = () => e.retry(true)
        discard.showModal()
    })

    const [saving, setSaving] = createSignal(false)
    const done = () => {
        if (saving()) return
        setSaving(true)
        const byId = new Map(list().map((c) => [c.id, chatName(c)]))
        const oldNames = new Map((stored() ?? []).map((a: AddedChat) => [a.chatId, a.title]))
        const chosen = [...ticked()].map((chatId) => ({ chatId, title: byId.get(chatId) ?? oldNames.get(chatId) ?? '' }))
        setAddedChats(chosen)
        leaving = true
        const next = setupNext()
        if (next) navigate(next, { replace: true })
        else back(navigate)
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
                <div class="picker-scopes" role="group" aria-label={t('browse.add_chats')}>
                    <For each={SCOPES}>
                        {(s) => (
                            <button class="history-tab" type="button" aria-pressed={scope() === s} onClick={() => setScope(s)}>
                                {t(SCOPE_LABEL[s])}
                            </button>
                        )}
                    </For>
                </div>
                <button class="link" type="button" onClick={() => (allOn() ? setTicked(clearVisible(ticked(), visible())) : selectAllNow())} disabled={!visible().length}>
                    {allOn() ? t('common.clear') : t('browse.select_all')}
                </button>
            </div>
            <Show when={failed()}>
                <div class="notice error-notice">
                    <p class="error">{t('chats.some_not_loaded')}</p>
                    <button class="link" type="button" onClick={retry}>{t('common.try_again')}</button>
                </div>
            </Show>
            <Show when={!loaded() && !failed() && !list().length}>
                <p class="muted">{t('picker.loading')}</p>
            </Show>
            <Show when={(loaded() || failed()) && !rows().length}>
                <p class="muted">{text().trim() ? t('chats.no_match', { query: text().trim() }) : t('picker.empty')}</p>
            </Show>
            <VirtualGrid items={items()} layout={() => ROW_LAYOUT} rowClass="chats" label={t('browse.add_chats')}>
                {(item) =>
                    'head' in item ? (
                        <h2 class="picker-band">{item.head}</h2>
                    ) : item.row.chat ? (
                        <PickRowView chat={item.row.chat} on={ticked().has(item.row.id)} onToggle={() => toggle(item.row.id)} count={countOf(item.row.id)} onWant={() => wantCount(item.row.id)} onRetry={() => retryCount(item.row.id)} />
                    ) : (
                        <div class="picker-gone-row">
                            <span class="chat-name">{item.row.title}</span>
                            <span class="muted small">{t('picker.gone_detail')}</span>
                            <Show when={ticked().has(item.row.id)}>
                                <button class="link" type="button" onClick={() => toggle(item.row.id)}>
                                    {t('picker.remove')}
                                </button>
                            </Show>
                        </div>
                    )
                }
            </VirtualGrid>
            <div class="select-bar">
                <span class="select-count" aria-live="polite">{footer()}</span>
                <button class="primary" type="button" disabled={saving()} onClick={done}>{t('common.done')}</button>
            </div>
            <dialog class="confirm" ref={confirmAll} aria-labelledby="select-all-title" onClose={() => setAsking(0)}>
                <h2 id="select-all-title">{t('picker.confirm_title', { count: asking() })}</h2>
                <p>{t('picker.confirm_body')}</p>
                <div class="confirm-buttons">
                    <button class="link" type="button" onClick={() => confirmAll.close()}>{t('common.cancel')}</button>
                    <button
                        class="primary"
                        type="button"
                        onClick={() => {
                            confirmAll.close()
                            setTicked(selectAll(ticked(), visible()))
                        }}
                    >
                        {t('browse.select_all')}
                    </button>
                </div>
            </dialog>
            <dialog class="confirm" ref={discard} aria-labelledby="discard-title" onClose={() => (retryLeave = null)}>
                <h2 id="discard-title">{t('picker.discard_title')}</h2>
                <p>{t('picker.discard_body')}</p>
                <div class="confirm-buttons">
                    <button class="link" type="button" onClick={() => discard.close()}>{t('common.cancel')}</button>
                    <button
                        class="primary danger"
                        type="button"
                        onClick={() => {
                            const go = retryLeave
                            leaving = true
                            discard.close()
                            go?.()
                        }}
                    >
                        {t('picker.discard_yes')}
                    </button>
                </div>
            </dialog>
        </>
    )
}

function PickRowView(props: { chat: ChatItem; on: boolean; onToggle: () => void; count: number | undefined; onWant: () => void; onRetry: () => void }) {
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
                <Show when={props.count !== undefined} fallback=" ">
                    {(props.count ?? 0) >= MANY ? t('picker.count_many') : t('picker.count_about', { count: props.count ?? 0 })}
                </Show>
            </Show>
        </span>
    )
}
