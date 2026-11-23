// The one-tap Choose chats button in the header of Chats, in the phone and desktop layouts (the
// TV's is walked in tv.spec.ts). Home has none: it is on Chats and in Settings. It goes where the
// Settings row goes, /added-chats.
import { expect, test, type Page } from '@playwright/test'
import { fakeCase } from './helpers'

test('Home has no Choose chats button', async ({ page }) => {
    await page.goto('/')
    await expect(page.locator('.home-title')).toBeVisible()
    await expect(page.getByRole('link', { name: 'Choose chats' })).toHaveCount(0)
})

for (const [name, path] of [['Chats', '/chats']] as const) {
    test.describe(name, () => {
        test('has a named button that opens the picker once, even on a double click', async ({ page }) => {
            await page.goto(path)
            const button = page.getByRole('link', { name: 'Choose chats' })
            await expect(button).toBeVisible()
            await expect(button).toHaveAttribute('title', 'Choose chats')
            const before = await page.evaluate(() => history.length)
            await button.dblclick()
            await expect(page).toHaveURL(/\/added-chats$/)
            await expect(page.getByRole('heading', { name: /Choose chats/ })).toBeVisible()
            expect(await page.evaluate(() => history.length)).toBe(before + 1)
        })

        test('opens from the keyboard and shows a focus ring', async ({ page }) => {
            await page.goto(path)
            const button = page.getByRole('link', { name: 'Choose chats' })
            await button.focus()
            await page.keyboard.press('Tab')
            await page.keyboard.press('Shift+Tab')
            await expect(button).toBeFocused()
            expect(await button.evaluate((el) => getComputedStyle(el).outlineStyle)).not.toBe('none')
            await page.keyboard.press('Enter')
            await expect(page).toHaveURL(/\/added-chats$/)
        })

        test('at 320 px with large text the header neither wraps nor scrolls sideways, and the button stays clear of the title', async ({ page }) => {
            await page.setViewportSize({ width: 320, height: 640 })
            await page.goto(path)
            await page.addStyleTag({ content: 'html { font-size: 28px !important; }' })
            const button = page.getByRole('link', { name: 'Choose chats' })
            await expect(button).toBeVisible()
            const box = (await button.boundingBox())!
            expect(box.x).toBeGreaterThanOrEqual(0)
            expect(box.x + box.width).toBeLessThanOrEqual(320)
            expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
            const title = page.locator('.list-head .page-title')
            const t = (await title.boundingBox())!
            expect(t.x + t.width).toBeLessThanOrEqual(box.x + 1)
        })

        test('in a right to left page it sits at the start of the line', async ({ page }) => {
            await page.goto(path)
            await page.evaluate(() => document.documentElement.setAttribute('dir', 'rtl'))
            const box = (await page.getByRole('link', { name: 'Choose chats' }).boundingBox())!
            const vw = page.viewportSize()!.width
            expect(box.x + box.width / 2).toBeLessThan(vw / 2)
        })
    })
}

// The picker by keyboard (WB22): Tab reaches the search, every filter, Select all, then the rows' ticks and stars in
// order, Space ticks, and a search that matches nothing says so instead of leaving a blank page.
test.describe('the picker by keyboard', () => {
    // 'picked' (vite.e2e.ts): an answered account with no chats picked, as the test server's default 'all' is the
    // All chats switch on since HX86, every tick disabled.
    test.beforeEach(async ({ context }) => {
        await fakeCase(context, 'picked')
    })
    const activeName = (page: Page) =>
        page.evaluate(() => {
            const a = document.activeElement as HTMLElement | null
            return a ? (a.getAttribute('aria-label') ?? a.textContent?.trim() ?? a.tagName) : ''
        })

    test('Tab walks the search, the filters, Select all, a row and its star; Space ticks; the count follows', async ({ page }) => {
        await page.goto('/added-chats')
        await expect(page.getByRole('checkbox', { name: 'Test Clips' })).toBeVisible()
        await page.getByRole('searchbox', { name: 'Search chats' }).focus()
        const seen: string[] = []
        for (let i = 0; i < 9; i++) {
            await page.keyboard.press('Tab')
            seen.push(await activeName(page))
        }
        expect(seen.slice(0, 6)).toEqual(['All', 'Archived', 'Channels', 'Groups', 'People', 'Select all'])
        // Then the first row: its tick, and its star.
        expect(seen[6]).toBe('Saved Messages')
        expect(seen[7]).toMatch(/favourites/)
        await page.getByRole('checkbox', { name: 'Test Clips' }).focus()
        await page.keyboard.press('Space')
        await expect(page.getByRole('checkbox', { name: 'Test Clips' })).toBeChecked()
        await expect(page.locator('.select-count')).toHaveText('1 chat chosen')
        expect(await page.getByRole('checkbox', { name: 'Test Clips' }).evaluate((el) => getComputedStyle(el).outlineStyle)).not.toBe('none')
    })

    test('a search that matches no chat says so, and Select all has nothing to act on', async ({ page }) => {
        await page.goto('/added-chats')
        await expect(page.getByRole('checkbox', { name: 'Test Clips' })).toBeVisible()
        await page.getByRole('searchbox', { name: 'Search chats' }).fill('zzzz nothing')
        await expect(page.getByText('No chat matches "zzzz nothing".')).toBeVisible()
        await expect(page.getByRole('button', { name: 'Select all' })).toBeDisabled()
        await expect(page.getByRole('checkbox')).toHaveCount(0)
    })
})

// The All chats switch (HX86): at the top above the search, off by default; on, every chat counts, the ticks and
// Select all are disabled (and kept), the foot says so, and off brings the ticks back.
test.describe('the All chats switch', () => {
    test.beforeEach(async ({ context }) => {
        await fakeCase(context, 'picked')
    })
    test('is off at first, disables the ticks while on, and gives them back when off', async ({ page }) => {
        await page.goto('/added-chats')
        const clips = page.getByRole('checkbox', { name: 'Test Clips' })
        await expect(clips).toBeVisible()
        const all = page.getByRole('switch', { name: /All chats/ })
        await expect(all).toHaveAttribute('aria-checked', 'false')
        await clips.check()
        const before = await page.locator('.select-count').innerText()
        await all.click()
        await expect(all).toHaveAttribute('aria-checked', 'true')
        await expect(clips).toBeDisabled()
        await expect(page.getByRole('button', { name: 'Select all' })).toBeDisabled()
        await expect(page.locator('.select-count')).toHaveText('All chats is on')
        await all.click()
        await expect(clips).toBeEnabled()
        await expect(clips).toBeChecked()
        await expect(page.locator('.select-count')).toHaveText(before)
    })
})
