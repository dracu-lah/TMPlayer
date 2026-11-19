// WB18: Report stutter in Playback details, Copy details with the scrubbed logs, and the crash card's
// way out, on a phone, a desktop and the TV skin (Chromium: the counters and the clipboard are its own).
// Nothing is sent anywhere in these flows: the test fails on any request that is not the app's own.
import { mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { expect, test, type Page } from '@playwright/test'
import { fakeCase, noSizeFloor, openCard, openClips, playsPast } from './helpers'

const SHOTS = fileURLToPath(new URL('../../design/web-gaps/WB18/', import.meta.url))

const FORMS = [
    { name: 'phone-390', width: 390, height: 844, layout: null, touch: true },
    { name: 'desktop-1280', width: 1280, height: 800, layout: 'desktop', touch: false },
    { name: 'tv-1920', width: 1920, height: 1080, layout: 'tv', touch: false },
] as const

const shot = async (page: Page, name: string) => {
    mkdirSync(SHOTS, { recursive: true })
    await page.screenshot({ path: `${SHOTS}${name}.png` })
}

async function prepare(page: Page, layout: string | null) {
    await noSizeFloor(page)
    if (layout) await page.evaluate((l) => localStorage.setItem('tmplayer-layout', l), layout)
}

/** The player's Playback details sheet: a menu line on a phone, the I key on a desktop and the TV. */
async function openDetails(page: Page, phone: boolean) {
    if (phone) {
        await page.mouse.move(200, 300)
        await page.locator('[data-shot=more]').first().click()
        await page.getByRole('menuitem', { name: /Playback details/ }).click()
    } else {
        await page.locator('.pc').focus()
        await page.keyboard.press('i')
    }
    await expect(page.locator('.pc-details')).toBeVisible()
}

for (const form of FORMS) {
    test.describe(`on ${form.name}`, () => {
        test.skip(({ browserName }) => browserName !== 'chromium', 'the counters and the clipboard are Chromium here')
        test.use({ viewport: { width: form.width, height: form.height }, hasTouch: form.touch, isMobile: form.touch })

        test.beforeEach(async ({ context, page }) => {
            await fakeCase(context)
            await context.grantPermissions(['clipboard-read', 'clipboard-write']).catch(() => {})
            // Nothing leaves the page: any request to another origin than the app's own fails the test.
            page.on('request', (r) => {
                const u = new URL(r.url())
                if (!['127.0.0.1', 'localhost'].includes(u.hostname) && !/^(data|blob|about):/.test(r.url())) throw new Error(`left the page: ${r.url()}`)
            })
        })

        test('Report stutter marks the moment, a second press says so, Copy details gives the scrubbed logs, all by keys too', async ({ page }) => {
            await prepare(page, form.layout)
            await openClips(page)
            await openCard(page, 'Native Clip')
            await playsPast(page, 0.5)
            await openDetails(page, form.touch)
            const sheet = page.locator('.pc-details')
            const mark = sheet.getByRole('button', { name: 'Report stutter' })
            const copy = sheet.getByRole('button', { name: 'Copy details' })
            await expect(mark).toBeVisible()
            await expect(sheet).toContainText('Marks this moment, so Copy details includes the last minute')
            await expect(sheet).toContainText(/Logs, \d+ KB, with names, numbers and file names taken out/)
            if (!form.touch) {
                // The keyboard and the remote: the sheet opens on its close button, Down reaches the buttons, Enter presses the one it is on.
                await expect(sheet.getByRole('button', { name: 'Close' })).toBeFocused()
                // The TV's remote walks with the arrows; a keyboard on a desktop with Tab.
                if (form.layout === 'tv') {
                    // Down from the close button reaches the nearest button below it, which is Copy details; Left is Report stutter.
                    await page.keyboard.press('ArrowDown')
                    await expect(copy).toBeFocused()
                    await page.keyboard.press('ArrowLeft')
                } else {
                    await page.keyboard.press('Tab')
                }
                await expect(mark).toBeFocused()
                await page.keyboard.press('Enter')
            } else {
                await mark.click()
            }
            await expect(sheet.getByRole('status')).toHaveText('Stutter marked. Copy details will include the last minute.')
            await shot(page, `${form.name}-details`)
            if (form.touch) await mark.click()
            else await page.keyboard.press('Enter')
            await expect(sheet.getByRole('status')).toHaveText('Already marked a moment ago')
            if (!form.touch) {
                await page.keyboard.press(form.layout === 'tv' ? 'ArrowRight' : 'Tab')
                await expect(copy).toBeFocused()
                await page.keyboard.press('Enter')
            } else {
                await copy.click()
            }
            await expect(sheet.getByRole('status')).toHaveText('Details copied')
            const text = await page.evaluate(() => navigator.clipboard.readText())
            expect(text).toMatch(/^TMPlayer logs\nApp version: web\nPlatform: Web\nDevice: .+ on .+\n/)
            expect(text).toContain('== Moments marked as stuttering ==')
            expect(text).toMatch(/Playback trace: 1 moment\(s\) the viewer marked as stuttering/)
            expect(text).toMatch(/engine=native speed=1x/)
            expect(text).toMatch(/Engine in use: native\nFrame counters: yes/)
            expect(text).not.toMatch(/Native Clip|Test Clips|\.mp4|\.mkv/i)
            // Only fixed words and the apps' buckets in a reading: no raw count.
            for (const line of text.split('\n').filter((l) => l.startsWith('  +'))) expect(line).toMatch(/^  \+\d+s dropped=(unknown|0|1|2-4|5-9|10-19|20-49|50\+) of=(unknown|0|1|2-4|5-9|10-19|20-49|50\+) pace=(unknown|under-50|50-89|on-time|over-110) engine=native speed=1x$/)
            // Escape closes the sheet and the video plays on.
            if (!form.touch) {
                await page.keyboard.press('Escape')
                await expect(sheet).toHaveCount(0)
            }
        })

        test('a browser with no frame counters does not offer Report stutter, but Copy details stays', async ({ page }) => {
            await page.addInitScript(() => {
                delete (HTMLVideoElement.prototype as { getVideoPlaybackQuality?: unknown }).getVideoPlaybackQuality
            })
            await prepare(page, form.layout)
            await openClips(page)
            await openCard(page, 'Native Clip')
            await playsPast(page, 0.5)
            await openDetails(page, form.touch)
            const sheet = page.locator('.pc-details')
            await expect(sheet.getByRole('button', { name: 'Copy details' })).toBeVisible()
            await expect(sheet.getByRole('button', { name: 'Report stutter' })).toHaveCount(0)
            await expect(sheet).not.toContainText('Marks this moment')
        })

        test('the watch runs a 2 s timer only while the video plays', async ({ page }) => {
            await page.addInitScript(() => {
                const live = new Set<unknown>()
                const set = window.setInterval.bind(window)
                const clear = window.clearInterval.bind(window)
                window.setInterval = ((fn: TimerHandler, ms?: number, ...rest: unknown[]) => {
                    const id = set(fn, ms, ...rest)
                    if (ms === 2000) live.add(id)
                    return id
                }) as typeof window.setInterval
                window.clearInterval = ((id?: number) => {
                    live.delete(id)
                    clear(id)
                }) as typeof window.clearInterval
                ;(window as unknown as { __twoSecond: () => number }).__twoSecond = () => live.size
            })
            await prepare(page, form.layout)
            await openClips(page)
            await openCard(page, 'Native Clip')
            await playsPast(page, 0.5)
            const count = () => page.evaluate(() => (window as unknown as { __twoSecond: () => number }).__twoSecond())
            await expect.poll(count).toBe(1)
            await page.locator('video').first().evaluate((v: HTMLVideoElement) => v.pause())
            await expect.poll(count).toBe(0)
            await page.locator('video').first().evaluate((v: HTMLVideoElement) => v.play())
            await expect.poll(count).toBe(1)
        })
    })
}

test.describe('the crash card', () => {
    test.skip(({ browserName }) => browserName !== 'chromium', 'Chromium')
    test.use({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true })

    test('shows once, never restarts the page by itself, copies scrubbed details, and the next run remembers what stopped it', async ({ page, context }) => {
        await fakeCase(context)
        await context.grantPermissions(['clipboard-read', 'clipboard-write']).catch(() => {})
        await noSizeFloor(page)
        await page.goto('/chats')
        let navigations = 0
        page.on('framenavigated', (f) => f === page.mainFrame() && navigations++)
        await page.evaluate(() => {
            setTimeout(() => {
                throw new TypeError('x is not a function for me@example.org in Show.S01E02.mkv')
            })
        })
        const card = page.locator('.crash-card')
        await expect(card).toBeVisible()
        await expect(card.getByRole('button')).toHaveCount(3)
        // A second failure while it is up adds nothing, and nothing reloads on its own.
        await page.evaluate(() => setTimeout(() => Promise.reject(new TypeError('again'))))
        await page.waitForTimeout(1500)
        await expect(page.locator('.crash-card')).toHaveCount(1)
        expect(navigations).toBe(0)
        await shot(page, 'crash-card')
        await card.getByRole('button', { name: 'Copy details' }).click()
        await expect(card.getByRole('button', { name: 'Details copied' })).toBeVisible()
        const text = await page.evaluate(() => navigator.clipboard.readText())
        expect(text).toMatch(/^TMPlayer logs\n/)
        expect(text).toContain('== Page log, kept in this tab only ==')
        expect(text).toMatch(/TypeError: x is not a function for \[email\] in \[file\]/)
        expect(text).not.toMatch(/example\.org|\.mkv/)
        // Close puts it away; the page is still the same page.
        await card.getByRole('button', { name: 'Close' }).click()
        await expect(card).toHaveCount(0)
        expect(navigations).toBe(0)
        // The next start keeps it for the report: the earlier run's crash is in what Copy details gives.
        await page.reload()
        await page.addInitScript(() => localStorage.setItem('tmplayer-feedback-off', '1'))
        await page.goto('/settings/about')
        await page.getByRole('button', { name: /Copy details/ }).click()
        await expect(page.getByText('Details copied')).toBeVisible()
        const next = await page.evaluate(() => navigator.clipboard.readText())
        expect(next).toContain('== Earlier run ==')
        expect(next).toMatch(/The page stopped on \d{4}-\d\d-\d\d \d\d:\d\d: TypeError: again|TypeError: x is not a function for \[email\] in \[file\]/)
    })

    test('the card is keyboard whole: Left and Right walk its three buttons, Escape puts it away', async ({ page, context }) => {
        await fakeCase(context)
        await noSizeFloor(page)
        await page.goto('/chats')
        await page.evaluate(() => setTimeout(() => { throw new RangeError('keys') }))
        const card = page.locator('.crash-card')
        await expect(card.getByRole('button', { name: 'Reload' })).toBeFocused()
        await page.keyboard.press('ArrowRight')
        await expect(card.getByRole('button', { name: 'Copy details' })).toBeFocused()
        await page.keyboard.press('ArrowRight')
        await expect(card.getByRole('button', { name: 'Close' })).toBeFocused()
        await page.keyboard.press('ArrowRight')
        await expect(card.getByRole('button', { name: 'Reload' })).toBeFocused()
        await page.keyboard.press('Escape')
        await expect(card).toHaveCount(0)
    })
})
