// What the browser tab, the app switcher and a screen reader call the page (WCAG 2.4.2), and where
// the keyboard and VoiceOver go after a route change (WP-57). Pure, so `node --test` runs it.
import { createSignal } from 'solid-js'

export const SITE = 'TMPlayer'

/**
 * The name of the open chat, video or watch page, which only that page knows. A page sets it while it
 * is open and clears it on the way out; the title never reads it while a profile is locked.
 */
const [pageName, setName] = createSignal<string | null>(null)
export { pageName }
export function setPageName(name: string | null | undefined) {
    setName(name?.trim() ? name.trim() : null)
}

/** The catalog key of a route's own name, or null where the page names itself (a chat, a video). */
export function routeKey(path: string): string | null {
    const seg = path.split('?')[0]!.split('/').filter(Boolean)
    switch (seg[0]) {
        case undefined:
            return 'browse.tab_home'
        case 'signin':
            return 'login.sign_in'
        case 'chats':
            return 'nav.chats'
        case 'search':
            return 'common.search'
        case 'recent':
            return 'browse.tab_history'
        case 'downloads':
            return 'nav.downloads'
        case 'favourites':
            return 'browse.tab_favourites_heading'
        case 'history':
            return 'browse.tab_history_heading'
        case 'lists':
            return 'lists.heading'
        case 'settings':
            return 'nav.settings'
        case 'added-chats':
            return 'picker.choose_chats'
        case 'setup':
            return 'setup.which_title'
        case 'chat':
        case 'video':
        case 'watch':
            return null
        default:
            return 'web.not_found'
    }
}

/** True for the pages that are named by what they show. */
export const namedByContent = (path: string) => routeKey(path) === null

/**
 * "Chats - TMPlayer", "Lighthouse - TMPlayer". `say` reads a catalog key in the shown language,
 * `name` is the open chat's or video's name, `settingsPage` the catalog key of a Settings page's name.
 * While a profile is locked, or before a name is known, only the site's name stands: never a chat or
 * a video's name over the PIN.
 */
export function pageTitle(o: { path: string; say: (key: string) => string; name: string | null; settingsPage?: string | null; locked?: boolean }): string {
    if (o.locked) return SITE
    const key = routeKey(o.path)
    const own = key === null ? o.name : o.settingsPage && o.path.startsWith('/settings') ? o.say(o.settingsPage) : o.say(key)
    return own ? `${own} - ${SITE}` : SITE
}

/**
 * The element focus moves to after a route change: the page's first `<h1>`, else the page itself.
 * Null when focus was put somewhere in the page on purpose (a field that took it, a sheet's button)
 * or the route is the first load.
 */
export function focusTarget<T extends { contains(n: unknown): boolean }>(o: { heading: T | null; page: T | null; active: unknown; body: unknown; first: boolean }): T | null {
    if (o.first) return null
    const a = o.active
    if (a && a !== o.body && o.page && a !== o.page && o.page.contains(a)) return null
    return o.heading ?? o.page
}
