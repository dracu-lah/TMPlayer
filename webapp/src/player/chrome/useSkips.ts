// Skip offers (intro, recap, credits) from the file's own chapters, and for an anime AniList matched what AniSkip holds
// (the worker's /tmdb/skip, by the file's episode number and length); the file's own chapters stay first, and a
// database's second opening beside them is not wanted (SkipMerge).
import { createEffect, createMemo, createSignal, onCleanup } from 'solid-js'
import { t } from '../../i18n'
import { answer, askOf, loadAnimeSkips } from '../../screens/posters'
import { settings } from '../../store'
import { skipSegments, type SkipSegment } from '../chapters'
import { mergeSkips } from '../skipOnline'
import type { Ctl } from './types'

export function useSkips(c: Ctl, listen: (name: 'timeupdate', fn: () => void) => void, duration: () => number, seekTo: (s: number) => void) {
    const v = c.video
    const [online, setOnline] = createSignal<SkipSegment[]>([])
    createEffect(() => {
        const code = c.info()?.episode?.code
        const ask = askOf(c.item)
        const number = code ? /^S\d+E(\d+)/i.exec(code)?.[1] : undefined
        if (!ask || !number || !settings().posters) return
        let live = true
        onCleanup(() => (live = false))
        answer(ask).then(
            (found) => {
                if (!live || !found.match || found.source !== 'anilist' || found.kind !== 'show' || !found.id) return
                loadAnimeSkips(found.id, Number(number), c.item.duration || 0).then((list) => live && setOnline(list), () => {})
            },
            () => {},
        )
    })
    const segments = createMemo(() => (c.info()?.episode ? mergeSkips(skipSegments(c.chapters(), duration()), online(), duration()) : []))
    const [now, setNow] = createSignal<SkipSegment | null>(null)
    listen('timeupdate', () => {
        const list = segments()
        if (!list.length) return void (now() && setNow(null))
        const p = v.currentTime
        const hit = list.find((s) => p >= s.start && p < s.end - 1) ?? null
        if (hit !== now()) setNow(hit)
    })
    const label = (s: SkipSegment) => t(`player.skip_${s.kind}`)
    const skip = () => {
        const s = now()
        if (s) (seekTo(s.end), setNow(null))
    }
    return { skipNow: now, skipLabel: label, skipIt: skip }
}
