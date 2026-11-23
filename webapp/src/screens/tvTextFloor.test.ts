// WP-04: no text on the TV's web pages under 14 of the app's dp, `node --test src/screens/tvTextFloor.test.ts`.
// tv.css sizes in --dp, player-tv.css in --u, and both are one Android TV dp at the window's size, so
// a size is read as its multiplier whichever of the two units it is written in. A size in any other
// form (px, rem, em, a bare number) would escape the floor, so it fails here too.
import assert from 'node:assert/strict'
import { readdirSync, readFileSync } from 'node:fs'
import { test } from 'node:test'

const FLOOR = 14

const SHEETS = [
    { file: '../tv/tv.css', unit: '--dp' },
    { file: '../player/player-tv.css', unit: '--u' },
]

const SIZE = /calc\(\s*var\(--(?:dp|u)(?:\s*,\s*[^)]*)?\)\s*\*\s*([0-9.]+)\s*\)/

/**
 * Every font-size declaration of a sheet, and every `font` shorthand that is not just a keyword
 * (`font: inherit` carries no size), with its multiplier of the app's dp (NaN when the size is not
 * written in --dp or --u, or the shorthand has none).
 */
function sizes(css: string): { at: number; value: string; dp: number }[] {
    const clean = css.replace(/\/\*[\s\S]*?\*\//g, (c) => c.replace(/[^\n]/g, ' '))
    const out: { at: number; value: string; dp: number }[] = []
    for (const m of clean.matchAll(/(?<![-\w])font-size\s*:\s*([^;}]+)/g)) {
        const value = m[1]!.trim()
        const calc = value.match(new RegExp(`^${SIZE.source}$`))
        out.push({ at: clean.slice(0, m.index).split('\n').length, value, dp: calc ? parseFloat(calc[1]!) : NaN })
    }
    for (const m of clean.matchAll(/(?<![-\w])font\s*:\s*([^;}]+)/g)) {
        const value = m[1]!.trim()
        if (/^(inherit|initial|unset|revert)$/.test(value)) continue
        const calc = value.match(SIZE)
        out.push({ at: clean.slice(0, m.index).split('\n').length, value: `font: ${value}`, dp: calc ? parseFloat(calc[1]!) : NaN })
    }
    return out
}

for (const { file, unit } of SHEETS) {
    const name = file.split('/').pop()!
    const css = readFileSync(new URL(file, import.meta.url), 'utf8')

    test(`${name}: every font size is a multiple of --dp or --u and at least ${FLOOR}`, () => {
        const all = sizes(css)
        assert.ok(all.length > 20, `${name} has font sizes to check`)
        const odd = all.filter((s) => Number.isNaN(s.dp)).map((s) => `${name}:${s.at} font-size: ${s.value}`)
        assert.deepEqual(odd, [], 'a size not written as calc(var(--dp or --u) * N) escapes the floor')
        const small = all.filter((s) => s.dp < FLOOR).map((s) => `${name}:${s.at} font-size: ${s.value} is ${s.dp}, want ${FLOOR}`)
        assert.deepEqual(small, [])
    })

    test(`${name}: the sheet names its unit at the top`, () => {
        const head = css.slice(0, css.indexOf('*/') + 2)
        assert.match(head, new RegExp(`Unit: ${unit}\\b`), `the opening comment says "Unit: ${unit}"`)
    })
}

/** Every .css file under src, with its path. */
function allSheets(dir = new URL('../', import.meta.url), prefix = ''): { path: string; css: string }[] {
    const out: { path: string; css: string }[] = []
    for (const e of readdirSync(dir, { withFileTypes: true })) {
        if (e.isDirectory()) out.push(...allSheets(new URL(`${e.name}/`, dir), `${prefix}${e.name}/`))
        else if (e.name.endsWith('.css')) out.push({ path: prefix + e.name, css: readFileSync(new URL(e.name, dir), 'utf8') })
    }
    return out
}

/** The font sizes written in rules whose selector is for the TV page or the TV player, in any sheet. */
function tvRuleSizes(css: string): { selector: string; value: string; dp: number }[] {
    const clean = css.replace(/\/\*[\s\S]*?\*\//g, '')
    const out: { selector: string; value: string; dp: number }[] = []
    for (const m of clean.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
        const selector = m[1]!.trim()
        if (!/data-form=["']?tv|data-variant=["']?tv/.test(selector)) continue
        for (const s of sizes(`a{${m[2]}}`)) out.push({ selector: selector.replace(/\s+/g, ' ').slice(0, 80), value: s.value, dp: s.dp })
    }
    return out
}

test('every other sheet holds its TV rules to the floor as well', () => {
    const bad: string[] = []
    for (const { path, css } of allSheets()) {
        if (path === 'tv/tv.css' || path === 'player/player-tv.css') continue
        for (const s of tvRuleSizes(css)) {
            if (!(s.dp >= FLOOR)) bad.push(`${path}: ${s.selector} { font-size: ${s.value} }`)
        }
    }
    assert.deepEqual(bad, [])
})

test('a TV rule that overrides a phone rule with a small size is caught, whichever way it is written', () => {
    const css = `
        .tag { font-size: 16px; }
        html[data-form=tv] .tag { font-size: calc(var(--dp) * 11); }
        .pc-note { font: 500 15px/20px sans-serif; }
        .pc[data-variant="tv"] .pc-note { font: 500 calc(var(--u) * 12)/1.2 sans-serif; }
        html[data-form=tv] .other { font: 12px sans-serif; }
        html[data-form=tv] .fine { font: inherit; font-size: calc(var(--dp) * 14); }
        @media (max-width: 600px) { html[data-form=tv] .wide { font-size: calc(var(--dp) * 13); } }
    `
    const small = tvRuleSizes(css).filter((s) => !(s.dp >= FLOOR)).map((s) => s.selector)
    assert.deepEqual(small, ['html[data-form=tv] .tag', '.pc[data-variant="tv"] .pc-note', 'html[data-form=tv] .other', 'html[data-form=tv] .wide'])
})

test('the checker reads both units and refuses a literal size', () => {
    assert.deepEqual(sizes('a { font-size: calc(var(--dp) * 12); }').map((s) => s.dp), [12])
    assert.deepEqual(sizes('a { font-size: calc(var(--u) * 13); }').map((s) => s.dp), [13])
    assert.deepEqual(sizes('a { font-size: calc(var(--dp, 1px) * 20); }').map((s) => s.dp), [20])
    assert.ok(Number.isNaN(sizes('a { font-size: 12px; }')[0]!.dp))
    assert.deepEqual(sizes('/* font-size: 9px */ a { line-height: 1; }'), [])
})
