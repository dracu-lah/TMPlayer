// The show's episodes on a detail page: the chat's own, with TMDB's laid over them.
import { Round } from './Round'
import { createMemo, createResource, createSignal, For, onCleanup, onMount, Show, untrack } from 'solid-js'
import { A, useNavigate } from '@solidjs/router'
import { Cancel01Icon, Clock01Icon, FilterHorizontalIcon, LeftToRightListBulletIcon, ListVideoIcon, Search01Icon, Tick02Icon } from '@hugeicons/core-free-icons'
import { t } from '../../i18n'
import { laterIds, setShowLater, setWatchedMany, showLaterId, watchedList, watchedTick } from '../../store'
import { openListPicker } from '../Lists'
import { showChoices, type MarkChoice } from '../showMarks'
import { agrees, counted, hasGaps, numbering, rekey, rows as guideRows, seasons as guideSeasons, seasonsFor, type HeldSeason, type ProviderEpisode, type Row as GuideRow, type SeasonStop } from '../seriesGuide'
import { loadSeason } from '../seasonFetch'
import { placeholder } from '../../animeGuide'
import '../series.css'
import { Icon } from '../../ui/Icon'
import { arrowKeys } from '../../ui/arrows'
import { form } from '../../form'
import { loadFile, type Extras } from '../posters'
import { shortLength, useProgress, watchPath } from '../VideoList'
import '../versions.css'
import type { VideoItem, WatchInfo } from '../../tg/types'
import { dot } from '../../ui/dot'
import { HeldStill } from './HeldStill'


/** The episode number in a code like "S01E04" or "S01E04a"; 0 when it has none. */
function numberOf(code: string): number {
    return Number(/E(\d+)/i.exec(code)?.[1] ?? 0)
}

const ONLY_MINE = 'tm.series.only_mine'

/** "Only show what I have" (T9), kept per browser; off (the full list) without it or where storage is blocked. */
function readOnlyMine(): boolean {
    try {
        return localStorage.getItem(ONLY_MINE) === '1'
    } catch {
        return false
    }
}

function saveOnlyMine(on: boolean): void {
    try {
        localStorage.setItem(ONLY_MINE, on ? '1' : '0')
    } catch {
        // Storage blocked: the choice lasts until the page is left.
    }
}

/**
 * The show's episodes, a season at a time, the one this page is about marked, the watched ones
 * ticked. Over them the show's own actions, as the apps' show page has them (HX20): Watch later for
 * the whole show, and Mark episodes, whose card lists the season's and the show's lines with how
 * many episodes each changes, so picking one is the confirmation.
 */
export function Episodes(props: { info: WatchInfo; current: VideoItem; extras: Extras | null; tmdbId: string | null }) {
    const progress = useProgress()
    const navigate = useNavigate()
    const seasonOf = () => props.info.seasons.find((s) => s.episodes.some((e) => e.item.messageId === props.current.messageId))?.number ?? props.info.seasons[0]?.number
    const [picked, setPicked] = createSignal<number | null>(null)
    // Every season and episode TMDB knows, laid over the chat's (T9): the chat's own list alone while
    // there is no season list, for a wrong match, or with "Only show what I have" on.
    const today = new Date().toISOString().slice(0, 10)
    const [onlyMine, setOnlyMine] = createSignal(readOnlyMine())
    const heldSeasons = createMemo((): HeldSeason[] =>
        props.info.seasons.map((s) => ({ number: s.number, numbers: s.episodes.map((e) => numberOf(e.code)), covered: s.covered ?? [], packed: props.info.packs.some((p) => p.season === s.number) })),
    )
    // The numbering the chat follows: TMDB's season split (S02E01) or TMDB's own (S01E26), as the apps decide it.
    const numb = createMemo(() => numbering(heldSeasons(), props.extras))
    const listed = () => numb().seasons
    const next = () => numb().next
    const full = createMemo((): SeasonStop[] | null => {
        if (!listed().length && !next()) return null
        return agrees(heldSeasons(), listed()) ? guideSeasons(heldSeasons(), listed(), next(), today) : null
    })
    const guide = () => (onlyMine() ? null : full())
    const stops = createMemo((): SeasonStop[] =>
        guide() ?? heldSeasons().map((s) => ({ number: s.number, have: new Set(s.numbers).size, total: null, state: 'have' as const, airDate: null, special: s.number === 0 })),
    )
    const shown = createMemo(() => {
        const want = picked() ?? seasonOf()
        return stops().some((s) => s.number === want) ? want : (props.info.seasons[0]?.number ?? 0)
    })
    const season = createMemo(() => props.info.seasons.find((s) => s.number === shown()))
    // The whole-season files of the season shown, after its episodes (a pack of several seasons sits under its first).
    const packsHere = () => props.info.packs.filter((p) => p.season === shown())
    const packName = (p: { season: number; seasonEnd: number | null }) => (p.seasonEnd ? t('series.pack_span', { first: p.season, last: p.seasonEnd }) : t('series.pack_whole'))
    // TMDB's episodes of the season shown, one by one (names, stills, days, lengths): the split's own season, or TMDB's, or
    // for a chat counting straight through the TMDB seasons its numbers fall in, put under the chat's numbers. Never throws:
    // no list (no key, offline, nothing known) leaves the page as the counts and the chat's names make it.
    const [provided] = createResource(
        // Only for a match the chat agrees with, or a season it counts straight through (S02E13 for TMDB's second season's first):
        // a wrong match would name its files wrongly.
        () => (props.tmdbId && shown() > 0 && (full() || counted(shown(), heldSeasons().find((h) => h.number === shown())?.numbers ?? [], listed())) ?`${props.tmdbId}|${shown()}|${numb().split ? (props.extras?.seasonGroup ?? '') : ''}|${listed().length}` : null),
        async (key): Promise<ProviderEpisode[] | null> => {
            try {
                const [id, s, group] = key.split('|')
                const n = Number(s)
                const mine = untrack(heldSeasons).find((h) => h.number === n)?.numbers ?? []
                const layout = untrack(listed)
                const lists = new Map<number, ProviderEpisode[]>()
                await Promise.all(seasonsFor(n, mine, layout).map(async (sn) => void lists.set(sn, (await loadSeason(id, sn, group || null)) ?? [])))
                return rekey(n, mine, layout, lists)
            } catch {
                return null
            }
        },
    )
    const listedEpisodes = (): ProviderEpisode[] | null => (provided.state === 'ready' ? provided() ?? null : null)
    const meta = createMemo(() => new Map((listedEpisodes() ?? []).map((e) => [e.number, e])))
    const lines = createMemo((): GuideRow[] => {
        const n = shown()
        if (!guide()) return (season()?.episodes ?? []).map((e) => ({ kind: 'have' as const, number: numberOf(e.code) }))
        return guideRows(n, heldSeasons(), listed().find((s) => s.number === n), next(), today, listedEpisodes())
    })
    const [gap, setGap] = createSignal<{ number: number; state: 'missing' | 'upcoming'; airDate: string | null } | null>(null)
    let gapCard!: HTMLDialogElement
    const code = (n: number) => `S${String(shown()).padStart(2, '0')}E${String(n).padStart(2, '0')}`
    const find = (words: string) => navigate(`/search?q=${encodeURIComponent(words)}`)
    const day = (iso: string) => new Date(iso).toLocaleDateString('en', { day: 'numeric', month: 'short', year: 'numeric' })
    const seasonName = (n: number) => (n === 0 ? t('series.specials') : t('series.season', { number: n }))
    const spokenUpcoming = (n: number, airDate: string | null) => t('series.gap_spoken_upcoming', { number: n, date: airDate ? day(airDate) : t('detail.upcoming') })
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
                {/* The whole show in one of the viewer's named lists (HX43). */}
                <Round icon={LeftToRightListBulletIcon} label={t('lists.add_to')} onClick={() => openListPicker(props.current, props.info.title)} />
                {/* The honest full list is the default; this is the way back to only the viewer's own (T9). */}
                <Show when={onlyMine() ? hasGaps(guideSeasons(heldSeasons(), listed(), next(), today)) && agrees(heldSeasons(), listed()) : hasGaps(stops())}>
                    <Round
                        icon={FilterHorizontalIcon}
                        label={onlyMine() ? t('series.show_all_short') : t('series.only_mine_short')}
                        on={onlyMine()}
                        onClick={() => {
                            const on = !onlyMine()
                            setOnlyMine(on)
                            saveOnlyMine(on)
                            setSaid(on ? t('series.only_mine') : t('series.show_all'))
                        }}
                    />
                </Show>
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
            <div class="season-tabs" role="tablist" onKeyDown={(e) => form() !== 'tv' && arrowKeys(e, e.currentTarget, '[role=tab]', { press: true })}>
                <For each={stops()}>
                    {(s) => (
                        <button role="tab" class="season-chip" classList={{ on: s.number === shown(), quiet: s.state !== 'have' }} aria-selected={s.number === shown()} onClick={() => setPicked(s.number)}>
                            <span class="season-name">
                                {/* "In your chats" by presence, not by a count that may be wrong. */}
                                <Show when={guide() && s.state === 'have'}>
                                    <span class="season-dot" role="img" aria-label={t('series.in_your_chats')} />
                                </Show>
                                {seasonName(s.number)}
                            </span>
                            <small>
                                {!guide()
                                    ? t('series.episodes_count', { count: s.have })
                                    : s.state === 'missing'
                                      ? t('series.not_in_chats')
                                      : s.state === 'upcoming'
                                        ? s.airDate
                                            ? t('detail.airs_on', { date: day(s.airDate) })
                                            : t('detail.upcoming')
                                        : s.total
                                          ? t('series.episodes_count', { count: s.total })
                                          : ''}
                            </small>
                        </button>
                    )}
                </For>
            </div>
            <Show when={guide() && !season()?.episodes.length && !packsHere().length}>
                <div class="gap-note">
                    <p class="muted">{stops().find((s) => s.number === shown())?.state === 'upcoming' ? t('detail.airs_on', { date: day(stops().find((s) => s.number === shown())?.airDate ?? '') }) : t('series.season_gap_note', { season: seasonName(shown()) })}</p>
                    <Show when={stops().find((s) => s.number === shown())?.state === 'missing'}>
                        <button type="button" class="link" onClick={() => find(`${props.info.title} S${String(shown()).padStart(2, '0')}`)}>{t('series.find_elsewhere')}</button>
                    </Show>
                </div>
            </Show>
            <dialog class="confirm card-menu" ref={gapCard} aria-labelledby="gap-title" onClick={(e) => e.target === gapCard && gapCard.close()}>
                <h2 id="gap-title">{gap() ? code(gap()!.number) : ''}</h2>
                <p class="muted">{props.info.title}</p>
                <p>{gap()?.state === 'upcoming' ? (gap()!.airDate ? t('detail.airs_on', { date: day(gap()!.airDate!) }) : t('detail.upcoming')) : gap() ? t('series.gap_missing', { code: code(gap()!.number) }) : ''}</p>
                <Show when={gap()?.state === 'missing'}>
                    <button class="menu-row" type="button" onClick={() => { const g = gap(); gapCard.close(); if (g) find(`${props.info.title} ${code(g.number)}`) }}>
                        <Icon icon={Search01Icon} size={22} />
                        <span>{t('series.find_elsewhere')}</span>
                    </button>
                </Show>
                <div class="confirm-buttons">
                    <button class="link" type="button" onClick={() => gapCard.close()}>{t('common.close')}</button>
                </div>
            </dialog>
            <ol class="episode-list">
                <For each={lines()}>
                    {(line) => (
                        <Show
                            when={line.kind === 'have'}
                            fallback={
                                <li>
                                    <Show
                                        when={line.kind === 'gap' && line.state === 'missing'}
                                        fallback={
                                            // Not aired yet: nothing to do with it, so no control and no focus; read as one line.
                                            <div class="episode gap upcoming" role="group" aria-label={spokenUpcoming(line.number, line.kind === 'gap' ? line.airDate : null)}>
                                                <GapBody number={line.number} season={shown()} state="upcoming" airDate={line.kind === 'gap' ? line.airDate : null} meta={meta().get(line.number)} day={day} />
                                            </div>
                                        }
                                    >
                                        {/* Missing: a dimmed row that wakes up on focus and keeps "Find in other chats" on a press. */}
                                        <button
                                            type="button"
                                            class="episode gap"
                                            aria-label={t('series.gap_spoken_missing', { number: line.number })}
                                            onClick={() => {
                                                if (line.kind !== 'gap') return
                                                setGap({ number: line.number, state: line.state, airDate: line.airDate })
                                                gapCard.showModal()
                                            }}
                                        >
                                            <GapBody number={line.number} season={shown()} state="missing" airDate={line.kind === 'gap' ? line.airDate : null} meta={meta().get(line.number)} day={day} />
                                        </button>
                                    </Show>
                                </li>
                            }
                        >
                            <For each={(season()?.episodes ?? []).filter((e) => numberOf(e.code) === line.number)}>
                                {(e) => {
                                    const here = () => e.item.messageId === props.current.messageId && e.item.chatId === props.current.chatId
                                    return (
                                        <li>
                                            <A class="episode" classList={{ playing: here() }} href={watchPath(e.item)} state={{ video: e.item }} aria-current={here() ? 'true' : undefined}>
                                                <HeldStill
                                                    item={e.item}
                                                    still={meta().get(line.number)?.still ?? null}
                                                    code={e.code}
                                                    number={line.number}
                                                    watched={watchedSet().has(`${e.item.chatId}:${e.item.messageId}`)}
                                                    progress={progress(e.item)}
                                                />
                                                <span class="episode-text">
                                                    <span class="episode-name">{titled(meta().get(line.number), line.number) || (e.name ?? shortLength(e.item.duration))}</span>
                                                    <Show when={heldFacts(e.item, meta().get(line.number), day)}>{(facts) => <span class="episode-facts">{facts()}</span>}</Show>
                                                    <Show when={meta().get(line.number)?.overview}>{(words) => <span class="episode-overview">{words()}</span>}</Show>
                                                </span>
                                            </A>
                                        </li>
                                    )
                                }}
                            </For>
                        </Show>
                    )}
                </For>
                <For each={packsHere()}>
                    {(p) => (
                        <li>
                            <A class="episode pack" href={watchPath(p.item)} state={{ video: p.item }}>
                                <span class="episode-code">{packName(p)}</span>
                                <span class="episode-name">{t('series.pack_note')}</span>
                            </A>
                        </li>
                    )}
                </For>
            </ol>
        </section>
    )
}

/** TMDB's name for an episode, or '' where it has none or only numbers it ("Episode 32" is no name in season 2). */
function titled(meta: ProviderEpisode | undefined, number: number): string {
    return meta?.name && !placeholder(meta.name, number) ? meta.name : ''
}

/** What a held episode says under its name: TMDB's running time (else the file's) and its day. The size is the video page's. */
function heldFacts(item: VideoItem, meta: ProviderEpisode | undefined, day: (iso: string) => string): string {
    if (!meta) return ''
    return [meta.runtime ? shortLength(meta.runtime * 60) : shortLength(item.duration), meta.airDate ? day(meta.airDate) : ''].filter(Boolean).join(dot)
}

/** The picture of an episode the chat lacks: TMDB's still once the row is near the screen (a long season loads only what is seen), else its number. */
function Still(props: { path: string | null; number: number }) {
    const [src, setSrc] = createSignal<string | null>(null)
    let box!: HTMLSpanElement
    onMount(() => {
        const path = props.path
        if (!path) return
        const load = () => void loadFile(path, 'w185').then(setSrc, () => {})
        if (typeof IntersectionObserver === 'undefined') return load()
        const seen = new IntersectionObserver((hits) => {
            if (!hits.some((h) => h.isIntersecting)) return
            seen.disconnect()
            load()
        }, { rootMargin: '300px' })
        seen.observe(box)
        onCleanup(() => seen.disconnect())
    })
    return (
        <span class="episode-still" ref={box} aria-hidden="true">
            <span class="episode-still-number">{props.number}</span>
            <Show when={src()}>
                <img src={src()!} alt="" decoding="async" />
            </Show>
        </span>
    )
}

/**
 * An episode TMDB lists and no chat holds, laid out as a held one and dimmed: its still (else its number), its code and a
 * small tag saying why it does not play, its name, length and day, and a line or two of its words.
 */
function GapBody(props: { number: number; season: number; state: 'missing' | 'upcoming'; airDate: string | null; meta: ProviderEpisode | undefined; day: (iso: string) => string }) {
    const name = () => titled(props.meta, props.number) || t('series.episode', { number: props.number })
    const tag = () => (props.state === 'upcoming' ? (props.airDate ? t('detail.airs_on', { date: props.day(props.airDate) }) : t('detail.upcoming')) : t('series.gap_not_in_chat'))
    // An upcoming one says its day in the tag already.
    const facts = () => [props.meta?.runtime && props.meta.runtime <= 1440 ? shortLength(props.meta.runtime * 60) : '', props.state === 'missing' && props.airDate ? props.day(props.airDate) : ''].filter(Boolean).join(dot)
    return (
        <>
            <Still path={props.meta?.still ?? null} number={props.number} />
            <span class="episode-text">
                <span class="gap-head">
                    <span class="episode-code">{t('series.episode_short', { season: props.season, episode: props.number })}</span>
                    <span class="gap-tag">{tag()}</span>
                </span>
                <span class="episode-name">{name()}</span>
                <Show when={facts()}>
                    <span class="episode-facts">{facts()}</span>
                </Show>
                <Show when={props.meta?.overview}>
                    <span class="episode-overview">{props.meta!.overview}</span>
                </Show>
            </span>
        </>
    )
}
