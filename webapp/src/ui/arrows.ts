// Arrow keys inside a group that says it is one control (a tab list, a list box): Left and Right (Up
// and Down in a vertical one) move to the next or the one before and wrap, Home and End go to the ends.
// Left and Right swap in a right to left language. The group's own buttons stay reachable by Tab.

/** Where the arrow goes from `at` in `count` entries, or null where the key is not one of the group's. */
export function stepTo(key: string, at: number, count: number, opts: { vertical?: boolean; rtl?: boolean } = {}): number | null {
    if (count <= 0) return null
    if (key === 'Home') return 0
    if (key === 'End') return count - 1
    const forward = opts.vertical ? 'ArrowDown' : opts.rtl ? 'ArrowLeft' : 'ArrowRight'
    const back = opts.vertical ? 'ArrowUp' : opts.rtl ? 'ArrowRight' : 'ArrowLeft'
    if (key !== forward && key !== back) return null
    const from = at < 0 ? 0 : at
    return (from + (key === forward ? 1 : -1) + count) % count
}

/**
 * The keydown handler of a group: moves the keyboard to the entry `stepTo` names, and presses it
 * when `press` is set (a tab list selects as the keyboard arrives). A TV's remote has its own walk.
 */
export function arrowKeys(e: KeyboardEvent, group: HTMLElement, selector: string, opts: { vertical?: boolean; press?: boolean } = {}) {
    if (e.altKey || e.ctrlKey || e.metaKey || e.shiftKey) return
    const items = [...group.querySelectorAll<HTMLElement>(selector)].filter((el) => !(el as HTMLButtonElement).disabled)
    const at = items.indexOf(document.activeElement as HTMLElement)
    if (at < 0) return
    const to = stepTo(e.key, at, items.length, { vertical: opts.vertical, rtl: getComputedStyle(group).direction === 'rtl' })
    if (to === null) return
    e.preventDefault()
    const el = items[to]
    el.focus()
    el.scrollIntoView?.({ block: 'nearest', inline: 'nearest' })
    if (opts.press && to !== at) el.click()
}
