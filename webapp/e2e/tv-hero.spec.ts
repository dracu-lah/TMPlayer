// WB7 (HX15 on the TV): Home's carousel on a TV draws the title's logo (a logo shaped placeholder
// while it comes, the name where there is none), the apps' facts line and the score with its mark,
// at both TV sizes, walked with the remote only. Also: the name at once with posters off or no TMDB
// key, a near black logo lifted off the dark page, very wide and tall logos kept in their box, the
// placeholder still once the carousel is out of sight, the pages not turning there, and the page
// quiet when idle. Pictures go to $TMPLAYER_SHOTS when it is set.
import { readFileSync } from 'node:fs'
import { crc32, deflateSync } from 'node:zlib'
import { expect, test, type Page } from '@playwright/test'
import { fakeCase } from './helpers'

test.skip(({ browserName }) => browserName !== 'chromium', 'the TV walk runs in Chromium')

const PICTURE = readFileSync(new URL('./media/thumb.jpg', import.meta.url))
const LOGO = readFileSync(new URL('./media/logo.png', import.meta.url))
const FOUND = { match: true, id: '77', kind: 'show', title: 'Harbour Notes', year: 2019, poster: '/harbournotes0.jpg', backdrop: '/harbourwide00.jpg', overview: 'A harbour town keeps its notes.' }
const EXTRAS = { found: true, genres: ['Drama', 'Mystery', 'Crime'], runtime: 42, seasons: 2, rating: 7.4, certification: 'TV-14', directors: [], creators: [], cast: [], trailer: null, ended: false, imdb: null }

/** A PNG of `w` by `h`, a solid block of `rgb` inset on a clear ground, built here so no file is needed. */
function png(w: number, h: number, rgb: [number, number, number]): Buffer {
    const chunk = (type: string, data: Buffer) => {
        const len = Buffer.alloc(4)
        len.writeUInt32BE(data.length)
        const body = Buffer.concat([Buffer.from(type, 'ascii'), data])
        const crc = Buffer.alloc(4)
        crc.writeUInt32BE(crc32(body))
        return Buffer.concat([len, body, crc])
    }
    const head = Buffer.alloc(13)
    head.writeUInt32BE(w, 0)
    head.writeUInt32BE(h, 4)
    head[8] = 8
    head[9] = 6
    const rows = Buffer.alloc((w * 4 + 1) * h)
    for (let y = 0; y < h; y++) {
        for (let x = 0; x < w; x++) {
            const o = y * (w * 4 + 1) + 1 + x * 4
            const solid = x > w * 0.05 && x < w * 0.95 && y > h * 0.1 && y < h * 0.9
            rows[o] = rgb[0]
            rows[o + 1] = rgb[1]
            rows[o + 2] = rgb[2]
            rows[o + 3] = solid ? 255 : 0
        }
    }
    return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', head), chunk('IDAT', deflateSync(rows)), chunk('IEND', Buffer.alloc(0))])
}

/** The layout set to TV, two chats starred and `settings`, written where the app keeps them before it starts. */
async function tv(page: Page, settings: Record<string, unknown> = {}) {
    await page.goto('/__e2e/sizes')
    await page.evaluate(
        (settings) =>
            new Promise<void>((resolve, reject) => {
                localStorage.setItem('tmplayer-layout', 'tv')
                const open = indexedDB.open('tmplayer-app')
                open.onupgradeneeded = () => open.result.createObjectStore('kv')
                open.onerror = () => reject(open.error)
                open.onsuccess = () => {
                    const tx = open.result.transaction('kv', 'readwrite')
                    const kv = tx.objectStore('kv')
                    kv.put({ sizeMin: 0, ...settings }, 'settings')
                    kv.put({ chatId: -1001, time: 1 }, 'star:-1001')
                    kv.put({ chatId: -1002, time: 2 }, 'star:-1002')
                    tx.oncomplete = () => (open.result.close(), resolve())
                }
            }),
        settings,
    )
}

interface Worker {
    /** The logo's file name in the extras, or null for none. */
    logo?: string | null
    /** Keeps the extras back until it settles. */
    hold?: Promise<void>
    /** Keeps the poster answers back until it settles (the carousel's pages are still being picked). */
    holdPoster?: Promise<void>
    /** No TMDB key: every lookup answers 503, as the worker does. */
    noKey?: boolean
}

/** Stands in for the /tmdb worker: every title matched with a wide picture, so Recently added fills the carousel. */
async function mockTmdb(page: Page, w: Worker = {}) {
    await page.route('**/tmdb/**', async (route) => {
        const path = new URL(route.request().url()).pathname
        if (w.noKey) return route.fulfill({ status: 503, json: { error: 'no key' }, headers: { 'Retry-After': '30' } })
        if (path === '/tmdb/poster') {
            await w.holdPoster
            return route.fulfill({ json: FOUND })
        }
        if (path === '/tmdb/extras') {
            await w.hold
            return route.fulfill({ json: { ...EXTRAS, logo: w.logo === undefined ? '/harbourlogo0.png' : w.logo } })
        }
        if (path.endsWith('.png')) return route.fulfill({ contentType: 'image/png', body: LOGO })
        if (path.startsWith('/tmdb/img/')) return route.fulfill({ contentType: 'image/jpeg', body: PICTURE })
        return route.fulfill({ json: { found: false } })
    })
}

async function shot(page: Page, name: string, size: { width: number }) {
    const dir = process.env.TMPLAYER_SHOTS
    if (!dir) return
    // The remote's first card scrolls the carousel up under the bar: the picture is of the top of Home.
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.waitForTimeout(100)
    await page.screenshot({ path: `${dir}/tv-${size.width}-${name}.png` })
}

/** What has the focus: its class and address. */
const focused = (page: Page) =>
    page.evaluate(() => {
        const a = document.activeElement as HTMLElement | null
        return a ? `${a.classList[0] ?? a.tagName}|${a.getAttribute('href') ?? a.getAttribute('aria-label') ?? ''}` : ''
    })

async function press(page: Page, key: string, times = 1) {
    for (let i = 0; i < times; i++) {
        await page.keyboard.press(key)
        await page.waitForTimeout(150)
    }
}

/** One dp at this window's size, as tv.css works it out. */
const dp = (size: { width: number; height: number }) => Math.min(size.width / 960, size.height / 540)

const title = (page: Page) => page.locator('.tv-hero h2.tv-hero-title')

for (const size of [
    { width: 1920, height: 1080 },
    { width: 1280, height: 720 },
]) {
    test.describe(`TV carousel words at ${size.width} x ${size.height}`, () => {
        test.use({ viewport: size })
        test.beforeEach(async ({ context }) => {
            await fakeCase(context, 'big')
        })

        test('a logo shaped placeholder, then the logo read out as the name, the facts line and the score with its mark; the remote is on Play throughout', async ({ page }) => {
            let release!: () => void
            await mockTmdb(page, { hold: new Promise<void>((go) => (release = go)) })
            await tv(page)
            await page.goto('/')
            await expect(title(page).locator('.title-logo-skeleton')).toBeVisible()
            await page.evaluate(() => window.scrollTo(0, 0))
            // The placeholder stands in the logo's box, at the column's start (measured before its 2.5 s ceiling).
            const slot = (await title(page).boundingBox())!
            expect(slot.height).toBeGreaterThanOrEqual(56 * dp(size) - 1)
            await shot(page, 'loading', size)
            // The score's line holds its place while the extras are on their way.
            await expect(page.locator('.tv-hero-score.empty')).toHaveCount(1)
            // Where a part stands inside the carousel, so the page's own scroll does not count.
            const within = (sel: string) =>
                page.locator(sel).evaluate((el) => {
                    const r = el.getBoundingClientRect()
                    const h = el.closest('.tv-hero')!.getBoundingClientRect()
                    return { top: r.top - h.top, bottom: r.bottom - h.top }
                })
            const factsAt = await within('.tv-hero-facts')
            const titleAt = await within('.tv-hero h2.tv-hero-title')
            // The remote walks up to Play, as before the logo (tv.spec's alignment walk).
            for (let i = 0; i < 4 && !(await focused(page)).startsWith('tv-hero-play|'); i++) await press(page, 'ArrowUp')
            expect(await focused(page)).toContain('tv-hero-play|/watch/')
            release()
            const logo = title(page).locator('img.title-logo')
            await expect(logo).toBeVisible()
            // Read out as the page's name, the logo itself silent.
            expect(await title(page).getAttribute('aria-label')).toBeTruthy()
            await expect(logo).toHaveAttribute('alt', '')
            await expect(title(page).locator('.title-logo-skeleton')).toHaveCount(0)
            // The logo keeps to its box (340 by 56 dp).
            const box = (await logo.boundingBox())!
            expect(box.width).toBeLessThanOrEqual(340 * dp(size) + 1)
            expect(box.height).toBeLessThanOrEqual(56 * dp(size) + 1)
            // The apps' facts line: the kind, the year, two genres, a show's seasons.
            const facts = page.locator('.tv-hero-facts')
            // Nothing above the score moved when it came: the facts and the logo's foot stand where they stood.
            await expect(page.locator('.tv-hero-score:not(.empty)')).toBeVisible()
            expect(Math.abs((await within('.tv-hero-facts')).top - factsAt.top)).toBeLessThan(1)
            expect(Math.abs((await within('.tv-hero h2.tv-hero-title')).bottom - titleAt.bottom)).toBeLessThan(1)
            await expect(facts).toHaveText('Series  ·  2019  ·  Drama, Mystery  ·  2 seasons')
            const score = page.locator('.tv-hero-score')
            await expect(score).toHaveAttribute('aria-label', 'TMDB 7.4 out of 10')
            await expect(score.locator('svg.tmdb-mark')).toBeVisible()
            await expect(score).toContainText('7.4')
            // The words stay clear of the bar, and Play stays where it was.
            const bar = (await page.locator('.tv-bar-row').boundingBox())!
            const words = (await page.locator('.tv-hero-text').boundingBox())!
            expect(words.y).toBeGreaterThan(bar.y + bar.height)
            expect(await focused(page)).toMatch(/^tv-hero-play\|\/watch\//)
            await page.waitForTimeout(300)
            await shot(page, 'logo', size)
            // Focus unchanged: Right to Details, Down to the rows, Up back to Play.
            await press(page, 'ArrowRight')
            expect(await focused(page)).toContain('tv-hero-pill|/video/')
            await press(page, 'ArrowDown')
            expect(await focused(page)).toContain('card|/video/')
            await press(page, 'ArrowUp')
            expect(await focused(page)).toMatch(/^tv-hero-(play|pill)\|/)
        })

        test('the loader while the pages are picked: the page\'s shapes where they will stand, still', async ({ page }) => {
            let release!: () => void
            await mockTmdb(page, { holdPoster: new Promise<void>((go) => (release = go)) })
            await tv(page)
            await page.goto('/')
            const wait = page.locator('.tv-hero-wait')
            await expect(wait).toBeAttached()
            for (const part of await wait.locator('i').all()) expect(await part.evaluate((el) => getComputedStyle(el).animationName)).toBe('none')
            // Where a part stands inside the carousel, so the page's own scroll does not count.
            const within = (sel: string) =>
                page.locator(sel).first().evaluate((el) => {
                    const r = el.getBoundingClientRect()
                    const h = el.closest('.tv-hero')!.getBoundingClientRect()
                    return { x: r.left - h.left, y: r.top - h.top, height: r.height }
                })
            const first = await within('.tv-hero-wait-pills i')
            expect(first.height).toBeCloseTo(52 * dp(size), 0)
            await page.evaluate(() => window.scrollTo(0, 0))
            await shot(page, 'loader', size)
            release()
            await expect(page.locator('.tv-hero-play')).toBeVisible()
            // Play stands where the loader's first pill stood.
            const play = await within('.tv-hero-play')
            expect(Math.abs(play.x - first.x)).toBeLessThan(1)
            expect(Math.abs(play.y - first.y)).toBeLessThan(1)
            expect(play.height).toBeCloseTo(first.height, 0)
        })

        test('no logo at TMDB: the name in type and no placeholder past the answer', async ({ page }) => {
            await mockTmdb(page, { logo: null })
            await tv(page)
            await page.goto('/')
            await expect(page.locator('.tv-hero-score')).toBeVisible()
            await expect(title(page).locator('.title-logo-skeleton, img')).toHaveCount(0)
            const name = await title(page).getAttribute('aria-label')
            expect(name).toBeTruthy()
            await expect(title(page)).toHaveText(name!)
            await expect(page.locator('.tv-hero-facts')).toContainText('Drama, Mystery')
            await shot(page, 'nologo', size)
        })

        // The carousel takes no page without TMDB's wide picture (TvFeaturedArt), so with no key or posters off it
        // is not there at all: Home opens on its rows, the loader gone, and no placeholder is ever drawn.
        for (const [name, worker, settings] of [
            ['no TMDB key', { noKey: true }, {}],
            ['posters off', {}, { posters: false }],
        ] as const) {
            test(`${name}: no carousel, no placeholder, the remote on the first card`, async ({ page }) => {
                const asked: string[] = []
                page.on('request', (r) => r.url().includes('/tmdb/') && asked.push(r.url()))
                await mockTmdb(page, worker)
                await tv(page, settings)
                await page.goto('/')
                await expect.poll(() => focused(page)).toContain('card|/video/')
                await expect(page.locator('.tv-hero')).toHaveCount(0)
                await page.waitForTimeout(1_500)
                await expect(page.locator('.tv-hero, .title-logo-skeleton')).toHaveCount(0)
                if (name === 'posters off') expect(asked).toEqual([])
                await shot(page, name.replace(/ /g, ''), size)
            })
        }

        test('a near black logo is lifted off the dark page; very wide and very tall ones keep to the box', async ({ page }) => {
            let body = png(400, 120, [16, 20, 40])
            await mockTmdb(page)
            // Registered last, so it answers first: the logo's file, whichever is set below.
            await page.route('**/tmdb/img/w500/**', (route) => route.fulfill({ contentType: 'image/png', body }))
            await tv(page)
            await page.goto('/')
            const logo = title(page).locator('img.title-logo')
            await expect(logo).toHaveClass(/\bdark\b/)
            expect(await logo.evaluate((el) => getComputedStyle(el).filter)).toContain('drop-shadow')
            await page.waitForTimeout(300)
            await shot(page, 'darklogo', size)
            // The light theme draws it as it is.
            const theme = await page.evaluate(() => document.documentElement.getAttribute('data-theme'))
            await page.evaluate(() => document.documentElement.setAttribute('data-theme', 'light'))
            expect(await logo.evaluate((el) => getComputedStyle(el).filter)).toBe('none')
            await page.evaluate((t) => (t ? document.documentElement.setAttribute('data-theme', t) : document.documentElement.removeAttribute('data-theme')), theme)
            for (const [w, h] of [
                [2000, 60],
                [120, 900],
            ]) {
                body = png(w, h, [240, 240, 240])
                await page.evaluate(() => caches.delete('tmplayer-posters').catch(() => {}))
                await page.reload()
                const img = title(page).locator('img.title-logo')
                await expect(img).toBeVisible()
                await expect.poll(() => img.evaluate((el) => (el as HTMLImageElement).naturalWidth)).toBe(w)
                const box = (await img.boundingBox())!
                expect(box.width, `${w} x ${h}`).toBeLessThanOrEqual(340 * dp(size) + 1)
                expect(box.height, `${w} x ${h}`).toBeLessThanOrEqual(56 * dp(size) + 1)
            }
        })

        test('out of sight under the rows: the placeholder stands still and the pages stop turning', async ({ page }) => {
            let release!: () => void
            await mockTmdb(page, { hold: new Promise<void>((go) => (release = go)) })
            await tv(page)
            await page.goto('/')
            const skeleton = title(page).locator('.title-logo-skeleton')
            await expect(skeleton).toBeVisible()
            // The remote's first card scrolls the carousel up under the bar, which no longer counts as in sight: back to the top.
            await page.evaluate(() => window.scrollTo(0, 0))
            await expect(page.locator('.tv-hero')).toHaveClass(/\bin-sight\b/)
            expect(await skeleton.evaluate((el) => getComputedStyle(el).animationName)).toBe('waiting')
            await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
            await expect(page.locator('.tv-hero')).not.toHaveClass(/\bin-sight\b/)
            expect(await skeleton.evaluate((el) => getComputedStyle(el).animationName)).toBe('none')
            release()
            // A key was pressed (so the clock would run), and the remote is off the carousel.
            await press(page, 'ArrowDown', 3)
            await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
            await expect(page.locator('.tv-hero')).not.toHaveClass(/\bin-sight\b/)
            const dot = () => page.locator('.tv-hero-dots span').evaluateAll((els) => els.findIndex((e) => e.classList.contains('on')))
            const before = await dot()
            await page.waitForTimeout(8_000)
            expect(await dot()).toBe(before)
        })

        test('idle on Home with the carousel up: the main thread is all but quiet', async ({ page }) => {
            test.setTimeout(45_000)
            await mockTmdb(page)
            await tv(page)
            await page.goto('/')
            await expect(title(page).locator('img.title-logo')).toBeVisible()
            await page.waitForTimeout(2_000)
            const cdp = await page.context().newCDPSession(page)
            await cdp.send('Performance.enable')
            const busy = async () => {
                const { metrics } = await cdp.send('Performance.getMetrics')
                return metrics.find((m) => m.name === 'TaskDuration')?.value ?? 0
            }
            const start = await busy()
            await page.waitForTimeout(5_000)
            const spent = (await busy()) - start
            // Under 4% of one core over 5 s (a page turn included, should one fall in the window).
            expect(spent, `main thread busy ${spent.toFixed(3)} s in 5 s`).toBeLessThan(0.2)
        })
    })
}
