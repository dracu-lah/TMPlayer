// WP-102: what the web says while a chat's first page comes late (fake/mtcute.ts `slow`: a chat's pages
// answer after 3 s) and once the lists are in. A chat says "Finding videos…" in the one status line
// (ui/WorkStatus.tsx: a polite role=status region), words and a spinner, no button, never focusable; the
// line ends with the answer and leaves no spinner behind, and the region takes no height while Idle (the
// grid under it does not shift when the line comes and goes). On the TV skin the remote's arrows never
// land in the line, its text is at least 14 dp and it stays inside the screen. The words are the apps'
// own strings (browse.finding_videos). Chats' first read is too quick on the fake (the chat list is not
// slowed by `slow`), so its words are covered by the node tests of ui/loadStates and the Kotlin suites.
import { expect, test, type Page } from '@playwright/test'
import { fakeCase, noSizeFloor } from './helpers'

test.skip(({ browserName }) => browserName !== 'chromium', 'the live cases run in Chromium')

const region = (page: Page) => page.locator('.work-status:not(.pill)').first()
const line = (page: Page) => page.locator('.work-status:not(.pill) .work-status-line')

/** The layout written where the app keeps it before it starts (the phone has none: its touch screen decides). */
async function layout(page: Page, name: string | null) {
    if (!name) return
    await page.addInitScript((value) => {
        try {
            localStorage.setItem('tmplayer-layout', value)
        } catch {}
    }, name)
}

const FORMS = [
    { name: 'phone', layout: null, width: 390, height: 844 },
    { name: 'desktop', layout: 'desktop', width: 1280, height: 800 },
    { name: 'tv', layout: 'tv', width: 1920, height: 1080 },
] as const

for (const form of FORMS) {
    test.describe(`${form.name}`, () => {
        test.use({ viewport: { width: form.width, height: form.height }, hasTouch: form.name === 'phone', isMobile: form.name === 'phone' })

        test('a chat says it is finding videos, with no button, and the line and its spinner end with the first page', async ({ page, context }) => {
            await fakeCase(context, 'slow')
            await layout(page, form.layout)
            await noSizeFloor(page)
            await page.goto('/chat/-1001')
            await expect(line(page)).toContainText('Finding videos…', { timeout: 2_500 })
            await expect(region(page)).toHaveAttribute('role', 'status')
            await expect(region(page)).toHaveAttribute('aria-live', 'polite')
            // Nothing in the line takes a press or the remote: it says words and shows a spinner.
            expect(await line(page).evaluate((el) => el.querySelectorAll('button, a, input, [tabindex]').length)).toBe(0)
            await expect(page.locator('.card').first()).toBeVisible({ timeout: 15_000 })
            await expect(line(page)).toHaveCount(0)
            await expect(page.locator('.work-spinner')).toHaveCount(0)
            await expect(page.locator('.work-status button')).toHaveCount(0)
            // Idle: the region stays for a reader that has met it and takes no height, so nothing under it moved.
            const idle = await region(page).boundingBox()
            expect(idle === null || idle.height === 0).toBe(true)
        })

        test('Chats, once its list is in, has no loading line, no spinner and no status button', async ({ page, context }) => {
            await fakeCase(context)
            await layout(page, form.layout)
            await noSizeFloor(page)
            await page.goto('/chats')
            await expect(page.getByRole('link', { name: /Test Clips/ }).first()).toBeVisible()
            await expect(line(page)).toHaveCount(0)
            await expect(page.locator('.work-spinner')).toHaveCount(0)
            await expect(page.locator('.work-status button')).toHaveCount(0)
        })
    })
}

test('on the TV skin the arrows never land in the loading line, and its text is at least 14 dp inside the screen', async ({ page, context }) => {
    await page.setViewportSize({ width: 1920, height: 1080 })
    await fakeCase(context, 'slow')
    await layout(page, 'tv')
    await noSizeFloor(page)
    await page.goto('/chat/-1001')
    await expect(line(page)).toContainText('Finding videos…', { timeout: 2_500 })
    for (const key of ['ArrowDown', 'ArrowRight', 'ArrowUp', 'ArrowLeft', 'Tab']) {
        await page.keyboard.press(key)
        await page.waitForTimeout(80)
        expect(await page.evaluate(() => !!document.activeElement?.closest('.work-status')), `${key} did not enter the line`).toBe(false)
    }
    // 1920 x 1080 is 2 px a dp: 14 dp is 28 px.
    expect(await line(page).evaluate((el) => parseFloat(getComputedStyle(el).fontSize))).toBeGreaterThanOrEqual(28)
    const box = (await line(page).boundingBox())!
    expect(box.x).toBeGreaterThanOrEqual(0)
    expect(box.x + box.width).toBeLessThanOrEqual(1920)
    expect(box.y + box.height).toBeLessThanOrEqual(1080)
})
