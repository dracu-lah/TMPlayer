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

// HX20: a show's own actions over its episodes, as the apps' show page has them.
test('a show: Mark episodes names its count and ticks the season, and the whole show goes on Watch later', async ({ page }) => {
    // The show's card opens the episode up next, whose page lists the show's seasons.
    await page.goto('/chat/-1002')
    await page.locator('.card', { hasText: 'Harbour Notes' }).first().click()
    await expect(page).toHaveURL(/\/video\//)
    await expect(page.locator('.episode')).toHaveCount(3)
    const marks = page.locator('.show-actions .detail-round', { hasText: 'Mark episodes' })
    await marks.click()
    const season = page.locator('dialog[open] .menu-row', { hasText: 'Mark season 1 as watched' })
    await expect(season).toContainText('3 episodes')
    await season.click()
    await expect(page.locator('.episode-watched')).toHaveCount(3)
    await page.reload()
    await expect(page.locator('.episode-watched')).toHaveCount(3)
    // And back: unwatched takes every tick off.
    await marks.click()
    await page.locator('dialog[open] .menu-row', { hasText: 'Mark season 1 as unwatched' }).click()
    await expect(page.locator('.episode-watched')).toHaveCount(0)
    const later = page.locator('.show-actions .detail-round', { hasText: 'Watch later' })
    await expect(later).toHaveAttribute('aria-pressed', 'false')
    await later.click()
    await expect(later).toHaveAttribute('aria-pressed', 'true')
    await page.goto('/history/later')
    await expect(page.locator('.card-name', { hasText: 'Harbour Notes' })).toBeVisible()
})

test('a wide desktop page is one centred column with equal gutters: the words and About stand side by side, the episodes under both', async ({ page }) => {
    await mockTmdb(page)
    await page.setViewportSize({ width: 1920, height: 1000 })
    await page.goto('/video/-1001/1')
    const form = await page.evaluate(() => document.documentElement.dataset.form)
    test.skip(form !== 'desktop', 'the two columns are the desktop layout')
    await expect(page.locator('.detail-title')).toHaveText('Big Buck Bunny')
    const box = async (sel: string) => (await page.locator(sel).first().boundingBox())!
    const width = await page.evaluate(() => document.documentElement.clientWidth)
    const head = await box('.detail-head')
    const side = await box('.detail-about')
    const main = await box('.detail-overview')
    // Symmetric: the space before the first column equals the space after the last, within a pixel.
    expect(Math.abs(head.x - (width - (side.x + side.width)))).toBeLessThanOrEqual(1)
    expect(side.x + side.width - head.x).toBeLessThanOrEqual(1280)
    // Two columns: About starts to the right of the overview's end, level with the actions and above the overview.
    expect(side.x).toBeGreaterThan(main.x + main.width)
    const actions = await box('.detail-actions')
    expect(Math.abs(side.y - actions.y)).toBeLessThanOrEqual(8)
    // The file name has room: its value is not squeezed into a narrow column.
    const value = await box('.detail-about dd')
    expect(value.width).toBeGreaterThan(200)
})
