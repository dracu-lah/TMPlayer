// A browser that refuses to play until a press, as Safari does in a fresh tab. Playwright's
// browsers always count as pressed, so the refusal is made here: play() fails with the browser's
// own NotAllowedError until the first press on the page.
import { expect, test } from '@playwright/test'
import { noSizeFloor, playsPast } from './helpers'

test('blocked autoplay waits for a press on Play', async ({ page }) => {
    await noSizeFloor(page)
    await page.addInitScript(() => {
        let pressed = false
        addEventListener('pointerdown', () => (pressed = true), { capture: true })
        const play = HTMLMediaElement.prototype.play
        HTMLMediaElement.prototype.play = function () {
            return pressed ? play.call(this) : Promise.reject(new DOMException('play() needs a press', 'NotAllowedError'))
        }
    })
    // Native Clip, opened by its address, with no press on the page yet.
    await page.goto('/watch/-1001/1')
    await expect(page.locator('video')).toHaveCount(1)
    await page.waitForTimeout(3000)
    expect(await page.locator('video').evaluate((v: HTMLVideoElement) => v.paused)).toBe(true)
    await expect(page.locator('.pc-play')).toBeVisible()
    await page.locator('.pc-play').click()
    await playsPast(page, 0.5)
})
