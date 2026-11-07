// The carousel's pages: the rules in featuredRules.ts, then only complete pages (an online match
// with a wide picture and an overview), decided once per fill. Answers on the device come first,
// then two lookups at a time for 1.5 s; a page still unanswered sits this fill out while its lookup
// finishes into the cache for the next one. No page comes or goes while it is looked at. Nothing
// qualifying hides the carousel.
import { createEffect, createMemo, createSignal, on } from 'solid-js'
import { settings } from '../store'
import { answer, askOf } from './posters'
import { candidatesOf, FEATURED_LIMIT, isComplete, type Candidate } from './featuredRules'
import { t } from '../i18n'
import type { HomeRow, Tile } from '../tg/types'

/**
 * A lone episode on a page of its own (a show with one episode in the chat) stands for its show,
 * as the apps' HomeFeatured.loneEpisode has it: the show's name and, on Play, the episode's code.
 * Null for a whole show, a film or an extra.
 */
export function loneEpisode(tile: Tile): { show: string; code: string } | null {
    const ask = tile.item.poster
    if (tile.kind !== 'file' || tile.extra || !ask?.episode || !ask.title.trim()) return null
    return { show: ask.title, code: ask.episode }
}

/** The name a carousel page writes: its show's for a lone episode, the card's otherwise. */
export function pageTitle(tile: Tile, cardTitle: string): string {
    return loneEpisode(tile)?.show ?? cardTitle
}

/** Play or Resume on a carousel page; a lone episode's names the episode ("Resume S01E02"). */
export function pagePlayLabel(tile: Tile, resumed: boolean): string {
    const lone = loneEpisode(tile)
    if (lone) return resumed ? t('series.resume', { code: lone.code }) : t('series.play', { code: lone.code })
    return resumed ? t('common.resume') : t('common.play')
}

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
    asked: !!askOf(t.item),
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
                verdict.set(tile.key, isComplete(await answer(askOf(tile.item)!)))
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

/** The last fill's pages, and the newest candidate it was made from (see useFeatured). */
let lastFill: { newest: string | null; keys: string[] } | null = null

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
                // Home come back to keeps the pages it had (and at once) while every one of them is
                // still a candidate and nothing newer has come in: a fill made again could pick
                // others while the remote is on one of them, and Recently added reading on (more of
                // a chat loaded) is no reason to. A new video at the top is, so it gets its page.
                if (lastFill && lastFill.newest === tiles[0]?.key) {
                    const kept = lastFill.keys.map((k) => tiles.find((t) => t.key === k)).filter((t): t is Tile => !!t)
                    if (kept.length === lastFill.keys.length) return void setPicks(kept)
                }
                state = { dead: false }
                const mineState = state
                void completeKeys(tiles, mineState).then((keys) => {
                    if (mine !== run) return
                    lastFill = { newest: tiles[0]?.key ?? null, keys }
                    setPicks(keys.map((k) => tiles.find((t) => t.key === k)!))
                })
            },
        ),
    )
    return picks
}
