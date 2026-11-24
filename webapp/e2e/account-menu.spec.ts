// WP-103: the account's picture on the bar and its menu, at the phone's and the desktop's widths
// (FORM_SPECS runs it at 390, 412 and 1280 px). The button is named "Account, <name>" and says it
// opens a menu; it draws the profile's saved photo, else the letter, else the person glyph, never a
// blank disc. The menu names whose account it is, offers Add account, Settings, the lists and Sign
// out (which asks first), and is walked with the arrows; Escape gives the keyboard back to the picture.
import { expect, test, type Page } from '@playwright/test'
import { fakeCase } from './helpers'

/** A 1 px PNG, a photo as the picker keeps it (cleanPhoto accepts it). */
const PHOTO = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='

const picture = (page: Page) => page.locator('[data-you]')
const menu = (page: Page) => page.getByRole('menu')
const focusedText = (page: Page) => page.evaluate(() => (document.activeElement as HTMLElement | null)?.textContent?.trim() ?? '')

test('the picture is named for the account, says it opens a menu, and draws the letter', async ({ page }) => {
    await page.goto('/')
    const you = picture(page)
    await expect(you).toHaveAccessibleName('Account, Dana Test')
    await expect(you).toHaveAttribute('aria-haspopup', 'menu')
    await expect(you.locator('.nav-picture')).toHaveAttribute('data-picture', 'initial')
    await expect(you.locator('.nav-picture')).toHaveText('D')
    // No visible label (a long name never fits one): the name is its accessible name and its tooltip.
    await expect(you.locator('.nav-label')).toHaveCount(0)
    await expect(you).toHaveAttribute('title', 'Account, Dana Test')
    await expect(page.locator('.nav-picture-blank')).toHaveCount(0)
})

test('a saved photo is drawn in place of the letter, on the bar and in the menu', async ({ page }) => {
    await page.addInitScript((photo) => localStorage.setItem('tmplayer-account-photo:main', photo), PHOTO)
    await page.goto('/')
    const art = picture(page).locator('.nav-picture')
    await expect(art).toHaveAttribute('data-picture', 'photo')
    await expect(art.locator('img')).toHaveAttribute('src', PHOTO)
    await picture(page).click()
    await expect(menu(page).locator('.you-picture img')).toHaveAttribute('src', PHOTO)
})

test('a saved photo the browser cannot draw falls back to the letter', async ({ page }) => {
    await page.addInitScript(() => localStorage.setItem('tmplayer-account-photo:main', 'data:image/png;base64,AAAA'))
    await page.goto('/')
    const art = picture(page).locator('.nav-picture')
    await expect(art).toHaveAttribute('data-picture', 'initial')
    await expect(art).toHaveText('D')
})

test('an account with no name gets the person glyph and the plain name, never a blank disc', async ({ context, page }) => {
    await fakeCase(context, 'noname')
    await page.goto('/')
    const you = picture(page)
    await expect(you).toHaveAccessibleName('Account')
    await expect(you.locator('.nav-picture')).toHaveAttribute('data-picture', 'glyph')
    await expect(you.locator('.nav-picture svg')).toHaveCount(1)
    await expect(page.locator('.nav-picture-blank')).toHaveCount(0)
})

test('Enter opens the menu with whose account it is, the arrows walk it, Escape gives the picture the keyboard back', async ({ page }) => {
    await page.goto('/')
    await picture(page).focus()
    await page.keyboard.press('Enter')
    const card = menu(page)
    await expect(card).toBeVisible()
    await expect(card).toHaveAccessibleName('Account, Dana Test')
    await expect(card.locator('.you-who')).toContainText('Dana Test')
    await expect(card.locator('.you-who')).toContainText('@danatest')
    // The first tile has the keyboard; the three tiles are one row (Left and Right), then Settings, Add account and the version.
    expect(await focusedText(page)).toContain('Favourites')
    await page.keyboard.press('ArrowRight')
    expect(await focusedText(page)).toContain('History')
    await page.keyboard.press('ArrowRight')
    expect(await focusedText(page)).toContain('Watch later')
    await page.keyboard.press('ArrowRight')
    expect(await focusedText(page)).toContain('Watch later')
    await page.keyboard.press('ArrowDown')
    expect(await focusedText(page)).toContain('Settings')
    await page.keyboard.press('ArrowDown')
    expect(await focusedText(page)).toBe('Add account')
    await page.keyboard.press('ArrowUp')
    await page.keyboard.press('ArrowUp')
    expect(await focusedText(page)).toContain('Watch later')
    // End is the menu's last line, the compact version (WP-104); Add account is the one above it.
    await page.keyboard.press('End')
    expect(await focusedText(page)).toMatch(/^(v\d+\.\d+(\.\d+)?|web)$/)
    await page.keyboard.press('ArrowUp')
    expect(await focusedText(page)).toBe('Add account')
    await page.keyboard.press('Escape')
    await expect(card).toHaveCount(0)
    await expect(picture(page)).toBeFocused()
})

test('the menu has no Sign out (Settings has it, and asks first)', async ({ page }) => {
    await page.goto('/')
    await picture(page).click()
    await expect(menu(page).getByRole('menuitem', { name: /Sign out/ })).toHaveCount(0)
    await menu(page).getByRole('menuitem', { name: /^Settings/ }).click()
    await expect(page).toHaveURL(/\/settings(\/[a-z-]+)?$/)
    await page.getByRole('button', { name: 'Sign out of Telegram' }).click()
    await expect(page.getByRole('dialog', { name: 'Sign out of Telegram as Dana Test?' })).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(page.locator('dialog[open]')).toHaveCount(0)
    await expect(picture(page)).toBeVisible()
})

test('Settings from the menu opens Settings', async ({ page }) => {
    await page.goto('/')
    await picture(page).click()
    await menu(page).getByRole('menuitem', { name: /^Settings/ }).click()
    // The desktop opens Settings on its first page.
    await expect(page).toHaveURL(/\/settings(\/[a-z-]+)?$/)
})
