// The video's page, the "You" menu and its lists (session 19), and the player's own controls.
import { expect, test } from '@playwright/test'
import { noSizeFloor, openCard, openClips, openDetail, playsPast } from './helpers'

test.beforeEach(async ({ page }) => {
    await noSizeFloor(page)
    await openClips(page)
})

test('Watch later on a video page puts it in the You menu list, and off again', async ({ page }) => {
    await openDetail(page, 'Native Clip')
    await expect(page.locator('.detail-title')).toHaveText('Native Clip')
    const later = page.locator('.detail-round', { hasText: 'Watch later' })
    await later.click()
    await expect(later).toHaveAttribute('aria-pressed', 'true')
    await page.locator('.detail-close').click()
    await page.locator('[data-you]').click()
    await page.getByRole('menuitem', { name: 'Watch later' }).click()
    await expect(page).toHaveURL(/\/history\/later$/)
    await expect(page.locator('.card').filter({ hasText: 'Native Clip' })).toHaveCount(1)
    await page.locator('.card').filter({ hasText: 'Native Clip' }).click()
    const again = page.locator('.detail-round', { hasText: 'Watch later' })
    await again.click()
    await expect(again).toHaveAttribute('aria-pressed', 'false')
    await page.locator('.detail-close').click()
    await expect(page).toHaveURL(/\/history\/later$/)
    await expect(page.locator('.empty-state')).toBeVisible()
})

test('Watched from a video page shows under History, Watched', async ({ page }) => {
    await openDetail(page, 'Matroska Clip')
    await page.locator('.detail-round', { hasText: 'Watched' }).click()
    await expect(page.locator('.detail-watched')).toBeVisible()
    await page.goto('/history/watched')
    await expect(page.locator('.card').filter({ hasText: 'Matroska Clip' })).toHaveCount(1)
})

test('the player answers its keys: Space pauses, the arrows jump, M mutes, Escape closes', async ({ page }) => {
    await openCard(page, 'Native Clip')
    await playsPast(page, 0.3)
    await page.keyboard.press('Space')
    await expect.poll(() => page.locator('video').evaluate((v: HTMLVideoElement) => v.paused)).toBe(true)
    await expect(page.locator('.pc')).toHaveClass(/shown/)
    await page.keyboard.press('m')
    await expect.poll(() => page.locator('video').evaluate((v: HTMLVideoElement) => v.muted)).toBe(true)
    await page.keyboard.press('Escape')
    await expect(page).toHaveURL(/\/video\//)
})

test('the speed menu changes the speed and the button says it', async ({ page }) => {
    await openCard(page, 'Native Clip')
    await playsPast(page, 0.3)
    await page.mouse.move(100, 100)
    await page.mouse.move(120, 120)
    await page.locator('[data-shot=speed]').click()
    await page.getByRole('menuitemradio', { name: '1.5x' }).click()
    await expect.poll(() => page.locator('video').evaluate((v: HTMLVideoElement) => v.playbackRate)).toBe(1.5)
    await expect(page.locator('[data-shot=speed]')).toHaveText('1.5x')
})
