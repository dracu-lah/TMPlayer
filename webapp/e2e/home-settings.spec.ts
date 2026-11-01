// Settings > Home (the apps' 8f55cf2 and c9a4522): a switch for each of Home's rows, and Recently
// added from Automatic or Chosen chats, with a picker for the chats.
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

test('Chosen chats: none chosen says so in the row\'s place, and the picked chat alone feeds Recently added', async ({ page }) => {
    await page.goto('/settings/home')
    await page.getByRole('radio', { name: 'Chosen chats' }).check({ force: true })
    await page.goto('/')
    await expect(page.locator('.home-none-chosen')).toBeVisible()
    await page.locator('.home-none-chosen a').click()
    await expect(page).toHaveURL(/\/settings\/home$/)
    await page.getByRole('link', { name: /^Choose chats/ }).click()
    await expect(page).toHaveURL(/\/home-chats$/)
    await page.getByRole('checkbox', { name: 'Test Clips' }).check()
    await expect(page.locator('.select-count')).toHaveText('1 chat chosen')
    await page.getByRole('button', { name: 'Done' }).click()
    await expect(page).toHaveURL(/\/settings\/home$/)
    await page.goto('/')
    await expect(page.locator('.home-none-chosen')).toHaveCount(0)
    const recent = page.locator('.home-row').filter({ has: page.getByRole('heading', { name: /^Recently added/ }) })
    await expect(recent.locator('.card').first()).toBeVisible()
    // Every card in it is from the chosen chat (the facts line names the chat).
    const facts = await recent.locator('.card-facts').allTextContents()
    expect(facts.length).toBeGreaterThan(0)
    for (const f of facts) expect(f).toContain('Test Clips')
})
