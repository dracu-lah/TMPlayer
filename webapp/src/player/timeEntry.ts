// A typed time, for the player's "Go to a time" (Cmd or Ctrl+G): core's TimeEntry.kt for the web,
// copied line by line because the prompt answers as the viewer types and the rules live in the
// worker. timeEntry.test.ts holds TimeEntryTest's cases. What it reads, with or without spaces:
// a clock (1:23:45, 83:10, 0:45), units (1h20m, 1h 20m 5s, 90s, 45m, 2h, min and sec too), a bare
// number as minutes (45 is 45:00), a percentage of the length (50%, 12.5%), any of those after +
// or - as a step from where the video is. Digits in any script count, and so do the full width
// colon and the Arabic percent sign. Null for text that is none of these, or a place past the end
// of a video whose length is known; a step is kept inside the video instead. The text is the
// viewer's own, so nothing in it is trusted: every path ends in a number or null.

const UNITS = /(\d+)(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)/g
const DIGIT = /^\p{Nd}$/u
/** Longer than any time anyone types; the field holds no more. */
export const MAX_TIME_TEXT = 24

/** The value of a decimal digit of any script: its place in the run of ten it belongs to. */
function digitValue(ch: string): number {
    let cp = ch.codePointAt(0)!
    let start = cp
    // Runs of digits sit next to each other in tens (the mathematical ones are five in a row).
    for (let i = 0; i < 60 && DIGIT.test(String.fromCodePoint(start - 1)); i++) start--
    return (cp - start) % 10
}

/** Lower case, ASCII digits, the plain colon and percent sign, inner spaces gone. */
function normalise(text: string): string {
    let out = ''
    for (const c of text.trim().toLowerCase()) {
        if (DIGIT.test(c)) out += String(digitValue(c))
        else if (c === '：') out += ':'
        else if (c === '٪' || c === '％') out += '%'
        else if (c === '＋') out += '+'
        else if (c === '−' || c === '－') out += '-'
        else if (c === '٫') out += '.'
        else if (/\s/.test(c)) continue
        else out += c
    }
    return out
}

const whole = (s: string): number | null => {
    if (!/^\d+$/.test(s)) return null
    const n = Number(s)
    return Number.isSafeInteger(n) ? n : null
}

function amount(t: string, durationMs: number): number | null {
    if (t.endsWith('%')) {
        if (durationMs <= 0) return null
        const raw = t.slice(0, -1).trim().replace(',', '.')
        // Stricter than core on purpose: Kotlin's toDoubleOrNull also takes 1e2, NaN and Infinity
        // (NaN% lands at 0 there); here only digits with an optional fraction are a percentage.
        if (!/^\d+(\.\d+)?$/.test(raw)) return null
        const number = Number(raw)
        if (number < 0 || number > 100) return null
        return Math.trunc((durationMs * number) / 100)
    }
    if (t.includes(':')) {
        const parts = t.split(':').map((p) => p.trim())
        if (parts.length < 2 || parts.length > 3) return null
        const numbers = parts.map(whole)
        if (numbers.some((n) => n === null)) return null
        const [h, m, s] = (numbers.length === 3 ? numbers : [0, ...numbers]) as number[]
        if (s >= 60) return null
        if (numbers.length === 3 && m >= 60) return null
        // A huge hour field: each part fits, the milliseconds would not.
        const ms = ((h * 60 + m) * 60 + s) * 1000
        return Number.isSafeInteger(ms) ? ms : null
    }
    if (/^\d+$/.test(t)) {
        const n = whole(t)
        return n === null ? null : n * 60_000
    }
    const units = [...t.matchAll(UNITS)]
    if (!units.length || units.map((u) => u[0]).join('') !== t) return null
    let total = 0
    const seen = new Set<string>()
    for (const u of units) {
        const number = whole(u[1])
        if (number === null) return null
        const unit = u[2][0]
        if (seen.has(unit)) return null
        seen.add(unit)
        total += number * (unit === 'h' ? 3_600_000 : unit === 'm' ? 60_000 : 1_000)
    }
    return Number.isSafeInteger(total) ? total : null
}

/** Where in the video the text points, in milliseconds; null when it points nowhere in it. */
export function parseTime(text: string, durationMs: number, positionMs = 0): number | null {
    if (typeof text !== 'string') return null
    let t = normalise(text.slice(0, MAX_TIME_TEXT * 2))
    if (!t) return null
    const sign = t[0] === '+' ? 1 : t[0] === '-' ? -1 : 0
    if (sign !== 0) t = t.slice(1).trim()
    if (!t) return null
    const got = amount(t, durationMs)
    if (got === null) return null
    if (sign !== 0) {
        const target = positionMs + sign * got
        return Math.max(0, durationMs > 0 ? Math.min(durationMs, target) : target)
    }
    if (durationMs > 0 && got > durationMs) return null
    return got
}
