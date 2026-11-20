// The desktop app's window keys (docs/shortcuts.md, "Desktop: the window") for the web shell: which
// chord goes where, and how the arrows walk a grid. The rules are pure so they run under `node --test`;
// installShellKeys is the one listener that applies them. A TV has its own remote walk (tv/focus.ts) and
// the player its own keys (player/), so neither is touched from here.

/** What a chord asks of the shell. */
export type ShellAction = 'search' | 'findInPage' | 'chooseChats' | 'downloads' | 'home' | 'chats' | 'cardMenu'

/** The parts of a KeyboardEvent the key map reads. */
export interface KeyLike {
    key: string
    code?: string
    ctrlKey: boolean
    metaKey: boolean
    altKey: boolean
    shiftKey: boolean
    isComposing?: boolean
    repeat?: boolean
}

export interface KeyContext {
    /** Command is the modifier (a Mac, an iPhone or iPad with a keyboard); elsewhere it is Ctrl. */
    mac: boolean
    /** A text field has the keyboard: plain keys stay its. */
    typing: boolean
    /** The page is an installed app (no browser tabs): only there Command or Ctrl and a digit are the page's, in a tab they switch tabs. */
    standalone?: boolean
}

/** Whether this is Apple's keyboard layout, where Command takes Ctrl's place. */
export function isMac(platform: string = (typeof navigator === 'undefined' ? '' : navigator.platform)): boolean {
    return /Mac|iPhone|iPad/.test(platform)
}

/** What a field the keyboard types into is: not a button, a checkbox or a radio. */
export function isTextField(el: { tagName?: string; type?: string; isContentEditable?: boolean } | null | undefined): boolean {
    if (!el) return false
    if (el.isContentEditable) return true
    const tag = (el.tagName ?? '').toUpperCase()
    if (tag === 'TEXTAREA' || tag === 'SELECT') return true
    if (tag !== 'INPUT') return false
    return !/^(checkbox|radio|button|submit|reset|range|color|file|image)$/i.test(el.type ?? 'text')
}

/**
 * The shell action a keydown asks for, or null. Chords with the command key work inside a text field
 * (as the desktop app's preview key does); `/` and the menu key only where nothing is being typed.
 * Chords the browser keeps for itself (Cmd or Ctrl with W, T, N, Q, a tab's number in a tabbed window)
 * are never taken here: docs/shortcuts.md lists them.
 */
export function shellAction(e: KeyLike, ctx: KeyContext): ShellAction | null {
    if (e.isComposing || e.key === 'Process') return null
    const mod = ctx.mac ? e.metaKey && !e.ctrlKey : e.ctrlKey && !e.metaKey
    const key = e.key.length === 1 ? e.key.toLowerCase() : e.key
    // A letter chord is the letter on the keycap when it is a Latin one, else the key's place (KeyK): a Cyrillic or Greek layout keeps Ctrl+K.
    const letter = /^[a-z]$/.test(key) ? key : /^Key[A-Z]$/.test(e.code ?? '') ? e.code!.slice(3).toLowerCase() : key
    // The digit row's own code, so Shift or a layout's symbols on it do not matter.
    const digit = e.code === 'Digit1' || e.code === 'Numpad1' ? '1' : e.code === 'Digit2' || e.code === 'Numpad2' ? '2' : key
    if (mod && !e.altKey) {
        if (!e.shiftKey) {
            if (letter === 'k') return 'search'
            if (letter === 'f') return 'findInPage'
            if (letter === 'j') return 'downloads'
            // In a browser tab these two are the browser's (they switch tabs): only an installed app has them free.
            if (ctx.standalone && digit === '1') return 'home'
            if (ctx.standalone && digit === '2') return 'chats'
        } else if (letter === 'a') return 'chooseChats'
        return null
    }
    if (e.ctrlKey || e.metaKey || e.altKey) return null
    if (ctx.typing) return null
    // `/` is the key's own character: on a layout where it needs Shift (German, Nordic) that is Shift+7 and still counts.
    if (key === '/') return 'findInPage'
    if (key === 'ContextMenu' && !e.shiftKey) return 'cardMenu'
    if (key === 'F10' && e.shiftKey) return 'cardMenu'
    return null
}

/** Whether the window is an installed app (a PWA, Safari's Home Screen app): no tab strip, so Command or Ctrl and a digit are free. */
export function isStandalone(): boolean {
    try {
        return window.matchMedia('(display-mode: standalone)').matches || (navigator as { standalone?: boolean }).standalone === true
    } catch {
        return false
    }
}

/** One row of a grid: its first item and the one after its last, as VirtualGrid lays them out. */
export type Row = readonly [number, number]

/** The row holding item `at`, or -1: the rows run in order, so it is a binary search (a chat of 100,000 videos). */
export function rowOf(rows: readonly Row[], at: number): number {
    let lo = 0
    let hi = rows.length - 1
    while (lo <= hi) {
        const mid = (lo + hi) >> 1
        const [a, b] = rows[mid]
        if (at < a) hi = mid - 1
        else if (at >= b) lo = mid + 1
        else return mid
    }
    return -1
}

/**
 * Whether the grid takes this key whatever happens: Home, End and the Page keys always do, even at an edge where
 * they have nowhere to go, because the page's own answer (scrolling the window) would leave the keyboard on a card
 * that is off screen. The arrows at an edge keep the page's small scroll.
 */
export function gridOwns(key: string, mods: { ctrl?: boolean; alt?: boolean; meta?: boolean; shift?: boolean }): boolean {
    if (mods.alt || mods.meta || mods.shift) return false
    if (key === 'Home' || key === 'End') return true
    return !mods.ctrl && (key === 'PageUp' || key === 'PageDown')
}

/**
 * Where the arrows go from item `at` in a grid of `rows`: Left and Right one item (across the rows),
 * Up and Down the same column one row away, Home and End the row's ends, Ctrl+Home and Ctrl+End the
 * first and last item, PageUp and PageDown `pageRows` rows. Nothing wraps: at an edge the answer is
 * null and the page scrolls as it would. Null as well for any key that is not the grid's, or one
 * with Alt, Cmd or Shift (Ctrl only with Home and End).
 */
export function gridTarget(key: string, mods: { ctrl?: boolean; alt?: boolean; meta?: boolean; shift?: boolean }, at: number, rows: readonly Row[], pageRows: number): number | null {
    if (mods.alt || mods.meta || mods.shift) return null
    const total = rows.length ? rows[rows.length - 1][1] : 0
    if (!Number.isInteger(at) || at < 0 || at >= total) return null
    if (mods.ctrl && key !== 'Home' && key !== 'End') return null
    const row = rowOf(rows, at)
    if (row < 0) return null
    const [start, end] = rows[row]
    const column = at - start
    const inRow = (r: number) => Math.min(rows[r][0] + column, rows[r][1] - 1)
    let to: number
    switch (key) {
        case 'ArrowLeft':
            to = at - 1
            break
        case 'ArrowRight':
            to = at + 1
            break
        case 'ArrowUp':
            if (row === 0) return null
            to = inRow(row - 1)
            break
        case 'ArrowDown':
            if (row === rows.length - 1) return null
            to = inRow(row + 1)
            break
        case 'PageUp':
            if (row === 0) return null
            to = inRow(Math.max(0, row - Math.max(1, pageRows)))
            break
        case 'PageDown':
            if (row === rows.length - 1) return null
            to = inRow(Math.min(rows.length - 1, row + Math.max(1, pageRows)))
            break
        case 'Home':
            to = mods.ctrl ? 0 : start
            break
        case 'End':
            to = mods.ctrl ? total - 1 : end - 1
            break
        default:
            return null
    }
    return to < 0 || to >= total || to === at ? null : to
}

/** The page's own controls the shell reaches. */
export interface ShellDeps {
    /** Whether the shell keys apply now: signed in, not on the TV skin, not in the player or the chats gate. */
    enabled: () => boolean
    /** The current path. */
    path: () => string
    go: (path: string) => void
    /** One step back inside the app, or Home where the page was the first one opened. */
    back: () => void
    /** The words of the search button a chat page keeps, when its search field is closed. */
    searchLabel: () => string
    mac?: boolean
    /** Hears the page's keys: the app's onGlobal (ui/globalInput.ts), which hears nothing while a profile is locked. */
    listen: (onKey: (e: KeyboardEvent) => void) => () => void
}

/** A control the keyboard can take: the first link, button or field of an item. */
export const FIRST_CONTROL = 'a[href], button:not([disabled]), input:not([disabled]):not([type=hidden]), [tabindex]:not([tabindex="-1"])'

const visible = (el: Element) => el instanceof HTMLElement && el.getClientRects().length > 0

/** Puts the keyboard in the page's search box, opening a chat's search first; false where the page has none. */
export function focusPageSearch(searchLabel: string): boolean {
    const field = [...document.querySelectorAll<HTMLInputElement>('main input[type=search], .search-field input')].find(visible)
    if (field) {
        field.focus()
        field.select()
        return true
    }
    // A chat page keeps its search behind a button, which toggles: only press it while shut.
    const opener = [...document.querySelectorAll<HTMLButtonElement>('button[aria-pressed]')].find((b) => b.title === searchLabel && visible(b))
    if (!opener || opener.getAttribute('aria-pressed') === 'true') return false
    opener.click()
    return true
}

/** The card's own menu, from the keyboard (the right click and the long press open it from a pointer). */
function openCardMenu(): boolean {
    const card = document.activeElement?.closest('a.card')
    if (!card) return false
    const r = card.getBoundingClientRect()
    card.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 }))
    return true
}

/**
 * The shell's window keys: one listener for the whole app. Returns the function that removes it.
 * A chord a page does not take (no search box to focus) is left to the browser: Ctrl+F stays Find.
 */
export function installShellKeys(deps: ShellDeps): () => void {
    const mac = deps.mac ?? isMac()
    const toggle = (path: string) => (deps.path() === path ? deps.back() : deps.go(path))
    const onKey = (e: KeyboardEvent) => {
        if (e.defaultPrevented || !deps.enabled()) return
        // A dialog (the shared confirm, a card's menu, a sheet) is its own layer: nothing under it hears the keys.
        if (document.querySelector('dialog[open]')) return
        const action = shellAction(e, { mac, typing: isTextField(e.target as HTMLElement | null), standalone: isStandalone() })
        if (!action) return
        // A held chord must not flip pages (Ctrl+K again is "back"): the first press acts, the repeats are spent.
        if (e.repeat) return void e.preventDefault()
        let took = true
        switch (action) {
            case 'search':
                toggle('/search')
                break
            case 'findInPage':
                took = focusPageSearch(deps.searchLabel())
                break
            case 'chooseChats':
                if (deps.path() !== '/added-chats') deps.go('/added-chats')
                break
            case 'downloads':
                toggle('/downloads')
                break
            case 'home':
                if (deps.path() !== '/') deps.go('/')
                break
            case 'chats':
                if (deps.path() !== '/chats') deps.go('/chats')
                break
            case 'cardMenu':
                took = openCardMenu()
                break
        }
        if (took) e.preventDefault()
    }
    return deps.listen(onKey)
}
