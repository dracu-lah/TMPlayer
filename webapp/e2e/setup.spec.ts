// The first run's "Your chats" step after signing in (995dd13), over the fake Telegram.
import { expect, test, type Page } from '@playwright/test'
import { fakeCase } from './helpers'

/** Signs in through the QR code and the two-step password (the fake scans the code at once). */
async function signIn(page: Page) {
    await page.goto('/')
    await page.locator('input[type=password]').fill('harbour')
    await page.locator('.signin form button.primary').click()
}

test('signing in asks once which chats to browse, and Browse everything goes on', async ({ page, context }) => {
    await fakeCase(context, 'signedout', 'password')
    await signIn(page)
    await expect(page).toHaveURL(/\/setup\/chats\?next=%2F$/)
    await expect(page.getByRole('heading', { name: 'Your chats' })).toBeVisible()
    await expect(page.getByRole('radio', { name: /Browse everything/ })).toBeChecked()
    await page.getByRole('button', { name: 'Next' }).click()
    await expect(page).toHaveURL(/\/$/)
})

test('Choose chats opens the picker, and Done goes on with only those chats', async ({ page, context }) => {
    await fakeCase(context, 'signedout', 'password')
    await signIn(page)
    await page.getByRole('radio', { name: /Choose chats/ }).check()
    await page.getByRole('button', { name: 'Next' }).click()
    await expect(page).toHaveURL(/\/added-chats\?setup=1/)
    await page.getByRole('checkbox', { name: 'Test Clips' }).check()
    await page.getByRole('button', { name: 'Done' }).click()
    await expect(page).toHaveURL(/\/$/)
    await page.locator('nav a[href="/chats"]').first().click()
    await expect(page.getByRole('link', { name: /Test Clips/ })).toBeVisible()
    await expect(page.getByText('Group 0', { exact: true })).toHaveCount(0)
})
