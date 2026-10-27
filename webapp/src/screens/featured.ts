// The carousel's pages: the rules in featuredRules.ts, then only complete pages (an online match
// with a wide picture and an overview), decided once per fill. Answers on the device come first,
// then two lookups at a time for 1.5 s; a page still unanswered sits this fill out while its lookup
// finishes into the cache for the next one. No page comes or goes while it is looked at. Nothing
// qualifying hides the carousel.
import { createEffect, createMemo, createSignal, on } from 'solid-js'
import { settings } from '../store'
import { answer } from './posters'
import { candidatesOf, FEATURED_LIMIT, isComplete, type Candidate } from './featuredRules'
import type { HomeRow, Tile } from '../tg/types'

const FILL_WAIT_MS = 1500
const LOOKUPS_AT_ONCE = 2

const asCandidate = (t: Tile): Candidate & { tile: Tile } => ({
    tile: t,
    key: t.key,
    kind: t.kind,
    title: t.title,
    extra: t.extra,
    size: t.item.size,
    duration: t.item.duration,
    asked: !!t.item.poster,
})

/** The keys of `list` that are complete, in order, at most the carousel's pages. */
async function completeKeys(list: Tile[], gone: { dead: boolean }): Promise<string[]> {
    const verdict = new Map<string, boolean | null>(list.map((t) => [t.key, null]))
    const settled = () => {
        let n = 0
        for (const t of list) {
            const v = verdict.get(t.key)
            if (v === null) return false
            if (v && ++n >= FEATURED_LIMIT) return true
        }
        return true
    }
    let wake = () => {}
    let next = 0
    const worker = async () => {
        while (!gone.dead && next < list.length) {
            const tile = list[next++]
            try {
                verdict.set(tile.key, isComplete(await answer(tile.item.poster!)))
            } catch {
                verdict.set(tile.key, false)
            }
            wake()
        }
    }
    for (let i = 0; i < LOOKUPS_AT_ONCE; i++) void worker()
    await new Promise<void>((done) => {
        const timer = window.setTimeout(done, FILL_WAIT_MS)
        wake = () => {
            if (settled()) {
                clearTimeout(timer)
                done()
            }
        }
        wake()
    })
    return list.filter((t) => verdict.get(t.key) === true).slice(0, FEATURED_LIMIT).map((t) => t.key)
}

/**
 * The carousel's pages: null while Recently added or the lookups are pending, an empty list when
 * nothing qualifies (the carousel is then not drawn). With Posters and details off there is no
 * online data, so there is no carousel.
 */
export function useFeatured(rows: () => HomeRow[] | undefined): () => Tile[] | null {
    const recent = createMemo<Tile[] | null>(() => {
        const r = rows()
        if (!r) return null
        const row = r.find((x) => x.kind === 'recent')
        if (!row) return []
        return row.loaded ? row.tiles : null
    })
    const list = createMemo(() => {
        const tiles = recent()
        return tiles ? candidatesOf(tiles.map(asCandidate)).map((c) => c.tile) : null
    })
    const [picks, setPicks] = createSignal<Tile[] | null>(null)
    let run = 0
    let state = { dead: false }
    createEffect(
        on(
            () => [list()?.map((t) => t.key).join('|') ?? null, settings().posters] as const,
            ([sig, online]) => {
                const mine = ++run
                state.dead = true
                if (sig === null) return
                const tiles = list()!
                if (!online || !tiles.length) return void setPicks([])
                state = { dead: false }
                const mineState = state
                void completeKeys(tiles, mineState).then((keys) => {
                    if (mine === run) setPicks(keys.map((k) => tiles.find((t) => t.key === k)!))
                })
            },
        ),
    )
    return picks
}
