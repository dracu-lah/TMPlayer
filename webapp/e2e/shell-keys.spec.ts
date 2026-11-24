// HX17 on the web (WB9): the desktop app's window keys (ui/shellKeys.ts) and the arrows through a grid
// (ui/VirtualGrid.tsx), walked by keyboard alone. Runs on a desktop browser (Chromium, WebKit); the TV skin keeps
// its remote walk, which the last test proves is not touched. Screenshots go to design/web-gaps/WB9/.
import { mkdirSync } from 'node:fs'
import { expect, test, type Page, type TestInfo } from '@playwright/test'
import { fakeCase, noSizeFloor, openClips } from './helpers'

const SHOTS = `${process.cwd()}/../design/web-gaps/WB9`

async function shot(page: Page, info: TestInfo, name: string) {
    mkdirSync(SHOTS, { recursive: true })
    await page.screenshot({ path: `${SHOTS}/${name}-${info.project.name}.png` })
}

/** The command key of this browser's platform, as the page reads it. */
const command = (page: Page) => page.evaluate(() => (/Mac|iPhone|iPad/.test(navigator.platform) ? 'Meta' : 'Control'))
const chord = async (page: Page, key: string, shift = false) => {
    const mod = await command(page)
    await page.keyboard.press(`${mod}+${shift ? 'Shift+' : ''}${key}`)
}
/** The words under the keyboard's place: a card's name, a chat's name or the field's label. */
const focused = (page: Page) => page.evaluate(() => (document.activeElement as HTMLElement | null)?.getAttribute('aria-label') ?? (document.activeElement as HTMLElement | null)?.innerText?.split('\n')[0] ?? '')
const focusedIndex = (page: Page) => page.evaluate(() => Number(document.activeElement?.closest('li[data-item]')?.getAttribute('data-item') ?? -1))

test.describe('window keys', () => {
    test.beforeEach(async ({ page }) => {
        await noSizeFloor(page)
        await page.goto('/chats')
        await expect(page.locator('a[href^="/chat/"]').first()).toBeVisible()
    })

    test('Command or Ctrl+K opens search with the box focused, and again goes back', async ({ page }, info) => {
        await chord(page, 'k')
        await expect(page).toHaveURL(/\/search/)
        await expect(page.getByRole('searchbox')).toBeFocused()
        await shot(page, info, 'search')
        // Inside the box the chord still answers (a preview key), and a second press leaves.
        await chord(page, 'k')
        await expect(page).toHaveURL(/\/chats$/)
    })

    test('Command+J is Downloads and toggles; Command+Shift+A chooses chats', async ({ page }) => {
        await chord(page, 'j')
        await expect(page).toHaveURL(/\/downloads$/)
        await chord(page, 'j')
        await expect(page).toHaveURL(/\/chats$/)
        await chord(page, 'A', true)
        await expect(page).toHaveURL(/\/added-chats/)
    })

    test('Command+1 and 2 stay the browser tabs\' in a tab, and are Home and Chats in an installed app', async ({ page }) => {
        await chord(page, '1')
        await expect(page).toHaveURL(/\/chats$/)
        await page.addInitScript(() => {
            const real = window.matchMedia.bind(window)
            window.matchMedia = (q: string) => (q.includes('display-mode: standalone') ? ({ ...real('all'), matches: true, media: q } as MediaQueryList) : real(q))
        })
        await page.reload()
        await expect(page.locator('a[href^="/chat/"]').first()).toBeVisible()
        await chord(page, '1')
        await expect(page).toHaveURL(/127\.0\.0\.1:\d+\/$/)
        await chord(page, '2')
        await expect(page).toHaveURL(/\/chats$/)
    })

    test('a held chord acts once, and Back from a deep link goes Home, never off the app', async ({ page }) => {
        await page.goto('/search')
        await expect(page.getByRole('searchbox')).toBeFocused()
        const mod = await command(page)
        // Down, repeat, repeat, up: the repeats are spent, so the first press's page stays.
        await page.keyboard.down(`${mod}`)
        await page.keyboard.down('KeyK')
        await page.keyboard.down('KeyK')
        await page.keyboard.down('KeyK')
        await page.keyboard.up('KeyK')
        await page.keyboard.up(`${mod}`)
        await expect(page).toHaveURL(/127\.0\.0\.1:\d+\/$/)
    })

    test('Escape in Search from a deep link goes Home', async ({ page }) => {
        await page.goto('/search')
        await expect(page.getByRole('searchbox')).toBeFocused()
        await page.keyboard.press('Escape')
        await expect(page).toHaveURL(/127\.0\.0\.1:\d+\/$/)
    })

    test('a letter chord follows the key place on a layout without Latin letters', async ({ page }) => {
        await page.evaluate(() => {
            const mac = /Mac|iPhone|iPad/.test(navigator.platform)
            document.body.dispatchEvent(new KeyboardEvent('keydown', { key: '\u043b', code: 'KeyK', ctrlKey: !mac, metaKey: mac, bubbles: true, cancelable: true }))
        })
        await expect(page).toHaveURL(/\/search/)
    })

    test('/ counts where it needs Shift (a German layout sends Shift+7)', async ({ page }) => {
        await page.locator('body').click({ position: { x: 5, y: 5 } })
        await page.evaluate(() => document.body.dispatchEvent(new KeyboardEvent('keydown', { key: '/', code: 'Digit7', shiftKey: true, bubbles: true, cancelable: true })))
        await expect(page.getByRole('searchbox')).toBeFocused()
    })

    test('the bar says its chords on a desktop', async ({ page }) => {
        const mod = (await command(page)) === 'Meta' ? '⌘' : 'Ctrl+'
        await expect(page.locator('.nav-cell[href="/search"]')).toHaveAttribute('title', `Search (${mod}K)`)
        // History took the fourth place when the web could not save (WP-59): it has no chord, its name stands alone.
        await expect(page.locator('.nav-cell[href="/history"]')).toHaveAttribute('title', 'History')
    })

    test('/ and Command+F focus the page search; inside the box, / is a letter', async ({ page }) => {
        await page.locator('body').click({ position: { x: 5, y: 5 } })
        await page.keyboard.press('/')
        const box = page.getByRole('searchbox')
        await expect(box).toBeFocused()
        await page.keyboard.type('a/b')
        await expect(box).toHaveValue('a/b')
        await box.fill('')
        await page.locator('a[href^="/chat/"]').first().focus()
        await chord(page, 'f')
        await expect(box).toBeFocused()
    })

    test('Ctrl+F is left to the browser where a page has no search box', async ({ page }) => {
        // Settings has one since WB20 (settings-find.spec.ts); Downloads, the Get the app card, has none.
        await page.goto('/downloads')
        await expect(page.locator('main').first()).toBeVisible()
        const seen = await page.evaluate(
            () =>
                new Promise<boolean>((resolve) => {
                    const mac = /Mac|iPhone|iPad/.test(navigator.platform)
                    addEventListener('keydown', (e) => setTimeout(() => resolve(e.defaultPrevented), 0), { once: true })
                    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'f', ctrlKey: !mac, metaKey: mac, bubbles: true, cancelable: true }))
                }),
        )
        expect(seen).toBe(false)
    })

    test('in a chat, / opens the chat search and Escape closes it again', async ({ page }) => {
        await openClips(page)
        await page.locator('body').click({ position: { x: 5, y: 5 } })
        await page.keyboard.press('/')
        const box = page.getByRole('searchbox', { name: 'Search this chat' })
        await expect(box).toBeFocused()
        await page.keyboard.press('Escape')
        await expect(box).toHaveCount(0)
    })

    test('Escape in Search clears the words first, then leaves', async ({ page }) => {
        await chord(page, 'k')
        const box = page.getByRole('searchbox')
        await box.fill('Clip')
        await expect(page.locator('.card').first()).toBeVisible()
        await page.keyboard.press('Escape')
        await expect(box).toHaveValue('')
        await expect(page).toHaveURL(/\/search/)
        await page.keyboard.press('Escape')
        await expect(page).toHaveURL(/\/chats$/)
    })

    test('a dialog is its own layer: the window keys wait while one is open', async ({ page }) => {
        await openClips(page)
        const card = page.locator('.card').first()
        await card.focus()
        await page.keyboard.press('Shift+F10')
        await expect(page.locator('dialog[open]')).toHaveCount(1)
        await chord(page, 'k')
        await expect(page).toHaveURL(/\/chat\/-1001/)
        await page.keyboard.press('Escape')
        await expect(page.locator('dialog[open]')).toHaveCount(0)
        await expect(card).toBeFocused()
    })
})

test.describe('a grid by keyboard', () => {
    test.beforeEach(async ({ page }) => {
        await noSizeFloor(page)
        await openClips(page)
        await expect(page.locator('.card').nth(3)).toBeVisible()
    })

    test('arrows, Home, End and the page keys walk the cards and never wrap', async ({ page }, info) => {
        const first = page.locator('li[data-item="0"] .card')
        await first.focus()
        expect(await focusedIndex(page)).toBe(0)
        await page.keyboard.press('ArrowLeft')
        expect(await focusedIndex(page)).toBe(0)
        await page.keyboard.press('ArrowRight')
        expect(await focusedIndex(page)).toBe(1)
        // The fake chat carries a sponsored message, which takes a row of its own across the grid (the third card's next).
        const rowEnd = await page.evaluate(() => {
            const row = document.querySelector('.vrow')!
            return row.querySelectorAll('li').length
        })
        expect(rowEnd).toBeGreaterThan(1)
        await page.keyboard.press('ArrowDown')
        expect(await focusedIndex(page)).toBe(rowEnd)
        await page.keyboard.press('ArrowUp')
        expect(await focusedIndex(page)).toBe(0)
        await page.keyboard.press('ArrowRight')
        await page.keyboard.press('End')
        expect(await focusedIndex(page)).toBe(rowEnd - 1)
        await page.keyboard.press('Home')
        expect(await focusedIndex(page)).toBe(0)
        await shot(page, info, 'grid-focus')
        await page.keyboard.press('Control+End')
        await expect.poll(() => focusedIndex(page)).toBeGreaterThan(0)
        const last = await focusedIndex(page)
        await page.keyboard.press('ArrowRight')
        await page.keyboard.press('ArrowDown')
        expect(await focusedIndex(page)).toBe(last)
        await page.keyboard.press('Control+Home')
        await expect.poll(() => focusedIndex(page)).toBe(0)
        // At an edge Home, End and Ctrl+Home are spent: the window does not scroll away from the card.
        await page.waitForTimeout(600)
        const scrollY = await page.evaluate(() => window.scrollY)
        await page.keyboard.press('Control+Home')
        await page.keyboard.press('Home')
        await page.keyboard.press('PageUp')
        expect(await page.evaluate(() => window.scrollY)).toBe(scrollY)
        await page.keyboard.press('Control+End')
        await expect.poll(() => focusedIndex(page)).toBeGreaterThan(0)
        await page.waitForTimeout(600)
        const bottom = await page.evaluate(() => window.scrollY)
        await page.keyboard.press('Control+End')
        await page.keyboard.press('End')
        await page.keyboard.press('PageDown')
        expect(await page.evaluate(() => window.scrollY)).toBe(bottom)
        expect(await focusedIndex(page)).toBeGreaterThan(0)
    })

    test('Enter opens the focused card, and Shift+F10 opens its menu', async ({ page }) => {
        await page.locator('li[data-item="0"] .card').focus()
        await page.keyboard.press('Shift+F10')
        await expect(page.getByRole('dialog')).toBeVisible()
        await page.keyboard.press('Escape')
        await expect(page.locator('li[data-item="0"] .card')).toBeFocused()
        await page.keyboard.press('Enter')
        await expect(page).toHaveURL(/\/video\//)
    })
})

test('a huge account: Ctrl+End reaches the last chat and the page draws only a few rows', async ({ page, context }, info) => {
    test.setTimeout(60_000)
    await fakeCase(context, 'huge')
    await noSizeFloor(page)
    await page.goto('/chats')
    await expect(page.locator('li[data-item="0"] a')).toBeVisible({ timeout: 20_000 })
    // The list arrives in batches: End goes to the last chat there is when it is pressed, so let it settle first.
    let height = -1
    await expect.poll(async () => {
        const now = await page.locator('.vgrid').evaluate((el) => el.getBoundingClientRect().height)
        const same = now === height
        height = now
        return same
    }, { timeout: 20_000, intervals: [1000] }).toBe(true)
    await page.locator('li[data-item="0"] a').focus()
    await page.keyboard.press('Control+End')
    await expect.poll(() => focusedIndex(page), { timeout: 15_000 }).toBeGreaterThan(1000)
    const drawn = await page.locator('li[data-item]').count()
    expect(drawn).toBeLessThan(60)
    await page.keyboard.press('PageUp')
    await expect.poll(() => focusedIndex(page)).toBeGreaterThan(0)
    await shot(page, info, 'huge-chats')
})

test('the TV skin keeps its remote walk: the shell chords do nothing there', async ({ page }) => {
    await page.addInitScript(() => {
        try {
            localStorage.setItem('tmplayer-layout', 'tv')
        } catch {}
    })
    await page.setViewportSize({ width: 1920, height: 1080 })
    await noSizeFloor(page)
    await page.goto('/chats')
    await expect(page.locator('html')).toHaveAttribute('data-form', 'tv')
    await expect(page.locator('a[href^="/chat/"]').first()).toBeVisible()
    await chord(page, 'k')
    await expect(page).toHaveURL(/\/chats$/)
    await page.keyboard.press('/')
    await expect(page).toHaveURL(/\/chats$/)
})

// A phone with a hardware keyboard (an iPhone or iPad with one): the same keys answer, and the layout is the phone's.
test.describe('phone with a keyboard', () => {
    test.use({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true })
    test('the arrows walk the cards and the chord opens Search', async ({ page }, info) => {
        await noSizeFloor(page)
        await openClips(page)
        await expect(page.locator('html')).toHaveAttribute('data-form', 'phone')
        await page.locator('li[data-item="0"] .card').focus()
        await page.keyboard.press('ArrowRight')
        expect(await focusedIndex(page)).toBe(1)
        await page.keyboard.press('ArrowDown')
        expect(await focusedIndex(page)).toBeGreaterThan(1)
        await shot(page, info, 'grid-focus-phone')
        await page.goto('/chats')
        await expect(page.locator('a[href^="/chat/"]').first()).toBeVisible()
        await chord(page, 'k')
        await expect(page).toHaveURL(/\/search/)
        await shot(page, info, 'search-phone')
    })
})
