// HX59: what cannot be undone asks first, in one shared dialog (src/ui/Confirm.tsx): it says what
// goes and what stays, puts the focus on Cancel, and Escape, Cancel, the backdrop and the TV's Back
// all keep everything. The questions behind it: Clear history, Clear Watch later, Clear cache,
// Delete list, Sign out. Run on the phone, the desktop and Safari forms; the TV has its own walk below.
import { mkdirSync } from 'node:fs'
import { expect, test, type Page, type TestInfo } from '@playwright/test'
import { fakeCase, noSizeFloor, openClips, openDetail, openTvSettingsPage } from './helpers'

const SHOTS = `${process.cwd()}/../design/web-gaps/WB8`

async function shot(page: Page, info: TestInfo, name: string) {
    mkdirSync(SHOTS, { recursive: true })
    await page.screenshot({ path: `${SHOTS}/${name}-${info.project.name}.png` })
}

const dialog = (page: Page) => page.locator('dialog[open]')
/** The words of what has the focus. */
const focusedText = (page: Page) => page.evaluate(() => (document.activeElement as HTMLElement | null)?.textContent?.trim() ?? '')

async function watched(page: Page, name = 'Native Clip') {
    await noSizeFloor(page)
    await openClips(page)
    await openDetail(page, name)
    await page.locator('.detail-round', { hasText: 'Watched' }).click()
}

test('Clear history asks first: focus on Cancel, Escape and Cancel keep it, the answer clears, Undo follows', async ({ page }, info) => {
    await watched(page)
    await page.goto('/history/watched')
    await expect(page.locator('.card')).toHaveCount(1)
    await page.goto('/settings/library')
    const row = page.getByRole('button', { name: /^Clear history/ })
    await row.click()
    const ask = page.getByRole('dialog', { name: 'Clear history?' })
    await expect(ask).toBeVisible()
    await expect(ask).toContainText('Nothing is deleted from Telegram')
    await expect(ask).toContainText('Watch later is kept')
    expect(await focusedText(page)).toBe('Cancel')
    await shot(page, info, 'clear-history')
    // Enter on the open question is Cancel's: nothing goes.
    await page.keyboard.press('Enter')
    await expect(dialog(page)).toHaveCount(0)
    await row.click()
    await expect(dialog(page)).toHaveCount(1)
    await page.keyboard.press('Escape')
    await expect(dialog(page)).toHaveCount(0)
    await row.click()
    await dialog(page).getByRole('button', { name: 'Cancel' }).click()
    await expect(dialog(page)).toHaveCount(0)
    await page.goto('/history/watched')
    await expect(page.locator('.card')).toHaveCount(1)
    // Confirming clears, with the toast's Undo still there.
    await page.goto('/settings/library')
    await row.click()
    await dialog(page).getByRole('button', { name: 'Clear history' }).click()
    await expect(dialog(page)).toHaveCount(0)
    await expect(page.getByText('History cleared')).toBeVisible()
    await page.getByRole('button', { name: 'Undo' }).click()
    await page.goto('/history/watched')
    await expect(page.locator('.card')).toHaveCount(1)
})

test('Clear history with nothing in it asks nothing', async ({ page }) => {
    await page.goto('/settings/library')
    await page.getByRole('button', { name: /^Clear history/ }).click()
    await expect(dialog(page)).toHaveCount(0)
    await expect(page.getByText('History cleared')).toBeVisible()
})

test('Clear Watch later asks first; Cancel keeps the list, the answer takes it, Undo still follows', async ({ page }, info) => {
    await noSizeFloor(page)
    await openClips(page)
    await openDetail(page, 'Native Clip')
    await page.locator('.detail-round', { hasText: 'Watch later' }).click()
    await page.goto('/history/later')
    const card = page.locator('.card').filter({ hasText: 'Native Clip' })
    await expect(card).toHaveCount(1)
    await page.getByRole('button', { name: 'Clear Watch later' }).click()
    await expect(page.getByRole('dialog', { name: 'Clear Watch later?' })).toBeVisible()
    expect(await focusedText(page)).toBe('Cancel')
    await shot(page, info, 'clear-watch-later')
    await dialog(page).getByRole('button', { name: 'Cancel' }).click()
    await expect(card).toHaveCount(1)
    await page.getByRole('button', { name: 'Clear Watch later' }).click()
    await dialog(page).getByRole('button', { name: 'Clear Watch later' }).click()
    await expect(page.locator('.empty-state')).toBeVisible()
    await page.getByRole('button', { name: 'Undo' }).click()
    await expect(card).toHaveCount(1)
})

test('a question closes itself when what it is about is gone', async ({ page }) => {
    await noSizeFloor(page)
    await openClips(page)
    await openDetail(page, 'Native Clip')
    await page.locator('.detail-round', { hasText: 'Watch later' }).click()
    await page.goto('/history/later')
    await expect(page.locator('.card')).toHaveCount(1)
    await page.getByRole('button', { name: 'Clear Watch later' }).click()
    await expect(dialog(page)).toHaveCount(1)
    // Another tab, or the sync, empties the list under the open question.
    await page.evaluate(async () => {
        const where = '/src/store.ts'
        const store = (await import(/* @vite-ignore */ where)) as { removeLater: (keys: null) => Promise<unknown> }
        await store.removeLater(null)
    })
    await expect(dialog(page)).toHaveCount(0)
})

test('Delete list asks first with how many videos go; Cancel keeps it', async ({ page }, info) => {
    await page.goto('/lists')
    await page.getByRole('button', { name: 'New list' }).click()
    await page.getByLabel('List name').fill('Weekend')
    await page.getByRole('button', { name: 'Create' }).click()
    await expect(page.getByRole('link', { name: 'Weekend' })).toBeVisible()
    await page.getByRole('button', { name: 'Delete list' }).click()
    const ask = page.getByRole('dialog', { name: 'Delete Weekend?' })
    await expect(ask).toBeVisible()
    await expect(ask).toContainText('stay in their chats')
    expect(await focusedText(page)).toBe('Cancel')
    await shot(page, info, 'delete-list')
    await page.keyboard.press('Escape')
    await expect(page.getByRole('link', { name: 'Weekend' })).toBeVisible()
    await page.getByRole('button', { name: 'Delete list' }).click()
    await dialog(page).getByRole('button', { name: 'Delete' }).click()
    await expect(page.getByRole('link', { name: 'Weekend' })).toHaveCount(0)
})

test('Clear cache says how much goes and that the account stays; Cancel keeps it', async ({ page, browserName }, info) => {
    test.skip(browserName !== 'chromium', 'the estimate is read in Chromium')
    await page.goto('/settings/storage')
    const clear = page.getByRole('button', { name: 'Clear cache' })
    await expect(clear).toBeVisible()
    await clear.click()
    const ask = page.getByRole('dialog', { name: 'Clear pictures and previews?' })
    await expect(ask).toBeVisible()
    await expect(ask).toContainText('untouched')
    expect(await focusedText(page)).toBe('Cancel')
    await shot(page, info, 'clear-cache')
    await page.keyboard.press('Escape')
    await expect(dialog(page)).toHaveCount(0)
    await clear.click()
    await dialog(page).getByRole('button', { name: 'Clear cache' }).click()
    await expect(dialog(page)).toHaveCount(0)
})

test('Sign out: focus on Cancel, Escape keeps the session', async ({ page }) => {
    await page.goto('/settings')
    await page.getByRole('button', { name: 'Sign out of Telegram' }).click()
    await expect(page.getByRole('dialog', { name: 'Sign out of Telegram as Dana Test?' })).toBeVisible()
    expect(await focusedText(page)).toBe('Cancel')
    await page.keyboard.press('Escape')
    await expect(dialog(page)).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Sign out of Telegram' })).toBeVisible()
    await expect(page).toHaveURL(/\/settings/)
})

test('Sync file: Replace and Turn off and delete ask first; Escape and Cancel keep the file, the answer changes it', async ({ context, page, browserName }, info) => {
    test.skip(browserName !== 'chromium', 'the sync runs in Chromium')
    const key = `k${Math.random().toString(36).slice(2, 10)}`
    await fakeCase(context, 'big', `cloud:${key}`)
    await noSizeFloor(page)
    const cloud = async (op: string, body?: unknown) =>
        (await (await page.request.post(`/__e2e/cloud?key=${key}&op=${op}`, { data: body })).json()) as { id: number; bytes: string }[]
    // A file in Saved Messages that this version cannot read: sync says so and offers Replace.
    const garbage = Buffer.from('not a sync file').toString('base64')
    await cloud('put', { date: Date.now(), text: '#TMPlayerSyncFile\n', fileName: 'tmplayer-sync.json.gz', bytes: garbage })
    await page.goto('/settings/privacy')
    const sw = page.getByRole('switch', { name: /^Sync across my devices/ })
    await sw.click()
    await page.getByRole('dialog', { name: 'Sync across my devices?' }).getByRole('button', { name: 'Yes, sync it' }).click()
    const replace = page.getByRole('button', { name: 'Replace the sync file', exact: true })
    await expect(replace).toBeVisible({ timeout: 20_000 })
    await replace.click()
    await expect(page.getByRole('dialog', { name: 'Replace the sync file?' })).toBeVisible()
    expect(await focusedText(page)).toBe('Cancel')
    await shot(page, info, 'replace-sync')
    await page.keyboard.press('Escape')
    await expect(dialog(page)).toHaveCount(0)
    await replace.click()
    await dialog(page).getByRole('button', { name: 'Cancel' }).click()
    expect((await cloud('list'))[0].bytes, 'the unreadable file is still there').toBe(garbage)
    await replace.click()
    await dialog(page).getByRole('button', { name: 'Replace the sync file' }).click()
    await expect.poll(async () => (await cloud('list'))[0]?.bytes, { timeout: 20_000 }).not.toBe(garbage)
    // Turn off and delete: Cancel keeps the file and the switch.
    const erase = page.getByRole('button', { name: /^Turn off and delete the sync file/ })
    await erase.click()
    await expect(page.getByRole('dialog', { name: 'Turn off and delete the sync file?' })).toBeVisible()
    expect(await focusedText(page)).toBe('Cancel')
    await page.keyboard.press('Enter')
    await expect(dialog(page)).toHaveCount(0)
    await expect(sw).toHaveAttribute('aria-checked', 'true')
    expect(await cloud('list')).toHaveLength(1)
    await erase.click()
    await dialog(page).getByRole('button', { name: 'Turn off and delete the sync file' }).click()
    await expect(sw).toHaveAttribute('aria-checked', 'false')
    await expect.poll(async () => (await cloud('list')).length).toBe(0)
})

/** The layout set to TV, written where the app keeps it before it starts (as tv.spec does). */
async function tv(page: Page) {
    await page.goto('/__e2e/sizes')
    await page.evaluate(
        () =>
            new Promise<void>((resolve, reject) => {
                localStorage.setItem('tmplayer-layout', 'tv')
                const open = indexedDB.open('tmplayer-app')
                open.onupgradeneeded = () => open.result.createObjectStore('kv')
                open.onerror = () => reject(open.error)
                open.onsuccess = () => {
                    const tx = open.result.transaction('kv', 'readwrite')
                    const kv = tx.objectStore('kv')
                    kv.put({ sizeMin: 0 }, 'settings')
                    kv.put(1, 'watched:-1001:1')
                    tx.oncomplete = () => (open.result.close(), resolve())
                }
            }),
    )
}

test.describe('TV remote', () => {
    test.skip(({ browserName }) => browserName !== 'chromium', 'the TV walk runs in Chromium')
    test.use({ viewport: { width: 1920, height: 1080 } })

    test('Clear history: OK opens the question on Cancel, Back keeps everything and returns to the button, Right then OK clears', async ({ page, context }, info) => {
        await fakeCase(context)
        await tv(page)
        await page.goto('/settings')
        const press = async (key: string, times = 1) => {
            for (let i = 0; i < times; i++) {
                await page.keyboard.press(key)
                await page.waitForTimeout(150)
            }
        }
        const label = () => page.evaluate(() => document.activeElement?.getAttribute('aria-labelledby') ?? '')
        await expect.poll(() => page.evaluate(() => document.activeElement?.tagName ?? '')).not.toBe('BODY')
        // Clear history is on the Home and library page of the rail (WP-88): open it, then walk down to the button.
        await openTvSettingsPage(page, 'home')
        for (let i = 0; i < 60 && (await label()) !== 'history-label'; i++) await press('ArrowDown')
        expect(await label()).toBe('history-label')
        await press('Enter')
        await expect(page.getByRole('dialog', { name: 'Clear history?' })).toBeVisible()
        expect(await focusedText(page)).toBe('Cancel')
        await shot(page, info, 'clear-history-tv')
        // Back is Cancel, and the remote is back on the button that asked.
        await press('Backspace')
        await expect(dialog(page)).toHaveCount(0)
        expect(await label()).toBe('history-label')
        await expect(page.locator('.toast')).toHaveCount(0)
        // OK, Right to the red button, OK.
        await press('Enter')
        await expect(dialog(page)).toHaveCount(1)
        await press('ArrowRight')
        expect(await focusedText(page)).toBe('Clear history')
        await press('Enter')
        await expect(dialog(page)).toHaveCount(0)
        await expect(page.locator('.toast')).toContainText('History cleared')
    })

    test('Remove selected asks first on the TV: OK on Cancel keeps the ticked cards, the red button removes them', async ({ page, context }, info) => {
        await fakeCase(context)
        await tv(page)
        await page.goto('/history/watched')
        const cards = page.locator('.card')
        await expect(cards).toHaveCount(1)
        await page.getByRole('button', { name: 'Select', exact: true }).click()
        const ticks = page.getByRole('checkbox')
        await ticks.first().evaluate((el: HTMLInputElement) => el.click())
        await page.getByRole('button', { name: 'Remove (1)' }).click()
        await expect(page.getByRole('dialog', { name: 'Remove 1 video?' })).toBeVisible()
        expect(await focusedText(page)).toBe('Cancel')
        await shot(page, info, 'remove-selected-tv')
        await page.keyboard.press('Enter')
        await expect(dialog(page)).toHaveCount(0)
        await expect(ticks).toHaveCount(1)
        await page.getByRole('button', { name: 'Remove (1)' }).click()
        await page.keyboard.press('ArrowRight')
        await page.keyboard.press('Enter')
        await expect(dialog(page)).toHaveCount(0)
        await expect(page.getByText('Removed from history')).toBeVisible()
        await expect(ticks).toHaveCount(0)
    })

    test('Sign out: OK on Cancel keeps the session, Back too', async ({ page, context }) => {
        await fakeCase(context)
        await tv(page)
        await page.goto('/settings')
        const out = page.locator('.danger-button')
        await out.focus()
        await page.keyboard.press('Enter')
        await expect(page.getByRole('dialog', { name: 'Sign out of Telegram as Dana Test?' })).toBeVisible()
        expect(await focusedText(page)).toBe('Cancel')
        await page.keyboard.press('Enter')
        await expect(dialog(page)).toHaveCount(0)
        await expect(out).toBeVisible()
        await expect(page).toHaveURL(/\/settings/)
    })
})
