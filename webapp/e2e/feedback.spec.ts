// WB11 (HX64): Report a problem and Request a feature under Settings > About. The inbox is answered by
// the test (https://inbox.test/v1/feedback, the build's address in e2e/vite.e2e.ts), so the form's
// steps, the one report per draft, the draft kept on the device and the ways round are all walked
// here: on a phone, a desktop, an iPhone in Safari and, with the remote's keys, the TV.
import { mkdirSync } from 'node:fs'
import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page, type Route, type TestInfo } from '@playwright/test'
import { fakeCase, noSizeFloor, openTvSettingsPage } from './helpers'

const SHOTS = `${process.cwd()}/../design/web-gaps/WB11`
const INBOX = 'https://inbox.test/v1/feedback'
const CODE = 'FB-7K3Q-M4XP'

async function shot(page: Page, info: TestInfo, name: string) {
    mkdirSync(SHOTS, { recursive: true })
    await page.screenshot({ path: `${SHOTS}/${name}-${info.project.name}.png` })
}

/** Every post to the inbox, kept as the page sent it. */
interface Posted {
    body: string
    type: string | undefined
    origin: string | undefined
}

async function inbox(page: Page, answer: (n: number, route: Route) => Promise<void> | void = (_n, r) => r.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ code: CODE, duplicate: false, keepDays: 30 }), headers: { 'access-control-allow-origin': '*' } })) {
    const posts: Posted[] = []
    await page.route(INBOX, async (route) => {
        const req = route.request()
        posts.push({ body: req.postData() ?? '', type: req.headers()['content-type'], origin: req.headers()['origin'] })
        await answer(posts.length, route)
    })
    return posts
}

const ok = (route: Route, duplicate = false) =>
    route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ code: CODE, duplicate, keepDays: 30 }), headers: { 'access-control-allow-origin': '*' } })

async function about(page: Page) {
    await noSizeFloor(page)
    // The reports are under Help since WP-78.
    await page.goto('/settings/help')
    await expect(page.locator('[data-feedback-rows]')).toBeVisible()
}

const dialog = (page: Page) => page.locator('dialog.feedback[open]')
const stored = (page: Page) => page.evaluate(() => localStorage.getItem('tmplayer-feedback'))

/** From the About row to the review step with [text] typed. */
async function toReview(page: Page, text = 'The picture stops after a minute.') {
    await page.getByRole('button', { name: /Report a problem/ }).click()
    await expect(dialog(page)).toBeVisible()
    await dialog(page).locator('textarea').fill(text)
    await dialog(page).getByRole('button', { name: 'Next' }).click()
    await expect(dialog(page).getByRole('heading', { name: 'Send this to the developer?' })).toBeVisible()
}

test.describe('the form', () => {
    test.beforeEach(async ({ context }) => {
        await fakeCase(context)
    })

    test('opens on the message with the kind, a counter and an optional way to reply; empty is not sent', async ({ page }, info) => {
        await about(page)
        await page.getByRole('button', { name: /Report a problem/ }).click()
        const ask = dialog(page)
        await expect(ask.getByRole('heading', { name: 'Report a problem' })).toBeVisible()
        await expect(ask.locator('textarea')).toBeFocused()
        await expect(ask).toContainText('0 of 4000')
        await expect(ask.getByLabel('How to reach you (optional)')).toBeVisible()
        await expect(ask).toContainText('Leave it empty to stay anonymous')
        await expect(ask).not.toContainText('Add my logs')
        await shot(page, info, 'form')
        await ask.getByRole('button', { name: 'Next' }).click()
        await expect(ask.getByText('Write a few words first.')).toBeVisible()
        await expect(ask.locator('textarea')).toHaveAttribute('aria-invalid', 'true')
        await expect(ask.locator('textarea')).toBeFocused()
        await ask.locator('textarea').fill('hello')
        await expect(ask.getByText('Write a few words first.')).toHaveCount(0)
        await expect(ask).toContainText('5 of 4000')
        // Too long: said, and not sent.
        await ask.locator('textarea').fill('x'.repeat(4001))
        await expect(ask).toContainText('That is 4001 characters. Keep it under 4000')
        await ask.getByRole('button', { name: 'Next' }).click()
        await expect(ask.getByRole('heading', { name: 'Report a problem' })).toBeVisible()
        // The kind switches the question.
        await ask.getByText('A feature idea').click()
        await expect(ask.getByRole('heading', { name: 'Request a feature' })).toBeVisible()
        await expect(ask.getByLabel('What would you like TMPlayer to do?')).toBeVisible()
    })

    test('the confirm step lists word for word what is sent, and Send files it once with the code shown', async ({ page }, info) => {
        const posts = await inbox(page)
        await about(page)
        await toReview(page, 'The picture stops\nafter a minute.')
        const ask = dialog(page)
        for (const line of ['A problem', 'The picture stops\nafter a minute.', 'Nothing: you stay anonymous', 'Web, ', 'TMPlayer web', 'No logs']) {
            await expect(ask).toContainText(line.trim())
        }
        await expect(ask).toContainText('no account, no chats, no file names')
        await shot(page, info, 'review')
        // Nothing has gone yet.
        expect(posts).toHaveLength(0)
        await ask.getByRole('button', { name: 'Send', exact: true }).click()
        await expect(ask.getByTestId('fb-code')).toHaveText(CODE)
        await expect(ask).toContainText('kept for 30 days')
        await shot(page, info, 'sent')
        expect(posts).toHaveLength(1)
        expect(posts[0].type).toContain('text/plain')
        const sent = JSON.parse(posts[0].body)
        expect(Object.keys(sent).sort()).toEqual(['app', 'id', 'kind', 'message', 'v'])
        expect(sent).toMatchObject({ v: 1, kind: 'problem', message: 'The picture stops\nafter a minute.' })
        expect(sent.id).toMatch(/^[0-9a-f]{32}$/)
        expect(Object.keys(sent.app).sort()).toEqual(['device', 'language', 'platform', 'version'])
        expect(sent.app).toMatchObject({ platform: 'Web', version: 'web' })
        expect(JSON.stringify(sent)).not.toMatch(/logs|contact/)
        // Filed: nothing is kept on the device, and Close returns to the row.
        expect(await stored(page)).toBeNull()
        await ask.getByRole('button', { name: 'Close' }).click()
        await expect(dialog(page)).toHaveCount(0)
        await expect(page.getByRole('button', { name: /Report a problem/ })).toBeFocused()
    })

    test('a contact is sent only when typed, and a feature request is a feature', async ({ page }) => {
        const posts = await inbox(page)
        await about(page)
        await page.getByRole('button', { name: /Request a feature/ }).click()
        const ask = dialog(page)
        await expect(ask.getByRole('heading', { name: 'Request a feature' })).toBeVisible()
        await ask.locator('textarea').fill('A sleep timer please')
        await ask.getByLabel('How to reach you (optional)').fill('  me@example.org ')
        await ask.getByRole('button', { name: 'Next' }).click()
        await expect(ask).toContainText('me@example.org')
        await expect(ask).toContainText('A feature idea')
        await ask.getByRole('button', { name: 'Send', exact: true }).click()
        await expect(ask.getByTestId('fb-code')).toBeVisible()
        expect(JSON.parse(posts[0].body)).toMatchObject({ kind: 'feature', message: 'A sleep timer please', contact: 'me@example.org' })
    })

    test('a double press files one report: one post, one id', async ({ page }) => {
        const posts = await inbox(page, async (_n, route) => {
            await new Promise((r) => setTimeout(r, 600))
            await ok(route)
        })
        await about(page)
        await toReview(page)
        const send = dialog(page).getByRole('button', { name: 'Send', exact: true })
        await send.dblclick()
        await dialog(page).getByRole('button', { name: 'Sending' }).first().click({ force: true, trial: true }).catch(() => {})
        await expect(dialog(page).getByTestId('fb-code')).toHaveText(CODE)
        expect(posts).toHaveLength(1)
    })

    test('no connection keeps it for later; Try again sends the same id and clears the draft', async ({ page }, info) => {
        let tries = 0
        const posts = await inbox(page, async (_n, route) => {
            tries++
            if (tries === 1) return route.abort('internetdisconnected')
            await ok(route, true)
        })
        await about(page)
        await toReview(page)
        await dialog(page).getByRole('button', { name: 'Send', exact: true }).click()
        const ask = dialog(page)
        await expect(ask.getByRole('heading', { name: 'Kept for later' })).toBeVisible()
        await expect(ask).toContainText('There is no connection right now')
        // The ways round are there, with the typed words in them.
        const github = ask.getByRole('link', { name: 'Open on GitHub' })
        expect(new URL((await github.getAttribute('href'))!).searchParams.get('what')).toBe('The picture stops after a minute.')
        expect(await ask.getByRole('link', { name: 'Send an email instead' }).getAttribute('href')).toContain('mailto:hello@tmplayer.org')
        await shot(page, info, 'queued')
        const draft = JSON.parse((await stored(page))!)
        expect(draft.pending).toContain('The picture stops')
        await ask.getByRole('button', { name: 'Try again' }).click()
        await expect(ask.getByTestId('fb-code')).toHaveText(CODE)
        expect(posts).toHaveLength(2)
        expect(JSON.parse(posts[0].body).id).toBe(JSON.parse(posts[1].body).id)
        expect(await stored(page)).toBeNull()
    })

    test('the inbox saying slow down or failing is kept; a refusal keeps the words and offers Edit', async ({ page }, info) => {
        let n = 0
        await inbox(page, async (_i, route) => {
            n++
            if (n === 1) return route.fulfill({ status: 429, contentType: 'application/json', body: '{"error":"slow_down"}', headers: { 'access-control-allow-origin': '*', 'retry-after': '60' } })
            if (n === 2) return route.fulfill({ status: 503, body: '{}', headers: { 'access-control-allow-origin': '*' } })
            return route.fulfill({ status: 400, contentType: 'application/json', body: '{"error":"not_feedback"}', headers: { 'access-control-allow-origin': '*' } })
        })
        await about(page)
        await toReview(page)
        const ask = dialog(page)
        await ask.getByRole('button', { name: 'Send', exact: true }).click()
        await expect(ask).toContainText('Several reports were sent from here a moment ago')
        await ask.getByRole('button', { name: 'Try again' }).click()
        await expect(ask).toContainText('There is no connection right now')
        await ask.getByRole('button', { name: 'Try again' }).click()
        await expect(ask.getByRole('heading', { name: 'Not sent' })).toBeVisible()
        await expect(ask).toContainText('code 400')
        await shot(page, info, 'refused')
        await ask.getByRole('button', { name: 'Edit' }).click()
        await expect(ask.locator('textarea')).toHaveValue('The picture stops after a minute.')
        expect(JSON.parse((await stored(page))!).pending ?? null).toBeNull()
    })

    test('an answer that is not the inbox answering (a captive portal) is not taken for a filed report: Not sent, words kept', async ({ page }) => {
        await inbox(page, (_n, route) => route.fulfill({ status: 200, contentType: 'text/html', body: '<html>Sign in to the Wi-Fi</html>', headers: { 'access-control-allow-origin': '*' } }))
        await about(page)
        await toReview(page)
        await dialog(page).getByRole('button', { name: 'Send', exact: true }).click()
        await expect(dialog(page).getByRole('heading', { name: 'Not sent' })).toBeVisible()
        await dialog(page).getByRole('button', { name: 'Edit' }).click()
        await expect(dialog(page).locator('textarea')).toHaveValue('The picture stops after a minute.')
    })

    test('offline sends nothing and keeps the draft', async ({ page, context }) => {
        const posts = await inbox(page)
        await about(page)
        await toReview(page)
        await context.setOffline(true)
        await dialog(page).getByRole('button', { name: 'Send', exact: true }).click()
        await expect(dialog(page).getByRole('heading', { name: 'Kept for later' })).toBeVisible()
        expect(posts).toHaveLength(0)
        await context.setOffline(false)
        await dialog(page).getByRole('button', { name: 'Try again' }).click()
        await expect(dialog(page).getByTestId('fb-code')).toHaveText(CODE)
        expect(posts).toHaveLength(1)
    })

    test('typing is kept on the device 1.5 s after the last change, with nothing but what was typed', async ({ page }) => {
        await about(page)
        await page.getByRole('button', { name: /Report a problem/ }).click()
        const ask = dialog(page)
        await ask.locator('textarea').fill('It stops')
        await ask.getByLabel('How to reach you (optional)').fill('@me')
        await page.waitForTimeout(700)
        expect(await stored(page)).toBeNull()
        await expect.poll(() => stored(page), { timeout: 3000 }).not.toBeNull()
        const draft = JSON.parse((await stored(page))!)
        expect(draft).toMatchObject({ kind: 'problem', message: 'It stops', contact: '@me' })
        expect(Object.keys(draft).sort()).toEqual(['contact', 'id', 'kind', 'message'])
        // Cancel keeps it; the next time the form opens on it, with the same id.
        await ask.getByRole('button', { name: 'Cancel' }).click()
        await page.getByRole('button', { name: /Report a problem/ }).click()
        await expect(dialog(page).locator('textarea')).toHaveValue('It stops')
        await expect(dialog(page).getByLabel('How to reach you (optional)')).toHaveValue('@me')
        expect(JSON.parse((await stored(page))!).id).toBe(draft.id)
        // Closing at once (before the wait ends) still keeps the words.
        await dialog(page).locator('textarea').fill('It stops again')
        await page.keyboard.press('Escape')
        await expect(dialog(page)).toHaveCount(0)
        // The dialog loses [open] at once and its close event, which keeps the words, comes a task later.
        await expect.poll(async () => JSON.parse((await stored(page)) ?? '{}').message).toBe('It stops again')
    })

    test('a draft that waits for a connection opens on Kept for later after a reload', async ({ page }) => {
        await inbox(page, (_n, route) => route.abort('internetdisconnected'))
        await about(page)
        await toReview(page)
        await dialog(page).getByRole('button', { name: 'Send', exact: true }).click()
        await expect(dialog(page).getByRole('heading', { name: 'Kept for later' })).toBeVisible()
        await page.reload()
        await expect(page.locator('[data-feedback-rows]')).toBeVisible()
        await page.getByRole('button', { name: /Report a problem/ }).click()
        await expect(dialog(page).getByRole('heading', { name: 'Kept for later' })).toBeVisible()
    })

    test('keyboard: Escape closes and the focus returns, Tab stays inside, Next is reachable', async ({ page }) => {
        await about(page)
        const row = page.getByRole('button', { name: /Report a problem/ })
        await row.focus()
        await page.keyboard.press('Enter')
        await expect(dialog(page)).toBeVisible()
        await expect(dialog(page).locator('textarea')).toBeFocused()
        for (let i = 0; i < 12; i++) {
            await page.keyboard.press('Tab')
            // Inside the window, or out on the browser's own bar (body): never on the page behind it.
            expect(await page.evaluate(() => document.activeElement === document.body || !!document.activeElement?.closest('dialog[open]'))).toBe(true)
        }
        await page.keyboard.press('Escape')
        await expect(dialog(page)).toHaveCount(0)
        await expect(row).toBeFocused()
    })

    test('axe: no serious findings on the About page, the form, its review and a failed send', async ({ page, browserName }) => {
        test.skip(browserName !== 'chromium', 'axe runs in Chromium')
        await inbox(page, (_n, route) => route.abort('internetdisconnected'))
        await about(page)
        const audit = async (where: string) => {
            const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze()
            expect(result.passes.length, `${where}: axe ran`).toBeGreaterThan(10)
            const bad = result.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical')
            expect(bad.map((v) => `${where}: ${v.id} ${v.nodes.slice(0, 3).map((n) => n.target.join(' ')).join(' | ')}`)).toEqual([])
        }
        await audit('About')
        await page.getByRole('button', { name: /Report a problem/ }).click()
        await expect(dialog(page)).toBeVisible()
        await audit('the form')
        await dialog(page).getByRole('button', { name: 'Next' }).click()
        await audit('the form with its error')
        await dialog(page).locator('textarea').fill('It stops')
        await dialog(page).getByRole('button', { name: 'Next' }).click()
        await audit('the review')
        await dialog(page).getByRole('button', { name: 'Send', exact: true }).click()
        await expect(dialog(page).getByRole('heading', { name: 'Kept for later' })).toBeVisible()
        await audit('Kept for later')
    })

    test('half an emoji (a stored draft, a paste) never breaks the form or its links', async ({ page }) => {
        await page.addInitScript(() => {
            const half = String.fromCharCode(0xd83d)
            localStorage.setItem('tmplayer-feedback', JSON.stringify({ kind: 'problem', message: `It stops ${half}`, contact: `me${half}`, id: '0123456789abcdef0123456789abcdef' }))
        })
        await about(page)
        await page.getByRole('button', { name: /Report a problem/ }).click()
        const ask = dialog(page)
        await expect(ask.locator('textarea')).toBeVisible()
        await expect(ask.getByRole('link', { name: 'Open on GitHub' })).toHaveAttribute('href', /what=It%20stops/)
        // A pair cut in two by typing and a lone half pasted in: still no crash, the report is the clean text.
        await ask.locator('textarea').evaluate((el: HTMLTextAreaElement) => {
            el.value = `Stops ${String.fromCharCode(0xde00)} again`
            el.dispatchEvent(new Event('input', { bubbles: true }))
        })
        await ask.getByRole('button', { name: 'Next' }).click()
        await expect(ask.getByRole('heading', { name: 'Send this to the developer?' })).toBeVisible()
        await expect(ask).toContainText('Stops  again')
    })

    test('garbage in the stored draft opens a clean form, never a crash', async ({ page }) => {
        await page.addInitScript(() => localStorage.setItem('tmplayer-feedback', '{"kind":7,"message":{"a":1},"id":"x","pending":"[]"}'))
        await about(page)
        await page.getByRole('button', { name: /Report a problem/ }).click()
        await expect(dialog(page).locator('textarea')).toHaveValue('')
    })
})

test.describe('without an inbox in the build', () => {
    test.beforeEach(async ({ context, page }) => {
        await fakeCase(context)
        await page.addInitScript(() => localStorage.setItem('tmplayer-feedback-off', '1'))
    })

    test('the rows are links to GitHub with the version and browser filled in, an e-mail and Copy details', async ({ page, context }, info) => {
        await context.grantPermissions(['clipboard-read', 'clipboard-write']).catch(() => {})
        await about(page)
        const problem = page.getByRole('link', { name: /Report a problem/ })
        const url = new URL((await problem.getAttribute('href'))!)
        expect(url.origin + url.pathname).toBe('https://github.com/dracu-lah/TMPlayer/issues/new')
        expect(url.searchParams.get('template')).toBe('bug_report.yml')
        expect(url.searchParams.get('version')).toBe('web')
        expect(url.searchParams.get('device')).toMatch(/ on /)
        expect(url.searchParams.get('device')).not.toMatch(/Mozilla|AppleWebKit/)
        expect(url.href.length).toBeLessThan(8 * 1024)
        await expect(problem).toHaveAttribute('target', '_blank')
        const feature = page.getByRole('link', { name: /Request a feature/ })
        expect(new URL((await feature.getAttribute('href'))!).searchParams.get('template')).toBe('feature_request.yml')
        const mail = page.getByRole('link', { name: /Send an email instead/ })
        expect(await mail.getAttribute('href')).toMatch(/^mailto:hello@tmplayer\.org\?subject=/)
        await expect(page.getByRole('button', { name: /Copy details/ })).toBeVisible()
        await shot(page, info, 'about-links')
        await page.getByRole('button', { name: /Copy details/ }).click()
        await expect(page.getByText('Details copied')).toBeVisible()
        if (info.project.name !== 'webkit-iphone') {
            const text = await page.evaluate(() => navigator.clipboard.readText()).catch(() => null)
            if (text !== null) {
                expect(text).toMatch(/^TMPlayer web\nPlatform: Web\nApp version: web\nDevice: .+\nApp language: .+$/)
                expect(text).not.toMatch(/Mozilla|@|https?:/)
            }
        }
    })
})

test.describe('on the TV, with the remote', () => {
    test.skip(({ browserName }) => browserName !== 'chromium', 'the TV walk runs in Chromium')
    test.use({ viewport: { width: 1920, height: 1080 } })
    const focusedLabel = (page: Page) => page.evaluate(() => (document.activeElement as HTMLElement | null)?.textContent?.trim() ?? '')
    async function press(page: Page, key: string) {
        await page.keyboard.press(key)
        await page.waitForTimeout(150)
    }
    async function tvPage(page: Page, off: boolean) {
        await page.goto('/__e2e/sizes')
        await page.evaluate(
            (off) =>
                new Promise<void>((resolve, reject) => {
                    localStorage.setItem('tmplayer-layout', 'tv')
                    if (off) localStorage.setItem('tmplayer-feedback-off', '1')
                    const open = indexedDB.open('tmplayer-app')
                    open.onupgradeneeded = () => open.result.createObjectStore('kv')
                    open.onerror = () => reject(open.error)
                    open.onsuccess = () => {
                        const tx = open.result.transaction('kv', 'readwrite')
                        tx.objectStore('kv').put({ sizeMin: 0 }, 'settings')
                        tx.oncomplete = () => (open.result.close(), resolve())
                    }
                }),
            off,
        )
        await page.goto('/settings')
        await expect(page.locator('html')).toHaveAttribute('data-form', 'tv')
    }

    test.beforeEach(async ({ context }, info) => {
        test.skip(info.project.name !== 'chromium', 'one TV pass')
        await fakeCase(context)
    })

    test('OK on Report a problem opens the form with a QR code beside it; typing needs OK, Back closes and returns the focus', async ({ page }, info) => {
        const posts = await inbox(page)
        await tvPage(page, false)
        await openTvSettingsPage(page, 'help')
        const row = page.getByRole('button', { name: 'Report a problem' })
        await row.focus()
        await press(page, 'Enter')
        const ask = dialog(page)
        await expect(ask).toBeVisible()
        await expect(ask.locator('.fb-qr-code svg')).toBeVisible()
        await expect(ask.getByRole('img', { name: 'QR code for the bug report on GitHub' })).toBeVisible()
        expect(await page.evaluate(() => !!document.activeElement?.closest('dialog[open]'))).toBe(true)
        await shot(page, info, 'tv-form')
        // The field waits for OK before it opens; a key then types.
        await expect(ask.locator('textarea')).toBeFocused()
        await press(page, 'Enter')
        await page.keyboard.type('Stops on the TV')
        await expect(ask.locator('textarea')).toHaveValue('Stops on the TV')
        // Down leaves the field (the way to reply is next, as a field the remote stops on) and then reaches the buttons; Right is Next.
        await press(page, 'ArrowDown')
        await expect(ask.getByLabel('How to reach you (optional)')).toBeFocused()
        await press(page, 'ArrowDown')
        expect(await focusedLabel(page)).toBe('Cancel')
        await press(page, 'ArrowRight')
        expect(await focusedLabel(page)).toBe('Next')
        await press(page, 'Enter')
        await expect(ask.getByRole('heading', { name: 'Send this to the developer?' })).toBeVisible()
        await expect.poll(() => focusedLabel(page)).toBe('Back')
        await shot(page, info, 'tv-review')
        await press(page, 'ArrowRight')
        expect(await focusedLabel(page)).toBe('Send')
        await press(page, 'Enter')
        await expect(ask.getByTestId('fb-code')).toHaveText(CODE)
        expect(posts).toHaveLength(1)
        await shot(page, info, 'tv-sent')
        await press(page, 'Backspace')
        await expect(dialog(page)).toHaveCount(0)
        await expect(row).toBeFocused()
    })

    test('with no inbox the TV shows the QR code and the e-mail line, and Close', async ({ page }, info) => {
        await tvPage(page, true)
        await openTvSettingsPage(page, 'help')
        const row = page.getByRole('button', { name: 'Request a feature' })
        await row.focus()
        await press(page, 'Enter')
        const ask = dialog(page)
        await expect(ask.getByRole('heading', { name: 'Request a feature' })).toBeVisible()
        await expect(ask.getByRole('img', { name: 'QR code for the feature request on GitHub' })).toBeVisible()
        await expect(ask).toContainText('hello@tmplayer.org')
        await expect(ask.locator('textarea')).toHaveCount(0)
        expect(await focusedLabel(page)).toBe('Close')
        await shot(page, info, 'tv-ways')
        await press(page, 'Enter')
        await expect(dialog(page)).toHaveCount(0)
        await expect(row).toBeFocused()
    })
})
