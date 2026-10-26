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

/** Opens a card of the chat's grid, scrolling to it first: a virtual grid draws only the rows near the screen. */
export async function openCard(page: Page, name: string) {
    const card = page.locator('.card').filter({ hasText: name })
    for (let i = 0; i < 30 && (await card.count()) === 0; i++) {
        await page.evaluate(() => window.scrollBy(0, 300))
        await page.waitForTimeout(100)
    }
    await card.click()
}
