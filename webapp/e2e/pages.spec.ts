// Session 19b: Settings as pages, a chat's info, "As rows", Continue watching's landscape cards, sign in's
// centring and the video page's TMDB words and copies, phone and desktop.
import { readFileSync } from 'node:fs'
import { expect, test, type Page } from '@playwright/test'
import { fakeCase, noSizeFloor } from './helpers'

const PICTURE = readFileSync(new URL('./media/thumb.jpg', import.meta.url))

const FILM = {
    match: true, id: '10378', kind: 'film', title: 'Big Buck Bunny', year: 2008, poster: '/bigbuckposter01.jpg', backdrop: '/bigbuckbackdrop1.jpg',
    overview: "A gentle giant of a rabbit wakes to a perfect spring morning. Patience runs out, and the big rabbit sets out to teach them a lesson.",
}
const EXTRAS = {
    found: true, genres: ['Animation', 'Comedy'], runtime: 10, seasons: null, rating: 6.5, certification: 'G', directors: ['Sacha Goedegebure'], creators: [],
    cast: [{ name: 'Jan Cornet', role: 'Frank', photo: null }, { name: 'Tony Economou', role: 'Rinky', photo: null }], trailer: 'aqz-KE-bpKQ', ended: false, imdb: 'tt1254207',
}

/** TMDB as the worker answers it, for every title. */
async function mockTmdb(page: Page, extras: object | number = EXTRAS) {
    const asked: string[] = []
    await page.route('**/tmdb/**', (route) => {
        const url = new URL(route.request().url())
        asked.push(url.pathname)
        if (url.pathname === '/tmdb/poster') return route.fulfill({ json: FILM })
        if (url.pathname === '/tmdb/extras') return typeof extras === 'number' ? route.fulfill({ status: extras, body: '{"error":"unavailable"}' }) : route.fulfill({ json: extras })
        return route.fulfill({ contentType: 'image/jpeg', body: PICTURE })
    })
    return asked
}

/** The form a test runs in, set before the page starts. */
async function inForm(page: Page, form: 'phone' | 'desktop') {
    await page.addInitScript((f) => localStorage.setItem('tmplayer-layout', f), form)
}

/** Where videos were left, written where the app keeps them. */
async function leave(page: Page, resumes: [number, number, number, number][]) {
    await noSizeFloor(page)
    await page.evaluate(
        (rs) =>
            new Promise<void>((resolve, reject) => {
                const open = indexedDB.open('tmplayer-app')
                open.onerror = () => reject(open.error)
                open.onsuccess = () => {
                    const tx = open.result.transaction('kv', 'readwrite')
                    for (const [chatId, messageId, position, length] of rs) tx.objectStore('kv').put({ chatId, messageId, position, length, time: Date.now() }, `resume:${chatId}:${messageId}`)
                    tx.oncomplete = () => (open.result.close(), resolve())
                }
            }),
        resumes,
    )
}

test.describe('Settings as pages', () => {
    test('a desktop shows the list beside the page, which its address names', async ({ page }) => {
        await inForm(page, 'desktop')
        await page.goto('/settings')
        await expect(page).toHaveURL(/\/settings\/appearance$/)
        const list = page.getByRole('navigation', { name: 'Settings' })
        await expect(list.getByRole('link')).toHaveCount(5)
        await expect(page.getByRole('radio', { name: 'Dark' })).toBeVisible()
        await expect(list.getByRole('link', { name: /^Appearance/ })).toHaveAttribute('aria-current', 'page')
        await list.getByRole('link', { name: /^Library/ }).click()
        await expect(page).toHaveURL(/\/settings\/library$/)
        await expect(page.getByRole('switch', { name: /^Carousel on Home/ })).toBeVisible()
        await expect(page.getByRole('radio', { name: 'Dark' })).toHaveCount(0)
        // The account and Sign out stay beside every page.
        await expect(page.getByText('@danatest')).toBeVisible()
        await expect(page.getByRole('button', { name: 'Sign out of Telegram' })).toBeVisible()
    })

    test('a phone takes the list and a page in turn, and Back from a page is the list', async ({ page }) => {
        await inForm(page, 'phone')
        await page.setViewportSize({ width: 393, height: 852 })
        await page.goto('/settings')
        await expect(page).toHaveURL(/\/settings$/)
        await expect(page.getByRole('radio', { name: 'Dark' })).toHaveCount(0)
        await page.getByRole('link', { name: /^Appearance/ }).click()
        await expect(page).toHaveURL(/\/settings\/appearance$/)
        await expect(page.getByRole('radio', { name: 'Dark' })).toBeVisible()
        await expect(page.getByRole('link', { name: /^Playback/ })).toHaveCount(0)
        await page.getByRole('button', { name: 'Back' }).click()
        await expect(page).toHaveURL(/\/settings$/)
        await expect(page.getByRole('link', { name: /^Playback/ })).toBeVisible()
    })

    test('the carousel switch is a switch, and stays as it was left after a reload', async ({ page }) => {
        await inForm(page, 'desktop')
        await page.goto('/settings/library')
        const carousel = page.getByRole('switch', { name: /^Carousel on Home/ })
        await expect(carousel).toHaveAttribute('aria-checked', 'true')
        await carousel.click()
        await expect(carousel).toHaveAttribute('aria-checked', 'false')
        await page.reload()
        await expect(carousel).toHaveAttribute('aria-checked', 'false')
    })

    test('changing the layout on a page keeps the viewer on it', async ({ page }) => {
        await inForm(page, 'desktop')
        await page.goto('/settings')
        await page.getByRole('radio', { name: 'Phone' }).click({ force: true })
        await expect(page.locator('html')).toHaveAttribute('data-form', 'phone')
        await expect(page).toHaveURL(/\/settings\/appearance$/)
        await expect(page.getByRole('radio', { name: 'Desktop' })).toBeVisible()
    })
})

test.describe('a chat', () => {
    test.beforeEach(async ({ page }) => {
        await noSizeFloor(page)
    })

    test('its Info button opens the info page with the kind, members, username, description and link', async ({ page }) => {
        await page.goto('/chat/-1001')
        await page.getByRole('link', { name: 'Chat info' }).click()
        await expect(page).toHaveURL(/\/chat\/-1001\/info$/)
        await expect(page.getByRole('heading', { name: 'Test Clips' })).toBeVisible()
        await expect(page.getByText('Channels  ·  12,400 subscribers'.replace(/ {2}/g, ' '))).toBeVisible()
        await expect(page.getByRole('button', { name: '@testclips' })).toBeVisible()
        await expect(page.getByText('Short clips for trying the player')).toBeVisible()
        await expect(page.getByRole('button', { name: 'Copy link' })).toBeVisible()
        await expect(page.getByText('t.me/testclips')).toBeVisible()
        await page.getByRole('button', { name: 'Back' }).click()
        await expect(page).toHaveURL(/\/chat\/-1001$/)
    })

    test('details that did not load are said so, with Try again, under the name the list had', async ({ page, context }) => {
        await fakeCase(context, 'infofail')
        await page.goto('/chat/-1001')
        await page.getByRole('link', { name: 'Chat info' }).click()
        await expect(page.getByRole('heading', { name: 'Test Clips' })).toBeVisible()
        await expect(page.getByText("This chat's details did not load.")).toBeVisible()
        await expect(page.getByRole('button', { name: 'Try again' })).toBeVisible()
    })

    test('As rows swaps the tiles for rows, keeps its place across a reload, and As tiles brings them back', async ({ page }) => {
        await inForm(page, 'phone')
        await page.setViewportSize({ width: 393, height: 852 })
        await page.goto('/chat/-1001')
        await expect(page.locator('.card').first()).toBeVisible()
        await page.getByTitle('Show as rows').click()
        await expect(page.locator('.row-card').first()).toBeVisible()
        await expect(page.locator('.card')).toHaveCount(0)
        // A phone's row is a 56 wide poster beside the name, in one column.
        const box = await page.locator('.row-art').first().boundingBox()
        expect(Math.round(box!.width)).toBe(56)
        await page.reload()
        await expect(page.locator('.row-card').first()).toBeVisible()
        await page.getByTitle('Show as tiles').click()
        await expect(page.locator('.card').first()).toBeVisible()
        await expect(page.locator('.row-card')).toHaveCount(0)
    })

    test("the chat's bar has each form's order: a phone Favourite, As rows, Search, Sort; a desktop Search, Favourite, Sort, As rows", async ({ page }) => {
        const labels = page.locator('.chat-bar .nav-label')
        await inForm(page, 'phone')
        await page.goto('/chat/-1001')
        await expect(labels).toHaveText(['Favourite', 'As rows', 'Search', 'Sort'])
        await page.addInitScript(() => localStorage.setItem('tmplayer-layout', 'desktop'))
        await page.reload()
        await expect(labels).toHaveText(['Search', 'Favourite', 'Sort', 'As rows'])
    })
})

test.describe('Continue watching', () => {
    test('Home and History show landscape cards with how long is left, a pill of progress and the card name', async ({ page }) => {
        await inForm(page, 'desktop')
        await leave(page, [[-1002, 16, 600, 1700]])
        await page.goto('/')
        const card = page.locator('.continue-card').first()
        await expect(card).toBeVisible()
        await expect(card.locator('.tile-badge')).toHaveText('18m left')
        await expect(card.locator('.pill-progress')).toBeVisible()
        // The name is the apps' continueCardTitle: the episode, then the show.
        await expect(card.locator('.continue-name')).toHaveText('S1 E1  ·  Harbour Notes'.replace(/ {2}/g, ' '))
        const art = await card.locator('.continue-art').boundingBox()
        expect(Math.round((art!.width / art!.height) * 100)).toBe(178)
        // It plays at once.
        await expect(card).toHaveAttribute('href', /\/watch\/-1002\/16$/)
        await page.goto('/history/continue')
        await expect(page.locator('.continue-card')).toHaveCount(1)
        await expect(page.getByRole('heading', { name: 'Today' })).toBeVisible()
        // The other tabs keep their portrait cards.
        await page.goto('/history/watched')
        await expect(page.locator('.continue-card')).toHaveCount(0)
    })
})

test.describe('sign in', () => {
    test.beforeEach(async ({ context }) => {
        await fakeCase(context, 'signedout')
    })

    test('a desktop centres one column under the mark and the name', async ({ page }) => {
        await inForm(page, 'desktop')
        await page.goto('/signin')
        const brand = page.locator('.signin-brand')
        await expect(brand).toContainText('TMPlayer')
        const box = await brand.boundingBox()
        const width = page.viewportSize()!.width
        expect(Math.abs(box!.x + box!.width / 2 - width / 2)).toBeLessThan(4)
        expect(await page.locator('.signin h1').evaluate((h) => getComputedStyle(h).textAlign)).toBe('center')
    })

    test('a phone starts at the top left with the mark and the name, and the other way in is a button', async ({ page }) => {
        await inForm(page, 'phone')
        await page.setViewportSize({ width: 393, height: 852 })
        await page.goto('/signin')
        const box = await page.locator('.signin-brand').boundingBox()
        expect(box!.x).toBeLessThan(40)
        await expect(page.locator('.signin-word')).toHaveText('TMPlayer')
        await expect(page.getByRole('heading', { name: 'Your phone number' })).toBeVisible()
        const alt = await page.getByRole('button', { name: 'Scan a QR code instead' }).boundingBox()
        expect(alt!.width).toBeGreaterThan(300)
    })
})

test.describe('a video page', () => {
    test.beforeEach(async ({ page }) => {
        await noSizeFloor(page)
        await inForm(page, 'desktop')
    })

    test("shows TMDB's overview, genres and age rating, score, cast and a trailer that opens YouTube, and has no Download", async ({ page }) => {
        await mockTmdb(page)
        await page.goto('/video/-1001/1')
        await expect(page.locator('.detail-title')).toHaveText('Big Buck Bunny')
        await expect(page.locator('.detail-genres')).toHaveText('Animation, Comedy · G')
        await expect(page.getByRole('img', { name: 'TMDB 6.5 out of 10' })).toBeVisible()
        await expect(page.locator('.detail-synopsis')).toContainText('A gentle giant of a rabbit')
        await expect(page.getByText('Directed by Sacha Goedegebure')).toBeVisible()
        await expect(page.locator('.cast-person')).toHaveCount(2)
        const trailer = page.getByRole('link', { name: 'Trailer' })
        await expect(trailer).toHaveAttribute('href', 'https://www.youtube.com/watch?v=aqz-KE-bpKQ')
        await expect(trailer).toHaveAttribute('target', '_blank')
        // Saving a file is not a thing a browser tab does for the apps: no Download.
        await expect(page.getByText('Download', { exact: true })).toHaveCount(0)
    })

    test('with posters off nothing is asked of TMDB and the page is the file only', async ({ page }) => {
        const asked = await mockTmdb(page)
        await page.goto('/settings/library')
        await page.getByRole('switch', { name: /^Posters/ }).click()
        await page.goto('/video/-1001/1')
        await expect(page.locator('.detail-title')).toHaveText('Native Clip')
        await expect(page.locator('.detail-synopsis')).toHaveCount(0)
        await expect(page.getByRole('link', { name: 'Trailer' })).toHaveCount(0)
        expect(asked).toEqual([])
    })

    test('a failing TMDB leaves the file facts and nothing else', async ({ page }) => {
        await mockTmdb(page, 503)
        await page.goto('/video/-1001/1')
        await expect(page.locator('.detail-title')).toHaveText('Big Buck Bunny')
        await expect(page.locator('.detail-genres')).toHaveCount(0)
        await expect(page.getByRole('link', { name: 'Trailer' })).toHaveCount(0)
        await expect(page.getByRole('button', { name: 'Play' })).toBeVisible()
    })

    test('a film posted three times lists its copies, and choosing one turns the page to it', async ({ page }) => {
        await mockTmdb(page)
        await page.goto('/video/-1005/23')
        const summary = page.locator('.detail-copy-summary')
        await expect(summary).toContainText('1080p · H.264 · Blu-ray')
        await expect(summary).toContainText('3 versions')
        await summary.click()
        const options = page.getByRole('option')
        await expect(options).toHaveCount(3)
        // Best first: 4K, then 1080p, then 720p.
        await expect(options.nth(0)).toContainText('4K')
        await expect(options.nth(1)).toHaveAttribute('aria-selected', 'true')
        await options.nth(2).click()
        await expect(page).toHaveURL(/\/video\/-1005\/24$/)
        await expect(page.locator('.detail-copy-summary')).toContainText('720p')
    })
})
