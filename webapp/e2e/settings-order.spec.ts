// WP-78: Settings lists its pages in the apps' order and by their names (Account, Playback, Subtitles and
// audio, Downloads and data, Appearance and language, Home and library, Help, About); an old address
// lands on the page that took the rows; a row that moved says "Moved to" at its old place for a release.
import { expect, test, type Page } from '@playwright/test'

const names = (page: Page) => page.locator('.set-list .set-card > a.set-row .set-title').allInnerTexts()

test('the list is in the apps order, with the apps names', async ({ page }) => {
    await page.goto('/settings')
    await expect(page.locator('.set-list')).toBeVisible()
    expect(await names(page)).toEqual(['Account', 'Playback', 'Subtitles and audio', 'Downloads and data', 'Appearance and language', 'Home and library', 'Help', 'About'])
})

test('Language is the first thing on Appearance and language', async ({ page }) => {
    await page.goto('/settings/appearance')
    const first = page.locator('.set-page .set-block-title, .set-bar ~ * .set-block-title').first()
    await expect(first).toHaveText(/Language/)
})

test('the old Privacy and lock address lands on Account with Sync across my devices', async ({ page }) => {
    await page.goto('/settings/privacy')
    await expect(page).toHaveURL(/\/settings\/account$/)
    await expect(page.getByRole('switch', { name: /Sync across my devices/ })).toBeVisible()
})

test('Playback says where online subtitles went, and a press goes there', async ({ page }) => {
    await page.goto('/settings/playback')
    const moved = page.locator('.moved-rows a.set-row').filter({ hasText: 'SubMaker' }).or(page.locator('.moved-rows a.set-row').first())
    await expect(moved.first()).toContainText('Moved to Subtitles and audio')
    await moved.first().click()
    await expect(page).toHaveURL(/\/settings\/subtitles#/)
    await expect(page.getByRole('heading', { name: /SubMaker|Online subtitles/ }).first()).toBeVisible()
})

test('About says where What is new and the reports went, and Help holds them', async ({ page }) => {
    await page.goto('/settings/about')
    await expect(page.locator('.moved-rows')).toContainText('Moved to Help')
    await page.goto('/settings/help')
    await expect(page.getByRole('link', { name: /What.s new|Changelog/ }).or(page.getByRole('button', { name: /What.s new|Report a problem/ })).first()).toBeVisible()
    await expect(page.locator('.moved-rows')).toHaveCount(0)
})

test('search still finds Sign out, Backup and Language, and a moved row by its words', async ({ page }) => {
    await page.goto('/settings')
    const box = page.getByRole('searchbox', { name: 'Search settings' })
    for (const [query, name] of [['sign out', /Sign out/], ['back up', /Back up/], ['language', /Language/], ['sync', /Sync across my devices/]] as const) {
        await box.fill(query)
        await expect(page.locator('.set-results .set-row').filter({ hasText: name }).first()).toBeVisible()
    }
})
