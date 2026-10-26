// The player's extras (session 13): chapters on the bar and their keys, the skip offer, A-B repeat,
// bookmarks, the sleep timer and "Still watching?", autoplay's countdown, subtitle timing, the key
// sheet and Watched in the menu. The 20 s chaptered show ("Lighthouse") is e2e/media/chapters.mkv.
import { expect, test, type Page } from '@playwright/test'
import { choice, noSizeFloor, openCard, playsPast } from './helpers'

test.skip(({ browserName }) => browserName !== 'chromium', 'the extras run in Chromium')

const time = (page: Page) => page.locator('video').evaluate((v: HTMLVideoElement) => v.currentTime)
const seek = (page: Page, s: number) => page.locator('video').evaluate((v: HTMLVideoElement, to) => (v.currentTime = to), s)

/** Opens the first Lighthouse episode and waits for it to play. */
async function lighthouse(page: Page) {
    await noSizeFloor(page)
    await page.goto('/chat/-1006')
    await openCard(page, 'Lighthouse')
    await playsPast(page, 0.3)
    // The chapters are read once the file has opened.
    await expect(page.locator('.pc-mark')).toHaveCount(2)
}

const openMore = async (page: Page) => {
    await page.mouse.move(400, 300)
    await page.locator('[data-shot=more]').first().click()
}

test('chapters mark the bar, the command key steps through them, and the card names the chapter', async ({ page }) => {
    await lighthouse(page)
    await page.keyboard.press('Control+ArrowRight')
    await expect(page.locator('.pc-note')).toHaveText('Chapter 2 of 3: The Harbour')
    expect(await time(page)).toBeGreaterThan(4.9)
    await page.keyboard.press('Control+ArrowRight')
    await expect(page.locator('.pc-note')).toHaveText('Chapter 3 of 3: Credits')
    await page.keyboard.press('Control+ArrowRight')
    await expect(page.locator('.pc-note')).toHaveText('This is the last chapter')
    // Just into the last chapter, back goes to the one before.
    await page.keyboard.press('Control+ArrowLeft')
    await expect(page.locator('.pc-note')).toHaveText('Chapter 2 of 3: The Harbour')
})

test('the intro is offered to skip, and skipping lands on the next chapter', async ({ page }) => {
    await lighthouse(page)
    await seek(page, 1)
    const pill = page.locator('.pc-corner.skip')
    await expect(pill).toHaveText('Skip intro')
    await pill.click()
    expect(await time(page)).toBeGreaterThan(4.9)
    await expect(pill).toHaveCount(0)
})

test('A-B repeat plays the stretch again and a third press ends it', async ({ page }) => {
    await lighthouse(page)
    await seek(page, 6)
    await page.keyboard.press('r')
    await expect(page.locator('.pc-chipline.loop')).toContainText(/Loop from|From 0:06/)
    await page.waitForTimeout(1200)
    await page.keyboard.press('r')
    await expect(page.locator('.pc-chipline.loop')).toContainText('Repeating')
    // Played into the end, it goes back to the start: the time never gets far past the end.
    await page.waitForTimeout(3500)
    expect(await time(page)).toBeLessThan(9.5)
    await page.keyboard.press('r')
    await expect(page.locator('.pc-chipline.loop')).toHaveCount(0)
})

test('a bookmark is saved at the time, listed, played from and deleted', async ({ page }) => {
    await lighthouse(page)
    await seek(page, 8)
    await page.keyboard.press('b')
    await expect(page.locator('.pc-note')).toContainText('Bookmark saved at 0:0')
    await seek(page, 2)
    await openMore(page)
    await page.getByRole('menuitem', { name: /^Bookmarks/ }).click()
    const row = page.locator('.pc-bookmark')
    await expect(row).toHaveCount(1)
    await row.click()
    await expect(page.locator('.pc-sheet')).toHaveCount(0)
    expect(await time(page)).toBeGreaterThan(7.5)
    // It is still there after the player is reopened: kept with the device's other notes.
    await page.reload()
    await expect(page.locator('video')).toHaveCount(1)
    await openMore(page)
    await page.getByRole('menuitem', { name: /^Bookmarks/ }).click()
    await expect(page.locator('.pc-bookmark')).toHaveCount(1)
    await page.getByRole('button', { name: 'Delete bookmark' }).click()
    await expect(page.locator('.pc-bookmark')).toHaveCount(0)
    await expect(page.getByText('No bookmarks yet')).toBeVisible()
})

test('the sleep timer set for the end of the video stops there and asks', async ({ page }) => {
    await lighthouse(page)
    await openMore(page)
    await page.getByRole('menuitem', { name: 'Playback options' }).click()
    await page.getByRole('menuitem', { name: /^Sleep timer/ }).click()
    await choice(page, 'Stops at the end of this video').click()
    await seek(page, 19)
    const card = page.getByRole('alertdialog', { name: 'Still watching?' })
    await expect(card).toBeVisible({ timeout: 10_000 })
    await expect(card).toContainText('The sleep timer stopped at the end of the video.')
    // Nothing started on its own: still on the first episode.
    await expect(page).toHaveURL(/\/watch\//)
    await card.getByRole('button', { name: 'Keep watching' }).click()
    await expect(card).toHaveCount(0)
})

test('a sleep timer in minutes pauses the video when it runs out', async ({ page }) => {
    await page.clock.install()
    await lighthouse(page)
    await openMore(page)
    await page.getByRole('menuitem', { name: 'Playback options' }).click()
    await page.getByRole('menuitem', { name: /^Sleep timer/ }).click()
    await choice(page, '15 minutes').click()
    await expect(page.locator('.pc-note')).toHaveText('Stops in 15 minutes')
    await page.clock.fastForward('15:05')
    await expect(page.getByRole('alertdialog', { name: 'Still watching?' })).toContainText('The sleep timer paused the video.')
    expect(await page.locator('video').evaluate((v: HTMLVideoElement) => v.paused)).toBe(true)
})

test('autoplay counts down on the next card and starts the next episode', async ({ page }) => {
    await lighthouse(page)
    await seek(page, 19.2)
    await expect(page.locator('.pc-next .pc-count')).toContainText('Next episode in')
    await expect(page).toHaveURL(/\/watch\/-1006\/\d+$/)
    const first = page.url()
    await expect.poll(() => page.url(), { timeout: 15_000 }).not.toBe(first)
    await playsPast(page, 0.3)
})

test('Hide on the countdown stops autoplay', async ({ page }) => {
    await lighthouse(page)
    await seek(page, 19.2)
    await page.locator('.pc-next').getByRole('button', { name: 'Cancel' }).click()
    const first = page.url()
    await page.waitForTimeout(6500)
    expect(page.url()).toBe(first)
})

test('subtitles move with the delay keys', async ({ page }) => {
    await lighthouse(page)
    await page.locator('input[type=file]').setInputFiles({ name: 'clip.srt', mimeType: 'text/plain', buffer: Buffer.from('1\n00:00:02,000 --> 00:00:04,000\nHello there\n') })
    await expect.poll(() => page.locator('video').evaluate((v: HTMLVideoElement) => v.textTracks[0]?.cues?.length ?? 0)).toBe(1)
    const start = () => page.locator('video').evaluate((v: HTMLVideoElement) => Math.round(((v.textTracks[0].cues![0] as VTTCue).startTime) * 100) / 100)
    expect(await start()).toBe(2)
    await page.keyboard.press('x')
    await expect(page.locator('.pc-note')).toHaveText('Subtitle delay +0.1 s')
    expect(await start()).toBe(2.1)
    await page.keyboard.press('z')
    await page.keyboard.press('z')
    expect(await start()).toBe(1.9)
})

test('the question mark opens the key sheet', async ({ page }) => {
    await lighthouse(page)
    await page.keyboard.press('Shift+?')
    const sheet = page.getByRole('dialog', { name: 'Keyboard shortcuts' })
    await expect(sheet).toBeVisible()
    await expect(sheet).toContainText('Previous, next chapter')
    await page.keyboard.press('Escape')
    await expect(sheet).toHaveCount(0)
})

test('Watched in the menu marks the video, and the entry turns into Unwatched', async ({ page }) => {
    await lighthouse(page)
    await openMore(page)
    await page.getByRole('menuitem', { name: 'Mark as watched' }).click()
    await expect(page.locator('.pc-note')).toHaveText('Marked as watched')
    await openMore(page)
    await expect(page.getByRole('menuitem', { name: 'Mark as unwatched' })).toBeVisible()
})

test('subtitle style and timing change the look', async ({ page }) => {
    await lighthouse(page)
    await openMore(page)
    await page.getByRole('menuitem', { name: 'Subtitle style and timing' }).click()
    const sheet = page.getByRole('dialog', { name: 'Subtitle style and timing' })
    await sheet.getByRole('radio', { name: 'Yellow' }).click()
    await sheet.getByRole('radio', { name: 'Large', exact: true }).click()
    const vars = await page.locator('video').evaluate((v: HTMLVideoElement) => [v.style.getPropertyValue('--cue-colour'), v.style.getPropertyValue('--cue-size')])
    expect(vars[0]).toBe('#FFE14D')
    expect(parseInt(vars[1])).toBeGreaterThan(10)
})
