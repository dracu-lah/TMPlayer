// WP-62: the small iPhone and iPad fixes that a machine can hold. Safari-only properties (a Chromium
// page drops -webkit-touch-callout from its rules) and the service worker are read from their files.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { saveData } from './saveData.ts'
import { isTablet } from './tablet.ts'

const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8')

test('links in lists with their own menu have no iOS link preview, and controls have touch-action: manipulation', () => {
    const css = read('../styles.css')
    assert.match(css, /a\.card, \.card a, [^{]*\{ -webkit-touch-callout: none; \}/)
    assert.match(css, /a, button, summary, \[role=button\][^{]*\{ touch-action: manipulation; \}/)
})

test('Save-Data and a 2g line are held back, a fast line and a browser with no answer are not', () => {
    assert.equal(saveData({ saveData: true }), true)
    assert.equal(saveData({ effectiveType: '2g' }), true)
    assert.equal(saveData({ effectiveType: 'slow-2g' }), true)
    assert.equal(saveData({ effectiveType: '3g' }), false)
    assert.equal(saveData({ saveData: false, effectiveType: '4g' }), false)
    assert.equal(saveData(undefined), false)
    assert.equal(saveData({}), false)
})

test('the service worker keeps only the first page ahead on Save-Data, by the same words', () => {
    const sw = read('../../public/sw.js')
    assert.match(sw, /conn\.saveData === true \|\| conn\.effectiveType === 'slow-2g' \|\| conn\.effectiveType === '2g'/)
    assert.match(sw, /lean \? SHELL\.filter\(\(path\) => path === '\/'\) : SHELL/)
})

test('a tablet is a touch screen with a short side of 600 or more, whatever the window is', () => {
    assert.equal(isTablet(true, 1024, 1366), true)
    assert.equal(isTablet(true, 744, 1133), true)
    assert.equal(isTablet(true, 390, 844), false)
    assert.equal(isTablet(false, 1024, 1366), false)
    assert.equal(isTablet(true, 0, 0), false)
})

test('index.html decides the layout before paint with the same tablet rule as form.ts', () => {
    const html = read('../../index.html')
    assert.match(html, /\(m\('\(pointer: coarse\)'\)&&Math\.min\(screen\.width,screen\.height\)>=600&&Math\.min\(innerWidth,innerHeight\)>=500\)/)
    const form = read('../form.ts')
    assert.match(read('./tablet.ts'), /Math\.min\(screenW, screenH\) >= 600/)
    assert.match(form, /isTablet\(/)
    assert.match(form, /TABLET_WINDOW = 500/)
})

test('the status bar follows the page: the default style, and the two theme colours Settings keeps in step', () => {
    const html = read('../../index.html')
    assert.match(html, /apple-mobile-web-app-status-bar-style" content="default"/)
    assert.match(html, /name="theme-color" content="#0c0e10" media="\(prefers-color-scheme: dark\)"/)
    assert.match(html, /name="theme-color" content="#f6fafd" media="\(prefers-color-scheme: light\)"/)
})

test('the first hero picture asks to be fetched first, the list pictures say their size', () => {
    assert.match(read('../screens/Hero.tsx'), /fetchpriority: 'high'/)
    const list = read('../screens/VideoList.tsx')
    assert.match(list, /class="card-thumb"[^>]*width="320" height="180"/)
    assert.match(list, /class="card-poster"[^>]*width="300" height="450"/)
})
