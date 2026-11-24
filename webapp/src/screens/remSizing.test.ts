// WP-55: text follows the system size. No font size or line height in a literal px outside the TV
// sheets (tv.css and player-tv.css size in --dp and --u, tvTextFloor.test.ts holds them to the floor).
// `max(12px, min(0.75rem, 15px))` is allowed: a px floor or cap around a rem value.
import assert from 'node:assert/strict'
import { readdirSync, readFileSync } from 'node:fs'
import { test } from 'node:test'

const TV_SHEETS = ['tv/tv.css', 'player/player-tv.css']

function sheets(dir = new URL('../', import.meta.url), prefix = ''): { path: string; css: string }[] {
    const out: { path: string; css: string }[] = []
    for (const e of readdirSync(dir, { withFileTypes: true })) {
        if (e.isDirectory()) out.push(...sheets(new URL(`${e.name}/`, dir), `${prefix}${e.name}/`))
        else if (e.name.endsWith('.css')) out.push({ path: prefix + e.name, css: readFileSync(new URL(e.name, dir), 'utf8') })
    }
    return out
}

/** Declarations that set a text size or a line height, with their value. */
function textSizes(css: string): { at: number; prop: string; value: string }[] {
    const clean = css.replace(/\/\*[\s\S]*?\*\//g, (c) => c.replace(/[^\n]/g, ' '))
    const out: { at: number; prop: string; value: string }[] = []
    for (const m of clean.matchAll(/(?<![-\w])(font-size|line-height|font)\s*:\s*([^;}]+)/g)) {
        out.push({ at: clean.slice(0, m.index).split('\n').length, prop: m[1]!, value: m[2]!.trim() })
    }
    return out
}

/** A literal px length standing on its own (not inside min(), max() or clamp()). */
function literalPx(prop: string, value: string): boolean {
    if (/\b(min|max|clamp)\(/.test(value)) return false
    if (prop === 'font') return /(^|\s|\/)\d*\.?\d+px(\s|\/|$)/.test(value)
    return /^\d*\.?\d+px$/.test(value)
}

test('no CSS outside the TV sheets sizes text in a literal px', () => {
    const bad: string[] = []
    for (const { path, css } of sheets()) {
        if (TV_SHEETS.includes(path)) continue
        for (const s of textSizes(css)) if (literalPx(s.prop, s.value)) bad.push(`${path}:${s.at} ${s.prop}: ${s.value}`)
    }
    assert.deepEqual(bad, [], 'a text size or line height in px ignores the browser and Dynamic Type: write it in rem')
})

test('the checker catches a px size, a px shorthand and a px line height, and lets a rem value with a px floor through', () => {
    const all = textSizes('a { font-size: 12px; } b { font: 500 14px/20px x; } c { line-height: 16px; } d { font-size: 0.875rem; } e { font-size: max(12px, min(0.75rem, 15px)); } f { font: inherit; }')
    assert.deepEqual(all.filter((s) => literalPx(s.prop, s.value)).map((s) => s.prop), ['font-size', 'font', 'line-height'])
})

test('the root keeps the browser size and an iPhone takes Dynamic Type', () => {
    const css = readFileSync(new URL('../styles.css', import.meta.url), 'utf8')
    assert.match(css, /html\s*\{\s*font-size:\s*100%\s*;?\s*\}/)
    assert.match(css, /@supports \(-webkit-touch-callout: none\)\s*\{\s*html\s*\{\s*font:\s*-apple-system-body/)
})

test('the bottom bar label never drops under 12 px', () => {
    const css = readFileSync(new URL('../styles.css', import.meta.url), 'utf8')
    assert.match(css, /\.nav-label\s*\{[^}]*font-size:\s*max\(12px,/)
})
