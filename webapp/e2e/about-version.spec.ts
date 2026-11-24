// WP-104: the version, the build and the update state in the account menu's last line, at the top of
// Settings and at the top of About, at the phone's and the desktop's widths (FORM_SPECS runs it at
// 390, 412 and 1280 px). The menu line is a menu item the arrows reach; a press copies
// "TMPlayer web (<commit>, <day>) <browser on system>" for support, the words change once to
// "Version details copied" in a polite live region, and the text holds no account data. "Update
// available" is a button of its own under the line and only shows when a new version waits.
import { expect, test, type Page } from '@playwright/test'

const LINE = /^TMPlayer web \(\w{3,7}, \d{4}-\d{2}-\d{2}\)/

const picture = (page: Page) => page.locator('[data-you]')
const menu = (page: Page) => page.getByRole('menu')

test('the account menu ends with the version line, and a press copies the support text and says so once', async ({ page, context, browserName }) => {
    if (browserName === 'chromium') await context.grantPermissions(['clipboard-read', 'clipboard-write'])
    await page.goto('/')
    await picture(page).click()
    const card = menu(page)
    const foot = card.locator('[data-version-line]')
    await expect(foot).toBeVisible()
    await expect(foot).toHaveAttribute('role', 'menuitem')
    await expect(foot.locator('.version-text')).toHaveText(LINE)
    // It is the card's last line, under Sign out.
    await expect(card.getByRole('menuitem').last()).toHaveText(LINE)
    await expect(foot.locator('.version-text')).toHaveAttribute('aria-live', 'polite')
    await foot.click()
    await expect(foot.locator('.version-text')).toHaveText('Version details copied')
    if (browserName === 'chromium') {
        const text = await page.evaluate(() => navigator.clipboard.readText())
        expect(text).toMatch(/^TMPlayer web \(\w{3,7}, \d{4}-\d{2}-\d{2}\) .+/)
        // Nothing of the account: not the name, the username, or a phone number.
        for (const secret of ['Dana', 'danatest', '+1']) expect(text).not.toContain(secret)
        expect(text).not.toMatch(/[\r\n]/)
    }
    // The words come back on their own after two seconds.
    await expect(foot.locator('.version-text')).toHaveText(LINE, { timeout: 5000 })
})

test('the arrows reach the version line, End lands on it, Up goes back to Sign out', async ({ page }) => {
    await page.goto('/')
    await picture(page).focus()
    await page.keyboard.press('Enter')
    await expect(menu(page)).toBeVisible()
    await page.keyboard.press('End')
    await expect(menu(page).locator('[data-version-line]')).toBeFocused()
    await page.keyboard.press('ArrowUp')
    await expect(menu(page).getByRole('menuitem', { name: 'Sign out' })).toBeFocused()
})

test('Settings has the line under the account and the search box, and a new version waiting is a button of its own', async ({ page }) => {
    await page.goto('/settings')
    const line = page.locator('.set-version [data-version-line]')
    await expect(line).toBeVisible()
    await expect(line).toHaveText(LINE)
    // Under the search box, above the first page row.
    const order = await page.evaluate(() => {
        const at = (sel: string) => document.querySelector(sel)?.getBoundingClientRect().top ?? -1
        return { search: at('.set-list [role=search], .set-list input[type=search]'), line: at('.set-version'), first: at('.set-card') }
    })
    expect(order.line).toBeGreaterThan(order.search)
    expect(order.first).toBeGreaterThan(order.line)
    // A waiting version (the dev server's hook stands in for a second build): the menu says so with its own button.
    await page.evaluate(() => (window as unknown as { __e2eUpdateReady: () => void }).__e2eUpdateReady())
    await expect(line).toContainText('Update available')
    await picture(page).click()
    await expect(menu(page).getByRole('menuitem', { name: 'Update available' })).toBeVisible()
})

test('About has the version line at its top', async ({ page }) => {
    await page.goto('/settings/about')
    const line = page.locator('.set-masthead [data-version-line]')
    await expect(line).toBeVisible()
    await expect(line).toHaveText(LINE)
    // 44 px to press, the line wraps rather than overflowing the page.
    const box = await line.boundingBox()
    expect(box?.height ?? 0).toBeGreaterThanOrEqual(44)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
})
