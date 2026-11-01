// The chats gate (App.tsx): a signed in visitor who never chose which chats to watch chooses
// before anything else, over the fake Telegram. The `unanswered` case keeps the choice open
// (vite.e2e.ts answers it for every other test).
import { expect, test, type Page } from '@playwright/test'
import { fakeCase } from './helpers'

/** Signs in through the QR code and the two-step password (the fake scans the code at once). */
async function signIn(page: Page) {
    await page.goto('/')
    await page.locator('input[type=password]').fill('harbour')
    await page.locator('.signin form button.primary').click()
}

test('signing in opens the chats gate, with no Back and no way past it without a chat', async ({ page, context }) => {
    await fakeCase(context, 'signedout', 'password', 'unanswered')
    await signIn(page)
    await expect(page).toHaveURL(/\/added-chats\?setup=1&next=%2F$/)
    await expect(page.getByRole('heading', { name: 'Choose chats to watch' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Back' })).toHaveCount(0)
    await expect(page.getByRole('checkbox', { name: 'Test Clips' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Choose at least one chat' })).toBeDisabled()
    await page.getByRole('checkbox', { name: 'Test Clips' }).check()
    await page.getByRole('button', { name: 'Done' }).click()
    await expect(page).toHaveURL(/\/$/)
    await page.locator('nav a[href="/chats"]').first().click()
    await expect(page.getByRole('link', { name: /Test Clips/ })).toBeVisible()
    await expect(page.getByText('Group 0', { exact: true })).toHaveCount(0)
})

test('a visitor already signed in who never chose meets the gate on any page, then goes on to it', async ({ page, context }) => {
    await fakeCase(context, 'unanswered')
    await page.goto('/chats')
    await expect(page).toHaveURL(/\/added-chats\?setup=1&next=%2Fchats$/)
    await page.getByRole('checkbox', { name: 'Test Clips' }).check()
    await page.getByRole('button', { name: 'Done' }).click()
    await expect(page).toHaveURL(/\/chats$/)
    await page.reload()
    await expect(page).toHaveURL(/\/chats$/)
})

test('on the TV the gate opens with the remote in the search field', async ({ page, context, browserName }) => {
    test.skip(browserName !== 'chromium', 'the TV walk runs in Chromium')
    await fakeCase(context, 'unanswered')
    await page.goto('/__e2e/sizes')
    await page.evaluate(() => localStorage.setItem('tmplayer-layout', 'tv'))
    await page.goto('/')
    await expect(page).toHaveURL(/\/added-chats\?setup=1/)
    await expect.poll(() => page.evaluate(() => (document.activeElement as HTMLInputElement | null)?.type)).toBe('search')
    await expect(page.locator('.tv-bar')).toHaveCount(0)
})
