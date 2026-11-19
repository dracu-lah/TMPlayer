import { createContext, createEffect, createSignal, onCleanup, onMount, untrack } from 'solid-js'
import { loadThumb } from './thumbs'

/**
 * Whether the cards inside may ask for their pictures yet (HX54). Home's rows close it until the row
 * has stayed on screen; a list that has no such rule leaves it open.
 */
export const AskGate = createContext<() => boolean>(() => true)

/** A picture asked again once, a few seconds after a failed ask (HX18): the first asks come while the connection is coming up. */
const AGAIN_MS = 4_000

/** Whether `el` is on screen, live (a card scrolled away is not). A browser with no observer says yes, once drawn. */
export function useOnScreen(el: () => Element | undefined): () => boolean {
    const [on, setOn] = createSignal(false)
    onMount(() => {
        const target = el()
        if (!target) return
        if (typeof IntersectionObserver === 'undefined') return setOn(true)
        const io = new IntersectionObserver((entries) => {
            const last = entries[entries.length - 1]
            if (last) setOn(last.isIntersecting)
        })
        io.observe(target)
        onCleanup(() => io.disconnect())
    })
    return on
}

/**
 * A card's thumbnail: asked once the card is on screen and its row (`open`) allows it, taken back
 * the moment the card leaves the screen, is drawn away or its video changes, so a fast scroll asks
 * for the few cards it stops on and not the hundreds it passed. `seen` turns true the first time
 * the card was live (for the asks that follow, a poster or a backdrop, which stay once begun).
 */
export function useCardThumb(o: { el: () => Element | undefined; thumb: () => string | null | undefined; skip: () => boolean; open?: () => boolean; onUrl: (url: string) => void }) {
    const onScreen = useOnScreen(o.el)
    const live = () => onScreen() && (o.open?.() ?? true)
    const [seen, setSeen] = createSignal(false)
    createEffect(() => live() && setSeen(true))
    createEffect(() => {
        const thumb = o.thumb()
        if (!live() || !thumb || o.skip()) return
        const ctl = new AbortController()
        let again: ReturnType<typeof setTimeout> | undefined
        const ask = (retry: boolean) =>
            loadThumb(thumb, ctl.signal).then(
                (url) => !ctl.signal.aborted && untrack(() => o.onUrl(url)),
                () => {
                    if (retry && !ctl.signal.aborted) again = setTimeout(() => !ctl.signal.aborted && ask(false), AGAIN_MS)
                },
            )
        void ask(true)
        onCleanup(() => {
            clearTimeout(again)
            ctl.abort()
        })
    })
    return { seen, onScreen }
}
