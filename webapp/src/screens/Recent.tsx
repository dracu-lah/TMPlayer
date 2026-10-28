import { createEffect, createResource, createSignal, on, onCleanup, Show } from 'solid-js'
import { useLocation } from '@solidjs/router'
import { call, errorText } from '../tg/rpc'
import { onBackOnline } from '../net'
import { t } from '../i18n'
import { progress as readProgress, settings, watchedTick } from '../store'
import { PageBar } from '../ui/PageBar'
import { noVideosWithin } from './sizes'
import { HiddenBySize, tileCard, VideoList } from './VideoList'
import type { ListView, RecentPage } from '../tg/types'

/** Asks for more in a row that brought no new card before the page stops by itself and offers a button. */
const IDLE_PAGES = 3

type Mode = 'open' | 'more' | 'view'

/**
 * Recently added's See all: the newest videos across Home's chats as a grid, grouped as a chat's
 * are, read on a page at a time as it nears its foot. The worker keeps at most a couple of
 * thousand (library.ts RECENT_MAX), so a huge account never holds every video at once.
 */
export function Recent() {
    const location = useLocation<{ starredOnly?: boolean }>()
    const [page, setPage] = createSignal<RecentPage | null>(null)
    const [error, setError] = createSignal<unknown>(null)
    const [busy, setBusy] = createSignal<Mode | null>(null)
    const [moreFailed, setMoreFailed] = createSignal(false)
    const [idlePages, setIdlePages] = createSignal(0)
    const [progress, { refetch: rereadProgress }] = createResource(readProgress)
    const view = (): ListView => ({
        minBytes: settings().sizeMin,
        maxBytes: settings().sizeMax,
        watched: progress()?.watched ?? [],
        started: progress()?.started ?? [],
    })

    // One ask at a time: a newer one (another view) stops the one before, and leaving stops both.
    let abort: AbortController | null = null
    onCleanup(() => abort?.abort())
    const run = async (mode: Mode) => {
        abort?.abort()
        const mine = (abort = new AbortController())
        const before = page()?.tiles.length ?? 0
        setBusy(mode)
        if (mode === 'more') setMoreFailed(false)
        try {
            const next = await call('recent', [view(), mode], { signal: mine.signal })
            if (mine.signal.aborted) return
            setPage(next)
            setError(null)
            if (mode === 'more') setIdlePages(next.tiles.length > before ? 0 : idlePages() + 1)
            else if (mode === 'open') setIdlePages(0)
        } catch (e) {
            if (mine.signal.aborted) return
            if (mode === 'more' && page()) setMoreFailed(true)
            else setError(e)
        } finally {
            if (abort === mine) setBusy(null)
        }
    }

    run('open')
    // A page lost with the connection loads once it is back.
    onBackOnline(() => {
        if (busy()) return
        if (error()) run(page() ? 'view' : 'open')
        else if (moreFailed()) run('more')
    })
    // Other size limits, or a video watched: the same videos arranged again, in the worker.
    createEffect(on(() => JSON.stringify(view()), () => page() && run('view'), { defer: true }))
    createEffect(on(watchedTick, () => void rereadProgress(), { defer: true }))

    // Near the foot: the next page, unless one is on its way, the last failed, or several brought nothing.
    const atEnd = () => {
        const p = page()
        if (!p || p.done || busy() || moreFailed() || error() || idlePages() >= IDLE_PAGES) return
        run('more')
    }
    // A first answer with no cards yet (all held back for the other feed) leaves no foot to reach.
    createEffect(() => {
        const p = page()
        if (p && !p.tiles.length) atEnd()
    })

    const title = () => ((page()?.starredOnly ?? location.state?.starredOnly) ? t('home.row_recent') : t('home.row_recent_all'))
    const cards = () => page()?.tiles.map((tile) => tileCard(tile, true)) ?? []
    const empty = () => {
        const p = page()
        if (!p || p.tiles.length || !p.done) return null
        if (p.hiddenBySize) return noVideosWithin(view().minBytes, view().maxBytes)
        return t('browse.no_playable_videos')
    }
    // A failed first page opens again; a failed arranging (the only other failure shown here) arranges again.
    const retry = () => run(page() ? 'view' : 'open')

    return (
        <>
            <PageBar title={title()} />
            <h1 class="visually-hidden">{title()}</h1>
            <Show when={busy() === 'open' && !page()}>
                <p class="muted">{t('browse.finding_videos')}</p>
            </Show>
            <Show when={error()}>
                <div class="notice error-notice">
                    <p class="error">{errorText(error())}</p>
                    <button class="link" type="button" onClick={retry}>{t('common.try_again')}</button>
                </div>
            </Show>
            <HiddenBySize count={page()?.hiddenBySize ?? 0} />
            <Show when={empty()}>
                <p class="muted">{empty()}</p>
            </Show>
            <VideoList cards={cards()} label={title()} onEnd={atEnd} />
            <div class="list-foot">
                <Show when={busy() === 'more'}>
                    <p class="muted">{t('grid.loading_more')}</p>
                </Show>
                <Show when={moreFailed()}>
                    <p class="error">{t('grid.more_failed')}</p>
                    <button class="link" type="button" onClick={() => run('more')}>{t('common.try_again')}</button>
                </Show>
                <Show when={page() && !page()!.done && !busy() && !moreFailed() && idlePages() >= IDLE_PAGES}>
                    <button class="link" type="button" onClick={() => run('more')}>{t('common.more')}</button>
                </Show>
                <Show when={page()?.capped}>
                    <p class="muted small">{t('home.recent_capped')}</p>
                </Show>
            </div>
        </>
    )
}
