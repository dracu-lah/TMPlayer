import { createMemo, createResource, createSignal, For, Match, onMount, Show, Switch } from 'solid-js'
import { A, useLocation, useNavigate, useParams } from '@solidjs/router'
import { Cancel01Icon, Clock01Icon, ListVideoIcon, PlayIcon, RepeatIcon, Tick02Icon } from '@hugeicons/core-free-icons'
import { api, errorText } from '../tg/rpc'
import { back } from '../session'
import { t } from '../i18n'
import { dropResume, getResume, laterIds, progress as readProgress, setLater, setWatched, settings, watchedList, watchedTick } from '../store'
import { Icon, type IconData } from '../ui/Icon'
import { StarButton } from './Chats'
import { loadThumb } from './thumbs'
import { loadPoster } from './posters'
import { Card, size, tileCard, useProgress, watchPath } from './VideoList'
import { warmFile } from '../stream/reader'
import type { ChatView, VideoItem, WatchInfo } from '../tg/types'

const dot = '  ·  '

/** SeekMath.clock. */
function clock(seconds: number): string {
    const s = Math.max(0, Math.floor(seconds))
    const h = Math.floor(s / 3600)
    const m = Math.floor((s % 3600) / 60)
    const sec = String(s % 60).padStart(2, '0')
    return h ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`
}

/** The app's short length: 24m, 1h 52m. */
function shortLength(seconds: number): string {
    if (!seconds) return ''
    const m = Math.max(1, Math.round(seconds / 60))
    return m >= 60 ? t('format.hours_minutes', { hours: Math.floor(m / 60), minutes: m % 60 }) : t('format.minutes', { minutes: m })
}

/**
 * A video's detail, as the phone's MediaDetailScreen and the desktop's WideDetail draw it: the
 * picture across the top fading into the page with the name set large on its foot, the facts on
 * one line, Play (or Resume at the place left) as the one bright pill with the everyday actions as
 * round buttons beside or under it, then the show's episodes, the file's own facts under About,
 * and more from the chat. Opened from a card with the item the card passed along; by its address
 * it asks Telegram first. Close and Back go back to where the card was.
 */
export function Detail() {
    const params = useParams<{ chatId: string; messageId: string }>()
    const location = useLocation<{ video?: VideoItem }>()
    const navigate = useNavigate()
    const ids = () => ({ chatId: Number(params.chatId), messageId: Number(params.messageId) })
    const [video] = createResource(ids, async ({ chatId, messageId }) => {
        const passed = location.state?.video
        const found = passed?.chatId === chatId && passed.messageId === messageId && (await api.known(passed.key)) ? passed : await api.video(chatId, messageId)
        if (found === null) dropResume(chatId, messageId)
        return found
    })
    const [seen] = createResource(readProgress)
    const view = (): ChatView => ({
        sort: settings().sort,
        minBytes: settings().sizeMin,
        maxBytes: settings().sizeMax,
        watched: seen()?.watched ?? [],
        started: seen()?.started ?? [],
        query: '',
    })
    const [info] = createResource(
        () => (seen.state === 'ready' ? { ...ids(), view: view() } : null),
        ({ chatId, messageId, view }) => api.watch(chatId, messageId, view).catch((e) => (console.warn('[detail]', e), null)),
    )
    const close = () => back(navigate)

    return (
        <Switch fallback={<div class="detail"><div class="detail-hero" /></div>}>
            <Match when={video.error}>
                <div class="boot">
                    <p class="error">{errorText(video.error)}</p>
                    <button class="primary" onClick={close}>{t('common.back')}</button>
                </div>
            </Match>
            <Match when={video.state === 'ready' && video() === null}>
                <div class="boot">
                    <p>{t('web.gone_from_chat')}</p>
                    <button class="primary" onClick={close}>{t('common.back')}</button>
                </div>
            </Match>
            <Match when={video()} keyed>
                {(item) => <Page item={item} info={info() ?? null} onClose={close} />}
            </Match>
        </Switch>
    )
}

function Page(props: { item: VideoItem; info: WatchInfo | null; onClose: () => void }) {
    const item = props.item
    const navigate = useNavigate()
    const [art, setArt] = createSignal<string | null>(null)
    const [poster, setPoster] = createSignal<string | null>(null)
    const [left] = createResource(() => getResume(item.chatId, item.messageId))
    const [watched] = createResource(watchedTick, async () => (await watchedList()).some((w) => w.chatId === item.chatId && w.messageId === item.messageId))
    const later = () => laterIds().has(`${item.chatId}:${item.messageId}`)
    onMount(() => {
        if (item.thumb) loadThumb(item.thumb).then(setArt, () => {})
        if (item.poster && settings().posters) loadPoster(item.poster).then((u) => u && setPoster(u), () => {})
        // Play is the likely next press: the file's head and tail start arriving now.
        warmFile(item)
    })
    const title = () => props.info?.title ?? item.title
    const ep = () => props.info?.episode ?? null
    const resolution = () => (props.info?.height ? `${props.info.height}p` : '')
    const container = () => (props.info?.fileName ?? item.title).match(/\.([a-z0-9]{2,4})$/i)?.[1]?.toUpperCase() ?? ''
    const resumeAt = () => {
        const r = left()
        return r && r.position > 0 ? r.position : 0
    }
    const play = (from?: 'start') => {
        if (from === 'start') dropResume(item.chatId, item.messageId)
        navigate(watchPath(item), { state: { video: item } })
    }
    const minutesLeft = () => {
        const r = left()
        if (!r) return 0
        return Math.max(1, Math.round(((r.length || item.duration) - r.position) / 60))
    }
    const fraction = () => {
        const r = left()
        const total = r?.length || item.duration
        return r && total ? Math.min(1, r.position / total) : 0
    }
    const posted = () => new Date(item.date).toLocaleDateString('en', { day: 'numeric', month: 'short', year: 'numeric' })

    return (
        <article class="detail">
            <div class="detail-hero" classList={{ blank: !art() && !poster() }}>
                <Show when={art()}>
                    <img class="detail-art" src={art()!} alt="" />
                </Show>
                <Show when={poster()} fallback={<Show when={art()}><img class="detail-frame" src={art()!} alt="" /></Show>}>
                    <img class="detail-poster" src={poster()!} alt="" />
                </Show>
                <div class="detail-fade" />
                <button type="button" class="detail-close" aria-label={t('detail.close')} title={t('detail.close')} onClick={props.onClose}>
                    <Icon icon={Cancel01Icon} size={24} />
                </button>
            </div>
            <div class="detail-body">
                <header class="detail-head">
                    <Show when={ep()}>
                        {(e) => <p class="detail-code">{e().name ? `${e().code}${dot}${e().name}` : e().code}</p>}
                    </Show>
                    <h1 class="detail-title">{title()}</h1>
                    <p class="detail-facts">
                        <For each={[shortLength(item.duration), resolution(), size(item.size)].filter(Boolean)}>
                            {(f) => (f === resolution() ? <span class="detail-badge">{f}</span> : <span>{f}</span>)}
                        </For>
                        <A class="detail-chat" href={`/chat/${item.chatId}`}>{props.info?.chatTitle ?? item.chat}</A>
                    </p>
                    <Show when={watched()}>
                        <p class="detail-watched">{t('detail.watched_line')}</p>
                    </Show>
                    <Show when={resumeAt() > 0}>
                        <div class="detail-progress">
                            <span class="detail-progress-bar"><span style={{ width: `${fraction() * 100}%` }} /></span>
                            <span>{t('sleep.minutes_left', { count: minutesLeft() })}</span>
                        </div>
                    </Show>
                </header>
                <div class="detail-actions">
                    <button type="button" class="detail-play" onClick={() => play()}>
                        <Icon icon={PlayIcon} size={22} />
                        {resumeAt() > 0 ? t('detail.resume_at', { time: clock(resumeAt()) }) : t('common.play')}
                    </button>
                    <div class="detail-rounds">
                        <Show when={props.info?.seasons.length}>
                            <Round icon={ListVideoIcon} label={t('detail.episodes')} onClick={() => document.getElementById('detail-episodes')?.scrollIntoView({ behavior: 'smooth' })} />
                        </Show>
                        <Show when={resumeAt() > 0}>
                            <Round icon={RepeatIcon} label={t('detail.start_over')} onClick={() => play('start')} />
                        </Show>
                        <Round icon={Tick02Icon} label={watched() ? t('detail.round_watched') : t('detail.round_watched')} on={!!watched()} onClick={() => setWatched(item.chatId, item.messageId, !watched())} />
                        <Round icon={Clock01Icon} label={t('detail.round_watch_later')} on={later()} onClick={() => setLater(item, !later())} />
                    </div>
                </div>
                <Show when={props.info?.protected || !item.canBeSaved}>
                    <p class="notice muted small">{t('detail.protected')}</p>
                </Show>
                <Show when={props.info?.seasons.length}>
                    <Episodes info={props.info!} current={item} />
                </Show>
                <section class="detail-about">
                    <h2 class="detail-section">{t('detail.about')}</h2>
                    <dl>
                        <Show when={props.info?.fileName}>
                            <dt>{t('detail.file_name')}</dt>
                            <dd>{props.info!.fileName}</dd>
                        </Show>
                        <Show when={props.info?.height}>
                            <dt>{t('detail.resolution')}</dt>
                            <dd>{`${resolution()}${dot}${props.info!.width} × ${props.info!.height}`}</dd>
                        </Show>
                        <Show when={container()}>
                            <dt>{t('detail.video')}</dt>
                            <dd>{container()}</dd>
                        </Show>
                        <dt>{t('detail.size')}</dt>
                        <dd>{size(item.size)}</dd>
                        <Show when={item.duration}>
                            <dt>{t('detail.length')}</dt>
                            <dd>{shortLength(item.duration)}</dd>
                        </Show>
                        <dt>{t('detail.posted')}</dt>
                        <dd>{posted()}</dd>
                        <dt>{t('nav.chats')}</dt>
                        <dd class="detail-chat-line">
                            <A class="inline-link" href={`/chat/${item.chatId}`}>{props.info?.chatTitle ?? item.chat}</A>
                            <StarButton chatId={item.chatId} />
                        </dd>
                    </dl>
                </section>
                <Show when={!props.info?.seasons.length && props.info?.more.length}>
                    <More info={props.info!} />
                </Show>
            </div>
        </article>
    )
}

/** One of the everyday actions: a round outlined button with its name under it; a switch fills when on. */
function Round(props: { icon: IconData; label: string; on?: boolean; onClick: () => void }) {
    return (
        <button type="button" class="detail-round" aria-pressed={props.on === undefined ? undefined : props.on} onClick={props.onClick}>
            <span class="detail-round-disc">
                <Icon icon={props.icon} size={22} />
            </span>
            <span class="detail-round-label">{props.label}</span>
        </button>
    )
}

/** The show's episodes, a season at a time, the one this page is about marked. */
function Episodes(props: { info: WatchInfo; current: VideoItem }) {
    const progress = useProgress()
    const seasonOf = () => props.info.seasons.find((s) => s.episodes.some((e) => e.item.messageId === props.current.messageId))?.number ?? props.info.seasons[0]?.number
    const [picked, setPicked] = createSignal<number | null>(null)
    const season = createMemo(() => props.info.seasons.find((s) => s.number === (picked() ?? seasonOf())) ?? props.info.seasons[0])
    return (
        <section class="detail-episodes" id="detail-episodes">
            <h2 class="detail-section with-icon">
                <Icon icon={ListVideoIcon} size={20} />
                {t('detail.episodes')}
            </h2>
            <div class="season-tabs" role="tablist">
                <For each={props.info.seasons}>
                    {(s) => (
                        <button role="tab" class="season-chip" classList={{ on: s.number === season()?.number }} aria-selected={s.number === season()?.number} onClick={() => setPicked(s.number)}>
                            <span>{t('series.season', { number: s.number })}</span>
                            <small>{t('series.episodes_count', { count: s.episodes.length })}</small>
                        </button>
                    )}
                </For>
            </div>
            <ol class="episode-list">
                <For each={season()?.episodes ?? []}>
                    {(e) => {
                        const here = () => e.item.messageId === props.current.messageId && e.item.chatId === props.current.chatId
                        return (
                            <li>
                                <A class="episode" classList={{ playing: here() }} href={watchPath(e.item)} state={{ video: e.item }} aria-current={here() ? 'true' : undefined}>
                                    <span class="episode-code">{e.code}</span>
                                    <span class="episode-name">{e.name ?? shortLength(e.item.duration)}</span>
                                    <Show when={progress(e.item)}>
                                        {(p) => <span class="episode-progress"><span style={{ width: `${Math.min(100, p() * 100)}%` }} /></span>}
                                    </Show>
                                </A>
                            </li>
                        )
                    }}
                </For>
            </ol>
        </section>
    )
}

/** Other videos of the chat, newest first, as cards. */
function More(props: { info: WatchInfo }) {
    const progress = useProgress()
    return (
        <section class="more-from">
            <h2 class="detail-section">{t('web.more_from_chat', { chat: props.info.chatTitle })}</h2>
            <div class="cards more-cards">
                <For each={props.info.more}>{(tile) => <Card card={tileCard(tile)} progress={progress(tile.item)} replace />}</For>
            </div>
        </section>
    )
}
