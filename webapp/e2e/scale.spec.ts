// HX58 on the web: a very large account (the fake's `huge` flag: 3,000 chats and 300 in the archive, 100,000 videos,
// 20,000 of them in Deep Room) against the budgets AGENTS.md sets for scale. First content on Home, Chats, a chat and
// Search within 3 s; Telegram calls per screen budgeted; the thumbnail and poster caches inside their caps; the page
// drawing only what is on screen; and nothing asked for once the screen is quiet (idle 10 s).
//
// The three forms run here, on the plain Chromium project, because the page's form is what changes (the phone's touch
// screen, the TV's layout), so no project of playwright.config.ts has to carry this spec. The 3 s is measured warm:
// a first visit loads the dev server's modules, which is the build's job and not the list's.
//
// The numbers a budget reads are the first measured run's, with headroom (see each `expect`); a failure is a finding
// to write down in private/WEB-GAPS-PLAN.md, not a budget to loosen.
import { expect, test, type Locator, type Page } from '@playwright/test'
import { countCalls, fakeCase, workerCalls } from './helpers'

/** A measured number, in the report and on the console, so the next run's budgets can be set from them. */
const note = (a: { type: string; description: string }) => {
    test.info().annotations.push(a)
    console.log(`[scale] ${test.info().project.name} ${test.info().title.slice(0, 30)}: ${a.type} = ${a.description}`)
}

/** The calls by kind: `12 (iterDialogs 1, searchMessages 6)`. */
const summary = (calls: string[]) => `${calls.length} (${[...new Set(calls.map((c) => c.split(' ')[0]))].map((k) => `${k} ${countCalls(calls, k)}`).join(', ')})`

/**
 * The 3 s of AGENTS.md, held on the local gate before a push. GitHub's shared runner gets a second more: it is a wall clock on a
 * small shared machine loading the dev server's modules one by one, and the same page measured 2.9 s there on one run (f7acaae,
 * run 45660649443) and 3.5 s on the next (1f7022f, run 45691829607, nothing in webapp/ changed but a spec). The calls, pages and
 * caches below are counts, the same on every machine, and keep their budgets everywhere.
 */
const FIRST_CONTENT_MS = process.env.GITHUB_ACTIONS ? 4000 : 3000
const IDLE_MS = 10_000
/** cacheBounds.ts: THUMB_CAP and PICTURE_CAP. */
const THUMB_CAP = 3000
const PICTURE_CAP = 1500

interface Form {
    name: string
    layout: 'phone' | 'desktop' | 'tv'
    width: number
    height: number
}
const FORMS: Form[] = [
    { name: 'desktop-1280', layout: 'desktop', width: 1280, height: 800 },
    { name: 'phone-390', layout: 'phone', width: 390, height: 844 },
    { name: 'tv-1920', layout: 'tv', width: 1920, height: 1080 },
]

/** The layout set before the page starts (the phone's comes from its touch screen), as tv.spec does. */
async function inForm(page: Page, form: Form) {
    if (form.layout === 'phone') return
    await page.addInitScript((layout) => {
        try {
            localStorage.setItem('tmplayer-layout', layout)
        } catch {}
    }, form.layout)
}

/** Seconds from `go` until `target` is on screen, after a first visit has loaded the modules (see the top). */
async function firstContent(page: Page, path: string, target: string) {
    await page.goto(path)
    await expect(page.locator(target).first()).toBeVisible({ timeout: 20_000 })
    await page.goto('about:blank')
    const start = Date.now()
    await page.goto(path)
    await expect(page.locator(target).first()).toBeVisible({ timeout: 20_000 })
    return Date.now() - start
}

/**
 * Waits for the screen's own work to end (the thumbnails still queued, a card's warm start, the TV's focus settling): the
 * call count must hold still for 3 s within 20 s of the last touch, else the screen does not stop by itself. Returns the
 * seconds it took.
 */
async function settle(page: Page) {
    const start = Date.now()
    let last = (await workerCalls(page)).length
    let since = Date.now()
    while (Date.now() - since < 3000) {
        if (Date.now() - start > 20_000) throw new Error(`still asking 20 s after the last touch (${(await workerCalls(page)).length} calls so far)`)
        await page.waitForTimeout(500)
        const now = (await workerCalls(page)).length
        if (now !== last) (last = now), (since = Date.now())
    }
    return (since - start) / 1000
}

/** The calls made once the screen has settled and then sits untouched for `ms`. */
async function idleCalls(page: Page, ms: number) {
    note({ type: 'settled after', description: `${await settle(page)} s` })
    const before = (await workerCalls(page)).length
    await page.waitForTimeout(ms)
    return (await workerCalls(page)).slice(before)
}

/** Types into a search field: the TV's field opens for typing on OK (Enter) only, a phone and a desktop take the text. */
async function typeInto(page: Page, form: Form, field: Locator, text: string) {
    if (form.layout !== 'tv') return field.fill(text)
    await field.focus()
    if (await field.evaluate((el: HTMLInputElement) => el.readOnly)) await page.keyboard.press('Enter')
    await page.keyboard.press('Control+a')
    await page.keyboard.type(text)
}

for (const form of FORMS) {
    test.describe(`scale, ${form.name}`, () => {
        test.use({ viewport: { width: form.width, height: form.height }, hasTouch: form.layout === 'phone', isMobile: form.layout === 'phone' })
        test.describe.configure({ timeout: 90_000 })
        test.beforeEach(async ({ context, page }, info) => {
            test.skip(info.project.name !== 'chromium', 'the three forms run inside this spec, on the plain Chromium project')
            await fakeCase(context, 'huge')
            await inForm(page, form)
        })

        test('Home shows its first row within 3 s, reads a bounded number of chats, and is quiet when idle', async ({ page }) => {
            const ms = await firstContent(page, '/', '.home-row .card')
            note({ type: 'first Home content', description: `${ms} ms` })
            expect(ms).toBeLessThan(FIRST_CONTENT_MS)
            await page.waitForTimeout(4000)
            const calls = await workerCalls(page)
            note({ type: 'Home calls', description: summary(calls) })
            // The list is read once (one pass over every page), the archive not at all, and Telegram's search across chats a few times.
            expect(countCalls(calls, 'iterDialogs')).toBeLessThanOrEqual(2)
            expect(calls.filter((c) => c.startsWith('iterDialogs only'))).toHaveLength(0)
            expect(countCalls(calls, 'searchGlobal')).toBeLessThanOrEqual(12)
            // A few chats a time, never the list: 3,000 chats must not become 3,000 reads.
            expect(countCalls(calls, 'searchMessages')).toBeLessThanOrEqual(60)
            expect(countCalls(calls, 'getFullChat')).toBeLessThanOrEqual(10)
            // Sync is off by default: nothing is written to Saved Messages.
            expect(countCalls(calls, 'sendMedia') + countCalls(calls, 'editMessage')).toBe(0)
            const idle = await idleCalls(page, IDLE_MS)
            expect(idle, `asked while idle: ${idle.join(', ')}`).toEqual([])
        })

        test('Chats lists the first chats within 3 s, finds one of 3,000 by name, and is quiet when idle', async ({ page }) => {
            const ms = await firstContent(page, '/chats', '.chat-link')
            note({ type: 'first Chats content', description: `${ms} ms` })
            expect(ms).toBeLessThan(FIRST_CONTENT_MS)
            const field = page.locator('.search-field input')
            await typeInto(page, form, field, 'Scale Chat 2999')
            const found = Date.now()
            await expect(page.locator('.chat-link', { hasText: 'Scale Chat 2999' })).toHaveCount(1, { timeout: FIRST_CONTENT_MS })
            note({ type: 'find a chat', description: `${Date.now() - found} ms` })
            // Typing a name asks Telegram for nothing: the list is on the device.
            const before = (await workerCalls(page)).length
            await typeInto(page, form, field, 'Scale Chat 1500')
            await expect(page.locator('.chat-link', { hasText: 'Scale Chat 1500' })).toHaveCount(1)
            expect((await workerCalls(page)).length).toBe(before)
            const idle = await idleCalls(page, IDLE_MS)
            expect(idle, `asked while idle: ${idle.join(', ')}`).toEqual([])
        })

        test('the picker lists 3,000 chats within 3 s and opens the archive of 300 by asking for it once', async ({ page }) => {
            const rows = page.getByRole('list', { name: 'Choose chats' }).getByRole('listitem')
            const ms = await firstContent(page, '/added-chats', '.chat-tick')
            note({ type: 'first picker content', description: `${ms} ms` })
            expect(ms).toBeLessThan(FIRST_CONTENT_MS)
            // The list is drawn in part, as the page is: 3,000 rows are never all in the document.
            note({ type: 'picker rows drawn', description: String(await rows.count()) })
            expect(await rows.count()).toBeLessThan(400)
            const found = Date.now()
            await page.getByRole('button', { name: 'Archived' }).click()
            await expect(page.getByRole('list', { name: 'Choose chats' }).getByText('Scale Archive 000').first()).toBeVisible({ timeout: FIRST_CONTENT_MS })
            note({ type: 'archive filter', description: `${Date.now() - found} ms` })
            const calls = await workerCalls(page)
            note({ type: 'picker calls', description: summary(calls) })
            // The main list once, the archive once, each through one pass.
            expect(calls.filter((c) => c.startsWith('iterDialogs exclude'))).toHaveLength(1)
            expect(calls.filter((c) => c.startsWith('iterDialogs only')).length).toBeLessThanOrEqual(1)
            // How far the background count has gone by now depends on timing: the test below holds it to a budget.
        })

        // WB22 (2026-11-20): the picker's counts have a budget (ui/pickCount.ts). WB23 found about 1,750 searchMessages in 15 s on
        // this account (every channel counted behind the list, no cap). Now the head start and the rows the viewer stays on are
        // counted (150 chats at most, two calls each) and the rest only when they ask to look in more chats (100 at a time).
        test('the picker keeps its background count of chats within a budget, and counts more only when asked', async ({ page }) => {
            await page.goto('/added-chats')
            await expect(page.locator('.chat-tick').first()).toBeVisible({ timeout: 20_000 })
            await page.waitForTimeout(15_000)
            const calls = await workerCalls(page)
            note({ type: 'picker calls after 15 s', description: summary(calls) })
            expect(countCalls(calls, 'searchMessages')).toBeLessThanOrEqual(400)
            await page.screenshot({ path: test.info().outputPath(`picker-${form.name}.png`) })
            // Scrolling through a few hundred rows counts only what the viewer stays on, inside the visit's budget.
            for (let i = 0; i < 12; i++) {
                await page.evaluate(() => window.scrollBy(0, innerHeight * 2))
                await page.waitForTimeout(500)
            }
            await page.waitForTimeout(3000)
            const scrolled = countCalls(await workerCalls(page), 'searchMessages')
            note({ type: 'picker searchMessages after scrolling', description: String(scrolled) })
            expect(scrolled).toBeLessThanOrEqual(2 * 150 + 10)
            // The way to count more is on the page once the budget is spent; it counts a hundred more chats, then offers again.
            await page.evaluate(() => window.scrollTo(0, 0))
            const more = page.getByRole('button', { name: 'Look in more chats' })
            await expect(more).toBeVisible()
            await expect(page.getByText(/Looked for videos in \d+ of 3\d{3} chats so far/)).toBeVisible()
            await page.screenshot({ path: test.info().outputPath(`picker-more-${form.name}.png`) })
            await more.click()
            await expect.poll(async () => countCalls(await workerCalls(page), 'searchMessages'), { timeout: 30_000 }).toBeGreaterThanOrEqual(scrolled + 150)
            await page.waitForTimeout(3000)
            const after = countCalls(await workerCalls(page), 'searchMessages')
            note({ type: 'picker searchMessages after look in more', description: String(after) })
            expect(after).toBeLessThanOrEqual(scrolled + 2 * 100 + 10)
            await expect(more).toBeVisible()
        })

        test('three chats that always fail to count are asked twice at most and do not hold the picker to its budget', async ({ context, page }) => {
            await fakeCase(context, 'huge', 'countfail')
            await page.goto('/added-chats')
            await expect(page.locator('.chat-tick').first()).toBeVisible({ timeout: 20_000 })
            await page.waitForTimeout(6000)
            const more = page.getByRole('button', { name: 'Look in more chats' })
            // The budget is spent by scrolling through the rows, then more is asked for, so the scan reaches the failing channels.
            for (let i = 0; i < 12; i++) {
                await page.evaluate(() => window.scrollBy(0, innerHeight * 2))
                await page.waitForTimeout(400)
            }
            await page.evaluate(() => window.scrollTo(0, 0))
            await expect(more).toBeVisible({ timeout: 20_000 })
            await more.click()
            await page.waitForTimeout(12_000)
            const calls = await workerCalls(page)
            const failing = [66, 69, 72].map((slot) => calls.filter((c) => c.startsWith(`searchMessages -${2_000_000 + slot} `)).length)
            note({ type: 'asks of the three failing chats', description: failing.join(', ') })
            // Two filters per ask, one ask and one retry at most.
            for (const n of failing) expect(n).toBeLessThanOrEqual(4)
            expect(failing.reduce((a, b) => a + b, 0)).toBeGreaterThan(0)
            expect(countCalls(calls, 'searchMessages')).toBeLessThanOrEqual(2 * (150 + 100) + 20)
            // And it stops by itself: nothing more is asked once the step is spent.
            const settled = countCalls(await workerCalls(page), 'searchMessages')
            await page.waitForTimeout(6000)
            expect(countCalls(await workerCalls(page), 'searchMessages')).toBe(settled)
        })

        test('the picker stops counting for the visit when Telegram refuses, and says so', async ({ context, page }) => {
            // Chats picked (none yet), not All chats, so the last step can tick one (HX86).
            await fakeCase(context, 'huge', 'countflood', 'picked')
            await page.goto('/added-chats')
            await expect(page.locator('.chat-tick').first()).toBeVisible({ timeout: 20_000 })
            await expect(page.getByText(/asked TMPlayer to slow down/)).toBeVisible({ timeout: 20_000 })
            await page.waitForTimeout(1000)
            const first = countCalls(await workerCalls(page), 'searchMessages')
            await page.waitForTimeout(4000)
            // A few calls were in flight when the refusal came; none after it.
            expect(countCalls(await workerCalls(page), 'searchMessages')).toBe(first)
            expect(first).toBeLessThan(40)
            await expect(page.getByRole('button', { name: 'Look in more chats' })).toHaveCount(0)
            // The list still works: a chat can be ticked.
            await page.locator('.chat-tick').nth(2).check()
            await expect(page.getByText(/1 chat chosen/)).toBeVisible()
        })

        test('Deep Room (20,000 videos) shows its first page within 3 s, pages only what is shown, draws a bounded page, keeps its caches in their caps and is quiet when idle', async ({ page }) => {
            const ms = await firstContent(page, '/chat/-1009', '.card')
            note({ type: 'first chat content', description: `${ms} ms` })
            expect(ms).toBeLessThan(FIRST_CONTENT_MS)
            await page.waitForTimeout(1500)
            const first = await workerCalls(page)
            // The first screen is one page of each feed, not a walk down the history.
            const pages = first.filter((c) => c.startsWith('searchMessages -1009'))
            note({ type: 'first chat pages', description: String(pages.length) })
            expect(pages.length).toBeLessThanOrEqual(6)
            // Scroll down through some 2,000 videos: it pages as it goes and draws only the rows near the screen.
            let drawn = 0
            for (let i = 0; i < 40; i++) {
                await page.evaluate(() => window.scrollBy(0, innerHeight * 3))
                await page.waitForTimeout(250)
                drawn = Math.max(drawn, await page.locator('.card').count())
            }
            note({ type: 'most cards drawn', description: String(drawn) })
            expect(drawn).toBeLessThan(250)
            const after = await workerCalls(page)
            const paged = after.filter((c) => c.startsWith('searchMessages -1009 inputMessagesFilterVideo')).length
            note({ type: 'video pages after scrolling', description: String(paged) })
            // 20,000 videos are 200 pages of a hundred: scrolling a little way must not have read them all.
            expect(paged).toBeLessThan(60)
            const stored = await page.evaluate(async () => {
                const count = async (name: string) => (await caches.keys()).includes(name) ? (await (await caches.open(name)).keys()).length : 0
                const heap = (performance as unknown as { memory?: { usedJSHeapSize: number } }).memory?.usedJSHeapSize ?? 0
                return { thumbs: await count('tmplayer-thumbs'), posters: await count('tmplayer-posters'), heap }
            })
            note({ type: 'caches', description: JSON.stringify(stored) })
            expect(stored.thumbs).toBeLessThanOrEqual(THUMB_CAP)
            expect(stored.posters).toBeLessThanOrEqual(PICTURE_CAP)
            // The page's own heap (Chromium only): thousands of videos arranged, not hundreds of megabytes.
            if (stored.heap) expect(stored.heap).toBeLessThan(300 * 1024 * 1024)
            await page.evaluate(() => window.scrollTo(0, 0))
            const idle = await idleCalls(page, IDLE_MS)
            expect(idle, `asked while idle: ${idle.join(', ')}`).toEqual([])
        })

        test('Search across 100,000 videos answers within 3 s and is quiet when idle', async ({ page }) => {
            // A direct address never brings a search back (Search.tsx), so the words are typed, after a first visit has loaded the modules.
            await page.goto('/search')
            await expect(page.getByRole('searchbox')).toBeVisible()
            await page.goto('about:blank')
            await page.goto('/search')
            const field = page.getByRole('searchbox')
            await expect(field).toBeVisible()
            await typeInto(page, form, field, 'Deep Reel 0000')
            const start = Date.now()
            await expect(page.locator('.card').first()).toBeVisible({ timeout: 20_000 })
            const ms = Date.now() - start
            note({ type: 'first Search content', description: `${ms} ms` })
            expect(ms).toBeLessThan(FIRST_CONTENT_MS)
            const calls = await workerCalls(page)
            note({ type: 'Search calls', description: summary(calls) })
            expect(countCalls(calls, 'searchGlobal')).toBeLessThanOrEqual(8)
            const idle = await idleCalls(page, IDLE_MS)
            expect(idle, `asked while idle: ${idle.join(', ')}`).toEqual([])
        })
    })
}
