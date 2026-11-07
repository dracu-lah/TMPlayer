// The one-tap Choose chats button in the header of Home and Chats, in the phone and desktop layouts
// (the TV's is walked in tv.spec.ts). It goes where the Settings row goes, /added-chats.
import { expect, test } from '@playwright/test'

for (const [name, path] of [
    ['Home', '/'],
    ['Chats', '/chats'],
] as const) {
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
            const title = page.locator(path === '/' ? '.home-title' : '.list-head .page-title')
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
