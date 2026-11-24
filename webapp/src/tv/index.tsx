// The TV layout's own pieces (plan 8.5), in one chunk that only the TV form loads (App.tsx and
// Home.tsx import it on demand): the remote's keys and focus map, the top bar, and Home's carousel.
// Their look follows the Android TV app's TvTopBar and TvHomeCarousel; sizes are in the app's dp
// on a 960 x 540 screen (tv.css).
import { pagePlayLabel, pageTitle, useFeatured } from '../screens/featured'
import { createEffect, createSignal, For, on, onCleanup, onMount, Show } from 'solid-js'
import { A, useLocation, useNavigate } from '@solidjs/router'
import { ArrowExpandIcon, ArrowShrinkIcon, Home03Icon, InformationCircleIcon, Message01Icon, PlayIcon, Search01Icon } from '@hugeicons/core-free-icons'
import { Icon, type IconData } from '../ui/Icon'
import { ChooseChatsButton } from '../ui/ChooseChatsButton'
import { initialOf } from '../ui/initial'
import { nameArt } from '../ui/colour'
import { me, back } from '../session'
import { addAccount } from '../accountFlows'
import { locked, many, openPicker } from '../accounts'
import { askSignOut } from '../screens/settings/SignOut'
import { t } from '../i18n'
import { loadThumb } from '../screens/thumbs'
import { answer, askOf, loadBackdropLightFirst, loadExtras, loadLogo, type Answer, type Extras } from '../screens/posters'
import { coverBackground } from '../screens/cover'
import { featuredFactsLine } from '../screens/featuredFacts'
import { settings } from '../store'
import { detailPath, shortLength, tileCard, useProgress, watchPath } from '../screens/VideoList'
import { TitleLogo } from '../ui/TitleLogo'
import { TmdbMark } from '../ui/TmdbMark'
import { VersionFooter } from '../ui/VersionFooter'
import { createFocusMap, resting } from './focus'
import type { HomeRow, Tile } from '../tg/types'
import './tv.css'

/** The four places the bar holds: their addresses, and which pages each stands for. */
const isTop = (p: string) => p === '/' || p === '/chats' || p === '/search' || p === '/settings'
function placeOf(path: string): string | null {
    if (path === '/' || path === '/search' || path === '/settings') return path
    if (path === '/chats' || path.startsWith('/chat/')) return '/chats'
    // Recently added's See all is Home's.
    if (path === '/recent') return '/'
    return null
}

/**
 * A Samsung TV hands a page its media and colour keys only once asked for each, and the TMPlayer
 * wrapper's own request is lost when it opens this page, so the page asks again. Names Tizen does
 * not know on a given set are skipped, and anywhere else this does nothing.
 */
function samsungKeys() {
    const input = (window as { tizen?: { tvinputdevice?: { getSupportedKeys(): { name: string }[]; registerKeyBatch(names: string[]): void } } }).tizen?.tvinputdevice
    if (!input) return
    try {
        const known = new Set(input.getSupportedKeys().map((k) => k.name))
        const wanted = ['MediaPlayPause', 'MediaPlay', 'MediaPause', 'MediaStop', 'MediaFastForward', 'MediaRewind', 'ColorF0Red', 'ColorF1Green', 'ColorF2Yellow', 'ColorF3Blue']
        input.registerKeyBatch(wanted.filter((k) => known.has(k)))
    } catch (e) {
        console.warn('[tv] media keys not registered', e)
    }
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
    onMount(samsungKeys)
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
 * Until CP105's thin wrapper exists, a TV reaches this page through the TV's own browser, chrome
 * and all, so the bar carries its own fullscreen toggle (not the player's, which already has one
 * for playback): the Fullscreen API on the whole page, reflecting the real state back
 * (fullscreenchange fires on a remote's Back too, not only this button) rather than just flipping
 * a local flag. Hidden outright where the browser has no Fullscreen API, or blocks it, so there is
 * never a dead button on screen.
 */
function TvFullscreenToggle() {
    const supported = typeof document !== 'undefined' && document.fullscreenEnabled
    const [on, setOn] = createSignal(!!document.fullscreenElement)
    const sync = () => setOn(!!document.fullscreenElement)
    onMount(() => {
        document.addEventListener('fullscreenchange', sync)
        onCleanup(() => document.removeEventListener('fullscreenchange', sync))
    })
    const toggle = () => {
        if (document.fullscreenElement) {
            document.exitFullscreen().catch(() => {})
        } else {
            document.documentElement.requestFullscreen?.({ navigationUI: 'hide' }).catch(() => {})
        }
    }
    return (
        <Show when={supported}>
            <button type="button" class="tv-fullscreen" onClick={toggle}>
                <Icon icon={on() ? ArrowShrinkIcon : ArrowExpandIcon} size={20} />
                <span>{on() ? t('player.exit_fullscreen') : t('player.fullscreen')}</span>
            </button>
        </Show>
    )
}

/**
 * The bar, as TvTopBar draws it: the account's picture at the start (Settings, as the web's "You"
 * opens), then Search, Home and Chats, and the app's mark and name at the end, which take no
 * focus. The page shown is marked by its filled icon, or the picture by a thin ring.
 */
export function TvBar() {
    const location = useLocation()
    const place = () => placeOf(location.pathname)
    const initial = () => initialOf(me()?.name)
    // The first word of the name at rest beside the picture, as the app's bar writes it (WP-01).
    const firstName = () => (me()?.name ?? '').trim().split(/\s+/)[0] ?? ''
    const label = () => (me()?.name?.trim() ? `${me()?.name?.trim()}, ${t('nav.account_menu')}` : t('nav.account'))
    const [scrolled, setScrolled] = createSignal(scrollY > 0)
    // The account's menu (WP-02): the picture opens it with OK; its header says whose account it is.
    const [menuOpen, setMenuOpen] = createSignal(false)
    // OK on the picture while the app lock is on opens nothing: it says why for a few seconds, read aloud as a status.
    const [lockedHint, setLockedHint] = createSignal(false)
    let hintTimer: number | undefined
    onCleanup(() => clearTimeout(hintTimer))
    const onPicture = () => {
        if (locked()) {
            setLockedHint(true)
            clearTimeout(hintTimer)
            hintTimer = window.setTimeout(() => setLockedHint(false), 4000)
            return
        }
        setMenuOpen(!menuOpen())
    }
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
                <div class="tv-account">
                    <button
                        type="button"
                        class="tv-picture"
                        aria-haspopup="menu"
                        aria-expanded={menuOpen()}
                        aria-label={label()}
                        title={label()}
                        aria-current={place() === '/settings' ? 'page' : undefined}
                        onClick={onPicture}
                        style={initial() ? { background: nameArt(me()?.name), color: '#fff' } : undefined}
                    >
                        <Show when={initial()} fallback={<span class="nav-picture-blank" />}>
                            {initial()}
                        </Show>
                    </button>
                    <span class="tv-lock-hint" role="status" aria-live="polite">
                        {lockedHint() ? t('profiles.unlock_first') : ''}
                    </span>
                    {/* Read once, in the picture's label: the chip is for the eye. */}
                    <Show when={firstName() && !lockedHint()}>
                        <span class="tv-picture-name" aria-hidden="true">
                            {firstName()}
                        </span>
                    </Show>
                    <Show when={menuOpen()}>
                        <TvAccountMenu onClose={() => setMenuOpen(false)} />
                    </Show>
                </div>
                <nav class="tv-tabs" aria-label="TMPlayer">
                    <For each={TABS}>
                        {(tab) => (
                            <A class="tv-tab" href={tab.href} aria-current={place() === tab.href ? 'page' : undefined}>
                                <Icon icon={tab.icon} size={20} selected={place() === tab.href} />
                                <span>{tab.label()}</span>
                            </A>
                        )}
                    </For>
                </nav>
                {/* The way into Choose chats, on Chats only (and in Settings), not on Home. */}
                <Show when={location.pathname === '/chats'}>
                    <ChooseChatsButton class="tv-choose" size={20} />
                </Show>
                <TvFullscreenToggle />
                <div class="tv-brand" aria-hidden="true">
                    <img src="/icon.svg" alt="" width="40" height="40" />
                    <span>TMPlayer</span>
                </div>
            </div>
        </header>
    )
}

/**
 * The account's menu on the TV bar (the app's TvMenu with its header, WP-02): the picture, the name
 * and the @username, then Add account (one profile) or Switch profile (two or more) and Sign out,
 * quiet at rest and red only under the remote, then Settings. It is a `role=menu` the remote's focus
 * map keeps the arrows inside (focus.ts `scope`), the first button has the remote on open, and Back
 * closes it onto the picture. Sign out asks first with Cancel focused (askSignOut) and the menu
 * stays under the question, so Cancel and Back put the remote back on Sign out.
 */
function TvAccountMenu(props: { onClose: () => void }) {
    let menu!: HTMLDivElement
    const initial = () => initialOf(me()?.name)
    const title = () => me()?.name?.trim() || t('nav.account')
    onMount(() => {
        // The bar's other items take no key while the menu is up; the page is left alone (focus.ts keeps the arrows inside the menu),
        // so a row that opens Settings never lands on an inert page.
        const held: Element[] = []
        for (const el of document.querySelectorAll('.tv-tabs, .tv-choose, .tv-fullscreen')) {
            if (!el.hasAttribute('inert')) (el.setAttribute('inert', ''), held.push(el))
        }
        onCleanup(() => held.forEach((el) => el.removeAttribute('inert')))
        menu.querySelector<HTMLElement>('button')?.focus()
    })
    const then = (run: () => void) => () => {
        props.onClose()
        run()
    }
    return (
        <div ref={menu} class="tv-menu" role="menu" aria-label={title()}>
            <div class="tv-menu-head">
                <span class="tv-menu-picture" aria-hidden="true" style={initial() ? { background: nameArt(me()?.name), color: '#fff' } : undefined}>
                    {initial()}
                </span>
                <div class="tv-menu-who">
                    <strong>{title()}</strong>
                    <Show when={me()?.username}>
                        <span>@{me()?.username}</span>
                    </Show>
                </div>
            </div>
            <div class="tv-menu-buttons">
                <button type="button" class="tv-menu-button" role="menuitem" onClick={then(() => (many() ? openPicker() : void addAccount()))}>
                    {many() ? t('accounts.switch') : t('accounts.add')}
                </button>
                {/* The menu stays under the question: Cancel puts the remote back here. */}
                <button type="button" class="tv-menu-button tv-menu-signout" role="menuitem" onClick={() => void askSignOut().then((yes) => yes && props.onClose())}>
                    {t('settings.sign_out_confirm')}
                </button>
            </div>
            <A class="tv-menu-row" role="menuitem" href="/settings" onClick={props.onClose}>
                {t('nav.settings')}
            </A>
            {/* The version and the update state, last; OK copies them for support (WP-104). */}
            <VersionFooter look="tv" />
        </div>
    )
}

/**
 * The carousel's page, kept while Home is away (a video's page opened from Details), so Back
 * finds the page it left and the remote on Details, as the app's carousel keeps its own.
 */
const [heroPage, setHeroPage] = createSignal<string | null>(null)

/** How long a page of the carousel is up before it moves on. */
const ADVANCE_MS = 7_000
/** How long a page is up before the next one's answers and logo are asked for, so a turn finds them kept. */
const AHEAD_MS = 1_500

/**
 * Home's carousel on a TV (TvHomeCarousel): the newest five in Recently added, one picture at a
 * time across the whole page and up behind the bar, its name and facts, Play and Details. Play and
 * Details stay put while the pages change under them; Left on Play goes back a page, Right on
 * Details goes on one. Both are the same plain bright pill, and the remote on one swaps its fill
 * to the focus fill, nothing more (no scale, no ring), so the mark holds with reduced motion and
 * in the dark theme alike (HX42). It moves on by itself only while the remote is elsewhere, the tab
 * is in sight, motion is allowed and a key was pressed in the last minute, so a TV left on Home is
 * not drawing a new picture every few seconds for hours, nor while it is scrolled out of sight
 * under the rows. It has its size from the first frame.
 */
export function TvHero(props: { rows: HomeRow[] | undefined }) {
    /** null while Recently added is still asked for; empty when there is nothing to show. */
    const picks = useFeatured(() => props.rows)
    const [key, setKey] = [heroPage, setHeroPage]
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
    // In sight: the rows scroll the carousel away under the bar, and a page nobody sees neither
    // turns nor pulses its logo's placeholder. A TV engine with no IntersectionObserver keeps the
    // pages turning as before and draws the placeholder still.
    const watched = typeof IntersectionObserver === 'function'
    const [inSight, setInSight] = createSignal(true)
    const observe = (el: HTMLElement) => {
        if (!watched) return
        // The strip under the fixed bar is not in sight: the bar's own height is taken off the top
        // (its 87 dp where it is not drawn yet).
        const bar = document.querySelector('.tv-bar')?.getBoundingClientRect().height || 87 * Math.min(innerWidth / 960, innerHeight / 540)
        const io = new IntersectionObserver(
            (seen) => {
                const last = seen[seen.length - 1]
                if (last) setInSight(last.isIntersecting)
            },
            { rootMargin: `-${Math.round(bar)}px 0px 0px 0px` },
        )
        io.observe(el)
        onCleanup(() => io.disconnect())
    }
    createEffect(() => {
        const n = picks()?.length ?? 0
        if (n < 2 || still || resting() || focusedIn() || !seen() || !inSight()) return
        index()
        const timer = window.setTimeout(() => go(index() + 1), ADVANCE_MS)
        onCleanup(() => clearTimeout(timer))
    })

    // The page's picture as the app's TvFeaturedArt picks it (BackdropSource): the title's wide
    // picture from TMDB (every page has one, the carousel takes no page without), its light copy
    // first; the video's own frame from Telegram until then, or with posters off; and the title's
    // cover gradient under both, so a page is never a blank dark area while they come or when
    // neither does.
    const [wide, setWide] = createSignal<string | null>(null)
    createEffect(() => {
        const it = pick()?.item
        const ask = it ? askOf(it) : null
        setWide(null)
        if (!ask || !settings().posters) return
        onCleanup(loadBackdropLightFirst(ask, setWide))
    })
    const [frame, setFrame] = createSignal<string | null>(null)
    createEffect(() => {
        const thumb = pick()?.item.thumb
        let live = true
        setFrame(null)
        if (!thumb) return
        loadThumb(thumb).then(
            (url) => live && setFrame(url),
            () => live && setFrame(null),
        )
        onCleanup(() => (live = false))
    })
    const src = () => wide() ?? frame()

    // The next page's answers and logo are asked for ahead (PrefetchTitleLogos), only while this
    // one is up and in sight, so a turn finds them in the cache and draws the logo at once.
    createEffect(() => {
        const list = picks()
        const next = list && list.length > 1 ? list[(index() + 1) % list.length] : null
        const ask = next && settings().posters && inSight() ? askOf(next.item) : null
        if (!ask) return
        const timer = window.setTimeout(() => {
            answer(ask)
                .then((found) => (found.match && found.id && found.kind ? loadExtras(found, null) : null))
                .then((x) => (x?.found ? loadLogo(x) : null))
                .catch(() => {})
        }, AHEAD_MS)
        onCleanup(() => clearTimeout(timer))
    })

    const progressOf = useProgress()
    const card = () => {
        const p = pick()
        if (!p) return null
        const c = tileCard(p, true)
        return { ...c, title: pageTitle(p, c.title) }
    }
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
            <section
                ref={observe}
                class="tv-hero"
                classList={{ 'in-sight': watched && inSight() }}
                data-tv-top
                aria-roledescription="carousel"
                aria-label={t('home.row_recent')}
            >
                <Show
                    when={pick()}
                    fallback={
                        <div class="tv-hero-wait" aria-hidden="true">
                            <i class="tv-hero-wait-logo" />
                            <i class="tv-hero-wait-facts" />
                            <span class="tv-hero-wait-pills">
                                <i />
                                <i />
                            </span>
                        </div>
                    }
                >
                    <div class="tv-hero-art" classList={{ cover: !src() }} aria-hidden="true" style={src() ? undefined : { background: coverBackground(card()!.title) }}>
                        <Show when={src()}>
                            {/* Keyed by address: a new page's picture comes in on its own. */}
                            <For each={[src()!]}>{(url) => <img src={url} alt="" decoding="async" />}</For>
                        </Show>
                    </div>
                    {/* Keyed by the page, so each page's logo waits for its own answer (the ceiling counts from its turn). */}
                    <Show when={pick()?.key} keyed>
                        {(_key) => <TvHeroWords tile={pick()!} title={card()!.title} />}
                    </Show>
                    <div
                        class="tv-hero-actions"
                        onFocusIn={() => {
                            setFocusedIn(true)
                            // The page the remote is on is kept by its key, so Home come back to shows it again.
                            if (key() !== pick()?.key) setKey(pick()?.key ?? null)
                        }}
                        onFocusOut={() => setFocusedIn(false)}
                    >
                        <A
                            class="tv-hero-play"
                            data-tv-first
                            data-tv-key="hero-play"
                            href={watchPath(pick()!.item)}
                            state={{ video: pick()!.item }}
                            ref={turn('left', () => index() > 0, () => index() - 1)}
                        >
                            <Icon icon={PlayIcon} size={26} />
                            <span>{pagePlayLabel(pick()!, resumed())}</span>
                        </A>
                        <A
                            class="tv-hero-pill"
                            data-tv-key="hero-details"
                            href={detailPath(pick()!.item)}
                            state={{ video: pick()!.item }}
                            ref={turn('right', () => (picks()?.length ?? 0) > 1, () => index() + 1)}
                        >
                            <Icon icon={InformationCircleIcon} size={22} />
                            <span>{t('detail.open_details')}</span>
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

/** A score the worker gave, only where it is a number out of ten. */
const scoreOf = (x: Extras | null): number | null => {
    const r = x?.rating
    return typeof r === 'number' && Number.isFinite(r) && r > 0 && r <= 10 ? r : null
}
const countOf = (n: unknown): number | null => (typeof n === 'number' && Number.isFinite(n) && n > 0 ? n : null)

/**
 * One page's words over Play and Details (TvHomeCarousel's column): the title's logo where TMDB has
 * one, in a 340 by 56 dp box (TV_LOGO_MAX_*), the name in type until it is clear there is none;
 * the facts line as the apps write it (kind, year, two genres, a film's length or a show's
 * seasons); and the score with its site's mark. Lookups off or no question to ask: the name at
 * once, no placeholder. What the worker answers is checked before it is drawn.
 */
function TvHeroWords(props: { tile: Tile; title: string }) {
    const [found, setFound] = createSignal<Answer | null>(null)
    const [extras, setExtras] = createSignal<Extras | null>(null)
    const [pending, setPending] = createSignal(false)
    createEffect(() => {
        const ask = askOf(props.tile.item)
        const on = !!ask && settings().posters
        setFound(null)
        setExtras(null)
        setPending(on)
        if (!on) return
        let live = true
        answer(ask!)
            .then((a) => {
                const usable = !!a && a.match === true && typeof a.id === 'string' && (a.kind === 'film' || a.kind === 'show')
                if (live && usable) setFound(a)
                return usable ? loadExtras(a, null) : null
            })
            .then((x) => live && setExtras(x && x.found === true ? x : null), () => {})
            .finally(() => live && setPending(false))
        onCleanup(() => (live = false))
    })
    const facts = () => {
        const f = found()
        const x = extras()
        const genres = Array.isArray(x?.genres) ? x!.genres!.filter((g): g is string => typeof g === 'string') : null
        return featuredFactsLine(
            {
                isShow: (f?.kind ?? props.tile.kind) === 'show',
                year: countOf(f?.year),
                genres,
                runtimeMin: countOf(x?.runtime),
                durationSec: props.tile.item.duration,
                seasons: countOf(x?.seasons),
                chatSeasons: props.tile.seasons,
            },
            { film: t('home.featured_film'), series: t('home.featured_series'), length: shortLength, seasons: (count) => t('metadata.seasons', { count }) },
        )
    }
    // The anime provider answers for a fansub name; its score says so, with its name for a mark.
    const site = () => (found()?.source === 'anilist' ? 'AniList' : 'TMDB')
    const score = () => scoreOf(extras())
    return (
        <div class="tv-hero-text">
            <TitleLogo level={2} class="tv-hero-title" name={props.title} extras={extras()} pending={pending()} />
            <p class="tv-hero-facts">{facts()}</p>
            {/* The score's line is always there, empty until the extras come, so the logo and facts above it never jump. */}
            <p
                class="tv-hero-score"
                classList={{ empty: !score() }}
                role={score() ? 'img' : undefined}
                aria-hidden={score() ? undefined : 'true'}
                aria-label={score() ? t('metadata.score_out_of_ten', { site: site(), score: score()! }) : undefined}
            >
                <Show when={score()}>
                    {(value) => (
                        <>
                            <Show when={site() === 'TMDB'} fallback={<span class="tv-hero-site">{site()}</span>}>
                                <TmdbMark height={14} />
                            </Show>
                            <span>{value().toFixed(1)}</span>
                        </>
                    )}
                </Show>
            </p>
        </div>
    )
}
