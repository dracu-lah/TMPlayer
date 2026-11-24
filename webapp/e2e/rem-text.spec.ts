// WP-55: text follows the system size. At a root font of 200 percent on a 390 and a 320 wide phone
// no page scrolls sideways and the bottom bar stays inside the screen with every place reachable.
import { expect, test, type Page } from '@playwright/test'
import { noSizeFloor, openClips } from './helpers'

const WIDTHS = [390, 320]

for (const width of WIDTHS) {
    test.describe(`root font 200 percent at ${width}`, () => {
        test.use({ viewport: { width, height: 760 }, hasTouch: true, isMobile: true })

        async function bigText(page: Page) {
            await page.addInitScript(() => {
                const style = document.createElement('style')
                // What a person's browser or phone setting does: the root's own size, never a px value.
                style.textContent = 'html { font-size: 200% !important; }'
                document.addEventListener('DOMContentLoaded', () => document.head.append(style))
            })
        }
        const noSideScroll = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)

        async function barInside(page: Page) {
            const cells = page.locator('.nav-cells > a, .nav-cells > button')
            const n = await cells.count()
            expect(n).toBeGreaterThanOrEqual(4)
            for (let i = 0; i < n; i++) {
                const box = (await cells.nth(i).boundingBox())!
                expect(box.x).toBeGreaterThanOrEqual(0)
                expect(box.x + box.width).toBeLessThanOrEqual(width)
                expect(box.y + box.height).toBeLessThanOrEqual(760)
            }
        }

        test('the rem root really is 200 percent here, and the page text follows it', async ({ page }) => {
            await bigText(page)
            await page.goto('/chats')
            await expect(page.locator('.nav-cells')).toBeVisible()
            expect(await page.evaluate(() => getComputedStyle(document.documentElement).fontSize)).toBe('32px')
            expect(await page.evaluate(() => getComputedStyle(document.body).fontSize)).toBe('28px')
        })

        test('Chats, a chat and Settings: no sideways scroll, the bar inside the screen', async ({ page }) => {
            await bigText(page)
            await noSizeFloor(page)
            await page.goto('/chats')
            await expect(page.getByRole('link', { name: /Test Clips/ }).first()).toBeVisible()
            expect(await noSideScroll(page)).toBe(true)
            await barInside(page)
            await openClips(page)
            await expect(page.locator('.card').first()).toBeVisible()
            expect(await noSideScroll(page)).toBe(true)
            await page.goto('/settings')
            await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
            expect(await noSideScroll(page)).toBe(true)
            await barInside(page)
        })

        test('the bar label stays a readable size and is not the 200 percent size', async ({ page }) => {
            await bigText(page)
            await page.goto('/chats')
            const px = await page.locator('.nav-label').first().evaluate((el) => parseFloat(getComputedStyle(el).fontSize))
            expect(px).toBeGreaterThanOrEqual(12)
            expect(px).toBeLessThanOrEqual(15)
        })
    })
}
