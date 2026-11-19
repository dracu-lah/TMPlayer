// WB16 Telegram links (the apps' U9): Copy Telegram link and Open in Telegram on a card's menu, Copy
// link at this time in the player's More options. A public chat gives t.me/<name>/<id>, a private
// channel t.me/c/<id>/<id>, a forum topic sits between; none for a chat that restricts saving; a
// message that is gone says so; a refused clipboard shows the link to copy by hand. The fake's
// chats with real looking ids are behind the `links` case (fake/mtcute.ts).
import { mkdirSync } from 'node:fs'
import { expect, test, type Locator, type Page, type TestInfo } from '@playwright/test'
import { fakeCase, noSizeFloor, playsPast } from './helpers'

const SHOTS = process.env.WB_SHOTS ? `${process.cwd()}/../design/web-gaps/WB16` : null
async function shot(page: Page, info: TestInfo, name: string) {
    if (!SHOTS) return
    await page.waitForTimeout(400)
    mkdirSync(SHOTS, { recursive: true })
    await page.screenshot({ path: `${SHOTS}/${name}-${info.project.name}.png` })
}

const LINK_ROOM = '/chat/-1001234567890'
const BACK_ROOM = '/chat/-1009876543210'
const SEALED_ROOM = '/chat/-1005555555555'

/** A clipboard the test can read in every browser: `refuse` is a browser that will not let the page copy. */
async function clipboard(page: Page, refuse = false) {
    await page.addInitScript((no) => {
        const w = window as unknown as { __copied: string[] }
        w.__copied = []
        const stub = { writeText: async (text: string) => (no ? Promise.reject(new DOMException('denied', 'NotAllowedError')) : void w.__copied.push(text)) }
        Object.defineProperty(navigator, 'clipboard', { value: stub, configurable: true })
        if (no) document.execCommand = () => false
    }, refuse)
}
const copied = (page: Page) => page.evaluate(() => (window as unknown as { __copied: string[] }).__copied)

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

const menu = (page: Page) => page.locator('dialog.card-menu[open]')
const copyRow = (page: Page) => menu(page).getByRole('button', { name: /^Copy Telegram link/ })
const openRow = (page: Page) => menu(page).getByRole('link', { name: 'Open in Telegram' })
const toast = (page: Page) => page.locator('.toast')

async function menuFor(page: Page, room: string, name: string) {
    await noSizeFloor(page)
    await page.goto(room)
    const card = page.locator('.card').filter({ hasText: name })
    await expect(card).toBeVisible()
    await menuOf(page, card)
    await expect(menu(page)).toBeVisible()
}

test.beforeEach(async ({ context, page }) => {
    await fakeCase(context, 'links')
    await clipboard(page)
})

test('a public chat: Copy Telegram link puts t.me/<name>/<id> on the clipboard and says so, Open in Telegram points at it', async ({ page }, info) => {
    await menuFor(page, LINK_ROOM, 'Plain Clip')
    await expect(openRow(page)).toHaveAttribute('href', /^https:\/\/t\.me\/linkroom\/\d+$/)
    await expect(openRow(page)).toHaveAttribute('target', '_blank')
    await expect(openRow(page)).toHaveAttribute('rel', /noopener/)
    await shot(page, info, 'menu')
    await copyRow(page).click()
    await expect(menu(page)).toHaveCount(0)
    await expect(toast(page)).toHaveText('Link copied')
    const [link] = await copied(page)
    expect(link).toMatch(/^https:\/\/t\.me\/linkroom\/\d+$/)
    await expect(page.locator('.card').filter({ hasText: 'Plain Clip' })).toBeVisible()
    await shot(page, info, 'copied')
})

test('a video in a forum topic has the topic between the chat and the message', async ({ page }) => {
    await menuFor(page, LINK_ROOM, 'Topic Clip')
    await expect(openRow(page)).toHaveAttribute('href', /^https:\/\/t\.me\/linkroom\/9\/\d+$/)
    await copyRow(page).click()
    expect((await copied(page))[0]).toMatch(/^https:\/\/t\.me\/linkroom\/9\/\d+$/)
})

test('a private channel gives the t.me/c/ form', async ({ page }) => {
    await menuFor(page, BACK_ROOM, 'Back Clip')
    await expect(openRow(page)).toHaveAttribute('href', /^https:\/\/t\.me\/c\/9876543210\/\d+$/)
    await copyRow(page).click()
    expect((await copied(page))[0]).toMatch(/^https:\/\/t\.me\/c\/9876543210\/\d+$/)
})

test('a chat that restricts saving offers no link, and the rest of the menu is there', async ({ page }) => {
    await menuFor(page, SEALED_ROOM, 'Sealed Clip')
    await expect(menu(page).getByRole('button', { name: 'Play' })).toBeVisible()
    await expect(copyRow(page)).toHaveCount(0)
    await expect(openRow(page)).toHaveCount(0)
})

test('a chat with no message links (a small group) says so and copies nothing', async ({ page }) => {
    await menuFor(page, '/chat/-1002', 'Harbour')
    await copyRow(page).click()
    await expect(toast(page)).toHaveText('This chat has no links to its messages')
    expect(await copied(page)).toEqual([])
    await expect(openRow(page)).toHaveCount(0)
})

test('a message that is gone (Telegram says it no longer exists): the menu says so, nothing is copied, no Open in Telegram', async ({ page, context }) => {
    await fakeCase(context, 'links', 'linksgone')
    await menuFor(page, LINK_ROOM, 'Plain Clip')
    await copyRow(page).click()
    await expect(toast(page)).toContainText('This post was not found')
    expect(await copied(page)).toEqual([])
    await expect(openRow(page)).toHaveCount(0)
})

test('a chat that became protected after the card was stored: Telegram says so when the menu opens, and the link entries go', async ({ page, context }) => {
    await fakeCase(context, 'links', 'linksprot')
    await menuFor(page, LINK_ROOM, 'Plain Clip')
    await expect(menu(page).getByRole('button', { name: 'Play' })).toBeVisible()
    await expect(copyRow(page)).toHaveCount(0)
    await expect(openRow(page)).toHaveCount(0)
    expect(await copied(page)).toEqual([])
})

test('a browser that refuses the clipboard shows the link, selected, to copy by hand', async ({ page }, info) => {
    await menuFor(page, LINK_ROOM, 'Plain Clip')
    // The stub has already been installed as allowing; replace it with one that refuses.
    await page.evaluate(() => {
        Object.defineProperty(navigator, 'clipboard', { value: { writeText: () => Promise.reject(new DOMException('denied', 'NotAllowedError')) }, configurable: true })
        document.execCommand = () => false
    })
    await copyRow(page).click()
    const field = menu(page).locator('input.link-field')
    await expect(field).toBeVisible()
    await expect(field).toHaveValue(/^https:\/\/t\.me\/linkroom\/\d+$/)
    expect(await field.evaluate((el: HTMLInputElement) => el.selectionEnd! - el.selectionStart!)).toBeGreaterThan(10)
    await expect(toast(page)).toHaveCount(0)
    await shot(page, info, 'by-hand')
    await page.keyboard.press('Escape')
    await expect(menu(page)).toHaveCount(0)
})

test('the same tap twice copies once, and the keyboard reaches Copy and Open in Telegram', async ({ page }) => {
    await menuFor(page, LINK_ROOM, 'Plain Clip')
    await copyRow(page).dblclick()
    expect(await copied(page)).toHaveLength(1)
    // Again from the keyboard: Tab from the first row to the Copy row, Enter.
    const card = page.locator('.card').filter({ hasText: 'Plain Clip' })
    await menuOf(page, card)
    await expect(menu(page)).toBeVisible()
    let reached = ''
    for (let i = 0; i < 14 && !reached.startsWith('Copy Telegram link'); i++) {
        await page.keyboard.press('Tab')
        reached = await page.evaluate(() => (document.activeElement as HTMLElement | null)?.textContent?.trim() ?? '')
    }
    expect(reached).toMatch(/^Copy Telegram link/)
    await page.keyboard.press('Tab')
    await expect(openRow(page)).toBeFocused()
    await page.keyboard.press('Shift+Tab')
    await page.keyboard.press('Enter')
    await expect(toast(page)).toHaveText('Link copied')
    expect(await copied(page)).toHaveLength(2)
})

test.describe('in the player', () => {
    test.skip(({ browserName }) => browserName !== 'chromium', 'the player menu runs in Chromium')

    async function play(page: Page, room: string, name: string) {
        await noSizeFloor(page)
        await page.goto(room)
        await page.locator('.card').filter({ hasText: name }).click()
        await expect(page).toHaveURL(/\/video\//)
        await page.locator('.detail-play').click()
        await expect(page).toHaveURL(/\/watch\//)
        await playsPast(page, 0.3)
        await page.locator('video').first().evaluate((v: HTMLVideoElement) => (v.pause(), (v.currentTime = 2)))
    }
    const openMore = async (page: Page) => {
        await page.mouse.move(400, 300)
        await page.locator('[data-shot=more]').first().click()
    }

    test('Copy link at this time puts the link with the second playing on the clipboard', async ({ page }, info) => {
        await play(page, LINK_ROOM, 'Plain Clip')
        await openMore(page)
        const entry = page.getByRole('menuitem', { name: 'Copy link at this time' })
        await expect(entry).toBeVisible()
        await shot(page, info, 'player-menu')
        await entry.click()
        await expect.poll(async () => (await copied(page))[0] ?? '').toMatch(/^https:\/\/t\.me\/linkroom\/\d+\?t=2$/)
        await expect(page.locator('.pc-note')).toHaveText('Link copied')
    })

    test('a refused clipboard opens a sheet with the link to copy by hand', async ({ page }, info) => {
        await play(page, LINK_ROOM, 'Plain Clip')
        await page.evaluate(() => {
            Object.defineProperty(navigator, 'clipboard', { value: { writeText: () => Promise.reject(new DOMException('denied', 'NotAllowedError')) }, configurable: true })
            document.execCommand = () => false
        })
        await openMore(page)
        await page.getByRole('menuitem', { name: 'Copy link at this time' }).click()
        const sheet = page.getByRole('dialog', { name: 'Copy link at this time' })
        await expect(sheet).toBeVisible()
        await expect(sheet.locator('input')).toHaveValue(/^https:\/\/t\.me\/linkroom\/\d+\?t=2$/)
        await expect(sheet.locator('input')).toBeFocused()
        await shot(page, info, 'player-by-hand')
        await page.keyboard.press('Escape')
    })

    test('a chat that became protected after the card was stored: the entry goes once Telegram has been asked', async ({ page, context }) => {
        await fakeCase(context, 'links', 'linksprot')
        await play(page, LINK_ROOM, 'Plain Clip')
        await openMore(page)
        await expect(page.getByRole('menuitem', { name: 'Playback details' })).toBeVisible()
        await expect(page.getByRole('menuitem', { name: 'Copy link at this time' })).toHaveCount(0)
    })

    test('no entry for a chat that restricts saving', async ({ page }) => {
        await play(page, SEALED_ROOM, 'Sealed Clip')
        await openMore(page)
        await expect(page.getByRole('menuitem', { name: 'Playback details' })).toBeVisible()
        await expect(page.getByRole('menuitem', { name: 'Copy link at this time' })).toHaveCount(0)
    })
})

// The TV has no card menu (cards open their page; VideoList turns the menu off on that layout), so the TV's way to a link is the player's More options.
test.describe('on the TV layout, with the remote', () => {
    test.use({ viewport: { width: 1280, height: 720 } })

    test('the arrows reach Copy link at this time in More options, OK copies, the note says so', async ({ page }, info) => {
        test.skip(info.project.name !== 'chromium', 'the TV walk runs in the desktop Chromium')
        await page.addInitScript(() => localStorage.setItem('tmplayer-layout', 'tv'))
        await noSizeFloor(page)
        await page.goto(LINK_ROOM)
        await page.locator('.card').filter({ hasText: 'Plain Clip' }).click()
        await page.locator('.detail-play').click()
        await expect(page).toHaveURL(/\/watch\//)
        await playsPast(page, 0.3)
        await page.locator('video').first().evaluate((v: HTMLVideoElement) => (v.pause(), (v.currentTime = 2)))
        // The remote: OK on the disc, Down to the bar, Down to the buttons, Right to More, OK.
        const key = async (k: string) => (await page.keyboard.press(k), page.waitForTimeout(150))
        await key('Enter')
        await key('ArrowDown')
        await key('ArrowDown')
        const onMore = () => page.evaluate(() => (document.activeElement as HTMLElement | null)?.getAttribute('data-shot') === 'more')
        for (let i = 0; i < 10 && !(await onMore()); i++) await key('ArrowRight')
        expect(await onMore()).toBe(true)
        await key('Enter')
        const focusedWords = () => page.evaluate(() => (document.activeElement as HTMLElement | null)?.textContent?.trim() ?? '')
        let at = ''
        for (let i = 0; i < 30 && !at.startsWith('Copy link at this time'); i++) {
            await page.keyboard.press('ArrowDown')
            await page.waitForTimeout(120)
            at = await focusedWords()
        }
        expect(at).toMatch(/^Copy link at this time/)
        await shot(page, info, 'tv-menu')
        await page.keyboard.press('Enter')
        await expect.poll(async () => (await copied(page))[0] ?? '').toMatch(/^https:\/\/t\.me\/linkroom\/\d+\?t=[1-4]$/)
    })
})
