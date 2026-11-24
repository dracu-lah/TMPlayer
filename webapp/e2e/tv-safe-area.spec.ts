// WP-05: on the TV layout (?tv=1) at 1920 x 1080 every control of the player (the row, the title and
// bar, the clock, the next-up card, the corner chips) stands inside 5 percent of each edge: 96 px
// across and 54 px down, the same 48 and 27 dp the Android player keeps.
import { expect, test } from '@playwright/test'
import { fakeCase, noSizeFloor, openCard, openClips, playsPast } from './helpers'

test.describe('TV player safe area at 1920 x 1080', () => {
    test.skip(({ browserName }) => browserName !== 'chromium', 'the TV layout runs in Chromium')
    test.use({ viewport: { width: 1920, height: 1080 } })
    test.beforeEach(async ({ context }) => {
        await fakeCase(context, 'mid')
    })

    test('every .pc control of the raised row is inside 5 percent', async ({ page }) => {
        await noSizeFloor(page)
        // ?tv=1 stays for the tab, so the pages after it keep the TV layout.
        await page.goto('/?tv=1')
        await expect(page.locator('html')).toHaveAttribute('data-form', 'tv')
        await openClips(page)
        await openCard(page, 'Native Clip')
        await expect(page.locator('.player[data-variant="tv"]')).toHaveCount(1)
        await playsPast(page, 1)
        // The row up, as the remote's first press raises it.
        await page.keyboard.press('ArrowUp')
        await page.waitForTimeout(400)

        const out = await page.evaluate(() => {
            const vw = innerWidth
            const vh = innerHeight
            const sel = '.pc button, .pc .pc-clock, .pc .pc-next, .pc .pc-corner, .pc .pc-offer, .pc .pc-cluster-title, .pc .pc-cluster-sub, .pc .pc-bar'
            const seen: { name: string; left: number; top: number; right: number; bottom: number }[] = []
            let counted = 0
            for (const el of document.querySelectorAll<HTMLElement>(sel)) {
                const r = el.getBoundingClientRect()
                const s = getComputedStyle(el)
                if (r.width <= 0 || r.height <= 0 || s.visibility === 'hidden' || s.display === 'none' || el.closest('.pc-panel, .pc-sheet')) continue
                counted++
                const name = `${el.tagName.toLowerCase()}.${(el.getAttribute('class') ?? '').split(' ')[0]}`
                if (r.left < vw * 0.05 - 1 || r.top < vh * 0.05 - 1 || r.right > vw * 0.95 + 1 || r.bottom > vh * 0.95 + 1) {
                    seen.push({ name, left: Math.round(r.left), top: Math.round(r.top), right: Math.round(r.right), bottom: Math.round(r.bottom) })
                }
            }
            return { counted, seen }
        })
        expect(out.counted, 'the raised row has controls').toBeGreaterThan(4)
        expect(out.seen, `controls outside the 96 x 54 px safe area: ${JSON.stringify(out.seen)}`).toEqual([])
    })

    test('the Playback details sheet is inside 5 percent; the settings drawer is edge-attached by design', async ({ page }) => {
        await noSizeFloor(page)
        await page.goto('/?tv=1')
        await expect(page.locator('html')).toHaveAttribute('data-form', 'tv')
        await openClips(page)
        await openCard(page, 'Native Clip')
        await playsPast(page, 1)
        await page.locator('.pc').focus()
        await page.keyboard.press('i')
        await expect(page.locator('.pc-details')).toBeVisible()
        await page.waitForTimeout(400)
        const box = await page.locator('.pc-details').boundingBox()
        expect(box).toBeTruthy()
        expect(box!.x, 'left edge at 96 px or more').toBeGreaterThanOrEqual(96 - 1)
        expect(box!.y, 'top edge at 54 px or more').toBeGreaterThanOrEqual(54 - 1)
        expect(box!.x + box!.width, 'right edge inside 1824 px').toBeLessThanOrEqual(1920 - 96 + 1)
        expect(box!.y + box!.height, 'bottom edge inside 1026 px').toBeLessThanOrEqual(1080 - 54 + 1)
        // .pc-panel (the menu drawer) is attached to the screen's end edge by design, like a
        // drawer; its rows are inside its own padding. .pc-sheet.pc-episodes is the same kind of
        // drawer (32 percent wide, the whole height) with 27u of padding above and below.
    })
})
