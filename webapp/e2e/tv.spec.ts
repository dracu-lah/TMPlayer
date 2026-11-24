// Plan 8.5: the TV layout walked with keys only (the remote's arrows, OK and Back), at both TV
// sizes: focus comes back to the item that was open after Back, after a menu and after a dialog.
import { readFileSync } from 'node:fs'
import { expect, test, type Page } from '@playwright/test'
import { fakeCase, mockSplitShow, openTvSettingsPage } from './helpers'

/** The layout set to TV and two chats starred, written where the app keeps them before it starts. */
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
                    kv.put({ chatId: -1001, time: 1 }, 'star:-1001')
                    kv.put({ chatId: -1002, time: 2 }, 'star:-1002')
                    tx.oncomplete = () => (open.result.close(), resolve())
                }
            }),
    )
}

/**
 * How far, in px, `inner` sits from the middle of `outer` along one axis; with no `inner`, the words'
 * row against the button's (0 when the button is a single centred row).
 */
const offCentre = (page: Page, outer: string, inner: string, axis: 'x' | 'y') =>
    page.locator(outer).first().evaluate((el, [inner, axis]) => {
        const mid = (r: DOMRect) => (axis === 'x' ? r.left + r.width / 2 : r.top + r.height / 2)
        if (!inner) return getComputedStyle(el).display.endsWith('flex') && getComputedStyle(el).alignItems === 'center' ? 0 : 99
        return Math.abs(mid(el.getBoundingClientRect()) - mid(el.querySelector(inner)!.getBoundingClientRect()))
    }, [inner, axis] as const)

/** What has the focus: its address, label or words. */
const focused = (page: Page) =>
    page.evaluate(() => {
        const a = document.activeElement as HTMLElement | null
        return a ? `${a.classList[0] ?? a.tagName}|${a.getAttribute('href') ?? a.getAttribute('aria-label') ?? ''}` : ''
    })

/** What has the focus, by its words when it has no label: a button of words has no `aria-label`. */
const focusedText = (page: Page) =>
    page.evaluate(() => {
        const a = document.activeElement as HTMLElement | null
        return a ? `${a.classList[0] ?? a.tagName}|${a.getAttribute('aria-label') ?? a.textContent?.trim() ?? ''}` : ''
    })

async function press(page: Page, key: string, times = 1) {
    for (let i = 0; i < times; i++) {
        await page.keyboard.press(key)
        await page.waitForTimeout(150)
    }
}

for (const size of [
    { width: 1920, height: 1080 },
    { width: 1280, height: 720 },
]) {
    test.describe(`TV layout at ${size.width} x ${size.height}`, () => {
        test.skip(({ browserName }) => browserName !== 'chromium', 'the TV walk runs in Chromium')
        test.use({ viewport: size })
        test.beforeEach(async ({ page, context }) => {
            await fakeCase(context)
            await tv(page)
        })

        // 'mid' (the tests that open Home): the fake account's files say 100 MB, over Home's 50 MB floor and under the carousel's 400 MB, so Home has rows, no carousel and opens on its first card. The player's tests keep the real sizes, which its stream needs.
        test('Home: no carousel to show opens on the first card, rows, OK opens the page on Play, Back returns to Play and then the card, Back goes to the bar', async ({ page, context }) => {
            await fakeCase(context, 'mid')
            await page.goto('/')
            await expect(page.locator('html')).toHaveAttribute('data-form', 'tv')
            // The picture says who is signed in (WP-01): its accessible name, and the first name at rest beside it.
            await expect(page.locator('.tv-picture')).toHaveAttribute('aria-label', 'Dana Test, open the account menu')
            await expect(page.locator('.tv-picture-name')).toHaveText('Dana')
            await expect.poll(() => focused(page)).toContain('card|/video/')
            await expect(page.locator('.tv-hero')).toHaveCount(0)
            await press(page, 'ArrowDown', 2)
            await press(page, 'ArrowRight', 2)
            const card = await focused(page)
            expect(card).toContain('card|/video/')
            // OK opens the video's page with the remote on Play, as the app's Open a video's page first.
            await press(page, 'Enter')
            await expect(page).toHaveURL(/\/video\//)
            await expect.poll(() => focused(page)).toBe('detail-play|')
            await expect(page.locator('.tv-bar, .detail-close:visible')).toHaveCount(0)
            await press(page, 'Enter')
            await expect(page).toHaveURL(/\/watch\//)
            await press(page, 'Backspace')
            await expect(page).toHaveURL(/\/video\//)
            await expect.poll(() => focused(page)).toBe('detail-play|')
            await press(page, 'Backspace')
            await expect(page).toHaveURL(/\/$/)
            await expect.poll(() => focused(page)).toBe(card)
            // The remote's Play key plays a card at once, past its page.
            await press(page, 'MediaPlayPause')
            await expect(page).toHaveURL(/\/watch\//)
            await press(page, 'Backspace')
            await expect(page).toHaveURL(/\/$/)
            await expect.poll(() => focused(page)).toBe(card)
            await press(page, 'Escape')
            expect(await focused(page)).toBe('tv-tab|/')
            // Up from the bar goes nowhere; Down goes back into the page where the remote was.
            await press(page, 'ArrowUp')
            expect(await focused(page)).toBe('tv-tab|/')
        })

        test('Chats and a chat: the bar keeps the remote, a chat opens on its first card, the sort menu and Back', async ({ page, context }) => {
            await fakeCase(context, 'mid')
            await page.goto('/')
            await expect.poll(() => focused(page)).toContain('card|/video/')
            // The page's Refresh button (WP-40) is the first stop above the cards, the bar the next.
            await press(page, 'ArrowUp', 2)
            await press(page, 'ArrowRight')
            await press(page, 'Enter')
            await expect(page).toHaveURL(/\/chats$/)
            await expect.poll(() => focused(page)).toBe('tv-tab|/chats')
            await press(page, 'ArrowDown', 2)
            await press(page, 'ArrowRight', 2)
            const row = await focused(page)
            expect(row).toBe('chat-link|/chat/-1001')
            await press(page, 'Enter')
            await expect(page).toHaveURL(/\/chat\/-1001/)
            await expect.poll(() => focused(page)).toContain('card|/video/-1001/')
            // Up from the first card is Back; Right passes the title to the chat's star, then Sort.
            await press(page, 'ArrowUp')
            expect(await focused(page)).toBe('round-button|Back')
            await press(page, 'ArrowRight', 2)
            expect(await focused(page)).toContain('Sort')
            await press(page, 'Enter')
            await press(page, 'ArrowDown')
            expect(await page.evaluate(() => !!document.activeElement?.closest('[role=menu]'))).toBe(true)
            await press(page, 'Backspace')
            await expect(page.locator('[role=menu]')).toHaveCount(0)
            expect(await focused(page)).toContain('Sort')
            await press(page, 'Backspace')
            await expect(page).toHaveURL(/\/chats$/)
            await expect.poll(() => focused(page)).toBe(row)
        })

        test('A card on a TV: a long OK opens its menu, and the Options button in the header names the card the remote left and opens the same menu', async ({ page, context }) => {
            await fakeCase(context, 'mid')
            await page.goto('/chat/-1001')
            await expect.poll(() => focused(page)).toContain('card|/video/-1001/')
            // No card had the remote before this page: the button appears once one has it.
            const options = page.locator('.options-button')
            await expect(options).toHaveCount(1)
            const name = await page.evaluate(() => (document.activeElement as HTMLElement).querySelector('.card-name')?.textContent ?? '')
            expect(name.length).toBeGreaterThan(0)
            // A held OK is the long press: the card's own menu (it opened nothing on a TV before).
            await page.keyboard.down('Enter')
            await page.waitForTimeout(800)
            await page.keyboard.up('Enter')
            await expect(page.locator('dialog.card-menu[open]')).toHaveCount(1)
            await expect(page).toHaveURL(/\/chat\/-1001$/)
            // The context key opens it the same way, and Back closes it onto the card.
            await press(page, 'Backspace')
            await expect(page.locator('dialog.card-menu[open]')).toHaveCount(0)
            await expect.poll(() => focused(page)).toContain('card|/video/-1001/')
            await page.keyboard.press('ContextMenu')
            await expect(page.locator('dialog.card-menu[open]')).toHaveCount(1)
            await press(page, 'Backspace')
            await expect.poll(() => focused(page)).toContain('card|/video/-1001/')
            // Up from the first card is Back; Right along Star, Sort reaches Options, which names the video.
            await press(page, 'ArrowUp')
            expect(await focused(page)).toBe('round-button|Back')
            await press(page, 'ArrowRight', 3)
            const label = await options.getAttribute('aria-label')
            expect(label).toMatch(/^Options for /)
            expect(label!.length).toBeLessThanOrEqual('Options for '.length + 24)
            expect(await focused(page)).toBe(`round-button|${label}`)
            // Its name is drawn in a tip while the remote is on it, and OK opens the card's menu.
            expect(await options.evaluate((el) => getComputedStyle(el, '::after').opacity)).toBe('1')
            await press(page, 'Enter')
            await expect(page.locator('dialog.card-menu[open]')).toHaveCount(1)
            await expect(page.locator('dialog.card-menu h2')).not.toBeEmpty()
            // Back from it lands on the card, not on the button it was opened from.
            await press(page, 'Backspace')
            await expect(page.locator('dialog.card-menu[open]')).toHaveCount(0)
            await expect.poll(() => focused(page)).toContain('card|/video/-1001/')
        })

        test('The bar on Chats with the longest name: picture, 120 dp name, tabs, Choose chats, fullscreen and the brand all fit, none over another', async ({ page, context }) => {
            await fakeCase(context, 'mid')
            await page.goto('/chats')
            await expect(page.locator('.tv-picture-name')).toHaveText('Dana')
            // The widest first name the chip can hold: 64 letters, which the 120 dp cap cuts with an ellipsis.
            await page.evaluate(() => {
                const chip = document.querySelector('.tv-picture-name')
                if (chip) chip.textContent = 'W'.repeat(64)
            })
            const boxes = await page.evaluate(() => {
                const rect = (el: Element | null) => {
                    const r = el?.getBoundingClientRect()
                    return r ? { left: r.left, right: r.right, top: r.top, bottom: r.bottom } : null
                }
                const dp = innerWidth / 960
                const tabs = [...document.querySelectorAll('.tv-tab')].map(rect)
                const nav = document.querySelector('.tv-tabs') as HTMLElement | null
                return {
                    dp,
                    width: innerWidth,
                    picture: rect(document.querySelector('.tv-picture')),
                    chip: rect(document.querySelector('.tv-picture-name')),
                    tabs,
                    navOverflows: nav ? nav.scrollWidth > nav.clientWidth + 1 : false,
                    choose: rect(document.querySelector('.tv-choose')),
                    fullscreen: rect(document.querySelector('.tv-fullscreen')),
                    brand: rect(document.querySelector('.tv-brand')),
                }
            })
            const { picture, chip, choose, brand, fullscreen } = boxes
            expect(picture && chip && choose && brand && fullscreen).toBeTruthy()
            expect(chip!.right - chip!.left).toBeLessThanOrEqual(120 * boxes.dp + 1)
            // Left to right: picture, name, every tab, Choose chats, fullscreen, brand, each ending before the next starts.
            const order = [picture, chip, ...boxes.tabs, choose, fullscreen, brand].filter(Boolean) as { left: number; right: number }[]
            for (let i = 1; i < order.length; i++) expect(order[i].left, `item ${i} starts after item ${i - 1} ends`).toBeGreaterThanOrEqual(order[i - 1].right - 1)
            expect(boxes.navOverflows).toBe(false)
            expect(brand!.right).toBeLessThanOrEqual(boxes.width)
        })

        test('Choose chats: the bar holds a button on Chats only, OK opens the picker on its page, Back returns the remote to the button', async ({ page, context }) => {
            await fakeCase(context, 'mid')
            await page.goto('/')
            await expect.poll(() => focused(page)).toContain('card|/video/')
            // The page's Refresh button (WP-40) is the first stop above the cards, the bar the next.
            await press(page, 'ArrowUp', 2)
            await press(page, 'ArrowRight')
            await press(page, 'Enter')
            await expect(page).toHaveURL(/\/chats$/)
            await expect.poll(() => focused(page)).toBe('tv-tab|/chats')
            await press(page, 'ArrowRight')
            expect(await focused(page)).toBe('choose-chats|/added-chats')
            await expect(page.locator('.list-head .choose-chats')).toHaveCount(0)
            await press(page, 'Enter')
            await expect(page).toHaveURL(/\/added-chats$/)
            // The picker takes the remote, not the bar.
            await expect.poll(() => page.evaluate(() => !!document.activeElement?.closest('.page'))).toBe(true)
            await press(page, 'Backspace')
            await expect(page).toHaveURL(/\/chats$/)
            await expect.poll(() => focused(page)).toBe('choose-chats|/added-chats')
            await press(page, 'ArrowLeft')
            expect(await focused(page)).toBe('tv-tab|/chats')
            // Home has none, and its first focus stays on the content.
            await page.goto('/')
            await expect.poll(() => focused(page)).toContain('card|/video/')
            await expect(page.locator('.tv-bar .choose-chats')).toHaveCount(0)
        })

        test('Choose chats on 3,000 chats: the remote reaches Select all, the rows, a star and Done, OK ticks, and Back asks before it discards', async ({ page, context }) => {
            // Chats picked (none yet), not All chats: with the switch on every tick is disabled (HX86).
            await fakeCase(context, 'huge', 'picked')
            await page.goto('/__e2e/sizes')
            await page.evaluate(() => localStorage.setItem('tmplayer-layout', 'tv'))
            await page.goto('/added-chats')
            await expect(page.getByText('Has many videos').first()).toBeVisible({ timeout: 20_000 })
            // The remote starts in the search field, as the apps' picker does.
            await expect.poll(() => focusedText(page)).toBe('INPUT|Search chats')
            // Down: Select all first in its line (the line's start is where Down lands), then the rows' ticks.
            await press(page, 'ArrowDown')
            expect(await focusedText(page)).toBe('link|Select all')
            await press(page, 'ArrowRight')
            expect(await focusedText(page)).toBe('history-tab|All')
            await press(page, 'ArrowRight', 4)
            expect(await focusedText(page)).toBe('history-tab|People')
            await press(page, 'ArrowLeft', 5)
            expect(await focusedText(page)).toBe('link|Select all')
            await press(page, 'ArrowDown')
            expect(await focusedText(page)).toBe('chat-tick|Saved Messages')
            await press(page, 'ArrowDown')
            const row = await focusedText(page)
            expect(row).toMatch(/^chat-tick\|/)
            // OK ticks the row.
            await press(page, 'Enter')
            await expect(page.locator('.select-count')).toHaveText('1 chat chosen')
            // Right: the star, then Done, which floats at the foot and which no row lies beside.
            await press(page, 'ArrowRight')
            expect(await focusedText(page)).toMatch(/^icon-button\|/)
            await press(page, 'ArrowRight')
            expect(await focusedText(page)).toBe('primary|Done')
            // Back to the star the remote came from, and on Right nothing moves off Done.
            await press(page, 'ArrowRight')
            expect(await focusedText(page)).toBe('primary|Done')
            await press(page, 'ArrowLeft')
            expect(await focusedText(page)).toMatch(/^icon-button\|/)
            await press(page, 'ArrowLeft')
            expect(await focusedText(page)).toBe(row)
            // Back with a tick not saved asks first; Back again keeps the choice and returns the remote to the row.
            await press(page, 'Backspace')
            await expect(page.locator('dialog[open] h2')).toHaveText('Discard changes?')
            await press(page, 'Backspace')
            await expect(page.locator('dialog[open]')).toHaveCount(0)
            await expect(page).toHaveURL(/\/added-chats$/)
            expect(await focusedText(page)).toBe(row)
            // Done saves and leaves.
            await press(page, 'ArrowRight', 2)
            expect(await focusedText(page)).toBe('primary|Done')
            await press(page, 'Enter')
            await expect(page).not.toHaveURL(/\/added-chats/)
        })

        test('Settings: a choice is made with OK, not by moving; Sign out asks, Back closes the question onto it', async ({ page, context }) => {
            await fakeCase(context, 'mid')
            await page.goto('/')
            await expect.poll(() => focused(page)).toContain('card|/video/')
            // The page's Refresh button (WP-40) is the first stop above the cards, the bar the next.
            await press(page, 'ArrowUp', 2)
            await press(page, 'ArrowLeft', 3)
            await press(page, 'Enter')
            // OK on the picture opens the account's menu (WP-02); Settings is its row, one Down from the buttons.
            await expect(page.locator('.tv-menu[role=menu]')).toHaveCount(1)
            await press(page, 'ArrowDown')
            expect(await focusedText(page)).toBe('tv-menu-row|Settings')
            await press(page, 'Enter')
            await expect(page).toHaveURL(/\/settings$/)
            await expect(page.locator('.tv-menu')).toHaveCount(0)
            // The page opened with the remote on its first item; Up reaches the bar, on the picture (the page shown).
            for (let i = 0; i < 6 && !(await focused(page)).includes('tv-picture'); i++) await press(page, 'ArrowUp')
            await expect.poll(() => focused(page)).toContain('tv-picture')
            // Down to the theme's choices (Chosen chats stands above them since HX27).
            // Settings is a rail and a pane since WP-88: Appearance and language is the fifth page, the pane follows, Right enters it.
            const railName = () => page.evaluate(() => (document.activeElement as HTMLElement | null)?.closest('.tv-rail') ? (document.activeElement as HTMLElement).innerText : '')
            for (let i = 0; i < 14 && (await railName()) !== 'Appearance and language'; i++) await press(page, 'ArrowDown')
            await page.waitForTimeout(300)
            await press(page, 'ArrowRight')
            const onTheme = () => page.evaluate(() => (document.activeElement as HTMLInputElement | null)?.name === 'theme')
            for (let i = 0; i < 80 && !(await onTheme()); i++) await press(page, 'ArrowDown')
            expect(await onTheme()).toBe(true)
            await press(page, 'ArrowRight')
            expect(await page.evaluate(() => document.documentElement.dataset.theme ?? 'system')).toBe('system')
            await press(page, 'Enter')
            expect(await page.evaluate(() => document.documentElement.dataset.theme)).toBe('dark')
            // Sign out is in the Account page: back to the rail, up to it, into its pane.
            await press(page, 'Backspace')
            await press(page, 'ArrowUp', 4)
            await page.waitForTimeout(300)
            await press(page, 'ArrowRight')
            for (let i = 0; i < 30 && !(await focused(page)).includes('danger-button'); i++) await press(page, 'ArrowDown')
            expect(await focused(page)).toContain('danger-button')
            // Its icon beside the words, not on a line above them (the TV's Settings is not .set).
            expect(await offCentre(page, '.danger-button', 'svg', 'y')).toBeLessThanOrEqual(1)
            expect(await offCentre(page, '.outline-button', '', 'y')).toBe(0)
            await press(page, 'Enter')
            await expect(page.locator('dialog[open]')).toHaveCount(1)
            expect(await page.evaluate(() => !!document.activeElement?.closest('dialog[open]'))).toBe(true)
            await press(page, 'Backspace')
            await expect(page.locator('dialog[open]')).toHaveCount(0)
            expect(await focused(page)).toContain('danger-button')
        })

        test('Account menu: OK on the picture opens it on its first button, Right reaches Sign out, OK asks with Cancel focused, Back returns to Sign out, Back again closes the menu onto the picture', async ({ page, context }) => {
            await fakeCase(context, 'mid')
            await page.goto('/')
            await expect.poll(() => focused(page)).toContain('card|/video/')
            // The page's Refresh button (WP-40) is the first stop above the cards, the bar the next.
            await press(page, 'ArrowUp', 2)
            await press(page, 'ArrowLeft', 3)
            await expect.poll(() => focused(page)).toContain('tv-picture')
            await press(page, 'Enter')
            const menu = page.locator('.tv-menu[role=menu]')
            await expect(menu).toHaveCount(1)
            // The header says whose account it is, and the arrows stay inside the menu.
            await expect(menu).toContainText('Dana Test')
            expect(await focusedText(page)).toBe('tv-menu-button|Add account')
            await press(page, 'ArrowRight')
            expect(await focusedText(page)).toBe('tv-menu-button|Sign out')
            await press(page, 'Enter')
            await expect(page.locator('dialog[open]')).toHaveCount(1)
            // The question names the account and has Cancel focused: a stray OK keeps everything.
            await expect(page.locator('dialog[open]')).toContainText('Dana Test')
            expect(await page.evaluate(() => document.activeElement?.textContent?.trim())).toBe('Cancel')
            await press(page, 'Backspace')
            await expect(page.locator('dialog[open]')).toHaveCount(0)
            await expect(menu).toHaveCount(1)
            expect(await focusedText(page)).toBe('tv-menu-button|Sign out')
            await press(page, 'Backspace')
            await expect(menu).toHaveCount(0)
            await expect.poll(() => focused(page)).toContain('tv-picture')
        })

        test('Account menu: Down from Settings reaches the version line, OK copies it and says so, Up returns', async ({ page, context }) => {
            await context.grantPermissions(['clipboard-read', 'clipboard-write'])
            await fakeCase(context, 'mid')
            await page.goto('/')
            await expect.poll(() => focused(page)).toContain('card|/video/')
            // The page's Refresh button (WP-40) is the first stop above the cards, the bar the next.
            await press(page, 'ArrowUp', 2)
            await press(page, 'ArrowLeft', 3)
            await expect.poll(() => focused(page)).toContain('tv-picture')
            await press(page, 'Enter')
            await expect(page.locator('.tv-menu[role=menu]')).toHaveCount(1)
            await press(page, 'ArrowDown')
            expect(await focusedText(page)).toBe('tv-menu-row|Settings')
            await press(page, 'ArrowDown')
            // The compact version (the TMPlayer icon and v1.28.7, or web); OK copies the full details line.
            expect(await focusedText(page)).toMatch(/^tv-menu-row\|(v\d+\.\d+(\.\d+)?|web)$/)
            // 14 dp text at least, on the 960 x 540 dp layout (--dp is 1 px per dp there).
            const size = await page.evaluate(() => parseFloat(getComputedStyle(document.activeElement as HTMLElement).fontSize) / Math.min(innerWidth / 960, innerHeight / 540))
            expect(size).toBeGreaterThanOrEqual(14)
            await press(page, 'Enter')
            await expect(page.locator('.tv-menu [data-version-line] .version-text')).toHaveText('Version details copied')
            await expect(page.locator('.tv-menu [data-version-line] .version-text')).toHaveAttribute('aria-live', 'polite')
            // The same row keeps the remote, and Up goes back to Settings.
            expect(await focusedText(page)).toBe('tv-menu-row|Version details copied')
            await press(page, 'ArrowUp')
            expect(await focusedText(page)).toBe('tv-menu-row|Settings')
        })

        test('Settings: choosing Desktop with the remote takes the TV bar and keys away at once', async ({ page }) => {
            await page.goto('/settings')
            await expect.poll(() => focused(page)).not.toBe('')
            // The layout's choices are far down the page since WP-78 (Account, Playback and storage come first).
            // Appearance and language is the fifth page of the rail (WP-88).
            const railName = () => page.evaluate(() => (document.activeElement as HTMLElement | null)?.closest('.tv-rail') ? (document.activeElement as HTMLElement).innerText : '')
            for (let i = 0; i < 14 && (await railName()) !== 'Appearance and language'; i++) await press(page, 'ArrowDown')
            expect(await railName()).toBe('Appearance and language')
            await page.waitForTimeout(300)
            await press(page, 'ArrowRight')
            const onLayout = () => page.evaluate(() => (document.activeElement as HTMLInputElement | null)?.name === 'layout')
            // Language comes first on the page, a list of its own: the walk goes through it.
            for (let i = 0; i < 80 && !(await onLayout()); i++) await press(page, 'ArrowDown')
            expect(await onLayout()).toBe(true)
            for (let i = 0; i < 4 && (await page.evaluate(() => document.activeElement?.closest('label')?.textContent?.trim())) !== 'Desktop'; i++) await press(page, 'ArrowLeft')
            await press(page, 'Enter')
            await expect(page.locator('html')).toHaveAttribute('data-form', 'desktop')
            await expect(page.locator('.tv-bar')).toHaveCount(0)
            await expect(page.locator('nav.nav')).toBeVisible()
            expect(await page.evaluate(() => document.documentElement.dataset.tvKeys)).toBeUndefined()
        })

        // ---- The player (session 21): everything with the remote's keys ----

        /** Opens a chat's first card with the remote and waits for the picture to play. */
        async function watch(page: Page, chat: string) {
            await page.goto(`/chat/${chat}`)
            await expect.poll(() => focused(page)).toContain('card|/video/')
            await press(page, 'Enter')
            await expect.poll(() => focused(page)).toBe('detail-play|')
            await press(page, 'Enter')
            await expect(page).toHaveURL(/\/watch\//)
            await expect(page.locator('.pc[data-variant=tv]')).toHaveCount(1)
            await expect.poll(() => page.locator('video').evaluate((v: HTMLVideoElement) => v.currentTime)).toBeGreaterThan(0.2)
        }
        const controlsUp = (page: Page) => page.evaluate(() => document.querySelector('.pc')?.classList.contains('shown') ?? false)
        const paused = (page: Page) => page.locator('video').evaluate((v: HTMLVideoElement) => v.paused)
        const onControl = (page: Page) =>
            page.evaluate(() => {
                const a = document.activeElement as HTMLElement | null
                if (!a) return ''
                if (a.getAttribute('role') === 'slider') return 'bar'
                if (a.classList.contains('pc-play')) return 'disc'
                return a.getAttribute('aria-label') ?? a.textContent?.trim() ?? a.tagName
            })
        /** A key the TV sends by code alone (webOS Back 461, Pause 19, Play 415). */
        const code = (page: Page, keyCode: number, key = 'Unidentified') =>
            page.evaluate(([c, k]) => (document.activeElement ?? document.body).dispatchEvent(new KeyboardEvent('keydown', { key: k as string, keyCode: c as number, bubbles: true, cancelable: true })), [keyCode, key])

        test('Player: the picture starts bare, Left jumps, Up raises the row on the bar, Back peels the row and then the video', async ({ page }) => {
            await page.goto('/chat/-1001')
            await expect.poll(() => focused(page)).toContain('card|/video/')
            const card = await focused(page)
            await press(page, 'Enter')
            await expect.poll(() => focused(page)).toBe('detail-play|')
            await press(page, 'Enter')
            await expect(page).toHaveURL(/\/watch\//)
            await expect(page.locator('.pc[data-variant=tv]')).toHaveCount(1)
            expect(await controlsUp(page)).toBe(false)
            // Left over the bare picture (the test clip is 4 s long): ten seconds at once, the figure on the left, no row.
            await press(page, 'ArrowLeft')
            await expect(page.locator('.pc-ripple.left')).toBeVisible()
            expect(await controlsUp(page)).toBe(false)
            await press(page, 'ArrowUp')
            expect(await controlsUp(page)).toBe(true)
            expect(await onControl(page)).toBe('bar')
            // Back puts the row away, the next Back leaves the video for its page, the next the page, and the card has the remote again.
            await press(page, 'Backspace')
            expect(await controlsUp(page)).toBe(false)
            await expect(page).toHaveURL(/\/watch\//)
            await press(page, 'Backspace')
            await expect(page).toHaveURL(/\/video\//)
            await expect.poll(() => focused(page)).toBe('detail-play|')
            await press(page, 'Backspace')
            await expect(page).toHaveURL(/\/chat\/-1001$/)
            await expect.poll(() => focused(page)).toBe(card)
        })

        test('An uncaught failure: on a page the card takes the remote; in the player, its own sheet, and only if the video is stuck', async ({ page }) => {
            // On a page: the card, Left and Right walk its buttons, Back closes it (HX19).
            await page.goto('/chat/-1001')
            await expect.poll(() => focused(page)).toContain('card|/video/')
            await page.evaluate(() => setTimeout(() => { throw new Error('e2e uncaught') }))
            await expect(page.locator('.crash-card')).toBeVisible()
            await expect.poll(() => page.evaluate(() => document.activeElement?.textContent)).toBe('Reload')
            await press(page, 'ArrowRight')
            expect(await page.evaluate(() => document.activeElement?.textContent)).toBe('Close')
            await press(page, 'ArrowLeft')
            expect(await page.evaluate(() => document.activeElement?.textContent)).toBe('Reload')
            await press(page, 'Backspace')
            await expect(page.locator('.crash-card')).toHaveCount(0)
            await expect(page).toHaveURL(/\/chat\/-1001$/)

            // In the player, while the picture moves on: nothing over it, as the Mi TV's 3D video that played fine.
            await watch(page, '-1001')
            await page.locator('video').evaluate((v: HTMLVideoElement) => (v.loop = true))
            await page.evaluate(() => setTimeout(() => { throw new Error('e2e uncaught') }))
            await page.waitForTimeout(9_000)
            await expect(page.locator('.crash-card')).toHaveCount(0)
            await expect(page.locator('.pc-status')).toHaveCount(0)
            expect(await paused(page)).toBe(false)

            // Stuck (the picture does not move): the player's own status sheet, never the card.
            await page.locator('video').evaluate((v: HTMLVideoElement) => (v.playbackRate = 0))
            await page.evaluate(() => setTimeout(() => { throw new Error('e2e uncaught') }))
            await expect(page.locator('.pc-status')).toBeVisible({ timeout: 12_000 })
            await expect(page.locator('.crash-card')).toHaveCount(0)
            await expect.poll(() => page.evaluate(() => !!document.activeElement?.closest('.pc-status'))).toBe(true)
        })

        test('Player: OK pauses with the remote on the disc, arrows walk disc, bar and buttons, menus close back onto their button', async ({ page }) => {
            await watch(page, '-1001')
            await press(page, 'Enter')
            await expect.poll(() => paused(page)).toBe(true)
            expect(await controlsUp(page)).toBe(true)
            expect(await onControl(page)).toBe('disc')
            // The glyph in the middle of its disc: the TV's text-pill padding on button.tonal pushed it 7 px right.
            expect(await offCentre(page, '.pc-play', '.pc-play-glyph', 'x')).toBeLessThanOrEqual(1)
            // OK on the disc plays again (the 4 s test clip loops so the walk below has time).
            await press(page, 'Enter')
            await expect.poll(() => paused(page)).toBe(false)
            await page.locator('video').evaluate((v: HTMLVideoElement) => (v.loop = true))
            await press(page, 'ArrowDown')
            expect(await onControl(page)).toBe('bar')
            // On the bar Left seeks at once.
            await press(page, 'ArrowLeft')
            await expect.poll(() => page.locator('video').evaluate((v: HTMLVideoElement) => v.currentTime)).toBeLessThan(1)
            await press(page, 'ArrowDown')
            expect(await onControl(page)).toBe('Subtitles')
            await press(page, 'ArrowRight')
            expect(await onControl(page)).toBe('Audio')
            await press(page, 'Enter')
            await expect(page.locator('.pc-menu')).toHaveCount(1)
            expect(await page.evaluate(() => !!document.activeElement?.closest('.pc-menu'))).toBe(true)
            await press(page, 'Backspace')
            await expect(page.locator('.pc-menu')).toHaveCount(0)
            expect(await onControl(page)).toBe('Audio')
            await press(page, 'ArrowRight')
            expect(await onControl(page)).toBe('Playback speed')
            await press(page, 'Enter')
            await press(page, 'ArrowDown', 4)
            await press(page, 'Enter')
            await expect(page.locator('.pc-menu')).toHaveCount(0)
            expect(await page.locator('video').evaluate((v: HTMLVideoElement) => v.playbackRate)).toBe(1.5)
            expect(await onControl(page)).toBe('Playback speed')
            // Shape steps with a press and More opens its side panel, both reached with the arrows.
            await press(page, 'ArrowRight')
            expect(await onControl(page)).toContain('Picture shape')
            await press(page, 'Enter')
            expect(await onControl(page)).toContain('Picture shape: Fill')
            await press(page, 'ArrowRight')
            expect(await onControl(page)).toBe('More')
            await press(page, 'Enter')
            await expect(page.locator('.pc-panel')).toHaveCount(1)
            expect(await page.evaluate(() => !!document.activeElement?.closest('.pc-panel'))).toBe(true)
            await press(page, 'Backspace')
            expect(await onControl(page)).toBe('More')
            // Up from the buttons is the bar, Up again the disc; Back puts the row away.
            await press(page, 'ArrowUp')
            expect(await onControl(page)).toBe('bar')
            await press(page, 'ArrowUp')
            expect(await onControl(page)).toBe('disc')
            await press(page, 'Backspace')
            expect(await controlsUp(page)).toBe(false)
        })

        test('Player: Episodes opens on the playing one, Down and OK start the next, Back closes it onto its pill', async ({ page }) => {
            await watch(page, '-1002')
            await press(page, 'ArrowUp')
            await press(page, 'ArrowDown')
            // Right along the row to the Episodes pill.
            for (let i = 0; i < 8 && (await onControl(page)) !== 'Episodes'; i++) await press(page, 'ArrowRight')
            expect(await onControl(page)).toBe('Episodes')
            await press(page, 'Enter')
            await expect(page.locator('.pc-episodes')).toBeVisible()
            expect(await page.evaluate(() => document.activeElement?.classList.contains('playing'))).toBe(true)
            await press(page, 'Backspace')
            await expect(page.locator('.pc-episodes')).toHaveCount(0)
            expect(await onControl(page)).toBe('Episodes')
            await press(page, 'Enter')
            const first = page.url()
            await press(page, 'ArrowDown')
            expect(await page.evaluate(() => document.activeElement?.classList.contains('pc-episode'))).toBe(true)
            await press(page, 'Enter')
            await expect(page.locator('.pc-episodes')).toHaveCount(0)
            await expect.poll(() => page.url()).not.toBe(first)
        })

        test('Player: the media keys by code (Pause 19, Play 415, Rewind 412, Back 461) work without a name', async ({ page }) => {
            await watch(page, '-1001')
            await code(page, 19)
            await expect.poll(() => paused(page)).toBe(true)
            expect(await onControl(page)).toBe('disc')
            await code(page, 415)
            await expect.poll(() => paused(page)).toBe(false)
            await page.locator('video').evaluate((v: HTMLVideoElement) => (v.loop = true))
            await code(page, 412)
            await expect(page.locator('.pc-ripple.left')).toBeVisible()
            // Back peels the row first (it is up for a few seconds after a key), then leaves.
            const rowUp = await controlsUp(page)
            await code(page, 461, 'GoBack')
            if (rowUp) await code(page, 461, 'GoBack')
            await expect(page).toHaveURL(/\/video\/-1001\//)
        })

        test('Player: a file that needs the app puts the remote on its sheet, and Back leaves', async ({ page }) => {
            await page.goto('/chat/-1001')
            await expect.poll(() => focused(page)).toContain('card|/video/')
            // Toward the AVI whose picture is not converted (Microsoft Video 1) with the arrows, whatever the columns: the sponsored card after the third video breaks the first row.
            for (let i = 0; i < 30; i++) {
                const way = await page.evaluate(() => {
                    const at = document.activeElement
                    const goal = [...document.querySelectorAll('a.card')].find((c) => c.textContent?.includes('Odd Picture Clip'))
                    if (!at || !goal || at === goal) return ''
                    const f = at.getBoundingClientRect()
                    const g = goal.getBoundingClientRect()
                    if (g.top >= f.bottom) return 'ArrowDown'
                    if (g.bottom <= f.top) return 'ArrowUp'
                    return g.left > f.left ? 'ArrowRight' : 'ArrowLeft'
                })
                if (!way) break
                await press(page, way)
            }
            expect(await focused(page)).toBe('card|/video/-1001/13')
            await press(page, 'Enter')
            await expect.poll(() => focused(page)).toBe('detail-play|')
            await press(page, 'Enter')
            await expect(page.getByText('This video needs the TMPlayer app')).toBeVisible({ timeout: 30_000 })
            expect(await page.evaluate(() => !!document.activeElement?.closest('.pc-status'))).toBe(true)
            await press(page, 'Backspace')
            await expect(page).toHaveURL(/\/video\/-1001\/13/)
        })

        test('Player: a paused picture with the row away stops the page\'s own timers', async ({ page }) => {
            await watch(page, '-1001')
            await code(page, 19)
            await press(page, 'Backspace')
            expect(await controlsUp(page)).toBe(false)
            // The container's own idle flag settles a few seconds after the last key; after that the save and
            // stall timers are cleared by the pause and nothing the page owns wakes in the next 2.5 s.
            await page.waitForTimeout(5000)
            const woke = await page.evaluate(
                () =>
                    new Promise<string[]>((resolve) => {
                        const seen: string[] = []
                        const mo = new MutationObserver((list) => list.forEach((m) => seen.push(`${m.type}:${(m.target as HTMLElement).className ?? ''}:${m.attributeName ?? ''}`)))
                        mo.observe(document.body, { subtree: true, childList: true, attributes: true, characterData: true })
                        setTimeout(() => (mo.disconnect(), resolve(seen)), 2500)
                    }),
            )
            expect(woke).toEqual([])
        })

        test('Search: the field opens for typing on OK only, Down goes to the results, Up to the bar', async ({ page }) => {
            await page.goto('/search')
            await expect.poll(() => page.evaluate(() => document.activeElement?.tagName)).toBe('INPUT')
            expect(await page.evaluate(() => (document.activeElement as HTMLInputElement).readOnly)).toBe(true)
            await press(page, 'Enter')
            await page.keyboard.type('Clip')
            await expect(page).toHaveURL(/q=Clip/)
            await expect(page.locator('.card').first()).toBeVisible()
            await press(page, 'ArrowDown')
            expect(await focused(page)).toContain('card|/video/')
            await press(page, 'ArrowUp')
            expect(await focused(page)).toBe('INPUT|Search videos and chats')
            await press(page, 'ArrowUp')
            expect(await focused(page)).toBe('tv-tab|/search')
        })

        test('A notice with buttons never takes the remote, but Up from the bar and Down from the foot reach it, and Back, Down or closing it gives the remote back (HX22)', async ({ page, context }) => {
            await fakeCase(context, 'mid')
            await page.goto('/')
            await expect.poll(() => focused(page)).toContain('card|/video/')
            // The page's Refresh button (WP-40) is the first stop above the cards, the bar the next.
            await press(page, 'ArrowUp', 2)
            await press(page, 'ArrowRight')
            await press(page, 'Enter')
            await expect.poll(() => focused(page)).toBe('tv-tab|/chats')
            const barItem = await focused(page)
            await page.evaluate(() => (window as unknown as { __e2eUpdateReady: () => void }).__e2eUpdateReady())
            await expect(page.locator('.update-notice')).toBeVisible()
            const inNotice = () => page.evaluate(() => !!document.activeElement?.closest('.update-notice'))
            const words = () => page.evaluate(() => document.activeElement?.textContent ?? '')
            // It came up without taking the remote.
            expect(await focused(page)).toBe(barItem)
            // Up from the bar: into it, on its first button. Left and Right walk its two buttons.
            await press(page, 'ArrowUp')
            expect(await inNotice()).toBe(true)
            expect(await words()).toBe('Later')
            await press(page, 'ArrowRight')
            expect(await words()).toBe('Reload')
            await press(page, 'ArrowLeft')
            expect(await words()).toBe('Later')
            // Back: the remote is back on the bar's item it came from, and the page did not move.
            await press(page, 'Backspace')
            expect(await focused(page)).toBe(barItem)
            await expect(page).toHaveURL(/\/chats$/)
            // Down from the bar goes into the page, not the notice.
            await press(page, 'ArrowDown')
            expect(await inNotice()).toBe(false)
            expect(await focused(page)).not.toBe(barItem)
            // A chat of a few cards: Down from its last row reaches the notice at the foot.
            await page.goto('/chat/-1002')
            await expect.poll(() => focused(page)).toContain('card|/video/')
            await page.evaluate(() => (window as unknown as { __e2eUpdateReady: () => void }).__e2eUpdateReady())
            await expect(page.locator('.update-notice')).toBeVisible()
            for (let i = 0; i < 30 && !(await inNotice()); i++) await press(page, 'ArrowDown')
            expect(await inNotice()).toBe(true)
            // Up, the way the remote came, goes back onto the page's last item.
            await press(page, 'ArrowUp')
            expect(await inNotice()).toBe(false)
            const last = await focused(page)
            await press(page, 'ArrowDown')
            expect(await inNotice()).toBe(true)
            // OK on Later: the notice goes, and the remote is back where it was.
            await press(page, 'Enter')
            await expect(page.locator('.update-notice')).toHaveCount(0)
            await expect.poll(() => focused(page)).toBe(last)
        })

        test('A toast\'s Undo is reached from the bar, stays up while the remote is on it, and OK undoes (HX22)', async ({ page }) => {
            test.setTimeout(45_000)
            await page.evaluate(
                () =>
                    new Promise<void>((resolve, reject) => {
                        const open = indexedDB.open('tmplayer-app')
                        open.onerror = () => reject(open.error)
                        open.onsuccess = () => {
                            const tx = open.result.transaction('kv', 'readwrite')
                            tx.objectStore('kv').put(1, 'watched:-1001:1')
                            tx.oncomplete = () => (open.result.close(), resolve())
                        }
                    }),
            )
            await page.goto('/settings')
            await expect.poll(() => focused(page)).not.toBe('')
            const on = () => page.evaluate(() => document.activeElement?.getAttribute('aria-labelledby') ?? '')
            // Clear history is on the rail's Home and library page (WP-88): open it, then Down walks the pane to the button.
            await openTvSettingsPage(page, 'home')
            for (let i = 0; i < 60 && (await on()) !== 'history-label'; i++) await press(page, 'ArrowDown')
            expect(await on()).toBe('history-label')
            await press(page, 'Enter')
            // Clear history asks first (HX59): the question opens on Cancel, Right reaches the red button.
            await expect(page.locator('dialog[open]')).toHaveCount(1)
            await press(page, 'ArrowRight')
            await press(page, 'Enter')
            await expect(page.locator('.toast button')).toBeVisible()
            // The toast never takes the remote.
            expect(await on()).toBe('history-label')
            // Back from the pane is the rail (WP-88), Back from the rail the bar.
            await press(page, 'Backspace')
            expect(await page.evaluate(() => !!document.activeElement?.closest('.tv-rail'))).toBe(true)
            await press(page, 'Backspace')
            const barItem = await focused(page)
            await press(page, 'ArrowUp')
            expect(await page.evaluate(() => !!document.activeElement?.closest('.toast'))).toBe(true)
            // Past its 8 s, it stays while the remote is on it.
            await page.waitForTimeout(9_000)
            await expect(page.locator('.toast button')).toBeVisible()
            await press(page, 'Enter')
            await expect(page.locator('.toast')).toHaveCount(0)
            await expect.poll(() => focused(page)).toBe(barItem)
            const back = await page.evaluate(
                () =>
                    new Promise<unknown>((resolve) => {
                        const open = indexedDB.open('tmplayer-app')
                        open.onsuccess = () => {
                            const get = open.result.transaction('kv').objectStore('kv').get('watched:-1001:1')
                            get.onsuccess = () => (open.result.close(), resolve(get.result))
                        }
                    }),
            )
            expect(back).toBe(1)
        })

        test('keys only, every screen: the remote lands on something, never falls to the page, and Back always gets out', async ({ page }) => {
            test.setTimeout(150_000)
            const onBody = () => page.evaluate(() => !document.activeElement || document.activeElement === document.body || document.activeElement === document.documentElement)
            for (const path of ['/', '/chats', '/chat/-1001', '/video/-1001/1', '/search', '/settings', '/history', '/history/later', '/history/watched']) {
                await page.goto(path)
                await expect(page.locator('html')).toHaveAttribute('data-form', 'tv')
                await expect.poll(() => onBody(), { message: `${path}: focus starts on a control` }).toBe(false)
                const seen = new Set<string>([await focused(page)])
                for (const key of ['ArrowDown', 'ArrowDown', 'ArrowRight', 'ArrowDown', 'ArrowLeft', 'ArrowUp', 'ArrowRight', 'ArrowRight', 'ArrowDown', 'ArrowUp', 'ArrowUp', 'ArrowLeft', 'ArrowLeft']) {
                    await press(page, key)
                    expect(await onBody(), `${path}: ${key} dropped the focus`).toBe(false)
                    seen.add(await focused(page))
                }
                expect(seen.size, `${path}: the arrows reach more than one control`).toBeGreaterThan(1)
                // Back, over and over, leaves every screen for the bar and stays there.
                for (let i = 0; i < 6; i++) await press(page, 'Backspace')
                expect(await onBody(), `${path}: Back dropped the focus`).toBe(false)
            }
        })
    })
}

test.describe('TV sign in', () => {
    test.skip(({ browserName }) => browserName !== 'chromium', 'the TV walk runs in Chromium')
    test.use({ viewport: { width: 1920, height: 1080 } })
    test.beforeEach(async ({ page, context }) => {
        await fakeCase(context, 'signedout')
        await tv(page)
    })

    test('the QR code first, the number by the pad and the remote\'s number keys, no field takes the keyboard', async ({ page }) => {
        await page.goto('/')
        await expect(page).toHaveURL(/\/signin/)
        await expect(page.locator('.signin.is-qr .qr')).toBeVisible()
        // Down to "Use my phone number", OK: the pad takes the remote on its first digit.
        for (let i = 0; i < 6 && !(await page.evaluate(() => document.activeElement?.textContent === 'Use my phone number')); i++) await press(page, 'ArrowDown')
        await press(page, 'Enter')
        await expect(page.locator('.keypad')).toBeVisible()
        expect(await focused(page)).toBe('key|')
        const field = page.getByRole('textbox', { name: 'Phone number' })
        expect(await field.evaluate((el: HTMLInputElement) => el.readOnly)).toBe(true)
        // 5 then 7 by the pad (Right, OK); 3 by the remote's number key; Delete takes the last away.
        await press(page, 'ArrowDown')
        await press(page, 'ArrowRight')
        expect(await page.evaluate(() => document.activeElement?.textContent)).toBe('5')
        await press(page, 'Enter')
        await press(page, 'ArrowDown')
        await press(page, 'ArrowLeft')
        expect(await page.evaluate(() => document.activeElement?.textContent)).toBe('7')
        await press(page, 'Enter')
        await page.keyboard.press('3')
        await expect(field).toHaveValue(/573/)
        await press(page, 'ArrowDown')
        expect(await page.evaluate(() => document.activeElement?.getAttribute('aria-label'))).toBe('Delete')
        await press(page, 'Enter')
        await expect(field).toHaveValue(/57$/)
        // No editable field is ever the one the remote is on.
        expect(await page.evaluate(() => (document.activeElement as HTMLInputElement | null)?.readOnly ?? true)).toBe(true)
    })
})

// HX18: the TV layout's alignment, measured, at both TV sizes.
const PICTURE = readFileSync(new URL('./media/thumb.jpg', import.meta.url))

/** A /tmdb worker that matches every title with a poster, a wide picture and an overview, so Recently added fills the carousel. */
async function everyTitleMatched(page: Page) {
    await page.route('**/tmdb/**', (route) => {
        const path = new URL(route.request().url()).pathname
        if (path === '/tmdb/poster') return route.fulfill({ json: { match: true, id: '77', kind: 'film', title: 'Harbour Notes', year: 2019, poster: '/p0.jpg', backdrop: '/w0.jpg', overview: 'A harbour town keeps its notes.' } })
        if (path.startsWith('/tmdb/img/')) return route.fulfill({ contentType: 'image/jpeg', body: PICTURE })
        return route.fulfill({ json: { found: false } })
    })
}

const box = (page: Page, selector: string) => page.locator(selector).first().evaluate((el) => el.getBoundingClientRect().toJSON() as DOMRect)
const mid = (r: DOMRect) => r.top + r.height / 2

for (const size of [
    { width: 1920, height: 1080 },
    { width: 1280, height: 720 },
]) {
    test.describe(`TV alignment at ${size.width} x ${size.height}`, () => {
        test.skip(({ browserName }) => browserName !== 'chromium', 'the TV walk runs in Chromium')
        test.use({ viewport: size })

        test('Home carousel: Play and Details are one height on one middle, the dots on it, and the title\'s wide picture is up', async ({ page, context }) => {
            await fakeCase(context, 'big')
            await everyTitleMatched(page)
            await tv(page)
            await page.goto('/')
            await expect(page.locator('.tv-hero-play')).toBeVisible()
            const play = await box(page, '.tv-hero-play')
            const pill = await box(page, '.tv-hero-pill')
            const dot = await box(page, '.tv-hero-dots span')
            // Heights as laid out (the remote's focus only swaps the fill, so nothing moves).
            const tall = (sel: string) => page.locator(sel).first().evaluate((el) => (el as HTMLElement).offsetHeight)
            expect(await tall('.tv-hero-play')).toBe(await tall('.tv-hero-pill'))
            expect(Math.abs(mid(play) - mid(pill))).toBeLessThan(1)
            expect(Math.abs(mid(play) - mid(dot))).toBeLessThan(1.5)
            // The page's picture is drawn, never a blank dark area.
            await expect(page.locator('.tv-hero-art img')).toHaveCount(1)
            await expect(page.locator('.tv-hero-art.cover')).toHaveCount(0)
            // Details opens the page's video, and Back puts the remote on Details again, not on that video's card below.
            for (let i = 0; i < 4 && !(await focused(page)).startsWith('tv-hero-play|'); i++) await press(page, 'ArrowUp')
            await press(page, 'ArrowRight')
            const details = await focused(page)
            expect(details).toContain('tv-hero-pill|/video/')
            await press(page, 'Enter')
            await expect(page).toHaveURL(/\/video\//)
            await press(page, 'Backspace')
            await expect(page).toHaveURL(/\/$/)
            await expect.poll(() => focused(page)).toBe(details)
        })

        test('Recently added: Right past the last card lands on See all, a card of the row\'s size, and OK opens the page, which reads on', async ({ page, context }) => {
            test.setTimeout(60_000)
            await fakeCase(context, 'manyrecent', 'mid')
            await tv(page)
            await page.goto('/')
            await expect.poll(() => focused(page)).toContain('card|/video/')
            // The heading keeps no See all of its own on a TV.
            await expect(page.locator('.row-head .see-all')).toHaveCount(0)
            const end = page.locator('.home-row').first().locator('.see-all-card')
            await expect(end).toHaveAttribute('href', '/recent')
            // Sizes as laid out: the focused first card is grown by the focus.
            const dims = (el: Element) => [(el as HTMLElement).offsetWidth, (el as HTMLElement).offsetHeight]
            const art = await end.locator('.card-art').evaluate(dims)
            const other = await page.locator('.home-row').first().locator('.card:not(.see-all-card) .card-art').last().evaluate(dims)
            expect(art).toEqual(other)
            for (let i = 0; i < 12 && !(await focused(page)).startsWith('card|/recent'); i++) await press(page, 'ArrowRight')
            expect(await focused(page)).toBe('card|/recent')
            // In sight once the remote is on it: the strip scrolled to it.
            const box = (await end.boundingBox())!
            expect(box.x + box.width).toBeLessThanOrEqual(size.width)
            await press(page, 'Enter')
            await expect(page).toHaveURL(/\/recent$/)
            await expect(page.locator('.title-pill')).toContainText('Recently added')
            await expect.poll(() => focused(page)).toContain('card|/video/')
            // Down a few rows moves through the grid; scrolled to its foot, it reads on to the oldest video.
            await press(page, 'ArrowDown', 3)
            expect(await focused(page)).toContain('card|/video/')
            await expect
                .poll(
                    async () => {
                        await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
                        await page.waitForTimeout(200)
                        return page.locator('.vgrid .card', { hasText: 'Native Clip' }).count()
                    },
                    { timeout: 25_000 },
                )
                .toBeGreaterThan(0)
        })

        test('a chat whose cards take seconds to come still opens on its first card, not on Back', async ({ page, context }) => {
            await fakeCase(context, 'slow')
            await tv(page)
            await page.goto('/chat/-1001')
            await expect.poll(() => focused(page), { timeout: 15_000 }).toContain('card|/video/')
        })

        test('Sign in: the two-step password column is in the middle of the screen', async ({ page, context }) => {
            await fakeCase(context, 'signedout', 'password')
            await tv(page)
            await page.goto('/')
            await expect(page.locator('input[type=password]')).toBeVisible()
            for (const sel of ['.password-field', '.signin form button.primary']) {
                const r = await box(page, sel)
                expect(Math.abs(r.left - (size.width - r.right)), sel).toBeLessThan(1)
            }
        })

        test('Sign in: the QR code beside its words, all of it on the screen, its two buttons one height', async ({ page, context }) => {
            await fakeCase(context, 'signedout')
            await tv(page)
            await page.goto('/')
            await expect(page.locator('.signin.is-qr .qr')).toBeVisible()
            const plate = await box(page, '.qr')
            const words = await box(page, '.signin.is-qr h1')
            expect(plate.right).toBeLessThan(words.left)
            for (const sel of ['.signin-brand', '.qr', '.signin-foot']) {
                const r = await box(page, sel)
                expect(r.top, sel).toBeGreaterThanOrEqual(0)
                expect(r.bottom, sel).toBeLessThanOrEqual(size.height)
            }
            const phone = await box(page, '.qr-actions > button.link')
            const fresh = await box(page, '.qr-actions > .qr-refresh')
            expect(Math.abs(phone.height - fresh.height)).toBeLessThan(1)
            expect(Math.abs(mid(phone) - mid(fresh))).toBeLessThan(1)
        })

        // WB19: the series page on the TV. A missing episode is a dimmed row the remote stops on (and that wakes up there), an
        // upcoming one is no control, so Up and Down move over it to the next row that is.
        test('Series page: the remote walks the held and missing episodes, wakes a dimmed row on focus and passes the ones not aired', async ({ page, context }) => {
            await fakeCase(context)
            await tv(page)
            await mockSplitShow(page, [], readFileSync(new URL('./media/thumb.jpg', import.meta.url)))
            await page.goto('/chat/-1002')
            await expect(page.locator('html')).toHaveAttribute('data-form', 'tv')
            await page.locator('.card', { hasText: 'Harbour Notes' }).first().click()
            await expect(page.locator('.episode.gap')).toHaveCount(22)
            await page.locator('.season-chip.on').focus()
            // Three held episodes (links), then the missing ones (buttons) from the fourth.
            await press(page, 'ArrowDown', 3)
            expect(await focused(page)).toMatch(/^episode\|\/watch\//)
            await press(page, 'ArrowDown')
            expect(await focused(page)).toBe('episode|Episode 4, not in this chat')
            // Dimmed at rest, awake on focus: the words are at full strength while the row holds the remote.
            expect(await page.evaluate(() => getComputedStyle(document.activeElement!.querySelector('.episode-text')!).opacity)).toBe('1')
            expect(await page.evaluate(() => getComputedStyle(document.querySelector('button.episode.gap:not(:focus) .episode-text')!).opacity)).toBe('0.45')
            // To the twenty-third (the last aired), one more Down: never onto a row not aired.
            await press(page, 'ArrowDown', 19)
            expect(await focused(page)).toBe('episode|Episode 23, not in this chat')
            await press(page, 'ArrowDown')
            expect(await page.evaluate(() => !!document.activeElement?.closest('.upcoming'))).toBe(false)
            await press(page, 'ArrowUp')
            expect(await focused(page)).toBe('episode|Episode 23, not in this chat')
            // OK on a missing row asks where to find it, Back closes the card and the remote is back on the row.
            await press(page, 'Enter')
            await expect(page.locator('dialog[open]')).toContainText('is not in your chats yet')
            await press(page, 'Escape')
            await expect(page.locator('dialog[open]')).toHaveCount(0)
            expect(await focused(page)).toBe('episode|Episode 23, not in this chat')
            if (process.env.WB_SHOTS) {
                await page.evaluate(() => document.querySelector('.season-tabs')!.scrollIntoView())
                await page.screenshot({ path: new URL(`../../design/web-gaps/WB19/tv-${size.width}.png`, import.meta.url).pathname })
            }
        })
    })
}
