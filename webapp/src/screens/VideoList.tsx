import { createEffect, createResource, createSignal, onCleanup, onMount, Show } from 'solid-js'
import { A } from '@solidjs/router'
import { resumes, settings } from '../store'
import { loadThumb } from './thumbs'
import { loadPoster } from './posters'
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
        loadPoster(ask).then(
            (url) => live && setPicture(url),
            () => {},
        )
        onCleanup(() => (live = false))
    })
    return (
        <div class="card-art" classList={{ posted: !!picture() }} ref={el} title={picture() ? props.title : undefined}>
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

/** One portrait card, which opens the video's page (or plays it, from Continue watching). */
export function Card(props: { card: CardData; progress?: number; replace?: boolean; direct?: boolean }) {
    return (
        <A
            class="card"
            href={cardPath(props.card.item, props.direct)}
            state={{ video: props.card.item }}
            replace={props.replace}
            onPointerEnter={() => intent(props.card.item)}
            onPointerLeave={() => clearTimeout(hoverTimer)}
            onFocus={() => intent(props.card.item)}
            // A touch may be the start of a scroll: the watch page warms the file once it opens.
            onPointerDown={(e: PointerEvent) => e.pointerType === 'mouse' && warmFile(props.card.item)}
        >
            <CardArt thumb={props.card.item.thumb} title={props.card.title} poster={props.card.item.poster} progress={props.progress} />
            <div class="card-facts">{props.card.facts}</div>
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
            {(e) => (isSponsored(e) ? <SponsoredCard item={e} /> : <Card card={e} progress={progress(e.item)} />)}
        </VirtualGrid>
    )
}

/** "3 videos hidden by the size limits. Change the minimum and maximum size in Settings." */
export function HiddenBySize(props: { count: number }) {
    return (
        <Show when={props.count}>
            <p class="notice muted small">
                {t('browse.hidden_by_size', { count: props.count })}{' '}
                <A class="inline-link" href="/settings#size">{t('browse.size_limits_in_settings')}</A>
            </p>
        </Show>
    )
}
