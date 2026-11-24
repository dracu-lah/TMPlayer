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
        ['Search', '/search', '.card'],
        ['History', '/history', 'main'],
        ['Settings', '/settings/appearance', 'main'],
        ['a video page', '/video/-1001/1', '.detail-title'],
    ] as const)
        test(name, async ({ page }) => {
            await page.goto(path)
            // A search opened by its address starts empty on purpose (a reload never brings an old one back): type the words.
            if (name === 'Search') await page.getByRole('searchbox').fill('Clip')
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

// R9: where the system asks for less motion, pictures arrive at once and a scroll the page starts jumps.
test('reduced motion: pictures do not fade in', async ({ page }) => {
    await page.goto('/chats')
    await expect(page.locator('main').first()).toBeVisible()
    const names = async () =>
        page.evaluate(() => {
            const box = document.createElement('div')
            box.innerHTML = '<div class="card-art"><img alt=""></div><img class="continue-img" alt=""><img class="detail-backdrop" alt=""><div class="cast-face"><img alt=""></div><div class="chat-picture"><img alt=""></div>'
            document.body.append(box)
            const out = [...box.querySelectorAll('img')].map((i) => getComputedStyle(i).animationName)
            box.remove()
            return out
        })
    await page.emulateMedia({ reducedMotion: 'no-preference' })
    expect((await names()).every((n) => n === 'fade')).toBe(true)
    await page.emulateMedia({ reducedMotion: 'reduce' })
    expect(await names()).toEqual(['none', 'none', 'none', 'none', 'none'])
})

// R9: the sort menu is keyboard whole: Enter opens it on its first line, the arrows walk, Escape or a pick gives the keyboard back to its button.
test('the sort menu opens on its first line, walks on arrows and hands the keyboard back', async ({ page }) => {
    await noSizeFloor(page)
    await page.goto('/chat/-1001')
    await expect(page.locator('.card').first()).toBeVisible()
    const trigger = page.getByRole('button', { name: 'Sort videos by' }).first()
    await trigger.focus()
    await page.keyboard.press('Enter')
    const items = page.getByRole('menuitemradio')
    // The first lines are Play all (WP-32) and Play random (WB13), the orders follow them.
    await expect(page.locator('[role^=menuitem]').first()).toBeFocused()
    await page.keyboard.press('ArrowDown')
    await page.keyboard.press('ArrowDown')
    await expect(items.first()).toBeFocused()
    await page.keyboard.press('End')
    await expect(page.locator('[role^=menuitem]').last()).toBeFocused()
    await page.keyboard.press('Escape')
    await expect(items.first()).toHaveCount(0)
    await expect(trigger).toBeFocused()
    await page.keyboard.press('Enter')
    await page.keyboard.press('ArrowDown')
    await page.keyboard.press('ArrowDown')
    await page.keyboard.press('Enter')
    await expect(items.first()).toHaveCount(0)
    await expect(trigger).toBeFocused()
})

// U19: under a finger the small round buttons answer a 48 px square, though they look 40 or 44.
test('small controls answer 48 px under a finger', async ({ page, isMobile }) => {
    test.skip(!isMobile, 'a touch screen only')
    await noSizeFloor(page)
    for (const path of ['/chats', '/chat/-1001', '/history', '/lists']) {
        await page.goto(path)
        await expect(page.locator('main').first()).toBeVisible()
        await page.waitForTimeout(500)
        const misses = await page.evaluate(() =>
            [...document.querySelectorAll<HTMLElement>('.round-button, .icon-button, .see-all, button.tonal, .history-tab, .outline-button, button.link, a.link')]
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

// Raju 4.10: two floating bars on top of each other hid the last row's caption and looked broken. A
// chat has its own bar at the foot in place of the main one, the last row clears it, and nothing
// else floats over it.
test('a chat has one bar at the foot, no floating bars overlap, and the last row clears it', async ({ page, isMobile }) => {
    test.skip(!isMobile, 'the floating bar is a phone and tablet layout')
    await noSizeFloor(page)
    await page.goto('/chat/-1001')
    await expect(page.locator('.card').first()).toBeVisible()
    await expect(page.locator('nav.chat-bar')).toBeVisible()
    await page.waitForTimeout(500)
    const bars = await page.evaluate(() =>
        [...document.querySelectorAll<HTMLElement>('nav, [role=toolbar], .floating')]
            .filter((el) => getComputedStyle(el).position === 'fixed' && el.getBoundingClientRect().width > 0)
            .map((el) => {
                const r = el.getBoundingClientRect()
                return { name: `${el.tagName.toLowerCase()}.${el.className}`, left: r.left, right: r.right, top: r.top, bottom: r.bottom }
            }),
    )
    expect(bars.length, 'the chat bar is there').toBeGreaterThan(0)
    expect(bars.filter((b) => b.name.includes('nav') && !b.name.includes('chat-bar')).map((b) => b.name), 'the main bar is not drawn under the chat bar').toEqual([])
    for (let i = 0; i < bars.length; i++) {
        for (let j = i + 1; j < bars.length; j++) {
            const a = bars[i]
            const b = bars[j]
            const apart = a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top
            expect(apart, `${a.name} and ${b.name} overlap`).toBe(true)
        }
    }
    // Scrolled to the end, the last row's caption is not under the bar.
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight))
    await page.waitForTimeout(300)
    const covered = await page.evaluate(() => {
        const bar = document.querySelector('nav.chat-bar .nav-cells')!.getBoundingClientRect()
        const cards = [...document.querySelectorAll<HTMLElement>('.card')].filter((c) => c.getBoundingClientRect().height > 0)
        const last = cards[cards.length - 1].getBoundingClientRect()
        const sideways = last.left < bar.right && last.right > bar.left
        return sideways ? last.bottom - bar.top : 0
    })
    expect(covered, 'px of the last card under the chat bar').toBeLessThanOrEqual(0)
})

// Raju 10.3: Sign out was the widest, filled red button on the page. It is a quiet outlined row now,
// and it asks first with a sentence that names what goes with it; Cancel leaves everything as it was.
test('Sign out is a quiet row that asks first', async ({ page }) => {
    await page.goto('/settings')
    const button = page.getByRole('button', { name: 'Sign out of Telegram' })
    await expect(button).toBeVisible()
    const look = await button.evaluate((el) => {
        const s = getComputedStyle(el)
        return { background: s.backgroundColor, border: s.borderTopWidth }
    })
    expect(look.background, 'no filled background').toMatch(/^rgba\(0, 0, 0, 0\)$|^transparent$/)
    expect(look.border, 'an outline instead').not.toBe('0px')
    await button.click()
    const ask = page.getByRole('dialog')
    await expect(ask.getByRole('heading', { name: 'Sign out of Telegram as Dana Test?' })).toBeVisible()
    await expect(ask).toContainText('favourites')
    await ask.getByRole('button', { name: 'Cancel' }).click()
    await expect(ask).toBeHidden()
    await expect(button).toBeVisible()
    await expect(page).toHaveURL(/\/settings/)
})

test('sign in', async ({ page, context }) => {
    await fakeCase(context, 'signedout')
    await page.goto('/')
    await expect(page.getByText('unofficial', { exact: false }).first()).toBeVisible()
    await audit(page, 'sign in')
})
