import { expect, type BrowserContext, type Page } from '@playwright/test'

/** Picks the fake Telegram's case for this context (see fake/mtcute.ts), before the first page loads. */
export async function fakeCase(context: BrowserContext, ...flags: string[]) {
    await context.addCookies([{ name: 'e2e', value: flags.join(','), url: `http://127.0.0.1:${process.env.E2E_PORT || 8097}` }])
}

/**
 * Lets every list show the test files, which are far under the default 50 MB floor: the
 * settings are written where the app keeps them before it starts.
 */
export async function noSizeFloor(page: Page) {
    await page.goto('/__e2e/sizes')
    await page.evaluate(
        () =>
            new Promise<void>((resolve, reject) => {
                const open = indexedDB.open('tmplayer-app')
                open.onupgradeneeded = () => open.result.createObjectStore('kv')
                open.onerror = () => reject(open.error)
                open.onsuccess = () => {
                    const tx = open.result.transaction('kv', 'readwrite')
                    tx.objectStore('kv').put({ sizeMin: 0 }, 'settings')
                    tx.oncomplete = () => (open.result.close(), resolve())
                }
            }),
    )
}

/** Opens Test Clips from the Chats page. */
export async function openClips(page: Page) {
    await page.goto('/chats')
    await page.getByRole('link', { name: /Test Clips/ }).first().click()
    await expect(page).toHaveURL(/\/chat\/-1001/)
}

/** Waits until the player's video has played past `seconds`. */
export async function playsPast(page: Page, seconds: number) {
    await expect.poll(() => page.locator('video').first().evaluate((v: HTMLVideoElement) => v.currentTime), { timeout: 20_000 }).toBeGreaterThan(seconds)
}

/** Opens a card's page (the video's detail), scrolling to it first: a virtual grid draws only the rows near the screen. */
export async function openDetail(page: Page, name: string) {
    const card = page.locator('.card').filter({ hasText: name })
    for (let i = 0; i < 30 && (await card.count()) === 0; i++) {
        await page.evaluate(() => window.scrollBy(0, 300))
        await page.waitForTimeout(100)
    }
    await card.click()
    await expect(page).toHaveURL(/\/video\//)
}

/** Opens a card and presses Play on its page. */
export async function openCard(page: Page, name: string) {
    await openDetail(page, name)
    await page.locator('.detail-play').click()
    await expect(page).toHaveURL(/\/watch\//)
}

/** A choice in the player's menu: a menu item on a desktop and the TV, a radio in the phone's sheet. */
export const choice = (page: Page, name: string) =>
    page.getByRole('menuitemradio', { name }).or(page.getByRole('radio', { name, exact: true })).first()

/**
 * The raw calls the fake Telegram has answered so far (fake/mtcute.ts `calls`: `searchMessages <chat> <filter> <offset>`,
 * `searchGlobal <filter> <offset>`, `iterDialogs <archived> <limit>`, `getMessages <chat>`, `getFullChat <chat>`, the
 * sync file's `sendMedia` and `editMessage`), read from the Telegram worker, which holds them. Empty before the worker starts.
 */
export async function workerCalls(page: Page): Promise<string[]> {
    const worker = page.workers().find((w) => /tg\/worker/.test(w.url()))
    if (!worker) return []
    return worker.evaluate(() => [...((globalThis as { fakeCalls?: string[] }).fakeCalls ?? [])])
}

/** How many of the calls so far start with `kind`. */
export const countCalls = (calls: string[], kind: string) => calls.filter((c) => c === kind || c.startsWith(`${kind} `)).length

/**
 * TMDB's split of Harbour Notes (the fake Test Shows chat holds its S01E01 to S01E03), as the worker answers it: season 1 of 25
 * (the last two not aired), season 2 of 12 (the last two not aired, the seventh untitled at TMDB). `asked` collects the
 * /tmdb/season queries; `picture` answers every image.
 */
export async function mockSplitShow(page: Page, asked: string[], picture: Buffer) {
    const episodes = (season: number) =>
        Array.from({ length: season === 1 ? 25 : 12 }, (_, i) => ({
            number: i + 1,
            name: season === 1 ? `The ${i + 1}th Tide` : i === 6 ? '' : `The ${26 + i}th Tide`,
            overview: `What happens in ${season}x${i + 1}.`,
            still: `/still${season}${String(i + 1).padStart(3, '0')}abc.jpg`,
            airDate: (season === 1 ? i >= 23 : i >= 10) ? '2099-01-01' : '2024-10-03',
            runtime: 24,
        }))
    await page.route('**/tmdb/**', (route) => {
        const url = new URL(route.request().url())
        if (url.pathname === '/tmdb/poster') return route.fulfill({ json: { match: true, id: '207347', kind: 'show', title: 'Harbour Notes', year: 2024, poster: null, backdrop: null, overview: '' } })
        if (url.pathname === '/tmdb/extras')
            return route.fulfill({
                json: {
                    found: true, genres: [], runtime: 24, seasons: 2, rating: null, certification: null, directors: [], creators: [], cast: [], trailer: null, ended: false, imdb: null, logo: null,
                    seasonList: [{ number: 1, episodes: 25, airDate: '2024-10-03' }, { number: 2, episodes: 12, airDate: '2025-02-01' }], next: null,
                    seasonGroup: 'seasons001', listedSeasons: [{ number: 1, episodes: 37, airDate: '2024-10-03' }], listedNext: null, groupsRead: true,
                },
            })
        if (url.pathname === '/tmdb/season') {
            asked.push(url.search)
            return route.fulfill({ json: { found: true, episodes: episodes(Number(url.searchParams.get('s'))) } })
        }
        return route.fulfill({ contentType: 'image/jpeg', body: picture })
    })
}

/** The form this project runs in: its name says (`phone-390`, `desktop-1280`, `webkit-iphone`); the rest are auto-detected desktops. */
export const isPhoneProject = (name: string) => name.startsWith('phone') || name.includes('iphone')

/**
 * On the TV's Settings (a rail of pages and a pane beside it): opens a page the way a remote does, with
 * the rail's item for it focused and Right, which shows that page and puts the remote on its first item.
 * A hash cannot do it: the pane follows the rail's focus after a moment.
 */
export async function openTvSettingsPage(page: Page, id: 'account' | 'playback' | 'subtitles' | 'storage' | 'appearance' | 'home' | 'help' | 'about') {
    const item = page.locator(`.tv-rail [data-rail="${id}"]`)
    await expect(item).toBeVisible()
    await item.focus()
    await page.keyboard.press('ArrowRight')
    await expect(item).toHaveAttribute('aria-current', 'page')
    await expect.poll(() => page.evaluate(() => !!document.activeElement?.closest('.tv-pane'))).toBe(true)
}
