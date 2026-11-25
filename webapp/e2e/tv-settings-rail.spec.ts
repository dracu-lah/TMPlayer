// WP-88: the TV's Settings as a rail and a pane. The rail lists the apps' pages in their order, the pane
// follows the remote after 150 ms, Right enters it, Left on its first column and Back return to the rail's
// chosen page (still marked). The header and its breadcrumb stay.
import { expect, test, type Page } from '@playwright/test'
import { fakeCase } from './helpers'

async function tv(page: Page) {
    await page.goto('/__e2e/sizes')
    await page.evaluate(
        () =>
            new Promise<void>((resolve, reject) => {
                localStorage.setItem('tmplayer-layout', 'tv')
                const open = indexedDB.open('tmplayer-app')
                open.onupgradeneeded = () => open.result.createObjectStore('kv')
                open.onerror = () => reject(open.error)
                open.onsuccess = () => {
                    const tx = open.result.transaction('kv', 'readwrite')
                    tx.objectStore('kv').put({ sizeMin: 0 }, 'settings')
                    tx.oncomplete = () => (open.result.close(), resolve())
                }
            }),
    )
}
const press = async (page: Page, key: string, times = 1, wait = 160) => {
    for (let i = 0; i < times; i++) {
        await page.keyboard.press(key)
        await page.waitForTimeout(wait)
    }
}
const chosen = (page: Page) => page.locator('.tv-rail [aria-current=page]').innerText()
const onRail = (page: Page) => page.evaluate(() => !!document.activeElement?.closest('.tv-rail'))
const inPane = (page: Page) => page.evaluate(() => !!document.activeElement?.closest('.tv-pane'))
const railText = (page: Page) => page.evaluate(() => (document.activeElement as HTMLElement | null)?.innerText ?? '')

for (const size of [
    { width: 1920, height: 1080 },
    { width: 1280, height: 720 },
]) {
    test.describe(`TV Settings at ${size.width} x ${size.height}`, () => {
        test.use({ viewport: size })
        test.beforeEach(async ({ page, context }) => {
            await fakeCase(context)
            await tv(page)
            await page.goto('/settings')
            await expect(page.locator('.tv-rail')).toBeVisible()
            // The remote lands on the rail a moment after the page draws (tv/focus.ts settle); a key pressed before that only
            // lands it. The walks below counted that landing press, and a slow runner, where the remote had landed by then, ended
            // them a page further (run 45691829607): each walk starts with the remote on Account.
            await expect.poll(() => onRail(page)).toBe(true)
        })

        test('the rail is the apps order, the header names where the pane is, and the first page is Account', async ({ page }) => {
            expect(await page.locator('.tv-rail-item').allInnerTexts()).toEqual(['Account', 'Playback', 'Subtitles and audio', 'Downloads and data', 'Appearance and language', 'Home and library', 'Help', 'About'])
            expect(await chosen(page)).toBe('Account')
            await expect(page.locator('.tv-crumb')).toContainText('Settings')
            await expect(page.locator('.tv-crumb')).toContainText('Account')
            await expect(page.locator('.tv-pane')).toContainText('Sync across my devices')
        })

        test('the pane follows the rail after a moment, and a remote passing down the rail draws only where it rests', async ({ page }) => {
            await press(page, 'ArrowDown', 1, 400)
            expect(await chosen(page)).toBe('Playback')
            await expect(page.locator('.tv-crumb')).toContainText('Playback')
            // Three presses in quick succession: only the last page is drawn.
            await press(page, 'ArrowDown', 3, 30)
            await page.waitForTimeout(500)
            expect(await chosen(page)).toBe('Appearance and language')
            await expect(page.locator('.tv-pane')).toContainText('Theme')
            await expect(page.locator('.tv-pane')).not.toContainText('Rewind')
        })

        test('Right enters the pane, Left returns to the chosen page, and Back does too', async ({ page }) => {
            await press(page, 'ArrowDown', 3, 250)
            expect(await chosen(page)).toBe('Downloads and data')
            await press(page, 'ArrowRight')
            expect(await inPane(page)).toBe(true)
            await press(page, 'ArrowLeft')
            expect(await onRail(page)).toBe(true)
            expect(await railText(page)).toBe('Downloads and data')
            await press(page, 'ArrowRight')
            expect(await inPane(page)).toBe(true)
            // Back from the pane: the rail, on the page the pane shows.
            await press(page, 'Backspace')
            expect(await onRail(page)).toBe(true)
            expect(await railText(page)).toBe('Downloads and data')
            await expect(page).toHaveURL(/\/settings$/)
            // The remote is on the rail now: the Back after this one is the page's own (focus.ts), which the other TV walks hold.
        })

        test('a choice in the pane is made with OK, and the rail keeps its mark while the remote is in the pane', async ({ page }) => {
            await press(page, 'ArrowDown', 4, 250)
            expect(await chosen(page)).toBe('Appearance and language')
            await press(page, 'ArrowRight')
            const theme = () => page.evaluate(() => document.documentElement.dataset.theme ?? 'system')
            for (let i = 0; i < 12 && !(await page.evaluate(() => (document.activeElement as HTMLInputElement | null)?.name === 'theme')); i++) await press(page, 'ArrowDown')
            expect(await page.evaluate(() => (document.activeElement as HTMLInputElement | null)?.name)).toBe('theme')
            expect(await theme()).toBe('system')
            await press(page, 'ArrowRight')
            await press(page, 'Enter')
            expect(await theme()).toBe('dark')
            expect(await chosen(page)).toBe('Appearance and language')
        })

        test('the page stays inside the safe area and the text is at the 14 dp floor', async ({ page }) => {
            const box = await page.locator('.tv-rail').boundingBox()
            expect(box!.x).toBeGreaterThanOrEqual(size.width / 960 * 48 - 1)
            const px = await page.locator('.tv-crumb').evaluate((el) => parseFloat(getComputedStyle(el).fontSize))
            expect(px).toBeGreaterThanOrEqual((size.width / 960) * 14 - 0.5)
            for (const text of await page.locator('.tv-rail-item').all()) {
                expect(await text.evaluate((el) => parseFloat(getComputedStyle(el).fontSize))).toBeGreaterThanOrEqual((size.width / 960) * 14 - 0.5)
            }
        })
    })
}
