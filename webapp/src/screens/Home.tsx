import { createEffect, createMemo, createResource, createSignal, For, Index, lazy, Match, on, onCleanup, onMount, Show, Switch } from 'solid-js'
import { A } from '@solidjs/router'
import { ArrowLeft01Icon, ArrowRight01Icon, UserAdd01Icon } from '@hugeicons/core-free-icons'
import { api, call, errorText, onWorkerRestart } from '../tg/rpc'
import { online, onBackOnline } from '../net'
import { t } from '../i18n'
import { addedIds, addedMode, addedView, namedLists, progress, rememberHomeChats, resumes, settings, starredIds, watchedTick } from '../store'
import { copiesTick } from '../copies'
import { arrangeOrder, parseOrder } from '../ui/homeOrder'
import { forgetGone } from '../goneVideos'
import { scrollBehavior } from '../ui/motion'
import { Icon } from '../ui/Icon'
import { PullToRefresh, RefreshButton, useAskedRefresh } from '../ui/AskedRefresh'
import { WorkStatusLine } from '../ui/WorkStatus'
import { keptFailure, rowsFailure } from '../ui/loadStates'
import { idle, mergeStatus, type WorkStatus } from '../ui/workStatus'
import { KeepPlaceCard } from './KeepPlaceCard'
import { SafariClearsCard } from './SafariClearsCard'
import { AskGate } from './cardAsk'
import { createDwell } from '../ui/dwell'
import { useObservedCovers } from './promo'
import { Card, ContinueCard, tileCard, useLeft, useProgress, useWarmDataCentres, videoCard, stableCards, type CardData } from './VideoList'
import { form } from '../form'
import type { HomePage, HomeRow, HomeView, Tile } from '../tg/types'

/** The TV layout's carousel, in the TV chunk (plan 8.5). */
const TvHero = lazy(() => import('../tv').then((m) => ({ default: m.TvHero })))
/** The phone's and the desktop's carousel (FeaturedCarousel), fetched with Home's first rows. */
const Hero = lazy(() => import('./Hero').then((m) => ({ default: m.Hero })))

/** Home as last drawn, so coming back to it paints at once while it is asked for again. */
const [last, setLast] = createSignal<HomePage | null>(null)

/**
 * Home as the app fills it: Continue watching, Recently added across Home's chats, then a row
 * for each starred chat, built in the worker by the app's own rules. Rows still waiting for
 * Telegram hold their place; the first run, with nothing anywhere, points to Saved Messages.
 */
export function Home() {
    const [error, setError] = createSignal<unknown>(null)
    const [busy, setBusy] = createSignal(false)
    // When the rows on screen last came from Telegram, for "Showing what was here at ..." after a failed load.
    const [keptAt, setKeptAt] = createSignal<number | null>(null)
    const apply = (page: HomePage) => {
        setLast(page)
        setKeptAt(Date.now())
        setError(null)
        // Named in Settings > Home's order list, which does not ask Telegram for them.
        rememberHomeChats(page.rows.flatMap((row) => (row.kind === 'chat' && row.chatId !== null && row.title ? [{ chatId: row.chatId, title: row.title }] : [])))
        // A video part way whose message is gone for good leaves the lists, and the page says so (HX21).
        if (page.gone.length) void forgetGone(page.gone)
    }

    let abort: AbortController | null = null
    onCleanup(() => abort?.abort())
    const load = async () => {
        abort?.abort()
        const mine = (abort = new AbortController())
        setBusy(true)
        try {
            const [left, seen] = await Promise.all([resumes(), progress()])
            const view = { minBytes: settings().sizeMin, maxBytes: settings().sizeMax, ...seen, resumes: left, starred: [...starredIds()], added: addedView(), layout: homeLayout() }
            apply(await call('home', [view], { signal: mine.signal, onEvent: (p) => void apply(p as HomePage) }))
        } catch (e) {
            if (!mine.signal.aborted) setError(e)
        } finally {
            if (abort === mine) setBusy(false)
        }
    }
    // Refresh (WP-40): the button, a pull on a phone and coming back after 5 minutes hidden are one asked reload, quiet unless it
    // runs long or fails; the rows on screen stay (the page is not unmounted), so the scroll stays.
    const refresh = useAskedRefresh(async () => {
        await load()
        if (error()) throw error()
    })
    // Asked again when the size limits, the stars, the added chats or Settings > Home change, as every list is.
    createEffect(on(() => [settings().sizeMin, settings().sizeMax, [...starredIds()].join(), watchedTick(), copiesTick(), addedMode(), [...(addedIds() ?? [])].join(), JSON.stringify(homeLayout())], load))
    // Lost on the way: asked again once the connection is back.
    onBackOnline(() => (error() || !last()?.settled) && load())
    // The Telegram worker was replaced: it knows no row is on screen and its Home call is gone. Rows on screen say so again, and Home is asked for again.
    onCleanup(
        onWorkerRestart(() => {
            rowDwell.reset()
            void load()
        }),
    )

    const page = () => last()
    // One status line under the heading (WP-99): a load that failed with rows on screen keeps them and says since when (LS-25),
    // beside the asked refresh's own words, and the first read says what it is doing under the placeholders.
    const headStatus = createMemo<WorkStatus>(() => {
        const kept = keptFailure({ failed: !!error(), online: online(), rows: page()?.rows.length ?? 0, keptAt: keptAt() })
        const first: WorkStatus = !page() && busy() ? { kind: 'loading', what: t('browse.finding_videos') } : idle
        return mergeStatus(mergeStatus(kept, refresh.status()), first)
    })
    // Chats whose rows could not be read left the page: the foot says how many, with Try again (LS-26, as the apps' foot).
    const footStatus = () => {
        const p = page()
        if (!p?.settled) return idle
        return rowsFailure(p.failed ?? 0, p.rows.filter((r) => r.loaded).length, (count) => t('home.rows_failed', { count }))
    }
    // Every row off in Settings > Home: Home says so and leads there, rather than standing blank.
    const allOff = () => {
        const l = homeLayout()
        return !l.carousel && !l.continueWatching && !l.recentlyAdded && !l.starredRows
    }
    const listRows = useListRows()
    // Home's rows and the named lists' rows (HX43) in the viewer's order (Settings > Home): a list's row moves like any other.
    const rows = createMemo(() => {
        const all = [...(page()?.rows ?? []), ...listRows()]
        const byKey = new Map(all.map((r) => [r.key, r]))
        return arrangeOrder(all.map((r) => r.key), parseOrder(settings().homeOrder)).flatMap((k) => byKey.get(k) ?? [])
    })
    // Row keys and rows apart: the list of keys is the same while the rows' answers change under them.
    const rowKeys = createMemo(() => rows().map((r) => r.key))
    const rowByKey = createMemo(() => new Map(rows().map((r) => [r.key, r])))
    const firstRun = () => !allOff() && page()?.settled && !page()!.rows.length && !listRows().length
    return (
        <>
            {/* The app's Home opens on its mark and name, not on a heading (the page is still called Home). */}
            <div class="home-head">
                <h1 class="page-title home-title">
                    <img class="home-mark" src="/icon.svg" alt="" width="28" height="28" />
                    <span class="home-brand" aria-hidden="true">TMPlayer</span>
                    <span class="visually-hidden">{t('browse.tab_home')}</span>
                </h1>
                <RefreshButton busy={refresh.busy()} onRefresh={refresh.ask} />
            </div>
            <WorkStatusLine status={headStatus()} onRetry={refresh.ask} />
            <PullToRefresh onRefresh={refresh.ask} />
            <Show when={settings().carousel}>
                <Show when={form() === 'tv'} fallback={<Hero rows={page()?.rows} />}>
                    <TvHero rows={page()?.rows} />
                </Show>
            </Show>
            {/* Offline with nothing to show: the Reload card says so (R4), instead of a row of cards that cannot load. */}
            <Show when={!online() && !page()?.rows.length}>
                <div class="notice error-notice">
                    <p class="error" role="alert">{t('errors.offline')}</p>
                    <button class="link" type="button" onClick={load}>{t('common.reload')}</button>
                </div>
            </Show>
            <Show when={error() && online() && !page()?.rows.length}>
                <div class="notice error-notice">
                    <p class="error" role="alert">{errorText(error())}</p>
                    <button class="link" type="button" onClick={load}>{t('common.try_again')}</button>
                </div>
            </Show>
            <Show when={!page() && busy()}>
                <Placeholder />
                <Placeholder />
            </Show>
            <Show when={firstRun()}>
                <div class="first-run">
                    <h2>{t('home.first_title')}</h2>
                    <p>{t('home.first_body')}</p>
                    <p class="muted">{t('home.first_more')}</p>
                    <Show when={page()!.selfId}>
                        <A class="primary-link" href={`/chat/${page()!.selfId}`}>{t('home.first_open_saved')}</A>
                    </Show>
                </div>
            </Show>
            <Show when={allOff()}>
                <div class="first-run">
                    <h2>{t('home.off_title')}</h2>
                    <p>{t('home.off_body')}</p>
                    <A class="primary-link" href="/settings/home">{t('home.open_settings')}</A>
                </div>
            </Show>
            {/* Keyed by the row's key: a page of answers makes new row objects, and a row drawn again would lose its cards, strip position and place in line. */}
            <For each={rowKeys()}>
                {(key, index) => {
                    let last: HomeRow
                    const row = () => (last = rowByKey().get(key) ?? last)
                    return (
                        <Switch>
                            {/* Recently added kept for the carousel alone: its row is off. */}
                            <Match when={row().kind === 'recent' && !row().asRow}>{null}</Match>
                            <Match when={row().kind === 'recent' && row().noneChosen}>
                                <section class="home-row" aria-labelledby={`row-${key}`}>
                                    <div class="row-head">
                                        <h2 class="row-title" id={`row-${key}`}>{t('home.row_recent_all')}</h2>
                                    </div>
                                    <div class="notice home-none-chosen">
                                        <p>{t('home.none_added')}</p>
                                        <A class="outline-button" href="/added-chats" data-tv-first>
                                            <Icon icon={UserAdd01Icon} size={18} />
                                            {t('picker.choose_chats')}
                                        </A>
                                    </div>
                                </section>
                            </Match>
                            <Match when={true}>
                                <Row row={row()} index={index()} selfId={page()?.selfId ?? null} />
                            </Match>
                        </Switch>
                    )
                }}
            </For>
            <WorkStatusLine status={footStatus()} onRetry={refresh.ask} />
            {/* The one question about keeping the place on every device: after a Home that has rows, never over a first run or an empty page. */}
            {/* Safari clears a tab's storage after 7 days unused: said once to a signed in iPhone tab (WP-56). */}
            <SafariClearsCard />
            <KeepPlaceCard eligible={!!page()?.settled && rows().length > 0 && !firstRun() && !allOff()} />
        </>
    )
}

/** Settings > Home as the worker reads it (HomeView.layout). */
function homeLayout(): HomeView['layout'] {
    const s = settings()
    return { carousel: s.carousel, continueWatching: s.homeContinue, recentlyAdded: s.homeRecent, starredRows: s.homeStarred, order: s.homeOrder }
}

/** Lines read for a list's row: its first cards are among them however its shows fold. */
const LIST_ROW_LINES = 200
const ROW_CARDS = 10

/** A row for each named list put on Home, its first cards grouped by the app's rules in the worker. */
function useListRows() {
    const wanted = () => namedLists().filter((l) => l.info.home && l.lines.length)
    const [rows] = createResource(
        () => wanted().map((l) => ({ id: l.info.id, name: l.info.name, count: l.lines.length, lines: l.lines.slice(0, LIST_ROW_LINES) })),
        (lists) =>
            Promise.all(
                lists.map(async (l): Promise<HomeRow> => {
                    const tiles = await call('listTiles', [l.lines.map((x) => ({ item: x.item, show: x.show }))]).catch(() => [])
                    return { key: `list-${l.id}`, kind: 'list', listId: l.id, chatId: null, title: l.name, loaded: true, starredOnly: false, tiles: tiles.slice(0, ROW_CARDS), hasMore: true, asRow: true, noneChosen: false }
                }),
            ),
    )
    return () => (rows.latest ?? []).filter((r) => r.tiles.length)
}

function rowTitle(row: HomeRow, selfId: number | null): string {
    if (row.kind === 'continue') return t('home.row_continue')
    if (row.kind === 'list') return row.title ?? ''
    if (row.kind === 'recent') return row.starredOnly ? t('home.row_recent') : t('home.row_recent_all')
    return row.chatId === selfId ? t('chats.saved_messages') : (row.title ?? '')
}

/** Where a row's See all goes: a starred chat's own page, or Recently added's; null for a row without one. */
function seeAllOf(row: HomeRow): string | null {
    // Always for a list: its page is where it is renamed, taken off Home or deleted.
    if (row.kind === 'list' && row.listId) return `/lists/${row.listId}`
    if (!row.hasMore) return null
    if (row.kind === 'chat' && row.chatId != null) return `/chat/${row.chatId}`
    if (row.kind === 'recent') return '/recent'
    return null
}

/** A row's cards, the same objects for a tile that did not change, so a page of answers does not draw every card again. */
function useRowCards(row: () => HomeRow): () => CardData[] {
    const make = stableCards((tile: Tile) => (row().kind === 'continue' ? videoCard(tile.item) : tileCard(tile, row().kind === 'recent' || row().kind === 'list')))
    return createMemo(() => make(row().tiles))
}

/** A row has to stay on screen this long before it asks for anything (the apps' ROW_DWELL_MS, HX54). */
const ROW_DWELL_MS = 250
/** Rows from the top that ask at once, as the worker reads its first rows without waiting (HomeViewModel.EAGER_CHATS). */
const EAGER_ROWS = 8

/** What the worker is told about a chat row: stayed on screen, or gone for good (a read not begun is then dropped). */
function tellWorker(key: string, on: boolean) {
    if (!key.startsWith('chat:')) return
    const id = Number(key.slice(5))
    if (Number.isSafeInteger(id)) api.homeRow(id, on).catch(() => {})
}

/**
 * Keyed by row, not by the component: Home draws its rows again as each page of answers lands, and a
 * row drawn anew a moment after it was drawn is the same row (ui/dwell.ts).
 */
const rowDwell = createDwell({ ms: ROW_DWELL_MS, grace: 600, confirm: (k) => tellWorker(k, true), release: (k) => tellWorker(k, false) })

/** One row: its heading (a starred chat's opens the chat), and its cards in a strip that scrolls sideways. */
function Row(props: { row: HomeRow; index: number; selfId: number | null }) {
    let strip: HTMLUListElement | undefined
    let section!: HTMLElement
    // A row asks for its content only after it has stayed on screen (HX54). Its cards ask for their
    // pictures once it has; the first rows, and the rows that are not a chat's, need no such wait.
    const dwellKey = props.row.kind === 'chat' && props.row.chatId != null ? `chat:${props.row.chatId}` : props.row.key
    const [stayed, setStayed] = createSignal(rowDwell.confirmed(dwellKey))
    const asks = () => stayed() || props.row.kind !== 'chat' || props.index < EAGER_ROWS
    const enter = () => rowDwell.enter(dwellKey, () => setStayed(true))
    const leave = () => {
        rowDwell.leave(dwellKey)
        setStayed(false)
    }
    const noObserver = typeof IntersectionObserver === 'undefined'
    /** What the observer said last: on screen or not. */
    let seen = false
    // Focus passing on before a frame was drawn (a remote held down on a slow TV stick: several rows a frame) leaves a row the
    // observer never saw on screen, so it never says it left: the row is left here, else it was read 250 ms later all the same.
    const focusLeft = (e: FocusEvent) => {
        if (noObserver || seen || section.contains(e.relatedTarget as Node | null)) return
        leave()
    }
    onMount(() => {
        if (noObserver) {
            // An old engine without an observer: the row asks when it is focused or pointed at (the TV's remote, a mouse), the first ones at once.
            if (props.index < 2) enter()
            return
        }
        const io = new IntersectionObserver((entries) => {
            const last = entries[entries.length - 1]
            if (!last) return
            seen = last.isIntersecting
            seen ? enter() : leave()
        })
        io.observe(section)
        onCleanup(() => io.disconnect())
    })
    onCleanup(() => rowDwell.leave(dwellKey))
    const [atStart, setAtStart] = createSignal(true)
    const [atEnd, setAtEnd] = createSignal(false)
    const progressOf = useProgress()
    const leftOf = useLeft()
    const cards = useRowCards(() => props.row)
    useWarmDataCentres(() => cards().map((c) => c.item))
    useObservedCovers(() => cards().map((c) => c.item))
    const edges = () => {
        if (!strip) return
        setAtStart(strip.scrollLeft <= 1)
        setAtEnd(strip.scrollLeft + strip.clientWidth >= strip.scrollWidth - 1)
    }
    createEffect(on(cards, () => queueMicrotask(edges)))
    const scroll = (dir: 1 | -1) => strip?.scrollBy({ left: dir * strip.clientWidth * 0.9, behavior: scrollBehavior() })
    const id = `row-${props.row.key}`
    const seeAll = () => seeAllOf(props.row)
    // Recently added's page says "in starred chats" as the row does, from its first frame.
    const seeAllState = () => (props.row.kind === 'recent' ? { starredOnly: props.row.starredOnly } : undefined)
    return (
        // Focus counts as on screen: the TV's remote moves it row to row, and a browser without an observer asks on it.
        <section class="home-row" aria-labelledby={id} ref={section} onFocusIn={enter} onFocusOut={focusLeft} onPointerEnter={() => noObserver && enter()}>
            <div class="row-head">
                <h2 class="row-title" id={id}>
                    <Show when={props.row.kind === 'chat' && props.row.chatId != null} fallback={rowTitle(props.row, props.selfId)}>
                        <A class="row-link" href={`/chat/${props.row.chatId}`}>{rowTitle(props.row, props.selfId)}</A>
                    </Show>
                </h2>
                <Show when={props.row.loaded}>
                    <div class="row-arrows">
                        <button class="round-button small" type="button" disabled={atStart()} aria-label={t('home.row_scroll_back')} title={t('home.row_scroll_back')} onClick={() => scroll(-1)}>
                            <Icon icon={ArrowLeft01Icon} size={20} />
                        </button>
                        <button class="round-button small" type="button" disabled={atEnd()} aria-label={t('home.row_scroll_on')} title={t('home.row_scroll_on')} onClick={() => scroll(1)}>
                            <Icon icon={ArrowRight01Icon} size={20} />
                        </button>
                    </div>
                </Show>
                {/* On a TV See all is the strip's last card instead (SeeAllCard), where Right past the last video lands. */}
                <Show when={form() !== 'tv' && seeAll()}>
                    <A class="link see-all" href={seeAll()!} state={seeAllState()}>{t('home.see_all')}</A>
                </Show>
            </div>
            <AskGate.Provider value={asks}>
                <Show when={props.row.loaded} fallback={<Strip continues={props.row.kind === 'continue' && form() !== 'tv'} />}>
                    <ul class="strip" classList={{ continues: props.row.kind === 'continue' && form() !== 'tv' }} ref={strip} onScroll={edges}>
                        <For each={cards()}>
                            {(c) => (
                                <li>
                                    {/* Continue watching: a landscape card with how long is left, which plays at once. */}
                                    <Show when={props.row.kind === 'continue' && form() !== 'tv'} fallback={<Card card={c} progress={progressOf(c.item)} direct={props.row.kind === 'continue'} />}>
                                        <ContinueCard item={c.item} left={leftOf(c.item)} />
                                    </Show>
                                </li>
                            )}
                        </For>
                        <Show when={form() === 'tv' && seeAll()}>
                            <li>
                                <SeeAllCard href={seeAll()!} state={seeAllState()} />
                            </li>
                        </Show>
                    </ul>
                </Show>
            </AskGate.Provider>
        </section>
    )
}

/**
 * A TV row's last card (the app's SeeAllTile): art the size of the row's posters, focused as they
 * are, a chevron over "See all" in the accent on the surface tinted with it. No line under it.
 */
function SeeAllCard(props: { href: string; state?: { starredOnly: boolean } }) {
    return (
        <A class="card see-all-card" href={props.href} state={props.state}>
            <div class="card-art see-all-art">
                <Icon icon={ArrowRight01Icon} size={32} />
                <span class="see-all-label">{t('home.see_all')}</span>
            </div>
        </A>
    )
}

/** A row still waiting for Telegram, the height of a filled one, so nothing below moves when it fills. */
function Strip(props: { continues?: boolean }) {
    return (
        <ul class="strip waiting" classList={{ continues: props.continues }} aria-hidden="true">
            <Index each={Array(8)}>
                {() => (
                    <li>
                        <div class={props.continues ? 'continue-art' : 'card-art'} />
                        <Show when={props.continues} fallback={<div class="card-name">&nbsp;</div>}>
                            <div class="continue-name">&nbsp;</div>
                        </Show>
                        <div class="card-facts">&nbsp;</div>
                    </li>
                )}
            </Index>
        </ul>
    )
}

function Placeholder() {
    return (
        <section class="home-row" aria-hidden="true">
            <div class="row-head">
                <h2 class="row-title">&nbsp;</h2>
            </div>
            <Strip />
        </section>
    )
}
