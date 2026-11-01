// Which chats TMPlayer browses: the Add chats picker with the archive (HX27, HX32), over the fake Telegram.
import { expect, test } from '@playwright/test'

test('the picker reads the archive, and its filter lists only archived chats', async ({ page }) => {
    await page.goto('/added-chats')
    await expect(page.getByRole('checkbox', { name: 'Test Clips' })).toBeVisible()
    await page.getByRole('button', { name: 'Archived', exact: true }).click()
    await expect(page.getByRole('checkbox', { name: 'Archived Reels' })).toBeVisible()
    await expect(page.getByRole('checkbox', { name: 'Old Group' })).toBeVisible()
    await expect(page.getByRole('checkbox', { name: 'Test Clips' })).toHaveCount(0)
    await page.getByRole('button', { name: 'People', exact: true }).click()
    await expect(page.getByRole('checkbox', { name: 'Saved Messages' })).toBeVisible()
    await expect(page.getByRole('checkbox', { name: 'Archived Reels' })).toHaveCount(0)
})

test('an archived chat that was added is browsed in Chats', async ({ page }) => {
    await page.goto('/chats')
    await page.getByRole('link', { name: 'Add chats' }).click()
    await page.getByRole('button', { name: 'Archived', exact: true }).click()
    await page.getByRole('checkbox', { name: 'Archived Reels' }).check()
    await page.getByRole('button', { name: 'Done' }).click()
    await expect(page).toHaveURL(/\/chats$/)
    await expect(page.getByRole('link', { name: /Archived Reels/ })).toBeVisible()
    await expect(page.getByRole('link', { name: /Test Clips/ })).toHaveCount(0)
})

test('leaving the picker with ticks not saved asks first', async ({ page }) => {
    await page.goto('/chats')
    await page.getByRole('link', { name: 'Add chats' }).click()
    await page.getByRole('checkbox', { name: 'Test Clips' }).check()
    await page.getByRole('button', { name: 'Back' }).click()
    const ask = page.getByRole('dialog', { name: 'Discard changes?' })
    await expect(ask).toBeVisible()
    await ask.getByRole('button', { name: 'Cancel' }).click()
    await expect(page).toHaveURL(/\/added-chats$/)
    await expect(page.getByRole('checkbox', { name: 'Test Clips' })).toBeChecked()
    await page.getByRole('button', { name: 'Back' }).click()
    await ask.getByRole('button', { name: 'Discard' }).click()
    await expect(page).toHaveURL(/\/chats$/)
})

test('Select all over a thousand chats asks first', async ({ page }) => {
    await page.goto('/added-chats')
    await expect(page.getByRole('checkbox', { name: 'Test Clips' })).toBeVisible()
    await page.getByRole('button', { name: 'Select all' }).click()
    const ask = page.getByRole('dialog', { name: /^Select [\d,]+ chats\?$/ })
    await expect(ask).toBeVisible()
    await ask.getByRole('button', { name: 'Select all' }).click()
    await expect(page.getByRole('checkbox', { name: 'Test Clips' })).toBeChecked()
    await expect(page.getByRole('button', { name: 'Clear' })).toBeVisible()
})
