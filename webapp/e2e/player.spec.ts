// Every way a file plays (plan section 3): native, repacked, sound converted, AVI and WMV through
// libav.js (MPEG-4 Part 2, DivX 3 and WMV decoded, H.264 copied), and ones that need the app, from
// e2e/media. The other old formats are in oldformats.spec.ts.
import { expect, test } from '@playwright/test'
import { noSizeFloor, openCard, openClips, playsPast } from './helpers'

const PLAYS = ['Native Clip', 'Tail Index', 'Matroska Clip', 'Transport Clip', 'Surround Clip', 'Webm Clip', 'Old Clip', 'Xvid Clip', 'Divx Clip', 'Avc Avi Clip', 'Windows Clip']

test.beforeEach(async ({ page }) => {
    await noSizeFloor(page)
    await openClips(page)
})

for (const name of PLAYS) {
    test(`${name} plays`, async ({ page }) => {
        await openCard(page, name)
        await expect(page).toHaveURL(/\/watch\//)
        await playsPast(page, 1)
    })
}

// movi (player/movi.ts) opens these after the repack and libav.js gave up, finds it decodes no
// picture, and says so after its 10 s watch: hence the longer wait.

test('a damaged AVI says it needs the app', async ({ page }) => {
    await openCard(page, 'Broken Clip')
    await expect(page.getByText('This video needs the TMPlayer app')).toBeVisible()
})

test('an AVI whose picture libav.js cannot convert says it needs the app', async ({ page }) => {
    await openCard(page, 'Odd Picture Clip')
    await expect(page.getByText('This video needs the TMPlayer app')).toBeVisible({ timeout: 30_000 })
    await expect(page.locator('a[href="https://tmplayer.org/#download"]')).toBeVisible()
})

test('an AVI with sound libav.js cannot decode plays without it, and says so', async ({ page }) => {
    await openCard(page, 'Odd Sound Clip')
    await playsPast(page, 1)
})

test('an AVI cut off part way plays what is there and then ends or stops with a card, never a spinner', async ({ page, browserName }) => {
    // Playwright's WebKit on Linux (GStreamer) takes the whole page down when a libav.js stream
    // plays to its end (any AVI, whole or cut; remuxed files end fine), so the end cannot be
    // watched there: the test only passed when a poll landed just before the crash.
    test.skip(browserName === 'webkit', 'Linux WebKit crashes at the end of a libav.js stream')
    await openCard(page, 'Cut Clip')
    await playsPast(page, 1)
    const card = page.getByText(/needs the TMPlayer app|Can.t play|stalled|Try again|Retry/i).first()
    const video = page.locator('video').first()
    await expect
        .poll(() => video.evaluate((v: HTMLVideoElement) => v.ended || v.paused || v.readyState < 3), { timeout: 25_000 })
        .toBeTruthy()
    // Whatever it settled on, the page is still alive and the card (if any) is a real one.
    await expect(card.or(video)).toBeVisible()
})

test('Back closes the player, then the detail page, back to the chat', async ({ page }) => {
    await openCard(page, 'Native Clip')
    await playsPast(page, 0.5)
    await page.goBack()
    await expect(page).toHaveURL(/\/video\/-1001\//)
    await expect(page.locator('video')).toHaveCount(0)
    await page.goBack()
    await expect(page).toHaveURL(/\/chat\/-1001/)
})

test('a watch page opened by its address plays', async ({ page }) => {
    const card = page.locator('.card').filter({ hasText: 'Matroska Clip' })
    for (let i = 0; i < 30 && (await card.count()) === 0; i++) await page.evaluate(() => window.scrollBy(0, 300))
    const href = await card.getAttribute('href')
    await page.goto(href!.replace('/video/', '/watch/'))
    await playsPast(page, 1)
})

// R9: Tab into controls that have gone shows them, and they stay while the keyboard is on one.
test('Tab brings the hidden controls back and they stay while a control has the keyboard', async ({ page, isMobile }) => {
    test.skip(isMobile, 'a keyboard is a desktop input')
    await openCard(page, 'Native Clip')
    await playsPast(page, 0.5)
    await page.mouse.move(640, 300)
    // The clip is short: it may have ended and paused, which keeps the controls up.
    await page.evaluate(() => { const v = document.querySelector('video')!; v.currentTime = 0; v.loop = true; return v.play() })
    await page.mouse.move(640, 320)
    await expect(page.locator('.pc.shown')).toHaveCount(0, { timeout: 8000 })
    await page.keyboard.press('Tab')
    await expect(page.locator('.pc.shown')).toHaveCount(1)
    const on = await page.evaluate(() => document.activeElement?.matches(':focus-visible') && !!document.activeElement.closest('.pc-layer'))
    expect(on, 'the keyboard is on a control of the row').toBe(true)
    // Past the 3.5 s hide time the row is still there.
    await page.waitForTimeout(5000)
    await expect(page.locator('.pc.shown')).toHaveCount(1)
    // The seek bar shows where the keyboard is: its thumb grows a ring (no outline round a round bar).
    for (let i = 0; i < 30 && (await page.evaluate(() => document.activeElement?.getAttribute('role'))) !== 'slider'; i++) await page.keyboard.press('Tab')
    await expect(page.locator('.pc-bar')).toBeFocused()
    await expect(page.locator('.pc-thumb')).toHaveCSS('box-shadow', /rgba\(255, 255, 255, 0\.9\) 0px 0px 0px 3px/)
})

// R9: a sheet over the player takes the keyboard (focus inside it) and the controls behind it are out of reach until it closes.
test('the shortcuts sheet takes the keyboard and Tab cannot reach the controls behind it', async ({ page, isMobile }) => {
    test.skip(isMobile, 'a keyboard is a desktop input')
    await openCard(page, 'Native Clip')
    await playsPast(page, 0.5)
    await page.keyboard.press('?')
    const sheet = page.locator('.pc-shortcuts')
    await expect(sheet).toBeVisible()
    await expect(sheet.locator('.pc-sheet-close')).toBeFocused()
    for (let i = 0; i < 6; i++) {
        await page.keyboard.press('Tab')
        expect(await page.evaluate(() => !!document.activeElement?.closest('.pc-layer')), 'focus never lands on a control behind the sheet').toBe(false)
    }
    await page.keyboard.press('Escape')
    await expect(sheet).toHaveCount(0)
    await page.keyboard.press('Tab')
    expect(await page.evaluate(() => !!document.activeElement?.closest('.pc-layer')), 'the controls answer again once it is closed').toBe(true)
})

test.describe('without a service worker', () => {
    test.use({ serviceWorkers: 'block' })
    test('an MP4 is repacked instead', async ({ page }) => {
        await openCard(page, 'Native Clip')
        await playsPast(page, 1)
    })
})
