// A video kept in a ZIP, and a video cut into raw pieces: listed as one card, played as one stream (stream/joined.ts)
// from the fake Telegram's `zipped` chat, whose pieces are real files in e2e/media (make-media.sh).
import { expect, test } from '@playwright/test'
import { fakeCase, noSizeFloor, openCard, playsPast } from './helpers'

test.beforeEach(async ({ page, context }) => {
    await fakeCase(context, 'zipped')
    await noSizeFloor(page)
    await page.goto('/chat/-1007')
    await expect(page.locator('.card').first()).toBeVisible()
})

test('four ZIP pieces are one card, a video cut in three is one card, and a phone backup is none', async ({ page }) => {
    await expect(page.locator('.card').filter({ hasText: 'Zipped Clip' })).toHaveCount(1)
    await expect(page.locator('.card').filter({ hasText: 'Raw Clip' })).toHaveCount(1)
    await expect(page.locator('.card').filter({ hasText: 'Deflated Clip' })).toHaveCount(1)
    await expect(page.locator('.card').filter({ hasText: 'Gap Clip' })).toHaveCount(1)
    await expect(page.locator('.card')).toHaveCount(4)
    await expect(page.locator('.card').filter({ hasText: /Ivy/ })).toHaveCount(0)
})

test('a stored video in four ZIP pieces plays out of the archive', async ({ page }) => {
    await openCard(page, 'Zipped Clip')
    await playsPast(page, 1)
    const duration = await page.locator('video').first().evaluate((v: HTMLVideoElement) => v.duration)
    expect(duration).toBeGreaterThan(3)
    expect(duration).toBeLessThan(5)
})

test('seeking in a video played out of a ZIP lands where it was told', async ({ page }) => {
    await openCard(page, 'Zipped Clip')
    await playsPast(page, 0.5)
    const at = await page.locator('video').first().evaluate(async (v: HTMLVideoElement) => {
        v.currentTime = 3
        await new Promise((r) => v.addEventListener('seeked', r, { once: true }))
        return v.currentTime
    })
    expect(at).toBeGreaterThan(2.9)
    await playsPast(page, 3.2)
})

test('a video cut into three raw pieces plays as one', async ({ page }) => {
    await openCard(page, 'Raw Clip')
    await playsPast(page, 1)
})

test('a ZIP whose video is compressed says so, not a decoder error', async ({ page }) => {
    await openCard(page, 'Deflated Clip')
    await expect(page.getByText('The video in this ZIP is compressed')).toBeVisible({ timeout: 15_000 })
})

test('a ZIP with a piece missing says which, and does not try', async ({ page }) => {
    await openCard(page, 'Gap Clip')
    await expect(page.getByText(/Part 3 of 4 is not in this chat/)).toBeVisible({ timeout: 15_000 })
})

test.describe('without a service worker', () => {
    test.use({ serviceWorkers: 'block' })
    test('a video in ZIP pieces is repacked from the joined file instead', async ({ page }) => {
        await openCard(page, 'Zipped Clip')
        await playsPast(page, 1)
    })
})
