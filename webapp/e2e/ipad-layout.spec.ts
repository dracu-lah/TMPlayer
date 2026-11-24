// WP-62: an iPad (a tablet screen, touch) in Split View or Slide Over gets the phone layout while its
// window is narrow, and Settings offers it the layout choice anyway, kept over the narrow window. Widths
// of an iPad's windows: 320 and 507 (Slide Over, one third), 678 (half) and 1024 (full). Also the small
// iPhone fixes: touch-action on controls, no link callout on cards, Save-Data held back, the first hero
// picture asking to come first.
import { expect, test, type Page } from '@playwright/test'

const IPAD = { screen: { width: 1024, height: 1366 }, hasTouch: true, isMobile: true }
const sideways = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
const formOf = (page: Page) => page.evaluate(() => document.documentElement.dataset.form)

for (const width of [320, 507, 678, 1024]) {
    test.describe(`an iPad window ${width} wide`, () => {
        test.use({ ...IPAD, viewport: { width, height: 1100 } })

        test('Home, Chats and Settings fit the window', async ({ page }) => {
            for (const path of ['/', '/chats', '/settings', '/settings/appearance']) {
                await page.goto(path)
                await expect(page.locator('main h1, main .page-title, main').first()).toBeVisible()
                expect(await sideways(page), path).toBeLessThanOrEqual(0)
            }
        })

        test('Layout is offered, and Desktop and Automatic hold over a reload', async ({ page }) => {
            // A 320 wide Slide Over is the phone's whatever is chosen: the desktop layout does not fit it.
            test.skip(width < 500, 'too narrow for the desktop layout')
            await page.goto('/settings/appearance')
            const layout = page.getByRole('radiogroup', { name: 'Layout' })
            await expect(layout).toBeVisible()
            const phoneNow = width < 840
            expect(await formOf(page)).toBe(phoneNow ? 'phone' : 'desktop')
            await layout.locator('label', { hasText: /^Desktop$/ }).click()
            await expect.poll(() => formOf(page)).toBe('desktop')
            await page.reload()
            expect(await formOf(page)).toBe('desktop')
            await page.getByRole('radiogroup', { name: 'Layout' }).locator('label', { hasText: /^Automatic$/ }).click()
            await expect.poll(() => formOf(page)).toBe(phoneNow ? 'phone' : 'desktop')
            await page.reload()
            expect(await formOf(page)).toBe(phoneNow ? 'phone' : 'desktop')
        })
    })
}

test.describe('an iPhone', () => {
    test.use({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true, screen: { width: 390, height: 844 } })

    test('a phone does not get the Layout choice, and its controls do not start a double tap zoom', async ({ page }) => {
        await page.goto('/settings/appearance')
        await expect(page.getByRole('radiogroup', { name: 'Theme' })).toBeVisible()
        await expect(page.getByRole('radiogroup', { name: 'Layout' })).toHaveCount(0)
        expect(await page.locator('.nav-cell').first().evaluate((el) => getComputedStyle(el).touchAction)).toBe('manipulation')
        expect(await page.locator('button, a').first().evaluate((el) => getComputedStyle(el).touchAction)).toBe('manipulation')
    })

    const SETTINGS_CODE = /\/src\/screens\/Settings\.tsx/

    test('with room on the line the other pages are fetched ahead', async ({ page }) => {
        const seen: string[] = []
        page.on('request', (r) => SETTINGS_CODE.test(r.url()) && seen.push(r.url()))
        await page.goto('/')
        await expect.poll(() => seen.length, { timeout: 15_000 }).toBeGreaterThan(0)
    })

    test('Save-Data holds the other pages back until they are opened', async ({ page }) => {
        await page.addInitScript(() => Object.defineProperty(navigator, 'connection', { value: { saveData: true, effectiveType: '4g' }, configurable: true }))
        const seen: string[] = []
        page.on('request', (r) => SETTINGS_CODE.test(r.url()) && seen.push(r.url()))
        await page.goto('/')
        await expect(page.locator('main').first()).toBeVisible()
        await page.waitForTimeout(5000)
        expect(seen).toEqual([])
        await page.goto('/settings')
        await expect(page.getByRole('heading', { name: 'Settings' }).first()).toBeVisible()
        expect(seen.length).toBeGreaterThan(0)
    })
})
