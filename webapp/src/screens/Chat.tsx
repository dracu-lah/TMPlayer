import { createEffect, createMemo, createResource, createSignal, For, on, onCleanup, Show } from 'solid-js'
import { A, useParams, useSearchParams } from '@solidjs/router'
import { Cancel01Icon, InformationCircleIcon, RefreshIcon, Search01Icon, Sorting01Icon, StarIcon, Tick02Icon } from '@hugeicons/core-free-icons'
import { api, call, errorText } from '../tg/rpc'
import { onBackOnline } from '../net'
import { t } from '../i18n'
import { progress as readProgress, settings, setSetting, setStarred, starredIds } from '../store'
import { form } from '../form'
import { Icon } from '../ui/Icon'
import { PageBar } from '../ui/PageBar'
import { notePool } from '../ui/PosterPicker'
import { ChatPicture } from './ChatPicture'
import { chatName, StarButton } from './Chats'
import { sizeLimits } from './sizes'
import { stableCards, tileCard, VideoList, type CardData } from './VideoList'
import type { ChatPage, ChatView, RpcError, Sort, Tile } from '../tg/types'

const SORTS: { value: Sort; key: Parameters<typeof t>[0] }[] = [
    { value: 'Newest', key: 'grid.sort_newest' },
    { value: 'Oldest', key: 'grid.sort_oldest' },
    { value: 'Name', key: 'grid.sort_name' },
    { value: 'Year', key: 'grid.sort_year' },
    { value: 'Size', key: 'grid.sort_size' },
    { value: 'Longest', key: 'grid.sort_longest' },
    { value: 'Unwatched', key: 'grid.sort_unwatched' },
]

/** Pages in a row that brought no new card before the list stops paging by itself and offers a button. */
const IDLE_PAGES = 3

/**
 * One chat's videos as the app arranges them: shows, copies and pieces folded together by its
 * rules, in the chosen order, every video unless "Hide small clips" is on, which ends the grid
 * with "N small videos hidden" and Show them. More loads near the foot; the words to look for are in the address (`?q=`).
 */
export function Chat() {
    const params = useParams<{ chatId: string }>()
    const [search, setSearch] = useSearchParams<{ q?: string }>()
    const chatId = () => Number(params.chatId)
    const [page, setPage] = createSignal<ChatPage | null>(null)
    const [error, setError] = createSignal<unknown>(null)
    const [busy, setBusy] = createSignal<'open' | 'more' | 'refresh' | 'view' | null>(null)
    const [moreFailed, setMoreFailed] = createSignal(false)
    const [refreshWait, setRefreshWait] = createSignal<number | null>(null)
    const [idlePages, setIdlePages] = createSignal(0)
    const [searching, setSearching] = createSignal(!!search.q)
    const [progress] = createResource(readProgress)
    // "Show them" on the end row lists the small videos for this visit; the saved switch is not changed (R1).
    const [showAll, setShowAll] = createSignal(false)
    const view = (): ChatView => ({
        sort: settings().sort,
        minBytes: showAll() ? 0 : settings().sizeMin,
        maxBytes: settings().sizeMax,
        watched: progress()?.watched ?? [],
        started: progress()?.started ?? [],
        query: search.q?.trim() ?? '',
    })

    let seq = 0
    const run = async (mode: 'open' | 'more' | 'refresh' | 'view') => {
        const mine = ++seq
        const id = chatId()
        const before = page()?.tiles.length ?? 0
        setBusy(mode)
        if (mode === 'more') setMoreFailed(false)
        try {
            const next = await call('chat', [id, view(), mode])
            if (mine !== seq || id !== chatId()) return
            setPage(next)
            setError(null)
            if (mode === 'more') setIdlePages(next.tiles.length > before ? 0 : idlePages() + 1)
            else if (mode !== 'view') setIdlePages(0)
        } catch (e) {
            if (mine !== seq) return
            const err = e as Partial<RpcError>
            if (mode === 'more') setMoreFailed(true)
            else if (mode === 'refresh' && err.text?.startsWith('FLOOD_WAIT') && page()) setRefreshWait(err.seconds ?? 0)
            else setError(e)
        } finally {
            if (mine === seq) setBusy(null)
        }
    }

    // A new chat (the address changed under the same page) starts afresh.
    createEffect(
        on(chatId, () => {
            setPage(null)
            setError(null)
            setShowAll(false)
            setIdlePages(0)
            setMoreFailed(false)
            run('open')
        }),
    )
    // A page lost with the connection loads once it is back.
    onBackOnline(() => {
        if (busy()) return
        if (error() && !page()) run('open')
        else if (moreFailed()) run('more')
    })
    // Another order, size limits or words: the same videos arranged again, in the worker.
    createEffect(on(() => JSON.stringify(view()), () => page() && run('view'), { defer: true }))

    // Scrolling to the foot always asks for the next page (infinite scroll, no More button).
    const atEnd = (auto = false) => {
        const p = page()
        if (!p || p.done || busy() || moreFailed() || (auto && idlePages() >= IDLE_PAGES)) return
        run('more')
    }

    let waitTimer = 0
    createEffect(() => {
        const w = refreshWait()
        clearTimeout(waitTimer)
        if (w != null) waitTimer = window.setTimeout(() => setRefreshWait(null), 4000)
    })
    onCleanup(() => clearTimeout(waitTimer))

    const title = () => (page() ? chatName(page()!) : '')
    const toCards = stableCards<Tile, CardData>((tile) => tileCard(tile))
    const cards = createMemo(() => toCards(page()?.tiles ?? []))
    // The videos on screen, so "Choose the right poster" can offer a pick to the others of its title.
    createEffect(() => {
        const p = page()
        if (p && !view().query) notePool(chatId(), p.tiles.map((tile) => tile.item))
    })
    const empty = () => {
        const p = page()
        if (!p || p.tiles.length) return null
        // Words: Telegram's search of the chat said nothing yet; Keep looking reads its pages on.
        if (view().query) return busy() ? t('browse.searching') : t('browse.no_match', { query: view().query })
        if (!p.done) return null
        // Videos left out by the floor are said by the end row, with the way to show them.
        if (p.hiddenBySize) return null
        return t('browse.no_playable_videos')
    }

    let debounce = 0
    let typing = false
    const [text, setText] = createSignal(search.q ?? '')
    onCleanup(() => clearTimeout(debounce))
    const onType = (v: string) => {
        setText(v)
        typing = true
        clearTimeout(debounce)
        debounce = window.setTimeout(() => {
            typing = false
            setSearch({ q: v || undefined }, { replace: true })
        }, 200)
    }
    createEffect(on(() => search.q, (q) => !typing && setText(q ?? ''), { defer: true }))
    const closeSearch = () => {
        setSearching(false)
        setText('')
        setSearch({ q: undefined }, { replace: true })
    }

    // "Hide small clips" (the Sort menu's switch) on: the saved floor is the clip floor, and "Show them" is not in force.
    const hidesSmall = () => !showAll() && sizeLimits() !== undefined && settings().sizeMin === sizeLimits()!.clipMin
    const setHideSmall = (on: boolean) => {
        const clip = sizeLimits()?.clipMin
        if (clip === undefined) return
        if (on && showAll() && settings().sizeMin === clip) setShowAll(false)
        else {
            setShowAll(false)
            setSetting('sizeMin', on ? clip : 0)
        }
    }
    // A floor chosen before the switch (or a ceiling), kept working: one line in the menu with a Reset.
    const custom = () => {
        const l = sizeLimits()
        if (!l) return false
        const min = settings().sizeMin
        return (min > 0 && min !== l.clipMin) || settings().sizeMax < l.ceiling
    }
    const [range] = createResource(
        () => (custom() ? ([settings().sizeMin, settings().sizeMax] as const) : false),
        ([min, max]) => api.sizeRange(min, max),
    )
    const resetLimits = () => {
        setShowAll(false)
        setSetting('sizeMin', 0)
        setSetting('sizeMax', sizeLimits()?.ceiling ?? settings().sizeMax)
    }
    const sortMenu = (cell?: boolean) => (
        <SortMenu
            cell={cell}
            hideSmall={hidesSmall()}
            clipLabel={sizeLimits()?.clipLabel}
            onHideSmall={sizeLimits() ? setHideSmall : undefined}
            customRange={custom() ? range.latest : undefined}
            onReset={resetLimits}
        />
    )
    const SearchCell = () => (
        <button type="button" class="nav-cell" aria-pressed={searching()} title={t('browse.search_chat')} onClick={() => (searching() ? closeSearch() : setSearching(true))}>
            <span class="nav-glyph"><Icon icon={searching() ? Cancel01Icon : Search01Icon} size={22} /></span>
            <span class="nav-label">{t('common.search')}</span>
        </button>
    )
    const StarCell = () => (
        <button
            type="button"
            class="nav-cell star"
            classList={{ on: starredIds().has(chatId()) }}
            aria-pressed={starredIds().has(chatId())}
            title={starredIds().has(chatId()) ? t('chats.unfavourite') : t('chats.favourite')}
            onClick={() => setStarred(chatId(), !starredIds().has(chatId()))}
        >
            <span class="nav-glyph"><Icon icon={StarIcon} size={22} selected={starredIds().has(chatId())} /></span>
            <span class="nav-label">{t('browse.favourite')}</span>
        </button>
    )
    return (
        <>
            <PageBar
                title={title()}
                picture={<ChatPicture photo={page()?.photo ?? null} name={title()} self={page()?.self} class="avatar compact" />}
                actions={
                    <Show
                        when={form() !== 'tv'}
                        fallback={
                            // The TV keeps the chat's buttons at the top; a phone and a desktop have them on the chat's own bar at the foot.
                            <>
                                <StarButton chatId={chatId()} round />
                                {sortMenu()}
                                <button
                                    class="round-button"
                                    type="button"
                                    aria-pressed={searching()}
                                    aria-label={t('browse.search_chat')}
                                    title={t('browse.search_chat')}
                                    onClick={() => (searching() ? closeSearch() : setSearching(true))}
                                >
                                    <Icon icon={searching() ? Cancel01Icon : Search01Icon} size={22} />
                                </button>
                                <button
                                    class="round-button"
                                    classList={{ spinning: busy() === 'refresh' }}
                                    type="button"
                                    disabled={busy() === 'refresh' || busy() === 'open'}
                                    aria-label={t('common.refresh')}
                                    title={t('common.refresh')}
                                    onClick={() => run('refresh')}
                                >
                                    <Icon icon={RefreshIcon} size={22} />
                                </button>
                            </>
                        }
                    >
                        {/* The chat's info beside its name (the apps' Info button), then Refresh, which the apps have as a pull or a key. */}
                        <A class="round-button" href={`/chat/${chatId()}/info`} state={{ chat: page() ? { title: title(), photo: page()!.photo, self: page()!.self } : undefined }} aria-label={t('chats.info_about')} title={t('chats.info_about')}>
                            <Icon icon={InformationCircleIcon} size={22} />
                        </A>
                        <button
                            class="round-button"
                            classList={{ spinning: busy() === 'refresh' }}
                            type="button"
                            disabled={busy() === 'refresh' || busy() === 'open'}
                            aria-label={t('common.refresh')}
                            title={t('common.refresh')}
                            onClick={() => run('refresh')}
                        >
                            <Icon icon={RefreshIcon} size={22} />
                        </button>
                    </Show>
                }
            />
            <Show when={form() !== 'tv'}>
                {/* The chat's bar (ChatBar): in place of the main bar while a chat is open, as the apps have it. */}
                <nav class="nav chat-bar" aria-label={title()}>
                    <div class="nav-cells">
                        {/* The order of each app's bar: a phone Favourite, Search, Sort (Sort last, where the thumb reaches first); the desktop Search, Favourite, Sort. */}
                        <Show when={form() === 'desktop'} fallback={<StarCell />}>
                            <SearchCell />
                        </Show>
                        <Show when={form() === 'desktop'} fallback={<SearchCell />}>
                            <StarCell />
                        </Show>
                        {sortMenu(true)}
                    </div>
                </nav>
            </Show>
            <Show when={searching()}>
                <label class="search-field">
                    <Icon icon={Search01Icon} size={20} />
                    <input
                        type="search"
                        placeholder={t('browse.search_chat')}
                        aria-label={t('browse.search_chat')}
                        value={text()}
                        onInput={(e) => onType(e.currentTarget.value)}
                        onKeyDown={(e) => e.key === 'Escape' && closeSearch()}
                        ref={(el) => queueMicrotask(() => !search.q && el.focus())}
                    />
                </label>
            </Show>
            <Show when={refreshWait() != null}>
                <p class="notice" role="status">{t('chats.refresh_wait', { seconds: refreshWait()! })}</p>
            </Show>
            <Show when={busy() === 'open' && !page()}>
                <p class="muted">{t('browse.finding_videos')}</p>
            </Show>
            <Show when={error()}>
                <div class="notice error-notice">
                    <p class="error" role="alert">{errorText(error())}</p>
                    <button class="link" type="button" onClick={() => run(page() ? 'refresh' : 'open')}>{t('common.try_again')}</button>
                </div>
            </Show>
            <Show when={page()?.protected}>
                <p class="notice muted small">{t('detail.protected')}</p>
            </Show>
            <Show when={page()?.selfDestructing}>
                <p class="notice muted small">{t('grid.self_destructing_hidden', { count: page()!.selfDestructing })}</p>
            </Show>
            <Show when={empty()}>
                <p class="muted">{empty()}</p>
            </Show>
            <VideoList cards={cards()} label={title()} onEnd={atEnd} sponsored={page()?.sponsored} sponsoredBetween={page()?.sponsoredBetween} />
            <div class="list-foot">
                <Show when={busy() === 'more'}>
                    <p class="muted">{t('grid.loading_more')}</p>
                </Show>
                <Show when={moreFailed()}>
                    <p class="error" role="alert">{t('grid.more_failed')}</p>
                    <button class="link" type="button" onClick={() => run('more')}>{t('common.try_again')}</button>
                </Show>
                {/* A search that has not read the whole chat: the rest on a press, as the apps' Keep looking. */}
                <Show when={view().query && page() && !page()!.done && !busy() && !moreFailed() && (idlePages() >= IDLE_PAGES || !page()!.tiles.length)}>
                    <button
                        class="link"
                        type="button"
                        onClick={() => {
                            setIdlePages(0)
                            run('more')
                        }}
                    >
                        {t('common.keep_looking')}
                    </button>
                </Show>
            </div>
            {/* Small videos left out by "Hide small clips": said where the chat ends, with Show them. The number only once the whole chat has been read. */}
            <Show when={!view().query && !searching() && (page()?.hiddenBySize ?? 0) > 0}>
                <div class="small-hidden">
                    <p class="muted">
                        {page()!.done ? t('grid.small_hidden', { count: page()!.hiddenBySize }) : t('grid.small_hidden_some')}
                    </p>
                    <button class="outline-button" type="button" onClick={() => setShowAll(true)}>{t('grid.filter_show_them')}</button>
                </div>
            </Show>
            <Show when={page()?.archives.length}>
                <details class="archives">
                    <summary>{t('grid.archives_heading', { count: page()!.archives.length })}</summary>
                    <ul>
                        <For each={page()!.archives}>{(name) => <li>{name}</li>}</For>
                    </ul>
                </details>
            </Show>
        </>
    )
}

/** Sort videos by: the app's seven orders in one list, the chosen one ticked. Kept for every chat. */
function SortMenu(props: {
    cell?: boolean
    /** The "Hide small clips" switch: on or off now, its words, and what turning it does; undefined until the limits are known. */
    hideSmall: boolean
    clipLabel?: string
    onHideSmall?: (on: boolean) => void
    /** A floor chosen before the switch, as a sentence, with a Reset. */
    customRange?: string
    onReset: () => void
}) {
    const [open, setOpen] = createSignal(false)
    let box!: HTMLDivElement
    const close = (e: Event) => {
        if (e instanceof KeyboardEvent ? e.key === 'Escape' : !box.contains(e.target as Node)) setOpen(false)
    }
    createEffect(() => {
        if (!open()) return
        document.addEventListener('pointerdown', close)
        document.addEventListener('keydown', close)
        onCleanup(() => {
            document.removeEventListener('pointerdown', close)
            document.removeEventListener('keydown', close)
        })
    })
    return (
        <div class="menu-box" ref={box}>
            <button
                class={props.cell ? 'nav-cell' : 'round-button'}
                type="button"
                aria-haspopup="true"
                aria-expanded={open()}
                aria-label={t('grid.sort_label')}
                title={t('grid.sort_label')}
                onClick={() => setOpen(!open())}
            >
                <Show when={props.cell} fallback={<Icon icon={Sorting01Icon} size={22} />}>
                    <span class="nav-glyph"><Icon icon={Sorting01Icon} size={22} /></span>
                    <span class="nav-label">{t('grid.sort')}</span>
                </Show>
            </button>
            <Show when={open()}>
                <div class="pop-menu" role="menu" aria-label={t('grid.sort_label')}>
                    <p class="pop-title">{t('grid.sort_label')}</p>
                    <For each={SORTS}>
                        {(s) => (
                            <button
                                type="button"
                                role="menuitemradio"
                                aria-checked={settings().sort === s.value}
                                classList={{ on: settings().sort === s.value }}
                                onClick={() => {
                                    setSetting('sort', s.value)
                                    setOpen(false)
                                }}
                            >
                                <span>{t(s.key)}</span>
                                <Show when={settings().sort === s.value}>
                                    <Icon icon={Tick02Icon} size={20} />
                                </Show>
                            </button>
                        )}
                    </For>
                    <Show when={props.onHideSmall}>
                        <p class="pop-title">{t('grid.filter_size_label')}</p>
                        {/* Stays open when pressed, so the grid is seen to change behind it. */}
                        <button
                            type="button"
                            role="menuitemcheckbox"
                            aria-checked={props.hideSmall}
                            classList={{ on: props.hideSmall }}
                            onClick={() => props.onHideSmall!(!props.hideSmall)}
                        >
                            <span>{t('grid.hide_small_clips', { size: props.clipLabel ?? '' })}</span>
                            <Show when={props.hideSmall}>
                                <Icon icon={Tick02Icon} size={20} />
                            </Show>
                        </button>
                        <Show when={props.customRange}>
                            <button type="button" role="menuitem" class="on" onClick={() => { props.onReset(); setOpen(false) }}>
                                <span>{t('grid.size_limit_on', { range: props.customRange! })}</span>
                            </button>
                        </Show>
                    </Show>
                </div>
            </Show>
        </div>
    )
}
