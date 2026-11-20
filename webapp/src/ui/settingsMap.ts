// Settings' map on the web (WB20, U20): which row lives on which page, and the words a search over
// them reads. The rows' ids are core's SettingsMap ids, so the apps' search rules (WebRules
// settingsSearch, run in the worker) pick the hits; this file only says which of them the web draws,
// where, and when. The few rows only the web has (storage, installing, the notices) are found by a
// plain word match here, since the apps' map has nothing to say about them. Pure, so it runs under
// `node --test`.

/** The pages of Settings on the web, in the list's order. */
export const PAGE_IDS = ['appearance', 'home', 'playback', 'privacy', 'storage', 'about'] as const
export type PageId = (typeof PAGE_IDS)[number]

/** Addresses that were pages once: Home and Library are one page now (Home and library). */
export const OLD_PAGES: Readonly<Record<string, PageId>> = { library: 'home' }

/** The page an address names, an old address included, or null. */
export function pageFor(name: string | undefined): PageId | null {
    if (!name) return null
    if ((PAGE_IDS as readonly string[]).includes(name)) return name as PageId
    return Object.hasOwn(OLD_PAGES, name) ? OLD_PAGES[name] : null
}

/** The catalog key of each page's name, as the list and the page's heading say it. */
export const PAGE_TITLES: Readonly<Record<PageId, string>> = {
    appearance: 'settings.appearance',
    home: 'settings.page_home_library',
    playback: 'settings.playback',
    privacy: 'settings.page_privacy_lock',
    storage: 'web.settings_storage',
    about: 'settings.page_about',
}

/** What decides whether a row is drawn now: the form, and what this device and account have. */
export interface RowContext {
    form: 'phone' | 'desktop' | 'tv'
    /** The address fixed the layout (a TV wrapper): Settings leaves Layout out. */
    layoutFixed: boolean
    /** The hidden MSone flag. */
    msone: boolean
    /** Resume on your other devices is on. */
    sync: boolean
    /** A sync file is in Saved Messages, on or off. */
    syncFile: boolean
    /** The browser offers to install, or this is Safari on an iPhone or iPad. */
    installable: boolean
    /** The browser can say how much the site holds, so Storage draws its sizes and Clear cache. */
    storageEstimate: boolean
}

export interface MapRow {
    /** The row's anchor (`#id` in the address). The apps' SettingsMap id where it has one. */
    id: string
    /** The page it is on; null for a row in the list beside the pages (Sign out). */
    page: PageId | null
    /** Whether the apps' SettingsMap has this row, so the apps' search rules answer for it. */
    core: boolean
    /** The catalog key of its title on the web. */
    title: string
    /** Catalog keys of the lines under it, read by the web's own match for a row the apps do not have. */
    details?: readonly string[]
    /** Where it is drawn; every phone and desktop form where absent. */
    shown?: (c: RowContext) => boolean
}

const always = () => true

/** Every row the web's Settings draws on a phone or a desktop, by page in the order the page draws them. */
const defs: MapRow[] = [
    { id: 'theme', page: 'appearance', core: true, title: 'settings.theme' },
    { id: 'layout', page: 'appearance', core: true, title: 'settings.layout', shown: (c) => c.form !== 'phone' && !c.layoutFixed },
    { id: 'language', page: 'appearance', core: true, title: 'settings.language' },

    { id: 'home_carousel', page: 'home', core: true, title: 'settings.home_carousel' },
    { id: 'home_continue', page: 'home', core: true, title: 'settings.home_continue' },
    { id: 'home_recent', page: 'home', core: true, title: 'settings.home_recent' },
    { id: 'home_starred_rows', page: 'home', core: true, title: 'settings.home_starred_rows' },
    { id: 'home_order', page: 'home', core: true, title: 'settings.home_order' },
    { id: 'chosen_chats', page: 'home', core: true, title: 'picker.row_title' },
    { id: 'posters', page: 'home', core: true, title: 'web.posters_title' },
    { id: 'trailers_in_app', page: 'home', core: true, title: 'metadata.trailers_in_app' },
    { id: 'clear_history', page: 'home', core: true, title: 'browse.clear_history' },

    { id: 'resume_rewind', page: 'playback', core: true, title: 'settings.resume_rewind' },
    { id: 'msone', page: 'playback', core: true, title: 'msone.title', shown: (c) => c.msone },
    { id: 'submaker_base', page: 'playback', core: true, title: 'submaker.settings_title', details: ['submaker.settings_message'] },

    { id: 'resume_sync', page: 'privacy', core: true, title: 'settings.resume_sync' },
    { id: 'resume_sync_delete', page: 'privacy', core: true, title: 'settings.resume_sync_delete', shown: (c) => c.sync || c.syncFile },

    { id: 'install', page: 'storage', core: false, title: 'web.install_title', details: ['web.install_pwa_body'], shown: (c) => c.installable },
    { id: 'storage', page: 'storage', core: false, title: 'web.storage_title', details: ['web.storage_body_stream'] },
    { id: 'clear_cache', page: 'storage', core: false, title: 'web.storage_clear_cache', details: ['web.storage_clear_cache_detail'], shown: (c) => c.storageEstimate },
    { id: 'keep_storage', page: 'storage', core: false, title: 'web.keep_storage' },

    { id: 'whats_new', page: 'about', core: true, title: 'settings.whats_new' },
    { id: 'changelog', page: 'about', core: true, title: 'settings.changelog', details: ['settings.changelog_detail'] },
    { id: 'report_problem', page: 'about', core: true, title: 'settings.report_problem' },
    { id: 'request_feature', page: 'about', core: true, title: 'web.request_feature', details: ['web.request_feature_detail'] },
    { id: 'notices', page: 'about', core: false, title: 'about.notices_title', details: ['about.notices_detail'] },
    { id: 'legal', page: 'about', core: false, title: 'login.legal' },
    { id: 'chip_in', page: 'about', core: false, title: 'support.buy_coffee', details: ['support.pay_upi'] },

    { id: 'sign_out', page: null, core: true, title: 'settings.sign_out' },
]

export const ROWS: readonly MapRow[] = defs.map((r): MapRow => ({ shown: always, ...r }))

const byId = new Map(ROWS.map((r) => [r.id, r]))

/** The row an anchor names, or undefined. */
export function rowFor(id: string): MapRow | undefined {
    return byId.get(id)
}

/** The rows drawn now. A television has no search box and no map: it is one long page. */
export function shownRows(c: RowContext): MapRow[] {
    if (c.form === 'tv') return []
    return ROWS.filter((r) => r.shown?.(c) ?? true)
}

/** The apps' form name for the web's: a phone is a phone, anything wider is the desktop's. */
export const coreForm = (form: RowContext['form']): 'phone' | 'desktop' => (form === 'phone' ? 'phone' : 'desktop')

// The words of the rows the apps' map does not have.

/** Lower case words, accents folded, split at anything that is not a letter or a digit. */
export function words(text: string): string[] {
    let folded = text
    try {
        folded = text.normalize('NFD').replace(/\p{M}+/gu, '')
    } catch {}
    return folded.toLowerCase().match(/[\p{L}\p{N}]+/gu) ?? []
}

/** The longest query either search reads. */
export const QUERY_MAX = 80

/**
 * The rows that answer [query] by the words the web draws them with: every word typed starts a word
 * of the row's title, its lines or its page's name; those answering in the title first. The only
 * answer for a row the apps' map lacks, and for the others the way to find a row by the title this
 * page shows when the apps word it otherwise. [text] reads a catalog key in the shown language.
 */
export function ownHits(query: string, rows: readonly MapRow[], text: (key: string) => string): string[] {
    const typed = words(query.slice(0, QUERY_MAX))
    if (!typed.length) return []
    const scored: { id: string; rank: number; index: number }[] = []
    rows.forEach((row, index) => {
        const title = words(text(row.title))
        const rest = [...(row.details ?? []).map(text), row.page ? text(PAGE_TITLES[row.page]) : ''].flatMap(words)
        if (!typed.every((w) => title.some((x) => x.startsWith(w)) || rest.some((x) => x.startsWith(w)))) return
        const inTitle = typed.every((w) => title.some((x) => x.startsWith(w)))
        scored.push({ id: row.id, rank: title.join(' ').startsWith(typed.join(' ')) ? 0 : inTitle ? 1 : 2, index })
    })
    return scored.sort((a, b) => a.rank - b.rank || a.index - b.index).map((s) => s.id)
}

/**
 * The ids to list for a search: the apps' rules' hits (in their order) first, then the web only
 * rows that match. An id the web does not draw (a stale answer, a made up one) is dropped, and each
 * shows once.
 */
export function mergeHits(coreIds: readonly string[], ownIds: readonly string[], shown: readonly MapRow[]): MapRow[] {
    const allowed = new Map(shown.map((r) => [r.id, r]))
    const out: MapRow[] = []
    const seen = new Set<string>()
    for (const id of [...coreIds, ...ownIds]) {
        const row = allowed.get(id)
        if (!row || seen.has(id)) continue
        seen.add(id)
        out.push(row)
    }
    return out
}
