import { createEffect, createResource, createSignal, onCleanup, onMount, Show } from 'solid-js'
import { A } from '@solidjs/router'
import { resumes, settings } from '../store'
import { loadThumb } from './thumbs'
import { coverBackground } from './cover'
import { loadBackdrop, loadPoster, posterSizeFor } from './posters'
import { warmDataCentres, warmFile } from '../stream/reader'
import { t } from '../i18n'
import { form } from '../form'
import { cardLayout, VirtualGrid } from '../ui/VirtualGrid'
import { placeSponsored, SponsoredCard } from './Sponsored'
import type { SponsoredItem, Tile, VideoItem } from '../tg/types'
import type { PosterAsk } from '../rules'

export function size(bytes: number): string {
    if (bytes >= 1e9) return `${(bytes / 1e9).toFixed(1)} GB`
    return `${Math.max(1, Math.round(bytes / 1e6))} MB`
}

export function length(s: number): string {
    if (!s) return ''
    const h = Math.floor(s / 3600)
    const m = Math.round((s % 3600) / 60)
    return h ? `${h} h ${m} min` : `${Math.max(1, m)} min`
}

/** SeekMath.clock: 1:05, 1:02:03. */
export function clock(seconds: number): string {
    const s = Math.max(0, Math.floor(seconds))
    const h = Math.floor(s / 3600)
    const m = Math.floor((s % 3600) / 60)
    const sec = String(s % 60).padStart(2, '0')
    return h ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`
}

/** The app's short length: 24m, 1h 52m. */
export function shortLength(seconds: number): string {
    if (!seconds) return ''
    const m = Math.max(1, Math.round(seconds / 60))
    return m >= 60 ? t('format.hours_minutes', { hours: Math.floor(m / 60), minutes: m % 60 }) : t('format.minutes', { minutes: m })
}

export const watchPath = (v: Pick<VideoItem, 'chatId' | 'messageId'>) => `/watch/${v.chatId}/${v.messageId}`
/** A video's page before it plays (the apps' detail), at `/video/<chat>/<message>`. */
export const detailPath = (v: Pick<VideoItem, 'chatId' | 'messageId'>) => `/video/${v.chatId}/${v.messageId}`
/**
 * Where a card goes: its detail page, as the apps open a card. The TV layout plays at once (the
 * web has no TV detail pane yet, so a card keeps going straight to /watch/; session 21); `direct` (Continue watching) plays at once too.
 */
const cardPath = (v: VideoItem, direct?: boolean) => (direct || form() === 'tv' ? watchPath(v) : detailPath(v))

/**
 * The art of a portrait card, as the app draws a video's: the frame across the card's width, the
 * same frame blurred to fill the 2:3 card round it, and the name on a shade at its foot. With
 * posters on (Settings), TMDB's poster for the video's title fades in over all of that when there
 * is one, as the app's PosterArt does, and the name is left to the poster (kept for screen
 * readers). A lookup that fails, or posters turned off, leaves the thumbnail.
 */
function CardArt(props: { thumb: string | null; title: string; poster: PosterAsk | null; progress?: number }) {
    let el!: HTMLDivElement
    const [src, setSrc] = createSignal<string | null>(null)
    const [seen, setSeen] = createSignal(false)
    const [picture, setPicture] = createSignal<string | null>(null)
    onMount(() => {
        const io = new IntersectionObserver((entries) => {
            if (!entries.some((e) => e.isIntersecting)) return
            io.disconnect()
            setSeen(true)
            if (props.thumb) loadThumb(props.thumb).then(setSrc, () => {})
        })
        io.observe(el)
        onCleanup(() => io.disconnect())
    })
    createEffect(() => {
        const ask = props.poster
        if (!seen() || !ask || !settings().posters) return void setPicture(null)
        let live = true
        // The size the card is drawn at, so a phone's small cards take the lighter file.
        loadPoster(ask, posterSizeFor(el.clientWidth)).then(
            (url) => live && setPicture(url),
            () => {},
        )
        onCleanup(() => (live = false))
    })
    return (
        // The desktop's cover gradient for the title under everything, so a card waiting for its
        // frame or poster, or one with neither, is never a grey box.
        <div class="card-art" classList={{ posted: !!picture() }} ref={el} title={picture() ? props.title : undefined} style={{ background: coverBackground(props.title) }}>
            <Show when={src()}>
                <img class="card-fill" src={src()!} alt="" decoding="async" />
                <img class="card-frame" src={src()!} alt="" decoding="async" />
            </Show>
            <Show when={picture()}>
                <img class="card-poster" src={picture()!} alt="" decoding="async" />
            </Show>
            <div class="card-shade" />
            <div class="card-art-title">{props.title}</div>
            <Show when={props.progress}>
                <div class="card-progress">
                    <div style={{ width: `${Math.min(100, props.progress! * 100)}%` }} />
                </div>
            </Show>
        </div>
    )
}

/** One portrait card: what it is called, what it plays and its line of facts. */
export interface CardData {
    key: string
    title: string
    item: VideoItem
    facts: string
}

const dot = '  ·  '

/** A video on its own: its chat (where cards come from several), size and length. */
export function videoCard(v: VideoItem, showChat = true): CardData {
    return { key: v.key, title: v.title, item: v, facts: [showChat ? v.chat : '', size(v.size), length(v.duration)].filter(Boolean).join(dot) }
}

/** A tile of a shelf: a show with its seasons and episodes, or a file with its parts; `showChat` where cards come from several chats. */
export function tileCard(tile: Tile, showChat = false): CardData {
    const v = tile.item
    const facts =
        tile.kind === 'show'
            ? t('series.summary', { seasons: tile.seasons, episodes: tile.episodes })
            : [
                  showChat ? v.chat : '',
                  tile.parts > 1 ? t('grid.parts_count', { count: tile.parts }) : size(v.size),
                  length(v.duration),
                  tile.copies ? t('search.copies', { count: tile.copies + 1 }) : '',
              ]
                  .filter(Boolean)
                  .join(dot)
    return { key: tile.key, title: tile.title, item: v, facts }
}

/** Where each video was left, for the line on its card. */
export function useProgress(): (v: VideoItem) => number | undefined {
    const [left] = createResource(async () => new Map((await resumes()).map((r) => [`${r.chatId}:${r.messageId}`, r])))
    return (v: VideoItem) => {
        const r = left()?.get(`${v.chatId}:${v.messageId}`)
        const total = r?.length || v.duration
        return r && total ? r.position / total : undefined
    }
}

// A card pointed at for 250 ms, focused, or pressed starts fetching its file's head and tail.
let hoverTimer = 0
const intent = (v: VideoItem) => {
    clearTimeout(hoverTimer)
    hoverTimer = window.setTimeout(() => warmFile(v), 250)
}

/** What every card's link does: warm the file on intent, keep the video it stands for for the page it opens. */
function cardLink(item: VideoItem, direct?: boolean, replace?: boolean) {
    return {
        href: cardPath(item, direct),
        state: { video: item },
        replace,
        onPointerEnter: () => intent(item),
        onPointerLeave: () => clearTimeout(hoverTimer),
        onFocus: () => intent(item),
        // A touch may be the start of a scroll: the watch page warms the file once it opens.
        onPointerDown: (e: PointerEvent) => e.pointerType === 'mouse' && warmFile(item),
    }
}

/** One portrait card, which opens the video's page (or plays it, from Continue watching). */
export function Card(props: { card: CardData; progress?: number; replace?: boolean; direct?: boolean }) {
    return (
        <A class="card" {...cardLink(props.card.item, props.direct, props.replace)}>
            <CardArt thumb={props.card.item.thumb} title={props.card.title} poster={props.card.item.poster} progress={props.progress} />
            {/* Under the card, as the apps write it (two lines kept, so the facts line up along a row). */}
            <div class="card-name">{props.card.title}</div>
            <div class="card-facts">{props.card.facts}</div>
        </A>
    )
}

/** Where a video was left: how far through, and how long is left. */
export interface Left {
    fraction: number
    seconds: number
}

/** Where each video was left, as its Continue watching card says it. */
export function useLeft(): (v: VideoItem) => Left | undefined {
    const [left] = createResource(async () => new Map((await resumes()).map((r) => [`${r.chatId}:${r.messageId}`, r])))
    return (v: VideoItem) => {
        const r = left()?.get(`${v.chatId}:${v.messageId}`)
        const total = r?.length || v.duration
        return r && total ? { fraction: Math.min(1, r.position / total), seconds: Math.max(0, total - r.position) } : undefined
    }
}

/**
 * A Continue watching card (ContinueCardArt): the picture across 16:9 under the tile's foot fade,
 * how long is left in a chip at its top end, a pill of progress along its foot, and under it the
 * name ("S1 E3  ·  Harbour Notes") over the chat and size. It plays the video at once.
 */
export function ContinueCard(props: { item: VideoItem; left?: Left }) {
    let el!: HTMLDivElement
    const [src, setSrc] = createSignal<string | null>(null)
    const [seen, setSeen] = createSignal(false)
    const [wide, setWide] = createSignal<string | null>(null)
    onMount(() => {
        const io = new IntersectionObserver((entries) => {
            if (!entries.some((e) => e.isIntersecting)) return
            io.disconnect()
            setSeen(true)
            if (props.item.thumb) loadThumb(props.item.thumb).then(setSrc, () => {})
        })
        io.observe(el)
        onCleanup(() => io.disconnect())
    })
    // The title's own wide picture where TMDB has one (OnlineArt), the video's frame until then.
    createEffect(() => {
        const ask = props.item.poster
        if (!seen() || !ask || !settings().posters) return void setWide(null)
        let live = true
        loadBackdrop(ask).then(
            (url) => live && setWide(url),
            () => {},
        )
        onCleanup(() => live && setWide(null))
    })
    const facts = () => [props.item.chat, size(props.item.size)].filter(Boolean).join(dot)
    return (
        <A class="card continue-card" {...cardLink(props.item, true)}>
            <div class="continue-art" ref={el}>
                <Show when={src()}>
                    <img class="continue-img" src={src()!} alt="" decoding="async" />
                </Show>
                <Show when={wide()}>
                    <img class="continue-img" src={wide()!} alt="" decoding="async" />
                </Show>
                <div class="continue-scrim" />
                <Show when={props.left && props.left.seconds > 0}>
                    <span class="tile-badge">{t('browse.time_left', { time: shortLength(props.left!.seconds) })}</span>
                </Show>
                <Show when={props.left}>
                    <div class="pill-progress" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow={Math.round(props.left!.fraction * 100)}>
                        <div style={{ width: `${Math.max(props.left!.fraction * 100, 3)}%` }} />
                    </div>
                </Show>
            </div>
            <div class="continue-name">{props.item.name}</div>
            <div class="card-facts">{facts()}</div>
        </A>
    )
}

/** Once a list has drawn, connect to the data centres its files live in. */
export function useWarmDataCentres(items: () => VideoItem[]) {
    let dcTimer = 0
    createEffect(() => {
        const list = items().slice(0, 20)
        clearTimeout(dcTimer)
        if (list.length) dcTimer = window.setTimeout(() => warmDataCentres(list), 1500)
    })
    onCleanup(() => clearTimeout(dcTimer))
}

const isSponsored = (e: CardData | SponsoredItem): e is SponsoredItem => !('item' in e)

/**
 * A grid of portrait cards, drawn a few rows at a time. `onEnd` asks for more near the foot. A
 * channel's sponsored messages take rows of their own, spaced as Telegram asks.
 */
export function VideoList(props: { cards: CardData[]; label?: string; onEnd?: () => void; sponsored?: SponsoredItem[]; sponsoredBetween?: number }) {
    useWarmDataCentres(() => props.cards.map((c) => c.item))
    onCleanup(() => clearTimeout(hoverTimer))
    const progress = useProgress()
    const entries = () => placeSponsored(props.cards, props.sponsored ?? [], props.sponsoredBetween ?? 0)
    return (
        <VirtualGrid items={entries()} layout={cardLayout} rowClass="cards" label={props.label} onEnd={props.onEnd} wide={isSponsored}>
            {(e) => (isSponsored(e) ? <SponsoredCard item={e} />  : <Card card={e} progress={progress(e.item)} />)}
        </VirtualGrid>
    )
}

/** "3 videos hidden by the size limits. Change the minimum and maximum size in Settings." */
export function HiddenBySize(props: { count: number }) {
    return (
        <Show when={props.count}>
            <p class="notice muted small">
                {t('browse.hidden_by_size', { count: props.count })}{' '}
                <A class="inline-link" href="/settings/library#size">{t('browse.size_limits_in_settings')}</A>
            </p>
        </Show>
    )
}
