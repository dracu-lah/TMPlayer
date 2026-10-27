// What History's select mode does with its ticks: pure, so it runs under `node --test`.

/** `picked` without the ids no longer in `visible` (an item that vanished while selecting). */
export function prune(picked: ReadonlySet<string>, visible: readonly string[]): Set<string> {
    const here = new Set(visible)
    return new Set([...picked].filter((id) => here.has(id)))
}

/** Shift click: every id from `anchor` to `to` in `order`, both ends included; just `to` with no usable anchor. */
export function range(order: readonly string[], anchor: string | null, to: string): string[] {
    const a = anchor === null ? -1 : order.indexOf(anchor)
    const b = order.indexOf(to)
    if (b < 0) return []
    if (a < 0) return [to]
    return order.slice(Math.min(a, b), Math.max(a, b) + 1)
}

/** Select all, or when everything is ticked already, none. */
export function toggleAll(picked: ReadonlySet<string>, visible: readonly string[]): Set<string> {
    return visible.length > 0 && visible.every((id) => picked.has(id)) ? new Set() : new Set(visible)
}
