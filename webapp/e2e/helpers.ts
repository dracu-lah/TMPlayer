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

/** The form this project runs in: its name says (`phone-390`, `desktop-1280`, `webkit-iphone`); the rest are auto-detected desktops. */
export const isPhoneProject = (name: string) => name.startsWith('phone') || name.includes('iphone')
