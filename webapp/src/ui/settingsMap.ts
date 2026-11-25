// Settings' map on the web (WB20, U20): which row lives on which page, and the words a search over
// them reads. The rows' ids are core's SettingsMap ids, so the apps' search rules (WebRules
// settingsSearch, run in the worker) pick the hits; this file only says which of them the web draws,
// where, and when. The few rows only the web has (storage, installing, the notices) are found by a
// plain word match here, since the apps' map has nothing to say about them. Pure, so it runs under
// `node --test`.

/** The pages of Settings on the web, in the list's order. */
// WP-78: the order and the names of core's SettingsGroup (WP-08): Account, Playback, Subtitles and audio, Downloads and data,
// Appearance and language, Home and library, Help, About. Privacy and lock and Notifications have no web rows, so they are not listed.
export const PAGE_IDS = ['account', 'playback', 'subtitles', 'storage', 'appearance', 'home', 'help', 'about'] as const
export type PageId = (typeof PAGE_IDS)[number]

/** Addresses that were pages once: Home and Library are one page now (Home and library). */
export const OLD_PAGES: Readonly<Record<string, PageId>> = { library: 'home', privacy: 'account' }

/** The page an address names, an old address included, or null. */
export function pageFor(name: string | undefined): PageId | null {
    if (!name) return null
    if ((PAGE_IDS as readonly string[]).includes(name)) return name as PageId
    return Object.hasOwn(OLD_PAGES, name) ? OLD_PAGES[name] : null
}

/** The catalog key of each page's name, as the list and the page's heading say it. */
export const PAGE_TITLES: Readonly<Record<PageId, string>> = {
    account: 'settings.group_account',
    playback: 'settings.playback',
    subtitles: 'settings.page_subtitles_audio',
    storage: 'settings.page_downloads_data',
    appearance: 'settings.page_appearance',
    home: 'settings.page_home_library',
    help: 'settings.help',
    about: 'settings.group_about',
}

/** What decides whether a row is drawn now: the form, and what this device and account have. */
export interface RowContext {
    form: 'phone' | 'desktop' | 'tv'
    /** The address fixed the layout (a TV wrapper): Settings leaves Layout out. */
    layoutFixed: boolean
    /** A touch device with a tablet's screen: Layout is offered even while the window is narrow enough for the phone's. */
    tablet?: boolean
    /** The hidden MSone flag. */
    msone: boolean
    /** Sync across my devices is on. */
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
    // Account: who is signed in and resume on the other devices. Sign out stays beside every page (the list), and the account's menu.
    { id: 'resume_sync', page: 'account', core: true, title: 'syncplace.switch' },
    { id: 'resume_sync_delete', page: 'account', core: true, title: 'settings.resume_sync_delete', shown: (c) => c.sync || c.syncFile },
    { id: 'sign_out', page: null, core: true, title: 'settings.sign_out' },

    { id: 'resume_rewind', page: 'playback', core: true, title: 'settings.resume_rewind' },

    { id: 'subtitle_style', page: 'subtitles', core: true, title: 'settings.subtitle_style' },
    { id: 'msone', page: 'subtitles', core: true, title: 'msone.title', shown: (c) => c.msone },
    { id: 'submaker_base', page: 'subtitles', core: true, title: 'submaker.settings_title', details: ['submaker.settings_message'] },

    { id: 'install', page: 'storage', core: false, title: 'web.install_title', details: ['web.install_pwa_body'], shown: (c) => c.installable },
    { id: 'storage', page: 'storage', core: false, title: 'web.storage_title', details: ['web.storage_body_stream'] },
    { id: 'clear_cache', page: 'storage', core: false, title: 'web.storage_clear_cache', details: ['web.storage_clear_cache_detail'], shown: (c) => c.storageEstimate },
    { id: 'keep_storage', page: 'storage', core: false, title: 'web.keep_storage' },
    { id: 'back_up', page: 'storage', core: true, title: 'backup.back_up', details: ['backup.back_up_detail'] },
    { id: 'restore', page: 'storage', core: true, title: 'backup.restore', details: ['backup.restore_detail'] },

    // Appearance and language: Language is the first row.
    { id: 'language', page: 'appearance', core: true, title: 'settings.language' },
    { id: 'theme', page: 'appearance', core: true, title: 'settings.theme' },
    { id: 'layout', page: 'appearance', core: false, title: 'settings.layout', shown: (c) => (c.form !== 'phone' || !!c.tablet) && !c.layoutFixed },
    { id: 'reduce_motion', page: 'appearance', core: false, title: 'settings.reduce_motion', details: ['settings.reduce_motion_on', 'settings.reduce_motion_off'] },
    { id: 'high_contrast', page: 'appearance', core: true, title: 'settings.high_contrast', details: ['settings.high_contrast_detail'] },

    { id: 'home_carousel', page: 'home', core: true, title: 'settings.home_carousel' },
    { id: 'home_continue', page: 'home', core: true, title: 'settings.home_continue' },
    { id: 'home_recent', page: 'home', core: true, title: 'settings.home_recent' },
    { id: 'home_starred_rows', page: 'home', core: true, title: 'settings.home_starred_rows' },
    { id: 'home_order', page: 'home', core: true, title: 'settings.home_order' },
    { id: 'chosen_chats', page: 'home', core: true, title: 'picker.row_title' },
    { id: 'remember_searches', page: 'home', core: true, title: 'settings.remember_searches', details: ['settings.remember_searches_on', 'settings.remember_searches_off'] },
    { id: 'posters', page: 'home', core: true, title: 'web.posters_title' },
    { id: 'trailers_in_app', page: 'home', core: true, title: 'metadata.trailers_in_app' },
    { id: 'clear_history', page: 'home', core: true, title: 'browse.clear_history' },

    // Help: the news, the reports.
    { id: 'whats_new', page: 'help', core: true, title: 'settings.whats_new' },
    { id: 'changelog', page: 'help', core: true, title: 'settings.changelog', details: ['settings.changelog_detail'] },
    { id: 'report_problem', page: 'help', core: true, title: 'settings.report_problem' },
    { id: 'request_feature', page: 'help', core: true, title: 'web.request_feature', details: ['web.request_feature_detail'] },

    { id: 'notices', page: 'about', core: false, title: 'about.notices_title', details: ['about.notices_detail'] },
    { id: 'legal', page: 'about', core: false, title: 'login.legal' },
    { id: 'chip_in', page: 'about', core: false, title: 'support.buy_coffee', details: ['support.pay_upi'] },
]

/**
 * Rows that moved in the WP-08 taxonomy and keep a "Moved to" line at their old place for one release (the apps' `also_in_moved`):
 * the old page lists them with the new page's name, and a press goes to the row. Remove them in the release after the beta.
 * Sync across my devices moved from Privacy and lock, a page the web no longer has: its address goes to Account (OLD_PAGES).
 */
export const MOVED: readonly { id: string; from: PageId; to: PageId }[] = [
    { id: 'submaker_base', from: 'playback', to: 'subtitles' },
    { id: 'msone', from: 'playback', to: 'subtitles' },
    { id: 'whats_new', from: 'about', to: 'help' },
    { id: 'changelog', from: 'about', to: 'help' },
    { id: 'report_problem', from: 'about', to: 'help' },
    { id: 'request_feature', from: 'about', to: 'help' },
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
