// The rules a sign in number goes through, as the app's PhoneEntry, PhoneRules, PhoneCountries
// and PhoneFormat apply them (ui/auth and core/data), over Telegram's own country list.
import type { Country, DialCode } from '../tg/types'

/** Telegram's own floor: seven digits, the calling code counted. */
export const MIN_DIGITS = 7
/** E.164 allows fifteen digits, the calling code included. */
export const MAX_DIGITS = 15
/** The longest calling code Telegram issues ("1242", "1868"). */
export const MAX_DIAL_DIGITS = 4

/**
 * Calling codes whose national numbers keep a leading 0: Italy and the Vatican, San Marino, Ivory
 * Coast, Gabon and Congo. Elsewhere a 0 in front is the trunk prefix, dropped internationally.
 */
const KEEPS_ZERO = new Set(['39', '378', '225', '241', '242'])

/** Which country a shared code means when nothing else says: the one most numbers belong to. */
const USUAL: Record<string, string> = {
    '1': 'US', '7': 'RU', '44': 'GB', '39': 'IT', '47': 'NO', '61': 'AU',
    '262': 'RE', '290': 'SH', '358': 'FI', '590': 'GP', '599': 'CW', '212': 'MA',
}

// The zero of each script's digits a phone keyboard may type: Arabic-Indic, Persian, Devanagari,
// Bengali, the other Indian scripts, Thai, Lao, Tibetan, Myanmar, Khmer, Mongolian, full width.
const ZEROS = [0x30, 0x660, 0x6f0, 0x966, 0x9e6, 0xa66, 0xae6, 0xb66, 0xbe6, 0xc66, 0xce6, 0xd66, 0xe50, 0xed0, 0xf20, 0x1040, 0x17e0, 0x1810, 0xff10]

/** The digits of `raw` as 0 to 9, whatever script they were typed in; everything else dropped. */
export function digits(raw: string): string {
    let out = ''
    for (const ch of raw) {
        const cp = ch.codePointAt(0)!
        const zero = ZEROS.find((z) => cp >= z && cp <= z + 9)
        if (zero !== undefined) out += String(cp - zero)
    }
    return out
}

export const dialCodes = (c: Country) => c.codes.map((k) => k.code)

/** Whether a country answers to the search box: by name, by ISO code, or by calling code ("+91"). */
export function matches(c: Country, query: string): boolean {
    const q = query.trim().replace(/^\+/, '').toLowerCase()
    if (!q) return true
    return c.name.toLowerCase().includes(q) || c.iso.toLowerCase() === q || dialCodes(c).some((d) => d.startsWith(q))
}

/** National digits for `dial`: the trunk 0 dropped where it is one, capped at E.164's length. */
export function national(raw: string, dial: string): string {
    const trimmed = KEEPS_ZERO.has(dial) ? raw : raw.replace(/^0+/, '')
    return trimmed.slice(0, Math.max(0, MAX_DIGITS - dial.length))
}

/**
 * A whole international number ("+44 7700 900123", "0044 7700...") taken apart into its calling
 * code (the longest one Telegram knows that it begins with) and the rest. Null when it is not
 * written as one, so the digits stay where they were typed.
 */
export function splitInternational(raw: string, countries: Country[]): [string, string] | null {
    const start = raw.search(/[+\p{Nd}]/u)
    if (start < 0) return null
    const lead = raw.slice(start)
    const all = digits(lead)
    const plus = lead.startsWith('+')
    if (!plus && !(all.startsWith('00') && all.length > 6)) return null
    const body = plus ? all : all.slice(2)
    const codes = new Set(countries.flatMap(dialCodes))
    for (let take = Math.min(MAX_DIAL_DIGITS, body.length - 1); take >= 1; take--) {
        const dial = body.slice(0, take)
        if (codes.has(dial)) return [dial, body.slice(take)]
    }
    return null
}

/** The calling code entry of `c` for `dial` whose area code begins `nat`, longest first. */
function areaMatch(c: Country, dial: string, nat: string): number {
    let best = -1
    for (const k of c.codes) {
        if (k.code !== dial) continue
        for (const p of k.prefixes) if (nat.startsWith(p) && p.length > best) best = p.length
    }
    return best
}

/**
 * The country for `dial`: the only one it reaches; where it is shared, the one whose area codes
 * the number starts with ("+1 204" is Canada), else the one already chosen, else the usual one.
 * Null for a code no country has, which the form shows as invalid.
 */
export function countryFor(countries: Country[], dial: string, nat: string, chosenIso: string | null): Country | null {
    if (!dial) return null
    const reach = countries.filter((c) => dialCodes(c).includes(dial))
    if (reach.length <= 1) return reach[0] ?? null
    let byArea: Country | null = null
    let longest = 0
    for (const c of reach) {
        const n = areaMatch(c, dial, nat)
        if (n > longest) [byArea, longest] = [c, n]
    }
    return byArea ?? reach.find((c) => c.iso === chosenIso) ?? reach.find((c) => c.iso === USUAL[dial]) ?? reach.find((c) => c.codes[0]?.code === dial) ?? reach[0]
}

/** How `nat` is grouped in its country: the first of Telegram's patterns whose fixed digits it fits. */
export function patternFor(c: Country | null, dial: string, nat: string): string | null {
    const entry: DialCode | undefined = c?.codes.find((k) => k.code === dial)
    const fits = (p: string) => {
        let i = 0
        for (const ch of p) {
            if (ch === ' ') continue
            if (i >= nat.length) return true
            if (ch !== 'X' && ch !== nat[i]) return false
            i++
        }
        return true
    }
    return entry?.patterns.find(fits) ?? null
}

/**
 * `nat` laid onto `pattern`: each digit takes the next slot (an X or a digit), the spaces between
 * are kept, nothing trails the last digit typed; digits past the pattern follow as they are.
 */
export function format(nat: string, pattern: string | null): string {
    if (!pattern || !nat) return nat
    let out = ''
    let used = 0
    for (const ch of pattern) {
        if (used === nat.length) break
        if (ch === ' ') out += ch
        else out += nat[used++]
    }
    return out + nat.slice(used)
}

/** Where the caret goes in `formatted` to stand after `count` digits. */
export function caretAfter(count: number, formatted: string): number {
    if (count <= 0) return 0
    let seen = 0
    for (let i = 0; i < formatted.length; i++) if (/\d/.test(formatted[i]) && ++seen === count) return i + 1
    return formatted.length
}

/** The flag for an ISO code, drawn by the platform's emoji font from two regional indicators. */
export function flag(iso: string): string {
    const code = iso.trim().toUpperCase()
    if (!/^[A-Z]{2}$/.test(code)) return ''
    return String.fromCodePoint(...[...code].map((ch) => 0x1f1e6 + ch.charCodeAt(0) - 65))
}

/** The first region named in the browser's languages: "IN" for "en-IN". A numeric one names no country. */
export function regionOf(tags: readonly string[]): string {
    for (const tag of tags) {
        const region = tag.split(/[-_]/).slice(1).find((p) => /^[A-Za-z]{2}$/.test(p))
        if (region) return region.toUpperCase()
    }
    return ''
}

/** The calling code as the sign in shows it beside the country ("+91"), SignInWords.dialChip; null while there is none. */
export function dialChip(dial: string): string | null {
    const d = digits(dial)
    return d ? `+${d}` : null
}

/** Whether Telegram offers another way to send this code (AuthState.Code.canResend); an email code can always be asked again. */
export function canResend(sent: { next: string; type: string }): boolean {
    return sent.next !== 'none' || sent.type === 'email'
}
