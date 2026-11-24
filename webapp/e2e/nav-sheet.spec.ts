// UX web (the user's account sheet request): the bottom bar, the account sheet and Settings' foot.
//  - The bar's structure is unchanged: one pill with Home, Chats, Search, History and the account's
//    picture last. Only its look changed: every label has one font size and line height, the five items
//    are equally wide (within 1 px) with one icon box and one icon-to-label gap, roomy padding, 44 px
//    targets; at 390, 360 and 844 landscape, and with the browser's text at 130 percent.
//  - The account's picture is one element of the app's shell: it is the same DOM node across the
//    tab taps (the web has no swipe between tabs and no view transitions), on a phone and on the TV bar.
//  - On a phone width the account menu is a bottom sheet (a modal dialog): the head, the three library
//    tiles in one row, Settings, Add account and the compact version; no Sign out (Settings has it).
//    Back, Escape, a tap outside and a drag down on the grip close it, focus returns to the picture; it
//    fits without scrolling at 390 by 844 and at 360 by 640 with text at 130 percent. Wider windows keep
//    the anchored card with the same content.
//  - Settings shows the TMPlayer icon in its header and ends its main list with the compact version, in
//    landscape and in the desktop's two panes too.
// SHOTS=1 also writes pictures to design/account-sheet-web/ (index.html shows them).
import { mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { expect, test, type Page } from '@playwright/test'
import { fakeCase } from './helpers'
/** The `longname` case's name (e2e/fake/mtcute.ts), 60 characters. */
const LONG_NAME = 'Maximilian-Alexander Featherstonehaugh-Montgomery Fitzgerald'

const picture = (page: Page) => page.locator('[data-you]')
const pill = (page: Page) => page.locator('#main-nav .nav-pill')
const sheet = (page: Page) => page.locator('dialog.you-sheet[open]')
const names = (page: Page) => page.getByRole('menu').getByRole('menuitem').evaluateAll((els) => els.map((e) => (e.textContent ?? '').trim()))
/** The sheet has finished sliding in (240 ms), so its box is where it stays. */
const settled = (page: Page) => page.waitForFunction(() => document.getAnimations().every((a) => a.playState !== 'running'))
const COMPACT = /^(v\d+\.\d+(\.\d+)?|web)$/

const SIZES = [
    { name: '390', width: 390, height: 844 },
    { name: '360', width: 360, height: 780 },
    { name: '844 landscape', width: 844, height: 390 },
] as const

async function zoom(page: Page, percent: number) {
    await page.addInitScript((p) => {
        const style = document.createElement('style')
        style.textContent = `html { font-size: ${p}% !important; }`
        document.addEventListener('DOMContentLoaded', () => document.head.append(style))
    }, percent)
}

for (const size of SIZES) {
    test.describe(`phone ${size.name}`, () => {
        test.use({ viewport: { width: size.width, height: size.height }, screen: { width: size.width, height: size.height }, hasTouch: true, isMobile: true })

        test('the pill keeps its five places with the account last and Search inside; the tab order is the visual order', async ({ page }) => {
            await page.goto('/')
            const cells = pill(page).locator('> .nav-cell')
            await expect(cells).toHaveCount(5)
            const hrefs = await cells.evaluateAll((els) => els.map((e) => e.getAttribute('href') ?? (e.hasAttribute('data-you') ? 'you' : '?')))
            expect(hrefs).toEqual(['/', '/chats', '/search', '/history', 'you'])
            await expect(page.locator('#main-nav a.nav-search')).toHaveCount(0)
            // One account picture on the page, in the bar, in the bottom half and inside the pill.
            await expect(page.locator('[data-you]')).toHaveCount(1)
            const p = (await pill(page).boundingBox())!
            const you = (await picture(page).boundingBox())!
            expect(you.y).toBeGreaterThan(size.height / 2)
            expect(you.x + you.width).toBeLessThanOrEqual(p.x + p.width)
            expect(p.x).toBeGreaterThanOrEqual(8)
            expect(p.x + p.width).toBeLessThanOrEqual(size.width - 8)
            for (const cell of await cells.all()) expect((await cell.boundingBox())!.height).toBeGreaterThanOrEqual(44)
            await cells.first().focus()
            const order: string[] = []
            for (let i = 0; i < 5; i++) {
                order.push(await page.evaluate(() => (document.activeElement as HTMLElement).getAttribute('href') ?? ((document.activeElement as HTMLElement).hasAttribute('data-you') ? 'you' : '?')))
                await page.keyboard.press('Tab')
            }
            expect(order).toEqual(['/', '/chats', '/search', '/history', 'you'])
        })

        for (const percent of [100, 130]) {
            test(`every label is one size and the five items are equally wide, text at ${percent} percent`, async ({ page }) => {
                await zoom(page, percent)
                await page.goto('/')
                await expect(pill(page)).toBeVisible()
                const m = await pill(page).evaluate((bar) => {
                    const cells = [...bar.querySelectorAll<HTMLElement>(':scope > .nav-cell')]
                    return cells.map((c) => {
                        // The account is the picture alone: no label to size.
                        const label = c.querySelector<HTMLElement>('.nav-label')
                        const glyph = c.querySelector<HTMLElement>('.nav-glyph')!.getBoundingClientRect()
                        const ls = label ? getComputedStyle(label) : null
                        return {
                            width: c.getBoundingClientRect().width,
                            font: ls?.fontSize ?? null,
                            line: ls?.lineHeight ?? null,
                            glyph: [Math.round(glyph.width), Math.round(glyph.height)],
                            gap: getComputedStyle(c).rowGap,
                        }
                    })
                })
                expect(m).toHaveLength(5)
                const labelled = m.filter((x) => x.font !== null)
                expect(labelled, 'four labels, the account has none').toHaveLength(4)
                for (const x of labelled) {
                    expect(x.font, 'one font size').toBe(labelled[0].font)
                    expect(x.line, 'one line height').toBe(labelled[0].line)
                }
                for (const x of m) {
                    expect(x.glyph, 'one icon box').toEqual(m[0].glyph)
                    expect(x.gap, 'one icon to label gap').toBe(m[0].gap)
                    expect(Math.abs(x.width - m[0].width), 'equal widths within 1 px').toBeLessThanOrEqual(1)
                }
                // 12 to 15 px, as the label has always been (it never takes the 130 percent text size whole).
                const px = parseFloat(labelled[0].font!)
                expect(px).toBeGreaterThanOrEqual(12)
                expect(px).toBeLessThanOrEqual(15)
                expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
            })
        }

        test('a 60 character name takes no room in the bar: avatar only, same item widths, the name is its label and tooltip', async ({ page, context }) => {
            await fakeCase(context, 'longname')
            await page.goto('/')
            const you = picture(page)
            await expect(you).toHaveAccessibleName(`Account, ${LONG_NAME}`)
            await expect(you).toHaveAttribute('title', `Account, ${LONG_NAME}`)
            await expect(you.locator('.nav-label')).toHaveCount(0)
            expect(LONG_NAME).toHaveLength(60)
            const widths = await pill(page).locator('> .nav-cell').evaluateAll((els) => els.map((e) => e.getBoundingClientRect().width))
            for (const w of widths) expect(Math.abs(w - widths[0])).toBeLessThanOrEqual(1)
            const fonts = await pill(page).locator('.nav-label').evaluateAll((els) => els.map((e) => getComputedStyle(e).fontSize))
            expect(new Set(fonts).size).toBe(1)
            // No overflow: the page, the pill and the avatar's cell stay inside the window and the avatar is centred in its cell.
            expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
            const cell = (await you.boundingBox())!
            const art = (await you.locator('.nav-picture').boundingBox())!
            expect(Math.abs(art.x + art.width / 2 - (cell.x + cell.width / 2))).toBeLessThanOrEqual(1)
            expect(Math.abs(art.y + art.height / 2 - (cell.y + cell.height / 2))).toBeLessThanOrEqual(1.5)
            const p = (await pill(page).boundingBox())!
            expect(p.x + p.width).toBeLessThanOrEqual(size.width)
            // The sheet or card names the account in full, in the head, inside its width.
            await you.click()
            const who = page.locator('.you-who strong')
            await expect(who).toContainText('Maximilian-Alexander')
            const box = (await who.boundingBox())!
            expect(box.x + box.width).toBeLessThanOrEqual(size.width)
        })
    })
}

test.describe('the picture stays', () => {
    test.use({ viewport: { width: 390, height: 844 }, screen: { width: 390, height: 844 }, hasTouch: true, isMobile: true })

    test('one element across Home, Chats, Search and History: the same node, once, visible, in the same place', async ({ page }) => {
        await page.goto('/')
        await expect(picture(page)).toBeVisible()
        await picture(page).evaluate((el) => ((el as unknown as { __mark: string }).__mark = 'first'))
        const home = (await picture(page).boundingBox())!
        for (const [href, path] of [['/chats', /\/chats$/], ['/search', /\/search$/], ['/history', /\/history$/], ['/', /\/$/]] as const) {
            await pill(page).locator(`a[href="${href}"]`).click()
            await expect(page).toHaveURL(path)
            // Straight after the tap, mid route change, and settled: exactly one, the same node, never faded.
            for (const when of ['at once', 'settled']) {
                if (when === 'settled') await page.waitForTimeout(500)
                await expect(page.locator('[data-you]'), `${href} ${when}`).toHaveCount(1)
                expect(await picture(page).evaluate((el) => (el as unknown as { __mark?: string }).__mark), `${href} ${when}: the same node`).toBe('first')
                expect(await picture(page).evaluate((el) => getComputedStyle(el).opacity), 'never faded').toBe('1')
                expect(await picture(page).evaluate((el) => getComputedStyle(el).getPropertyValue('view-transition-name') || 'none')).toBe('none')
                const box = (await picture(page).boundingBox())!
                expect(Math.abs(box.x - home.x) + Math.abs(box.y - home.y), 'same place').toBeLessThanOrEqual(1)
            }
        }
    })
})

test.describe('the account entry on the TV bar stays', () => {
    test.use({ viewport: { width: 1920, height: 1080 } })

    test('the same node across the TV tabs', async ({ page }) => {
        await page.addInitScript(() => localStorage.setItem('tmplayer-layout', 'tv'))
        await page.goto('/')
        const entry = page.locator('.tv-bar .tv-picture')
        await expect(entry).toBeVisible()
        await entry.evaluate((el) => ((el as unknown as { __mark: string }).__mark = 'tv'))
        for (const href of ['/chats', '/search', '/']) {
            await page.locator(`.tv-tabs a[href="${href}"]`).click()
            await expect(page.locator('.tv-bar .tv-picture')).toHaveCount(1)
            expect(await entry.evaluate((el) => (el as unknown as { __mark?: string }).__mark)).toBe('tv')
            await expect(entry).toBeVisible()
            expect(await entry.evaluate((el) => getComputedStyle(el).opacity)).toBe('1')
        }
    })
})

test.describe('phone sheet', () => {
    test.use({ viewport: { width: 390, height: 844 }, screen: { width: 390, height: 844 }, hasTouch: true, isMobile: true })

    test('the account opens a bottom sheet: head, three tiles in one row, Settings, Add account, the compact version; no Sign out; no scrolling', async ({ page }) => {
        await page.goto('/')
        await picture(page).click()
        const card = sheet(page)
        await expect(card).toBeVisible()
        await settled(page)
        const box = (await card.boundingBox())!
        expect(Math.round(box.y + box.height)).toBe(844)
        expect(box.width).toBe(390)
        await expect(card.locator('.sheet-handle')).toBeVisible()
        await expect(card.locator('.you-who')).toContainText('Dana Test')
        await expect(card.locator('.you-who')).toContainText('@danatest')
        const lines = await names(page)
        expect(lines).toEqual([expect.stringContaining('Favourites'), expect.stringContaining('History'), expect.stringContaining('Watch later'), expect.stringContaining('Settings'), 'Add account', expect.stringMatching(COMPACT)])
        // The counts that were a strip are on the tiles.
        await expect(card.locator('.you-tile').nth(0).locator('.you-tile-sub')).toHaveText(/^Starred \d+$/)
        await expect(card.locator('.you-tile').nth(1).locator('.you-tile-sub')).toHaveText(/^Watching \d+$/)
        // One row of three equal tiles, one label size.
        const tiles = await card.locator('.you-tile').evaluateAll((els) => els.map((e) => ({ top: Math.round(e.getBoundingClientRect().top), w: e.getBoundingClientRect().width, h: e.getBoundingClientRect().height, f: getComputedStyle(e.querySelector('.you-tile-label')!).fontSize })))
        expect(new Set(tiles.map((t) => t.top)).size).toBe(1)
        expect(new Set(tiles.map((t) => t.f)).size).toBe(1)
        for (const t of tiles) {
            expect(Math.abs(t.w - tiles[0].w)).toBeLessThanOrEqual(1)
            expect(t.h).toBeGreaterThanOrEqual(44)
        }
        for (const row of await card.locator('.you-row').all()) expect((await row.boundingBox())!.height).toBeGreaterThanOrEqual(44)
        expect(await card.evaluate((el) => el.scrollHeight <= el.clientHeight + 1)).toBe(true)
        // Inside the dialog: Tab never leaves it.
        for (let i = 0; i < 10; i++) await page.keyboard.press('Tab')
        expect(await page.evaluate(() => !!document.activeElement?.closest('dialog.you-sheet'))).toBe(true)
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
    })

    test('the first tile has the keyboard, Left and Right walk the tiles, Down the rows', async ({ page }) => {
        await page.goto('/')
        await picture(page).click()
        await expect(sheet(page)).toBeVisible()
        const at = () => page.evaluate(() => (document.activeElement?.textContent ?? '').trim())
        expect(await at()).toContain('Favourites')
        await page.keyboard.press('ArrowRight')
        expect(await at()).toContain('History')
        await page.keyboard.press('ArrowDown')
        expect(await at()).toContain('Settings')
        await page.keyboard.press('ArrowUp')
        expect(await at()).toContain('History')
    })

    test('Back closes the sheet and stays on the page; focus returns to the picture', async ({ page }) => {
        await page.goto('/chats')
        const url = page.url()
        await picture(page).click()
        await expect(sheet(page)).toBeVisible()
        await page.goBack()
        await expect(sheet(page)).toHaveCount(0)
        expect(page.url()).toBe(url)
        await expect(picture(page)).toBeFocused()
        await picture(page).click()
        await expect(sheet(page)).toBeVisible()
    })

    test('Escape, a tap on the dim part and a drag down on the grip each close it', async ({ page }) => {
        await page.goto('/')
        await picture(page).click()
        await expect(sheet(page)).toBeVisible()
        await page.keyboard.press('Escape')
        await expect(sheet(page)).toHaveCount(0)
        await expect(picture(page)).toBeFocused()

        await picture(page).click()
        await expect(sheet(page)).toBeVisible()
        await page.mouse.click(195, 40)
        await expect(sheet(page)).toHaveCount(0)

        await picture(page).click()
        await expect(sheet(page)).toBeVisible()
        await settled(page)
        const grip = (await sheet(page).locator('.sheet-handle').boundingBox())!
        await page.mouse.move(grip.x + grip.width / 2, grip.y + 10)
        await page.mouse.down()
        await page.mouse.move(grip.x + grip.width / 2, grip.y + 60, { steps: 4 })
        // Follows the finger, a slow short drag springs back.
        expect(await sheet(page).evaluate((el) => (el as HTMLElement).style.transform)).toMatch(/translateY\(/)
        await page.waitForTimeout(500)
        await page.mouse.up()
        await expect(sheet(page)).toBeVisible()
        await page.mouse.move(grip.x + grip.width / 2, grip.y + 10)
        await page.mouse.down()
        await page.mouse.move(grip.x + grip.width / 2, grip.y + 200, { steps: 6 })
        await page.mouse.up()
        await expect(sheet(page)).toHaveCount(0)
    })

    test('a tile opens its page, the sheet is gone, and Back leads to where the person was', async ({ page }) => {
        await page.goto('/chats')
        await picture(page).click()
        await page.getByRole('menuitem', { name: /^History/ }).click()
        await expect(page).toHaveURL(/\/history$/)
        await expect(page.locator('dialog[open]')).toHaveCount(0)
        // The watched count the strip used to show is in History's heading.
        await expect(page.locator('[data-history-tally]')).toHaveText(/\d+ watching, \d+ watched, \d+ starred/)
        await page.goBack()
        await expect(page).toHaveURL(/\/chats$/)
    })
})

test.describe('phone sheet, small and zoomed', () => {
    test.use({ viewport: { width: 360, height: 640 }, screen: { width: 360, height: 640 }, hasTouch: true, isMobile: true })

    test('text at 130 percent: the sheet still fits without scrolling', async ({ page }) => {
        await zoom(page, 130)
        await page.goto('/')
        await picture(page).click()
        const card = sheet(page)
        await expect(card).toBeVisible()
        await settled(page)
        const box = (await card.boundingBox())!
        expect(box.y).toBeGreaterThanOrEqual(0)
        expect(Math.round(box.y + box.height)).toBe(640)
        expect(await card.evaluate((el) => el.scrollHeight <= el.clientHeight + 1), 'fits without scrolling').toBe(true)
        for (const tile of await card.locator('.you-tile').all()) expect((await tile.boundingBox())!.width).toBeGreaterThan(80)
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
    })
})

test.describe('phone sheet in landscape', () => {
    test.use({ viewport: { width: 844, height: 390 }, screen: { width: 844, height: 390 }, hasTouch: true, isMobile: true })

    test('the sheet scrolls inside itself and the version stays reachable', async ({ page }) => {
        await page.goto('/')
        await picture(page).click()
        const card = sheet(page)
        await expect(card).toBeVisible()
        await settled(page)
        const box = (await card.boundingBox())!
        expect(box.y).toBeGreaterThanOrEqual(0)
        expect(Math.round(box.y + box.height)).toBe(390)
        await card.locator('[data-version-line]').scrollIntoViewIfNeeded()
        await expect(card.locator('[data-version-line]')).toBeInViewport()
    })
})

for (const size of [
    { name: 'tablet 768', width: 768, height: 1024 },
    { name: 'desktop 1280', width: 1280, height: 800 },
]) {
    test.describe(size.name, () => {
        test.use({ viewport: { width: size.width, height: size.height } })

        test('the same content in an anchored card, the pill ends with the account', async ({ page }) => {
            await page.addInitScript(() => localStorage.setItem('tmplayer-layout', 'desktop'))
            await page.goto('/')
            await expect(pill(page).locator('> .nav-cell')).toHaveCount(5)
            await expect(pill(page).locator('> .nav-cell').last()).toHaveAttribute('data-you', '')
            await picture(page).click()
            await expect(page.locator('dialog.you-sheet')).toHaveCount(0)
            const lines = await names(page)
            expect(lines).toEqual([expect.stringContaining('Favourites'), expect.stringContaining('History'), expect.stringContaining('Watch later'), expect.stringContaining('Settings'), 'Add account', expect.stringMatching(COMPACT)])
            const card = (await page.getByRole('menu').boundingBox())!
            const bar = (await pill(page).boundingBox())!
            expect(card.y + card.height).toBeLessThanOrEqual(bar.y)
            expect(card.y).toBeGreaterThanOrEqual(0)
        })

        test('Settings: the two panes end the list with the compact version, the icon is in the header', async ({ page }) => {
            await page.addInitScript(() => localStorage.setItem('tmplayer-layout', 'desktop'))
            await page.goto('/settings')
            await expect(page.locator('.set-head .set-title-mark img.version-mark')).toBeVisible()
            const list = page.locator('.set-list')
            expect(await list.evaluate((el) => el.lastElementChild?.classList.contains('set-version-foot'))).toBe(true)
            await expect(list.locator('[data-version-line] .version-text')).toHaveText(COMPACT)
            await expect(page.getByRole('button', { name: 'Sign out of Telegram' })).toBeVisible()
        })
    })
}

test.describe('Settings on a phone', () => {
    for (const size of [
        { width: 390, height: 844 },
        { width: 844, height: 390 },
    ]) {
        test.describe(`${size.width} by ${size.height}`, () => {
            test.use({ viewport: size, screen: size, hasTouch: true, isMobile: true })

            test("the icon is in the header, Sign out is on the list, and the version is the list's last line", async ({ page }) => {
                await page.goto('/settings')
                await expect(page.locator('.set-bar .set-title-mark img.version-mark')).toBeVisible()
                await expect(page.getByRole('button', { name: 'Sign out of Telegram' })).toBeAttached()
                const foot = page.locator('.set-version-foot [data-version-line]')
                await foot.scrollIntoViewIfNeeded()
                await expect(foot).toBeInViewport()
                await expect(foot.locator('.version-text')).toHaveText(COMPACT)
                // Not hidden under the floating bar when scrolled to the end.
                await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight))
                const clear = await page.evaluate(() => {
                    const f = document.querySelector('.set-version-foot')!.getBoundingClientRect()
                    const n = document.querySelector('#main-nav .nav-pill')!.getBoundingClientRect()
                    return n.top - f.bottom
                })
                expect(clear).toBeGreaterThanOrEqual(0)
                await foot.click()
                await expect(page).toHaveURL(/\/settings\/about$/)
            })
        })
    }
})

test.describe('pictures', () => {
    test.skip(!process.env.SHOTS, 'SHOTS=1 writes the pictures')
    const DIR = fileURLToPath(new URL('../../design/account-sheet-web/', import.meta.url))
    const shots: { name: string; width: number; height: number; phone: boolean; url: string; open?: boolean; zoom?: number; mid?: boolean }[] = [
        { name: 'phone-390-bar', width: 390, height: 844, phone: true, url: '/' },
        { name: 'phone-390-sheet', width: 390, height: 844, phone: true, url: '/', open: true },
        { name: 'phone-360x640-sheet-130', width: 360, height: 640, phone: true, url: '/', open: true, zoom: 130 },
        { name: 'phone-390-settings', width: 390, height: 844, phone: true, url: '/settings' },
        { name: 'phone-390-mid-transition', width: 390, height: 844, phone: true, url: '/', mid: true },
        { name: 'phone-844-landscape-bar', width: 844, height: 390, phone: true, url: '/' },
        { name: 'phone-844-landscape-sheet', width: 844, height: 390, phone: true, url: '/', open: true },
        { name: 'phone-844-landscape-settings', width: 844, height: 390, phone: true, url: '/settings' },
        { name: 'tablet-768-menu', width: 768, height: 1024, phone: false, url: '/', open: true },
        { name: 'desktop-1280-menu', width: 1280, height: 800, phone: false, url: '/', open: true },
        { name: 'desktop-1280-settings', width: 1280, height: 800, phone: false, url: '/settings' },
    ]
    for (const s of shots) {
        test(s.name, async ({ browser }) => {
            mkdirSync(DIR, { recursive: true })
            const context = await browser.newContext({ baseURL: test.info().project.use.baseURL, viewport: { width: s.width, height: s.height }, screen: { width: s.width, height: s.height }, hasTouch: s.phone, isMobile: s.phone, deviceScaleFactor: 2 })
            const page = await context.newPage()
            if (!s.phone) await page.addInitScript(() => localStorage.setItem('tmplayer-layout', 'desktop'))
            if (s.zoom) await zoom(page, s.zoom)
            await page.goto(s.url)
            await expect(page.locator('#main-nav')).toBeVisible()
            if (s.open) {
                await picture(page).click()
                await expect(page.getByRole('menu')).toBeVisible()
                await page.waitForTimeout(500)
            }
            if (s.mid) {
                // A route change in flight: the tap, and the picture drawn on the very next frame.
                await page.evaluate(() => {
                    document.querySelector<HTMLElement>('#main-nav a[href="/chats"]')!.click()
                    return new Promise((done) => requestAnimationFrame(() => done(null)))
                })
                await expect(picture(page)).toBeVisible()
            }
            if (s.url === '/settings') {
                await page.locator('.set-version-foot').scrollIntoViewIfNeeded()
                await page.waitForTimeout(300)
            }
            await page.screenshot({ path: `${DIR}${s.name}.png` })
            await context.close()
        })
    }
})
