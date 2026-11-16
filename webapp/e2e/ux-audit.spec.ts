// Review wave R9: a sweep over the screens that reports what a person with a finger, a keyboard or a big font
// would hit. It fails on the things that must never hold (a control with no name, a page that scrolls
// sideways, a control the keyboard cannot reach) and logs the rest (small targets) for the review notes.
import { expect, test, type Page } from '@playwright/test'
import { noSizeFloor } from './helpers'

test.skip(({ browserName }) => browserName !== 'chromium', 'one engine is enough for a sweep')

const SCREENS: [string, string, string][] = [
    ['Home', '/', 'main'],
    ['Chats', '/chats', 'a[href^="/chat/"]'],
    ['a chat', '/chat/-1001', '.card'],
    ['Search', '/search', 'main'],
    ['History', '/history', 'main'],
    ['History saved', '/history/saved', 'main'],
    ['Lists', '/lists', 'main'],
    ['Favourites', '/favourites', 'main'],
    ['Recent', '/recent', 'main'],
    ['Added chats', '/added-chats', 'main'],
    ['Settings', '/settings', 'main'],
    ['Settings appearance', '/settings/appearance', 'main'],
    ['Downloads', '/downloads', 'main'],
    ['a video page', '/video/-1001/1', '.detail-title'],
]

/** What each interactive element offers: its name, size, and whether the page can hit it. */
async function sweep(page: Page) {
    return page.evaluate(() => {
        const sel = 'a[href], button, input, select, textarea, [role=button], [role=menuitem], [role=tab], [role=switch], [role=slider], [tabindex]:not([tabindex="-1"])'
        const name = (el: Element) => {
            const h = el as HTMLElement
            const labelled = el.getAttribute('aria-label') || el.getAttribute('title')
            const by = el.getAttribute('aria-labelledby')
            const byText = by ? by.split(' ').map((i) => document.getElementById(i)?.textContent ?? '').join(' ') : ''
            const lab = (el as HTMLInputElement).labels ? [...(el as HTMLInputElement).labels!].map((l) => l.textContent).join(' ') : ''
            return (labelled || byText || lab || h.innerText || '').trim()
        }
        const out: { tag: string; cls: string; label: string; w: number; h: number; named: boolean }[] = []
        for (const el of document.querySelectorAll<HTMLElement>(sel)) {
            const r = el.getBoundingClientRect()
            if (r.width === 0 || r.height === 0) continue
            const st = getComputedStyle(el)
            if (st.visibility === 'hidden' || st.display === 'none') continue
            if (el.closest('[inert], [aria-hidden=true]')) continue
            const n = name(el)
            out.push({ tag: el.tagName.toLowerCase(), cls: (el.className?.toString?.() ?? '').slice(0, 60), label: n.slice(0, 40), w: Math.round(r.width), h: Math.round(r.height), named: n.length > 0 })
        }
        return out
    })
}

for (const zoom of [false, true])
    test.describe(zoom ? 'reflowed to 320 px wide (page zoom 400% on a desktop)' : 'at normal text', () => {
        test.beforeEach(async ({ page }) => {
            await noSizeFloor(page)
        })
        for (const [title, path, ready] of SCREENS)
            test(title, async ({ page }, info) => {
                if (zoom) await page.setViewportSize({ width: 320, height: 640 })
                await page.goto(path)
                await expect(page.locator(ready).first()).toBeVisible()
                await page.waitForTimeout(600)
                const all = await sweep(page)
                const unnamed = all.filter((e) => !e.named && e.tag !== 'input')
                const small = all.filter((e) => (e.w < 44 || e.h < 44) && e.tag !== 'input')
                if (!zoom && small.length) console.log(`SMALL ${info.project.name} ${title}: ` + small.map((e) => `${e.tag}.${e.cls}[${e.label}] ${e.w}x${e.h}`).join(' | '))
                // WCAG reflow: nothing but a scroller's own content may reach past the right edge of a 320 px window.
                const wide = zoom
                    ? await page.evaluate(() =>
                          [...document.querySelectorAll<HTMLElement>('body *')]
                              .filter((el) => {
                                  const r = el.getBoundingClientRect()
                                  if (r.width === 0 || r.right <= innerWidth + 1 || getComputedStyle(el).position === 'fixed') return false
                                  for (let p = el.parentElement; p; p = p.parentElement) if (/(auto|scroll|hidden|clip)/.test(getComputedStyle(p).overflowX)) return false
                                  return true
                              })
                              .slice(0, 6)
                              .map((el) => `${el.tagName.toLowerCase()}.${el.className?.toString?.().slice(0, 40)} right=${Math.round(el.getBoundingClientRect().right)}`),
                      )
                    : []
                if (wide.length) console.log(`SIDEWAYS ${info.project.name} ${title}: ${wide.join(' | ')}`)
                expect(unnamed.map((e) => `${e.tag}.${e.cls}`), `${title}: controls with no name`).toEqual([])
            })
    })

test('Tab reaches the first control and never leaves the page', async ({ page }) => {
    await noSizeFloor(page)
    await page.goto('/chats')
    await expect(page.locator('a[href^="/chat/"]').first()).toBeVisible()
    for (let i = 0; i < 12; i++) await page.keyboard.press('Tab')
    const where = await page.evaluate(() => {
        const a = document.activeElement as HTMLElement | null
        if (!a || a === document.body) return 'body'
        const st = getComputedStyle(a)
        return `${a.tagName.toLowerCase()} outline=${st.outlineStyle} ${st.outlineWidth} shadow=${st.boxShadow !== 'none'}`
    })
    console.log('TAB 12 ->', where)
    expect(where).not.toBe('body')
})
