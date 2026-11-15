// The canvas engine (player/libavcanvas.ts): what plays a non-H.264 picture on a TV, whose Chromium has
// no WebCodecs. The page is opened with the encoders and decoders taken away, as a TV's is, so
// the libav.js engine that re-encodes for the Media Source cannot run and movi cannot start: the file
// goes to libav.js in a worker and a canvas. Clips are the host ffmpeg's (make-media.sh).
import { expect, test, type Page } from '@playwright/test'
import { fakeCase, noSizeFloor, openCard, openClips, playsPast } from './helpers'

/** What a TV 2020 does not have (Chromium 68 and 79): the whole of WebCodecs. */
async function asATv(page: Page) {
    await page.addInitScript(() => {
        for (const name of ['VideoEncoder', 'VideoDecoder', 'AudioEncoder', 'AudioDecoder', 'ImageDecoder']) delete (window as unknown as Record<string, unknown>)[name]
    })
}

type Stats = { drawing: string; clock: number; shown: number | null; offset: number | null; running: boolean; queued: number; queuedBytes: number; maxFrames: number; dropped: number; underruns: number; decodeFps: number; sources: number; audio: string; contextClock: boolean }
const stats = (page: Page) => page.evaluate(() => (window as unknown as { __canvas?: { stats(): unknown } }).__canvas?.stats() as Stats | undefined)

/** How many distinct colours the canvas shows (a picture, not a blank or a single fill), read from its pixels. */
const colours = (page: Page) =>
    page.evaluate(() => {
        const c = document.querySelector('canvas.sw-canvas') as HTMLCanvasElement | null
        if (!c || !c.width) return 0
        const probe = document.createElement('canvas')
        probe.width = 64
        probe.height = 36
        const ctx = probe.getContext('2d')!
        ctx.drawImage(c, 0, 0, 64, 36)
        const d = ctx.getImageData(0, 0, 64, 36).data
        const seen = new Set<number>()
        for (let i = 0; i < d.length; i += 4) seen.add(((d[i] >> 4) << 8) | ((d[i + 1] >> 4) << 4) | (d[i + 2] >> 4))
        return seen.size
    })

/** A fingerprint of the canvas, to tell one picture from the next. */
const fingerprint = (page: Page) =>
    page.evaluate(() => {
        const c = document.querySelector('canvas.sw-canvas') as HTMLCanvasElement
        const probe = document.createElement('canvas')
        probe.width = 32
        probe.height = 18
        const ctx = probe.getContext('2d')!
        ctx.drawImage(c, 0, 0, 32, 18)
        return Array.from(ctx.getImageData(0, 0, 32, 18).data).join(',')
    })

const paused = (page: Page) => page.locator('video').first().evaluate((v: HTMLVideoElement) => v.paused)
const time = (page: Page) => page.locator('video').first().evaluate((v: HTMLVideoElement) => v.currentTime)

test.describe('clips chat, no WebCodecs', () => {
    test.beforeEach(async ({ page }) => {
        await asATv(page)
        await noSizeFloor(page)
        await openClips(page)
    })

    for (const name of ['Old Clip', 'Xvid Clip', 'Divx Clip', 'Windows Clip']) {
        test(`${name} plays on a canvas with its sound as the clock`, async ({ page }) => {
            await openCard(page, name)
            await playsPast(page, 1.2)
            await expect(page.locator('canvas.sw-canvas')).toHaveCount(1)
            await expect(page.locator('video.sw-active')).toHaveCount(1)
            await expect(page.getByText('This video needs the TMPlayer app')).toHaveCount(0)
            expect(await colours(page)).toBeGreaterThan(8)
            const s = (await stats(page))!
            expect(s.running).toBe(true)
            // The sound is the clock.
            expect(s.contextClock).toBe(true)
            expect(s.audio).toBe('running')
            expect(s.sources).toBeGreaterThan(0)
            // The picture is within 80 ms of the clock (a 25 fps frame is 40 ms).
            expect(Math.abs(s.offset!)).toBeLessThan(0.08)
            // A small ring, never a pile of frames.
            expect(s.queued).toBeLessThanOrEqual(s.maxFrames)
            expect(s.queuedBytes).toBeLessThan(12 * 2 ** 20)
        })
    }

    test('the picture follows the sound within 80 ms for several seconds, and the clock keeps real time', async ({ page }) => {
        await openCard(page, 'Xvid Clip')
        await playsPast(page, 0.4)
        // Sampled inside the page, every 150 ms for 1.8 s: a round trip from the test per sample stretches on a busy machine
        // until the 4 s clip has ended, and a clock that stopped at the end is not a clock that lost time.
        const run = await page.evaluate(async () => {
            const canvas = (window as unknown as { __canvas: { stats(): { clock: number; offset: number | null; running: boolean; underruns: number } } }).__canvas
            const first = { at: performance.now() / 1000, clock: canvas.stats().clock }
            const offsets: number[] = []
            for (let i = 0; i < 12; i++) {
                await new Promise((r) => setTimeout(r, 150))
                const s = canvas.stats()
                if (s.offset !== null && s.running) offsets.push(Math.abs(s.offset))
            }
            const s = canvas.stats()
            return { offsets, drift: s.clock - first.clock - (performance.now() / 1000 - first.at), underruns: s.underruns }
        })
        const { offsets } = run
        // 1.8 s later by the page's own clock, the sound's clock says about the same.
        expect(Math.abs(run.drift)).toBeLessThan(0.35)
        expect(offsets.length).toBeGreaterThan(5)
        expect(Math.max(...offsets)).toBeLessThan(0.08)
        // A 4 s clip: few frames dropped, no waiting for the decoder.
        expect(run.underruns).toBe(0)
    })

    test('pause holds the picture and the clock, play goes on', async ({ page }) => {
        await openCard(page, 'Old Clip')
        await playsPast(page, 0.8)
        await page.locator('video').first().evaluate((v: HTMLVideoElement) => v.pause())
        await expect.poll(() => paused(page)).toBe(true)
        const at = await time(page)
        const shot = await fingerprint(page)
        await page.waitForTimeout(900)
        expect(await time(page)).toBe(at)
        expect(await fingerprint(page)).toBe(shot)
        // Nothing keeps turning while paused: no loop of its own.
        expect((await stats(page))!.running).toBe(false)
        await page.locator('video').first().evaluate((v: HTMLVideoElement) => v.play())
        await playsPast(page, at + 0.5)
        expect(await fingerprint(page)).not.toBe(shot)
    })

    test('seeking flushes and restarts at the target, forward and back to the start, and plays on', async ({ page }) => {
        await openCard(page, 'Divx Clip')
        await playsPast(page, 0.5)
        const at = await page.locator('video').first().evaluate(async (v: HTMLVideoElement) => {
            v.currentTime = 2.5
            await new Promise((r) => v.addEventListener('seeked', r, { once: true }))
            return v.currentTime
        })
        expect(at).toBeGreaterThan(2.3)
        expect(at).toBeLessThan(2.8)
        await playsPast(page, 3)
        expect(await colours(page)).toBeGreaterThan(8)
        await page.locator('video').first().evaluate(async (v: HTMLVideoElement) => {
            v.currentTime = 0
            await new Promise((r) => v.addEventListener('seeked', r, { once: true }))
        })
        await playsPast(page, 0.5)
        // Seeking while paused shows the picture there and stays paused.
        await page.locator('video').first().evaluate((v: HTMLVideoElement) => v.pause())
        const before = await fingerprint(page)
        const to = await page.locator('video').first().evaluate(async (v: HTMLVideoElement) => {
            v.currentTime = 3
            await new Promise((r) => v.addEventListener('seeked', r, { once: true }))
            return v.currentTime
        })
        expect(to).toBeGreaterThan(2.8)
        expect(await paused(page)).toBe(true)
        expect(await fingerprint(page)).not.toBe(before)
    })

    test('a run of seeks leaves it playing at the last one', async ({ page }) => {
        await openCard(page, 'Xvid Clip')
        await playsPast(page, 0.5)
        await page.locator('video').first().evaluate(async (v: HTMLVideoElement) => {
            for (const t of [1, 1.5, 2, 0.5, 2.6]) {
                v.currentTime = t
                await new Promise((r) => setTimeout(r, 30))
            }
            await new Promise((r) => v.addEventListener('seeked', r, { once: true }))
        })
        await expect.poll(() => time(page)).toBeGreaterThan(2.6)
    })

    test('it plays to the end and says so, and Play starts it again', async ({ page }) => {
        await openCard(page, 'Old Clip')
        await page.locator('video').first().evaluate((v: HTMLVideoElement) => new Promise((r) => (v.ended ? r(0) : v.addEventListener('ended', r, { once: true }))))
        expect(await paused(page)).toBe(true)
        expect(await time(page)).toBeGreaterThan(3.4)
        await page.locator('video').first().evaluate((v: HTMLVideoElement) => v.play())
        await expect.poll(() => time(page), { timeout: 8000 }).toBeLessThan(2)
    })

    test('volume and mute go to the sound, the speed to the clock', async ({ page }) => {
        await openCard(page, 'Old Clip')
        await playsPast(page, 0.5)
        const set = await page.locator('video').first().evaluate((v: HTMLVideoElement) => {
            v.volume = 0.3
            v.muted = true
            return [v.volume, v.muted]
        })
        expect(set).toEqual([0.3, true])
        await page.locator('video').first().evaluate((v: HTMLVideoElement) => (v.playbackRate = 1.5))
        const a = await time(page)
        await page.waitForTimeout(1000)
        const b = await time(page)
        expect(b - a).toBeGreaterThan(1.0)
    })

    test('the 2D canvas draws the same picture where there is no WebGL', async ({ page }) => {
        await page.goto('/')
        await openClips(page)
        await openCard(page, 'Old Clip')
        const url = new URL(page.url())
        await page.goto(`${url.pathname}?canvas=2d`)
        await playsPast(page, 0.8)
        expect((await stats(page))!.drawing).toBe('canvas2d')
        expect(await colours(page)).toBeGreaterThan(8)
    })

    test('a picture beyond what this device decodes says too heavy, not a stutter', async ({ page }) => {
        await openCard(page, 'Old Clip')
        const url = new URL(page.url())
        await page.goto(`${url.pathname}?canvascap=1000`)
        await expect(page.getByText('This video needs the TMPlayer app')).toBeVisible()
        await expect(page.locator('canvas.sw-canvas')).toHaveCount(0)
    })

    test('Odd Picture Clip still ends at the needs-the-app page', async ({ page }) => {
        await openCard(page, 'Odd Picture Clip')
        await expect(page.getByText('This video needs the TMPlayer app')).toBeVisible()
        // A picture it cannot draw (RGB555) ends the run: nothing is left showing or playing under the page.
        await expect(page.locator('canvas.sw-canvas:visible')).toHaveCount(0)
        expect((await stats(page))!.sources).toBe(0)
    })

    test('closing the player takes the canvas away and gives the element back', async ({ page }) => {
        await openCard(page, 'Old Clip')
        await playsPast(page, 0.5)
        await page.goBack()
        await expect(page.locator('canvas.sw-canvas')).toHaveCount(0)
    })
})

test.describe('the old formats chat, no WebCodecs', () => {
    test.beforeEach(async ({ page, context }) => {
        await asATv(page)
        await fakeCase(context, 'oldformats')
        await noSizeFloor(page)
        await page.goto('/chat/-1008')
        await expect(page.locator('.card').first()).toBeVisible()
    })

    for (const name of ['Flash Clip', 'Real Clip', 'Dvd Clip', 'Broadcast Clip', 'Part Two Clip', 'Theora Clip']) {
        test(`${name} plays on a canvas`, async ({ page }) => {
            await openCard(page, name)
            await playsPast(page, 1)
            await expect(page.locator('canvas.sw-canvas')).toHaveCount(1)
            expect(await colours(page)).toBeGreaterThan(8)
            await expect(page.getByText('This video needs the TMPlayer app')).toHaveCount(0)
        })
    }

    for (const name of ['Dvd Clip', 'Flash Clip', 'Broadcast Clip']) {
        test(`seeking in ${name} lands near where it was told`, async ({ page }) => {
            await openCard(page, name)
            await playsPast(page, 0.5)
            const at = await page.locator('video').first().evaluate(async (v: HTMLVideoElement) => {
                v.currentTime = 3
                await new Promise((r) => v.addEventListener('seeked', r, { once: true }))
                return v.currentTime
            })
            expect(at).toBeGreaterThan(2.4)
            await playsPast(page, 3.1)
        })
    }
})
