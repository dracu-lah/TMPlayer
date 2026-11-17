// A trailer plays in YouTube's own embedded player in a sheet over the video's page (ui/TrailerSheet.tsx,
// private/TRAILERS-PLAN.md): nothing from Google loads before the tap, the sheet takes the focus and
// gives it back, an error or a silent player falls back to the link, and the setting off keeps the link.
// No real network: TMDB and the embed (youtube-nocookie.com) are fakes; the embed's page posts what
// YouTube's posts, `onReady` and `onError`.
import { readFileSync } from 'node:fs'
import { expect, test, type Page } from '@playwright/test'
import { noSizeFloor } from './helpers'

const PICTURE = readFileSync(new URL('./media/thumb.jpg', import.meta.url))
const KEY = 'aqz-KE-bpKQ'

const FILM = { match: true, id: '10378', kind: 'film', title: 'Big Buck Bunny', year: 2008, poster: '/p.jpg', backdrop: '/b.jpg', overview: 'A gentle giant of a rabbit wakes to a perfect spring morning.' }
const extras = (trailer: string | null) => ({
    found: true, genres: ['Animation', 'Comedy'], runtime: 10, seasons: null, rating: 6.5, certification: 'G', directors: [], creators: [],
    cast: [], trailer, ended: false, imdb: 'tt1254207',
})

/** What the fake embed page posts to the page that holds it, as YouTube's does (a JSON string). */
const POSTS = {
    ready: `parent.postMessage(JSON.stringify({ event: 'onReady' }), '*'); setTimeout(() => parent.postMessage(JSON.stringify({ event: 'onStateChange', info: 1 }), '*'), 30)`,
    error: `parent.postMessage(JSON.stringify({ event: 'onError', info: 150 }), '*')`,
    silent: ``,
}

/** TMDB and the embed, faked. Returns every request that went to Google, to show when it went. */
async function fakes(page: Page, embed: keyof typeof POSTS, trailer: string | null = KEY) {
    const google: string[] = []
    page.on('request', (r) => {
        if (/youtube|google|ytimg|gstatic/.test(new URL(r.url()).hostname)) google.push(r.url())
    })
    await page.route('**/tmdb/**', (route) => {
        const url = new URL(route.request().url())
        if (url.pathname === '/tmdb/poster') return route.fulfill({ json: FILM })
        if (url.pathname === '/tmdb/extras') return route.fulfill({ json: extras(trailer) })
        return route.fulfill({ contentType: 'image/jpeg', body: PICTURE })
    })
    await page.route('https://www.youtube-nocookie.com/**', (route) =>
        route.fulfill({ contentType: 'text/html', body: `<!doctype html><title>fake embed</title><body style="background:#123">fake trailer<script>${POSTS[embed]}</script>` }),
    )
    return google
}

const trailerButton = (page: Page) => page.getByRole('button', { name: 'Trailer', exact: true })
const sheet = (page: Page) => page.getByRole('dialog', { name: 'Trailer' })

async function openPage(page: Page) {
    await page.goto('/video/-1001/1')
    await expect(page.locator('.detail-title')).toHaveText('Big Buck Bunny')
}

test.describe('a trailer in the app', () => {
    test.use({ viewport: { width: 1280, height: 800 }, hasTouch: false, isMobile: false })
    test.beforeEach(async ({ page }) => {
        await noSizeFloor(page)
    })

    test('nothing from Google loads before the tap, then the embed loads with the key and plays on the tap', async ({ page }) => {
        const google = await fakes(page, 'ready')
        await openPage(page)
        await expect(trailerButton(page)).toBeVisible()
        // The page is fully up, the button there: still nothing asked of Google, no frame in the page.
        await page.waitForTimeout(500)
        expect(google).toEqual([])
        await expect(page.locator('iframe')).toHaveCount(0)

        await trailerButton(page).click()
        await expect(sheet(page)).toBeVisible()
        const frame = page.locator('iframe[title="Trailer"]')
        await expect(frame).toHaveCount(1)
        const src = (await frame.getAttribute('src'))!
        expect(src.startsWith(`https://www.youtube-nocookie.com/embed/${KEY}?`)).toBe(true)
        expect(src).toContain('autoplay=1')
        expect(src).toContain('enablejsapi=1')
        await expect(sheet(page).getByText(/loads from YouTube only after you open it/)).toBeVisible()
        // The player said ready: the loading line goes and the player stays; no fallback.
        await expect(sheet(page).getByText('Loading the trailer')).toHaveCount(0)
        await expect(frame).toBeVisible()
        await expect(sheet(page).getByRole('link', { name: 'Watch on YouTube' })).toHaveCount(0)
        // Only the privacy enhanced host was asked, nothing from youtube.com itself.
        expect(google.length).toBeGreaterThan(0)
        for (const url of google) expect(new URL(url).hostname).toBe('www.youtube-nocookie.com')
    })

    test('the focus goes into the sheet, Escape closes it and the focus is back on the trailer button', async ({ page }) => {
        await fakes(page, 'ready')
        await openPage(page)
        await trailerButton(page).focus()
        await page.keyboard.press('Enter')
        await expect(sheet(page)).toBeVisible()
        await expect(sheet(page).getByRole('button', { name: 'Close trailer' })).toBeFocused()
        // The sheet is the whole page for the keyboard: Tab goes through it (Close, the player) and out to the browser, never to the page behind.
        for (let i = 0; i < 4; i++) {
            await page.keyboard.press('Tab')
            expect(await page.evaluate(() => document.activeElement === document.body || !!document.activeElement?.closest('dialog.trailer-sheet'))).toBe(true)
        }
        await page.getByRole('button', { name: 'Close trailer' }).focus()
        await page.keyboard.press('Escape')
        await expect(sheet(page)).toHaveCount(0)
        await expect(page.locator('iframe')).toHaveCount(0)
        await expect(trailerButton(page)).toBeFocused()
    })

    test('Close and a press on the dimmed page close it too, and the same trailer opens again', async ({ page }) => {
        await fakes(page, 'ready')
        await openPage(page)
        await trailerButton(page).click()
        await sheet(page).getByRole('button', { name: 'Close trailer' }).click()
        await expect(sheet(page)).toHaveCount(0)
        await expect(trailerButton(page)).toBeFocused()
        await trailerButton(page).click()
        await expect(sheet(page)).toBeVisible()
        await page.mouse.click(5, 5)
        await expect(sheet(page)).toHaveCount(0)
        await expect(trailerButton(page)).toBeFocused()
        await trailerButton(page).click()
        await expect(page.locator('iframe[title="Trailer"]')).toHaveCount(1)
    })

    test('an error from the player falls back to the message and the link to YouTube', async ({ page }) => {
        await fakes(page, 'error')
        await openPage(page)
        await trailerButton(page).click()
        const link = sheet(page).getByRole('link', { name: 'Watch on YouTube' })
        await expect(link).toBeVisible()
        await expect(sheet(page).getByText("This trailer can't play inside the app")).toBeVisible()
        await expect(link).toHaveAttribute('href', `https://www.youtube.com/watch?v=${KEY}`)
        await expect(link).toHaveAttribute('target', '_blank')
        await expect(page.locator('iframe')).toHaveCount(0)
        await expect(link).toBeFocused()
        // Close still works from the fallback.
        await page.keyboard.press('Escape')
        await expect(sheet(page)).toHaveCount(0)
        await expect(trailerButton(page)).toBeFocused()
    })

    test('a player that never says ready falls back after twelve seconds', async ({ page }) => {
        await page.clock.install()
        await fakes(page, 'silent')
        await openPage(page)
        await trailerButton(page).click()
        await expect(sheet(page).getByText('Loading the trailer')).toBeVisible()
        await page.clock.fastForward(11_000)
        await expect(sheet(page).getByRole('link', { name: 'Watch on YouTube' })).toHaveCount(0)
        await page.clock.fastForward(1_500)
        await expect(sheet(page).getByRole('link', { name: 'Watch on YouTube' })).toBeVisible()
        await expect(sheet(page).getByText("This trailer can't play inside the app")).toBeVisible()
    })

    test('with Play trailers in TMPlayer off the trailer is the link to YouTube and nothing is embedded', async ({ page }) => {
        const google = await fakes(page, 'ready')
        await page.goto('/settings/library')
        const setting = page.getByRole('switch', { name: /^Play trailers in TMPlayer/ })
        await expect(setting).toBeChecked()
        await setting.click()
        await expect(setting).not.toBeChecked()
        await openPage(page)
        const link = page.getByRole('link', { name: 'Trailer', exact: true })
        await expect(link).toHaveAttribute('href', `https://www.youtube.com/watch?v=${KEY}`)
        await expect(link).toHaveAttribute('target', '_blank')
        await expect(trailerButton(page)).toHaveCount(0)
        await expect(page.locator('dialog.trailer-sheet[open]')).toHaveCount(0)
        expect(google).toEqual([])
    })

    test('a key that is not a YouTube id is never offered', async ({ page }) => {
        const google = await fakes(page, 'ready', 'x"><script>1')
        await openPage(page)
        await expect(page.locator('.detail-round', { hasText: 'Watch later' })).toBeVisible()
        await expect(page.locator('.detail-round', { hasText: 'Trailer' })).toHaveCount(0)
        expect(google).toEqual([])
    })
})

test.describe('on a phone', () => {
    test.use({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true })
    test.beforeEach(async ({ page }) => {
        await noSizeFloor(page)
    })

    for (const [name, embed] of [['the player', 'ready'], ['the fallback', 'error']] as const) {
        test(`${name} fits 390 px wide, also at twice the type size, with 48 px targets`, async ({ page }) => {
            await fakes(page, embed)
            await openPage(page)
            await trailerButton(page).tap()
            await expect(sheet(page)).toBeVisible()
            if (embed === 'error') await expect(sheet(page).getByRole('link', { name: 'Watch on YouTube' })).toBeVisible()
            else await expect(page.locator('iframe[title="Trailer"]')).toBeVisible()

            for (const doubled of [false, true]) {
                if (doubled) {
                    await page.addStyleTag({ content: '.trailer-sheet h2 { font-size: 48px !important; line-height: 56px !important } .trailer-sheet p, .trailer-sheet a, .trailer-sheet button { font-size: 28px !important; line-height: 36px !important }' })
                }
                const box = (await sheet(page).boundingBox())!
                expect(box.x).toBeGreaterThanOrEqual(0)
                expect(box.x + box.width).toBeLessThanOrEqual(390)
                expect(box.y).toBeGreaterThanOrEqual(0)
                expect(box.y + box.height).toBeLessThanOrEqual(844)
                // Nothing pushes the sheet sideways, and the page behind it has no sideways scroll.
                expect(await sheet(page).evaluate((el) => el.scrollWidth <= el.clientWidth)).toBe(true)
                expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
                if (embed === 'ready') {
                    const frame = (await page.locator('iframe[title="Trailer"]').boundingBox())!
                    expect(frame.width).toBeLessThanOrEqual(390 - 32)
                    expect(Math.abs(frame.width / frame.height - 16 / 9)).toBeLessThan(0.05)
                }
                const close = sheet(page).getByRole('button', { name: 'Close trailer' })
                await close.scrollIntoViewIfNeeded()
                const target = (await close.boundingBox())!
                expect(target.height).toBeGreaterThanOrEqual(48)
                expect(target.width).toBeGreaterThanOrEqual(48)
                expect(target.x).toBeGreaterThanOrEqual(0)
                expect(target.x + target.width).toBeLessThanOrEqual(390)
                if (embed === 'error') {
                    const link = (await sheet(page).getByRole('link', { name: 'Watch on YouTube' }).boundingBox())!
                    expect(link.height).toBeGreaterThanOrEqual(48)
                    expect(link.x + link.width).toBeLessThanOrEqual(390)
                }
            }
            await sheet(page).getByRole('button', { name: 'Close trailer' }).tap()
            await expect(sheet(page)).toHaveCount(0)
        })
    }
})
