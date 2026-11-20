/** Adds `value` as the newest of `set`; past `max` the oldest goes, so the newest `max` are kept and none is refused. */
export function addCapped<T>(set: Set<T>, value: T, max: number): void {
    set.delete(value)
    set.add(value)
    while (set.size > max) set.delete(set.values().next().value as T)
}
