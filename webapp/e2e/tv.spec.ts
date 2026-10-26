// Plan 8.5: the TV layout walked with keys only (the remote's arrows, OK and Back), at both TV
// sizes: focus comes back to the item that was open after Back, after a menu and after a dialog.
import { expect, test, type Page } from '@playwright/test'
import { fakeCase } from './helpers'

/** The layout set to TV and two chats starred, written where the app keeps them before it starts. */
async function tv(page: Page) {
    await page.goto('/__e2e/sizes')
    await page.evaluate(
        () =>
            new Promise<void>((resolve, reject) => {
                localStorage.setItem('tmplayer-layout', 'tv')
                const open = indexedDB.open('tmplayer-app')
                open.onupgradeneeded = () => open.result.createObjectStore('kv')
                open.onerror = () => reject(open.error)
                open.onsuccess = () => {
                    const tx = open.result.transaction('kv', 'readwrite')
                    const kv = tx.objectStore('kv')
                    kv.put({ sizeMin: 0 }, 'settings')
                    kv.put({ chatId: -1001, time: 1 }, 'star:-1001')
                    kv.put({ chatId: -1002, time: 2 }, 'star:-1002')
                    tx.oncomplete = () => (open.result.close(), resolve())
                }
            }),
    )
}

/** What has the focus: its address, label or words. */
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

for (const size of [
    { width: 1920, height: 1080 },
    { width: 1280, height: 720 },
]) {
    test.describe(`TV layout at ${size.width} x ${size.height}`, () => {
        test.skip(({ browserName }) => browserName !== 'chromium', 'the TV walk runs in Chromium')
        test.use({ viewport: size })
        test.beforeEach(async ({ page, context }) => {
            await fakeCase(context)
            await tv(page)
        })

        test('Home: carousel first, rows, Back from the player returns to the card, Back goes to the bar', async ({ page }) => {
            await page.goto('/')
            await expect(page.locator('html')).toHaveAttribute('data-form', 'tv')
            await expect.poll(() => focused(page)).toContain('tv-hero-play')
            await press(page, 'ArrowDown', 3)
            await press(page, 'ArrowRight', 2)
            const card = await focused(page)
            expect(card).toContain('card|/watch/')
            await press(page, 'Enter')
            await expect(page).toHaveURL(/\/watch\//)
            await press(page, 'Backspace')
            await expect(page).toHaveURL(/\/$/)
            await expect.poll(() => focused(page)).toBe(card)
            await press(page, 'Escape')
            expect(await focused(page)).toBe('tv-tab|/')
            // Up from the bar goes nowhere; Down goes back into the page where the remote was.
            await press(page, 'ArrowUp')
            expect(await focused(page)).toBe('tv-tab|/')
        })

        test('Chats and a chat: the bar keeps the remote, a chat opens on its first card, the sort menu and Back', async ({ page }) => {
            await page.goto('/')
            await expect.poll(() => focused(page)).toContain('tv-hero-play')
            await press(page, 'ArrowUp')
            await press(page, 'ArrowRight')
            await press(page, 'Enter')
            await expect(page).toHaveURL(/\/chats$/)
            await expect.poll(() => focused(page)).toBe('tv-tab|/chats')
            await press(page, 'ArrowDown', 2)
            await press(page, 'ArrowRight', 2)
            const row = await focused(page)
            expect(row).toBe('chat-link|/chat/-1001')
            await press(page, 'Enter')
            await expect(page).toHaveURL(/\/chat\/-1001/)
            await expect.poll(() => focused(page)).toContain('card|/watch/-1001/')
            // Up from the first card is Back; Right passes the title to the chat's star, then Sort.
            await press(page, 'ArrowUp')
            expect(await focused(page)).toBe('round-button|Back')
            await press(page, 'ArrowRight', 2)
            expect(await focused(page)).toContain('Sort')
            await press(page, 'Enter')
            await press(page, 'ArrowDown')
            expect(await page.evaluate(() => !!document.activeElement?.closest('[role=menu]'))).toBe(true)
            await press(page, 'Backspace')
            await expect(page.locator('[role=menu]')).toHaveCount(0)
            expect(await focused(page)).toContain('Sort')
            await press(page, 'Backspace')
            await expect(page).toHaveURL(/\/chats$/)
            await expect.poll(() => focused(page)).toBe(row)
        })

        test('Settings: a choice is made with OK, not by moving; Sign out asks, Back closes the question onto it', async ({ page }) => {
            await page.goto('/')
            await expect.poll(() => focused(page)).toContain('tv-hero-play')
            await press(page, 'ArrowUp')
            await press(page, 'ArrowLeft', 3)
            await press(page, 'Enter')
            await expect(page).toHaveURL(/\/settings$/)
            await expect.poll(() => focused(page)).toContain('tv-picture')
            await press(page, 'ArrowDown')
            await press(page, 'ArrowRight')
            expect(await page.evaluate(() => document.documentElement.dataset.theme ?? 'system')).toBe('system')
            await press(page, 'Enter')
            expect(await page.evaluate(() => document.documentElement.dataset.theme)).toBe('dark')
            for (let i = 0; i < 30 && !(await focused(page)).includes('danger-button'); i++) await press(page, 'ArrowDown')
            expect(await focused(page)).toContain('danger-button')
            await press(page, 'Enter')
            await expect(page.locator('dialog[open]')).toHaveCount(1)
            expect(await page.evaluate(() => !!document.activeElement?.closest('dialog[open]'))).toBe(true)
            await press(page, 'Backspace')
            await expect(page.locator('dialog[open]')).toHaveCount(0)
            expect(await focused(page)).toContain('danger-button')
        })

        test('Settings: choosing Desktop with the remote takes the TV bar and keys away at once', async ({ page }) => {
            await page.goto('/settings')
            await expect.poll(() => focused(page)).not.toBe('')
            for (let i = 0; i < 6 && !(await focused(page)).startsWith('INPUT|'); i++) await press(page, 'ArrowDown')
            await press(page, 'ArrowDown')
            for (let i = 0; i < 4 && (await page.evaluate(() => document.activeElement?.closest('label')?.textContent?.trim())) !== 'Desktop'; i++) await press(page, 'ArrowLeft')
            await press(page, 'Enter')
            await expect(page.locator('html')).toHaveAttribute('data-form', 'desktop')
            await expect(page.locator('.tv-bar')).toHaveCount(0)
            await expect(page.locator('nav.nav')).toBeVisible()
            expect(await page.evaluate(() => document.documentElement.dataset.tvKeys)).toBeUndefined()
        })

        test('Search: the field opens for typing on OK only, Down goes to the results, Up to the bar', async ({ page }) => {
            await page.goto('/search')
            await expect.poll(() => page.evaluate(() => document.activeElement?.tagName)).toBe('INPUT')
            expect(await page.evaluate(() => (document.activeElement as HTMLInputElement).readOnly)).toBe(true)
            await press(page, 'Enter')
            await page.keyboard.type('Clip')
            await expect(page).toHaveURL(/q=Clip/)
            await expect(page.locator('.card').first()).toBeVisible()
            await press(page, 'ArrowDown')
            expect(await focused(page)).toContain('card|/watch/')
            await press(page, 'ArrowUp')
            expect(await focused(page)).toBe('INPUT|Search videos and chats')
            await press(page, 'ArrowUp')
            expect(await focused(page)).toBe('tv-tab|/search')
        })
    })
}
