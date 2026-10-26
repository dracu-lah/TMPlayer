import { createEffect, createMemo, createSignal, For, onCleanup, onMount, Show } from 'solid-js'
import { A } from '@solidjs/router'
import { InformationCircleIcon, PlayIcon } from '@hugeicons/core-free-icons'
import { t } from '../i18n'
import { Icon } from '../ui/Icon'
import { loadThumb } from './thumbs'
import { detailPath, tileCard, useProgress, watchPath } from './VideoList'
import type { HomeRow, Tile } from '../tg/types'

/** How long a page is up before the next (FeaturedCarousel), and how many pages there are. */
const ADVANCE_MS = 7_000
const PAGES = 5
/** Nobody has touched the page for this long: the carousel stops on the page it is on. */
const IDLE_MS = 60_000

/**
 * Home's carousel on a phone and a desktop (FeaturedCarousel): the newest five in Recently added,
 * one picture across the top of Home with the name, its facts, Play (or Resume) as the bright
 * pill with Details beside it, and the page dots. A swipe or the dots change the page; it moves
 * on by itself only while the tab is in sight, the pointer is elsewhere, motion is allowed and
 * the page was touched in the last minute, so a Home left open stops drawing. Its height is held
 * from the first frame, so the rows under it never move when it fills.
 */
export function Hero(props: { rows: HomeRow[] | undefined }) {
    const picks = createMemo<Tile[] | null>(() => {
        if (!props.rows) return null
        const recent = props.rows.find((r) => r.kind === 'recent')
        if (!recent) return []
        return recent.loaded ? recent.tiles.slice(0, PAGES) : null
    })
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
    const resumed = () => (pick() ? (progressOf(pick()!.item) ?? 0) > 0 : false)

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
                class="hero"
                aria-roledescription="carousel"
                aria-label={t('home.row_recent')}
                onPointerEnter={(e) => e.pointerType === 'mouse' && setOver(true)}
                onPointerLeave={() => setOver(false)}
                onPointerDown={down}
                onPointerUp={up}
            >
                <Show when={pick()} fallback={<div class="hero-wait" aria-hidden="true" />}>
                    <div class="hero-art" aria-hidden="true">
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
                        <h2 class="hero-title">{card()!.title}</h2>
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
