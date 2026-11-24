// WP-57: every route names the tab ("Chats - TMPlayer", the chat's or video's own name on its page),
// focus goes to the new page's heading after a navigation, and the keyboard has a way to the bar.
import { expect, test } from '@playwright/test'
import { noSizeFloor, openClips } from './helpers'

test('each route sets the tab title; a chat takes its own name', async ({ page }) => {
    await noSizeFloor(page)
    await page.goto('/')
    await expect(page).toHaveTitle('Home - TMPlayer')
    await page.goto('/chats')
    await expect(page).toHaveTitle('Chats - TMPlayer')
    await page.goto('/search')
    await expect(page).toHaveTitle('Search - TMPlayer')
    await page.goto('/settings')
    await expect(page).toHaveTitle('Settings - TMPlayer')
    await page.goto('/settings/storage')
    await expect(page).toHaveTitle(/ - TMPlayer$/)
    await openClips(page)
    await expect(page).toHaveTitle('Test Clips - TMPlayer')
    await page.goBack()
    await expect(page).toHaveTitle('Chats - TMPlayer')
})

test('a video page and the player are titled with the video', async ({ page }) => {
    await noSizeFloor(page)
    await openClips(page)
    await page.locator('.card').first().click()
    await expect(page).toHaveURL(/\/video\//)
    await expect(page).toHaveTitle(/^.+ - TMPlayer$/)
    expect(await page.title()).not.toBe('Chats - TMPlayer')
})

test('after a navigation focus is on the new page heading, not left on the bar', async ({ page }) => {
    await page.goto('/')
    await expect(page.locator('main h1').first()).toBeVisible()
    await page.locator('#main-nav a[href="/chats"]').click()
    await expect(page).toHaveURL(/\/chats$/)
    await expect(page.locator('main h1', { hasText: 'Chats' })).toBeFocused()
})

test('a field the page focused on purpose keeps the focus after a navigation', async ({ page }) => {
    await page.goto('/')
    await expect(page.locator('main h1').first()).toBeVisible()
    await page.locator('#main-nav a[href="/search"]').click()
    await expect(page).toHaveURL(/\/search/)
    const field = page.getByRole('searchbox').or(page.locator('main input[type=search], main input[type=text]')).first()
    await field.focus()
    await page.waitForTimeout(700)
    await expect(field).toBeFocused()
})

test('Skip to navigation is the first stop, seen on focus, and lands on the bar', async ({ page }) => {
    await page.goto('/chats')
    await expect(page.locator('main h1').first()).toBeVisible()
    await page.keyboard.press('Tab')
    const skip = page.getByRole('link', { name: 'Skip to navigation' })
    await expect(skip).toBeFocused()
    await expect(skip).toBeVisible()
    await page.keyboard.press('Enter')
    await expect(page.locator('#main-nav .nav-cell').first()).toBeFocused()
    expect(new URL(page.url()).hash).toBe('')
})
