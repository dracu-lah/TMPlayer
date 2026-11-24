// The smaller pieces of the player's controls: the desktop's volume, the phone's speed sheet, the episodes panel
// and the sentences the top bar and the sheets show.
import { createMemo, createResource, createSignal, For, onMount, Show } from 'solid-js'
import { Add01Icon, Cancel01Icon, MinusSignIcon, NextIcon, PreviousIcon } from '@hugeicons/core-free-icons'
import { Icon, type IconData } from '../../ui/Icon'
import { t } from '../../i18n'
import { PhoneSheet } from '../sheets'
import { type MsoneTarget } from '../msone-sheet'
import { episodeOf } from '../msone'
import type { ChatSubtitle, VideoItem, WatchInfo } from '../../tg/types'
import { FINE_STEP, SPEEDS, SPEED_MAX, SPEED_MIN, speedLabel } from './parts'
import { dot } from '../../ui/dot'
import { HeldStill } from '../../screens/detail/HeldStill'
import { useProgress } from '../../screens/VideoList'
import { watchedList, watchedTick } from '../../store'

export function chatDetail(sub: ChatSubtitle, busy: boolean): string | undefined {
    if (busy) return t('online.downloading')
    let language: string | undefined
    try {
        if (sub.language) language = new Intl.DisplayNames([navigator.language], { type: 'language' }).of(sub.language)
    } catch {}
    const parts = [language, sub.forced ? t('tracks.forced') : '', sub.hearing ? t('online.hearing_impaired') : '', sub.archive ? t('tracks.in_chat_compressed') : '']
    return parts.filter((p) => p).join(dot) || undefined
}

/**
 * What MSone is asked about the video: the title the poster lookup cleaned from its name (the
 * show's, for an episode) and its year, and the episode's season and number from its code ("S1 E3").
 */
export function msoneTarget(item: VideoItem, info: WatchInfo | null): MsoneTarget {
    const ep = info?.episode ? episodeOf(info.episode.code) : null
    return {
        q: item.poster?.title || info?.episode?.show || item.title,
        year: item.poster?.year ?? null,
        season: ep?.season ?? null,
        episode: ep?.episode ?? null,
    }
}

/**
 * The top bar's second line: the episode's code and name, then the chat, as the phone, TV and
 * desktop players write it. The length and size stay in the details.
 */
export function subtitleLine(info: WatchInfo | null, item: VideoItem): string {
    const ep = info?.episode
    const chat = info?.chatTitle || item.chat
    return [ep ? (ep.name ? `${ep.code}${dot}${ep.name}` : ep.code) : '', chat].filter((p) => p).join(dot)
}

/** The desktop's volume: the speaker button, and on hover a slider beside it (VolumeControl). */
export function VolumeControl(props: { volume: () => number; muted: () => boolean; icon: () => IconData; onVolume: (x: number) => void; onMute: () => void }) {
    return (
        <div class="pc-volume">
            <input
                type="range"
                min="0"
                max="100"
                step="1"
                aria-label={t('keys.row_volume')}
                value={props.muted() ? 0 : Math.round(props.volume() * 100)}
                style={{ '--v': `${props.muted() ? 0 : props.volume() * 100}%` }}
                onInput={(e) => props.onVolume(Number(e.currentTarget.value) / 100)}
            />
            <button type="button" class="pc-btn" aria-label={props.muted() ? t('player.unmute_hint') : t('player.mute_hint')} data-tip={props.muted() ? t('player.unmute_hint') : t('player.mute_hint')} onClick={props.onMute}>
                <Icon icon={props.icon()} size={24} />
            </button>
        </div>
    )
}


/**
 * The phone's speed sheet: the presets as tiles (four to a row upright, all seven sideways), then
 * the fine control, minus and plus either side of the figure, a press at a time.
 */
export function SpeedSheet(props: { rate: () => number; landscape: boolean; onPick: (s: number) => void; onFine: (s: number) => void; onClose: () => void }) {
    const fine = (up: boolean) => props.onFine(Math.round((props.rate() + (up ? FINE_STEP : -FINE_STEP)) * 100) / 100)
    return (
        <PhoneSheet title={t('player.playback_speed')} onClose={props.onClose} class="speed">
            <div class="pc-tiles" classList={{ wide: props.landscape }}>
                <For each={SPEEDS}>
                    {(s) => (
                        <button type="button" class="pc-tile" role="radio" aria-checked={Math.abs(props.rate() - s) < 0.001} onClick={() => props.onPick(s)}>
                            {speedLabel(s)}
                        </button>
                    )}
                </For>
            </div>
            <div class="pc-fine">
                <div class="pc-fine-head">{t('player.speed_fine')}</div>
                <div class="pc-fine-detail">{t('player.speed_fine_detail', { step: speedLabel(FINE_STEP), min: speedLabel(SPEED_MIN), max: speedLabel(SPEED_MAX) })}</div>
                <div class="pc-fine-row">
                    <button type="button" class="pc-round" aria-label={t('player.speed_slower')} disabled={props.rate() <= SPEED_MIN + 0.001} onClick={() => fine(false)}>
                        <Icon icon={MinusSignIcon} size={24} />
                    </button>
                    <span class="pc-fine-value" aria-live="polite">{speedLabel(props.rate())}</span>
                    <button type="button" class="pc-round" aria-label={t('player.speed_faster')} disabled={props.rate() >= SPEED_MAX - 0.001} onClick={() => fine(true)}>
                        <Icon icon={Add01Icon} size={24} />
                    </button>
                </div>
            </div>
        </PhoneSheet>
    )
}

/**
 * The episode list (EpisodesPanel): the show and its count at the top, the previous and next
 * steps, the seasons as chips and the season's episodes, the one playing marked. On a phone the
 * whole screen; on the desktop a panel in the middle of the picture.
 */
export function EpisodesPanel(props: { info: WatchInfo; current: VideoItem; phone: boolean; autoplay: boolean; onAutoplay: (on: boolean) => void; onPlay: (v: VideoItem) => void; onClose: () => void }) {
    const seasonOf = () => props.info.seasons.find((s) => s.episodes.some((e) => e.item.messageId === props.current.messageId))?.number ?? props.info.seasons[0]?.number
    const [picked, setPicked] = createSignal<number | null>(null)
    const season = createMemo(() => props.info.seasons.find((s) => s.number === (picked() ?? seasonOf())) ?? props.info.seasons[0])
    const count = () => props.info.seasons.reduce((n, s) => n + s.episodes.length, 0)
    const show = () => props.info.episode?.show ?? props.info.title
    let el!: HTMLDivElement
    const progress = useProgress()
    const [seen] = createResource(watchedTick, async () => new Set((await watchedList()).map((w) => `${w.chatId}:${w.messageId}`)))
    const watchedSet = () => seen.latest ?? new Set<string>()
    // The one playing is focused first and scrolled to the middle of the list, so the viewer sees where they are.
    onMount(() => {
        const now = el.querySelector<HTMLElement>('.pc-episode.playing')
        ;(now ?? el.querySelector<HTMLElement>('.pc-episode'))?.focus({ preventScroll: true })
        now?.scrollIntoView({ block: 'center' })
    })
    return (
        <div class="pc-sheet-scrim episodes" onClick={(e) => e.target === e.currentTarget && props.onClose()}>
            <div ref={el} class="pc-sheet pc-episodes" role="dialog" aria-label={t('episodes.title')}>
                <div class="pc-sheet-head">
                    <div>
                        <h2>{t('episodes.title')}</h2>
                        <p>{`${show()}  ·  ${t('series.summary', { seasons: props.info.seasons.length, episodes: count() })}`}</p>
                    </div>
                    <button type="button" class="pc-sheet-close" aria-label={t('common.close')} onClick={props.onClose}>
                        <Icon icon={Cancel01Icon} size={24} />
                    </button>
                </div>
                <div class="pc-episodes-body">
                    <div class="pc-episodes-side">
                        <button type="button" class="pc-step-line pc-switch" role="switch" aria-checked={props.autoplay} onClick={() => props.onAutoplay(!props.autoplay)}>
                            <span class="pc-sw" aria-hidden="true" />
                            {t('episodes.autoplay')}
                        </button>
                        <Show when={props.info.previous}>
                            <button type="button" class="pc-step-line" onClick={() => props.onPlay(props.info.previous!)}>
                                <Icon icon={PreviousIcon} size={18} />
                                {t('player.previous_up', { title: props.info.previousCode ?? props.info.previous!.title })}
                            </button>
                        </Show>
                        <Show when={props.info.next}>
                            <button type="button" class="pc-step-line" onClick={() => props.onPlay(props.info.next!)}>
                                <Icon icon={NextIcon} size={18} />
                                {t('player.next_up', { title: props.info.nextCode ?? props.info.next!.title })}
                            </button>
                        </Show>
                    </div>
                    <div class="pc-episodes-main">
                        <Show when={props.info.seasons.length > 1}>
                            <div class="pc-seasons" role="tablist">
                                <For each={props.info.seasons}>
                                    {(s) => (
                                        <button type="button" role="tab" class="pc-season" aria-selected={s.number === season()?.number} onClick={() => setPicked(s.number)}>
                                            <span>{t('series.season', { number: s.number })}</span>
                                            <small>{t('series.episodes_count', { count: s.episodes.length })}</small>
                                        </button>
                                    )}
                                </For>
                            </div>
                        </Show>
                        <ol class="pc-episode-list">
                            <For each={season()?.episodes ?? []}>
                                {(e) => {
                                    const playing = () => e.item.messageId === props.current.messageId && e.item.chatId === props.current.chatId
                                    return (
                                        <li>
                                            <button type="button" class="pc-episode" classList={{ playing: playing() }} aria-current={playing() ? 'true' : undefined} onClick={() => !playing() && props.onPlay(e.item)}>
                                                <HeldStill
                                                    item={e.item}
                                                    code={e.code}
                                                    number={Number(/E(\d+)/i.exec(e.code)?.[1] ?? 0)}
                                                    watched={watchedSet().has(`${e.item.chatId}:${e.item.messageId}`)}
                                                    progress={progress(e.item)}
                                                />
                                                <span class="pc-episode-text">
                                                    <span class="pc-episode-name">{e.name || t('series.episode', { number: Number(/E(\d+)/i.exec(e.code)?.[1] ?? 0) })}</span>
                                                    <span class="pc-episode-facts">
                                                        <Show when={playing()}><span class="pc-badge">{t('episodes.playing')}</span></Show>
                                                        {facts(e.item)}
                                                    </span>
                                                </span>
                                            </button>
                                        </li>
                                    )
                                }}
                            </For>
                        </ol>
                    </div>
                </div>
            </div>
        </div>
    )
}

/** An episode's running time; the file's size is the video page's (user, 2026-11-25). */
export function facts(v: VideoItem): string {
    const m = Math.round(v.duration / 60)
    return m ? `${m}m` : ''
}
