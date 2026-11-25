// WB12: the web in the apps' 22 languages. The browser's language on a first visit, a stored choice
// winning, Arabic right to left before the first paint, a catalog that does not come in (English
// and Try again), a line a catalog lacks staying English, a switch in another tab while a video
// plays, the TV remote on the list, and the screens in German and Arabic with nothing pushed
// sideways, a screenshot each for design/web-gaps/WB12.
import { readFileSync } from 'node:fs'
import { expect, test, type Page, type TestInfo } from '@playwright/test'
import { noSizeFloor, openCard, openClips, openTvSettingsPage, playsPast } from './helpers'

type Catalog = Record<string, Record<string, string>>
const catalog = (tag: string): Catalog => JSON.parse(readFileSync(new URL(`../../core/src/commonMain/resources/i18n/${tag}.json`, import.meta.url), 'utf8'))
const en = catalog('en')
const de = catalog('de')
const ar = catalog('ar')
const ja = catalog('ja')
const SHOTS = new URL('../../design/web-gaps/WB12/', import.meta.url).pathname

const chromiumOnly = () => test.skip(({ browserName }) => browserName !== 'chromium', 'one engine is enough for the language sweep')

/** Settings > Appearance on a phone or desktop, Settings on the TV: where the Language list is. */
async function openLanguages(page: Page) {
    await page.goto('/settings/appearance')
    await expect(page.locator('.lang-list')).toBeVisible()
}

/** The language picked, kept where i18n.ts keeps it, before the next load. */
async function stored(page: Page, tag: string) {
    await page.goto('/__e2e/sizes')
    await page.evaluate((tag) => localStorage.setItem('tmplayer-language', tag), tag)
}

const htmlLang = (page: Page) => page.evaluate(() => ({ lang: document.documentElement.lang, dir: document.documentElement.dir }))

test.describe('the browser’s language on a first visit', () => {
    chromiumOnly()
    test.use({ locale: 'de-DE' })
    test('German from the browser, and a choice stored in Settings wins over it after a reload', async ({ page }) => {
        await openLanguages(page)
        await expect(page.locator('html')).toHaveAttribute('lang', 'de')
        await expect(page.getByRole('heading', { name: de.settings.language, exact: true })).toBeVisible()
        await expect(page.getByRole('button', { name: new RegExp(`^${de.settings.language_automatic}`) })).toHaveAttribute('aria-pressed', 'true')
        await page.getByRole('button', { name: '日本語' }).click()
        await expect(page.getByRole('heading', { name: ja.settings.language, exact: true })).toBeVisible()
        await expect(page.getByRole('button', { name: '日本語' })).toHaveAttribute('aria-pressed', 'true')
        expect(await htmlLang(page)).toEqual({ lang: 'ja', dir: 'ltr' })
        await page.reload()
        await expect(page.getByRole('heading', { name: ja.settings.language, exact: true })).toBeVisible()
        // Automatic again follows the browser.
        await page.getByRole('button', { name: new RegExp(`^${ja.settings.language_automatic}`) }).click()
        await expect(page.getByRole('heading', { name: de.settings.language, exact: true })).toBeVisible()
        expect(await page.evaluate(() => localStorage.getItem('tmplayer-language'))).toBeNull()
    })
})

test.describe('one engine', () => {
    chromiumOnly()

    test('Arabic is right to left, and the next load is before its first paint', async ({ page }) => {
        await openLanguages(page)
        await page.getByRole('button', { name: 'العربية' }).click()
        await expect(page.getByRole('heading', { name: ar.settings.language, exact: true })).toBeVisible()
        expect(await htmlLang(page)).toEqual({ lang: 'ar', dir: 'rtl' })
        // What <html> says when <body> first appears: index.html's own script has run, the app's code has not.
        await page.addInitScript(() => {
            new MutationObserver((_, o) => {
                if (!document.body) return
                ;(window as unknown as { early: string }).early = `${document.documentElement.lang} ${document.documentElement.dir}`
                o.disconnect()
            }).observe(document, { childList: true, subtree: true })
        })
        await page.reload()
        await expect(page.getByRole('heading', { name: ar.settings.language, exact: true })).toBeVisible()
        expect(await page.evaluate(() => (window as unknown as { early: string }).early)).toBe('ar rtl')
        // Back to English: left to right at once.
        await page.getByRole('button', { name: 'English', exact: true }).click()
        await expect(page.getByRole('heading', { name: en.settings.language, exact: true })).toBeVisible()
        expect(await htmlLang(page)).toEqual({ lang: 'en', dir: 'ltr' })
    })

    test('a catalog that does not come in keeps the page as it was, with Try again; a line the catalog lacks stays English', async ({ page }) => {
        // The German catalog the page gets lacks the failure line (the fixture strips it, so a shipped catalog
        // being complete does not matter): the line shows in English inside a German page.
        await page.route('**/__strings/de.json', async (route) => {
            const response = await route.fetch()
            const body = (await response.json()) as Catalog
            delete body.web?.language_failed
            await route.fulfill({ response, json: body })
        })
        await stored(page, 'de')
        await openLanguages(page)
        await expect(page.getByRole('heading', { name: de.settings.language, exact: true })).toBeVisible()
        await page.route('**/__strings/ja.json', (route) => route.abort())
        await page.getByRole('button', { name: '日本語' }).click()
        const alert = page.getByRole('alert').filter({ hasText: en.web.language_failed.replace('{language}', '日本語') })
        await expect(alert).toBeVisible()
        await expect(page.getByRole('heading', { name: de.settings.language, exact: true })).toBeVisible()
        expect(await htmlLang(page)).toEqual({ lang: 'de', dir: 'ltr' })
        await page.unroute('**/__strings/ja.json')
        await alert.getByRole('button', { name: de.common.try_again }).click()
        await expect(page.getByRole('heading', { name: ja.settings.language, exact: true })).toBeVisible()
        await expect(alert).toHaveCount(0)
    })

    test('at the start, a catalog that does not come in gives English with Try again in a notice', async ({ page }) => {
        await stored(page, 'ar')
        await page.route('**/__strings/ar.json', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: '{"settings": {"lang' }))
        await openLanguages(page)
        await expect(page.getByRole('heading', { name: en.settings.language, exact: true })).toBeVisible()
        expect(await htmlLang(page)).toEqual({ lang: 'en', dir: 'ltr' })
        const notice = page.getByText(en.web.language_failed.replace('{language}', 'العربية'))
        await expect(notice.first()).toBeVisible()
        await page.unroute('**/__strings/ar.json')
        await page.getByRole('button', { name: en.common.try_again }).first().click()
        await expect(page.getByRole('heading', { name: ar.settings.language, exact: true })).toBeVisible()
        expect(await htmlLang(page)).toEqual({ lang: 'ar', dir: 'rtl' })
    })

    // The web holds one tab at a time (a second one offers Use here), so the choice that reaches a playing
    // page comes as the browser's storage event, which is what another tab's Settings sends.
    test('a language picked in another tab changes the words in place, and the video playing here plays on', async ({ page }) => {
        await noSizeFloor(page)
        await openClips(page)
        await openCard(page, 'Native Clip')
        await playsPast(page, 1)
        const seek = page.locator(`[aria-label="${en.web.seek_bar}"]`)
        await expect(seek).toHaveCount(1)
        await page.evaluate(() => {
            localStorage.setItem('tmplayer-language', 'de')
            dispatchEvent(new StorageEvent('storage', { key: 'tmplayer-language', newValue: 'de', storageArea: localStorage }))
        })
        await expect(page.locator(`[aria-label="${de.web.seek_bar}"]`)).toHaveCount(1)
        expect(await htmlLang(page)).toEqual({ lang: 'de', dir: 'ltr' })
        await expect(page).toHaveURL(/\/watch\//)
        const before = await page.locator('video').first().evaluate((v: HTMLVideoElement) => v.currentTime)
        await expect.poll(() => page.locator('video').first().evaluate((v: HTMLVideoElement, before) => !v.paused && v.currentTime > before + 0.5, before), { timeout: 10_000 }).toBe(true)
    })

    test('the TV remote walks the list without switching, OK picks, and the focus stays on the pick', async ({ page }) => {
        await page.setViewportSize({ width: 1920, height: 1080 })
        await page.goto('/__e2e/sizes')
        await page.evaluate(() => localStorage.setItem('tmplayer-layout', 'tv'))
        await page.goto('/settings')
        await expect(page.locator('html')).toHaveAttribute('data-form', 'tv')
        // The language list is on the Appearance and language page of the rail (WP-88): Right from its item enters the pane.
        await openTvSettingsPage(page, 'appearance')
        const automatic = page.locator('.lang-option').first()
        await expect(automatic).toBeVisible()
        // Down from the pane's top until the remote is on the list.
        const onList = () => page.evaluate(() => !!document.activeElement?.classList.contains('lang-option'))
        for (let i = 0; i < 30 && !(await onList()); i++) await page.keyboard.press('ArrowDown')
        // Into the list where the remote came down, then left along the first row to Automatic.
        for (let i = 0; i < 4 && !(await automatic.evaluate((el) => el === document.activeElement)); i++) await page.keyboard.press('ArrowLeft')
        await expect(automatic).toBeFocused()
        await page.keyboard.press('ArrowRight')
        await page.keyboard.press('ArrowRight')
        await page.keyboard.press('ArrowDown')
        // Moving switched nothing.
        expect(await htmlLang(page)).toEqual({ lang: 'en', dir: 'ltr' })
        const focused = page.locator('.lang-option:focus')
        const name = (await focused.textContent())!.trim()
        await page.keyboard.press('Enter')
        await expect(page.locator('html')).not.toHaveAttribute('lang', 'en')
        await expect(page.locator('.lang-option:focus')).toHaveText(name)
        await expect(page.locator('.lang-option:focus')).toHaveAttribute('aria-pressed', 'true')
    })
})

/** Nothing reaches past the right edge but inside a scroller of its own (a row of cards), and the page itself never scrolls sideways. */
async function sideways(page: Page): Promise<string[]> {
    return page.evaluate(() => {
        const out: string[] = []
        const root = document.scrollingElement!
        if (root.scrollWidth > innerWidth + 1) out.push(`page ${root.scrollWidth} > ${innerWidth}`)
        for (const el of document.querySelectorAll<HTMLElement>('body *')) {
            const r = el.getBoundingClientRect()
            if (r.width === 0 || getComputedStyle(el).position === 'fixed') continue
            if (r.right <= innerWidth + 1 && r.left >= -1) continue
            let clipped = false
            for (let p = el.parentElement; p && !clipped; p = p.parentElement) clipped = /(auto|scroll|hidden|clip)/.test(getComputedStyle(p).overflowX)
            if (!clipped) out.push(`${el.tagName.toLowerCase()}.${el.className?.toString?.().slice(0, 30)} ${Math.round(r.left)}..${Math.round(r.right)}`)
            if (out.length > 6) break
        }
        return out
    })
}

const SCREENS: [string, string, string][] = [
    ['home', '/', 'main'],
    ['chats', '/chats', 'a[href^="/chat/"]'],
    ['chat', '/chat/-1001', '.card'],
    ['history', '/history', 'main'],
    ['settings', '/settings', 'main'],
    ['appearance', '/settings/appearance', '.lang-list'],
    ['video', '/video/-1001/1', '.detail-title'],
]

/** phone-390, desktop-1280, or the TV at 1920 in the plain Chromium project. */
const formOf = (info: TestInfo) => (info.project.name === 'chromium' ? 'tv-1920' : info.project.name)

for (const tag of ['de', 'ar'])
    test.describe(`the screens in ${tag}`, () => {
        test.beforeEach(async ({ page }, info) => {
            test.skip(!['chromium', 'phone-390', 'desktop-1280'].includes(info.project.name), 'phone-390, desktop-1280 and the TV at 1920 in Chromium')
            await noSizeFloor(page)
            if (formOf(info) === 'tv-1920') {
                await page.setViewportSize({ width: 1920, height: 1080 })
                await page.evaluate(() => localStorage.setItem('tmplayer-layout', 'tv'))
            }
            await page.evaluate((tag) => localStorage.setItem('tmplayer-language', tag), tag)
        })
        for (const [name, path, ready] of SCREENS)
            test(name, async ({ page }, info) => {
                // The TV's Settings opens its pane on the page a row's address names (WP-88), not on a path.
                await page.goto(formOf(info) === 'tv-1920' && path === '/settings/appearance' ? '/settings#language' : path)
                await expect(page.locator(ready).first()).toBeVisible()
                await expect(page.locator('html')).toHaveAttribute('lang', tag)
                await expect(page.locator('html')).toHaveAttribute('dir', tag === 'ar' ? 'rtl' : 'ltr')
                await page.waitForTimeout(700)
                expect(await sideways(page), `${tag} ${name}: pushed past the edge`).toEqual([])
                if (['home', 'appearance', 'video', 'chat'].includes(name)) await page.screenshot({ path: `${SHOTS}${tag}-${name}-${formOf(info)}.png` })
            })
    })
