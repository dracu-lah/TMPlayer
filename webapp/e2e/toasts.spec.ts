// UX web toasts: a message at the foot stands ABOVE the floating bar, 12 px clear of its top edge and
// centred, never behind it (the bar's height is measured, not a constant); on a phone it is the width
// less 16 px a side up to 560, on a desktop it fits its words between 280 and 480; four kinds (success,
// info, warning, error) are a tinted card with a round 32 px chip and an icon that moves once, the check
// drawing in, the error shaking, the warning pulsing, the note scaling in; none of it moves under
// reduced motion; errors are announced at once, the rest politely; it never takes focus and a press
// dismisses it. SHOTS=1 also writes pictures of the four kinds to design/toasts-web/ (index.html shows them).
import { mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { expect, test, type Page } from '@playwright/test'

const KINDS = ['success', 'info', 'warning', 'error'] as const
type Kind = (typeof KINDS)[number]

const TEXT: Record<Kind, string> = {
    success: 'Link copied',
    info: 'Trying again',
    warning: 'Up to 5 accounts fit on this device.',
    error: 'Could not save the file. Try again.',
}

/** Shows a toast through the page's own module, as a call site does. */
async function show(page: Page, kind: Kind | undefined, text = 'Trying again', action = false) {
    await page.evaluate(
        async ([k, text, action]) => {
            const path: string = '/src/ui/Toast.tsx'
            const mod = (await import(/* @vite-ignore */ path)) as { showToast: (t: { text: string; kind?: string; action?: { label: string; run: () => void } }) => void }
            mod.showToast({ text, kind: k ?? undefined, action: action ? { label: 'Undo', run: () => {} } : undefined })
        },
        [kind ?? null, text, action] as const,
    )
}

const toast = (page: Page) => page.locator('.toast')

const FORMS = [
    { name: 'phone 390', width: 390, height: 844, phone: true },
    { name: 'phone 844 landscape', width: 844, height: 390, phone: true },
    { name: 'desktop 1280', width: 1280, height: 800, phone: false },
]

for (const f of FORMS) {
    test.describe(f.name, () => {
        test.use({ viewport: { width: f.width, height: f.height }, screen: { width: f.width, height: f.height }, hasTouch: f.phone, isMobile: f.phone })

        test('stands 12 px above the bar, centred, at the right width', async ({ page }) => {
            if (!f.phone) await page.addInitScript(() => localStorage.setItem('tmplayer-layout', 'desktop'))
            await page.goto('/')
            await expect(page.locator('#main-nav .nav-cells')).toBeVisible()
            await show(page, 'success', 'Up to date')
            await expect(toast(page)).toBeVisible()
            await page.waitForTimeout(400)
            const m = await page.evaluate(() => {
                const t = document.querySelector('.toast')!.getBoundingClientRect()
                const b = document.querySelector('#main-nav .nav-cells')!.getBoundingClientRect()
                return { tBottom: t.bottom, tLeft: t.left, tRight: t.right, tWidth: t.width, bTop: b.top, win: document.documentElement.clientWidth }
            })
            expect(Math.abs(m.bTop - m.tBottom - 12), 'the toast ends 12 px over the bar').toBeLessThanOrEqual(1)
            expect(Math.abs((m.tLeft + m.tRight) / 2 - m.win / 2), 'centred').toBeLessThanOrEqual(1)
            if (f.phone) {
                expect(m.tWidth).toBeLessThanOrEqual(560)
                expect(Math.abs(m.tWidth - Math.min(560, m.win - 32))).toBeLessThanOrEqual(1)
            } else {
                expect(m.tWidth).toBeGreaterThanOrEqual(279)
                expect(m.tWidth).toBeLessThanOrEqual(481)
            }
        })

        test('a larger text size moves the bar and the toast follows it', async ({ page }) => {
            await page.addInitScript(() => {
                const style = document.createElement('style')
                style.textContent = 'html { font-size: 150% !important; }'
                document.addEventListener('DOMContentLoaded', () => document.head.append(style))
            })
            if (!f.phone) await page.addInitScript(() => localStorage.setItem('tmplayer-layout', 'desktop'))
            await page.goto('/')
            // The bar first: a toast shown before it is drawn stands at the foot until its next look (twice a second), and
            // a slow runner drew the bar after the toast's 700 ms here (74 px apart, run 45691829607).
            await expect(page.locator('#main-nav .nav-cells')).toBeVisible()
            await show(page, 'info')
            await expect(toast(page)).toBeVisible()
            const gap = () => page.evaluate(() => document.querySelector('#main-nav .nav-cells')!.getBoundingClientRect().top - document.querySelector('.toast')!.getBoundingClientRect().bottom)
            await expect.poll(async () => Math.abs((await gap()) - 12), { timeout: 3000 }).toBeLessThanOrEqual(1)
        })
    })
}

test.describe('the kinds', () => {
    test.use({ viewport: { width: 390, height: 844 }, screen: { width: 390, height: 844 }, hasTouch: true, isMobile: true })

    for (const kind of KINDS) {
        test(`${kind}: a tinted card, a 32 px chip and its icon, the right role, one move of the icon`, async ({ page }) => {
            await page.goto('/')
            await show(page, kind, TEXT[kind], true)
            const card = toast(page)
            await expect(card).toBeVisible()
            await expect(card).toHaveAttribute('data-kind', kind)
            await expect(card).toHaveAttribute('role', kind === 'error' ? 'alert' : 'status')
            await expect(card).toHaveAttribute('aria-live', kind === 'error' ? 'assertive' : 'polite')
            await expect(card).toHaveText(`${TEXT[kind]}Undo`)
            const chip = (await card.locator('.toast-chip').boundingBox())!
            expect(Math.round(chip.width)).toBe(32)
            expect(Math.round(chip.height)).toBe(32)
            await expect(card.locator('.toast-chip svg')).toHaveCount(1)
            // No shadow, a hairline in the kind's colour, a wash of it on the card.
            const look = await card.evaluate((el) => {
                const s = getComputedStyle(el)
                return { shadow: s.boxShadow, border: s.borderTopColor, bg: s.backgroundColor, bw: s.borderTopWidth }
            })
            expect(look.shadow).toBe('none')
            expect(look.bw).toBe('1px')
            expect(look.border).not.toBe('rgba(0, 0, 0, 0)')
            // The icon alone moves.
            const move = await card.evaluate((el) => {
                const icon = el.querySelector<SVGElement>('.toast-icon')!
                const draw = el.querySelector<SVGElement>('.toast-draw')
                return { icon: getComputedStyle(icon).animationName, draw: draw ? getComputedStyle(draw).animationName : null, chip: getComputedStyle(el.querySelector('.toast-chip')!).animationName }
            })
            expect(move.chip).toBe('none')
            expect(move.icon).toBe({ success: 'none', info: 'toast-pop', warning: 'toast-pulse', error: 'toast-shake' }[kind])
            expect(move.draw).toBe(kind === 'success' ? 'toast-draw' : null)
            // It never took the keyboard and is no focus stop of its own (the Undo button stays reachable).
            expect(await page.evaluate(() => !!document.activeElement?.closest('.toast'))).toBe(false)
            expect(await card.evaluate((el) => el.tabIndex)).toBe(-1)
        })
    }

    test('the default kind is info, a long message stops at two lines, a press dismisses it, a new one replaces it', async ({ page }) => {
        await page.goto('/')
        await show(page, undefined, 'x '.repeat(200))
        await expect(toast(page)).toHaveAttribute('data-kind', 'info')
        const lines = await toast(page).locator('p').evaluate((el) => Math.round(el.getBoundingClientRect().height / parseFloat(getComputedStyle(el).lineHeight)))
        expect(lines).toBeLessThanOrEqual(2)
        await show(page, 'error', 'Second')
        await expect(toast(page)).toHaveCount(1)
        await expect(toast(page)).toHaveAttribute('data-kind', 'error')
        await toast(page).locator('p').click()
        await expect(toast(page)).toHaveCount(0)
    })

    test('a swipe sideways dismisses it, and the Undo button runs and dismisses', async ({ page }) => {
        await page.goto('/')
        await show(page, 'info', 'Removed', true)
        const box = (await toast(page).boundingBox())!
        await page.mouse.move(box.x + 100, box.y + box.height / 2)
        await page.mouse.down()
        await page.mouse.move(box.x + 260, box.y + box.height / 2, { steps: 5 })
        await page.mouse.up()
        await expect(toast(page)).toHaveCount(0)
        await show(page, 'success', 'Removed', true)
        await toast(page).getByRole('button', { name: 'Undo' }).click()
        await expect(toast(page)).toHaveCount(0)
    })

    test('reduced motion: the card and the icon do not move', async ({ page }) => {
        await page.emulateMedia({ reducedMotion: 'reduce' })
        await page.goto('/')
        for (const kind of KINDS) {
            await show(page, kind, TEXT[kind])
            await expect(toast(page)).toHaveAttribute('data-kind', kind)
            const names = await toast(page).evaluate((el) => {
                const icon = el.querySelector<SVGElement>('.toast-icon')!
                const draw = el.querySelector<SVGElement>('.toast-draw')
                return [getComputedStyle(el).animationName, getComputedStyle(icon).animationName, draw ? getComputedStyle(draw).animationName : 'none']
            })
            expect(names, kind).toEqual(['none', 'none', 'none'])
        }
        // The check is whole, not waiting to be drawn.
        await show(page, 'success', 'Done')
        expect(await toast(page).locator('.toast-draw').evaluate((el) => getComputedStyle(el).strokeDashoffset)).toBe('0px')
    })
})

test.describe('the TV', () => {
    test.use({ viewport: { width: 1920, height: 1080 } })

    test('inside the safe area, text at least 16 px, not a focus stop', async ({ page }) => {
        await page.addInitScript(() => localStorage.setItem('tmplayer-layout', 'tv'))
        await page.goto('/')
        await expect(page.locator('.tv-bar')).toBeVisible()
        await show(page, 'success', 'Link copied')
        await expect(toast(page)).toBeVisible()
        // The entry animation moves the card sideways while it runs (WebKit is slower than Chromium): measure it at rest.
        await page.evaluate(() => Promise.all(document.querySelector('.toast')!.getAnimations().map((a) => a.finished.catch(() => null))))
        const m = await page.evaluate(() => {
            const t = document.querySelector('.toast')!.getBoundingClientRect()
            return { bottom: t.bottom, left: t.left, right: t.right, font: parseFloat(getComputedStyle(document.querySelector('.toast p')!).fontSize), h: innerHeight, w: innerWidth }
        })
        expect(m.font).toBeGreaterThanOrEqual(16)
        expect(m.h - m.bottom).toBeGreaterThanOrEqual(m.h * 0.025)
        expect(m.left).toBeGreaterThanOrEqual(m.w * 0.04)
        expect(m.w - m.right).toBeGreaterThanOrEqual(m.w * 0.04)
        expect(await page.evaluate(() => !!document.activeElement?.closest('.toast'))).toBe(false)
    })
})

test.describe('pictures', () => {
    test.skip(!process.env.SHOTS, 'SHOTS=1 writes the pictures')
    const DIR = fileURLToPath(new URL('../../design/toasts-web/', import.meta.url))
    const FORM = [
        { name: 'phone', width: 390, height: 844, phone: true },
        { name: 'desktop', width: 1280, height: 800, phone: false },
    ]
    for (const f of FORM) {
        for (const kind of KINDS) {
            test(`${f.name} ${kind}`, async ({ browser }) => {
                mkdirSync(DIR, { recursive: true })
                const context = await browser.newContext({ baseURL: test.info().project.use.baseURL, viewport: { width: f.width, height: f.height }, screen: { width: f.width, height: f.height }, hasTouch: f.phone, isMobile: f.phone, deviceScaleFactor: 2 })
                const page = await context.newPage()
                if (!f.phone) await page.addInitScript(() => localStorage.setItem('tmplayer-layout', 'desktop'))
                await page.goto('/')
                await expect(page.locator('#main-nav .nav-cells')).toBeVisible()
                await show(page, kind, TEXT[kind], kind === 'success')
                await expect(toast(page)).toBeVisible()
                await page.waitForTimeout(700)
                await page.screenshot({ path: `${DIR}${f.name}-${kind}.png` })
                await context.close()
            })
        }
    }
})
