// Recently added's See all on the phone and the desktop: the row's heading opens a page of the
// newest videos across Home's chats, read on a page at a time as it is scrolled.
import { expect, test } from '@playwright/test'
import { fakeCase, noSizeFloor } from './helpers'

test('Recently added: the heading\'s See all opens the page, which reads on to the oldest video as it is scrolled', async ({ page, context }) => {
    test.setTimeout(60_000)
    await fakeCase(context, 'manyrecent')
    await noSizeFloor(page)
    await page.goto('/')
    const row = page.locator('.home-row', { has: page.locator('.row-title', { hasText: 'Recently added' }) })
    const seeAll = row.locator('.row-head .see-all')
    await expect(seeAll).toHaveAttribute('href', '/recent')
    // The strip's own end card is the TV's only.
    await expect(row.locator('.see-all-card')).toHaveCount(0)
    await seeAll.click()
    await expect(page).toHaveURL(/\/recent$/)
    await expect(page.locator('.vgrid .card').first()).toBeVisible()
    // The newest first, and the oldest not yet: the page holds a first page only.
    await expect(page.locator('.vgrid .card', { hasText: 'Native Clip' })).toHaveCount(0)
    // Scrolled to the foot again and again, it reads on until the oldest video is in: Native Clip, held back
    // until the files' feed (four hundred newer) catches up with the videos' one, so it is never placed above cards already drawn.
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

test('Recently added: a failed first page says so with Try again, which loads it', async ({ page, context }) => {
    await fakeCase(context, 'recentfails')
    await noSizeFloor(page)
    await page.goto('/recent')
    await expect(page.locator('.error-notice')).toBeVisible()
    await page.locator('.error-notice button').click()
    await expect(page.locator('.vgrid .card').first()).toBeVisible()
})
