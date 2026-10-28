import { createEffect, createSignal, For, onCleanup, onMount, Show } from 'solid-js'
import { A } from '@solidjs/router'
import { InformationCircleIcon, PlayIcon } from '@hugeicons/core-free-icons'
import { t } from '../i18n'
import { Icon } from '../ui/Icon'
import { answer, loadBackdropLightFirst, loadExtras, type Extras } from './posters'
import { settings } from '../store'
import { TitleLogo } from '../ui/TitleLogo'
import { coverBackground } from './cover'
import { useFeatured } from './featured'
import { detailPath, tileCard, useProgress, watchPath } from './VideoList'
import type { HomeRow } from '../tg/types'

/** How long a page is up before the next (FeaturedCarousel), and how many pages there are. */
const ADVANCE_MS = 7_000
/** Nobody has touched the page for this long: the carousel stops on the page it is on. */
const IDLE_MS = 60_000
/** A touchpad's sideways travel that turns the page, and the quiet that ends one gesture. */
const WHEEL_TURN_PX = 60
const WHEEL_QUIET_MS = 220

/**
 * Home's carousel on a phone and a desktop (FeaturedCarousel): the newest five in Recently added,
 * one picture across the top of Home with the name, its facts, Play (or Resume) as the bright
 * pill with Details beside it, and the page dots. A swipe or the dots change the page; it moves
 * on by itself only while the tab is in sight, the pointer is elsewhere, motion is allowed and
 * the page was touched in the last minute, so a Home left open stops drawing. Its height is held
 * from the first frame, so the rows under it never move when it fills.
 */
export function Hero(props: { rows: HomeRow[] | undefined }) {
    const picks = useFeatured(() => props.rows)
    const [index, setIndex] = createSignal(0)
    const pick = () => picks()?.[Math.min(index(), (picks()?.length ?? 1) - 1)] ?? null
    const go = (i: number) => {
        const n = picks()?.length ?? 0
        if (n) setIndex(((i % n) + n) % n)
    }

    // The clock: one timer per page, stopped while anything says so.
    const [seen, setSeen] = createSignal(!document.hidden)
    const [over, setOver] = createSignal(false)
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
    createEffect(() => {
        const n = picks()?.length ?? 0
        if (n < 2 || still || over() || !seen() || !awake()) return
        index()
        const timer = window.setTimeout(() => {
            if (performance.now() - lastTouch() > IDLE_MS) return setAwake(false)
            go(index() + 1)
        }, ADVANCE_MS)
        onCleanup(() => clearTimeout(timer))
    })

    // The title's wide picture, the light copy at once and the full one after it; the cover until then.
    const [src, setSrc] = createSignal<string | null>(null)
    createEffect(() => {
        const ask = pick()?.item.poster
        setSrc(null)
        if (!ask) return
        const stop = loadBackdropLightFirst(ask, setSrc)
        onCleanup(stop)
    })

    // The title's logo, by way of its extras (the apps' carousel draws the logo, not the name).
    const [extras, setExtras] = createSignal<Extras | null>(null)
    const [pending, setPending] = createSignal(false)
    createEffect(() => {
        const ask = pick()?.item.poster
        setExtras(null)
        setPending(!!ask && settings().posters)
        if (!ask || !settings().posters) return
        let live = true
        answer(ask)
            .then((found) => (found.match ? loadExtras(found, null) : null))
            .then((x) => live && setExtras(x?.found ? x : null), () => {})
            .finally(() => live && setPending(false))
        onCleanup(() => (live = false))
    })

    const progressOf = useProgress()
    const card = () => (pick() ? tileCard(pick()!, true) : null)
    const resumed = () => (pick() ? (progressOf(pick()!.item) ?? 0) > 0 : false)

    // A laptop's touchpad swipes with two fingers as a sideways wheel, not a pointer drag: taken
    // here, it turns the page once per gesture instead of reaching the browser, which read it as
    // Back and left Home. A mostly upright scroll is left to the page.
    let section!: HTMLElement
    let swept = 0
    let gestureTimer = 0
    let turned = false
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
        const rtl = getComputedStyle(section).direction === 'rtl'
        go(index() + ((swept > 0) !== rtl ? 1 : -1))
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

    // A sideways swipe turns the page.
    let startX = 0
    let startY = 0
    const down = (e: PointerEvent) => {
        startX = e.clientX
        startY = e.clientY
    }
    const up = (e: PointerEvent) => {
        const dx = e.clientX - startX
        if (Math.abs(dx) > 48 && Math.abs(dx) > Math.abs(e.clientY - startY) * 1.5) go(index() + (dx < 0 ? 1 : -1))
    }

    return (
        <Show when={picks()?.length !== 0}>
            <section
                ref={hold}
                class="hero"
                aria-roledescription="carousel"
                aria-label={t('home.row_recent')}
                onPointerEnter={(e) => e.pointerType === 'mouse' && setOver(true)}
                onPointerLeave={() => setOver(false)}
                onPointerDown={down}
                onPointerUp={up}
            >
                <Show when={pick()} fallback={<div class="hero-wait" aria-hidden="true" />}>
                    <div class="hero-art" aria-hidden="true" style={{ background: src() ? undefined : coverBackground(card()!.title) }}>
                        <Show when={src()}>
                            <For each={[src()!]}>
                                {(url) => (
                                    <>
                                        <img class="hero-fill" src={url} alt="" decoding="async" />
                                        <img class="hero-frame" src={url} alt="" decoding="async" />
                                    </>
                                )}
                            </For>
                        </Show>
                        <div class="hero-fade" />
                    </div>
                    <div class="hero-text">
                        <Show when={pick()} keyed>
                            {/* Keyed by the page, so each page's logo waits on its own clock. */}
                            {(_page) => <TitleLogo level={2} class="hero-title" name={card()!.title} extras={extras()} pending={pending()} />}
                        </Show>
                        <p class="hero-facts">{card()!.facts}</p>
                        <div class="hero-actions">
                            <A class="hero-play" href={watchPath(pick()!.item)} state={{ video: pick()!.item }}>
                                <Icon icon={PlayIcon} size={22} />
                                {resumed() ? t('common.resume') : t('common.play')}
                            </A>
                            <A class="hero-info" href={detailPath(pick()!.item)} state={{ video: pick()!.item }} aria-label={t('detail.open_details')} title={t('detail.open_details')}>
                                <Icon icon={InformationCircleIcon} size={22} />
                            </A>
                        </div>
                    </div>
                    <Show when={(picks()?.length ?? 0) > 1}>
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
                    </Show>
                </Show>
            </section>
        </Show>
    )
}
