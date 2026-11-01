// History's edits on Watch later and the desktop's ways to tick cards (8124d0d, 8470ce8), over the fake Telegram.
import { expect, test } from '@playwright/test'
import { noSizeFloor, openClips, openDetail } from './helpers'

test('Watch later: Clear Watch later takes the list at once, and Undo puts it back', async ({ page }) => {
    await noSizeFloor(page)
    await openClips(page)
    await openDetail(page, 'Native Clip')
    await page.locator('.detail-round', { hasText: 'Watch later' }).click()
    await page.goto('/history/later')
    const card = page.locator('.card').filter({ hasText: 'Native Clip' })
    await expect(card).toHaveCount(1)
    await page.getByRole('button', { name: 'Clear Watch later' }).click()
    await expect(page.locator('.empty-state')).toBeVisible()
    await expect(page.getByText('Watch later cleared')).toBeVisible()
    await page.getByRole('button', { name: 'Undo' }).click()
    await expect(card).toHaveCount(1)
    // Select mode works here too: the ticked line leaves Watch later alone.
    await page.getByRole('button', { name: 'Select', exact: true }).click()
    await page.getByRole('checkbox', { name: 'Native Clip' }).check()
    await page.getByRole('button', { name: 'Remove (1)' }).click()
    await expect(page.getByText('Removed from Watch later')).toBeVisible()
    await expect(page.locator('.empty-state')).toBeVisible()
})

test('History on a computer: a Ctrl click ticks a card, the hover tick box too, Ctrl+A ticks all', async ({ page }) => {
    await noSizeFloor(page)
    await openClips(page)
    for (const name of ['Matroska Clip', 'Native Clip']) {
        await openDetail(page, name)
        await page.locator('.detail-round', { hasText: 'Watched' }).click()
        await page.locator('.detail-close').click()
    }
    await page.goto('/history/watched')
    const cards = page.locator('.card')
    await expect(cards).toHaveCount(2)
    await cards.first().click({ modifiers: ['ControlOrMeta'] })
    await expect(page).toHaveURL(/\/history\/watched$/)
    await expect(page.getByText('1 selected')).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(page.getByText('1 selected')).toHaveCount(0)
    await cards.last().hover()
    await page.locator('.hover-tick').last().click()
    await expect(page.getByText('1 selected')).toBeVisible()
    await page.keyboard.press('ControlOrMeta+a')
    await expect(page.getByText('2 selected')).toBeVisible()
})
