// HX93: the service tiles. The subtitles list names MSone with its tile, Settings' credits name TMDB,
// TVmaze and AniList with theirs, the tiles are decoration (hidden from a screen reader, never a tab
// stop), the TMDB mark is the official one (a white chip, its gradient), and the rest wear an initial.
// Pictures go to build/device-test/review/ for a look.
import { expect, test, type Page } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { noSizeFloor, openCard, playsPast } from './helpers'

const SHOTS = 'build/device-test/review'

async function gateway(page: Page) {
    const cors = { 'access-control-allow-origin': '*', 'access-control-allow-headers': '*', 'access-control-allow-methods': 'GET, OPTIONS' }
    await page.route('https://subs.tmplayer.org/**', async (route) => {
        const url = new URL(route.request().url())
        if (route.request().method() === 'OPTIONS') return route.fulfill({ status: 204, headers: cors })
        if (url.pathname === '/health') return route.fulfill({ json: { ok: true, providers: { msone: { state: 'on', message: null } } }, headers: cors })
        return route.fulfill({ status: 404, headers: cors })
    })
}

/** Never a tab stop or a button, and not read: a tile is drawn only. */
async function assertDecoration(page: Page, selector: string) {
    const tiles = page.locator(selector)
    expect(await tiles.count()).toBeGreaterThan(0)
    for (const tile of await tiles.all()) {
        await expect(tile).toHaveAttribute('aria-hidden', 'true')
        expect(await tile.evaluate((el) => el.tabIndex)).toBe(-1)
    }
}

test('Settings credits name TMDB, TVmaze and AniList with their tiles, and the TMDB mark is the official one', async ({ page }, info) => {
    mkdirSync(SHOTS, { recursive: true })
    await page.goto('/settings/library')
    const credit = page.locator('.svc-row').first()
    await expect(credit).toBeVisible()
    await expect(credit.locator('.svc-name')).toHaveText(['TMDB', 'TVmaze', 'AniList'])
    await assertDecoration(page, '.svc-row .svc-tile')
    const tmdb = credit.locator('.svc-tile[data-service="tmdb"]')
    await expect(tmdb).toHaveClass(/svc-logo/)
    await expect(tmdb.locator('path')).toHaveAttribute('d', /^M105\.67,36\.06h66\.9/)
    expect(await tmdb.evaluate((el) => getComputedStyle(el).backgroundColor)).toBe('rgb(255, 255, 255)')
    await expect(credit.locator('.svc-tile[data-service="tvmaze"]')).toHaveClass(/svc-initial/)
    await expect(credit.locator('.svc-tile[data-service="anilist"]')).toHaveText('A')
    await credit.scrollIntoViewIfNeeded()
    await page.screenshot({ path: `${SHOTS}/service-credits-${info.project.name}.png` })
})

test('the subtitles list names MSone with its tile', async ({ page, browserName }, info) => {
    test.skip(browserName !== 'chromium', 'the 20 s chaptered show plays in Chromium')
    mkdirSync(SHOTS, { recursive: true })
    await gateway(page)
    await noSizeFloor(page)
    await page.goto('/chat/-1006')
    await openCard(page, 'Lighthouse')
    await playsPast(page, 0.3)
    await page.mouse.move(400, 300)
    const bar = page.getByRole('button', { name: 'Subtitles', exact: true })
    if (await bar.count()) {
        await bar.click()
    } else {
        await page.locator('[data-shot=more]').first().click()
        await page.getByRole('menuitem', { name: /^Subtitles/ }).click()
    }
    const row = page.getByText('Malayalam subtitles from MSone')
    await expect(row).toBeVisible()
    const tile = page.locator('.svc-tile[data-service="msone"]').first()
    await expect(tile).toHaveText('M')
    await assertDecoration(page, '.svc-tile[data-service="msone"]')
    // The row's name is read once: the tile adds nothing to the button's accessible name.
    const button = page.getByRole('menuitem', { name: /Malayalam subtitles from MSone/ }).or(page.getByRole('button', { name: /Malayalam subtitles from MSone/ }))
    await expect(button.first()).toBeVisible()
    await page.screenshot({ path: `${SHOTS}/service-picker-${info.project.name}.png` })
})
