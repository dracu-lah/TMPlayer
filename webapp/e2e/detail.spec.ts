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

// The anime episode guide (T4 follow-up): a show AniList matched lists no seasons, so the worker's guide supplies the run.
test('an anime show lists the episodes its chat lacks from the guide, names the episode, and asks again while the guide is cut short', async ({ page }) => {
    const guideAsks: string[] = []
    const entry = (n: number) => ({ absolute: n, season: null, episode: null, name: n === 1 ? 'The Rescue Mission' : '', overview: n === 1 ? 'A rescue goes wrong.' : '', still: null, airDate: '2026-01-01', runtime: null, source: n === 1 ? 'kitsu' : null, stillSource: null })
    await page.route('**/tmdb/**', (route) => {
        const url = new URL(route.request().url())
        if (url.pathname === '/tmdb/poster') return route.fulfill({ json: { match: true, id: '20', kind: 'show', title: 'Harbour Notes', year: 2019, poster: null, backdrop: null, overview: '', source: 'anilist' } })
        if (url.pathname === '/tmdb/extras') return route.fulfill({ json: { found: true, genres: ['Adventure'], runtime: 24, seasons: null, rating: null, certification: null, directors: [], creators: [], cast: [], trailer: null, ended: false, seasonList: [], next: null, imdb: null, logo: null } })
        if (url.pathname === '/tmdb/anime') {
            guideAsks.push(url.searchParams.get('id') ?? '')
            // The first answer is cut short (6 episodes known), the next is whole (12, the tenth to air).
            const whole = guideAsks.length > 1
            const total = whole ? 12 : 6
            return route.fulfill({ json: { found: true, complete: whole, total, declared: whole ? 12 : 0, layout: [], next: whole ? { season: 1, episode: 10, airDate: '2099-01-01' } : null, entries: Array.from({ length: total }, (_, i) => entry(i + 1)) } })
        }
        return route.fulfill({ status: 404, body: '' })
    })
    await page.goto('/chat/-1002')
    await page.locator('.card', { hasText: 'Harbour Notes' }).first().click()
    await expect(page).toHaveURL(/\/video\//)
    // The chat holds three: the guide's other three show as missing at once.
    await expect(page.locator('.episode')).toHaveCount(6)
    await expect(page.locator('.episode.gap')).toHaveCount(3)
    expect(guideAsks).toEqual(['20'])
    // The page is about the first episode: the guide's title and words stand in for the ones AniList does not have.
    await expect(page.locator('.detail-code')).toContainText('The Rescue Mission')
    await expect(page.locator('.detail-synopsis')).toContainText('A rescue goes wrong.')
    // Cut short, it is asked again (6 s later); the whole guide adds the rest, the tenth on as upcoming.
    await expect(page.locator('.episode')).toHaveCount(12, { timeout: 15_000 })
    await expect(page.locator('.episode.gap')).toHaveCount(9)
    expect(guideAsks).toEqual(['20', '20'])
    await expect(page.locator('.season-chip')).toHaveCount(1)
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

// HX38 on the web (WB3): Big.Buck.Bunny posted three times in Test Copies (fake/mtcute.ts), as a
// 2160p HEVC, a 1080p x264 and a 720p x264. The card's badge, its count and the copy Play opens are
// one answer, the copies list puts that copy first with "Best for this device", and a phone or a
// browser without HEVC never leads with the 4K HEVC (decision D5).

/** The copy this browser and form recommend: a phone is capped at 1080p, a browser without HEVC passes the HEVC copy over. */
async function recommended(page: Page): Promise<{ height: '4K' | '1080p'; message: number; hevc: boolean; device: string }> {
    const { hevc, form } = await page.evaluate(() => {
        const ms = (globalThis as { ManagedMediaSource?: typeof MediaSource }).ManagedMediaSource ?? globalThis.MediaSource
        const ask = (m: string) => !!ms?.isTypeSupported?.(m) || document.createElement('video').canPlayType(m) !== ''
        return { hevc: ask('video/mp4; codecs="hvc1.1.6.L93.B0"') || ask('video/mp4; codecs="hev1.1.6.L93.B0"'), form: document.documentElement.dataset.form }
    })
    const device = form === 'tv' ? 'TV' : form === 'phone' ? 'phone' : 'computer'
    return form !== 'phone' && hevc ? { height: '4K', message: 25, hevc, device } : { height: '1080p', message: 26, hevc, device }
}

const shotsDir = new URL('../../design/web-gaps/WB3/', import.meta.url)

test('a video posted three times: the card badges and opens the recommended copy, its page lists it first, the count is the list', async ({ page }, info) => {
    await mockTmdb(page)
    await page.goto('/chat/-1005')
    const card = page.locator('.card').filter({ hasText: 'Big Buck Bunny' }).first()
    await expect(card).toBeVisible()
    const best = await recommended(page)
    await expect(card).toContainText('3 versions')
    await expect(card.locator('.card-format')).toHaveText(best.height)
    await expect(card).toHaveAttribute('href', new RegExp(`/video/-1005/${best.message}$`))
    await card.click()
    await expect(page).toHaveURL(new RegExp(`/video/-1005/${best.message}$`))
    const summary = page.locator('.detail-copy-summary')
    await expect(summary).toContainText(best.height)
    await expect(summary).toContainText('3 versions')
    await summary.click()
    const options = page.getByRole('option')
    await expect(options).toHaveCount(3)
    await expect(options.nth(0)).toContainText(best.height)
    await expect(options.nth(0)).toHaveAttribute('aria-selected', 'true')
    await expect(options.nth(0).locator('.detail-copy-best')).toHaveText('Best for this device')
    await expect(page.locator('.detail-copy-best')).toHaveCount(1)
    const hevcRow = options.filter({ hasText: '4K' })
    if (!best.hevc) {
        await expect(options.nth(2)).toContainText('4K')
        await expect(hevcRow).toContainText(`May stutter on this ${best.device}`)
        await expect(options.nth(0)).toContainText(`Smooth on this ${best.device}`)
    } else {
        await expect(page.getByText(/May stutter/)).toHaveCount(0)
    }
    if (process.env.WB_SHOTS) {
        await options.nth(0).scrollIntoViewIfNeeded()
        await page.screenshot({ path: new URL(`${info.project.name}.png`, shotsDir).pathname })
    }
    // Play opens the copy the card badged.
    await page.locator('.detail-play').click()
    await expect(page).toHaveURL(new RegExp(`/watch/-1005/${best.message}$`))
})

test("the viewer's own pick persists over the recommendation: the card plays it, after a reload too", async ({ page }) => {
    await mockTmdb(page)
    await page.goto('/video/-1005/26')
    await page.locator('.detail-copy-summary').click()
    await page.getByRole('option').filter({ hasText: '720p' }).click()
    await expect(page).toHaveURL(/\/video\/-1005\/27$/)
    // The page turned to the copy chosen, and the focus is back on the copies line (the keyboard and the remote go on from it).
    await expect(page.locator('.detail-copy-summary')).toContainText('720p')
    await expect(page.locator('.detail-copy-summary')).toBeFocused()
    await page.goto('/chat/-1005')
    const card = page.locator('.card').filter({ hasText: 'Big Buck Bunny' }).first()
    await expect(card).toHaveAttribute('href', /\/video\/-1005\/27$/)
    await expect(card.locator('.card-format')).toHaveText('720p')
    await page.reload()
    await expect(page.locator('.card').filter({ hasText: 'Big Buck Bunny' }).first()).toHaveAttribute('href', /\/video\/-1005\/27$/)
})

test('Not the same video splits the file from its copies, and Group with its copies again puts it back', async ({ page }) => {
    await mockTmdb(page)
    await page.goto('/video/-1005/27')
    await expect(page.locator('.detail-copy-summary')).toContainText('3 versions')
    await page.getByRole('button', { name: /Not the same video/ }).click()
    await expect(page.locator('.detail-versions')).toHaveCount(0)
    const again = page.getByRole('button', { name: 'Group with its copies again' })
    await expect(again).toBeFocused()
    await page.goto('/chat/-1005')
    const cards = page.locator('.card').filter({ hasText: 'Big Buck Bunny' })
    await expect(cards).toHaveCount(2)
    await expect(cards.filter({ hasText: '2 versions' })).toHaveCount(1)
    // Kept apart after a reload, then grouped again from its page.
    await page.goto('/video/-1005/27')
    await page.getByRole('button', { name: 'Group with its copies again' }).click()
    await expect(page.locator('.detail-copy-summary')).toContainText('3 versions')
    await expect(page.getByRole('button', { name: /Not the same video/ })).toBeFocused()
    await page.goto('/chat/-1005')
    await expect(page.locator('.card').filter({ hasText: 'Big Buck Bunny' })).toHaveCount(1)
})

test('a card menu splits a grouped video and offers Group again on the file kept apart', async ({ page }) => {
    const touch = await page.evaluate(() => matchMedia('(pointer: coarse)').matches)
    test.skip(touch, 'the right click opens the menu here; a long press on a phone is the same menu (CardMenu.tsx)')
    await mockTmdb(page)
    await page.goto('/chat/-1005')
    const card = page.locator('.card').filter({ hasText: 'Big Buck Bunny' }).first()
    await card.click({ button: 'right' })
    await page.getByRole('button', { name: /Not the same video/ }).click()
    await expect(page.locator('.card').filter({ hasText: 'Big Buck Bunny' })).toHaveCount(2)
    const alone = page.locator('.card').filter({ hasText: 'Big Buck Bunny' }).filter({ hasNotText: 'versions' })
    await alone.click({ button: 'right' })
    await expect(page.getByRole('button', { name: /Not the same video/ })).toHaveCount(0)
    await page.getByRole('button', { name: 'Group with its copies again' }).click()
    await expect(page.locator('.card').filter({ hasText: 'Big Buck Bunny' })).toHaveCount(1)
})

test.describe('on the TV', () => {
    test.use({ viewport: { width: 1920, height: 1080 } })
    test('the copies line is reached with the remote, and the focus comes back to it after a pick', async ({ page }, info) => {
        test.skip(info.project.name !== 'chromium', 'the TV layout is one form: run once')
        await page.addInitScript(() => localStorage.setItem('tmplayer-layout', 'tv'))
        await mockTmdb(page)
        await page.goto('/video/-1005/26')
        await expect(page.locator('html')).toHaveAttribute('data-form', 'tv')
        const summary = page.locator('.detail-copy-summary')
        await expect(summary).toBeVisible()
        await page.keyboard.press('ArrowDown')
        for (let i = 0; i < 8 && !(await summary.evaluate((el) => el === document.activeElement)); i++) await page.keyboard.press('ArrowDown')
        await expect(summary).toBeFocused()
        await page.keyboard.press('Enter')
        const options = page.getByRole('option')
        await expect(options).toHaveCount(3)
        await expect(options.nth(0).locator('.detail-copy-best')).toHaveText('Best for this device')
        await expect(options.nth(2)).toContainText('May stutter on this TV')
        if (process.env.WB_SHOTS) await page.screenshot({ path: new URL('tv-1920.png', shotsDir).pathname })
        for (let i = 0; i < 4 && !(await options.nth(1).evaluate((el) => el === document.activeElement)); i++) await page.keyboard.press('ArrowDown')
        await expect(options.nth(1)).toBeFocused()
        await page.keyboard.press('Enter')
        await expect(page).toHaveURL(/\/video\/-1005\/27$/)
        await expect(page.locator('.detail-copy-summary')).toBeFocused()
    })
})
