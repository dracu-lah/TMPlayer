// WP-86: Save to device. The file goes through the service worker to the browser's own download, a
// piece at a time; the size is said, no room check (a page cannot know the Downloads disk); a running save can be cancelled and the
// browser keeps nothing; a restricted chat never offers it. Chromium: the other engines are the user's
// device check.
import { expect, test, type Locator, type Page } from '@playwright/test'
import { fakeCase, noSizeFloor } from './helpers'

test.beforeEach(async ({ page, isMobile, browserName }) => {
    test.skip(isMobile, 'a desktop browser saves to its downloads folder')
    test.skip(browserName !== 'chromium', 'the service worker is blocked in the WebKit projects (playwright.config.ts); the other engines are the user\'s device check')
    await noSizeFloor(page)
})

const controlled = (page: Page) => expect.poll(() => page.evaluate(() => !!navigator.serviceWorker?.controller), { timeout: 15_000 }).toBe(true)

async function openDetail(page: Page, room: string, name: string) {
    await page.goto(room)
    await controlled(page)
    await page.locator('.card').filter({ hasText: name }).click()
    await expect(page).toHaveURL(/\/video\//)
}

async function menuOf(page: Page, card: Locator) {
    await card.click({ button: 'right' })
    await expect(page.locator('dialog.card-menu[open]')).toBeVisible()
}

test.describe('with room', () => {
    test.beforeEach(async ({ context }) => {
        await fakeCase(context, 'links')
    })

    test('the video page saves the file as a download, with its name, and says so', async ({ page }) => {
        await openDetail(page, '/chat/-1001234567890', 'Plain Clip')
        const downloading = page.waitForEvent('download')
        await page.locator('.detail-round', { hasText: 'Save to device' }).click()
        const file = await downloading
        expect(file.suggestedFilename()).toMatch(/\.(mp4|mkv|webm|mov)$/)
        const path = await file.path()
        expect(path).toBeTruthy()
        expect(await file.failure()).toBeNull()
        await expect(page.locator('.toast')).toContainText('Saved')
        await expect(page.locator('.save-status')).toHaveCount(0, { timeout: 10_000 })
    })

    test('the card menu has the row, and a chat that restricts saving does not', async ({ page }) => {
        await page.goto('/chat/-1001234567890')
        await controlled(page)
        await menuOf(page, page.locator('.card').filter({ hasText: 'Plain Clip' }))
        await expect(page.locator('dialog.card-menu[open]').getByRole('button', { name: /Save to device/ })).toBeVisible()
        await page.keyboard.press('Escape')
        await expect(page.locator('dialog[open]')).toHaveCount(0)
        // The menu's own history entry goes with it; a navigation made before that lands on the old one.
        await page.waitForTimeout(300)
        await page.goto('/chat/-1005555555555')
        await controlled(page)
        await menuOf(page, page.locator('.card').first())
        await expect(page.locator('dialog.card-menu[open]').getByRole('button', { name: /Save to device/ })).toHaveCount(0)
        await page.keyboard.press('Escape')
        await page.locator('.card').first().click()
        await expect(page).toHaveURL(/\/video\//)
        await expect(page.locator('.detail-round', { hasText: 'Save to device' })).toHaveCount(0)
    })

    test('a small storage quota does not refuse a save: it says the size and the download goes ahead', async ({ page }) => {
        await page.addInitScript(() => {
            Object.defineProperty(navigator.storage, 'estimate', { value: async () => ({ quota: 200_000, usage: 150_000 }), configurable: true })
            // A small file saves at once and its "Saved" toast takes the place of "Saving ...": every toast text is kept.
            const seen: string[] = []
            ;(window as unknown as { __toasts: string[] }).__toasts = seen
            new MutationObserver(() => {
                for (const p of document.querySelectorAll('.toast p')) if (p.textContent && seen[seen.length - 1] !== p.textContent) seen.push(p.textContent)
            }).observe(document, { childList: true, subtree: true, characterData: true })
        })
        await openDetail(page, '/chat/-1001234567890', 'Plain Clip')
        const downloading = page.waitForEvent('download')
        await page.locator('.detail-round', { hasText: 'Save to device' }).click()
        await expect.poll(() => page.evaluate(() => (window as unknown as { __toasts: string[] }).__toasts.join('|'))).toMatch(/Saving .+ to your Downloads/)
        const file = await downloading
        expect(await file.failure()).toBeNull()
    })
})

test('a big file can be cancelled while it saves: the line goes, the toast says it was not saved', async ({ page, context }) => {
    await fakeCase(context, 'links', 'big')
    await openDetail(page, '/chat/-1001234567890', 'Plain Clip')
    await page.locator('.detail-round', { hasText: 'Save to device' }).click()
    const line = page.locator('.save-status .save-line').first()
    await expect(line).toContainText('Saving')
    await line.getByRole('button', { name: 'Cancel' }).click()
    await expect(page.locator('.toast')).toContainText(/Stopped|Could not save/)
    await expect(page.locator('.save-status')).toHaveCount(0, { timeout: 10_000 })
})
