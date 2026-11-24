// WP-54: the iPhone's edge swipe back (history Back) closes a sheet instead of the page under it.
// A card's menu, the list picker it opens, and Escape taking its entry off again; focus returns to the
// card that opened the menu (ui/modalHistory.ts).
import { expect, test, type Locator, type Page } from '@playwright/test'
import { noSizeFloor, openClips } from './helpers'

/** A card's menu: a long press with a finger on a phone, a right click elsewhere (CardMenu.tsx). */
async function menuOf(page: Page, card: Locator) {
    await card.focus()
    const touch = await page.evaluate(() => matchMedia('(pointer: coarse)').matches)
    if (!touch) return card.click({ button: 'right' })
    const box = (await card.boundingBox())!
    const at = { clientX: box.x + 20, clientY: box.y + 20, pointerType: 'touch', isPrimary: true }
    await card.dispatchEvent('pointerdown', at)
    await page.waitForTimeout(650)
    await card.dispatchEvent('pointerup', at)
}

const menu = (page: Page) => page.locator('dialog.card-menu[open]')

async function menuOnFirstCard(page: Page) {
    await noSizeFloor(page)
    await openClips(page)
    const url = page.url()
    const card = page.locator('.card').first()
    await expect(card).toBeVisible()
    await menuOf(page, card)
    await expect(menu(page)).toBeVisible()
    return { url, card }
}

test('Back closes the card menu and the page stays, with focus on the card that opened it', async ({ page }) => {
    const { url, card } = await menuOnFirstCard(page)
    await page.goBack()
    await expect(page.locator('dialog[open]')).toHaveCount(0)
    expect(page.url()).toBe(url)
    await expect(card).toBeVisible()
    await expect(page.locator('.card').first()).toBeFocused()
})

test('a menu closed by Escape leaves no entry behind: Back leaves the page', async ({ page }) => {
    const { url } = await menuOnFirstCard(page)
    await page.keyboard.press('Escape')
    await expect(page.locator('dialog[open]')).toHaveCount(0)
    await expect.poll(() => page.evaluate(() => (history.state as Record<string, unknown> | null)?.tmModal ?? null)).toBeNull()
    expect(page.url()).toBe(url)
    await page.goBack()
    await expect(page).toHaveURL(/\/chats/)
})

test('the list picker opened from the menu closes on Back and takes the one entry it shares with the menu', async ({ page }) => {
    const { url } = await menuOnFirstCard(page)
    await menu(page).getByRole('button', { name: /Add to a list/ }).click()
    await expect(page.locator('dialog.card-menu[open]')).toHaveCount(1)
    await expect(page.locator('dialog[open][aria-label="Add to a list"]')).toBeVisible()
    await page.goBack()
    await expect(page.locator('dialog[open]')).toHaveCount(0)
    expect(page.url()).toBe(url)
    // The entry is gone with the dialogs: the next Back is the previous page.
    await page.goBack()
    await expect(page).toHaveURL(/\/chats/)
})

test('a menu row that opens a page closes the menu and leaves no entry under it', async ({ page }) => {
    await menuOnFirstCard(page)
    await menu(page).getByRole('button', { name: 'Details' }).click()
    await expect(page).toHaveURL(/\/video\//)
    await expect(page.locator('dialog[open]')).toHaveCount(0)
    await page.goBack()
    await expect(page).toHaveURL(/\/chat\/-1001/)
    await page.goBack()
    await expect(page).toHaveURL(/\/chats/)
})
