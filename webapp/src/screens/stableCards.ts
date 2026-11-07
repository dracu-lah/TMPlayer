// Keeps a list's card objects the same between updates (see stableCards). Plain TypeScript, so it is testable.

/** What stableCards compares of a card: what the card draws. */
export interface StableCard {
    key: string
    title: string
    facts: string
    item: {
        key: string
        thumb: string | null
        cover?: string | null
        size: number
        duration: number
        name: string
        chat: string
        poster: unknown
    }
}

/**
 * Maps tiles to cards, handing back the SAME card object for a tile whose card did not change since
 * the last call. The worker answers with new objects on every page and every re-arrangement, and the
 * grid keeps a card's DOM by the object it was drawn from: rebuilt each time, every card on the page
 * was drawn again at each new page (the flicker on Recently added's See all and in a chat).
 */
export function stableCards<T, C extends StableCard>(make: (tile: T) => C): (tiles: readonly T[]) => C[] {
    let kept = new Map<string, C>()
    const same = (a: C, b: C) =>
        a.title === b.title &&
        a.facts === b.facts &&
        a.item.key === b.item.key &&
        a.item.thumb === b.item.thumb &&
        a.item.cover === b.item.cover &&
        a.item.size === b.item.size &&
        a.item.duration === b.item.duration &&
        a.item.name === b.item.name &&
        a.item.chat === b.item.chat &&
        JSON.stringify(a.item.poster) === JSON.stringify(b.item.poster)
    return (tiles) => {
        const next = new Map<string, C>()
        const out = tiles.map((tile) => {
            const card = make(tile)
            const before = kept.get(card.key)
            const use = before && same(before, card) ? before : card
            next.set(card.key, use)
            return use
        })
        kept = next
        return out
    }
}

