// The page's words, from the app's own catalogs (core's i18n/*.json): only the sections
// vite.config.ts picks (strings.config.ts). English is in the first load; another language's lines
// are a hashed file fetched once it is chosen (WB12), laid over English key by key, so a line its
// catalog lacks stays English. `t` reads the shown dictionary, so a switch rewrites the words in
// place: nothing is remounted, and a playing video plays on.
import { batch, createSignal } from 'solid-js'
import { flatten, translator, type TemplateResolver } from '@solid-primitives/i18n'
import strings from 'virtual:strings'
import catalogs from 'virtual:catalogs'
import { showToast } from './ui/Toast'
import { ENGLISH, findLanguage, format, formatsFor, isRtl, loadCatalog, resolveLanguage } from './ui/language'

const english = flatten(strings)
type Dict = typeof english

type Args = Record<string, string | number>

/** The viewer's choice: a shipped tag, or absent to follow the browser. */
export const LANGUAGE_KEY = 'tmplayer-language'
/** The language last shown, for index.html to set <html lang dir> before the first paint. */
const SHOWN_KEY = 'tmplayer-language-shown'
/** A catalog that has not come in by then counts as failed. */
const LOAD_TIMEOUT_MS = 15_000

function readSaved(): string {
    try {
        return findLanguage(localStorage.getItem(LANGUAGE_KEY))?.tag ?? ''
    } catch {
        return ''
    }
}

/** The browser's languages, best first. */
function browserLanguages(): string[] {
    try {
        const list = navigator.languages
        if (list && list.length) return Array.from(list)
        return navigator.language ? [navigator.language] : []
    } catch {
        return []
    }
}

const [choice, setChoice] = createSignal(readSaved())
const [shown, setShown] = createSignal(ENGLISH)
const [dict, setDict] = createSignal<Dict>(english)
const [failed, setFailed] = createSignal<string | null>(null)
const [loading, setLoading] = createSignal<string | null>(null)

/** Settings' choice: a tag, or "" for Automatic. */
export const languageChoice = choice
/** The language the page is in now (a shipped tag). */
export const language = shown
/** The language whose catalog did not come in (the page stays in the one it was in), or null. */
export const languageFailed = failed
/** The language whose catalog is on its way, or null. */
export const languageLoading = loading
/** What Automatic picks from the browser's languages now. */
export const browserLanguage = () => resolveLanguage('', browserLanguages())

let formats = formatsFor(ENGLISH)

const fill = ((text: string, args?: Args) => (args || text.includes('{') ? format(text, args ?? {}, formats) : text)) as TemplateResolver

/** `t('login.qr_title')`, `t('login.delivery_sms', { number })`. */
export const t = translator(() => dict(), fill) as (key: keyof Dict, args?: Args) => string

/** <html lang dir>, and the note index.html reads before the next load's first paint. */
function mark(tag: string) {
    const root = document.documentElement
    root.lang = tag
    root.dir = isRtl(tag) ? 'rtl' : 'ltr'
    try {
        localStorage.setItem(SHOWN_KEY, tag)
    } catch {}
}

function commit(tag: string, next: Dict) {
    formats = formatsFor(tag)
    batch(() => {
        setDict(() => next)
        setShown(tag)
    })
    mark(tag)
}

/** Catalogs read this session, so a switch back is instant: at most the 21 others, about 60 KB each. */
const read = new Map<string, Dict>()

/** The page strings of [tag], fetched as a hashed file (the service worker keeps it once fetched) and read as untrusted. */
const fetchCatalog = (tag: string): Promise<Dict> => loadCatalog((catalogs as Record<string, string | undefined>)[tag], english, (url, init) => fetch(url, init), LOAD_TIMEOUT_MS)

let generation = 0
/** The first show has run, so <html lang dir> says what is shown. */
let settled = false

/**
 * Shows [tag]: at once for English, else once its catalog is in. A catalog that does not come in
 * leaves the page as it was (English at the start) and sets [languageFailed] for a Try again. Only
 * the last call counts, so quick switches never land out of order.
 */
async function show(tag: string): Promise<boolean> {
    const mine = ++generation
    setFailed(null)
    // Already shown: nothing to fetch (a load on its way to another language is dropped).
    if (settled && tag === shown()) {
        setLoading(null)
        return true
    }
    settled = true
    if (tag === ENGLISH || !findLanguage(tag)) {
        setLoading(null)
        commit(ENGLISH, english)
        return true
    }
    setLoading(tag)
    try {
        const next = read.get(tag) ?? (await fetchCatalog(tag))
        read.set(tag, next)
        if (mine !== generation) return false
        commit(tag, next)
        return true
    } catch (e) {
        if (mine !== generation) return false
        console.warn('[i18n] catalog', tag, e)
        // index.html may have marked the page for this language before its words came in.
        mark(shown())
        setFailed(tag)
        return false
    } finally {
        if (mine === generation) setLoading(null)
    }
}

/** The language to show for the stored choice and the browser's languages, loaded. Called once by main.tsx before the first render. */
export function startLanguage(): Promise<boolean> {
    // A choice made in another tab: this one follows, in place, so a video playing here plays on.
    window.addEventListener('storage', (e: StorageEvent) => {
        if (e.key !== LANGUAGE_KEY && e.key !== null) return
        const next = readSaved()
        if (next === choice()) return
        setChoice(next)
        void show(resolveLanguage(next, browserLanguages()))
    })
    // So does a change of the browser's own languages while Automatic is on.
    window.addEventListener('languagechange', () => {
        if (!choice()) void show(resolveLanguage('', browserLanguages()))
    })
    return show(resolveLanguage(choice(), browserLanguages()))
}

/** Settings > Language: [tag], or "" for Automatic; kept, and shown as soon as its catalog is in. */
export function setLanguage(tag: string): Promise<boolean> {
    const picked = findLanguage(tag)?.tag ?? ''
    setChoice(picked)
    try {
        if (picked) localStorage.setItem(LANGUAGE_KEY, picked)
        else localStorage.removeItem(LANGUAGE_KEY)
    } catch {}
    return show(resolveLanguage(picked, browserLanguages()))
}

/** Try again after a failed catalog. */
export function retryLanguage(): Promise<boolean> {
    return show(resolveLanguage(choice(), browserLanguages()))
}

/** At the start, when the viewer's language did not come in: the page is in English, with a Try again (Settings has one too). */
export function languageFailedToast() {
    const tag = failed()
    if (!tag) return
    showToast({ kind: 'error', text: t('web.language_failed', { language: languageName(tag) }), action: { label: t('common.try_again'), run: () => void retryLanguage().then((ok) => !ok && languageFailedToast()) } })
}

/** The hashed file of [tag]'s page strings (English has none: it is in the first load), for the worker's rules to read in the same words. */
export const catalogUrl = (tag: string): string | null => (tag === ENGLISH ? null : ((catalogs as Record<string, string | undefined>)[tag] ?? null))

/** A language's own name ("Deutsch"), the same in every language. */
export const languageName = (tag: string) => findLanguage(tag)?.name ?? tag

/** A number written as the shown language writes it ("1.234,5" in German). */
export function formatNumber(n: number, options?: Intl.NumberFormatOptions): string {
    try {
        return new Intl.NumberFormat(shown(), options).format(n)
    } catch {
        return String(n)
    }
}

/** A date written in the shown language ("3. März"). */
export function formatDate(at: number | Date, options: Intl.DateTimeFormatOptions): string {
    const date = typeof at === 'number' ? new Date(at) : at
    try {
        return date.toLocaleDateString(shown(), options)
    } catch {
        return date.toDateString()
    }
}

/** A wait in the largest unit that fits it, as the app's Failures.waitPhrase: "42 seconds", "5 minutes". */
export function waitPhrase(seconds: number): string {
    const [n, unit] =
        seconds < 60 ? [seconds, 'second'] : seconds < 3600 ? [Math.floor(seconds / 60), 'minute'] : seconds < 2 * 86_400 ? [Math.floor(seconds / 3600), 'hour'] : [Math.floor(seconds / 86_400), 'day']
    try {
        return new Intl.NumberFormat(shown(), { style: 'unit', unit: unit as string, unitDisplay: 'long' }).format(n as number)
    } catch {
        return `${n} ${unit}${n === 1 ? '' : 's'}`
    }
}

/** "1:05", as the app's resend clock. */
export const clock = (seconds: number) => `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
