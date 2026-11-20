// The one way a screen listens to the keyboard, the wheel or the pointer on the whole page (window or
// document). While the profile is locked (HX88: the picker at launch or locked again after a minute
// away) the page behind the picker hears nothing: a player's `k` must not play the locked video, nor
// Ctrl+P open it in picture in picture. ui/globalInput.test.ts fails on a page-wide input listener
// added anywhere else, as core's policy tests do.
import { locked } from '../accounts'

type Target = Window | Document

/**
 * Adds `handler` for `type` on the whole page, skipped while the profile is locked; returns the way
 * to remove it.
 */
export function onGlobal<E extends Event>(target: Target, type: string, handler: (e: E) => void, options?: boolean | AddEventListenerOptions): () => void {
    const wrapped = (e: Event) => {
        if (locked()) return
        handler(e as E)
    }
    target.addEventListener(type, wrapped, options)
    return () => target.removeEventListener(type, wrapped, options)
}
