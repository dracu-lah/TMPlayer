// Every way a file plays (plan section 3): native, repacked, sound converted, AVI through libav.js
// (MPEG-4 Part 2 and DivX 3 decoded, H.264 copied), and one that needs the app, from e2e/media.
import { expect, test } from '@playwright/test'
import { noSizeFloor, openCard, openClips, playsPast } from './helpers'

const PLAYS = ['Native Clip', 'Tail Index', 'Matroska Clip', 'Transport Clip', 'Surround Clip', 'Webm Clip', 'Old Clip', 'Xvid Clip', 'Divx Clip', 'Avc Avi Clip']

test.beforeEach(async ({ page }) => {
    await noSizeFloor(page)
    await openClips(page)
})

for (const name of PLAYS) {
    test(`${name} plays`, async ({ page }) => {
        await openCard(page, name)
        await expect(page).toHaveURL(/\/watch\//)
        await playsPast(page, 1)
    })
}

test('a WMV says it needs the app', async ({ page }) => {
    await openCard(page, 'Windows Clip')
    await expect(page.getByText('This video needs the TMPlayer app')).toBeVisible()
    await expect(page.locator('a[href="https://tmplayer.org/#download"]')).toBeVisible()
})

test('a damaged AVI says it needs the app', async ({ page }) => {
    await openCard(page, 'Broken Clip')
    await expect(page.getByText('This video needs the TMPlayer app')).toBeVisible()
})

test('Back closes the player and returns to the chat', async ({ page }) => {
    await openCard(page, 'Native Clip')
    await playsPast(page, 0.5)
    await page.goBack()
    await expect(page).toHaveURL(/\/chat\/-1001/)
    await expect(page.locator('video')).toHaveCount(0)
})

test('a watch page opened by its address plays', async ({ page }) => {
    const card = page.locator('.card').filter({ hasText: 'Matroska Clip' })
    for (let i = 0; i < 30 && (await card.count()) === 0; i++) await page.evaluate(() => window.scrollBy(0, 300))
    const href = await card.getAttribute('href')
    await page.goto(href!)
    await playsPast(page, 1)
})

test.describe('without a service worker', () => {
    test.use({ serviceWorkers: 'block' })
    test('an MP4 is repacked instead', async ({ page }) => {
        await openCard(page, 'Native Clip')
        await playsPast(page, 1)
    })
})
