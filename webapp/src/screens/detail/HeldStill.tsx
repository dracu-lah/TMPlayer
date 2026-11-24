// The 16:9 picture of an episode the chat holds, for the show's page and the player's episode list:
// Telegram's own thumbnail for the video, TMDB's still over it once it arrives, the episode number
// where there is neither. The code sits on its top start, the watched tick on its top end and the
// progress along its foot. Both pictures are asked for only once the card is near the screen, so a
// season of 500 episodes loads the few in view, and nothing is kept here: the thumbnail loader holds
// the bounded cache.
import { createEffect, createSignal, onCleanup, Show } from 'solid-js'
import { Tick02Icon } from '@hugeicons/core-free-icons'
import { Icon } from '../../ui/Icon'
import { t } from '../../i18n'
import { useCardThumb } from '../cardAsk'
import { loadFile } from '../posters'
import { isPromoCover } from '../promo'
import type { VideoItem } from '../../tg/types'

export function HeldStill(props: { item: VideoItem; still?: string | null; code: string; number: number; watched: boolean; progress?: number }) {
    const [frame, setFrame] = createSignal<string | null>(null)
    const [still, setStill] = createSignal<string | null>(null)
    let box!: HTMLSpanElement
    const { seen } = useCardThumb({ el: () => box, thumb: () => props.item.thumb, skip: () => isPromoCover(props.item.cover), onUrl: setFrame })
    createEffect(() => {
        const path = props.still
        if (!seen() || !path) return void setStill(null)
        let live = true
        loadFile(path, 'w185').then(
            (url) => live && setStill(url),
            () => {},
        )
        onCleanup(() => (live = false))
    })
    return (
        <span class="episode-still" ref={box} aria-hidden={props.watched ? undefined : 'true'}>
            <Show when={!frame() && !still()}>
                <span class="episode-still-number">{props.number}</span>
            </Show>
            <Show when={frame()}>
                <img src={frame()!} alt="" decoding="async" loading="lazy" />
            </Show>
            <Show when={still()}>
                <img src={still()!} alt="" decoding="async" loading="lazy" />
            </Show>
            <Show when={frame() || still()}>
                <span class="episode-badge">{props.code}</span>
            </Show>
            <Show when={props.watched}>
                <span class="episode-watched" role="img" aria-label={t('detail.watched_line')}>
                    <Icon icon={Tick02Icon} size={14} />
                </span>
            </Show>
            <Show when={props.progress}>
                {(p) => (
                    <span class="episode-progress">
                        <span style={{ width: `${Math.min(100, p() * 100)}%` }} />
                    </span>
                )}
            </Show>
        </span>
    )
}
