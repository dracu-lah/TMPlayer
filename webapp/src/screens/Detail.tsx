import { createEffect, createMemo, createResource, createSignal, For, Match, onCleanup, onMount, Show, Switch, type JSX } from 'solid-js'
import { A, useLocation, useNavigate, useParams } from '@solidjs/router'
import { Cancel01Icon, ClapperboardIcon, Clock01Icon, ListVideoIcon, PlayIcon, RepeatIcon, Tick02Icon } from '@hugeicons/core-free-icons'
import { api, errorText } from '../tg/rpc'
import { back } from '../session'
import { t } from '../i18n'
import { dropResume, getResume, laterIds, progress as readProgress, setLater, setShowLater, setWatched, setWatchedMany, settings, showLaterId, watchedList, watchedTick } from '../store'
import { showChoices, type MarkChoice } from './showMarks'
import { Icon, type IconData } from '../ui/Icon'
import { TmdbMark } from '../ui/TmdbMark'
import { TitleLogo } from '../ui/TitleLogo'
import { initialOf } from '../ui/initial'
import { StarButton } from './Chats'
import { loadThumb } from './thumbs'
import { answer, loadBackdropLightFirst, loadExtras, loadFile, loadPoster, type Answer, type Extras } from './posters'
import { coverBackground } from './cover'
import { Card, clock, detailPath, shortLength, size, tileCard, useProgress, watchPath } from './VideoList'
import { warmFile } from '../stream/reader'
import type { ChatView, CopyRef, VideoItem, WatchInfo } from '../tg/types'

const dot = '  ·  '

/**
 * A video's detail, as the phone's MediaDetailScreen and the desktop's WideDetail draw it: the
 * picture across the top fading into the page with the name set large on its foot, the facts on
 * one line, then its genres, age rating and score, Play (or Resume at the place left) as the one
 * bright pill with the everyday actions as round buttons beside or under it, the copies it comes
 * in, its overview, the show's episodes, the file's own facts under About, the cast and more from
 * the chat. The overview, genres, score, cast and trailer come from TMDB (the apps' MetaInfo and
 * MetaExtras) through the worker, only while posters are on; without them the page is the file's
 * own facts. Opened from a card with the item the card passed along; by its address it asks
 * Telegram first. Close and Back go back to where the card was.
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
                {(item) => <Page item={item} info={info() ?? null} infoSettled={info.state === 'ready' || info.state === 'errored'} onClose={close} />}
            </Match>
        </Switch>
    )
}

/** What TMDB says of the title: the poster answer (title, year, overview, wide picture) and the extras. */
interface Meta {
    found: Answer
    extras: Extras | null
}

function Page(props: { item: VideoItem; info: WatchInfo | null; infoSettled: boolean; onClose: () => void }) {
    const item = props.item
    const navigate = useNavigate()
    const [art, setArt] = createSignal<string | null>(null)
    const [poster, setPoster] = createSignal<string | null>(null)
    const [backdrop, setBackdrop] = createSignal<string | null>(null)
    const [left] = createResource(() => getResume(item.chatId, item.messageId))
    const [watched] = createResource(watchedTick, async () => (await watchedList()).some((w) => w.chatId === item.chatId && w.messageId === item.messageId))
    const later = () => laterIds().has(`${item.chatId}:${item.messageId}`)
    const [more, setMore] = createSignal(false)
    // "More" is offered only for an overview that is cut short (the app's SynopsisSlot).
    let synopsis: HTMLParagraphElement | undefined
    const [cut, setCut] = createSignal(false)
    const measure = () => synopsis && setCut(synopsis.scrollHeight > synopsis.clientHeight + 1)
    onMount(() => {
        window.addEventListener('resize', measure)
        onCleanup(() => window.removeEventListener('resize', measure))
        if (item.thumb) loadThumb(item.thumb).then(setArt, () => {})
        // Play is the likely next press: the file's head and tail start arriving now.
        warmFile(item)
    })
    // TMDB's picture of the title and its words, once the page knows which episode it is (a show's own words are the episode's).
    const [meta] = createResource(
        () => (settings().posters && item.poster && props.infoSettled ? { ask: item.poster, code: props.info?.episode?.code ?? null } : null),
        async ({ ask, code }): Promise<Meta | null> => {
            // A failed lookup (no key, TMDB down) is "nothing known": thrown here it would reach the page's
            // error boundary, which mounts the page again and asks again, over and over.
            const found = await answer(ask).catch(() => null)
            if (!found?.match) return null
            const m = code ? /^S(\d+)E(\d+)/i.exec(code) : null
            const extras = await loadExtras(found, m ? { season: Number(m[1]), episode: Number(m[2]) } : null).catch(() => null)
            return { found, extras: extras?.found ? extras : null }
        },
    )
    const found = () => meta.latest?.found ?? null
    const extras = () => meta.latest?.extras ?? null
    // The poster stands behind a show's page, the title's wide picture behind a film's, as the apps
    // draw them. Which one is known from the poster answer alone, asked at once and past the cards'
    // queue: until it is in, the page shows the video's own frame, never a poster that a film's wide
    // picture then replaces. The wide picture comes light first (w300), then whole (w780).
    const [kind, setKind] = createSignal<'film' | 'show' | null | undefined>(undefined)
    const film = () => kind() === 'film'
    onMount(() => {
        const ask = item.poster
        if (!ask || !settings().posters) return void setKind(null)
        let stop = () => {}
        answer(ask).then(
            (f) => {
                const k = f.match ? (f.kind ?? null) : null
                setKind(k)
                if (k === 'film' && f.backdrop) stop = loadBackdropLightFirst(ask, setBackdrop)
                else if (f.poster) loadPoster(ask, 'w342', { priority: true }).then((u) => u && setPoster(u), () => {})
            },
            () => setKind(null),
        )
        onCleanup(() => stop())
    })
    const title = () => found()?.title || props.info?.title || item.title
    const ep = () => props.info?.episode ?? null
    const episodeName = () => extras()?.episode?.name || ep()?.name || ''
    const resolution = () => props.info?.quality ?? (props.info?.height ? `${props.info.height}p` : '')
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
    const year = () => found()?.year ?? null
    const overview = () => extras()?.episode?.overview || found()?.overview || ''
    createEffect(() => (overview(), more(), queueMicrotask(() => !more() && measure())))
    const genres = () => [(extras()?.genres ?? []).join(', '), extras()?.certification ?? ''].filter(Boolean).join(dot)
    // The facts line: year, length, quality, size and the chat, with a dot between (the apps' FactLine).
    const facts = () => [year() ? String(year()) : '', shortLength(item.duration), resolution(), size(item.size)].filter(Boolean)
    const credit = () => {
        const x = extras()
        if (!x) return ''
        if (x.directors?.length) return t('metadata.directed_by', { names: x.directors.join(', ') })
        if (x.creators?.length) return t('metadata.created_by', { names: x.creators.join(', ') })
        return ''
    }

    return (
        <article class="detail">
            <div class="detail-hero" classList={{ blank: !art() && !poster() && !backdrop() }} style={!art() && !poster() && !backdrop() ? { background: coverBackground(title()) } : undefined}>
                <Show when={art()}>
                    <img class="detail-art" src={art()!} alt="" />
                </Show>
                <Show
                    when={film() && backdrop()}
                    fallback={
                        <Show when={poster()} fallback={<Show when={art()}><img class="detail-frame" src={art()!} alt="" /></Show>}>
                            <img class="detail-poster" src={poster()!} alt="" />
                        </Show>
                    }
                >
                    <img class="detail-backdrop" src={backdrop()!} alt="" />
                </Show>
                <div class="detail-fade" />
                <button type="button" class="detail-close" aria-label={t('detail.close')} title={t('detail.close')} onClick={props.onClose}>
                    <Icon icon={Cancel01Icon} size={24} />
                </button>
            </div>
            <div class="detail-body">
                <header class="detail-head">
                    <Show when={ep()}>
                        {(e) => <p class="detail-code">{episodeName() ? `${e().code}${dot}${episodeName()}` : e().code}</p>}
                    </Show>
                    {/* The title logo where TMDB has one, a placeholder while it may still come, the name otherwise. */}
                    <TitleLogo
                        level={1}
                        class="detail-title"
                        name={title()}
                        extras={extras()}
                        pending={!!(settings().posters && item.poster) && (!props.infoSettled || meta.loading)}
                    />
                    <p class="detail-facts">
                        <For each={facts()}>
                            {(f) => (
                                <>
                                    {f === resolution() ? <span class="detail-badge">{f}</span> : <span>{f}</span>}
                                    <span class="detail-sep" aria-hidden="true">•</span>
                                </>
                            )}
                        </For>
                        <A class="detail-chat" href={`/chat/${item.chatId}`}>{props.info?.chatTitle ?? item.chat}</A>
                    </p>
                    <Show when={genres() || extras()?.rating}>
                        <div class="detail-meta">
                            <Show when={genres()}>
                                <p class="detail-genres">{genres()}</p>
                            </Show>
                            <Show when={extras()?.rating}>
                                {/* TMDB's score (MetaScore): its mark and the number out of ten. */}
                                <p class="detail-scores">
                                    <span class="detail-score" role="img" aria-label={t('metadata.score_out_of_ten', { site: 'TMDB', score: extras()!.rating! })}>
                                        <TmdbMark height={14} />
                                        <span>{extras()!.rating!.toFixed(1)}</span>
                                    </span>
                                </p>
                            </Show>
                        </div>
                    </Show>
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
                    <button type="button" class="detail-play" data-tv-first onClick={() => play()}>
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
                        <Round icon={Tick02Icon} label={t('detail.round_watched')} on={!!watched()} onClick={() => setWatched(item.chatId, item.messageId, !watched())} />
                        <Round icon={Clock01Icon} label={t('detail.round_watch_later')} on={later()} onClick={() => setLater(item, !later())} />
                        {/* The trailer plays on YouTube, in another tab: the page keeps no key and embeds nothing. */}
                        <Show when={extras()?.trailer}>
                            <Round icon={ClapperboardIcon} label={t('detail.round_trailer')} href={`https://www.youtube.com/watch?v=${extras()!.trailer}`} />
                        </Show>
                    </div>
                </div>
                <Show when={(props.info?.copies.length ?? 0) > 1}>
                    <Versions item={item} info={props.info!} />
                </Show>
                <Show when={props.info?.protected || !item.canBeSaved}>
                    <p class="notice muted small">{t('detail.protected')}</p>
                </Show>
                <Show when={overview()}>
                    <section class="detail-overview">
                        <p class="detail-synopsis" classList={{ open: more() }} ref={synopsis}>{overview()}</p>
                        <Show when={cut() || more()}>
                            <button type="button" class="link detail-more" onClick={() => setMore(!more())}>{more() ? t('common.less') : t('common.more')}</button>
                        </Show>
                        <p class="muted small">{t('metadata.credit', { provider: 'TMDB' })}</p>
                    </section>
                </Show>
                <Show when={props.info?.seasons.length}>
                    <Episodes info={props.info!} current={item} />
                </Show>
                <section class="detail-about">
                    <h2 class="detail-section">{t('detail.about')}</h2>
                    <Show when={credit()}>
                        <p class="detail-credit">{credit()}</p>
                    </Show>
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
                <Show when={extras()?.cast?.length}>
                    <Cast people={extras()!.cast!} />
                </Show>
                <Show when={!props.info?.seasons.length && props.info?.more.length}>
                    <More info={props.info!} />
                </Show>
            </div>
        </article>
    )
}

/** One of the everyday actions: a round outlined button with its name under it; a switch fills when on. A link opens elsewhere. */
function Round(props: { icon: IconData; label: string; on?: boolean; onClick?: () => void; href?: string }): JSX.Element {
    const inner = () => (
        <>
            <span class="detail-round-disc">
                <Icon icon={props.icon} size={22} />
            </span>
            <span class="detail-round-label">{props.label}</span>
        </>
    )
    return (
        <Show
            when={props.href}
            fallback={
                <button type="button" class="detail-round" aria-pressed={props.on === undefined ? undefined : props.on} onClick={props.onClick}>
                    {inner()}
                </button>
            }
        >
            <a class="detail-round" href={props.href} target="_blank" rel="noopener noreferrer">
                {inner()}
            </a>
        </Show>
    )
}

/**
 * The copies of this video the chat holds (the apps' Versions): one quiet line naming the copy this
 * page is about ("1080p · H.264 · Blu-ray · 885 MB · from Film Club") with Change at its end, and
 * under it, once Change is pressed, every copy as a row of its own to choose from. Choosing one turns
 * the page to that copy, so Play and the facts speak of it.
 */
function Versions(props: { item: VideoItem; info: WatchInfo }) {
    const navigate = useNavigate()
    const [open, setOpen] = createSignal(false)
    const here = () => props.info.copies.find((c) => c.item.messageId === props.item.messageId && c.item.chatId === props.item.chatId)
    const heading = (c: CopyRef) => (c.facts.length ? c.facts.join(dot) : size(c.item.size))
    const summary = () => {
        const c = here()
        return c ? [heading(c), c.facts.length ? size(c.item.size) : '', t('detail.copy_from', { chat: c.chat })].filter(Boolean).join(dot) : ''
    }
    const pick = (c: CopyRef) => {
        setOpen(false)
        if (c === here()) return
        navigate(detailPath(c.item), { replace: true, state: { video: c.item } })
    }
    const datesDiffer = () => new Set(props.info.copies.map((c) => Math.floor(c.date / 86_400_000))).size > 1
    return (
        <section class="detail-versions" aria-label={t('detail.copies_title')}>
            <button type="button" class="detail-copy-summary" aria-expanded={open()} onClick={() => setOpen(!open())}>
                <span class="detail-copy-text">
                    <span class="detail-copy-line">{summary()}</span>
                    <span class="muted small">{t('detail.versions', { count: props.info.copies.length })}</span>
                </span>
                <span class="detail-copy-change">{open() ? t('detail.copy_done') : t('detail.copy_change')}</span>
            </button>
            <Show when={open()}>
                <ul class="detail-copy-list" role="listbox" aria-label={t('detail.copies_title')}>
                    <For each={props.info.copies}>
                        {(c) => (
                            <li>
                                <button type="button" role="option" class="detail-copy" aria-selected={c === here()} onClick={() => pick(c)}>
                                    <span class="detail-copy-text">
                                        <span class="detail-copy-line">{heading(c)}</span>
                                        <span class="muted small">
                                            {[c.facts.length ? size(c.item.size) : '', shortLength(c.item.duration), datesDiffer() ? new Date(c.item.date).toLocaleDateString('en', { day: 'numeric', month: 'short', year: 'numeric' }) : ''].filter(Boolean).join(dot)}
                                        </span>
                                        <span class="muted small detail-copy-file">{c.fileName}</span>
                                    </span>
                                    <Show when={c === here()}>
                                        <Icon icon={Tick02Icon} size={22} />
                                    </Show>
                                </button>
                            </li>
                        )}
                    </For>
                </ul>
            </Show>
        </section>
    )
}

/** The cast: a face (or its first letter), the name and who they play, in a row that scrolls sideways. */
function Cast(props: { people: { name: string; role: string; photo: string | null }[] }) {
    return (
        <section class="detail-cast">
            <h2 class="detail-section">{t('metadata.cast')}</h2>
            <ul class="cast-row">
                <For each={props.people}>{(p) => <Person person={p} />}</For>
            </ul>
        </section>
    )
}

function Person(props: { person: { name: string; role: string; photo: string | null } }) {
    const [src, setSrc] = createSignal<string | null>(null)
    onMount(() => {
        if (props.person.photo) loadFile(props.person.photo, 'w185').then(setSrc, () => {})
    })
    return (
        <li class="cast-person">
            <span class="cast-face" aria-hidden="true">
                <Show when={src()} fallback={<span class="initial">{initialOf(props.person.name)}</span>}>
                    <img src={src()!} alt="" decoding="async" />
                </Show>
            </span>
            <span class="cast-name">{props.person.name}</span>
            <Show when={props.person.role}>
                <span class="cast-role">{props.person.role}</span>
            </Show>
        </li>
    )
}

/**
 * The show's episodes, a season at a time, the one this page is about marked, the watched ones
 * ticked. Over them the show's own actions, as the apps' show page has them (HX20): Watch later for
 * the whole show, and Mark episodes, whose card lists the season's and the show's lines with how
 * many episodes each changes, so picking one is the confirmation.
 */
function Episodes(props: { info: WatchInfo; current: VideoItem }) {
    const progress = useProgress()
    const seasonOf = () => props.info.seasons.find((s) => s.episodes.some((e) => e.item.messageId === props.current.messageId))?.number ?? props.info.seasons[0]?.number
    const [picked, setPicked] = createSignal<number | null>(null)
    const season = createMemo(() => props.info.seasons.find((s) => s.number === (picked() ?? seasonOf())) ?? props.info.seasons[0])
    const [seen] = createResource(watchedTick, async () => new Set((await watchedList()).map((w) => `${w.chatId}:${w.messageId}`)))
    const watchedSet = () => seen.latest ?? new Set<string>()
    const seasons = () => props.info.seasons.map((s) => ({ number: s.number, episodes: s.episodes.map((e) => e.item) }))
    const choices = createMemo(() => {
        const all = seasons()
        return showChoices(all, all.find((s) => s.number === season()?.number), watchedSet())
    })
    const showLater = () => laterIds().has(showLaterId(props.current.chatId, props.info.title))
    const [said, setSaid] = createSignal('')
    let marks!: HTMLDialogElement
    const pick = (choice: MarkChoice<VideoItem>) => {
        marks.close()
        setWatchedMany(choice.episodes, choice.watched)
        // Watched whole, the show leaves Watch later, as a finished video does in the apps.
        if (choice.watched && choice.episodes.length >= props.info.seasons.reduce((n, s) => n + s.episodes.filter((e) => !watchedSet().has(`${e.item.chatId}:${e.item.messageId}`)).length, 0)) {
            setShowLater(props.current, props.info.title, false)
        }
        setSaid(choice.watched ? t('series.marked_watched', { count: choice.episodes.length }) : t('series.marked_unwatched', { count: choice.episodes.length }))
    }
    const label = (c: MarkChoice<VideoItem>) =>
        c.scope === 'season'
            ? t(c.watched ? 'series.mark_season_watched' : 'series.mark_season_unwatched', { number: c.season ?? 1 })
            : t(c.watched ? 'series.mark_show_watched' : 'series.mark_show_unwatched')
    return (
        <section class="detail-episodes" id="detail-episodes">
            <h2 class="detail-section with-icon">
                <Icon icon={ListVideoIcon} size={20} />
                {t('detail.episodes')}
            </h2>
            <div class="detail-rounds show-actions">
                <Round icon={Tick02Icon} label={t('series.mark_title')} on={choices().length > 0 && choices().every((c) => !c.watched)} onClick={() => marks.showModal()} />
                <Round
                    icon={Clock01Icon}
                    label={showLater() ? t('detail.watch_later_remove') : t('detail.watch_later_add')}
                    on={showLater()}
                    onClick={() => {
                        const on = !showLater()
                        setShowLater(props.current, props.info.title, on)
                        setSaid(on ? t('detail.watch_later_added', { title: props.info.title }) : t('detail.watch_later_removed', { title: props.info.title }))
                    }}
                />
            </div>
            <p class="muted small" role="status" aria-live="polite">{said()}</p>
            <dialog class="confirm card-menu" ref={marks} aria-labelledby="marks-title" onClick={(e) => e.target === marks && marks.close()}>
                <h2 id="marks-title">{t('series.mark_title')}</h2>
                <p class="muted">{props.info.title}</p>
                <Show when={choices().length} fallback={<p class="muted">{t('series.all_watched')}</p>}>
                    <For each={choices()}>
                        {(c) => (
                            <button class="menu-row" type="button" onClick={() => pick(c)}>
                                <Icon icon={c.watched ? Tick02Icon : Cancel01Icon} size={22} />
                                <span>
                                    {label(c)}
                                    <small>{t('series.episodes_count', { count: c.episodes.length })}</small>
                                </span>
                            </button>
                        )}
                    </For>
                </Show>
                <div class="confirm-buttons">
                    <button class="link" type="button" onClick={() => marks.close()}>{t('common.cancel')}</button>
                </div>
            </dialog>
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
                                    <Show when={watchedSet().has(`${e.item.chatId}:${e.item.messageId}`)}>
                                        <span class="episode-watched" role="img" aria-label={t('detail.watched_line')}>
                                            <Icon icon={Tick02Icon} size={16} />
                                        </span>
                                    </Show>
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
