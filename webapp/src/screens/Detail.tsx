import { createEffect, createMemo, createResource, createSignal, For, Match, onCleanup, onMount, Show, Switch } from 'solid-js'
import { A, useLocation, useNavigate, useParams } from '@solidjs/router'
import { ArrowLeft02Icon, ClapperboardIcon, Clock01Icon, Download04Icon, Edit02Icon, InformationCircleIcon, LeftToRightListBulletIcon, ListVideoIcon, PlayIcon, RepeatIcon, Search01Icon, Share08Icon, Tick02Icon } from '@hugeicons/core-free-icons'
import { api, errorText } from '../tg/rpc'
import { back } from '../session'
import { t } from '../i18n'
import { dropResume, getResume, laterIds, namedLists, progress as readProgress, setLater, setSetting, setWatched, settings, watchedList, watchedTick } from '../store'
import { openListPicker } from './Lists'
import { chatGone, forgetGone } from '../goneVideos'
import './series.css'
import { scrollBehavior } from '../ui/motion'
import { Icon } from '../ui/Icon'
import { TmdbMark } from '../ui/TmdbMark'
import { TitleLogo } from '../ui/TitleLogo'
import { openTrailer } from '../ui/TrailerSheet'
import { inApp, isValidKey, watchUrl } from '../ui/trailer'
import { openPosterPicker } from '../ui/PosterPicker'
import { canReportTitle, openRename, reportTitle } from '../ui/renameTarget'
import { StarButton } from './Chats'
import { loadThumb } from './thumbs'
import { answer, askOf, followAnimeGuide, loadBackdropLightFirst, loadExtras, loadPoster, withAnimeGuide, type Answer, type AnimeGuide, type Extras } from './posters'
import { coverBackground } from './cover'
import { clock, shortLength, size, watchPath } from './VideoList'
import { warmFile } from '../stream/reader'
import { copiesTick, renamedTitle } from '../copies'
import './versions.css'
import type { ChatView, VideoItem, WatchInfo } from '../tg/types'
import { Cast } from './detail/Cast'
import { CopyRepair, Versions } from './detail/Versions'
import { Episodes } from './detail/Episodes'
import { More } from './detail/More'
import { Round } from './detail/Round'
import { dot } from '../ui/dot'
import { isTgLink, linkOf, linkOffered } from '../ui/tgLinks'
import { canShare, shareVideo } from '../ui/shareVideo'
import { canSaveToDevice, saveToDevice } from '../ui/saveRuntime'
import { setPageName } from '../ui/pageTitle'


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
        let found: VideoItem | null
        try {
            found = passed?.chatId === chatId && passed.messageId === messageId && (await api.known(passed.key)) ? passed : await api.video(chatId, messageId)
        } catch (e) {
            // Its chat closed to this account: the page says so, and the lists let it go (HX21).
            if (chatGone(e)) void forgetGone([{ chatId, messageId }], false)
            throw e
        }
        // Gone from the chat: it leaves Continue watching, Watched and Watch later (HX21).
        if (found === null) void forgetGone([{ chatId, messageId }], false)
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
    // Asked again when a file is kept apart or a copy chosen (HX38), once the worker has it.
    const [info] = createResource(
        () => (seen.state === 'ready' ? { ...ids(), view: view(), tick: copiesTick() } : null),
        ({ chatId, messageId, view }) => api.watch(chatId, messageId, view).catch((e) => (console.warn('[detail]', e), null)),
    )
    const close = () => back(navigate)

    return (
        <Switch fallback={<div class="detail"><div class="detail-hero" /></div>}>
            <Match when={video.error}>
                <div class="boot">
                    <p class="error" role="alert">{errorText(video.error)}</p>
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
    const inAList = () => namedLists().some((l) => l.lines.some((x) => !x.show && x.item.chatId === item.chatId && x.item.messageId === item.messageId))
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
        () => (settings().posters && askOf(item) && props.infoSettled ? { ask: askOf(item)!, code: props.info?.episode?.code ?? null } : null),
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
    // An anime AniList matched lists no seasons or episodes: the worker's guide (the apps' AnimeEpisodes: TMDB, Kitsu, Jikan,
    // AniList) supplies the run, the next episode to air and this episode's own title and words. Asked again while it is cut short.
    const [animeGuide, setAnimeGuide] = createSignal<AnimeGuide | null>(null)
    createEffect(() => {
        const f = found()
        setAnimeGuide(null)
        if (f?.source !== 'anilist' || f.kind !== 'show' || !f.id) return
        onCleanup(followAnimeGuide(f.id, setAnimeGuide))
    })
    // The TMDB show the series page asks its episodes of: not AniList's (its guide is the anime one above), not a film.
    const tmdbShow = () => {
        const f = found()
        return f?.match && f.kind === 'show' && f.source !== 'anilist' && f.id && /^\d{1,9}$/.test(f.id) ? f.id : null
    }
    const episodeAt = () => {
        const m = props.info?.episode?.code ? /^S(\d+)E(\d+)/i.exec(props.info.episode.code) : null
        return m ? { season: Number(m[1]), episode: Number(m[2]) } : null
    }
    const extras = createMemo(() => withAnimeGuide(meta.latest?.extras ?? null, animeGuide(), episodeAt()))
    // The poster stands behind a show's page, the title's wide picture behind a film's, as the apps
    // draw them. Which one is known from the poster answer alone, asked at once and past the cards'
    // queue: until it is in, the page shows the video's own frame, never a poster that a film's wide
    // picture then replaces. The wide picture comes light first (w300), then whole (w780).
    const [kind, setKind] = createSignal<'film' | 'show' | null | undefined>(undefined)
    // The answer could not be had (no key, offline): not "nothing found", so no line says so.
    const [failed, setFailed] = createSignal(false)
    const film = () => kind() === 'film'
    // An effect, not a mount: choosing another title ('Choose the right poster') asks again at once.
    let asked = false
    createEffect(() => {
        const ask = askOf(item)
        let live = true
        let stop = () => {}
        onCleanup(() => {
            live = false
            stop()
        })
        setFailed(false)
        if (asked) {
            setKind(undefined)
            setPoster(null)
            setBackdrop(null)
        }
        asked = true
        if (!ask || !settings().posters) return void setKind(null)
        answer(ask).then(
            (f) => {
                if (!live) return
                const k = f.match ? (f.kind ?? null) : null
                setKind(k)
                if (k === 'film' && f.backdrop) stop = loadBackdropLightFirst(ask, (u) => live && setBackdrop(u))
                else if (f.poster) loadPoster(ask, 'w342', { priority: true }).then((u) => live && u && setPoster(u), () => {})
            },
            () => live && (setFailed(true), setKind(null)),
        )
    })
    // Why there is no poster, in one line with the one action that fixes it ("Why no poster?"): never for a match, while the answer is coming, or when it could not be had.
    const why = (): 'off' | 'unread' | 'nothing' | null => {
        if (!settings().posters) return 'off'
        if (kind() === undefined || kind() !== null || failed()) return null
        return askOf(item) ? 'nothing' : 'unread'
    }
    // Share (WP-59): the video's Telegram link to the share sheet, only where the browser has one and the chat lets a link be given.
    const [shareable] = createResource(
        () => (canShare() && linkOffered(item) ? item : null),
        async (it) => {
            const a = await linkOf(it, api.messageLink)
            return 'link' in a && isTgLink(a.link) ? a.link : null
        },
    )
    // The viewer's own title (Rename) leads; TMDB's, then the name the worker reads, follow.
    const title = () => renamedTitle(item) || found()?.title || props.info?.title || item.title
    // The tab's title names the video while its page is open (ui/pageTitle.ts).
    createEffect(() => setPageName(title()))
    onCleanup(() => setPageName(null))
    // Every copy of this video: a rename is written to all of them, so it holds whichever one a card shows.
    const copyItems = () => props.info?.group.length ? props.info.group : (props.info?.copies.map((c) => c.item) ?? [])
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
    // TMDB's words first; else the caption, its file facts (File name, Size, Quality...) and lines
    // repeating the name taken off (MediaText.overviewOf), as the apps' detail falls back to it.
    // The anime provider (AniList, anilist.ts) answers for a fansub name; its credit and score say so.
    const provider = () => (found()?.source === 'anilist' ? 'AniList' : 'TMDB')
    const tmdbOverview = () => extras()?.episode?.overview || found()?.overview || ''
    const overview = () => tmdbOverview() || props.info?.overview || ''
    // What stands behind the words: a film's wide picture, else a show's poster (whole, over a soft
    // copy of itself, never Telegram's thumbnail blown up behind it), else the video's own frame.
    const shown = () => (film() && backdrop() ? 'backdrop' : poster() ? 'poster' : art() ? 'frame' : null)
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

    const aboutSection = () => (
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
    )

    return (
        <article class="detail">
            <div class="detail-hero" classList={{ blank: !shown() }} style={!shown() ? { background: coverBackground(title()) } : undefined}>
                <div class="detail-pic">
                    <Switch>
                        <Match when={shown() === 'backdrop'}>
                            <img class="detail-backdrop" src={backdrop()!} alt="" />
                        </Match>
                        <Match when={shown() === 'poster'}>
                            <img class="detail-poster-soft" src={poster()!} alt="" />
                            <img class="detail-poster" src={poster()!} alt="" />
                        </Match>
                        <Match when={shown() === 'frame'}>
                            <img class="detail-art" src={art()!} alt="" />
                            <img class="detail-frame" src={art()!} alt="" />
                        </Match>
                    </Switch>
                </div>
                <div class="detail-fade" />
                {/* Back at the top start, as every other page leaves (the apps' detail since 2026-10-27). */}
                <button type="button" class="detail-close" aria-label={t('common.back')} title={t('common.back')} onClick={props.onClose}>
                    <Icon icon={ArrowLeft02Icon} size={24} />
                </button>
            </div>
            <div class="detail-body">
                {/* Two columns on a wide window (the main words, About beside them); one column otherwise, each wrapper a plain pass-through there. */}
                <div class="detail-cols">
                <header class="detail-head">
                    <Show when={ep()}>
                        {(e) => <p class="detail-code">{episodeName() ? `${e().code}${dot}${episodeName()}` : e().code}</p>}
                    </Show>
                    {/* The title logo where TMDB has one, a placeholder while it may still come, the name otherwise. */}
                    <TitleLogo
                        level={1}
                        class="detail-title"
                        name={title()}
                        extras={renamedTitle(item) ? null : extras()}
                        pending={!renamedTitle(item) && !!(settings().posters && askOf(item)) && (!props.infoSettled || meta.loading)}
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
                                    <span class="detail-score" role="img" aria-label={t('metadata.score_out_of_ten', { site: provider(), score: extras()!.rating! })}>
                                        <Show when={provider() === 'TMDB'}><TmdbMark height={14} /></Show>
                                        <Show when={provider() !== 'TMDB'}><span>{provider()}</span></Show>
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
                <div class="detail-main">
                <div class="detail-actions">
                    <button type="button" class="detail-play" data-tv-first onClick={() => play()}>
                        <Icon icon={PlayIcon} size={22} />
                        {resumeAt() > 0 ? t('detail.resume_at', { time: clock(resumeAt()) }) : t('common.play')}
                    </button>
                    <div class="detail-rounds">
                        <Show when={props.info?.seasons.length}>
                            <Round icon={ListVideoIcon} label={t('detail.episodes')} onClick={() => document.getElementById('detail-episodes')?.scrollIntoView({ behavior: scrollBehavior() })} />
                        </Show>
                        <Show when={resumeAt() > 0}>
                            <Round icon={RepeatIcon} label={t('detail.start_over')} onClick={() => play('start')} />
                        </Show>
                        <Round icon={Tick02Icon} label={t('detail.round_watched')} on={!!watched()} onClick={() => setWatched(item.chatId, item.messageId, !watched())} />
                        <Show when={canSaveToDevice(item)}>
                            <Round icon={Download04Icon} label={t('web.save_to_device')} onClick={() => void saveToDevice(item)} />
                        </Show>
                        <Show when={shareable()}>
                            {(link) => <Round icon={Share08Icon} label={t('common.share')} onClick={() => void shareVideo(link(), title())} />}
                        </Show>
                        <Round icon={Clock01Icon} label={t('detail.round_watch_later')} on={later()} onClick={() => setLater(item, !later())} />
                        {/* One of the viewer's named lists (HX43): kept by name, so a restricted chat's video goes in one too. */}
                        <Round icon={LeftToRightListBulletIcon} label={t('detail.round_list')} on={inAList()} onClick={() => openListPicker(item)} />
                        {/* Beside the other repairs a wrong poster is fixed by: TMDB's titles for this name, one chosen. */}
                        <Show when={settings().posters}>
                            <Round icon={Search01Icon} label={t('poster.round')} onClick={() => openPosterPicker(item, props.info?.seasons.flatMap((s) => s.episodes.map((e) => e.item)) ?? [])} />
                        </Show>
                        {/* Rename: the title the viewer wants to see (the apps' Rename), kept for every copy and carried by the sync file. Wrong title? (WB28) shows only once its inbox is registered. */}
                        <Round icon={Edit02Icon} label={t('grid.rename')} popup onClick={() => openRename(item, props.info?.title || item.title, copyItems())} />
                        <Show when={canReportTitle()}>
                            <Round icon={InformationCircleIcon} label={t('titles.menu')} popup onClick={() => reportTitle(item, props.info?.title || item.title, copyItems())} />
                        </Show>
                        {/* A trailer plays in YouTube's own player in a sheet, which loads only after this press (ui/TrailerSheet.tsx); with Play trailers in TMPlayer off it is a link to YouTube in another tab. A key that is not a YouTube id is never offered. */}
                        <Show when={isValidKey(extras()?.trailer) ? extras()!.trailer! : undefined} keyed>
                            {(key) => (
                                <Show
                                    when={inApp(settings().trailersInApp, key)}
                                    fallback={<Round icon={ClapperboardIcon} label={t('detail.round_trailer')} href={watchUrl(key)!} />}
                                >
                                    <Round icon={ClapperboardIcon} label={t('detail.round_trailer')} popup onClick={(e) => openTrailer(key, e.currentTarget)} />
                                </Show>
                            )}
                        </Show>
                    </div>
                    <Show when={why()}>
                        {(w) => (
                            <p class="poster-why muted small">
                                <span>{t(w() === 'off' ? 'poster.why_off' : w() === 'unread' ? 'poster.why_name_none' : 'poster.why_nothing')}</span>
                                <Show
                                    when={w() === 'off'}
                                    fallback={<button type="button" class="link" onClick={() => openPosterPicker(item, props.info?.seasons.flatMap((s) => s.episodes.map((e) => e.item)) ?? [])}>{t('poster.why_fix')}</button>}
                                >
                                    <button type="button" class="link" onClick={() => setSetting('posters', true)}>{t('poster.why_off_action')}</button>
                                </Show>
                            </p>
                        )}
                    </Show>
                </div>
                <Show when={(props.info?.copies.length ?? 0) > 1}>
                    <Versions item={item} info={props.info!} />
                </Show>
                <Show when={props.info}>
                    <CopyRepair item={item} grouped={(props.info?.copies.length ?? 0) > 1} />
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
                        <Show when={tmdbOverview()}>
                            <p class="muted small">{t('metadata.credit', { provider: provider() })}</p>
                        </Show>
                    </section>
                </Show>
                <Show when={extras()?.cast?.length}>
                    <Cast people={extras()!.cast!} />
                </Show>
                </div>
                <div class="detail-side">{aboutSection()}</div>
                </div>
                <Show when={props.info?.seasons.length}>
                    <Episodes info={props.info!} current={item} extras={extras()} tmdbId={tmdbShow()} />
                </Show>
                <Show when={!props.info?.seasons.length && props.info?.more.length}>
                    <More info={props.info!} />
                </Show>
            </div>
        </article>
    )
}
