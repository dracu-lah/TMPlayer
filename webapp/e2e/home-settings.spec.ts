// Settings > Home (the apps' 8f55cf2 and c9a4522): a switch for each of Home's rows. Recently added
// follows the chats added in Add chats (2026-11-01), so Settings > Home has no picker of its own.
import { expect, test } from '@playwright/test'
import { fakeCase, noSizeFloor } from './helpers'

test.beforeEach(async ({ context, page }) => {
    await fakeCase(context)
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

test('Recently added follows the chats added in Add chats, with no picker of its own in Settings > Home', async ({ page }) => {
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
