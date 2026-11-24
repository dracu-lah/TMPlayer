// WB20: Settings' search box over the apps' map, Home and library as one page, a way to a row by its
// address, and What's new. Phone and desktop have the box, the TV keeps its one long page; a search
// that matches nothing says so, a row the form lacks is not found, the keyboard lands on the row a
// result names, German and Arabic fit, and a screenshot each into design/web-gaps/WB20.
import { readFileSync } from 'node:fs'
import { mkdirSync } from 'node:fs'
import { expect, test, type Page, type TestInfo } from '@playwright/test'
import { openTvSettingsPage } from './helpers'

type Catalog = Record<string, Record<string, string>>
const catalog = (tag: string): Catalog => JSON.parse(readFileSync(new URL(`../../core/src/commonMain/resources/i18n/${tag}.json`, import.meta.url), 'utf8'))
const de = catalog('de')
const ar = catalog('ar')
const SHOTS = new URL('../../design/web-gaps/WB20/', import.meta.url).pathname

const IN = {
    // The screen of a phone, not the default tablet-sized one: a tablet is offered the Layout choice (WP-62).
    phone: { viewport: { width: 393, height: 852 }, screen: { width: 393, height: 852 }, hasTouch: true, isMobile: true },
    desktop: { viewport: { width: 1280, height: 800 }, hasTouch: false, isMobile: false },
    tv: { viewport: { width: 1920, height: 1080 }, hasTouch: false, isMobile: false },
}

async function shot(page: Page, info: TestInfo, name: string) {
    mkdirSync(SHOTS, { recursive: true })
    await page.screenshot({ path: `${SHOTS}/${name}.png` })
}

const box = (page: Page) => page.getByRole('searchbox', { name: 'Search settings' })
const results = (page: Page) => page.locator('.set-results .set-row')
const focusedId = (page: Page) => page.evaluate(() => document.activeElement?.id ?? '')
const sideways = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)

async function setting(page: Page, key: string, value: string) {
    await page.goto('/__e2e/sizes')
    await page.evaluate(([k, v]) => localStorage.setItem(k, v), [key, value])
}

test.describe('on a phone', () => {
    test.use(IN.phone)

    test('a search lists the rows with their page, opens the page and puts the keyboard on the row', async ({ page }, info) => {
        await page.goto('/settings')
        await box(page).fill('poster')
        const posters = results(page).filter({ hasText: 'Posters' })
        await expect(posters).toHaveCount(1)
        await expect(posters).toContainText('Home and library')
        // Results stand in place of the pages.
        await expect(page.getByRole('link', { name: /^Appearance/ })).toBeHidden()
        await shot(page, info, 'phone-search-results')
        await posters.click()
        await expect(page).toHaveURL(/\/settings\/home#posters$/)
        await expect.poll(() => focusedId(page)).toBe('row-posters')
        await expect(page.locator('#row-posters')).toHaveClass(/set-found/)
        // Back is the list, and the search is still there.
        await page.getByRole('button', { name: 'Back' }).click()
        await expect(box(page)).toHaveValue('poster')
        await expect(results(page).filter({ hasText: 'Posters' })).toHaveCount(1)
    })

    test('a search that matches nothing says so and keeps the pages', async ({ page }, info) => {
        await page.goto('/settings')
        await box(page).fill('zzzzqqq')
        await expect(page.getByRole('status').filter({ hasText: 'Nothing in Settings matches zzzzqqq' })).toBeVisible()
        await expect(page.getByRole('link', { name: /^Appearance/ })).toBeVisible()
        await expect(results(page)).toHaveCount(0)
        await shot(page, info, 'phone-search-none')
        // Blanks are not a search.
        await box(page).fill('    ')
        await expect(page.getByRole('status').filter({ hasText: 'Nothing in Settings' })).toHaveCount(0)
        // A hostile line does not break it.
        await box(page).fill('<img src=x onerror=alert(1)> ' + '😀'.repeat(300))
        await expect(page.locator('img[src="x"]')).toHaveCount(0)
        await expect(page.getByRole('link', { name: /^Appearance/ })).toBeVisible()
    })

    test('a row a phone does not have is not found: Layout', async ({ page }) => {
        await page.goto('/settings')
        await box(page).fill('layout')
        // The search ends in the no match line (nothing else on a phone answers "layout"), never in a Layout row.
        await expect(page.getByRole('status').filter({ hasText: 'Nothing in Settings matches layout' })).toBeVisible()
        await expect(results(page)).toHaveCount(0)
    })

    test('an old page address naming a row on another page settles on that page and row, once', async ({ page }) => {
        const moves: string[] = []
        page.on('framenavigated', (f) => f === page.mainFrame() && moves.push(f.url()))
        await page.goto('/settings/library#resume_sync')
        await expect(page).toHaveURL(/\/settings\/account#resume_sync$/)
        await expect.poll(() => focusedId(page)).toBe('row-resume_sync')
        // No loop: it stays put, and the address changed by history only a few times (the redirect, then the page).
        await page.waitForTimeout(800)
        await expect(page).toHaveURL(/\/settings\/account#resume_sync$/)
        expect(moves.length).toBeLessThan(6)
        expect(await page.evaluate(() => history.length)).toBeLessThan(5)
    })

    test('the words people type find a row: log out finds Sign out, and the list stays on screen to press it', async ({ page }) => {
        await page.goto('/settings')
        await box(page).fill('log out')
        await expect(results(page).first()).toContainText('Sign out')
        await results(page).first().click()
        await expect.poll(() => focusedId(page)).toBe('row-sign_out')
        await expect(page).toHaveURL(/\/settings$/)
    })

    test('old page addresses land on Home and library, and an address can name a row on any page', async ({ page }) => {
        await page.goto('/settings/library')
        await expect(page).toHaveURL(/\/settings\/home$/)
        await expect(page.getByRole('switch', { name: /^Posters/ })).toBeVisible()
        await page.goto('/settings/library#trailers_in_app')
        await expect(page).toHaveURL(/\/settings\/home#trailers_in_app$/)
        await expect.poll(() => focusedId(page)).toBe('row-trailers_in_app')
        // A row on another page than the address says: its page.
        await page.goto('/settings/appearance#resume_sync')
        await expect(page).toHaveURL(/\/settings\/account#resume_sync$/)
        await expect.poll(() => focusedId(page)).toBe('row-resume_sync')
        // An anchor that names nothing, or a hostile one, is ignored.
        await page.goto('/settings/storage#nope%ZZ')
        await expect(page.getByRole('heading', { name: 'Downloads and data', exact: true }).first()).toBeVisible()
    })

    test('Home and library are one page: the Home rows, the order and the library', async ({ page }, info) => {
        await page.goto('/settings')
        await expect(page.getByRole('link', { name: /^Home and library/ })).toBeVisible()
        await expect(page.getByRole('link', { name: /^Library/ })).toHaveCount(0)
        await page.getByRole('link', { name: /^Home and library/ }).click()
        await expect(page.getByRole('switch', { name: /^Carousel on Home/ })).toBeVisible()
        await expect(page.getByRole('switch', { name: /^Posters/ })).toBeVisible()
        await expect(page.getByRole('button', { name: /^Clear history/ })).toBeVisible()
        await shot(page, info, 'phone-home-and-library')
    })

    test('What is new opens the apps sheet for this build, in a dialog with a way to the changelog', async ({ page }, info) => {
        // What is new lives under Help since WP-78 (About keeps a Moved to line).
        await page.goto('/settings/help')
        const row = page.getByRole('button', { name: /^What's new/ })
        await expect(row).toContainText(/The highlights of version \d+\.\d+\.\d+/)
        await row.click()
        const sheet = page.getByRole('dialog', { name: /^What's new in \d+\.\d+\.\d+/ })
        await expect(sheet).toBeVisible()
        await expect.poll(() => sheet.locator('li').count()).toBeGreaterThanOrEqual(5)
        await expect(sheet.getByRole('link', { name: 'Full changelog' })).toHaveAttribute('href', /^https:\/\/tmplayer\.org\/changelog/)
        await shot(page, info, 'phone-whats-new')
        await page.keyboard.press('Escape')
        await expect(sheet).toBeHidden()
        await expect(row).toBeFocused()
        await expect(page.getByRole('link', { name: /^Changelog/ })).toHaveAttribute('href', /tmplayer\.org\/changelog/)
    })

    test('German words are found by German words, long ones wrap, and nothing is pushed sideways', async ({ page }, info) => {
        await setting(page, 'tmplayer-language', 'de')
        await page.goto('/settings')
        await expect(page.getByRole('searchbox', { name: de.settings.search })).toBeVisible()
        await page.getByRole('searchbox', { name: de.settings.search }).fill(de.settings.theme)
        await expect(results(page).first()).toContainText(de.settings.theme)
        expect(await sideways(page)).toBeLessThanOrEqual(0)
        await shot(page, info, 'phone-search-de')
    })

    test('Arabic reads right to left, and a huge font still fits', async ({ page }, info) => {
        await setting(page, 'tmplayer-language', 'ar')
        await page.goto('/settings')
        await page.getByRole('searchbox', { name: ar.settings.search }).fill(ar.settings.theme)
        await expect(results(page).first()).toBeVisible()
        await expect(page.locator('html')).toHaveAttribute('dir', 'rtl')
        expect(await sideways(page)).toBeLessThanOrEqual(0)
        await shot(page, info, 'phone-search-ar')
        await page.evaluate(() => (document.documentElement.style.fontSize = '32px'))
        expect(await sideways(page)).toBeLessThanOrEqual(0)
    })
})

test.describe('on a desktop', () => {
    test.use(IN.desktop)

    test('the box sits over the list, Down walks the results, Enter opens the first, Escape clears', async ({ page }, info) => {
        await page.goto('/settings')
        await expect(page).toHaveURL(/\/settings\/account$/)
        await box(page).fill('home')
        await expect(results(page).first()).toBeVisible()
        await shot(page, info, 'desktop-search-results')
        await box(page).press('ArrowDown')
        await expect(results(page).first()).toBeFocused()
        await page.keyboard.press('ArrowUp')
        await expect(box(page)).toBeFocused()
        await box(page).press('Escape')
        await expect(box(page)).toHaveValue('')
        await expect(page.getByRole('link', { name: /^Appearance/ })).toBeVisible()
        // Enter opens the best answer and the keyboard goes to its row.
        await box(page).fill('posters')
        await expect(results(page)).not.toHaveCount(0)
        await box(page).press('Enter')
        await expect(page).toHaveURL(/\/settings\/home#posters$/)
        await expect.poll(() => focusedId(page)).toBe('row-posters')
        await expect(page.getByRole('switch', { name: /^Posters/ })).toBeFocused()
        await shot(page, info, 'desktop-landed')
    })

    test('/ puts the keyboard in the box, as on the other pages', async ({ page }) => {
        await page.goto('/settings')
        await expect(box(page)).toBeVisible()
        // The window's keys are installed a moment after the page is drawn: asked again until they answer.
        await expect(async () => {
            await page.locator('body').click({ position: { x: 5, y: 5 } })
            await page.keyboard.press('/')
            await expect(box(page)).toBeFocused({ timeout: 1000 })
        }).toPass()
    })

    test('Layout is a desktop row and is found, and the clear button empties the box and returns to it', async ({ page }) => {
        await page.goto('/settings')
        await box(page).fill('layout')
        await expect(results(page).filter({ hasText: 'Layout' }).first()).toBeVisible()
        await page.getByRole('button', { name: 'Clear the search' }).click()
        await expect(box(page)).toBeFocused()
        await expect(box(page)).toHaveValue('')
    })

    test('the storage rows only this web has are found, and land on their row', async ({ page }) => {
        await page.goto('/settings')
        await box(page).fill('cache')
        await expect(results(page).filter({ hasText: 'Clear cache' })).toHaveCount(1)
        await results(page).filter({ hasText: 'Clear cache' }).click()
        await expect(page).toHaveURL(/\/settings\/storage#clear_cache$/)
        await expect.poll(() => focusedId(page)).toBe('row-clear_cache')
    })
})

test.describe('on a TV', () => {
    test.use(IN.tv)

    test('there is no box, a rail of pages, a way to a row by its address, and What is new', async ({ page }, info) => {
        await setting(page, 'tmplayer-layout', 'tv')
        await page.goto('/settings#posters')
        await expect(page.locator('html')).toHaveAttribute('data-form', 'tv')
        await expect(page.getByRole('searchbox')).toHaveCount(0)
        await expect.poll(() => page.evaluate(() => (document.activeElement as HTMLInputElement | null)?.name)).toBe('posters')
        await expect(page.locator('#row-posters')).toHaveClass(/set-found/)
        await page.goto('/settings/library')
        await expect(page).toHaveURL(/\/settings$/)
        // What's new is on the rail's Help page (WP-88).
        await openTvSettingsPage(page, 'help')
        const news = page.getByRole('button', { name: "What's new" })
        await news.scrollIntoViewIfNeeded()
        await news.focus()
        await page.keyboard.press('Enter')
        await expect(page.getByRole('dialog', { name: /^What's new in/ })).toBeVisible()
        await shot(page, info, 'tv-whats-new')
        await page.keyboard.press('Escape')
        await expect(news).toBeFocused()
    })
})
