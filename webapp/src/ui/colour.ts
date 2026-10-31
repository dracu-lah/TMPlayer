// The colour a name wears where there is no picture: a chat with no photo, an account with none.
// The same eight pairs and the same hash as the apps' core/NameColour.kt; keep the two alike, or
// a chat is one colour here and another in the apps, which reads as a bug.

/**
 * Eight hand-tuned dark pairs, the lighter stop first. White on either stop is 6.9:1 or better.
 * The dark stop sits 65% of the way back toward the light one rather than near black (2026-10-31:
 * a fallback gradient read as plain black on an OLED screen); keep this list and the Kotlin one
 * in `core/src/pureMain/kotlin/com/tmplayer/data/NameColour.kt` alike.
 */
export const PAIRS: readonly (readonly [string, string])[] = [
    ['#34397A', '#292D64'],
    ['#145A5A', '#10494A'],
    ['#6A2A5E', '#54214B'],
    ['#7A3524', '#60291C'],
    ['#245C34', '#1C492A'],
    ['#1F4A7E', '#183A66'],
    ['#4A3680', '#3A2A67'],
    ['#6B5719', '#554514'],
]

/**
 * The name as the hash reads it: the ends trimmed, runs of blanks as one space, lower case. A
 * blank is one of the four characters spelt out here and not `\s`, which would take in a no-break
 * space that Kotlin's own rule leaves alone.
 */
function key(name: string | null | undefined): string {
    const text = name ?? ''
    let out = ''
    let blank = false
    // By code unit, as Kotlin reads a String: a surrogate pair's two halves are copied in turn,
    // which comes to the same string.
    for (let i = 0; i < text.length; i++) {
        const c = text[i]
        if (c === ' ' || c === '\t' || c === '\n' || c === '\r') {
            blank = true
            continue
        }
        if (blank && out.length > 0) out += ' '
        blank = false
        out += c
    }
    return out.toLowerCase()
}

/** Which of the [PAIRS] a name wears: 0 until PAIRS.length, never negative. */
export function pairIndexOf(name: string | null | undefined): number {
    const text = key(name)
    // FNV-1a over the UTF-16 code units, low byte then high, in 32 bit arithmetic (Math.imul).
    let h = 0x811c9dc5 | 0
    for (let i = 0; i < text.length; i++) {
        const code = text.charCodeAt(i)
        h = Math.imul(h ^ (code & 0xff), 0x01000193)
        h = Math.imul(h ^ (code >>> 8), 0x01000193)
    }
    h = h ^ (h >>> 16)
    const size = PAIRS.length
    return ((h % size) + size) % size
}

/** The pair a name wears, the lighter stop first. */
export function pairOf(name: string | null | undefined): readonly [string, string] {
    return PAIRS[pairIndexOf(name)]
}

/**
 * The CSS background the name's stand-in wears: its gradient with the same soft light in the top
 * start corner the apps draw, so a chat without a photo looks the same in the browser.
 */
export function nameArt(name: string | null | undefined): string {
    const [light, deep] = pairOf(name)
    return (
        `radial-gradient(circle at 15% 10%, rgba(255, 255, 255, .10), rgba(255, 255, 255, 0) 85%),` +
        ` linear-gradient(135deg, ${light}, ${deep})`
    )
}
