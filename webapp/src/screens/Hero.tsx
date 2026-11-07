import { createEffect, createSignal, For, on, onCleanup, onMount, Show } from 'solid-js'
import { A, useNavigate } from '@solidjs/router'
import { ArrowLeft01Icon, ArrowRight01Icon, InformationCircleIcon, PlayIcon } from '@hugeicons/core-free-icons'
import { t } from '../i18n'
import { Icon } from '../ui/Icon'
import { answer, askOf, loadBackdropLightFirst, loadExtras, loadLogo, type Answer, type Extras } from './posters'
import { settings } from '../store'
import { TitleLogo } from '../ui/TitleLogo'
import { TmdbMark } from '../ui/TmdbMark'
import { coverBackground } from './cover'
import { pagePlayLabel, pageTitle, useFeatured } from './featured'
import { featuredFactsLine } from './featuredFacts'
import { detailPath, shortLength, tileCard, useProgress, watchPath } from './VideoList'
import type { HomeRow, Tile } from '../tg/types'

/** How long a page is up before the next (FeaturedCarousel), and how many pages there are. */
const ADVANCE_MS = 7_000
/** Nobody has touched the page for this long: the carousel stops on the page it is on. */
const IDLE_MS = 60_000
/** A touchpad's sideways travel that turns the page, and the quiet that ends one gesture. */
const WHEEL_TURN_PX = 60
const WHEEL_QUIET_MS = 220
/** How long the arrows stay up after a touch: a touch screen has no hover to show them with. */
const TOUCH_ARROWS_MS = 4_000
/** How long a page's words and picture stay under the next as it cross-fades in (the CSS's 400 ms and a little more). */
const FADE_MS = 520

/** The page that was up when Home was left, so a Back to Home lands on it (the apps keep the page's key). */
let lastKey: string | null = null

/**
 * Home's carousel on a phone and a desktop (FeaturedCarousel): the newest five in Recently added,
 * one picture across the top of Home with the name (its logo where TMDB has one), a line of facts,
 * the overview, Play (or Resume) as the bright pill with Details beside it, and the page dots with
 * an arrow either side. One picture cross-fades under the pages and the words cross-fade in place.
 * The arrows show while the pointer is over the carousel, the keyboard is in it, and for a while
 * after a touch; Tab reaches the carousel (a ring round it), Left and Right turn the page, Enter
 * opens its details and Space plays it. A drag, a swipe and a touchpad's sideways scroll turn one
 * page a gesture. It moves on by itself only while the tab is in sight, the pointer and the
 * keyboard are elsewhere, nothing is held or touched, motion is allowed and the page was touched
 * in the last minute, so a Home left open stops drawing. Its height is held from the first frame
 * (the skeleton is as tall), so the rows under it never move when it fills.
 */
export function Hero(props: { rows: HomeRow[] | undefined }) {
    const picks = useFeatured(() => props.rows)
    const navigate = useNavigate()
    const [index, setIndex] = createSignal(0)
    const pick = () => picks()?.[Math.min(index(), (picks()?.length ?? 1) - 1)] ?? null
    const go = (i: number) => {
        const n = picks()?.length ?? 0
        if (n) setIndex(((i % n) + n) % n)
    }
    // Home come back to lands on the page it was left on, while that page is still one of the pages.
    let restored = false
    createEffect(() => {
        const list = picks()
        if (!list?.length || restored) return
        restored = true
        const at = lastKey ? list.findIndex((tile) => tile.key === lastKey) : -1
        if (at > 0) setIndex(at)
    })
    createEffect(() => {
        const key = pick()?.key
        if (key && restored) lastKey = key
    })

    // The clock: one timer per page, stopped while anything says so.
    const [seen, setSeen] = createSignal(!document.hidden)
    const [over, setOver] = createSignal(false)
    const [within, setWithin] = createSignal(false)
    const [held, setHeld] = createSignal(false)
    const [touchShown, setTouchShown] = createSignal(false)
    const [lastTouch, setLastTouch] = createSignal(performance.now())
    const [awake, setAwake] = createSignal(true)
    const touched = () => {
        setLastTouch(performance.now())
        setAwake(true)
    }
    const onVisible = () => setSeen(!document.hidden)
    onMount(() => {
        document.addEventListener('visibilitychange', onVisible)
        for (const e of ['pointerdown', 'keydown', 'wheel', 'touchstart'] as const) addEventListener(e, touched, { passive: true })
    })
    onCleanup(() => {
        document.removeEventListener('visibilitychange', onVisible)
        for (const e of ['pointerdown', 'keydown', 'wheel', 'touchstart'] as const) removeEventListener(e, touched)
    })
    const still = matchMedia('(prefers-reduced-motion: reduce)').matches
    const paused = () => over() || within() || held() || touchShown()
    createEffect(() => {
        const n = picks()?.length ?? 0
        if (n < 2 || still || paused() || !seen() || !awake()) return
        index()
        const timer = window.setTimeout(() => {
            if (performance.now() - lastTouch() > IDLE_MS) return setAwake(false)
            go(index() + 1)
        }, ADVANCE_MS)
        onCleanup(() => clearTimeout(timer))
    })
    // A touch shows the arrows for a while.
    let touchTimer = 0
    const touchArrows = () => {
        setTouchShown(true)
        clearTimeout(touchTimer)
        touchTimer = window.setTimeout(() => setTouchShown(false), TOUCH_ARROWS_MS)
    }
    onCleanup(() => clearTimeout(touchTimer))

    // The pages on show: the one up and, for a moment after a turn, the one leaving, so the picture
    // and the words cross-fade in place instead of one being swapped for the other.
    const [layers, setLayers] = createSignal<{ key: string; tile: Tile }[]>([])
    let settle = 0
    createEffect(
        on(pick, (tile) => {
            if (!tile) return setLayers([])
            if (layers().at(-1)?.key === tile.key) return
            setLayers([...layers().slice(-1), { key: tile.key, tile }])
            clearTimeout(settle)
            settle = window.setTimeout(() => setLayers((l) => l.slice(-1)), still ? 0 : FADE_MS)
        }),
    )
    onCleanup(() => clearTimeout(settle))
    // The next page's logo and facts are asked for ahead, so a turn finds them in the cache.
    createEffect(() => {
        const list = picks()
        const next = list && list.length > 1 ? list[(index() + 1) % list.length] : null
        const ask = next && settings().posters ? askOf(next.item) : null
        if (!ask) return
        const timer = window.setTimeout(() => {
            answer(ask)
                .then((found) => (found.match ? loadExtras(found, null) : null))
                .then((x) => (x?.found ? loadLogo(x) : null))
                .catch(() => {})
        }, 1500)
        onCleanup(() => clearTimeout(timer))
    })

    const progressOf = useProgress()

    // A laptop's touchpad swipes with two fingers as a sideways wheel, not a pointer drag: taken
    // here, it turns the page once per gesture instead of reaching the browser, which read it as
    // Back and left Home. A mostly upright scroll is left to the page.
    let section!: HTMLElement
    let swept = 0
    let gestureTimer = 0
    let turned = false
    const rtl = () => getComputedStyle(section).direction === 'rtl'
    const wheel = (e: WheelEvent) => {
        if (Math.abs(e.deltaX) <= Math.abs(e.deltaY) || e.ctrlKey) return
        e.preventDefault()
        touched()
        clearTimeout(gestureTimer)
        // The gesture ends once the wheel has been quiet a moment (its inertia included).
        gestureTimer = window.setTimeout(() => {
            swept = 0
            turned = false
        }, WHEEL_QUIET_MS)
        if (turned) return
        swept += e.deltaMode === 1 ? e.deltaX * 16 : e.deltaX
        if (Math.abs(swept) < WHEEL_TURN_PX) return
        turned = true
        go(index() + ((swept > 0) !== rtl() ? 1 : -1))
    }
    // Non-passive, so the browser's Back gesture can be held off; set when the section is drawn.
    const hold = (el: HTMLElement) => {
        section = el
        el.addEventListener('wheel', wheel, { passive: false })
    }
    onCleanup(() => {
        section?.removeEventListener('wheel', wheel)
        clearTimeout(gestureTimer)
    })

    // A sideways swipe or drag turns the page.
    let startX = 0
    let startY = 0
    const down = (e: PointerEvent) => {
        startX = e.clientX
        startY = e.clientY
        setHeld(true)
        if (e.pointerType === 'touch') touchArrows()
    }
    const up = (e: PointerEvent) => {
        setHeld(false)
        const dx = e.clientX - startX
        if (e.type === 'pointerup' && Math.abs(dx) > 48 && Math.abs(dx) > Math.abs(e.clientY - startY) * 1.5) go(index() + (dx < 0 ? 1 : -1))
    }

    // Left and Right turn the page once the carousel has the keyboard, wherever in it; Enter on the
    // carousel itself opens the page's details and Space plays it, as on Home's cards.
    const key = (e: KeyboardEvent) => {
        if (e.altKey || e.ctrlKey || e.metaKey || e.shiftKey) return
        const n = picks()?.length ?? 0
        const tile = pick()
        if ((e.key === 'ArrowLeft' || e.key === 'ArrowRight') && n > 1) {
            e.preventDefault()
            go(index() + ((e.key === 'ArrowRight') !== rtl() ? 1 : -1))
            // A button left behind on the old page would keep the keys off the carousel.
            if (document.activeElement !== section) section.focus({ preventScroll: true })
        } else if (e.target === section && tile && (e.key === 'Enter' || e.key === ' ')) {
            e.preventDefault()
            navigate(e.key === 'Enter' ? detailPath(tile.item) : watchPath(tile.item), { state: { video: tile.item } })
        }
    }

    return (
        <Show when={picks()?.length !== 0}>
            <section
                ref={hold}
                class="hero"
                tabindex="0"
                aria-roledescription="carousel"
                aria-label={t('home.row_recent')}
                data-arrows={(over() || within() || touchShown()) && (picks()?.length ?? 0) > 1 ? '' : undefined}
                onPointerEnter={(e) => e.pointerType === 'mouse' && setOver(true)}
                onPointerLeave={(e) => {
                    setOver(false)
                    if (e.pointerType === 'mouse') setHeld(false)
                }}
                onPointerDown={down}
                onPointerUp={up}
                onPointerCancel={up}
                onFocusIn={() => setWithin(true)}
                onFocusOut={(e) => !section.contains(e.relatedTarget as Node | null) && setWithin(false)}
                onKeyDown={key}
            >
                <Show
                    when={pick()}
                    fallback={
                        <div class="hero-wait" aria-hidden="true">
                            <div class="hero-text hero-skeleton">
                                <i />
                                <i />
                                <i />
                            </div>
                        </div>
                    }
                >
                    <div class="hero-art" aria-hidden="true">
                        <For each={layers()}>{(l) => <HeroPicture tile={l.tile} />}</For>
                        <div class="hero-fade" />
                    </div>
                    <For each={layers()}>
                        {(l) => <HeroWords tile={l.tile} leaving={l.key !== pick()?.key} swapping={layers().length > 1} progressOf={progressOf} />}
                    </For>
                    <Show when={(picks()?.length ?? 0) > 1}>
                        <div class="hero-nav">
                            <span class="hero-arrow-slot">
                                <button type="button" class="round-button hero-arrow" tabindex="-1" aria-label={t('home.row_scroll_back')} title={t('home.row_scroll_back')} onClick={() => go(index() - 1)}>
                                    <Icon icon={ArrowLeft01Icon} size={22} />
                                </button>
                            </span>
                            <div class="hero-dots" role="tablist" aria-label={t('home.featured_page', { index: index() + 1, count: picks()!.length })}>
                                <For each={picks()!}>
                                    {(_, i) => (
                                        <button
                                            type="button"
                                            role="tab"
                                            aria-selected={i() === index()}
                                            aria-label={t('home.featured_page', { index: i() + 1, count: picks()!.length })}
                                            classList={{ on: i() === index() }}
                                            onClick={() => go(i())}
                                        />
                                    )}
                                </For>
                            </div>
                            <span class="hero-arrow-slot">
                                <button type="button" class="round-button hero-arrow" tabindex="-1" aria-label={t('home.row_scroll_on')} title={t('home.row_scroll_on')} onClick={() => go(index() + 1)}>
                                    <Icon icon={ArrowRight01Icon} size={22} />
                                </button>
                            </span>
                        </div>
                    </Show>
                </Show>
            </section>
        </Show>
    )
}

/** One page's picture: the title's wide picture, the light copy at once and the full one after it; the cover until then. */
function HeroPicture(props: { tile: Tile }) {
    const [src, setSrc] = createSignal<string | null>(null)
    createEffect(() => {
        const ask = askOf(props.tile.item)
        if (!ask) return
        const stop = loadBackdropLightFirst(ask, setSrc)
        onCleanup(stop)
    })
    return (
        <div class="hero-pic" style={{ background: src() ? undefined : coverBackground(tileCard(props.tile, true).title) }}>
            <Show when={src()}>
                <For each={[src()!]}>
                    {(url) => (
                        <>
                            <img class="hero-fill" src={url} alt="" decoding="async" draggable={false} />
                            <img class="hero-frame" src={url} alt="" decoding="async" draggable={false} />
                        </>
                    )}
                </For>
            </Show>
        </div>
    )
}

/**
 * One page's words: its name (the logo, by way of its extras: the apps' carousel draws the logo,
 * not the name), the facts, the score and the overview, Play and Details. Each page asks for its
 * own answers, so two of them can stand over each other while the pages cross-fade.
 */
function HeroWords(props: { tile: Tile; leaving: boolean; swapping: boolean; progressOf: (v: Tile['item']) => number | undefined }) {
    const [found, setFound] = createSignal<Answer | null>(null)
    const [extras, setExtras] = createSignal<Extras | null>(null)
    const [pending, setPending] = createSignal(false)
    createEffect(() => {
        const ask = askOf(props.tile.item)
        setFound(null)
        setExtras(null)
        setPending(!!ask && settings().posters)
        if (!ask || !settings().posters) return
        let live = true
        answer(ask)
            .then((a) => {
                if (live && a.match) setFound(a)
                return a.match ? loadExtras(a, null) : null
            })
            .then((x) => live && setExtras(x?.found ? x : null), () => {})
            .finally(() => live && setPending(false))
        onCleanup(() => (live = false))
    })
    const card = () => {
        const c = tileCard(props.tile, true)
        return { ...c, title: pageTitle(props.tile, c.title) }
    }
    const resumed = () => (props.progressOf(props.tile.item) ?? 0) > 0
    const facts = () =>
        featuredFactsLine(
            {
                isShow: (found()?.kind ?? props.tile.kind) === 'show',
                year: found()?.year,
                genres: extras()?.genres,
                runtimeMin: extras()?.runtime,
                durationSec: props.tile.item.duration,
                seasons: extras()?.seasons,
                chatSeasons: props.tile.seasons,
            },
            { film: t('home.featured_film'), series: t('home.featured_series'), length: shortLength, seasons: (count) => t('metadata.seasons', { count }) },
        )
    const overview = () => found()?.overview?.trim() ?? ''
    return (
        <div class="hero-text" classList={{ leaving: props.leaving, swapping: props.swapping && !props.leaving }} inert={props.leaving} aria-hidden={props.leaving ? 'true' : undefined}>
            <TitleLogo level={2} class="hero-title" name={card().title} extras={extras()} pending={pending()} />
            <p class="hero-facts">{facts()}</p>
            <Show when={extras()?.rating}>
                {/* TMDB's score (MetaScore): its mark and the number out of ten. */}
                <p class="hero-score" role="img" aria-label={t('metadata.score_out_of_ten', { site: 'TMDB', score: extras()!.rating! })}>
                    <TmdbMark height={14} />
                    <span>{extras()!.rating!.toFixed(1)}</span>
                </p>
            </Show>
            <Show when={overview()}>
                <p class="hero-overview">{overview()}</p>
            </Show>
            <div class="hero-actions">
                <A class="hero-play" href={watchPath(props.tile.item)} state={{ video: props.tile.item }}>
                    <Icon icon={PlayIcon} size={22} />
                    {pagePlayLabel(props.tile, resumed())}
                </A>
                <A class="hero-info" href={detailPath(props.tile.item)} state={{ video: props.tile.item }} aria-label={t('detail.open_details')} title={t('detail.open_details')}>
                    <Icon icon={InformationCircleIcon} size={22} />
                </A>
            </div>
        </div>
    )
}
