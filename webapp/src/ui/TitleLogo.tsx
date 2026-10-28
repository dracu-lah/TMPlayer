import { createEffect, createSignal, on, onCleanup, Show } from 'solid-js'
import { Dynamic } from 'solid-js/web'
import { loadLogo, type Extras } from '../screens/posters'

/** How long the name's place is held for a logo still on its way: the apps' LOGO_WAIT_MS. */
const CEILING_MS = 2_500
/** Above this mean luminance a logo is white or near it, and vanishes on a light page (BRIGHT_LOGO). */
const BRIGHT = 0.72

/**
 * A film's or show's name as its title logo from TMDB where it has one, the apps' TitleArt: a
 * logo shaped placeholder while the answer or the picture is on its way, the logo once it has
 * loaded, and the name in type once it is clear there is none (no match, TMDB has none, it would
 * not load) or after CEILING_MS. With `pending` false and no logo known (lookups off, no match)
 * the name is there at once and no placeholder is ever drawn. A white logo is set in the page's
 * ink on a light theme, as the apps do. The heading always reads out as the name.
 */
export function TitleLogo(props: { name: string; pending: boolean; extras: Extras | null | undefined; class: string; level: 1 | 2 }) {
    const [src, setSrc] = createSignal<string | null>(null)
    const [settled, setSettled] = createSignal(false)
    const [bright, setBright] = createSignal(false)
    const [late, setLate] = createSignal(false)
    const timer = setTimeout(() => setLate(true), CEILING_MS)
    onCleanup(() => clearTimeout(timer))
    createEffect(
        on(
            () => props.extras?.logo ?? null,
            (path) => {
                setSrc(null)
                setSettled(!path)
                if (!path || !props.extras) return
                let live = true
                loadLogo(props.extras).then(
                    (u) => live && (setSrc(u), setSettled(!u)),
                    () => live && setSettled(true),
                )
                onCleanup(() => (live = false))
            },
        ),
    )
    const waiting = () => !src() && !late() && (props.pending || !settled())
    return (
        <Dynamic component={props.level === 1 ? 'h1' : 'h2'} class={props.class} classList={{ 'title-logo-slot': !!src() || waiting() }} aria-label={props.name}>
            <Show
                when={src()}
                fallback={
                    <Show when={waiting()} fallback={props.name}>
                        <span class="title-logo-skeleton" aria-hidden="true" />
                    </Show>
                }
            >
                <img
                    class="title-logo"
                    classList={{ bright: bright() }}
                    src={src()!}
                    alt=""
                    decoding="async"
                    onLoad={(e) => setBright(isBright(e.currentTarget))}
                    onError={() => (setSrc(null), setSettled(true))}
                />
            </Show>
        </Dynamic>
    )
}

/** The mean luminance of a logo's solid pixels, sampled small: white or near it above BRIGHT. */
function isBright(img: HTMLImageElement): boolean {
    try {
        const w = 48
        const h = Math.max(1, Math.round((w * img.naturalHeight) / Math.max(1, img.naturalWidth)))
        const canvas = document.createElement('canvas')
        canvas.width = w
        canvas.height = h
        const g = canvas.getContext('2d', { willReadFrequently: true })
        if (!g) return false
        g.drawImage(img, 0, 0, w, h)
        const px = g.getImageData(0, 0, w, h).data
        let sum = 0
        let weight = 0
        for (let i = 0; i < px.length; i += 4) {
            const a = px[i + 3] / 255
            if (a <= 0.5) continue
            sum += ((0.2126 * px[i] + 0.7152 * px[i + 1] + 0.0722 * px[i + 2]) / 255) * a
            weight += a
        }
        return weight > 0 && sum / weight > BRIGHT
    } catch {
        return false
    }
}
