// WB14 Rename: the title a viewer gives a video, from its page and from a card's menu, in every form.
// It reads on the page and on the cards at once, holds for every copy of a grouped video, survives a
// reload and another tab, is cut at 120, and "Use the file name" takes it off again.
import { expect, test, type Locator, type Page } from '@playwright/test'
import { noSizeFloor } from './helpers'

const SHOTS = process.env.WB_SHOTS ? new URL('../../design/web-gaps/WB14/', import.meta.url).pathname : null

test.beforeEach(async ({ page }) => {
    await noSizeFloor(page)
})

const sheet = (page: Page) => page.getByRole('dialog', { name: 'Rename video' })
const field = (page: Page) => sheet(page).locator('input')
const rename = (page: Page) => page.locator('.detail-round', { hasText: 'Rename' })

/** A card's menu: a long press with a finger on a phone, a right click elsewhere (CardMenu.tsx). */
async function menuOf(page: Page, card: Locator) {
    const touch = await page.evaluate(() => matchMedia('(pointer: coarse)').matches)
    if (!touch) return card.click({ button: 'right' })
    const box = (await card.boundingBox())!
    const at = { clientX: box.x + 20, clientY: box.y + 20, pointerType: 'touch', isPrimary: true }
    await card.dispatchEvent('pointerdown', at)
    await page.waitForTimeout(650)
    await card.dispatchEvent('pointerup', at)
}

/** The page's title once it is drawn. */
async function titleOf(page: Page) {
    await expect(page.locator('.detail-title')).not.toHaveText('')
    return ((await page.locator('.detail-title').textContent()) ?? '').trim()
}

async function shot(page: Page, name: string, info: { project: { name: string } }) {
    if (SHOTS) await page.screenshot({ path: `${SHOTS}${name}-${info.project.name}.png` })
}

test('Rename on the video page: the field starts with the title shown, Save puts the new one on the page, a reload keeps it, Use the file name takes it off', async ({ page }, info) => {
    await page.goto('/video/-1001/1')
    const before = await titleOf(page)
    expect(before).not.toBe('')
    await rename(page).click()
    await expect(sheet(page)).toBeVisible()
    await expect(field(page)).toBeFocused()
    await expect(field(page)).toHaveValue(before)
    // Nothing changed: Save is not offered yet, and "Use the file name" is not either (nothing to take off).
    await expect(sheet(page).getByRole('button', { name: 'Save' })).toBeDisabled()
    await expect(sheet(page).getByRole('button', { name: 'Use the file name' })).toHaveCount(0)
    await shot(page, 'sheet', info)
    await field(page).fill('My Own Title (2021)')
    await sheet(page).getByRole('button', { name: 'Save' }).click()
    await expect(sheet(page)).toBeHidden()
    await expect(page.locator('.detail-title')).toHaveText('My Own Title (2021)')
    await expect(rename(page)).toBeFocused()
    await shot(page, 'renamed', info)
    await page.reload()
    await expect(page.locator('.detail-title')).toHaveText('My Own Title (2021)')
    // The sheet starts from the rename, and offers to take it off.
    await rename(page).click()
    await expect(field(page)).toHaveValue('My Own Title (2021)')
    await sheet(page).getByRole('button', { name: 'Use the file name' }).click()
    await expect(sheet(page)).toBeHidden()
    await expect(page.locator('.detail-title')).toHaveText(before)
    await page.reload()
    await expect(page.locator('.detail-title')).toHaveText(before)
})

test('the field is cut at 120 characters, an empty one is no rename, Escape and Cancel change nothing, Enter saves', async ({ page }) => {
    await page.goto('/video/-1001/1')
    const before = await titleOf(page)
    await rename(page).click()
    // A pasted 500 characters (the field's own limit is the first stop, a script gets past it).
    await field(page).evaluate((el: HTMLInputElement) => {
        el.removeAttribute('maxlength')
        el.value = 'x'.repeat(500)
        el.dispatchEvent(new Event('input', { bubbles: true }))
    })
    await expect(field(page)).toHaveValue('x'.repeat(120))
    await expect(sheet(page).locator('.rename-count')).toHaveText('120 / 120')
    // An emoji and right to left text are kept whole.
    await field(page).fill('فيلم الليل 😀')
    await expect(field(page)).toHaveValue('فيلم الليل 😀')
    // The count and the cap are in whole characters: 60 emoji are 60 of 120, and typing goes on.
    await field(page).fill('😀'.repeat(60))
    await expect(sheet(page).locator('.rename-count')).toHaveText('60 / 120')
    await field(page).press('End')
    await page.keyboard.type('abc')
    await expect(sheet(page).locator('.rename-count')).toHaveText('63 / 120')
    // Escape closes with nothing saved and the focus back on the button.
    await page.keyboard.press('Escape')
    await expect(sheet(page)).toBeHidden()
    await expect(page.locator('.detail-title')).toHaveText(before)
    await expect(rename(page)).toBeFocused()
    // Cancel likewise.
    await rename(page).click()
    await field(page).fill('Cancelled')
    await sheet(page).getByRole('button', { name: 'Cancel' }).click()
    await expect(page.locator('.detail-title')).toHaveText(before)
    // Only spaces: nothing to save.
    await rename(page).click()
    await field(page).fill('     ')
    await expect(sheet(page).getByRole('button', { name: 'Save' })).toBeDisabled()
    await page.keyboard.press('Enter')
    await expect(sheet(page)).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(sheet(page)).toBeHidden()
    await expect(page.locator('.detail-title')).toHaveText(before)
    // Enter in the field saves.
    await rename(page).click()
    await field(page).fill('Typed with Enter')
    await page.keyboard.press('Enter')
    await expect(page.locator('.detail-title')).toHaveText('Typed with Enter')
})

test('Save pressed twice at once is one save', async ({ page }) => {
    await page.goto('/video/-1001/1')
    await rename(page).click()
    await field(page).fill('Twice')
    const save = sheet(page).getByRole('button', { name: 'Save' })
    // Two presses in the same moment (a double tap): the form is sent twice.
    await save.evaluate((b: HTMLButtonElement) => {
        b.click()
        b.click()
    })
    await expect(page.locator('.detail-title')).toHaveText('Twice')
    await expect(sheet(page)).toBeHidden()
})

test('a rename holds for every copy of a grouped video: one card, and the page of another copy reads it', async ({ page }) => {
    await page.goto('/video/-1005/27')
    await expect(page.locator('.detail-copy-summary')).toContainText('3 versions')
    await rename(page).click()
    await field(page).fill('Renamed Bunny (2009)')
    await sheet(page).getByRole('button', { name: 'Save' }).click()
    await expect(page.locator('.detail-title')).toHaveText('Renamed Bunny (2009)')
    // Every copy was renamed, so they are still one card, now with the new title.
    await expect(page.locator('.detail-copy-summary')).toContainText('3 versions')
    await page.goto('/chat/-1005')
    const cards = page.locator('.card').filter({ hasText: 'Renamed Bunny (2009)' })
    await expect(cards).toHaveCount(1)
    await expect(cards.filter({ hasText: '3 versions' })).toHaveCount(1)
    await page.goto('/video/-1005/25')
    await expect(page.locator('.detail-title')).toHaveText('Renamed Bunny (2009)')
})

test('a card menu has Rename too, and the card shows the new title; Use the file name brings the old one back', async ({ page }, info) => {
    await page.goto('/chat/-1001')
    const first = page.locator('.card').first()
    await expect(first).toBeVisible()
    await expect(first.locator('.card-name')).not.toHaveText('')
    const before = ((await first.locator('.card-name').textContent()) ?? '').trim()
    await menuOf(page, first)
    await page.getByRole('button', { name: /^Rename/ }).click()
    await expect(sheet(page)).toBeVisible()
    await expect(field(page)).toHaveValue(before)
    await field(page).fill('Menu Renamed')
    await sheet(page).getByRole('button', { name: 'Save' }).click()
    await expect(page.locator('.card-name', { hasText: 'Menu Renamed' })).toHaveCount(1)
    await shot(page, 'card', info)
    await menuOf(page, page.locator('.card', { hasText: 'Menu Renamed' }))
    await page.getByRole('button', { name: /^Rename/ }).click()
    await sheet(page).getByRole('button', { name: 'Use the file name' }).click()
    await expect(page.locator('.card-name', { hasText: 'Menu Renamed' })).toHaveCount(0)
    await expect(page.locator('.card-name', { hasText: before }).first()).toBeVisible()
})

test('a second tab opened later reads the rename the first one kept (one tab at a time uses the account)', async ({ context, page }) => {
    await page.goto('/video/-1001/1')
    await titleOf(page)
    await rename(page).click()
    await field(page).fill('Kept For Later')
    await sheet(page).getByRole('button', { name: 'Save' }).click()
    await expect(page.locator('.detail-title')).toHaveText('Kept For Later')
    await expect.poll(() => page.evaluate(() => indexedDB.databases().then((d) => d.length))).toBeGreaterThan(0)
    const other = await context.newPage()
    await other.goto('/video/-1001/1')
    await other.getByRole('button', { name: 'Use it here' }).click()
    await expect(other.locator('.detail-title')).toHaveText('Kept For Later')
    await other.close()
})

test('with storage refused the rename still holds for the session, with no error', async ({ page }) => {
    const errors: string[] = []
    page.on('pageerror', (e) => errors.push(e.message))
    await page.addInitScript(() => {
        // A private window: IndexedDB opens but every write fails.
        const put = IDBObjectStore.prototype.put
        IDBObjectStore.prototype.put = function (...args: Parameters<typeof put>) {
            if (String(args[1]).startsWith('title')) throw new DOMException('full', 'QuotaExceededError')
            return put.apply(this, args)
        }
    })
    await page.goto('/video/-1001/1')
    await rename(page).click()
    await field(page).fill('Memory Only')
    await sheet(page).getByRole('button', { name: 'Save' }).click()
    await expect(page.locator('.detail-title')).toHaveText('Memory Only')
    expect(errors).toEqual([])
})

test.describe('on the TV', () => {
    test.use({ viewport: { width: 1920, height: 1080 } })
    test('the remote reaches Rename, the field takes the keys, Back closes it, and the title shows after Save', async ({ page }, info) => {
        test.skip(info.project.name !== 'chromium', 'the TV layout is one form: run once')
        await page.addInitScript(() => localStorage.setItem('tmplayer-layout', 'tv'))
        await page.goto('/video/-1001/1')
        await expect(page.locator('html')).toHaveAttribute('data-form', 'tv')
        const before = await titleOf(page)
        await expect(page.locator('.detail-play')).toBeFocused()
        const button = rename(page)
        // The round buttons share Play's line: Right walks them. Down leaves the line for the chat's row
        // below once More from has loaded, so it is not pressed (it passed only when that row was late).
        for (let i = 0; i < 12 && !(await button.evaluate((el) => el === document.activeElement)); i++) await page.keyboard.press('ArrowRight')
        await expect(button).toBeFocused()
        await page.keyboard.press('Enter')
        await expect(sheet(page)).toBeVisible()
        await expect(field(page)).toBeFocused()
        await shot(page, 'sheet-tv', info)
        // Back (Escape) leaves it as it was and the focus comes back to the button.
        await page.keyboard.press('Escape')
        await expect(sheet(page)).toBeHidden()
        await expect(button).toBeFocused()
        await expect(page.locator('.detail-title')).toHaveText(before)
        await page.keyboard.press('Enter')
        await expect(field(page)).toBeFocused()
        await page.keyboard.type('Remote title')
        await page.keyboard.press('Enter')
        await expect(page.locator('.detail-title')).toHaveText('Remote title')
        await expect(button).toBeFocused()
    })
})
