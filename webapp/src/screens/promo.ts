import { createEffect, createSignal } from 'solid-js'
import type { VideoItem } from '../tg/types'
import { promoCovers } from './promoRules'

// The covers found to be an uploader's advert in any list this visit has shown (the apps'
// PromoCoverBook). The same picture is the same advert wherever it shows. At most MAX kept.
const MAX = 2048
const [flagged, setFlagged] = createSignal<ReadonlySet<string>>(new Set())

/** Reads `items` for adverts, and keeps any new cover found. */
export function observeCovers(items: readonly VideoItem[]) {
    const found = promoCovers(items)
    if (!found.size) return
    const held = flagged()
    if ([...found].every((c) => held.has(c))) return
    const next = new Set(held)
    for (const c of found) {
        next.delete(c)
        next.add(c)
    }
    while (next.size > MAX) next.delete(next.values().next().value as string)
    setFlagged(next)
}

/** True when `cover` (a video's `cover`) is a flagged advert. Reactive. */
export function isPromoCover(cover: string | null | undefined): boolean {
    return !!cover && flagged().has(cover)
}

/** Observes a list as it changes: a chat's page, a Home row. Computed once per change, not per card. */
export function useObservedCovers(items: () => readonly VideoItem[]) {
    createEffect(() => observeCovers(items()))
}
