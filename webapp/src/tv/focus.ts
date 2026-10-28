// The TV layout's remote (plan 8.5): a focus map of our own that moves the focus by where things
// stand on screen, the keys a TV remote sends, and the focus kept where it belongs: back on the
// item that was open after Back, a dialog or a refresh, and on the bar when a page was opened from
// it. Only loaded in the TV layout (App.tsx), so the phone and the desktop never carry it.
//
// Two zones, as the app's TvTopBar has them: the bar, and the page under it. Left and Right never
// leave the zone they are in; Up from the page's top-most item goes to the bar's item for the page,
// and Down from the bar to the item the remote was last on in the page. An open dialog or menu
// keeps the remote inside it.
//
// A notice with buttons that floats over the page (`[data-tv-reach]`: the update notice, a toast's
// Undo) never takes the remote, and the page's arrows never wander into it (HX22). Up from the bar
// goes into it (the bar is where Back always lands), and Down from the page's last item into one
// at the foot. Inside, Left and Right walk its buttons, and Back, or an arrow off its edge back
// the way the remote came, puts the remote back where it was; so does the notice going away.
import { createSignal } from 'solid-js'

export type Dir = 'up' | 'down' | 'left' | 'right'

const FOCUSABLE = 'a[href], button, input:not([type=hidden]), select, textarea, summary, [tabindex]'

/** Remote keys by `key` and by the TV's own codes (webOS, Tizen, HbbTV); anything else is left to the page. */
const ARROWS: Record<string, Dir> = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right', Up: 'up', Down: 'down', Left: 'left', Right: 'right' }
const ARROW_CODES: Record<number, Dir> = { 38: 'up', 40: 'down', 37: 'left', 39: 'right' }
const OK_KEYS = new Set(['Enter', 'Select', 'Accept'])
/** Escape and Backspace on a keyboard, 461 on webOS, 10009 on Tizen, 166 BrowserBack (CEC through some boxes). */
const BACK_KEYS = new Set(['Escape', 'Backspace', 'GoBack', 'BrowserBack', 'XF86Back', 'Back'])
const BACK_CODES = new Set([27, 8, 461, 10009, 166])
/** Play, Pause, Play/Pause: on a card they open it, as OK does. Stop, Rewind and Fast forward are the player's (session 21). */
const PLAY_KEYS = new Set(['MediaPlay', 'MediaPlayPause', 'Play', 'MediaPause'])
const PLAY_CODES = new Set([415, 19, 179, 10252])
/** The colour keys, as shortcuts to the bar's places in its order: red the account (Settings), green Search, yellow Home, blue Chats. */
const COLOUR_CODES: Record<number, string> = { 403: '/settings', 404: '/search', 405: '/', 406: '/chats' }
const COLOUR_KEYS: Record<string, string> = { ColorF0Red: '/settings', ColorF1Green: '/search', ColorF2Yellow: '/', ColorF3Blue: '/chats' }
/** A hold of OK this long is a long press, as the context menu key is. */
const HOLD_MS = 600
/** After this long with no key the page counts as idle (Home's carousel stops). */
const IDLE_MS = 60_000

const [resting, setResting] = createSignal(false)
/** Nobody has pressed a key for a minute: whatever turns by itself stops (AGENTS.md, low-end devices). */
export { resting }
let restTimer = 0
function pressed() {
    if (resting()) setResting(false)
    clearTimeout(restTimer)
    restTimer = window.setTimeout(() => setResting(true), IDLE_MS)
}

export interface Host {
    /** The page's address with its query: the key focus is remembered under. */
    path: () => string
    go: (to: string) => void
    /** The page's own Back (session.back). */
    back: () => void
    /** A page outside the bar's four: Back leaves it rather than going to the bar. */
    isSubPage: (path: string) => boolean
}

const root = document.documentElement
const isTextInput = (el: Element | null): el is HTMLInputElement | HTMLTextAreaElement =>
    (el instanceof HTMLInputElement && !['range', 'radio', 'checkbox', 'button', 'submit', 'reset', 'file', 'color'].includes(el.type)) || el instanceof HTMLTextAreaElement
const isRange = (el: Element | null): el is HTMLInputElement => el instanceof HTMLInputElement && el.type === 'range'
const isRadio = (el: Element | null): el is HTMLInputElement => el instanceof HTMLInputElement && el.type === 'radio'

/** A radio is drawn by its label (Settings' segmented choices); the input itself is invisible. */
function rectOf(el: Element): DOMRect {
    if (isRadio(el)) return (el.closest('label') ?? el).getBoundingClientRect()
    return el.getBoundingClientRect()
}

function usable(el: Element): el is HTMLElement {
    if (!(el instanceof HTMLElement) && !(el instanceof SVGElement)) return false
    const h = el as HTMLElement
    if ((h as HTMLButtonElement).disabled) return false
    if (h.tabIndex < 0 && !isRadio(h)) return false
    if (h.closest('[inert], [hidden], [aria-hidden="true"]')) return false
    // A closed <details> hides everything but its summary.
    const details = h.closest('details:not([open])')
    if (details && h.tagName !== 'SUMMARY') return false
    const r = rectOf(h)
    if (r.width === 0 && r.height === 0) return false
    return getComputedStyle(h).visibility !== 'hidden'
}

/** A notice over the page the remote can reach (HX22), and the one the remote is in. */
const REACH = '[data-tv-reach]'
const reachOf = (el: Element | null) => (el && el !== document.body ? el.closest(REACH) : null)

/** Where the remote may go now: inside an open dialog or menu if there is one, else the whole page. */
function scope(): Element {
    const modal = [...document.querySelectorAll('dialog[open]')].find((d) => {
        try {
            return d.matches(':modal')
        } catch {
            return true
        }
    })
    if (modal) return modal
    const other = document.querySelector('.other-tab')
    if (other) return other
    // A menu that is open (the player keeps its own, hidden, in the page).
    const menu = [...document.querySelectorAll('[role=menu]')].find((m) => m.getClientRects().length && !m.closest('[hidden], .player'))
    if (menu) return menu
    // In a notice over the page: its buttons, and nothing behind it.
    const notice = reachOf(document.activeElement)
    if (notice) return notice
    return document.body
}

const bar = () => document.querySelector('.tv-bar')
const inBar = (el: Element | null) => !!el && !!bar()?.contains(el)

function candidates(within: Element, zone: 'bar' | 'page' | 'any'): HTMLElement[] {
    const b = bar()
    return [...within.querySelectorAll(FOCUSABLE)].filter((el): el is HTMLElement => {
        // A notice's buttons only from inside it: the page's arrows never wander in (TvReach).
        if (!within.matches(REACH) && el.closest(REACH)) return false
        if (zone !== 'any' && b && within === document.body) {
            const there = b.contains(el)
            if ((zone === 'bar') !== there) return false
        }
        return usable(el)
    })
}

/**
 * The best item from `from` in `dir`: it must lie that way (a little overlap allowed, for a card
 * grown under focus), and the nearest wins, an item in line with `from` well ahead of one off to
 * the side.
 */
function nearest(from: DOMRect, dir: Dir, list: HTMLElement[], self?: Element): HTMLElement | null {
    let best: HTMLElement | null = null
    let bestScore = Infinity
    const fx = (from.left + from.right) / 2
    const fy = (from.top + from.bottom) / 2
    for (const el of list) {
        if (el === self) continue
        const r = rectOf(el)
        const tolY = Math.min(from.height, r.height) * 0.3
        const tolX = Math.min(from.width, r.width) * 0.3
        let ahead: boolean
        let primary: number
        let side: number
        switch (dir) {
            case 'down':
                ahead = r.top >= from.bottom - tolY && (r.top + r.bottom) / 2 > fy
                primary = Math.max(0, r.top - from.bottom)
                side = Math.max(0, r.left - from.right, from.left - r.right)
                break
            case 'up':
                ahead = r.bottom <= from.top + tolY && (r.top + r.bottom) / 2 < fy
                primary = Math.max(0, from.top - r.bottom)
                side = Math.max(0, r.left - from.right, from.left - r.right)
                break
            case 'right':
                ahead = r.left >= from.right - tolX && (r.left + r.right) / 2 > fx
                primary = Math.max(0, r.left - from.right)
                side = Math.max(0, r.top - from.bottom, from.top - r.bottom)
                break
            default:
                ahead = r.right <= from.left + tolX && (r.left + r.right) / 2 < fx
                primary = Math.max(0, from.left - r.right)
                side = Math.max(0, r.top - from.bottom, from.top - r.bottom)
        }
        if (!ahead) continue
        // Left and Right stay on the line they are on.
        if ((dir === 'left' || dir === 'right') && side > Math.min(from.height, r.height) * 0.5) continue
        // From something wide (a search field) onto a row of small items, the row's start wins, as a TV's lists begin there.
        const centre =
            dir === 'up' || dir === 'down'
                ? from.width > r.width * 1.5 ? Math.abs(r.left - from.left) : Math.abs((r.left + r.right) / 2 - fx)
                : from.height > r.height * 1.5 ? Math.abs(r.top - from.top) : Math.abs((r.top + r.bottom) / 2 - fy)
        const score = primary + side * 3 + centre * 0.01
        if (score < bestScore) {
            bestScore = score
            best = el
        }
    }
    return best
}

// ---- Keeping an item in view -------------------------------------------------------------

/** The bar's height, which the page scrolls under. */
const barBottom = () => bar()?.getBoundingClientRect().bottom ?? 0

function scrollParentX(el: Element): HTMLElement | null {
    for (let p = el.parentElement; p && p !== document.body; p = p.parentElement) {
        const o = getComputedStyle(p).overflowX
        if ((o === 'auto' || o === 'scroll') && p.scrollWidth > p.clientWidth) return p
    }
    return null
}

/**
 * A row scrolls sideways to keep the item inside its padding; the page scrolls so the item sits
 * clear of the bar and of the foot, with some of the rows around it in sight. Home's carousel
 * (`[data-tv-top]`) takes the page back to its top. No smooth scrolling: on a 1 GB stick an
 * animated scroll under a held key is what makes the remote feel late.
 */
export function reveal(el: Element) {
    const strip = scrollParentX(el)
    if (strip) {
        const s = strip.getBoundingClientRect()
        const cs = getComputedStyle(strip)
        const padL = parseFloat(cs.paddingLeft) || 0
        const padR = parseFloat(cs.paddingRight) || 0
        const r = el.getBoundingClientRect()
        if (r.right > s.right - padR) strip.scrollLeft += r.right - (s.right - padR)
        else if (r.left < s.left + padL) strip.scrollLeft -= s.left + padL - r.left
    }
    if (inBar(el) || el.closest('dialog, [role=menu], .other-tab, .update-notice, .offline-line, [data-tv-reach]')) return
    if (el.closest('[data-tv-top]')) {
        if (scrollY) scrollTo({ top: 0, behavior: 'instant' })
        return
    }
    // A row of Home stands just under the bar, its heading in sight, as Google TV's rows do.
    const row = el.closest('.home-row')
    if (row) {
        const gap = barBottom() + innerHeight * 0.02
        const top = row.getBoundingClientRect().top
        if (Math.abs(top - gap) > 1) scrollBy({ top: top - gap, behavior: 'instant' })
        return
    }
    const r = rectOf(el)
    const margin = innerHeight * 0.14
    const top = barBottom() + margin
    const bottom = innerHeight - margin
    if (r.height > bottom - top) scrollBy({ top: r.top - top, behavior: 'instant' })
    else if (r.top < top) scrollBy({ top: r.top - top, behavior: 'instant' })
    else if (r.bottom > bottom) scrollBy({ top: r.bottom - bottom, behavior: 'instant' })
}

// ---- Remembering where the remote was ----------------------------------------------------

/** What an item is, as a new copy of it (a row drawn again, a page come back) is too: its address, label or words. */
function keyBase(el: Element): string {
    const radio = isRadio(el) ? `${el.name}=${el.closest('label')?.textContent?.trim() || el.value}` : null
    const id = el.getAttribute('href') ?? radio ?? el.getAttribute('aria-label') ?? el.getAttribute('title') ?? el.textContent?.trim().slice(0, 40) ?? ''
    return `${el.tagName}|${id}`
}

/** keyBase and which of the items with that name it is, in the page's order. */
function keyOf(el: Element): string {
    const base = keyBase(el)
    let n = 0
    for (const other of document.querySelectorAll(el.tagName)) {
        if (other === el) break
        if (keyBase(other) === base) n++
    }
    return `${base}#${n}`
}

function byKey(key: string, within: Element = document.body): HTMLElement | null {
    const [tag] = key.split('|', 1)
    const hash = key.lastIndexOf('#')
    const base = key.slice(0, hash)
    const n = Number(key.slice(hash + 1))
    let i = 0
    for (const el of within.querySelectorAll(tag)) {
        if (keyBase(el) !== base) continue
        if (i++ === n) return usable(el) ? (el as HTMLElement) : null
    }
    return null
}

interface Memory {
    key: string
    scroll: number
}

export function createFocusMap(host: Host) {
    /** Per page: the item the remote was last on, and how far down the page was. */
    const memory = new Map<string, Memory>()
    /** The page's item the remote was last on, for Down from the bar; and its place, for a lost focus. */
    let lastPage: { el: HTMLElement; key: string; rect: DOMRect } | null = null
    const currentDepth = () => (history.state as { _depth?: number } | null)?._depth ?? null
    let lastDepth = currentDepth()
    let editing: HTMLInputElement | HTMLTextAreaElement | null = null
    let hold: { el: HTMLElement; at: number; long: boolean; timer: number } | null = null
    /** The notice the remote went into, and where it was before: where it goes back to. */
    let reach: { el: Element; from: HTMLElement | null; edge: 'top' | 'bottom' } | null = null
    let waiting = 0
    let waitToken = 0
    const watching = () => host.path().startsWith('/watch/')

    const keysOn = () => {
        if (root.dataset.tvKeys === undefined) root.dataset.tvKeys = ''
    }

    const focus = (el: HTMLElement, show = true) => {
        el.focus({ preventScroll: true })
        if (show) reveal(el)
    }

    const remember = (el: Element) => {
        if (!(el instanceof HTMLElement) || inBar(el) || !document.querySelector('.page')?.contains(el)) return
        const key = keyOf(el)
        lastPage = { el, key, rect: rectOf(el) }
        memory.set(host.path(), { key, scroll: scrollY })
    }

    /** The bar's item for the page shown: Up from the page's top and Back land there. */
    const barCurrent = (): HTMLElement | null =>
        (bar()?.querySelector('[aria-current]') as HTMLElement | null) ?? (bar()?.querySelector(FOCUSABLE) as HTMLElement | null)

    /** Where the remote goes in the page when nothing says otherwise: Home's Play, else the first item. */
    const pageDefault = (): HTMLElement | null => {
        const page = document.querySelector('.page') ?? document.body
        return listDefault(page) ?? candidates(page, 'any')[0] ?? null
    }

    /** Home's Play, else a list's first item (a chat's first card, the first chat), as the app's pages open. */
    const listDefault = (page: Element): HTMLElement | null => {
        const first = page.querySelector('[data-tv-first]')
        if (first && usable(first)) return first as HTMLElement
        const list = page.querySelector('.vgrid, .strip')
        return list ? (candidates(list, 'any')[0] ?? null) : null
    }

    const intoPage = () => {
        if (lastPage?.el.isConnected && usable(lastPage.el)) return focus(lastPage.el)
        const page = document.querySelector('.page') ?? document.body
        const again = lastPage && byKey(lastPage.key, page)
        if (again) return focus(again)
        // Nothing yet in this page: what stands below the bar's item for it (a search field, Home's Play).
        const from = barCurrent()
        const below = from && nearest(rectOf(from), 'down', candidates(page, 'any'))
        if (below) return focus(below)
        const first = pageDefault()
        if (first) focus(first)
    }

    /**
     * Into the newest notice over the page: one at the top for Up from the bar, else the one at
     * the foot; Down from the page's foot only into one at the foot. True if there was one.
     */
    const enterReach = (from: HTMLElement | null, edges: ('top' | 'bottom')[]): boolean => {
        for (const edge of edges) {
            const notices = [...document.querySelectorAll(`[data-tv-reach="${edge}"]`)].filter((n) => candidates(n, 'any').length)
            const notice = notices[notices.length - 1]
            if (!notice) continue
            reach = { el: notice, from, edge }
            focus(candidates(notice, 'any')[0])
            return true
        }
        return false
    }
    /** Out of the notice: back where the remote was, else the bar's item, else the page. */
    const leaveReach = () => {
        const from = reach?.from
        reach = null
        if (from?.isConnected && usable(from)) return focus(from)
        const item = barCurrent()
        if (item) return focus(item)
        intoPage()
    }

    /** Waits (a few seconds at most, only after a page change) for `find` to give an item, then focuses it. */
    const settle = (find: () => HTMLElement | null, scroll: number | null, fallback: boolean, patience = 4000) => {
        const token = ++waitToken
        const until = performance.now() + patience
        const step = () => {
            if (token !== waitToken) return
            if (scroll != null && Math.abs(scrollY - scroll) > 2) scrollTo({ top: scroll, behavior: 'instant' })
            const el = find()
            if (el) return focus(el, scroll == null)
            if (performance.now() < until) waiting = window.setTimeout(step, 50)
            else if (fallback) {
                const first = pageDefault()
                if (first) focus(first)
            }
        }
        clearTimeout(waiting)
        step()
    }
    const stopWaiting = () => {
        waitToken++
        clearTimeout(waiting)
    }

    /** A new page: back on the item that was open (Back), on the bar (opened from the bar), else its first item. */
    const routed = (path: string) => {
        // The router stamps each history entry with its depth: a lower one than the page had at
        // the last key or press is a Back (a push is written to history only after this runs).
        const depth = currentDepth()
        const wasBack = depth != null && lastDepth != null && depth < lastDepth
        lastPage = null
        if (path.startsWith('/watch/')) return stopWaiting()
        const active = document.activeElement
        if (!wasBack && inBar(active)) {
            // The bar's item for the new page, once the bar has marked it.
            return settle(() => barCurrent(), null, false, 500)
        }
        const saved = wasBack ? memory.get(path) : undefined
        if (saved) return settle(() => byKey(saved.key, document.querySelector('.page') ?? document.body), saved.scroll, true)
        // Home waits a moment for its carousel's Play before taking the first card. After a second
        // the page's first item stands in (a chat's Back while its cards are still coming), and the
        // wait goes on: the first card takes the remote when it arrives, unless the remote or a
        // press has gone elsewhere meanwhile. A chat whose first page took over a second left the
        // remote on Back for good (HX18).
        let tries = 0
        let stopgap: HTMLElement | null = null
        settle(
            () => {
                tries++
                const page = document.querySelector('.page')
                if (stopgap && document.activeElement !== stopgap) {
                    stopWaiting()
                    return null
                }
                const first = page && listDefault(page)
                if (first) return first
                if (tries > 20 && !stopgap) {
                    stopgap = pageDefault()
                    if (stopgap) focus(stopgap)
                }
                return null
            },
            null,
            true,
            8000,
        )
    }

    const move = (dir: Dir, from: Element | null) => {
        stopWaiting()
        const within = scope()
        let start = from && from !== document.body && within.contains(from) ? (from as HTMLElement) : null
        if (!start) {
            // Nothing focused (a mouse press, or the item drawn again a moment ago): the key goes on
            // from where the remote last was, or that item drawn again.
            const page = document.querySelector('.page') ?? document.body
            const back = !lastPage ? null : lastPage.el.isConnected && usable(lastPage.el) ? lastPage.el : byKey(lastPage.key, page)
            if (within === document.body && back) start = back
            else {
                const first = within === document.body ? pageDefault() : candidates(within, 'any')[0]
                if (first) focus(first)
                return
            }
        }
        // An item may take a key for itself (the carousel's Left and Right turn its pages).
        if (!start.dispatchEvent(new CustomEvent('tvmove', { detail: dir, cancelable: true }))) return
        const zone = within !== document.body ? 'any' : inBar(start) ? 'bar' : 'page'
        const target = nearest(rectOf(start), dir, candidates(within, zone), start)
        if (within.matches(REACH)) {
            // Inside a notice: its own buttons, and off its edge back where the remote was, but
            // never on through the screen's edge it stands against.
            if (target) return focus(target)
            const edge = within.getAttribute('data-tv-reach')
            if ((edge === 'top' && dir === 'up') || (edge === 'bottom' && dir === 'down')) return
            return leaveReach()
        }
        if (target) {
            // Into a set of choices: on the chosen one, as a radio group takes the remote.
            if (isRadio(target) && !(isRadio(start) && start.name === target.name)) {
                const chosen = document.querySelector(`input[type=radio][name="${CSS.escape(target.name)}"]:checked`) as HTMLElement | null
                if (chosen && usable(chosen)) return focus(chosen)
            }
            return focus(target)
        }
        if (zone === 'page' && dir === 'up') {
            const item = barCurrent()
            if (item) focus(item)
        } else if (zone === 'bar' && dir === 'down') {
            intoPage()
        } else if (zone === 'bar' && dir === 'up') {
            enterReach(start, ['top', 'bottom'])
        } else if (zone === 'page' && dir === 'down') {
            enterReach(start, ['bottom'])
        }
    }

    // ---- Text fields: read only until OK, so passing through one never pops up a TV's keyboard.
    const lock = (el: HTMLInputElement | HTMLTextAreaElement) => {
        if (el.readOnly && el.dataset.tvLocked === undefined) return
        el.dataset.tvLocked = ''
        el.readOnly = true
        if (editing === el) editing = null
    }
    const unlock = (el: HTMLInputElement | HTMLTextAreaElement) => {
        if (el.dataset.tvLocked === undefined) return
        el.readOnly = false
        editing = el
    }
    const release = (el: Element) => {
        if (!isTextInput(el) || el.dataset.tvLocked === undefined) return
        el.readOnly = false
        delete el.dataset.tvLocked
        if (editing === el) editing = null
    }

    // ---- Back ---------------------------------------------------------------------------
    const goBack = (e: KeyboardEvent): boolean => {
        const active = document.activeElement
        if (editing && active === editing) {
            lock(editing)
            return true
        }
        // A field with an Escape of its own (a chat's search closes) gets it first.
        if (isTextInput(active) && !document.querySelector('dialog[open]')) {
            active.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
            if (!active.isConnected) return true
        }
        const within = scope()
        if (within.matches?.(REACH)) {
            leaveReach()
            return true
        }
        if (within instanceof HTMLDialogElement) {
            // Escape closes a modal dialog by itself; the other Back keys do it here.
            if (e.key !== 'Escape') within.close()
            return e.key !== 'Escape'
        }
        if (within.matches?.('[role=menu]')) {
            const opener = within.parentElement?.querySelector('[aria-expanded="true"]') as HTMLElement | null
            if (opener) {
                opener.click()
                focus(opener)
            }
            return true
        }
        if (within.matches?.('.other-tab')) return false
        const path = host.path()
        if (path.startsWith('/watch/')) {
            // Escape is the player's own; the remote's Back keys close it the same way.
            if (e.key === 'Escape') return false
            host.back()
            return true
        }
        if (path === '/signin' || path.startsWith('/signin?')) return false
        if (host.isSubPage(path)) {
            host.back()
            return true
        }
        if (!inBar(active)) {
            const item = barCurrent()
            if (item) {
                if (scrollY) scrollTo({ top: 0, behavior: 'instant' })
                focus(item)
            }
            return true
        }
        if (path !== '/') host.go('/')
        return true
    }

    /** OK on an item: a radio is chosen, a text field opens for typing, anything else is pressed. */
    const press = (el: HTMLElement) => {
        if (isRadio(el)) {
            if (!el.checked) el.click()
            return
        }
        el.click()
    }

    // ---- The listeners --------------------------------------------------------------------
    const onKeyDown = (e: KeyboardEvent) => {
        if (!e.isTrusted) return
        // A key of the viewer's own ends any wait to put the remote somewhere after a page change.
        stopWaiting()
        lastDepth = currentDepth()
        if (e.ctrlKey || e.altKey || e.metaKey) return
        pressed()
        const code = e.keyCode
        const dir = ARROWS[e.key] ?? (e.key === 'Unidentified' || !e.key ? ARROW_CODES[code] : undefined)
        const isOk = OK_KEYS.has(e.key) || (code === 13 && !e.key)
        const isBack = BACK_KEYS.has(e.key) || (BACK_CODES.has(code) && (!e.key || e.key === 'Unidentified'))
        if (dir || isOk || isBack) keysOn()
        const active = document.activeElement as HTMLElement | null

        // The player has its own keys (Chrome.tsx `tvKey`, session 21), Back included: the row first,
        // then the video. Only before its controls are drawn is Back taken here.
        if (watching()) {
            if (document.querySelector('.pc')) return
            if (isBack && e.key !== 'Backspace' && goBack(e)) e.preventDefault()
            else if (e.key === 'Backspace' && !isTextInput(active) && goBack(e)) e.preventDefault()
            return
        }

        if (isBack) {
            // Backspace deletes in a field being typed in.
            if (e.key === 'Backspace' && editing && active === editing) return
            if (goBack(e)) {
                e.preventDefault()
                e.stopPropagation()
            }
            return
        }

        if (isTextInput(active) && active.dataset.tvLocked !== undefined) {
            if (isOk && active.readOnly) {
                e.preventDefault()
                unlock(active)
                return
            }
            if (!active.readOnly) {
                // Typing: Left and Right move in the words, Up and Down leave the field.
                if (dir === 'up' || dir === 'down') {
                    lock(active)
                    e.preventDefault()
                    move(dir, active)
                } else if (isOk) {
                    // Enter still does what it does (a form sends), and typing ends.
                    queueMicrotask(() => lock(active))
                }
                return
            }
            // A key that types starts typing, for a keyboard in the TV layout.
            if (e.key.length === 1) unlock(active)
        }

        if (dir) {
            // A slider takes Left and Right; Up and Down leave it. A radio does not choose by moving.
            if (isRange(active) && (dir === 'left' || dir === 'right')) return
            e.preventDefault()
            e.stopPropagation()
            move(dir, active)
            return
        }

        if (isOk && active && active !== document.body && !isTextInput(active)) {
            // Pressed on release, so a hold can be a long press; a held key does not press again and again.
            e.preventDefault()
            if (e.repeat || hold) return
            const h = { el: active, at: performance.now(), long: false, timer: 0 }
            h.timer = window.setTimeout(() => {
                h.long = true
                longPress(active)
            }, HOLD_MS)
            hold = h
            return
        }

        if (e.key === 'ContextMenu' || code === 93) {
            if (active && active !== document.body) {
                e.preventDefault()
                longPress(active)
            }
            return
        }

        if (PLAY_KEYS.has(e.key) || PLAY_CODES.has(code)) {
            // Play on a card plays it, past its page (OK opens the page); on Home's or the page's Play it presses it.
            if (active?.matches('.card, [data-tv-first]')) {
                e.preventDefault()
                const href = active.getAttribute('href')
                if (href?.startsWith('/video/')) host.go(href.replace('/video/', '/watch/'))
                else active.click()
            }
            return
        }

        const to = COLOUR_KEYS[e.key] ?? COLOUR_CODES[code]
        if (to) {
            e.preventDefault()
            const item = bar()?.querySelector(`a[href="${to}"]`) as HTMLElement | null
            host.go(to)
            if (item) focus(item)
        }
    }

    const onKeyUp = (e: KeyboardEvent) => {
        if (!hold || !(OK_KEYS.has(e.key) || e.keyCode === 13)) return
        const h = hold
        hold = null
        clearTimeout(h.timer)
        if (!h.long && document.activeElement === h.el) press(h.el)
    }

    /** A long press (OK held, or the context menu key): what a right click would open, where an item has one. */
    const longPress = (el: HTMLElement) => {
        const r = el.getBoundingClientRect()
        el.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 }))
    }

    const onFocusIn = (e: FocusEvent) => {
        const el = e.target as Element
        if (isTextInput(el) && el.dataset.tvLocked === undefined && !el.readOnly) lock(el)
        remember(el)
    }
    const onFocusOut = (e: FocusEvent) => release(e.target as Element)

    /** Mouse and remote together: the ring goes at a mouse press and comes back at the next key (FocusVisibility). */
    const onPointerDown = (e: PointerEvent) => {
        lastDepth = currentDepth()
        if (e.pointerType === 'mouse' || e.pointerType === 'pen') delete root.dataset.tvKeys
        stopWaiting()
    }

    const onScroll = () => {
        const m = memory.get(host.path())
        if (m) m.scroll = scrollY
    }

    // An item taken away under the remote (a row drawn again, a button disabled while it works):
    // the remote goes back to it, or to the same item drawn again, or to the nearest.
    let pending = 0
    const observer = new MutationObserver(() => {
        if (pending || watching()) return
        pending = requestAnimationFrame(() => {
            pending = 0
            const active = document.activeElement
            // A notice gone (Later, Undo, its time up) while it had the remote: back where it was.
            if (reach && !reach.el.isConnected) {
                const had = !active || active === document.body
                const from = reach.from
                reach = null
                if (had && root.dataset.tvKeys !== undefined) {
                    if (from?.isConnected && usable(from)) return focus(from, false)
                    const item = barCurrent()
                    if (item) return focus(item, false)
                }
            }
            if (active && active !== document.body) return
            if (root.dataset.tvKeys === undefined || !lastPage) return
            const was = lastPage
            // Disabled while it works (Refresh): it takes the remote back once it is done.
            if (was.el.isConnected && (was.el as HTMLButtonElement).disabled) return
            if (was.el.isConnected && usable(was.el)) return focus(was.el, false)
            const again = byKey(was.key, document.querySelector('.page') ?? document.body)
            if (again) return focus(again, false)
            const page = document.querySelector('.page')
            if (!page) return
            const near = nearest(was.rect, 'down', candidates(page, 'any')) ?? nearest(was.rect, 'up', candidates(page, 'any'))
            if (near) focus(near, false)
        })
    })

    const start = () => {
        root.dataset.tvKeys = ''
        window.addEventListener('keydown', onKeyDown, true)
        window.addEventListener('keyup', onKeyUp, true)
        document.addEventListener('focusin', onFocusIn)
        document.addEventListener('focusout', onFocusOut)
        window.addEventListener('pointerdown', onPointerDown, true)
        window.addEventListener('scroll', onScroll, { passive: true })
        observer.observe(document.getElementById('app') ?? document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['disabled'] })
        pressed()
        const active = document.activeElement
        if (isTextInput(active)) lock(active)
    }

    const stop = () => {
        delete root.dataset.tvKeys
        window.removeEventListener('keydown', onKeyDown, true)
        window.removeEventListener('keyup', onKeyUp, true)
        document.removeEventListener('focusin', onFocusIn)
        document.removeEventListener('focusout', onFocusOut)
        window.removeEventListener('pointerdown', onPointerDown, true)
        window.removeEventListener('scroll', onScroll)
        observer.disconnect()
        cancelAnimationFrame(pending)
        stopWaiting()
        clearTimeout(restTimer)
        if (hold) clearTimeout(hold.timer)
        for (const el of document.querySelectorAll<HTMLInputElement>('[data-tv-locked]')) release(el)
    }

    return { start, stop, routed, move }
}
