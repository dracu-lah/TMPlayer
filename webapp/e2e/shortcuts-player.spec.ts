// WB10: the player's keys the desktop app has (HX17) and the free pinch zoom (PZ1). The keyboard on
// a desktop browser (chromium, webkit), the fingers on an iPhone (webkit-iphone, pinches sent as
// touch pointers, since Playwright has no two finger touch of its own). TMPLAYER_SHOTS=<dir> keeps
// a picture per form factor there (design/web-gaps/WB10).
import { expect, test, type Page } from '@playwright/test'
import { noSizeFloor, openCard, openClips, playsPast } from './helpers'

const shots = async (page: Page, name: string) => {
    const dir = process.env.TMPLAYER_SHOTS
    if (dir) await page.screenshot({ path: `${dir}/${name}-${test.info().project.name}.png` })
}
/** The zoom on the picture, read back from its transform: 1 with none. */
const scale = (page: Page) =>
    page.locator('video').first().evaluate((v: HTMLVideoElement) => Number(/scale\(([\d.]+)\)/.exec(v.style.transform)?.[1] ?? 1))
const pan = (page: Page) =>
    page.locator('video').first().evaluate((v: HTMLVideoElement) => {
        // WebKit writes translate(12px) back when the second value is 0.
        const m = /translate\((-?[\d.]+)px(?:,\s*(-?[\d.]+)px)?\)/.exec(v.style.transform)
        return m ? { x: Number(m[1]), y: Number(m[2] ?? 0) } : { x: 0, y: 0 }
    })

/** The player open on a short clip, paused near its start so nothing ends under the test. */
async function openPaused(page: Page) {
    await openCard(page, 'Native Clip')
    await playsPast(page, 0.3)
    await page.locator('video').first().evaluate((v: HTMLVideoElement) => (v.pause(), (v.currentTime = 0.2)))
}

test.beforeEach(async ({ page }) => {
    await noSizeFloor(page)
    await openClips(page)
})

test.describe('the keyboard', () => {
    test.beforeEach(({ isMobile }) => test.skip(isMobile, 'a keyboard is a desktop input'))

    test('Alt+= and Alt+- zoom the picture with its figure up, Alt+0 puts it back, Ctrl+wheel zooms around the pointer', async ({ page }) => {
        await openPaused(page)
        await page.keyboard.press('Alt+Equal')
        await page.keyboard.press('Alt+Equal')
        await expect(page.locator('.pc-zoom-chip')).toHaveText('Zoom 141%')
        expect(await scale(page)).toBeCloseTo(Math.SQRT2, 2)
        // The subtitles stay outside the picture's transform: the layer over it is drawn instead of the video's own.
        await expect(page.locator('.player.pc-zoomed .movi-cues')).toHaveCount(1)
        await page.keyboard.press('Alt+Minus')
        expect(await scale(page)).toBeCloseTo(2 ** 0.25, 2)
        await page.keyboard.press('Alt+Digit0')
        expect(await scale(page)).toBe(1)
        await expect(page.locator('.player.pc-zoomed')).toHaveCount(0)
        await expect(page.locator('.movi-cues')).toHaveCount(0)
        // A mouse's notch with Ctrl held, near the left edge: one step, and the picture moves toward the pointer's side.
        await page.locator('.pc-picture').evaluate((el) => {
            const r = el.getBoundingClientRect()
            el.dispatchEvent(new WheelEvent('wheel', { deltaY: -100, ctrlKey: true, clientX: r.left + 40, clientY: r.top + r.height / 2, bubbles: true, cancelable: true }))
        })
        expect(await scale(page)).toBeCloseTo(2 ** 0.25, 2)
        expect((await pan(page)).x).toBeGreaterThan(0)
        // The page's own zoom was not touched.
        expect(await page.evaluate(() => window.visualViewport?.scale ?? 1)).toBe(1)
        await shots(page, 'zoom')
        // A new picture shape starts the zoom again, quietly: the shape's note shows, the zoom's figure goes.
        await page.keyboard.press('w')
        expect(await scale(page)).toBe(1)
        await expect(page.locator('.pc-zoom-chip')).toHaveCount(0)
        await expect(page.locator('.pc-note')).toContainText('Picture shape')
    })

    test("an engine's canvas that comes after the zoom wears it, and an engine's own cue layer replaces the zoom's", async ({ page }) => {
        await openPaused(page)
        await page.keyboard.press('Alt+Equal')
        await expect(page.locator('.player.pc-zoomed .movi-cues')).toHaveCount(1)
        // What movi.ts and libavcanvas.ts do when they take over: a canvas beside the video, then their cue layer.
        await page.locator('video').first().evaluate((v) => {
            const c = document.createElement('canvas')
            c.className = 'sw-canvas'
            v.after(c)
            const cues = document.createElement('div')
            cues.className = 'movi-cues engine'
            v.parentElement!.append(cues)
        })
        await expect.poll(() => page.locator('canvas.sw-canvas').evaluate((c: HTMLCanvasElement) => c.style.transform)).toContain('scale(1.18')
        await expect(page.locator('.movi-cues')).toHaveCount(1)
        await expect(page.locator('.movi-cues.engine')).toHaveCount(1)
        await page.keyboard.press('Alt+Digit0')
        expect(await page.locator('canvas.sw-canvas').evaluate((c: HTMLCanvasElement) => c.style.transform)).toBe('')
    })

    test('a touch screen in the desktop form pinches too', async ({ page }) => {
        await openPaused(page)
        const box = (await page.locator('.pc-picture').boundingBox())!
        const cx = box.width / 2
        const cy = box.height / 2
        await fingers(page, [
            { type: 'down', id: 1, x: cx - 50, y: cy },
            { type: 'down', id: 2, x: cx + 50, y: cy },
            { type: 'move', id: 2, x: cx + 150, y: cy },
            { type: 'up', id: 1, x: cx - 50, y: cy },
            { type: 'up', id: 2, x: cx + 150, y: cy },
        ])
        expect(await scale(page)).toBeCloseTo(2, 2)
        await expect(page.locator('.pc-zoom-chip')).toHaveText('Zoom 200%')
    })

    test('Cmd or Ctrl+G goes to a typed time, says so when the text is no time in the video, and Escape gives the keyboard back', async ({ page }) => {
        await openPaused(page)
        const mod = (await page.evaluate(() => /Mac/.test(navigator.platform))) ? 'Meta' : 'Control'
        await page.keyboard.press(`${mod}+KeyG`)
        const prompt = page.getByRole('dialog', { name: 'Go to a time' })
        await expect(prompt).toBeVisible()
        await expect(prompt.locator('input')).toBeFocused()
        await page.keyboard.type('9:00:00')
        await page.keyboard.press('Enter')
        await expect(prompt.getByRole('alert')).toHaveText('That is not a time in this video')
        await expect(prompt).toBeVisible()
        await shots(page, 'goto')
        // Keys typed there are the field's: a digit does not jump the video.
        await prompt.locator('input').fill('')
        await page.keyboard.type('0:01')
        await page.keyboard.press('Enter')
        await expect(prompt).toHaveCount(0)
        await expect.poll(() => page.locator('video').first().evaluate((v: HTMLVideoElement) => v.currentTime)).toBeGreaterThan(0.9)
        await page.keyboard.press(`${mod}+KeyG`)
        await expect(prompt).toBeVisible()
        await page.keyboard.press('Escape')
        await expect(prompt).toHaveCount(0)
        // Escape closed the prompt only: the player is still open.
        await expect(page).toHaveURL(/\/watch\//)
    })

    test('Backspace puts a changed speed back before it leaves, and Shift+3 is not a jump', async ({ page }) => {
        await openPaused(page)
        await page.keyboard.press(']')
        await expect.poll(() => page.locator('video').first().evaluate((v: HTMLVideoElement) => v.playbackRate)).toBe(1.25)
        await page.keyboard.press('Backspace')
        await expect.poll(() => page.locator('video').first().evaluate((v: HTMLVideoElement) => v.playbackRate)).toBe(1)
        await expect(page).toHaveURL(/\/watch\//)
        const at = await page.locator('video').first().evaluate((v: HTMLVideoElement) => v.currentTime)
        await page.keyboard.press('Shift+Digit3')
        expect(await page.locator('video').first().evaluate((v: HTMLVideoElement) => v.currentTime)).toBeCloseTo(at, 1)
    })

    test('S saves the frame shown as a PNG named after the video and the time', async ({ page, browserName }) => {
        test.skip(browserName === 'webkit', "WebKit on Linux paints no frame from this file into a canvas")
        await openPaused(page)
        const saved = page.waitForEvent('download')
        await page.keyboard.press('s')
        const file = await saved
        expect(file.suggestedFilename()).toMatch(/^Native Clip 0\.00\.png$/)
        await expect(page.locator('.pc-note')).toContainText('Screenshot saved')
    })

    test('the sheet names the new keys, and says the sound delay keys are not here', async ({ page }) => {
        await openPaused(page)
        await page.keyboard.press('?')
        const sheet = page.locator('.pc-shortcuts')
        await expect(sheet).toBeVisible()
        for (const line of ['Zoom in, out, reset', 'Go to a time', 'Screenshot, without subtitles', 'Picture in picture', 'Seek 5 min'])
            await expect(sheet.locator('.pc-key-row', { hasText: line })).toHaveCount(1)
        await expect(sheet.locator('.pc-key-note')).toHaveText('Sound delay is not available in this player.')
        await shots(page, 'sheet')
        await page.keyboard.press('Escape')
        await expect(sheet).toHaveCount(0)
    })
})

/** Touch pointers on the picture, as a finger's would come: down, the moves, up. */
async function fingers(page: Page, steps: { type: 'down' | 'move' | 'up'; id: number; x: number; y: number }[]) {
    await page.locator('.pc-picture').evaluate((el, steps) => {
        const r = el.getBoundingClientRect()
        for (const s of steps) {
            el.dispatchEvent(
                new PointerEvent(`pointer${s.type}`, {
                    pointerId: s.id, pointerType: 'touch', isPrimary: s.id === 1, clientX: r.left + s.x, clientY: r.top + s.y, bubbles: true, cancelable: true,
                }),
            )
        }
    }, steps)
}

test.describe('the fingers', () => {
    test.beforeEach(({ isMobile }) => test.skip(!isMobile, 'a pinch is a touch screen input'))

    test('a pinch zooms around the fingers, one finger then moves the picture, a pinch back under 105% settles, More options resets', async ({ page }) => {
        await openPaused(page)
        const box = (await page.locator('.pc-picture').boundingBox())!
        const cx = box.width / 2
        const cy = box.height / 2
        // Out from 100 px apart to 200: twice the size.
        await fingers(page, [
            { type: 'down', id: 1, x: cx - 50, y: cy },
            { type: 'down', id: 2, x: cx + 50, y: cy },
            { type: 'move', id: 1, x: cx - 75, y: cy },
            { type: 'move', id: 2, x: cx + 75, y: cy },
            { type: 'move', id: 1, x: cx - 100, y: cy },
            { type: 'move', id: 2, x: cx + 100, y: cy },
        ])
        await expect(page.locator('.pc-zoom-chip')).toHaveText('Zoom 200%')
        await fingers(page, [{ type: 'up', id: 1, x: cx - 100, y: cy }, { type: 'up', id: 2, x: cx + 100, y: cy }])
        expect(await scale(page)).toBeCloseTo(2, 2)
        await shots(page, 'pinch')
        // One finger drags the enlarged picture sideways (the scrub waits until it is back at 100 per cent).
        await fingers(page, [
            { type: 'down', id: 3, x: cx, y: cy },
            { type: 'move', id: 3, x: cx + 30, y: cy },
            { type: 'move', id: 3, x: cx + 60, y: cy },
            { type: 'up', id: 3, x: cx + 60, y: cy },
        ])
        expect((await pan(page)).x).toBeGreaterThan(20)
        await expect(page.locator('.pc-scrub')).toHaveCount(0)
        // Back in to just over the fitted size: it settles on 100 per cent.
        await fingers(page, [
            { type: 'down', id: 4, x: cx - 100, y: cy },
            { type: 'down', id: 5, x: cx + 100, y: cy },
            { type: 'move', id: 4, x: cx - 52, y: cy },
            { type: 'move', id: 5, x: cx + 52, y: cy },
            { type: 'up', id: 4, x: cx - 52, y: cy },
            { type: 'up', id: 5, x: cx + 52, y: cy },
        ])
        expect(await scale(page)).toBe(1)
        // Zoomed again, the reset is in More options too, with its figure.
        await fingers(page, [
            { type: 'down', id: 6, x: cx - 50, y: cy },
            { type: 'down', id: 7, x: cx + 50, y: cy },
            { type: 'move', id: 7, x: cx + 100, y: cy },
            { type: 'up', id: 6, x: cx - 50, y: cy },
            { type: 'up', id: 7, x: cx + 100, y: cy },
        ])
        expect(await scale(page)).toBeGreaterThan(1.2)
        await page.locator('.pc-top [data-shot="more"]').click()
        const reset = page.getByRole('menuitem', { name: /Reset zoom/ })
        await expect(reset).toContainText('%')
        await reset.click()
        expect(await scale(page)).toBe(1)
    })

    test('a locked screen does not zoom', async ({ page }) => {
        await openPaused(page)
        await page.locator('.pc-top [data-shot="more"]').click()
        await page.getByRole('menuitem', { name: 'Lock the screen' }).click()
        const box = (await page.locator('.pc-picture').boundingBox())!
        await fingers(page, [
            { type: 'down', id: 1, x: box.width / 2 - 50, y: 200 },
            { type: 'down', id: 2, x: box.width / 2 + 50, y: 200 },
            { type: 'move', id: 2, x: box.width / 2 + 150, y: 200 },
            { type: 'up', id: 1, x: box.width / 2 - 50, y: 200 },
            { type: 'up', id: 2, x: box.width / 2 + 150, y: 200 },
        ])
        expect(await scale(page)).toBe(1)
        await expect(page.locator('.pc-zoom-chip')).toHaveCount(0)
    })
})
