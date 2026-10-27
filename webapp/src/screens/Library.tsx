// The viewer's own lists behind the "You" menu: Favourites (the starred chats) and History, with
// the app's tabs (Recent, Continue, Watch later, Watched), each day under its own heading.
import { createEffect, createMemo, createResource, createSignal, For, Match, on, onCleanup, onMount, Show, Switch, type JSX } from 'solid-js'
import { A, useParams } from '@solidjs/router'
import { CheckmarkCircle02Icon, Clock01Icon, History, PlayIcon, StarIcon, Tick02Icon } from '@hugeicons/core-free-icons'
import { api, call } from '../tg/rpc'
import { t } from '../i18n'
import { laterIds, laterList, resumes, starredIds, watchedList, watchedTick } from '../store'
import { Icon, type IconData } from '../ui/Icon'
import { Card, ContinueCard, useLeft, useProgress, videoCard } from './VideoList'
import { ChatRow } from './Chats'
import { form } from '../form'
import { clearHistoryWithUndo } from '../clearHistory'
import { historyId, removeWithUndo } from '../historyEdits'
import { prune, range, toggleAll } from '../ui/selection'
import type { VideoItem } from '../tg/types'

/** How many videos a tab asks Telegram about at most: the ones further back wait for their own page. */
const ASK_AT_MOST = 60

/** Favourites: the starred chats, in the order they were starred. */
export function Favourites() {
    const [chats] = createResource(() => call('chats', [false]))
    const starred = () => (chats() ?? []).filter((c) => starredIds().has(c.id))
    return (
        <>
            <PageHead title={t('browse.tab_favourites_heading')} blurb={t('browse.tab_favourites_blurb')} />
            <Switch>
                <Match when={chats.loading && !starredIds().size}>
                    <p class="muted">{t('browse.loading_chats')}</p>
                </Match>
                <Match when={!starredIds().size || (chats() && !starred().length)}>
                    <Empty icon={StarIcon} text={t('browse.empty_favourites_touch')} />
                </Match>
                <Match when={chats()}>
                    <ul class="chats favourites-list">
                        <For each={starred()}>
                            {(c) => (
                                <li>
                                    <ChatRow chat={c} />
                                </li>
                            )}
                        </For>
                    </ul>
                </Match>
            </Switch>
        </>
    )
}

type Tab = 'recent' | 'continue' | 'later' | 'watched'
const TABS: { tab: Tab; href: string; icon: IconData; label: () => string; blurb: () => string; empty: () => string }[] = [
    { tab: 'recent', href: '/history', icon: History, label: () => t('browse.tab_history'), blurb: () => t('browse.tab_history_blurb'), empty: () => t('browse.empty_continue') },
    { tab: 'continue', href: '/history/continue', icon: PlayIcon, label: () => t('browse.tab_continue'), blurb: () => t('browse.tab_continue_blurb'), empty: () => t('browse.empty_continue') },
    { tab: 'later', href: '/history/later', icon: Clock01Icon, label: () => t('browse.tab_watch_later'), blurb: () => t('browse.tab_watch_later_blurb'), empty: () => t('browse.empty_watch_later') },
    { tab: 'watched', href: '/history/watched', icon: Tick02Icon, label: () => t('browse.tab_watched'), blurb: () => t('browse.tab_watched_blurb'), empty: () => t('browse.empty_watched') },
]

interface Entry {
    item: VideoItem
    time: number
}

/** Telegram's answer for each id, the ones gone from their chat left out. */
async function lookUp(list: { chatId: number; messageId: number; time: number }[]): Promise<Entry[]> {
    const found = await Promise.all(list.slice(0, ASK_AT_MOST).map(async (r) => ({ item: await api.video(r.chatId, r.messageId).catch(() => null), time: r.time })))
    return found.filter((e): e is Entry => !!e.item)
}

/** History, at `/history[/continue|/later|/watched]`. */
export function HistoryPage() {
    const params = useParams<{ tab?: string }>()
    const tab = (): Tab => (['continue', 'later', 'watched'].includes(params.tab ?? '') ? (params.tab as Tab) : 'recent')
    const meta = () => TABS.find((x) => x.tab === tab())!
    const [entries] = createResource(
        () => ({ tab: tab(), later: laterIds().size, watched: watchedTick() }),
        async ({ tab }): Promise<Entry[]> => {
            const left = async () => lookUp((await resumes()).map((r) => ({ chatId: r.chatId, messageId: r.messageId, time: r.time })))
            const done = async () => lookUp(await watchedList())
            if (tab === 'continue') return left()
            if (tab === 'watched') return done()
            if (tab === 'later') return (await laterList()).map((l) => ({ item: l.item, time: l.time }))
            const both = [...(await left()), ...(await done())]
            // A video can be in both lists (finished once, left part way since): one card, the newer.
            const seen = new Set<string>()
            return both.sort((a, b) => b.time - a.time).filter((e) => !seen.has(historyId(e.item)) && !!seen.add(historyId(e.item)))
        },
    )
    const progress = useProgress()
    const leftOf = useLeft()
    const days = () => byDay(entries.latest ?? [])

    // Select mode (not on Watch later, which keeps its own list): ticks, Select all, Remove (n).
    const [selecting, setSelecting] = createSignal(false)
    const [picked, setPicked] = createSignal<ReadonlySet<string>>(new Set())
    let anchor: string | null = null
    let confirmDialog!: HTMLDialogElement
    const order = createMemo(() => (entries.latest ?? []).map((e) => historyId(e.item)))
    const tv = () => form() === 'tv'
    // The selection follows what is on the page: an item that vanished (removed, undone away, a tab change) is dropped.
    createEffect(on(order, (ids) => setPicked((p) => (p.size ? prune(p, ids) : p))))
    createEffect(on(tab, () => leave(), { defer: true }))
    const leave = () => {
        setSelecting(false)
        setPicked(new Set<string>())
        anchor = null
    }
    const tick = (id: string, shift: boolean) => {
        const ids = shift ? range(order(), anchor, id) : [id]
        const on = !picked().has(id)
        setPicked((p) => {
            const next = new Set(p)
            for (const x of ids) (on ? next.add(x) : next.delete(x))
            return next
        })
        anchor = id
    }
    const doRemove = async () => {
        const ids = [...picked()]
        if (!ids.length) return
        leave()
        await removeWithUndo(ids, !tv())
    }
    // A TV remote cannot reach the toast's Undo, so it is asked first; the phone and the desktop remove at once.
    const remove = () => (tv() ? confirmDialog.showModal() : void doRemove())
    onMount(() => {
        const keys = (e: KeyboardEvent) => {
            if (!selecting() || form() !== 'desktop') return
            const inField = e.target instanceof HTMLElement && /^(INPUT|TEXTAREA|SELECT)$/.test(e.target.tagName) && (e.target as HTMLInputElement).type !== 'checkbox'
            if (inField) return
            if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'a') {
                e.preventDefault()
                setPicked(new Set(order()))
            } else if ((e.key === 'Delete' || e.key === 'Backspace') && picked().size) {
                e.preventDefault()
                remove()
            } else if (e.key === 'Escape' && !confirmDialog.open) leave()
        }
        addEventListener('keydown', keys)
        onCleanup(() => removeEventListener('keydown', keys))
    })
    return (
        <>
            <PageHead title={t('browse.tab_history_heading')} blurb={meta().blurb()} />
            {/* One click, no question: Continue watching and Watched are cleared and the toast offers Undo for 8 s. Not on Watch later, which it keeps. */}
            <Show when={tab() !== 'later' && days().length}>
                <div class="history-actions">
                    <button class="link" type="button" onClick={() => (selecting() ? leave() : setSelecting(true))}>
                        {selecting() ? t('common.cancel') : t('browse.select')}
                    </button>
                    <Show when={!selecting()}>
                        <button class="link" type="button" onClick={() => void clearHistoryWithUndo()}>
                            {t('browse.clear_history')}
                        </button>
                    </Show>
                </div>
            </Show>
            <nav class="history-tabs" aria-label={t('browse.tab_history')}>
                <For each={TABS}>
                    {(x) => (
                        <A class="history-tab" href={x.href} end aria-current={tab() === x.tab ? 'page' : undefined}>
                            <Icon icon={x.tab === 'watched' && tab() === 'watched' ? CheckmarkCircle02Icon : x.icon} size={16} />
                            {x.tab === 'recent' ? t('browse.sort_recent') : x.label()}
                        </A>
                    )}
                </For>
            </nav>
            <Switch>
                <Match when={entries.loading && !entries.latest}>
                    <p class="muted">{t('common.loading')}</p>
                </Match>
                <Match when={!days().length}>
                    <Empty icon={meta().icon} text={meta().empty()}>
                        <A class="outline-button" href="/chats">{t('browse.go_to_chats')}</A>
                    </Empty>
                </Match>
                <Match when={days().length}>
                    <For each={days()}>
                        {(d) => (
                            <section class="history-day">
                                <h2 class="history-day-title">{d.label}</h2>
                                <div class="cards" classList={{ 'continue-cards': tab() === 'continue' && form() !== 'tv' }}>
                                    <For each={d.entries}>
                                        {(e) => {
                                            const id = historyId(e.item)
                                            const card = (
                                                <Show when={tab() === 'continue' && form() !== 'tv'} fallback={<Card card={videoCard(e.item)} progress={progress(e.item)} direct={tab() === 'continue'} history={tab() !== 'later' && !tv()} />}>
                                                    <ContinueCard item={e.item} left={leftOf(e.item)} />
                                                </Show>
                                            )
                                            return (
                                                <Show when={selecting()} fallback={card}>
                                                    <label class="pick" classList={{ on: picked().has(id) }}>
                                                        <input type="checkbox" checked={picked().has(id)} onClick={(ev) => tick(id, ev.shiftKey)} onChange={(ev) => (ev.currentTarget.checked = picked().has(id))} aria-label={e.item.name} />
                                                        <span class="pick-box" aria-hidden="true"><Icon icon={Tick02Icon} size={16} /></span>
                                                        <div class="pick-card" inert>{card}</div>
                                                    </label>
                                                </Show>
                                            )
                                        }}
                                    </For>
                                </div>
                            </section>
                        )}
                    </For>
                </Match>
            </Switch>
            <Show when={selecting()}>
                <div class="select-bar" role="toolbar" aria-label={t('browse.select')}>
                    <span class="select-count" aria-live="polite">{t('browse.selected_count', { count: picked().size })}</span>
                    <button class="link" type="button" onClick={() => setPicked(toggleAll(picked(), order()))}>
                        {order().length > 0 && picked().size === order().length ? t('browse.select_none') : t('browse.select_all')}
                    </button>
                    <button class="primary danger" type="button" disabled={!picked().size} onClick={remove}>
                        {t('browse.remove_count', { count: picked().size })}
                    </button>
                </div>
            </Show>
            <dialog class="confirm" ref={confirmDialog} aria-labelledby="remove-title">
                <h2 id="remove-title">{t('browse.remove_selected_title', { count: picked().size })}</h2>
                <p>{t('browse.remove_selected_message', { count: picked().size })}</p>
                <p class="muted">{t('browse.remove_selected_detail')}</p>
                <div class="confirm-buttons">
                    <button class="link" type="button" onClick={() => confirmDialog.close()}>{t('common.cancel')}</button>
                    <button
                        class="primary danger"
                        type="button"
                        onClick={() => {
                            confirmDialog.close()
                            void doRemove()
                        }}
                    >
                        {t('browse.remove_count', { count: picked().size })}
                    </button>
                </div>
            </dialog>
        </>
    )
}

/** The entries under Today, Yesterday and then their dates, as the app groups History. */
function byDay(entries: Entry[]): { label: string; entries: Entry[] }[] {
    const out: { label: string; entries: Entry[] }[] = []
    const today = new Date()
    today.setHours(0, 0, 0, 0)
    for (const e of entries) {
        const day = new Date(e.time)
        day.setHours(0, 0, 0, 0)
        const ago = Math.round((today.getTime() - day.getTime()) / 86_400_000)
        const label = !e.time ? '' : ago <= 0 ? t('watched.today') : ago === 1 ? t('watched.yesterday') : day.toLocaleDateString('en', { day: 'numeric', month: 'long' })
        const last = out[out.length - 1]
        if (last && last.label === label) last.entries.push(e)
        else out.push({ label, entries: [e] })
    }
    return out
}

function PageHead(props: { title: string; blurb: string }) {
    return (
        <header class="library-head">
            <h1 class="page-title">{props.title}</h1>
            <p class="muted">{props.blurb}</p>
        </header>
    )
}

/** A list with nothing in it: its icon on a disc, a line, and the way on. */
function Empty(props: { icon: IconData; text: string; children?: JSX.Element }) {
    return (
        <div class="empty-state">
            <span class="empty-disc">
                <Icon icon={props.icon} size={28} />
            </span>
            <p>{props.text}</p>
            <Show when={props.children}>{props.children}</Show>
        </div>
    )
}
