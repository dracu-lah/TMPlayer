// The screen sweep (the 2026-11-25 audit): every main route walked at a phone (390x844), a tablet (820x1180), a
// desktop (1280x800) and the web TV layout (1920x1080), in Chromium and WebKit, asserting the faults a person
// kept finding by eye: the page scrolling sideways, text hard clipped by its own box, a control or tab truncated
// with an ellipsis, a label not centred in its pill, and something to press hidden under the fixed bar once the
// page is scrolled to its end. With AUDIT_SHOTS=<dir> each route is also saved as <dir>/<engine>/<form>/<route>.png.
import { mkdirSync } from 'node:fs'
import { expect, test, type Page } from '@playwright/test'
import { fakeCase } from './helpers'

interface Form {
    name: string
    width: number
    height: number
    touch: boolean
    layout: 'desktop' | 'tv' | null
}

const FORMS: Form[] = [
    { name: 'phone-390', width: 390, height: 844, touch: true, layout: null },
    { name: 'tablet-820', width: 820, height: 1180, touch: true, layout: null },
    { name: 'desktop-1280', width: 1280, height: 800, touch: false, layout: 'desktop' },
    { name: 'tv-1920', width: 1920, height: 1080, touch: false, layout: 'tv' },
]

/** The routes the app has with data behind them in the fake Telegram; `flags` choose its case. */
const ROUTES: { name: string; path: string; flags?: string[]; act?: (page: Page) => Promise<void> }[] = [
    { name: 'home', path: '/' },
    { name: 'chats', path: '/chats' },
    { name: 'chat', path: '/chat/-1001' },
    { name: 'chat-info', path: '/chat/-1001/info' },
    { name: 'search', path: '/search' },
    { name: 'recent', path: '/recent' },
    { name: 'added-chats', path: '/added-chats' },
    { name: 'video', path: '/video/-1001/1' },
    { name: 'settings', path: '/settings' },
    { name: 'settings-account', path: '/settings/account' },
    { name: 'settings-playback', path: '/settings/playback' },
    { name: 'settings-subtitles', path: '/settings/subtitles' },
    { name: 'settings-storage', path: '/settings/storage' },
    { name: 'settings-appearance', path: '/settings/appearance' },
    { name: 'settings-home', path: '/settings/home' },
    { name: 'settings-help', path: '/settings/help' },
    { name: 'settings-about', path: '/settings/about' },
    { name: 'downloads', path: '/downloads' },
    { name: 'favourites', path: '/favourites' },
    { name: 'history', path: '/history' },
    { name: 'history-saved', path: '/history/saved' },
    { name: 'lists', path: '/lists' },
    { name: 'notfound', path: '/nowhere/at/all' },
    {
        name: 'account-menu',
        path: '/',
        act: async (page) => {
            // The TV's bar keeps the account in its own place with no menu of this kind.
            const you = page.locator('[data-you]')
            if (await you.count()) await you.first().click({ timeout: 5_000 })
        },
    },
    { name: 'chat-sort', path: '/chat/-1001', act: (page) => page.getByRole('button', { name: 'Sort' }).first().click({ timeout: 5_000 }) },
    { name: 'search-typed', path: '/search', act: (page) => page.getByRole('searchbox').fill('clip', { timeout: 5_000 }) },
    { name: 'signin', path: '/signin', flags: ['signedout'] },
    { name: 'home-longname', path: '/', flags: ['longname'] },
    { name: 'settings-longname', path: '/settings', flags: ['longname'] },
]

/** Seeds what the app keeps (no size floor, two starred chats) and the layout, before the app starts. */
async function seed(page: Page, layout: Form['layout']) {
    await page.goto('/__e2e/sizes')
    await page.evaluate(
        (layout) =>
            new Promise<void>((resolve, reject) => {
                if (layout) localStorage.setItem('tmplayer-layout', layout)
                const open = indexedDB.open('tmplayer-app')
                open.onupgradeneeded = () => open.result.createObjectStore('kv')
                open.onerror = () => reject(open.error)
                open.onsuccess = () => {
                    const tx = open.result.transaction('kv', 'readwrite')
                    const kv = tx.objectStore('kv')
                    kv.put({ sizeMin: 0 }, 'settings')
                    kv.put({ chatId: -1001, time: 1 }, 'star:-1001')
                    kv.put({ chatId: -1002, time: 2 }, 'star:-1002')
                    tx.oncomplete = () => (open.result.close(), resolve())
                }
            }),
        layout,
    )
}

/** What is wrong with the page as drawn now, as short lines; empty when nothing is. */
async function faults(page: Page, atEnd: boolean): Promise<string[]> {
    return page.evaluate((atEnd) => {
        const out: string[] = []
        const label = (el: Element) => `${el.tagName.toLowerCase()}${el.className && typeof el.className === 'string' ? '.' + el.className.trim().split(/\s+/)[0] : ''}[${(el.textContent ?? '').trim().replace(/\s+/g, ' ').slice(0, 28)}]`
        /** Drawn at all (decorative icons are `aria-hidden` and still drawn). */
        const painted = (el: Element) => {
            const r = el.getBoundingClientRect()
            if (r.width < 1 || r.height < 1) return false
            const st = getComputedStyle(el)
            return st.visibility !== 'hidden' && st.display !== 'none' && Number(st.opacity) !== 0
        }
        const shown = (el: Element) => painted(el) && !el.closest('[inert], [aria-hidden=true], [hidden], dialog:not([open])')
        const hasOwnText = (el: Element) => [...el.childNodes].some((n) => n.nodeType === 3 && (n.textContent ?? '').trim().length > 0)

        // 1. The page scrolls sideways.
        const doc = document.documentElement
        if (doc.scrollWidth > innerWidth + 1) out.push(`SIDEWAYS page ${doc.scrollWidth} > ${innerWidth}`)

        const all = [...document.querySelectorAll<HTMLElement>('body *')].filter(shown)

        // 2. Text clipped hard by its own box (no ellipsis, no scrolling), sideways or down.
        for (const el of all) {
            // A 1 px box is the screen-reader-only trick (skip link, visually hidden headings), clipped on purpose.
            if (!hasOwnText(el) || el.clientWidth <= 2 || el.clientHeight <= 2) continue
            const st = getComputedStyle(el)
            const clipX = /(hidden|clip)/.test(st.overflowX) && st.textOverflow !== 'ellipsis' && el.scrollWidth > el.clientWidth + 1
            const clamp = st.getPropertyValue('-webkit-line-clamp') !== 'none' && st.getPropertyValue('-webkit-line-clamp') !== ''
            const clipY = /(hidden|clip)/.test(st.overflowY) && !clamp && st.textOverflow !== 'ellipsis' && el.scrollHeight > el.clientHeight + 1
            if (clipX && el.clientWidth > 0) out.push(`CLIPPED-X ${label(el)} ${el.scrollWidth}>${el.clientWidth}`)
            if (clipY && el.clientHeight > 0) out.push(`CLIPPED-Y ${label(el)} ${el.scrollHeight}>${el.clientHeight}`)
        }

        // 3. A control, tab, chip or nav word cut short with an ellipsis: its name must be readable.
        const named = 'button, [role=tab], [role=menuitem], [role=radio], nav a, nav button, .chip, .pill, .badge, label'
        for (const el of all) {
            if (!el.matches(named)) continue
            for (const part of [el, ...el.querySelectorAll<HTMLElement>('*')]) {
                const st = getComputedStyle(part)
                if (st.textOverflow === 'ellipsis' && part.scrollWidth > part.clientWidth + 1 && hasOwnText(part)) out.push(`ELLIPSIS ${label(part)} ${part.scrollWidth}>${part.clientWidth}`)
            }
        }

        // 4. A label not centred in its pill, within 2 px (a single line, no stacked icon).
        for (const el of all) {
            if (!el.matches('button, [role=tab], .chip, .pill, .badge, a.btn, a.button')) continue
            const st = getComputedStyle(el)
            if (st.display === 'inline') continue
            const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT)
            let top = Infinity
            let bottom = -Infinity
            let left = Infinity
            let right = -Infinity
            for (let n = walker.nextNode(); n; n = walker.nextNode()) {
                if (!(n.textContent ?? '').trim()) continue
                const range = document.createRange()
                range.selectNodeContents(n)
                for (const r of range.getClientRects()) {
                    if (r.width < 1 || r.height < 1) continue
                    top = Math.min(top, r.top)
                    bottom = Math.max(bottom, r.bottom)
                    left = Math.min(left, r.left)
                    right = Math.max(right, r.right)
                }
            }
            if (!isFinite(top)) continue
            const line = parseFloat(st.lineHeight) || parseFloat(st.fontSize) * 1.3
            if (bottom - top > line * 1.6) continue
            const box = el.getBoundingClientRect()
            // Another element beside or above the words (a stacked icon, a count) makes the words' own place the design's.
            const others = [...el.querySelectorAll('svg, img, canvas, .icon, [class*=icon]')].filter(painted).map((c) => c.getBoundingClientRect())
            if (others.some((r) => Math.abs((r.top + r.bottom) / 2 - (top + bottom) / 2) > 6)) continue
            const dy = Math.abs((top + bottom) / 2 - (box.top + box.bottom) / 2)
            if (dy > 2 && box.height < 90) out.push(`UNCENTRED-Y ${label(el)} off by ${dy.toFixed(1)}px`)
            const centredX = st.textAlign === 'center' || /center/.test(st.justifyContent)
            if (centredX && others.length === 0) {
                const dx = Math.abs((left + right) / 2 - (box.left + box.right) / 2)
                if (dx > 2 && box.width < 300) out.push(`UNCENTRED-X ${label(el)} off by ${dx.toFixed(1)}px`)
            }
        }

        // 5. Something to press hidden under a fixed bar (at the end of the page) or under any fixed bar at the top.
        const bars = all.filter((el) => {
            const st = getComputedStyle(el)
            if (st.position !== 'fixed' && st.position !== 'sticky') return false
            const r = el.getBoundingClientRect()
            if (r.width < innerWidth * 0.2 || r.height < 24 || st.pointerEvents === 'none') return false
            return r.bottom > innerHeight - 140 || r.top < 140
        })
        const clickable = all.filter((el) => el.matches('a[href], button, input, select, textarea, [role=button], [role=tab], [role=menuitem], summary, .card'))
        for (const el of clickable) {
            const r = el.getBoundingClientRect()
            if (r.bottom < 0 || r.top > innerHeight || r.right < 0 || r.left > innerWidth) continue
            const inside = bars.some((b) => b.contains(el) || el.contains(b))
            if (inside) continue
            const cx = Math.min(Math.max(r.left + r.width / 2, 1), innerWidth - 1)
            const cy = Math.min(Math.max(r.top + r.height / 2, 1), innerHeight - 1)
            const hit = document.elementFromPoint(cx, cy)
            if (!hit || el.contains(hit) || hit.contains(el)) continue
            const cover = bars.find((b) => b.contains(hit))
            // At the end of the page nothing may sit under a bar; at the top, only a bar at the top counts (the foot scrolls).
            const barRect = cover?.getBoundingClientRect()
            const barAtTop = barRect ? barRect.top < innerHeight / 2 : false
            // A bar at the foot hides nothing once the page is at its end; a bar at the top hides nothing once the page is
            // scrolled (that is what it is for), but at the top of the page nothing may start under it.
            if (cover && (atEnd ? !barAtTop : barAtTop)) out.push(`UNDER-BAR ${label(el)} under ${label(cover)}`)
        }
        // 6. A chosen place's glyph must be drawn: History's went blank (no filled form to hand over to) and Chats drew two
        // dashes (the lines filled, the bubble cut away): the filled form covers most of the outline, or the outline stays.
        for (const svg of document.querySelectorAll<SVGSVGElement>('svg.icon.chosen')) {
            const outline = svg.querySelector<SVGGElement>('.icon-outline')
            const fill = svg.querySelector<SVGGElement>('.icon-fill')
            if (!outline || !painted(svg)) continue
            const o = outline.getBBox()
            const f = fill?.getBBox()
            const outlineShown = Number(getComputedStyle(outline).opacity) > 0.5
            const fillShown = !!fill && Number(getComputedStyle(fill).opacity) > 0.5 && !!f && f.width * f.height > o.width * o.height * 0.5
            if (!outlineShown && !fillShown) out.push(`ICON-BLANK ${label(svg.closest('a, button') ?? svg)}`)
        }
        return [...new Set(out)]
    }, atEnd)
}

for (const form of FORMS)
    test.describe(`${form.name}`, () => {
        test(`every route is whole at ${form.width}x${form.height}`, async ({ browser, browserName }) => {
            test.setTimeout(600_000)
            const shots = process.env.AUDIT_SHOTS
            const problems: string[] = []
            const byFlags = new Map<string, typeof ROUTES>()
            for (const route of ROUTES) byFlags.set((route.flags ?? []).join(','), [...(byFlags.get((route.flags ?? []).join(',')) ?? []), route])
            for (const [flags, routes] of byFlags) {
                const context = await browser.newContext({
                    viewport: { width: form.width, height: form.height },
                    hasTouch: form.touch,
                    isMobile: form.touch && browserName === 'chromium',
                    serviceWorkers: 'block',
                })
                await fakeCase(context, ...(flags ? flags.split(',') : []))
                const page = await context.newPage()
                await seed(page, form.layout)
                for (const route of routes) {
                    await page.goto(route.path)
                    await page.waitForTimeout(1800)
                    if (route.act) {
                        // A form without that control (the TV has no account menu) is walked as the plain route.
                        await route.act(page).catch(() => undefined)
                        await page.waitForTimeout(700)
                    }
                    if (shots) {
                        const dir = `${shots}/${browserName}/${form.name}`
                        mkdirSync(dir, { recursive: true })
                        await page.screenshot({ path: `${dir}/${route.name}.png` })
                    }
                    for (const f of await faults(page, false)) problems.push(`${route.name} (top): ${f}`)
                    const tall = await page.evaluate(() => document.documentElement.scrollHeight > innerHeight + 40)
                    if (tall) {
                        await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
                        await page.waitForTimeout(400)
                        if (shots) await page.screenshot({ path: `${shots}/${browserName}/${form.name}/${route.name}-end.png` })
                        for (const f of await faults(page, true)) if (!problems.includes(`${route.name} (top): ${f}`)) problems.push(`${route.name} (end): ${f}`)
                    }
                }
                await context.close()
            }
            expect(problems, problems.join('\n')).toEqual([])
        })
    })
