// WP-16: the tv-ux-review checklist as a machine suite, on the web app's TV layout (?tv=1) at
// 1920 x 1080: every focusable is named, sits inside the 5 percent safe area and draws its focus
// ring inside its own box; every text is 14 or more in the unit its sheet uses (--dp on the TV
// pages, --u in the TV player). Known failures come from the Android suite's allow lists
// (app/src/test/resources/tvux-allow/WP-xx.txt, screens named "web:..."), so one file per package
// covers both; a finding no line covers fails the screen.
import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { expect, test, type Page } from '@playwright/test'
import { fakeCase } from './helpers'

type Finding = { rule: string; node: string; detail: string }
type Allow = { screen: string; rule: string; node: string; file: string }

const ALLOW_DIR = new URL('../../app/src/test/resources/tvux-allow/', import.meta.url)

const glob = (pattern: string, text: string) =>
    pattern === '*' || new RegExp(`^${pattern.split('*').map((p) => p.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('.*')}$`, 's').test(text)

/** Every allow line, read the way TvUxAllow reads them: `screen | rule | node | reason`, # comments. */
function allowLines(): Allow[] {
    if (!existsSync(ALLOW_DIR)) return []
    return readdirSync(ALLOW_DIR)
        .filter((f) => f.endsWith('.txt'))
        .flatMap((file) =>
            readFileSync(new URL(file, ALLOW_DIR), 'utf8')
                .split('\n')
                .map((raw) => raw.split('#')[0].trim())
                .filter(Boolean)
                .map((line) => line.split('|').map((p) => p.trim()))
                .filter((p) => p.length >= 4 && p.slice(0, 3).every(Boolean))
                .map(([screen, rule, node]) => ({ screen, rule, node, file })),
        )
}

/** The rules over the page as it stands, the remote on each focusable in turn for the ring. */
async function audit(page: Page): Promise<Finding[]> {
    return page.evaluate(async () => {
        const out: { rule: string; node: string; detail: string }[] = []
        // A page under load is still moving (a row rising, a focus growing) when the rules read it: wait for what ends, then measure.
        for (let i = 0; i < 3; i++) {
            const ending = document.getAnimations().filter((a) => a.effect?.getComputedTiming().iterations !== Infinity)
            if (!ending.length) break
            await Promise.all(ending.map((a) => a.finished.catch(() => null)))
            await new Promise((resolve) => requestAnimationFrame(() => resolve(null)))
        }
        const vw = innerWidth
        const vh = innerHeight
        const dp = Math.min(vw / 960, vh / 540)
        const shown = (el: Element) => {
            const r = el.getBoundingClientRect()
            const s = getComputedStyle(el)
            return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none' && r.bottom > 0 && r.right > 0 && r.top < vh && r.left < vw
        }
        const words = (el: Element) => {
            const by = el.getAttribute('aria-labelledby')
            const labelled = by ? by.split(/\s+/).map((id) => document.getElementById(id)?.textContent ?? '').join(' ') : ''
            const img = el.querySelector('img[alt]')?.getAttribute('alt') ?? ''
            return (el.getAttribute('aria-label') || labelled || (el as HTMLElement).innerText || el.getAttribute('title') || img || (el as HTMLInputElement).placeholder || '').trim()
        }
        const name = (el: Element) =>
            (words(el) || `${el.tagName.toLowerCase()}.${(el.getAttribute('class') ?? '').split(' ')[0]}`).replace(/\s+/g, ' ').replace(/\|/g, '/').slice(0, 60).trim()
        /** Cut by a scrolling parent or the window: a peek of the next item, which the place rules skip. */
        const cut = (el: Element) => {
            const r = el.getBoundingClientRect()
            if (r.left < 0 || r.top < 0 || r.right > vw || r.bottom > vh) return true
            for (let p = el.parentElement; p; p = p.parentElement) {
                const s = getComputedStyle(p)
                if (/(auto|scroll|hidden|clip)/.test(s.overflowX + s.overflowY)) {
                    const c = p.getBoundingClientRect()
                    if (r.left < c.left - 1 || r.top < c.top - 1 || r.right > c.right + 1 || r.bottom > c.bottom + 1) return true
                }
            }
            return false
        }
        const focusables = [...document.querySelectorAll<HTMLElement>('a[href], button, input, select, textarea, [tabindex]:not([tabindex="-1"])')]
            .filter((el) => shown(el) && !(el as HTMLButtonElement).disabled && !el.closest('[inert], [aria-hidden="true"]'))

        for (const el of focusables) {
            const n = name(el)
            if (!words(el)) out.push({ rule: 'labelled', node: n, detail: 'no accessible name' })
            if (cut(el)) continue
            const r = el.getBoundingClientRect()
            if (r.left < vw * 0.05 - 1 || r.top < vh * 0.05 - 1 || r.right > vw * 0.95 + 1 || r.bottom > vh * 0.95 + 1) {
                out.push({ rule: 'safeArea', node: n, detail: `at ${Math.round(r.left)},${Math.round(r.top)} ${Math.round(r.width)}x${Math.round(r.height)} px, outside the 5 percent safe area` })
            }
        }

        // The ring: on each control in turn, once its transitions have finished.
        const before = document.activeElement as HTMLElement | null
        for (const el of focusables.slice(0, 40)) {
            el.focus({ preventScroll: true })
            el.getAnimations({ subtree: false }).forEach((a) => a.finish())
            await new Promise((resolve) => requestAnimationFrame(() => resolve(null)))
            const s = getComputedStyle(el)
            const width = s.outlineStyle !== 'none' ? parseFloat(s.outlineWidth) || 0 : 0
            const outside = width > 0 ? width + (parseFloat(s.outlineOffset) || 0) : 0
            if (outside > 0.5) out.push({ rule: 'focusRingInside', node: name(el), detail: `outline ${outside.toFixed(1)} px outside the element box` })
            // Each outer shadow layer reaches past the box by its larger offset plus its blur and
            // spread, so a glow (blur alone) or a shifted shadow counts as much as a spread ring.
            const shadow = s.boxShadow
            if (shadow && shadow !== 'none') {
                const layers = shadow.split(/,(?![^(]*\))/).map((l) => l.trim())
                for (const layer of layers) {
                    if (layer.includes('inset') || /rgba\([^)]*,\s*0\)/.test(layer) || layer.includes('transparent')) continue
                    const [x = 0, y = 0, blur = 0, spread = 0] = (layer.replace(/(rgba?|hsla?|color)\([^)]*\)/g, '').match(/-?[\d.]+px/g) ?? []).map((v) => parseFloat(v))
                    const reach = Math.max(Math.abs(x), Math.abs(y)) + Math.max(0, blur) + spread
                    if (reach > 0.5) {
                        out.push({ rule: 'focusRingInside', node: name(el), detail: `box-shadow ${layer} reaches ${reach.toFixed(1)} px outside the box` })
                        break
                    }
                }
            }
        }
        before?.focus({ preventScroll: true })

        // The text floor, in the unit of the sheet the text sits in: --u inside the TV player, --dp elsewhere.
        const unitOf = (el: Element) => {
            const player = el.closest('.player[data-variant="tv"]')
            if (!player) return { unit: dp, css: '--dp' }
            const probe = document.createElement('div')
            probe.style.cssText = 'position:absolute;visibility:hidden;width:calc(var(--u) * 100)'
            player.appendChild(probe)
            const u = probe.getBoundingClientRect().width / 100
            probe.remove()
            return { unit: u > 0 ? u : dp, css: '--u' }
        }
        const seen = new Set<string>()
        const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT)
        for (let t = walker.nextNode(); t; t = walker.nextNode()) {
            const text = t.textContent?.trim()
            const el = t.parentElement
            if (!text || !el || !shown(el) || el.closest('[aria-hidden="true"], script, style')) continue
            const { unit, css } = unitOf(el)
            const size = parseFloat(getComputedStyle(el).fontSize) / unit
            const n = text.replace(/\s+/g, ' ').replace(/\|/g, '/').slice(0, 60).trim()
            if (size + 0.05 < 14 && !seen.has(n)) {
                seen.add(n)
                out.push({ rule: 'textFloor', node: n, detail: `${size.toFixed(1)} ${css}, want 14` })
            }
        }
        return out
    })
}

function check(screen: string, findings: Finding[], allow: Allow[]) {
    const open = findings.filter((f) => !allow.some((a) => glob(a.screen, screen) && glob(a.rule, f.rule) && glob(a.node, f.node)))
    const lines = open.map((f) => `${screen} | ${f.rule} | ${f.node} | TODO`)
    expect.soft(open, `${open.length} finding(s) no tvux-allow line covers on ${screen}:\n${open.map((f) => `  ${f.rule} on '${f.node}': ${f.detail}`).join('\n')}\nAs allow lines:\n${lines.join('\n')}`).toEqual([])
}

test.describe('TV checklist at 1920 x 1080', () => {
    test.skip(({ browserName }) => browserName !== 'chromium', 'the TV checklist runs in Chromium')
    test.use({ viewport: { width: 1920, height: 1080 } })
    test.beforeEach(async ({ context }) => {
        await fakeCase(context, 'mid')
    })

    const allow = allowLines()

    for (const [screen, path] of [
        ['web:home', '/?tv=1'],
        ['web:chats', '/chats?tv=1'],
        ['web:chat', '/chat/-1001?tv=1'],
        ['web:history', '/history?tv=1'],
        ['web:downloads', '/downloads?tv=1'],
        ['web:search', '/search?tv=1'],
        ['web:settings', '/settings?tv=1'],
    ] as const) {
        test(screen, async ({ page }) => {
            await page.goto(path)
            await expect(page.locator('html')).toHaveAttribute('data-form', 'tv')
            await page.waitForTimeout(800)
            check(screen, await audit(page), allow)
        })
    }

    test('web:video and web:player', async ({ page }) => {
        await page.goto('/?tv=1')
        await expect(page.locator('html')).toHaveAttribute('data-form', 'tv')
        const href = await page.locator('a[href^="/video/"]').first().getAttribute('href')
        expect(href).toBeTruthy()
        await page.goto(`${href}?tv=1`)
        await page.waitForTimeout(800)
        check('web:video', await audit(page), allow)
        await page.goto(`${href!.replace('/video/', '/watch/')}?tv=1`)
        await expect(page.locator('.player[data-variant="tv"]')).toHaveCount(1)
        await page.waitForTimeout(1500)
        // The row up, as the remote's first press raises it.
        await page.keyboard.press('ArrowUp')
        await page.waitForTimeout(400)
        // The row is up, so the rules read its controls rather than an empty picture.
        expect(await page.locator('.player button:visible').count()).toBeGreaterThan(0)
        check('web:player', await audit(page), allow)
    })
})
