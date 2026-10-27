import { createSignal, onCleanup, onMount, Show } from 'solid-js'
import { ArrowLeft02Icon } from '@hugeicons/core-free-icons'
import { answer, loadBackdropLightFirst, loadPoster } from '../screens/posters'
import { loadThumb } from '../screens/thumbs'
import { shortLength } from '../screens/VideoList'
import { clock } from './Chrome'
import { t } from '../i18n'
import { Icon } from '../ui/Icon'
import { settings } from '../store'
import type { VideoItem, WatchInfo } from '../tg/types'

/**
 * The short pre-roll while a video opens, the apps' PlayerLoader: TMDB's wide picture for the
 * title (or the video's own frame), drifting very slowly behind scrims, the clean name and one
 * line under it, the overview when there is one, and one thin progress line with one small status
 * line. No picture at all leaves a calm tonal ground, never a blurred logo. It stands until the
 * first frame; a stall or a failure hands over to the status sheet.
 *
 * `stage` is how far the opening has come: 0 reading the file, 1 its head read, 2 able to play.
 */
export function OpeningLoader(props: { item: VideoItem; info: WatchInfo | null; at: number; stage: number; onClose: () => void }) {
    const item = props.item
    const [art, setArt] = createSignal<string | null>(null)
    const [poster, setPoster] = createSignal<string | null>(null)
    const [found, setFound] = createSignal<{ title?: string; year?: number | null; overview?: string } | null>(null)
    let wide = false
    onMount(() => {
        if (item.thumb) loadThumb(item.thumb).then((u) => !wide && setArt(u), () => {})
        const ask = item.poster
        if (!ask || !settings().posters) return
        let stop = () => {}
        answer(ask).then(
            (f) => {
                if (!f.match) return
                setFound(f)
                if (f.backdrop) {
                    stop = loadBackdropLightFirst(ask, (u) => {
                        wide = true
                        setArt(u)
                    })
                }
                if (f.poster) loadPoster(ask, 'w342', { priority: true }).then((u) => u && setPoster(u), () => {})
            },
            () => {},
        )
        onCleanup(() => stop())
    })

    const ep = () => props.info?.episode ?? null
    const title = () => found()?.title || ep()?.show || props.info?.title || item.title
    const line = () => {
        const e = ep()
        if (e) return [e.code, e.name].filter(Boolean).join('  ·  ')
        return [found()?.year?.toString(), shortLength(item.duration)].filter(Boolean).join('  ·  ')
    }
    // An episode's own words are not in the poster answer: a show's overview would describe the wrong thing.
    const overview = () => (ep() ? '' : (found()?.overview ?? '').trim())
    const status = () => (props.stage === 0 ? t('player.loader_reading') : props.at > 0 ? t('player.resuming_from', { time: clock(props.at) }) : t('player.loader_starting'))
    const progress = () => [0.12, 0.45, 0.85][Math.min(2, Math.max(0, props.stage))]

    return (
        <div class="pc-loader" classList={{ pictured: !!art() }} role="status" aria-label={status()}>
            <Show when={art()} keyed>
                {(src) => <img class="pc-loader-art" src={src} alt="" />}
            </Show>
            <div class="pc-loader-scrim" />
            <button type="button" class="pc-loader-back" aria-label={t('common.back')} onClick={() => props.onClose()}>
                <Icon icon={ArrowLeft02Icon} size={24} />
            </button>
            <div class="pc-loader-words">
                <Show when={poster()}>
                    <img class="pc-loader-poster" src={poster()!} alt="" />
                </Show>
                <div class="pc-loader-text">
                    <h2 class="pc-loader-title">{title()}</h2>
                    <Show when={line()}>
                        <p class="pc-loader-line">{line()}</p>
                    </Show>
                    <Show when={overview()}>
                        <p class="pc-loader-overview">{overview()}</p>
                    </Show>
                    <div class="pc-loader-progress">
                        <span style={{ transform: `scaleX(${progress()})` }} />
                    </div>
                    <p class="pc-loader-status">{status()}</p>
                </div>
            </div>
        </div>
    )
}
