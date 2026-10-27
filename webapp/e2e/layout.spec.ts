// Plan 8.3: the layout (data-form) from faked user agents and pointers, the Settings choice (Desktop or TV, none on a phone), and the live switch.
import { expect, test, type Browser } from '@playwright/test'
import { fakeCase } from './helpers'

const IPHONE = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1'
const DESKTOP = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36'
const ANDROID_PHONE = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36'

async function formOf(browser: Browser, opts: { ua: string; width: number; height: number; touch?: boolean; path?: string }) {
    const context = await browser.newContext({
        userAgent: opts.ua,
        viewport: { width: opts.width, height: opts.height },
        hasTouch: opts.touch,
        isMobile: opts.touch,
    })
    await fakeCase(context, 'signedout')
    const page = await context.newPage()
    await page.goto(opts.path ?? '/')
    const form = await page.evaluate(() => document.documentElement.dataset.form)
    await context.close()
    return form
}

test.describe('detection table', () => {
    test.skip(({ browserName }) => browserName !== 'chromium', 'pointer and user agent overrides are Chromium only')
    const tvs: [string, string][] = [
        ['webOS', 'Mozilla/5.0 (Web0S; Linux/SmartTV) AppleWebKit/537.36 Chrome/79.0 Safari/537.36 WebAppManager'],
        ['Tizen', 'Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/537.36 Chrome/76.0 TV Safari/537.36'],
        ['Fire TV', 'Mozilla/5.0 (Linux; Android 9; AFTMM Build/PS7233) AppleWebKit/537.36 Chrome/90.0 Mobile Safari/537.36'],
        ['Chromecast', 'Mozilla/5.0 (X11; Linux armv7l) AppleWebKit/537.36 Chrome/114.0 Safari/537.36 CrKey/1.56'],
        ['HbbTV', 'Mozilla/5.0 (Linux; HbbTV/1.5.1) AppleWebKit/537.36 Chrome/79.0 Safari/537.36'],
        ['Android TV', 'Mozilla/5.0 (Linux; Android 11; BRAVIA 4K TV) AppleWebKit/537.36 Chrome/110.0 Safari/537.36'],
        // No "TV" in it, a fine pointer and no touch: the real one, from a Mi TV's WebView (2026-10-27).
        ['Mi TV', 'Mozilla/5.0 (Linux; Android 9; MiTV-AESP0 Build/PI; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/138.0.7204.179 Mobile Safari/537.36'],
    ]
    for (const [name, ua] of tvs)
        test(`${name} is tv`, async ({ browser }) => {
            expect(await formOf(browser, { ua, width: 1920, height: 1080 })).toBe('tv')
        })
    test('a phone (coarse, no hover, short side under 840) is phone, in both turns', async ({ browser }) => {
        expect(await formOf(browser, { ua: IPHONE, width: 390, height: 844, touch: true })).toBe('phone')
        expect(await formOf(browser, { ua: IPHONE, width: 844, height: 390, touch: true })).toBe('phone')
    })
    test('an Android phone, with touch, is phone and not tv', async ({ browser }) => {
        expect(await formOf(browser, { ua: ANDROID_PHONE, width: 412, height: 915, touch: true })).toBe('phone')
    })
    test('a touch tablet (short side 840 or more) is desktop', async ({ browser }) => {
        expect(await formOf(browser, { ua: IPHONE, width: 1024, height: 1366, touch: true })).toBe('desktop')
    })
    test('a desktop, even in a narrow window, is desktop', async ({ browser }) => {
        expect(await formOf(browser, { ua: DESKTOP, width: 1280, height: 800 })).toBe('desktop')
        expect(await formOf(browser, { ua: DESKTOP, width: 400, height: 700 })).toBe('desktop')
    })
})

test('the Settings choice wins, survives a reload and is set before the page runs', async ({ page, context }) => {
    await page.goto('/settings')
    const html = page.locator('html')
    await expect(page.getByRole('radio', { name: 'Desktop' })).toBeChecked()
    await expect(page.getByRole('radio', { name: 'Phone' })).toHaveCount(0)
    await expect(page.getByRole('radio', { name: 'Auto' })).toHaveCount(0)
    // Each form draws Settings in its own way (the TV's one long page, a list of pages elsewhere), so the choice is pressed, not checked: the radio is a new one by the time the form has changed.
    // A press can land on the old radio while the page redraws for the last choice, so a press is tried again until the form follows.
    const choose = (name: string) =>
        expect(async () => {
            await page.getByRole('radio', { name }).click({ force: true })
            await expect(html).toHaveAttribute('data-form', name.toLowerCase(), { timeout: 2000 })
        }).toPass({ timeout: 15_000 })
    for (const name of ['TV', 'Desktop']) await choose(name)
    await choose('TV')
    await page.reload()
    await expect(page.getByRole('radio', { name: 'TV' })).toBeChecked()
    await expect(html).toHaveAttribute('data-form', 'tv')
    // The inline script has set it before any module ran.
    const seen = await context.newPage().then(async (p) => {
        await p.addInitScript(() => document.addEventListener('DOMContentLoaded', () => ((window as any).early = document.documentElement.dataset.form)))
        await p.goto('/settings')
        return p.evaluate(() => (window as any).early)
    })
    expect(seen).toBe('tv')
    await choose('Desktop')
})

test('?tv=1 and ?tv=0 in the address choose TV and Desktop, and the choice stays for a plain address', async ({ page }) => {
    const html = page.locator('html')
    await page.goto('/?tv=1')
    await expect(html).toHaveAttribute('data-form', 'tv')
    await page.goto('/settings')
    await expect(html).toHaveAttribute('data-form', 'tv')
    await expect(page.getByRole('radio', { name: 'TV' })).toBeChecked()
    await page.goto('/?tv=0')
    await expect(html).toHaveAttribute('data-form', 'desktop')
    await page.goto('/settings')
    await expect(page.getByRole('radio', { name: 'Desktop' })).toBeChecked()
})

test('a phone always has the phone layout: no choice in Settings, and a choice saved before is passed over', async ({ browser, browserName }) => {
    test.skip(browserName !== 'chromium', 'pointer overrides are Chromium only')
    const context = await browser.newContext({ userAgent: IPHONE, viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true })
    await context.addInitScript(() => localStorage.setItem('tmplayer-layout', 'tv'))
    const page = await context.newPage()
    await page.goto('/settings/appearance')
    await expect(page.locator('html')).toHaveAttribute('data-form', 'phone')
    await expect(page.getByRole('radio', { name: 'Dark' })).toBeVisible()
    await expect(page.getByRole('radio', { name: 'TV' })).toHaveCount(0)
    await context.close()
})

test('a desktop follows a resize live; the route stays', async ({ page, browserName }) => {
    test.skip(browserName !== 'chromium', 'desktop viewport resize')
    await page.goto('/settings')
    await expect(page.locator('html')).toHaveAttribute('data-form', 'desktop')
    // A fine pointer stays desktop however narrow the window gets.
    await page.setViewportSize({ width: 360, height: 700 })
    await expect(page.locator('html')).toHaveAttribute('data-form', 'desktop')
    await expect(page).toHaveURL(/\/settings/)
})

test('a touch device changes the form live with the window, keeping the route', async ({ browser, browserName }) => {
    test.skip(browserName !== 'chromium', 'pointer overrides are Chromium only')
    const context = await browser.newContext({ userAgent: IPHONE, viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true })
    const page = await context.newPage()
    await page.goto('/settings')
    const html = page.locator('html')
    await expect(html).toHaveAttribute('data-form', 'phone')
    await page.setViewportSize({ width: 1000, height: 1000 })
    await expect(html).toHaveAttribute('data-form', 'desktop')
    await page.setViewportSize({ width: 800, height: 600 })
    await expect(html).toHaveAttribute('data-form', 'phone')
    await expect(page).toHaveURL(/\/settings/)
    await context.close()
})
