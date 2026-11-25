// WB5 (HX54 on the web): a Home row asks for its chat only after it has stayed 250 ms on screen, drops what it
// asked when it leaves before the ask began, and a card that leaves the screen takes its thumbnail ask back.
// On the huge account of fake/mtcute.ts (3,000 chats, 100,000 videos) with 40 chats starred, so Home has 40 rows.
// The three forms run here, on the plain Chromium project, as scale.spec.ts does. The numbers are the first
// measured runs' with headroom; a failure is a finding, not a budget to loosen.
import { mkdirSync } from 'node:fs'
import { expect, test, type Page } from '@playwright/test'
import { countCalls, fakeCase, workerCalls } from './helpers'

const STARRED = 40
const EAGER = 8
const starId = (slot: number) => -2_000_000 - slot
const SHOTS = new URL('../../design/web-gaps/WB5/', import.meta.url).pathname

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

/** The layout set before the page starts (the phone's comes from its touch screen). */
async function inForm(page: Page, form: Form) {
    if (form.layout === 'phone') return
    await page.addInitScript((layout) => {
        try {
            localStorage.setItem('tmplayer-layout', layout)
        } catch {}
    }, form.layout)
}

/** Stars the first `STARRED` chats of the huge account where the app keeps stars, before the app starts. */
async function starChats(page: Page) {
    await page.goto('/__e2e/sizes')
    await page.evaluate(
        ([count, first]) =>
            new Promise<void>((resolve, reject) => {
                const open = indexedDB.open('tmplayer-app')
                open.onupgradeneeded = () => open.result.createObjectStore('kv')
                open.onerror = () => reject(open.error)
                open.onsuccess = () => {
                    const tx = open.result.transaction('kv', 'readwrite')
                    const store = tx.objectStore('kv')
                    for (let i = 0; i < count; i++) store.put({ chatId: first - i, time: 1_000 + i }, `star:${first - i}`)
                    tx.oncomplete = () => (open.result.close(), resolve())
                }
            }),
        [STARRED, starId(0)],
    )
}

/** The starred chats Home has read so far, with how many reads each had (`searchMessages <chat> ...`). */
async function chatReads(page: Page): Promise<Map<number, number>> {
    const reads = new Map<number, number>()
    for (const c of await workerCalls(page)) {
        const m = /^searchMessages (-\d+) /.exec(c)
        if (!m) continue
        const id = Number(m[1])
        if (id <= starId(0) && id > starId(STARRED)) reads.set(id, (reads.get(id) ?? 0) + 1)
    }
    return reads
}

/** Thumbnails fetched from Telegram so far (fake/mtcute.ts `fakeThumbs`). */
async function thumbFetches(page: Page): Promise<number> {
    const worker = page.workers().find((w) => /tg\/worker/.test(w.url()))
    return worker ? worker.evaluate(() => ((globalThis as { fakeThumbs?: string[] }).fakeThumbs ?? []).length) : 0
}

/** Waits for the screen's own work to end (a card's warm start, rows still arriving): the calls must hold still for 3 s. */
async function settle(page: Page) {
    const start = Date.now()
    let last = (await workerCalls(page)).length + (await thumbFetches(page))
    let since = Date.now()
    while (Date.now() - since < 3000) {
        if (Date.now() - start > 20_000) throw new Error('still asking 20 s after the last touch')
        await page.waitForTimeout(500)
        const now = (await workerCalls(page)).length + (await thumbFetches(page))
        if (now !== last) (last = now), (since = Date.now())
    }
}

/**
 * A hard fling to the foot: nine tenths of a screen every 30 ms, so a row is on screen for well under 250 ms. Run in the page
 * and placed by the clock at each frame, as a fling is: a step per round trip from the test let a slow runner keep each
 * screen up for more than 250 ms, and the rows it showed were read as they should be (run 45691829607).
 */
async function flingToFoot(page: Page) {
    await page.evaluate(async () => {
        const speed = (innerHeight * 0.9) / 30
        const from = scrollY
        const start = performance.now()
        while (performance.now() - start < 20_000) {
            await new Promise((r) => requestAnimationFrame(r))
            window.scrollTo(0, from + (performance.now() - start) * speed)
            if (scrollY + innerHeight >= document.documentElement.scrollHeight - 2) return
        }
    })
}

const note = (type: string, description: string) => {
    test.info().annotations.push({ type, description })
    console.log(`[defer] ${test.info().project.name} ${test.info().title.slice(0, 40)}: ${type} = ${description}`)
}

for (const form of FORMS) {
    test.describe(`home deferral, ${form.name}`, () => {
        test.use({ viewport: { width: form.width, height: form.height }, hasTouch: form.layout === 'phone', isMobile: form.layout === 'phone' })
        test.describe.configure({ timeout: 90_000 })
        test.beforeEach(async ({ context, page }, info) => {
            test.skip(info.project.name !== 'chromium', 'the three forms run inside this spec, on the plain Chromium project')
            await fakeCase(context, 'huge', 'slowthumbs')
            await inForm(page, form)
            await starChats(page)
        })

        test('forty starred rows: only the first screens and the rows that stay are read, a returning row is not read again', async ({ page }) => {
            await page.goto('/')
            await expect(page.locator('.home-row .card').first()).toBeVisible({ timeout: 20_000 })
            await page.waitForTimeout(1500)
            const first = await chatReads(page)
            note('read at first', `${first.size} of ${STARRED} rows`)
            // The first screens (EAGER rows) and the rows on screen once they stayed: never the forty.
            expect(first.size).toBeGreaterThan(0)
            expect(first.size).toBeLessThanOrEqual(EAGER + 6)
            mkdirSync(SHOTS, { recursive: true })
            await page.screenshot({ path: `${SHOTS}home-${form.name}.png` })

            // A hard fling to the foot: each row is on screen for far less than 250 ms.
            const before = (await workerCalls(page)).length
            await flingToFoot(page)
            const during = await chatReads(page)
            note('read during the fling', `${during.size - first.size} new rows`)
            // At most the rows that were on screen long enough at the foot, and the one the fling began on.
            await page.waitForTimeout(1200)
            const after = await chatReads(page)
            note('read after it settled at the foot', `${after.size} of ${STARRED} rows`)
            expect(after.size - first.size).toBeLessThanOrEqual(10)
            expect(after.size).toBeLessThan(STARRED / 2)
            expect((await workerCalls(page)).length - before).toBeLessThan(40)

            // Back to the top: rows already read are not read again.
            await page.evaluate(() => window.scrollTo(0, 0))
            await page.waitForTimeout(1500)
            const back = await chatReads(page)
            for (const [id, n] of after) expect(back.get(id), `chat ${id} read again`).toBe(n)

            // Idle: nothing asked once the viewer stops.
            await settle(page)
            const idleFrom = (await workerCalls(page)).length
            const thumbsFrom = await thumbFetches(page)
            await page.waitForTimeout(4000)
            const idle = (await workerCalls(page)).slice(idleFrom)
            expect(idle, `asked while idle: ${idle.join(', ')}`).toEqual([])
            expect(await thumbFetches(page)).toBe(thumbsFrom)
        })

        test('a row that stays on screen is read, one left before 250 ms is not', async ({ page }) => {
            await page.goto('/')
            await expect(page.locator('.home-row .card').first()).toBeVisible({ timeout: 20_000 })
            await page.waitForTimeout(1500)
            const first = await chatReads(page)
            const rows = page.locator('.home-row')
            const target = rows.nth(EAGER + 6)
            const title = (await target.locator('.row-title').innerText()).trim()
            // Brought into view and left again within 150 ms: not read.
            await target.evaluate(async (el) => {
                el.scrollIntoView()
                await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)))
                window.scrollTo(0, 0)
            })
            await page.waitForTimeout(900)
            expect((await chatReads(page)).size).toBe(first.size)
            // Kept in view: read, and its cards fill.
            await target.scrollIntoViewIfNeeded()
            await expect.poll(async () => (await chatReads(page)).size, { timeout: 5000 }).toBeGreaterThan(first.size)
            await expect(target.locator('.card').first(), title).toBeVisible({ timeout: 10_000 })
        })

        test('thumbnails: a hard fling down a long chat fetches the few cards it stops on, not every card it passed', async ({ page }) => {
            await page.goto('/chat/-1009')
            await expect(page.locator('.card').first()).toBeVisible({ timeout: 20_000 })
            await page.waitForTimeout(1500)
            const from = await thumbFetches(page)
            let passed = 0
            for (let i = 0; i < 40; i++) {
                await page.evaluate(() => window.scrollBy(0, innerHeight * 2.5))
                await page.waitForTimeout(40)
                passed += await page.locator('.card').count()
            }
            await page.waitForTimeout(2500)
            const fetched = (await thumbFetches(page)) - from
            note('cards drawn during the fling', String(passed))
            note('thumbnails fetched', String(fetched))
            // Cards drawn in passing (hundreds) are not fetched: a few screens' worth at most.
            expect(fetched).toBeLessThan(passed / 6)
            expect(fetched).toBeLessThan(260)
            // The cards on screen at the end have theirs.
            await expect(page.locator('.card .card-thumb').first()).toBeVisible({ timeout: 10_000 })
            await settle(page)
            const idleFrom = await thumbFetches(page)
            await page.waitForTimeout(3000)
            expect(await thumbFetches(page)).toBe(idleFrom)
        })
    })
}

test.describe('home deferral, the TV remote and an engine without an observer', () => {
    test.use({ viewport: { width: 1920, height: 1080 } })
    test.describe.configure({ timeout: 90_000 })
    test.beforeEach(async ({ context, page }, info) => {
        test.skip(info.project.name !== 'chromium', 'runs on the plain Chromium project')
        await fakeCase(context, 'huge', 'slowthumbs')
        await inForm(page, FORMS[2])
        await starChats(page)
    })

    test('focus jumping down the rows reads the rows it rests on, not the ones it passes', async ({ page }) => {
        await page.goto('/')
        await expect(page.locator('.home-row .card').first()).toBeVisible({ timeout: 20_000 })
        await page.waitForTimeout(1500)
        const first = await chatReads(page)
        // The remote held down: a row every 40 ms for 30 rows.
        await page.locator('.home-row .card').first().focus()
        for (let i = 0; i < 30; i++) {
            await page.keyboard.press('ArrowDown')
            await page.waitForTimeout(15)
        }
        await page.waitForTimeout(1500)
        const after = await chatReads(page)
        note('rows read after 30 rows of focus', `${after.size - first.size} new`)
        // The row focus rests on is read: found by its heading ('Scale Chat 0017' is the chat of the 17th slot).
        const rest = await page.evaluate(() => {
            const row = document.activeElement?.closest('.home-row')
            return { title: row?.querySelector('.row-title')?.textContent?.trim() ?? '', index: row ? [...document.querySelectorAll('.home-row')].indexOf(row) : -1 }
        })
        note('focus rests on', `${rest.title} (row ${rest.index})`)
        expect(rest.index).toBeGreaterThanOrEqual(EAGER)
        const slot = /Scale Chat (\d+)/.exec(rest.title)
        expect(slot, 'a starred chat row').not.toBeNull()
        await expect.poll(async () => (await chatReads(page)).has(starId(Number(slot![1]))), { timeout: 5000 }).toBe(true)
        // Rests on one row: it, and at most the row or two showing beside it.
        expect(after.size - first.size).toBeLessThanOrEqual(10)
        expect(after.size).toBeLessThan(STARRED)
        // Idle: CPU time of the page over 5 s of no touch (Chromium's own counters) stays under 5 % of the wall.
        const cdp = await page.context().newCDPSession(page)
        await cdp.send('Performance.enable')
        const busy = async () => {
            const { metrics } = await cdp.send('Performance.getMetrics')
            return metrics.find((m) => m.name === 'TaskDuration')?.value ?? 0
        }
        const t0 = await busy()
        await page.waitForTimeout(5000)
        const used = (await busy()) - t0
        note('idle task time over 5 s', `${(used * 1000).toFixed(0)} ms`)
        expect(used / 5).toBeLessThan(0.05)
    })

    test('with no IntersectionObserver the first rows still show and a focused row is read', async ({ page }) => {
        const errors: string[] = []
        page.on('pageerror', (e) => errors.push(String(e)))
        await page.addInitScript(() => {
            ;(window as unknown as { IntersectionObserver?: unknown }).IntersectionObserver = undefined
        })
        await page.goto('/')
        await expect(page.locator('.home-row .card').first()).toBeVisible({ timeout: 20_000 })
        await page.waitForTimeout(1500)
        const first = await chatReads(page)
        const target = page.locator('.home-row').nth(EAGER + 6)
        await target.scrollIntoViewIfNeeded()
        await page.waitForTimeout(600)
        // Scrolled to but not focused: not read.
        expect((await chatReads(page)).size).toBe(first.size)
        await target.locator('.row-link').focus()
        await expect.poll(async () => (await chatReads(page)).size, { timeout: 5000 }).toBeGreaterThan(first.size)
        expect(errors).toEqual([])
        expect(countCalls(await workerCalls(page), 'iterDialogs')).toBeLessThanOrEqual(2)
    })
})
