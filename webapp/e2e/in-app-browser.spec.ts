// WP-58: a link opened inside another app's web view says "Open this in Safari" before sign in, and the
// Home Screen steps follow the iOS version (iOS 26: More, Share, Add to Home Screen, Open as Web App on).
import { expect, test } from '@playwright/test'
import { fakeCase } from './helpers'

const WKWEBVIEW = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148'
const SAFARI_26 = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Mobile/15E148 Safari/604.1'
const SAFARI_18 = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1'
const PHONE = { viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true }

test.describe('inside another app', () => {
    test.use({ ...PHONE, userAgent: WKWEBVIEW })

    test('the sign in page is the Open this in Safari card, with Copy link and a way to continue', async ({ context, page }) => {
        await fakeCase(context, 'signedout')
        await page.addInitScript(() => {
            const w = window as unknown as { __copied: string[] }
            w.__copied = []
            Object.defineProperty(navigator, 'clipboard', { value: { writeText: async (s: string) => void w.__copied.push(s) }, configurable: true })
        })
        await page.goto('/chats')
        const card = page.locator('.in-app-card')
        await expect(card.getByRole('heading', { name: 'Open this in Safari' })).toBeVisible()
        await expect(card).toContainText('This browser cannot keep your sign in.')
        await card.getByRole('button', { name: 'Copy link' }).click()
        await expect(page.locator('.toast')).toHaveText('Link copied')
        expect(await page.evaluate(() => (window as unknown as { __copied: string[] }).__copied)).toEqual([new URL(page.url()).origin + '/'])
        await card.getByRole('button', { name: 'Continue here anyway' }).click()
        await expect(card).toHaveCount(0)
        await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible()
    })
})

for (const [name, ua, steps] of [
    ['iOS 26', SAFARI_26, 'Tap the More button (•••) at the bottom, then Share, then Add to Home Screen. Keep Open as Web App on.'],
    ['iOS 18', SAFARI_18, "Tap Share in the browser's bar"],
] as const) {
    test.describe(name, () => {
        test.use({ ...PHONE, userAgent: ua })

        test('the sign in page and Settings use that iOS wording', async ({ context, page }) => {
            await fakeCase(context, 'signedout')
            await page.goto('/signin')
            await expect(page.locator('.in-app-card')).toHaveCount(0)
            await page.locator('.signin-install summary').click()
            await expect(page.locator('.signin-install')).toContainText(steps)
        })
    })
}
