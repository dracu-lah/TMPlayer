// The page's settings (the apps' SettingsStore, on the web): the signal every screen reads, the
// theme and look put on the page, and the write that keeps a change.
import { createSignal } from 'solid-js'
import type { Sort } from '../tg/types'
import { write } from './kv'

export type Theme = 'system' | 'dark' | 'light' | 'black'

export interface Settings {
    theme: Theme
    /**
     * The app's size limits (SizeFilter), in bytes: 0 lists every video; 50 MB is the chat's
     * "Hide small clips" switch; anything else is a floor chosen before the switch, kept.
     * Larger videos are left out unless the top is at its ceiling, which means no limit.
     */
    sizeMin: number
    sizeMax: number
    sort: Sort
    /** Posters from TMDB on the cards (the apps' MetadataStore `enabled`, on by default); off, nothing is looked up online. */
    posters: boolean
    /** A detail page's trailer in YouTube's embedded player on the page (the apps' MetadataStore `trailers_in_app`), on by default; off, it opens on YouTube. */
    trailersInApp: boolean
    /** Taken off a saved position when a video resumes, in milliseconds; null for the app's default. */
    rewindMs: number | null
    /** The carousel at the top of Home (the apps' "Carousel on Home"), on by default. */
    carousel: boolean
    /** Settings > Home's other rows (the apps' HomeLayout), each on by default. */
    homeContinue: boolean
    homeRecent: boolean
    homeStarred: boolean
    /** Recent searches are kept to pick again (the apps' `remember_searches`), on by default. */
    rememberSearches: boolean
    reduceMotion: 'auto' | 'on' | 'off'
    contrast: 'auto' | 'on' | 'off'
    /** The order of Home's rows (the apps' HomeOrder): row keys, comma separated; empty is Home's own order. */
    homeOrder: string
}

const MB = 1024 * 1024
/** SizeFilter.DEFAULT_MIN and DEFAULT_MAX: every video, no top (R1, design/redesign/show-every-video.md). */
export const SIZE_DEFAULTS = { sizeMin: 0, sizeMax: 8 * 1024 * MB }
/** SizeFilter.OLD_DEFAULT_MIN: the default until 2026-11. It was never a choice, so a saved 400 MB reads as "not set". */
export const OLD_DEFAULT_MIN = 400 * MB
export const DEFAULTS: Settings = {
    theme: 'system',
    ...SIZE_DEFAULTS,
    sort: 'Newest',
    posters: true,
    trailersInApp: true,
    rewindMs: null,
    carousel: true,
    homeContinue: true,
    homeRecent: true,
    homeStarred: true,
    rememberSearches: true,
    reduceMotion: 'auto',
    contrast: 'auto',
    homeOrder: '',
}

const THEME_KEY = 'tmplayer-theme'

// Settings

const [settings, setSettings] = createSignal<Settings>({ ...DEFAULTS, theme: savedTheme() })
export { settings, setSettings }

/** The theme is also kept in localStorage, so index.html paints the first frame in it. */
function savedTheme(): Theme {
    try {
        const t = localStorage.getItem(THEME_KEY)
        if (t === 'dark' || t === 'light' || t === 'black' || t === 'system') return t
    } catch {}
    return DEFAULTS.theme
}

/** Reduce motion and High contrast on the page: `html[data-motion]` is reduce or full, `html[data-contrast]` high or off; Automatic leaves both off the element and the system's own query decides (styles.css). */
export function applyLook(s: { reduceMotion: Settings['reduceMotion']; contrast: Settings['contrast'] }) {
    const root = document.documentElement
    if (s.reduceMotion === 'on') root.dataset.motion = 'reduce'
    else if (s.reduceMotion === 'off') root.dataset.motion = 'full'
    else delete root.dataset.motion
    if (s.contrast === 'on') root.dataset.contrast = 'high'
    else if (s.contrast === 'off') root.dataset.contrast = 'off'
    else delete root.dataset.contrast
}

export function applyTheme(theme: Theme) {
    const root = document.documentElement
    if (theme === 'system') delete root.dataset.theme
    else root.dataset.theme = theme
    // The browser's own bars take the page's ground: index.html's pair follow the device, a chosen theme pins both.
    const ground = { dark: '#0c0e10', light: '#f6fafd', black: '#000000' }
    document.querySelectorAll<HTMLMetaElement>('meta[name=theme-color]').forEach((m) => {
        m.content = theme === 'system' ? (m.getAttribute('media')?.includes('light') ? ground.light : ground.dark) : ground[theme]
    })
    try {
        if (theme === 'system') localStorage.removeItem(THEME_KEY)
        else localStorage.setItem(THEME_KEY, theme)
    } catch {}
}

export function setSetting<K extends keyof Settings>(key: K, value: Settings[K]) {
    const next = { ...settings(), [key]: value }
    setSettings(next)
    write('settings', next)
    if (key === 'theme') applyTheme(value as Theme)
    if (key === 'reduceMotion' || key === 'contrast') applyLook(next)
}
