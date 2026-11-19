// The page's languages (WB12): which of the apps' 22 catalogs a viewer gets, how a downloaded
// catalog is read, and the catalog's ICU subset. Pure, so node tests run it; src/i18n.ts holds the
// signals and the loading. The list and the resolution are core's Languages (WebRules
// languageList, languageResolve): the page needs them before the Telegram worker, which holds the
// rules, has started, so they are kept here and language.test.ts checks them against core's on
// every run.

export interface Language {
    tag: string
    /** The language's own name, the same in every language ("Deutsch"). */
    name: string
    rtl: boolean
}

/** Languages.all, in its order: English first. */
export const LANGUAGES: readonly Language[] = [
    ['en', 'English'],
    ['es-419', 'Español (Latinoamérica)'],
    ['pt-BR', 'Português (Brasil)'],
    ['fr', 'Français'],
    ['de', 'Deutsch'],
    ['it', 'Italiano'],
    ['pl', 'Polski'],
    ['cs', 'Čeština'],
    ['ru', 'Русский'],
    ['uk', 'Українська'],
    ['tr', 'Türkçe'],
    ['id', 'Bahasa Indonesia'],
    ['vi', 'Tiếng Việt'],
    ['ar', 'العربية', true],
    ['fa', 'فارسی', true],
    ['zh-CN', '简体中文'],
    ['zh-TW', '繁體中文'],
    ['ja', '日本語'],
    ['ko', '한국어'],
    ['hi', 'हिन्दी'],
    ['bn', 'বাংলা'],
    ['ml', 'മലയാളം'],
].map(([tag, name, rtl]) => ({ tag: tag as string, name: name as string, rtl: rtl === true }))

export const ENGLISH = 'en'

/** The shipped language for [tag], matched as Languages.find does (case aside), or undefined. */
export function findLanguage(tag: string | null | undefined): Language | undefined {
    if (typeof tag !== 'string') return undefined
    const low = tag.toLowerCase()
    return LANGUAGES.find((l) => l.tag.toLowerCase() === low)
}

export const isRtl = (tag: string) => findLanguage(tag)?.rtl ?? false

/** Underscores to hyphens and the old codes to today's (`in` to `id`), as Languages.normalise. */
function normalise(tag: string): string {
    const parts = tag.trim().replace(/_/g, '-').split('-').filter((p) => p.length > 0)
    if (!parts.length) return ''
    const l = parts[0].toLowerCase()
    const language = l === 'in' ? 'id' : l === 'iw' ? 'he' : l === 'ji' ? 'yi' : l
    return [language, ...parts.slice(1)].join('-')
}

/** The shipped tag for one browser tag, or null when none fits (Languages.match). */
export function matchLanguage(systemTag: string): string | null {
    const parts = normalise(systemTag).split('-')
    const language = parts[0].toLowerCase()
    const rest = parts.slice(1)
    const script = rest.find((p) => p.length === 4)?.toLowerCase()
    const region = rest.find((p) => p.length === 2 || (p.length === 3 && /^\d{3}$/.test(p)))?.toUpperCase()
    if (language === 'es') return 'es-419'
    if (language === 'pt') return 'pt-BR'
    if (language === 'zh') {
        if (script === 'hans') return 'zh-CN'
        if (script === 'hant') return 'zh-TW'
        if (region === undefined || region === 'CN' || region === 'SG' || region === 'MY') return 'zh-CN'
        if (region === 'TW' || region === 'HK' || region === 'MO') return 'zh-TW'
        return null
    }
    return LANGUAGES.find((l) => l.tag === language)?.tag ?? null
}

/**
 * The catalog to show (Languages.resolve): the [saved] choice ("" or null follows the browser), else
 * the first of the browser's [system] languages that matches a shipped one, else English.
 */
export function resolveLanguage(saved: string | null | undefined, system: readonly unknown[]): string {
    if (typeof saved === 'string' && saved.trim()) {
        const found = findLanguage(saved)
        if (found) return found.tag
    }
    for (const tag of system) {
        if (typeof tag !== 'string') continue
        const m = matchLanguage(tag)
        if (m) return m
    }
    return ENGLISH
}

// ---- the catalog's ICU subset ---------------------------------------------------------------------

type Args = Record<string, string | number>

/** How one language counts and writes numbers, for `{n, plural, ...}` and `#`. */
export interface Formats {
    plural: (n: number) => string
    number: (n: number) => string
}

/** Intl's rules for [tag], or English's where the engine lacks them (the TV build's oldest Chromium). */
export function formatsFor(tag: string): Formats {
    try {
        const p = new Intl.PluralRules(tag)
        const n = new Intl.NumberFormat(tag)
        return { plural: (x) => p.select(x), number: (x) => n.format(x) }
    } catch {
        return { plural: (x) => (x === 1 ? 'one' : 'other'), number: (x) => String(x) }
    }
}

/** Where the `}` closing a `{` opened just before `from` is; the text's length when it never closes. */
function closing(text: string, from: number): number {
    let depth = 1
    for (let i = from; i < text.length; i++) {
        if (text[i] === '{') depth++
        else if (text[i] === '}' && --depth === 0) return i
    }
    return text.length
}

const HEAD = /^\s*(\w+)\s*(?:,\s*(plural|select)\s*,)?/
const CASE = /^\s*(=?\w+)\s*\{/

/** A `{...}` group's `key {message}` cases, from `from` on. */
function casesOf(inner: string, from: number): Map<string, string> {
    const cases = new Map<string, string>()
    for (let j = from; j < inner.length; ) {
        const m = CASE.exec(inner.slice(j))
        if (!m) break
        const open = j + m[0].length
        const close = closing(inner, open)
        cases.set(m[1], inner.slice(open, close))
        j = close + 1
    }
    return cases
}

/**
 * The catalog's ICU subset, as the apps' Translator reads it: `{name}`, and `{count, plural, one
 * {# video} other {# videos}}` (with `=0` cases) and `{form, select, tv {...} other {...}}`,
 * nested. A whole MessageFormat library would weigh more than the page's strings. Text it cannot
 * read is shown as it is, never thrown.
 */
export function format(text: string, args: Args, formats: Formats, hash?: string): string {
    let out = ''
    for (let i = 0; i < text.length; ) {
        const c = text[i]
        if (c === '#' && hash !== undefined) {
            out += hash
            i++
            continue
        }
        if (c !== '{') {
            out += c
            i++
            continue
        }
        const end = closing(text, i + 1)
        const inner = text.slice(i + 1, end)
        i = end + 1
        const head = HEAD.exec(inner)
        if (!head) {
            out += `{${inner}}`
            continue
        }
        const [whole, name, kind] = head
        if (!kind) {
            out += Object.prototype.hasOwnProperty.call(args, name) ? String(args[name]) : `{${inner}}`
            continue
        }
        const cases = casesOf(inner, whole.length)
        const value = args[name]
        if (kind === 'plural') {
            const n = Number(value)
            const pick = cases.get(`=${n}`) ?? cases.get(formats.plural(n)) ?? cases.get('other') ?? ''
            out += format(pick, args, formats, formats.number(n))
        } else out += format(cases.get(String(value)) ?? cases.get('other') ?? '', args, formats, hash)
    }
    return out
}

/**
 * The argument names [text] uses, walked as [format] walks it; null when it has a `{` that never
 * closes or a group that names nothing, which [format] would show as it stands.
 */
export function argumentNames(text: string, into: Set<string> = new Set()): Set<string> | null {
    for (let i = 0; i < text.length; i++) {
        if (text[i] !== '{') continue
        const end = closing(text, i + 1)
        if (end >= text.length) return null
        const inner = text.slice(i + 1, end)
        i = end
        const head = HEAD.exec(inner)
        if (!head) return null
        into.add(head[1])
        if (!head[2]) continue
        for (const message of casesOf(inner, head[0].length).values()) if (!argumentNames(message, into)) return null
    }
    return into
}

// ---- reading a downloaded catalog -----------------------------------------------------------------

/** Longer than any line the page shows: past it a value is taken for garbage. */
const MAX_LINE = 4000

const isRecord = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)

/**
 * One language's page strings over English's, key by key: a line the catalog lacks, or has as
 * anything but text, too long, with a `{` that never closes or naming an argument English does not
 * pass, stays English. Throws only when the file is not a catalog at all (not an object), which the
 * loader counts as a failed load.
 */
export function readCatalog<D extends object>(raw: unknown, english: D): { dict: D; translated: number } {
    if (!isRecord(raw)) throw new Error('catalog is not an object')
    const base = english as Record<string, unknown>
    const dict: Record<string, unknown> = { ...base }
    let translated = 0
    for (const section of Object.keys(raw)) {
        const lines = raw[section]
        if (!isRecord(lines)) continue
        for (const key of Object.keys(lines)) {
            const value = lines[key]
            const flat = `${section}.${key}`
            if (typeof value !== 'string' || !value || value.length > MAX_LINE) continue
            const own = Object.prototype.hasOwnProperty.call(base, flat) ? base[flat] : undefined
            if (typeof own !== 'string') continue
            const theirs = argumentNames(value)
            if (!theirs) continue
            const ours = argumentNames(own)
            if (ours && [...theirs].some((n) => !ours.has(n))) continue
            dict[flat] = value
            translated++
        }
    }
    return { dict: dict as unknown as D, translated }
}

/**
 * Fetches one language's page strings from [url] and reads them over [english] ([readCatalog]).
 * Throws when there is no file, the answer is not 200, the text is not JSON or not a catalog, or
 * nothing came in [timeoutMs]: the page then stays in the language it was in.
 */
export async function loadCatalog<D extends object>(
    url: string | undefined,
    english: D,
    fetcher: (url: string, init: { signal: AbortSignal }) => Promise<{ ok: boolean; status: number; text: () => Promise<string> }>,
    timeoutMs: number,
): Promise<D> {
    if (!url) throw new Error('no catalog file')
    const abort = new AbortController()
    const timer = setTimeout(() => abort.abort(), timeoutMs)
    try {
        const answer = await Promise.race([
            fetcher(url, { signal: abort.signal }),
            new Promise<never>((_, reject) => abort.signal.addEventListener('abort', () => reject(new Error('catalog timed out')))),
        ])
        if (!answer.ok) throw new Error(`catalog answered ${answer.status}`)
        const text = await answer.text()
        let raw: unknown
        try {
            raw = JSON.parse(text)
        } catch {
            throw new Error('catalog is not JSON')
        }
        return readCatalog(raw, english).dict
    } finally {
        clearTimeout(timer)
    }
}
