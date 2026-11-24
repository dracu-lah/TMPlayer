// WP-56: Safari clears a tab's storage after 7 days. A signed in iPhone tab gets one card on Home, a
// row in Settings that stays, and a way to keep the data: the Home Screen steps, resume sync on, and
// a backup file the person keeps (Back up, Restore) in the apps' format.
import { readFileSync } from 'node:fs'
import { expect, test } from '@playwright/test'

const IPHONE_TAB = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1'

test.describe('an iPhone tab', () => {
    test.use({ userAgent: IPHONE_TAB, viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true })

    test('the card says Safari clears the site, offers the Home Screen and resume sync, and Not now closes it for good', async ({ page }) => {
        await page.goto('/')
        const card = page.locator('.safari-clears')
        await expect(card.getByRole('heading', { name: 'Safari clears this site after 7 days unused' })).toBeVisible()
        await expect(card).toContainText('Add TMPlayer to the Home Screen')
        await expect(card.getByRole('button', { name: 'Keep your places in Saved Messages' })).toBeVisible()
        await card.getByRole('button', { name: 'Not now' }).click()
        await expect(card).toHaveCount(0)
        await page.reload()
        await expect(page.locator('.home-title, .home-row, .boot, main').first()).toBeVisible()
        await expect(page.locator('.safari-clears')).toHaveCount(0)
    })

    test('Settings keeps the warning as a row after the card is closed', async ({ page }) => {
        await page.goto('/')
        await page.locator('.safari-clears').getByRole('button', { name: 'Not now' }).click()
        await page.goto('/settings/storage')
        await expect(page.locator('#safari-clears').getByRole('heading', { name: 'Safari clears this site after 7 days unused' })).toBeVisible()
    })
})

test.describe('Back up and Restore', () => {
    test.use({ viewport: { width: 1280, height: 800 } })

    test('a backup is a tmplayer-backup file; restoring it again changes nothing, and garbage is refused', async ({ page }, info) => {
        await page.goto('/settings/storage')
        const downloading = page.waitForEvent('download')
        await page.getByRole('button', { name: 'Back up' }).click()
        const file = await downloading
        expect(file.suggestedFilename()).toMatch(/^tmplayer-backup-\d{4}-\d{2}-\d{2}\.json$/)
        const path = info.outputPath('backup.json')
        await file.saveAs(path)
        const json = JSON.parse(readFileSync(path, 'utf8'))
        expect(json.format).toBe('tmplayer-backup')
        expect([1, 2]).toContain(json.version)
        expect(JSON.stringify(json)).not.toMatch(/auth_key|authKey|session|token/i)

        const pick = page.locator('input[type=file][accept*=json]')
        await pick.setInputFiles(path)
        await expect(page.locator('.toast')).toHaveText('This device already has everything in the backup.')

        await pick.setInputFiles({ name: 'x.json', mimeType: 'application/json', buffer: Buffer.from('{"hello":1}') })
        await expect(page.locator('.toast')).toHaveText('That file is not a TMPlayer backup.')
        await pick.setInputFiles({ name: 'y.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify({ format: 'tmplayer-backup', version: 9 })) })
        await expect(page.locator('.toast')).toHaveText('This backup comes from a newer TMPlayer. Update TMPlayer, then restore it again.')
    })

    test('a backup with a favourite and a place restores after the question, and not before', async ({ page }) => {
        await page.goto('/settings/storage')
        const backup = {
            format: 'tmplayer-backup',
            version: 1,
            createdAt: Date.now(),
            app: '1.9.0',
            settings: {},
            metadata: {},
            favourites: [-1009999999],
            recentSearches: [],
            memory: {},
            resume: [{ chat: -1009999999, message: 3 * 1048576, file: 5, title: 'Harbour', chatTitle: 'C', size: 1, durationSec: 60, positionMs: 20_000, durationMs: 60_000, updatedAt: Date.now() }],
            watched: [],
            watchLater: [],
            bookmarks: [],
        }
        await page.locator('input[type=file][accept*=json]').setInputFiles({ name: 'z.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(backup)) })
        const ask = page.locator('dialog.confirm-ask[open]')
        await expect(ask).toContainText('1 video gets back where you stopped.')
        await expect(ask).toContainText('1 chat gets its star.')
        await ask.getByRole('button', { name: 'Cancel' }).click()
        await expect(page.locator('.toast')).toHaveCount(0)
        await page.locator('input[type=file][accept*=json]').setInputFiles({ name: 'z.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(backup)) })
        await page.locator('dialog.confirm-ask[open]').getByRole('button', { name: 'Restore' }).click()
        await expect(page.locator('.toast')).toHaveText('Backup restored')
    })
})
