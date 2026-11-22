// Settings > Home (the apps' 8f55cf2 and c9a4522): a switch for each of Home's rows. Recently added
// follows the chats added in Add chats (2026-11-01), so Settings > Home has no picker of its own.
import { expect, test } from '@playwright/test'
import { fakeCase, noSizeFloor } from './helpers'

// 'big': Home leaves out videos under 50 MB (SizeFilter.HOME_MIN), and the test files are tiny.
test.beforeEach(async ({ context, page }) => {
    await fakeCase(context, 'big')
    await noSizeFloor(page)
})

test('every row off leaves Home saying so, with the way to Home settings', async ({ page }) => {
    await page.goto('/settings/home')
    for (const name of [/^Carousel on Home/, /^Continue watching/, /^Recently added/, /^Starred chat rows/]) {
        const row = page.getByRole('switch', { name })
        await expect(row).toHaveAttribute('aria-checked', 'true')
        await row.click()
        await expect(row).toHaveAttribute('aria-checked', 'false')
    }
    await page.goto('/')
    await expect(page.getByRole('heading', { name: 'Home is empty' })).toBeVisible()
    await expect(page.locator('.home-row')).toHaveCount(0)
    await page.getByRole('link', { name: 'Home settings' }).click()
    await expect(page).toHaveURL(/\/settings\/home$/)
    // Kept after a reload, as every setting is.
    await page.reload()
    await expect(page.getByRole('switch', { name: /^Recently added/ })).toHaveAttribute('aria-checked', 'false')
})

test('Recently added follows the chats added in Add chats, with no picker of its own in Settings > Home', async ({ page, context }) => {
    // Chats picked (none yet), not All chats: with the switch on the ticks are disabled (HX86). Cleared first, as the
    // beforeEach's page already answered for this browser.
    await page.evaluate(() => new Promise<void>((resolve) => { const o = indexedDB.open('tmplayer-app'); o.onsuccess = () => { const tx = o.result.transaction('kv', 'readwrite'); tx.objectStore('kv').put('picked', 'addedMode'); tx.oncomplete = () => (o.result.close(), resolve()) } }))
    await page.goto('/settings/home')
    await expect(page.getByText(/^Recently added and the carousel show the newest videos of the chats you added/)).toBeVisible()
    await expect(page.getByRole('radio', { name: 'Chosen chats' })).toHaveCount(0)
    await page.goto('/added-chats')
    await page.getByRole('checkbox', { name: 'Test Clips' }).check()
    await page.getByRole('button', { name: 'Done' }).click()
    await page.goto('/')
    await expect(page.locator('.home-none-chosen')).toHaveCount(0)
    const recent = page.locator('.home-row').filter({ has: page.getByRole('heading', { name: /^Recently added/ }) })
    await expect(recent.locator('.card').first()).toBeVisible()
    // Every card in it is from the added chat (the facts line names the chat).
    const facts = await recent.locator('.card-facts').allTextContents()
    expect(facts.length).toBeGreaterThan(0)
    for (const f of facts) expect(f).toContain('Test Clips')
})

// R9: a row moved from the keyboard keeps the keyboard: Enter on Move down leaves the focus on that row's Move down.
test('moving a Home row from the keyboard keeps the focus on the row', async ({ page }) => {
    await page.goto('/settings/home')
    const titles = () => page.locator('.order-row .set-title').allTextContents()
    await expect(page.locator('.order-row').first()).toBeVisible()
    const before = await titles()
    const down = page.locator('.order-row').first().locator('.order-move').nth(1)
    await down.focus()
    await page.keyboard.press('Enter')
    await expect.poll(titles).not.toEqual(before)
    const after = await titles()
    expect(after[1]).toBe(before[0])
    const onRow = await page.evaluate(() => {
        const a = document.activeElement
        return a?.classList.contains('order-move') ? (a.closest('.order-row')?.querySelector('.set-title')?.textContent ?? '') : 'lost'
    })
    expect(onRow, 'the focus stays on the moved row').toBe(before[0])
})
