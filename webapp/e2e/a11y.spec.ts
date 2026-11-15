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

// U19: under a finger the small round buttons answer a 48 px square, though they look 40 or 44.
test('small controls answer 48 px under a finger', async ({ page, isMobile }) => {
    test.skip(!isMobile, 'a touch screen only')
    await noSizeFloor(page)
    for (const path of ['/chats', '/chat/-1001']) {
        await page.goto(path)
        await expect(page.locator('.round-button').first()).toBeVisible()
        await page.waitForTimeout(500)
        const misses = await page.evaluate(() =>
            [...document.querySelectorAll<HTMLElement>('.round-button, .icon-button, .see-all, button.tonal')]
                .filter((el) => el.getBoundingClientRect().width > 0)
                .flatMap((el) => {
                    const r = el.getBoundingClientRect()
                    const cx = r.left + r.width / 2
                    const cy = r.top + r.height / 2
                    // One under the floating bar, or off the screen, is not there to press.
                    const middle = document.elementFromPoint(cx, cy)
                    if (!middle || !el.contains(middle)) return []
                    // A pixel inside each edge of the 48 px square round the middle.
                    return [[cx - 23, cy], [cx + 23, cy], [cx, cy - 23], [cx, cy + 23]]
                        .filter(([x, y]) => x >= 0 && y >= 0 && x < innerWidth && y < innerHeight)
                        .filter(([x, y]) => {
                            const hit = document.elementFromPoint(x, y)
                            // Its own square, a neighbouring control's where two meet, or the floating bar over it.
                            return !hit || !(el.contains(hit) || hit.closest('a, button, input, [role=button], nav'))
                        })
                        .map(([x, y]) => `${el.className} "${el.getAttribute('aria-label') ?? el.textContent?.trim()}" misses ${Math.round(x)},${Math.round(y)}`)
                }),
        )
        expect(misses, path).toEqual([])
    }
})

test('sign in', async ({ page, context }) => {
    await fakeCase(context, 'signedout')
    await page.goto('/')
    await expect(page.getByText('unofficial', { exact: false }).first()).toBeVisible()
    await audit(page, 'sign in')
})
