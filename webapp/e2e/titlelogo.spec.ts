// The title logo, the card score and the opening loader's facts (HX15), over a mocked /tmdb worker:
// a placeholder while the extras are on their way, the logo once they and its file are in, the
// name where TMDB has none, a card's TMDB score only once the browser keeps the title's extras,
// and nothing new at all with posters off. Pictures go to $TMPLAYER_SHOTS when it is set.
import { readFileSync } from 'node:fs'
import { expect, test, type Page } from '@playwright/test'
import { fakeCase, noSizeFloor, openDetail } from './helpers'

test.skip(({ browserName }) => browserName !== 'chromium', 'shots and the mocked worker in Chromium')

const PICTURE = readFileSync(new URL('./media/thumb.jpg', import.meta.url))
const LOGO = readFileSync(new URL('./media/logo.png', import.meta.url))
const FOUND = { match: true, id: '77', kind: 'show', title: 'Harbour Notes', year: 2019, poster: '/harbournotes0.jpg', backdrop: '/harbourwide00.jpg', overview: 'A harbour town keeps its notes.' }
const EXTRAS = { found: true, genres: ['Drama', 'Mystery', 'Crime'], runtime: 42, seasons: 2, rating: 7.4, certification: 'TV-14', directors: [], creators: [], cast: [], trailer: null, ended: false, imdb: null }

/** Stands in for the worker. `logo` names the title's logo file or null; `hold` keeps the extras back until released. */
async function mockTmdb(page: Page, logo: string | null, hold?: Promise<void>) {
    await page.route('**/*', async (route) => {
        const url = new URL(route.request().url())
        if (!url.protocol.startsWith('http') || url.hostname !== '127.0.0.1') return route.continue()
        if (url.pathname === '/tmdb/poster') return route.fulfill({ json: FOUND })
        if (url.pathname === '/tmdb/extras') {
            await hold
            return route.fulfill({ json: { ...EXTRAS, logo } })
        }
        if (url.pathname.endsWith('.png')) return route.fulfill({ contentType: 'image/png', body: LOGO })
        if (url.pathname.startsWith('/tmdb/img/')) return route.fulfill({ contentType: 'image/jpeg', body: PICTURE })
        return route.continue()
    })
}

async function shot(page: Page, name: string, project: string) {
    const dir = process.env.TMPLAYER_SHOTS
    if (dir) await page.screenshot({ path: `${dir}/web-${project}-${name}.png` })
}

async function openShow(page: Page) {
    await noSizeFloor(page)
    await page.goto('/chat/-1002')
    await openDetail(page, 'Harbour Notes')
}

test('the page shows a placeholder, then the logo, read out as the name', async ({ page }, info) => {
    let release!: () => void
    await mockTmdb(page, '/harbourlogo0.png', new Promise<void>((go) => (release = go)))
    await openShow(page)
    const title = page.locator('h1.detail-title')
    await expect(title.locator('.title-logo-skeleton')).toBeVisible()
    await shot(page, 'detail-loading', info.project.name)
    release()
    await expect(title.locator('img.title-logo')).toBeVisible()
    await expect(title).toHaveAttribute('aria-label', 'Harbour Notes')
    await expect(title.locator('.title-logo-skeleton')).toHaveCount(0)
    await page.waitForTimeout(300)
    await shot(page, 'detail-logo', info.project.name)
})

test('no logo at TMDB: the name, and the placeholder never lingers past the answer', async ({ page }, info) => {
    await mockTmdb(page, null)
    await openShow(page)
    const title = page.locator('h1.detail-title')
    await expect(title).toHaveText('Harbour Notes')
    await expect(title.locator('.title-logo-skeleton')).toHaveCount(0)
    await shot(page, 'detail-nologo', info.project.name)
})

test('a card wears the TMDB score once the title is kept, and the light theme inks a white logo', async ({ page }, info) => {
    await mockTmdb(page, '/harbourlogo0.png')
    await openShow(page)
    await expect(page.locator('h1.detail-title img.title-logo')).toBeVisible()
    await page.goBack()
    const pill = page.locator('.card-score')
    await expect(pill.first()).toBeVisible()
    await expect(pill.first()).toContainText('7.4')
    await shot(page, 'cards-score', info.project.name)
    await page.evaluate(() => document.documentElement.setAttribute('data-theme', 'light'))
    await openDetail(page, 'Harbour Notes')
    await expect(page.locator('img.title-logo.bright')).toBeVisible()
    await page.waitForTimeout(300)
    await shot(page, 'detail-logo-light', info.project.name)
})

test('a touchpad swipe across the carousel turns one page and never goes Back', async ({ page, context }) => {
    await fakeCase(context, 'big')
    // Every title matched, with a wide picture, so Recently added fills the carousel.
    await mockTmdb(page, '/harbourlogo0.png')
    await noSizeFloor(page)
    await page.goto('/')
    const hero = page.locator('section.hero')
    await expect(hero).toBeVisible()
    const dots = hero.locator('.hero-dots button')
    await expect(dots.nth(1)).toBeVisible()
    await expect(dots.nth(0)).toHaveAttribute('aria-selected', 'true')
    await shot(page, 'home-carousel', test.info().project.name)
    const url = page.url()
    // One gesture: many small sideways wheel steps, as a two finger swipe sends them.
    await hero.evaluate(async (el) => {
        for (let i = 0; i < 12; i++) {
            el.dispatchEvent(new WheelEvent('wheel', { deltaX: 18, deltaY: 1, bubbles: true, cancelable: true }))
            await new Promise((r) => setTimeout(r, 16))
        }
    })
    await expect(dots.nth(1)).toHaveAttribute('aria-selected', 'true')
    expect(page.url()).toBe(url)
    // The browser's own handling (Back) is held off.
    const cancelled = await hero.evaluate((el) => !el.dispatchEvent(new WheelEvent('wheel', { deltaX: 30, deltaY: 0, bubbles: true, cancelable: true })))
    expect(cancelled).toBe(true)
})
