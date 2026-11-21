import { createSignal, onMount, Show, type JSX } from 'solid-js'
import { Alert02Icon } from '@hugeicons/core-free-icons'
import { Icon } from '../ui/Icon'
import { loadThumb } from '../screens/thumbs'
import type { VideoItem } from '../tg/types'

/**
 * The player's loading and failure sheet (the app's status_sheet): the video's picture, its name,
 * what happened in a heading and a line, and the ways on as pills in a row that wraps. Stacked on
 * a phone held upright, the picture beside the words where the height is short.
 */
export function StatusSheet(props: { item: VideoItem; title: string; heading: string; body: string; note?: string; warning: boolean; format?: string; children: JSX.Element }) {
    const [art, setArt] = createSignal<string | null>(null)
    onMount(() => {
        if (props.item.thumb && !props.warning) loadThumb(props.item.thumb).then(setArt, () => {})
    })
    return (
        <div class="pc-status" role="alert">
            <div class="pc-status-inner" classList={{ pictured: !!art() }}>
                <Show when={art()}>
                    <img class="pc-status-art" src={art()!} alt="" />
                </Show>
                <div class="pc-status-text">
                    <p class="pc-status-name">{props.title}</p>
                    <Show when={props.warning}>
                        <span class="pc-status-warning">
                            <Icon icon={Alert02Icon} size={40} />
                        </span>
                    </Show>
                    <h2>{props.heading}</h2>
                    <p class="pc-status-body">{props.body}</p>
                    <Show when={props.format}>
                        <p class="pc-status-format">{props.format}</p>
                    </Show>
                    <Show when={props.note}>
                        <p class="pc-status-body">{props.note}</p>
                    </Show>
                    <div class="pc-status-actions">{props.children}</div>
                </div>
            </div>
        </div>
    )
}
