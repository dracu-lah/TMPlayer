// WP-59: recent searches under the empty box (ten, one tap runs one again, remove, Clear, the
// Remember searches switch), Share on a video's card menu and page (the browser's share sheet, stubbed),
// and History in the bar's fourth place.
import { expect, test, type Locator, type Page } from '@playwright/test'
import { fakeCase, noSizeFloor } from './helpers'

const box = (page: Page) => page.getByRole('searchbox')
const recents = (page: Page) => page.locator('.search-recents')

async function search(page: Page, words: string) {
    await box(page).fill(words)
    await expect(page).toHaveURL(new RegExp(`q=${encodeURIComponent(words).replace(/%20/g, '(%20|\\+)')}`))
}

test.beforeEach(async ({ page }) => {
    await noSizeFloor(page)
})

test('a search is remembered, run again with one tap, removed one by one and cleared', async ({ page }) => {
    await page.goto('/search')
    await expect(recents(page)).toHaveCount(0)
    await search(page, 'Clip')
    await search(page, 'Harbour')
    await page.goto('/search')
    await expect(recents(page).getByRole('heading', { name: 'Recent searches' })).toBeVisible()
    await expect(recents(page).locator('.recent-run')).toHaveText(['Harbour', 'Clip'])
    await recents(page).getByRole('button', { name: 'Clip', exact: true }).click()
    await expect(box(page)).toHaveValue('Clip')
    await expect(page).toHaveURL(/q=Clip/)
    // A box with words has no list under it; emptied, the list is back with Clip in front.
    await expect(recents(page)).toHaveCount(0)
    await box(page).fill('')
    await expect(recents(page).locator('.recent-run')).toHaveText(['Clip', 'Harbour'])
    await recents(page).getByRole('button', { name: 'Remove “Harbour” from recent searches' }).click()
    await expect(recents(page).locator('.recent-run')).toHaveText(['Clip'])
    await recents(page).getByRole('button', { name: 'Clear recent searches' }).click()
    await expect(page.locator('.toast')).toHaveText('Recent searches cleared')
    await expect(recents(page)).toHaveCount(0)
})

test('only ten are kept, newest first, and the same words in another case are one', async ({ page }) => {
    await page.goto('/search')
    for (let i = 0; i < 12; i++) await search(page, `word${i}`)
    await search(page, 'WORD5')
    await page.goto('/search')
    const chips = recents(page).locator('.recent-run')
    await expect(chips).toHaveCount(10)
    await expect(chips.first()).toHaveText('WORD5')
    await expect(recents(page).getByText('word5', { exact: true })).toHaveCount(0)
})

test('with Remember searches off nothing is kept, and turning it off forgets what was', async ({ page }) => {
    await page.goto('/search')
    await search(page, 'Clip')
    await page.goto('/settings/home')
    await page.getByRole('switch', { name: /Remember searches/ }).click()
    await page.goto('/search')
    await expect(recents(page)).toHaveCount(0)
    await search(page, 'Harbour')
    await page.goto('/search')
    await expect(recents(page)).toHaveCount(0)
})

test('a stored list that is garbage reads as none', async ({ page }) => {
    await page.goto('/__e2e/sizes')
    await page.evaluate(
        () =>
            new Promise<void>((resolve, reject) => {
                const open = indexedDB.open('tmplayer-app')
                open.onerror = () => reject(open.error)
                open.onsuccess = () => {
                    const tx = open.result.transaction('kv', 'readwrite')
                    tx.objectStore('kv').put('{"not":"a list"', 'recents')
                    tx.oncomplete = () => (open.result.close(), resolve())
                }
            }),
    )
    await page.goto('/search')
    await expect(box(page)).toBeVisible()
    await expect(recents(page)).toHaveCount(0)
})

/** A card's menu: a long press with a finger on a phone, a right click elsewhere (CardMenu.tsx). */
async function menuOf(page: Page, card: Locator) {
    const touch = await page.evaluate(() => matchMedia('(pointer: coarse)').matches)
    if (!touch) return card.click({ button: 'right' })
    const b = (await card.boundingBox())!
    const at = { clientX: b.x + 20, clientY: b.y + 20, pointerType: 'touch', isPrimary: true }
    await card.dispatchEvent('pointerdown', at)
    await page.waitForTimeout(650)
    await card.dispatchEvent('pointerup', at)
}

const stubShare = (page: Page) =>
    page.addInitScript(() => {
        const w = window as unknown as { __shared: unknown[] }
        w.__shared = []
        Object.defineProperty(navigator, 'share', { value: async (d: unknown) => void w.__shared.push(d), configurable: true })
    })
const shared = (page: Page) => page.evaluate(() => (window as unknown as { __shared: { url: string; title: string }[] }).__shared)

test.describe('Share', () => {
    test.beforeEach(async ({ context }) => {
        await fakeCase(context, 'links')
    })

    test('the card menu shares the video link, and a chat that restricts saving offers none', async ({ page }) => {
        await stubShare(page)
        await page.goto('/chat/-1001234567890')
        const card = page.locator('.card').filter({ hasText: 'Plain Clip' })
        await expect(card).toBeVisible()
        await menuOf(page, card)
        const menu = page.locator('dialog.card-menu[open]')
        await menu.getByRole('button', { name: 'Share' }).click()
        await expect(menu).toHaveCount(0)
        const [sent] = await shared(page)
        expect(sent!.url).toMatch(/^https:\/\/t\.me\/linkroom\/\d+$/)
        expect(sent!.title).toContain('Plain Clip')

        await page.goto('/chat/-1005555555555')
        const sealed = page.locator('.card').first()
        await expect(sealed).toBeVisible()
        await menuOf(page, sealed)
        await expect(page.locator('dialog.card-menu[open]')).toBeVisible()
        await expect(page.locator('dialog.card-menu[open]').getByRole('button', { name: 'Share' })).toHaveCount(0)
    })

    test('a browser with no share sheet has no Share row', async ({ page }) => {
        await page.addInitScript(() => Object.defineProperty(navigator, 'share', { value: undefined, configurable: true }))
        await page.goto('/chat/-1001234567890')
        const card = page.locator('.card').filter({ hasText: 'Plain Clip' })
        await menuOf(page, card)
        const menu = page.locator('dialog.card-menu[open]')
        await expect(menu.getByRole('button', { name: 'Copy Telegram link' })).toBeVisible()
        await expect(menu.getByRole('button', { name: 'Share' })).toHaveCount(0)
    })

    test('a video page has a Share round button', async ({ page }) => {
        await stubShare(page)
        await page.goto('/chat/-1001234567890')
        await page.locator('.card').filter({ hasText: 'Plain Clip' }).click()
        await expect(page).toHaveURL(/\/video\//)
        await page.locator('.detail-round', { hasText: 'Share' }).click()
        const [sent] = await shared(page)
        expect(sent!.url).toMatch(/^https:\/\/t\.me\/linkroom\/\d+$/)
    })
})
