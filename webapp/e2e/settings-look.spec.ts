// WP-60: Reduce motion and High contrast (Automatic follows the system, On and Off are the viewer's
// word), forced colours, the field edge token, and the Subtitles and audio page with the player's look.
import { expect, test, type Page } from '@playwright/test'

const root = (page: Page, name: string) => page.evaluate((n) => getComputedStyle(document.documentElement).getPropertyValue(n).trim(), name)
const navTransition = (page: Page) => page.locator('.nav-cell').first().evaluate((el) => getComputedStyle(el).transitionDuration)

async function choose(page: Page, group: RegExp | string, word: string) {
    await page.getByRole('radiogroup', { name: group }).locator('label', { hasText: new RegExp(`^${word}$`) }).click()
}

test('Reduce motion: On stills the transitions, Off keeps them over a system that reduces, Automatic follows it', async ({ page }) => {
    await page.goto('/settings/appearance')
    await expect(page.getByRole('radiogroup', { name: 'Reduce motion' })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.dataset.motion ?? '')).toBe('')
    expect(await navTransition(page)).not.toBe('0s')

    await choose(page, 'Reduce motion', 'On')
    await expect.poll(() => page.evaluate(() => document.documentElement.dataset.motion)).toBe('reduce')
    expect(await navTransition(page)).toBe('0s')

    await choose(page, 'Reduce motion', 'Automatic')
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await expect.poll(() => navTransition(page)).toBe('0s')
    await choose(page, 'Reduce motion', 'Off')
    await expect.poll(() => page.evaluate(() => document.documentElement.dataset.motion)).toBe('full')
    expect(await navTransition(page)).not.toBe('0s')
    // The choice is kept over a reload.
    await page.reload()
    await expect(page.getByRole('radiogroup', { name: 'Reduce motion' }).locator('label.on')).toHaveText('Off')
})

test('High contrast: Automatic follows prefers-contrast, On forces it, Off beats the system', async ({ page }) => {
    await page.goto('/settings/appearance')
    const normal = await root(page, '--muted')
    const hc = await root(page, '--muted-hc')
    expect(normal).not.toBe(hc)
    await page.emulateMedia({ contrast: 'more' })
    await expect.poll(() => root(page, '--muted')).toBe(hc)
    await choose(page, 'High contrast', 'Off')
    await expect.poll(() => root(page, '--muted')).toBe(normal)
    await choose(page, 'High contrast', 'Automatic')
    await expect.poll(() => root(page, '--muted')).toBe(hc)
    await page.emulateMedia({ contrast: 'no-preference' })
    await expect.poll(() => root(page, '--muted')).toBe(normal)
    await choose(page, 'High contrast', 'On')
    await expect.poll(() => root(page, '--muted')).toBe(hc)
    expect(await root(page, '--line')).toBe(await root(page, '--line-hc'))
})

test('a field and an outlined button have an edge you can see, 3:1 against the page', async ({ page }) => {
    await page.goto('/settings/storage')
    const edge = await page.getByRole('button', { name: 'Back up' }).evaluate((el) => getComputedStyle(el).borderTopColor)
    const field = await root(page, '--field-line')
    const line = await root(page, '--line')
    expect(field).not.toBe(line)
    const rgb = (hex: string) => `rgb(${[1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16)).join(', ')})`
    expect(edge).toBe(rgb(field))
})

test('forced colours: the bar highlight is a border, not only a background', async ({ page }) => {
    await page.emulateMedia({ forcedColors: 'active' })
    await page.goto('/chats')
    await expect(page.locator('.nav-highlight')).toHaveCSS('border-top-width', '2px')
})

test('Subtitles and audio holds the player look, kept over a reload, with a way back to the default', async ({ page }) => {
    await page.goto('/settings/subtitles')
    await expect(page.getByRole('heading', { level: 1, name: 'Subtitles and audio' }).or(page.getByRole('heading', { name: /Subtitles/ })).first()).toBeVisible()
    await expect(page.locator('.look-preview')).toContainText('This is how subtitles will look.')
    await page.getByRole('radiogroup', { name: 'Size' }).locator('label', { hasText: /^Large$/ }).click()
    await page.getByRole('radiogroup', { name: 'Colour' }).locator('label', { hasText: /^Yellow$/ }).click()
    await page.reload()
    await expect(page.getByRole('radiogroup', { name: 'Size' }).locator('label.on')).toHaveText('Large')
    await expect(page.getByRole('radiogroup', { name: 'Colour' }).locator('label.on')).toHaveText('Yellow')
    await page.getByRole('button', { name: 'Back to the default look' }).click()
    await page.reload()
    await expect(page.getByRole('radiogroup', { name: 'Size' }).locator('label.on')).toHaveText('Medium')
    // The online subtitles row lives here now, and Settings' list names the page.
    await expect(page.getByRole('heading', { name: /SubMaker|Online subtitles/ }).first()).toBeVisible()
})
