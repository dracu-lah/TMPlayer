// "Choose the right poster" on the web: the TMDB title a viewer picked for one video, kept on this
// device the way the apps keep it (VideoCorrection.pick, stored as `tmdb=film:123`). Pure, so the
// stored text is tested (pick.test.ts) and the store, the poster lookup and the picker share it.
import type { PosterAsk } from './rules'

/** A title the viewer chose: its TMDB id, and whether it is a film or a show. */
export interface Pick {
    id: number
    kind: 'film' | 'show'
}

/** The question the poster lookup puts, with the title the viewer picked in place of the one the name suggests. */
export type PickedAsk = PosterAsk & { picked?: boolean }

/** The stored text for a pick: `tmdb=film:123` or `tmdb=show:123`. */
export const encodePick = (p: Pick): string => `tmdb=${p.kind}:${p.id}`

const STORED = /^tmdb=(film|show):(\d{1,9})$/

/** A pick read back from what storage holds; null for anything that is not one (the value is outside data). */
export function decodePick(text: unknown): Pick | null {
    if (typeof text !== 'string') return null
    for (const line of text.split('\n')) {
        const found = STORED.exec(line.trim())
        const id = found ? Number(found[2]) : 0
        if (found && Number.isSafeInteger(id) && id > 0) return { id, kind: found[1] as Pick['kind'] }
    }
    return null
}

/** Whether `v` is a title the picker's worker answer can carry (guards what comes over the network). */
export function isPick(v: unknown): v is Pick {
    const p = v as Pick | null
    return !!p && typeof p === 'object' && Number.isSafeInteger(p.id) && p.id > 0 && p.id <= 999_999_999 && (p.kind === 'film' || p.kind === 'show')
}

/** Lower case, accents and punctuation gone: enough to tell whether two cleaned titles are one. */
const plain = (title: string) =>
    title
        .toLowerCase()
        .normalize('NFD')
        .replace(/\p{M}+/gu, '')
        .replace(/[^\p{L}\p{N}]+/gu, ' ')
        .trim()

interface Askable {
    chatId: number
    messageId: number
    poster: PosterAsk | null
}

/**
 * The other videos in `pool` that stand for the same title as `item` in its chat: a film needs the
 * same cleaned name and year, a show the same name. For "Also use it for the others"; a video with no
 * poster question (its name asks nothing) has no title to share.
 */
export function sameTitle<T extends Askable>(item: Askable, pool: Iterable<T>): T[] {
    const ask = item.poster
    if (!ask) return []
    const name = plain(ask.title)
    if (!name) return []
    const seen = new Set<number>([item.messageId])
    const out: T[] = []
    for (const other of pool) {
        const b = other.poster
        if (!b || other.chatId !== item.chatId || seen.has(other.messageId)) continue
        if (plain(b.title) !== name || b.series !== ask.series) continue
        if (!ask.series && b.year !== ask.year) continue
        seen.add(other.messageId)
        out.push(other)
    }
    return out
}

/**
 * The poster question for a video once the viewer picked a title for it: that id and kind, flagged
 * so no year has to back it. A show keeps the file's episode code, a film drops it. `title` is the
 * video's own title, for a name that asked nothing by itself.
 */
export function withPick(ask: PosterAsk | null, pick: Pick, title: string): PickedAsk {
    const show = pick.kind === 'show'
    return {
        title: ask?.title || title,
        year: ask?.year ?? null,
        kind: pick.kind,
        tmdbId: pick.id,
        series: show && !!ask?.series,
        languages: ask?.languages ?? [],
        episode: show ? ask?.episode ?? null : null,
        picked: true,
    } as PickedAsk
}
