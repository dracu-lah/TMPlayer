// WP-98: the status line (ui/WorkStatus.tsx) over the fake Telegram's line (fake/mtcute.ts: `linkdown`
// reports connecting, `linkupdating` reports updating). "Connecting…" comes after 3 s of that line and
// "Updating…" after a 2 s settle, as a polite `role=status` that never takes focus; the toast does not
// slide when the viewer asked for less motion. The page's clock is the test's (page.clock), the
// worker's own is real, which is why a flag is given 1.2 s to reach the page.
import { expect, test, type Page } from '@playwright/test'
import { fakeCase } from './helpers'

test.skip(({ browserName }) => browserName !== 'chromium', 'the live cases run in Chromium')

/** The layout and the size floor written where the app keeps them before it starts. */
async function prepare(page: Page, layout: string | null) {
    await page.goto('/__e2e/sizes')
    await page.evaluate(
        (layout) =>
            new Promise<void>((resolve, reject) => {
                if (layout) localStorage.setItem('tmplayer-layout', layout)
                const open = indexedDB.open('tmplayer-app')
                open.onupgradeneeded = () => open.result.createObjectStore('kv')
                open.onerror = () => reject(open.error)
                open.onsuccess = () => {
                    const tx = open.result.transaction('kv', 'readwrite')
                    tx.objectStore('kv').put({ sizeMin: 0 }, 'settings')
                    tx.oncomplete = () => (open.result.close(), resolve())
                }
            }),
        layout,
    )
}

const line = (page: Page) => page.locator('.work-status.pill .work-status-line')

async function chats(page: Page, layout: string | null) {
    await page.clock.install()
    await prepare(page, layout)
    await page.goto('/chats')
    await expect(page.getByRole('link', { name: /Test Clips/ }).first()).toBeVisible()
    // The page's clock stands still from here: only runFor moves it, so a 2 s settle is not
    // passed by the real second the worker needs to tell the page the line changed.
    await page.clock.pauseAt(new Date(Date.now() + 1_000))
}

test('Updating shows after a 2 s settle as a polite status, and goes when the line is up', async ({ page, context }) => {
    await chats(page, 'desktop')
    await fakeCase(context, 'linkupdating')
    await page.waitForTimeout(1200)
    await page.clock.runFor(1_500)
    await expect(line(page)).toHaveCount(0)
    await page.clock.runFor(1_000)
    await expect(line(page)).toBeVisible()
    await expect(line(page)).toHaveText('Updating…')
    const region = page.locator('.work-status.pill')
    await expect(region).toHaveAttribute('role', 'status')
    await expect(region).toHaveAttribute('aria-live', 'polite')
    // Not busy: a polite region marked busy holds back what lands in it, and the pill must be read once.
    await expect(region).not.toHaveAttribute('aria-busy', 'true')
    await fakeCase(context)
    await page.waitForTimeout(1200)
    await expect(line(page)).toHaveCount(0)
})

test('Connecting shows after 3 s and gives way to the unreachable banner at 20 s', async ({ page, context }) => {
    await chats(page, 'desktop')
    await fakeCase(context, 'linkdown')
    await page.waitForTimeout(1200)
    await page.clock.runFor(2_000)
    await expect(line(page)).toHaveCount(0)
    await page.clock.runFor(2_000)
    await expect(line(page)).toHaveText('Connecting…')
    await page.clock.runFor(17_000)
    await expect(page.locator('.reach-banner')).toBeVisible()
    await expect(line(page)).toHaveCount(0)
})

test('a device that is offline has the offline line and no status line', async ({ page, context }) => {
    await chats(page, 'desktop')
    await fakeCase(context, 'linkdown')
    await context.setOffline(true)
    await page.waitForTimeout(1200)
    await page.clock.runFor(10_000)
    await expect(page.locator('.offline-line')).toBeVisible()
    await expect(line(page)).toHaveCount(0)
    await context.setOffline(false)
})

test('on the TV skin the line is in the offline line corner, never takes focus, and is at least 14 dp', async ({ page, context }) => {
    await page.setViewportSize({ width: 1920, height: 1080 })
    await chats(page, 'tv')
    await fakeCase(context, 'linkupdating')
    await page.waitForTimeout(1200)
    await page.clock.runFor(2_500)
    await expect(line(page)).toBeVisible()
    for (let i = 0; i < 4; i++) {
        await page.keyboard.press('ArrowDown')
        await page.waitForTimeout(100)
    }
    expect(await page.evaluate(() => !!document.activeElement?.closest('.work-status'))).toBe(false)
    expect(await line(page).evaluate((el) => el.querySelectorAll('button, a, [tabindex]').length)).toBe(0)
    // 1920 x 1080 is 2 px a dp: 14 dp is 28 px.
    expect(await line(page).evaluate((el) => parseFloat(getComputedStyle(el).fontSize))).toBeGreaterThanOrEqual(28)
    const box = (await line(page).boundingBox())!
    expect(box.y + box.height).toBeLessThanOrEqual(1080 - 27 * 2 + 1)
})

for (const form of [
    { name: 'phone', layout: null, width: 390, height: 844 },
    { name: 'desktop', layout: 'desktop', width: 1280, height: 800 },
    { name: 'tv', layout: 'tv', width: 1920, height: 1080 },
] as const) {
    test(`a toast stands above the pill and never covers it (${form.name})`, async ({ page, context }) => {
        await page.setViewportSize({ width: form.width, height: form.height })
        await chats(page, form.layout)
        await fakeCase(context, 'linkupdating')
        await page.waitForTimeout(1200)
        await page.clock.runFor(2_500)
        await expect(line(page)).toBeVisible()
        // The app's own toast, from the module the page loaded.
        await page.evaluate(async () => {
            const path: string = '/src/ui/Toast.tsx'
            const toast = (await import(/* @vite-ignore */ path)) as { showToast: (t: { text: string }) => void }
            toast.showToast({ text: 'Trying again' })
        })
        const toast = page.locator('.toast')
        await expect(toast).toBeVisible()
        const a = (await toast.boundingBox())!
        const b = (await line(page).boundingBox())!
        expect(a.y + a.height, 'the toast ends above the pill').toBeLessThanOrEqual(b.y)
        // The pill goes: the toast comes back down to its own place.
        await fakeCase(context)
        await page.waitForTimeout(1200)
        await expect(line(page)).toHaveCount(0)
        const down = (await toast.boundingBox())!
        expect(down.y).toBeGreaterThan(a.y)
    })
}

for (const reduce of [true, false]) {
    test(`a toast ${reduce ? 'has no slide under reduced motion' : 'slides in otherwise'}`, async ({ page, context }) => {
        await page.emulateMedia({ reducedMotion: reduce ? 'reduce' : 'no-preference' })
        await chats(page, 'desktop')
        await fakeCase(context, 'linkdown')
        await page.waitForTimeout(1200)
        await page.clock.runFor(20_500)
        await page.locator('.reach-banner').getByRole('button', { name: 'Retry' }).click()
        const toast = page.locator('.toast')
        await expect(toast).toContainText('Trying again')
        const motion = await toast.evaluate((el) => {
            const s = getComputedStyle(el)
            return { animation: s.animationName, transition: s.transitionDuration }
        })
        if (reduce) {
            expect(motion.animation).toBe('none')
            // The default transition is "all 0s": nothing moves over time.
            expect(motion.transition.split(',').every((d) => parseFloat(d) === 0)).toBe(true)
        } else {
            expect(motion.animation).toBe('toast-in')
        }
    })
}
