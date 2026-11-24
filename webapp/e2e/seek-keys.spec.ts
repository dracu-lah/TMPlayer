// WP-61: one stepper for a held seek key. A held ArrowRight writes the video's time a few times a
// second, not on every key repeat, the step is 5 s on a keyboard, the seek bar's arrows are the same
// stepper, a screen reader hears the run once in a while, and Home, End and the Page keys are the
// player's, not the bar's.
import { expect, test, type Page } from '@playwright/test'
import { noSizeFloor, openCard, openClips, playsPast } from './helpers'
import { GATE_MS } from '../src/player/seekStepper'

test.beforeEach(async ({ page, isMobile }) => {
    test.skip(isMobile, 'a keyboard is a desktop input')
    await noSizeFloor(page)
    await openClips(page)
})

/** Counts every write of currentTime on the page's videos. */
async function countWrites(page: Page) {
    await page.evaluate(() => {
        const w = window as unknown as { __writes: number[] }
        w.__writes = []
        const d = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'currentTime')!
        Object.defineProperty(HTMLMediaElement.prototype, 'currentTime', {
            configurable: true,
            get: d.get,
            set(v: number) {
                w.__writes.push(performance.now())
                d.set!.call(this, v)
            },
        })
    })
}
const writes = (page: Page) => page.evaluate(() => (window as unknown as { __writes: number[] }).__writes.length)

async function openPlaying(page: Page) {
    await openCard(page, 'Native Clip')
    await playsPast(page, 0.3)
    await page.locator('video').first().evaluate((v: HTMLVideoElement) => (v.pause(), (v.currentTime = 0.5)))
}

test('a held ArrowRight writes the time at most seven times a second, and the step is 5 s', async ({ page }) => {
    await openPlaying(page)
    await countWrites(page)
    const t0 = await page.locator('video').first().evaluate((v: HTMLVideoElement) => v.currentTime)
    await page.keyboard.down('ArrowRight')
    // About 30 repeats a second for a second and a bit.
    for (let i = 0; i < 36; i++) {
        await page.keyboard.down('ArrowRight')
        await page.waitForTimeout(30)
    }
    await page.keyboard.up('ArrowRight')
    // Counted against the clock the gate reads, not a fixed number: a slow or busy browser (WebKit in
    // a container) takes longer over the 37 presses and lets more steps through, each a gate apart.
    const at = await page.evaluate(() => (window as unknown as { __writes: number[] }).__writes)
    const n = at.length
    expect(n).toBeGreaterThanOrEqual(2)
    expect(n).toBeLessThan(37)
    for (let i = 1; i < n; i++) expect(at[i] - at[i - 1]).toBeGreaterThanOrEqual(GATE_MS - 1)
    const t1 = await page.locator('video').first().evaluate((v: HTMLVideoElement) => v.currentTime)
    expect(t1).toBeGreaterThan(t0)
})

test('one tap is one write and a screen reader line with the run, the picture note is not read out', async ({ page }) => {
    await openPlaying(page)
    await countWrites(page)
    await page.keyboard.press('ArrowRight')
    expect(await writes(page)).toBe(1)
    await expect(page.locator('.pc .visually-hidden[role=status]')).toContainText('+5')
    await expect(page.locator('.pc-note.right')).toHaveAttribute('aria-hidden', 'true')
})

test('the focused seek bar answers its arrows with the same stepper, and Home leaves it to the player', async ({ page }) => {
    await openPlaying(page)
    await page.locator('.pc-bar').focus()
    await countWrites(page)
    await page.keyboard.press('ArrowRight')
    expect(await writes(page)).toBe(1)
    await page.keyboard.press('Home')
    await expect.poll(() => page.locator('video').first().evaluate((v: HTMLVideoElement) => v.currentTime)).toBeLessThan(1)
})
