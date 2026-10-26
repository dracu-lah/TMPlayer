import { createEffect, createSignal, For, Index, on, onCleanup, Show } from 'solid-js'
import { A } from '@solidjs/router'
import { ArrowLeft01Icon, ArrowRight01Icon } from '@hugeicons/core-free-icons'
import { call, errorText } from '../tg/rpc'
import { onBackOnline } from '../net'
import { t } from '../i18n'
import { dropResume, progress, resumes, settings, starredIds } from '../store'
import { Icon } from '../ui/Icon'
import { Card, tileCard, useProgress, useWarmDataCentres, videoCard, type CardData } from './VideoList'
import type { HomePage, HomeRow } from '../tg/types'

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
    const dropped = new Set<string>()
    const apply = (page: HomePage) => {
        setLast(page)
        setError(null)
        // A video part way whose message is gone is forgotten quietly.
        for (const g of page.gone) {
            const key = `${g.chatId}:${g.messageId}`
            if (dropped.has(key)) continue
            dropped.add(key)
            dropResume(g.chatId, g.messageId)
        }
    }

    let abort: AbortController | null = null
    onCleanup(() => abort?.abort())
    const load = async () => {
        abort?.abort()
        const mine = (abort = new AbortController())
        setBusy(true)
        try {
            const [left, seen] = await Promise.all([resumes(), progress()])
            const view = { minBytes: settings().sizeMin, maxBytes: settings().sizeMax, ...seen, resumes: left, starred: [...starredIds()] }
            apply(await call('home', [view], { signal: mine.signal, onEvent: (p) => void apply(p as HomePage) }))
        } catch (e) {
            if (!mine.signal.aborted) setError(e)
        } finally {
            if (abort === mine) setBusy(false)
        }
    }
    // Asked again when the size limits or the stars change, as every list is.
    createEffect(on(() => [settings().sizeMin, settings().sizeMax, [...starredIds()].join()], load))
    // Lost on the way: asked again once the connection is back.
    onBackOnline(() => (error() || !last()?.settled) && load())

    const page = () => last()
    const firstRun = () => page()?.settled && !page()!.rows.length
    return (
        <>
            <h1 class="page-title">{t('browse.tab_home')}</h1>
            <Show when={error() && !page()?.rows.length}>
                <div class="notice error-notice">
                    <p class="error">{errorText(error())}</p>
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
            <For each={page()?.rows ?? []}>{(row) => <Row row={row} selfId={page()?.selfId ?? null} />}</For>
        </>
    )
}

function rowTitle(row: HomeRow, selfId: number | null): string {
    if (row.kind === 'continue') return t('home.row_continue')
    if (row.kind === 'recent') return row.starredOnly ? t('home.row_recent') : t('home.row_recent_all')
    return row.chatId === selfId ? t('chats.saved_messages') : (row.title ?? '')
}

function cardsOf(row: HomeRow): CardData[] {
    if (row.kind === 'continue') return row.tiles.map((tile) => videoCard(tile.item))
    return row.tiles.map((tile) => tileCard(tile, row.kind === 'recent'))
}

/** One row: its heading (a starred chat's opens the chat), and its cards in a strip that scrolls sideways. */
function Row(props: { row: HomeRow; selfId: number | null }) {
    let strip: HTMLUListElement | undefined
    const [atStart, setAtStart] = createSignal(true)
    const [atEnd, setAtEnd] = createSignal(false)
    const progressOf = useProgress()
    const cards = () => cardsOf(props.row)
    useWarmDataCentres(() => cards().map((c) => c.item))
    const edges = () => {
        if (!strip) return
        setAtStart(strip.scrollLeft <= 1)
        setAtEnd(strip.scrollLeft + strip.clientWidth >= strip.scrollWidth - 1)
    }
    createEffect(on(cards, () => queueMicrotask(edges)))
    const scroll = (dir: 1 | -1) => strip?.scrollBy({ left: dir * strip.clientWidth * 0.9, behavior: 'smooth' })
    const id = `row-${props.row.key}`
    return (
        <section class="home-row" aria-labelledby={id}>
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
                <Show when={props.row.hasMore && props.row.chatId != null}>
                    <A class="link see-all" href={`/chat/${props.row.chatId}`}>{t('home.see_all')}</A>
                </Show>
            </div>
            <Show when={props.row.loaded} fallback={<Strip />}>
                <ul class="strip" ref={strip} onScroll={edges}>
                    <For each={cards()}>
                        {(c) => (
                            <li>
                                <Card card={c} progress={progressOf(c.item)} />
                            </li>
                        )}
                    </For>
                </ul>
            </Show>
        </section>
    )
}

/** A row still waiting for Telegram, the height of a filled one, so nothing below moves when it fills. */
function Strip() {
    return (
        <ul class="strip waiting" aria-hidden="true">
            <Index each={Array(8)}>
                {() => (
                    <li>
                        <div class="card-art" />
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
