// Home's carousel and brand on the desktop and the phone (HX65, HX60, HX55; decision D1): no arrow
// buttons on the desktop (pages turn by key, dot, drag and swipe), the phone keeps its arrows, the
// brand is sized from one token set (32 px mark and 22 px name on the desktop, as BrandSize.Desktop),
// and the words, score, Play and Details stay inside the frame from 1280x800 to 3840x2160 and at a
// browser zoom of 150 and 200. Pictures go to $TMPLAYER_SHOTS when it is set.
import { readFileSync } from 'node:fs'
import { expect, test, type Page } from '@playwright/test'
import { fakeCase, noSizeFloor } from './helpers'

test.skip(({ browserName }) => browserName !== 'chromium', 'shots and the mocked worker in Chromium')

const PICTURE = readFileSync(new URL('./media/thumb.jpg', import.meta.url))
const LOGO = readFileSync(new URL('./media/logo.png', import.meta.url))
const FOUND = {
    match: true,
    id: '77',
    kind: 'show',
    title: 'Harbour Notes',
    year: 2019,
    poster: '/harbournotes0.jpg',
    backdrop: '/harbourwide00.jpg',
    overview: 'A harbour town keeps its notes, and every winter a stranger comes to read them aloud in the square, one page at a time, until nobody can say whose they were.',
}
const EXTRAS = { found: true, genres: ['Drama', 'Mystery', 'Crime'], runtime: 42, seasons: 2, rating: 7.4, certification: 'TV-14', directors: [], creators: [], cast: [], trailer: null, ended: false, imdb: null }

/** Stands in for the worker: every title matched with a wide picture, a score and a logo (or none). */
async function mockTmdb(page: Page, logo: string | null) {
    await page.route('**/*', async (route) => {
        const url = new URL(route.request().url())
        if (!url.protocol.startsWith('http') || url.hostname !== '127.0.0.1') return route.continue()
        if (url.pathname === '/tmdb/poster') return route.fulfill({ json: FOUND })
        if (url.pathname === '/tmdb/extras') return route.fulfill({ json: { ...EXTRAS, logo } })
        if (url.pathname.endsWith('.png')) return route.fulfill({ contentType: 'image/png', body: LOGO })
        if (url.pathname.startsWith('/tmdb/img/')) return route.fulfill({ contentType: 'image/jpeg', body: PICTURE })
        return route.continue()
    })
}

async function shot(page: Page, name: string) {
    const dir = process.env.TMPLAYER_SHOTS
    if (dir) await page.screenshot({ path: `${dir}/web-hero-${name}.png` })
}

async function openHome(page: Page, logo: string | null = '/harbourlogo0.png') {
    await mockTmdb(page, logo)
    await noSizeFloor(page)
    await page.goto('/')
    const hero = page.locator('section.hero')
    await expect(hero).toBeVisible()
    await expect(hero.locator('.hero-dots button').nth(1)).toBeVisible()
    return hero
}

const formOf = (page: Page) => page.evaluate(() => document.documentElement.dataset.form)

/** The page's words, once the logo or name and the facts are in. */
async function settled(page: Page, logo = true) {
    const words = page.locator('section.hero .hero-text:not(.leaving)').first()
    await expect(words.locator('.hero-score')).toBeVisible()
    await expect(words.locator('.hero-overview')).toBeVisible()
    if (logo) await expect(words.locator('img.title-logo')).toBeVisible()
    await page.waitForTimeout(700)
    return words
}

test('the brand is sized from one set of tokens: 32 px mark and 22 px name on the desktop, the phone as it was', async ({ page, context }) => {
    await fakeCase(context, 'big')
    await openHome(page)
    const desktop = (await formOf(page)) === 'desktop'
    const mark = await page.locator('.home-mark').boundingBox()
    const name = await page.locator('.home-brand').evaluate((el) => getComputedStyle(el).fontSize)
    expect(Math.round(mark!.width)).toBe(desktop ? 32 : 28)
    expect(Math.round(mark!.height)).toBe(desktop ? 32 : 28)
    expect(name).toBe(desktop ? '22px' : '18px')
    expect(await page.locator('.home-title').evaluate((el) => getComputedStyle(el).columnGap)).toBe(desktop ? '12px' : '10px')
    // The page is still called Home for a screen reader.
    await expect(page.getByRole('heading', { level: 1, name: /Home/ })).toBeAttached()
})

test('the desktop carousel has no arrow buttons: pages turn by key, dot, drag and swipe', async ({ page, context }) => {
    test.skip(!test.info().project.name.match(/chromium|desktop/), 'the desktop form')
    await fakeCase(context, 'big')
    const hero = await openHome(page)
    expect(await formOf(page)).toBe('desktop')
    const dots = hero.locator('.hero-dots button')
    const n = await dots.count()
    expect(n).toBeGreaterThan(1)
    await hero.hover()
    await hero.focus()
    // Not hidden, not hovered in: gone.
    await expect(hero.locator('.hero-arrow')).toHaveCount(0)
    await expect(hero.getByRole('button', { name: /^Scroll (on|back)$/ })).toHaveCount(0)
    // A dot is the mouse's way, and is big enough to hit (at least 8 px with a larger target round it).
    const on = await dots.nth(0).boundingBox()
    expect(on!.height).toBeGreaterThanOrEqual(8)
    await dots.nth(2).click()
    await expect(dots.nth(2)).toHaveAttribute('aria-selected', 'true')
    // The keys, both ways, and a wrap round.
    await hero.focus()
    await page.keyboard.press('ArrowLeft')
    await expect(dots.nth(1)).toHaveAttribute('aria-selected', 'true')
    await page.keyboard.press('ArrowLeft')
    await page.keyboard.press('ArrowLeft')
    await expect(dots.nth(n - 1)).toHaveAttribute('aria-selected', 'true')
    await page.keyboard.press('ArrowRight')
    await expect(dots.nth(0)).toHaveAttribute('aria-selected', 'true')
    // A mouse drag to the left turns on, to the right turns back.
    const box = (await hero.boundingBox())!
    const y = box.y + box.height * 0.3
    await page.mouse.move(box.x + box.width * 0.7, y)
    await page.mouse.down()
    await page.mouse.move(box.x + box.width * 0.4, y, { steps: 6 })
    await page.mouse.up()
    await expect(dots.nth(1)).toHaveAttribute('aria-selected', 'true')
    await page.mouse.move(box.x + box.width * 0.4, y)
    await page.mouse.down()
    await page.mouse.move(box.x + box.width * 0.7, y, { steps: 6 })
    await page.mouse.up()
    await expect(dots.nth(0)).toHaveAttribute('aria-selected', 'true')
    // A touchpad's sideways swipe turns one page (the Back gesture held off).
    await hero.evaluate(async (el) => {
        for (let i = 0; i < 10; i++) {
            el.dispatchEvent(new WheelEvent('wheel', { deltaX: 20, deltaY: 0, bubbles: true, cancelable: true }))
            await new Promise((r) => setTimeout(r, 16))
        }
    })
    await expect(dots.nth(1)).toHaveAttribute('aria-selected', 'true')
})

test('a right to left page turns the other way by key and keeps its dots', async ({ page, context }) => {
    test.skip(!test.info().project.name.match(/chromium|desktop/), 'the desktop form')
    await fakeCase(context, 'big')
    const hero = await openHome(page)
    await page.evaluate(() => (document.documentElement.dir = 'rtl'))
    const dots = hero.locator('.hero-dots button')
    await hero.focus()
    await page.keyboard.press('ArrowLeft')
    await expect(dots.nth(1)).toHaveAttribute('aria-selected', 'true')
    await page.keyboard.press('ArrowRight')
    await expect(dots.nth(0)).toHaveAttribute('aria-selected', 'true')
    await expect(hero.locator('.hero-arrow')).toHaveCount(0)
})

test('the phone keeps its arrows beside the dots', async ({ page, context }) => {
    test.skip(!test.info().project.name.startsWith('phone'), 'the phone form')
    await fakeCase(context, 'big')
    const hero = await openHome(page)
    expect(await formOf(page)).toBe('phone')
    await expect(hero.locator('.hero-arrow')).toHaveCount(2)
    await expect(hero.getByRole('button', { name: 'Scroll on' })).toBeHidden()
    await hero.focus()
    await expect(hero.getByRole('button', { name: 'Scroll on' })).toBeVisible()
    await hero.getByRole('button', { name: 'Scroll on' }).click()
    await expect(hero.locator('.hero-dots button').nth(1)).toHaveAttribute('aria-selected', 'true')
    await shot(page, 'phone')
})

/** Every word and button of the page is inside the carousel's frame and below the brand over it. */
async function expectInside(page: Page, what: string) {
    const frame = (await page.locator('section.hero').boundingBox())!
    const title = (await page.locator('.home-title').boundingBox())!
    const parts = ['.hero-title', '.hero-facts', '.hero-score', '.hero-overview', '.hero-play', '.hero-info']
    const words = page.locator('section.hero .hero-text:not(.leaving)').first()
    const viewport = page.viewportSize()!
    for (const part of parts) {
        const b = await words.locator(part).first().boundingBox()
        expect(b, `${what}: ${part} drawn`).not.toBeNull()
        expect(b!.y, `${what}: ${part} top inside the frame`).toBeGreaterThanOrEqual(frame.y - 0.5)
        expect(b!.y + b!.height, `${what}: ${part} bottom inside the frame`).toBeLessThanOrEqual(frame.y + frame.height + 0.5)
        expect(b!.y + b!.height, `${what}: ${part} bottom on screen`).toBeLessThanOrEqual(viewport.height + 0.5)
        expect(b!.x, `${what}: ${part} start inside the frame`).toBeGreaterThanOrEqual(frame.x - 0.5)
        expect(b!.x + b!.width, `${what}: ${part} end inside the frame`).toBeLessThanOrEqual(frame.x + frame.width + 0.5)
        // Buttons and words are not tight under the frame's foot.
        expect(b!.y + b!.height, `${what}: ${part} has room under it`).toBeLessThanOrEqual(frame.y + frame.height - 8)
        // The brand over the picture is never covered by the words.
        expect(b!.y, `${what}: ${part} clear of the brand`).toBeGreaterThanOrEqual(title.y + title.height - 0.5)
    }
}

// Sizes of a computer's window, and the same windows at a browser zoom of 150 and 200 (the page
// then sees a window of 1/1.5 and 1/2 the size at twice the pixels).
const SIZES: [number, number, number][] = [
    [1280, 800, 1],
    [1920, 1080, 1],
    [2560, 1440, 1],
    [3840, 2160, 1],
    [1280, 800, 1.5],
    [1280, 800, 2],
    [1920, 1080, 2],
    // A laptop's browser area (1366x768 screen minus the browser's chrome), a short wide window and a narrow tall one.
    [1366, 650, 1],
    [1366, 768, 1],
    [1440, 700, 1],
    [900, 900, 1],
    [951, 700, 1],
]

for (const [w, h, zoom] of SIZES) {
    test(`the desktop carousel keeps its words inside the frame at ${w}x${h}${zoom > 1 ? ` and zoom ${zoom * 100}` : ''}`, async ({ page, context }) => {
        test.skip(test.info().project.name !== 'chromium', 'one project sets the window itself')
        await fakeCase(context, 'big')
        await page.setViewportSize({ width: Math.round(w / zoom), height: Math.round(h / zoom) })
        await openHome(page)
        expect(await formOf(page)).toBe('desktop')
        await settled(page)
        const name = `desktop-${w}x${h}${zoom > 1 ? `-zoom${zoom * 100}` : ''}`
        await expectInside(page, name)
        await shot(page, name)
    })
}

test('no logo: the name stands in its place and still fits; reduced motion draws at once', async ({ page, context }) => {
    test.skip(test.info().project.name !== 'chromium', 'one project sets the window itself')
    await fakeCase(context, 'big')
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await page.setViewportSize({ width: 1280, height: 800 })
    await openHome(page, null)
    await settled(page, false)
    const title = page.locator('section.hero .hero-text:not(.leaving) .hero-title').first()
    await expect(title.locator('img.title-logo')).toHaveCount(0)
    await expect(title).toHaveText(/\S/)
    await expect(title).toHaveAttribute('aria-label', /\S/)
    await expectInside(page, 'no logo')
    // Reduced motion: the carousel does not turn by itself.
    const dots = page.locator('section.hero .hero-dots button')
    await page.mouse.move(640, 790)
    await page.waitForTimeout(8000)
    await expect(dots.nth(0)).toHaveAttribute('aria-selected', 'true')
    await shot(page, 'desktop-nologo-1280x800')
    // The setting is read live: turned off while the page is open, the carousel turns on its own.
    await page.emulateMedia({ reducedMotion: 'no-preference' })
    await expect(dots.nth(1)).toHaveAttribute('aria-selected', 'true', { timeout: 12_000 })
})

test('a pause button stops the carousel for the session, is reached by the keyboard and says what it does', async ({ page, context }) => {
    test.skip(test.info().project.name !== 'chromium', 'one project sets the window itself')
    await fakeCase(context, 'big')
    await page.setViewportSize({ width: 1280, height: 800 })
    const hero = await openHome(page)
    const pause = hero.getByRole('button', { name: 'Pause the carousel' })
    await expect(pause).toBeVisible()
    await expect(pause).toHaveAttribute('aria-pressed', 'false')
    // Tab reaches it (the carousel, then the button).
    await hero.focus()
    for (let i = 0; i < 12 && !(await pause.evaluate((el) => el === document.activeElement)); i++) await page.keyboard.press('Tab')
    await expect(pause).toBeFocused()
    await page.keyboard.press('Enter')
    const play = hero.getByRole('button', { name: 'Play the carousel' })
    await expect(play).toHaveAttribute('aria-pressed', 'true')
    // Stopped: left alone and away from it, no page turns.
    await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur())
    await page.mouse.move(640, 790)
    await page.waitForTimeout(8500)
    await expect(hero.locator('.hero-dots button').nth(0)).toHaveAttribute('aria-selected', 'true')
    // It holds when Home is left and come back to, and turns on again when asked.
    await page.getByRole('link', { name: 'Chats' }).first().click()
    await page.goBack()
    await expect(hero.getByRole('button', { name: 'Play the carousel' })).toBeVisible()
    await hero.getByRole('button', { name: 'Play the carousel' }).click()
    await expect(hero.getByRole('button', { name: 'Pause the carousel' })).toBeVisible()
})

test('a drag that starts and ends on Play turns the page and does not press Play', async ({ page, context }) => {
    test.skip(test.info().project.name !== 'chromium', 'one project sets the window itself')
    await fakeCase(context, 'big')
    await page.setViewportSize({ width: 1280, height: 800 })
    const hero = await openHome(page)
    await settled(page)
    const url = page.url()
    const play = (await hero.locator('.hero-text:not(.leaving) .hero-play').boundingBox())!
    const y = play.y + play.height / 2
    // Across Play, from its end to its start (over 48 px, to the left): the page turns on, the release lands on Play.
    await page.mouse.move(play.x + play.width - 6, y)
    await page.mouse.down()
    await page.mouse.move(play.x + 6, y, { steps: 8 })
    await page.mouse.up()
    await page.waitForTimeout(600)
    await expect(hero.locator('.hero-dots button').nth(1)).toHaveAttribute('aria-selected', 'true')
    expect(page.url()).toBe(url)
    // A plain press on Play still opens the video.
    await page.waitForTimeout(300)
    await hero.locator('.hero-text:not(.leaving) .hero-play').click()
    await expect(page).toHaveURL(/\/watch\/|\/video\//)
})

test('a dot has a hit area of at least 24 px', async ({ page, context }) => {
    test.skip(test.info().project.name === 'webkit', 'Chromium and the form projects')
    await fakeCase(context, 'big')
    const hero = await openHome(page)
    const dot = hero.locator('.hero-dots button').nth(2)
    const area = await dot.evaluate((el) => {
        const r = getComputedStyle(el, '::after')
        return { w: el.getBoundingClientRect().width + parseFloat(r.left) * -2, h: el.getBoundingClientRect().height + parseFloat(r.top) * -2 }
    })
    expect(area.w).toBeGreaterThanOrEqual(24)
    expect(area.h).toBeGreaterThanOrEqual(24)
})
