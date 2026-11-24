// The Sort menu's filter chips on the TV layout (WB13), walked with the remote's keys only: the
// chips are reached, OK picks one, the menu stays open so the grid is seen to change, Back closes it
// onto the Sort button, and a filter that hides everything puts the remote on Clear filter.
import { expect, test, type Page } from '@playwright/test'
import { fakeCase } from './helpers'

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
                    tx.objectStore('kv').put({ sizeMin: 0 }, 'settings')
                    tx.oncomplete = () => (open.result.close(), resolve())
                }
            }),
    )
}

const focusedText = (page: Page) => page.evaluate(() => (document.activeElement as HTMLElement | null)?.textContent?.trim() ?? '')

async function press(page: Page, key: string, times = 1) {
    for (let i = 0; i < times; i++) {
        await page.keyboard.press(key)
        await page.waitForTimeout(120)
    }
}

/** Down until the remote is on a line that says `text`, at most `max` presses. */
async function downTo(page: Page, text: string, max = 40) {
    for (let i = 0; i < max && (await focusedText(page)) !== text; i++) await press(page, 'ArrowDown')
    expect(await focusedText(page)).toBe(text)
}

for (const size of [
    { width: 1920, height: 1080 },
    { width: 1280, height: 720 },
]) {
    test.describe(`Chat filters on the TV at ${size.width} x ${size.height}`, () => {
        test.skip(({ browserName }) => browserName !== 'chromium', 'the TV walk runs in Chromium')
        test.use({ viewport: size })
        test.setTimeout(90_000)
        test.beforeEach(async ({ page, context }) => {
            await fakeCase(context)
            await tv(page)
        })

        test('the remote reaches the chips, OK picks one, Back closes the menu onto Sort, and a filter that hides all puts the remote on Clear filter', async ({ page }, info) => {
            await page.goto('/chat/-1001')
            await expect.poll(() => page.evaluate(() => document.activeElement?.className ?? '')).toContain('card')
            // Up is Back, Right passes the title to the star, then Sort.
            await press(page, 'ArrowUp')
            await press(page, 'ArrowRight', 2)
            expect(await page.evaluate(() => document.activeElement?.getAttribute('aria-label'))).toBe('Sort videos by')
            await press(page, 'Enter')
            await expect(page.getByRole('menu')).toBeVisible()
            // The menu opens on its first line (Play all, WP-32), Play random is the next; Down walks the orders and then the chips.
            expect(await focusedText(page)).toBe('Play all')
            await press(page, 'ArrowDown')
            expect(await focusedText(page)).toBe('Play random')
            await downTo(page, 'Unwatched first')
            await press(page, 'ArrowDown')
            expect(await focusedText(page)).toBe('All videos')
            await press(page, 'ArrowRight')
            expect(await focusedText(page)).toBe('Unwatched')
            await press(page, 'ArrowRight', 3)
            expect(await focusedText(page)).toBe('Shows')
            if (process.env.E2E_SHOTS) await page.screenshot({ path: `../design/web-gaps/WB13/menu-tv-${size.width}.png` })
            await press(page, 'Enter')
            // The menu stays open, the chip is ticked and the grid has changed behind it.
            await expect(page.getByRole('menuitemradio', { name: 'Shows', exact: true })).toHaveAttribute('aria-checked', 'true')
            await expect(page.locator('.card')).toHaveCount(0)
            await press(page, 'Backspace')
            await expect(page.getByRole('menu')).toHaveCount(0)
            expect(await page.evaluate(() => document.activeElement?.getAttribute('aria-label'))).toBe('Sort and filter, a filter is on')
            await expect(page.getByText(/^Shows hides all \d+ videos loaded so far\.$/)).toBeVisible()
            if (process.env.E2E_SHOTS) await page.screenshot({ path: `../design/web-gaps/WB13/hides-all-tv-${size.width}.png` })
            // Down from the bar reaches Clear filter, and OK clears it.
            await press(page, 'ArrowDown')
            expect(await focusedText(page)).toBe('Clear filter')
            await press(page, 'Enter')
            await expect(page.locator('.card').first()).toBeVisible()
            await expect(page.getByText(/hides all/)).toHaveCount(0)
            expect(info.project.name).toBeTruthy()
        })
    })
}
