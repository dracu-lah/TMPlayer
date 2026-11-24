// The player's episode list (W15, 2026-11-25): a panel at the end about a third of the width from 600 px, a
// bottom sheet under it; 16:9 pictures with the code on them, short words (no file size), the playing
// episode marked and in view. WB_SHOTS=1 writes the frames to design/episodes/web/.
import { expect, test } from '@playwright/test'

const SHOTS = new URL('../../design/episodes/web/', import.meta.url)

for (const [name, width, height, touch] of [
    ['desktop-1280', 1280, 800, false],
    ['phone-390', 390, 844, true],
] as const) {
    test.describe(name, () => {
        test.use({ viewport: { width, height }, hasTouch: touch, isMobile: touch })

        test(`the list is a ${touch ? 'bottom sheet' : 'panel at the end'} with pictures and short words`, async ({ page }) => {
            await page.goto('/chat/-1002')
            await page.locator('.card', { hasText: 'Harbour Notes' }).first().click()
            await expect(page).toHaveURL(/\/video\//)
            await page.getByRole('button', { name: 'Play', exact: true }).first().click()
            await expect(page).toHaveURL(/\/watch\//)
            await expect.poll(() => page.locator('video').evaluate((v: HTMLVideoElement) => v.currentTime)).toBeGreaterThan(0.2)
            const opener = page.locator('[data-shot="episodes"]')
            await page.locator('.pc').hover()
            if (touch) await page.locator('.pc').tap()
            await opener.click()
            const panel = page.locator('.pc-sheet.pc-episodes')
            await expect(panel).toBeVisible()
            // Past its slide in.
            await page.waitForTimeout(400)
            const box = (await panel.boundingBox())!
            if (touch) {
                expect(box.y + box.height, 'the sheet rests on the bottom edge').toBeGreaterThanOrEqual(height - 1)
                expect(box.width, 'the sheet is the full width').toBeGreaterThanOrEqual(width - 1)
                expect(box.height / height, '82 percent of the height').toBeCloseTo(0.82, 1)
            } else {
                expect(box.x + box.width, 'the panel rests on the end edge').toBeGreaterThanOrEqual(width - 1)
                expect(box.width, 'a third of the width, 340 to 520 px').toBeGreaterThanOrEqual(340)
                expect(box.width).toBeLessThanOrEqual(520 + 1)
                expect(box.height, 'the whole height').toBeGreaterThanOrEqual(height - 1)
            }
            const still = page.locator('.pc-episode .episode-still').first()
            await expect(still).toBeVisible()
            const s = (await still.boundingBox())!
            expect(Math.abs((s.width * 9) / 16 - s.height), '16:9').toBeLessThan(2)
            await expect(page.locator('.pc-episode.playing')).toHaveCount(1)
            await expect(panel).not.toContainText(/\d (MB|GB)/)
            await expect(panel).not.toContainText('.mkv')
            if (process.env.WB_SHOTS) await page.screenshot({ path: new URL(`player-episodes-${name}.png`, SHOTS).pathname })
            // Escape or Back closes it and the video plays on.
            await page.keyboard.press('Escape')
            await expect(panel).toHaveCount(0)
            expect(await page.locator('video').evaluate((v: HTMLVideoElement) => v.readyState)).toBeGreaterThan(0)
        })
    })
}
