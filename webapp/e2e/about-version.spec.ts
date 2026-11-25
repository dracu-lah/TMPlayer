// WP-104 and the account sheet (UX web): the version in the account menu's last line and at the foot of
// Settings' main list is compact, the TMPlayer icon and "v1.28.7" (or "web" when the build has no
// number), and a press opens About, where the full line stays at the top and a press on it copies
// "TMPlayer web (<commit>, <day>) <browser on system>" for support, the words changing once to
// "Version details copied" in a polite live region, with no account data. "Update available" is a button
// of its own beside the compact version and only shows when a new version waits. Run at the phone's and
// the desktop's widths (FORM_SPECS runs it at 390, 412 and 1280 px).
import { expect, test, type Page } from '@playwright/test'

const LINE = /^TMPlayer web \(\w{3,7}, \d{4}-\d{2}-\d{2}\)/
const COMPACT = /^(v\d+\.\d+(\.\d+)?|web)$/

const picture = (page: Page) => page.locator('[data-you]')
const menu = (page: Page) => page.getByRole('menu')

test('the account menu ends with the compact version, 44 px to press, and a press opens About', async ({ page }) => {
    await page.goto('/')
    await picture(page).click()
    const card = menu(page)
    const foot = card.locator('[data-version-line]')
    await expect(foot).toBeVisible()
    await expect(foot).toHaveAttribute('role', 'menuitem')
    await expect(foot.locator('.version-text')).toHaveText(COMPACT)
    await expect(foot.locator('img.version-mark')).toBeVisible()
    await expect(foot).toHaveAttribute('aria-label', /^TMPlayer (v\d+\.\d+(\.\d+)?|web), open About$/)
    // It is the card's last line, under Add account, and one short line (no build, no browser).
    await expect(card.getByRole('menuitem').last()).toHaveText(COMPACT)
    // Layout height, not the bounding box: the menu opens with a scale animation (you-in), which shrinks the box for a moment.
    expect(await foot.evaluate((el: HTMLElement) => el.offsetHeight)).toBeGreaterThanOrEqual(44)
    await foot.click()
    await expect(page).toHaveURL(/\/settings\/about$/)
    await expect(menu(page)).toHaveCount(0)
    await expect(page.locator('dialog[open]')).toHaveCount(0)
})

test('the compact row is one centred group and never says where updates come from', async ({ page }) => {
    await page.goto('/')
    await picture(page).click()
    const foot = menu(page).locator('.you-foot')
    await expect(foot).toBeVisible()
    await expect(menu(page)).not.toContainText('Updates come from')
    const [row, mark, text] = [await foot.boundingBox(), await foot.locator('img.version-mark').boundingBox(), await foot.locator('.version-text').boundingBox()]
    const groupCentre = (Math.min(mark!.x, text!.x) + Math.max(mark!.x + mark!.width, text!.x + text!.width)) / 2
    expect(Math.abs(groupCentre - (row!.x + row!.width / 2))).toBeLessThanOrEqual(1)
    await page.goto('/settings')
    await expect(page.locator('.set-list')).not.toContainText('Updates come from')
})

test('the arrows reach the version, End lands on it, Up goes back to Add account', async ({ page }) => {
    await page.goto('/')
    await picture(page).focus()
    await page.keyboard.press('Enter')
    await expect(menu(page)).toBeVisible()
    await page.keyboard.press('End')
    await expect(menu(page).locator('[data-version-line]')).toBeFocused()
    await page.keyboard.press('ArrowUp')
    await expect(menu(page).getByRole('menuitem', { name: 'Add account' })).toBeFocused()
})

test('Settings ends its main list with the compact version, once, and a new version waiting is a chip beside it', async ({ page }) => {
    await page.goto('/settings')
    const line = page.locator('.set-list [data-version-line]')
    await expect(line).toHaveCount(1)
    await expect(line).toBeVisible()
    await expect(line.locator('.version-text')).toHaveText(COMPACT)
    await expect(line).toHaveAttribute('href', '/settings/about')
    // The very last thing of the list, under the pages, Sign out and the footnote.
    const order = await page.evaluate(() => {
        const list = document.querySelector('.set-list')!
        const top = (sel: string) => document.querySelector(sel)?.getBoundingClientRect().top ?? -1
        return { last: list.lastElementChild?.classList.contains('set-version-foot'), foot: top('.set-version-foot'), cards: top('.set-card'), note: top('.set-foot') }
    })
    expect(order.last).toBe(true)
    expect(order.foot).toBeGreaterThan(order.cards)
    expect(order.foot).toBeGreaterThan(order.note)
    expect((await line.boundingBox())?.height ?? 0).toBeGreaterThanOrEqual(44)
    // A waiting version (the dev server's hook stands in for a second build): a chip beside the version, and in the menu.
    await page.evaluate(() => (window as unknown as { __e2eUpdateReady: () => void }).__e2eUpdateReady())
    await expect(page.locator('.set-list .version-state.available')).toHaveText('Update available')
    await picture(page).click()
    await expect(menu(page).getByRole('menuitem', { name: 'Update available' })).toBeVisible()
})

test('About has the full version line at its top, which a press copies and says so once', async ({ page, context, browserName }) => {
    if (browserName === 'chromium') await context.grantPermissions(['clipboard-read', 'clipboard-write'])
    await page.goto('/settings/about')
    const line = page.locator('.set-masthead [data-version-line]')
    await expect(line).toBeVisible()
    await expect(line).toHaveText(LINE)
    await expect(line.locator('.version-text')).toHaveAttribute('aria-live', 'polite')
    // 44 px to press, the line wraps rather than overflowing the page.
    const box = await line.boundingBox()
    expect(box?.height ?? 0).toBeGreaterThanOrEqual(44)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
    await line.click()
    await expect(line.locator('.version-text')).toHaveText('Version details copied')
    if (browserName === 'chromium') {
        const text = await page.evaluate(() => navigator.clipboard.readText())
        expect(text).toMatch(/^TMPlayer web \(\w{3,7}, \d{4}-\d{2}-\d{2}\) .+/)
        // Nothing of the account: not the name, the username, or a phone number.
        for (const secret of ['Dana', 'danatest', '+1']) expect(text).not.toContain(secret)
        expect(text).not.toMatch(/[\r\n]/)
    }
    await expect(line.locator('.version-text')).toHaveText(LINE, { timeout: 5000 })
})
