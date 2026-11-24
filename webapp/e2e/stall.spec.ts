// WB17: why a video is not starting (U1) and "Can't reach Telegram" (U2), over the fake Telegram's live
// cases (fake/mtcute.ts: `stall`, `flood:N`, `linkdown`), on a phone, a desktop and the
// TV skin. The page's clock is the test's (page.clock), so the 10 s and the 20 s pass at once; the
// worker's own clock is real, which is why the cases are flags it reads again each time.
import { mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { expect, test, type Page } from '@playwright/test'
import { fakeCase, openCard, openClips, playsPast } from './helpers'

const SHOTS = fileURLToPath(new URL('../../design/web-gaps/WB17/', import.meta.url))

const FORMS = [
    { name: 'phone-390', width: 390, height: 844, layout: null, touch: true },
    { name: 'desktop-1280', width: 1280, height: 800, layout: 'desktop', touch: false },
    { name: 'tv-1920', width: 1920, height: 1080, layout: 'tv', touch: false },
] as const

/** The layout and the size floor written where the app keeps them before it starts. */
async function prepare(page: Page, layout: string | null) {
    await page.goto('/__e2e/sizes')
    await page.evaluate(
        (layout) =>
            new Promise<void>((resolve, reject) => {
                if (layout) localStorage.setItem('tmplayer-layout', layout)
                const open = indexedDB.open('tmplayer-app')
                open.onupgradeneeded = () => open.result.createObjectStore('kv')
                open.onerror = () => reject(open.error)
                open.onsuccess = () => {
                    const tx = open.result.transaction('kv', 'readwrite')
                    tx.objectStore('kv').put({ sizeMin: 0 }, 'settings')
                    tx.oncomplete = () => (open.result.close(), resolve())
                }
            }),
        layout,
    )
}

/** Moves the page's clock on a second at a time until the stall sheet is up: the player starts reading at its own pace, so the 10 s are counted from there. */
async function untilSheet(page: Page, upTo = 80_000) {
    for (let spent = 0; spent < upTo && (await page.locator('.pc-status').count()) === 0; spent += 1000) {
        await page.clock.runFor(1000)
        await page.waitForTimeout(50)
    }
}

const banner = (page: Page) => page.locator('.reach-banner')
const shot = async (page: Page, name: string) => {
    mkdirSync(SHOTS, { recursive: true })
    await page.screenshot({ path: `${SHOTS}${name}.png` })
}

for (const form of FORMS) {
    test.describe(`on ${form.name}`, () => {
        test.skip(({ browserName }) => browserName !== 'chromium', 'the live cases run in Chromium')
        test.use({ viewport: { width: form.width, height: form.height }, hasTouch: form.touch, isMobile: form.touch })

        test('a video Telegram does not send says so after 10 s, with Try again and Go back, and plays once bytes flow', async ({ page, context }) => {
            await fakeCase(context, 'stall')
            await page.clock.install()
            await prepare(page, form.layout)
            await openClips(page)
            await openCard(page, 'Native Clip')
            // Nothing is said at once: the loader is up, and it does not blame Telegram yet.
            await expect(page.locator('.pc-loader')).toBeVisible()
            await page.clock.runFor(5_000)
            await expect(page.locator('.pc-status')).toHaveCount(0)
            // A read of a chunk is out: a chunk ends at once or not at all, so 10 s is not yet enough to say so (about 31 s here, at 25 KB/s).
            await page.clock.runFor(15_000)
            await expect(page.locator('.pc-status')).toHaveCount(0)
            await untilSheet(page)
            const sheet = page.locator('.pc-status')
            await expect(sheet.getByRole('heading', { name: "This video hasn't started" })).toBeVisible()
            await expect(sheet.getByText("Telegram hasn't started sending this video yet.")).toBeVisible()
            await expect(sheet.getByRole('button', { name: 'Try again' })).toBeVisible()
            await expect(sheet.getByRole('button', { name: 'Go back' })).toBeVisible()
            await shot(page, `${form.name}-not-started`)
            // The bytes come: Try again opens it again and it plays. Nothing restarted by itself before.
            await fakeCase(context)
            // The read the worker still holds may be answered the moment the case changes, and the sheet then goes by itself
            // before the press lands: the press is tried while the sheet stands, and the video must play either way.
            await sheet.getByRole('button', { name: 'Try again' }).click({ timeout: 4_000 }).catch(() => {})
            await playsPast(page, 0.5)
            await expect(page.locator('.pc-status')).toHaveCount(0)
        })

        test('a chunk that comes late (a slow line) is not called a stall, and the video plays when it is in', async ({ page, context }) => {
            await fakeCase(context, 'late')
            await page.clock.install()
            await prepare(page, form.layout)
            await openClips(page)
            await openCard(page, 'Native Clip')
            // Past the 10 s that would have shown the sheet, while the read is still out (it answers after 6 s of real time).
            await page.clock.runFor(14_000)
            await expect(page.locator('.pc-status')).toHaveCount(0)
            await playsPast(page, 0.5)
        })

        test('Telegram asking to wait is said with how long, and it carries on by itself', async ({ page, context }) => {
            await fakeCase(context, 'flood:40')
            await page.clock.install()
            await prepare(page, form.layout)
            await openClips(page)
            await openCard(page, 'Native Clip')
            await untilSheet(page)
            const sheet = page.locator('.pc-status')
            await expect(sheet.getByText(/Too many requests went out from this device in a short time, so the app is pausing for \d+ seconds\. It carries on by itself\./)).toBeVisible()
            await expect(sheet.getByRole('button', { name: 'Try again' })).toBeVisible()
            await expect(sheet.getByRole('button', { name: 'Go back' })).toBeVisible()
            await shot(page, `${form.name}-flood-wait`)
        })

        test('a banner after 20 s of a held line, Retry answers, a blink does not clear it', async ({ page, context }) => {
            await page.clock.install()
            await prepare(page, form.layout)
            await page.goto('/chats')
            await expect(page.getByRole('link', { name: /Test Clips/ }).first()).toBeVisible()
            await fakeCase(context, 'linkdown')
            // The fake's line follows the flag within half a second (real time); the page's 20 s are the test's.
            await page.waitForTimeout(1200)
            await page.clock.runFor(15_000)
            await expect(banner(page)).toHaveCount(0)
            await page.clock.runFor(5_000)
            await expect(banner(page)).toBeVisible()
            await expect(banner(page)).toContainText("Can't reach Telegram right now")
            await expect(banner(page)).toContainText("Telegram isn't answering yet. TMPlayer keeps trying by itself.")
            await shot(page, `${form.name}-banner`)
            // The line comes back for a blink: the banner stays, and when it drops again there is no second 20 s.
            await fakeCase(context)
            await page.waitForTimeout(1200)
            await page.clock.runFor(1_000)
            await fakeCase(context, 'linkdown')
            await page.waitForTimeout(1200)
            await page.clock.runFor(10_000)
            await expect(banner(page)).toBeVisible()
            // Retry answers at once (a line says so) and the banner is gone; the line is still down, so it is back after 20 s.
            await banner(page).getByRole('button', { name: 'Retry' }).click()
            await expect(banner(page)).toHaveCount(0)
            await expect(page.locator('.toast')).toContainText('Trying again')
            await page.clock.runFor(20_500)
            await expect(banner(page)).toBeVisible()
            // Held for good: it goes 3 s after the line is up, not at once.
            await fakeCase(context)
            await page.waitForTimeout(1200)
            await expect(banner(page)).toBeVisible()
            await page.clock.runFor(3_500)
            await expect(banner(page)).toHaveCount(0)
        })
    })
}

test.describe('the banner and the offline line, apart', () => {
    test.skip(({ browserName }) => browserName !== 'chromium', 'the live cases run in Chromium')

    test('a device that is offline has the offline line and never this banner', async ({ page, context }) => {
        await page.clock.install()
        await prepare(page, 'desktop')
        await page.goto('/chats')
        await expect(page.getByRole('link', { name: /Test Clips/ }).first()).toBeVisible()
        await fakeCase(context, 'linkdown')
        await context.setOffline(true)
        await page.waitForTimeout(1200)
        await page.clock.runFor(40_000)
        await expect(page.locator('.offline-line')).toBeVisible()
        await expect(banner(page)).toHaveCount(0)
        await context.setOffline(false)
    })

    test('a page in the background does not count, and counts afresh on its return', async ({ page, context }) => {
        await page.clock.install()
        await prepare(page, 'desktop')
        await page.goto('/chats')
        await expect(page.getByRole('link', { name: /Test Clips/ }).first()).toBeVisible()
        const hide = (hidden: boolean) =>
            page.evaluate((hidden) => {
                Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden })
                document.dispatchEvent(new Event('visibilitychange'))
            }, hidden)
        await hide(true)
        await fakeCase(context, 'linkdown')
        await page.waitForTimeout(1200)
        await page.clock.runFor(60_000)
        await expect(banner(page)).toHaveCount(0)
        await hide(false)
        await page.clock.runFor(15_000)
        await expect(banner(page)).toHaveCount(0)
        await page.clock.runFor(5_500)
        await expect(banner(page)).toBeVisible()
    })

    test('with the TV remote, Up from the bar lands on Retry, Back gives the remote back, OK on Retry answers', async ({ page, context }) => {
        await page.setViewportSize({ width: 1920, height: 1080 })
        await page.clock.install()
        await prepare(page, 'tv')
        await page.goto('/chats')
        await expect(page.getByRole('link', { name: /Test Clips/ }).first()).toBeVisible()
        const press = async (key: string) => {
            await page.keyboard.press(key)
            await page.waitForTimeout(150)
        }
        const inBanner = () => page.evaluate(() => !!document.activeElement?.closest('.reach-banner'))
        // The bar's item the remote is on, as the HX22 walk gets there.
        await page.evaluate(() => (document.querySelector('.tv-tab[href="/chats"]') as HTMLElement | null)?.focus())
        const onBar = () => page.evaluate(() => document.activeElement?.getAttribute('href') ?? '')
        expect(await onBar()).toBe('/chats')
        await fakeCase(context, 'linkdown')
        await page.waitForTimeout(1200)
        await page.clock.runFor(21_000)
        await expect(banner(page)).toBeVisible()
        // It came up without taking the remote.
        expect(await onBar()).toBe('/chats')
        await press('ArrowUp')
        expect(await inBanner()).toBe(true)
        expect(await page.evaluate(() => document.activeElement?.textContent)).toBe('Retry')
        await shot(page, 'tv-1920-banner-retry-focused')
        await press('Backspace')
        expect(await onBar()).toBe('/chats')
        expect(await inBanner()).toBe(false)
        await press('ArrowUp')
        expect(await inBanner()).toBe(true)
        await press('Enter')
        await expect(banner(page)).toHaveCount(0)
        await expect(page.locator('.toast')).toContainText('Trying again')
    })
})
