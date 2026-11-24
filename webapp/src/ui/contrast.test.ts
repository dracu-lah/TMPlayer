// WP-60: the colour tokens clear WCAG: a field's edge 3:1 (1.4.11) against the surface it sits on,
// text 4.5:1 (1.4.3), and what High contrast brings forward clears the stricter marks. The tokens are
// read from styles.css itself, in each theme's block, so a change to a colour is checked where it is made.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'

const css = readFileSync(new URL('../styles.css', import.meta.url), 'utf8')

/** The declarations of the first rule whose selector line starts with `selector` (comments dropped). */
function block(selector: string, from = 0): Record<string, string> {
    const at = css.indexOf(selector, from)
    assert.ok(at >= 0, selector)
    const open = css.indexOf('{', at)
    const close = css.indexOf('}', open)
    const out: Record<string, string> = {}
    for (const m of css.slice(open + 1, close).replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) out[m[1]!] = m[2]!.trim()
    return out
}

const light = block('  :root:not([data-theme="dark"]):not([data-theme="black"])', css.indexOf('@media (prefers-color-scheme: light)'))
const themes = {
    dark: block(':root {'),
    light: block(':root[data-theme="light"] {'),
    black: { ...block(':root {'), ...block(':root[data-theme="black"] {') },
}

const channel = (v: number) => ((v /= 255) <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4)
function luminance(hex: string): number {
    const h = hex.replace('#', '')
    const full = h.length === 3 ? [...h].map((c) => c + c).join('') : h
    const [r, g, b] = [0, 2, 4].map((i) => channel(parseInt(full.slice(i, i + 2), 16)))
    return 0.2126 * r! + 0.7152 * g! + 0.0722 * b!
}
export const ratio = (a: string, b: string) => {
    const [x, y] = [luminance(a), luminance(b)].sort((p, q) => q - p)
    return (x! + 0.05) / (y! + 0.05)
}

test('the system light block and the chosen Light theme hold the same field and contrast tokens', () => {
    for (const key of ['--field-line', '--muted-hc', '--line-hc', '--field-line-hc']) assert.equal(light[key], themes.light[key], key)
})

for (const [name, t] of Object.entries(themes)) {
    test(`${name}: a field's edge is 3:1 against every surface it can sit on`, () => {
        for (const ground of ['--bg', '--surface', '--surface-high', '--dialog']) assert.ok(ratio(t['--field-line']!, t[ground]!) >= 3, `${name} --field-line on ${ground}: ${ratio(t['--field-line']!, t[ground]!).toFixed(2)}`)
    })

    test(`${name}: text and second lines are 4.5:1`, () => {
        for (const ground of ['--bg', '--surface', '--surface-high', '--dialog']) {
            assert.ok(ratio(t['--fg']!, t[ground]!) >= 4.5, `fg on ${ground}`)
            assert.ok(ratio(t['--muted']!, t[ground]!) >= 4.5, `muted on ${ground}: ${ratio(t['--muted']!, t[ground]!).toFixed(2)}`)
        }
    })

    test(`${name}: High contrast brings the second lines to 7:1, the hairlines and field edges to 3:1 and 4.5:1`, () => {
        for (const ground of ['--bg', '--surface', '--surface-high', '--dialog']) {
            assert.ok(ratio(t['--muted-hc']!, t[ground]!) >= 7, `muted-hc on ${ground}: ${ratio(t['--muted-hc']!, t[ground]!).toFixed(2)}`)
            assert.ok(ratio(t['--line-hc']!, t[ground]!) >= 3, `line-hc on ${ground}: ${ratio(t['--line-hc']!, t[ground]!).toFixed(2)}`)
            assert.ok(ratio(t['--field-line-hc']!, t[ground]!) >= 4.5, `field-line-hc on ${ground}: ${ratio(t['--field-line-hc']!, t[ground]!).toFixed(2)}`)
        }
    })
}

test('Automatic follows prefers-contrast, On is a data attribute, and Off beats the system', () => {
    assert.match(css, /@media \(prefers-contrast: more\)\s*\{\s*:root:not\(\[data-contrast="off"\]\)/)
    assert.match(css, /:root\[data-contrast="high"\]\s*\{/)
})

test('forced colours get their own rules for the highlight, the chosen choice and the progress', () => {
    const at = css.indexOf('@media (forced-colors: active)')
    assert.ok(at > 0)
    const rules = css.slice(at)
    for (const part of ['.nav-highlight', '.segmented label.on', '.card-progress', '[aria-checked=true] > .switch']) assert.ok(rules.includes(part), part)
})

test('Reduce motion has a switch in the CSS for every system rule: a rule under the media query has its On twin', () => {
    const media = [...css.matchAll(/@media \(prefers-reduced-motion: reduce\)/g)].length
    const twins = [...css.matchAll(/The same, when Reduce motion is On in Settings \(WP-60\)/g)].length
    assert.ok(media > 5)
    assert.equal(twins, media)
})
