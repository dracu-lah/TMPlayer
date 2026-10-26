// Session 22: the video page in the form the project runs in (auto-detected, or the phone and desktop
// projects' own), and its Watch later and Watched marks, which are written in the background.
import { readFileSync } from 'node:fs'
import { expect, test, type Page } from '@playwright/test'
import { noSizeFloor, playsPast } from './helpers'

const PICTURE = readFileSync(new URL('./media/thumb.jpg', import.meta.url))

async function mockTmdb(page: Page) {
    await page.route('**/tmdb/**', (route) => {
        const url = new URL(route.request().url())
        if (url.pathname === '/tmdb/poster')
            return route.fulfill({ json: { match: true, id: '10378', kind: 'film', title: 'Big Buck Bunny', year: 2008, poster: '/p.jpg', backdrop: '/b.jpg', overview: 'A gentle giant of a rabbit wakes to a perfect spring morning.' } })
        if (url.pathname === '/tmdb/extras')
            return route.fulfill({ json: { found: true, genres: ['Animation', 'Comedy'], runtime: 10, seasons: null, rating: 6.5, certification: 'G', directors: [], creators: [], cast: [{ name: 'Jan Cornet', role: 'Frank', photo: null }], trailer: 'aqz-KE-bpKQ', ended: false, imdb: 'tt1254207' } })
        return route.fulfill({ contentType: 'image/jpeg', body: PICTURE })
    })
}

test.beforeEach(async ({ page }) => {
    await noSizeFloor(page)
})

test('the video page shows the title, the story and the marks; Play starts the player and Back returns', async ({ page }) => {
    await mockTmdb(page)
    await page.goto('/video/-1001/1')
    await expect(page.locator('.detail-title')).toHaveText('Big Buck Bunny')
    await expect(page.locator('.detail-synopsis')).toContainText('A gentle giant')
    await expect(page.locator('.detail-genres')).toContainText('Animation')
    await expect(page.locator('.detail-round', { hasText: 'Watch later' })).toBeVisible()
    await expect(page.locator('.detail-round', { hasText: 'Watched' })).toBeVisible()
    await page.locator('.detail-play').click()
    await expect(page).toHaveURL(/\/watch\//)
    await playsPast(page, 0.3)
    await page.goBack()
    await expect(page).toHaveURL(/\/video\//)
    await expect(page.locator('.detail-title')).toHaveText('Big Buck Bunny')
})

test('Watch later pressed and the page reloaded at once still holds it', async ({ page }) => {
    await page.goto('/video/-1001/1')
    const later = () => page.locator('.detail-round', { hasText: 'Watch later' })
    await expect(later()).toHaveAttribute('aria-pressed', 'false')
    await later().click()
    await expect(later()).toHaveAttribute('aria-pressed', 'true')
    await page.reload()
    await expect(later()).toHaveAttribute('aria-pressed', 'true')
    // And off again, reloaded straight after.
    await later().click()
    await expect(later()).toHaveAttribute('aria-pressed', 'false')
    await page.reload()
    await expect(later()).toHaveAttribute('aria-pressed', 'false')
})

test('Watched pressed and the page reloaded at once still holds it', async ({ page }) => {
    await page.goto('/video/-1001/1')
    await page.locator('.detail-round', { hasText: 'Watched' }).click()
    await expect(page.locator('.detail-watched')).toBeVisible()
    await page.reload()
    await expect(page.locator('.detail-watched')).toBeVisible()
})
