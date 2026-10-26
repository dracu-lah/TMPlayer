// Posters (plan 8.2): the card asks the /tmdb worker for the app's question, draws the answer over
// its thumbnail, and keeps the thumbnail when TMDB has nothing, fails, or the Settings switch is off.
// The worker is mocked here (its own rules are tested in tmdb/*.test.ts).
import { readFileSync } from 'node:fs'
import { expect, test, type Page } from '@playwright/test'
import { noSizeFloor } from './helpers'

const PICTURE = readFileSync(new URL('./media/thumb.jpg', import.meta.url))

interface Seen {
    asks: URLSearchParams[]
    pictures: string[]
    others: string[]
}

/** Stands in for the worker: `answer` is its JSON, or a status for a failure. */
async function mockTmdb(page: Page, answer: object | number): Promise<Seen> {
    const seen: Seen = { asks: [], pictures: [], others: [] }
    await page.route('**/*', (route) => {
        const url = new URL(route.request().url())
        // WebKit also routes the page's own blob: and data: pictures; they never leave it.
        if (!url.protocol.startsWith('http')) return route.continue()
        if (url.hostname !== '127.0.0.1') {
            seen.others.push(url.hostname)
            return route.continue()
        }
        if (url.pathname === '/tmdb/poster') {
            seen.asks.push(url.searchParams)
            if (typeof answer === 'number') return route.fulfill({ status: answer, headers: { 'Retry-After': '30' }, body: '{"error":"unavailable"}' })
            return route.fulfill({ json: answer })
        }
        if (url.pathname.startsWith('/tmdb/img/')) {
            seen.pictures.push(url.pathname)
            return route.fulfill({ contentType: 'image/jpeg', body: PICTURE })
        }
        return route.continue()
    })
    return seen
}

async function openShows(page: Page) {
    await noSizeFloor(page)
    await page.goto('/chats')
    await page.getByRole('link', { name: /Test Shows/ }).first().click()
    await expect(page).toHaveURL(/\/chat\/-1002/)
}

const FOUND = { match: true, id: '77', kind: 'show', title: 'Harbour Notes', year: 2019, poster: '/harbournotes0.jpg', backdrop: null, overview: '' }

test('a show card draws the poster for the title the app asks about, and nothing else leaves the page', async ({ page }) => {
    const seen = await mockTmdb(page, FOUND)
    await openShows(page)
    const poster = page.locator('.card-poster')
    await expect(poster).toHaveCount(1)
    await expect.poll(() => poster.evaluate((img: HTMLImageElement) => img.naturalWidth)).toBeGreaterThan(0)
    // The app's question for "Harbour.Notes.S01E01.mkv": the cleaned title, a show, an episode named.
    expect(seen.asks[0].get('t')).toBe('Harbour Notes')
    expect(seen.asks[0].get('k')).toBe('show')
    expect(seen.asks[0].get('s')).toBe('1')
    // One lookup for the show's one card, however many episodes it holds, and its picture at the card's size.
    expect(seen.asks).toHaveLength(1)
    expect(seen.pictures).toEqual(['/tmdb/img/w342/harbournotes0.jpg'])
    // TMDB itself is never contacted by the page: only the worker is, so the key stays there.
    expect(seen.others).toEqual([])
    // The poster carries the title; the name stays for screen readers and as a tooltip.
    await expect(page.locator('.card-art.posted')).toHaveAttribute('title', 'Harbour Notes')
    await expect(page.locator('.card-shade')).toBeHidden()
})

test('the answer is kept: a second visit draws the poster without asking again', async ({ page, browserName }) => {
    test.skip(browserName === 'webkit', "Playwright's WebKit forgets the Cache API on every reload (a plain put and match shows it), so the kept answer cannot be seen there")
    const seen = await mockTmdb(page, FOUND)
    await openShows(page)
    await expect(page.locator('.card-poster')).toHaveCount(1)
    expect(seen.asks).toHaveLength(1)
    await page.reload()
    await expect(page.locator('.card-poster')).toHaveCount(1)
    expect(seen.asks).toHaveLength(1)
    expect(seen.pictures).toHaveLength(1)
})

test('nothing matched: the thumbnail stays, and the name stays on it', async ({ page }) => {
    const seen = await mockTmdb(page, { match: false })
    await openShows(page)
    await expect.poll(() => seen.asks.length).toBe(1)
    await expect(page.locator('.card-art-title')).toBeVisible()
    await expect(page.locator('.card-poster')).toHaveCount(0)
})

test('TMDB down or throttled: the thumbnail stays, and the page stops asking for a while', async ({ page }) => {
    const seen = await mockTmdb(page, 503)
    await openShows(page)
    await expect.poll(() => seen.asks.length).toBeGreaterThan(0)
    await expect(page.locator('.card-art-title')).toBeVisible()
    await expect(page.locator('.card-poster')).toHaveCount(0)
    await page.reload()
    await expect(page.locator('.card')).toHaveCount(1)
    await page.waitForTimeout(500)
    // A failure is never kept.
    expect(await page.evaluate(async () => (await (await caches.open('tmplayer-posters')).keys()).length)).toBe(0)
})

test('the Settings switch turns lookups off at once, and stays off after a reload', async ({ page }) => {
    const seen = await mockTmdb(page, FOUND)
    await openShows(page)
    await expect(page.locator('.card-poster')).toHaveCount(1)
    // Settings is in the "You" menu, behind the viewer's picture on the main bar (a chat has its own bar).
    await page.goto('/')
    await page.locator('[data-you]').click()
    await page.getByRole('menuitem', { name: 'Settings' }).click()
    // Settings is a list of pages; Posters is on Library.
    await page.getByRole('link', { name: /^Library/ }).click()
    await expect(page.getByText('This product uses the TMDB API but is not endorsed or certified by TMDB.')).toBeVisible()
    await expect(page.getByLabel('TMDB').first()).toBeVisible()
    const posters = page.getByRole('switch', { name: /^Posters/ })
    await expect(posters).toHaveAttribute('aria-checked', 'true')
    await posters.click()
    await expect(page.getByText('Off. Nothing is looked up online')).toBeVisible()
    const asked = seen.asks.length
    await page.reload()
    await expect(posters).toHaveAttribute('aria-checked', 'false')
    await page.goBack()
    await page.goto('/chats')
    await page.getByRole('link', { name: /Test Shows/ }).first().click()
    await expect(page.locator('.card')).toHaveCount(1)
    await expect(page.locator('.card-art-title')).toBeVisible()
    await page.waitForTimeout(500)
    expect(seen.asks).toHaveLength(asked)
    await expect(page.locator('.card-poster')).toHaveCount(0)
})
