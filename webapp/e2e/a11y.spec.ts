// Session 22: axe-core over the main screens in the form the project runs in. Serious and critical
// findings fail; the smaller ones (moderate, minor) are not asked of a first version.
import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { fakeCase, noSizeFloor, openCard } from './helpers'

test.skip(({ browserName }) => browserName !== 'chromium', 'axe runs in Chromium')

async function audit(page: Page, where: string) {
    const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze()
    // Guard against an audit that checked nothing.
    expect(result.passes.length, `${where}: axe ran`).toBeGreaterThan(10)
    const bad = result.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical')
    expect(
        bad.map((v) => `${where}: ${v.id} (${v.impact}) ${v.help}: ${v.nodes.slice(0, 3).map((n) => n.target.join(' ')).join(' | ')}`),
        `${where}: serious accessibility findings`,
    ).toEqual([])
}

test.describe('axe on the main screens', () => {
    test.beforeEach(async ({ page }) => {
        await noSizeFloor(page)
    })
    for (const [name, path, ready] of [
        ['Home', '/', 'main'],
        ['Chats', '/chats', 'a[href^="/chat/"]'],
        ['a chat', '/chat/-1001', '.card'],
        ['Search', '/search?q=Clip', '.card'],
        ['History', '/history', 'main'],
        ['Settings', '/settings/appearance', 'main'],
        ['a video page', '/video/-1001/1', '.detail-title'],
    ] as const)
        test(name, async ({ page }) => {
            await page.goto(path)
            await expect(page.locator(ready).first()).toBeVisible()
            await page.waitForTimeout(500)
            await audit(page, name)
        })

    test('the player', async ({ page }) => {
        await page.goto('/chat/-1001')
        await openCard(page, 'Native Clip')
        await expect(page.locator('.pc')).toBeVisible()
        await page.mouse.move(300, 300)
        await page.waitForTimeout(500)
        await audit(page, 'the player')
    })
})

test('sign in', async ({ page, context }) => {
    await fakeCase(context, 'signedout')
    await page.goto('/')
    await expect(page.getByText('unofficial', { exact: false }).first()).toBeVisible()
    await audit(page, 'sign in')
})
