import { createEffect, createMemo, createResource, createSignal, For, on, onCleanup, Show } from 'solid-js'
import { A, useNavigate, useParams, useSearchParams } from '@solidjs/router'
import { Cancel01Icon, InformationCircleIcon, RefreshIcon, Search01Icon, StarIcon } from '@hugeicons/core-free-icons'
import { api, call, errorText } from '../tg/rpc'
import { onBackOnline } from '../net'
import { t } from '../i18n'
import { chatPref, progress as readProgress, setChatPref, settings, setSetting, setStarred, starredIds } from '../store'
import { copiesTick } from '../copies'
import { form } from '../form'
import { Icon } from '../ui/Icon'
import { OptionsButton } from '../ui/CardMenu'
import { PageBar } from '../ui/PageBar'
import { notePool } from '../ui/PosterPicker'
import { showToast } from '../ui/Toast'
import { createLatest, isAbort } from '../ui/loadStates'
import { idle, type WorkStatus } from '../ui/workStatus'
import { WorkStatusLine } from '../ui/WorkStatus'
import { AUTO_PAGES, AUTO_TOTAL, cleanFacets, filterLabel, filterState, filtersOn, NO_FACETS, NO_PREF, randomScope, wantsMore, type ChatFilter, type ChatPref, type Facets } from '../ui/videoFilter'
import { ChatPicture } from './ChatPicture'
import { chatName, StarButton } from './Chats'
import { sizeLimits } from './sizes'
import { playRandomOf } from './randomPlay'
import { startChatRun } from './playAllRun'
import { ensureTouches } from './touches'
import { SortMenu } from './SortMenu'
import { stableCards, tileCard, VideoList, watchPath, type CardData } from './VideoList'
import './topics.css'
import { setPageName } from '../ui/pageTitle'
import type { ChatPage, ChatView, RpcError, Tile } from '../tg/types'

/** Pages in a row that brought no new card before the list stops paging by itself and offers a button. */
const IDLE_PAGES = 3

/**
 * One chat's videos as the app arranges them: shows, copies and pieces folded together by its
 * rules, in the chosen order, every video unless "Hide small clips" is on, which ends the grid
 * with "N small videos hidden" and Show them. More loads near the foot; the words to look for are in the address (`?q=`).
 */
export function Chat() {
    const params = useParams<{ chatId: string }>()
    const [search, setSearch] = useSearchParams<{ q?: string; topic?: string }>()
    const chatId = () => Number(params.chatId)
    // Opened on one topic of a forum chat (a "Chat > Topic" row of Search, `?topic=`): only that topic's videos, read through the topic itself.
    const topic = () => {
        const n = Number(search.topic)
        return Number.isSafeInteger(n) && n > 0 ? n : 0
    }
    const [page, setPage] = createSignal<ChatPage | null>(null)
    const [error, setError] = createSignal<unknown>(null)
    const [busy, setBusy] = createSignal<'open' | 'more' | 'refresh' | 'view' | null>(null)
    const [moreFailed, setMoreFailed] = createSignal(false)
    const [refreshWait, setRefreshWait] = createSignal<number | null>(null)
    const [idlePages, setIdlePages] = createSignal(0)
    const [searching, setSearching] = createSignal(!!search.q)
    const navigate = useNavigate()
    const [progress] = createResource(readProgress)
    // "Show them" on the end row lists the small videos for this visit; the saved switch is not changed (R1).
    const [showAll, setShowAll] = createSignal(false)
    // The chat's filters (U6), kept for this chat; "Show them" on the filter note lists every video for this visit, Filter again puts them back.
    const [pref, setPref] = createSignal<ChatPref>(NO_PREF)
    const [filterPaused, setFilterPaused] = createSignal(false)
    // Pages the grid asked for by itself while a filter left it short; Keep looking asks again.
    const [autoPages, setAutoPages] = createSignal(0)
    const [facets] = createResource(() => pref().facets, (line) => (line ? api.facets(line) : Promise.resolve(NO_FACETS)))
    const facetsNow = (): Facets => (pref().facets ? (facets.latest ?? NO_FACETS) : NO_FACETS)
    // Pages asked for by itself in this whole visit, however the filters change: a total budget (AUTO_TOTAL), then only Keep looking reads on.
    const [autoTotal, setAutoTotal] = createSignal(0)
    // The Last played order's times: 0 while they are being sent to the worker, -1 where that failed (the order then has none), else their version.
    const lastPlayedSort = () => settings().sort === 'LastPlayed'
    const [touchVer, setTouchVer] = createSignal(0)
    const sendTouches = () => ensureTouches().then(setTouchVer, () => setTouchVer(-1))
    // Choosing the order sends the times once; until they are there the grid keeps the order it had (no flash of the wrong one).
    createEffect(on(lastPlayedSort, (now) => now && (setTouchVer(0), void sendTouches())))
    const view = (): ChatView => ({
        sort: settings().sort,
        minBytes: showAll() ? 0 : settings().sizeMin,
        maxBytes: settings().sizeMax,
        watched: progress()?.watched ?? [],
        started: progress()?.started ?? [],
        query: search.q?.trim() ?? '',
        ...(topic() ? { topic: topic() } : {}),
        filter: filterPaused() ? 'All' : pref().filter,
        facets: filterPaused() ? '' : pref().facets,
        ...(lastPlayedSort() && touchVer() > 0 ? { touches: touchVer() } : {}),
    })

    // One read at a time (LS-28): a new read, another chat or another topic aborts the one running, and the worker is told.
    const latest = createLatest()
    onCleanup(() => latest.cancel())
    const run = async (mode: 'open' | 'more' | 'refresh' | 'view') => {
        const read = latest.begin()
        const id = chatId()
        const before = page()?.tiles.length ?? 0
        setBusy(mode)
        if (mode === 'more') setMoreFailed(false)
        try {
            const next = await call('chat', [id, view(), mode], { signal: read.signal })
            if (!read.current() || id !== chatId()) return
            setPage(next)
            setError(null)
            if (mode === 'more') setIdlePages(next.tiles.length > before ? 0 : idlePages() + 1)
            else if (mode !== 'view') setIdlePages(0)
        } catch (e) {
            if (!read.current() || isAbort(e)) return
            const err = e as Partial<RpcError>
            if (mode === 'more') setMoreFailed(true)
            else if (mode === 'refresh' && err.text?.startsWith('FLOOD_WAIT') && page()) setRefreshWait(err.seconds ?? 0)
            else setError(e)
        } finally {
            if (read.current()) setBusy(null)
        }
    }

    const headStatus = (): WorkStatus => (busy() === 'open' && !page() ? { kind: 'loading', what: t('browse.finding_videos') } : busy() === 'refresh' ? { kind: 'syncing', what: '' } : idle)
    const footStatus = (): WorkStatus => (busy() === 'more' ? { kind: 'syncing', what: t('grid.loading_more') } : idle)

    // A new chat (the address changed under the same page) starts afresh.
    createEffect(
        on(chatId, (id) => {
            // The old chat's read stops now, and the loading line is up while its filters are read (no blank wait on a change of chat).
            latest.cancel()
            setBusy('open')
            setPage(null)
            setError(null)
            setShowAll(false)
            setFilterPaused(false)
            setAutoPages(0)
            setAutoTotal(0)
            setIdlePages(0)
            setMoreFailed(false)
            // The chat's own filters are read first, so its first page is already screened (no flash of every video).
            setPref(NO_PREF)
            void chatPref(id)
                .catch(() => NO_PREF)
                .then(async (p) => {
                    if (id !== chatId()) return
                    setPref(p)
                    // Last played: the first page waits for the times, so it is drawn once, in its order.
                    if (lastPlayedSort()) await sendTouches()
                    if (id !== chatId()) return
                    run('open')
                })
        }),
    )
    // Another topic, or every topic again: that grid is read afresh (the old one is not shown under the new name).
    createEffect(
        on(topic, () => {
            latest.cancel()
            setPage(null)
            setError(null)
            setAutoPages(0)
            setIdlePages(0)
            setMoreFailed(false)
            run('open')
        }, { defer: true }),
    )
    // A page lost with the connection loads once it is back.
    onBackOnline(() => {
        if (busy()) return
        if (error() && !page()) run('open')
        else if (moreFailed()) run('more')
    })
    // Another order, size limits or words, or a file kept apart or a copy chosen (HX38): the same videos arranged again, in the worker.
    // Not while the Last played times are on their way: the grid keeps its order until they are there.
    createEffect(on(() => [JSON.stringify(view()), copiesTick()], () => page() && !(lastPlayedSort() && touchVer() === 0) && run('view'), { defer: true }))

    // Scrolling to the foot always asks for the next page (infinite scroll, no More button).
    const atEnd = (auto = false) => {
        const p = page()
        if (!p || p.done || busy() || moreFailed() || (auto && idlePages() >= IDLE_PAGES)) return
        run('more')
    }

    // The chat's filters (U6). Each pick is kept for the chat at once; the grid is arranged again by the view effect above.
    const changePref = (next: ChatPref) => {
        setPref(next)
        setFilterPaused(false)
        setAutoPages(0)
        setIdlePages(0)
        setChatPref(chatId(), next)
    }
    const pickFilter = (f: ChatFilter) => changePref({ ...pref(), filter: f })
    // One change at a time, each read from the line the one before left: two quick presses never overwrite each other.
    let facetQueue: Promise<void> = Promise.resolve()
    const pickFacet = (field: 'length' | 'picture' | 'added' | 'hdr' | 'subtitles' | 'language', value: string | null) => {
        const id = chatId()
        facetQueue = facetQueue
            .then(async () => {
                const line = cleanFacets(await api.facetsWith(pref().facets, field, value))
                if (id === chatId()) changePref({ ...pref(), facets: line })
            })
            .catch(() => {})
    }
    const clearFilter = () => changePref(NO_PREF)
    const filterCount = () => filtersOn(pref(), facetsNow().count)
    const state = () =>
        filterState({
            on: filterCount(),
            query: !!view().query,
            showAll: filterPaused(),
            shown: page()?.tiles.length ?? 0,
            total: page()?.total ?? 0,
            hidden: page()?.hiddenByFilter ?? 0,
        })
    // A filter that leaves the grid too short to scroll reads a few more pages by itself, then offers Keep looking (20,000 videos are never read for one filter).
    createEffect(() => {
        const p = page()
        if (!p) return
        if (wantsMore({ state: state(), shown: p.tiles.length, done: p.done, busy: !!busy(), failed: moreFailed(), auto: autoPages(), total: autoTotal() })) {
            setAutoPages(autoPages() + 1)
            setAutoTotal(autoTotal() + 1)
            run('more')
        }
    })
    const keepLookingFilter = () => (state() === 'hides-all' || state() === 'hides-some') && !!page() && !page()!.done && !busy() && !moreFailed() && (autoPages() >= AUTO_PAGES || autoTotal() >= AUTO_TOTAL)
    // The visit's budget of pages read by itself is spent: said, with Keep looking as the way on (one page a press).
    const budgetSpent = () => keepLookingFilter() && autoTotal() >= AUTO_TOTAL
    const keepLooking = () => {
        setIdlePages(0)
        run('more')
    }
    const why = () =>
        filterLabel(pref(), facetsNow(), (key) => t(key as Parameters<typeof t>[0])) ?? ''
    // Play random (U16): one of the videos on screen, unwatched first, never the one this chat played last. Nothing more is loaded for it.
    const playRandom = async () => {
        const shown = page()?.tiles ?? []
        if (!shown.length) return
        try {
            await playRandomOf({
                scope: randomScope('chat', chatId()),
                items: shown.map((tile) => tile.item),
                watched: progress()?.watched ?? [],
                open: (path, item) => navigate(path, { state: { video: item } }),
            })
        } catch (e) {
            showToast({ kind: 'error', text: errorText(e) })
        }
    }

    // Play all (WP-32): the first video listed, in the order the grid is sorted in; the chat's order follows a page at a time (Watch.tsx).
    const playAll = () => {
        const first = page()?.tiles[0]?.item
        if (!first) return
        startChatRun(first.chatId)
        navigate(watchPath(first), { state: { video: first } })
    }

    let waitTimer = 0
    createEffect(() => {
        const w = refreshWait()
        clearTimeout(waitTimer)
        if (w != null) waitTimer = window.setTimeout(() => setRefreshWait(null), 4000)
    })
    onCleanup(() => clearTimeout(waitTimer))

    const title = () => (page() ? chatName(page()!) : '')
    // The tab's title names the chat while it is open (ui/pageTitle.ts).
    createEffect(() => setPageName(title()))
    onCleanup(() => setPageName(null))
    const toCards = stableCards<Tile, CardData>((tile) => tileCard(tile))
    const cards = createMemo(() => toCards(page()?.tiles ?? []))
    // The videos on screen, so "Choose the right poster" can offer a pick to the others of its title.
    createEffect(() => {
        const p = page()
        if (p && !view().query && !topic()) notePool(chatId(), p.tiles.map((tile) => tile.item))
    })
    const empty = () => {
        const p = page()
        if (!p || p.tiles.length) return null
        // Every loaded video screened out by the filters has its own empty state, with Clear filter.
        if (state() === 'hides-all') return null
        // Words: Telegram's search of the chat said nothing yet; Keep looking reads its pages on.
        if (view().query) return busy() ? t('browse.searching') : topic() ? t('grid.topic_no_match', { topic: topicLabel(), query: view().query }) : t('browse.no_match', { query: view().query })
        if (!p.done) return null
        // Videos left out by the floor are said by the end row, with the way to show them.
        if (p.hiddenBySize) return null
        return topic() ? t('grid.topic_empty', { topic: topicLabel() }) : t('browse.no_playable_videos')
    }
    const topicLabel = () => page()?.topic?.name?.trim() || t('grid.topic_untitled')
    const allTopics = () => setSearch({ topic: undefined }, { replace: true })

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
            filter={pref().filter}
            facets={facetsNow()}
            languages={page()?.languages ?? []}
            filtersOn={filterCount()}
            onFilter={pickFilter}
            onFacet={pickFacet}
            onClear={clearFilter}
            onPlayAll={page()?.tiles.length ? playAll : undefined}
            onRandom={page()?.tiles.length ? () => void playRandom() : undefined}
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
                                {/* The options of the card the remote was on last, for a remote with no Menu key (WP-14). */}
                                <OptionsButton />
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
            <Show when={topic()}>
                <div class="topic-chip-row">
                    <span class="topic-chip" role="status">{page() ? topicLabel() : t('browse.finding_videos')}</span>
                    <button class="link" type="button" onClick={allTopics}>{t('grid.topics_all')}</button>
                </div>
            </Show>
            <Show when={refreshWait() != null}>
                <p class="notice" role="status">{t('chats.refresh_wait', { seconds: refreshWait()! })}</p>
            </Show>
            {/* The first read of a chat or topic, and Refresh: one status line, a polite status (LS-28). */}
            <WorkStatusLine status={headStatus()} />
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
            {/* The chat's filters in force (U6): what they hide, with Show them, Keep looking and Clear filter; every video hidden gets the empty state in the grid's place. */}
            <Show when={state() === 'shown-all' || state() === 'hides-some' || ((page()?.unknown ?? 0) > 0 && state() === 'on')}>
                <div class="filter-note" role="status">
                    <p class="muted">
                        {state() === 'shown-all'
                            ? t('grid.filter_paused')
                            : [
                                  (page()?.hiddenByFilter ?? 0) > 0 ? t('grid.filter_hidden', { count: page()!.hiddenByFilter }) : '',
                                  (page()?.unknown ?? 0) > 0 ? t('grid.filter_unknown', { count: page()!.unknown }) : '',
                              ]
                                  .filter(Boolean)
                                  .join('. ')}
                    </p>
                    <Show when={state() === 'shown-all'}>
                        <button class="outline-button" type="button" onClick={() => (setFilterPaused(false), setAutoPages(0))}>{t('grid.filter_again')}</button>
                    </Show>
                    <Show when={state() !== 'shown-all' && (page()?.hiddenByFilter ?? 0) > 0}>
                        <button class="outline-button" type="button" onClick={() => setFilterPaused(true)}>{t('grid.filter_show_them')}</button>
                    </Show>
                    <Show when={keepLookingFilter()}>
                        <button class="outline-button" type="button" onClick={keepLooking}>{t('common.keep_looking')}</button>
                    </Show>
                    <button class="outline-button" type="button" onClick={clearFilter}>{t('grid.filter_clear')}</button>
                </div>
            </Show>
            <Show when={state() === 'hides-all'}>
                <div class="filter-empty">
                    <p class="muted">{t('grid.filter_hides_all', { count: page()!.total, filter: why() })}</p>
                    <button class="primary" type="button" onClick={clearFilter} data-tv-first>{t('grid.filter_clear')}</button>
                    <Show when={keepLookingFilter()}>
                        <button class="outline-button" type="button" onClick={keepLooking}>{t('common.keep_looking')}</button>
                    </Show>
                </div>
            </Show>
            <Show when={budgetSpent()}>
                <p class="muted small">{t('grid.filter_budget', { count: page()!.loaded })}</p>
            </Show>
            {/* Every order but Newest sorts what has loaded so far, while older pages remain: said once (the apps' note). */}
            <Show when={settings().sort !== 'Newest' && !view().query && page() && !page()!.done && page()!.loaded > 0}>
                <p class="muted small">{t('grid.sort_loaded_note', { count: page()!.loaded })}</p>
            </Show>
            <Show when={empty()}>
                <p class="muted">{empty()}</p>
            </Show>
            <VideoList cards={cards()} label={title()} onEnd={atEnd} sponsored={page()?.sponsored} sponsoredBetween={page()?.sponsoredBetween} />
            <div class="list-foot">
                <WorkStatusLine status={footStatus()} />
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
