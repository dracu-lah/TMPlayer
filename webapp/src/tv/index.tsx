// The TV layout's own pieces (plan 8.5), in one chunk that only the TV form loads (App.tsx and
// Home.tsx import it on demand): the remote's keys and focus map, the top bar, and Home's carousel.
// Their look follows the Android TV app's TvTopBar and TvHomeCarousel; sizes are in the app's dp
// on a 960 x 540 screen (tv.css).
import { createEffect, createMemo, createSignal, For, on, onCleanup, onMount, Show } from 'solid-js'
import { A, useLocation, useNavigate } from '@solidjs/router'
import { Home03Icon, Message01Icon, PlayIcon, Search01Icon } from '@hugeicons/core-free-icons'
import { Icon, type IconData } from '../ui/Icon'
import { initialOf } from '../ui/initial'
import { me, back } from '../session'
import { t } from '../i18n'
import { loadThumb } from '../screens/thumbs'
import { tileCard, useProgress, watchPath } from '../screens/VideoList'
import { createFocusMap, resting } from './focus'
import type { HomeRow, Tile } from '../tg/types'
import './tv.css'

/** The four places the bar holds: their addresses, and which pages each stands for. */
const isTop = (p: string) => p === '/' || p === '/chats' || p === '/search' || p === '/settings'
function placeOf(path: string): string | null {
    if (path === '/' || path === '/search' || path === '/settings') return path
    if (path === '/chats' || path.startsWith('/chat/')) return '/chats'
    return null
}

/** The remote: listens while the TV layout is on, and puts the focus where it belongs after every page change. */
export function TvKeys() {
    const location = useLocation()
    const navigate = useNavigate()
    const map = createFocusMap({
        path: () => window.location.pathname + window.location.search,
        go: (to) => navigate(to),
        back: () => back(navigate),
        isSubPage: (p) => !isTop(p.split('?')[0]),
    })
    onMount(map.start)
    onCleanup(map.stop)
    // A new page, not new words in its address (a search being typed keeps the remote in its field).
    createEffect(on(() => location.pathname, () => queueMicrotask(() => map.routed(window.location.pathname + window.location.search))))
    return null
}

interface Tab {
    href: string
    label: () => string
    icon: IconData
}

const TABS: Tab[] = [
    { href: '/search', label: () => t('common.search'), icon: Search01Icon },
    { href: '/', label: () => t('browse.tab_home'), icon: Home03Icon },
    { href: '/chats', label: () => t('nav.chats'), icon: Message01Icon },
]

/** Home's carousel is up (the bar stands over its picture until the rows scroll under it). */
const [heroUp, setHeroUp] = createSignal(false)

/**
 * The bar, as TvTopBar draws it: the account's picture at the start (Settings, as the web's "You"
 * opens), then Search, Home and Chats, and the app's mark and name at the end, which take no
 * focus. The page shown is marked by its filled icon, or the picture by a thin ring.
 */
export function TvBar() {
    const location = useLocation()
    const place = () => placeOf(location.pathname)
    const initial = () => initialOf(me()?.name)
    const [scrolled, setScrolled] = createSignal(scrollY > 0)
    const onScroll = () => {
        const s = scrollY > innerHeight * 0.25
        if (s !== scrolled()) setScrolled(s)
    }
    onMount(() => {
        onScroll()
        addEventListener('scroll', onScroll, { passive: true })
        onCleanup(() => removeEventListener('scroll', onScroll))
    })
    return (
        <header class="tv-bar" classList={{ 'over-art': location.pathname === '/' && heroUp() && !scrolled() }}>
            <div class="tv-bar-row">
                <A class="tv-picture" href="/settings" aria-label={t('nav.you')} title={t('nav.you')} aria-current={place() === '/settings' ? 'page' : undefined}>
                    <Show when={initial()} fallback={<span class="nav-picture-blank" />}>
                        {initial()}
                    </Show>
                </A>
                <nav class="tv-tabs" aria-label="Main">
                    <For each={TABS}>
                        {(tab) => (
                            <A class="tv-tab" href={tab.href} aria-current={place() === tab.href ? 'page' : undefined}>
                                <Icon icon={tab.icon} size={20} selected={place() === tab.href} />
                                <span>{tab.label()}</span>
                            </A>
                        )}
                    </For>
                </nav>
                <div class="tv-brand" aria-hidden="true">
                    <img src="/icon.svg" alt="" width="40" height="40" />
                    <span>TMPlayer</span>
                </div>
            </div>
        </header>
    )
}

/** How long a page of the carousel is up before it moves on. */
const ADVANCE_MS = 7_000
const PAGES = 5

/**
 * Home's carousel on a TV (TvHomeCarousel): the newest five in Recently added, one picture at a
 * time across the page and up behind the bar, its name and facts, Play and its chat. Play and the
 * chat's pill stay put while the pages change under them; Left on Play goes back a page, Right on
 * the chat's pill goes on one. It moves on by itself only while the remote is elsewhere, the tab
 * is in sight, motion is allowed and a key was pressed in the last minute, so a TV left on Home is
 * not drawing a new picture every few seconds for hours. It has its size from the first frame.
 */
export function TvHero(props: { rows: HomeRow[] | undefined }) {
    /** null while Recently added is still asked for; empty when there is nothing to show. */
    const picks = createMemo<Tile[] | null>(() => {
        if (!props.rows) return null
        const recent = props.rows.find((r) => r.kind === 'recent')
        if (!recent) return []
        return recent.loaded ? recent.tiles.slice(0, PAGES) : null
    })
    const [key, setKey] = createSignal<string | null>(null)
    const index = () => Math.max(0, picks()?.findIndex((p) => p.key === key()) ?? 0)
    const pick = () => picks()?.[index()] ?? null
    const go = (i: number) => {
        const list = picks()
        if (list?.length) setKey(list[((i % list.length) + list.length) % list.length].key)
    }

    createEffect(() => setHeroUp(picks()?.length !== 0))
    onCleanup(() => setHeroUp(false))

    // The clock: one timer, started again on each page, stopped while anything says so.
    const [focusedIn, setFocusedIn] = createSignal(false)
    const [seen, setSeen] = createSignal(!document.hidden)
    const onVisible = () => setSeen(!document.hidden)
    document.addEventListener('visibilitychange', onVisible)
    onCleanup(() => document.removeEventListener('visibilitychange', onVisible))
    const still = matchMedia('(prefers-reduced-motion: reduce)').matches
    createEffect(() => {
        const n = picks()?.length ?? 0
        if (n < 2 || still || resting() || focusedIn() || !seen()) return
        index()
        const timer = window.setTimeout(() => go(index() + 1), ADVANCE_MS)
        onCleanup(() => clearTimeout(timer))
    })

    const [src, setSrc] = createSignal<string | null>(null)
    createEffect(() => {
        const thumb = pick()?.item.thumb
        let live = true
        if (!thumb) return setSrc(null)
        loadThumb(thumb).then(
            (url) => live && setSrc(url),
            () => live && setSrc(null),
        )
        onCleanup(() => (live = false))
    })

    const progressOf = useProgress()
    const card = () => (pick() ? tileCard(pick()!, true) : null)
    const resumed = () => {
        const p = pick()
        return p ? (progressOf(p.item) ?? 0) > 0 : false
    }
    const turn = (dir: 'left' | 'right', allowed: () => boolean, to: () => number) => (el: HTMLElement) => {
        el.addEventListener('tvmove', (e) => {
            if ((e as CustomEvent).detail !== dir || !allowed()) return
            e.preventDefault()
            go(to())
        })
    }

    return (
        <Show when={picks()?.length !== 0}>
            <section class="tv-hero" data-tv-top aria-roledescription="carousel" aria-label={t('home.row_recent')}>
                <Show when={pick()} fallback={<div class="tv-hero-wait" aria-hidden="true"><span /><span /><span /></div>}>
                    <div class="tv-hero-art" aria-hidden="true">
                        <Show when={src()}>
                            {/* Keyed by address: a new page's picture comes in on its own. */}
                            <For each={[src()!]}>{(url) => <img src={url} alt="" decoding="async" />}</For>
                        </Show>
                    </div>
                    <div class="tv-hero-text">
                        <h2 class="tv-hero-title">{card()!.title}</h2>
                        <p class="tv-hero-facts">{card()!.facts}</p>
                    </div>
                    <div class="tv-hero-actions" onFocusIn={() => setFocusedIn(true)} onFocusOut={() => setFocusedIn(false)}>
                        <A
                            class="tv-hero-play"
                            data-tv-first
                            href={watchPath(pick()!.item)}
                            state={{ video: pick()!.item }}
                            ref={turn('left', () => index() > 0, () => index() - 1)}
                        >
                            <Icon icon={PlayIcon} size={26} />
                            <span>{resumed() ? t('common.resume') : t('common.play')}</span>
                        </A>
                        <A class="tv-hero-pill" href={`/chat/${pick()!.item.chatId}`} ref={turn('right', () => (picks()?.length ?? 0) > 1, () => index() + 1)}>
                            <Icon icon={Message01Icon} size={22} />
                            <span>{t('detail.open_chat', { title: pick()!.item.chat })}</span>
                        </A>
                    </div>
                    <Show when={(picks()?.length ?? 0) > 1}>
                        <div class="tv-hero-dots" aria-label={t('home.featured_page', { index: index() + 1, count: picks()!.length })} role="img">
                            <For each={picks()!}>{(_, i) => <span classList={{ on: i() === index() }} />}</For>
                        </div>
                    </Show>
                </Show>
            </section>
        </Show>
    )
}
